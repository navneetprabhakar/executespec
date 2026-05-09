package com.navneet.executespec.api.advice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * B-23 fix (2026-05-09) — global 5xx exception handler returning JSON.
 *
 * <p>Pre-fix, {@link ValidationAdvice} handled every 4xx case (validation, ResponseStatusException,
 * AccessDeniedException, malformed JSON, type-mismatch). But uncaught RuntimeException /
 * IOException / NullPointerException / IllegalStateException bubbled to Spring's default
 * ErrorController, which renders an HTML error page. API consumers (lib/api.ts, third-party SDKs,
 * mobile clients, ops dashboards) saw a JSON body on every 4xx and an HTML page on every 5xx —
 * inconsistent shape, broken JSON parsers, opaque incidents.
 *
 * <p>This advice catches anything ValidationAdvice didn't and renders the same JSON envelope,
 * status 500. Stack-trace is logged at ERROR (with the request URL when available) but never
 * leaked into the response body — only the exception class name + sanitised message.
 *
 * <p>404 NoHandlerFoundException is also caught here so a typo'd URL gets JSON instead of the
 * default Spring whitelabel page.
 *
 * <p>Order: {@code Ordered.LOWEST_PRECEDENCE} so this advice runs last — every more-specific
 * handler in {@link ValidationAdvice} or {@link OptimisticLockAdvice} wins first.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ServerErrorAdvice {

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<Map<String, Object>> onNoHandler(NoHandlerFoundException ex) {
    log.debug("404 — no handler for {} {}", ex.getHttpMethod(), ex.getRequestURL());
    return jsonError(
        HttpStatus.NOT_FOUND,
        baseBody(HttpStatus.NOT_FOUND, "not_found", "No handler for " + ex.getRequestURL()));
  }

  /**
   * Catch-all for any exception ValidationAdvice / OptimisticLockAdvice didn't handle. Always
   * returns 500 with JSON. Body deliberately does NOT include the stack trace — that goes to logs.
   *
   * <p>If a future endpoint legitimately wants to surface a 5xx with a domain-specific message, it
   * should throw a {@link org.springframework.web.server.ResponseStatusException} which is caught
   * by ValidationAdvice instead and uses the supplied reason as the message.
   */
  @ExceptionHandler(Throwable.class)
  public ResponseEntity<Map<String, Object>> onUncaught(Throwable ex) {
    String exType = ex.getClass().getSimpleName();
    String safeMsg = sanitiseMessage(ex.getMessage());
    log.error(
        "Unhandled {} from request — returning 500 (message='{}')", exType, safeMsg, ex);
    return jsonError(
        HttpStatus.INTERNAL_SERVER_ERROR,
        baseBody(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_error",
            "An unexpected error occurred. The team has been notified."));
  }

  private static <T> ResponseEntity<T> jsonError(HttpStatus code, T body) {
    // Mirrors ValidationAdvice — force JSON regardless of incoming Accept header.
    return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  private static Map<String, Object> baseBody(HttpStatus code, String errorSlug, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", code.value());
    body.put("error", errorSlug);
    body.put("message", message);
    return body;
  }

  /**
   * Defensive scrub: trims, caps to 200 chars, replaces newlines/tabs with spaces. Prevents an
   * exception message that happens to contain a stack-trace fragment or a SQL fragment from
   * leaking into the JSON response body.
   */
  private static String sanitiseMessage(String raw) {
    if (raw == null) return "";
    String oneLine = raw.replaceAll("\\s+", " ").trim();
    return oneLine.length() > 200 ? oneLine.substring(0, 197) + "..." : oneLine;
  }
}
