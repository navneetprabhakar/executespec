package com.navneet.executespec.agent;

import java.net.http.HttpClient;
import java.time.Duration;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
 * <p><b>Fail-safe.</b> Returns an unusable outcome on provider/local failures (no key, blank
 * transcript, API 4xx, parse error) so {@code LlmRouter.callAnthropic} falls through to the normal
 * {@code AnthropicChatModel} path. A durable provider-start fence failure is the exception: it
 * propagates and blocks fallback so a caller that lost dispatch authority cannot spend through a
 * second transport.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AnthropicForcedToolClient {

  private final ObjectMapper objectMapper;

  /**
   * RR-1180 fallback HTTP read ceiling when the caller does not (yet) forward a live worker
   * deadline. Matches {@code AgentModelConfig}'s platform-wide LLM read window so a forced salvage
   * cannot block forever while staying long enough for any legitimate single-turn tool response.
   */
  private static final int DEFAULT_FORCED_READ_TIMEOUT_SECONDS = 300;

  @Value("${spring.ai.anthropic.api-key:}")
  private String platformApiKey;

  /**
   * A RestClient builder with a bounded connect + read timeout for the low-level {@link
   * AnthropicApi} forced-call client (RR-1180). Without this the client uses the JDK default (no
   * read timeout), so a silent/stalled provider socket blocks {@code chatCompletionEntity} forever.
   */
  private static RestClient.Builder boundedRestClientBuilder(Integer readTimeoutSeconds) {
    int readSeconds =
        (readTimeoutSeconds != null && readTimeoutSeconds > 0)
            ? readTimeoutSeconds
            : DEFAULT_FORCED_READ_TIMEOUT_SECONDS;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(Duration.ofSeconds(readSeconds));
    return RestClient.builder().requestFactory(factory);
  }

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
    ForcedToolCallOutcome outcome =
        forcedToolCallOutcome(model, messages, tools, maxTokens, apiKeyOverride);
    return outcome.usable() ? outcome.response() : null;
  }

  /** True only when the forced path will issue a physical provider request. */
  public boolean canAttempt(
      List<Message> messages, List<ToolCallback> tools, String apiKeyOverride) {
    String key =
        (apiKeyOverride != null && !apiKeyOverride.isBlank()) ? apiKeyOverride : platformApiKey;
    return key != null
        && !key.isBlank()
        && tools != null
        && !tools.isEmpty()
        && messages != null
        && !messages.isEmpty();
  }

  /**
   * Auditable forced-call outcome. A provider HTTP success with no usable tool remains a successful
   * paid response and carries its usage; transport/parsing failure is separate from "not
   * attempted".
   */
  public ForcedToolCallOutcome forcedToolCallOutcome(
      String model,
      List<Message> messages,
      List<ToolCallback> tools,
      Integer maxTokens,
      String apiKeyOverride) {
    return forcedToolCallOutcome(model, messages, tools, maxTokens, apiKeyOverride, null);
  }

  /**
   * RR-1180 deadline-aware overload. The low-level {@link AnthropicApi} used here builds its OWN
   * RestClient and does NOT inherit the application's 300s read-timeout customizer, so the previous
   * {@code chatCompletionEntity} call could block indefinitely — a forced salvage / terminal
   * finalizer could then spend past the worker's shutdown reserve and task wall clock. This binds a
   * bounded HTTP read timeout to the physical client.
   *
   * <p><b>Honest limits.</b> A read timeout aborts a stalled/silent socket, which is the RR-1180
   * hazard, but it is a fixed HTTP ceiling rather than a continuously recomputed worker budget. The
   * router forwards the admitted physical-attempt deadline; direct legacy callers fall back to
   * {@link #DEFAULT_FORCED_READ_TIMEOUT_SECONDS}.
   */
  public ForcedToolCallOutcome forcedToolCallOutcome(
      String model,
      List<Message> messages,
      List<ToolCallback> tools,
      Integer maxTokens,
      String apiKeyOverride,
      Integer readTimeoutSeconds) {
    return forcedToolCallOutcome(
        model, messages, tools, maxTokens, apiKeyOverride, readTimeoutSeconds, null);
  }

  /**
   * Deadline-aware, durably fenced forced call. The provider-start callback runs only after all
   * local transcript/schema/request/client construction succeeds and immediately before the HTTP
   * exchange. Callback failure propagates and therefore prevents both this socket write and the
   * router's normal Anthropic fallback.
   */
  public ForcedToolCallOutcome forcedToolCallOutcome(
      String model,
      List<Message> messages,
      List<ToolCallback> tools,
      Integer maxTokens,
      String apiKeyOverride,
      Integer readTimeoutSeconds,
      Runnable physicalAttemptStartedCallback) {
    if (!canAttempt(messages, tools, apiKeyOverride)) {
      return new ForcedToolCallOutcome(null, false, null, false);
    }
    String key =
        (apiKeyOverride != null && !apiKeyOverride.isBlank()) ? apiKeyOverride : platformApiKey;
    try {
      // 1) Flatten: system -> system param; everything else -> one user transcript.
      StringBuilder system = new StringBuilder();
      StringBuilder transcript = new StringBuilder();
      List<AnthropicApi.ContentBlock> mediaBlocks = new ArrayList<>();
      for (Message m : messages) {
        if (m instanceof SystemMessage sm) {
          if (system.length() > 0) system.append("\n\n");
          system.append(sm.getText());
        } else if (m instanceof UserMessage um) {
          transcript.append("USER:\n").append(um.getText()).append("\n\n");
          if (um.getMedia() != null) {
            for (var media : um.getMedia()) {
              mediaBlocks.add(
                  new AnthropicApi.ContentBlock(
                      AnthropicApi.ContentBlock.Type.IMAGE,
                      new AnthropicApi.ContentBlock.Source(
                          "base64",
                          media.getMimeType().toString(),
                          java.util.Base64.getEncoder().encodeToString(media.getDataAsByteArray()),
                          null,
                          null),
                      null,
                      null));
            }
          }
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
      if (transcript.length() == 0) {
        return new ForcedToolCallOutcome(null, false, null, false);
      }

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
      List<AnthropicApi.ContentBlock> userBlocks = new ArrayList<>();
      userBlocks.add(new AnthropicApi.ContentBlock(transcript.toString()));
      userBlocks.addAll(mediaBlocks);
      AnthropicApi.AnthropicMessage userMsg =
          new AnthropicApi.AnthropicMessage(userBlocks, AnthropicApi.Role.USER);
      AnthropicApi.ChatCompletionRequest req =
          AnthropicApi.ChatCompletionRequest.builder()
              .model(model)
              .messages(List.of(userMsg))
              .system(system.length() == 0 ? null : system.toString())
              .maxTokens(maxTokens != null && maxTokens > 0 ? maxTokens : 4096)
              .tools(aTools)
              .toolChoice(new AnthropicApi.ToolChoiceAny())
              .build();

      AnthropicApi api =
          AnthropicApi.builder()
              .apiKey(key)
              .restClientBuilder(boundedRestClientBuilder(readTimeoutSeconds))
              .build();
      signalPhysicalAttemptStarted(physicalAttemptStartedCallback);
      AnthropicApi.ChatCompletionResponse resp = api.chatCompletionEntity(req).getBody();
      if (resp == null || resp.content() == null) {
        return new ForcedToolCallOutcome(
            null,
            false,
            new IllegalStateException("Anthropic returned an empty forced response"),
            true);
      }

      // 4) Parse content blocks -> outcome (fail-closed on abnormal stop reason, RR-1181).
      return buildForcedOutcome(resp, model);
    } catch (ProviderStartFenceException fenceFailure) {
      throw fenceFailure;
    } catch (Exception e) {
      log.warn("Anthropic forced salvage failed — falling back to auto: {}", e.toString());
      return new ForcedToolCallOutcome(null, false, e, true);
    }
  }

  private static void signalPhysicalAttemptStarted(Runnable callback) {
    ProviderStartFenceException.signal(callback);
  }

  /**
   * Normalizes a completed Anthropic forced-call response into a {@link ForcedToolCallOutcome}.
   * Package-private so the RR-1181 fail-closed stop-reason gate is unit-testable without a live
   * provider call.
   *
   * <p>RR-1181 — a forced tool call is only trustworthy when Anthropic terminated with the
   * documented complete tool-call stop reason ({@code tool_use}). An abnormal reason ({@code
   * max_tokens}, {@code refusal}, {@code stop_sequence}, …) means the response was truncated or the
   * model declined, so a {@code tool_use} block's input JSON may be incomplete or the call
   * unauthorized. Previously stop reason was metadata only, so a truncated/refused response with
   * any tool_use block was dispatched as an authorized write/edit/shell mutation. Fail closed: keep
   * the response for audit/usage but mark it NOT usable so {@code LlmRouter} falls through to the
   * normal auto path rather than executing mutations from an abnormally-terminated response.
   */
  ForcedToolCallOutcome buildForcedOutcome(AnthropicApi.ChatCompletionResponse resp, String model)
      throws Exception {
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
    ChatResponse response =
        new ChatResponse(
            List.of(
                new Generation(
                    msg, ChatGenerationMetadata.builder().finishReason(resp.stopReason()).build())),
            meta.build());
    if (calls.isEmpty()) {
      log.info(
          "Anthropic forced salvage: model returned no tool call (stopReason={})",
          resp.stopReason());
      return new ForcedToolCallOutcome(response, false, null, true);
    }
    String stopReason = resp.stopReason();
    if (stopReason != null && !"tool_use".equals(stopReason)) {
      log.warn(
          "Anthropic forced salvage: {} tool call(s) present but stopReason={} is not a complete"
              + " tool_use termination — failing closed, no mutation dispatched",
          calls.size(),
          stopReason);
      return new ForcedToolCallOutcome(response, false, null, true);
    }
    return new ForcedToolCallOutcome(response, true, null, true);
  }

  public record ForcedToolCallOutcome(
      ChatResponse response, boolean usable, Throwable failure, boolean attempted) {}

  private static String trunc(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "…[+" + (s.length() - max) + "]";
  }
}
