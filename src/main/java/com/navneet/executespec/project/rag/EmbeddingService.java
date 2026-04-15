package com.navneet.executespec.project.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embeds short text passages with OpenAI text-embedding-3-small (1536 dims).
 * Uses a direct REST call so we don't depend on the Spring AI auto-configured
 * EmbeddingModel bean (which only registers when a chat-side property triggers it).
 * Shares the {@code spring.ai.openai.api-key} the rest of the platform already uses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private static final int BATCH_SIZE = 100;
    private static final String DEFAULT_BASE_URL = "https://api.openai.com";
    private static final String EMBEDDING_PATH = "/v1/embeddings";
    private static final String MODEL = "text-embedding-3-small";

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:" + DEFAULT_BASE_URL + "}")
    private String baseUrl;

    private final ObjectMapper objectMapper;

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OpenAI api-key not configured (set OPENAI_API_KEY)");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            out.addAll(callOnce(batch));
        }
        return out;
    }

    public float[] embedQuery(String text) {
        return embed(List.of(text)).get(0);
    }

    private List<float[]> callOnce(List<String> batch) {
        RestClient client = RestClient.builder().baseUrl(baseUrl).build();
        Map<String, Object> body = Map.of("model", MODEL, "input", batch);
        try {
            String response = client.post()
                    .uri(EMBEDDING_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (Exception first) {
            log.warn("OpenAI embeddings call failed, retrying once: {}", first.getMessage());
            try {
                Thread.sleep(Duration.ofSeconds(1).toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String response = client.post()
                    .uri(EMBEDDING_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        }
    }

    private List<float[]> parse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            List<float[]> out = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode embedding = item.path("embedding");
                float[] vec = new float[embedding.size()];
                for (int j = 0; j < embedding.size(); j++) {
                    vec[j] = (float) embedding.get(j).asDouble();
                }
                out.add(vec);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI embeddings response", e);
        }
    }
}
