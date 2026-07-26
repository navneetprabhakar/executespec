package com.navneet.executespec.project.pr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.project.service.ProjectSourceFreshnessInvalidator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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
  private final ProjectSourceFreshnessInvalidator sourceFreshnessInvalidator;

  /**
   * 2026-05-07 — Phase P1: ingest {@code pull_request_review_comment} webhook events into the inbox
   * so the v3 RunDetail surface can show "your reviewer left a comment" without polling.
   * {@code @Autowired(required = false)} because legacy environments may not have wired this
   * service yet.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.navneet.executespec.prreview.inbox.PrCommentInboxService prCommentInboxService;

  @Value("${github.webhook.secret:}")
  private String webhookSecret;

  /**
   * RR-193 — hard ceiling on the webhook request body ExecuteSpec will buffer before doing any HMAC
   * / JSON / DB work. The subscribed event families (pull_request, check_run,
   * pull_request_review_comment) are metadata payloads well under this bound, so an oversized body
   * is an abuse / misconfiguration signal, not a legitimate delivery. Configurable via {@code
   * github.webhook.max-body-bytes}; default 1 MiB.
   */
  @Value("${github.webhook.max-body-bytes:1048576}")
  private int maxBodyBytes = 1_048_576;

  @PostMapping("/webhooks/github")
  public ResponseEntity<Void> receive(
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
      @RequestHeader(value = "X-GitHub-Event", required = false) String event,
      HttpServletRequest request) {

    if (webhookSecret == null || webhookSecret.isBlank()) {
      log.warn("GitHub webhook received but secret not configured — rejecting");
      return ResponseEntity.status(401).build();
    }

    // RR-193 — reject an oversized delivery before allocating/hashing/parsing it. Check the
    // declared
    // Content-Length first (cheap early-out), then enforce the same bound while reading so a lying
    // or
    // absent Content-Length cannot bypass it.
    long declaredLength = request.getContentLengthLong();
    if (declaredLength > maxBodyBytes) {
      log.warn("GitHub webhook body too large (declared {} bytes) — rejecting", declaredLength);
      return ResponseEntity.status(413).build();
    }
    byte[] body;
    try {
      body = readCapped(request.getInputStream(), maxBodyBytes);
    } catch (BodyTooLargeException tooLarge) {
      log.warn("GitHub webhook body exceeded {} bytes while streaming — rejecting", maxBodyBytes);
      return ResponseEntity.status(413).build();
    } catch (IOException io) {
      log.warn("GitHub webhook body read failed: {}", io.getMessage());
      return ResponseEntity.badRequest().build();
    }

    if (!signatureValid(signature, body)) {
      log.warn("GitHub webhook signature mismatch");
      return ResponseEntity.status(401).build();
    }
    if (event == null) return ResponseEntity.noContent().build();
    String bodyText = new String(body, StandardCharsets.UTF_8);

    // v2.5.12 — distinguish parse errors (400) from unexpected errors (500).
    // Previously every exception surfaced as 400, masking real backend bugs
    // as "bad payload" and inviting GitHub to retry until the bug was fixed.
    JsonNode root;
    try {
      root = objectMapper.readTree(bodyText);
    } catch (com.fasterxml.jackson.core.JsonProcessingException parseEx) {
      log.warn("GitHub webhook JSON parse failed: {}", parseEx.getOriginalMessage());
      return ResponseEntity.badRequest().build();
    }
    try {
      switch (event) {
        case "pull_request" -> handlePullRequest(root);
        case "push" -> handlePush(root);
        case "check_run" -> handleCheckRun(root);
        case "pull_request_review_comment" -> handlePullRequestReviewComment(root);
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
    if (merged) {
      sourceFreshnessInvalidator.invalidateGitHubRepo(repoFullName);
    }
  }

  /** Invalidate exact repo/project freshness keys when a signed push observes HEAD movement. */
  private void handlePush(JsonNode root) {
    String repoFullName = textOrNull(root.path("repository"), "full_name");
    String before = textOrNull(root, "before");
    String after = textOrNull(root, "after");
    if (repoFullName == null || before == null || after == null || before.equals(after)) return;
    sourceFreshnessInvalidator.invalidateGitHubRepo(repoFullName);
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

  /**
   * 2026-05-07 — Phase P1: a reviewer left a line-anchored or PR-level comment on a PR ExecuteSpec
   * opened. Resolve {@code (repo_full_name, pr_number)} to a runId via the existing {@link
   * PullRequestRecord} table and insert into {@code pr_comment_inbox}; the v3 RunDetail surface
   * will pick it up via the {@code PR_COMMENT_RECEIVED} SSE event the service emits.
   *
   * <p>We listen for {@code action=created} only — edits + deletions don't produce actionable "the
   * user said something new" signals (and the dedup constraint on commentId would no-op the edit
   * case anyway).
   *
   * <p>Comments on PRs ExecuteSpec didn't open are silently dropped by the service when the (repo,
   * prNumber) lookup misses.
   */
  private void handlePullRequestReviewComment(JsonNode root) {
    if (prCommentInboxService == null) return; // not wired in this environment
    String action = textOrNull(root, "action");
    if (!"created".equals(action)) return;

    JsonNode comment = root.path("comment");
    JsonNode pr = root.path("pull_request");
    JsonNode repo = root.path("repository");
    String repoFullName = textOrNull(repo, "full_name");
    Integer prNumber = pr.hasNonNull("number") ? pr.get("number").asInt() : null;
    Long commentId = comment.hasNonNull("id") ? comment.get("id").asLong() : null;
    if (repoFullName == null || prNumber == null || commentId == null) return;

    String body = textOrNull(comment, "body");
    if (body == null || body.isBlank()) return;
    String filePath = textOrNull(comment, "path");
    Integer lineNumber =
        comment.hasNonNull("line")
            ? comment.get("line").asInt()
            : (comment.hasNonNull("original_line") ? comment.get("original_line").asInt() : null);
    String author = textOrNull(comment.path("user"), "login");
    String htmlUrl = textOrNull(comment, "html_url");

    // RR-194 — a transient ingest failure must NOT be acknowledged to GitHub as a successful
    // delivery (204). Let it propagate to the receive() handler, which returns 500 so GitHub
    // redelivers; ingest is idempotent (dedup on commentId), so a redelivery cannot double-insert.
    prCommentInboxService.ingest(
        new com.navneet.executespec.prreview.inbox.PrCommentInboxService.IngestRequest(
            repoFullName, prNumber, commentId, filePath, lineNumber, body, author, htmlUrl));
  }

  /**
   * RR-193 — read at most {@code cap} bytes from the request stream. GitHub signs the exact raw
   * bytes, so HMAC is computed over what is actually read here. Throws {@link
   * BodyTooLargeException} the moment a delivery would exceed the cap rather than continuing to
   * allocate.
   */
  private static byte[] readCapped(InputStream in, int cap) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(cap, 8192));
    byte[] chunk = new byte[8192];
    int total = 0;
    int read;
    while ((read = in.read(chunk)) != -1) {
      total += read;
      if (total > cap) throw new BodyTooLargeException();
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  private static final class BodyTooLargeException extends IOException {}

  private boolean signatureValid(String signature, byte[] body) {
    if (signature == null || !signature.startsWith("sha256=")) return false;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = mac.doFinal(body);
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
