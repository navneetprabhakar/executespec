package com.navneet.executespec.testgen;

import com.navneet.executespec.deployment.preview.PreviewService;
import com.navneet.executespec.deployment.preview.PreviewStatus;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.event.TestExecutionCompletedEvent;
import com.navneet.executespec.testgen.event.TestsReadyEvent;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the full test generation pipeline:
 *   plan → generate → compile → [ready for execution]
 *
 * Runs in parallel to the code generation pipeline on a virtual thread.
 * Triggered after plan approval when structured input is available.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestGenOrchestrator {

    private final TestPlannerAgent testPlannerAgent;
    private final TestWorkerAgent testWorkerAgent;
    private final TestCompilationGate testCompilationGate;
    private final TestProjectScaffold testProjectScaffold;
    private final ApiConventionsGenerator conventionsGenerator;
    private final TestPlanRepository testPlanRepository;
    private final TestTaskRepository testTaskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SseBroadcaster broadcaster;
    private final com.navneet.executespec.deployment.preview.DevDockerfileGenerator dockerfileGenerator;
    private final ApiProber apiProber;

    /** Lazy to avoid circular dependency: PreviewService → TestPreviewCoordinator → TestGenOrchestrator */
    @Lazy @Autowired
    private PreviewService previewService;

    /**
     * Entry point — triggered when plan is approved.
     * Runs on a virtual thread, parallel to code gen.
     */
    public void runTestGenPipeline(String runId, String userRequest, String planJson) {
        try {
            log.info("[TestGen] Starting test generation pipeline for run {}", runId);
            emitTestEvent(runId, "TEST_GEN_STARTED", "Test generation pipeline started");

            // 1. Detect project type first (needed for scaffold + conventions)
            ProjectConfig projectConfig = detectProjectConfig(runId);
            boolean isCli = projectConfig.isCli;
            boolean isStaticHtml = projectConfig.isStaticHtml;

            // 2. Scaffold test project (Playwright POM for static HTML, RestAssured for REST APIs)
            testProjectScaffold.scaffold(runId, isStaticHtml);

            // 3. Generate conventions doc
            String conventionsDoc = conventionsGenerator.generate(userRequest, planJson, isCli, isStaticHtml);

            // 4. Plan tests
            emitTestEvent(runId, "TEST_PLANNING_STARTED",
                    "Planning integration tests (CLI: " + isCli + ", staticHtml: " + isStaticHtml + ")");
            TestPlan testPlan = testPlannerAgent.planTests(runId, userRequest, conventionsDoc, isCli, isStaticHtml);
            List<TestTask> tasks = testTaskRepository.findByTestPlanId(testPlan.getId());
            emitTestEvent(runId, "TEST_PLANNING_COMPLETED",
                    "Test plan created with " + tasks.size() + " test classes");

            // 5. Probe preview if already running (auto-started by PipelineHarness after COMPLETE)
            String apiProbeResults = "";
            try {
                var existingPreview = previewService.getStatus(runId);
                if (existingPreview != null && existingPreview.status() == PreviewStatus.RUNNING
                        && existingPreview.url() != null) {
                    log.info("[TestGen] Probing running preview at {} for run {}", existingPreview.url(), runId);
                    apiProbeResults = apiProber.probe(existingPreview.url(), List.of());
                }
            } catch (Exception e) {
                log.debug("[TestGen] Preview probe skipped for run {}: {}", runId, e.getMessage());
            }

            // 6. Generate test classes sequentially
            emitTestEvent(runId, "TEST_GENERATION_STARTED",
                    "Generating " + tasks.size() + " test classes");

            final String probeResults = apiProbeResults;
            for (TestTask task : tasks) {
                try {
                    testWorkerAgent.generateTestClass(runId, task, conventionsDoc, userRequest, isCli, isStaticHtml, probeResults);
                } catch (Exception e) {
                    log.error("[TestGen] Test generation failed for task {}: {}",
                            task.getTaskName(), e.getMessage());
                    task.setStatus(TestTaskStatus.FAILED);
                    task.setCompilationError(e.getMessage());
                    testTaskRepository.save(task);
                }
            }

            // 7. Compile all generated tests together
            emitTestEvent(runId, "TEST_COMPILATION_STARTED", "Compiling generated tests");
            TestCompilationGate.CompilationResult compilationResult = testCompilationGate.compileTests(runId);

            if (compilationResult.success()) {
                testPlan.setStatus(TestPlanStatus.COMPILED);
                emitTestEvent(runId, "TEST_COMPILATION_PASSED", "All tests compiled successfully");
            } else {
                boolean survived = handleCompilationFailure(runId, testPlan, compilationResult,
                        conventionsDoc, userRequest, isCli, isStaticHtml);
                if (!survived) {
                    return;
                }
            }

            testPlanRepository.save(testPlan);

            // 7. Publish event — tests are ready for execution
            eventPublisher.publishEvent(new TestsReadyEvent(runId, testPlan.getId()));
            log.info("[TestGen] Test generation complete for run {} — ready for execution", runId);

        } catch (Exception e) {
            log.error("[TestGen] Test gen pipeline failed for run {}: {}", runId, e.getMessage(), e);
            emitTestEvent(runId, "TEST_PIPELINE_FAILED",
                    "Test generation pipeline failed: " + e.getMessage());
            // Notify that test pipeline ended (no results) so validation report isn't blocked
            eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                    runId, 0L, TestExecutionSummary.empty()));
        }
    }

    private record ProjectConfig(boolean isCli, boolean isStaticHtml) {}

    private ProjectConfig detectProjectConfig(String runId) {
        java.nio.file.Path workspace = java.nio.file.Path.of("workspace", runId);
        com.navneet.executespec.deployment.preview.PreviewProjectType type = dockerfileGenerator.detect(workspace);
        boolean isCli = false;
        boolean isStaticHtml = false;
        if (type != null) {
            com.navneet.executespec.deployment.preview.PreviewMode mode = dockerfileGenerator.detectMode(type, workspace);
            isCli = (mode == com.navneet.executespec.deployment.preview.PreviewMode.CLI_TERMINAL);
            isStaticHtml = (type == com.navneet.executespec.deployment.preview.PreviewProjectType.STATIC_HTML
                         || type == com.navneet.executespec.deployment.preview.PreviewProjectType.NODE_FRONTEND);
        }
        return new ProjectConfig(isCli, isStaticHtml);
    }

    /**
     * Handle compilation failure: retry failed tasks, re-compile, and determine if any tests survived.
     *
     * @return true if at least some tests compiled successfully, false if all failed (pipeline should abort)
     */
    private boolean handleCompilationFailure(String runId, TestPlan testPlan,
                                              TestCompilationGate.CompilationResult compilationResult,
                                              String conventionsDoc, String userRequest,
                                              boolean isCli, boolean isStaticHtml) {
        // Mark failed tasks and try one retry per failed test
        testCompilationGate.markFailedTasks(
                testPlan.getId(), compilationResult.failedClasses(), compilationResult.output());

        handleCompilationRetries(runId, testPlan, conventionsDoc, userRequest, isCli, isStaticHtml);

        // Re-compile after retries
        TestCompilationGate.CompilationResult retryResult = testCompilationGate.compileTests(runId);
        if (retryResult.success()) {
            testPlan.setStatus(TestPlanStatus.COMPILED);
            emitTestEvent(runId, "TEST_COMPILATION_PASSED",
                    "Tests compiled after retry");
            return true;
        }

        // Mark remaining failures
        testCompilationGate.markFailedTasks(
                testPlan.getId(), retryResult.failedClasses(), retryResult.output());

        // Check if any tests survived
        long compiledCount = testTaskRepository.findByTestPlanId(testPlan.getId()).stream()
                .filter(t -> t.getStatus() != TestTaskStatus.FAILED)
                .count();
        if (compiledCount > 0) {
            testPlan.setStatus(TestPlanStatus.COMPILED);
            emitTestEvent(runId, "TEST_COMPILATION_PASSED",
                    compiledCount + " tests compiled (" +
                            retryResult.failedClasses().size() + " failed)");
            return true;
        }

        testPlan.setStatus(TestPlanStatus.FAILED);
        emitTestEvent(runId, "TEST_COMPILATION_FAILED",
                "All tests failed compilation");
        testPlanRepository.save(testPlan);
        // Notify that test pipeline ended (no results)
        eventPublisher.publishEvent(new TestExecutionCompletedEvent(
                runId, testPlan.getId(), TestExecutionSummary.empty()));
        return false;
    }

    private void handleCompilationRetries(String runId, TestPlan testPlan,
                                            String conventionsDoc, String userRequest,
                                            boolean isCli, boolean isStaticHtml) {
        List<TestTask> failedTasks = testTaskRepository
                .findByTestPlanIdAndStatus(testPlan.getId(), TestTaskStatus.FAILED);

        for (TestTask task : failedTasks) {
            if (task.getCompilationError() == null) continue;
            try {
                testWorkerAgent.retryWithError(runId, task, conventionsDoc, userRequest,
                        task.getCompilationError(), isCli, isStaticHtml);
                task.setStatus(TestTaskStatus.GENERATING);
                testTaskRepository.save(task);
            } catch (Exception e) {
                log.warn("[TestGen] Retry failed for {}: {}", task.getTaskName(), e.getMessage());
                // Leave as FAILED
            }
        }
    }

    private void emitTestEvent(String runId, String eventType, String message) {
        broadcaster.emit(runId, RunEvent.log(runId, null, "[TestGen] " + message));
    }
}
