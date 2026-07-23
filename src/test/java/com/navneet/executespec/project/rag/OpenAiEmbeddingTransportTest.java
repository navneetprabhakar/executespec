package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

class OpenAiEmbeddingTransportTest {

  @Test
  void boundsAProviderThatAcceptsTheConnectionButNeverResponds() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          try {
            Thread.sleep(2_000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      OpenAiEmbeddingTransport transport = new OpenAiEmbeddingTransport(new ObjectMapper());
      ReflectionTestUtils.setField(
          transport, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
      ReflectionTestUtils.setField(transport, "dimensions", 1);
      ReflectionTestUtils.setField(transport, "connectTimeoutMillis", 250);
      ReflectionTestUtils.setField(transport, "readTimeoutMillis", 100);

      long started = System.nanoTime();
      assertThatThrownBy(() -> transport.embed("test-key", "embedding-test", List.of("hello")))
          .isInstanceOf(ResourceAccessException.class)
          .hasMessageContaining("I/O error");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsMissingCredentialsBeforeOpeningTransport() {
    OpenAiEmbeddingTransport transport = new OpenAiEmbeddingTransport(new ObjectMapper());

    assertThatThrownBy(() -> transport.embed(" ", "embedding-test", List.of("hello")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Resolved OpenAI embedding credential is unavailable");
  }
}
