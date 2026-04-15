package com.navneet.executespec.repo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages GitHub repositories for pipeline runs.
 * Creates new repos (with random names and descriptions), clones existing ones,
 * and provides read-access to repo contents when local workspace is unavailable.
 *
 * <p>All methods accept optional {@code overridePat} / {@code overrideOwner} parameters so
 * per-user credentials take precedence over the system-level defaults from {@code application.yml}.
 */
@Service
@Slf4j
public class GitHubRepoService {

    @Value("${agent.github.pat:#{null}}")
    private String pat;

    @Value("${agent.github.default-owner:owner}")
    private String defaultOwner;

    private static final String[] ADJECTIVES = {
        "swift", "bright", "calm", "bold", "keen", "neat", "warm", "cool", "fair", "vast",
        "rapid", "crisp", "lunar", "solar", "prime", "agile", "vivid", "lucid", "sonic", "amber"
    };
    private static final String[] NOUNS = {
        "spark", "forge", "craft", "pulse", "drift", "bloom", "crest", "flame", "grove", "nexus",
        "vault", "atlas", "prism", "orbit", "scout", "slate", "ember", "cedar", "frost", "ridge"
    };

    /** Result from a single repo creation attempt. */
    private record CreateResult(String cloneUrl, boolean nameTaken) {}

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Creates a new GitHub repo using the system PAT (backward-compat overload).
     */
    public String createRepo(String goal, String nameOverride, String visibility) {
        return createRepo(goal, nameOverride, visibility, null, null);
    }

    /**
     * Creates a new GitHub repo with a random name and description derived from the goal.
     * If the generated name already exists on GitHub, appends a random 2-digit number and retries.
     *
     * @param overridePat   per-user PAT (takes priority over system PAT if non-blank)
     * @param overrideOwner per-user GitHub owner/org (takes priority over system default-owner)
     * @return HTTPS clone URL, or a best-effort constructed URL on failure
     */
    public String createRepo(String goal, String nameOverride, String visibility,
                             String overridePat, String overrideOwner) {
        String effectivePat   = resolve(overridePat,   pat);
        String effectiveOwner = resolve(overrideOwner, defaultOwner);

        if (effectivePat == null || effectivePat.isBlank()) {
            log.warn("[GitHubRepoService] No GitHub PAT configured — skipping repo creation");
            return null;
        }

        String baseName = nameOverride != null ? nameOverride : generateRepoName();
        boolean isPrivate = !"public".equals(visibility);
        String description = sanitiseDescription(goal);

        // Try up to 3 times: first with baseName, then with random 2-digit suffixes
        for (int attempt = 0; attempt < 3; attempt++) {
            String name = attempt == 0 ? baseName
                    : baseName + "-" + ThreadLocalRandom.current().nextInt(10, 100);

            String body = """
                    {"name":"%s","private":%b,"auto_init":true,"description":"%s"}"""
                    .formatted(name, isPrivate, description);

            // Try org endpoint first; if owner is a personal account this returns 404/422
            CreateResult orgResult = tryCreate(
                    "https://api.github.com/orgs/" + effectiveOwner + "/repos",
                    effectivePat, body, effectiveOwner, name);

            if (orgResult.cloneUrl() != null) return orgResult.cloneUrl();

            // Fall back to personal account endpoint
            CreateResult userResult = tryCreate(
                    "https://api.github.com/user/repos",
                    effectivePat, body, effectiveOwner, name);

            if (userResult.cloneUrl() != null) return userResult.cloneUrl();

            // If name was taken, retry with suffix
            if (userResult.nameTaken() || orgResult.nameTaken()) {
                log.info("[GitHubRepoService] Repo name '{}' already exists — retrying with suffix (attempt {})",
                        name, attempt + 1);
                continue;
            }

            // Some other failure (auth, network, etc.) — don't retry
            return "https://github.com/" + effectiveOwner + "/" + name + ".git";
        }

        // All retries exhausted
        String fallbackName = baseName + "-" + ThreadLocalRandom.current().nextInt(10, 100);
        return "https://github.com/" + effectiveOwner + "/" + fallbackName + ".git";
    }

    /**
     * Updates the description of an existing GitHub repo.
     *
     * @param repoUrl     HTTPS clone URL (e.g. https://github.com/owner/repo.git)
     * @param description new description text
     * @param overridePat per-user PAT (null falls back to system PAT)
     */
    public void updateRepoDescription(String repoUrl, String description, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return;

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return;

        String sanitised = sanitiseDescription(description);
        String body = """
                {"description":"%s"}""".formatted(sanitised);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("[GitHubRepoService] Updated description for {}/{}", ownerRepo[0], ownerRepo[1]);
            } else {
                log.warn("[GitHubRepoService] Failed to update description for {}/{}: {}",
                        ownerRepo[0], ownerRepo[1], response.statusCode());
            }
        } catch (Exception e) {
            log.warn("[GitHubRepoService] Error updating repo description: {}", e.getMessage());
        }
    }

    /**
     * Lists all file paths in a GitHub repo's main branch via the Git Trees API.
     *
     * @param repoUrl     HTTPS clone URL
     * @param overridePat per-user PAT (null falls back to system PAT)
     * @return sorted list of relative file paths, or empty list on failure
     */
    public List<String> listRepoFiles(String repoUrl, String overridePat) {
        return listRepoFiles(repoUrl, null, overridePat);
    }

    /** Variant that respects a non-default branch (avoids the hardcoded {@code main}). */
    public List<String> listRepoFiles(String repoUrl, String branch, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return List.of();

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return List.of();

        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/git/trees/" + effectiveBranch + "?recursive=1"))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[GitHubRepoService] Failed to list repo files for {}: {}", repoUrl, response.statusCode());
                return List.of();
            }

            // Parse the "tree" array: extract "path" where "type" is "blob"
            return parseTreePaths(response.body());
        } catch (Exception e) {
            log.warn("[GitHubRepoService] Error listing repo files: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Reads a single file from a GitHub repo via the Contents API.
     *
     * @param repoUrl      HTTPS clone URL
     * @param relativePath file path relative to repo root
     * @param overridePat  per-user PAT (null falls back to system PAT)
     * @return file content as string, or error message on failure
     */
    public String readRepoFile(String repoUrl, String relativePath, String overridePat) {
        return readRepoFile(repoUrl, relativePath, null, overridePat);
    }

    public String readRepoFile(String repoUrl, String relativePath, String branch, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return "// No GitHub PAT configured";

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return "// Invalid repo URL";

        String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/contents/" + relativePath + "?ref=" + effectiveBranch))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "// File not found on GitHub (status " + response.statusCode() + ")";
            }

            // Extract base64-encoded "content" field and decode
            return decodeGitHubContent(response.body());
        } catch (Exception e) {
            log.warn("[GitHubRepoService] Error reading repo file {}: {}", relativePath, e.getMessage());
            return "// Error reading file from GitHub: " + e.getMessage();
        }
    }

    /**
     * Clones repo into workspacePath using the system PAT (backward-compat overload).
     */
    public void cloneRepo(String repoUrl, String workspacePath) throws Exception {
        cloneRepo(repoUrl, workspacePath, null);
    }

    /**
     * Clones a GitHub repo into {@code workspacePath} using the provided PAT for auth.
     * Falls back to {@code git init} if no PAT is available.
     */
    public void cloneRepo(String repoUrl, String workspacePath, String overridePat) throws Exception {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) {
            log.info("[GitHubRepoService] No PAT configured — initializing empty git repo at {}", workspacePath);
            ProcessBuilder pb = new ProcessBuilder("git", "init", workspacePath);
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) throw new RuntimeException("git init failed with exit code " + exit);
            return;
        }

        String authUrl = repoUrl.replace("https://", "https://oauth2:" + effectivePat + "@");
        ProcessBuilder pb = new ProcessBuilder("git", "clone", authUrl, workspacePath);
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) throw new RuntimeException("git clone failed with exit code " + exit);
    }

    /**
     * Generates a random memorable repo name (e.g. "swift-spark", "bold-nexus").
     */
    public String generateRepoName() {
        var rng = ThreadLocalRandom.current();
        return ADJECTIVES[rng.nextInt(ADJECTIVES.length)] + "-" + NOUNS[rng.nextInt(NOUNS.length)];
    }

    /**
     * Generates a URL-safe slug from a goal string (kept for backward compatibility).
     * "Add real-time collaborative editing" -> "realtime-collaborative-editing"
     */
    public String generateSlug(String goal) {
        String slug = goal.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
        slug = slug.replaceAll("^(add|build|create|implement|fix|update|make|write|design)-", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return slug.replaceAll("-+$", "");
    }

    // ── Branch + PR ──────────────────────────────────────────────────────────────

    /**
     * Returns the default branch name (e.g. "main", "master") for a GitHub repo.
     *
     * @param repoUrl     HTTPS clone URL
     * @param overridePat per-user PAT (null falls back to system PAT)
     * @return default branch name, or "main" on failure
     */
    public String getDefaultBranch(String repoUrl, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return "main";

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return "main";

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                int idx = body.indexOf("\"default_branch\":");
                if (idx >= 0) {
                    int start = body.indexOf("\"", idx + 17) + 1;
                    int end = body.indexOf("\"", start);
                    return body.substring(start, end);
                }
            }
            log.warn("[GitHubRepoService] Failed to get default branch for {}: status {}",
                    repoUrl, response.statusCode());
        } catch (Exception e) {
            log.warn("[GitHubRepoService] Error getting default branch: {}", e.getMessage());
        }
        return "main";
    }

    /**
     * Pilot v3.0 — fetch the HEAD SHA of a given branch for drift detection.
     * Returns {@code null} on any failure (missing PAT, unauthorised, repo moved)
     * so callers can treat the repo as "unknown" without throwing.
     */
    public String getBranchHeadSha(String repoUrl, String branch, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return null;
        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return null;
        String effectiveBranch = (branch == null || branch.isBlank()) ? "main" : branch;

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/commits/" + effectiveBranch))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                int idx = body.indexOf("\"sha\":");
                if (idx >= 0) {
                    int start = body.indexOf("\"", idx + 6) + 1;
                    int end = body.indexOf("\"", start);
                    return body.substring(start, end);
                }
            }
            log.warn("[GitHubRepoService] getBranchHeadSha {} status {}", repoUrl, response.statusCode());
        } catch (Exception e) {
            log.warn("[GitHubRepoService] getBranchHeadSha error: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Creates a pull request on a GitHub repository.
     *
     * @param repoUrl     HTTPS clone URL
     * @param head        source branch name (e.g. "executespec/abc123")
     * @param base        target branch name (e.g. "main")
     * @param title       PR title
     * @param prBody      PR description body
     * @param overridePat per-user PAT (null falls back to system PAT)
     * @return PR HTML URL (e.g. "https://github.com/owner/repo/pull/1"), or null on failure
     */
    public String createPullRequest(String repoUrl, String head, String base,
                                     String title, String prBody, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) {
            log.warn("[GitHubRepoService] No PAT — cannot create PR");
            return null;
        }

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) {
            log.warn("[GitHubRepoService] Cannot parse owner/repo from {}", repoUrl);
            return null;
        }

        String safeTitle = sanitiseDescription(title);
        String safeBody = prBody != null
                ? prBody.replace("\"", "'").replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")
                : "";

        String requestBody = """
                {"title":"%s","head":"%s","base":"%s","body":"%s"}"""
                .formatted(safeTitle, head, base, safeBody);

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1] + "/pulls"))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                // Extract html_url from response
                String responseBody = response.body();
                int idx = responseBody.indexOf("\"html_url\":");
                if (idx >= 0) {
                    int start = responseBody.indexOf("\"", idx + 11) + 1;
                    int end = responseBody.indexOf("\"", start);
                    String prUrl = responseBody.substring(start, end);
                    log.info("[GitHubRepoService] Created PR: {}", prUrl);
                    return prUrl;
                }
            }
            log.warn("[GitHubRepoService] Failed to create PR for {}/{}: status {} — {}",
                    ownerRepo[0], ownerRepo[1], response.statusCode(), response.body());
        } catch (Exception e) {
            log.warn("[GitHubRepoService] Error creating PR: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Pilot v3.0 — patch a PR's body. Used for cross-linking PRs created by
     * the same multi-repo run. Best-effort: logs and continues on failure.
     */
    public void updatePullRequestBody(String repoUrl, int prNumber, String newBody, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return;
        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return;
        String safeBody = newBody == null ? ""
                : newBody.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        String body = "{\"body\":\"" + safeBody + "\"}";
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/pulls/" + prNumber))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("[GitHubRepoService] updatePullRequestBody PR#{} status {}",
                        prNumber, response.statusCode());
            }
        } catch (Exception e) {
            log.warn("[GitHubRepoService] updatePullRequestBody error: {}", e.getMessage());
        }
    }

    /**
     * Pilot R5 — request reviewers on an existing PR via
     * {@code POST /repos/{owner}/{repo}/pulls/{number}/requested_reviewers}.
     * Best-effort: logs on failure but never throws.
     */
    public void requestReviewers(String repoUrl, int prNumber, java.util.List<String> reviewers, String overridePat) {
        if (reviewers == null || reviewers.isEmpty()) return;
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return;
        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return;

        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < reviewers.size(); i++) {
            if (i > 0) arr.append(',');
            arr.append('"').append(reviewers.get(i).replace("\"", "")).append('"');
        }
        arr.append(']');
        String body = "{\"reviewers\":" + arr + "}";

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/pulls/" + prNumber + "/requested_reviewers"))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("[GitHubRepoService] requestReviewers PR#{} status {} — {}",
                        prNumber, response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("[GitHubRepoService] requestReviewers error: {}", e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Returns override if non-blank, otherwise falls back to the system default. */
    private static String resolve(String override, String systemDefault) {
        return (override != null && !override.isBlank()) ? override : systemDefault;
    }

    /** Sanitises a goal string for use as a GitHub repo description (max 200 chars, JSON-safe). */
    private static String sanitiseDescription(String goal) {
        if (goal == null || goal.isBlank()) return "";
        String desc = goal.replace("\"", "'").replace("\\", "").replace("\n", " ").replace("\r", "");
        return desc.length() > 200 ? desc.substring(0, 197) + "..." : desc;
    }

    /**
     * Extracts [owner, repo] from a clone URL like https://github.com/owner/repo.git
     * Returns null if the URL doesn't match the expected pattern.
     */
    public static String[] extractOwnerRepo(String repoUrl) {
        if (repoUrl == null) return null;
        // Strip .git suffix and protocol
        String path = repoUrl.replace("https://github.com/", "")
                .replace("http://github.com/", "")
                .replaceAll("\\.git$", "");
        String[] parts = path.split("/");
        if (parts.length >= 2) {
            return new String[]{parts[0], parts[1]};
        }
        return null;
    }

    /** Attempts to create a repo via {@code endpoint}. Returns result with clone_url or failure reason. */
    private CreateResult tryCreate(String endpoint, String effectivePat, String body,
                                   String owner, String name) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 201 || status == 200) {
                String responseBody = response.body();
                int idx = responseBody.indexOf("\"clone_url\":");
                if (idx >= 0) {
                    int start = responseBody.indexOf("\"", idx + 12) + 1;
                    int end = responseBody.indexOf("\"", start);
                    String url = responseBody.substring(start, end);
                    log.info("[GitHubRepoService] Created repo at {}", url);
                    return new CreateResult(url, false);
                }
            }

            // 422 with "name already exists" → name collision
            boolean nameTaken = status == 422 && response.body().contains("name already exists");
            log.debug("[GitHubRepoService] {} returned status {} for {}/{} (nameTaken={})",
                    endpoint, status, owner, name, nameTaken);
            return new CreateResult(null, nameTaken);

        } catch (Exception e) {
            log.warn("[GitHubRepoService] Repo creation via {} failed: {}", endpoint, e.getMessage());
            return new CreateResult(null, false);
        }
    }

    /**
     * Parses the Git Trees API response to extract file paths.
     * Expected format: {"tree": [{"path": "src/main.java", "type": "blob"}, ...]}
     */
    private List<String> parseTreePaths(String json) {
        List<String> paths = new ArrayList<>();
        // Simple JSON parsing — find each "path":"..." where preceding "type":"blob"
        int idx = 0;
        while (idx < json.length()) {
            int typeIdx = json.indexOf("\"type\":", idx);
            if (typeIdx < 0) break;

            int pathIdx = json.indexOf("\"path\":", typeIdx);
            if (pathIdx < 0) break;

            // Check if type is "blob"
            int typeValStart = json.indexOf("\"", typeIdx + 7) + 1;
            int typeValEnd = json.indexOf("\"", typeValStart);
            String typeVal = json.substring(typeValStart, typeValEnd);

            // Extract path value
            int pathValStart = json.indexOf("\"", pathIdx + 7) + 1;
            int pathValEnd = json.indexOf("\"", pathValStart);
            String pathVal = json.substring(pathValStart, pathValEnd);

            if ("blob".equals(typeVal) && !pathVal.startsWith(".git")) {
                paths.add(pathVal);
            }

            idx = pathValEnd + 1;
        }
        paths.sort(String::compareTo);
        return paths;
    }

    /** Lightweight DTO for {@link #listOwnerRepos(String, String)}. */
    public record RemoteRepo(String fullName, String defaultBranch, boolean isPrivate) {}

    /** Snapshot of a PR for dashboard rendering (title, body, stats, truncated diff). */
    public record PullRequestSnapshot(
            String title, String body, String state, boolean merged,
            int additions, int deletions, int changedFiles,
            String headBranch, String baseBranch,
            String diffPreview) {}

    /**
     * Fetches a PR + its diff (truncated) for inline dashboard rendering.
     * Returns {@code null} on any failure — caller should degrade to link-only.
     */
    public PullRequestSnapshot getPullRequestSnapshot(String repoFullName, int prNumber,
                                                       String overridePat, int diffMaxChars) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return null;
        if (repoFullName == null || repoFullName.isBlank()) return null;
        try {
            HttpClient client = HttpClient.newHttpClient();

            // PR metadata (JSON)
            HttpRequest metaReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github+json")
                    .GET().build();
            HttpResponse<String> metaResp = client.send(metaReq, HttpResponse.BodyHandlers.ofString());
            if (metaResp.statusCode() != 200) {
                log.warn("[GitHubRepoService] PR snapshot {} #{} meta status={}",
                        repoFullName, prNumber, metaResp.statusCode());
                return null;
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode meta = mapper.readTree(metaResp.body());

            // PR diff (text)
            HttpRequest diffReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber))
                    .header("Authorization", "Bearer " + effectivePat)
                    .header("Accept", "application/vnd.github.v3.diff")
                    .GET().build();
            HttpResponse<String> diffResp = client.send(diffReq, HttpResponse.BodyHandlers.ofString());
            String diff = diffResp.statusCode() == 200 ? diffResp.body() : "";
            String truncated = diff;
            if (diffMaxChars > 0 && diff.length() > diffMaxChars) {
                truncated = diff.substring(0, diffMaxChars)
                        + "\n\n… (truncated, " + (diff.length() - diffMaxChars) + " more chars)";
            }

            return new PullRequestSnapshot(
                    meta.path("title").asText(""),
                    meta.path("body").asText(""),
                    meta.path("state").asText("open"),
                    meta.path("merged").asBoolean(false),
                    meta.path("additions").asInt(0),
                    meta.path("deletions").asInt(0),
                    meta.path("changed_files").asInt(0),
                    meta.path("head").path("ref").asText(""),
                    meta.path("base").path("ref").asText(""),
                    truncated);
        } catch (Exception e) {
            log.warn("[GitHubRepoService] PR snapshot error {} #{}: {}", repoFullName, prNumber, e.getMessage());
            return null;
        }
    }

    /**
     * Lists repos visible to the supplied PAT. Pulls up to 200 results across two
     * pages of {@code /user/repos} (covers private + collaborator + org-member repos
     * the token can see). If {@code ownerFilter} is non-blank, results are filtered
     * to that owner only — useful when the PAT can see many orgs but the user only
     * wants to register repos from one of them.
     */
    public List<RemoteRepo> listOwnerRepos(String ownerFilter, String overridePat) {
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return List.of();

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<RemoteRepo> out = new ArrayList<>();
        try {
            HttpClient client = HttpClient.newHttpClient();
            for (int page = 1; page <= 2; page++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.github.com/user/repos"
                                + "?affiliation=owner,collaborator,organization_member"
                                + "&per_page=100&page=" + page))
                        .header("Authorization", "Bearer " + effectivePat)
                        .header("Accept", "application/vnd.github+json")
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("[GitHubRepoService] /user/repos page {} returned {}: {}",
                            page, resp.statusCode(), truncate(resp.body(), 200));
                    break;
                }
                com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(resp.body());
                if (!arr.isArray() || arr.isEmpty()) break;
                for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                    String full = node.path("full_name").asText(null);
                    if (full == null) continue;
                    if (ownerFilter != null && !ownerFilter.isBlank()
                            && !full.toLowerCase().startsWith(ownerFilter.toLowerCase() + "/")) {
                        continue;
                    }
                    out.add(new RemoteRepo(
                            full,
                            node.path("default_branch").asText("main"),
                            node.path("private").asBoolean(false)));
                }
                if (arr.size() < 100) break;   // last page
            }
        } catch (Exception e) {
            log.warn("[GitHubRepoService] listOwnerRepos failed: {}", e.getMessage());
        }
        out.sort((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()));
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * Decodes the base64 "content" field from a GitHub Contents API response.
     * The GitHub API returns content in base64 with newlines every 60 chars.
     */
    private String decodeGitHubContent(String json) {
        int contentIdx = json.indexOf("\"content\":");
        if (contentIdx < 0) return "// No content field in response";

        int start = json.indexOf("\"", contentIdx + 10) + 1;
        int end = json.indexOf("\"", start);
        String base64 = json.substring(start, end)
                .replace("\\n", "")
                .replace("\n", "")
                .trim();

        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            return new String(decoded);
        } catch (Exception e) {
            return "// Failed to decode file content: " + e.getMessage();
        }
    }
}
