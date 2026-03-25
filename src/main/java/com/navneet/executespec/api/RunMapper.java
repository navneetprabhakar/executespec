package com.navneet.executespec.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.api.dto.TaskSummary;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationRunRepository;
import com.navneet.executespec.testgen.model.TestPlan;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RunMapper {

    private final IntegrationRunRepository integrationRunRepository;
    private final ProjectRepository projectRepository;
    private final AppUserRepository appUserRepository;
    private final TestPlanRepository testPlanRepository;
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
            r.getKeySource(),
            r.getTechStack(),
            resolveProjectName(r.getProjectId()),
            resolveCreatorName(r.getCreatedByUserId()),
            r.getRunTitle(),
            r.getRunSummary()
        );
    }

    /** Resolves a project ID to its human-readable name, or null if not found / not set. */
    private String resolveProjectName(String projectId) {
        if (projectId == null || projectId.isBlank()) return null;
        return projectRepository.findById(projectId).map(Project::getName).orElse(null);
    }

    /** Resolves a user ID to "FirstName LastName", or null if not found / not set. */
    private String resolveCreatorName(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return appUserRepository.findById(userId)
                .map(u -> ((u.getFirstName() != null ? u.getFirstName() : "") + " "
                         + (u.getLastName() != null ? u.getLastName() : "")).trim())
                .filter(name -> !name.isEmpty())
                .orElse(null);
    }

    /** Builds the per-agent (planner/reviewer/compiler/test-gen) token entries for the detail view. */
    private List<RunDetail.AgentTokenEntry> buildAgentTokens(PipelineRun r) {
        List<RunDetail.AgentTokenEntry> entries = new java.util.ArrayList<>();
        int pIn  = r.getPlannerInputTokens()  != null ? r.getPlannerInputTokens()  : 0;
        int pOut = r.getPlannerOutputTokens() != null ? r.getPlannerOutputTokens() : 0;
        if (pIn > 0 || pOut > 0) {
            entries.add(new RunDetail.AgentTokenEntry("planner",
                    r.getPlannerProvider(), r.getPlannerActualModel(), pIn, pOut));
        }
        int rIn  = r.getReviewerInputTokens()  != null ? r.getReviewerInputTokens()  : 0;
        int rOut = r.getReviewerOutputTokens() != null ? r.getReviewerOutputTokens() : 0;
        if (rIn > 0 || rOut > 0) {
            entries.add(new RunDetail.AgentTokenEntry("reviewer",
                    r.getReviewerProvider(), r.getReviewerActualModel(), rIn, rOut));
        }
        int cIn  = r.getCompilerInputTokens()  != null ? r.getCompilerInputTokens()  : 0;
        int cOut = r.getCompilerOutputTokens() != null ? r.getCompilerOutputTokens() : 0;
        if (cIn > 0 || cOut > 0) {
            entries.add(new RunDetail.AgentTokenEntry("compiler",
                    r.getCompilerProvider(), r.getCompilerActualModel(), cIn, cOut));
        }

        // Test gen pipeline tokens (from TestPlan entity)
        testPlanRepository.findByRunId(r.getRunId()).ifPresent(tp -> {
            int tpIn  = tp.getPlannerInputTokens()  != null ? tp.getPlannerInputTokens()  : 0;
            int tpOut = tp.getPlannerOutputTokens() != null ? tp.getPlannerOutputTokens() : 0;
            if (tpIn > 0 || tpOut > 0) {
                entries.add(new RunDetail.AgentTokenEntry("test-planner",
                        tp.getPlannerProvider(), tp.getPlannerModel(), tpIn, tpOut));
            }
            int twIn  = tp.getWorkerInputTokens()  != null ? tp.getWorkerInputTokens()  : 0;
            int twOut = tp.getWorkerOutputTokens() != null ? tp.getWorkerOutputTokens() : 0;
            if (twIn > 0 || twOut > 0) {
                entries.add(new RunDetail.AgentTokenEntry("test-worker",
                        tp.getWorkerProvider(), tp.getWorkerModel(), twIn, twOut));
            }
            int trIn  = tp.getReportInputTokens()  != null ? tp.getReportInputTokens()  : 0;
            int trOut = tp.getReportOutputTokens() != null ? tp.getReportOutputTokens() : 0;
            if (trIn > 0 || trOut > 0) {
                entries.add(new RunDetail.AgentTokenEntry("test-report-gen",
                        tp.getPlannerProvider(), tp.getPlannerModel(), trIn, trOut));
            }
        });

        return entries;
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
            .actualCostUsd(r.getActualCostUsd())
            .techStack(r.getTechStack())
            .projectName(resolveProjectName(r.getProjectId()))
            .createdByName(resolveCreatorName(r.getCreatedByUserId()))
            .agentTokens(buildAgentTokens(r))
            .applySuggestionCount(r.getApplySuggestionCount())
            .runTitle(r.getRunTitle())
            .runSummary(r.getRunSummary())
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
            t.getProvider(),
            t.getModel(),
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
