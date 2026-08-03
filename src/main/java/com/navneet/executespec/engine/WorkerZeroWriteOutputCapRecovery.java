package com.navneet.executespec.engine;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.tool.CompleteTaskTool;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import lombok.extern.slf4j.Slf4j;

/** Tighten-only policy for the worker's one-shot zero-write provider-output-cap recovery. */
@Slf4j
final class WorkerZeroWriteOutputCapRecovery {

  record ToolRefusal(String message, String logReason) {}

  static final String STEP_ID = "worker.output-cap-recovery";
  private static final int MIN_OUTPUT_TOKENS = 8_192;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final Set<String> MUTATION_TOOLS = Set.of("writeFile", "editFile");

  private WorkerZeroWriteOutputCapRecovery() {}

  static UserMessage instruction() {
    return new UserMessage(
        "OUTPUT LIMIT RECOVERY. Your previous response reached the provider output cap before"
            + " a complete mutation could be accepted. Emit writeFile or editFile tool calls"
            + " now for EVERY requested production and maintained-test file; a production-only"
            + " mutation is incomplete when the request requires tests. Do not repeat code as"
            + " assistant text, investigate further, or call completeTask in this recovery turn."
            + " The normal worker loop will resume after these mutations so you can verify the"
            + " current tree and complete the task there.");
  }

  static int outputTokens(Integer normalMaximum) {
    int normal = normalMaximum == null ? 0 : normalMaximum;
    return Math.min(MAX_OUTPUT_TOKENS, Math.max(MIN_OUTPUT_TOKENS, normal));
  }

  static ChatResponse call(
      WorkerTaskRequest request,
      List<Message> history,
      List<ToolCallback> tools,
      LlmRouter router,
      RunCostTracker costTracker,
      OptionalInt timeout) {
    List<ToolCallback> recoveryTools =
        tools.stream()
            .filter(
                tool -> {
                  String name = tool.getToolDefinition().name();
                  return MUTATION_TOOLS.contains(name) || CompleteTaskTool.NAME.equals(name);
                })
            .toList();
    if (recoveryTools.stream()
        .noneMatch(tool -> MUTATION_TOOLS.contains(tool.getToolDefinition().name()))) {
      log.warn("WorkerAgent output-cap recovery refused: no mutation callback is bound");
      return null;
    }
    if (WorkerLiveBudgetGuard.taskViolation(request, costTracker, request.workerStartedAtEpochMs())
        != null) {
      log.info("WorkerAgent output-cap recovery refused: live task budget exhausted");
      return null;
    }
    if (timeout.isEmpty()) {
      log.info("WorkerAgent output-cap recovery refused: absolute deadline reached");
      return null;
    }
    try {
      return router.call(
          WorkerLlmRequestFactory.create(
              request,
              history,
              "WorkerAgent",
              STEP_ID,
              0.0,
              outputTokens(request.maxTokensPerCall()),
              recoveryTools,
              "required",
              AbsoluteDeadlineBudget.readTimeoutOverride(timeout)));
    } catch (Exception failure) {
      log.warn("WorkerAgent output-cap recovery failed: {}", failure.getMessage());
      return null;
    }
  }

  static int followUpIterationLimit(int configuredLimit, int recoveryIteration) {
    long bounded = Math.min((long) configuredLimit + 2L, (long) recoveryIteration + 2L);
    return (int) Math.min(Integer.MAX_VALUE, Math.max(configuredLimit, bounded));
  }

  static ToolRefusal refusal(String toolName) {
    if (CompleteTaskTool.NAME.equals(toolName)) {
      return new ToolRefusal(
          "completeTask refused during output-limit mutation recovery. Finish every requested"
              + " source and maintained-test edit, verify the current tree on a normal worker"
              + " turn, then call completeTask.",
          "output-cap recovery must re-enter worker loop");
    }
    return MUTATION_TOOLS.contains(toolName)
        ? null
        : new ToolRefusal(
            "output-limit recovery permits mutation tools only",
            "outside output-cap recovery authority");
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
