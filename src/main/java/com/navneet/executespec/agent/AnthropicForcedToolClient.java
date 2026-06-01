package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RL-2026-05-059 — Anthropic FORCED-tool-call salvage client.
 *
 * <p><b>Why this exists.</b> Spring AI 1.1.6's {@code AnthropicChatOptions} does not expose
 * Anthropic's {@code tool_choice}, so the {@code LlmRouter.callAnthropic} path (which uses {@code
 * AnthropicChatModel}) can never FORCE a tool call. The WorkerAgent Lever-B salvage (V224, flag
 * {@code worker.salvage-force-tool-choice.enabled}) sets {@code tool_choice="required"} to make a
 * zero-write terminal emit an actual edit — honoured by the OpenAI-wire clients, but IGNORED by
 * Anthropic, so Sonnet's no-edit runs (6/50 on the 2026-05-31 eval) survive even with the flag on.
 *
 * <p>This client bypasses {@code AnthropicChatModel} for the salvage call ONLY: it POSTs a
 * single-turn request via the low-level {@link AnthropicApi} with {@code tool_choice =
 * ToolChoiceAny} (the model MUST call one of the supplied tools). The multi-turn worker history is
 * FLATTENED into one user message (system stays the system param) — this trades some fidelity for
 * safety: a single user turn has no tool_use/tool_result pairing to get wrong (an invalid sequence
 * would 400). The restricted tool set (writeFile / editFile / completeTask) is passed by the
 * caller, so the forced call can only pick a productive action.
 *
 * <p><b>Fail-safe.</b> Returns {@code null} on ANY failure (no key, blank transcript, API 4xx,
 * parse error) so {@code LlmRouter.callAnthropic} falls through to the normal {@code
 * AnthropicChatModel} path — the forced salvage can NEVER regress the existing Anthropic behaviour,
 * and it only ever runs on an already-failing zero-write salvage call.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnthropicForcedToolClient {

  private final ObjectMapper objectMapper;

  @Value("${spring.ai.anthropic.api-key:}")
  private String platformApiKey;

  /**
   * Force one Anthropic tool call. {@code apiKeyOverride} wins (BYOK) over the platform key.
   *
   * @return a {@link ChatResponse} carrying the forced tool call(s), or {@code null} to fall
   *     through.
   */
  public ChatResponse forcedToolCall(
      String model,
      List<Message> messages,
      List<ToolCallback> tools,
      Integer maxTokens,
      String apiKeyOverride) {
    String key =
        (apiKeyOverride != null && !apiKeyOverride.isBlank()) ? apiKeyOverride : platformApiKey;
    if (key == null || key.isBlank()) return null;
    if (tools == null || tools.isEmpty() || messages == null || messages.isEmpty()) return null;
    try {
      // 1) Flatten: system -> system param; everything else -> one user transcript.
      StringBuilder system = new StringBuilder();
      StringBuilder transcript = new StringBuilder();
      for (Message m : messages) {
        if (m instanceof SystemMessage sm) {
          if (system.length() > 0) system.append("\n\n");
          system.append(sm.getText());
        } else if (m instanceof UserMessage um) {
          transcript.append("USER:\n").append(um.getText()).append("\n\n");
        } else if (m instanceof AssistantMessage am) {
          if (am.getText() != null && !am.getText().isBlank()) {
            transcript.append("ASSISTANT:\n").append(am.getText()).append("\n");
          }
          if (am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
            transcript.append("ASSISTANT tool calls: ");
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
              transcript
                  .append(tc.name())
                  .append("(")
                  .append(trunc(tc.arguments(), 300))
                  .append(") ");
            }
            transcript.append("\n\n");
          }
        } else if (m instanceof ToolResponseMessage trm) {
          transcript.append("TOOL RESULTS:\n");
          for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
            transcript.append(trunc(r.responseData(), 800)).append("\n");
          }
          transcript.append("\n");
        }
      }
      if (transcript.length() == 0) return null;

      // 2) Tools -> AnthropicApi.Tool (input schema parsed to a Map).
      List<AnthropicApi.Tool> aTools = new ArrayList<>();
      for (ToolCallback tc : tools) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema =
            objectMapper.readValue(tc.getToolDefinition().inputSchema(), Map.class);
        aTools.add(
            new AnthropicApi.Tool(
                tc.getToolDefinition().name(), tc.getToolDefinition().description(), schema));
      }

      // 3) Single-turn request with tool_choice = ANY (must emit a tool call).
      AnthropicApi.AnthropicMessage userMsg =
          new AnthropicApi.AnthropicMessage(
              List.of(new AnthropicApi.ContentBlock(transcript.toString())),
              AnthropicApi.Role.USER);
      AnthropicApi.ChatCompletionRequest req =
          AnthropicApi.ChatCompletionRequest.builder()
              .model(model)
              .messages(List.of(userMsg))
              .system(system.length() == 0 ? null : system.toString())
              .maxTokens(maxTokens != null && maxTokens > 0 ? maxTokens : 4096)
              .tools(aTools)
              .toolChoice(new AnthropicApi.ToolChoiceAny())
              .build();

      AnthropicApi api = AnthropicApi.builder().apiKey(key).build();
      AnthropicApi.ChatCompletionResponse resp = api.chatCompletionEntity(req).getBody();
      if (resp == null || resp.content() == null) return null;

      // 4) Parse content blocks -> text + forced tool call(s).
      StringBuilder text = new StringBuilder();
      List<AssistantMessage.ToolCall> calls = new ArrayList<>();
      for (AnthropicApi.ContentBlock cb : resp.content()) {
        if (cb.type() == AnthropicApi.ContentBlock.Type.TEXT && cb.text() != null) {
          text.append(cb.text());
        } else if (cb.type() == AnthropicApi.ContentBlock.Type.TOOL_USE) {
          String args = objectMapper.writeValueAsString(cb.input() == null ? Map.of() : cb.input());
          calls.add(new AssistantMessage.ToolCall(cb.id(), "function", cb.name(), args));
        }
      }
      if (calls.isEmpty()) {
        log.info(
            "Anthropic forced salvage: model returned no tool call (stopReason={})",
            resp.stopReason());
        return null; // nothing to dispatch — fall through
      }

      ChatResponseMetadata.Builder meta = ChatResponseMetadata.builder().model(model);
      if (resp.usage() != null) {
        int in = resp.usage().inputTokens() == null ? 0 : resp.usage().inputTokens();
        int out = resp.usage().outputTokens() == null ? 0 : resp.usage().outputTokens();
        int cr =
            resp.usage().cacheReadInputTokens() == null ? 0 : resp.usage().cacheReadInputTokens();
        meta.usage(new DefaultUsage(in, out));
        meta.keyValue("cache_read_input_tokens", cr);
      }
      log.info(
          "Anthropic forced salvage: forced {} tool call(s) via ToolChoiceAny (stopReason={})",
          calls.size(),
          resp.stopReason());
      AssistantMessage msg =
          AssistantMessage.builder().content(text.toString()).toolCalls(calls).build();
      return new ChatResponse(
          List.of(
              new Generation(
                  msg, ChatGenerationMetadata.builder().finishReason(resp.stopReason()).build())),
          meta.build());
    } catch (Exception e) {
      log.warn("Anthropic forced salvage failed — falling back to auto: {}", e.toString());
      return null;
    }
  }

  private static String trunc(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…[+" + (s.length() - max) + "]";
  }
}
