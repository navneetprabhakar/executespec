package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PlanApprovalServiceTest {

  @Mock PlanGateDecisionService decisionService;
  @Mock AppUserDetails actor;

  private PlanApprovalService service;
  private PipelineRun run;

  @BeforeEach
  void setUp() {
    service = new PlanApprovalService(decisionService);
    run = PipelineRun.builder().runId("run-1").build();
  }

  @Test
  void firstApprovalMapsAtomicDecisionToResumeQueued() {
    var command = PlanApprovalService.Command.approveLegacy();
    when(decisionService.decide(
            "run-1", command, actor, PlanGateDecisionService.ResolutionSource.PLAN_APPROVAL_API))
        .thenReturn(
            new PlanGateDecisionService.DecisionResult(
                run, PlanGateDecisionService.DecisionKind.APPROVE, null, false));

    var result = service.decide("run-1", command, actor);

    assertThat(result.outcome()).isEqualTo(RunLifecycleService.PlanApprovalOutcome.RESUME_QUEUED);
    assertThat(result.replayed()).isFalse();
    verify(decisionService)
        .decide(
            "run-1", command, actor, PlanGateDecisionService.ResolutionSource.PLAN_APPROVAL_API);
  }

  @Test
  void exactReplayMapsToAlreadyResolved() {
    var command = PlanApprovalService.Command.approveLegacy();
    when(decisionService.decide(
            "run-1", command, actor, PlanGateDecisionService.ResolutionSource.PLAN_APPROVAL_API))
        .thenReturn(
            new PlanGateDecisionService.DecisionResult(
                run, PlanGateDecisionService.DecisionKind.APPROVE, null, true));

    var result = service.decide("run-1", command, actor);

    assertThat(result.outcome())
        .isEqualTo(RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED);
    assertThat(result.replayed()).isTrue();
  }

  @Test
  void rejectionMapsAtomicDecisionToRejected() {
    var command = PlanApprovalService.Command.rejectLegacy();
    when(decisionService.decide(
            "run-1", command, actor, PlanGateDecisionService.ResolutionSource.PLAN_APPROVAL_API))
        .thenReturn(
            new PlanGateDecisionService.DecisionResult(
                run, PlanGateDecisionService.DecisionKind.REJECT, null, false));

    assertThat(service.decide("run-1", command, actor).outcome())
        .isEqualTo(RunLifecycleService.PlanApprovalOutcome.REJECTED);
  }

  @Test
  void retiredBatchFailsBeforeAtomicDecision() {
    var command = new PlanApprovalService.Command(true, null, "BATCH", null, null, null);

    assertThatThrownBy(() -> service.decide("run-1", command, actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GONE));

    verifyNoInteractions(decisionService);
  }
}
