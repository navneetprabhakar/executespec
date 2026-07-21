package com.navneet.executespec.folder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.security.SafeWorkspacePath;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * E1.4 — single chokepoint for every write to a customer repo's {@code .executespec/} folder.
 *
 * <h2>Why this exists</h2>
 *
 * The enterprise-readiness plan (see {@code
 * docs/plans/pending-enterprise-readiness-execution-eval.md}) makes the {@code .executespec/}
 * folder in customer git the persistent surface for every artefact ExecuteSpec generates: codemaps,
 * contracts, ADRs, standards, invariants. The "single architectural rule" of the plan is that no UI
 * in ExecuteSpec mutates persistent customer state without a PR against {@code .executespec/}. This
 * service enforces that rule on the backend: the only callers of the GitHub Contents/Refs APIs that
 * write to paths under {@code .executespec/} go through here.
 *
 * <h2>Write strategy</h2>
 *
 * Driven per-{@link ProjectRepo} by {@link ProjectRepo#prModeEnum()}:
 *
 * <ul>
 *   <li>{@link ProjectRepo.PrMode#PR} (default) — opens a setup branch + PR for every change. Right
 *       for the picky enterprise persona.
 *   <li>{@link ProjectRepo.PrMode#DIRECT_PUSH} — opt-in bypass for trusted high-frequency writes
 *       (codemap regen post-merge) so customers who don't want bot-PR fatigue can reduce noise.
 * </ul>
 *
 * <h2>Conflict-resolution policy (initial — refine in a follow-up commit)</h2>
 *
 * When a customer's manual edit to a {@code .executespec/} file collides with an ExecuteSpec-opened
 * refresh PR, GitHub natively handles the merge (the PR enters a conflicted state visible in the GH
 * UI). For v1, the resolution path is: customer resolves the conflict in GitHub. We do NOT
 * auto-rebase or auto-merge. Drift detection (E2.8) reports the conflicted state to the dashboard
 * so the user knows about it.
 *
 * <h2>Scope deferred from this slice (logged in plan §13)</h2>
 *
 * <ul>
 *   <li>{@code writeToBranch} — only needed for chat-action batching (plan §7 deferred to v1.1).
 *   <li>Real-PR integration test — needs a sandbox GitHub repo + a PAT. Unit tests below cover the
 *       contract via mocked HTTP.
 *   <li>ArchUnit caller-allowlist guard — needs a curated allowlist similar to {@code
 *       AsyncAllowlistTest}; design pass first.
 * </ul>
 */
@Service
@Slf4j
public class ExecutespecFolderWriter {

  /** Required prefix for any path written/read by this service. */
  public static final String FOLDER_ROOT = ".executespec/";

  /** Sub-path that gets project-namespaced when {@code projectSlug} is non-null. */
  private static final String CONFIG_PREFIX = ".executespec/config/";

  private final HttpClient http;
  private final ObjectMapper json;
  private final GitHubRepoService gh;

  @Autowired
  public ExecutespecFolderWriter(ObjectMapper json, GitHubRepoService gh) {
    this(HttpClient.newHttpClient(), json, gh);
  }

  /** Test seam: inject a mock {@link HttpClient}. */
  ExecutespecFolderWriter(HttpClient http, ObjectMapper json, GitHubRepoService gh) {
    this.http = http;
    this.json = json;
    this.gh = gh;
  }

  /**
   * Write a batch of files to {@code .executespec/}. PR-mode opens a setup branch + PR;
   * DIRECT_PUSH-mode commits directly to the default branch.
   *
   * @param repo target repo (its {@link ProjectRepo#prModeEnum()} drives strategy)
   * @param projectSlug when non-null, paths under {@code .executespec/config/} get prefixed with
   *     {@code <projectSlug>/} (multi-project repo support)
   * @param files map of path → content; every path must begin with {@link #FOLDER_ROOT}
   * @param prTitle PR title (PR mode) or commit message (DIRECT_PUSH mode)
   * @param prDescription PR body (PR mode) or ignored (DIRECT_PUSH mode)
   * @param actorPat per-user PAT (caller's GitHub PAT) — falls back to system PAT
   * @return {@link PullRequestInfo} on success, empty on auth/network/permission failure
   */
  public Optional<PullRequestInfo> writeFiles(
      ProjectRepo repo,
      String projectSlug,
      Map<String, String> files,
      String prTitle,
      String prDescription,
      String actorPat) {
    if (repo == null || files == null || files.isEmpty()) return Optional.empty();
    Map<String, String> prepared = prepareFiles(files, projectSlug);
    prepared.keySet().forEach(this::requireFolderRoot);

    String pat = pat(actorPat);
    if (pat == null) {
      log.warn(
          "[ExecutespecFolderWriter] no PAT available — cannot write to {}",
          repo.getGithubRepoFullName());
      return Optional.empty();
    }

    String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();
    String defaultBranch = gh.getDefaultBranch(repoUrl, pat);
    if (defaultBranch == null || defaultBranch.isBlank()) defaultBranch = "main";

    ProjectRepo.PrMode mode = repo.prModeEnum();
    if (mode == ProjectRepo.PrMode.DIRECT_PUSH) {
      return directPush(repo, defaultBranch, prepared, prTitle, pat);
    }
    return prFlow(repo, defaultBranch, prepared, prTitle, prDescription, pat);
  }

  /**
   * Read a single file from {@code .executespec/} on the repo's default branch. Returns empty when
   * the file does not exist or the call fails.
   */
  public Optional<FileSnapshot> readFile(ProjectRepo repo, String pathInRepo, String actorPat) {
    if (repo == null || pathInRepo == null) return Optional.empty();
    requireFolderRoot(pathInRepo);

    String pat = pat(actorPat);
    if (pat == null) return Optional.empty();

    String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();
    String branch = gh.getDefaultBranch(repoUrl, pat);
    if (branch == null || branch.isBlank()) branch = "main";

    return getContents(repo.getGithubRepoFullName(), branch, pathInRepo, pat);
  }

  /**
   * List paths under {@code .executespec/<pathPrefix>} on the repo's default branch. Pass {@code
   * ""} as {@code pathPrefix} to list everything under {@code .executespec/}.
   *
   * <p>Backed by {@link GitHubRepoService#listRepoFiles}, so the underlying pagination + tree-walk
   * semantics match the rest of the codebase.
   */
  public List<String> listFiles(ProjectRepo repo, String pathPrefix, String actorPat) {
    if (repo == null) return List.of();
    String safePrefix = pathPrefix == null ? "" : pathPrefix;
    if (safePrefix.startsWith("/")) safePrefix = safePrefix.substring(1);
    String fullPrefix = FOLDER_ROOT + safePrefix;

    String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();
    List<String> all = gh.listRepoFiles(repoUrl, pat(actorPat));
    if (all == null) return List.of();
    return all.stream().filter(p -> p.startsWith(fullPrefix)).collect(Collectors.toList());
  }

  /**
   * Compare a known content hash for a {@code .executespec/} file against the current state on the
   * default branch.
   *
   * <p>Returns true when the file is missing OR its current SHA-256 differs from {@code
   * knownContentHash}. Returns false only when the hashes match exactly (no drift).
   */
  public boolean hasDrift(
      ProjectRepo repo, String pathInRepo, String knownContentHash, String actorPat) {
    return readFile(repo, pathInRepo, actorPat)
        .map(snap -> !snap.contentHash().equals(knownContentHash))
        .orElse(true); // file missing counts as drift
  }

  /**
   * RR-536 — build one commit containing every validated file and move exactly one ref to it.
   *
   * <p>The previous implementation looped GitHub Contents-API PUTs, one commit per file. In
   * DIRECT_PUSH mode each PUT landed on the customer's default branch immediately, so a failure on
   * file 3 of 5 left files 1 and 2 permanently committed while the caller was told nothing was
   * written; the caller then retried and produced more commits. PR mode had the same shape plus an
   * orphaned branch. Governance artifacts read back internally inconsistent.
   *
   * <p>Blobs, trees and commits are content-addressed and invisible until a ref points at them, so
   * every step before the ref update is free of visible side effects — an abort there leaves the
   * repository byte-identical and the objects are garbage collected. The ref update itself is the
   * single atomic act:
   *
   * <ul>
   *   <li>DIRECT_PUSH updates the default branch with {@code force=false}. Because the new commit's
   *       parent is the base SHA we read, GitHub rejects the update if the branch has advanced —
   *       that is the compare-and-swap, and a lost race fails cleanly rather than clobbering.
   *   <li>PR mode creates a brand-new branch ref that already contains the whole commit, then opens
   *       the PR. If the PR call fails the branch is deleted so no orphan is left behind.
   * </ul>
   *
   * @return the commit SHA that the ref now points at, or empty when nothing was published
   */
  private Optional<String> buildCommit(
      String repoFullName, String baseSha, Map<String, String> files, String message, String pat) {
    Optional<String> baseTree = getCommitTreeSha(repoFullName, baseSha, pat);
    if (baseTree.isEmpty()) {
      log.warn(
          "[ExecutespecFolderWriter] could not read base commit {} on {}", baseSha, repoFullName);
      return Optional.empty();
    }
    Map<String, String> blobsByPath = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> e : files.entrySet()) {
      Optional<String> blob = createBlob(repoFullName, e.getValue(), pat);
      if (blob.isEmpty()) {
        // No ref has moved, so abandoning here leaves the repository untouched.
        log.warn(
            "[ExecutespecFolderWriter] blob creation failed for {} on {}; nothing published",
            e.getKey(),
            repoFullName);
        return Optional.empty();
      }
      blobsByPath.put(e.getKey(), blob.get());
    }
    Optional<String> tree = createTree(repoFullName, baseTree.get(), blobsByPath, pat);
    if (tree.isEmpty()) {
      log.warn(
          "[ExecutespecFolderWriter] tree creation failed on {}; nothing published", repoFullName);
      return Optional.empty();
    }
    return createCommit(repoFullName, "ExecuteSpec: " + message, tree.get(), baseSha, pat);
  }

  private Optional<PullRequestInfo> prFlow(
      ProjectRepo repo,
      String defaultBranch,
      Map<String, String> files,
      String prTitle,
      String prDescription,
      String pat) {
    String repoFullName = repo.getGithubRepoFullName();
    Optional<String> baseSha = getRefSha(repoFullName, defaultBranch, pat);
    if (baseSha.isEmpty()) {
      log.warn(
          "[ExecutespecFolderWriter] could not resolve {} default branch SHA for {}",
          defaultBranch,
          repoFullName);
      return Optional.empty();
    }

    // 2026-05-02 — pre-flight content compare against the default branch.
    // Empty-PR cycle fix: ProjectArchitectureService keys codemap idempotency
    // on the default-branch HEAD SHA. After a user merges any system PR, HEAD
    // advances → next analyse re-runs codemap generation → publishes a new
    // codemap.md to .executespec/ → opens a PR. If the source code itself
    // didn't change, the new codemap content is identical to what's already
    // on the default branch, so the PR has zero diff. Catching that here
    // keeps the PR list clean. The hash comparison uses the SAME SHA-256 the
    // hasDrift() probe uses, so the two stay in sync.
    Map<String, String> drifted = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> e : files.entrySet()) {
      String desiredHash = sha256(e.getValue());
      Optional<FileSnapshot> existing = getContents(repoFullName, defaultBranch, e.getKey(), pat);
      if (existing.isPresent() && existing.get().contentHash().equals(desiredHash)) {
        continue; // content unchanged — nothing to write for this path
      }
      drifted.put(e.getKey(), e.getValue());
    }
    if (drifted.isEmpty()) {
      log.info(
          "[ExecutespecFolderWriter] every requested file already up to date on {}/{} — "
              + "skipping branch + PR (would have been a no-op)",
          repoFullName,
          defaultBranch);
      return Optional.empty();
    }

    // One commit carrying every drifted file. Nothing is visible in the repository until the ref
    // below is created, so any failure up to that point publishes nothing at all.
    Optional<String> commitSha = buildCommit(repoFullName, baseSha.get(), drifted, prTitle, pat);
    if (commitSha.isEmpty()) return Optional.empty();

    String branchName = synthesiseBranchName(prTitle);
    if (!createBranchAt(repoFullName, branchName, commitSha.get(), pat)) {
      log.warn(
          "[ExecutespecFolderWriter] could not create branch {} on {}", branchName, repoFullName);
      return Optional.empty();
    }

    String prUrl =
        gh.createPullRequest(
            "https://github.com/" + repoFullName,
            branchName,
            defaultBranch,
            prTitle,
            prDescription == null ? "" : prDescription,
            pat);
    if (prUrl == null) {
      // Compensate: the branch exists but carries no PR, so it is an orphan by definition.
      // Deleting it keeps a retry from accumulating abandoned executespec/* branches.
      log.warn(
          "[ExecutespecFolderWriter] branch {} pushed but PR open failed for {} — deleting branch",
          branchName,
          repoFullName);
      if (!deleteRef(repoFullName, branchName, pat)) {
        log.error(
            "[ExecutespecFolderWriter] orphan branch {} on {} could not be deleted; it must be "
                + "cleaned up manually",
            branchName,
            repoFullName);
      }
      return Optional.empty();
    }
    return Optional.of(new PullRequestInfo(prUrl, branchName, List.copyOf(drifted.keySet()), true));
  }

  private Optional<PullRequestInfo> directPush(
      ProjectRepo repo,
      String defaultBranch,
      Map<String, String> files,
      String commitMessage,
      String pat) {
    String repoFullName = repo.getGithubRepoFullName();
    Optional<String> baseSha = getRefSha(repoFullName, defaultBranch, pat);
    if (baseSha.isEmpty()) {
      log.warn(
          "[ExecutespecFolderWriter] could not resolve {} SHA for {}; DIRECT_PUSH aborted",
          defaultBranch,
          repoFullName);
      return Optional.empty();
    }
    Optional<String> commitSha =
        buildCommit(repoFullName, baseSha.get(), files, commitMessage, pat);
    if (commitSha.isEmpty()) return Optional.empty();

    // Compare-and-swap on the default branch: force=false plus a parent of baseSha means GitHub
    // refuses the update if anyone else moved the branch since we read it.
    if (!fastForwardRef(repoFullName, defaultBranch, commitSha.get(), pat)) {
      log.warn(
          "[ExecutespecFolderWriter] DIRECT_PUSH ref update rejected on {}/{} — nothing published",
          repoFullName,
          defaultBranch);
      return Optional.empty();
    }
    return Optional.of(
        new PullRequestInfo(null, defaultBranch, List.copyOf(files.keySet()), false));
  }

  Optional<String> getRefSha(String repoFullName, String branch, String pat) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/git/refs/heads/"
                          + URLEncoder.encode(branch, StandardCharsets.UTF_8)))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) return Optional.empty();
      JsonNode tree = json.readTree(res.body());
      JsonNode obj = tree.path("object").path("sha");
      return obj.isMissingNode() ? Optional.empty() : Optional.of(obj.asText());
    } catch (Exception ex) {
      log.debug("getRefSha failed for {}@{}: {}", repoFullName, branch, ex.getMessage());
      return Optional.empty();
    }
  }

  /** Tree SHA of an existing commit; the base every new tree is layered on. */
  Optional<String> getCommitTreeSha(String repoFullName, String commitSha, String pat) {
    JsonNode body =
        sendJson(
            "GET",
            "https://api.github.com/repos/" + repoFullName + "/git/commits/" + commitSha,
            null,
            pat,
            200);
    if (body == null) return Optional.empty();
    JsonNode sha = body.path("tree").path("sha");
    return sha.isMissingNode() ? Optional.empty() : Optional.of(sha.asText());
  }

  /**
   * Create a dangling blob. Invisible to the repository until a tree and commit reference it, so a
   * batch that aborts after some blobs exist has published nothing.
   */
  Optional<String> createBlob(String repoFullName, String content, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put(
        "content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
    req.put("encoding", "base64");
    JsonNode body =
        sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/blobs", req, pat, 201);
    return sha(body);
  }

  /** One tree carrying every path in the batch, layered over the base commit's tree. */
  Optional<String> createTree(
      String repoFullName, String baseTreeSha, Map<String, String> blobsByPath, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("base_tree", baseTreeSha);
    var entries = req.putArray("tree");
    for (Map.Entry<String, String> e : blobsByPath.entrySet()) {
      ObjectNode entry = entries.addObject();
      entry.put("path", e.getKey());
      entry.put("mode", "100644");
      entry.put("type", "blob");
      entry.put("sha", e.getValue());
    }
    JsonNode body =
        sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/trees", req, pat, 201);
    return sha(body);
  }

  /** One commit for the whole batch, parented on the SHA the batch was planned against. */
  Optional<String> createCommit(
      String repoFullName, String message, String treeSha, String parentSha, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("message", message);
    req.put("tree", treeSha);
    req.putArray("parents").add(parentSha);
    JsonNode body =
        sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/commits", req, pat, 201);
    return sha(body);
  }

  /**
   * Create a new branch ref pointing straight at the batch commit.
   *
   * <p>Only 201 counts. The former implementation also treated a 422 "already exists" as success,
   * which would have opened a PR from a ref this call did not place — the branch could point
   * anywhere.
   */
  boolean createBranchAt(String repoFullName, String newBranch, String commitSha, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("ref", "refs/heads/" + newBranch);
    req.put("sha", commitSha);
    return sendJson(
            "POST", "https://api.github.com/repos/" + repoFullName + "/git/refs", req, pat, 201)
        != null;
  }

  /**
   * Move an existing branch to {@code commitSha} without forcing. GitHub rejects the update when
   * the commit is not a descendant of the current head, which is exactly the compare-and-swap this
   * needs: a concurrent writer wins and we publish nothing rather than overwriting them.
   */
  boolean fastForwardRef(String repoFullName, String branch, String commitSha, String pat) {
    ObjectNode req = json.createObjectNode();
    req.put("sha", commitSha);
    req.put("force", false);
    return sendJson(
            "PATCH",
            "https://api.github.com/repos/"
                + repoFullName
                + "/git/refs/heads/"
                + URLEncoder.encode(branch, StandardCharsets.UTF_8),
            req,
            pat,
            200)
        != null;
  }

  /** Compensation for an orphaned branch whose PR never opened. */
  boolean deleteRef(String repoFullName, String branch, String pat) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/git/refs/heads/"
                          + URLEncoder.encode(branch, StandardCharsets.UTF_8)))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json")
              .DELETE()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      return res.statusCode() == 204 || res.statusCode() == 404;
    } catch (Exception ex) {
      log.debug("deleteRef failed for {}@{}: {}", repoFullName, branch, ex.getMessage());
      return false;
    }
  }

  private static Optional<String> sha(JsonNode body) {
    if (body == null) return Optional.empty();
    JsonNode sha = body.path("sha");
    return sha.isMissingNode() ? Optional.empty() : Optional.of(sha.asText());
  }

  /** Single JSON transport for the Git Data calls. Returns null on any non-expected status. */
  private JsonNode sendJson(
      String method, String url, ObjectNode requestBody, String pat, int expectedStatus) {
    try {
      HttpRequest.BodyPublisher publisher =
          requestBody == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(requestBody.toString());
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json");
      if (requestBody != null) builder.header("Content-Type", "application/json");
      HttpResponse<String> res =
          http.send(
              builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != expectedStatus) {
        log.debug("{} {} returned {}", method, url, res.statusCode());
        return null;
      }
      return json.readTree(res.body());
    } catch (Exception ex) {
      log.debug("{} {} failed: {}", method, url, ex.getMessage());
      return null;
    }
  }

  Optional<FileSnapshot> getContents(String repoFullName, String branch, String path, String pat) {
    try {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/contents/"
                          + URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/")
                          + "?ref="
                          + URLEncoder.encode(branch, StandardCharsets.UTF_8)))
              .header("Authorization", "Bearer " + pat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) return Optional.empty();
      JsonNode node = json.readTree(res.body());
      String contentB64 = node.path("content").asText("").replaceAll("\\s+", "");
      String sha = node.path("sha").asText(null);
      if (contentB64.isEmpty() || sha == null) return Optional.empty();
      String content = new String(Base64.getDecoder().decode(contentB64), StandardCharsets.UTF_8);
      return Optional.of(new FileSnapshot(content, sha, sha256(content)));
    } catch (Exception ex) {
      log.debug("getContents failed for {}: {}", path, ex.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Apply project-namespacing to {@code .executespec/config/...} paths and normalise the input map.
   * Other paths pass through unchanged.
   */
  Map<String, String> prepareFiles(Map<String, String> files, String projectSlug) {
    if (projectSlug == null || projectSlug.isBlank()) return files;
    return files.entrySet().stream()
        .collect(
            Collectors.toMap(
                e -> namespaceConfigPath(e.getKey(), projectSlug), Map.Entry::getValue));
  }

  static String namespaceConfigPath(String path, String projectSlug) {
    if (path == null || projectSlug == null || projectSlug.isBlank()) return path;
    if (!path.startsWith(CONFIG_PREFIX)) return path;
    String suffix = path.substring(CONFIG_PREFIX.length());
    // Already namespaced for THIS project? Leave it.
    if (suffix.startsWith(projectSlug + "/")) return path;
    return CONFIG_PREFIX + projectSlug + "/" + suffix;
  }

  /**
   * RR-525 — a bare {@code startsWith(".executespec/")} is not containment: {@code
   * .executespec/contracts/produced/../../../.github/workflows/release.yml} passes it and lands
   * anywhere in the repository. Callers derive these names from customer/source-controlled strings
   * (contract names), so the writer must enforce canonical POSIX relative segments itself and
   * re-check containment after normalization, not trust its callers.
   */
  private void requireFolderRoot(String path) {
    if (path == null || !path.startsWith(FOLDER_ROOT)) {
      throw new IllegalArgumentException(
          "ExecutespecFolderWriter only writes paths under " + FOLDER_ROOT + "; got: " + path);
    }
    try {
      SafeWorkspacePath.requireSafeRelativePosixPath(path);
    } catch (SecurityException e) {
      throw new IllegalArgumentException(
          "ExecutespecFolderWriter rejected an unsafe path: " + e.getMessage());
    }
    // Normalize defensively: even with dot segments rejected above, prove the canonical form is
    // still beneath the folder root before any SCM write.
    String normalized = java.nio.file.Path.of(path).normalize().toString().replace('\\', '/');
    if (!normalized.startsWith(FOLDER_ROOT)) {
      throw new IllegalArgumentException(
          "ExecutespecFolderWriter path escapes " + FOLDER_ROOT + " after normalization");
    }
  }

  private String pat(String actorPat) {
    return (actorPat != null && !actorPat.isBlank()) ? actorPat : null;
  }

  static String synthesiseBranchName(String prTitle) {
    String slug =
        (prTitle == null ? "update" : prTitle)
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("-+$", "");
    return "executespec/" + slug + "-" + Long.toString(System.currentTimeMillis(), 36);
  }

  static String sha256(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) sb.append(String.format("%02x", b));
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 not available", ex);
    }
  }

  /**
   * Result of a successful {@link #writeFiles} call.
   *
   * @param prUrl GitHub PR URL ({@code null} when {@code openedNewPr=false})
   * @param branchName name of the branch the files were committed to
   * @param filesWritten paths actually written (after project-namespacing)
   * @param openedNewPr true for PR-mode; false for DIRECT_PUSH-mode (no PR)
   */
  public record PullRequestInfo(
      String prUrl, String branchName, List<String> filesWritten, boolean openedNewPr) {}

  /**
   * Snapshot of a file as it currently exists on the repo's default branch.
   *
   * @param content decoded UTF-8 file content
   * @param contentSha GitHub blob SHA — opaque to us, used for subsequent update PUT calls
   * @param contentHash SHA-256 of the content, used by {@link #hasDrift}
   */
  public record FileSnapshot(String content, String contentSha, String contentHash) {}
}
