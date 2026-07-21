package com.navneet.executespec.testgen;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestPlanStatus;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Docker-based headless test executor for batch runs.
 *
 * <p>Runs the compiled test project inside an isolated Docker container with {@code --network=none}
 * — no live preview required. Only unit tests execute (integration tests that call {@code
 * -Dapp.base-url} are skipped via {@code -DskipITs=true}).
 *
 * <p>Shares the attempt-provenance contract of {@link TestExecutionService} (RR-1582 / RR-1583 /
 * RR-1587): reports are reset before launch, the container runs under a timeout that can fire, the
 * exit status must mean "tests ran", every parsed report must postdate the launch, and the plan's
 * V277 workspace fingerprint must still match. Otherwise an explicitly unavailable summary is
 * returned instead of a pass count.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HeadlessTestExecutor {

  private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(300); // 5 minutes

  /** See {@link TestExecutionService}: 0 = all passed, 1 = Surefire ran and some failed. */
  private static final List<Integer> TESTS_RAN_EXIT_CODES = List.of(0, 1);

  private final TestPlanRepository testPlanRepository;
  private final TestResultRepository testResultRepository;
  private final SurefireReportParser surefireReportParser;
  private final SseBroadcaster broadcaster;
  private final TestGenerationAdmissionService admissionService;

  /**
   * Execute unit tests for a batch run without a live preview.
   *
   * @param runId the run ID
   * @param testPlanId the test plan to update
   * @return execution summary (never null)
   */
  public TestExecutionSummary execute(String runId, Long testPlanId) {
    String executionId = UUID.randomUUID().toString();
    log.info(
        "[HeadlessTestExec] Starting headless test execution for run {} (execution {})",
        runId,
        executionId);
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId, null, "[TestExec] Running unit tests (headless mode — no preview required)"));

    Path testProjectDir = Path.of("workspace-test", runId);
    Path absTestDir = testProjectDir.toAbsolutePath();

    TestPlan plan = testPlanRepository.findById(testPlanId).orElse(null);

    // RR-1583 — refuse to score a plan against a workspace it was not generated from.
    String plannedFingerprint = plan != null ? plan.getWorkspaceFingerprint() : null;
    String preFingerprint = admissionService.currentWorkspaceFingerprint(runId);
    String bindingProblem =
        TestExecutionProvenance.fingerprintMismatch(plannedFingerprint, preFingerprint);
    if (bindingProblem != null) {
      return terminate(runId, plan, TestPlanStatus.STALE, executionId, bindingProblem);
    }

    Instant start = Instant.now();
    try {
      // RR-1582 — clear any report left behind by an earlier attempt before launching this one.
      Path reportsDir = testProjectDir.resolve("target/surefire-reports");
      if (!TestExecutionProvenance.clearReports(reportsDir)) {
        return terminate(
            runId,
            plan,
            TestPlanStatus.FAILED,
            executionId,
            "surefire report directory could not be reset before execution");
      }
      Instant freshnessFloor = start.minusSeconds(2);
      String containerName = TestExecutionProvenance.containerName("es-test-headless", executionId);

      // Docker container with no network access — unit tests only, no live app required
      ProcessBuilder pb =
          new ProcessBuilder(
              "docker",
              "run",
              "--rm",
              "--name",
              containerName,
              "--network=none",
              "--memory=2g",
              "--cpus=2",
              "--read-only",
              "--tmpfs",
              "/tmp:rw,size=256m",
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
              "-DskipITs=true", // skip integration tests (require live app)
              "-Dsurefire.failIfNoSpecifiedTests=false",
              "-T",
              "1C");
      pb.redirectErrorStream(true);

      BoundedProcess.Outcome outcome = BoundedProcess.run(pb, EXECUTION_TIMEOUT);
      if (!outcome.finished()) {
        TestExecutionProvenance.killContainer(containerName);
        log.warn("[HeadlessTestExec] Timed out for run {} — destroying container", runId);
        return terminate(
            runId,
            plan,
            TestPlanStatus.FAILED,
            executionId,
            "test execution exceeded " + EXECUTION_TIMEOUT.toSeconds() + "s and was destroyed");
      }
      if (!TESTS_RAN_EXIT_CODES.contains(outcome.exitCode())) {
        return terminate(
            runId,
            plan,
            TestPlanStatus.FAILED,
            executionId,
            "test runner exited " + outcome.exitCode() + " without running tests");
      }

      SurefireReportParser.ReportAdmission admission =
          surefireReportParser.admit(reportsDir, freshnessFloor);
      if (!admission.admitted()) {
        return terminate(runId, plan, TestPlanStatus.FAILED, executionId, admission.reason());
      }
      List<Path> attemptReports =
          TestExecutionProvenance.isolateAttempt(
              testProjectDir, executionId, admission.reportFiles());

      List<TestResult> results = surefireReportParser.parse(attemptReports, testPlanId);

      String postFingerprint = admissionService.currentWorkspaceFingerprint(runId);
      String drift =
          TestExecutionProvenance.fingerprintMismatch(plannedFingerprint, postFingerprint);
      if (drift != null) {
        return terminate(runId, plan, TestPlanStatus.STALE, executionId, drift);
      }

      testResultRepository.saveAll(results);

      long durationMs = java.time.Duration.between(start, Instant.now()).toMillis();

      int passed =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
      int failed =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
      int skipped =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.SKIPPED).count();
      int errors =
          (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.ERROR).count();

      if (plan != null) {
        plan.setStatus(TestPlanStatus.COMPLETED);
        testPlanRepository.save(plan);
      }

      log.info(
          "[HeadlessTestExec] Complete for run {}: {}/{} passed (headless, execution {})",
          runId,
          passed,
          results.size(),
          executionId);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              null,
              "[TestExec] Headless tests complete: " + passed + "/" + results.size() + " passed"));

      return TestExecutionSummary.builder()
          .totalTests(results.size())
          .passed(passed)
          .failed(failed)
          .skipped(skipped)
          .errors(errors)
          .executionDurationMs(durationMs)
          .executionVerified(Boolean.TRUE)
          .executionId(executionId)
          .build();

    } catch (Exception e) {
      log.error("[HeadlessTestExec] Failed for run {}: {}", runId, e.getMessage(), e);
      return terminate(
          runId,
          plan,
          TestPlanStatus.FAILED,
          executionId,
          "headless test execution failed: " + e.getMessage());
    }
  }

  private TestExecutionSummary terminate(
      String runId, TestPlan plan, TestPlanStatus status, String executionId, String reason) {
    if (plan != null) {
      plan.setStatus(status);
      testPlanRepository.save(plan);
    }
    log.warn("[HeadlessTestExec] No verified test evidence for run {}: {}", runId, reason);
    broadcaster.emit(
        runId, RunEvent.log(runId, null, "[TestExec] Test evidence unavailable — " + reason));
    return TestExecutionSummary.unavailable(executionId, reason);
  }
}
