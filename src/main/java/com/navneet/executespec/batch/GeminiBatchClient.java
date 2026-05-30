package com.navneet.executespec.batch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * B1 (audit 2026-05-30) — Gemini batch client. Third concrete {@link BatchClient}; see {@code
 * docs/plans/v2-batch-execution-plan.md} §1.
 *
 * <p><b>⚠ ELEVATED UNCERTAINTY — read before wiring.</b> The per-request <em>content</em>
 * serialization here (contents / systemInstruction / functionDeclarations) mirrors the known-good
 * {@code GeminiNativeToolClient} shape and is unit-tested. But the Gemini Developer-API <em>batch
 * envelope + endpoints + the long-running-operation response shape are the least stable of the
 * three providers</em> and are <b>reasoned-not-smoked</b>: this targets the inline {@code
 * :batchGenerateContent} → Operation shape, but the live API may instead require the {@code
 * batches} resource and/or a File-API (GCS) input for non-trivial batches. <b>Confirm endpoint +
 * request + Operation/response JSON against live Gemini batch docs (and run a smoke) before
 * enabling.</b> Two known gaps to close at that time: (1) a Gemini batch is single-model (the URL
 * carries the model), so all {@link BatchRequest}s in one submit must share a model — this uses the
 * first request's; (2) tool schemas are passed through as-is here; apply the same schema
 * sanitisation {@code GeminiNativeToolClient#sanitizeSchemaForGemini} does.
 *
 * <p>Inert until the coordinator is wired (same as the other batch clients); flag-OFF +
 * byte-identical production path.
 */
@Component
@Slf4j
public class GeminiBatchClient implements BatchClient {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration READ_TIMEOUT = Duration.ofMinutes(2);
  private static final int DEFAULT_MAX_TOKENS = 8192;

  private final ObjectMapper objectMapper;
  private final ApiKeyResolver apiKeyResolver;
  private final PipelineRunRepository runRepo;
  private final String baseUrl;
  private final String systemKey;

  public GeminiBatchClient(
      ObjectMapper objectMapper,
      ApiKeyResolver apiKeyResolver,
      PipelineRunRepository runRepo,
      @Value("${spring.ai.google.genai.base-url:https://generativelanguage.googleapis.com}")
          String baseUrl,
      @Value("${spring.ai.google.genai.api-key:}") String systemKey) {
    this.objectMapper = objectMapper;
    this.apiKeyResolver = apiKeyResolver;
    this.runRepo = runRepo;
    this.baseUrl = baseUrl;
    this.systemKey = systemKey;
  }

  @Override
  public LlmProvider provider() {
    return LlmProvider.GEMINI;
  }

  @Override
  public BatchJob submit(String runId, List<BatchRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      throw new IllegalArgumentException("Gemini batch submit requires at least one request");
    }
    String model = requests.get(0).model(); // single-model batch — see class caveat (1)
    String key = resolveKey(runId);
    ObjectNode body = buildBatchBody(requests);
    JsonNode op =
        client()
            .post()
            .uri("/v1beta/models/" + model + ":batchGenerateContent?key=" + key)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
    String opName = op == null ? null : op.path("name").asText(null);
    if (opName == null) {
      throw new IllegalStateException("Gemini batch submit returned no operation name");
    }
    log.info(
        "[GeminiBatchClient] submitted batch op {} for run {} ({} requests)",
        opName,
        runId,
        requests.size());
    return new BatchJob(runId, LlmProvider.GEMINI, opName, mapOperationState(op), null, null);
  }

  @Override
  public BatchJob.V2BatchJobState poll(BatchJob job) {
    String key = resolveKey(job.runId());
    JsonNode op =
        client()
            .get()
            .uri("/v1beta/" + job.providerJobId() + "?key=" + key)
            .retrieve()
            .body(JsonNode.class);
    return mapOperationState(op);
  }

  @Override
  public List<BatchTaskResult> fetchResults(BatchJob job) {
    String key = resolveKey(job.runId());
    JsonNode op =
        client()
            .get()
            .uri("/v1beta/" + job.providerJobId() + "?key=" + key)
            .retrieve()
            .body(JsonNode.class);
    return parseInlinedResponses(op);
  }

  @Override
  public void cancel(BatchJob job) {
    // Best-effort — Gemini batch cancellation support varies; no-op + log per the contract.
    log.warn(
        "[GeminiBatchClient] cancel requested for op {} — Gemini batch cancel not implemented "
            + "(verify support against live docs).",
        job.providerJobId());
  }

  // ---- pure helpers (unit-tested) ---------------------------------------------------------

  /**
   * Long-running Operation → job state: {@code done=false}→IN_PROGRESS; done+error→FAILED;
   * done→COMPLETED.
   */
  static BatchJob.V2BatchJobState mapOperationState(JsonNode op) {
    if (op == null) return BatchJob.V2BatchJobState.IN_PROGRESS;
    boolean done = op.path("done").asBoolean(false);
    if (!done) return BatchJob.V2BatchJobState.IN_PROGRESS;
    if (op.has("error") && op.path("error").isObject()) return BatchJob.V2BatchJobState.FAILED;
    return BatchJob.V2BatchJobState.COMPLETED;
  }

  /**
   * Build the inline-batch create body. Each entry carries the task's {@code key} in metadata so
   * results can be matched back regardless of order.
   */
  ObjectNode buildBatchBody(List<BatchRequest> requests) {
    ObjectNode body = objectMapper.createObjectNode();
    ObjectNode batch = body.putObject("batch");
    batch.put("display_name", "es-batch");
    ArrayNode reqArr = batch.putObject("input_config").putObject("requests").putArray("requests");
    if (requests != null) {
      for (BatchRequest req : requests) {
        ObjectNode entry = reqArr.addObject();
        entry.set("request", buildGenerateContentRequest(req));
        entry.putObject("metadata").put("key", req.taskId());
      }
    }
    return body;
  }

  private ObjectNode buildGenerateContentRequest(BatchRequest req) {
    ObjectNode r = objectMapper.createObjectNode();
    ArrayNode contents = r.putArray("contents");
    StringBuilder system = new StringBuilder();
    if (req.messages() != null) {
      for (Message m : req.messages()) {
        String text = m.getText() == null ? "" : m.getText();
        if (m.getMessageType() == MessageType.SYSTEM) {
          if (system.length() > 0) system.append("\n\n");
          system.append(text);
        } else {
          // USER/ASSISTANT → contents with role user/model.
          String role = m.getMessageType() == MessageType.ASSISTANT ? "model" : "user";
          ObjectNode c = contents.addObject();
          c.put("role", role);
          c.putArray("parts").addObject().put("text", text);
        }
      }
    }
    if (system.length() > 0) {
      r.putObject("systemInstruction").putArray("parts").addObject().put("text", system.toString());
    }
    ObjectNode genConfig = r.putObject("generationConfig");
    genConfig.put(
        "maxOutputTokens", req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS);
    if (req.temperature() != null) genConfig.put("temperature", req.temperature());

    if (req.tools() != null && !req.tools().isEmpty()) {
      ArrayNode decls = r.putArray("tools").addObject().putArray("functionDeclarations");
      for (ToolCallback tc : req.tools()) {
        ObjectNode decl = decls.addObject();
        decl.put("name", tc.getToolDefinition().name());
        decl.put("description", tc.getToolDefinition().description());
        try {
          // NOTE: pass-through — apply GeminiNativeToolClient#sanitizeSchemaForGemini before use.
          decl.set("parameters", objectMapper.readTree(tc.getToolDefinition().inputSchema()));
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Invalid JSON schema for tool '" + tc.getToolDefinition().name() + "'", e);
        }
      }
    }
    return r;
  }

  /** Parse a done Operation's inlined responses into per-task results (matched by metadata key). */
  List<BatchTaskResult> parseInlinedResponses(JsonNode op) {
    List<BatchTaskResult> out = new ArrayList<>();
    if (op == null) return out;
    JsonNode inlined = op.path("response").path("inlinedResponses").path("inlinedResponses");
    if (!inlined.isArray()) {
      // Fallback shape: response.inlinedResponses directly an array.
      inlined = op.path("response").path("inlinedResponses");
    }
    if (!inlined.isArray()) return out;
    for (JsonNode item : inlined) {
      String taskId = item.path("metadata").path("key").asText(null);
      if (item.has("error") && item.path("error").isObject()) {
        out.add(
            new BatchTaskResult(
                taskId, null, 0, 0, item.path("error").path("message").asText("error")));
        continue;
      }
      JsonNode response = item.path("response");
      JsonNode usage = response.path("usageMetadata");
      out.add(
          new BatchTaskResult(
              taskId,
              toAssistantMessage(response.path("candidates").path(0).path("content")),
              usage.path("promptTokenCount").asInt(0),
              usage.path("candidatesTokenCount").asInt(0),
              null));
    }
    return out;
  }

  /** Gemini candidate content (parts: text + functionCall) → Spring AI {@link AssistantMessage}. */
  private AssistantMessage toAssistantMessage(JsonNode content) {
    StringBuilder text = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    JsonNode parts = content.path("parts");
    if (parts.isArray()) {
      for (JsonNode part : parts) {
        if (part.has("text")) {
          text.append(part.path("text").asText(""));
        } else if (part.has("functionCall")) {
          JsonNode fc = part.path("functionCall");
          toolCalls.add(
              new AssistantMessage.ToolCall(
                  fc.path("name").asText(""),
                  "function",
                  fc.path("name").asText(""),
                  fc.path("args").toString()));
        }
      }
    }
    return AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
  }

  // ---- HTTP ------------------------------------------------------------------------------

  private RestClient client() {
    java.net.http.HttpClient jdk =
        java.net.http.HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
    factory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /** BYOK-aware key (Gemini passes it as a {@code ?key=} query param, set per-request). */
  private String resolveKey(String runId) {
    String userId =
        runId == null
            ? null
            : runRepo.findById(runId).map(r -> r.getCreatedByUserId()).orElse(null);
    if (userId != null) {
      try {
        String k = apiKeyResolver.resolve(userId).geminiKey();
        if (k != null && !k.isBlank()) return k;
      } catch (Exception e) {
        log.debug(
            "[GeminiBatchClient] per-user key resolve failed for {}: {}", userId, e.getMessage());
      }
    }
    if (systemKey == null || systemKey.isBlank()) {
      throw new IllegalStateException(
          "No Gemini API key available for run " + runId + " (BYOK or system key required)");
    }
    return systemKey;
  }
}
