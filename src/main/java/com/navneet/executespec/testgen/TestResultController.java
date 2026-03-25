package com.navneet.executespec.testgen;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TestResultController {

    private final TestPlanRepository testPlanRepository;
    private final TestResultRepository testResultRepository;
    private final RunAccessGuard runAccessGuard;

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

        return ResponseEntity.ok(Map.of(
                "testPlanId", plan.getId(),
                "runId", runId,
                "status", plan.getStatus().name(),
                "totalTests", results.size(),
                "passed", passed,
                "failed", failed,
                "skipped", skipped,
                "errors", errors,
                "results", results
        ));
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
}
