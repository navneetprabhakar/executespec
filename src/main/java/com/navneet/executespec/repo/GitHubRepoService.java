package com.navneet.executespec.repo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Manages GitHub repositories for pipeline runs. Creates new repos (with random names and
 * descriptions), clones existing ones, and provides read-access to repo contents when local
 * workspace is unavailable.
 *
 * <p>All methods accept optional {@code overridePat} / {@code overrideOwner} parameters so per-user
 * credentials take precedence over the system-level defaults from {@code application.yml}.
 */
@Service
@Slf4j
public class GitHubRepoService {

  @Value("${agent.github.default-owner:owner}")
  private String defaultOwner;

  /**
   * Callback URL that GitHub will POST to — should match the handler route in {@code
   * GitHubWebhookController}. Optional: null disables auto-provisioning.
   */
  @Value("${github.webhook.callback-url:}")
  private String webhookCallbackUrl;

  /**
   * HMAC shared secret for webhook signature verification (same value the handler reads via {@code
   * github.webhook.secret}).
   */
  @Value("${github.webhook.secret:}")
  private String webhookSecret;

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

  /** Creates a new GitHub repo using the system PAT (backward-compat overload). */
  public String createRepo(String goal, String nameOverride, String visibility) {
    return createRepo(goal, nameOverride, visibility, null, null);
  }

  /**
   * Creates a new GitHub repo with a random name and description derived from the goal. If the
   * generated name already exists on GitHub, appends a random 2-digit number and retries.
   *
   * @param overridePat per-user PAT (takes priority over system PAT if non-blank)
   * @param overrideOwner per-user GitHub owner/org (takes priority over system default-owner)
   * @return HTTPS clone URL, or a best-effort constructed URL on failure
   */
  public String createRepo(
      String goal,
      String nameOverride,
      String visibility,
      String overridePat,
      String overrideOwner) {
    String effectivePat = overridePat;
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
      String name =
          attempt == 0 ? baseName : baseName + "-" + ThreadLocalRandom.current().nextInt(10, 100);

      String body =
          """
          {"name":"%s","private":%b,"auto_init":true,"description":"%s"}"""
              .formatted(name, isPrivate, description);

      // Try org endpoint first; if owner is a personal account this returns 404/422
      CreateResult orgResult =
          tryCreate(
              "https://api.github.com/orgs/" + effectiveOwner + "/repos",
              effectivePat,
              body,
              effectiveOwner,
              name);

      if (orgResult.cloneUrl() != null) return orgResult.cloneUrl();

      // Fall back to personal account endpoint
      CreateResult userResult =
          tryCreate("https://api.github.com/user/repos", effectivePat, body, effectiveOwner, name);

      if (userResult.cloneUrl() != null) return userResult.cloneUrl();

      // If name was taken, retry with suffix
      if (userResult.nameTaken() || orgResult.nameTaken()) {
        log.info(
            "[GitHubRepoService] Repo name '{}' already exists — retrying with suffix (attempt {})",
            name,
            attempt + 1);
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
   * v2.5.2 (Phase 2.3) — close a PR via PATCH. Idempotent: a PR already closed/merged returns
   * {@code true}. Best-effort on network/auth failure.
   *
   * @param repoFullName e.g. "owner/repo"
   * @param prNumber pull-request number (not the API URL id)
   * @param overridePat caller's PAT (must have repo:write)
   * @return true on 200 or 422 (already closed); false otherwise
   */
  public boolean closePullRequest(String repoFullName, int prNumber, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return false;
    if (repoFullName == null || !repoFullName.contains("/")) return false;

    String body = "{\"state\":\"closed\"}";
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create("https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .header("Content-Type", "application/json")
              .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 200) {
        log.info("[GitHubRepoService] Closed PR {}#{}", repoFullName, prNumber);
        return true;
      }
      // 422 = state transition rejected (already closed/merged) — treat as success.
      if (res.statusCode() == 422) {
        log.info("[GitHubRepoService] PR {}#{} already in terminal state", repoFullName, prNumber);
        return true;
      }
      log.warn(
          "[GitHubRepoService] closePullRequest {}#{} returned {}: {}",
          repoFullName,
          prNumber,
          res.statusCode(),
          res.body());
      return false;
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] closePullRequest {}#{} failed: {}",
          repoFullName,
          prNumber,
          e.getMessage());
      return false;
    }
  }

  /**
   * PR-H (2026-05-14) — delete a remote branch via the GitHub Git Refs API. Used by the
   * stale-branch sweeper to reclaim {@code executespec/*} branches left behind by terminated /
   * abandoned runs.
   *
   * <p>Idempotent on common error codes — 422 (already deleted) is treated as success, 404 is
   * treated as success (already gone). Network / auth failures return false so the sweeper can
   * record a partial failure without aborting the pass.
   *
   * @param repoFullName e.g. "owner/repo"
   * @param branch e.g. "executespec/abc123" (NOT a refs path — caller passes the branch name)
   * @param overridePat caller's PAT (must have repo:write to delete refs)
   * @return true on success (or already-deleted); false on real failure (network / 403 / 401)
   */
  public boolean deleteBranch(String repoFullName, String branch, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return false;
    if (repoFullName == null || !repoFullName.contains("/")) return false;
    if (branch == null || branch.isBlank()) return false;
    // Protect against accidentally deleting protected branches via a typo upstream.
    if ("main".equals(branch) || "master".equals(branch) || "develop".equals(branch)) {
      log.warn(
          "[GitHubRepoService] deleteBranch refusing to delete protected branch {} on {}",
          branch,
          repoFullName);
      return false;
    }

    try {
      HttpClient client = HttpClient.newHttpClient();
      // GitHub Git Refs API: DELETE /repos/{o}/{r}/git/refs/heads/{branch}
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/" + repoFullName + "/git/refs/heads/" + branch))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .DELETE()
              .build();
      HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
      int sc = res.statusCode();
      if (sc == 204) {
        log.info("[GitHubRepoService] Deleted branch {} on {}", branch, repoFullName);
        return true;
      }
      if (sc == 404 || sc == 422) {
        log.info(
            "[GitHubRepoService] Branch {} on {} already gone (HTTP {})", branch, repoFullName, sc);
        return true;
      }
      log.warn(
          "[GitHubRepoService] deleteBranch {} on {} returned {}: {}",
          branch,
          repoFullName,
          sc,
          res.body());
      return false;
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] deleteBranch {} on {} failed: {}",
          branch,
          repoFullName,
          e.getMessage());
      return false;
    }
  }

  /**
   * Install a {@code pull_request} webhook on the given repo so the {@link
   * com.navneet.executespec.project.pr.GitHubWebhookController} receives merge events. Best-effort:
   * logs a warning and returns {@code false} on any failure (no PAT, no callback URL, network
   * error, 422 duplicate, 404 missing admin) so the caller can continue linking the repo either
   * way.
   *
   * <p>Requires {@code github.webhook.callback-url} + {@code github.webhook.secret} to be
   * configured; otherwise this short-circuits to {@code false}.
   *
   * @param repoFullName e.g. "owner/repo"
   * @param overridePat caller's PAT (must have {@code admin:repo_hook})
   * @return {@code true} on 201 Created, else {@code false}
   */
  /**
   * v2.5.5 (UX-4.2) — list files changed between two commits via GitHub's compare API. Returns one
   * {@link ChangedFile} per file (added / modified / removed / renamed) or empty on failure. No
   * pagination — the compare endpoint caps at 300 files per response which is plenty for an arch
   * diff. Uses the caller's PAT for private-repo access.
   */
  public List<ChangedFile> compareCommits(
      String repoFullName, String baseSha, String headSha, String overridePat) {
    String effectivePat = overridePat;
    if (repoFullName == null || baseSha == null || headSha == null) return List.of();
    if (baseSha.equals(headSha)) return List.of();
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest.Builder b =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/compare/"
                          + baseSha
                          + "..."
                          + headSha))
              .header("Accept", "application/vnd.github+json")
              .GET();
      if (effectivePat != null && !effectivePat.isBlank()) {
        b.header("Authorization", "Bearer " + effectivePat);
      }
      HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) {
        log.warn(
            "[GitHubRepoService] compareCommits {}/{}...{} returned {}",
            repoFullName,
            baseSha,
            headSha,
            res.statusCode());
        return List.of();
      }
      JsonNode root = new ObjectMapper().readTree(res.body());
      JsonNode files = root.path("files");
      if (!files.isArray()) return List.of();
      List<ChangedFile> out = new ArrayList<>(files.size());
      for (JsonNode f : files) {
        out.add(
            new ChangedFile(
                f.path("filename").asText(""),
                f.path("status").asText("modified"),
                f.path("additions").asInt(0),
                f.path("deletions").asInt(0)));
      }
      return out;
    } catch (Exception e) {
      log.warn("[GitHubRepoService] compareCommits {}: {}", repoFullName, e.getMessage());
      return List.of();
    }
  }

  /** GitHub compare API file entry — status is one of added/modified/removed/renamed. */
  public record ChangedFile(String filename, String status, int additions, int deletions) {}

  public boolean installWebhook(String repoFullName, String overridePat) {
    if (webhookCallbackUrl == null
        || webhookCallbackUrl.isBlank()
        || webhookSecret == null
        || webhookSecret.isBlank()) {
      log.debug("[GitHubRepoService] Webhook auto-provision disabled — callback/secret not set");
      return false;
    }
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return false;
    if (repoFullName == null || !repoFullName.contains("/")) return false;

    String body =
        """
{"name":"web","active":true,"events":["pull_request"],"config":{"url":"%s","content_type":"json","secret":"%s","insecure_ssl":"0"}}"""
            .formatted(webhookCallbackUrl, webhookSecret.replace("\"", "\\\""));
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/hooks"))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 201) {
        log.info("[GitHubRepoService] Installed PR webhook on {}", repoFullName);
        return true;
      }
      // 422 = duplicate hook (already installed) — treat as success so the repo flag flips.
      if (res.statusCode() == 422 && res.body() != null && res.body().contains("already exists")) {
        log.info(
            "[GitHubRepoService] PR webhook already exists on {} — treating as installed",
            repoFullName);
        return true;
      }
      log.warn(
          "[GitHubRepoService] Webhook install on {} returned {}: {}",
          repoFullName,
          res.statusCode(),
          res.body());
      return false;
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] Webhook install on {} failed: {}", repoFullName, e.getMessage());
      return false;
    }
  }

  /**
   * Updates the description of an existing GitHub repo.
   *
   * @param repoUrl HTTPS clone URL (e.g. https://github.com/owner/repo.git)
   * @param description new description text
   * @param overridePat per-user PAT (null falls back to system PAT)
   */
  public void updateRepoDescription(String repoUrl, String description, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return;

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return;

    String sanitised = sanitiseDescription(description);
    String body =
        """
        {"description":"%s"}"""
            .formatted(sanitised);

    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
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
        log.warn(
            "[GitHubRepoService] Failed to update description for {}/{}: {}",
            ownerRepo[0],
            ownerRepo[1],
            response.statusCode());
      }
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error updating repo description: {}", e.getMessage());
    }
  }

  /**
   * Lists all file paths in a GitHub repo's main branch via the Git Trees API.
   *
   * @param repoUrl HTTPS clone URL
   * @param overridePat per-user PAT (null falls back to system PAT)
   * @return sorted list of relative file paths, or empty list on failure
   */
  public List<String> listRepoFiles(String repoUrl, String overridePat) {
    return listRepoFiles(repoUrl, null, overridePat);
  }

  /** Variant that respects a non-default branch (avoids the hardcoded {@code main}). */
  public List<String> listRepoFiles(String repoUrl, String branch, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return List.of();

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return List.of();

    String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + ownerRepo[0]
                          + "/"
                          + ownerRepo[1]
                          + "/git/trees/"
                          + effectiveBranch
                          + "?recursive=1"))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn(
            "[GitHubRepoService] Failed to list repo files for {}: {}",
            repoUrl,
            response.statusCode());
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
   * @param repoUrl HTTPS clone URL
   * @param relativePath file path relative to repo root
   * @param overridePat per-user PAT (null falls back to system PAT)
   * @return file content as string, or error message on failure
   */
  public String readRepoFile(String repoUrl, String relativePath, String overridePat) {
    return readRepoFile(repoUrl, relativePath, null, overridePat);
  }

  public String readRepoFile(
      String repoUrl, String relativePath, String branch, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return "// No GitHub PAT configured";

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return "// Invalid repo URL";

    String effectiveBranch = (branch != null && !branch.isBlank()) ? branch : "main";
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + ownerRepo[0]
                          + "/"
                          + ownerRepo[1]
                          + "/contents/"
                          // URL-encode the path (preserving '/') so Next.js dynamic-route
                          // segments like [contactId] don't throw "Illegal character in path".
                          + URLEncoder.encode(relativePath, StandardCharsets.UTF_8)
                              .replace("%2F", "/")
                          + "?ref="
                          + URLEncoder.encode(effectiveBranch, StandardCharsets.UTF_8)))
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

  /** Clones repo into workspacePath using the system PAT (backward-compat overload). */
  public void cloneRepo(String repoUrl, String workspacePath) throws Exception {
    cloneRepo(repoUrl, workspacePath, null);
  }

  /**
   * Clones a GitHub repo into {@code workspacePath} using the provided PAT for auth. Falls back to
   * {@code git init} if no PAT is available.
   */
  public void cloneRepo(String repoUrl, String workspacePath, String overridePat) throws Exception {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) {
      log.info(
          "[GitHubRepoService] No PAT configured — initializing empty git repo at {}",
          workspacePath);
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

  /** Generates a random memorable repo name (e.g. "swift-spark", "bold-nexus"). */
  public String generateRepoName() {
    var rng = ThreadLocalRandom.current();
    return ADJECTIVES[rng.nextInt(ADJECTIVES.length)] + "-" + NOUNS[rng.nextInt(NOUNS.length)];
  }

  /**
   * Generates a URL-safe slug from a goal string (kept for backward compatibility). "Add real-time
   * collaborative editing" -> "realtime-collaborative-editing"
   */
  public String generateSlug(String goal) {
    String slug = goal.toLowerCase().replaceAll("[^a-z0-9\\s-]", "").trim().replaceAll("\\s+", "-");
    slug = slug.replaceAll("^(add|build|create|implement|fix|update|make|write|design)-", "");
    if (slug.length() > 40) slug = slug.substring(0, 40);
    return slug.replaceAll("-+$", "");
  }

  // ── Branch + PR ──────────────────────────────────────────────────────────────

  /**
   * Returns the default branch name (e.g. "main", "master") for a GitHub repo.
   *
   * @param repoUrl HTTPS clone URL
   * @param overridePat per-user PAT (null falls back to system PAT)
   * @return default branch name, or "main" on failure
   */
  public String getDefaultBranch(String repoUrl, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return "main";

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return "main";

    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
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
      log.warn(
          "[GitHubRepoService] Failed to get default branch for {}: status {}",
          repoUrl,
          response.statusCode());
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error getting default branch: {}", e.getMessage());
    }
    return "main";
  }

  /**
   * R-323 (D-125 #3) — read the repo's reported size (in KB) from GitHub's repo-meta endpoint so we
   * can gate attach on a clone-time budget. Returns {@code -1} on any failure (missing PAT, repo
   * unreachable, parse error) so callers can treat "unknown size" as "don't gate" rather than
   * blowing up the attach flow.
   *
   * <p>Background: {@code GET /repos/{owner}/{repo}} returns a JSON body with a top-level {@code
   * "size"} integer in KB. We've already been hitting this endpoint inside {@link
   * #getDefaultBranch}; this is a parallel reader on the same resource.
   */
  public long getRepoSizeKb(String repoUrl, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return -1L;

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return -1L;

    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.debug("[R-323] getRepoSizeKb {} status {}", repoUrl, response.statusCode());
        return -1L;
      }
      JsonNode root = new ObjectMapper().readTree(response.body());
      JsonNode size = root.get("size");
      if (size == null || !size.isNumber()) return -1L;
      return size.asLong();
    } catch (Exception e) {
      log.debug("[R-323] getRepoSizeKb error for {}: {}", repoUrl, e.getMessage());
      return -1L;
    }
  }

  /**
   * Pilot v3.0 — fetch the HEAD SHA of a given branch for drift detection. Returns {@code null} on
   * any failure (missing PAT, unauthorised, repo moved) so callers can treat the repo as "unknown"
   * without throwing.
   */
  public String getBranchHeadSha(String repoUrl, String branch, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return null;
    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return null;
    String effectiveBranch = (branch == null || branch.isBlank()) ? "main" : branch;

    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + ownerRepo[0]
                          + "/"
                          + ownerRepo[1]
                          + "/commits/"
                          + effectiveBranch))
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
   * @param repoUrl HTTPS clone URL
   * @param head source branch name (e.g. "executespec/abc123")
   * @param base target branch name (e.g. "main")
   * @param title PR title
   * @param prBody PR description body
   * @param overridePat per-user PAT (null falls back to system PAT)
   * @return PR HTML URL (e.g. "https://github.com/owner/repo/pull/1"), or null on failure
   */
  public String createPullRequest(
      String repoUrl, String head, String base, String title, String prBody, String overridePat) {
    String effectivePat = overridePat;
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
    String safeBody =
        prBody != null
            ? prBody.replace("\"", "'").replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")
            : "";

    String requestBody =
        """
        {"title":"%s","head":"%s","base":"%s","body":"%s"}"""
            .formatted(safeTitle, head, base, safeBody);

    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + ownerRepo[0]
                          + "/"
                          + ownerRepo[1]
                          + "/pulls"))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 201 || response.statusCode() == 200) {
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
      // 422 with "A pull request already exists" is normal for continue-on-PR
      // flows — the push already updated the branch, and GitHub auto-updates
      // the existing PR. Return null gracefully (caller should not treat as error).
      if (response.statusCode() == 422
          && response.body() != null
          && response.body().contains("already exists")) {
        log.info(
            "[GitHubRepoService] PR already exists for {}/{} head={} — follow-up push updated it",
            ownerRepo[0],
            ownerRepo[1],
            head);
        return null;
      }
      log.warn(
          "[GitHubRepoService] Failed to create PR for {}/{}: status {} — {}",
          ownerRepo[0],
          ownerRepo[1],
          response.statusCode(),
          response.body());
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error creating PR: {}", e.getMessage());
    }
    return null;
  }

  /**
   * Pilot v3.0 — patch a PR's body. Used for cross-linking PRs created by the same multi-repo run.
   * Best-effort: logs and continues on failure.
   */
  public void updatePullRequestBody(
      String repoUrl, int prNumber, String newBody, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return;
    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return;
    String safeBody =
        newBody == null
            ? ""
            : newBody
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    String body = "{\"body\":\"" + safeBody + "\"}";
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + ownerRepo[0]
                          + "/"
                          + ownerRepo[1]
                          + "/pulls/"
                          + prNumber))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .header("Content-Type", "application/json")
              .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        log.warn(
            "[GitHubRepoService] updatePullRequestBody PR#{} status {}",
            prNumber,
            response.statusCode());
      }
    } catch (Exception e) {
      log.warn("[GitHubRepoService] updatePullRequestBody error: {}", e.getMessage());
    }
  }

  /**
   * Pilot R5 — request reviewers on an existing PR via {@code POST
   * /repos/{owner}/{repo}/pulls/{number}/requested_reviewers}. Best-effort: logs on failure but
   * never throws.
   */
  public void requestReviewers(
      String repoUrl, int prNumber, java.util.List<String> reviewers, String overridePat) {
    if (reviewers == null || reviewers.isEmpty()) return;
    String effectivePat = overridePat;
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
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + ownerRepo[0]
                          + "/"
                          + ownerRepo[1]
                          + "/pulls/"
                          + prNumber
                          + "/requested_reviewers"))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 300) {
        log.warn(
            "[GitHubRepoService] requestReviewers PR#{} status {} — {}",
            prNumber,
            response.statusCode(),
            response.body());
      }
    } catch (Exception e) {
      log.warn("[GitHubRepoService] requestReviewers error: {}", e.getMessage());
    }
  }

  // ── PR review comments ──────────────────────────────────────────────────────

  private static final int PR_COMMENTS_MAX_CHARS = 4000;
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  /**
   * Fetches PR review comments (inline code comments + top-level review bodies) and formats them as
   * markdown suitable for prompt injection.
   *
   * @return formatted comments, or empty string on failure (non-fatal)
   */
  public String fetchPrReviewComments(String repoFullName, int prNumber, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return "";
    if (repoFullName == null || repoFullName.isBlank()) return "";

    StringBuilder sb = new StringBuilder();
    try {
      HttpClient client = HttpClient.newHttpClient();

      // 1. Top-level review bodies (approve/request-changes/comment)
      String reviewsUrl =
          "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber + "/reviews";
      HttpResponse<String> reviewsResp =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(reviewsUrl))
                  .header("Authorization", "Bearer " + effectivePat)
                  .header("Accept", "application/vnd.github+json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      if (reviewsResp.statusCode() == 200) {
        JsonNode reviews = JSON_MAPPER.readTree(reviewsResp.body());
        for (JsonNode r : reviews) {
          String body = textOrEmpty(r, "body");
          String state = textOrEmpty(r, "state");
          String user =
              r.has("user") && r.get("user").has("login")
                  ? r.get("user").get("login").asText()
                  : "unknown";
          if (body.isBlank()) continue;
          sb.append("### Review by @")
              .append(user)
              .append(" (")
              .append(state)
              .append(")\n")
              .append(body)
              .append("\n\n");
          if (sb.length() > PR_COMMENTS_MAX_CHARS) break;
        }
      }

      // 2. Inline code comments (file + line + body)
      String commentsUrl =
          "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber + "/comments";
      HttpResponse<String> commentsResp =
          client.send(
              HttpRequest.newBuilder()
                  .uri(URI.create(commentsUrl))
                  .header("Authorization", "Bearer " + effectivePat)
                  .header("Accept", "application/vnd.github+json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      if (commentsResp.statusCode() == 200) {
        JsonNode comments = JSON_MAPPER.readTree(commentsResp.body());
        for (JsonNode c : comments) {
          String body = textOrEmpty(c, "body");
          String path = textOrEmpty(c, "path");
          int line = c.has("line") && !c.get("line").isNull() ? c.get("line").asInt() : 0;
          String user =
              c.has("user") && c.get("user").has("login")
                  ? c.get("user").get("login").asText()
                  : "unknown";
          if (body.isBlank()) continue;
          sb.append("- **").append(path);
          if (line > 0) sb.append(":").append(line);
          sb.append("** (@").append(user).append("): ").append(body).append("\n");
          if (sb.length() > PR_COMMENTS_MAX_CHARS) break;
        }
      }
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] fetchPrReviewComments {}/{} failed: {}",
          repoFullName,
          prNumber,
          e.getMessage());
      return "";
    }

    String result = sb.toString();
    if (result.length() > PR_COMMENTS_MAX_CHARS) {
      result = result.substring(0, PR_COMMENTS_MAX_CHARS) + "\n...(truncated)";
    }
    return result;
  }

  private static String textOrEmpty(JsonNode node, String field) {
    return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
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
   * Extracts [owner, repo] from a clone URL like https://github.com/owner/repo.git Returns null if
   * the URL doesn't match the expected pattern.
   */
  public static String[] extractOwnerRepo(String repoUrl) {
    if (repoUrl == null) return null;
    // Strip .git suffix and protocol
    String path =
        repoUrl
            .replace("https://github.com/", "")
            .replace("http://github.com/", "")
            .replaceAll("\\.git$", "");
    String[] parts = path.split("/");
    if (parts.length >= 2) {
      return new String[] {parts[0], parts[1]};
    }
    return null;
  }

  /**
   * Attempts to create a repo via {@code endpoint}. Returns result with clone_url or failure
   * reason.
   */
  private CreateResult tryCreate(
      String endpoint, String effectivePat, String body, String owner, String name) {
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder()
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
      log.debug(
          "[GitHubRepoService] {} returned status {} for {}/{} (nameTaken={})",
          endpoint,
          status,
          owner,
          name,
          nameTaken);
      return new CreateResult(null, nameTaken);

    } catch (Exception e) {
      log.warn("[GitHubRepoService] Repo creation via {} failed: {}", endpoint, e.getMessage());
      return new CreateResult(null, false);
    }
  }

  /**
   * Parses the Git Trees API response to extract file paths. Expected format: {"tree": [{"path":
   * "src/main.java", "type": "blob"}, ...]}
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

  /**
   * Snapshot of a PR for dashboard rendering (title, body, stats, truncated diff).
   *
   * <p>v2.6.4 — gained {@code headSha} so callers (PR Review Agent in particular) can build a
   * stable idempotency key per (repo, pr, sha). Older callers that ignore {@code headSha} are
   * unaffected — Java records are forward-compatible at the call site as long as construction
   * happens here.
   */
  public record PullRequestSnapshot(
      String title,
      String body,
      String state,
      boolean merged,
      int additions,
      int deletions,
      int changedFiles,
      String headBranch,
      String baseBranch,
      String headSha,
      String diffPreview) {}

  /**
   * Fetches a PR + its diff (truncated) for inline dashboard rendering. Returns {@code null} on any
   * failure — caller should degrade to link-only.
   *
   * <p>2026-05-04 — P0 hotfix: previously {@link HttpClient#newHttpClient()} ran with no timeouts,
   * so a slow GitHub or DNS hang would block the thread until Cloudflare's 100s edge timeout fired
   * (HTTP 524). Now uses a 5s connect + 15s per-request timeout. Also: when {@code diffMaxChars ==
   * 0} (the {@link com.navneet.executespec.project.pr.PrRefreshService} path which only needs state
   * + merged), we skip the diff GET entirely — the refresh button only cares whether the PR is
   * OPEN/CLOSED/MERGED, so pulling the entire diff body is wasted IO and adds the same hang risk
   * for a value the caller throws away.
   */
  public PullRequestSnapshot getPullRequestSnapshot(
      String repoFullName, int prNumber, String overridePat, int diffMaxChars) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return null;
    if (repoFullName == null || repoFullName.isBlank()) return null;
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

      // PR metadata (JSON)
      HttpRequest metaReq =
          HttpRequest.newBuilder()
              .uri(
                  URI.create("https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> metaResp = client.send(metaReq, HttpResponse.BodyHandlers.ofString());
      if (metaResp.statusCode() != 200) {
        log.warn(
            "[GitHubRepoService] PR snapshot {} #{} meta status={}",
            repoFullName,
            prNumber,
            metaResp.statusCode());
        return null;
      }
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode meta = mapper.readTree(metaResp.body());

      String truncated = "";
      // PR diff (text) — skipped when caller passes diffMaxChars=0 (refresh path).
      if (diffMaxChars > 0) {
        HttpRequest diffReq =
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "https://api.github.com/repos/" + repoFullName + "/pulls/" + prNumber))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + effectivePat)
                .header("Accept", "application/vnd.github.v3.diff")
                .GET()
                .build();
        HttpResponse<String> diffResp = client.send(diffReq, HttpResponse.BodyHandlers.ofString());
        String diff = diffResp.statusCode() == 200 ? diffResp.body() : "";
        truncated = diff;
        if (diff.length() > diffMaxChars) {
          truncated =
              diff.substring(0, diffMaxChars)
                  + "\n\n… (truncated, "
                  + (diff.length() - diffMaxChars)
                  + " more chars)";
        }
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
          meta.path("head").path("sha").asText(""),
          truncated);
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] PR snapshot error {} #{}: {}",
          repoFullName,
          prNumber,
          e.getMessage());
      return null;
    }
  }

  /**
   * Rollup of GitHub's check-runs for a single commit. Maps GitHub's per-run {@code conclusion}
   * field into the user-visible {@link
   * com.navneet.executespec.project.pr.PullRequestRecord.CiCheckStatus}.
   *
   * <p>Conclusion priority (any failing → overall FAILURE):
   *
   * <ul>
   *   <li>FAILURE if any {@code conclusion} ∈ {failure, cancelled, timed_out, action_required,
   *       startup_failure}.
   *   <li>PENDING if any check still has {@code status != completed}.
   *   <li>SUCCESS if all completed checks have {@code conclusion ∈ {success, skipped}}.
   *   <li>NEUTRAL when checks exist but all are {@code conclusion = neutral} (treated separately
   *       from SUCCESS so an explicit "neutral" run doesn't claim green).
   *   <li>UNKNOWN when GitHub returns zero check-runs (no CI configured, or pre-first-push).
   * </ul>
   *
   * @return record with the rollup status + count of failing checks (0 unless FAILURE).
   */
  public record CheckRunRollup(String status, int failingCount) {}

  public CheckRunRollup getCheckRunRollup(String repoFullName, String sha, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return new CheckRunRollup("UNKNOWN", 0);
    if (repoFullName == null || repoFullName.isBlank() || sha == null || sha.isBlank()) {
      return new CheckRunRollup("UNKNOWN", 0);
    }
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/commits/"
                          + sha
                          + "/check-runs?per_page=100"))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.debug(
            "[GitHubRepoService] check-runs {} @ {} status={}",
            repoFullName,
            sha.substring(0, Math.min(7, sha.length())),
            resp.statusCode());
        return new CheckRunRollup("UNKNOWN", 0);
      }
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(resp.body());
      com.fasterxml.jackson.databind.JsonNode runs = root.path("check_runs");
      if (!runs.isArray() || runs.size() == 0) return new CheckRunRollup("UNKNOWN", 0);

      int failing = 0;
      int pending = 0;
      int success = 0;
      int neutral = 0;
      int other = 0;
      for (com.fasterxml.jackson.databind.JsonNode r : runs) {
        String status = r.path("status").asText("");
        String conclusion = r.path("conclusion").asText("");
        if (!"completed".equalsIgnoreCase(status)) {
          pending++;
          continue;
        }
        switch (conclusion == null ? "" : conclusion.toLowerCase()) {
          case "failure", "cancelled", "timed_out", "action_required", "startup_failure" ->
              failing++;
          case "success" -> success++;
          case "skipped" -> success++;
          case "neutral" -> neutral++;
          default -> other++;
        }
      }
      if (failing > 0) return new CheckRunRollup("FAILURE", failing);
      if (pending > 0) return new CheckRunRollup("PENDING", 0);
      if (success > 0) return new CheckRunRollup("SUCCESS", 0);
      if (neutral > 0) return new CheckRunRollup("NEUTRAL", 0);
      // Only "other" buckets present (stale, etc.) — degrade to UNKNOWN rather
      // than fake a green; caller treats UNKNOWN as "no signal."
      return other > 0 ? new CheckRunRollup("UNKNOWN", 0) : new CheckRunRollup("UNKNOWN", 0);
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] check-runs error {} @ {}: {}",
          repoFullName,
          sha != null ? sha.substring(0, Math.min(7, sha.length())) : "?",
          e.getMessage());
      return new CheckRunRollup("UNKNOWN", 0);
    }
  }

  /**
   * Lists repos visible to the supplied PAT. Pulls up to 200 results across two pages of {@code
   * /user/repos} (covers private + collaborator + org-member repos the token can see). If {@code
   * ownerFilter} is non-blank, results are filtered to that owner only — useful when the PAT can
   * see many orgs but the user only wants to register repos from one of them.
   */
  public List<RemoteRepo> listOwnerRepos(String ownerFilter, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return List.of();

    com.fasterxml.jackson.databind.ObjectMapper mapper =
        new com.fasterxml.jackson.databind.ObjectMapper();
    List<RemoteRepo> out = new ArrayList<>();
    try {
      HttpClient client = HttpClient.newHttpClient();
      for (int page = 1; page <= 2; page++) {
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "https://api.github.com/user/repos"
                            + "?affiliation=owner,collaborator,organization_member"
                            + "&per_page=100&page="
                            + page))
                .header("Authorization", "Bearer " + effectivePat)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
          log.warn(
              "[GitHubRepoService] /user/repos page {} returned {}: {}",
              page,
              resp.statusCode(),
              truncate(resp.body(), 200));
          break;
        }
        com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(resp.body());
        if (!arr.isArray() || arr.isEmpty()) break;
        for (com.fasterxml.jackson.databind.JsonNode node : arr) {
          String full = node.path("full_name").asText(null);
          if (full == null) continue;
          if (ownerFilter != null
              && !ownerFilter.isBlank()
              && !full.toLowerCase().startsWith(ownerFilter.toLowerCase() + "/")) {
            continue;
          }
          out.add(
              new RemoteRepo(
                  full,
                  node.path("default_branch").asText("main"),
                  node.path("private").asBoolean(false)));
        }
        if (arr.size() < 100) break; // last page
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
   * Decodes the base64 "content" field from a GitHub Contents API response. The GitHub API returns
   * content in base64 with newlines every 60 chars.
   */
  private String decodeGitHubContent(String json) {
    int contentIdx = json.indexOf("\"content\":");
    if (contentIdx < 0) return "// No content field in response";

    int start = json.indexOf("\"", contentIdx + 10) + 1;
    int end = json.indexOf("\"", start);
    String base64 = json.substring(start, end).replace("\\n", "").replace("\n", "").trim();

    try {
      byte[] decoded = Base64.getDecoder().decode(base64);
      return new String(decoded);
    } catch (Exception e) {
      return "// Failed to decode file content: " + e.getMessage();
    }
  }
}
