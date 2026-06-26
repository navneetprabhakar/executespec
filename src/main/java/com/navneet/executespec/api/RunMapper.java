package com.navneet.executespec.api;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.api.dto.RunSummary;
import com.navneet.executespec.api.dto.TaskSummary;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.RunCodeMap;
import com.navneet.executespec.codeintel.repository.RunCodeMapRepository;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationRunRepository;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RunMapper {

  private final IntegrationRunRepository integrationRunRepository;
  private final ProjectRepository projectRepository;
  private final AppUserRepository appUserRepository;
  private final TestPlanRepository testPlanRepository;
  private final RunCodeMapRepository runCodeMapRepository;
  private final ValidationReportRepository validationReportRepository;
  private final ObjectMapper objectMapper;

  public RunSummary toSummary(PipelineRun r) {
    return toSummary(r, null, null, null);
  }

  public RunSummary toSummary(
      PipelineRun r, Boolean localExecutionRequested, Boolean localExecutionAccepted) {
    return toSummary(r, null, localExecutionRequested, localExecutionAccepted);
  }

  /**
   * RL-2026-06-019 — batch variant for the list endpoint: hydrates each summary's verifiedScore
   * with ONE query across the page instead of a per-row report lookup.
   */
  public List<RunSummary> toSummaries(List<PipelineRun> runs) {
    if (runs.isEmpty()) return List.of();
    List<String> ids = runs.stream().map(PipelineRun::getRunId).toList();
    Map<String, Integer> scores = new HashMap<>();
    for (Object[] row : validationReportRepository.findScoresByRunIds(ids)) {
      if (row[0] != null && row[1] != null) {
        scores.put(String.valueOf(row[0]), ((Number) row[1]).intValue());
      }
    }
    return runs.stream().map(r -> toSummary(r, scores.get(r.getRunId()), null, null)).toList();
  }

  private RunSummary toSummary(
      PipelineRun r,
      Integer verifiedScore,
      Boolean localExecutionRequested,
      Boolean localExecutionAccepted) {
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
        r.getRunSummary(),
        r.getExecutionMode(),
        r.getArchivedAt(),
        r.getApprovedExecutionMode(),
        r.getAiProvider(),
        r.getSourceRunId() != null,
        r.getSourceRunId(),
        r.getHarnessVersion(),
        r.getActualCostUsd(),
        r.getStatusReason(),
        // PR-B (2026-05-13) — hydrate PR URLs in the listing endpoint so the project launches
        // surface can render PR pills without a per-row drill-down. Prefer scm-agnostic
        // scmPrUrl; the legacy githubPrUrl ships alongside for pre-SCM-split back-compat.
        r.getScmPrUrl(),
        r.getGithubPrUrl(),
        verifiedScore,
        localExecutionRequested,
        localExecutionAccepted);
  }

  /** Resolves a project ID to its human-readable name, or null if not found / not set. */
  private String resolveProjectName(String projectId) {
    if (projectId == null || projectId.isBlank()) return null;
    return projectRepository.findById(projectId).map(Project::getName).orElse(null);
  }

  /** Resolves a user ID to "FirstName LastName", or null if not found / not set. */
  private String resolveCreatorName(String userId) {
    if (userId == null || userId.isBlank()) return null;
    return appUserRepository
        .findById(userId)
        .map(
            u ->
                ((u.getFirstName() != null ? u.getFirstName() : "")
                        + " "
                        + (u.getLastName() != null ? u.getLastName() : ""))
                    .trim())
        .filter(name -> !name.isEmpty())
        .orElse(null);
  }

  /**
   * Builds the per-agent (planner/reviewer/compiler/test-gen) token entries for the detail view.
   */
  private List<RunDetail.AgentTokenEntry> buildAgentTokens(PipelineRun r) {
    List<RunDetail.AgentTokenEntry> entries = new java.util.ArrayList<>();
    int pIn = r.getPlannerInputTokens() != null ? r.getPlannerInputTokens() : 0;
    int pOut = r.getPlannerOutputTokens() != null ? r.getPlannerOutputTokens() : 0;
    if (pIn > 0 || pOut > 0) {
      entries.add(
          new RunDetail.AgentTokenEntry(
              "planner", r.getPlannerProvider(), r.getPlannerActualModel(), pIn, pOut));
    }
    int rIn = r.getReviewerInputTokens() != null ? r.getReviewerInputTokens() : 0;
    int rOut = r.getReviewerOutputTokens() != null ? r.getReviewerOutputTokens() : 0;
    if (rIn > 0 || rOut > 0) {
      entries.add(
          new RunDetail.AgentTokenEntry(
              "reviewer", r.getReviewerProvider(), r.getReviewerActualModel(), rIn, rOut));
    }
    int cIn = r.getCompilerInputTokens() != null ? r.getCompilerInputTokens() : 0;
    int cOut = r.getCompilerOutputTokens() != null ? r.getCompilerOutputTokens() : 0;
    if (cIn > 0 || cOut > 0) {
      entries.add(
          new RunDetail.AgentTokenEntry(
              "compiler", r.getCompilerProvider(), r.getCompilerActualModel(), cIn, cOut));
    }

    // Test gen pipeline tokens (from TestPlan entity)
    testPlanRepository
        .findByRunId(r.getRunId())
        .ifPresent(
            tp -> {
              int tpIn = tp.getPlannerInputTokens() != null ? tp.getPlannerInputTokens() : 0;
              int tpOut = tp.getPlannerOutputTokens() != null ? tp.getPlannerOutputTokens() : 0;
              if (tpIn > 0 || tpOut > 0) {
                entries.add(
                    new RunDetail.AgentTokenEntry(
                        "test-planner",
                        tp.getPlannerProvider(),
                        tp.getPlannerModel(),
                        tpIn,
                        tpOut));
              }
              int twIn = tp.getWorkerInputTokens() != null ? tp.getWorkerInputTokens() : 0;
              int twOut = tp.getWorkerOutputTokens() != null ? tp.getWorkerOutputTokens() : 0;
              if (twIn > 0 || twOut > 0) {
                entries.add(
                    new RunDetail.AgentTokenEntry(
                        "test-worker", tp.getWorkerProvider(), tp.getWorkerModel(), twIn, twOut));
              }
              int trIn = tp.getReportInputTokens() != null ? tp.getReportInputTokens() : 0;
              int trOut = tp.getReportOutputTokens() != null ? tp.getReportOutputTokens() : 0;
              if (trIn > 0 || trOut > 0) {
                entries.add(
                    new RunDetail.AgentTokenEntry(
                        "test-report-gen",
                        tp.getPlannerProvider(),
                        tp.getPlannerModel(),
                        trIn,
                        trOut));
              }
            });

    return entries;
  }

  public RunDetail toDetail(PipelineRun r, List<AgentTask> tasks) {
    // Tier 7: populate integration source if this run was triggered by an integration
    RunDetail.IntegrationSource integrationSource =
        integrationRunRepository
            .findByRunId(r.getRunId())
            .map(
                ir ->
                    new RunDetail.IntegrationSource(
                        ir.getSourceType(), ir.getSourceId(), ir.getSourceUrl()))
            .orElse(null);

    // Codemap stats — single lookup, four nullable fields out
    RunCodeMap codemap = runCodeMapRepository.findByRunId(r.getRunId()).orElse(null);

    // Validation report scoring snapshot — mirrored into RunDetail so UI doesn't
    // need a second request for the headline verifiedScore / accuracyScore.
    ValidationReport report = validationReportRepository.findByRunId(r.getRunId()).orElse(null);

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
        .statusReason(r.getStatusReason())
        .planJson(r.getPlanJson())
        .reviewJson(r.getReviewJson())
        .runGoalDoneness(parseJson(r.getRunGoalDonenessJson()))
        .tasks(
            tasks.stream()
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
        .costEstimateLow(r.getCostEstimateLow())
        .costEstimateHigh(r.getCostEstimateHigh())
        .creditsCharged(r.getCreditsCharged())
        .hardCapUsd(r.getHardCapUsd())
        // v2.6.2-track — hierarchical-planner phase breakdown.
        .planStrategy(r.getPlanStrategy())
        .classifierReasoning(r.getClassifierReasoning())
        .classifierCostUsd(r.getClassifierCostUsd())
        .classifierInputTokens(r.getClassifierInputTokens())
        .classifierOutputTokens(r.getClassifierOutputTokens())
        .outlineCostUsd(r.getOutlineCostUsd())
        .outlineInputTokens(r.getOutlineInputTokens())
        .outlineOutputTokens(r.getOutlineOutputTokens())
        .expansionCostUsd(r.getExpansionCostUsd())
        .expansionInputTokens(r.getExpansionInputTokens())
        .expansionOutputTokens(r.getExpansionOutputTokens())
        .coherenceCostUsd(r.getCoherenceCostUsd())
        .coherenceInputTokens(r.getCoherenceInputTokens())
        .coherenceOutputTokens(r.getCoherenceOutputTokens())
        .techStack(r.getTechStack())
        // V169-companion (2026-05-02) — projectId on the wire so V3 "Open run"
        // can route to /projects/{id}/runs/{runId}. Was missing → frontend
        // dropped to the /runs/{id} fallback → v2 dispatcher redirected to a
        // default project (wrong project entirely).
        .projectId(r.getProjectId())
        .projectName(resolveProjectName(r.getProjectId()))
        .createdByName(resolveCreatorName(r.getCreatedByUserId()))
        .agentTokens(buildAgentTokens(r))
        .applySuggestionCount(r.getApplySuggestionCount())
        .runTitle(r.getRunTitle())
        .runSummary(r.getRunSummary())
        .executionMode(r.getExecutionMode())
        .githubPrUrl(r.getGithubPrUrl())
        .scmPrUrl(r.getScmPrUrl())
        .scmProvider(r.getScmProvider())
        .assessmentResult(r.getAssessmentResult())
        .figmaUrl(r.getFigmaUrl())
        .codemapFileCount(codemap != null ? codemap.getFileCount() : null)
        .codemapRouteCount(codemap != null ? codemap.getRouteCount() : null)
        .codemapInputTokens(codemap != null ? codemap.getInputTokens() : null)
        .codemapOutputTokens(codemap != null ? codemap.getOutputTokens() : null)
        .archivedAt(r.getArchivedAt())
        .approvedExecutionMode(r.getApprovedExecutionMode())
        .batchCredits(r.getBatchCredits())
        .batchAvailable(r.getBatchAvailable())
        .creditSavings(r.getCreditSavings())
        .batchProgressCompleted(r.getBatchProgressCompleted())
        .batchProgressTotal(r.getBatchProgressTotal())
        .aiProvider(r.getAiProvider())
        .harnessVersion(r.getHarnessVersion())
        .runMode(r.getRunMode())
        .triggeredByFinding(r.getTriggeredByFinding())
        .isRetry(r.getSourceRunId() != null)
        .sourceRunId(r.getSourceRunId())
        .runRetryCount(r.getRunRetryCount())
        // Validation status/phase tracking retired with V1 BackgroundValidationService.
        // V2 runs expose validation state through the validation report itself.
        .validationStatus(null)
        .validationPhase(null)
        .verifiedScore(report != null ? report.getVerifiedScore() : null)
        .accuracyScore(report != null ? report.getAccuracyScore() : null)
        .reviewerVerdict(report != null ? report.getReviewerVerdict() : null)
        .finalBuildPassed(report != null ? report.getFinalBuildPassed() : null)
        .testsPassed(report != null ? report.getTestsPassed() : null)
        .testsTotal(report != null ? report.getTestsTotal() : null)
        .build();
  }

  /**
   * Sort order for tasks in the detail view: WORKING first (in-flight), then PENDING/LOCKED
   * (queued), then PARTIAL/BLOCKED (stalled), then FAILED, and finally COMPLETE at the bottom.
   */
  private static int taskStatusOrder(String status) {
    return switch (status != null ? status : "") {
      case "WORKING" -> 0;
      case "PENDING", "LOCKED" -> 1;
      case "PARTIAL", "BLOCKED" -> 2;
      case "FAILED" -> 3;
      case "COMPLETE" -> 4;
      default -> 5;
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
        parseJson(t.getGoalDonenessJson()),
        parseJson(t.getContinuationDecisionJson()),
        extractObjective(t.getTaskJson()),
        t.getCompletionNotes(),
        t.getParentTaskId(),
        t.getSubtaskIndex(),
        t.getSubtaskTotal());
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

  private JsonNode parseJson(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Pulls a human-readable failure reason from the task's stored report JSON (WorkerReport#blockers
   * for BLOCKED, WorkerReport#selfReview for PARTIAL) or falls back to the lastLlmFailure flag set
   * by the recovery system.
   *
   * <p>Note: {@code self_review} is intentionally skipped for COMPLETE tasks — parent tasks
   * completed from subtask decomposition store "Decomposed into N subtasks: N complete" in {@code
   * self_review}, which is a success note, not a failure reason. That info is already surfaced via
   * {@code completionNotes} in the TaskSummary.
   */
  private String extractFailureReason(AgentTask t) {
    boolean isComplete = "COMPLETE".equals(t.getStatus());
    if (t.getWorkerReportJson() != null && !t.getWorkerReportJson().isBlank()) {
      try {
        JsonNode report = objectMapper.readTree(t.getWorkerReportJson());
        // BLOCKED tasks: "blockers" field
        String blockers = report.path("blockers").asText("");
        if (!blockers.isBlank()) return blockers;
        // PARTIAL tasks: "self_review" (snake_case from WorkerReport).
        // Skip for COMPLETE tasks — self_review on COMPLETE tasks contains subtask
        // decomposition summaries ("Decomposed into N subtasks: N complete"), which
        // are success notes, not failures. These are already in completionNotes.
        if (!isComplete) {
          String selfReview = report.path("self_review").asText("");
          if (!selfReview.isBlank()) return selfReview;
        }
      } catch (Exception ignored) {
      }
    }
    // Fallback: lastLlmFailure (set by blockTask / recovery)
    String last = t.getLastLlmFailure();
    if (last != null && !last.isBlank()) return last;
    // Final safety net: never return an empty string for FAILED tasks.
    // Empty string is worse than null because the UI's null-check may treat
    // it as "set" and skip the placeholder. Eval Bug D (2026-04-16): S2
    // Anthropic T3 surfaced as `failureReason: ""` and the harness retried
    // silently. Anchor to "unknown" so the UI always shows something.
    if ("FAILED".equals(t.getStatus())) {
      return "unknown — see worker logs for run " + t.getRunId();
    }
    return null;
  }
}
