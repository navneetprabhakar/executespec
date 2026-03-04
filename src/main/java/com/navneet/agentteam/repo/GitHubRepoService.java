package com.navneet.agentteam.repo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Manages GitHub repositories for pipeline runs.
 * Creates new repos and clones existing ones into the workspace.
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

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Creates a new GitHub repo using the system PAT (backward-compat overload).
     */
    public String createRepo(String goal, String nameOverride, String visibility) {
        return createRepo(goal, nameOverride, visibility, null, null);
    }

    /**
     * Creates a new GitHub repo.
     * First tries the org endpoint ({@code /orgs/{owner}/repos}); falls back to
     * the personal endpoint ({@code /user/repos}) if the owner is not an org.
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

        String name = nameOverride != null ? nameOverride : generateSlug(goal);
        boolean isPrivate = !"public".equals(visibility);
        String body = """
                {"name":"%s","private":%b,"auto_init":true}""".formatted(name, isPrivate);

        // Try org endpoint first; if owner is a personal account this returns 404/422
        String cloneUrl = tryCreate("https://api.github.com/orgs/" + effectiveOwner + "/repos",
                effectivePat, body, effectiveOwner, name);

        // Fall back to personal account endpoint
        if (cloneUrl == null) {
            cloneUrl = tryCreate("https://api.github.com/user/repos",
                    effectivePat, body, effectiveOwner, name);
        }

        return cloneUrl != null ? cloneUrl : "https://github.com/" + effectiveOwner + "/" + name + ".git";
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
     * Generates a URL-safe slug from a goal string.
     * "Add real-time collaborative editing" → "realtime-collaborative-editing"
     */
    public String generateSlug(String goal) {
        String slug = goal.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .trim()
                .replaceAll("\\s+", "-");
        // Strip common leading verbs that add no info
        slug = slug.replaceAll("^(add|build|create|implement|fix|update|make|write|design)-", "");
        // Truncate to 40 chars, clean trailing dash
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return slug.replaceAll("-+$", "");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Returns override if non-blank, otherwise falls back to the system default. */
    private static String resolve(String override, String systemDefault) {
        return (override != null && !override.isBlank()) ? override : systemDefault;
    }

    /** Attempts to create a repo via {@code endpoint}. Returns clone_url or null on failure. */
    private String tryCreate(String endpoint, String effectivePat, String body,
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
                    return url;
                }
            }

            // 404/422 on org endpoint = not an org — caller will fall back to /user/repos
            log.debug("[GitHubRepoService] {} returned status {} for {}/{}", endpoint, status, owner, name);
            return null;

        } catch (Exception e) {
            log.warn("[GitHubRepoService] Repo creation via {} failed: {}", endpoint, e.getMessage());
            return null;
        }
    }
}
