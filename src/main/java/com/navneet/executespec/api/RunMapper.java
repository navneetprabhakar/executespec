package com.navneet.executespec.api;

import java.util.Comparator;
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
import com.navneet.executespec.billing.repository.ManagedAiUsageEventRepository;
import com.navneet.executespec.codeintel.entity.RunCodeMap;
import com.navneet.executespec.codeintel.repository.RunCodeMapRepository;
import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.PlannerFailureDiagnostic;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.ValidationReportScoreProjection;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.IntegrationRunRepository;
import com.navneet.executespec.testgen.repository.TestPlanRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class RunMapper {

  private static final int UNPROVEN_COMPLETION_SCORE_CAP = 84;

  private final IntegrationRunRepository integrationRunRepository;
  private final ProjectRepository projectRepository;
  private final AppUserRepository appUserRepository;
  private final TestPlanRepository testPlanRepository;
  private final RunCodeMapRepository runCodeMapRepository;
  private final ValidationReportRepository validationReportRepository;
  private final ManagedAiUsageEventRepository managedAiUsageEventRepository;
  private final RunEffectiveRouteService runEffectiveRouteService;
  private final AgentTaskRepository agentTaskRepository;
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
    Map<String, Integer> scores =
        ValidationReportScoreProjection.effectiveScores(
            validationReportRepository.findScoresByRunIds(ids), objectMapper);
    Map<String, List<AgentTask>> tasksByRun =
        agentTaskRepository.findByRunIdIn(ids).stream()
            .collect(java.util.stream.Collectors.groupingBy(AgentTask::getRunId));
    return runs.stream()
        .map(
            r ->
                toSummary(
                    r,
                    scores.get(r.getRunId()),
                    null,
                    null,
                    tasksByRun.getOrDefault(r.getRunId(), List.of())))
        .toList();
  }

  private RunSummary toSummary(
      PipelineRun r,
      Integer verifiedScore,
      Boolean localExecutionRequested,
      Boolean localExecutionAccepted) {
    return toSummary(r, verifiedScore, localExecutionRequested, localExecutionAccepted, List.of());
  }

  private RunSummary toSummary(
      PipelineRun r,
      Integer verifiedScore,
      Boolean localExecutionRequested,
      Boolean localExecutionAccepted,
      List<AgentTask> tasks) {
    String displayStatus = displayRunStatus(r.getStatus(), tasks);
    return new RunSummary(
        r.getRunId(),
        displayStatus,
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
        displayRunStatusReason(
            r.getStatusReason(), r.getFailureReason(), r.getStatus(), displayStatus),
        // PR-B (2026-05-13) — hydrate PR URLs in the listing endpoint so the project launches
        // surface can render PR pills without a per-row drill-down. Prefer scm-agnostic
        // scmPrUrl; the legacy githubPrUrl ships alongside for pre-SCM-split back-compat.
        r.getScmPrUrl(),
        r.getGithubPrUrl(),
        displayVerifiedScore(verifiedScore, displayStatus),
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
        .findNameById(userId)
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

    // C1 (G4) — DERIVED effective route. Read from the physical-attempt ledger rather than stored,
    // so it can never drift from the usage facts it summarises.
    EffectiveRouteProjection effectiveRoute = runEffectiveRouteService.forRun(r.getRunId());
    String displayStatus = displayRunStatus(r.getStatus(), tasks);

    return RunDetail.builder()
        .runId(r.getRunId())
        .status(displayStatus)
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
        .failureReason(displayRunFailureReason(r.getFailureReason(), r.getStatus(), displayStatus))
        .statusReason(
            displayRunStatusReason(
                r.getStatusReason(), r.getFailureReason(), r.getStatus(), displayStatus))
        .planJson(r.getPlanJson())
        .reviewJson(r.getReviewJson())
        .runGoalDoneness(parseJson(r.getRunGoalDonenessJson(), "runGoalDoneness", r.getRunId()))
        .tasks(
            tasks.stream()
                // RR-1446 — order by the persisted planner-intent index first so the approval gate
                // shows the planner's sequence; status is a decoration-only tiebreaker (and the
                // sole ordering for legacy rows that predate plan_index, which sort last).
                .sorted(TASK_DISPLAY_ORDER)
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
        .aiCreditsCharged(managedAiUsageEventRepository.sumAiCreditsChargedByRunId(r.getRunId()))
        .userCostCapUsd(r.getUserCostCap())
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
        .requestedProvider(r.getRequestedProvider())
        .requestedModel(r.getRequestedModel())
        .routingMode(r.getRoutingMode())
        .routePinned(r.isRoutePinned())
        .effectiveProvider(effectiveRoute.provider())
        .effectiveModel(effectiveRoute.model())
        .effectiveRouteAttribution(effectiveRoute.attribution().name())
        .routeDiverged(
            effectiveRoute.divergesFromRequestedRoute(
                r.getRequestedProvider(), r.getRequestedModel()))
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
        .verifiedScore(
            displayVerifiedScore(
                ValidationReportScoreProjection.effectiveVerifiedScore(report, objectMapper),
                displayStatus))
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
  /**
   * RR-1446 — task display order for the run detail view and the approval gate. The persisted
   * planner-intent index is the primary key so the human sees the planner's sequence; status is a
   * decoration-only tiebreaker, and it is the sole ordering for legacy rows that predate plan_index
   * (they sort after every indexed task).
   */
  static final Comparator<AgentTask> TASK_DISPLAY_ORDER =
      Comparator.comparingInt((AgentTask t) -> planOrder(t.getPlanIndex()))
          .thenComparingInt(t -> TaskPresentation.statusOrder(t.getStatus()));

  private static int planOrder(Integer planIndex) {
    return planIndex == null ? Integer.MAX_VALUE : planIndex;
  }

  public TaskSummary toTaskSummary(AgentTask t) {
    JsonNode goalDoneness = parseJson(t.getGoalDonenessJson(), "goalDoneness", t.getTaskId());
    String displayStatus = displayTaskStatus(t.getStatus(), goalDoneness);
    String failureReason = extractFailureReason(t);
    if (failureReason == null && "PARTIAL".equals(displayStatus) && goalDoneness != null) {
      failureReason = goalDoneness.path("reason").asText(null);
    }
    return new TaskSummary(
        t.getTaskId(),
        t.getTitle(),
        t.getComplexity(),
        displayStatus,
        t.getAssignedModel(),
        t.getProvider(),
        t.getModel(),
        t.getEffectiveProvider(),
        t.getEffectiveModel(),
        t.getEffectiveRouteAttribution(),
        TaskPresentation.routeDiverged(t),
        t.getRoutingTier(),
        t.getRoutingTaskCategory(),
        t.getRoutingSource(),
        t.getBranchName(),
        t.getReadmeUpdated(),
        t.getInputTokens() != null ? t.getInputTokens().longValue() : null,
        t.getOutputTokens() != null ? t.getOutputTokens().longValue() : null,
        t.getDurationMs(),
        t.getStartedAt(),
        t.getCompletedAt(),
        failureReason,
        goalDoneness,
        parseJson(t.getContinuationDecisionJson(), "continuationDecision", t.getTaskId()),
        parseJson(t.getContinuationActionsJson(), "continuationActions", t.getTaskId()),
        extractObjective(t.getTaskJson()),
        t.getCompletionNotes(),
        t.getParentTaskId(),
        t.getSubtaskIndex(),
        t.getSubtaskTotal());
  }

  /**
   * A persisted COMPLETE row means the worker stopped at this task boundary; it does not override
   * the task's later evidence-backed goal decision. Keep the persistence status unchanged for
   * crash-resume bookkeeping, but fail closed in the customer-facing DTO whenever goal proof is
   * present and not DONE. Legacy COMPLETE rows without goal proof remain backward compatible.
   */
  static String displayTaskStatus(String persistedStatus, JsonNode goalDoneness) {
    if (!"COMPLETE".equals(persistedStatus) || goalDoneness == null) {
      return persistedStatus;
    }
    String goalState = goalDoneness.path("state").asText("");
    return goalState.isBlank()
            || "DONE".equals(goalState)
            || downstreamValidationDeferred(goalDoneness)
        ? persistedStatus
        : "PARTIAL";
  }

  String displayRunStatus(String persistedStatus, List<AgentTask> tasks) {
    if (!"COMPLETE".equals(persistedStatus) || tasks == null || tasks.isEmpty()) {
      return persistedStatus;
    }
    boolean allTasksProvenComplete = tasks.stream().allMatch(this::isTaskProvenCompleteForDisplay);
    return allTasksProvenComplete ? persistedStatus : "PARTIAL";
  }

  static Integer displayVerifiedScore(Integer persistedScore, String displayStatus) {
    if (persistedScore == null || "COMPLETE".equals(displayStatus)) return persistedScore;
    return Math.min(persistedScore, UNPROVEN_COMPLETION_SCORE_CAP);
  }

  private boolean isTaskProvenCompleteForDisplay(AgentTask task) {
    if (!"COMPLETE".equals(task.getStatus())) return false;
    String json = task.getGoalDonenessJson();
    if (json == null || json.isBlank()) return true;
    try {
      JsonNode proof = objectMapper.readTree(json);
      String state = proof.path("state").asText("");
      return state.isBlank() || "DONE".equals(state) || downstreamValidationDeferred(proof);
    } catch (Exception error) {
      log.warn(
          "Malformed goal proof cannot certify run completion: taskId={} ({})",
          task.getTaskId(),
          error.getMessage());
      return false;
    }
  }

  private static boolean downstreamValidationDeferred(JsonNode goalDoneness) {
    JsonNode evidence = goalDoneness == null ? null : goalDoneness.path("evidence");
    if (evidence == null || !evidence.isArray()) return false;
    for (JsonNode item : evidence) {
      if ("downstreamValidationDeferred=true".equals(item.asText())) return true;
    }
    return false;
  }

  static String displayRunStatusReason(
      String persistedReason,
      String persistedFailure,
      String persistedStatus,
      String displayStatus) {
    return "COMPLETE".equals(persistedStatus) && "PARTIAL".equals(displayStatus)
        ? "TASK_PROOF_INCOMPLETE"
        : PlannerFailureDiagnostic.projectStatusReason(persistedReason, persistedFailure);
  }

  private static String displayRunFailureReason(
      String persistedFailure, String persistedStatus, String displayStatus) {
    if (persistedFailure != null && !persistedFailure.isBlank()) return persistedFailure;
    return "COMPLETE".equals(persistedStatus) && "PARTIAL".equals(displayStatus)
        ? "one or more tasks lack durable completion proof"
        : persistedFailure;
  }

  /** Parses the objective field from the planner's TaskDefinition JSON stored in the task. */
  private String extractObjective(String taskJson) {
    if (taskJson == null || taskJson.isBlank()) return null;
    try {
      return objectMapper.readTree(taskJson).path("objective").asText(null);
    } catch (Exception e) {
      // RR-1124 — a non-blank but malformed taskJson is corruption/schema drift, not "no
      // objective". Emit a repair signal instead of swallowing it.
      log.warn(
          "RR-1124 malformed persisted taskJson while extracting objective: {}", e.getMessage());
      return null;
    }
  }

  private JsonNode parseJson(String json) {
    return parseJson(json, "unknown", "unknown");
  }

  /**
   * RR-1124 — this used to catch every parse failure and return null with no log or metric, so a
   * corrupted/schema-drifted proof field looked identical to "never measured" and the UI silently
   * omitted mandatory proof and controls with no repair signal. The DTO contract is unchanged
   * (still null on failure), but a non-blank field that fails to parse now logs a warning carrying
   * the owning entity id and field so corruption is visible and diagnosable.
   */
  private JsonNode parseJson(String json, String field, String entityId) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readTree(json);
    } catch (Exception e) {
      log.warn(
          "RR-1124 malformed persisted JSON: field={} id={} — proof rendered absent ({})",
          field,
          entityId,
          e.getMessage());
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
      } catch (Exception e) {
        // RR-1124 — a non-blank but malformed worker report is corruption, not "no failure
        // reason". Log a repair signal; the lastLlmFailure fallback below still applies.
        log.warn(
            "RR-1124 malformed persisted workerReportJson: taskId={} — failure reason from report"
                + " unavailable ({})",
            t.getTaskId(),
            e.getMessage());
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
