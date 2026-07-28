package com.navneet.executespec.chat;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.ToolProtocolLeakGuard;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes one bounded, read-only post-run Ask turn.
 *
 * <p>This collaborator owns provider turns, tool dispatch, protocol-leak handling, and forced final
 * synthesis. Run admission, evidence assembly, transcript persistence, and customer-facing error
 * translation remain in {@link ChatService}.
 */
@Slf4j
@RequiredArgsConstructor
final class RunChatTurnExecutor {

  private static final int MAX_TOOL_ROUNDS = 4;

  private final LlmRouter router;

  ChatService.ChatAnswer execute(
      String runId,
      String providerName,
      String modelName,
      List<Message> seed,
      ExecutionContext ctx,
      List<ToolCallback> tools) {
    List<Message> conversation = new ArrayList<>(seed);

    for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
      LlmRequest req =
          LlmRequest.builder()
              .provider(providerName)
              .model(modelName)
              .messages(conversation)
              .context(ctx)
              .agentRole("RunChatAgent")
              .stepId("run-chat.round-" + (round + 1))
              .temperature(0.2)
              .maxTokens(ChatService.MAX_TOKENS_PER_CALL)
              .toolsOverride(tools)
              .build();

      ChatResponse resp = router.call(req);
      StringBuilder turnText = new StringBuilder();
      List<AssistantMessage.ToolCall> calls = new ArrayList<>();
      for (var gen : resp.getResults()) {
        String text = gen.getOutput().getText();
        if (text != null && !text.isEmpty()) {
          if (!turnText.isEmpty()) turnText.append('\n');
          turnText.append(text);
        }
        if (gen.getOutput().getToolCalls() != null) {
          calls.addAll(gen.getOutput().getToolCalls());
        }
      }

      if (responseWasTruncated(resp)) {
        String partial = turnText.toString();
        if (ToolProtocolLeakGuard.containsProtocolMarkup(partial)) {
          log.warn("Run chat suppressed truncated textual tool-protocol markup for run {}", runId);
          partial =
              "The provider reached its output limit before it produced a safe grounded answer. "
                  + "Internal tool protocol was suppressed.";
        }
        return ChatService.ChatAnswer.outputLimited(partial);
      }
      if (calls.isEmpty()) {
        if (!ToolProtocolLeakGuard.containsProtocolMarkup(turnText.toString())) {
          return ChatService.ChatAnswer.complete(turnText.toString());
        }
        log.warn("Run chat suppressed textual tool-protocol markup for run {}", runId);
        conversation.add(new AssistantMessage(turnText.toString()));
        conversation.add(
            new UserMessage(
                "Your previous response exposed internal tool-call markup instead of an answer. "
                    + "Do not call or describe tools. Answer the original question now using only "
                    + "the run metadata and validation evidence already supplied. If that evidence "
                    + "is insufficient, state the exact missing evidence plainly."));
        return synthesizeFinal(runId, providerName, modelName, conversation, ctx);
      }

      conversation.add(
          AssistantMessage.builder().content(turnText.toString()).toolCalls(calls).build());
      List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
      for (AssistantMessage.ToolCall toolCall : calls) {
        ToolCallback callback = findCallback(tools, toolCall.name());
        String envelope;
        if (callback == null) {
          envelope = "{\"ok\":false,\"message\":\"unknown tool '" + toolCall.name() + "'\"}";
          log.warn("Run chat: model called unknown tool '{}' for run {}", toolCall.name(), runId);
        } else {
          try {
            envelope = callback.call(toolCall.arguments());
          } catch (Exception failure) {
            log.warn(
                "Run chat tool '{}' threw {}: {}",
                toolCall.name(),
                failure.getClass().getSimpleName(),
                failure.getMessage());
            envelope = "{\"ok\":false,\"message\":\"" + failure.getClass().getSimpleName() + "\"}";
          }
        }
        toolResponses.add(
            new ToolResponseMessage.ToolResponse(
                toolCall.id() == null ? "tc-" + System.nanoTime() : toolCall.id(),
                toolCall.name(),
                envelope));
      }
      conversation.add(ToolResponseMessage.builder().responses(toolResponses).build());
    }

    log.warn(
        "Run chat hit MAX_TOOL_ROUNDS={} for run {}; forcing synthesis", MAX_TOOL_ROUNDS, runId);
    conversation.add(
        new UserMessage(
            "Tool inspection is complete. Answer the user's original question now using only the"
                + " evidence already collected. Apply the evidence precedence and fact-versus-"
                + "hypothesis rules from the system prompt. Be specific and do not describe future"
                + " tool actions."));
    return synthesizeFinal(runId, providerName, modelName, conversation, ctx);
  }

  private ChatService.ChatAnswer synthesizeFinal(
      String runId,
      String providerName,
      String modelName,
      List<Message> conversation,
      ExecutionContext ctx) {
    LlmRequest synthesis =
        LlmRequest.builder()
            .provider(providerName)
            .model(modelName)
            .messages(conversation)
            .context(ctx)
            .agentRole("RunChatAgent")
            .stepId("run-chat.synthesis")
            .temperature(0.2)
            .maxTokens(ChatService.MAX_TOKENS_PER_CALL)
            .toolsOverride(List.of())
            .toolChoice("none")
            .build();
    ChatResponse finalResponse = router.call(synthesis);
    StringBuilder answer = new StringBuilder();
    for (var generation : finalResponse.getResults()) {
      String text = generation.getOutput().getText();
      if (text == null || text.isBlank()) continue;
      if (!answer.isEmpty()) answer.append('\n');
      answer.append(text);
    }
    boolean truncated = responseWasTruncated(finalResponse);
    if (answer.isEmpty() || ToolProtocolLeakGuard.containsProtocolMarkup(answer.toString())) {
      log.warn("Run chat synthesis produced no safe customer answer for run {}", runId);
      String safeFallback =
          "I could not produce a grounded answer from the available run evidence. No internal "
              + "tool output was used as a substitute for evidence.";
      return truncated
          ? ChatService.ChatAnswer.outputLimited(safeFallback)
          : ChatService.ChatAnswer.complete(safeFallback);
    }
    return truncated
        ? ChatService.ChatAnswer.outputLimited(answer.toString())
        : ChatService.ChatAnswer.complete(answer.toString());
  }

  static boolean responseWasTruncated(ChatResponse response) {
    if (response == null || response.getResults() == null) return false;
    return response.getResults().stream()
        .filter(java.util.Objects::nonNull)
        .map(org.springframework.ai.chat.model.Generation::getMetadata)
        .filter(java.util.Objects::nonNull)
        .map(org.springframework.ai.chat.metadata.ChatGenerationMetadata::getFinishReason)
        .filter(java.util.Objects::nonNull)
        .map(Object::toString)
        .map(reason -> reason.trim().toLowerCase(java.util.Locale.ROOT))
        .anyMatch(reason -> reason.equals("length") || reason.equals("max_tokens"));
  }

  private static ToolCallback findCallback(List<ToolCallback> tools, String name) {
    if (name == null) return null;
    for (ToolCallback tool : tools) {
      if (name.equals(tool.getToolDefinition().name())) return tool;
    }
    return null;
  }
}
