package com.navneet.executespec.api.advice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * B-23 (audit, 2026-05-09) — last-chance handler for any uncaught {@link Throwable} reaching the
 * Spring DispatcherServlet. Without this advice, a bare {@code RuntimeException} bubbles into
 * Spring's default error handler which renders the {@code /error} HTML page (or worse, a stack
 * trace as JSON in dev profile). Either way the client sees something other than the canonical
 * {@code {error, message}} envelope every other ExecuteSpec endpoint returns.
 *
 * <p>Two design choices worth calling out:
 *
 * <ul>
 *   <li><b>{@link Order} runs last</b>. {@link OptimisticLockAdvice} +
 *       {@link ValidationAdvice} +
 *       Spring's own {@link ResponseStatusException} handling all win — this is strictly the
 *       fallback for unexpected exceptions, never for the normal API-error shape.
 *   <li><b>Opaque error id in the response, full stack trace in the log</b>. The client gets a
 *       short correlation id (so support can grep) but no internal class names / line numbers /
 *       message strings that could leak schema or library internals. The full exception is
 *       logged server-side at ERROR.
 * </ul>
 */
@Slf4j
@RestControllerAdvice
@Order(Integer.MAX_VALUE)
public class GlobalExceptionAdvice {

  /**
   * {@link ResponseStatusException} carries an explicit status + reason; pass them through.
   * Without this, ResponseStatusException would fall to {@link #onAny} and become an opaque 500.
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> onResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
    String error = status.name().toLowerCase().replace('_', '-');
    String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "timestamp",
                Instant.now().toString(),
                "status",
                status.value(),
                "error",
                error,
                "message",
                message));
  }

  /**
   * Last-chance handler. Any uncaught Throwable becomes a structured 500 with a correlation id.
   */
  @ExceptionHandler(Throwable.class)
  public ResponseEntity<Map<String, Object>> onAny(Throwable ex) {
    String errorId = UUID.randomUUID().toString().substring(0, 8);
    log.error("Unhandled exception (errorId={}): {}", errorId, ex.getMessage(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            Map.of(
                "timestamp",
                Instant.now().toString(),
                "status",
                500,
                "error",
                "internal-server-error",
                "message",
                "Something went wrong on our end. Please try again.",
                "errorId",
                errorId));
  }
}
