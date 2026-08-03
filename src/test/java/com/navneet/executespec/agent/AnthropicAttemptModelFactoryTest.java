package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class AnthropicAttemptModelFactoryTest {

  private HttpServer server;
  private ExecutorService executor;

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
    if (executor != null) executor.shutdownNow();
  }

  @Test
  void transientHttpFailureIssuesExactlyOneSocketRequest() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    String baseUrl =
        startServer(
            exchange -> {
              requests.incrementAndGet();
              respond(exchange, 500, "{\"error\":{\"message\":\"temporary\"}}");
            });
    var model =
        new AnthropicAttemptModelFactory().create("test-key", "claude-test", 64, 5, baseUrl);

    long startedAt = System.nanoTime();
    assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("hello")))))
        .isInstanceOf(RuntimeException.class);

    assertThat(requests).hasValue(1);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void readTimeoutIsBoundToTheAdmittedAttemptWindow() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    String baseUrl =
        startServer(
            exchange -> {
              requests.incrementAndGet();
              try {
                Thread.sleep(2500);
                respond(exchange, 200, "{}");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    var model =
        new AnthropicAttemptModelFactory().create("test-key", "claude-test", 64, 1, baseUrl);

    long startedAt = System.nanoTime();
    assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("hello")))))
        .isInstanceOf(RuntimeException.class);

    assertThat(requests).hasValue(1);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
        .isBetween(Duration.ofMillis(700), Duration.ofSeconds(2));
  }

  private String startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newSingleThreadExecutor();
    server.setExecutor(executor);
    server.createContext("/v1/messages", handler);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
