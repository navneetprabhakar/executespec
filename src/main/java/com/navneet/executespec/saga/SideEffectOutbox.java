package com.navneet.executespec.saga;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Outbox for side effects that must be delivered reliably.
 *
 * <p>Each effect is persisted to {@code side_effect_outbox} before execution. {@link
 * SideEffectExecutor} commits a fenced database lease before invoking a versioned idempotent
 * handler and retries failures with exponential backoff (max {@code max_attempts}).
 *
 * <h3>Executable effect types</h3>
 *
 * <ul>
 *   <li>{@code GCS_ARCHIVE} — archive workspace to GCS
 *   <li>{@code COMPENSATE_GITHUB_BRANCH} — delete GitHub branch on run failure
 * </ul>
 *
 * <p>Adding an effect name without first defining a {@link SideEffectHandlerContract} fails fast;
 * unknown work is never acknowledged as a successful no-op.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SideEffectOutbox {

  private final SideEffectOutboxRepository repository;
  private final ObjectMapper mapper;

  /**
   * Enqueues a side effect for reliable async delivery. The entry is immediately PENDING and will
   * be picked up by {@link SideEffectExecutor}.
   */
  @Transactional
  public SideEffectOutboxEntry enqueue(
      String runId, String effectType, Map<String, Object> payload) {
    SideEffectHandlerContract contract = SideEffectHandlerContract.require(effectType);
    String payloadJson;
    try {
      payloadJson = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "Could not serialize side-effect payload for " + effectType, e);
    }

    SideEffectOutboxEntry entry =
        SideEffectOutboxEntry.builder()
            .runId(runId)
            .effectType(effectType)
            .payload(payloadJson)
            .status("PENDING")
            .attempt(0)
            .maxAttempts(3)
            .idempotencyKey("side-effect:" + java.util.UUID.randomUUID())
            .handlerContractVersion(contract.version())
            .nextRetryAt(Instant.now())
            .build();

    SideEffectOutboxEntry saved = repository.save(entry);
    log.info("[Outbox] Enqueued {} for run {} (id={})", effectType, runId, saved.getId());
    return saved;
  }

  /**
   * Enqueues a compensation action — called when a run fails to undo side effects that were
   * previously applied (delete branch, delete Cloud Run service, etc.).
   */
  @Transactional
  public void enqueueCompensation(
      String runId, String compensationType, Map<String, Object> payload) {
    log.info("[Outbox] Enqueuing compensation {} for failed run {}", compensationType, runId);
    enqueue(runId, compensationType, payload);
  }
}
