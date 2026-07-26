package com.navneet.executespec.integration;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.contracts.gate.PlanContractGateService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Synchronous admission and resolution authority for every customer-facing plan decision.
 *
 * <p>Authorization remains at the route boundary because run, execution-view and agent-session
 * callers have different lookup surfaces. Once authorized, however, no caller may resolve a plan
 * gate without passing this exact policy sequence against the canonical {@link PipelineRun}.
 */
@Service
@RequiredArgsConstructor
public class PlanApprovalService {

  private final PipelineRunRepository runRepository;
  private final StateStore stateStore;
  private final ApiKeyResolver apiKeyResolver;
  private final ManagedAiUsageService managedAiUsageService;
  private final GovernanceGuard governanceGuard;
  private final PlanContractGateService planContractGate;
  private final RunLifecycleService runLifecycleService;

  public Decision decide(String runId, Command command, AppUserDetails actor) {
    if (command == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan decision body is required.");
    }
    PipelineRun run =
        runRepository
            .findById(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + runId));

    rejectRetiredBatchApproval(command.approved(), command.executionMode());
    if (command.approved()) {
      validatePlanIdentity(run, command.planFingerprint());
      validateCredits(runId, command.creditsConfirmed(), command.executionMode());
      double estimateHighUsd = estimateHighUsd(run);

      // The run owner is the billing principal. An org/project administrator may be the actor who
      // approves, but later provider calls debit the owner's managed-AI period and key hierarchy.
      String billingUserId = run.getCreatedByUserId();
      if (billingUserId == null || billingUserId.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Run has no billing owner; plan approval cannot safely authorize provider spend.");
      }
      boolean exactProviderUsesOwnKey = exactProviderUsesOwnKey(run, billingUserId);
      managedAiUsageService.assertCanApproveEstimatedSpend(
          billingUserId, run.getOrgId(), exactProviderUsesOwnKey, estimateHighUsd);

      governanceGuard.assertCanApproveCost(run.getOrgId(), estimateHighUsd, actor);
      governanceGuard.assertProjectSpendCap(run.getProjectId(), estimateHighUsd);

      // Keep the durable acknowledgement last among admission checks so a spend/governance denial
      // cannot consume acknowledgement evidence for an approval that never reached the gate.
      var acknowledgement =
          planContractGate.acknowledge(
              runId,
              command.breakingChangeAck(),
              command.breakingChangeReportDigest(),
              actor == null || actor.getUser() == null ? null : actor.getUser().getId());
      switch (acknowledgement) {
        case MISSING ->
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This plan changes a contract that other repositories consume. Review the"
                    + " breaking-change report and type BREAKING to acknowledge it before"
                    + " approving.");
        case STALE_REPORT ->
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "The breaking-change report you acknowledged is not the one currently on this run."
                    + " Reload the plan and review the change again before approving.");
        default -> {
          // NOT_REQUIRED or ACKNOWLEDGED.
        }
      }
    }

    RunLifecycleService.PlanApprovalOutcome outcome =
        runLifecycleService.resolvePlanGate(runId, command.approved());
    assertAcceptedOutcome(command.approved(), outcome);
    return new Decision(run, outcome, normalizedExecutionMode(command.executionMode()));
  }

  private static void validatePlanIdentity(PipelineRun run, String suppliedFingerprint) {
    if (suppliedFingerprint == null || suppliedFingerprint.isBlank()) return;
    String actual = ExactPlanFingerprint.sha256(run.getPlanJson());
    if (!suppliedFingerprint.trim().equalsIgnoreCase(actual)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The plan you reviewed is not the plan currently on this run. Reload the plan and review"
              + " it again before approving.");
    }
  }

  private void validateCredits(String runId, Integer confirmed, String executionMode) {
    if (confirmed == null) return;
    var state =
        stateStore
            .findRun(runId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The current cost estimate is unavailable. Reload the plan before"
                            + " approving."));
    int expected =
        "BATCH".equalsIgnoreCase(executionMode) && state.getBatchCredits() != null
            ? state.getBatchCredits()
            : (state.getCreditsCharged() != null ? state.getCreditsCharged() : 1);
    if (!confirmed.equals(expected)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Credits mismatch: expected "
              + expected
              + " but confirmed "
              + confirmed
              + ". Cost estimate may have changed — please review.");
    }
  }

  private static double estimateHighUsd(PipelineRun run) {
    if (run.getCostEstimateHigh() != null) return run.getCostEstimateHigh();
    return run.getHardCapUsd() != null ? run.getHardCapUsd() : 0.0;
  }

  /**
   * Grants the BYOK approval exemption only when this run's selected provider has a customer key.
   * Org-wide OWN_KEYS mode alone is insufficient: an Anthropic-only key set must not exempt a
   * Gemini run and then discover the missing credential after approval.
   */
  private boolean exactProviderUsesOwnKey(PipelineRun run, String billingUserId) {
    ApiKeyResolver.CredentialResolution resolution =
        apiKeyResolver.resolveWithProvenance(billingUserId);
    if (resolution == null || resolution.keys() == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Run credential policy is unavailable; plan approval cannot safely authorize provider"
              + " spend.");
    }
    var keys = resolution.keys();
    if (!"OWN_KEYS".equalsIgnoreCase(keys.keySourceMode())) return false;

    String provider =
        run.getAiProvider() == null || run.getAiProvider().isBlank()
            ? "openai"
            : run.getAiProvider().trim().toLowerCase(java.util.Locale.ROOT);
    if ("google".equals(provider)) provider = "gemini";
    if (!keys.isProviderAllowed(provider) || resolution.originFor(provider) == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This run uses "
              + provider
              + ", but the OWN_KEYS billing owner has no enabled customer key for that provider."
              + " Add the provider key or choose a keyed provider before approving.");
    }
    return true;
  }

  private static void assertAcceptedOutcome(
      boolean approved, RunLifecycleService.PlanApprovalOutcome outcome) {
    if (outcome == RunLifecycleService.PlanApprovalOutcome.CONFLICTING_DECISION) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plan gate was already resolved with the opposite decision.");
    }
    boolean accepted =
        outcome == RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED
            || (approved && outcome == RunLifecycleService.PlanApprovalOutcome.RESUME_QUEUED)
            || (!approved && outcome == RunLifecycleService.PlanApprovalOutcome.REJECTED);
    if (!accepted) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Plan approval cannot resume from the run's current state.");
    }
  }

  public static void rejectRetiredBatchApproval(boolean approved, String executionMode) {
    if (approved && "BATCH".equalsIgnoreCase(executionMode)) {
      throw new ResponseStatusException(
          HttpStatus.GONE,
          "BATCH_EXECUTION_RETIRED: batch execution is not available in ExecuteSpec 2.0; use"
              + " REALTIME");
    }
  }

  private static String normalizedExecutionMode(String executionMode) {
    return executionMode == null || executionMode.isBlank() ? "REALTIME" : executionMode;
  }

  public record Command(
      boolean approved,
      Integer creditsConfirmed,
      String executionMode,
      String planFingerprint,
      String breakingChangeAck,
      String breakingChangeReportDigest) {
    public static Command approveLegacy() {
      return new Command(true, null, "REALTIME", null, null, null);
    }
  }

  public record Decision(
      PipelineRun run, RunLifecycleService.PlanApprovalOutcome outcome, String executionMode) {}
}
