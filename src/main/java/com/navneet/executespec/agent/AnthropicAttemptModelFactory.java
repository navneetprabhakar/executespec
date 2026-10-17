package com.navneet.executespec.agent;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;

/** Builds one bounded, non-retrying Spring Anthropic model for one admitted physical attempt. */
@Component
@RequiredArgsConstructor
public class AnthropicAttemptModelFactory {

  private final AnthropicWorkspaceHeaders workspaceHeaders;

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
  private static final RetryTemplate SINGLE_ATTEMPT =
      RetryTemplate.builder().maxAttempts(1).build();

  public AnthropicChatModel create(
      String apiKey, String model, Integer maxTokens, int readTimeoutSeconds) {
    return create(apiKey, model, maxTokens, readTimeoutSeconds, AnthropicApi.DEFAULT_BASE_URL);
  }

  /** Package-private base-URL seam for transport-level tests. */
  AnthropicChatModel create(
      String apiKey, String model, Integer maxTokens, int readTimeoutSeconds, String baseUrl) {
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("Anthropic api-key not configured");
    }
    Duration readTimeout = Duration.ofSeconds(Math.max(1, readTimeoutSeconds));
    Duration connectTimeout =
        readTimeout.compareTo(CONNECT_TIMEOUT) < 0 ? readTimeout : CONNECT_TIMEOUT;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);
    RestClient.Builder restClient =
        workspaceHeaders.apply(RestClient.builder().requestFactory(requestFactory), apiKey);
    AnthropicApi api =
        AnthropicApi.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .restClientBuilder(restClient)
            .build();
    AnthropicChatOptions.Builder options = AnthropicChatOptions.builder().model(model);
    if (maxTokens != null && maxTokens > 0) options.maxTokens(maxTokens);
    return AnthropicChatModel.builder()
        .anthropicApi(api)
        .defaultOptions(options.build())
        .retryTemplate(SINGLE_ATTEMPT)
        .build();
  }
}
