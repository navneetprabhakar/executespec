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
 */
@Service
@Slf4j
public class GitHubRepoService {

    @Value("${agent.github.pat:#{null}}")
    private String pat;

    @Value("${agent.github.default-owner:owner}")
    private String defaultOwner;

    /**
     * Creates a new GitHub repo. Auto-generates slug from goal if nameOverride is null.
     * Returns the HTTPS clone URL.
     */
    public String createRepo(String goal, String nameOverride, String visibility) {
        if (pat == null || pat.isBlank()) {
            log.warn("[GitHubRepoService] No GitHub PAT configured — skipping repo creation");
            return null;
        }

        String name = nameOverride != null ? nameOverride : generateSlug(goal);
        boolean isPrivate = !"public".equals(visibility);

        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = """
                    {"name":"%s","private":%b,"auto_init":true}""".formatted(name, isPrivate);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/repos"))
                    .header("Authorization", "Bearer " + pat)
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                // Parse clone_url from JSON response (simple extraction)
                String responseBody = response.body();
                int idx = responseBody.indexOf("\"clone_url\":");
                if (idx >= 0) {
                    int start = responseBody.indexOf("\"", idx + 12) + 1;
                    int end = responseBody.indexOf("\"", start);
                    return responseBody.substring(start, end);
                }
            }

            log.warn("[GitHubRepoService] Repo creation returned status {}", response.statusCode());
            return "https://github.com/" + defaultOwner + "/" + name + ".git";

        } catch (Exception e) {
            log.warn("[GitHubRepoService] Repo creation failed: {}", e.getMessage());
            return "https://github.com/" + defaultOwner + "/" + name + ".git";
        }
    }

    /**
     * Clones repo into workspacePath using PAT for auth.
     */
    public void cloneRepo(String repoUrl, String workspacePath) throws Exception {
        if (pat == null || pat.isBlank()) {
            log.info("[GitHubRepoService] No PAT configured — initializing empty git repo at {}", workspacePath);
            ProcessBuilder pb = new ProcessBuilder("git", "init", workspacePath);
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) throw new RuntimeException("git init failed with exit code " + exit);
            return;
        }

        String authUrl = repoUrl.replace("https://", "https://oauth2:" + pat + "@");
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
}
