package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

class OpenAiNativeToolClientProviderStartFenceTest {

  private MockWebServer server;
  private OpenAiNativeToolClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    client = new OpenAiNativeToolClient(new ObjectMapper());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  @Test
  void callbackRunsAfterLocalPreflightAndImmediatelyBeforeTransport() throws Exception {
    AtomicInteger providerStarted = new AtomicInteger();
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1}}
                """));

    var response =
        client.call(
            request(
                "test-key",
                () -> {
                  assertThat(server.getRequestCount()).isZero();
                  providerStarted.incrementAndGet();
                }));

    assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    assertThat(providerStarted).hasValue(1);
    assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void callbackFailureBlocksSocketIo() {
    assertThatThrownBy(
            () ->
                client.call(
                    request(
                        "test-key",
                        () -> {
                          throw new IllegalStateException("durable provider-start fence lost");
                        })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("durable provider-start fence lost");

    assertThat(server.getRequestCount()).isZero();
  }

  @Test
  void missingCredentialFailsLocalPreflightWithoutSignallingProviderStart() {
    AtomicInteger providerStarted = new AtomicInteger();

    assertThatThrownBy(() -> client.call(request(null, providerStarted::incrementAndGet)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("api-key not configured");

    assertThat(providerStarted).hasValue(0);
    assertThat(server.getRequestCount()).isZero();
  }

  private OpenAiNativeToolClient.OpenAiToolCallRequest request(String apiKey, Runnable callback) {
    return OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
        .model("deepseek-test")
        .messages(List.of(new UserMessage("hello")))
        .tools(List.of())
        .apiKey(apiKey)
        .baseUrl(server.url("/").toString())
        .physicalAttemptStartedCallback(callback)
        .build();
  }
}
