package com.navneet.executespec.execution.service;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * CAS-style update helper for {@link ExecutionRun}. Refetches the latest row,
 * applies a caller-supplied mutator, then saves; retries up to 3 times on
 * {@link ObjectOptimisticLockingFailureException} with 50ms × attempt back-off.
 *
 * <p>Promoted from {@code ExecutionOrchestrator#casUpdateRun} (private helper
 * shipped with the 2026-04-26 triggerAnalysis hotfix) so every long-running
 * async write site that holds an in-memory {@code ExecutionRun} reference
 * across an LLM / git / V2 sub-call can converge on the same retry contract.
 *
 * <p>Background — {@code ExecutionRun} carries a JPA {@code @Version} column
 * (V112). Concurrent writers (SSE update path, project-level analyse callback,
 * sibling cron) bump that version mid-flight; the stale in-memory reference
 * then OOMs on save because its version no longer matches the DB row. Without
 * this helper, the @Async exception surfaces to
 * {@link org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler}
 * with no recovery and the row is wedged in whatever non-terminal state it was
 * last in.
 *
 * <p>Returns false when the row has been deleted concurrently or all 3 retry
 * attempts hit version conflicts. Callers that care should log + emit an
 * appropriate SSE / NotificationCenter signal so the UI knows the row is stuck.
 *
 * <p>Thread-safety: stateless. Safe to share as a singleton bean.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExecutionRunCasUpdater {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 50L;

    private final ExecutionRunRepository runRepo;

    /**
     * Refetch + mutate + save with bounded retry on optimistic-lock failure.
     *
     * @param runId   the ExecutionRun id
     * @param mutator the mutation to apply on every fetch (must be idempotent;
     *                the lambda may be invoked up to {@value #MAX_ATTEMPTS} times)
     * @return true on successful save, false if the row vanished or all
     *         retries failed
     */
    public boolean casUpdate(String runId, Consumer<ExecutionRun> mutator) {
        int attempts = 0;
        while (attempts < MAX_ATTEMPTS) {
            attempts++;
            try {
                ExecutionRun fresh = runRepo.findById(runId).orElse(null);
                if (fresh == null) return false;
                mutator.accept(fresh);
                runRepo.save(fresh);
                return true;
            } catch (ObjectOptimisticLockingFailureException ole) {
                if (attempts >= MAX_ATTEMPTS) {
                    log.error("casUpdate: optimistic-lock retry exhausted for exec {} after {} attempts",
                            runId, attempts);
                    return false;
                }
                log.info("casUpdate: optimistic-lock conflict on exec {} attempt {}/{}; refetching",
                        runId, attempts, MAX_ATTEMPTS);
                try {
                    Thread.sleep(BACKOFF_BASE_MS * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
