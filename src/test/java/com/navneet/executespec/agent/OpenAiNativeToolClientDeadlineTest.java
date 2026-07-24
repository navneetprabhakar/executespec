package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;

class OpenAiNativeToolClientDeadlineTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final OpenAiWireHttpTransport transport = new OpenAiWireHttpTransport();

  @Test
  void abortsWhenProviderNeverReturnsHeaders() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
      server.start();

      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  transport.execute(
                      server.url("/v1/chat/completions").toString(),
                      "test-key",
                      requestBody(),
                      mapper,
                      Duration.ofMillis(150)))
          .hasMessageContaining("timed out");

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
  }

  @Test
  void abortsWhenProviderReturnsHeadersButStallsBody() throws Exception {
    try (MockWebServer server = new MockWebServer()) {
      server.enqueue(
          new MockResponse()
              .setResponseCode(200)
              .setHeader("Content-Type", "application/json")
              .setBodyDelay(5, TimeUnit.SECONDS)
              .setBody("{\"choices\":[]}"));
      server.start();

      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  transport.execute(
                      server.url("/v1/chat/completions").toString(),
                      "test-key",
                      requestBody(),
                      mapper,
                      Duration.ofMillis(150)))
          .hasMessageContaining("timed out");

      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
  }

  private ObjectNode requestBody() {
    ObjectNode body = mapper.createObjectNode();
    body.put("model", "test-model");
    body.putArray("messages");
    return body;
  }
}
