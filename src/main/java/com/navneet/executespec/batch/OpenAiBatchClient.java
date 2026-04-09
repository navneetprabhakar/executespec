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
 * OpenAI Batch API client.
 * <p>
 * Two-step submission: upload JSONL file → create batch referencing it.
 * <p>
 * Upload:  POST /v1/files (multipart)
 * Submit:  POST /v1/batches
 * Status:  GET  /v1/batches/{id}
 * Results: GET  /v1/files/{output_file_id}/content  (JSONL)
 * Cancel:  POST /v1/batches/{id}/cancel
 */
@Slf4j
@Component
public class OpenAiBatchClient implements LlmBatchClient {

    private static final String BASE_URL = "https://api.openai.com";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OpenAiBatchClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String submitBatch(String runId, List<BatchRequest> requests, String apiKey) {
        try {
            // Step 1: Build JSONL content
            StringBuilder jsonl = new StringBuilder();
            for (BatchRequest req : requests) {
                ObjectNode line = objectMapper.createObjectNode();
                line.put("custom_id", req.customId());
                line.put("method", "POST");
                line.put("url", "/v1/chat/completions");

                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", req.model());
                body.put("max_completion_tokens", req.maxTokens());

                ArrayNode messages = objectMapper.createArrayNode();
                if (req.systemPrompt() != null && !req.systemPrompt().isBlank()) {
                    ObjectNode sysMsg = objectMapper.createObjectNode();
                    sysMsg.put("role", "system");
                    sysMsg.put("content", req.systemPrompt());
                    messages.add(sysMsg);
                }
                for (var msg : req.messages()) {
                    ObjectNode m = objectMapper.createObjectNode();
                    m.put("role", msg.get("role"));
                    m.put("content", msg.get("content"));
                    messages.add(m);
                }
                body.set("messages", messages);
                line.set("body", body);

                jsonl.append(objectMapper.writeValueAsString(line)).append("\n");
            }

            // Step 2: Upload JSONL file
            String boundary = "----BatchBoundary" + System.currentTimeMillis();
            String fileBody = buildMultipartBody(boundary, "batch_input.jsonl", jsonl.toString());

            HttpRequest uploadReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/files"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofString(fileBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> uploadResp = httpClient.send(uploadReq, HttpResponse.BodyHandlers.ofString());
            if (uploadResp.statusCode() < 200 || uploadResp.statusCode() >= 300) {
                throw new BatchSubmissionException(
                        "OpenAI file upload failed: " + uploadResp.statusCode() + " — " + uploadResp.body(),
                        uploadResp.statusCode());
            }

            String inputFileId = objectMapper.readTree(uploadResp.body()).path("id").asText();
            log.debug("[Run {}] OpenAI batch file uploaded: {}", runId, inputFileId);

            // Step 3: Create batch
            ObjectNode batchBody = objectMapper.createObjectNode();
            batchBody.put("input_file_id", inputFileId);
            batchBody.put("endpoint", "/v1/chat/completions");
            batchBody.put("completion_window", "24h");

            HttpRequest batchReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/batches"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(batchBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> batchResp = httpClient.send(batchReq, HttpResponse.BodyHandlers.ofString());
            if (batchResp.statusCode() < 200 || batchResp.statusCode() >= 300) {
                throw new BatchSubmissionException(
                        "OpenAI batch create failed: " + batchResp.statusCode() + " — " + batchResp.body(),
                        batchResp.statusCode());
            }

            String batchId = objectMapper.readTree(batchResp.body()).path("id").asText();
            log.info("[Run {}] OpenAI batch submitted: {} ({} requests)", runId, batchId, requests.size());
            return batchId;

        } catch (BatchSubmissionException e) {
            throw e;
        } catch (Exception e) {
            throw new BatchSubmissionException("OpenAI batch submit error: " + e.getMessage(), e);
        }
    }

    @Override
    public BatchStatus checkStatus(String batchId, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/batches/" + batchId))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());

            String status = node.path("status").asText("unknown");
            String outputFileId = node.path("output_file_id").asText(null);
            JsonNode counts = node.path("request_counts");
            int completed = counts.path("completed").asInt(0);
            int failed = counts.path("failed").asInt(0);
            int total = counts.path("total").asInt(0);

            log.info("[Batch {}] OpenAI status: {}, completed: {}/{}, output_file: {}, error_file: {}",
                    batchId, status, completed, total, outputFileId, node.path("error_file_id").asText(null));

            // OpenAI may report "completed" before output_file_id is ready —
            // treat as IN_PROGRESS until the file is available
            BatchStatus.State mappedState = mapOpenAiState(status);
            if (mappedState == BatchStatus.State.COMPLETED
                    && (outputFileId == null || outputFileId.isBlank())) {
                log.info("[Batch {}] OpenAI reports completed but output file not ready — treating as in_progress", batchId);
                mappedState = BatchStatus.State.IN_PROGRESS;
            }

            return new BatchStatus(
                    mappedState,
                    completed + failed,
                    total,
                    failed > 0 ? failed + " request(s) failed" : null
            );
        } catch (Exception e) {
            log.error("OpenAI batch status check failed for {}: {}", batchId, e.getMessage());
            return new BatchStatus(BatchStatus.State.FAILED, 0, 0, e.getMessage());
        }
    }

    @Override
    public BatchResults getResults(String batchId, String apiKey) {
        try {
            // First get the output_file_id
            HttpRequest statusReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/batches/" + batchId))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> statusResp = httpClient.send(statusReq, HttpResponse.BodyHandlers.ofString());
            JsonNode statusNode = objectMapper.readTree(statusResp.body());
            String status = statusNode.path("status").asText("unknown");
            String outputFileId = statusNode.path("output_file_id").asText(null);
            log.info("[Batch {}] Status: {}, output_file_id: {}, error_file_id: {}",
                    batchId, status, outputFileId, statusNode.path("error_file_id").asText(null));

            if (outputFileId == null || outputFileId.isBlank()) {
                throw new IllegalStateException("OpenAI batch " + batchId + " has no output file yet (status=" + status + ")");
            }

            // Download the output file
            HttpRequest fileReq = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/files/" + outputFileId + "/content"))
                    .header("Authorization", "Bearer " + apiKey)
                    .GET()
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> fileResp = httpClient.send(fileReq, HttpResponse.BodyHandlers.ofString());
            log.info("[Batch {}] OpenAI results file downloaded: {} bytes, status {}",
                    batchId, fileResp.body().length(), fileResp.statusCode());
            List<BatchResult> results = new ArrayList<>();

            // JSONL — one result per line
            try (BufferedReader reader = new BufferedReader(new StringReader(fileResp.body()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode entry = objectMapper.readTree(line);
                    String customId = entry.path("custom_id").asText();

                    JsonNode response = entry.path("response");
                    int statusCode = response.path("status_code").asInt(200);

                    if (statusCode != 200) {
                        results.add(new BatchResult(customId, null, 0, 0,
                                "HTTP " + statusCode));
                        continue;
                    }

                    JsonNode body = response.path("body");
                    JsonNode choice = body.path("choices").path(0);
                    String content = choice.path("message").path("content").asText("");
                    String finishReason = choice.path("finish_reason").asText("stop");

                    JsonNode usage = body.path("usage");
                    results.add(new BatchResult(
                            customId,
                            content,
                            usage.path("prompt_tokens").asInt(0),
                            usage.path("completion_tokens").asInt(0),
                            finishReason
                    ));
                }
            }

            return new BatchResults(batchId, results);
        } catch (Exception e) {
            throw new BatchSubmissionException("OpenAI batch results error: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelBatch(String batchId, String apiKey) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/v1/batches/" + batchId + "/cancel"))
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(30))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("OpenAI batch {} cancel requested", batchId);
        } catch (Exception e) {
            log.error("OpenAI batch cancel failed for {}: {}", batchId, e.getMessage());
        }
    }

    private static BatchStatus.State mapOpenAiState(String status) {
        return switch (status) {
            case "validating", "in_progress", "finalizing" -> BatchStatus.State.IN_PROGRESS;
            case "completed"    -> BatchStatus.State.COMPLETED;
            case "failed"       -> BatchStatus.State.FAILED;
            case "expired"      -> BatchStatus.State.EXPIRED;
            case "cancelling"   -> BatchStatus.State.CANCELING;
            case "cancelled"    -> BatchStatus.State.FAILED;
            default             -> BatchStatus.State.PENDING;
        };
    }

    private static String buildMultipartBody(String boundary, String filename, String content) {
        return "--" + boundary + "\r\n" +
               "Content-Disposition: form-data; name=\"purpose\"\r\n\r\nbatch\r\n" +
               "--" + boundary + "\r\n" +
               "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n" +
               "Content-Type: application/jsonl\r\n\r\n" +
               content + "\r\n" +
               "--" + boundary + "--\r\n";
    }

}
