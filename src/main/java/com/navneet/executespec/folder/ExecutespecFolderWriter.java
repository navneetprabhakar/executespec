package com.navneet.executespec.folder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.repo.GitHubRepoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * E1.4 — single chokepoint for every write to a customer repo's
 * {@code .executespec/} folder.
 *
 * <h2>Why this exists</h2>
 *
 * The enterprise-readiness plan (see {@code
 * docs/plans/pending-enterprise-readiness-execution-eval.md}) makes the
 * {@code .executespec/} folder in customer git the persistent surface for
 * every artefact ExecuteSpec generates: codemaps, contracts, ADRs,
 * standards, invariants. The "single architectural rule" of the plan is
 * that no UI in ExecuteSpec mutates persistent customer state without a
 * PR against {@code .executespec/}. This service enforces that rule on
 * the backend: the only callers of the GitHub Contents/Refs APIs that
 * write to paths under {@code .executespec/} go through here.
 *
 * <h2>Write strategy</h2>
 *
 * Driven per-{@link ProjectRepo} by {@link ProjectRepo#prModeEnum()}:
 * <ul>
 *   <li>{@link ProjectRepo.PrMode#PR} (default) — opens a setup branch +
 *       PR for every change. Right for the picky enterprise persona.</li>
 *   <li>{@link ProjectRepo.PrMode#DIRECT_PUSH} — opt-in bypass for trusted
 *       high-frequency writes (codemap regen post-merge) so customers who
 *       don't want bot-PR fatigue can reduce noise.</li>
 * </ul>
 *
 * <h2>Conflict-resolution policy (initial — refine in a follow-up commit)</h2>
 *
 * When a customer's manual edit to a {@code .executespec/} file collides
 * with an ExecuteSpec-opened refresh PR, GitHub natively handles the merge
 * (the PR enters a conflicted state visible in the GH UI). For v1, the
 * resolution path is: customer resolves the conflict in GitHub. We do NOT
 * auto-rebase or auto-merge. Drift detection (E2.8) reports the conflicted
 * state to the dashboard so the user knows about it.
 *
 * <h2>Scope deferred from this slice (logged in plan §13)</h2>
 *
 * <ul>
 *   <li>{@code writeToBranch} — only needed for chat-action batching (plan §7
 *       deferred to v1.1).</li>
 *   <li>Real-PR integration test — needs a sandbox GitHub repo + a PAT.
 *       Unit tests below cover the contract via mocked HTTP.</li>
 *   <li>ArchUnit caller-allowlist guard — needs a curated allowlist
 *       similar to {@code AsyncAllowlistTest}; design pass first.</li>
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

    @Value("${agent.github.pat:#{null}}")
    private String systemPat;

    public ExecutespecFolderWriter(ObjectMapper json, GitHubRepoService gh) {
        this(HttpClient.newHttpClient(), json, gh);
    }

    /** Test seam: inject a mock {@link HttpClient}. */
    ExecutespecFolderWriter(HttpClient http, ObjectMapper json, GitHubRepoService gh) {
        this.http = http;
        this.json = json;
        this.gh = gh;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Write a batch of files to {@code .executespec/}. PR-mode opens a setup
     * branch + PR; DIRECT_PUSH-mode commits directly to the default branch.
     *
     * @param repo            target repo (its {@link ProjectRepo#prModeEnum()} drives strategy)
     * @param projectSlug     when non-null, paths under {@code .executespec/config/}
     *                        get prefixed with {@code <projectSlug>/} (multi-project repo support)
     * @param files           map of path → content; every path must begin with {@link #FOLDER_ROOT}
     * @param prTitle         PR title (PR mode) or commit message (DIRECT_PUSH mode)
     * @param prDescription   PR body (PR mode) or ignored (DIRECT_PUSH mode)
     * @param actorPat        per-user PAT (caller's GitHub PAT) — falls back to system PAT
     * @return {@link PullRequestInfo} on success, empty on auth/network/permission failure
     */
    public Optional<PullRequestInfo> writeFiles(ProjectRepo repo,
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
            log.warn("[ExecutespecFolderWriter] no PAT available — cannot write to {}", repo.getGithubRepoFullName());
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
     * Read a single file from {@code .executespec/} on the repo's default
     * branch. Returns empty when the file does not exist or the call fails.
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
     * List paths under {@code .executespec/<pathPrefix>} on the repo's default
     * branch. Pass {@code ""} as {@code pathPrefix} to list everything under
     * {@code .executespec/}.
     *
     * <p>Backed by {@link GitHubRepoService#listRepoFiles}, so the underlying
     * pagination + tree-walk semantics match the rest of the codebase.
     */
    public List<String> listFiles(ProjectRepo repo, String pathPrefix, String actorPat) {
        if (repo == null) return List.of();
        String safePrefix = pathPrefix == null ? "" : pathPrefix;
        if (safePrefix.startsWith("/")) safePrefix = safePrefix.substring(1);
        String fullPrefix = FOLDER_ROOT + safePrefix;

        String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();
        List<String> all = gh.listRepoFiles(repoUrl, pat(actorPat));
        if (all == null) return List.of();
        return all.stream()
                .filter(p -> p.startsWith(fullPrefix))
                .collect(Collectors.toList());
    }

    /**
     * Compare a known content hash for a {@code .executespec/} file against the
     * current state on the default branch.
     *
     * <p>Returns true when the file is missing OR its current SHA-256 differs
     * from {@code knownContentHash}. Returns false only when the hashes match
     * exactly (no drift).
     */
    public boolean hasDrift(ProjectRepo repo, String pathInRepo, String knownContentHash, String actorPat) {
        return readFile(repo, pathInRepo, actorPat)
                .map(snap -> !snap.contentHash().equals(knownContentHash))
                .orElse(true); // file missing counts as drift
    }

    // ── Internal: PR flow ───────────────────────────────────────────────────

    private Optional<PullRequestInfo> prFlow(ProjectRepo repo,
                                             String defaultBranch,
                                             Map<String, String> files,
                                             String prTitle,
                                             String prDescription,
                                             String pat) {
        String repoFullName = repo.getGithubRepoFullName();
        Optional<String> baseSha = getRefSha(repoFullName, defaultBranch, pat);
        if (baseSha.isEmpty()) {
            log.warn("[ExecutespecFolderWriter] could not resolve {} default branch SHA for {}",
                    defaultBranch, repoFullName);
            return Optional.empty();
        }

        String branchName = synthesiseBranchName(prTitle);
        if (!createBranch(repoFullName, branchName, baseSha.get(), pat)) {
            log.warn("[ExecutespecFolderWriter] could not create branch {} on {}", branchName, repoFullName);
            return Optional.empty();
        }

        List<String> writtenPaths = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            // Read existing SHA (if file exists on default branch) so PUT becomes an update.
            String existingSha = getContents(repoFullName, defaultBranch, e.getKey(), pat)
                    .map(FileSnapshot::contentSha).orElse(null);
            if (!putContents(repoFullName, branchName, e.getKey(), e.getValue(),
                    "ExecuteSpec: " + prTitle, existingSha, pat)) {
                log.warn("[ExecutespecFolderWriter] put failed for {} on branch {}; aborting batch",
                        e.getKey(), branchName);
                return Optional.empty();
            }
            writtenPaths.add(e.getKey());
        }

        String prUrl = gh.createPullRequest(
                "https://github.com/" + repoFullName,
                branchName,
                defaultBranch,
                prTitle,
                prDescription == null ? "" : prDescription,
                pat);
        if (prUrl == null) {
            log.warn("[ExecutespecFolderWriter] branch {} pushed but PR open failed for {}",
                    branchName, repoFullName);
            return Optional.empty();
        }
        return Optional.of(new PullRequestInfo(prUrl, branchName, writtenPaths, true));
    }

    // ── Internal: DIRECT_PUSH flow ──────────────────────────────────────────

    private Optional<PullRequestInfo> directPush(ProjectRepo repo,
                                                 String defaultBranch,
                                                 Map<String, String> files,
                                                 String commitMessage,
                                                 String pat) {
        String repoFullName = repo.getGithubRepoFullName();
        List<String> writtenPaths = new ArrayList<>();
        for (Map.Entry<String, String> e : files.entrySet()) {
            String existingSha = getContents(repoFullName, defaultBranch, e.getKey(), pat)
                    .map(FileSnapshot::contentSha).orElse(null);
            if (!putContents(repoFullName, defaultBranch, e.getKey(), e.getValue(),
                    "ExecuteSpec: " + commitMessage, existingSha, pat)) {
                log.warn("[ExecutespecFolderWriter] DIRECT_PUSH put failed for {} on {}",
                        e.getKey(), repoFullName);
                return Optional.empty();
            }
            writtenPaths.add(e.getKey());
        }
        return Optional.of(new PullRequestInfo(null, defaultBranch, writtenPaths, false));
    }

    // ── Internal: GitHub HTTP primitives ────────────────────────────────────

    Optional<String> getRefSha(String repoFullName, String branch, String pat) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repoFullName
                            + "/git/refs/heads/" + URLEncoder.encode(branch, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + pat)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
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

    boolean createBranch(String repoFullName, String newBranch, String fromSha, String pat) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("ref", "refs/heads/" + newBranch);
            body.put("sha", fromSha);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/git/refs"))
                    .header("Authorization", "Bearer " + pat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            // 201 Created = success; 422 with "Reference already exists" = idempotent success
            return res.statusCode() == 201
                    || (res.statusCode() == 422 && res.body() != null && res.body().contains("already exists"));
        } catch (Exception ex) {
            log.debug("createBranch failed: {}", ex.getMessage());
            return false;
        }
    }

    boolean putContents(String repoFullName, String branch, String path, String content,
                        String message, String existingSha, String pat) {
        try {
            String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
            ObjectNode body = json.createObjectNode();
            body.put("message", message);
            body.put("content", encoded);
            body.put("branch", branch);
            if (existingSha != null) body.put("sha", existingSha);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/contents/"
                            + URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/")))
                    .header("Authorization", "Bearer " + pat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            // 200 OK = updated existing; 201 Created = created new
            return res.statusCode() == 200 || res.statusCode() == 201;
        } catch (Exception ex) {
            log.debug("putContents failed for {}: {}", path, ex.getMessage());
            return false;
        }
    }

    Optional<FileSnapshot> getContents(String repoFullName, String branch, String path, String pat) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/contents/"
                            + URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/")
                            + "?ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + pat)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
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

    // ── Internal: helpers ───────────────────────────────────────────────────

    /**
     * Apply project-namespacing to {@code .executespec/config/...} paths and
     * normalise the input map. Other paths pass through unchanged.
     */
    Map<String, String> prepareFiles(Map<String, String> files, String projectSlug) {
        if (projectSlug == null || projectSlug.isBlank()) return files;
        return files.entrySet().stream().collect(Collectors.toMap(
                e -> namespaceConfigPath(e.getKey(), projectSlug),
                Map.Entry::getValue));
    }

    static String namespaceConfigPath(String path, String projectSlug) {
        if (path == null || projectSlug == null || projectSlug.isBlank()) return path;
        if (!path.startsWith(CONFIG_PREFIX)) return path;
        String suffix = path.substring(CONFIG_PREFIX.length());
        // Already namespaced for THIS project? Leave it.
        if (suffix.startsWith(projectSlug + "/")) return path;
        return CONFIG_PREFIX + projectSlug + "/" + suffix;
    }

    private void requireFolderRoot(String path) {
        if (path == null || !path.startsWith(FOLDER_ROOT)) {
            throw new IllegalArgumentException(
                    "ExecutespecFolderWriter only writes paths under " + FOLDER_ROOT
                            + "; got: " + path);
        }
    }

    private String pat(String actorPat) {
        if (actorPat != null && !actorPat.isBlank()) return actorPat;
        if (systemPat != null && !systemPat.isBlank()) return systemPat;
        return null;
    }

    static String synthesiseBranchName(String prTitle) {
        String slug = (prTitle == null ? "update" : prTitle).toLowerCase()
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

    // ── Public records ──────────────────────────────────────────────────────

    /**
     * Result of a successful {@link #writeFiles} call.
     *
     * @param prUrl         GitHub PR URL ({@code null} when {@code openedNewPr=false})
     * @param branchName    name of the branch the files were committed to
     * @param filesWritten  paths actually written (after project-namespacing)
     * @param openedNewPr   true for PR-mode; false for DIRECT_PUSH-mode (no PR)
     */
    public record PullRequestInfo(String prUrl, String branchName,
                                  List<String> filesWritten, boolean openedNewPr) {}

    /**
     * Snapshot of a file as it currently exists on the repo's default branch.
     *
     * @param content      decoded UTF-8 file content
     * @param contentSha   GitHub blob SHA — opaque to us, used for subsequent
     *                     update PUT calls
     * @param contentHash  SHA-256 of the content, used by {@link #hasDrift}
     */
    public record FileSnapshot(String content, String contentSha, String contentHash) {}
}
