package com.navneet.executespec.recovery;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.harness.PipelineHarness;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRecovery;
import com.navneet.executespec.state.repository.RunRecoveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects orphaned pipeline runs — runs stuck in an active non-terminal status
 * (WORKING, PLANNING, REVIEWING, STUCK) where the pipeline thread died and
 * no task is actively executing.
 *
 * <p>Complements {@link StuckRunMonitor} which handles idle <em>tasks</em>.
 * This watchdog handles the case where the pipeline thread itself died
 * (OOM, uncaught error between tasks, thread interrupt) leaving the run
 * in an active status with no tasks in WORKING or LOCKED state.
 *
 * <p><strong>Deduplication (6 layers):</strong>
 * <ol>
 *   <li>ShedLock ({@code @SchedulerLock}) — Redis-backed, one instance across replicas</li>
 *   <li>In-memory {@code recovering} set — one recovery in-flight per run per JVM</li>
 *   <li>DB {@code wasRecentlyRecovered()} — cross-instance, survives restarts</li>
 *   <li>Active task check — if tasks are WORKING/LOCKED, pipeline thread is alive</li>
 *   <li>Status guard in StateStore — idempotent terminal→terminal transitions</li>
 *   <li>{@code @Scheduled(fixedDelay)} not {@code fixedRate} — no overlap</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrphanedRunWatchdog {

    private final StateStore stateStore;
    private final PipelineHarness harness;
    private final RunRecoveryRepository recoveryRepo;
    private final SseBroadcaster broadcaster;
    private final RecoveryProperties props;
    private final ActivityAuditService auditService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Prevent duplicate recovery: track run IDs currently being recovered. */
    private final Set<String> recovering = ConcurrentHashMap.newKeySet();

    // ── Scheduled entry point ────────────────────────────────────────────────

    @Scheduled(fixedDelay = 3 * 60 * 1000)  // Every 3 minutes
    @SchedulerLock(name = "OrphanedRunWatchdog", lockAtMostFor = "2m", lockAtLeastFor = "30s")
    public void detectOrphanedRuns() {
        if (!props.isEnabled()) return;

        // 1. Query all runs in active non-terminal, non-gate statuses
        List<PipelineRun> candidates = stateStore.findRunsByStatuses(
            List.of("WORKING", "PLANNING", "REVIEWING", "STUCK")
        );

        if (candidates.isEmpty()) return;

        Instant now = Instant.now();
        Duration idleThreshold = Duration.ofMinutes(props.getOrphanedRunThresholdMinutes());

        for (PipelineRun run : candidates) {
            String runId = run.getRunId();

            // 2. Skip if currently being recovered (in-memory dedup guard)
            if (recovering.contains(runId)) continue;

            // 2b. Skip if pipeline thread is still alive (prevents duplicate execution)
            if (harness.isRunActive(runId)) continue;

            // 3. Skip if any task is actively executing (WORKING or LOCKED)
            List<AgentTask> activeTasks = stateStore.getActiveTasksForRun(runId);
            if (!activeTasks.isEmpty()) continue;

            // 4. Skip if run is at a gate (user-gated, not stuck)
            if (run.getCurrentGate() != null) continue;

            // 5. Determine last activity timestamp and check idle duration
            Instant lastActivity = determineLastActivity(run);
            Duration idle = Duration.between(lastActivity, now);
            if (idle.compareTo(idleThreshold) < 0) continue;

            // 6. Skip if recently recovered (within last 10 min) — prevent loops
            if (wasRecentlyRecovered(runId, Duration.ofMinutes(10))) continue;

            // 7. Recover the orphaned run
            log.warn("Orphaned run detected: {} — status={}, idle={}min, no active tasks",
                runId, run.getStatus(), idle.toMinutes());

            recovering.add(runId);
            try {
                recoverOrphanedRun(run, idle);
            } finally {
                recovering.remove(runId);
            }
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Determines last activity for a run by inspecting the latest of:
     * - Most recent task completedAt
     * - Most recent task lastActivityAt
     * - Run startedAt (if no tasks have any activity)
     */
    private Instant determineLastActivity(PipelineRun run) {
        List<AgentTask> allTasks = stateStore.getAllTasks(run.getRunId());
        Instant latest = run.getStartedAt() != null ? run.getStartedAt() : Instant.now();

        for (AgentTask task : allTasks) {
            if (task.getCompletedAt() != null && task.getCompletedAt().isAfter(latest)) {
                latest = task.getCompletedAt();
            }
            if (task.getLastActivityAt() != null && task.getLastActivityAt().isAfter(latest)) {
                latest = task.getLastActivityAt();
            }
        }
        return latest;
    }

    /**
     * Checks the run_recovery table for a recent recovery within the given window.
     * Cross-instance, survives restarts.
     */
    private boolean wasRecentlyRecovered(String runId, Duration window) {
        return recoveryRepo.existsByOriginalRunIdAndRecoveredAtAfter(
            runId, Instant.now().minus(window)
        );
    }

    /**
     * Recovers an orphaned run. Strategy depends on task state:
     * <ul>
     *   <li><strong>Partial progress (has PENDING tasks + some COMPLETE)</strong>:
     *       Pause → resume to re-execute incomplete tasks.</li>
     *   <li><strong>All tasks done but pipeline died</strong>:
     *       Mark FAILED — pipeline post-processing (compiler/reviewer/merge) can't be partially resumed.</li>
     *   <li><strong>No recoverable state</strong>:
     *       Mark FAILED.</li>
     * </ul>
     */
    private void recoverOrphanedRun(PipelineRun run, Duration idle) {
        String runId = run.getRunId();
        List<AgentTask> allTasks = stateStore.getAllTasks(runId);

        boolean hasPendingTasks = allTasks.stream()
            .anyMatch(t -> "PENDING".equals(t.getStatus()));
        boolean hasCompletedTasks = allTasks.stream()
            .anyMatch(t -> "COMPLETE".equals(t.getStatus()));

        String detail = "Pipeline thread died — no active tasks for run in "
            + run.getStatus() + " status (idle " + idle.toMinutes() + "min)";

        // Record recovery attempt in run_recovery table
        RunRecovery recovery = RunRecovery.builder()
            .id(UUID.randomUUID().toString())
            .originalRunId(runId)
            .classification(StuckClassification.UNKNOWN)
            .classificationDetail(detail)
            .originalGoal(run.getUserRequest() != null ? run.getUserRequest() : "")
            .recoveredAt(Instant.now())
            .build();

        if (hasPendingTasks) {
            // Partial progress — pause and resume to re-execute incomplete tasks
            // StateStore.pauseRun() sets non-COMPLETE tasks to FAILED,
            // StateStore.resumeRun() resets all non-COMPLETE tasks back to PENDING.
            // PipelineHarness.resumeRun() then spawns a new async thread.
            recovery.setActionTaken("AUTO_RESUME");
            recoveryRepo.save(recovery);

            boolean paused = stateStore.pauseRun(runId);
            if (!paused) {
                // Run may have changed status between detection and recovery
                log.warn("Orphaned run {} could not be paused — status may have changed", runId);
                recordAudit(run, "AUTO_RESUME_FAILED", idle);
                return;
            }

            boolean resumed = harness.resumeRun(runId);
            if (resumed) {
                broadcaster.emit(runId, RunEvent.log(runId, null,
                    "\uD83D\uDD04 Watchdog: Pipeline thread recovered — resuming incomplete tasks"));
                log.info("Orphaned run {} auto-resumed ({} pending tasks)", runId,
                    allTasks.stream().filter(t -> "PENDING".equals(t.getStatus())).count());
                recordAudit(run, "AUTO_RESUME", idle);
            } else {
                stateStore.markFailed(runId, "Watchdog: Auto-resume failed after pause");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                log.error("Orphaned run {} auto-resume failed", runId);
                recordAudit(run, "AUTO_RESUME_FAILED", idle);
            }

        } else if (hasCompletedTasks) {
            // All executable tasks done — pipeline died during post-processing.
            // For PROTOTYPE mode: code is generated and preview may already be running.
            // Auto-complete instead of failing — post-processing (review/test/report)
            // is skipped in PROTOTYPE anyway.
            boolean allTasksComplete = allTasks.stream()
                .allMatch(t -> "COMPLETE".equals(t.getStatus()));
            boolean isPrototype = "PROTOTYPE".equals(run.getExecutionMode());

            if (isPrototype && allTasksComplete) {
                recovery.setActionTaken("AUTO_COMPLETE");
                recoveryRepo.save(recovery);

                stateStore.markComplete(runId);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "Run recovered — all tasks completed successfully"));
                broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
                eventPublisher.publishEvent(new com.navneet.executespec.harness.event.PipelineEvent.RunCompleted(runId));

                // Auto-start preview if it hasn't been started yet
                if (run.getPreviewStatus() == null || "NONE".equals(run.getPreviewStatus())) {
                    try {
                        broadcaster.emit(runId, RunEvent.log(runId, "harness",
                            "Code ready — starting preview..."));
                        harness.autoStartPreview(runId, run);
                        log.info("Orphaned PROTOTYPE run {} — preview auto-started by watchdog", runId);
                    } catch (Exception previewEx) {
                        log.warn("Orphaned PROTOTYPE run {} — preview auto-start failed: {}",
                            runId, previewEx.getMessage());
                    }
                }

                log.info("Orphaned PROTOTYPE run {} auto-completed — all {} tasks done",
                    runId, allTasks.size());
                recordAudit(run, "AUTO_COMPLETE", idle);
            } else {
                // PRODUCTION mode or has incomplete tasks — can't partially resume post-processing
                recovery.setActionTaken("AUTO_FAIL");
                recoveryRepo.save(recovery);

                stateStore.markFailed(runId,
                    "Watchdog: Pipeline thread died after tasks completed — please re-run");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                log.info("Orphaned run {} marked FAILED — tasks done but pipeline died in post-processing",
                    runId);
                recordAudit(run, "AUTO_FAIL", idle);
            }

        } else {
            // No progress at all — mark failed
            recovery.setActionTaken("AUTO_FAIL");
            recoveryRepo.save(recovery);

            stateStore.markFailed(runId, "Watchdog: Pipeline thread died — no recoverable state");
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            log.info("Orphaned run {} marked FAILED — no recoverable state", runId);
            recordAudit(run, "AUTO_FAIL", idle);
        }
    }

    /**
     * Record an activity audit entry for admin traceability.
     * Not surfaced on the Audit Page UI, but queryable for diagnostics.
     */
    private void recordAudit(PipelineRun run, String action, Duration idle) {
        try {
            auditService.record(ActivityAudit.builder()
                .eventType("RUN_ORPHANED_DETECTED")
                .entityType("RUN")
                .entityId(run.getRunId())
                .orgId(run.getOrgId())
                .actorId("SYSTEM")
                .oldValue(run.getStatus())
                .newValue(action)
                .metadata("{\"idleMinutes\":" + idle.toMinutes() + "}")
                .build());
        } catch (Exception e) {
            log.warn("Failed to record watchdog audit for run {}: {}", run.getRunId(), e.getMessage());
        }
    }
}
