package com.navneet.executespec.project.pr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Receives GitHub pull-request webhooks. Verifies {@code X-Hub-Signature-256}
 * before touching state; ignores non-PR events and untracked PRs.
 *
 * <p>Configure with {@code github.webhook.secret} (or env {@code GITHUB_WEBHOOK_SECRET}).
 * When the secret is blank the controller rejects every request as unauthenticated.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookController {

    private final PullRequestRecordService prService;
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
        if (event == null || !"pull_request".equals(event)) {
            return ResponseEntity.noContent().build();
        }

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
            String action = textOrNull(root, "action");
            JsonNode pr = root.path("pull_request");
            JsonNode repo = root.path("repository");
            String repoFullName = textOrNull(repo, "full_name");
            Integer prNumber = pr.hasNonNull("number") ? pr.get("number").asInt() : null;
            if (repoFullName == null || prNumber == null) return ResponseEntity.noContent().build();

            // We only care about closed (merged or not).
            if (!"closed".equals(action)) return ResponseEntity.noContent().build();

            boolean merged = pr.path("merged").asBoolean(false);
            Instant ts = merged && pr.hasNonNull("merged_at")
                    ? Instant.parse(pr.get("merged_at").asText())
                    : (pr.hasNonNull("closed_at") ? Instant.parse(pr.get("closed_at").asText()) : Instant.now());
            prService.applyWebhookUpdate(repoFullName, prNumber, merged, ts);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("GitHub webhook processing failed unexpectedly: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
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
