package com.navneet.executespec.engine;

import java.util.List;

import com.navneet.executespec.agent.LlmRequest;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

/** Builds worker calls with identical route, timeout, tool-authority, and dollar-cap evidence. */
final class WorkerLlmRequestFactory {

  private WorkerLlmRequestFactory() {}

  static LlmRequest create(
      WorkerTaskRequest request,
      List<Message> messages,
      String agentRole,
      String stepId,
      Double temperature,
      Integer maxTokens,
      List<ToolCallback> tools,
      String toolChoice,
      Integer readTimeoutSeconds) {
    return LlmRequest.builder()
        .provider(request.provider())
        .model(request.model())
        .messages(messages)
        .context(request.context())
        .agentRole(request.effectiveAgentRole(agentRole))
        .stepId(request.effectiveStepId(stepId))
        .temperature(temperature)
        .maxTokens(maxTokens)
        .strictProvider(Boolean.TRUE.equals(request.strictProvider()))
        .runHardCapUsd(activeCostCap(request.runHardCapUsd()))
        .taskCostCapUsd(activeCostCap(request.dollarCap()))
        .toolsOverride(tools)
        .toolChoice(toolChoice)
        .readTimeoutSeconds(readTimeoutSeconds)
        .build();
  }

  private static Double activeCostCap(Double cap) {
    return cap == null || cap <= 0.0 ? null : cap;
  }
}
