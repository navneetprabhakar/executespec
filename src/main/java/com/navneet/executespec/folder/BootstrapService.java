package com.navneet.executespec.folder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * E1.5 — opens the initial {@code .executespec/} setup PR for a repo when it is linked to an
 * ExecuteSpec project.
 *
 * <h2>Flow</h2>
 *
 * <ol>
 *   <li><b>PAT pre-flight</b> — added in this slice (vs. source plan, which discovers scope gaps
 *       only when the PR fails). We hit GitHub's {@code GET /user} once and read the {@code
 *       X-OAuth-Scopes} response header. Insufficient scopes ({@code repo} required) → mark {@code
 *       BOOTSTRAP_FAILED} + emit failure event + stop. Converts the hostile "your first PR couldn't
 *       be opened" experience into an actionable surface at link time.
 *   <li><b>Idempotency</b> — if {@code .executespec/README.md} is already on the default branch
 *       (customer set it up manually before linking), we skip the PR and mark {@code BOOTSTRAPPED}
 *       directly.
 *   <li><b>Compose seed files</b> — generate the README via {@link FolderReadmeGenerator} and a
 *       {@code .gitkeep} placeholder for the project's {@code config/<slug>/} sub-folder.
 *   <li><b>Open the PR</b> via {@link ExecutespecFolderWriter}, mark {@code BOOTSTRAP_PR_OPEN} +
 *       persist the PR URL on {@link ProjectRepo}, and emit {@code EXECUTESPEC_BOOTSTRAP_INITIATED}
 *       on the activity feed.
 *   <li>On failure at the writer level → mark {@code BOOTSTRAP_FAILED} + emit failure event.
 * </ol>
 *
 * <p>All work runs on the {@code @Async} executor — a slow GitHub round-trip must never block the
 * {@code linkToProject} controller response.
 *
 * <h2>Required PAT scopes</h2>
 *
 * The bootstrap PR needs {@code repo} (write access). Read-only scopes cause {@link
 * #checkPatScopes(String)} to reject before we even try to open the PR.
 */
@Service
@Slf4j
public class BootstrapService {

  private static final Set<String> REQUIRED_SCOPES = Set.of("repo");

  private final ExecutespecFolderWriter writer;
  private final FolderReadmeGenerator readmeGenerator;
  private final ProjectRepoRepository repoRepo;
  private final ActivityEventEmitter activityEmitter;
  private final HttpClient http;

  @Value("${agent.github.pat:#{null}}")
  private String systemPat;

  @Autowired
  public BootstrapService(
      ExecutespecFolderWriter writer,
      FolderReadmeGenerator readmeGenerator,
      ProjectRepoRepository repoRepo,
      ActivityEventEmitter activityEmitter) {
    this(writer, readmeGenerator, repoRepo, activityEmitter, HttpClient.newHttpClient());
  }

  /** Test seam: inject a mock {@link HttpClient}. */
  BootstrapService(
      ExecutespecFolderWriter writer,
      FolderReadmeGenerator readmeGenerator,
      ProjectRepoRepository repoRepo,
      ActivityEventEmitter activityEmitter,
      HttpClient http) {
    this.writer = writer;
    this.readmeGenerator = readmeGenerator;
    this.repoRepo = repoRepo;
    this.activityEmitter = activityEmitter;
    this.http = http;
  }

  /**
   * Async entry point — call from {@code ProjectRepoService.linkToProject} after the link row is
   * persisted.
   */
  @Async
  public void bootstrapAsync(
      UUID repoId, UUID projectId, String projectName, UUID actorUserId, String actorPat) {
    try {
      bootstrap(repoId, projectId, projectName, actorUserId, actorPat);
    } catch (Exception ex) {
      // Belt-and-suspenders — internal helpers already swallow, but
      // never let an async thread crash silently.
      log.warn(
          "[BootstrapService] async bootstrap crashed for repo {} project {}: {}",
          repoId,
          projectId,
          ex.getMessage(),
          ex);
    }
  }

  /** Package-private for direct unit-testing without async indirection. */
  void bootstrap(
      UUID repoId, UUID projectId, String projectName, UUID actorUserId, String actorPat) {
    Optional<ProjectRepo> maybe = repoRepo.findById(repoId);
    if (maybe.isEmpty()) {
      log.warn("[BootstrapService] repo {} not found; aborting bootstrap", repoId);
      return;
    }
    ProjectRepo repo = maybe.get();
    String pat = effectivePat(actorPat);
    if (pat == null) {
      markFailed(repo, "no PAT available (caller has no GitHub PAT and no system fallback)");
      emitFailed(projectId, repo, actorUserId, "no PAT available");
      return;
    }

    // 1. PAT pre-flight — fail fast with an actionable message on scope gap.
    ScopeCheckResult scopeResult = checkPatScopes(pat);
    if (!scopeResult.ok) {
      String msg =
          "PAT missing required scope " + REQUIRED_SCOPES + "; got " + scopeResult.observed;
      markFailed(repo, msg);
      emitFailed(projectId, repo, actorUserId, msg);
      return;
    }

    // 2. Idempotency — already bootstrapped manually? Mark + return.
    Optional<ExecutespecFolderWriter.FileSnapshot> existingReadme =
        writer.readFile(repo, ".executespec/README.md", pat);
    if (existingReadme.isPresent()) {
      log.info(
          "[BootstrapService] repo {} already has .executespec/README.md; marking BOOTSTRAPPED",
          repo.getGithubRepoFullName());
      repo.setExecutespecFolderStatus(ProjectRepo.FolderStatus.BOOTSTRAPPED.name());
      repo.setExecutespecFolderSha(existingReadme.get().contentSha());
      repo.setExecutespecLastSyncedAt(Instant.now());
      repo.setUpdatedAt(Instant.now());
      repoRepo.save(repo);
      // Quiet success — don't fire INITIATED for a no-op bootstrap, but
      // do fire COMPLETED so consumers see the terminal state once.
      activityEmitter.emit(
          projectId,
          ActivityEventType.EXECUTESPEC_BOOTSTRAP_COMPLETED,
          actorUserId,
          "REPO",
          repoId.toString(),
          "Folder already present on " + repo.getGithubRepoFullName(),
          Map.of("repoFullName", repo.getGithubRepoFullName(), "noOp", true));
      return;
    }

    // 3. Compose seed files.
    String slug = slugify(projectName);
    String readmeBody = readmeGenerator.render(repo, slug);
    Map<String, String> seedFiles = new LinkedHashMap<>();
    seedFiles.put(".executespec/README.md", readmeBody);
    seedFiles.put(".executespec/config/" + slug + "/.gitkeep", "");

    // 4. Open the PR.
    Optional<ExecutespecFolderWriter.PullRequestInfo> pr =
        writer.writeFiles(
            repo,
            slug,
            seedFiles,
            "ExecuteSpec setup: contract index and codemap for " + repo.getGithubRepoFullName(),
            "Initial `.executespec/` folder seeded by ExecuteSpec at "
                + "link time. See `.executespec/README.md` for what each "
                + "subfolder is for and which paths are regenerable vs "
                + "review-worthy.\n\n"
                + "After merging, ExecuteSpec will start writing the "
                + "per-repo codemap (E1.2) and any contract auto-detections "
                + "to this folder via additional PRs.",
            pat);
    if (pr.isEmpty()) {
      String msg =
          "ExecutespecFolderWriter.writeFiles returned empty (PAT scope OK; "
              + "see service logs for the underlying GH HTTP error)";
      markFailed(repo, msg);
      emitFailed(projectId, repo, actorUserId, msg);
      return;
    }

    // 5. Persist success state + emit initiated event.
    repo.setExecutespecFolderStatus(ProjectRepo.FolderStatus.BOOTSTRAP_PR_OPEN.name());
    repo.setExecutespecBootstrapPrUrl(pr.get().prUrl());
    repo.setExecutespecLastSyncedAt(Instant.now());
    repo.setUpdatedAt(Instant.now());
    repoRepo.save(repo);
    activityEmitter.emit(
        projectId,
        ActivityEventType.EXECUTESPEC_BOOTSTRAP_INITIATED,
        actorUserId,
        "REPO",
        repoId.toString(),
        "Opened bootstrap PR " + pr.get().prUrl(),
        Map.of(
            "repoFullName",
            repo.getGithubRepoFullName(),
            "branchName",
            pr.get().branchName(),
            "filesWritten",
            pr.get().filesWritten()));
  }

  // ── PAT scope pre-flight ────────────────────────────────────────────────

  /**
   * GitHub returns the granted scopes for a token in the {@code X-OAuth-Scopes} header on any
   * authenticated request. We do one cheap call to {@code /user} and parse it. Fine-grained tokens
   * won't include this header — we treat that as success and let the actual PR call surface any
   * permission gap (better than rejecting valid tokens).
   */
  ScopeCheckResult checkPatScopes(String pat) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.github.com/user"))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 401 || res.statusCode() == 403) {
        return new ScopeCheckResult(false, List.of("(GitHub rejected the PAT)"));
      }
      // Header may have any case; HttpHeaders is case-insensitive.
      Optional<String> scopesHeader = res.headers().firstValue("X-OAuth-Scopes");
      if (scopesHeader.isEmpty()) {
        // Fine-grained token: no classic scope header. Defer judgement
        // to the actual PR call — accepting here avoids false positives.
        return new ScopeCheckResult(true, List.of("(fine-grained token)"));
      }
      List<String> observed = List.of(scopesHeader.get().split("\\s*,\\s*"));
      boolean ok =
          observed.stream().anyMatch(s -> REQUIRED_SCOPES.contains(s) || s.startsWith("repo"));
      return new ScopeCheckResult(ok, observed);
    } catch (Exception ex) {
      log.debug("[BootstrapService] PAT scope check failed: {}", ex.getMessage());
      // Network blip — don't block; let the writer call surface real failures.
      return new ScopeCheckResult(
          true, List.of("(scope check failed: " + ex.getClass().getSimpleName() + ")"));
    }
  }

  record ScopeCheckResult(boolean ok, List<String> observed) {}

  // ── Internal helpers ────────────────────────────────────────────────────

  private void markFailed(ProjectRepo repo, String reason) {
    repo.setExecutespecFolderStatus(ProjectRepo.FolderStatus.BOOTSTRAP_FAILED.name());
    repo.setExecutespecLastSyncedAt(Instant.now());
    repo.setUpdatedAt(Instant.now());
    repoRepo.save(repo);
    log.warn(
        "[BootstrapService] BOOTSTRAP_FAILED for {}: {}", repo.getGithubRepoFullName(), reason);
  }

  private void emitFailed(UUID projectId, ProjectRepo repo, UUID actorUserId, String reason) {
    activityEmitter.emit(
        projectId,
        ActivityEventType.EXECUTESPEC_BOOTSTRAP_FAILED,
        actorUserId,
        "REPO",
        repo.getId().toString(),
        "Bootstrap failed for " + repo.getGithubRepoFullName() + ": " + reason,
        Map.of("repoFullName", repo.getGithubRepoFullName(), "reason", reason));
  }

  private String effectivePat(String actorPat) {
    if (actorPat != null && !actorPat.isBlank()) return actorPat;
    if (systemPat != null && !systemPat.isBlank()) return systemPat;
    return null;
  }

  public static String slugify(String projectName) {
    if (projectName == null || projectName.isBlank()) return "default";
    String slug = projectName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    return slug.isBlank() ? "default" : slug;
  }
}
