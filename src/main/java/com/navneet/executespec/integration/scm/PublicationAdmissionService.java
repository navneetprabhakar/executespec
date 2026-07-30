package com.navneet.executespec.integration.scm;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Final deterministic correctness admission shared by SCM publication and permanent deployment.
 *
 * <p>Model-authored scores are intentionally irrelevant here. Publication requires current build
 * and test evidence, an exact workspace-generation binding, and exact physical route evidence for
 * every user-approved continuation.
 */
@Service
@RequiredArgsConstructor
public class PublicationAdmissionService {

  private final ValidationReportRepository validationReportRepository;
  private final AgentTaskRepository taskRepository;
  private final CorrectionWorkspaceTransaction workspaceGenerations;
  private final AgentProperties agentProperties;
  private final ObjectMapper objectMapper;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  private final RunEffectiveRouteService effectiveRoutes;

  public Admission assertEligible(PipelineRun run) {
    Eligibility eligibility = evaluate(run);
    if (!eligibility.eligible()) {
      throw new PublicationBlockedException(eligibility);
    }
    return new Admission(eligibility.currentWorkspaceGeneration(), eligibility.code());
  }

  /**
   * Read-only publication preview used by web and CLI clients.
   *
   * <p>The method reads durable evidence and the current workspace, including the protected
   * brownfield surface, but does not persist a comparison and performs no provider, SCM, or
   * deployment call. The mutation boundary repeats the same admission through {@link
   * #assertEligible(PipelineRun)} immediately before publication.
   */
  public Eligibility evaluate(PipelineRun run) {
    if (run == null || run.getRunId() == null || run.getRunId().isBlank()) {
      return blockedEligibility(
          "PUBLICATION_RUN_MISSING",
          "RUN",
          "Publication requires a durable run identity",
          false,
          "Reload the run. If it no longer exists, start a new run.",
          null,
          null);
    }
    String currentGeneration;
    try {
      currentGeneration = currentGeneration(run);
    } catch (RuntimeException unavailable) {
      return blockedEligibility(
          "PUBLICATION_WORKSPACE_UNAVAILABLE",
          "WORKSPACE",
          "The current workspace generation could not be read",
          true,
          "Restore or sync the run workspace, then regenerate validation evidence.",
          null,
          null);
    }
    ValidationReport report = validationReportRepository.findByRunId(run.getRunId()).orElse(null);
    if (report == null) {
      return blockedEligibility(
          "PUBLICATION_VALIDATION_MISSING",
          "VALIDATION",
          "No completed validation report exists for this run",
          true,
          "Generate validation evidence for the current workspace, then check eligibility again.",
          null,
          currentGeneration);
    }
    if (!"COMPLETE".equalsIgnoreCase(report.getStatus())) {
      return blockedEligibility(
          "PUBLICATION_VALIDATION_INCOMPLETE",
          "VALIDATION",
          "Validation evidence is not complete (status=" + report.getStatus() + ")",
          true,
          "Wait for validation to complete or retry failed validation.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (report.getPipelineResult() == PipelineResult.FAILED) {
      return blockedEligibility(
          "PUBLICATION_VALIDATION_FAILED",
          "VALIDATION",
          "Deterministic validation failed for the current workspace",
          true,
          "Fix the failed deterministic validation gate and regenerate validation evidence.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (report.getWorkspaceGeneration() == null || report.getWorkspaceGeneration().isBlank()) {
      return blockedEligibility(
          "PUBLICATION_EVIDENCE_UNBOUND",
          "EVIDENCE_FRESHNESS",
          "Validation evidence is not bound to a workspace generation",
          true,
          "Regenerate validation so its evidence is bound to the current workspace.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (!report.getWorkspaceGeneration().equals(currentGeneration)) {
      return blockedEligibility(
          "PUBLICATION_EVIDENCE_STALE",
          "EVIDENCE_FRESHNESS",
          "Workspace content changed after validation; regenerate deterministic evidence",
          true,
          "Regenerate build, test, workflow, reviewer, and contract evidence on the current"
              + " workspace.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (!Boolean.TRUE.equals(report.getFinalBuildPassed())) {
      return blockedEligibility(
          "PUBLICATION_BUILD_UNPROVEN",
          "BUILD",
          "Publication requires an explicit passing final build",
          true,
          "Fix the build and regenerate validation evidence.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (!Boolean.TRUE.equals(report.getTestExecutionVerified())
        || report.getTestsTotal() == null
        || report.getTestsTotal() <= 0) {
      return blockedEligibility(
          "PUBLICATION_TESTS_UNPROVEN",
          "TESTS",
          "Publication requires a real, execution-verified test run",
          true,
          "Run at least one real test and regenerate validation evidence.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (report.getTestsPassed() == null || report.getTestsPassed() < report.getTestsTotal()) {
      return blockedEligibility(
          "PUBLICATION_TESTS_FAILED",
          "TESTS",
          "Publication requires every executed test to pass ("
              + String.valueOf(report.getTestsPassed())
              + "/"
              + report.getTestsTotal()
              + ")",
          true,
          "Fix the failing tests and regenerate validation evidence.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    WorkflowConfigEvidence workflowEvidence = workflowEvidence(report);
    if (workflowEvidence == null
        || workflowEvidence.status() == WorkflowConfigEvidence.Status.UNAVAILABLE) {
      return blockedEligibility(
          "PUBLICATION_WORKFLOW_CONFIG_UNPROVEN",
          "WORKFLOW_CONFIG",
          "Publication requires deterministic workflow/configuration evidence",
          true,
          "Regenerate validation after deterministic workflow/configuration inspection succeeds.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (workflowEvidence.status() == WorkflowConfigEvidence.Status.FAILED) {
      return blockedEligibility(
          "PUBLICATION_WORKFLOW_CONFIG_FAILED",
          "WORKFLOW_CONFIG",
          workflowEvidence.reason() == null
              ? "Workflow/configuration validation failed"
              : workflowEvidence.reason(),
          true,
          "Fix the invalid workflow/configuration files and regenerate validation.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    try {
      verifyApprovedContinuationRoutes(run);
    } catch (PublicationBlockedException mismatch) {
      return blockedEligibility(
          mismatch.code(),
          "APPROVED_ROUTE",
          mismatch.getMessage(),
          false,
          "Do not publish this workspace. Re-run the correction through its approved exact"
              + " provider/model route.",
          report.getWorkspaceGeneration(),
          currentGeneration);
    }
    if (isBrownfield(run)) {
      try {
        var compatibility = brownfieldHttpContractGuard.inspectCompatibility(run.getRunId());
        if (compatibility == null || !compatibility.compatible()) {
          return blockedEligibility(
              "BROWNFIELD_HTTP_CONTRACT_REMOVED",
              "PROTECTED_CONTRACTS",
              "Publication blocked because protected HTTP contracts are incompatible",
              false,
              "Restore the protected routes, or obtain an exact method/path-bound breaking-change"
                  + " approval before regenerating evidence.",
              report.getWorkspaceGeneration(),
              currentGeneration);
        }
      } catch (BrownfieldHttpContractGuard.ContractCompatibilityException incompatible) {
        return blockedEligibility(
            incompatible.code(),
            "PROTECTED_CONTRACTS",
            incompatible.getMessage(),
            false,
            "Restore the protected routes, or obtain an exact method/path-bound breaking-change"
                + " approval before regenerating evidence.",
            report.getWorkspaceGeneration(),
            currentGeneration);
      }
    }
    return new Eligibility(
        true,
        "ELIGIBLE",
        "PUBLICATION_ELIGIBLE",
        null,
        "All deterministic publication gates pass on the current workspace generation.",
        false,
        null,
        report.getWorkspaceGeneration(),
        currentGeneration);
  }

  private String currentGeneration(PipelineRun run) {
    Path workspace = Path.of(agentProperties.getWorkspace(), run.getRunId());
    return workspaceGenerations.captureGeneration(workspace);
  }

  private boolean isBrownfield(PipelineRun run) {
    return "EXISTING".equalsIgnoreCase(String.valueOf(run.getRepoMode()))
        || (run.getSelectedRepoIds() != null && !run.getSelectedRepoIds().isBlank())
        || brownfieldHttpContractGuard.hasBaseline(run.getRunId());
  }

  private WorkflowConfigEvidence workflowEvidence(ValidationReport report) {
    String json = report.getWorkflowConfigEvidenceJson();
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readValue(json, WorkflowConfigEvidence.class);
    } catch (Exception malformed) {
      return WorkflowConfigEvidence.unavailable("Persisted workflow/config evidence is malformed");
    }
  }

  private void verifyApprovedContinuationRoutes(PipelineRun run) {
    List<AgentTask> tasks = taskRepository.findByRunId(run.getRunId());
    for (AgentTask task : tasks) {
      ApprovedRoute approved = latestApprovedRoute(task);
      if (approved == null) {
        continue;
      }
      boolean approvalIdentityMatches =
          approved.approvalId().equals(task.getContinuationApprovalId())
              && approved.proposalDigest().equals(task.getContinuationApprovalProposalDigest())
              && "COMPLETED".equalsIgnoreCase(task.getContinuationApprovalStatus())
              && sameProvider(approved.provider(), task.getContinuationApprovalProvider())
              && approved.model().equals(task.getContinuationApprovalModel())
              && approved.decision().equals(task.getContinuationApprovalAction())
              && Objects.equals(approved.postReview(), task.getContinuationApprovalPostReview());
      if (!approvalIdentityMatches) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Approved continuation action does not match the durable approval identity for "
                + task.getTaskId());
      }
      var workerRoute =
          effectiveRoutes.forTaskPhysicalAttempts(
              run.getRunId(),
              logicalTaskId(run.getRunId(), task.getTaskId()),
              approved.workerAttempt().physicalAttemptIds());
      if (!approved.workerAttempt().matches(workerRoute, approved.provider(), approved.model())) {
        throw blocked(
            "PUBLICATION_APPROVED_ROUTE_MISMATCH",
            "Approved continuation route "
                + approved.provider()
                + "/"
                + approved.model()
                + " does not match the fence-specific physical worker attempts for "
                + task.getTaskId());
      }
      if (approved.reviewerAttempt() == null) {
        throw blocked(
            "PUBLICATION_APPROVED_REVIEWER_ROUTE_MISMATCH",
            "Approved continuation is missing fence-specific physical reviewer attempts");
      }
      var reviewerRoute =
          effectiveRoutes.forReviewerPhysicalAttempts(
              run.getRunId(), approved.reviewerAttempt().physicalAttemptIds());
      if (!approved
          .reviewerAttempt()
          .matches(reviewerRoute, approved.provider(), approved.model())) {
        throw blocked(
            "PUBLICATION_APPROVED_REVIEWER_ROUTE_MISMATCH",
            "Approved continuation route "
                + approved.provider()
                + "/"
                + approved.model()
                + " does not match its fence-specific physical reviewer attempts");
      }
    }
  }

  private ApprovedRoute latestApprovedRoute(AgentTask task) {
    String json = task.getContinuationActionsJson();
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode actions = objectMapper.readTree(json);
      if (!actions.isArray()) {
        throw blocked(
            "PUBLICATION_ROUTE_EVIDENCE_INVALID",
            "Continuation route evidence is not a JSON array for " + task.getTaskId());
      }
      for (int i = actions.size() - 1; i >= 0; i--) {
        JsonNode action = actions.get(i);
        if (!"APPROVED".equalsIgnoreCase(action.path("status").asText())) {
          continue;
        }
        String provider = action.path("provider").asText("");
        String model = action.path("model").asText("");
        String decision = action.path("decision").asText("");
        String approvalId = action.path("approvalId").asText("");
        String proposalDigest = action.path("proposalDigest").asText("");
        AttemptIdentity workerAttempt = attemptIdentity(action.path("workerAttempt"));
        boolean postReview = action.path("postReview").asBoolean(false);
        AttemptIdentity reviewerAttempt = attemptIdentity(action.path("reviewerAttempt"));
        if (provider.isBlank()
            || model.isBlank()
            || decision.isBlank()
            || !isUuid(approvalId)
            || !proposalDigest.matches("[0-9a-f]{64}")
            || workerAttempt == null
            || reviewerAttempt == null) {
          throw blocked(
              "PUBLICATION_ROUTE_EVIDENCE_INVALID",
              "Approved continuation route evidence is incomplete for " + task.getTaskId());
        }
        return new ApprovedRoute(
            provider,
            model,
            postReview,
            decision,
            approvalId,
            proposalDigest,
            workerAttempt,
            reviewerAttempt);
      }
      return null;
    } catch (PublicationBlockedException blocked) {
      throw blocked;
    } catch (Exception malformed) {
      throw blocked(
          "PUBLICATION_ROUTE_EVIDENCE_INVALID",
          "Continuation route evidence is malformed for " + task.getTaskId());
    }
  }

  private static AttemptIdentity attemptIdentity(JsonNode node) {
    if (node == null || !node.isObject()) {
      return null;
    }
    String provider = node.path("provider").asText("");
    String model = node.path("model").asText("");
    String attribution = node.path("attribution").asText("");
    long floor = node.path("callIndexFloor").asLong(-1L);
    long first = node.path("firstCallIndex").asLong(-1L);
    long last = node.path("lastCallIndex").asLong(-1L);
    JsonNode idsNode = node.path("physicalAttemptIds");
    if (provider.isBlank()
        || model.isBlank()
        || !"LEDGER".equals(attribution)
        || floor < 0
        || first <= floor
        || last < first
        || !idsNode.isArray()
        || idsNode.isEmpty()) {
      return null;
    }
    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
    for (JsonNode id : idsNode) {
      if (!id.isTextual() || id.asText().isBlank() || !ids.add(id.asText())) {
        return null;
      }
    }
    return new AttemptIdentity(provider, model, List.copyOf(ids), floor, first, last);
  }

  private static boolean isUuid(String value) {
    try {
      return value != null && value.equals(java.util.UUID.fromString(value).toString());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static boolean sameProvider(String expected, String actual) {
    return expected != null
        && actual != null
        && expected.trim().toUpperCase(Locale.ROOT).equals(actual.trim().toUpperCase(Locale.ROOT));
  }

  private static String logicalTaskId(String runId, String taskId) {
    if (runId == null || taskId == null || runId.length() < 8) {
      return taskId;
    }
    String prefix = runId.substring(0, 8) + "_";
    return taskId.startsWith(prefix) ? taskId.substring(prefix.length()) : taskId;
  }

  private static PublicationBlockedException blocked(String code, String message) {
    return new PublicationBlockedException(
        blockedEligibility(code, "PUBLICATION", message, false, null, null, null));
  }

  private static Eligibility blockedEligibility(
      String code,
      String blockingGate,
      String reason,
      boolean retryable,
      String remediation,
      String evidenceWorkspaceGeneration,
      String currentWorkspaceGeneration) {
    return new Eligibility(
        false,
        "BLOCKED",
        code,
        blockingGate,
        reason,
        retryable,
        remediation,
        evidenceWorkspaceGeneration,
        currentWorkspaceGeneration);
  }

  public record Admission(String workspaceGeneration, String code) {}

  public record Eligibility(
      boolean eligible,
      String eligibility,
      String code,
      String blockingGate,
      String reason,
      boolean retryable,
      String remediation,
      String evidenceWorkspaceGeneration,
      String currentWorkspaceGeneration) {}

  public static final class PublicationBlockedException extends IllegalStateException {
    private final String code;
    private final Eligibility eligibility;

    PublicationBlockedException(Eligibility eligibility) {
      super(eligibility.reason());
      this.code = eligibility.code();
      this.eligibility = eligibility;
    }

    public String code() {
      return code;
    }

    public Eligibility eligibility() {
      return eligibility;
    }
  }

  private record ApprovedRoute(
      String provider,
      String model,
      boolean postReview,
      String decision,
      String approvalId,
      String proposalDigest,
      AttemptIdentity workerAttempt,
      AttemptIdentity reviewerAttempt) {}

  private record AttemptIdentity(
      String provider,
      String model,
      List<String> physicalAttemptIds,
      long callIndexFloor,
      long firstCallIndex,
      long lastCallIndex) {
    boolean matches(
        RunEffectiveRouteService.AttemptRouteEvidence ledger,
        String expectedProvider,
        String expectedModel) {
      return ledger != null
          && ledger.isExact(expectedProvider, expectedModel)
          && sameProvider(expectedProvider, provider)
          && expectedModel.equals(model)
          && new java.util.HashSet<>(physicalAttemptIds)
              .equals(new java.util.HashSet<>(ledger.physicalAttemptIds()))
          && firstCallIndex == ledger.firstCallIndex()
          && lastCallIndex == ledger.lastCallIndex();
    }
  }
}
