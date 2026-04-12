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
@Deprecated(since = "v2")
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
    @Deprecated(since = "v2")
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

    @Deprecated(since = "v2")
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

    @Deprecated(since = "v2")
    private String submitFileBased(String runId, List<BatchRequest> requests, String model, String apiKey)
            throws Exception {
        // Build JSONL content — each line: {"key": "<customId>", "request": {generateContent body}}
        StringBuilder jsonl = new StringBuilder();
        for (BatchRequest req : requests) {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("key", req.customId());

            ObjectNode request = objectMapper.createObjectNode();

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

        // Upload JSONL file via Google File API
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

        String fileName = objectMapper.readTree(uploadResp.body()).path("file").path("name").asText();
        log.info("[Run {}] Google file uploaded: {}", runId, fileName);

        // Create async batch job via POST /v1beta/models/{model}:batchGenerateContent
        ObjectNode batchBody = objectMapper.createObjectNode();
        ObjectNode batch = objectMapper.createObjectNode();
        batch.put("display_name", "executespec-" + runId);
        ObjectNode inputConfig = objectMapper.createObjectNode();
        inputConfig.put("file_name", fileName);
        batch.set("input_config", inputConfig);
        batchBody.set("batch", batch);

        HttpRequest batchReq = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/v1beta/models/" + model + ":batchGenerateContent?key=" + apiKey))
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
    @Deprecated(since = "v2")
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

            // Google uses metadata.state or top-level state
            String state = node.path("metadata").path("state").asText(
                    node.path("state").asText("JOB_STATE_UNSPECIFIED"));
            int total = node.path("metadata").path("totalCount").asInt(
                    node.path("totalCount").asInt(0));
            int succeeded = node.path("metadata").path("succeededCount").asInt(
                    node.path("succeededCount").asInt(0));
            int failed = node.path("metadata").path("failedCount").asInt(
                    node.path("failedCount").asInt(0));

            log.debug("[Batch {}] Google status: state={}, total={}, succeeded={}, failed={}",
                    batchId, state, total, succeeded, failed);

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
    @Deprecated(since = "v2")
    public BatchResults getResults(String batchId, String apiKey) {
        try {
            // For inline batches, results were returned in the submit response
            // The caller should have cached them — this is a fallback
            if (batchId.startsWith("google-inline-")) {
                log.warn("Google inline batch {} results not cached — returning empty", batchId);
                return new BatchResults(batchId, List.of());
            }

            // 1. Get batch status to find the response file name
            HttpRequest statusReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1beta/" + batchId + "?key=" + apiKey))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
            JsonNode statusNode = objectMapper.readTree(statusResp.body());

            // Check for inlined responses first (small batches may return inline)
            JsonNode inlinedResponses = statusNode.path("response").path("inlinedResponses");
            if (inlinedResponses.isArray() && !inlinedResponses.isEmpty()) {
                return parseInlinedResponses(batchId, inlinedResponses);
            }

            // 2. For file-based results, download the response file
            String responsesFileName = statusNode.path("response").path("responsesFileName").asText(null);
            if (responsesFileName == null || responsesFileName.isBlank()) {
                // Fallback: check dest.file_name (alternative response format)
                responsesFileName = statusNode.path("dest").path("file_name").asText(null);
            }
            if (responsesFileName == null || responsesFileName.isBlank()) {
                log.warn("Google batch {} — no response file found in status: {}", batchId, statusResp.body());
                return new BatchResults(batchId, List.of());
            }

            HttpRequest downloadReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/download/v1beta/" + responsesFileName + ":download?alt=media&key=" + apiKey))
                    .GET()
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> downloadResp = httpClient.send(downloadReq, HttpResponse.BodyHandlers.ofString());
            if (downloadResp.statusCode() < 200 || downloadResp.statusCode() >= 300) {
                throw new BatchSubmissionException(
                        "Google batch results download failed: " + downloadResp.statusCode() + " — " + downloadResp.body());
            }

            // 3. Parse JSONL response — each line: {"key": "...", "response": {GenerateContentResponse}}
            List<BatchResult> results = new ArrayList<>();
            for (String line : downloadResp.body().split("\n")) {
                if (line.isBlank()) continue;
                JsonNode entry = objectMapper.readTree(line);
                String customId = entry.path("key").asText("task-" + results.size());
                JsonNode response = entry.path("response");

                JsonNode candidate = response.path("candidates").path(0);
                StringBuilder content = new StringBuilder();
                for (JsonNode part : candidate.path("content").path("parts")) {
                    content.append(part.path("text").asText());
                }

                JsonNode usage = response.path("usageMetadata");
                results.add(new BatchResult(
                        customId,
                        content.toString(),
                        usage.path("promptTokenCount").asInt(0),
                        usage.path("candidatesTokenCount").asInt(0),
                        candidate.path("finishReason").asText("STOP")
                ));
            }

            log.info("[Batch {}] Downloaded {} results from Google", batchId, results.size());
            return new BatchResults(batchId, results);
        } catch (BatchSubmissionException e) {
            throw e;
        } catch (Exception e) {
            throw new BatchSubmissionException("Google batch results error: " + e.getMessage(), e);
        }
    }

    @Deprecated(since = "v2")
    private BatchResults parseInlinedResponses(String batchId, JsonNode inlinedResponses) {
        List<BatchResult> results = new ArrayList<>();
        for (JsonNode entry : inlinedResponses) {
            String customId = entry.path("key").asText("task-" + results.size());
            JsonNode response = entry.path("response");

            JsonNode candidate = response.path("candidates").path(0);
            StringBuilder content = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                content.append(part.path("text").asText());
            }

            JsonNode usage = response.path("usageMetadata");
            results.add(new BatchResult(
                    customId,
                    content.toString(),
                    usage.path("promptTokenCount").asInt(0),
                    usage.path("candidatesTokenCount").asInt(0),
                    candidate.path("finishReason").asText("STOP")
            ));
        }
        return new BatchResults(batchId, results);
    }

    @Override
    @Deprecated(since = "v2")
    public void cancelBatch(String batchId, String apiKey) {
        try {
            if (batchId.startsWith("google-inline-")) return;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1beta/" + batchId + ":cancel?key=" + apiKey))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("Google batch {} cancel requested", batchId);
        } catch (Exception e) {
            log.error("Google batch cancel failed for {}: {}", batchId, e.getMessage());
        }
    }

    @Deprecated(since = "v2")
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
