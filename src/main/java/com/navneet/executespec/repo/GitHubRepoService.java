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
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return List.of();

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return List.of();

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/git/trees/main?recursive=1"))
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
        String effectivePat = resolve(overridePat, pat);
        if (effectivePat == null || effectivePat.isBlank()) return "// No GitHub PAT configured";

        String[] ownerRepo = extractOwnerRepo(repoUrl);
        if (ownerRepo == null) return "// Invalid repo URL";

        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]
                            + "/contents/" + relativePath + "?ref=main"))
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
