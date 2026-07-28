package com.navneet.executespec.integration;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;

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

  private final PlanGateDecisionService decisionService;

  public Decision decide(String runId, Command command, AppUserDetails actor) {
    return decide(
        runId, command, actor, PlanGateDecisionService.ResolutionSource.PLAN_APPROVAL_API);
  }

  public Decision decide(
      String runId,
      Command command,
      AppUserDetails actor,
      PlanGateDecisionService.ResolutionSource resolutionSource) {
    if (command == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan decision body is required.");
    }
    if (resolutionSource == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Plan decision resolution source is required.");
    }
    rejectRetiredBatchApproval(command.approved(), command.executionMode());
    PlanGateDecisionService.DecisionResult result =
        decisionService.decide(runId, command, actor, resolutionSource);
    RunLifecycleService.PlanApprovalOutcome outcome =
        result.replayed()
            ? RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED
            : command.approved()
                ? RunLifecycleService.PlanApprovalOutcome.RESUME_QUEUED
                : RunLifecycleService.PlanApprovalOutcome.REJECTED;
    return new Decision(
        result.run(),
        outcome,
        normalizedExecutionMode(command.executionMode()),
        result.evidence(),
        result.replayed());
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

    public static Command rejectLegacy() {
      return new Command(false, null, "REALTIME", null, null, null);
    }
  }

  public record Decision(
      PipelineRun run,
      RunLifecycleService.PlanApprovalOutcome outcome,
      String executionMode,
      DecisionEvidenceV1 evidence,
      boolean replayed) {}
}
