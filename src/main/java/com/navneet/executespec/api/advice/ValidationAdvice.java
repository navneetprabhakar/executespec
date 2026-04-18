package com.navneet.executespec.api.advice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Translates Bean Validation failures (e.g. {@code @Size}, {@code @NotBlank},
 * {@code @Email}) into a JSON 400 with field-level detail.
 *
 * <p>Without this advice Spring returns an empty 400 for any DTO whose
 * {@code @Valid} check fails — which is exactly what bit Gap #2 of the
 * 2026-04-18 persona test pass: a too-short register password produced a
 * mystery 400 with no hint as to which rule failed. The advice runs before
 * the controller method, so {@link com.navneet.executespec.auth.service.AuthService#validatePasswordStrength}
 * never gets a chance to throw its more descriptive
 * {@link org.springframework.web.server.ResponseStatusException}.
 */
@Slf4j
@RestControllerAdvice
public class ValidationAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        // Field errors → { fieldName: "first message" }
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        e -> e.getDefaultMessage() == null ? "invalid" : e.getDefaultMessage(),
                        (a, b) -> a,
                        LinkedHashMap::new));
        // Class-level / global errors (e.g. @AssertTrue on a method)
        List<String> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .toList();

        String summary = fieldErrors.isEmpty() && globalErrors.isEmpty()
                ? "Request validation failed"
                : (fieldErrors.size() + globalErrors.size()) + " validation error(s)";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation_failed");
        body.put("message", summary);
        if (!fieldErrors.isEmpty()) body.put("fields", fieldErrors);
        if (!globalErrors.isEmpty()) body.put("globalErrors", globalErrors);

        log.debug("Bean Validation failure: {}", body);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
