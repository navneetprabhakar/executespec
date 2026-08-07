package com.navneet.executespec.agent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import lombok.extern.slf4j.Slf4j;

/**
 * Streamed transport for OpenAI-wire {@code chat/completions} (OpenAI, OpenRouter, DeepSeek,
 * Fireworks).
 *
 * <p>Returns the assembled response as the SAME JSON shape a non-streaming call returns, so the
 * caller's existing parse/normalize/billing path is reused verbatim. See {@link
 * OpenAiWireStreamAssembler} for the folding rules and {@link StreamingTransportPolicy} for why
 * time-to-first-token — not the inter-chunk gap — is the deadline that separates a dead route from
 * a slow one.
 */
@Slf4j
final class OpenAiWireStreamTransport {

  private OpenAiWireStreamTransport() {}

  /**
   * Bounded read for an ERROR body, so a hostile gateway cannot exhaust heap on the failure path.
   */
  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  static JsonNode stream(
      RestClient client,
      String completionsPath,
      String apiKey,
      ObjectNode body,
      StreamingTransportPolicy policy,
      Duration callerCeiling,
      Runnable onAttemptStart,
      String requestedModel,
      ObjectMapper boundedMapper,
      long maxResponseBytes) {

    body.put("stream", true);
    // Without this the streamed response carries no usage at all and every call would look free.
    body.putObject("stream_options").put("include_usage", true);

    OpenAiWireStreamAssembler assembler =
        new OpenAiWireStreamAssembler(boundedMapper, maxResponseBytes);
    Duration ttft = policy.timeToFirstToken();
    Duration total = policy.totalFor(callerCeiling);
    Duration interChunk = policy.interChunk();

    CompletableFuture<Void> firstChunk = new CompletableFuture<>();
    AtomicLong lastChunkNanos = new AtomicLong(System.nanoTime());

    if (onAttemptStart != null) onAttemptStart.run();
    long startedNanos = System.nanoTime();

    // Read on a virtual thread so the TTFT deadline is precise rather than an artifact of the
    // socket read timeout: a route that accepts the request and then goes silent is caught in
    // seconds instead of burning the whole window.
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    CompletableFuture<Void> pump =
        CompletableFuture.runAsync(
            () ->
                client
                    .post()
                    .uri(completionsPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .body(body)
                    .exchange(
                        (clientRequest, clientResponse) -> {
                          var status = clientResponse.getStatusCode();
                          if (status.isError()) {
                            throw errorFor(clientResponse.getStatusCode(), clientResponse);
                          }
                          assembler.consume(
                              clientResponse.getBody(),
                              () -> {
                                lastChunkNanos.set(System.nanoTime());
                                firstChunk.complete(null);
                              });
                          return null;
                        }),
            executor);

    try {
      awaitFirstToken(firstChunk, pump, ttft);
      awaitCompletion(pump, startedNanos, lastChunkNanos, total, interChunk);
    } catch (RuntimeException e) {
      throw rethrow(e);
    } finally {
      executor.shutdown();
    }

    // OpenAI documents that an interrupted or cancelled stream may never deliver the final usage
    // chunk. Say so rather than letting an absent usage object read as a measured zero: the usage
    // ledger treats a terminal fact as authoritative and would otherwise under-bill silently.
    if (!assembler.sawUsage()) {
      log.warn(
          "OpenAI-wire stream completed without a usage chunk model={} — usage unavailable for this"
              + " attempt",
          requestedModel);
    }
    return assembler.toCompletionResponse(requestedModel);
  }

  private static void awaitFirstToken(
      CompletableFuture<Void> firstChunk, CompletableFuture<Void> pump, Duration ttft) {
    try {
      firstChunk.get(ttft.toSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException noFirstToken) {
      pump.cancel(true);
      throw new IllegalStateException(
          "OpenAI-wire stream produced no tokens within "
              + ttft.toSeconds()
              + "s (route not responding)");
    } catch (ExecutionException failed) {
      // The request itself failed before any token — surface that, not a timeout.
      throw rethrow(failed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      pump.cancel(true);
      throw new IllegalStateException("OpenAI-wire stream interrupted awaiting first token");
    }
  }

  private static void awaitCompletion(
      CompletableFuture<Void> pump,
      long startedNanos,
      AtomicLong lastChunkNanos,
      Duration total,
      Duration interChunk) {
    while (true) {
      try {
        pump.get(1, TimeUnit.SECONDS);
        return;
      } catch (TimeoutException stillStreaming) {
        long now = System.nanoTime();
        if (now - startedNanos > total.toNanos()) {
          pump.cancel(true);
          throw new IllegalStateException(
              "OpenAI-wire stream exceeded its " + total.toSeconds() + "s ceiling");
        }
        if (now - lastChunkNanos.get() > interChunk.toNanos()) {
          pump.cancel(true);
          throw new IllegalStateException(
              "OpenAI-wire stream stalled for more than " + interChunk.toSeconds() + "s");
        }
      } catch (ExecutionException failed) {
        throw rethrow(failed);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        pump.cancel(true);
        throw new IllegalStateException("OpenAI-wire stream interrupted mid-stream");
      }
    }
  }

  /**
   * Unwraps async wrappers so the router sees the SAME failure surface as the non-streaming path —
   * its retry/failover classification depends on {@link RestClientResponseException} in particular.
   */
  private static RuntimeException rethrow(Throwable e) {
    Throwable cause = e;
    // Unwrap the async wrappers so the router sees the SAME failure surface as the non-streaming
    // path; its retry/failover classification keys on RestClientResponseException in particular.
    while ((cause instanceof ExecutionException
            || cause instanceof java.util.concurrent.CompletionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof RestClientResponseException responseError) {
      log.error("OpenAI-wire streamed call failed http={}", responseError.getStatusCode());
      return responseError;
    }
    log.error("OpenAI-wire streamed call failed errorClass={}", cause.getClass().getSimpleName());
    if (cause instanceof RuntimeException runtime) return runtime;
    return new IllegalStateException(cause.getMessage(), cause);
  }

  private static RestClientResponseException errorFor(
      org.springframework.http.HttpStatusCode status,
      org.springframework.http.client.ClientHttpResponse response) {
    byte[] bytes;
    try (InputStream in = response.getBody()) {
      bytes = in.readNBytes(MAX_ERROR_BODY_BYTES);
    } catch (Exception unreadable) {
      bytes = new byte[0];
    }
    String statusText;
    try {
      statusText = HttpStatus.valueOf(status.value()).getReasonPhrase();
    } catch (IllegalArgumentException ignored) {
      statusText = "";
    }
    org.springframework.http.HttpHeaders headers;
    try {
      headers = response.getHeaders();
    } catch (Exception ignored) {
      headers = new org.springframework.http.HttpHeaders();
    }
    return new RestClientResponseException(
        "OpenAI-wire error " + status.value(),
        status,
        statusText,
        headers,
        bytes,
        StandardCharsets.UTF_8);
  }
}
