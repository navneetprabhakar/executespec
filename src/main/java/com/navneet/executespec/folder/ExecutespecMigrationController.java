package com.navneet.executespec.folder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E1.3 — admin-only one-shot helper for migrating projects whose linked repos pre-date E1.5 (the
 * bootstrap-on-link flow).
 *
 * <p>For each linked repo: if the row's {@code executespec_folder_status} is already terminal
 * (BOOTSTRAPPED) or in-flight (BOOTSTRAP_PR_OPEN), skip with that status. Otherwise call {@link
 * BootstrapService#bootstrap} synchronously (because the admin caller is paged on the result, not
 * just submitting a fire-and-forget) and return per-repo status: {@code BOOTSTRAPPED}, {@code
 * BOOTSTRAP_PR_OPEN}, or {@code BOOTSTRAP_FAILED}.
 *
 * <p>Idempotent: re-running on the same project returns {@code SKIPPED_ALREADY_BOOTSTRAPPED} for
 * repos that finished, and re-attempts only repos that previously failed.
 */
@RestController
@RequestMapping("/api/admin/projects")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ExecutespecMigrationController {

  private final ProjectRepository projectRepository;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final AppUserRepository userRepo;
  private final BootstrapService bootstrap;

  @PostMapping("/{projectId}/migrate-codemaps")
  public ResponseEntity<MigrateResponse> migrate(
      @PathVariable String projectId, Authentication auth) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "project not found: " + projectId));
    UUID actorUserId = parseUuidOrNull(auth == null ? null : auth.getName());
    // Admin's own PAT used for the GH calls so the request is auditable as
    // the admin's action; falls back to system PAT inside the writer.
    String adminPat =
        actorUserId == null
            ? null
            : userRepo.findById(actorUserId.toString()).map(u -> u.getGithubPat()).orElse(null);

    List<ProjectRepoLink> links = linkRepo.findByProjectId(projectId);
    List<RepoMigrationResult> results = new ArrayList<>();

    for (ProjectRepoLink link : links) {
      Optional<ProjectRepo> maybe = repoRepo.findById(link.getRepoId());
      if (maybe.isEmpty()) {
        results.add(
            new RepoMigrationResult(
                link.getRepoId().toString(),
                null,
                "REPO_NOT_FOUND",
                "ProjectRepo row missing for link " + link.getId()));
        continue;
      }
      ProjectRepo repo = maybe.get();
      ProjectRepo.FolderStatus pre = repo.folderStatusEnum();
      if (pre == ProjectRepo.FolderStatus.BOOTSTRAPPED) {
        results.add(
            new RepoMigrationResult(
                repo.getId().toString(),
                repo.getGithubRepoFullName(),
                "SKIPPED_ALREADY_BOOTSTRAPPED",
                null));
        continue;
      }
      if (pre == ProjectRepo.FolderStatus.BOOTSTRAP_PR_OPEN) {
        results.add(
            new RepoMigrationResult(
                repo.getId().toString(),
                repo.getGithubRepoFullName(),
                "SKIPPED_BOOTSTRAP_IN_PROGRESS",
                repo.getExecutespecBootstrapPrUrl()));
        continue;
      }

      // Synchronous bootstrap so the admin gets a real per-repo result.
      try {
        bootstrap.bootstrap(
            repo.getId(), UUID.fromString(projectId), project.getName(), actorUserId, adminPat);
        ProjectRepo refreshed = repoRepo.findById(repo.getId()).orElse(repo);
        results.add(
            new RepoMigrationResult(
                refreshed.getId().toString(),
                refreshed.getGithubRepoFullName(),
                refreshed.folderStatusEnum().name(),
                refreshed.getExecutespecBootstrapPrUrl()));
      } catch (Exception ex) {
        log.warn(
            "[ExecutespecMigrationController] bootstrap failed for {}: {}",
            repo.getGithubRepoFullName(),
            ex.getMessage());
        results.add(
            new RepoMigrationResult(
                repo.getId().toString(),
                repo.getGithubRepoFullName(),
                "BOOTSTRAP_FAILED",
                ex.getMessage()));
      }
    }

    return ResponseEntity.ok(new MigrateResponse(projectId, results));
  }

  private static UUID parseUuidOrNull(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public record RepoMigrationResult(
      String repoId, String githubRepoFullName, String status, String detail) {}

  public record MigrateResponse(String projectId, List<RepoMigrationResult> results) {}
}
