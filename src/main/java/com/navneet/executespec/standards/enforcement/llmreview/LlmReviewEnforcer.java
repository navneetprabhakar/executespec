package com.navneet.executespec.standards.enforcement.llmreview;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.standards.EffectiveStandardsResolver;
import com.navneet.executespec.standards.model.Standard;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.6 — composes the review-prompt block injected into the reviewer agent's system prompt + parses
 * the reviewer's JSON output back into {@link LlmReviewViolation} records.
 *
 * <p>This enforcer doesn't make the LLM call itself — it only owns the prompt-fragment shape + the
 * response-parsing shape. The actual reviewer-side integration happens in E3.10.
 *
 * <p>Reviewer is asked to emit:
 *
 * <pre>{
 *   "standardsReviewed": [
 *     {"standardId": "no-pii-in-logs",
 *      "verdict": "VIOLATION" | "PASS",
 *      "details": "..." }
 *   ]
 * }</pre>
 */
@Component
@Slf4j
public class LlmReviewEnforcer {

  private final ObjectMapper json;

  public LlmReviewEnforcer(ObjectMapper json) {
    this.json = json;
  }

  /**
   * Build the prompt fragment listing every {@code llm-review} standard applicable to this run.
   * Empty string when nothing applies — the reviewer prompt slot {@code {{LLM_REVIEW_STANDARDS}}}
   * is then a no-op.
   */
  public String composePromptBlock(List<EffectiveStandardsResolver.Effective> effective) {
    List<EffectiveStandardsResolver.Effective> applicable = new ArrayList<>();
    for (EffectiveStandardsResolver.Effective e : effective) {
      if (e.standard().enforcement() instanceof Standard.LlmReviewEnforcement) {
        applicable.add(e);
      }
    }
    if (applicable.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    sb.append("=== LLM_REVIEW_STANDARDS ===\n");
    sb.append("For each standard below, evaluate the diff and emit a JSON object:\n");
    sb.append("  { \"standardsReviewed\": [\n");
    sb.append(
        "    { \"standardId\": \"<id>\", \"verdict\": \"VIOLATION\"|\"PASS\", \"details\": \"...\""
            + " }\n");
    sb.append("  ] }\n\n");
    for (EffectiveStandardsResolver.Effective e : applicable) {
      Standard s = e.standard();
      Standard.LlmReviewEnforcement lre = (Standard.LlmReviewEnforcement) s.enforcement();
      sb.append("--- standard: ")
          .append(s.id())
          .append(" (severity=")
          .append(s.severity())
          .append(") ---\n");
      sb.append("description: ").append(s.description()).append("\n");
      sb.append("review_prompt: ").append(lre.reviewPrompt()).append("\n");
      if (s.rationale() != null && !s.rationale().isBlank()) {
        sb.append("rationale: ").append(s.rationale()).append("\n");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  /**
   * Parse the reviewer's structured response into per-standard violations. Robust against malformed
   * JSON / missing fields — returns an empty list rather than throwing so a single bad reviewer
   * turn doesn't fail the whole run.
   */
  public List<LlmReviewViolation> parseReviewResult(String reviewerOutput) {
    if (reviewerOutput == null || reviewerOutput.isBlank()) return List.of();
    // Reviewer may wrap the JSON in surrounding prose. Find the first
    // '{' and last '}' and parse the slice.
    int start = reviewerOutput.indexOf('{');
    int end = reviewerOutput.lastIndexOf('}');
    if (start < 0 || end <= start) return List.of();
    String slice = reviewerOutput.substring(start, end + 1);
    try {
      JsonNode root = json.readTree(slice);
      JsonNode arr = root.path("standardsReviewed");
      if (!arr.isArray()) return List.of();
      List<LlmReviewViolation> out = new ArrayList<>();
      for (JsonNode entry : arr) {
        String standardId = entry.path("standardId").asText("");
        String verdict = entry.path("verdict").asText("PASS").toUpperCase();
        String details = entry.path("details").asText("");
        if (!"VIOLATION".equals(verdict)) continue;
        if (standardId.isBlank()) continue;
        out.add(new LlmReviewViolation(standardId, details));
      }
      return out;
    } catch (Exception ex) {
      log.warn("[LlmReviewEnforcer] parse failed: {}", ex.getMessage());
      return List.of();
    }
  }

  /**
   * @param standardId the YAML id of the violated standard
   * @param details reviewer's explanation
   */
  public record LlmReviewViolation(String standardId, String details) {}
}
