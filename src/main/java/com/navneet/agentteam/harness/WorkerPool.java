package com.navneet.agentteam.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.WorkerAgent;
import com.navneet.agentteam.agent.model.TaskDefinition;
import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkerPool {

    private final WorkerAgent workerAgent;
    private final StateStore stateStore;
    private final ObjectMapper mapper;

    // ── Tier 5: Thread registry for cancellation ──────────────────────────────
    private final ConcurrentHashMap<String, Thread> taskThreads = new ConcurrentHashMap<>();

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
    public WorkerReport executeTask(AgentTask task) {
        String taskId = task.getTaskId();
        taskThreads.put(taskId, Thread.currentThread());
        try {
            WorkerReport report = workerAgent.execute(task, task.getRunId());
            stateStore.completeTask(taskId, report);
            return report;
        } finally {
            taskThreads.remove(taskId);
        }
    }

    public void resubmitTask(AgentTask task) {
        Thread t = Thread.ofVirtual()
            .name("worker-retry-" + task.getTaskId())
            .start(() -> {
                try {
                    WorkerReport report = workerAgent.execute(task, task.getRunId());
                    stateStore.completeTask(task.getTaskId(), report);
                    log.info("Resubmitted task [{}]: {}", task.getTaskId(), report.getStatus());
                } finally {
                    taskThreads.remove(task.getTaskId());
                }
            });
        taskThreads.put(task.getTaskId(), t);
    }

    public List<WorkerReport> runAll(List<AgentTask> tasks, String runId, String model) {
        List<WorkerReport> reports = new CopyOnWriteArrayList<>();
        Set<String> completedTaskIds = ConcurrentHashMap.newKeySet();

        // Run until all tasks are complete or there's nothing left to run
        List<AgentTask> remaining = new ArrayList<>(tasks);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {

            while (!remaining.isEmpty()) {
                // Find tasks whose dependencies are all met
                List<AgentTask> ready = remaining.stream()
                    .filter(t -> areDependenciesMet(t, completedTaskIds))
                    .toList();

                if (ready.isEmpty()) {
                    // Nothing ready — wait for in-flight tasks to complete
                    Thread.sleep(500);
                    continue;
                }

                // Remove ready tasks from remaining before submitting
                remaining.removeAll(ready);

                // Submit all ready tasks in parallel
                List<Future<WorkerReport>> futures = ready.stream()
                    .map(task -> executor.submit(() -> {
                        taskThreads.put(task.getTaskId(), Thread.currentThread());
                        try {
                            String branchName = "agent/" + runId.substring(0, 8) + "/" + task.getTaskId();
                            if (!stateStore.lockTask(task.getTaskId(), model, branchName)) {
                                log.warn("Task {} already locked -- skipping", task.getTaskId());
                                // Mark as completed so dependents are not blocked forever
                                completedTaskIds.add(task.getTaskId());
                                return null;
                            }
                            WorkerReport report = workerAgent.execute(task, runId);
                            stateStore.completeTask(task.getTaskId(), report);
                            completedTaskIds.add(task.getTaskId());
                            log.info("  Task [{}]: {}", task.getTaskId(), report.getStatus());
                            return report;
                        } catch (Exception e) {
                            // Uncaught exceptions (e.g. ProviderUnavailableException at iteration=0)
                            // must be caught here — if not, the task is never marked done and
                            // all dependents spin in the while(!remaining.isEmpty()) loop forever.
                            log.error("Worker task {} threw uncaught exception: {}",
                                task.getTaskId(), e.getMessage(), e);
                            WorkerReport blocked = WorkerReport.blocked(
                                task.getTaskId(),
                                "Uncaught exception: " + e.getMessage(), 0, 0);
                            try {
                                stateStore.completeTask(task.getTaskId(), blocked);
                            } catch (Exception dbEx) {
                                log.error("Failed to persist BLOCKED report for task {}: {}",
                                    task.getTaskId(), dbEx.getMessage());
                            }
                            completedTaskIds.add(task.getTaskId()); // unblock dependents
                            return blocked;
                        } finally {
                            taskThreads.remove(task.getTaskId());
                        }
                    }))
                    .toList();

                // Collect results (block until this batch completes)
                for (Future<WorkerReport> f : futures) {
                    try {
                        WorkerReport r = f.get();
                        if (r != null) reports.add(r);
                    } catch (ExecutionException e) {
                        // Should not normally reach here since the lambda catches all exceptions,
                        // but guard against any unexpected Future failure.
                        log.error("Worker future failed unexpectedly: {}",
                            e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    }
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return reports;
    }

    private boolean areDependenciesMet(AgentTask task, Set<String> completed) {
        TaskDefinition def = parseTaskDef(task.getTaskJson());
        if (def.getDependsOn() == null || def.getDependsOn().isEmpty()) {
            return true;
        }
        return def.getDependsOn().stream().allMatch(completed::contains);
    }

    private TaskDefinition parseTaskDef(String json) {
        try {
            return mapper.readValue(json, TaskDefinition.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse task definition", e);
        }
    }
}
