package com.navneet.executespec.batch;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ProviderDispatchAuthorization;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * B1 (audit 2026-05-30) — OpenAI <a href="https://platform.openai.com/docs/guides/batch">Batch
 * API</a> client. Second concrete {@link BatchClient}; see {@code AnthropicBatchClient} for the
 * reference shape and {@code docs/plans/v2-batch-execution-plan.md} §1 for the contract.
 *
 * <p><b>File-based</b> (unlike Anthropic's inline): submit uploads a JSONL of per-task chat
 * requests to the Files API → creates a batch over it; results come back as an output file. Each
 * input line is a chat-completions request whose {@code body} mirrors {@code
 * OpenAiNativeToolClient}'s shape (role/content messages + {@code type:function} tools).
 *
 * <p><b>Verification boundary (honest):</b> the pure JSON shaping — {@link #buildInputJsonl} (line
 * envelope + completion body), {@link #mapStatus}, {@link #parseOutputJsonl} — is unit-tested. The
 * HTTP (file upload + create + poll + download + cancel) is <em>reasoned-not-smoked</em> (no {@code
 * api.openai.com} reach here); a live smoke is required before any flip. Inert until the
 * coordinator is wired (same as {@code AnthropicBatchClient}).
 */
@Component
@Slf4j
public class OpenAiBatchClient implements BatchClient {

  private static final String FILES_PATH = "/v1/files";
  private static final String BATCHES_PATH = "/v1/batches";
  private static final String COMPLETIONS_URL = "/v1/chat/completions";
  private static final String COMPLETION_WINDOW = "24h";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration READ_TIMEOUT = Duration.ofMinutes(2);
  private static final int DEFAULT_MAX_TOKENS = 8192;

  private final ObjectMapper objectMapper;
  private final ApiKeyResolver apiKeyResolver;
  private final PipelineRunRepository runRepo;
  private final ProviderDispatchAuthorization dispatchAuthorization;
  private final String baseUrl;
  private final String systemKey;

  public OpenAiBatchClient(
      ObjectMapper objectMapper,
      ApiKeyResolver apiKeyResolver,
      PipelineRunRepository runRepo,
      ProviderDispatchAuthorization dispatchAuthorization,
      @Value("${spring.ai.openai.base-url:https://api.openai.com}") String baseUrl,
      @Value("${spring.ai.openai.api-key:}") String systemKey) {
    this.objectMapper = objectMapper;
    this.apiKeyResolver = apiKeyResolver;
    this.runRepo = runRepo;
    this.dispatchAuthorization = dispatchAuthorization;
    this.baseUrl = baseUrl;
    this.systemKey = systemKey;
  }

  @Override
  public LlmProvider provider() {
    return LlmProvider.OPENAI;
  }

  @Override
  public BatchJob submit(String runId, List<BatchRequest> requests) {
    String model = singleModel(requests);
    dispatchAuthorization.authorize("openai", model, runId);
    String jsonl = buildInputJsonl(requests);
    // 1. Upload the JSONL to the Files API (purpose=batch).
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("purpose", "batch");
    form.add(
        "file",
        new ByteArrayResource(jsonl.getBytes(StandardCharsets.UTF_8)) {
          @Override
          public String getFilename() {
            return "batch-" + runId + ".jsonl";
          }
        });
    JsonNode fileResp =
        client(runId)
            .post()
            .uri(FILES_PATH)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(form)
            .retrieve()
            .body(JsonNode.class);
    String fileId = fileResp == null ? null : fileResp.path("id").asText(null);
    if (fileId == null) {
      throw new IllegalStateException("OpenAI batch input-file upload returned no file id");
    }
    // 2. Create the batch over that file.
    ObjectNode createBody = objectMapper.createObjectNode();
    createBody.put("input_file_id", fileId);
    createBody.put("endpoint", COMPLETIONS_URL);
    createBody.put("completion_window", COMPLETION_WINDOW);
    JsonNode batchResp =
        client(runId)
            .post()
            .uri(BATCHES_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(createBody)
            .retrieve()
            .body(JsonNode.class);
    if (batchResp == null || batchResp.path("id").asText(null) == null) {
      throw new IllegalStateException("OpenAI batch create returned no batch id");
    }
    String batchId = batchResp.get("id").asText();
    log.info(
        "[OpenAiBatchClient] submitted batch {} for run {} ({} requests)",
        batchId,
        runId,
        requests == null ? 0 : requests.size());
    return new BatchJob(
        runId,
        LlmProvider.OPENAI,
        batchId,
        mapStatus(batchResp.path("status").asText("validating")),
        null,
        null);
  }

  private static String singleModel(List<BatchRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      throw new IllegalArgumentException("OpenAI batch submit requires at least one request");
    }
    List<String> models = requests.stream().map(BatchRequest::model).distinct().toList();
    if (models.size() != 1 || models.get(0) == null || models.get(0).isBlank()) {
      throw new IllegalArgumentException("OpenAI batch submit requires exactly one model");
    }
    return models.get(0);
  }

  @Override
  public BatchJob.V2BatchJobState poll(BatchJob job) {
    JsonNode resp =
        client(job.runId())
            .get()
            .uri(BATCHES_PATH + "/" + job.providerJobId())
            .retrieve()
            .body(JsonNode.class);
    return mapStatus(resp == null ? "" : resp.path("status").asText(""));
  }

  @Override
  public List<BatchTaskResult> fetchResults(BatchJob job) {
    // Resolve output_file_id from the batch, then download its JSONL content.
    JsonNode batch =
        client(job.runId())
            .get()
            .uri(BATCHES_PATH + "/" + job.providerJobId())
            .retrieve()
            .body(JsonNode.class);
    String outputFileId = batch == null ? null : batch.path("output_file_id").asText(null);
    if (outputFileId == null) {
      throw new IllegalStateException(
          "OpenAI batch " + job.providerJobId() + " has no output_file_id yet");
    }
    String jsonl =
        client(job.runId())
            .get()
            .uri(FILES_PATH + "/" + outputFileId + "/content")
            .retrieve()
            .body(String.class);
    return parseOutputJsonl(jsonl);
  }

  @Override
  public void cancel(BatchJob job) {
    try {
      client(job.runId())
          .post()
          .uri(BATCHES_PATH + "/" + job.providerJobId() + "/cancel")
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn(
          "[OpenAiBatchClient] cancel of batch {} failed: {}", job.providerJobId(), e.getMessage());
    }
  }

  // ---- pure helpers (unit-tested) ---------------------------------------------------------

  /** OpenAI batch status → job state. */
  static BatchJob.V2BatchJobState mapStatus(String status) {
    if (status == null) return BatchJob.V2BatchJobState.IN_PROGRESS;
    return switch (status) {
      case "completed" -> BatchJob.V2BatchJobState.COMPLETED;
      case "failed", "expired" -> BatchJob.V2BatchJobState.FAILED;
      case "cancelled", "cancelling" -> BatchJob.V2BatchJobState.CANCELLED;
      default -> BatchJob.V2BatchJobState.IN_PROGRESS; // validating / in_progress / finalizing
    };
  }

  /**
   * Build the JSONL upload body: one line per task, each a {@code /v1/chat/completions} request.
   */
  String buildInputJsonl(List<BatchRequest> requests) {
    if (requests == null || requests.isEmpty()) return "";
    List<String> lines = new ArrayList<>();
    for (BatchRequest req : requests) {
      ObjectNode line = objectMapper.createObjectNode();
      line.put("custom_id", req.taskId());
      line.put("method", "POST");
      line.put("url", COMPLETIONS_URL);
      line.set("body", buildCompletionBody(req));
      lines.add(line.toString());
    }
    return String.join("\n", lines);
  }

  private ObjectNode buildCompletionBody(BatchRequest req) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", req.model());
    body.put("max_tokens", req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS);
    if (req.temperature() != null) body.put("temperature", req.temperature());

    ArrayNode messages = body.putArray("messages");
    if (req.messages() != null) {
      for (Message m : req.messages()) {
        String text = m.getText() == null ? "" : m.getText();
        if (m.getMessageType() == MessageType.SYSTEM) {
          messages.addObject().put("role", "system").put("content", text);
        } else if (m.getMessageType() == MessageType.USER) {
          messages.addObject().put("role", "user").put("content", text);
        } else if (m.getMessageType() == MessageType.ASSISTANT) {
          messages.addObject().put("role", "assistant").put("content", text);
        }
        // TOOL messages skipped — batch carries the first turn only.
      }
    }

    if (req.tools() != null && !req.tools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      for (ToolCallback tc : req.tools()) {
        ObjectNode tool = tools.addObject();
        tool.put("type", "function");
        ObjectNode fn = tool.putObject("function");
        fn.put("name", tc.getToolDefinition().name());
        fn.put("description", tc.getToolDefinition().description());
        try {
          fn.set("parameters", objectMapper.readTree(tc.getToolDefinition().inputSchema()));
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Invalid JSON schema for tool '" + tc.getToolDefinition().name() + "'", e);
        }
      }
    }
    return body;
  }

  /** Parse the output-file JSONL into per-task results, matched by {@code custom_id}. */
  List<BatchTaskResult> parseOutputJsonl(String jsonl) {
    List<BatchTaskResult> out = new ArrayList<>();
    if (jsonl == null || jsonl.isBlank()) return out;
    for (String line : jsonl.split("\\r?\\n")) {
      if (line.isBlank()) continue;
      try {
        out.add(parseOutputLine(objectMapper.readTree(line)));
      } catch (Exception e) {
        log.warn("[OpenAiBatchClient] unparseable result line: {}", e.getMessage());
      }
    }
    return out;
  }

  private BatchTaskResult parseOutputLine(JsonNode node) {
    String taskId = node.path("custom_id").asText(null);
    // Per-line error (request never produced a response).
    if (!node.path("error").isNull() && node.path("error").isObject()) {
      return new BatchTaskResult(
          taskId, null, 0, 0, node.path("error").path("message").asText("error"));
    }
    JsonNode response = node.path("response");
    int statusCode = response.path("status_code").asInt(0);
    JsonNode body = response.path("body");
    if (statusCode != 0 && statusCode >= 400) {
      return new BatchTaskResult(taskId, null, 0, 0, "http " + statusCode);
    }
    JsonNode message = body.path("choices").path(0).path("message");
    JsonNode usage = body.path("usage");
    return new BatchTaskResult(
        taskId,
        toAssistantMessage(message),
        usage.path("prompt_tokens").asInt(0),
        usage.path("completion_tokens").asInt(0),
        null);
  }

  /** OpenAI chat message (content + tool_calls) → Spring AI {@link AssistantMessage}. */
  private AssistantMessage toAssistantMessage(JsonNode message) {
    String content = message.path("content").asText("");
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    JsonNode tcs = message.path("tool_calls");
    if (tcs.isArray()) {
      for (JsonNode tc : tcs) {
        toolCalls.add(
            new AssistantMessage.ToolCall(
                tc.path("id").asText(""),
                "function",
                tc.path("function").path("name").asText(""),
                tc.path("function").path("arguments").asText("")));
      }
    }
    return AssistantMessage.builder().content(content).toolCalls(toolCalls).build();
  }

  // ---- HTTP ------------------------------------------------------------------------------

  private RestClient client(String runId) {
    String key = resolveKey(runId);
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "No OpenAI API key available for run " + runId + " (BYOK or system key required)");
    }
    java.net.http.HttpClient jdk =
        java.net.http.HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
    factory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .defaultHeader("Authorization", "Bearer " + key)
        .build();
  }

  private String resolveKey(String runId) {
    String userId =
        runId == null
            ? null
            : runRepo.findById(runId).map(r -> r.getCreatedByUserId()).orElse(null);
    if (userId != null) {
      try {
        String k = apiKeyResolver.resolve(userId).openAiKey();
        if (k != null && !k.isBlank()) return k;
      } catch (Exception e) {
        log.debug(
            "[OpenAiBatchClient] per-user key resolve failed for {}: {}", userId, e.getMessage());
      }
    }
    return systemKey;
  }
}
