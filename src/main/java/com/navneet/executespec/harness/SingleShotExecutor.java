package com.navneet.executespec.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.FileSystemTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Executes simple (SINGLE_SHOT) requests without the planner or worktree system.
 *
 * <p>Builds a synthetic 1-task plan, persists it, then calls
 * {@link WorkerAgent#executeOnMainBranch(AgentTask, String)} directly.
 * Post-worker phases (compile, review, test, report) are handled by PipelineHarness
 * after this executor returns.
 *
 * <p>This bypasses:
 * <ul>
 *   <li>PlannerAgent (no planning LLM call)</li>
 *   <li>Plan approval gate (no human approval)</li>
 *   <li>Git worktrees (worker operates directly on main branch)</li>
 *   <li>SequentialTaskExecutor (no tiered parallel execution)</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SingleShotExecutor {

    private final WorkerAgent workerAgent;
    private final ModelResolver modelResolver;
    private final StateStore stateStore;
    private final SseBroadcaster broadcaster;
    private final ObjectMapper mapper;
    private final FileSystemTool fsTool;

    @Value("${agent.workspace}")
    private String workspaceRoot;

    /**
     * Result of single-shot execution — mirrors SequentialTaskExecutor.SequentialExecutionResult
     * so PipelineHarness can handle both paths uniformly.
     */
    public record SingleShotResult(
        WorkerReport report,
        TaskPlan syntheticPlan,
        boolean success
    ) {
        public List<WorkerReport> reports() { return List.of(report); }
    }

    /**
     * Builds a synthetic plan, persists it (auto-approved), and executes a single worker
     * on the main branch.
     *
     * @param run            the pipeline run
     * @param userRequest    the raw user specification
     * @return execution result with the worker report and synthetic plan
     */
    public SingleShotResult execute(PipelineRun run, String userRequest) {
        String runId = run.getRunId();
        log.info("[SingleShot] Starting single-shot execution for run {}", runId);

        // ── 1. Build synthetic plan ──────────────────────────────────────────
        String taskId = runId.substring(0, 8) + "_single_shot";
        ResolvedModel resolved = modelResolver.resolve("WorkerAgent", run.getAiProviderEnum());

        TaskDefinition taskDef = TaskDefinition.builder()
            .taskId(taskId)
            .title("Implement request")
            .objective(userRequest)
            .boundaries("Single-shot execution — implement the complete request in one pass.")
            .complexity("MEDIUM")
            .assignedModel(resolved.getModel())
            .ownedFiles(List.of())
            .sharedFiles(List.of())
            .dependsOn(List.of())
            .build();

        TaskPlan syntheticPlan = TaskPlan.builder()
            .projectType("UNKNOWN")
            .planTitle("Single-shot: " + truncateTitle(userRequest))
            .planSummary(truncateSummary(userRequest))
            .tasks(List.of(taskDef))
            .build();

        // ── 2. Persist plan + task (auto-approved, no gate) ──────────────────
        stateStore.savePlan(runId, syntheticPlan);
        stateStore.markApproved(runId);
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

        log.info("[SingleShot] Synthetic plan created for run {} — task={}, model={}:{}",
            runId, taskId, resolved.providerName(), resolved.getModel());

        // ── 3. Execute worker on main branch (no worktree) ──────────────────
        // Re-fetch the task from DB (savePlan may have prefixed the taskId)
        List<AgentTask> tasks = stateStore.getPendingTasks(runId);
        if (tasks.isEmpty()) {
            log.error("[SingleShot] No pending tasks found after savePlan for run {}", runId);
            return new SingleShotResult(
                WorkerReport.partial(taskId, "No task found after plan save", 0, 0),
                syntheticPlan, false
            );
        }

        AgentTask agentTask = tasks.get(0);

        // Lock the task (PENDING → WORKING in DB) so UI shows correct status
        stateStore.lockTask(agentTask.getTaskId(), resolved.getModel(), "main");
        broadcaster.emit(runId, RunEvent.taskStatus(runId, agentTask.getTaskId(), "WORKING"));

        // Write .agent-context/ files BEFORE worker starts (worker reads these for orientation)
        try {
            String planJson = mapper.writeValueAsString(syntheticPlan);
            initAgentContext(runId, planJson);
        } catch (Exception e) {
            log.warn("[SingleShot] Failed to init agent context for run {}: {}", runId, e.getMessage());
        }

        WorkerReport report;
        try {
            report = workerAgent.executeOnMainBranch(agentTask, runId);
        } catch (Exception e) {
            log.error("[SingleShot] Worker failed for run {}: {}", runId, e.getMessage(), e);
            report = WorkerReport.partial(agentTask.getTaskId(),
                "Worker exception: " + e.getMessage(), 0, 0);
        }

        // ── 4. Update task state ────────────────────────────────────────────
        stateStore.completeTask(agentTask.getTaskId(), report);
        String status = "complete".equals(report.getStatus()) ? "COMPLETE" : "PARTIAL";
        broadcaster.emit(runId, RunEvent.taskStatus(runId, agentTask.getTaskId(), status));

        boolean success = "COMPLETE".equals(status);
        log.info("[SingleShot] Run {} completed — status={}, files created={}, modified={}",
            runId, status,
            report.getFilesCreated() != null ? report.getFilesCreated().size() : 0,
            report.getFilesModified() != null ? report.getFilesModified().size() : 0);

        return new SingleShotResult(report, syntheticPlan, success);
    }

    private String truncateTitle(String request) {
        if (request == null) return "request";
        String clean = request.replaceAll("\\s+", " ").trim();
        return clean.length() > 80 ? clean.substring(0, 77) + "..." : clean;
    }

    /**
     * Writes .agent-context/ files so the worker has orientation (same as PipelineHarness.initAgentContext).
     * For single-shot, the context tells the worker it's in a fresh workspace on the main branch.
     */
    private void initAgentContext(String runId, String planJson) {
        fsTool.writeFile(runId, ".agent-context/task-assignments.json", planJson);
        fsTool.writeFile(runId, ".agent-context/file-locks.json", "{}");
        fsTool.writeFile(runId, ".agent-context/shared-contracts.json", "{}");
        fsTool.writeFile(runId, ".agent-context/project.json", "{\"project_type\": \"UNKNOWN\"}");
        fsTool.writeFile(runId, ".agent-context/CODEBASE_CONTEXT.md", """
            # Workspace Context

            This is a **fresh, isolated workspace** for this run. No project exists yet —
            create all files from scratch.

            ## Environment

            - Git is initialised on branch `main`.
            - The git identity is pre-configured — `git_commit` will work immediately.
            - Available build tools: `mvn` (Maven 3.x), `npm`, `python`.
            - Java version: 21. Spring Boot: 3.3.x (use for Java/Spring projects).

            ## Single-Shot Mode

            You are the ONLY worker for this run. Implement the complete request in one pass.
            Write ALL files using the `writeFile` tool, then commit with `git_commit`.

            ## Constraints

            - ALWAYS use `writeFile` to create files — do NOT describe file contents in text.
            - Commit after writing all files: `git_commit` with message "feat: <description>".
            - Do NOT run `git push`.
            """);
    }

    /** Truncate summary to fit run_summary column (varchar 500). */
    private String truncateSummary(String request) {
        if (request == null) return "";
        String clean = request.replaceAll("\\s+", " ").trim();
        return clean.length() > 490 ? clean.substring(0, 487) + "..." : clean;
    }
}
