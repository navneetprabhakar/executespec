package com.navneet.executespec.agent;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class CoherencePlannerProtocol {

  static ChatResponse call(
      LlmRouter router,
      List<Message> messages,
      ResolvedModel resolved,
      ExecutionContext context,
      ToolCallback patchPlan,
      String stepId,
      int maxTokens) {
    return router.call(
        LlmRequest.builder()
            .provider(resolved.providerName())
            .model(resolved.getModel())
            .messages(List.copyOf(messages))
            .context(context)
            .agentRole("CoherencePlannerAgent")
            .stepId(stepId)
            .temperature(0.0)
            .maxTokens(maxTokens)
            .toolChoice("required")
            .toolsOverride(List.of(patchPlan))
            .build());
  }

  static CoherencePlannerAgent.PatchResult repair(
      LlmRouter router,
      Plan plan,
      List<Message> messages,
      ResolvedModel resolved,
      ExecutionContext context,
      ToolCallback patchPlan,
      String failure,
      int maxTokens,
      java.util.function.BiFunction<Plan, String, CoherencePlannerAgent.PatchResult> applyPatches) {
    messages.add(
        new UserMessage(
            "PROTOCOL REPAIR: the prior patchPlan payload was absent, malformed, or truncated ("
                + truncate(failure, 160)
                + "). Call patchPlan exactly once with complete valid JSON. Emit only material"
                + " conflicts/patches, at most 12 fatal conflicts and 12 patches; keep each reason"
                + " under 120 characters. Use empty arrays when the enriched plan is already"
                + " coherent. Do not add commentary."));
    try {
      ChatResponse response =
          call(
              router,
              messages,
              resolved,
              context,
              patchPlan,
              "planner.coherence.protocol-repair",
              maxTokens);
      AssistantMessage.ToolCall repairedCall = findPatchCall(response);
      if (repairedCall == null) return failed(plan, "patchPlan tool was not called after repair");
      return applyPatches.apply(plan, repairedCall.arguments());
    } catch (Exception e) {
      log.warn("CoherencePlanner protocol repair failed: {}", e.getMessage());
      return failed(plan, "protocol repair LLM call failed");
    }
  }

  static AssistantMessage.ToolCall findPatchCall(ChatResponse response) {
    if (response == null || response.getResults() == null) return null;
    for (var generation : response.getResults()) {
      if (generation.getOutput().getToolCalls() == null) continue;
      for (var toolCall : generation.getOutput().getToolCalls()) {
        if ("patchPlan".equals(toolCall.name())) return toolCall;
      }
    }
    return null;
  }

  static boolean isRepairable(CoherencePlannerAgent.PatchResult result) {
    if (result == null || result.validated() || result.validationFailure() == null) return false;
    return result.validationFailure().contains("invalid JSON")
        || result.validationFailure().contains("omitted the patches array")
        || result.validationFailure().contains("tool was not called");
  }

  static CoherencePlannerAgent.PatchResult fatalConflictFailure(Plan plan, JsonNode conflictsNode) {
    if (!conflictsNode.isArray() || conflictsNode.isEmpty()) return null;
    List<String> conflicts = new java.util.ArrayList<>();
    conflictsNode.forEach(
        conflict -> {
          String summary = conflict.asText("").trim();
          if (!summary.isEmpty()) conflicts.add(truncate(summary, 240));
        });
    if (conflicts.isEmpty()) return null;
    log.warn("CoherencePlanner rejected plan with {} fatal contract conflicts", conflicts.size());
    return failed(plan, "fatal contract conflicts: " + String.join("; ", conflicts));
  }

  private static CoherencePlannerAgent.PatchResult failed(Plan plan, String reason) {
    return new CoherencePlannerAgent.PatchResult(plan, 0, List.of(), false, reason);
  }

  private static String truncate(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max - 1) + "…";
  }
}
