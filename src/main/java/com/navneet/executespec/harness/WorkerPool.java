package com.navneet.executespec.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.tool.GitTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes worker tasks <b>sequentially</b> in topological (dependency) order.
 *
 * <p>After each task completes successfully, its branch is immediately merged to main
 * (progressive merge) so that:
 * <ul>
 *   <li>Subsequent tasks see all prior work on main.</li>
 *   <li>The Code tab in the UI shows files as soon as each task finishes.</li>
 *   <li>Merge conflicts are detected early (per-task, not end-of-run).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerPool {

    private final WorkerAgent workerAgent;
    private final StateStore stateStore;
    private final ObjectMapper mapper;
    private final SseBroadcaster broadcaster;
    private final GitTool gitTool;
    private final GitMergeService mergeService;

    // ── Tier 5: Thread registry for cancellation ──────────────────────────────
    private final ConcurrentHashMap<String, Thread> taskThreads = new ConcurrentHashMap<>();

    // ── Return type for runAll ────────────────────────────────────────────────
    public record WorkerPoolResult(
        List<WorkerReport> reports,
        Set<String> mergedTaskIds,
        Set<String> failedMergeTaskIds
    ) {}

    public void cancelTask(String taskId) {
        Thread t = taskThreads.remove(taskId);
        if (t != null && t.isAlive()) {
            t.interrupt();
            log.info("Interrupted thread for task {}", taskId);
        }
    }

    public void cancelAllTasksForRun(String runId) {
        stateStore.getTasksForRun(runId).forEach(task -> cancelTask(task.getTaskId()));
    }

    /**
     * Runs a single task synchronously on the calling thread and persists the result.
     * Used by the force-retry flow so callers can await completion.
     */
    public WorkerReport executeTask(AgentTask task, String runId) {
        String taskId = task.getTaskId();
        taskThreads.put(taskId, Thread.currentThread());
        try {
            broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));
            WorkerReport report = workerAgent.execute(task, task.getRunId());
            stateStore.completeTask(taskId, report);
            String st = normalizeStatus(report.getStatus());
            broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, st));
            return report;
        } finally {
            taskThreads.remove(taskId);
        }
    }

    public void resubmitTask(AgentTask task, String runId) {
        Thread t = Thread.ofVirtual()
            .name("worker-retry-" + task.getTaskId())
            .start(() -> {
                try {
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "WORKING"));
                    WorkerReport report = workerAgent.execute(task, task.getRunId());
                    stateStore.completeTask(task.getTaskId(), report);
                    String st = normalizeStatus(report.getStatus());
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), st));
                    log.info("Resubmitted task [{}]: {}", task.getTaskId(), report.getStatus());
                } finally {
                    taskThreads.remove(task.getTaskId());
                }
            });
        taskThreads.put(task.getTaskId(), t);
    }

    /**
     * Runs all tasks <b>sequentially</b> in topological order (respecting depends_on).
     * After each task completes, merges its branch to main immediately.
     *
     * @return WorkerPoolResult with reports and merge tracking sets
     */
    public WorkerPoolResult runAll(List<AgentTask> tasks, String runId, String model) {
        List<WorkerReport> reports = new ArrayList<>();
        Set<String> completedTaskIds = new HashSet<>();
        Set<String> mergedTaskIds = new HashSet<>();
        Set<String> failedMergeTaskIds = new HashSet<>();
        List<AgentTask> previouslyMerged = new ArrayList<>();

        // Topological sort: process tasks in dependency order
        List<AgentTask> ordered = topologicalSort(tasks);

        for (AgentTask task : ordered) {
            String taskId = task.getTaskId();
            taskThreads.put(taskId, Thread.currentThread());

            try {
                // Emit WORKING status via SSE
                broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));

                String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;
                String taskModel = task.getAssignedModel() != null
                    ? task.getAssignedModel() : model;

                if (!stateStore.lockTask(taskId, taskModel, branchName)) {
                    log.warn("Task {} already locked — skipping", taskId);
                    completedTaskIds.add(taskId);
                    continue;
                }
                // lockTask persists branchName to DB but doesn't update the in-memory
                // AgentTask object — set it here so downstream merge code can read it.
                task.setBranchName(branchName);
                task.setAssignedModel(taskModel);

                WorkerReport report = workerAgent.execute(task, runId);
                stateStore.completeTask(taskId, report);
                completedTaskIds.add(taskId);

                String st = normalizeStatus(report.getStatus());
                broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, st));
                log.info("  Task [{}]: {}", taskId, st);
                reports.add(report);

                // Progressive merge: merge completed task's branch to main immediately
                // Uses GitMergeService's conflict resolution cascade
                if ("COMPLETE".equals(st)) {
                    boolean merged = tryMergeToMain(runId, task, branchName, previouslyMerged);
                    if (merged) {
                        mergedTaskIds.add(taskId);
                        previouslyMerged.add(task);
                    } else {
                        failedMergeTaskIds.add(taskId);
                    }
                }

            } catch (Exception e) {
                log.error("Worker task {} threw uncaught exception: {}",
                    taskId, e.getMessage(), e);
                WorkerReport blocked = WorkerReport.blocked(
                    taskId, "Uncaught exception: " + e.getMessage(), 0, 0);
                try {
                    stateStore.completeTask(taskId, blocked);
                } catch (Exception dbEx) {
                    log.error("Failed to persist BLOCKED report for task {}: {}",
                        taskId, dbEx.getMessage());
                }
                completedTaskIds.add(taskId);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "BLOCKED"));
                reports.add(blocked);
            } finally {
                taskThreads.remove(taskId);
            }
        }

        return new WorkerPoolResult(reports, mergedTaskIds, failedMergeTaskIds);
    }

    // ── Progressive merge ────────────────────────────────────────────────────

    /**
     * Merges the task's branch to main immediately after completion.
     * Uses GitMergeService's conflict resolution cascade (specialized mergers → Haiku → Sonnet).
     * Returns true on success, false on unresolvable conflict.
     */
    private boolean tryMergeToMain(String runId, AgentTask task, String branchName,
                                    List<AgentTask> previouslyMerged) {
        try {
            GitMergeService.TaskMergeResult result = mergeService.mergeTaskBranchWithResolution(
                runId, task, previouslyMerged);

            if (result.outcome() == MergeOutcome.FAILED) {
                stateStore.failTask(task.getTaskId(),
                    "Merge conflict — auto-resolution failed for: "
                    + String.join(", ", result.failedFiles())
                    + ". Retry this task to regenerate the changes cleanly.");
                broadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "FAILED"));
                broadcaster.emit(runId, RunEvent.log(runId, task.getTaskId(),
                    "⚠️ Merge conflict on branch " + branchName + " — task marked FAILED"));
                return false;
            }

            if (result.outcome() != MergeOutcome.CLEAN) {
                broadcaster.emit(runId, RunEvent.log(runId, task.getTaskId(),
                    "✅ Auto-resolved merge conflict in: " + String.join(", ", result.resolvedFiles())));
            }

            gitTool.removeWorktree(runId, task.getTaskId());
            log.info("Progressive merge: task {} branch {} merged to main (outcome={})",
                task.getTaskId(), branchName, result.outcome());
            return true;
        } catch (Exception e) {
            log.error("Progressive merge failed for task {}: {}", task.getTaskId(), e.getMessage(), e);
            return false;
        }
    }

    // ── Topological sort ─────────────────────────────────────────────────────

    /**
     * Kahn's algorithm: returns tasks in dependency-first order.
     * Tasks with no dependencies come first; tasks whose deps are all
     * satisfied come next, etc.
     */
    private List<AgentTask> topologicalSort(List<AgentTask> tasks) {
        Map<String, AgentTask> byId = new LinkedHashMap<>();
        Map<String, List<String>> deps = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (AgentTask t : tasks) {
            byId.put(t.getTaskId(), t);
            TaskDefinition def = parseTaskDef(t.getTaskJson());
            List<String> taskDeps = def.getDependsOn() != null ? def.getDependsOn() : List.of();
            deps.put(t.getTaskId(), taskDeps);
            inDegree.put(t.getTaskId(), 0);
        }

        // Count in-degrees (only for deps that are in our task set)
        for (var entry : deps.entrySet()) {
            int count = 0;
            for (String dep : entry.getValue()) {
                if (byId.containsKey(dep)) count++;
            }
            inDegree.put(entry.getKey(), count);
        }

        // BFS: start with tasks that have zero in-degree
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<AgentTask> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            sorted.add(byId.get(id));

            // Reduce in-degree for tasks that depend on this one
            for (var entry : deps.entrySet()) {
                if (entry.getValue().contains(id)) {
                    int newDeg = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDeg);
                    if (newDeg == 0) queue.add(entry.getKey());
                }
            }
        }

        // If some tasks weren't added (circular deps), append them at the end
        if (sorted.size() < tasks.size()) {
            Set<String> added = new HashSet<>();
            sorted.forEach(t -> added.add(t.getTaskId()));
            for (AgentTask t : tasks) {
                if (!added.contains(t.getTaskId())) {
                    log.warn("Task {} has unresolved dependencies — appending at end", t.getTaskId());
                    sorted.add(t);
                }
            }
        }

        return sorted;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String normalizeStatus(String raw) {
        if (raw == null) return "BLOCKED";
        return switch (raw.toLowerCase()) {
            case "complete", "done" -> "COMPLETE";
            case "blocked" -> "BLOCKED";
            case "partial" -> "PARTIAL";
            case "failed"  -> "FAILED";
            default -> raw.toUpperCase();
        };
    }

    private TaskDefinition parseTaskDef(String json) {
        try {
            return mapper.readValue(json, TaskDefinition.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse task definition", e);
        }
    }
}
