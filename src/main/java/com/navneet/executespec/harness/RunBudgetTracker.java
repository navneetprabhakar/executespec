package com.navneet.executespec.harness;

import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.agent.model.TaskDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks cumulative LLM calls per run and enforces a budget ceiling.
 *
 * <p>The budget is derived from the plan's task count and complexity:
 * each task gets an estimated call count based on complexity, and the
 * ceiling is set at 3× the total estimate. This prevents runaway costs
 * from infinite retry loops or stalled workers.
 *
 * <p>Thread-safe — multiple workers can increment concurrently.
 */
@Component
@Slf4j
public class RunBudgetTracker {

    /** Per-complexity estimated LLM calls per task. */
    private static final int CALLS_LOW    = 5;
    private static final int CALLS_MEDIUM = 12;
    private static final int CALLS_HIGH   = 20;
    /** Budget multiplier — actual calls allowed = estimate × MULTIPLIER. */
    private static final int MULTIPLIER   = 3;

    private final ConcurrentHashMap<String, AtomicInteger> actualCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> budgetCeilings = new ConcurrentHashMap<>();

    /**
     * Initializes the budget for a run from its task plan.
     * Must be called after planning, before task execution starts.
     */
    public void initBudget(String runId, TaskPlan plan) {
        if (plan == null || plan.getTasks() == null) return;

        int estimatedTotal = 0;
        for (TaskDefinition task : plan.getTasks()) {
            String complexity = task.getComplexity() != null ? task.getComplexity().toUpperCase() : "MEDIUM";
            estimatedTotal += switch (complexity) {
                case "LOW"  -> CALLS_LOW;
                case "HIGH" -> CALLS_HIGH;
                default     -> CALLS_MEDIUM;
            };
        }

        int ceiling = estimatedTotal * MULTIPLIER;
        budgetCeilings.put(runId, ceiling);
        actualCalls.put(runId, new AtomicInteger(0));

        log.info("[RunBudget] Run {} — {} tasks, estimated {} calls, ceiling {} ({}×)",
            runId, plan.getTasks().size(), estimatedTotal, ceiling, MULTIPLIER);
    }

    /**
     * Records an LLM call for a run.
     * @return the new cumulative call count
     */
    public int recordCall(String runId) {
        AtomicInteger counter = actualCalls.get(runId);
        if (counter == null) return 0;
        return counter.incrementAndGet();
    }

    /**
     * Returns true if the run has exceeded its budget ceiling.
     * Returns false if no budget was initialized (backward compatibility).
     */
    public boolean isOverBudget(String runId) {
        AtomicInteger counter = actualCalls.get(runId);
        Integer ceiling = budgetCeilings.get(runId);
        if (counter == null || ceiling == null) return false;
        return counter.get() > ceiling;
    }

    /**
     * Returns the current call count for a run, or 0 if not tracked.
     */
    public int getCurrentCalls(String runId) {
        AtomicInteger counter = actualCalls.get(runId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Returns the budget ceiling for a run, or -1 if not initialized.
     */
    public int getCeiling(String runId) {
        return budgetCeilings.getOrDefault(runId, -1);
    }

    /**
     * Cleans up tracking state for a completed run (prevents memory leak).
     */
    public void remove(String runId) {
        actualCalls.remove(runId);
        budgetCeilings.remove(runId);
    }
}
