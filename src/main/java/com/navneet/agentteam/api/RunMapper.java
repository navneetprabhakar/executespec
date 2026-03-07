package com.navneet.agentteam.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.api.dto.RunDetail;
import com.navneet.agentteam.api.dto.RunSummary;
import com.navneet.agentteam.api.dto.TaskSummary;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.state.repository.IntegrationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RunMapper {

    private final IntegrationRunRepository integrationRunRepository;
    private final ObjectMapper objectMapper;

    public RunSummary toSummary(PipelineRun r) {
        return new RunSummary(
            r.getRunId(),
            r.getStatus(),
            r.getUserRequest(),
            r.getCurrentGate(),
            r.getQueuePosition(),
            r.getPlannerModel(),
            r.getWorkerModel(),
            r.getReviewerModel(),
            r.getTotalInputTokens(),
            r.getTotalOutputTokens(),
            r.getStartedAt(),
            r.getCompletedAt(),
            r.getRepoUrl(),
            r.getRepoMode(),
            r.getProjectId(),
            r.getKeySource()
        );
    }

    public RunDetail toDetail(PipelineRun r, List<AgentTask> tasks) {
        // Tier 7: populate integration source if this run was triggered by an integration
        RunDetail.IntegrationSource integrationSource = integrationRunRepository
                .findByRunId(r.getRunId())
                .map(ir -> new RunDetail.IntegrationSource(ir.getSourceType(), ir.getSourceId(), ir.getSourceUrl()))
                .orElse(null);

        return RunDetail.builder()
            .runId(r.getRunId())
            .status(r.getStatus())
            .userRequest(r.getUserRequest())
            .currentGate(r.getCurrentGate())
            .queuePosition(r.getQueuePosition())
            .plannerModel(r.getPlannerModel())
            .workerModel(r.getWorkerModel())
            .reviewerModel(r.getReviewerModel())
            .totalInputTokens(r.getTotalInputTokens())
            .totalOutputTokens(r.getTotalOutputTokens())
            .startedAt(r.getStartedAt())
            .completedAt(r.getCompletedAt())
            .repoUrl(r.getRepoUrl())
            .repoMode(r.getRepoMode())
            .failureReason(r.getFailureReason())
            .planJson(r.getPlanJson())
            .reviewJson(r.getReviewJson())
            .tasks(tasks.stream()
                .sorted(Comparator.comparingInt((AgentTask t) -> taskStatusOrder(t.getStatus())))
                .map(this::toTaskSummary)
                .toList())
            .integrationSource(integrationSource)
            // Tier 8: compile / API spec / preview
            .hasFrontend(r.isHasFrontend())
            .hasApiSpec(r.isHasApiSpec())
            .previewStatus(r.getPreviewStatus())
            .keySource(r.getKeySource())
            .build();
    }

    /**
     * Sort order for tasks in the detail view:
     * WORKING first (in-flight), then PENDING/LOCKED (queued), then
     * PARTIAL/BLOCKED (stalled), then FAILED, and finally COMPLETE at the bottom.
     */
    private static int taskStatusOrder(String status) {
        return switch (status != null ? status : "") {
            case "WORKING"          -> 0;
            case "PENDING", "LOCKED"-> 1;
            case "PARTIAL", "BLOCKED"-> 2;
            case "FAILED"           -> 3;
            case "COMPLETE"         -> 4;
            default                 -> 5;
        };
    }

    public TaskSummary toTaskSummary(AgentTask t) {
        return new TaskSummary(
            t.getTaskId(),
            t.getTitle(),
            t.getComplexity(),
            t.getStatus(),
            t.getAssignedModel(),
            t.getBranchName(),
            t.getReadmeUpdated(),
            t.getInputTokens() != null ? t.getInputTokens().longValue() : null,
            t.getOutputTokens() != null ? t.getOutputTokens().longValue() : null,
            t.getDurationMs(),
            t.getStartedAt(),
            t.getCompletedAt(),
            extractFailureReason(t),
            extractObjective(t.getTaskJson())
        );
    }

    /** Parses the objective field from the planner's TaskDefinition JSON stored in the task. */
    private String extractObjective(String taskJson) {
        if (taskJson == null || taskJson.isBlank()) return null;
        try {
            return objectMapper.readTree(taskJson).path("objective").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pulls a human-readable failure reason from the task's stored report JSON
     * (WorkerReport#blockers for BLOCKED, WorkerReport#selfReview for PARTIAL)
     * or falls back to the lastLlmFailure flag set by the recovery system.
     */
    private String extractFailureReason(AgentTask t) {
        if (t.getWorkerReportJson() != null && !t.getWorkerReportJson().isBlank()) {
            try {
                JsonNode report = objectMapper.readTree(t.getWorkerReportJson());
                // BLOCKED tasks: "blockers" field
                String blockers = report.path("blockers").asText("");
                if (!blockers.isBlank()) return blockers;
                // PARTIAL tasks: "self_review" (snake_case from WorkerReport)
                String selfReview = report.path("self_review").asText("");
                if (!selfReview.isBlank()) return selfReview;
            } catch (Exception ignored) {}
        }
        // Fallback: lastLlmFailure (set by blockTask / recovery)
        String last = t.getLastLlmFailure();
        return (last != null && !last.isBlank()) ? last : null;
    }
}
