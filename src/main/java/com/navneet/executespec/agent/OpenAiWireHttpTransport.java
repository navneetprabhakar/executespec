package com.navneet.executespec.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Deadline-bounded HTTP transport shared by the OpenAI-wire provider client. */
final class OpenAiWireHttpTransport {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

  private final ConcurrentHashMap<String, HttpClient> clientPool = new ConcurrentHashMap<>();

  Response execute(
      String url, String apiKey, ObjectNode body, ObjectMapper requestMapper, Duration timeout)
      throws IOException, InterruptedException {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestMapper.writeValueAsBytes(body)))
            .build();

    CompletableFuture<HttpResponse<InputStream>> pending =
        resolveClient(url).sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
    HttpResponse<InputStream> response;
    try {
      response = pending.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      pending.cancel(true);
      throw new HttpTimeoutException("OpenAI-wire response headers timed out after " + timeout);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof InterruptedException interrupted) throw interrupted;
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IOException("OpenAI-wire request failed", cause);
    }

    byte[] bytes;
    try (InputStream responseBody = response.body()) {
      bytes = readBoundedWithin(responseBody, deadlineNanos, timeout);
    }
    return new Response(response.statusCode(), response.headers().map(), bytes);
  }

  private HttpClient resolveClient(String url) {
    String authority = URI.create(url).getAuthority();
    return clientPool.computeIfAbsent(
        authority,
        ignored ->
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
  }

  private static byte[] readBoundedWithin(
      InputStream responseBody, long deadlineNanos, Duration timeout)
      throws IOException, InterruptedException {
    FutureTask<byte[]> readTask = new FutureTask<>(() -> readBounded(responseBody));
    Thread reader = Thread.ofVirtual().name("openai-wire-body-reader").start(readTask);
    try {
      return readTask.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      responseBody.close();
      reader.interrupt();
      throw new HttpTimeoutException("OpenAI-wire response body timed out after " + timeout);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IOException("OpenAI-wire response body read failed", cause);
    }
  }

  static byte[] readBounded(InputStream in) throws IOException {
    if (in == null) return new byte[0];
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int total = 0;
    int read;
    while ((read = in.read(chunk)) != -1) {
      total += read;
      if (total > MAX_RESPONSE_BYTES) {
        throw new IllegalStateException(
            "OpenAI-wire response exceeded the " + MAX_RESPONSE_BYTES + "-byte ceiling");
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  private static long remainingMillis(long deadlineNanos) throws HttpTimeoutException {
    long remainingNanos = deadlineNanos - System.nanoTime();
    if (remainingNanos <= 0) throw new HttpTimeoutException("OpenAI-wire request timed out");
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
  }

  record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {}
}
