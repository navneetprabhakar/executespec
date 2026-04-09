package com.navneet.executespec.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Messages Batches API client.
 * <p>
 * Submit:  POST /v1/messages/batches
 * Status:  GET  /v1/messages/batches/{id}
 * Results: GET  /v1/messages/batches/{id}/results  (streaming JSONL)
 * Cancel:  POST /v1/messages/batches/{id}/cancel
 */
@Slf4j
@Component
public class AnthropicBatchClient implements LlmBatchClient {

    private static final String BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";
    private static final String BETA_HEADER = "message-batches-2024-09-24";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AnthropicBatchClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String submitBatch(String runId, List<BatchRequest> requests, String apiKey) {
        try {
            ArrayNode requestsArray = objectMapper.createArrayNode();
            for (BatchRequest req : requests) {
                ObjectNode entry = objectMapper.createObjectNode();
                entry.put("custom_id", req.customId());

                ObjectNode params = objectMapper.createObjectNode();
                params.put("model", req.model());
                params.put("max_tokens", req.maxTokens());

                if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                    params.put("system", req.systemPrompt());
                }

                ArrayNode messages = objectMapper.createArrayNode();
                for (var msg : req.messages()) {
                    ObjectNode m = objectMapper.createObjectNode();
                    m.put("role", msg.get("role"));
                    m.put("content", msg.get("content"));
                    messages.add(m);
                }
                params.set("messages", messages);
                entry.set("params", params);
                requestsArray.add(entry);
            }

            ObjectNode body = objectMapper.createObjectNode();
            body.set("requests", requestsArray);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/messages/batches"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("anthropic-beta", BETA_HEADER)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BatchSubmissionException(
                        "Anthropic batch submit failed: " + resp.statusCode() + " — " + resp.body(),
                        resp.statusCode());
            }

            JsonNode result = objectMapper.readTree(resp.body());
            String batchId = result.path("id").asText();
            log.info("[Run {}] Anthropic batch submitted: {} ({} requests)", runId, batchId, requests.size());
            return batchId;

        } catch (BatchSubmissionException e) {
            throw e;
        } catch (Exception e) {
            throw new BatchSubmissionException("Anthropic batch submit error: " + e.getMessage(), e);
        }
    }

    @Override
    public BatchStatus checkStatus(String batchId, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/messages/batches/" + batchId))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("anthropic-beta", BETA_HEADER)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());

            String status = node.path("processing_status").asText("unknown");
            JsonNode counts = node.path("request_counts");
            int succeeded = counts.path("succeeded").asInt(0);
            int errored = counts.path("errored").asInt(0);
            int expired = counts.path("expired").asInt(0);
            int canceled = counts.path("canceled").asInt(0);
            int processing = counts.path("processing").asInt(0);

            int completed = succeeded + errored + expired + canceled;
            int total = completed + processing;

            return new BatchStatus(
                    mapAnthropicState(status),
                    completed,
                    total,
                    errored > 0 ? errored + " request(s) errored" : null
            );
        } catch (Exception e) {
            log.error("Anthropic batch status check failed for {}: {}", batchId, e.getMessage());
            return new BatchStatus(BatchStatus.State.FAILED, 0, 0, e.getMessage());
        }
    }

    @Override
    public BatchResults getResults(String batchId, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/messages/batches/" + batchId + "/results"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("anthropic-beta", BETA_HEADER)
                    .GET()
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            List<BatchResult> results = new ArrayList<>();

            // Response is streaming JSONL — one JSON object per line
            try (BufferedReader reader = new BufferedReader(new StringReader(resp.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode entry = objectMapper.readTree(line);
                    String customId = entry.path("custom_id").asText();

                    JsonNode result = entry.path("result");
                    if (!"succeeded".equals(result.path("type").asText())) {
                        results.add(new BatchResult(customId, null, 0, 0,
                                result.path("error").path("message").asText("batch_error")));
                        continue;
                    }

                    JsonNode message = result.path("message");
                    StringBuilder content = new StringBuilder();
                    for (JsonNode block : message.path("content")) {
                        if ("text".equals(block.path("type").asText())) {
                            content.append(block.path("text").asText());
                        }
                    }

                    JsonNode usage = message.path("usage");
                    results.add(new BatchResult(
                            customId,
                            content.toString(),
                            usage.path("input_tokens").asInt(0),
                            usage.path("output_tokens").asInt(0),
                            message.path("stop_reason").asText("end_turn")
                    ));
                }
            }

            return new BatchResults(batchId, results);
        } catch (Exception e) {
            throw new BatchSubmissionException("Anthropic batch results error: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelBatch(String batchId, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/messages/batches/" + batchId + "/cancel"))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", API_VERSION)
                    .header("anthropic-beta", BETA_HEADER)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Anthropic batch {} cancel requested", batchId);
        } catch (Exception e) {
            log.error("Anthropic batch cancel failed for {}: {}", batchId, e.getMessage());
        }
    }

    private static BatchStatus.State mapAnthropicState(String status) {
        return switch (status) {
            case "in_progress" -> BatchStatus.State.IN_PROGRESS;
            case "ended"       -> BatchStatus.State.COMPLETED;
            case "canceling"   -> BatchStatus.State.CANCELING;
            case "expired"     -> BatchStatus.State.EXPIRED;
            default            -> BatchStatus.State.PENDING;
        };
    }
}
