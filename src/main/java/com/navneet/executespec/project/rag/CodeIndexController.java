package com.navneet.executespec.project.rag;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Honest, tenant-guarded semantic-index coverage/readiness for the project Repos surface. */
@RestController
@RequestMapping("/api/projects/{projectId}/code-index")
@RequiredArgsConstructor
public class CodeIndexController {

  public record RepoIndexStatus(String repoName, CodeIndexingService.Readiness readiness) {}

  public record RefreshAccepted(
      String repoId,
      String status,
      String fallback,
      boolean relationshipGraphAvailable,
      String reason) {}

  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectRepoLinkRepository linkRepository;
  private final ProjectRepoRepository repoRepository;
  private final CodeIndexingService indexingService;

  @GetMapping
  public List<RepoIndexStatus> list(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return linkRepository.findByProjectId(projectId).stream()
        .map(link -> repoRepository.findById(link.getRepoId()).orElse(null))
        .filter(java.util.Objects::nonNull)
        .map(
            repo ->
                new RepoIndexStatus(
                    displayName(repo),
                    indexingService.readiness(projectId, repo, userDetails.getUser().getId())))
        .toList();
  }

  @GetMapping("/{repoId}")
  public RepoIndexStatus get(
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    ProjectRepo repo = requireLinked(projectId, repoId);
    return new RepoIndexStatus(
        displayName(repo),
        indexingService.readiness(projectId, repo, userDetails.getUser().getId()));
  }

  /**
   * Explicitly resume/rebuild a partial, stale, or failed index. This queues the same fenced async
   * path used by project chat; it never performs provider work on the HTTP thread.
   */
  @PostMapping("/{repoId}/refresh")
  public ResponseEntity<RefreshAccepted> refresh(
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    ProjectRepo repo = requireLinked(projectId, repoId);
    CodeIndexingService.Readiness readiness =
        indexingService.readiness(projectId, repo, userDetails.getUser().getId());
    if (readiness.status() == CodeIndexStateStore.Status.READY
        && readiness.semanticSearchAvailable()) {
      return ResponseEntity.accepted()
          .body(new RefreshAccepted(repoId.toString(), "READY", "RIPGREP", false, null));
    }
    if (readiness.status() == CodeIndexStateStore.Status.INDEXING) {
      return ResponseEntity.accepted()
          .body(
              new RefreshAccepted(
                  repoId.toString(), "QUEUED", "RIPGREP", false, readiness.reason()));
    }
    if (readiness.reason() != null && readiness.reason().startsWith("EMBEDDING_")) {
      return ResponseEntity.accepted()
          .body(
              new RefreshAccepted(
                  repoId.toString(), "UNAVAILABLE", "RIPGREP", false, readiness.reason()));
    }
    boolean bitbucket = "BITBUCKET".equalsIgnoreCase(repo.getScmProvider());
    indexingService.indexRepoExplicitAsync(
        projectId,
        repo,
        bitbucket ? userDetails.getUser().getBitbucketUsername() : null,
        bitbucket
            ? userDetails.getUser().getBitbucketAppPassword()
            : userDetails.getUser().getGithubPat(),
        userDetails.getUser().getId());
    return ResponseEntity.accepted()
        .body(new RefreshAccepted(repoId.toString(), "QUEUED", "RIPGREP", false, null));
  }

  private ProjectRepo requireLinked(String projectId, UUID repoId) {
    if (linkRepository.findByProjectIdAndRepoId(projectId, repoId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository is not linked");
    }
    return repoRepository
        .findById(repoId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found"));
  }

  private static String displayName(ProjectRepo repo) {
    if (repo.getSourceDisplayName() != null && !repo.getSourceDisplayName().isBlank()) {
      return repo.getSourceDisplayName();
    }
    if (repo.getGithubRepoFullName() != null && !repo.getGithubRepoFullName().isBlank()) {
      return repo.getGithubRepoFullName();
    }
    return repo.getId().toString();
  }
}
