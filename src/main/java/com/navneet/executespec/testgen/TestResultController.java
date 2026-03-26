package com.navneet.executespec.testgen;

import com.navneet.executespec.agent.ApplySuggestionTooLargeException;
import com.navneet.executespec.agent.ConversationAgent;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.harness.PipelineHarness;
import com.navneet.executespec.state.ApplySource;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.*;
import java.util.stream.Collectors;

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
    private final PipelineHarness harness;

    /**
     * Get full test report for a run.
     */
    @GetMapping("/runs/{runId}/test-report")
    public ResponseEntity<Map<String, Object>> getTestReport(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        runAccessGuard.requireRunAccess(runId, userDetails);

        TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }

        List<TestResult> results = testResultRepository.findByTestPlanId(plan.getId());

        int passed = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
        int failed = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
        int skipped = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.SKIPPED).count();
        int errors = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.ERROR).count();

        // Group failures by root cause signature
        List<TestResult> failures = results.stream()
                .filter(r -> r.getStatus() == TestResultStatus.FAILED || r.getStatus() == TestResultStatus.ERROR)
                .toList();

        List<Map<String, Object>> rootCauseGroups = new ArrayList<>();
        if (!failures.isEmpty()) {
            Map<String, List<TestResult>> grouped = failures.stream()
                    .collect(Collectors.groupingBy(
                            tr -> ValidationReportService.extractFailureSignature(tr.getFailureMessage()),
                            LinkedHashMap::new, Collectors.toList()));

            for (var entry : grouped.entrySet()) {
                List<TestResult> group = entry.getValue();
                String suggestion = group.stream()
                        .map(TestResult::getSuggestion)
                        .filter(s -> s != null && !s.isBlank())
                        .findFirst().orElse(null);
                List<Long> testResultIds = group.stream().map(TestResult::getId).toList();

                rootCauseGroups.add(Map.of(
                        "signature", entry.getKey(),
                        "count", group.size(),
                        "testMethods", group.stream().map(TestResult::getTestMethod).toList(),
                        "testResultIds", testResultIds,
                        "suggestion", suggestion != null ? suggestion : "",
                        "hasSuggestion", suggestion != null
                ));
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

    /**
     * Mark a test result as invalid (incorrect test — exclude from failure count).
     */
    @PutMapping("/test-results/{id}/mark-invalid")
    public ResponseEntity<Void> markInvalid(
            @PathVariable Long id,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        TestResult result = testResultRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Test result not found"));

        // Verify access via the test plan's run
        TestPlan plan = testPlanRepository.findById(result.getTestPlanId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Test plan not found"));
        runAccessGuard.requireRunAccess(plan.getRunId(), userDetails);

        result.setStatus(TestResultStatus.SKIPPED);
        result.setFailureMessage("[Marked invalid by user] " +
                (result.getFailureMessage() != null ? result.getFailureMessage() : ""));
        testResultRepository.save(result);

        return ResponseEntity.ok().build();
    }

    /**
     * Collect failed test suggestions, apply them as code fixes, then re-run tests.
     * Optional ?signature= param filters to a specific root-cause group.
     */
    @PostMapping("/runs/{runId}/apply-test-fixes")
    public ResponseEntity<Map<String, Object>> applyTestFixes(
            @PathVariable String runId,
            @RequestParam(required = false) String signature,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        runAccessGuard.requireRunAccess(runId, userDetails);

        PipelineRun run = stateStore.findRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

        // Collect suggestions from failed tests
        TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
        if (plan == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No test plan found for this run");
        }

        List<TestResult> failedResults = testResultRepository.findByTestPlanId(plan.getId()).stream()
                .filter(r -> r.getStatus() == TestResultStatus.FAILED || r.getStatus() == TestResultStatus.ERROR)
                .filter(r -> r.getSuggestion() != null && !r.getSuggestion().isBlank())
                .toList();

        // If a signature is provided, filter to only that root-cause group
        if (signature != null && !signature.isBlank()) {
            failedResults = failedResults.stream()
                    .filter(r -> signature.equals(
                            ValidationReportService.extractFailureSignature(r.getFailureMessage())))
                    .toList();
        }

        if (failedResults.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No failed tests with fix suggestions found"
                    + (signature != null ? " for root cause: " + signature : ""));
        }

        // For root-cause groups, compile a single focused goal instead of N individual ones
        String compiledGoal;
        if (signature != null && failedResults.size() > 1) {
            // Single root-cause goal — more focused than listing every test individually
            String firstSuggestion = failedResults.stream()
                    .map(TestResult::getSuggestion)
                    .filter(s -> s != null && !s.isBlank())
                    .findFirst().orElse("");
            String methods = failedResults.stream()
                    .map(TestResult::getTestMethod)
                    .limit(5)
                    .collect(Collectors.joining(", "));
            compiledGoal = failedResults.size() + " tests failing with root cause '" + signature
                    + "' (tests: " + methods + "). Fix: " + firstSuggestion;
        } else {
            compiledGoal = failedResults.stream()
                    .map(r -> "Fix test failure in " + r.getTestMethod() + ": " + r.getSuggestion())
                    .collect(Collectors.joining("\n"));
        }

        // Generate mini-plan and apply
        TaskPlan miniPlan;
        try {
            miniPlan = conversationAgent.generateSuggestionPlan(run, compiledGoal);
        } catch (ApplySuggestionTooLargeException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "too_many_tasks",
                    "compiledGoal", e.getCompiledGoal()
            ));
        }

        int applyIndex = run.getApplySuggestionCount() + 1;
        stateStore.savePlanVersion(runId, "TEST_FIX", userDetails.getUser().getId());
        stateStore.incrementPlanVersion(runId);
        List<AgentTask> newTasks = stateStore.appendSuggestionTasks(runId, miniPlan, applyIndex, ApplySource.REVIEW);
        stateStore.incrementApplySuggestionCount(runId);

        List<String> newTaskIds = newTasks.stream().map(AgentTask::getTaskId).toList();
        harness.executeApplySuggestion(runId, newTaskIds);

        log.info("[ApplyTestFixes] run={} failedTests={} tasks={}", runId, failedResults.size(), newTasks.size());

        return ResponseEntity.accepted().body(Map.of(
                "taskCount", newTasks.size(),
                "failedTests", failedResults.size()
        ));
    }
}
