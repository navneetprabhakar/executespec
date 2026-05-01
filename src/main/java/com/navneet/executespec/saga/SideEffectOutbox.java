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
 * SideEffectExecutor} picks up PENDING entries every 30 seconds and retries failures with
 * exponential backoff (max {@code max_attempts}).
 *
 * <h3>Effect types</h3>
 *
 * <ul>
 *   <li>{@code GITHUB_PR} — create GitHub pull request
 *   <li>{@code CLOUD_RUN_DEPLOY} — deploy to Cloud Run
 *   <li>{@code JIRA_COMMENT} — post completion comment to JIRA
 *   <li>{@code CONFLUENCE_PAGE} — create/update Confluence page
 *   <li>{@code GCS_ARCHIVE} — archive workspace to GCS
 *   <li>{@code COMPENSATE_GITHUB_BRANCH} — delete GitHub branch on run failure
 *   <li>{@code COMPENSATE_CLOUD_RUN} — delete Cloud Run service on run failure
 *   <li>{@code COMPENSATE_JIRA_COMMENT} — post failure comment to JIRA
 * </ul>
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
    String payloadJson;
    try {
      payloadJson = mapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      payloadJson = "{}";
      log.warn(
          "[Outbox] Failed to serialize payload for {}/{}: {}", runId, effectType, e.getMessage());
    }

    SideEffectOutboxEntry entry =
        SideEffectOutboxEntry.builder()
            .runId(runId)
            .effectType(effectType)
            .payload(payloadJson)
            .status("PENDING")
            .attempt(0)
            .maxAttempts(3)
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
