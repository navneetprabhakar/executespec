package com.navneet.executespec.engine;

import java.util.Locale;

import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

/** Tighten-only policy for the worker's one-shot zero-write provider-output-cap recovery. */
final class WorkerZeroWriteOutputCapRecovery {

  static final String STEP_ID = "worker.output-cap-recovery";
  static final String SUMMARY = "output-cap recovery: mutation produced; verification required";
  private static final int MIN_OUTPUT_TOKENS = 8_192;
  private static final int MAX_OUTPUT_TOKENS = 16_384;

  private WorkerZeroWriteOutputCapRecovery() {}

  static UserMessage instruction() {
    return new UserMessage(
        "OUTPUT LIMIT RECOVERY. Your previous response reached the provider output cap before"
            + " a complete mutation could be accepted. Emit writeFile or editFile tool calls"
            + " now for the requested files; do not repeat code as assistant text and do not"
            + " investigate further. If no valid mutation is possible, call completeTask with"
            + " a concise explanation.");
  }

  static int outputTokens(Integer normalMaximum) {
    int normal = normalMaximum == null ? 0 : normalMaximum;
    return Math.min(MAX_OUTPUT_TOKENS, Math.max(MIN_OUTPUT_TOKENS, normal));
  }

  static boolean reached(ChatResponse response) {
    if (response == null || response.getResults() == null) return false;
    return response.getResults().stream()
        .filter(generation -> generation != null && generation.getMetadata() != null)
        .map(generation -> generation.getMetadata().getFinishReason())
        .filter(java.util.Objects::nonNull)
        .map(reason -> reason.toString().trim().toLowerCase(Locale.ROOT))
        .anyMatch(
            reason ->
                reason.equals("length")
                    || reason.equals("max_tokens")
                    || reason.equals("max_output_tokens"));
  }
}
