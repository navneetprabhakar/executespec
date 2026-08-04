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

  private static final Duration WEBHOOK_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration WEBHOOK_REQUEST_TIMEOUT = Duration.ofSeconds(20);

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

      // RR-149 — some other failure (auth, network, API error). A repository was NOT created, so we
      // must NOT return a constructed URL: the run would persist and publish against a source that
      // does not exist. Signal failure (null); callers surface a safe, explicit creation error.
      log.warn(
          "[GitHubRepoService] Repo creation for '{}' failed without a name collision — no repo was"
              + " created; returning null rather than a fabricated URL",
          name);
      return null;
    }

    // RR-149 — all name-collision retries exhausted without a successful create. Never return an
    // unattempted URL; report failure so retry/idempotency stays explicit.
    log.warn(
        "[GitHubRepoService] Repo creation exhausted {} name-collision retries for base '{}' —"
            + " returning null",
        3,
        baseName);
    return null;
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
   * {@link ChangedFile} per file (added / modified / removed / renamed). No pagination — the
   * compare endpoint caps at 300 files per response which is plenty for an arch diff. Uses the
   * caller's PAT for private-repo access.
   *
   * <p>RR-1656 — the result is typed because an empty file list is NOT evidence of "these two SHAs
   * are identical": a non-200 response, an auth failure and a transport error all produce no files
   * as well. Callers that render or score the diff must branch on {@link
   * CompareResult#successful()} rather than on emptiness.
   */
  public CompareResult compareCommits(
      String repoFullName, String baseSha, String headSha, String overridePat) {
    String effectivePat = overridePat;
    if (repoFullName == null || baseSha == null || headSha == null) {
      return CompareResult.failed("INVALID_COMPARE_ARGUMENTS", null);
    }
    // Identical SHAs are a genuine, verified no-change compare — never a failure.
    if (baseSha.equals(headSha)) return CompareResult.success(List.of());
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
        return CompareResult.failed("GITHUB_COMPARE_HTTP_ERROR", res.statusCode());
      }
      JsonNode root = new ObjectMapper().readTree(res.body());
      JsonNode files = root.path("files");
      // A compare payload with no "files" array is a shape we cannot read — not a clean compare.
      if (!files.isArray()) return CompareResult.failed("GITHUB_COMPARE_UNREADABLE_BODY", 200);
      List<ChangedFile> out = new ArrayList<>(files.size());
      for (JsonNode f : files) {
        out.add(
            new ChangedFile(
                f.path("filename").asText(""),
                f.path("status").asText("modified"),
                f.path("additions").asInt(0),
                f.path("deletions").asInt(0)));
      }
      return CompareResult.success(out);
    } catch (Exception e) {
      log.warn("[GitHubRepoService] compareCommits {}: {}", repoFullName, e.getMessage());
      return CompareResult.failed("GITHUB_COMPARE_REQUEST_FAILED", null);
    }
  }

  /**
   * Typed GitHub compare result (RR-1656). {@code successful=true} with an empty {@code files} list
   * means the compare ran and found no changed files; {@code successful=false} means the question
   * could not be asked and the caller must not present the result as a clean diff.
   */
  public record CompareResult(
      List<ChangedFile> files, boolean successful, String errorCode, Integer upstreamStatus) {
    public CompareResult {
      files = files == null ? List.of() : List.copyOf(files);
    }

    public static CompareResult success(List<ChangedFile> files) {
      return new CompareResult(files, true, null, 200);
    }

    public static CompareResult failed(String errorCode, Integer upstreamStatus) {
      return new CompareResult(List.of(), false, errorCode, upstreamStatus);
    }
  }

  /**
   * True if EVERY file changed between {@code baseSha} and {@code headSha} sits under {@code
   * prefix} (e.g. {@code ".executespec/"}). Used to break a regenerate loop: ExecuteSpec publishes
   * codemaps to the customer repo under {@code .executespec/}, which moves HEAD — without this the
   * next sync sees HEAD ≠ the codemap's {@code generatedFromSha} and regenerates (→ publish → HEAD
   * moves → …). Fail-safe: returns false on equal SHAs (caller handles equality), an empty/failed
   * compare, or any file outside {@code prefix} — i.e. when in doubt, treat it as a real change.
   */
  public boolean diffConfinedTo(
      String repoFullName, String baseSha, String headSha, String prefix, String overridePat) {
    if (baseSha == null || headSha == null || baseSha.equals(headSha)) return false;
    CompareResult compare = compareCommits(repoFullName, baseSha, headSha, overridePat);
    // Fail-safe: an unavailable compare is "in doubt", which this contract treats as a real change.
    if (!compare.successful()) return false;
    List<ChangedFile> files = compare.files();
    if (files.isEmpty()) return false;
    for (ChangedFile f : files) {
      if (f.filename() == null || !f.filename().startsWith(prefix)) return false;
    }
    return true;
  }

  /** GitHub compare API file entry — status is one of added/modified/removed/renamed. */
  public record ChangedFile(String filename, String status, int additions, int deletions) {}

  /** Actionable, secret-free result for one GitHub webhook installation attempt. */
  public record WebhookInstallResult(
      boolean installed,
      String eligibilityState,
      String blockingGate,
      String errorCode,
      String reason,
      String remediation,
      boolean retryable,
      Integer upstreamStatus) {

    public static WebhookInstallResult success() {
      return new WebhookInstallResult(true, "ELIGIBLE", null, null, null, null, false, 201);
    }

    public static WebhookInstallResult blocked(
        String gate, String code, String reason, String remediation) {
      return new WebhookInstallResult(
          false, "BLOCKED", gate, code, reason, remediation, false, null);
    }

    public static WebhookInstallResult rejected(
        String gate,
        String code,
        String reason,
        String remediation,
        boolean retryable,
        Integer upstreamStatus) {
      return new WebhookInstallResult(
          false, "REJECTED", gate, code, reason, remediation, retryable, upstreamStatus);
    }
  }

  /** Compatibility wrapper for existing background auto-provisioning. */
  public boolean installWebhook(String repoFullName, String overridePat) {
    return installWebhookDetailed(repoFullName, overridePat).installed();
  }

  public WebhookInstallResult installWebhookDetailed(String repoFullName, String overridePat) {
    if (webhookCallbackUrl == null
        || webhookCallbackUrl.isBlank()
        || webhookSecret == null
        || webhookSecret.isBlank()) {
      log.debug("[GitHubRepoService] Webhook auto-provision disabled — callback/secret not set");
      return WebhookInstallResult.blocked(
          "SERVER_WEBHOOK_CONFIGURATION",
          "WEBHOOK_CONFIGURATION_UNAVAILABLE",
          "Webhook installation is not configured in this ExecuteSpec environment.",
          "Ask an operator to configure the GitHub webhook callback URL and signing secret, then"
              + " retry.");
    }
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) {
      return WebhookInstallResult.blocked(
          "CALLER_GITHUB_CREDENTIAL",
          "GITHUB_PAT_REQUIRED",
          "A GitHub personal access token is required to install this repository webhook.",
          "Add a GitHub PAT with repository webhook administration permission, then retry.");
    }
    if (repoFullName == null || !repoFullName.matches("[^/\\s]+/[^/\\s]+")) {
      return WebhookInstallResult.blocked(
          "SOURCE_IDENTITY",
          "GITHUB_REPOSITORY_IDENTITY_INVALID",
          "The linked repository does not have a valid GitHub owner/name identity.",
          "Re-sync or relink the repository before installing its webhook.");
    }

    String body =
        """
{"name":"web","active":true,"events":["pull_request"],"config":{"url":"%s","content_type":"json","secret":"%s","insecure_ssl":"0"}}"""
            .formatted(webhookCallbackUrl, webhookSecret.replace("\"", "\\\""));
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(WEBHOOK_CONNECT_TIMEOUT).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.github.com/repos/" + repoFullName + "/hooks"))
              .timeout(WEBHOOK_REQUEST_TIMEOUT)
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() == 201) {
        log.info("[GitHubRepoService] Installed PR webhook on {}", repoFullName);
        return WebhookInstallResult.success();
      }
      // 422 = duplicate hook (already installed) — treat as success so the repo flag flips.
      if (res.statusCode() == 422 && res.body() != null && res.body().contains("already exists")) {
        log.info(
            "[GitHubRepoService] PR webhook already exists on {} — treating as installed",
            repoFullName);
        return new WebhookInstallResult(
            true, "ALREADY_INSTALLED", null, null, null, null, false, 422);
      }
      log.warn(
          "[GitHubRepoService] Webhook install on {} returned {}: {}",
          repoFullName,
          res.statusCode(),
          res.body());
      return upstreamWebhookFailure(res.statusCode());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.warn("[GitHubRepoService] Webhook install on {} was interrupted", repoFullName);
      return WebhookInstallResult.rejected(
          "GITHUB_API",
          "GITHUB_WEBHOOK_INSTALL_INTERRUPTED",
          "Webhook installation was interrupted before GitHub completed the request.",
          "Retry the installation when no repository operation is being cancelled.",
          true,
          null);
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] Webhook install on {} failed: {}",
          repoFullName,
          e.getClass().getSimpleName());
      return WebhookInstallResult.rejected(
          "GITHUB_API",
          "GITHUB_WEBHOOK_API_UNAVAILABLE",
          "GitHub could not be reached while installing the webhook.",
          "Retry after GitHub connectivity is restored.",
          true,
          null);
    }
  }

  private static WebhookInstallResult upstreamWebhookFailure(int status) {
    return switch (status) {
      case 401 ->
          WebhookInstallResult.rejected(
              "CALLER_GITHUB_CREDENTIAL",
              "GITHUB_PAT_INVALID",
              "GitHub rejected the personal access token.",
              "Replace the expired or invalid GitHub PAT, then retry.",
              false,
              status);
      case 403 ->
          WebhookInstallResult.rejected(
              "GITHUB_WEBHOOK_PERMISSION",
              "GITHUB_WEBHOOK_PERMISSION_DENIED",
              "GitHub denied permission to administer webhooks for this repository.",
              "Grant the PAT repository administration/webhook permission and confirm the user has"
                  + " admin access, then retry.",
              false,
              status);
      case 404 ->
          WebhookInstallResult.rejected(
              "SOURCE_ACCESS",
              "GITHUB_REPOSITORY_NOT_ACCESSIBLE",
              "GitHub could not find this repository for the supplied account.",
              "Confirm the repository still exists and the PAT can access it, then re-sync and"
                  + " retry.",
              false,
              status);
      case 429 ->
          WebhookInstallResult.rejected(
              "GITHUB_RATE_LIMIT",
              "GITHUB_RATE_LIMITED",
              "GitHub rate-limited the webhook installation request.",
              "Wait for the GitHub rate limit to reset, then retry.",
              true,
              status);
      default ->
          WebhookInstallResult.rejected(
              "GITHUB_API",
              "GITHUB_WEBHOOK_INSTALL_REJECTED",
              "GitHub rejected the webhook installation request.",
              "Check repository administration access and GitHub service status, then retry.",
              status >= 500,
              status);
    };
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
    return listRepoFilesResult(repoUrl, null, overridePat).files();
  }

  /** Variant that respects a non-default branch (avoids the hardcoded {@code main}). */
  public List<String> listRepoFiles(String repoUrl, String branch, String overridePat) {
    return listRepoFilesResult(repoUrl, branch, overridePat).files();
  }

  /**
   * Typed GitHub tree result. An empty successful tree is materially different from a missing PAT,
   * upstream rejection, or transport failure; callers that make readiness/scoring decisions must
   * use this method instead of inferring success from an empty list.
   */
  public RepoFileListResult listRepoFilesResult(String repoUrl, String branch, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) {
      return RepoFileListResult.failed("MISSING_GITHUB_PAT", null);
    }

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return RepoFileListResult.failed("INVALID_REPOSITORY_URL", null);

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
        return RepoFileListResult.failed("GITHUB_TREE_HTTP_ERROR", response.statusCode());
      }

      // Parse the "tree" array: extract "path" where "type" is "blob". GitHub marks recursive
      // trees as truncated for very large repositories; preserve that fact instead of presenting
      // a partial inventory as complete.
      boolean truncated =
          new ObjectMapper().readTree(response.body()).path("truncated").asBoolean(false);
      return RepoFileListResult.success(parseTreePaths(response.body()), truncated);
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error listing repo files: {}", e.getMessage());
      return RepoFileListResult.failed("GITHUB_TREE_REQUEST_FAILED", null);
    }
  }

  public record RepoFileListResult(
      List<String> files,
      boolean successful,
      boolean truncated,
      String errorCode,
      Integer upstreamStatus) {
    public RepoFileListResult {
      files = files == null ? List.of() : List.copyOf(files);
    }

    public static RepoFileListResult success(List<String> files) {
      return success(files, false);
    }

    public static RepoFileListResult success(List<String> files, boolean truncated) {
      return new RepoFileListResult(files, true, truncated, null, 200);
    }

    public static RepoFileListResult failed(String errorCode, Integer upstreamStatus) {
      return new RepoFileListResult(List.of(), false, false, errorCode, upstreamStatus);
    }
  }

  /**
   * Reads a single file from a GitHub repo via the Contents API.
   *
   * <p>RR-1631 — failures are signalled OUT OF BAND. This method previously returned a {@code "//
   * …"} sentinel string in the same channel as file content, which is indistinguishable from a
   * legitimate C-style/proto/GraphQL source file whose first line is a {@code //} comment. Callers
   * must branch on {@link RepoFileResult#successful()} and never inspect the content prefix.
   *
   * @param repoUrl HTTPS clone URL
   * @param relativePath file path relative to repo root
   * @param overridePat per-user PAT
   */
  public RepoFileResult readRepoFile(String repoUrl, String relativePath, String overridePat) {
    return readRepoFile(repoUrl, relativePath, null, overridePat);
  }

  public RepoFileResult readRepoFile(
      String repoUrl, String relativePath, String branch, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) {
      return RepoFileResult.failed("MISSING_GITHUB_PAT", null);
    }

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return RepoFileResult.failed("INVALID_REPOSITORY_URL", null);

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
        return RepoFileResult.failed("GITHUB_CONTENTS_HTTP_ERROR", response.statusCode());
      }

      // Extract base64-encoded "content" field and decode
      return decodeGitHubContent(response.body());
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error reading repo file {}: {}", relativePath, e.getMessage());
      return RepoFileResult.failed("GITHUB_CONTENTS_REQUEST_FAILED", null);
    }
  }

  /**
   * Typed single-file read result (RR-1631). {@code successful=false} carries a stable, secret-free
   * {@code errorCode} and never a content string, so an unavailable read can never be mistaken for
   * file bytes — nor a real file whose first line is a comment mistaken for a failure.
   */
  public record RepoFileResult(
      String content, boolean successful, String errorCode, Integer upstreamStatus) {

    public static RepoFileResult success(String content) {
      return new RepoFileResult(content == null ? "" : content, true, null, 200);
    }

    public static RepoFileResult failed(String errorCode, Integer upstreamStatus) {
      return new RepoFileResult(null, false, errorCode, upstreamStatus);
    }

    /** Content when the read succeeded, else {@code null}. Never a failure sentinel. */
    public String contentOrNull() {
      return successful ? content : null;
    }

    /** True when GitHub answered that the path does not exist on the requested ref. */
    public boolean notFound() {
      return !successful && upstreamStatus != null && upstreamStatus == 404;
    }
  }

  /** Clones repo into workspacePath using the system PAT (backward-compat overload). */
  public void cloneRepo(String repoUrl, String workspacePath) throws Exception {
    cloneRepo(repoUrl, workspacePath, null);
  }

  /** Thrown when an existing-repo clone cannot proceed because no credential is available. */
  public static final class MissingCredentialException extends RuntimeException {
    public MissingCredentialException(String message) {
      super(message);
    }
  }

  /**
   * Clones a GitHub repo into {@code workspacePath} using the provided PAT for auth.
   *
   * <p>RR-150 — a missing PAT NEVER silently substitutes an empty {@code git init} workspace: doing
   * so let callers plan and generate against an empty tree while believing they were modifying an
   * existing repository (replacement-style diffs, false readiness). Without a credential we attempt
   * an unauthenticated clone, which succeeds for public repositories; if that fails (private/
   * unreachable) we fail closed with {@link MissingCredentialException} so the caller stops before
   * planning rather than proceeding against an empty source.
   */
  public void cloneRepo(String repoUrl, String workspacePath, String overridePat) throws Exception {
    String effectivePat = overridePat;
    boolean authenticated = effectivePat != null && !effectivePat.isBlank();
    if (!authenticated) {
      log.info(
          "[GitHubRepoService] No PAT configured — attempting unauthenticated clone of {} (public"
              + " repositories only)",
          repoUrl);
    }

    // Credential-gate 2026-07-17: PAT rides the child env (GitProcessAuth), not argv/remote —
    // and inheritIO is gone so child git output can't stream tokens into the parent's stdout.
    ProcessBuilder pb =
        GitProcessAuth.git(
            authenticated ? effectivePat : null,
            java.util.List.of("clone", repoUrl, workspacePath));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      if (!authenticated) {
        throw new MissingCredentialException(
            "MISSING_GITHUB_PAT: clone of "
                + repoUrl
                + " failed without a credential (likely a private or unreachable repository);"
                + " refusing to substitute an empty workspace");
      }
      throw new RuntimeException("git clone failed with exit code " + exit + ": " + out);
    }
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

  /**
   * Returns the default branch name (e.g. "main", "master") for a GitHub repo.
   *
   * @param repoUrl HTTPS clone URL
   * @param overridePat per-user PAT (null falls back to system PAT)
   * @return default branch name, or "main" on failure
   */
  public String getDefaultBranch(String repoUrl, String overridePat) {
    DefaultBranchLookup lookup = findDefaultBranch(repoUrl, overridePat);
    return lookup.known() ? lookup.branch() : "main";
  }

  public record DefaultBranchLookup(boolean known, String branch) {
    public static DefaultBranchLookup unknown() {
      return new DefaultBranchLookup(false, null);
    }

    public static DefaultBranchLookup found(String branch) {
      return new DefaultBranchLookup(true, branch);
    }
  }

  /** Bounded fail-closed default-branch lookup for publication/admission security decisions. */
  public DefaultBranchLookup findDefaultBranch(String repoUrl, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) return DefaultBranchLookup.unknown();

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) return DefaultBranchLookup.unknown();

    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.github.com/repos/" + ownerRepo[0] + "/" + ownerRepo[1]))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + effectivePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        String branch = JSON_MAPPER.readTree(response.body()).path("default_branch").asText(null);
        if (branch != null && !branch.isBlank()) {
          return DefaultBranchLookup.found(branch);
        }
      }
      log.warn(
          "[GitHubRepoService] Failed to get default branch for {}: status {}",
          repoUrl,
          response.statusCode());
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error getting default branch: {}", e.getMessage());
    }
    return DefaultBranchLookup.unknown();
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
   * RR-146 — the outcome of a PR-creation attempt, distinguishing "this call opened a new PR" from
   * "GitHub already has a PR for this head" and from a real failure.
   *
   * <p>The untyped {@code null} return conflated all three, and callers read {@code null} as
   * failure and enqueued deletion of the head branch — so republishing an existing PR could
   * schedule destruction of the branch that PR is reviewing. Only {@link Outcome#FAILED} may
   * authorize branch cleanup.
   */
  public record PullRequestCreation(
      Outcome outcome, String prUrl, Integer prNumber, String detail) {

    public enum Outcome {
      /** This invocation created the pull request. */
      CREATED,
      /** GitHub reports an open pull request already exists for this head branch. */
      ALREADY_EXISTS,
      /** No pull request exists as a result of this invocation, and none was found. */
      FAILED
    }

    public static PullRequestCreation created(String prUrl, Integer prNumber) {
      return new PullRequestCreation(Outcome.CREATED, prUrl, prNumber, "created");
    }

    public static PullRequestCreation alreadyExists(String prUrl, Integer prNumber) {
      return new PullRequestCreation(
          Outcome.ALREADY_EXISTS, prUrl, prNumber, "pull request already exists for this branch");
    }

    public static PullRequestCreation failed(String detail) {
      return new PullRequestCreation(Outcome.FAILED, null, null, detail);
    }

    /** True when a pull request exists for the branch, whoever created it. */
    public boolean prExists() {
      return outcome != Outcome.FAILED;
    }

    public boolean failed() {
      return outcome == Outcome.FAILED;
    }
  }

  /**
   * Creates a pull request on a GitHub repository.
   *
   * <p>Compatibility shape: returns the PR URL, or {@code null} for BOTH "already exists" and real
   * failure. Callers that take a destructive action on failure MUST use {@link
   * #createPullRequestDetailed} instead — see RR-146.
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
    return createPullRequestDetailed(repoUrl, head, base, title, prBody, overridePat).prUrl();
  }

  /**
   * RR-146 — typed PR creation. On GitHub's 422 "A pull request already exists" the existing open
   * PR for {@code head} is looked up and returned as {@link
   * PullRequestCreation.Outcome#ALREADY_EXISTS}; the branch is live review material, never an
   * orphan.
   */
  public PullRequestCreation createPullRequestDetailed(
      String repoUrl, String head, String base, String title, String prBody, String overridePat) {
    String effectivePat = overridePat;
    if (effectivePat == null || effectivePat.isBlank()) {
      log.warn("[GitHubRepoService] No PAT — cannot create PR");
      return PullRequestCreation.failed("no GitHub credential");
    }

    String[] ownerRepo = extractOwnerRepo(repoUrl);
    if (ownerRepo == null) {
      log.warn("[GitHubRepoService] Cannot parse owner/repo from {}", repoUrl);
      return PullRequestCreation.failed("unparseable repository URL");
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
          return PullRequestCreation.created(prUrl, prNumberFromUrl(prUrl));
        }
      }
      // 422 "A pull request already exists" is normal for continue-on-PR flows — the push already
      // updated the branch and GitHub auto-updated the existing PR. RR-146: this is explicitly NOT
      // a failure; the head branch is under active review and must never be queued for deletion.
      if (response.statusCode() == 422
          && response.body() != null
          && response.body().contains("already exists")) {
        log.info(
            "[GitHubRepoService] PR already exists for {}/{} head={} — follow-up push updated it",
            ownerRepo[0],
            ownerRepo[1],
            head);
        OpenPullRequestLookup existing =
            findOpenPullRequestForHead(ownerRepo[0] + "/" + ownerRepo[1], head, effectivePat);
        return PullRequestCreation.alreadyExists(existing.prUrl(), existing.prNumber());
      }
      log.warn(
          "[GitHubRepoService] Failed to create PR for {}/{}: status {}",
          ownerRepo[0],
          ownerRepo[1],
          response.statusCode());
      return PullRequestCreation.failed("GitHub returned status " + response.statusCode());
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Error creating PR: {}", e.getMessage());
      return PullRequestCreation.failed("transport error contacting GitHub");
    }
  }

  /** Extract the trailing {@code /pull/<n>} number from a PR HTML URL; null when absent. */
  static Integer prNumberFromUrl(String prUrl) {
    if (prUrl == null) return null;
    int idx = prUrl.lastIndexOf("/pull/");
    if (idx < 0) return null;
    String tail = prUrl.substring(idx + "/pull/".length()).trim();
    int end = 0;
    while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
    if (end == 0) return null;
    try {
      return Integer.valueOf(tail.substring(0, end));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * RR-146 / RR-380 — result of asking GitHub whether an OPEN pull request currently exists for a
   * head branch. {@code known=false} means the question could not be answered (no credential,
   * transport error, unexpected status) and callers must fail closed rather than assume "none".
   */
  public record OpenPullRequestLookup(
      boolean known,
      boolean present,
      String prUrl,
      Integer prNumber,
      String baseBranch,
      String headSha) {

    public static OpenPullRequestLookup unknown() {
      return new OpenPullRequestLookup(false, false, null, null, null, null);
    }

    public static OpenPullRequestLookup none() {
      return new OpenPullRequestLookup(true, false, null, null, null, null);
    }

    public static OpenPullRequestLookup found(String prUrl, Integer prNumber) {
      return new OpenPullRequestLookup(true, true, prUrl, prNumber, null, null);
    }

    public static OpenPullRequestLookup found(
        String prUrl, Integer prNumber, String baseBranch, String headSha) {
      return new OpenPullRequestLookup(true, true, prUrl, prNumber, baseBranch, headSha);
    }

    /** True only when GitHub positively answered that no open PR references the branch. */
    public boolean provenAbsent() {
      return known && !present;
    }
  }

  /**
   * Look up the OPEN pull request whose head is {@code branch} in {@code repoFullName}. Bounded
   * timeouts; never throws.
   */
  public OpenPullRequestLookup findOpenPullRequestForHead(
      String repoFullName, String branch, String overridePat) {
    if (overridePat == null || overridePat.isBlank()) return OpenPullRequestLookup.unknown();
    if (repoFullName == null || !repoFullName.contains("/")) return OpenPullRequestLookup.unknown();
    if (branch == null || branch.isBlank()) return OpenPullRequestLookup.unknown();
    String owner = repoFullName.substring(0, repoFullName.indexOf('/'));
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/pulls?state=open&per_page=100&head="
                          + URLEncoder.encode(owner + ":" + branch, StandardCharsets.UTF_8)))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + overridePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn(
            "[GitHubRepoService] open-PR lookup {} head={} status {}",
            repoFullName,
            branch,
            response.statusCode());
        return OpenPullRequestLookup.unknown();
      }
      return parseOpenPullRequestLookup(repoFullName, branch, response.body());
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] open-PR lookup {} head={} failed: {}",
          repoFullName,
          branch,
          e.getMessage());
      return OpenPullRequestLookup.unknown();
    }
  }

  static OpenPullRequestLookup parseOpenPullRequestLookup(
      String repoFullName, String branch, String responseBody) {
    try {
      JsonNode array = JSON_MAPPER.readTree(responseBody);
      if (!array.isArray()) return OpenPullRequestLookup.unknown();
      OpenPullRequestLookup match = null;
      for (JsonNode pr : array) {
        // GitHub's head filter is prefix-tolerant; exact ref equality is mandatory.
        if (!branch.equals(pr.path("head").path("ref").asText(""))) continue;
        String prUrl = pr.path("html_url").asText(null);
        Integer prNumber = pr.hasNonNull("number") ? pr.path("number").asInt() : null;
        String baseBranch = pr.path("base").path("ref").asText(null);
        String headSha = pr.path("head").path("sha").asText(null);
        if (prUrl == null
            || prUrl.isBlank()
            || prNumber == null
            || prNumber <= 0
            || baseBranch == null
            || baseBranch.isBlank()
            || headSha == null
            || !headSha.matches("[0-9a-fA-F]{40}")) {
          log.warn(
              "[GitHubRepoService] malformed open-PR lookup {} head={} omitted exact identity",
              repoFullName,
              branch);
          return OpenPullRequestLookup.unknown();
        }
        OpenPullRequestLookup candidate =
            OpenPullRequestLookup.found(prUrl, prNumber, baseBranch, headSha);
        if (match != null) {
          log.warn(
              "[GitHubRepoService] ambiguous open-PR lookup {} head={} matched multiple PRs",
              repoFullName,
              branch);
          return OpenPullRequestLookup.unknown();
        }
        match = candidate;
      }
      return match == null ? OpenPullRequestLookup.none() : match;
    } catch (Exception malformed) {
      return OpenPullRequestLookup.unknown();
    }
  }

  /**
   * RR-380 — current state of a remote branch ref. {@code known=false} means GitHub did not answer,
   * so a caller about to delete must abort rather than proceed on an assumption.
   */
  public record BranchRefState(boolean known, boolean present, String sha) {

    public static BranchRefState unknown() {
      return new BranchRefState(false, false, null);
    }

    public static BranchRefState absent() {
      return new BranchRefState(true, false, null);
    }

    public static BranchRefState at(String sha) {
      return new BranchRefState(true, true, sha);
    }
  }

  /**
   * Read the exact current SHA of {@code refs/heads/<branch>} in {@code repoFullName}. Used as the
   * freshness check immediately before an irreversible branch deletion.
   */
  public BranchRefState getBranchRef(String repoFullName, String branch, String overridePat) {
    if (overridePat == null || overridePat.isBlank()) return BranchRefState.unknown();
    if (repoFullName == null || !repoFullName.contains("/")) return BranchRefState.unknown();
    if (branch == null || branch.isBlank()) return BranchRefState.unknown();
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "https://api.github.com/repos/"
                          + repoFullName
                          + "/git/ref/heads/"
                          + URLEncoder.encode(branch, StandardCharsets.UTF_8).replace("%2F", "/")))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + overridePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) return BranchRefState.absent();
      if (response.statusCode() != 200) {
        log.warn(
            "[GitHubRepoService] branch ref {} on {} status {}",
            branch,
            repoFullName,
            response.statusCode());
        return BranchRefState.unknown();
      }
      JsonNode node = JSON_MAPPER.readTree(response.body());
      String sha = node.path("object").path("sha").asText("");
      return sha.isBlank() ? BranchRefState.unknown() : BranchRefState.at(sha);
    } catch (Exception e) {
      log.warn(
          "[GitHubRepoService] branch ref {} on {} failed: {}",
          branch,
          repoFullName,
          e.getMessage());
      return BranchRefState.unknown();
    }
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
  static List<String> parseTreePaths(String json) {
    List<String> paths = new ArrayList<>();
    try {
      JsonNode tree = new ObjectMapper().readTree(json).path("tree");
      if (!tree.isArray()) return List.of();
      for (JsonNode entry : tree) {
        String type = entry.path("type").asText("");
        String path = entry.path("path").asText("");
        if ("blob".equals(type) && !path.isBlank() && !isGitMetadataPath(path)) {
          paths.add(path);
        }
      }
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Failed to parse Git Trees response: {}", e.getMessage());
      return List.of();
    }
    paths.sort(String::compareTo);
    return paths;
  }

  private static boolean isGitMetadataPath(String path) {
    return ".git".equals(path) || path.startsWith(".git/");
  }

  /** Lightweight DTO for {@link #listOwnerRepos(String, String)}. */
  public record RemoteRepo(
      String immutableId,
      String fullName,
      String cloneUrl,
      String defaultBranch,
      boolean isPrivate,
      Long sizeKb) {}

  /** Secret-free result that preserves the provider boundary for repository attachment. */
  public record RemoteRepoLookupResult(
      RemoteRepo repository, String errorCode, Integer upstreamStatus, boolean retryable) {
    public static RemoteRepoLookupResult found(RemoteRepo repository) {
      return new RemoteRepoLookupResult(repository, null, 200, false);
    }

    public static RemoteRepoLookupResult failed(
        String errorCode, Integer upstreamStatus, boolean retryable) {
      return new RemoteRepoLookupResult(null, errorCode, upstreamStatus, retryable);
    }

    public boolean successful() {
      return repository != null;
    }
  }

  /** Reads canonical repository metadata, including GitHub's immutable numeric repository id. */
  public RemoteRepo getRemoteRepo(String fullName, String overridePat) {
    return getRemoteRepoResult(fullName, overridePat).repository();
  }

  /**
   * Reads canonical repository metadata without erasing GitHub's safe status category.
   *
   * <p>The legacy nullable wrapper remains for older readiness callers. New admission paths use
   * this result so an inaccessible private repository is not mislabeled as an ExecuteSpec outage.
   */
  public RemoteRepoLookupResult getRemoteRepoResult(String fullName, String overridePat) {
    if (overridePat == null || overridePat.isBlank() || fullName == null || fullName.isBlank()) {
      return RemoteRepoLookupResult.failed("GITHUB_REPOSITORY_REQUEST_INVALID", 400, false);
    }
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://api.github.com/repos/" + fullName.trim()))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + overridePat)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return remoteRepoFailure(response.statusCode());
      JsonNode node = new ObjectMapper().readTree(response.body());
      String canonical = node.path("full_name").asText(null);
      if (canonical == null
          || canonical.isBlank()
          || !node.path("id").isIntegralNumber()
          || node.path("id").asLong() <= 0) {
        return RemoteRepoLookupResult.failed("GITHUB_INVALID_RESPONSE", 502, true);
      }
      return RemoteRepoLookupResult.found(
          new RemoteRepo(
              Long.toString(node.path("id").asLong()),
              canonical,
              node.path("clone_url").asText("https://github.com/" + canonical + ".git"),
              node.path("default_branch").asText("main"),
              node.path("private").asBoolean(false),
              node.path("size").isNumber() ? node.path("size").asLong() : null));
    } catch (Exception ex) {
      log.warn(
          "[GitHubRepoService] getRemoteRepo failed for {}: {}",
          fullName,
          ex.getClass().getSimpleName());
      return RemoteRepoLookupResult.failed("GITHUB_UNAVAILABLE", null, true);
    }
  }

  static RemoteRepoLookupResult remoteRepoFailure(int status) {
    return switch (status) {
      case 401 -> RemoteRepoLookupResult.failed("GITHUB_CREDENTIAL_REJECTED", 401, false);
      case 403 -> RemoteRepoLookupResult.failed("GITHUB_REPOSITORY_ACCESS_DENIED", 403, false);
      case 404 ->
          RemoteRepoLookupResult.failed("GITHUB_REPOSITORY_NOT_FOUND_OR_INACCESSIBLE", 404, false);
      case 429 -> RemoteRepoLookupResult.failed("GITHUB_RATE_LIMITED", 429, true);
      default ->
          status >= 500
              ? RemoteRepoLookupResult.failed("GITHUB_UNAVAILABLE", status, true)
              : RemoteRepoLookupResult.failed("GITHUB_REPOSITORY_UNREADABLE", status, false);
    };
  }

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
                  Long.toString(node.path("id").asLong()),
                  full,
                  node.path("clone_url").asText("https://github.com/" + full + ".git"),
                  node.path("default_branch").asText("main"),
                  node.path("private").asBoolean(false),
                  node.path("size").isNumber() ? node.path("size").asLong() : null));
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
  private RepoFileResult decodeGitHubContent(String json) {
    int contentIdx = json.indexOf("\"content\":");
    if (contentIdx < 0) return RepoFileResult.failed("GITHUB_CONTENTS_NO_CONTENT_FIELD", 200);

    int start = json.indexOf("\"", contentIdx + 10) + 1;
    int end = json.indexOf("\"", start);
    if (start <= 0 || end < start) {
      return RepoFileResult.failed("GITHUB_CONTENTS_UNREADABLE_BODY", 200);
    }
    String base64 = json.substring(start, end).replace("\\n", "").replace("\n", "").trim();

    try {
      byte[] decoded = Base64.getDecoder().decode(base64);
      return RepoFileResult.success(new String(decoded, StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.warn("[GitHubRepoService] Failed to decode file content: {}", e.getMessage());
      return RepoFileResult.failed("GITHUB_CONTENTS_DECODE_FAILED", 200);
    }
  }
}
