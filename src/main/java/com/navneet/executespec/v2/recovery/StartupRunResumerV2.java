package com.navneet.executespec.v2.recovery;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Boot-time recovery for V2 runs left mid-flight by a previous JVM — crash,
 * SIGTERM, or deploy rollover.
 *
 * <p>Phase 1 behaviour (this class): <em>fail-cleanly</em>. The resumer scans
 * non-terminal V2 runs and marks each FAILED with a clear reason + emits an SSE
 * event so the UI shows it. This turns "stuck in WORKING forever" into "user
 * can see and retry" — already a meaningful availability improvement without
 * needing replay logic.
 *
 * <p>Phase 2 (follow-up PR): actual replay via
 * {@link com.navneet.executespec.v2.context.ContextStoreV2#load} +
 * {@code WorkerAgentV2.resumeFromSnapshot()} +
 * {@code SequentialExecutorV2.continueFromTask(...)}. Designed as a drop-in
 * replacement for the fail-path below.
 *
 * <p>Run state handling:
 * <ul>
 *   <li>{@code AWAITING_APPROVAL} — untouched. The plan gate is user-facing;
 *       the user can approve whenever, and that path does not require the old
 *       orchestrator thread.</li>
 *   <li>{@code PLANNING} — failed. No snapshot yet (worker hadn't started); no
 *       useful recovery other than restart-from-scratch, which we leave to the user.</li>
 *   <li>{@code WORKING} / {@code REVIEWING} — failed with "recovery pending"
 *       reason. Snapshots exist in Redis, but replay wiring is Phase 2.</li>
 * </ul>
 *
 * <p>Scoping: only touches rows where {@code harnessVersion = "v2"}. V1's
 * {@code StartupRunResumer} remains authoritative for legacy runs until the
 * deprecation-removal plan lands.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunResumerV2 {

    /** Statuses that indicate a run was in-flight when the JVM died. */
    private static final List<String> RESUMABLE_STATUSES =
            List.of("PLANNING", "WORKING", "REVIEWING", "AWAITING_APPROVAL");

    /** Hard cap on resume attempts per run. Once exceeded the run is
     *  permanently FAILED; the user can submit a fresh run instead. */
    private static final int MAX_RESUME_ATTEMPTS = 3;

    /** Runs older than this at boot time are considered abandoned rather
     *  than interrupted — we fail them cleanly instead of attempting resume. */
    private static final Duration STALE_CUTOFF = Duration.ofHours(6);

    private final PipelineRunRepository runRepo;
    private final SseBroadcaster broadcaster;

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            scanAndRecover();
        } catch (Exception e) {
            // Never let recovery failures block startup.
            log.error("StartupRunResumerV2: unexpected error during scan", e);
        }
    }

    @Transactional
    public void scanAndRecover() {
        List<PipelineRun> candidates = runRepo.findByStatusIn(RESUMABLE_STATUSES).stream()
                .filter(r -> "v2".equalsIgnoreCase(r.getHarnessVersion()))
                .toList();

        if (candidates.isEmpty()) {
            log.info("StartupRunResumerV2: no interrupted V2 runs to recover");
            return;
        }

        log.warn("StartupRunResumerV2: found {} interrupted V2 run(s); recovering",
                candidates.size());

        int awaitingApproval = 0;
        int failedInterrupted = 0;
        int failedCapped = 0;
        int failedStale = 0;
        Instant cutoff = Instant.now().minus(STALE_CUTOFF);
        for (PipelineRun run : candidates) {
            String status = run.getStatus();
            if ("AWAITING_APPROVAL".equals(status)) {
                // User-facing gate; no orchestrator thread required to unblock.
                log.info("StartupRunResumerV2: run={} AWAITING_APPROVAL — left as-is",
                        run.getRunId());
                awaitingApproval++;
                continue;
            }

            int attempts = run.getResumeAttempts() == null ? 0 : run.getResumeAttempts();
            if (attempts >= MAX_RESUME_ATTEMPTS) {
                markFailed(run, status,
                        "Resume attempt limit (" + MAX_RESUME_ATTEMPTS + ") reached. "
                                + "Please submit a fresh run.");
                failedCapped++;
                continue;
            }

            Instant startedAt = run.getStartedAt();
            if (startedAt != null && startedAt.isBefore(cutoff)) {
                // Abandoned — too old to replay meaningfully. Fail without consuming
                // an attempt so the counter only reflects real resume attempts.
                markFailed(run, status,
                        "Run exceeded " + STALE_CUTOFF.toHours() + "h since start; "
                                + "considered stale rather than interrupted.");
                failedStale++;
                continue;
            }

            // Count this as a resume attempt even in Phase 1 (fail-cleanly) so
            // repeated deploy-interrupt cycles trip the cap and don't thrash.
            run.setResumeAttempts(attempts + 1);
            markFailed(run, status,
                    "Interrupted during " + status
                            + " (JVM restart / deploy). Please retry the run. "
                            + "Resume attempts used: " + (attempts + 1) + "/" + MAX_RESUME_ATTEMPTS);
            failedInterrupted++;
        }
        log.warn("StartupRunResumerV2: recovery complete — awaiting-approval={} "
                        + "interrupted={} cap-exceeded={} stale={}",
                awaitingApproval, failedInterrupted, failedCapped, failedStale);
    }

    private void markFailed(PipelineRun run, String previousStatus, String reason) {
        String runId = run.getRunId();
        run.setStatus("FAILED");
        run.setFailureReason(reason);
        run.setCompletedAt(Instant.now());
        runRepo.save(run);
        log.warn("StartupRunResumerV2: run={} prev={} → FAILED ({})",
                runId, previousStatus, reason);
        try {
            broadcaster.emit(runId, RunEvent.runStatus(runId, "FAILED"));
            broadcaster.complete(runId);
        } catch (Exception e) {
            log.debug("StartupRunResumerV2: SSE emit failed for run={}: {}",
                    runId, e.getMessage());
        }
    }
}
