package com.navneet.executespec.batch;

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
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * B1 (audit 2026-05-29) — Anthropic <a
 * href="https://docs.anthropic.com/en/api/creating-message-batches">Message Batches</a> client. The
 * first concrete {@link BatchClient}; the rest of {@code batch/} was an interface-only scaffold (0
 * provider clients).
 *
 * <p><b>What batch is for here:</b> bulk-submit each task's <em>first</em> worker turn (system +
 * user + tools) at the ~50%-discounted batch rate; {@link BatchResumeService} then dispatches the
 * returned tool calls locally and continues realtime. So serialization here only needs the
 * first-turn shape (system + user + assistant text) + the tool schemas — not multi-turn tool-result
 * history.
 *
 * <p><b>Verification boundary (honest):</b> the pure JSON shaping — request-envelope construction
 * ({@link #buildBatchBody}), the {@code processing_status} → {@link BatchJob.V2BatchJobState} map
 * ({@link #mapProcessingStatus}), and the JSONL results parse ({@link #parseResultsJsonl}) — is
 * unit-tested ({@code AnthropicBatchClientTest}). The four HTTP calls themselves are
 * <em>reasoned-not-smoked</em>: this environment can't reach {@code api.anthropic.com}. A live
 * smoke is required before any production flip.
 *
 * <p><b>Inert until wired:</b> registered as a bean so {@link BatchClientRouter} discovers it, but
 * {@link BatchCoordinator#submit} still throws and nothing calls it — the batch path stays flag-OFF
 * + unwired (P2 coordinator/poll/resume wiring is separate). Per the product decision, actual batch
 * <em>submission</em> must remain behind an explicit human confirm when that wiring lands.
 */
@Component
@Slf4j
public class AnthropicBatchClient implements BatchClient {

  private static final String CREATE_PATH = "/v1/messages/batches";
  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration READ_TIMEOUT = Duration.ofMinutes(2);
  private static final int DEFAULT_MAX_TOKENS = 8192;

  private final ObjectMapper objectMapper;
  private final ApiKeyResolver apiKeyResolver;
  private final PipelineRunRepository runRepo;
  private final ProviderDispatchAuthorization dispatchAuthorization;
  private final String baseUrl;
  private final String systemKey;

  public AnthropicBatchClient(
      ObjectMapper objectMapper,
      ApiKeyResolver apiKeyResolver,
      PipelineRunRepository runRepo,
      ProviderDispatchAuthorization dispatchAuthorization,
      @Value("${spring.ai.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
      @Value("${spring.ai.anthropic.api-key:}") String systemKey) {
    this.objectMapper = objectMapper;
    this.apiKeyResolver = apiKeyResolver;
    this.runRepo = runRepo;
    this.dispatchAuthorization = dispatchAuthorization;
    this.baseUrl = baseUrl;
    this.systemKey = systemKey;
  }

  @Override
  public LlmProvider provider() {
    return LlmProvider.ANTHROPIC;
  }

  @Override
  public BatchJob submit(String runId, List<BatchRequest> requests) {
    authorizeSubmit(runId, requests);
    ObjectNode body = buildBatchBody(requests);
    JsonNode resp =
        client(runId)
            .post()
            .uri(CREATE_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(JsonNode.class);
    if (resp == null || resp.path("id").asText(null) == null) {
      throw new IllegalStateException("Anthropic batch submit returned no batch id");
    }
    String batchId = resp.get("id").asText();
    log.info(
        "[AnthropicBatchClient] submitted batch {} for run {} ({} requests)",
        batchId,
        runId,
        requests == null ? 0 : requests.size());
    return new BatchJob(
        runId,
        LlmProvider.ANTHROPIC,
        batchId,
        mapProcessingStatus(resp.path("processing_status").asText("in_progress")),
        null,
        null);
  }

  private void authorizeSubmit(String runId, List<BatchRequest> requests) {
    String model = singleModel(requests);
    dispatchAuthorization.authorize("anthropic", model, runId);
  }

  private static String singleModel(List<BatchRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      throw new IllegalArgumentException("Anthropic batch submit requires at least one request");
    }
    List<String> models = requests.stream().map(BatchRequest::model).distinct().toList();
    if (models.size() != 1 || models.get(0) == null || models.get(0).isBlank()) {
      throw new IllegalArgumentException("Anthropic batch submit requires exactly one model");
    }
    return models.get(0);
  }

  @Override
  public BatchJob.V2BatchJobState poll(BatchJob job) {
    JsonNode resp =
        client(job.runId())
            .get()
            .uri(CREATE_PATH + "/" + job.providerJobId())
            .retrieve()
            .body(JsonNode.class);
    String status = resp == null ? "" : resp.path("processing_status").asText("");
    return mapProcessingStatus(status);
  }

  @Override
  public List<BatchTaskResult> fetchResults(BatchJob job) {
    // Anthropic streams results as JSONL from /results once processing_status == ended.
    String jsonl =
        client(job.runId())
            .get()
            .uri(CREATE_PATH + "/" + job.providerJobId() + "/results")
            .retrieve()
            .body(String.class);
    return parseResultsJsonl(jsonl);
  }

  @Override
  public void cancel(BatchJob job) {
    try {
      client(job.runId())
          .post()
          .uri(CREATE_PATH + "/" + job.providerJobId() + "/cancel")
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      // Best-effort per the contract — a batch already ended can't be cancelled.
      log.warn(
          "[AnthropicBatchClient] cancel of batch {} failed: {}",
          job.providerJobId(),
          e.getMessage());
    }
  }

  // ---- pure helpers (unit-tested) ---------------------------------------------------------

  /**
   * processing_status → job state. {@code ended} means results are fetchable (per-task errors are
   * surfaced per-result, not as a job failure).
   */
  static BatchJob.V2BatchJobState mapProcessingStatus(String processingStatus) {
    if (processingStatus == null) return BatchJob.V2BatchJobState.IN_PROGRESS;
    return switch (processingStatus) {
      case "ended" -> BatchJob.V2BatchJobState.COMPLETED;
      case "canceling" -> BatchJob.V2BatchJobState.IN_PROGRESS; // still draining
      case "in_progress" -> BatchJob.V2BatchJobState.IN_PROGRESS;
      default -> BatchJob.V2BatchJobState.IN_PROGRESS;
    };
  }

  /**
   * Build the {@code {"requests":[{custom_id, params}]}} create body from the per-task requests.
   */
  ObjectNode buildBatchBody(List<BatchRequest> requests) {
    ObjectNode body = objectMapper.createObjectNode();
    ArrayNode arr = body.putArray("requests");
    if (requests == null) return body;
    for (BatchRequest req : requests) {
      ObjectNode entry = arr.addObject();
      entry.put("custom_id", req.taskId());
      entry.set("params", buildParams(req));
    }
    return body;
  }

  private ObjectNode buildParams(BatchRequest req) {
    ObjectNode params = objectMapper.createObjectNode();
    params.put("model", req.model());
    params.put("max_tokens", req.maxTokens() != null ? req.maxTokens() : DEFAULT_MAX_TOKENS);
    if (req.temperature() != null) params.put("temperature", req.temperature());

    // System messages collapse into Anthropic's top-level `system` string; user/assistant become
    // the messages array. First-turn batch submits carry no tool-result (TOOL) messages.
    StringBuilder system = new StringBuilder();
    ArrayNode messages = objectMapper.createArrayNode();
    if (req.messages() != null) {
      for (Message m : req.messages()) {
        String text = m.getText() == null ? "" : m.getText();
        if (m.getMessageType() == MessageType.SYSTEM) {
          if (system.length() > 0) system.append("\n\n");
          system.append(text);
        } else if (m.getMessageType() == MessageType.USER) {
          messages.addObject().put("role", "user").put("content", text);
        } else if (m.getMessageType() == MessageType.ASSISTANT) {
          messages.addObject().put("role", "assistant").put("content", text);
        }
        // TOOL messages are intentionally skipped — batch carries the first turn only.
      }
    }
    if (system.length() > 0) params.put("system", system.toString());
    params.set("messages", messages);

    if (req.tools() != null && !req.tools().isEmpty()) {
      ArrayNode tools = params.putArray("tools");
      for (ToolCallback tc : req.tools()) {
        ObjectNode tool = tools.addObject();
        tool.put("name", tc.getToolDefinition().name());
        tool.put("description", tc.getToolDefinition().description());
        try {
          tool.set("input_schema", objectMapper.readTree(tc.getToolDefinition().inputSchema()));
        } catch (Exception e) {
          throw new IllegalArgumentException(
              "Invalid JSON schema for tool '" + tc.getToolDefinition().name() + "'", e);
        }
      }
    }
    return params;
  }

  /** Parse the JSONL results stream into per-task results, matched back by {@code custom_id}. */
  List<BatchTaskResult> parseResultsJsonl(String jsonl) {
    List<BatchTaskResult> out = new ArrayList<>();
    if (jsonl == null || jsonl.isBlank()) return out;
    for (String line : jsonl.split("\\r?\\n")) {
      if (line.isBlank()) continue;
      try {
        out.add(parseResultLine(objectMapper.readTree(line)));
      } catch (Exception e) {
        log.warn("[AnthropicBatchClient] unparseable result line: {}", e.getMessage());
      }
    }
    return out;
  }

  private BatchTaskResult parseResultLine(JsonNode node) {
    String taskId = node.path("custom_id").asText(null);
    JsonNode result = node.path("result");
    String type = result.path("type").asText("");
    if (!"succeeded".equals(type)) {
      // errored / canceled / expired → surface the type (+ any error message) to the resume layer.
      String err = result.path("error").path("message").asText(type);
      return new BatchTaskResult(taskId, null, 0, 0, type + (err.isBlank() ? "" : ": " + err));
    }
    JsonNode message = result.path("message");
    JsonNode usage = message.path("usage");
    return new BatchTaskResult(
        taskId,
        toAssistantMessage(message.path("content")),
        usage.path("input_tokens").asInt(0),
        usage.path("output_tokens").asInt(0),
        null);
  }

  /** Anthropic content blocks (text + tool_use) → Spring AI {@link AssistantMessage}. */
  private AssistantMessage toAssistantMessage(JsonNode content) {
    StringBuilder text = new StringBuilder();
    List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    if (content != null && content.isArray()) {
      for (JsonNode block : content) {
        String type = block.path("type").asText("");
        if ("text".equals(type)) {
          text.append(block.path("text").asText(""));
        } else if ("tool_use".equals(type)) {
          toolCalls.add(
              new AssistantMessage.ToolCall(
                  block.path("id").asText(""),
                  "function",
                  block.path("name").asText(""),
                  block.path("input").toString()));
        }
      }
    }
    return AssistantMessage.builder().content(text.toString()).toolCalls(toolCalls).build();
  }

  // ---- HTTP ------------------------------------------------------------------------------

  private RestClient client(String runId) {
    String key = resolveKey(runId);
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "No Anthropic API key available for run " + runId + " (BYOK or system key required)");
    }
    java.net.http.HttpClient jdk =
        java.net.http.HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
    factory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .defaultHeader("x-api-key", key)
        .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
        .build();
  }

  /** BYOK-aware: prefer the run owner's resolved key, fall back to the system key. */
  private String resolveKey(String runId) {
    String userId =
        runId == null
            ? null
            : runRepo.findById(runId).map(r -> r.getCreatedByUserId()).orElse(null);
    if (userId != null) {
      try {
        String k = apiKeyResolver.resolve(userId).anthropicKey();
        if (k != null && !k.isBlank()) return k;
      } catch (Exception e) {
        log.debug(
            "[AnthropicBatchClient] per-user key resolve failed for {}: {}",
            userId,
            e.getMessage());
      }
    }
    return systemKey;
  }
}
