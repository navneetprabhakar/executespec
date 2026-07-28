package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubRepoService.ChangedFile;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * v2.5.5 (UX-4.2) — architecture version diff with source-file correlation.
 *
 * <p>For each repo that appears in both versions' {@code generated_from_shas} JSON maps, calls
 * GitHub's compare API and aggregates per-repo changed files (added / modified / removed /
 * renamed). It also returns the two authorized architecture document bodies so the client can
 * render the actual {@code architecture.md} line diff. Source-file correlation is secondary
 * context; it is not itself the history diff.
 *
 * <p>No new persistence: relies on SHAs already captured at generate-time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchitectureDiffService {

  private final ProjectArchitectureDocRepository docRepo;
  private final GitHubRepoService gitHub;
  private final AppUserRepository userRepo;
  // RR-1589 — current tenancy graph. The stored architecture document names the repos it was
  // generated from; it does not decide which repos this project may still read.
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository repoRepo;
  private final ObjectMapper objectMapper;

  public record RepoChanges(String repoFullName, List<ChangedFile> files) {}

  /**
   * RR-1589 — a repo named by the stored document that produced no file list, and why. {@code
   * NOT_LINKED} rows were never fetched at all.
   */
  public record RepoDiffIssue(String repoFullName, String status, String detail) {}

  /**
   * RR-1589 — {@code perRepo} keeps its existing meaning (repos with confirmed changed files).
   * {@code skipped} and {@code unverified} are additive: {@code totalFiles == 0} with a non-empty
   * {@code unverified} list means "we could not tell", not "nothing changed".
   */
  public record DiffReport(
      int fromVersion,
      int toVersion,
      String fromContent,
      String toContent,
      List<RepoChanges> perRepo,
      int totalFiles,
      List<RepoDiffIssue> skipped,
      List<RepoDiffIssue> unverified) {}

  public DiffReport diff(String projectId, int fromVersion, int toVersion, String callerUserId) {
    ProjectArchitectureDoc from =
        docRepo
            .findByProjectIdAndVersion(projectId, fromVersion)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "architecture v" + fromVersion + " not found"));
    ProjectArchitectureDoc to =
        docRepo
            .findByProjectIdAndVersion(projectId, toVersion)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "architecture v" + toVersion + " not found"));

    Map<String, String> fromShas = parseShas(from);
    Map<String, String> toShas = parseShas(to);

    String pat =
        callerUserId == null
            ? null
            : userRepo.findById(callerUserId).map(AppUser::getGithubPat).orElse(null);

    // RR-1589 — authority comes from the CURRENT ProjectRepoLink membership, not from repo names
    // parsed out of the persisted architecture document. A repo unlinked from this project after
    // the architecture version was generated must not be fetched with the caller's PAT just because
    // a historical document still names it.
    Set<String> linkedRepoNames = currentlyLinkedRepoNames(projectId);

    List<RepoChanges> perRepo = new ArrayList<>();
    List<RepoDiffIssue> skipped = new ArrayList<>();
    List<RepoDiffIssue> unverified = new ArrayList<>();
    int totalFiles = 0;
    for (Map.Entry<String, String> entry : toShas.entrySet()) {
      String repo = entry.getKey();
      String head = entry.getValue();
      String base = fromShas.get(repo);
      if (repo == null || !linkedRepoNames.contains(repo.toLowerCase(Locale.ROOT))) {
        skipped.add(
            new RepoDiffIssue(
                repo,
                "NOT_LINKED",
                "named by architecture v"
                    + toVersion
                    + " but no longer linked to this project — not fetched"));
        continue;
      }
      if (base == null || head == null) {
        skipped.add(
            new RepoDiffIssue(
                repo, "NO_BASELINE", "repo has no recorded sha in both architecture versions"));
        continue;
      }
      if (base.equals(head)) continue; // deterministically unchanged; no call needed
      GitHubRepoService.CompareResult compare = gitHub.compareCommits(repo, base, head, pat);
      if (!compare.successful()) {
        // RR-1656 — the compare could not be performed (non-200, network error, auth failure).
        // That is an unverified repo, NOT a repo with no source changes; totalFiles deliberately
        // excludes it and the caller can show the failure distinctly.
        unverified.add(
            new RepoDiffIssue(
                repo,
                "COMPARE_FAILED",
                "GitHub compare could not be fetched ("
                    + compare.errorCode()
                    + (compare.upstreamStatus() == null
                        ? ""
                        : ", status " + compare.upstreamStatus())
                    + ") — source changes for this repo are unknown"));
        continue;
      }
      List<ChangedFile> files = compare.files();
      if (files.isEmpty()) {
        // RR-1656 — now a VERIFIED no-change compare: GitHub answered, and it listed no files.
        continue;
      }
      perRepo.add(new RepoChanges(repo, files));
      totalFiles += files.size();
    }
    return new DiffReport(
        fromVersion,
        toVersion,
        from.getContent(),
        to.getContent(),
        perRepo,
        totalFiles,
        skipped,
        unverified);
  }

  /** RR-1589 — lowercase GitHub full names of the repos currently linked to {@code projectId}. */
  private Set<String> currentlyLinkedRepoNames(String projectId) {
    Set<String> names = new LinkedHashSet<>();
    for (ProjectRepoLink link : linkRepo.findByProjectId(projectId)) {
      ProjectRepo repo = repoRepo.findById(link.getRepoId()).orElse(null);
      if (repo == null || repo.getGithubRepoFullName() == null) continue;
      names.add(repo.getGithubRepoFullName().toLowerCase(Locale.ROOT));
    }
    return names;
  }

  private Map<String, String> parseShas(ProjectArchitectureDoc doc) {
    if (doc.getGeneratedFromShas() == null || doc.getGeneratedFromShas().isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(
          doc.getGeneratedFromShas(), new TypeReference<LinkedHashMap<String, String>>() {});
    } catch (Exception e) {
      log.warn("Failed to parse generated_from_shas for doc {}: {}", doc.getId(), e.getMessage());
      return Map.of();
    }
  }
}
