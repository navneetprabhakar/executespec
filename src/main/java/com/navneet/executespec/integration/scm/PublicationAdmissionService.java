package com.navneet.executespec.integration.scm;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.DurableCorrectionLifecycle;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
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
  private final DurableCorrectionLifecycle durableCorrections;
  private final RunWorkspaceAuthorityService workspaceAuthorities;

  public Admission assertEligible(PipelineRun run) {
    if (run == null || run.getRunId() == null || run.getRunId().isBlank()) {
      Eligibility eligibility = evaluate(run);
      throw new PublicationBlockedException(eligibility);
    }
    RunWorkspaceAuthorityService.Lease lease =
        workspaceAuthorities.acquire(
            run.getRunId(), RunWorkspaceAuthorityService.Authority.PUBLICATION);
    try {
      Eligibility eligibility = evaluate(run);
      if (!eligibility.eligible()) {
        throw new PublicationBlockedException(eligibility);
      }
      return new Admission(eligibility.currentWorkspaceGeneration(), eligibility.code(), lease);
    } catch (RuntimeException failure) {
      lease.close();
      throw failure;
    }
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
    try {
      durableCorrections.assertNoUnresolvedAttempt(run.getRunId());
    } catch (RuntimeException unresolved) {
      return blockedEligibility(
          "CORRECTION_ATTEMPT_UNRESOLVED",
          "CORRECTION_RECOVERY",
          "An incomplete correction still owns this run workspace",
          false,
          "Wait for correction recovery. If recovery failed, restore the checkpoint before"
              + " publication.",
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
      new ApprovedContinuationRouteAttestation(taskRepository, objectMapper, effectiveRoutes)
          .verify(run);
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

  /** Rechecks that publication preparation did not mutate the admitted workspace. */
  public void assertWorkspaceGenerationUnchanged(PipelineRun run, Admission admission) {
    if (admission == null) {
      throw new PublicationBlockedException(
          blockedEligibility(
              "PUBLICATION_ADMISSION_MISSING",
              "EVIDENCE_FRESHNESS",
              "Publication admission is missing",
              true,
              "Re-run publication admission before preparing deployment artifacts.",
              null,
              null));
    }
    admission.assertOwned();
    String current = currentGeneration(run);
    if (!admission.workspaceGeneration().equals(current)) {
      throw new PublicationBlockedException(
          blockedEligibility(
              "PUBLICATION_EVIDENCE_STALE",
              "EVIDENCE_FRESHNESS",
              "Workspace content changed during publication preparation",
              true,
              "Regenerate validation on the prepared workspace before publishing it.",
              admission.workspaceGeneration(),
              current));
    }
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

  public static final class Admission implements AutoCloseable {
    private final String workspaceGeneration;
    private final String code;
    private final RunWorkspaceAuthorityService.Lease lease;

    private Admission(
        String workspaceGeneration, String code, RunWorkspaceAuthorityService.Lease lease) {
      this.workspaceGeneration = workspaceGeneration;
      this.code = code;
      this.lease = lease;
    }

    /** Test-fixture constructor; production admission always carries a durable workspace lease. */
    public Admission(String workspaceGeneration, String code) {
      this(workspaceGeneration, code, null);
    }

    public String workspaceGeneration() {
      return workspaceGeneration;
    }

    public String code() {
      return code;
    }

    /** Re-fences the lease immediately before an irreversible publication side effect. */
    public void assertOwned() {
      if (lease != null) lease.assertOwned();
    }

    @Override
    public void close() {
      if (lease != null) lease.close();
    }
  }

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
}
