package com.navneet.executespec.testgen;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes compiled tests against a live preview deployment. Runs Maven Surefire with base URL
 * pointed at the preview.
 *
 * <p><strong>RR-1582 / RR-1583 / RR-1587.</strong> These counts rewrite the customer-facing
 * verifiedScore, so an attempt only publishes them when it can prove they belong to it. Before
 * launch the report directory is cleared and the plan's V277 workspace fingerprint is re-verified;
 * the container runs under a timeout that can actually fire; afterwards the exit status must mean
 * "tests ran", every report file must postdate the launch, and the fingerprint must still match.
 * Anything else publishes an explicitly unavailable summary instead of a pass count.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TestExecutionService {

  /**
   * Temporary fail-closed gate for the legacy live-preview runner. Its Docker command uses {@code
   * --network=host}; leave this off until controlled outbound routing replaces host networking.
   */
  public static final String FLAG_LIVE_PREVIEW_NETWORK = "testgen.live-preview-network.enabled";

  private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(120);

  /**
   * Maven exits 0 when every test passed and 1 when Surefire ran tests and some failed. Any other
   * status (image pull failure, mount failure, in-container compile failure, forced destroy) means
   * no trustworthy result was produced, whatever XML happens to be on disk.
   */
  private static final List<Integer> TESTS_RAN_EXIT_CODES = List.of(0, 1);

  private final TestPlanRepository testPlanRepository;
  private final TestResultRepository testResultRepository;
  private final SurefireReportParser surefireReportParser;
  private final SseBroadcaster broadcaster;
  private final TestGenerationAdmissionService admissionService;
  private final FeatureFlagService featureFlagService;
  private final TestWorkspaceResolver workspaceResolver;

  /** Run all compiled tests against the preview URL. */
  public TestExecutionSummary executeTests(String runId, Long testPlanId, String previewBaseUrl) {
    String executionId = UUID.randomUUID().toString();
    log.info(
        "[TestExec] Executing tests for run {} against {} (execution {})",
        runId,
        previewBaseUrl,
        executionId);

    Path testProjectDir = workspaceResolver.projectRoot(runId);

    // Update plan status
    TestPlan plan = testPlanRepository.findById(testPlanId).orElseThrow();
    if (!featureFlagService.isEnabled(FLAG_LIVE_PREVIEW_NETWORK)) {
      return unavailable(
          runId,
          plan,
          executionId,
          "live-preview test execution is disabled until controlled sandbox egress replaces"
              + " Docker host networking");
    }
    plan.setStatus(TestPlanStatus.EXECUTING);
    testPlanRepository.save(plan);

    broadcaster.emit(
        runId, RunEvent.log(runId, null, "[TestExec] Running tests against " + previewBaseUrl));

    // RR-1583 — the plan's pass/fail is evidence for the diff it was planned against, nothing else.
    String plannedFingerprint = plan.getWorkspaceFingerprint();
    String preFingerprint = admissionService.currentWorkspaceFingerprint(runId);
    String bindingProblem =
        TestExecutionProvenance.fingerprintMismatch(plannedFingerprint, preFingerprint);
    if (bindingProblem != null) {
      return staleBinding(runId, plan, executionId, bindingProblem);
    }

    Instant start = Instant.now();

    try {
      // RR-1582 — the target dir is bind-mounted rw and survives --rm, and manual re-execution can
      // re-enter on the same directory. Clear the reports before every attempt so leftover XML from
      // an earlier attempt cannot be parsed as this attempt's evidence.
      Path reportsDir = testProjectDir.resolve("target/surefire-reports");
      if (!TestExecutionProvenance.clearReports(reportsDir)) {
        return unavailable(
            runId,
            plan,
            executionId,
            "surefire report directory could not be reset before execution");
      }

      // Freshness floor with slack for filesystem timestamp granularity and container clock skew.
      Instant freshnessFloor = start.minusSeconds(2);

      Path absTestDir = testProjectDir.toAbsolutePath();
      String containerName = TestExecutionProvenance.containerName("es-test-exec", executionId);
      // Run tests inside a Docker container for isolation:
      // --network=host to access preview, --memory=4g, --cpus=2, --read-only (tmpfs for Maven
      // cache)
      ProcessBuilder pb =
          new ProcessBuilder(
              "docker",
              "run",
              "--rm",
              "--name",
              containerName,
              "--network=host",
              "--memory=4g",
              "--cpus=2",
              "--read-only",
              "--tmpfs",
              "/tmp:rw,size=512m",
              "--tmpfs",
              "/root/.m2:rw,size=1g",
              "--security-opt",
              "no-new-privileges:true",
              "--cap-drop",
              "ALL",
              "-v",
              absTestDir + ":/workspace:ro",
              "-v",
              absTestDir.resolve("target") + ":/workspace/target:rw",
              "-w",
              "/workspace",
              "maven:3.9-eclipse-temurin-21",
              "mvn",
              "test",
              "-Dapp.base-url=" + previewBaseUrl,
              "-T",
              "1C");
      pb.redirectErrorStream(true);

      BoundedProcess.Outcome outcome = BoundedProcess.run(pb, EXECUTION_TIMEOUT);
      if (!outcome.finished()) {
        // The JVM-side child is gone but the detached container is not — kill it by name.
        TestExecutionProvenance.killContainer(containerName);
        log.warn(
            "[TestExec] Test execution timed out for run {} (execution {})", runId, executionId);
        return unavailable(
            runId,
            plan,
            executionId,
            "test execution exceeded " + EXECUTION_TIMEOUT.toSeconds() + "s and was destroyed");
      }
      if (!TESTS_RAN_EXIT_CODES.contains(outcome.exitCode())) {
        log.warn(
            "[TestExec] Test runner exited {} for run {} — no trustworthy result",
            outcome.exitCode(),
            runId);
        return unavailable(
            runId,
            plan,
            executionId,
            "test runner exited " + outcome.exitCode() + " without running tests");
      }

      // RR-1582 — every report must provably belong to this attempt before it is parsed, and the
      // admitted set is moved under a per-attempt directory so a summary cannot be attributed to
      // the wrong attempt.
      SurefireReportParser.ReportAdmission admission =
          surefireReportParser.admit(reportsDir, freshnessFloor);
      if (!admission.admitted()) {
        return unavailable(runId, plan, executionId, admission.reason());
      }
      List<Path> attemptReports =
          TestExecutionProvenance.isolateAttempt(
              testProjectDir, executionId, admission.reportFiles());

      List<TestResult> results = surefireReportParser.parse(attemptReports, testPlanId);

      // RR-1583 — the workspace must not have moved under us while the suite ran.
      String postFingerprint = admissionService.currentWorkspaceFingerprint(runId);
      String drift =
          TestExecutionProvenance.fingerprintMismatch(plannedFingerprint, postFingerprint);
      if (drift != null) {
        return staleBinding(runId, plan, executionId, drift);
      }

      // Check for "Connection refused" pattern — preview crashed
      boolean allConnectionRefused =
          !results.isEmpty()
              && results.stream()
                  .filter(
                      r ->
                          r.getStatus() == TestResultStatus.ERROR
                              || r.getStatus() == TestResultStatus.FAILED)
                  .allMatch(
                      r ->
                          r.getFailureMessage() != null
                              && r.getFailureMessage()
                                  .toLowerCase()
                                  .contains("connection refused"));

      // Persist results
      testResultRepository.saveAll(results);

      long durationMs = java.time.Duration.between(start, Instant.now()).toMillis();

      // Build summary
      int passed =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
      int failed =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
      int skipped =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.SKIPPED).count();
      int errors =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.ERROR).count();

      TestExecutionSummary summary =
          TestExecutionSummary.builder()
              .totalTests(results.size())
              .passed(passed)
              .failed(failed)
              .skipped(skipped)
              .errors(errors)
              .executionDurationMs(durationMs)
              .allConnectionRefused(allConnectionRefused)
              .executionVerified(Boolean.TRUE)
              .executionId(executionId)
              .evidenceScope(TestExecutionSummary.EvidenceScope.LIVE_PREVIEW_FUNCTIONAL)
              .build();

      // Update plan status
      plan.setStatus(TestPlanStatus.COMPLETED);
      testPlanRepository.save(plan);

      log.info(
          "[TestExec] Test execution complete for run {}: {}/{} passed (execution {})",
          runId,
          passed,
          results.size(),
          executionId);

      return summary;

    } catch (Exception e) {
      log.error("[TestExec] Test execution failed for run {}: {}", runId, e.getMessage(), e);
      plan.setStatus(TestPlanStatus.FAILED);
      testPlanRepository.save(plan);
      throw new RuntimeException("Test execution failed: " + e.getMessage(), e);
    }
  }

  private TestExecutionSummary unavailable(
      String runId, TestPlan plan, String executionId, String reason) {
    plan.setStatus(TestPlanStatus.FAILED);
    testPlanRepository.save(plan);
    log.warn("[TestExec] No verified test evidence for run {}: {}", runId, reason);
    broadcaster.emit(
        runId, RunEvent.log(runId, null, "[TestExec] Test evidence unavailable — " + reason));
    return TestExecutionSummary.unavailable(executionId, reason);
  }

  private TestExecutionSummary staleBinding(
      String runId, TestPlan plan, String executionId, String reason) {
    plan.setStatus(TestPlanStatus.STALE);
    testPlanRepository.save(plan);
    log.warn("[TestExec] Test plan for run {} is stale: {}", runId, reason);
    broadcaster.emit(
        runId, RunEvent.log(runId, null, "[TestExec] Test evidence unavailable — " + reason));
    return TestExecutionSummary.unavailable(executionId, reason);
  }
}
