package com.navneet.executespec.compliance;

import java.time.Instant;

import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * RR-090 — the transactional DB half of GDPR erasure, split out of {@link DataRetentionService} so
 * the orchestrator's cancel-and-wait phases never hold a database transaction open across a sleep.
 *
 * <p>Every method here is its own short transaction: the {@code @Modifying} soft-delete needs one,
 * and the obligation upsert/clear must be atomic. Keeping them in a distinct Spring bean also means
 * the transaction proxy actually applies (a self-invocation from {@code DataRetentionService} would
 * bypass it).
 */
@Service
@RequiredArgsConstructor
public class ErasureObligationStore {

  private final PipelineRunRepository runRepository;
  private final PendingErasureObligationRepository obligationRepository;

  /** Tombstones the user's terminal runs (GDPR right-to-erasure). Returns the count tombstoned. */
  @Transactional
  public int softDeleteTerminal(String userId) {
    return runRepository.softDeleteTerminalByUserId(
        userId, Instant.now(), PipelineRunRepository.TERMINAL_STATUSES);
  }

  /**
   * Records (or refreshes) the durable obligation that erasure is not yet complete for this user.
   * Idempotent per user: the first attempt inserts; a retry preserves {@code requestedAt}, bumps
   * {@code attempts}, and updates the residual count and reason.
   */
  @Transactional
  public void recordObligation(String userId, long liveRunsRemaining, String reason) {
    Instant now = Instant.now();
    PendingErasureObligation existing = obligationRepository.findById(userId).orElse(null);
    if (existing == null) {
      obligationRepository.save(
          PendingErasureObligation.builder()
              .userId(userId)
              .requestedAt(now)
              .lastAttemptAt(now)
              .attempts(1)
              .liveRunsRemaining(liveRunsRemaining)
              .lastReason(reason)
              .build());
    } else {
      existing.setLastAttemptAt(now);
      existing.setAttempts(existing.getAttempts() + 1);
      existing.setLiveRunsRemaining(liveRunsRemaining);
      existing.setLastReason(reason);
      obligationRepository.save(existing);
    }
  }

  /** Clears a satisfied obligation. Idempotent — a no-op when none is open for the user. */
  @Transactional
  public void clearObligation(String userId) {
    if (obligationRepository.existsById(userId)) {
      obligationRepository.deleteById(userId);
    }
  }
}
