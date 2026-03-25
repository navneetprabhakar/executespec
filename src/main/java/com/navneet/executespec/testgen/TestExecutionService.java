package com.navneet.executespec.testgen;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.model.*;
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
 * Executes compiled tests against a live preview deployment.
 * Runs Maven Surefire with base URL pointed at the preview.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestExecutionService {

    private static final long EXECUTION_TIMEOUT_SECONDS = 120;

    private final TestPlanRepository testPlanRepository;
    private final TestResultRepository testResultRepository;
    private final SurefireReportParser surefireReportParser;
    private final SseBroadcaster broadcaster;

    /**
     * Run all compiled tests against the preview URL.
     */
    public TestExecutionSummary executeTests(String runId, Long testPlanId, String previewBaseUrl) {
        log.info("[TestExec] Executing tests for run {} against {}", runId, previewBaseUrl);

        Path testProjectDir = Path.of("workspace-test", runId);
        Path pomFile = testProjectDir.resolve("pom.xml");

        // Update plan status
        TestPlan plan = testPlanRepository.findById(testPlanId).orElseThrow();
        plan.setStatus(TestPlanStatus.EXECUTING);
        testPlanRepository.save(plan);

        broadcaster.emit(runId, RunEvent.log(runId, null, "[TestExec] Running tests against " + previewBaseUrl));

        Instant start = Instant.now();

        try {
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome == null || javaHome.isBlank()) {
                javaHome = "/opt/homebrew/opt/openjdk@21";
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "mvn", "test",
                    "-f", pomFile.toString(),
                    "-Dapp.base-url=" + previewBaseUrl
            );
            pb.environment().put("JAVA_HOME", javaHome);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[TestExec] Test execution timed out for run {}", runId);
            }

            // Parse surefire reports
            Path reportsDir = testProjectDir.resolve("target/surefire-reports");
            List<TestResult> results = surefireReportParser.parse(reportsDir, testPlanId);

            // Check for "Connection refused" pattern — preview crashed
            boolean allConnectionRefused = !results.isEmpty() && results.stream()
                    .filter(r -> r.getStatus() == TestResultStatus.ERROR || r.getStatus() == TestResultStatus.FAILED)
                    .allMatch(r -> r.getFailureMessage() != null &&
                            r.getFailureMessage().toLowerCase().contains("connection refused"));

            // Persist results
            testResultRepository.saveAll(results);

            long durationMs = java.time.Duration.between(start, Instant.now()).toMillis();

            // Build summary
            int passed = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
            int failed = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
            int skipped = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.SKIPPED).count();
            int errors = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.ERROR).count();

            TestExecutionSummary summary = TestExecutionSummary.builder()
                    .totalTests(results.size())
                    .passed(passed)
                    .failed(failed)
                    .skipped(skipped)
                    .errors(errors)
                    .executionDurationMs(durationMs)
                    .allConnectionRefused(allConnectionRefused)
                    .build();

            // Update plan status
            plan.setStatus(TestPlanStatus.COMPLETED);
            testPlanRepository.save(plan);

            log.info("[TestExec] Test execution complete for run {}: {}/{} passed",
                    runId, passed, results.size());

            return summary;

        } catch (Exception e) {
            log.error("[TestExec] Test execution failed for run {}: {}", runId, e.getMessage(), e);
            plan.setStatus(TestPlanStatus.FAILED);
            testPlanRepository.save(plan);
            throw new RuntimeException("Test execution failed: " + e.getMessage(), e);
        }
    }
}
