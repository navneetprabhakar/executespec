package com.navneet.executespec.codeintel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectCodeMap;
import com.navneet.executespec.codeintel.entity.RepoArchitectureDoc;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class ProjectArchitectureController {

  private final ProjectArchitectureService service;
  private final StalenessService stalenessService;
  private final com.navneet.executespec.project.workspace.ProjectWorkspaceService workspace;
  private final ArchitectureDiffService diffService;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  // 2026-05-16 — Browse-tab file-fetch endpoints below need the per-repo
  // entity (for the github slug + default branch) and the GitHub client
  // (for the actual list + read calls).
  private final com.navneet.executespec.project.repository.ProjectRepoRepository projectRepoRepo;
  private final com.navneet.executespec.project.repository.ProjectRepoLinkRepository
      projectRepoLinkRepo;
  private final com.navneet.executespec.auth.repository.AppUserRepository userRepo;
  private final com.navneet.executespec.repo.GitHubRepoService github;

  public record ArchDocDto(
      UUID id, Integer version, String content, String userAnnotations, Instant generatedAt) {
    static ArchDocDto from(ProjectArchitectureDoc d) {
      return new ArchDocDto(
          d.getId(), d.getVersion(), d.getContent(), d.getUserAnnotations(), d.getGeneratedAt());
    }

    /**
     * Lean variant for the versions LIST — omits the (potentially large) content + annotations
     * body. The Versions tab only renders version number + timestamp; full content is fetched on
     * demand via the latest/diff endpoints. Keeps the list payload flat as docs grow.
     */
    static ArchDocDto fromMeta(ProjectArchitectureDoc d) {
      return new ArchDocDto(d.getId(), d.getVersion(), null, null, d.getGeneratedAt());
    }
  }

  public record CodeMapDto(String content, Integer version, Instant generatedAt) {
    static CodeMapDto from(ProjectCodeMap c) {
      return new CodeMapDto(c.getContent(), c.getVersion(), c.getGeneratedAt());
    }
  }

  @PostMapping("/api/projects/{projectId}/architecture/generate")
  public ResponseEntity<ArchDocDto> generate(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails,
      @PathVariable String projectId) {
    // Audit 2026-05-12 review item B #13 — regenerate is a paid LLM call + workspace mutation.
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
    ProjectArchitectureDoc doc = service.generate(projectId, null, pat);
    stalenessService.invalidate(projectId);
    return ResponseEntity.status(HttpStatus.CREATED).body(ArchDocDto.from(doc));
  }

  /** Alias + preferred entry: regenerates codemaps + architecture + summary on disk. */
  @PostMapping("/api/projects/{projectId}/analyse")
  public ResponseEntity<ArchDocDto> analyse(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails,
      @PathVariable String projectId,
      @org.springframework.web.bind.annotation.RequestParam(value = "branch", required = false)
          String branch) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
    ProjectArchitectureDoc doc = service.generate(projectId, branch, pat);
    stalenessService.invalidate(projectId);
    return ResponseEntity.status(HttpStatus.CREATED).body(ArchDocDto.from(doc));
  }

  @GetMapping("/api/projects/{projectId}/summary")
  public ResponseEntity<Map<String, String>> summary(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String content = workspace.readFile(projectId, "summary.md").orElse("");
    return ResponseEntity.ok(Map.of("content", content));
  }

  @GetMapping("/api/projects/{projectId}/architecture/staleness")
  public ResponseEntity<StalenessService.StalenessReport> staleness(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
    return ResponseEntity.ok(stalenessService.report(projectId, pat));
  }

  @GetMapping("/api/projects/{projectId}/architecture")
  public ResponseEntity<ArchDocDto> latest(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return service
        .latest(projectId)
        .map(d -> ResponseEntity.ok(ArchDocDto.from(d)))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/api/projects/{projectId}/architecture/versions")
  public ResponseEntity<List<ArchDocDto>> versions(
      @PathVariable String projectId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    List<ArchDocDto> out = service.versions(projectId).stream().map(ArchDocDto::fromMeta).toList();
    return ResponseEntity.ok(out);
  }

  /**
   * v2.5.5 (UX-4.2) — source file changes between two architecture versions. Uses GitHub's compare
   * API against each repo's SHAs captured at generate-time. The text diff of {@code
   * architecture.md} remains client-side (LCS).
   */
  @GetMapping("/api/projects/{projectId}/architecture/diff")
  public ResponseEntity<ArchitectureDiffService.DiffReport> diff(
      @PathVariable String projectId,
      @RequestParam("from") int from,
      @RequestParam("to") int to,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    String userId = userDetails == null ? null : userDetails.getUser().getId();
    return ResponseEntity.ok(diffService.diff(projectId, from, to, userId));
  }

  @PutMapping("/api/projects/{projectId}/architecture/annotate")
  public ResponseEntity<ArchDocDto> annotate(
      @PathVariable String projectId,
      @RequestBody Map<String, String> body,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    // Audit 2026-05-12 review item B #13 — annotation mutates persisted state.
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    String annotation = body.getOrDefault("annotation", "");
    return ResponseEntity.ok(ArchDocDto.from(service.annotate(projectId, annotation)));
  }

  @GetMapping("/api/projects/{projectId}/repos/{repoId}/codemap")
  public ResponseEntity<CodeMapDto> codemap(
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return service
        .latestCodeMap(projectId, repoId)
        .map(c -> ResponseEntity.ok(CodeMapDto.from(c)))
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No codemap for repo — regenerate architecture first"));
  }

  /**
   * v2.5.12 — per-repo architecture doc (Step 2 of the 3-step pipeline). Returns 204 when the
   * project has been analysed but this repo was linked after the last Analyse run.
   */
  public record RepoArchDto(
      UUID id,
      Integer version,
      String content,
      String modelUsed,
      Integer inputTokens,
      Integer outputTokens,
      Instant generatedAt) {
    static RepoArchDto from(RepoArchitectureDoc d) {
      return new RepoArchDto(
          d.getId(),
          d.getVersion(),
          d.getContent(),
          d.getModelUsed(),
          d.getInputTokens(),
          d.getOutputTokens(),
          d.getGeneratedAt());
    }
  }

  @GetMapping("/api/projects/{projectId}/repos/{repoId}/architecture")
  public ResponseEntity<RepoArchDto> repoArchitecture(
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return service
        .latestRepoArch(projectId, repoId)
        .map(d -> ResponseEntity.ok(RepoArchDto.from(d)))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  /**
   * Task #82 — explicit per-repo architecture regen (Steps 1 + 2: codemap + per-repo arch doc),
   * split out of the {@code POST /api/repos/{repoId}/sync} path so Sync stays a cheap metadata
   * refresh and never holds a DB connection across the LLM calls. Mirrors {@link #generate}: a paid
   * LLM call + workspace mutation, so it's write-gated and runs synchronously (the caller
   * explicitly asked to regenerate); {@link ProjectArchitectureService#regenerateForRepo} is
   * deliberately not {@code @Transactional}. Invalidates the staleness cache so the dashboard badge
   * refreshes.
   */
  @PostMapping("/api/projects/{projectId}/repos/{repoId}/regenerate-architecture")
  public ResponseEntity<RepoArchDto> regenerateRepoArchitecture(
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails,
      @PathVariable String projectId,
      @PathVariable UUID repoId) {
    // Paid LLM call + workspace mutation — same write-gate as generate()/analyse().
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
    RepoArchitectureDoc doc = service.regenerateForRepo(projectId, repoId, pat);
    stalenessService.invalidate(projectId);
    return ResponseEntity.status(HttpStatus.CREATED).body(RepoArchDto.from(doc));
  }

  // 2026-05-16 — file-browse endpoints for Project → Code → Browse
  //
  // The Browse tab now shows actual file content, not codemap-derived
  // metadata. Two endpoints back it:
  //
  //   GET /api/projects/{projectId}/repos/{repoId}/files
  //     → list every path in the repo (via the GitHub Trees API). Returns
  //       the same data CodeMapGenerator already fetches; we surface it
  //       directly so the UI doesn't need to wait for a codemap regenerate
  //       to render the file tree.
  //
  //   GET /api/projects/{projectId}/repos/{repoId}/file?path=<rel>
  //     → return the raw text content of one file. Uses the caller's
  //       per-user PAT (preferred) or falls back to the system PAT,
  //       same resolution order as the planner / worker use elsewhere.
  //
  // Both go through ProjectAccessGuard so cross-org reads are blocked at
  // the same line every other project-scoped endpoint uses.

  public record RepoFileListDto(String repoFullName, String defaultBranch, List<String> files) {}

  @GetMapping("/api/projects/{projectId}/repos/{repoId}/files")
  public ResponseEntity<RepoFileListDto> listRepoFiles(
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    var repo = linkedRepo(projectId, repoId);
    String pat = projectRepoReadPat(projectId, repoId, userDetails);
    String slug = repo.getGithubRepoFullName();
    String branch = repo.getDefaultBranch();
    List<String> files;
    try {
      files = github.listRepoFiles("https://github.com/" + slug, branch, pat);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "GitHub list failed: " + e.getMessage());
    }
    return ResponseEntity.ok(new RepoFileListDto(slug, branch, files));
  }

  public record RepoFileDto(
      String repoFullName, String defaultBranch, String path, String content, int sizeBytes) {}

  @GetMapping("/api/projects/{projectId}/repos/{repoId}/file")
  public ResponseEntity<RepoFileDto> readRepoFile(
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @org.springframework.web.bind.annotation.RequestParam("path") String path,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    // Path-traversal guard — even though `path` ends up as a relative segment
    // in the GitHub contents URL (server-side dereferenced), normalise + reject
    // any `..` so a misguided caller can't probe sibling repos via clever paths.
    if (path == null || path.isBlank() || path.contains("..") || path.startsWith("/")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path");
    }
    var repo = linkedRepo(projectId, repoId);
    String pat = projectRepoReadPat(projectId, repoId, userDetails);
    String slug = repo.getGithubRepoFullName();
    String branch = repo.getDefaultBranch();
    String content = github.readRepoFile("https://github.com/" + slug, path, branch, pat);
    // Defensive size cap — repos with massive generated SQL dumps or vendored
    // bundles shouldn't drag the chat thread. 1 MB matches what the worker's
    // tool dispatcher already enforces.
    int bytes = content == null ? 0 : content.length();
    if (bytes > 1_000_000) {
      content =
          content.substring(0, 1_000_000) + "\n\n// … (truncated; file exceeds 1 MB display cap)";
    }
    return ResponseEntity.ok(new RepoFileDto(slug, branch, path, content, bytes));
  }

  private com.navneet.executespec.project.entity.ProjectRepo linkedRepo(
      String projectId, UUID repoId) {
    projectRepoLinkRepo
        .findByProjectIdAndRepoId(projectId, repoId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not linked"));
    return projectRepoRepo
        .findById(repoId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Repo not found"));
  }

  /**
   * Project members read host-project source through the actor who linked the repo. External
   * invitees often do not have direct GitHub access to the host repo, so using their PAT first
   * makes the project shell visible while the file tree silently comes back empty for private
   * repos.
   */
  private String projectRepoReadPat(
      String projectId,
      UUID repoId,
      com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    String linkPat =
        projectRepoLinkRepo
            .findByProjectIdAndRepoId(projectId, repoId)
            .flatMap(link -> userRepo.findById(link.getLinkedBy()))
            .map(com.navneet.executespec.auth.entity.AppUser::getGithubPat)
            .filter(pat -> pat != null && !pat.isBlank())
            .orElse(null);
    if (linkPat != null) return linkPat;
    return userDetails != null ? userDetails.getUser().getGithubPat() : null;
  }
}
