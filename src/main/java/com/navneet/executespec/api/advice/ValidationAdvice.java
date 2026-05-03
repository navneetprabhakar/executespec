package com.navneet.executespec.api.advice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

/**
 * Global 4xx shape advice. Every 4xx response from the backend lands here so API consumers see one
 * consistent JSON body:
 *
 * <pre>
 * { "timestamp": "...", "status": 400, "error": "...", "message": "...", "fields": {...}? }
 * </pre>
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Bean Validation on request bodies ({@code @Valid} + {@code @NotBlank} etc.)
 *   <li>{@link ResponseStatusException} thrown from controllers + services (e.g. {@code
 *       InputSanitizer.scan} rejecting prompt injection — adversarial-test-report Finding 19)
 *   <li>{@link AccessDeniedException} from Spring Security (RBAC denials that don't go through a
 *       status exception)
 *   <li>{@link HttpMessageNotReadableException} for malformed JSON
 *   <li>{@link MethodArgumentTypeMismatchException} for bad path / query types
 * </ul>
 *
 * <p>Closes adversarial-test-report-2026-04-18 Finding 5 (inconsistent 4xx body shape) and Finding
 * 19 (prompt-injection 400 had no message).
 */
@Slf4j
@RestControllerAdvice
public class ValidationAdvice {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors =
        ex.getBindingResult().getFieldErrors().stream()
            .collect(
                Collectors.toMap(
                    FieldError::getField,
                    e -> e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage(),
                    (a, b) -> a,
                    LinkedHashMap::new));
    List<String> globalErrors =
        ex.getBindingResult().getGlobalErrors().stream()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .toList();

    String summary =
        fieldErrors.isEmpty() && globalErrors.isEmpty()
            ? "Request validation failed"
            : (fieldErrors.size() + globalErrors.size()) + " validation error(s)";

    Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, "validation_failed", summary);
    if (!fieldErrors.isEmpty()) body.put("fields", fieldErrors);
    if (!globalErrors.isEmpty()) body.put("globalErrors", globalErrors);

    log.debug("Bean Validation failure: {}", body);
    return jsonError(HttpStatus.BAD_REQUEST, body);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> onResponseStatus(ResponseStatusException ex) {
    HttpStatusCode code = ex.getStatusCode();
    String reason = ex.getReason();
    String errorSlug = slugForStatus(code);
    String message =
        (reason == null || reason.isBlank())
            ? HttpStatus.valueOf(code.value()).getReasonPhrase()
            : reason;
    Map<String, Object> body = baseBody(code, errorSlug, message);
    if (log.isDebugEnabled()) {
      log.debug("ResponseStatusException: status={} reason={}", code, reason);
    }
    return jsonError(code, body);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, Object>> onAccessDenied(AccessDeniedException ex) {
    return jsonError(
        HttpStatus.FORBIDDEN,
        baseBody(
            HttpStatus.FORBIDDEN,
            "forbidden",
            ex.getMessage() == null ? "Access denied" : ex.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> onBadJson(HttpMessageNotReadableException ex) {
    return jsonError(
        HttpStatus.BAD_REQUEST,
        baseBody(
            HttpStatus.BAD_REQUEST, "bad_request", "Request body is missing or not valid JSON"));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> onTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    String name = ex.getName();
    String expected = ex.getRequiredType() == null ? "value" : ex.getRequiredType().getSimpleName();
    return jsonError(
        HttpStatus.BAD_REQUEST,
        baseBody(
            HttpStatus.BAD_REQUEST,
            "bad_request",
            "Parameter '" + name + "' must be a valid " + expected));
  }

  /**
   * 2026-05-02 — every error response is forced to {@code application/json} regardless of the
   * incoming request's {@code Accept} header. Without the explicit content type, Spring tries to
   * negotiate against the request's accepted types — which fails with {@link
   * org.springframework.web.HttpMediaTypeNotAcceptableException} on endpoints that declared a
   * non-JSON producer (e.g. {@code GET /runs/&#123;id&#125;/report/pdf} sends {@code Accept:
   * application/pdf}). The fallback was an opaque 500 instead of the intended 4xx body. Setting the
   * content type bypasses the negotiation since the JSON body is what we always serve from this
   * advice.
   */
  private static <T> ResponseEntity<T> jsonError(HttpStatusCode code, T body) {
    return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON).body(body);
  }

  private static Map<String, Object> baseBody(
      HttpStatusCode code, String errorSlug, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", code.value());
    body.put("error", errorSlug);
    body.put("message", message);
    return body;
  }

  private static String slugForStatus(HttpStatusCode code) {
    return switch (code.value()) {
      case 400 -> "bad_request";
      case 401 -> "unauthorized";
      case 403 -> "forbidden";
      case 404 -> "not_found";
      case 409 -> "conflict";
      case 410 -> "gone";
      case 429 -> "rate_limited";
      case 502 -> "bad_gateway";
      default -> {
        HttpStatus status = HttpStatus.resolve(code.value());
        yield status == null ? "error" : status.getReasonPhrase().toLowerCase().replace(' ', '_');
      }
    };
  }
}
