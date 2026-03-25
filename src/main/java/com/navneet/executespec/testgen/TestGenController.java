package com.navneet.executespec.testgen;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.testgen.model.*;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TestGenController {

    private final TestPlanRepository testPlanRepository;
    private final TestTaskRepository testTaskRepository;
    private final TestResultRepository testResultRepository;
    private final RunAccessGuard runAccessGuard;

    /**
     * Get test plan with all test tasks (for Tasks tab).
     */
    @GetMapping("/runs/{runId}/test-plan")
    public ResponseEntity<Map<String, Object>> getTestPlan(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        runAccessGuard.requireRunAccess(runId, userDetails);

        TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }

        List<TestTask> tasks = testTaskRepository.findByTestPlanId(plan.getId());

        List<Map<String, Object>> taskDtos = tasks.stream()
                .map(t -> Map.<String, Object>of(
                        "id", t.getId(),
                        "taskName", t.getTaskName(),
                        "taskDescription", t.getTaskDescription() != null ? t.getTaskDescription() : "",
                        "targetEntity", t.getTargetEntity() != null ? t.getTargetEntity() : "",
                        "testCategory", t.getTestCategory() != null ? t.getTestCategory().name() : "",
                        "status", t.getStatus().name(),
                        "compilationError", t.getCompilationError() != null ? t.getCompilationError() : ""
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "testPlanId", plan.getId(),
                "runId", runId,
                "status", plan.getStatus().name(),
                "tasks", taskDtos
        ));
    }

    /**
     * Get test pipeline graph data (for Graph tab).
     */
    @GetMapping("/runs/{runId}/test-graph")
    public ResponseEntity<Map<String, Object>> getTestGraph(
            @PathVariable String runId,
            @AuthenticationPrincipal AppUserDetails userDetails) {

        runAccessGuard.requireRunAccess(runId, userDetails);

        TestPlan plan = testPlanRepository.findByRunId(runId).orElse(null);
        if (plan == null) {
            return ResponseEntity.notFound().build();
        }

        List<TestTask> tasks = testTaskRepository.findByTestPlanId(plan.getId());
        List<TestResult> results = testResultRepository.findByTestPlanId(plan.getId());

        // Build graph nodes
        List<Map<String, Object>> nodes = new java.util.ArrayList<>();

        // Planner node
        nodes.add(Map.of(
                "id", "test-planner",
                "label", "Test Planner",
                "type", "agent",
                "status", plan.getStatus().ordinal() >= TestPlanStatus.GENERATING.ordinal() ? "COMPLETED" : plan.getStatus().name()
        ));

        // Worker nodes (one per task)
        for (TestTask task : tasks) {
            nodes.add(Map.of(
                    "id", "test-worker-" + task.getId(),
                    "label", task.getTaskName(),
                    "type", "worker",
                    "status", task.getStatus().name(),
                    "category", task.getTestCategory() != null ? task.getTestCategory().name() : ""
            ));
        }

        // Compilation node
        nodes.add(Map.of(
                "id", "test-compile",
                "label", "Compile Tests",
                "type", "gate",
                "status", plan.getStatus().ordinal() >= TestPlanStatus.COMPILED.ordinal() ? "COMPLETED" :
                        plan.getStatus() == TestPlanStatus.GENERATING ? "PENDING" : plan.getStatus().name()
        ));

        // Execution node
        String execStatus = "PENDING";
        if (!results.isEmpty()) execStatus = "COMPLETED";
        else if (plan.getStatus() == TestPlanStatus.EXECUTING) execStatus = "EXECUTING";
        nodes.add(Map.of(
                "id", "test-execute",
                "label", "Execute Tests",
                "type", "execution",
                "status", execStatus
        ));

        // Report node
        String reportStatus = !results.isEmpty() ? "COMPLETED" : "PENDING";
        nodes.add(Map.of(
                "id", "test-report",
                "label", "Test Report",
                "type", "report",
                "status", reportStatus
        ));

        // Build edges
        List<Map<String, String>> edges = new java.util.ArrayList<>();
        edges.add(Map.of("from", "test-planner", "to", "test-worker-first"));
        for (TestTask task : tasks) {
            edges.add(Map.of("from", "test-planner", "to", "test-worker-" + task.getId()));
            edges.add(Map.of("from", "test-worker-" + task.getId(), "to", "test-compile"));
        }
        edges.add(Map.of("from", "test-compile", "to", "test-execute"));
        edges.add(Map.of("from", "test-execute", "to", "test-report"));

        // Summary stats
        int passed = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();
        int failed = (int) results.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();

        return ResponseEntity.ok(Map.of(
                "planStatus", plan.getStatus().name(),
                "nodes", nodes,
                "edges", edges,
                "totalTasks", tasks.size(),
                "completedTasks", tasks.stream().filter(t -> t.getStatus() == TestTaskStatus.COMPILED).count(),
                "failedTasks", tasks.stream().filter(t -> t.getStatus() == TestTaskStatus.FAILED).count(),
                "testResults", Map.of("total", results.size(), "passed", passed, "failed", failed)
        ));
    }
}
