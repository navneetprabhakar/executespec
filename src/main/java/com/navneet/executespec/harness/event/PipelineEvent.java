package com.navneet.executespec.harness.event;

import com.navneet.executespec.agent.model.ReviewReport;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.state.entity.PipelineRun;

import java.util.List;

/**
 * Sealed hierarchy of pipeline lifecycle events published via Spring's
 * {@link org.springframework.context.ApplicationEventPublisher}.
 *
 * <p>Listeners use {@code @EventListener} (optionally with {@code @Async})
 * to react to specific transitions without being directly wired into
 * {@link com.navneet.executespec.harness.PipelineHarness}.
 */
public sealed interface PipelineEvent {

    String runId();

    /** Run has entered PLANNING state — planner is producing a task plan. */
    record RunStarted(String runId) implements PipelineEvent {}

    /**
     * A successful merge to main branch has just completed.
     * Post-merge agents (context, optimiser, API test, preview) react to this.
     */
    record MergeCompleted(
            String runId,
            PipelineRun completedRun,
            List<WorkerReport> reports,
            ReviewReport review,
            boolean hadFailures
    ) implements PipelineEvent {}

    /** Run finished successfully (status = COMPLETE). */
    record RunCompleted(String runId) implements PipelineEvent {}

    /** Run failed (status = FAILED). */
    record RunFailed(String runId, String reason) implements PipelineEvent {}

    /**
     * Recovery service requests a fresh restart of a run.
     * PipelineHarness handles this event by calling {@code submitRun}.
     */
    record RestartRun(
            String originalRunId,
            String userRequest,
            String sourceType,
            String repoUrl,
            String repoMode,
            int autoRestartCount
    ) implements PipelineEvent {
        @Override
        public String runId() { return originalRunId; }
    }

    /** A task has been detected as stuck by the stuck-run monitor. */
    record TaskStuck(String runId, String taskId, String classification) implements PipelineEvent {}
}
