package com.navneet.executespec.testgen;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.event.TestExecutionCompletedEvent;
import com.navneet.executespec.testgen.event.TestsReadyEvent;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates the convergence of preview readiness and test compilation readiness.
 * Tests only execute when BOTH the preview is running AND tests are compiled.
 *
 * <p>Uses Spring events + in-memory coordination state per run.
 * No internal timeout — the report-generation safety cap in PipelineHarness
 * (10 min on the CompletableFuture) is the single timeout authority.
 * Call {@link #cancelIfPending(String)} before report generation to release
 * runs whose preview never started.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestPreviewCoordinator {

    private final TestExecutionService testExecutionService;
    private final TestReportGenerator testReportGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final SseBroadcaster broadcaster;

    private final ConcurrentHashMap<String, CoordinationState> stateMap = new ConcurrentHashMap<>();

    /**
     * Called when the preview is ready for a run.
     * This can be invoked from PreviewService or any component that detects preview readiness.
     */
    public void onPreviewReady(String runId, String previewUrl) {
        log.info("[TestCoord] Preview ready for run {} at {}", runId, previewUrl);
        CoordinationState state = stateMap.computeIfAbsent(runId, CoordinationState::new);
        state.setPreviewUrl(previewUrl);
        state.setPreviewReady(true);
        tryExecute(runId, state);
    }

    @EventListener
    public void onTestsReady(TestsReadyEvent event) {
        log.info("[TestCoord] Tests ready for run {}, planId={}", event.runId(), event.testPlanId());
        CoordinationState state = stateMap.computeIfAbsent(event.runId(), CoordinationState::new);
        state.setTestPlanId(event.testPlanId());
        state.setTestsReady(true);
        state.setTestsReadyAt(Instant.now());

        // If preview already failed (cancelIfPending was called before tests were ready),
        // immediately fire the completion event with an empty summary.
        if (state.isPreviewFailed()) {
            log.info("[TestCoord] Tests ready but preview already failed for run {} — skipping execution",
                    event.runId());
            broadcaster.emit(event.runId(), RunEvent.log(event.runId(), null,
                    "[TestExec] Test execution skipped — preview failed before tests were ready"));
            eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                    event.runId(), state.getTestPlanId(), TestExecutionSummary.empty()));
            stateMap.remove(event.runId());
            return;
        }

        tryExecute(event.runId(), state);
    }

    private synchronized void tryExecute(String runId, CoordinationState state) {
        if (state.isPreviewReady() && state.isTestsReady() && !state.isExecutionStarted()) {
            state.setExecutionStarted(true);

            var snapshot = ContextSnapshotFactory.builder().build().captureAll();
            Thread.ofVirtual().name("test-exec-" + runId).start(snapshot.wrap(() -> {
                try {
                    broadcaster.emit(runId, RunEvent.log(runId, null,
                            "[TestExec] Starting test execution against preview"));

                    TestExecutionSummary summary = testExecutionService.executeTests(
                            runId, state.getTestPlanId(), state.getPreviewUrl());

                    if (summary.isAllConnectionRefused()) {
                        broadcaster.emit(runId, RunEvent.log(runId, null,
                                "[TestExec] Preview app crashed during testing — all tests got Connection refused"));
                    }

                    // Generate report with fix suggestions
                    testReportGenerator.generateAndEmit(runId, state.getTestPlanId(), summary,
                            state.getPreviewUrl());

                    eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                            runId, state.getTestPlanId(), summary));

                } catch (Exception e) {
                    log.error("[TestCoord] Test execution failed for run {}: {}", runId, e.getMessage(), e);
                    broadcaster.emit(runId, RunEvent.log(runId, null,
                            "[TestExec] Test execution failed: " + e.getMessage()));
                    // Publish empty summary so the validation report future isn't blocked
                    eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                            runId, state.getTestPlanId(), TestExecutionSummary.empty()));
                } finally {
                    stateMap.remove(runId);
                }
            }));
        }
    }

    /**
     * Cancel pending test execution for a run if preview never started.
     * Called by PipelineHarness before report generation so the CompletableFuture
     * is completed immediately (with an empty summary) instead of blocking for
     * the 10-minute safety timeout.
     *
     * <p>If execution already started (preview arrived + tests ready), this is a no-op
     * — the execution will complete naturally and publish its own event.
     */
    public void cancelIfPending(String runId) {
        CoordinationState state = stateMap.get(runId);
        if (state != null && !state.isExecutionStarted()) {
            long waitSec = state.getTestsReadyAt() != null
                    ? Duration.between(state.getTestsReadyAt(), Instant.now()).toSeconds() : 0;
            log.info("[TestCoord] Cancelling pending test execution for run {} " +
                    "(tests ready {}s ago, preview never arrived)", runId, waitSec);
            broadcaster.emit(runId, RunEvent.log(runId, null,
                    "[TestExec] Test execution skipped — preview was not ready in time"));
            eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                    runId, state.getTestPlanId(), TestExecutionSummary.empty()));
            stateMap.remove(runId);
        } else if (state == null) {
            // Preview failed BEFORE tests were ready. Mark the state so that when
            // onTestsReady fires later, it immediately completes instead of waiting.
            CoordinationState earlyCancel = new CoordinationState(runId);
            earlyCancel.setPreviewFailed(true);
            stateMap.put(runId, earlyCancel);
            log.info("[TestCoord] Preview failed before tests ready for run {} — " +
                    "will auto-cancel when tests arrive", runId);
        }
    }

    @Data
    private static class CoordinationState {
        private final String runId;
        private boolean previewReady;
        private boolean testsReady;
        private boolean executionStarted;
        private boolean previewFailed;
        private String previewUrl;
        private Long testPlanId;
        private Instant testsReadyAt;
    }
}
