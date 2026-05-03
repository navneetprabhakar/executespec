package com.navneet.executespec.project.pr;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Receives GitHub pull-request webhooks. Verifies {@code X-Hub-Signature-256} before touching
 * state; ignores non-PR events and untracked PRs.
 *
 * <p>Configure with {@code github.webhook.secret} (or env {@code GITHUB_WEBHOOK_SECRET}). When the
 * secret is blank the controller rejects every request as unauthenticated.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookController {

  private final PullRequestRecordService prService;
  private final CiFailureAutoFixService ciAutoFixService;
  private final ObjectMapper objectMapper;

  @Value("${github.webhook.secret:}")
  private String webhookSecret;

  @PostMapping("/webhooks/github")
  public ResponseEntity<Void> receive(
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestHeader(value = "X-GitHub-Event", required = false) String event,
      @RequestBody String body) {

    if (webhookSecret == null || webhookSecret.isBlank()) {
      log.warn("GitHub webhook received but secret not configured — rejecting");
      return ResponseEntity.status(401).build();
    }
    if (!signatureValid(signature, body)) {
      log.warn("GitHub webhook signature mismatch");
      return ResponseEntity.status(401).build();
    }
    if (event == null) return ResponseEntity.noContent().build();

    // v2.5.12 — distinguish parse errors (400) from unexpected errors (500).
    // Previously every exception surfaced as 400, masking real backend bugs
    // as "bad payload" and inviting GitHub to retry until the bug was fixed.
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (com.fasterxml.jackson.core.JsonProcessingException parseEx) {
      log.warn("GitHub webhook JSON parse failed: {}", parseEx.getOriginalMessage());
      return ResponseEntity.badRequest().build();
    }
    try {
      switch (event) {
        case "pull_request" -> handlePullRequest(root);
        case "check_run" -> handleCheckRun(root);
        default -> {
          // Other event types (push, issues, etc.) are not subscribed to in our app config but
          // may arrive if a maintainer enabled "Send everything." Silently drop.
        }
      }
      return ResponseEntity.noContent().build();
    } catch (Exception e) {
      log.error("GitHub webhook processing failed unexpectedly: {}", e.getMessage(), e);
      return ResponseEntity.status(500).build();
    }
  }

  /** Existing PR closed/merged handler — kept verbatim from V82. */
  private void handlePullRequest(JsonNode root) {
    String action = textOrNull(root, "action");
    JsonNode pr = root.path("pull_request");
    JsonNode repo = root.path("repository");
    String repoFullName = textOrNull(repo, "full_name");
    Integer prNumber = pr.hasNonNull("number") ? pr.get("number").asInt() : null;
    if (repoFullName == null || prNumber == null) return;

    if (!"closed".equals(action)) return;

    boolean merged = pr.path("merged").asBoolean(false);
    Instant ts =
        merged && pr.hasNonNull("merged_at")
            ? Instant.parse(pr.get("merged_at").asText())
            : (pr.hasNonNull("closed_at")
                ? Instant.parse(pr.get("closed_at").asText())
                : Instant.now());
    prService.applyWebhookUpdate(repoFullName, prNumber, merged, ts);
  }

  /**
   * 2026-05-02 — auto-fix re-run dispatch on CI failure. Filters to {@code action=completed} +
   * {@code conclusion=failure} (or {@code timed_out}/{@code cancelled}). Other check-run events
   * (success, neutral, in_progress, queued) are no-ops. Branch is read from {@code
   * check_run.check_suite.head_branch}; failure summary from {@code check_run.output.summary} or
   * {@code check_run.output.text}; deep-link from {@code check_run.html_url}.
   *
   * <p>The actual same-branch resubmit logic lives in {@link CiFailureAutoFixService}; the webhook
   * only filters and unpacks.
   */
  private void handleCheckRun(JsonNode root) {
    String action = textOrNull(root, "action");
    if (!"completed".equals(action)) return;
    JsonNode cr = root.path("check_run");
    String conclusion = textOrNull(cr, "conclusion");
    if (conclusion == null) return;
    if (!"failure".equals(conclusion)
        && !"timed_out".equals(conclusion)
        && !"cancelled".equals(conclusion)) {
      return;
    }
    String repoFullName = textOrNull(root.path("repository"), "full_name");
    String headBranch = textOrNull(cr.path("check_suite"), "head_branch");
    String htmlUrl = textOrNull(cr, "html_url");
    JsonNode output = cr.path("output");
    String summary = textOrNull(output, "summary");
    if (summary == null || summary.isBlank()) summary = textOrNull(output, "text");
    if (summary == null) summary = "";
    summary = summary + (summary.isBlank() ? "" : "\n\n") + "Conclusion: " + conclusion;

    var result = ciAutoFixService.onCheckRunFailed(repoFullName, headBranch, summary, htmlUrl);
    if (result.dispatched()) {
      log.info(
          "[ci-auto-fix] dispatched run {} for {} (branch {}) — auto-fix attempt active",
          result.newRunId(),
          repoFullName,
          headBranch);
    } else {
      log.debug(
          "[ci-auto-fix] skipped {} (branch {}): {}", repoFullName, headBranch, result.reason());
    }
  }

  private boolean signatureValid(String signature, String body) {
    if (signature == null || !signature.startsWith("sha256=")) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) hex.append(String.format("%02x", b));
      String expected = "sha256=" + hex;
      return constantTimeEquals(expected, signature);
    } catch (Exception e) {
      log.warn("Webhook HMAC compute failed: {}", e.getMessage());
      return false;
    }
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }

  private static String textOrNull(JsonNode n, String field) {
    JsonNode v = n == null ? null : n.get(field);
    return v != null && !v.isNull() ? v.asText() : null;
  }
}
