package com.navneet.executespec.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agent.AnthropicModelOptions;
import com.navneet.executespec.agent.OpenAiNativeToolClient;
import com.navneet.executespec.agent.OpenAiNativeToolClient.OpenAiToolCallRequest;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 Phase 0 verification endpoint.
 *
 * <p>Calls each provider with a single-tool prompt and asserts that the response contains a
 * structured tool call (not embedded XML/JSON in text). This validates Spring AI 1.1.4's native
 * tool calling support across Anthropic, OpenAI, and Google Gemini before V2 builds the full
 * LlmRouter layer on top.
 *
 * <p>Usage: {@code GET /api/v2/test/native-tools?provider=anthropic|openai|gemini}
 *
 * <p>This controller is intentionally minimal — it exists to prove the foundation, not to be
 * production code. Will be removed once LlmRouter is in place.
 */
// Audit r5 (§6.2, 2026-05-06) — see ContextStoreTestController for rationale.
@org.springframework.context.annotation.Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test")
@Slf4j
public class NativeToolsTestController {

  private final OpenAiNativeToolClient openAiNativeToolClient;
  private final AnthropicChatModel anthropicModel;
  private final GoogleGenAiChatModel geminiModel;
  private final String openAiModelName;

  // Inject all three model beans by their default qualifiers (defined in AgentModelConfig).
  // These may be null if the provider's API key is not configured — handled per-request.
  // OpenAI: do NOT use the auto-configured bean — its defaults (max-tokens: 16384 from
  // application.yml) get merged into call-time options and Spring AI 1.1.4 serializes the
  // legacy max_tokens field into extra_body for gpt-5.x reasoning models, which the
  // chat-completions endpoint rejects. Build a fresh model inline with cleared defaults
  // (mirrors V1 FallbackLlmRouter.buildOpenAiModel pattern at FallbackLlmRouter.java:613-621).
  public NativeToolsTestController(
      OpenAiNativeToolClient openAiNativeToolClient,
      @Qualifier("anthropicChatModel") ObjectProvider<AnthropicChatModel> anthropicModelProvider,
      @Qualifier("googleGenAiChatModel") ObjectProvider<GoogleGenAiChatModel> geminiModelProvider,
      @Value("${spring.ai.openai.chat.options.model:gpt-5.4-mini}") String openAiModelName) {
    this.openAiNativeToolClient = openAiNativeToolClient;
    this.anthropicModel = anthropicModelProvider.getIfAvailable();
    this.geminiModel = geminiModelProvider.getIfAvailable();
    this.openAiModelName = openAiModelName;
  }

  @GetMapping("/native-tools")
  public Map<String, Object> testNativeTools(
      @RequestParam String provider, @RequestParam(required = false) String model) {
    ToolCallback echo = buildEchoToolCallback();
    List<ToolCallback> callbacks = List.of(echo);
    String userPrompt = "Use the echoMessage tool to echo back the text 'hello-v2'.";

    Map<String, Object> result = new HashMap<>();
    result.put("provider", provider);
    result.put("prompt", userPrompt);
    if (model != null) result.put("modelOverride", model);
    try {
      ChatResponse resp =
          switch (provider.toLowerCase()) {
            case "anthropic" -> callAnthropic(userPrompt, callbacks, model);
            case "openai" -> callOpenAi(userPrompt, callbacks, model);
            case "gemini" -> callGemini(userPrompt, callbacks, model);
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
          };
      return summarizeResponse(result, resp);
    } catch (Exception e) {
      log.error("Native tools test failed for provider {}", provider, e);
      result.put("ok", false);
      result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      return result;
    }
  }

  private ChatResponse callAnthropic(
      String userPrompt, List<ToolCallback> callbacks, String modelOverride) {
    if (anthropicModel == null) throw new IllegalStateException("Anthropic model not configured");
    var b =
        AnthropicChatOptions.builder()
            .maxTokens(512)
            .toolCallbacks(callbacks)
            .internalToolExecutionEnabled(false);
    if (modelOverride != null && !modelOverride.isBlank()) b.model(modelOverride);
    if (!AnthropicModelOptions.shouldOmitTemperature(modelOverride)) b.temperature(0.0);
    return anthropicModel.call(new Prompt(List.of(new UserMessage(userPrompt)), b.build()));
  }

  private ChatResponse callOpenAi(
      String userPrompt, List<ToolCallback> callbacks, String modelOverride) {
    String modelToUse =
        (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : openAiModelName;
    // Routes through OpenAiNativeToolClient — direct REST call, bypasses Spring AI's
    // OpenAi serialization (which adds extra_body when toolCallbacks is set in 1.1.4).
    return openAiNativeToolClient.call(
        OpenAiToolCallRequest.builder()
            .model(modelToUse)
            .userPrompt(userPrompt)
            .tools(callbacks)
            .temperature(0.0)
            .maxCompletionTokens(512)
            .build());
  }

  /**
   * Tests every model in the V2 plan matrix for native tool calling support. Single curl for full
   * Phase 0 verification.
   */
  @GetMapping("/native-tools/all")
  public Map<String, Object> testAll() {
    // V2 model matrix (per application.yml model-matrix) + control models for OpenAI.
    List<String[]> matrix =
        List.of(
            new String[] {"anthropic", "claude-sonnet-4-6"},
            new String[] {"anthropic", "claude-haiku-4-5-20251001"},
            new String[] {"gemini", "gemini-2.5-flash"},
            new String[] {"gemini", "gemini-3-flash-preview"},
            new String[] {"openai", "gpt-5.4"},
            new String[] {"openai", "gpt-5.4-mini"},
            new String[] {"openai", "gpt-4o"}, // control: standard model
            new String[] {"openai", "gpt-4o-mini"} // control: standard model
            );
    ToolCallback echo = buildEchoToolCallback();
    List<ToolCallback> callbacks = List.of(echo);
    String userPrompt = "Use the echoMessage tool to echo back the text 'hello-v2'.";

    List<Map<String, Object>> results = new java.util.ArrayList<>();
    for (String[] pair : matrix) {
      String provider = pair[0];
      String model = pair[1];
      Map<String, Object> row = new java.util.LinkedHashMap<>();
      row.put("provider", provider);
      row.put("model", model);
      try {
        ChatResponse resp =
            switch (provider) {
              case "anthropic" -> callAnthropic(userPrompt, callbacks, model);
              case "openai" -> callOpenAi(userPrompt, callbacks, model);
              case "gemini" -> callGemini(userPrompt, callbacks, model);
              default -> throw new IllegalArgumentException("Unknown provider: " + provider);
            };
        int tcCount = 0;
        for (var gen : resp.getResults()) {
          if (gen.getOutput().getToolCalls() != null)
            tcCount += gen.getOutput().getToolCalls().size();
        }
        row.put("ok", tcCount > 0);
        row.put("toolCalls", tcCount);
        row.put("finishReason", resp.getResult().getMetadata().getFinishReason());
      } catch (Exception e) {
        row.put("ok", false);
        String msg = e.getMessage();
        row.put(
            "error",
            e.getClass().getSimpleName()
                + ": "
                + (msg == null ? "" : msg.substring(0, Math.min(300, msg.length()))));
      }
      results.add(row);
    }
    Map<String, Object> out = new HashMap<>();
    out.put("totalTested", results.size());
    out.put("passed", results.stream().filter(r -> Boolean.TRUE.equals(r.get("ok"))).count());
    out.put("failed", results.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count());
    out.put("results", results);
    return out;
  }

  private ChatResponse callGemini(
      String userPrompt, List<ToolCallback> callbacks, String modelOverride) {
    if (geminiModel == null) throw new IllegalStateException("Gemini model not configured");
    var b =
        GoogleGenAiChatOptions.builder()
            .temperature(0.0)
            .maxOutputTokens(512)
            .toolCallbacks(callbacks)
            .internalToolExecutionEnabled(false);
    if (modelOverride != null && !modelOverride.isBlank()) b.model(modelOverride);
    return geminiModel.call(new Prompt(List.of(new UserMessage(userPrompt)), b.build()));
  }

  private Map<String, Object> summarizeResponse(Map<String, Object> result, ChatResponse resp) {
    // Scan ALL generations for tool calls — Anthropic returns multiple generations
    // (e.g. an empty text block followed by a tool_use block); resp.getResult() only
    // returns generation #0, which may be the empty one.
    org.springframework.ai.chat.messages.AssistantMessage.ToolCall firstToolCall = null;
    int totalToolCalls = 0;
    String winningFinishReason = null;
    for (var gen : resp.getResults()) {
      var msg = gen.getOutput();
      if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
        totalToolCalls += msg.getToolCalls().size();
        if (firstToolCall == null) {
          firstToolCall = msg.getToolCalls().get(0);
          winningFinishReason = gen.getMetadata().getFinishReason();
        }
      }
    }
    result.put("ok", firstToolCall != null);
    result.put("nativeToolCallCount", totalToolCalls);
    result.put("hasToolCallsFlag", resp.getResult().getOutput().hasToolCalls());
    result.put("textContent", resp.getResult().getOutput().getText());
    if (firstToolCall != null) {
      Map<String, Object> tc = new HashMap<>();
      tc.put("name", firstToolCall.name());
      tc.put("arguments", firstToolCall.arguments());
      tc.put("id", firstToolCall.id());
      result.put("firstToolCall", tc);
    }
    result.put(
        "finishReason",
        winningFinishReason != null
            ? winningFinishReason
            : resp.getResult().getMetadata().getFinishReason());

    // Diagnostic: walk all generations (some providers split tool calls into separate Generations)
    List<Map<String, Object>> allGens = new java.util.ArrayList<>();
    for (var gen : resp.getResults()) {
      Map<String, Object> g = new HashMap<>();
      var msg = gen.getOutput();
      g.put("text", msg.getText());
      g.put("toolCallCount", msg.getToolCalls() == null ? 0 : msg.getToolCalls().size());
      g.put("hasToolCalls", msg.hasToolCalls());
      g.put("finishReason", gen.getMetadata().getFinishReason());
      g.put("messageType", msg.getClass().getSimpleName());
      g.put(
          "messageToString",
          String.valueOf(msg).substring(0, Math.min(500, String.valueOf(msg).length())));
      allGens.add(g);
    }
    result.put("allGenerations", allGens);

    // Diagnostic: response-level metadata
    var meta = resp.getMetadata();
    if (meta != null) {
      Map<String, Object> m = new HashMap<>();
      m.put("model", meta.getModel());
      m.put("id", meta.getId());
      if (meta.getUsage() != null) {
        m.put("promptTokens", meta.getUsage().getPromptTokens());
        m.put("completionTokens", meta.getUsage().getCompletionTokens());
      }
      result.put("responseMetadata", m);
    }
    return result;
  }

  /**
   * Builds a no-op {@code echoMessage} tool callback. The tool definition uses a strict JSON schema
   * (additionalProperties: false). The handler is never expected to run because {@code
   * internalToolExecutionEnabled(false)} keeps execution in the harness — we only want to prove the
   * model emits a structured tool call.
   */
  private ToolCallback buildEchoToolCallback() {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("echoMessage")
            .description(
                "Echoes back the provided message text. Use this when asked to echo something.")
            .inputSchema(
                """
                {
                  "type": "object",
                  "properties": {
                    "message": {
                      "type": "string",
                      "description": "The text to echo back"
                    }
                  },
                  "required": ["message"],
                  "additionalProperties": false
                }
                """)
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return def;
      }

      @Override
      public String call(String toolInput) {
        return "{\"echoed\":" + toolInput + "}";
      }
    };
  }
}
