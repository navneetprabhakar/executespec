package com.navneet.executespec.engine;

import java.util.List;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.tool.CompleteTaskTool;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import lombok.extern.slf4j.Slf4j;

/** Executes one bounded salvage call without expanding the already-ceilinged worker loop. */
@Slf4j
final class WorkerSalvageAttempt {

  record Config(
      String toolChoice,
      Integer maxTokens,
      int resultIterations,
      String stepId,
      String completionSummary) {}

  @FunctionalInterface
  interface ToolDispatch {
    String dispatch(AssistantMessage.ToolCall call);
  }

  @FunctionalInterface
  interface TerminalRecorder {
    void record(WorkerResult.Outcome outcome, int iterations);
  }

  private WorkerSalvageAttempt() {}

  static WorkerResult execute(
      WorkerTaskRequest request,
      List<Message> history,
      int toolsDispatched,
      List<String> toolLog,
      long startedAt,
      List<ToolCallback> tools,
      Config config,
      LlmRouter router,
      RunCostTracker costTracker,
      OptionalInt timeout,
      ToolDispatch dispatch,
      TerminalRecorder terminal,
      ObjectMapper objectMapper) {
    if (WorkerLiveBudgetGuard.taskViolation(request, costTracker, request.workerStartedAtEpochMs())
        != null) {
      log.info("WorkerAgent SALVAGE refused: live task budget exhausted");
      return null;
    }
    if (timeout.isEmpty()) {
      log.info("WorkerAgent SALVAGE refused: absolute deadline shutdown reserve reached");
      return null;
    }
    ChatResponse response;
    try {
      response =
          router.call(
              WorkerLlmRequestFactory.create(
                  request,
                  history,
                  "WorkerAgent",
                  config.stepId(),
                  0.0,
                  config.maxTokens(),
                  tools,
                  config.toolChoice(),
                  AbsoluteDeadlineBudget.readTimeoutOverride(timeout)));
    } catch (Exception failure) {
      log.warn(
          "WorkerAgent SALVAGE router error (toolChoice={}): {}",
          config.toolChoice(),
          failure.getMessage());
      return null;
    }
    if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
      return null;
    }
    List<AssistantMessage.ToolCall> calls =
        response.getResults().getFirst().getOutput().getToolCalls();
    if (calls == null || calls.isEmpty()) {
      log.info("WorkerAgent SALVAGE no tool calls produced — falling through to iter-limit");
      return null;
    }

    boolean wrote = false;
    for (AssistantMessage.ToolCall call : calls) {
      if (CompleteTaskTool.NAME.equals(call.name())) {
        String summary = "salvage path: " + summary(call.arguments(), objectMapper);
        terminal.record(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED, config.resultIterations());
        return WorkerResult.builder()
            .outcome(WorkerResult.Outcome.ITERATION_LIMIT_EXCEEDED)
            .iterations(config.resultIterations())
            .toolCallsDispatched(toolsDispatched)
            .completionSummary(summary)
            .toolCallLog(toolLog)
            .totalLatencyMs(System.currentTimeMillis() - startedAt)
            .build();
      }
      if (WorkerToolPolicy.isWriteTool(call.name())) {
        String envelope = dispatch.dispatch(call);
        boolean ok = envelope != null && envelope.startsWith("{\"ok\":true");
        toolLog.add("SALVAGE " + call.name() + " -> " + (ok ? "OK" : "ERR"));
        wrote |= ok;
      }
    }
    if (!wrote) return null;
    log.info("WorkerAgent SALVAGE produced an edit — returning COMPLETED (unverified)");
    terminal.record(WorkerResult.Outcome.COMPLETED, config.resultIterations());
    return WorkerResult.builder()
        .outcome(WorkerResult.Outcome.COMPLETED)
        .iterations(config.resultIterations())
        .toolCallsDispatched(toolsDispatched + calls.size())
        .completionSummary(config.completionSummary())
        .toolCallLog(toolLog)
        .totalLatencyMs(System.currentTimeMillis() - startedAt)
        .salvagedUnverified(true)
        .build();
  }

  private static String summary(String arguments, ObjectMapper objectMapper) {
    try {
      return objectMapper
          .readTree(arguments == null ? "{}" : arguments)
          .path("summary")
          .asText("(no summary)");
    } catch (Exception failure) {
      return "(unparseable summary args: " + failure.getMessage() + ")";
    }
  }
}
