package com.navneet.executespec.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Generative AI Batch API client.
 * <p>
 * Uses inline mode for small batches ({@code <= googleInlineTaskLimit}),
 * file-based upload for larger batches.
 * <p>
 * Submit:  POST /v1beta/models/{model}:batchGenerateContent
 * Status:  GET  /v1beta/batches/{id}
 * Results: Inline in batch response or file-based output
 * Cancel:  DELETE /v1beta/batches/{id}
 */
@Slf4j
@Component
public class GoogleBatchClient implements LlmBatchClient {

    private static final String BASE_URL = "https://generativelanguage.googleapis.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final int inlineTaskLimit;

    public GoogleBatchClient(ObjectMapper objectMapper, AgentProperties agentProperties) {
        this.objectMapper = objectMapper;
        this.inlineTaskLimit = agentProperties.getCost().getBatch().getGoogleInlineTaskLimit();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String submitBatch(String runId, List<BatchRequest> requests, String apiKey) {
        try {
            if (requests.isEmpty()) {
                throw new BatchSubmissionException("No requests to submit");
            }

            // Use the model from the first request (all tasks use the same model)
            String model = requests.getFirst().model();

            if (requests.size() <= inlineTaskLimit) {
                return submitInline(runId, requests, model, apiKey);
            } else {
                return submitFileBased(runId, requests, model, apiKey);
            }
        } catch (BatchSubmissionException e) {
            throw e;
        } catch (Exception e) {
            throw new BatchSubmissionException("Google batch submit error: " + e.getMessage(), e);
        }
    }

    private String submitInline(String runId, List<BatchRequest> requests, String model, String apiKey)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode requestsArray = objectMapper.createArrayNode();

        for (BatchRequest req : requests) {
            ObjectNode request = objectMapper.createObjectNode();

            // Build contents array
            ArrayNode contents = objectMapper.createArrayNode();
            for (var msg : req.messages()) {
                ObjectNode content = objectMapper.createObjectNode();
                String role = "user".equals(msg.get("role")) ? "user" : "model";
                content.put("role", role);
                ArrayNode parts = objectMapper.createArrayNode();
                ObjectNode textPart = objectMapper.createObjectNode();
                textPart.put("text", msg.get("content"));
                parts.add(textPart);
                content.set("parts", parts);
                contents.add(content);
            }
            request.set("contents", contents);

            // System instruction
            if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                ObjectNode systemInstruction = objectMapper.createObjectNode();
                ArrayNode sysParts = objectMapper.createArrayNode();
                ObjectNode sysTextPart = objectMapper.createObjectNode();
                sysTextPart.put("text", req.systemPrompt());
                sysParts.add(sysTextPart);
                systemInstruction.set("parts", sysParts);
                request.set("systemInstruction", systemInstruction);
            }

            // Generation config
            ObjectNode genConfig = objectMapper.createObjectNode();
            genConfig.put("maxOutputTokens", req.maxTokens());
            request.set("generationConfig", genConfig);

            // Custom ID via metadata (Google uses labels for tracking)
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("customId", req.customId());
            request.set("metadata", metadata);

            requestsArray.add(request);
        }
        body.set("requests", requestsArray);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1beta/models/" + model + ":batchGenerateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new BatchSubmissionException(
                    "Google batch submit failed: " + resp.statusCode() + " — " + resp.body(),
                    resp.statusCode());
        }

        JsonNode result = objectMapper.readTree(resp.body());
        // Inline responses come back immediately for small batches
        String batchId = result.has("name") ? result.path("name").asText()
                : "google-inline-" + runId;
        log.info("[Run {}] Google batch submitted (inline): {} ({} requests)", runId, batchId, requests.size());
        return batchId;
    }

    private String submitFileBased(String runId, List<BatchRequest> requests, String model, String apiKey)
            throws Exception {
        // Build JSONL content for file upload
        StringBuilder jsonl = new StringBuilder();
        for (BatchRequest req : requests) {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("customId", req.customId());

            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", "models/" + model);

            ArrayNode contents = objectMapper.createArrayNode();
            for (var msg : req.messages()) {
                ObjectNode content = objectMapper.createObjectNode();
                content.put("role", "user".equals(msg.get("role")) ? "user" : "model");
                ArrayNode parts = objectMapper.createArrayNode();
                ObjectNode textPart = objectMapper.createObjectNode();
                textPart.put("text", msg.get("content"));
                parts.add(textPart);
                content.set("parts", parts);
                contents.add(content);
            }
            request.set("contents", contents);

            if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                ObjectNode systemInstruction = objectMapper.createObjectNode();
                ArrayNode sysParts = objectMapper.createArrayNode();
                ObjectNode sysTextPart = objectMapper.createObjectNode();
                sysTextPart.put("text", req.systemPrompt());
                sysParts.add(sysTextPart);
                systemInstruction.set("parts", sysParts);
                request.set("systemInstruction", systemInstruction);
            }

            ObjectNode genConfig = objectMapper.createObjectNode();
            genConfig.put("maxOutputTokens", req.maxTokens());
            request.set("generationConfig", genConfig);

            line.set("request", request);
            jsonl.append(objectMapper.writeValueAsString(line)).append("\n");
        }

        // Upload file
        String boundary = "----GoogleBatchBoundary" + System.currentTimeMillis();
        String fileBody = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"batch_input.jsonl\"\r\n" +
                "Content-Type: application/jsonl\r\n\r\n" +
                jsonl + "\r\n" +
                "--" + boundary + "--\r\n";

        HttpRequest uploadReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/upload/v1beta/files?key=" + apiKey))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(fileBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> uploadResp = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
        if (uploadResp.statusCode() < 200 || uploadResp.statusCode() >= 300) {
            throw new BatchSubmissionException(
                    "Google file upload failed: " + uploadResp.statusCode() + " — " + uploadResp.body(),
                    uploadResp.statusCode());
        }

        String fileUri = objectMapper.readTree(uploadResp.body()).path("file").path("uri").asText();

        // Create batch job referencing uploaded file
        ObjectNode batchBody = objectMapper.createObjectNode();
        batchBody.put("model", "models/" + model);
        ObjectNode srcNode = objectMapper.createObjectNode();
        srcNode.put("fileUri", fileUri);
        batchBody.set("src", srcNode);

        HttpRequest batchReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1beta/batches?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(batchBody)))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> batchResp = httpClient.send(batchReq, HttpResponse.BodyHandlers.ofString());
        if (batchResp.statusCode() < 200 || batchResp.statusCode() >= 300) {
            throw new BatchSubmissionException(
                    "Google batch create failed: " + batchResp.statusCode() + " — " + batchResp.body(),
                    batchResp.statusCode());
        }

        String batchId = objectMapper.readTree(batchResp.body()).path("name").asText();
        log.info("[Run {}] Google batch submitted (file): {} ({} requests)", runId, batchId, requests.size());
        return batchId;
    }

    @Override
    public BatchStatus checkStatus(String batchId, String apiKey) {
        try {
            // Inline batches are immediately complete
            if (batchId.startsWith("google-inline-")) {
                return new BatchStatus(BatchStatus.State.COMPLETED, 0, 0, null);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1beta/" + batchId + "?key=" + apiKey))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());

            String state = node.path("state").asText("JOB_STATE_UNSPECIFIED");
            int total = node.path("totalCount").asInt(0);
            int succeeded = node.path("succeededCount").asInt(0);
            int failed = node.path("failedCount").asInt(0);

            return new BatchStatus(
                    mapGoogleState(state),
                    succeeded + failed,
                    total,
                    failed > 0 ? failed + " request(s) failed" : null
            );
        } catch (Exception e) {
            log.error("Google batch status check failed for {}: {}", batchId, e.getMessage());
            return new BatchStatus(BatchStatus.State.FAILED, 0, 0, e.getMessage());
        }
    }

    @Override
    public BatchResults getResults(String batchId, String apiKey) {
        try {
            // For inline batches, results were returned in the submit response
            // The caller should have cached them — this is a fallback
            if (batchId.startsWith("google-inline-")) {
                log.warn("Google inline batch {} results not cached — returning empty", batchId);
                return new BatchResults(batchId, List.of());
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1beta/" + batchId + "?key=" + apiKey))
                    .GET()
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());

            List<BatchResult> results = new ArrayList<>();
            JsonNode responses = node.path("responses");
            if (responses.isArray()) {
                for (JsonNode entry : responses) {
                    String customId = entry.path("metadata").path("customId").asText(
                            "task-" + results.size());

                    JsonNode candidate = entry.path("candidates").path(0);
                    StringBuilder content = new StringBuilder();
                    for (JsonNode part : candidate.path("content").path("parts")) {
                        content.append(part.path("text").asText());
                    }

                    JsonNode usage = entry.path("usageMetadata");
                    results.add(new BatchResult(
                            customId,
                            content.toString(),
                            usage.path("promptTokenCount").asInt(0),
                            usage.path("candidatesTokenCount").asInt(0),
                            candidate.path("finishReason").asText("STOP")
                    ));
                }
            }

            return new BatchResults(batchId, results);
        } catch (Exception e) {
            throw new BatchSubmissionException("Google batch results error: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelBatch(String batchId, String apiKey) {
        try {
            if (batchId.startsWith("google-inline-")) return;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1beta/" + batchId + "?key=" + apiKey))
                    .DELETE()
                    .timeout(Duration.ofSeconds(30))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Google batch {} cancel requested", batchId);
        } catch (Exception e) {
            log.error("Google batch cancel failed for {}: {}", batchId, e.getMessage());
        }
    }

    private static BatchStatus.State mapGoogleState(String state) {
        return switch (state) {
            case "JOB_STATE_PENDING"   -> BatchStatus.State.PENDING;
            case "JOB_STATE_RUNNING"   -> BatchStatus.State.IN_PROGRESS;
            case "JOB_STATE_SUCCEEDED" -> BatchStatus.State.COMPLETED;
            case "JOB_STATE_FAILED"    -> BatchStatus.State.FAILED;
            case "JOB_STATE_CANCELLED" -> BatchStatus.State.FAILED;
            default                    -> BatchStatus.State.PENDING;
        };
    }
}
