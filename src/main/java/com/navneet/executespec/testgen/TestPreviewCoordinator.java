package com.navneet.executespec.testgen;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.deployment.preview.PreviewService;
import com.navneet.executespec.deployment.preview.PreviewStatus;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.testgen.event.TestExecutionCompletedEvent;
import com.navneet.executespec.testgen.event.TestsReadyEvent;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final HeadlessTestExecutor headlessTestExecutor;
    private final TestReportGenerator testReportGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final SseBroadcaster broadcaster;
    private final AppUserRepository appUserRepository;
    private final StateStore stateStore;

    /** ObjectProvider breaks the circular dependency
     *  (PreviewService → TestPreviewCoordinator → PreviewService) without the
     *  `@Lazy @Autowired` field-injection anti-pattern. The provider resolves
     *  the bean on-demand at call sites, so instantiation order doesn't deadlock. */
    private final ObjectProvider<PreviewService> previewServiceProvider;

    private PreviewService previewService() {
        return previewServiceProvider.getObject();
    }

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

        // Batch runs: run unit tests headlessly (no preview required)
        var run = stateStore.findRun(event.runId()).orElse(null);
        if (run != null && "BATCH".equals(run.getApprovedExecutionMode())) {
            log.info("[TestCoord] Batch run {} — running headless unit tests (no preview needed)",
                    event.runId());
            var snapshot = ContextSnapshotFactory.builder().build().captureAll();
            Thread.ofVirtual().name("headless-test-" + event.runId()).start(snapshot.wrap(() -> {
                try {
                    TestExecutionSummary summary = headlessTestExecutor.execute(
                            event.runId(), event.testPlanId());
                    testReportGenerator.generateAndEmit(event.runId(), event.testPlanId(), summary, null);
                    eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                            event.runId(), event.testPlanId(), summary));
                } catch (Exception e) {
                    log.error("[TestCoord] Headless test execution failed for run {}: {}",
                            event.runId(), e.getMessage(), e);
                    eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                            event.runId(), event.testPlanId(), TestExecutionSummary.empty()));
                } finally {
                    stateMap.remove(event.runId());
                }
            }));
            return;
        }

        // Start preview now that tests are ready — ensures full TTL for test execution.
        // The 10-minute CompletableFuture timeout in PipelineHarness.generateValidationReport()
        // is the safety net if preview startup takes too long.
        startPreviewForTests(event.runId());
        // Don't call tryExecute() here — onPreviewReady() will trigger it when preview is live
    }

    /**
     * Starts the preview after test generation is complete.
     * This ensures the full preview TTL is available for test execution,
     * avoiding the race condition where preview expires before tests finish.
     */
    private void startPreviewForTests(String runId) {
        // Check if a preview is already running (auto-started by PipelineHarness after COMPLETE).
        // If so, reuse it instead of starting a duplicate.
        var existingSession = previewService().getStatus(runId);
        if (existingSession != null && existingSession.status() == PreviewStatus.RUNNING) {
            log.info("[TestCoord] Reusing existing preview for run {} at {}", runId, existingSession.url());
            onPreviewReady(runId, existingSession.url());
            return;
        }

        var snapshot = ContextSnapshotFactory.builder().build().captureAll();
        Thread.ofVirtual().name("test-preview-" + runId).start(snapshot.wrap(() -> {
            try {
                // Re-check after thread start (preview may have become ready in the meantime)
                var session = previewService().getStatus(runId);
                if (session != null && session.status() == PreviewStatus.RUNNING) {
                    log.info("[TestCoord] Preview became ready for run {} at {} (reusing)", runId, session.url());
                    onPreviewReady(runId, session.url());
                    return;
                }

                var run = stateStore.findRun(runId).orElse(null);
                if (run == null) {
                    log.warn("[TestCoord] No run found for {} — skipping preview", runId);
                    cancelIfPending(runId);
                    return;
                }
                var user = run.getCreatedByUserId() != null
                        ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null)
                        : null;
                if (user == null) {
                    log.warn("[TestCoord] No user found for run {} — skipping preview", runId);
                    cancelIfPending(runId);
                    return;
                }
                log.info("[TestCoord] Starting preview for run {} (tests ready, starting preview for execution)", runId);
                broadcaster.emit(runId, RunEvent.log(runId, null,
                        "Tests generated — starting preview for test execution..."));
                previewService().start(runId, user);
                // PreviewService will call onPreviewReady() when ready → triggers tryExecute()
            } catch (Exception e) {
                log.warn("[TestCoord] Failed to start preview for run {}: {}", runId, e.getMessage());
                broadcaster.emit(runId, RunEvent.log(runId, null,
                        "⚠️ Preview failed: " + e.getMessage() + " — skipping integration tests"));
                CoordinationState state = stateMap.get(runId);
                if (state != null) {
                    state.setPreviewFailed(true);
                }
                eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                        runId, stateMap.containsKey(runId) ? stateMap.get(runId).getTestPlanId() : 0L,
                        TestExecutionSummary.empty()));
                stateMap.remove(runId);
            }
        }));
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
        if (state != null && !state.isExecutionStarted() && !state.isTestsReady()) {
            // Tests were never ready (gen pipeline failed or still running) — no preview was started.
            // Safe to cancel: nothing is in-flight.
            long waitSec = state.getTestsReadyAt() != null
                    ? Duration.between(state.getTestsReadyAt(), Instant.now()).toSeconds() : 0;
            log.info("[TestCoord] Cancelling pending test execution for run {} " +
                    "(tests never compiled, preview never started)", runId);
            broadcaster.emit(runId, RunEvent.log(runId, null,
                    "[TestExec] Test execution skipped — tests did not compile in time"));
            eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                    runId, state.getTestPlanId(), TestExecutionSummary.empty()));
            stateMap.remove(runId);
        } else if (state != null && !state.isExecutionStarted() && state.isTestsReady()) {
            // Tests are ready and preview is starting (or about to start).
            // Do NOT cancel — let the preview come up and execute tests.
            // The 10-minute CompletableFuture timeout in generateValidationReport
            // is the safety net if preview never arrives.
            log.info("[TestCoord] Tests ready for run {} but execution not started yet — " +
                    "allowing preview+test flow to continue (10min timeout will catch failures)", runId);
        } else if (state == null) {
            // Test gen hasn't finished yet. Do NOT poison the state — let test gen
            // complete naturally. Preview starts only after tests are ready (onTestsReady),
            // so there's no resource to cancel. The CompletableFuture timeout handles
            // the case where test gen takes too long.
            log.debug("[TestCoord] cancelIfPending called for run {} but test gen not finished — " +
                    "letting test pipeline proceed naturally", runId);
        }
    }

    /**
     * Manually trigger test execution against a running preview.
     * Used by the "Run Tests" button in the UI for batch runs (or any run with a manual preview).
     */
    public void triggerManualExecution(String runId, long testPlanId, String previewUrl) {
        CoordinationState state = stateMap.computeIfAbsent(runId, CoordinationState::new);
        state.setTestPlanId(testPlanId);
        state.setTestsReady(true);
        state.setTestsReadyAt(Instant.now());
        state.setPreviewUrl(previewUrl);
        state.setPreviewReady(true);
        log.info("[TestCoord] Manual test execution triggered for run {} against {}", runId, previewUrl);
        tryExecute(runId, state);
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
