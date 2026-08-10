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
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.RunPipelineSupport;
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

  private final IntegrationRunRepository integrationRunRepository;
  private final ProjectRepository projectRepository;
  private final AppUserRepository appUserRepository;
  private final TestPlanRepository testPlanRepository;
  private final RunCodeMapRepository runCodeMapRepository;
  private final ValidationReportRepository validationReportRepository;
  private final ManagedAiUsageEventRepository managedAiUsageEventRepository;
  private final RunEffectiveRouteService runEffectiveRouteService;
  private final AgentTaskRepository agentTaskRepository;
  private final RunCostTracker runCostTracker;
  private final ObjectMapper objectMapper;
  private final com.navneet.executespec.security.WorkspacePathRedaction workspacePathRedaction;

  /**
   * Durable V300 failed-run quota compensation ledger. Nullable-tolerant so bare test constructions
   * without the bean keep working; a null repository leaves refund state unresolved (never falsely
   * "not refunded").
   */
  private final com.navneet.executespec.billing.repository.RunQuotaRefundRepository
      runQuotaRefundRepository;

  private final WorkspaceGenerationEvidenceProjection workspaceGenerationEvidenceProjection;

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
    return toSummary(
        r,
        verifiedScore,
        localExecutionRequested,
        localExecutionAccepted,
        tasksForSingleRunProjection(r));
  }

  /**
   * Task-proof fail-closed coverage for SINGLE-run summary responses (submit/fork ACK, project
   * reassignment). The batch list path already hydrates tasks per page; the single-run path used to
   * pass an empty task list, so a persisted COMPLETE row with durable non-DONE goal evidence was
   * projected as COMPLETE by these responses while the list and detail said PARTIAL. Only
   * persisted-COMPLETE runs need the lookup — every other status is projected unchanged.
   */
  private List<AgentTask> tasksForSingleRunProjection(PipelineRun r) {
    if (agentTaskRepository == null
        || r == null
        || r.getRunId() == null
        || !"COMPLETE".equals(r.getStatus())) {
      return List.of();
    }
    try {
      List<AgentTask> tasks = agentTaskRepository.findByRunId(r.getRunId());
      return tasks == null ? List.of() : tasks;
    } catch (RuntimeException ex) {
      log.warn(
          "task-proof lookup failed for run {} — projecting without task evidence: {}",
          r.getRunId(),
          ex.getMessage());
      return List.of();
    }
  }

  private RunSummary toSummary(
      PipelineRun r,
      Integer verifiedScore,
      Boolean localExecutionRequested,
      Boolean localExecutionAccepted,
      List<AgentTask> tasks) {
    String displayStatus = displayRunStatus(r.getStatus(), tasks);
    Double actualCostUsd = actualCostForRead(r);
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
        actualCostUsd,
        RunDisplayPolicy.displayRunStatusReason(
            r.getStatusReason(), r.getFailureReason(), r.getStatus(), displayStatus),
        com.navneet.executespec.run.outcome.RunTerminalOutcomeContract.project(
            r.getRunId(),
            displayStatus,
            RunDisplayPolicy.displayRunStatusReason(
                r.getStatusReason(), r.getFailureReason(), r.getStatus(), displayStatus),
            RunDisplayPolicy.displayRunFailureReason(
                r.getFailureReason(), r.getStatus(), displayStatus),
            actualCostUsd,
            r.getHardCapUsd() != null ? r.getHardCapUsd() : r.getUserCostCap(),
            r.getScmPrUrl() != null ? r.getScmPrUrl() : r.getGithubPrUrl(),
            com.navneet.executespec.run.outcome.RunTerminalOutcomeContract.autoPublicationExpected(
                r)),
        // PR-B (2026-05-13) — hydrate PR URLs in the listing endpoint so the project launches
        // surface can render PR pills without a per-row drill-down. Prefer scm-agnostic
        // scmPrUrl; the legacy githubPrUrl ships alongside for pre-SCM-split back-compat.
        r.getScmPrUrl(),
        r.getGithubPrUrl(),
        RunDisplayPolicy.displayVerifiedScore(verifiedScore, displayStatus),
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
   * Viewer-less detail projection. Operator-grade continuation-policy internals are withheld
   * (fail-closed) because no viewer authority is available — the SSE {@code RUN_DETAIL} push path
   * cannot know each subscriber's role. Authenticated REST reads should use {@link
   * #toDetail(PipelineRun, List, com.navneet.executespec.auth.security.AppUserDetails)}.
   */
  public RunDetail toDetail(PipelineRun r, List<AgentTask> tasks) {
    return toDetail(r, tasks, null);
  }

  public RunDetail toDetail(
      PipelineRun r,
      List<AgentTask> tasks,
      com.navneet.executespec.auth.security.AppUserDetails viewer) {
    // RR-1552 pattern reused via CostVisibilityPolicy: continuation-policy ranking/candidate
    // evidence is operator-grade detail, scoped to billing-grade roles of the run's OWNING org.
    boolean includeOperatorContinuationPolicy =
        com.navneet.executespec.cost.CostVisibilityPolicy.canViewRawCost(viewer, r.getOrgId());
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
    List<RunDetail.WorkspaceGenerationAttemptEvidence> generationAttempts =
        workspaceGenerationEvidenceProjection == null
            ? List.of()
            : workspaceGenerationEvidenceProjection.forRun(r.getRunId());
    // C1 (G4) — DERIVED effective route. Read from the physical-attempt ledger rather than stored,
    // so it can never drift from the usage facts it summarises.
    EffectiveRouteProjection effectiveRoute = runEffectiveRouteService.forRun(r.getRunId());
    String displayStatus = displayRunStatus(r.getStatus(), tasks);
    String displayStatusReason =
        RunDisplayPolicy.displayRunStatusReason(
            r.getStatusReason(), r.getFailureReason(), r.getStatus(), displayStatus);
    String displayFailureReason =
        redactPaths(
            RunDisplayPolicy.displayRunFailureReason(
                r.getFailureReason(), r.getStatus(), displayStatus));
    Double actualCostUsd = actualCostForRead(r);
    Boolean creditsRefunded = creditsRefunded(r, displayStatus);
    com.navneet.executespec.run.outcome.RunTerminalOutcome terminalOutcome =
        com.navneet.executespec.run.outcome.RunTerminalOutcomeContract.project(
            r.getRunId(),
            displayStatus,
            displayStatusReason,
            displayFailureReason,
            actualCostUsd,
            r.getHardCapUsd() != null ? r.getHardCapUsd() : r.getUserCostCap(),
            r.getScmPrUrl() != null ? r.getScmPrUrl() : r.getGithubPrUrl(),
            com.navneet.executespec.run.outcome.RunTerminalOutcomeContract.autoPublicationExpected(
                r),
            creditsRefunded);

    RunDetail.RunDetailBuilder builder =
        RunDetail.builder()
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
            // Terminal-success display statuses keep the (already success-suppressed) structured
            // value instead of echoing success copy into a field named "failureReason".
            .failureReason(
                terminalOutcome == null || RunDisplayPolicy.isTerminalSuccessDisplay(displayStatus)
                    ? displayFailureReason
                    : terminalOutcome.customerMessage())
            .statusReason(displayStatusReason)
            .terminalOutcome(terminalOutcome)
            .creditsRefunded(creditsRefunded)
            .planJson(r.getPlanJson())
            .reviewJson(r.getReviewJson())
            .runGoalDoneness(
                parseJson(redactPaths(r.getRunGoalDonenessJson()), "runGoalDoneness", r.getRunId()))
            .tasks(
                tasks.stream()
                    // RR-1446 — order by the persisted planner-intent index first so the approval
                    // gate
                    // shows the planner's sequence; status is a decoration-only tiebreaker (and the
                    // sole ordering for legacy rows that predate plan_index, which sort last).
                    .sorted(TASK_DISPLAY_ORDER)
                    .map(t -> toTaskSummary(t, includeOperatorContinuationPolicy))
                    .toList())
            .integrationSource(integrationSource)
            // Tier 8: compile / API spec / preview
            .hasFrontend(r.isHasFrontend())
            .hasApiSpec(r.isHasApiSpec())
            .previewStatus(r.getPreviewStatus())
            .keySource(r.getKeySource())
            .actualCostUsd(actualCostUsd)
            .costEstimateLow(r.getCostEstimateLow())
            .costEstimateHigh(r.getCostEstimateHigh())
            .creditsCharged(r.getCreditsCharged())
            .aiCreditsCharged(
                managedAiUsageEventRepository.sumAiCreditsChargedByRunId(r.getRunId()))
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
            .agentTokens(RunAgentTokenProjection.project(r, testPlanRepository))
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
            .harnessVersion(r.getHarnessVersion())
            .runMode(r.getRunMode())
            .acceptedWorkspaceGeneration(r.getAcceptedWorkspaceGeneration())
            .acceptedTaskIndex(r.getAcceptedTaskIndex())
            .acceptedGenerationAttemptId(
                r.getAcceptedGenerationAttemptId() == null
                    ? null
                    : r.getAcceptedGenerationAttemptId().toString())
            .acceptedCheckpointKind(r.getAcceptedCheckpointKind())
            .acceptedCheckpointDigest(r.getAcceptedCheckpointDigest())
            .acceptedCheckpointCompletedAt(r.getAcceptedCheckpointCompletedAt())
            .workspaceGenerationAttempts(generationAttempts)
            .triggeredByFinding(r.getTriggeredByFinding())
            .isRetry(r.getSourceRunId() != null)
            .sourceRunId(r.getSourceRunId())
            .runRetryCount(r.getRunRetryCount())
            // Validation status/phase tracking retired with V1 BackgroundValidationService.
            // V2 runs expose validation state through the validation report itself.
            .validationStatus(null)
            .validationPhase(null)
            .verifiedScore(
                RunDisplayPolicy.displayVerifiedScore(
                    ValidationReportScoreProjection.effectiveVerifiedScore(report, objectMapper),
                    displayStatus))
            .accuracyScore(report != null ? report.getAccuracyScore() : null);
    return RunDetailEvidenceProjection.apply(builder, r, report, effectiveRoute, objectMapper)
        .build();
  }

  /**
   * Active detail reads may be ahead of the last persistence checkpoint. Overlay only a JVM-owned
   * live snapshot, and only monotonically; terminal rows remain durable billing truth.
   */
  private Double actualCostForRead(PipelineRun run) {
    Double stored = run.getActualCostUsd();
    if (RunPipelineSupport.isTerminal(run.getStatus()) || runCostTracker == null) {
      return stored;
    }
    return runCostTracker
        .liveSnapshot(run.getRunId())
        .map(snapshot -> Math.max(stored == null ? 0.0 : stored, snapshot.costUsd()))
        .orElse(stored);
  }

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

  /**
   * Viewer-less task projection: operator-grade continuation-policy internals are withheld
   * (fail-closed). Privileged surfaces must call {@link #toTaskSummary(AgentTask, boolean)}.
   */
  public TaskSummary toTaskSummary(AgentTask t) {
    return toTaskSummary(t, false);
  }

  public TaskSummary toTaskSummary(AgentTask t, boolean includeOperatorContinuationPolicy) {
    // Server workspace paths (e.g. /opt/app/workspace/<runId>/...) are host layout, not customer
    // evidence — redact them from every DTO-projected diagnostic field before it leaves the API.
    JsonNode goalDoneness =
        parseJson(redactPaths(t.getGoalDonenessJson()), "goalDoneness", t.getTaskId());
    String displayStatus = RunDisplayPolicy.displayTaskStatus(t.getStatus(), goalDoneness);
    String failureReason = redactPaths(RunDisplayPolicy.extractFailureReason(objectMapper, t));
    if (failureReason == null && "PARTIAL".equals(displayStatus) && goalDoneness != null) {
      failureReason = goalDoneness.path("reason").asText(null);
    }
    JsonNode continuationDecision =
        RunDisplayPolicy.withheldOperatorContinuationPolicy(
            parseJson(
                redactPaths(t.getContinuationDecisionJson()),
                "continuationDecision",
                t.getTaskId()),
            includeOperatorContinuationPolicy);
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
        continuationDecision,
        parseJson(
            redactPaths(t.getContinuationActionsJson()), "continuationActions", t.getTaskId()),
        extractObjective(t.getTaskJson()),
        t.getCompletionNotes(),
        t.getParentTaskId(),
        t.getSubtaskIndex(),
        t.getSubtaskTotal());
  }

  /**
   * V300 refund evidence for the run detail projection. {@code null} when refund state is not
   * applicable (non-terminal or success display status) or cannot be resolved (no repository in a
   * bare test construction, lookup failure); {@code true} when a durable {@code run_quota_refund}
   * row exists (PENDING rows are idempotently applied by the refund worker, so the durable intent
   * itself is refund evidence); {@code false} when this terminal non-success run has no refund row.
   */
  private Boolean creditsRefunded(PipelineRun r, String displayStatus) {
    if (r == null
        || r.getRunId() == null
        || !RunDisplayPolicy.isRefundApplicableDisplayStatus(displayStatus)) {
      return null;
    }
    if (runQuotaRefundRepository == null) {
      return null;
    }
    try {
      return runQuotaRefundRepository.existsById(r.getRunId());
    } catch (RuntimeException ex) {
      log.warn("refund lookup failed for run {}: {}", r.getRunId(), ex.getMessage());
      return null;
    }
  }

  /**
   * Public because customer-facing aggregates (e.g. the project dashboard counters) must bucket
   * runs by the same fail-closed display projection as the run list, not by persisted status.
   */
  public String displayRunStatus(String persistedStatus, List<AgentTask> tasks) {
    if (!"COMPLETE".equals(persistedStatus) || tasks == null || tasks.isEmpty()) {
      return persistedStatus;
    }
    boolean allTasksProvenComplete = tasks.stream().allMatch(this::isTaskProvenCompleteForDisplay);
    return allTasksProvenComplete ? persistedStatus : "PARTIAL";
  }

  private boolean isTaskProvenCompleteForDisplay(AgentTask task) {
    if (!"COMPLETE".equals(task.getStatus())) return false;
    String json = task.getGoalDonenessJson();
    if (json == null || json.isBlank()) return true;
    try {
      JsonNode proof = objectMapper.readTree(json);
      String state = proof.path("state").asText("");
      return state.isBlank()
          || "DONE".equals(state)
          || RunDisplayPolicy.downstreamValidationDeferred(proof);
    } catch (Exception error) {
      log.warn(
          "Malformed goal proof cannot certify run completion: taskId={} ({})",
          task.getTaskId(),
          error.getMessage());
      return false;
    }
  }

  /** Null-tolerant path redaction so bare test constructions without the bean stay usable. */
  private String redactPaths(String value) {
    return workspacePathRedaction == null ? value : workspacePathRedaction.redact(value);
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
}
