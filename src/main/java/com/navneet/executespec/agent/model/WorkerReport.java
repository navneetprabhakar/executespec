package com.navneet.executespec.agent.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class WorkerReport {

  private String taskId;
  private String status; // complete | blocked | partial
  private List<String> filesCreated;
  private List<String> filesModified;
  private List<String> filesRead;
  private String selfReview;
  private String blockers;
  private String notesForReviewer;
  private String summary; // Worker's completion or failure summary
  private int inputTokens;
  private int outputTokens;
  private int cacheReadTokens; // Anthropic cache_read_input_tokens  (billed at 0.10x input rate)
  private int
      cacheWriteTokens; // Anthropic cache_creation_input_tokens (billed at 1.25x input rate)

  /**
   * AI provider that actually served this task (e.g. "anthropic", "gemini", "openai"). May differ
   * from initial assignment due to failover.
   */
  private String provider;

  /**
   * Actual model ID used (e.g. "claude-haiku-4-5-20251001"). May differ from assignedModel due to
   * escalation.
   */
  private String model;

  /** Whether the task hit output token truncation at least once. */
  private boolean wasTruncated;

  /** Max output tokens configured for this task (from TokenBudgetMonitor). */
  private int maxOutputTokens;

  /** Total output tokens actually produced. */
  private int actualOutputTokens;

  /** Number of agentic loop iterations. */
  private int iterationCount;

  /** Convenience factory for tasks that hit a token/iteration limit. */
  public static WorkerReport partial(
      String taskId, String reason, int inputTokens, int outputTokens) {
    return WorkerReport.builder()
        .taskId(taskId)
        .status("partial")
        .filesCreated(java.util.List.of())
        .filesModified(java.util.List.of())
        .filesRead(java.util.List.of())
        .selfReview(reason)
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .build();
  }

  /** Convenience factory for tasks that are blocked on an external dependency. */
  public static WorkerReport blocked(
      String taskId, String blocker, int inputTokens, int outputTokens) {
    return WorkerReport.builder()
        .taskId(taskId)
        .status("blocked")
        .filesCreated(java.util.List.of())
        .filesModified(java.util.List.of())
        .filesRead(java.util.List.of())
        .blockers(blocker)
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .build();
  }
}
