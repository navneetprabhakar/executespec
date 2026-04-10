package com.navneet.executespec.testgen;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Docker-based headless test executor for batch runs.
 *
 * <p>Runs the compiled test project inside an isolated Docker container with
 * {@code --network=none} — no live preview required. Only unit tests execute
 * (integration tests that call {@code -Dapp.base-url} are skipped via
 * {@code -DskipITs=true}).
 *
 * <p>Uses the same surefire report parsing as {@link TestExecutionService}.
 * Replaces the empty-event hack in {@link TestPreviewCoordinator} for batch runs.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HeadlessTestExecutor {

    private static final int EXECUTION_TIMEOUT_SECONDS = 300; // 5 minutes

    private final TestPlanRepository testPlanRepository;
    private final TestResultRepository testResultRepository;
    private final SurefireReportParser surefireReportParser;
    private final SseBroadcaster broadcaster;

    /**
     * Execute unit tests for a batch run without a live preview.
     *
     * @param runId      the run ID
     * @param testPlanId the test plan to update
     * @return execution summary (never null)
     */
    public TestExecutionSummary execute(String runId, Long testPlanId) {
        log.info("[HeadlessTestExec] Starting headless test execution for run {}", runId);
        broadcaster.emit(runId, RunEvent.log(runId, null,
                "[TestExec] Running unit tests (headless mode — no preview required)"));

        Path testProjectDir = Path.of("workspace-test", runId);
        Path absTestDir = testProjectDir.toAbsolutePath();

        var plan = testPlanRepository.findById(testPlanId).orElse(null);

        Instant start = Instant.now();
        try {
            // Docker container with no network access — unit tests only, no live app required
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "run", "--rm",
                    "--network=none",
                    "--memory=2g",
                    "--cpus=2",
                    "--read-only",
                    "--tmpfs", "/tmp:rw,size=256m",
                    "--tmpfs", "/root/.m2:rw,size=1g",
                    "--security-opt", "no-new-privileges:true",
                    "--cap-drop", "ALL",
                    "-v", absTestDir + ":/workspace:ro",
                    "-v", absTestDir.resolve("target") + ":/workspace/target:rw",
                    "-w", "/workspace",
                    "maven:3.9-eclipse-temurin-21",
                    "mvn", "test",
                    "-DskipITs=true",          // skip integration tests (require live app)
                    "-Dsurefire.failIfNoSpecifiedTests=false",
                    "-T", "1C"
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[HeadlessTestExec] Timed out for run {} — destroying container", runId);
            }

            // Parse surefire reports
            Path reportsDir = testProjectDir.resolve("target/surefire-reports");
            List<TestResult> results = surefireReportParser.parse(reportsDir, testPlanId);
            testResultRepository.saveAll(results);

            long durationMs = java.time.Duration.between(start, Instant.now()).toMillis();

            int passed  = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
            int failed  = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
            int skipped = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.SKIPPED).count();
            int errors  = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.ERROR).count();

            if (plan != null) {
                plan.setStatus(com.navneet.executespec.testgen.model.TestPlanStatus.COMPLETED);
                testPlanRepository.save(plan);
            }

            log.info("[HeadlessTestExec] Complete for run {}: {}/{} passed (headless)",
                    runId, passed, results.size());
            broadcaster.emit(runId, RunEvent.log(runId, null,
                    "[TestExec] Headless tests complete: " + passed + "/" + results.size() + " passed"));

            return TestExecutionSummary.builder()
                    .totalTests(results.size())
                    .passed(passed)
                    .failed(failed)
                    .skipped(skipped)
                    .errors(errors)
                    .executionDurationMs(durationMs)
                    .build();

        } catch (Exception e) {
            log.error("[HeadlessTestExec] Failed for run {}: {}", runId, e.getMessage(), e);
            if (plan != null) {
                plan.setStatus(com.navneet.executespec.testgen.model.TestPlanStatus.FAILED);
                testPlanRepository.save(plan);
            }
            broadcaster.emit(runId, RunEvent.log(runId, null,
                    "[TestExec] Headless test execution failed: " + e.getMessage()));
            return TestExecutionSummary.empty();
        }
    }
}
