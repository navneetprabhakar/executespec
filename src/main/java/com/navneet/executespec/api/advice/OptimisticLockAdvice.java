package com.navneet.executespec.api.advice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Translates Hibernate optimistic-lock failures to a user-actionable 409
 * Conflict. Introduced with V112 + {@code @Version} on {@code ExecutionRun}
 * to close Bug #3 (async planner save racing against /plan/approve).
 *
 * <p>Kept intentionally narrow — one exception type, one status code, one
 * human message. If more entities adopt {@code @Version} later, they inherit
 * this translation automatically.
 */
@Slf4j
@RestControllerAdvice
public class OptimisticLockAdvice {

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> onOptimisticLock(
            ObjectOptimisticLockingFailureException ex) {
        log.info("Optimistic lock conflict on {} id={}: {}",
                ex.getPersistentClassName(), ex.getIdentifier(), ex.getMessage());
        Map<String, Object> body = Map.of(
                "error", "optimistic_lock_conflict",
                "message", "Another request modified this record at the same time. "
                        + "Reload and retry.",
                "entity", ex.getPersistentClassName(),
                "id", String.valueOf(ex.getIdentifier())
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
