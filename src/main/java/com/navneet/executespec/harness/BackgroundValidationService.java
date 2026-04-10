package com.navneet.executespec.harness;

import com.navneet.executespec.harness.entity.BackgroundValidation;
import com.navneet.executespec.harness.repository.BackgroundValidationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * DB-backed lifecycle manager for background validation records.
 *
 * <p>Replaces the in-memory {@code ConcurrentHashSet<String> backgroundValidationRuns} in
 * {@link PipelineHarness} with a durable Postgres record so that app restarts don't lose
 * track of in-flight validations.
 *
 * <p>Phase flow:
 * <pre>
 *   create(PENDING) → start(RUNNING) → phase(...) → complete(COMPLETED | FAILED)
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackgroundValidationService {

    private final BackgroundValidationRepository repo;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Creates a PENDING record for the run if one doesn't already exist.
     * Idempotent — safe to call multiple times.
     */
    @Transactional
    public BackgroundValidation create(String runId) {
        return repo.findByRunId(runId).orElseGet(() -> {
            BackgroundValidation bv = BackgroundValidation.create(runId);
            BackgroundValidation saved = repo.save(bv);
            log.debug("[BgValidation] Created PENDING record for run {}", runId);
            return saved;
        });
    }

    /** Transitions PENDING → RUNNING, records start time. */
    @Transactional
    public void markRunning(String runId) {
        repo.findByRunId(runId).ifPresent(bv -> {
            bv.setStatus("RUNNING");
            bv.setStartedAt(Instant.now());
            bv.setCurrentPhase(null);
            repo.save(bv);
            log.debug("[BgValidation] Run {} → RUNNING (attempt {})", runId, bv.getAttempt());
        });
    }

    /** Records the current phase name (e.g. TEST_GEN, REVIEW, RE_VALIDATE, REPORT). */
    @Transactional
    public void setPhase(String runId, String phase) {
        repo.findByRunId(runId).ifPresent(bv -> {
            bv.setCurrentPhase(phase);
            repo.save(bv);
        });
    }

    /** Records a phase result key/value into the JSONB column. */
    @Transactional
    public void recordPhaseResult(String runId, String phase, Object result) {
        repo.findByRunId(runId).ifPresent(bv -> {
            bv.getPhaseResults().put(phase, result);
            repo.save(bv);
        });
    }

    /** Transitions to COMPLETED. */
    @Transactional
    public void markCompleted(String runId) {
        repo.findByRunId(runId).ifPresent(bv -> {
            bv.setStatus("COMPLETED");
            bv.setCompletedAt(Instant.now());
            bv.setCurrentPhase(null);
            repo.save(bv);
            log.info("[BgValidation] Run {} → COMPLETED", runId);
        });
    }

    /** Transitions to FAILED with error message. */
    @Transactional
    public void markFailed(String runId, String error) {
        repo.findByRunId(runId).ifPresent(bv -> {
            bv.setStatus("FAILED");
            bv.setCompletedAt(Instant.now());
            bv.setLastError(error);
            repo.save(bv);
            log.warn("[BgValidation] Run {} → FAILED (attempt {}/{}): {}", runId, bv.getAttempt(), bv.getMaxAttempts(), error);
        });
    }

    /**
     * Prepares a FAILED record for retry: increments attempt, resets to PENDING.
     * Returns false if max attempts already reached.
     */
    @Transactional
    public boolean prepareRetry(String runId) {
        Optional<BackgroundValidation> opt = repo.findByRunId(runId);
        if (opt.isEmpty()) return false;
        BackgroundValidation bv = opt.get();
        if (!bv.canRetry()) {
            log.warn("[BgValidation] Run {} has exhausted retries ({}/{})", runId, bv.getAttempt(), bv.getMaxAttempts());
            return false;
        }
        bv.setAttempt(bv.getAttempt() + 1);
        bv.setStatus("PENDING");
        bv.setStartedAt(null);
        bv.setCompletedAt(null);
        bv.setLastError(null);
        bv.setCurrentPhase(null);
        repo.save(bv);
        log.info("[BgValidation] Run {} reset for retry (attempt {})", runId, bv.getAttempt());
        return true;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Returns the current record, or empty if none exists. */
    @Transactional(readOnly = true)
    public Optional<BackgroundValidation> find(String runId) {
        return repo.findByRunId(runId);
    }

    /**
     * Returns true if background validation is currently RUNNING or PENDING for this run.
     * Used by {@link PipelineHarness} to decide whether to defer cleanup.
     */
    @Transactional(readOnly = true)
    public boolean isActive(String runId) {
        return repo.findByRunId(runId)
                .map(bv -> "RUNNING".equals(bv.getStatus()) || "PENDING".equals(bv.getStatus()))
                .orElse(false);
    }

    /**
     * Finds validations that have been RUNNING for longer than {@code staleThresholdMinutes}
     * and have not completed. Used by {@link com.navneet.executespec.recovery.StuckRunMonitor}
     * for stall detection.
     */
    @Transactional(readOnly = true)
    public java.util.List<BackgroundValidation> findStalled(int staleThresholdMinutes) {
        Instant staleBefore = Instant.now().minusSeconds(staleThresholdMinutes * 60L);
        return repo.findStalledValidations(staleBefore);
    }
}
