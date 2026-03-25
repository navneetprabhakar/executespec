package com.navneet.executespec.testgen;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.event.TestExecutionCompletedEvent;
import com.navneet.executespec.testgen.event.TestsReadyEvent;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates the convergence of preview readiness and test compilation readiness.
 * Tests only execute when BOTH the preview is running AND tests are compiled.
 *
 * Uses Spring events + in-memory coordination state per run.
 * 5-minute timeout: if preview not ready after tests compiled, skip execution.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestPreviewCoordinator {

    private static final Duration PREVIEW_TIMEOUT = Duration.ofMinutes(5);

    private final TestExecutionService testExecutionService;
    private final TestReportGenerator testReportGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final SseBroadcaster broadcaster;

    private final ConcurrentHashMap<String, CoordinationState> stateMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("test-coord-timeout-", 0).factory());

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

        // Schedule timeout: if preview not ready in 5 minutes, skip execution
        timeoutScheduler.schedule(() -> checkTimeout(event.runId()),
                PREVIEW_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        tryExecute(event.runId(), state);
    }

    private synchronized void tryExecute(String runId, CoordinationState state) {
        if (state.isPreviewReady() && state.isTestsReady() && !state.isExecutionStarted()) {
            state.setExecutionStarted(true);

            Thread.ofVirtual().name("test-exec-" + runId).start(() -> {
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
                } finally {
                    stateMap.remove(runId);
                }
            });
        }
    }

    private void checkTimeout(String runId) {
        CoordinationState state = stateMap.get(runId);
        if (state != null && state.isTestsReady() && !state.isPreviewReady() && !state.isExecutionStarted()) {
            log.warn("[TestCoord] Preview timeout for run {} — tests were ready but preview never started", runId);
            broadcaster.emit(runId, RunEvent.log(runId, null,
                    "[TestExec] Test execution skipped — preview was not ready within 5 minutes"));
            stateMap.remove(runId);
        }
    }

    @Data
    private static class CoordinationState {
        private final String runId;
        private boolean previewReady;
        private boolean testsReady;
        private boolean executionStarted;
        private String previewUrl;
        private Long testPlanId;
        private Instant testsReadyAt;
    }
}
