package com.navneet.agentteam.api;

import com.navneet.agentteam.api.dto.RunDetail;
import com.navneet.agentteam.api.dto.RunSummary;
import com.navneet.agentteam.api.dto.TaskSummary;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.state.repository.IntegrationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RunMapper {

    private final IntegrationRunRepository integrationRunRepository;

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
            r.getRepoMode()
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
            .tasks(tasks.stream().map(this::toTaskSummary).toList())
            .integrationSource(integrationSource)
            // Tier 8: compile / API spec / preview
            .hasFrontend(r.isHasFrontend())
            .hasApiSpec(r.isHasApiSpec())
            .previewStatus(r.getPreviewStatus())
            .build();
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
            t.getCompletedAt()
        );
    }
}
