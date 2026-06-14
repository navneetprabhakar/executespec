package com.navneet.executespec.api.advice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
 *   <li><b>{@link Order} runs last</b>. {@link OptimisticLockAdvice} + {@link ValidationAdvice} +
 *       Spring's own {@link ResponseStatusException} handling all win — this is strictly the
 *       fallback for unexpected exceptions, never for the normal API-error shape.
 *   <li><b>Opaque error id in the response, full stack trace in the log</b>. The client gets a
 *       short correlation id (so support can grep) but no internal class names / line numbers /
 *       message strings that could leak schema or library internals. The full exception is logged
 *       server-side at ERROR.
 * </ul>
 */
@Slf4j
@RestControllerAdvice
@Order(Integer.MAX_VALUE)
public class GlobalExceptionAdvice {

  /**
   * SSE clients routinely disconnect after they have received the terminal event they needed. At
   * that point the servlet response is already committed as {@code text/event-stream}; attempting
   * to render the normal JSON error envelope only creates a second noisy "no converter" failure.
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void onAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
    log.debug("Async client disconnected before response completion: {}", ex.getMessage());
  }

  /**
   * Spring 6 ships {@link ErrorResponseException} as the supertype of every standard error-class
   * exception ({@link ResponseStatusException}, {@code NoResourceFoundException}, {@code
   * MethodArgumentNotValidException} after Spring 6.1, etc). Catching the supertype here means a
   * 404 from the static-resource handler stays a 404 instead of falling to {@link #onAny}'s generic
   * 500 envelope.
   *
   * <p>Each subclass carries its own status code; we forward it as-is + render the canonical
   * envelope. The pre-existing {@link OptimisticLockAdvice} + {@link ValidationAdvice} stay in
   * front of this handler via their own narrower {@code @ExceptionHandler} mappings.
   */
  @ExceptionHandler(ErrorResponseException.class)
  public ResponseEntity<Map<String, Object>> onErrorResponse(ErrorResponseException ex) {
    HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
    if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
    String error = status.name().toLowerCase().replace('_', '-');
    String message =
        ex instanceof ResponseStatusException rse && rse.getReason() != null
            ? rse.getReason()
            : status.getReasonPhrase();
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
   * Spring's static-resource handler throws {@link NoResourceFoundException} when a URL doesn't
   * match any controller AND no static asset exists at that path. It does NOT extend {@link
   * ErrorResponseException} in current Spring 6.x, so it would otherwise fall through to {@link
   * #onAny}'s opaque 500. Map it to the canonical 404 envelope so unmapped backend paths are
   * obviously 404 (not 500) to clients + dashboards.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Map<String, Object>> onNoResource(NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            Map.of(
                "timestamp",
                Instant.now().toString(),
                "status",
                404,
                "error",
                "not_found",
                "message",
                "No endpoint at /" + (ex.getResourcePath() == null ? "" : ex.getResourcePath())));
  }

  /** Last-chance handler. Any uncaught Throwable becomes a structured 500 with a correlation id. */
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
