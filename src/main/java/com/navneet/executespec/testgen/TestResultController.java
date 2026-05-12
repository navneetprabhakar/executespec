package com.navneet.executespec.testgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.agent.ConversationAgent;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.deployment.preview.PreviewService;
import com.navneet.executespec.deployment.preview.PreviewStatus;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.model.TestPlanStatus;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.testgen.model.TestResultStatus;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api")
@Slf4j
@RequiredArgsConstructor
public class TestResultController {

  private final TestPlanRepository testPlanRepository;
  private final TestResultRepository testResultRepository;
  private final RunAccessGuard runAccessGuard;

  private final StateStore stateStore;
  private final ConversationAgent conversationAgent;

  private final TestPreviewCoordinator testPreviewCoordinator;

  /** Lazy to break circular dependency: PreviewService → TestPreviewCoordinator → PreviewService */
  @Lazy @Autowired private PreviewService previewService;

  /** Get full test report for a run. */
  @GetMapping("/runs/{runId}/test-report")
  public ResponseEntity<Map<String, Object>> getTestReport(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    runAccessGuard.requireRunAccess(runId, userDetails);

    TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
    if (plan == null) {
      return ResponseEntity.notFound().build();
    }

    List<TestResult> results = testResultRepository.findByTestPlanId(plan.getId());

    int passed =
        (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
    int failed =
        (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
    int skipped =
        (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.SKIPPED).count();
    int errors =
        (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.ERROR).count();

    // Group failures by root cause signature
    List<TestResult> failures =
        results.stream()
            .filter(
                r ->
                    r.getStatus() == TestResultStatus.FAILED
                        || r.getStatus() == TestResultStatus.ERROR)
            .toList();

    List<Map<String, Object>> rootCauseGroups = new ArrayList<>();
    if (!failures.isEmpty()) {
      Map<String, List<TestResult>> grouped =
          failures.stream()
              .collect(
                  Collectors.groupingBy(
                      tr -> ValidationReportService.extractFailureSignature(tr.getFailureMessage()),
                      LinkedHashMap::new,
                      Collectors.toList()));

      for (var entry : grouped.entrySet()) {
        List<TestResult> group = entry.getValue();
        String suggestion =
            group.stream()
                .map(TestResult::getSuggestion)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);
        List<Long> testResultIds = group.stream().map(TestResult::getId).toList();

        rootCauseGroups.add(
            Map.of(
                "signature",
                entry.getKey(),
                "count",
                group.size(),
                "testMethods",
                group.stream().map(TestResult::getTestMethod).toList(),
                "testResultIds",
                testResultIds,
                "suggestion",
                suggestion != null ? suggestion : "",
                "hasSuggestion",
                suggestion != null));
      }
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("testPlanId", plan.getId());
    response.put("runId", runId);
    response.put("status", plan.getStatus().name());
    response.put("totalTests", results.size());
    response.put("passed", passed);
    response.put("failed", failed);
    response.put("skipped", skipped);
    response.put("errors", errors);
    response.put("rootCauseGroups", rootCauseGroups);
    response.put("results", results);

    return ResponseEntity.ok(response);
  }

  /** Mark a test result as invalid (incorrect test — exclude from failure count). */
  @PutMapping("/test-results/{id}/mark-invalid")
  public ResponseEntity<Void> markInvalid(
      @PathVariable Long id, @AuthenticationPrincipal AppUserDetails userDetails) {

    TestResult result =
        testResultRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Test result not found"));

    // Verify access via the test plan's run
    TestPlan plan =
        testPlanRepository
            .findById(result.getTestPlanId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Test plan not found"));
    // Audit 2026-05-12 review item B #18 — was requireRunAccess (any org member could mutate
    // another user's run). markInvalid downgrades test failures, so restrict to owner +
    // ORG_ADMIN.
    runAccessGuard.requireRunWriteAccess(plan.getRunId(), userDetails);

    result.setStatus(TestResultStatus.SKIPPED);
    result.setFailureMessage(
        "[Marked invalid by user] "
            + (result.getFailureMessage() != null ? result.getFailureMessage() : ""));
    testResultRepository.save(result);

    return ResponseEntity.ok().build();
  }

  /**
   * Manually trigger test execution against a running preview. Used by the "Run Tests" button in
   * the UI for batch runs (or any run with a manual preview).
   */
  @PostMapping("/runs/{runId}/run-tests")
  public ResponseEntity<Map<String, Object>> runTests(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {

    /* V2 check removed — V2 is default */

    // GA-readiness P4.1-followup — multi-tenant access guard. Closes the legacy gap
    // where this endpoint mutated run state (manual test execution) without an org
    // check, so a caller in org B could trigger tests on a run from org A.
    runAccessGuard.requireRunAccess(runId, userDetails);

    PipelineRun run =
        stateStore
            .findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

    // Check preview is running
    var preview = previewService.getStatus(runId);
    if (preview == null || preview.status() != PreviewStatus.RUNNING) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Preview is not running — start the preview first");
    }

    // Find the latest COMPILED test plan
    TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
    if (plan == null || plan.getStatus() != TestPlanStatus.COMPILED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "No compiled test plan found — tests may still be generating");
    }

    testPreviewCoordinator.triggerManualExecution(runId, plan.getId(), preview.url());

    log.info(
        "[RunTests] Manual test execution triggered for run {} against {}", runId, preview.url());

    return ResponseEntity.accepted()
        .body(
            Map.of(
                "runId", runId,
                "testPlanId", plan.getId(),
                "previewUrl", preview.url()));
  }

  /**
   * Collect failed test suggestions, apply them as code fixes, then re-run tests. Optional
   * ?signature= param filters to a specific root-cause group.
   */
  @PostMapping("/runs/{runId}/apply-test-fixes")
  public ResponseEntity<Map<String, Object>> applyTestFixes(
      @PathVariable String runId,
      @RequestParam(required = false) String signature,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    /* V2 check removed — V2 is default */

    // Audit 2026-05-12 review item J #60: this endpoint historically performed a chunk of
    // work (plan generation, plan-version increment, suggestion-task append, counter
    // increment, state-store writes) BEFORE throwing 410 Gone, which mutated data the caller
    // can't see / use. Short-circuit at the top so the 410 is the FIRST observable behaviour
    // and no data is mutated. When V2 wires this back up, remove the throw and the work
    // below proceeds normally.
    //
    // Auth + tenant guard run first so 401/403 fire before 410 (correct HTTP status
    // ordering) AND so AccessGuardCoverageTest ArchUnit pin still sees a guard call on this
    // run-scoped endpoint. The guard call is cheap (one indexed DB lookup) and we still
    // throw 410 immediately after.
    runAccessGuard.requireRunAccess(runId, userDetails);
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.GONE, "apply-test-fixes not yet supported in V2");
    // Original logic preserved below for the V2 wire-up follow-up. Currently unreachable.
    /*
    runAccessGuard.requireRunAccess(runId, userDetails);

    PipelineRun run =
        stateStore
            .findRun(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

    // Collect suggestions from failed tests
    TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
    if (plan == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No test plan found for this run");
    }

    List<TestResult> failedResults =
        testResultRepository.findByTestPlanId(plan.getId()).stream()
            .filter(
                r ->
                    r.getStatus() == TestResultStatus.FAILED
                        || r.getStatus() == TestResultStatus.ERROR)
            .filter(r -> r.getSuggestion() != null && !r.getSuggestion().isBlank())
            .toList();

    // If a signature is provided, filter to only that root-cause group
    if (signature != null && !signature.isBlank()) {
      failedResults =
          failedResults.stream()
              .filter(
                  r ->
                      signature.equals(
                          ValidationReportService.extractFailureSignature(r.getFailureMessage())))
              .toList();
    }

    if (failedResults.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "No failed tests with fix suggestions found"
              + (signature != null ? " for root cause: " + signature : ""));
    }

    // For root-cause groups, compile a single focused goal instead of N individual ones
    String compiledGoal;
    if (signature != null && failedResults.size() > 1) {
      // Single root-cause goal — more focused than listing every test individually
      String firstSuggestion =
          failedResults.stream()
              .map(TestResult::getSuggestion)
              .filter(s -> s != null && !s.isBlank())
              .findFirst()
              .orElse("");
      String methods =
          failedResults.stream()
              .map(TestResult::getTestMethod)
              .limit(5)
              .collect(Collectors.joining(", "));
      compiledGoal =
          failedResults.size()
              + " tests failing with root cause '"
              + signature
              + "' (tests: "
              + methods
              + "). Fix: "
              + firstSuggestion;
    } else {
      compiledGoal =
          failedResults.stream()
              .map(r -> "Fix test failure in " + r.getTestMethod() + ": " + r.getSuggestion())
              .collect(Collectors.joining("\n"));
    }

    // Generate mini-plan and apply
    TaskPlan miniPlan;
    try {
      miniPlan = conversationAgent.generateSuggestionPlan(run, compiledGoal);
    } catch (ApplySuggestionTooLargeException e) {
      return ResponseEntity.unprocessableEntity()
          .body(Map.of("error", "too_many_tasks", "compiledGoal", e.getCompiledGoal()));
    }

    int applyIndex = run.getApplySuggestionCount() + 1;
    stateStore.incrementPlanVersion(runId);
    stateStore.savePlanVersion(runId, "TEST_FIX", userDetails.getUser().getId());
    List<AgentTask> newTasks =
        stateStore.appendSuggestionTasks(runId, miniPlan, applyIndex, ApplySource.REVIEW);
    stateStore.incrementApplySuggestionCount(runId);

    @SuppressWarnings("unused")
    List<String> newTaskIds = newTasks.stream().map(AgentTask::getTaskId).toList();
    log.info(
        "[ApplyTestFixes] run={} failedTests={} tasks={} — V2 apply-suggestion not yet implemented",
        runId,
        failedResults.size(),
        newTasks.size());
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.GONE, "apply-test-fixes not yet supported in V2");
    */
  }
}
