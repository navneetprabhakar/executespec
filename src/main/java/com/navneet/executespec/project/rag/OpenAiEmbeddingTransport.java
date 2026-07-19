package com.navneet.executespec.project.rag;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;

/**
 * Single physical OpenAI embeddings attempt; policy and attribution live in {@link
 * EmbeddingService}.
 */
@Component
@RequiredArgsConstructor
class OpenAiEmbeddingTransport {

  record Result(List<float[]> vectors, long inputTokens) {}

  private final ObjectMapper objectMapper;

  @Value("${spring.ai.openai.base-url:https://api.openai.com}")
  private String baseUrl;

  @Value("${project.rag.embedding.openai.dimensions:1536}")
  private int dimensions = 1536;

  @Value("${project.rag.embedding.openai.connect-timeout-millis:5000}")
  private int connectTimeoutMillis = 5000;

  @Value("${project.rag.embedding.openai.read-timeout-millis:60000}")
  private int readTimeoutMillis = 60000;

  Result embed(String apiKey, String model, List<String> texts) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("Resolved OpenAI embedding credential is unavailable");
    }
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMillis)))
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMillis)));
    String response =
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build()
            .post()
            .uri("/v1/embeddings")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("model", model, "input", texts))
            .retrieve()
            .body(String.class);
    return parse(response, texts.size());
  }

  private Result parse(String response, int expected) {
    try {
      JsonNode root = objectMapper.readTree(response);
      List<JsonNode> ordered = new ArrayList<>();
      root.path("data").forEach(ordered::add);
      ordered.sort(Comparator.comparingInt(node -> node.path("index").asInt(-1)));
      if (ordered.size() != expected) {
        throw new IllegalStateException("Embedding response count mismatch");
      }
      List<float[]> vectors = new ArrayList<>(expected);
      for (int i = 0; i < ordered.size(); i++) {
        JsonNode item = ordered.get(i);
        if (item.path("index").asInt(-1) != i) {
          throw new IllegalStateException("Embedding response index mismatch");
        }
        JsonNode embedding = item.path("embedding");
        if (!embedding.isArray() || embedding.size() != dimensions) {
          throw new IllegalStateException("Embedding response dimension mismatch");
        }
        float[] vector = new float[dimensions];
        for (int j = 0; j < dimensions; j++) vector[j] = (float) embedding.get(j).asDouble();
        vectors.add(vector);
      }
      return new Result(
          List.copyOf(vectors), Math.max(0, root.path("usage").path("prompt_tokens").asLong(0)));
    } catch (RuntimeException malformed) {
      throw malformed;
    } catch (Exception malformed) {
      throw new IllegalStateException("Embedding response could not be parsed", malformed);
    }
  }
}
