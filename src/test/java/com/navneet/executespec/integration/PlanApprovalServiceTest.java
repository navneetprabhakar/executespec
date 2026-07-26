package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.requirement.ExactPlanFingerprint;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.contracts.gate.PlanContractGateService;
import com.navneet.executespec.governance.GovernanceGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PlanApprovalServiceTest {

  @Mock PipelineRunRepository runRepository;
  @Mock StateStore stateStore;
  @Mock ApiKeyResolver apiKeyResolver;
  @Mock ManagedAiUsageService managedAiUsageService;
  @Mock GovernanceGuard governanceGuard;
  @Mock PlanContractGateService planContractGate;
  @Mock RunLifecycleService runLifecycleService;
  @Mock AppUserDetails actor;

  private PlanApprovalService service;
  private PipelineRun run;

  @BeforeEach
  void setUp() {
    service =
        new PlanApprovalService(
            runRepository,
            stateStore,
            apiKeyResolver,
            managedAiUsageService,
            governanceGuard,
            planContractGate,
            runLifecycleService);
    run =
        PipelineRun.builder()
            .runId("run-1")
            .createdByUserId("owner-1")
            .orgId("org-1")
            .projectId("project-1")
            .planJson("{\"tasks\":[\"a\"]}")
            .costEstimateHigh(4.5)
            .creditsCharged(3)
            .build();
    when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
    org.mockito.Mockito.lenient()
        .when(apiKeyResolver.resolveWithProvenance("owner-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(ResolvedKeys.SYSTEM_ONLY, "org-1", Map.of()));
  }

  @Test
  void positiveDecisionUsesRunOwnerForBillingThenGovernanceThenAcknowledgementThenLifecycle() {
    AppUser actingUser = AppUser.builder().id("admin-1").orgId("org-1").build();
    when(actor.getUser()).thenReturn(actingUser);
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));
    when(planContractGate.acknowledge("run-1", "BREAKING", "digest-1", "admin-1"))
        .thenReturn(PlanContractGateService.AckOutcome.ACKNOWLEDGED);
    when(runLifecycleService.resolvePlanGate("run-1", true))
        .thenReturn(RunLifecycleService.PlanApprovalOutcome.RESUME_QUEUED);

    var result =
        service.decide(
            "run-1",
            new PlanApprovalService.Command(
                true,
                3,
                "REALTIME",
                ExactPlanFingerprint.sha256(run.getPlanJson()),
                "BREAKING",
                "digest-1"),
            actor);

    assertThat(result.outcome()).isEqualTo(RunLifecycleService.PlanApprovalOutcome.RESUME_QUEUED);
    verify(apiKeyResolver).resolveWithProvenance("owner-1");
    verify(managedAiUsageService).assertCanApproveEstimatedSpend("owner-1", "org-1", false, 4.5);
    InOrder order =
        inOrder(managedAiUsageService, governanceGuard, planContractGate, runLifecycleService);
    order
        .verify(managedAiUsageService)
        .assertCanApproveEstimatedSpend("owner-1", "org-1", false, 4.5);
    order.verify(governanceGuard).assertCanApproveCost("org-1", 4.5, actor);
    order.verify(governanceGuard).assertProjectSpendCap("project-1", 4.5);
    order.verify(planContractGate).acknowledge("run-1", "BREAKING", "digest-1", "admin-1");
    order.verify(runLifecycleService).resolvePlanGate("run-1", true);
  }

  @Test
  void stalePlanFingerprintFailsBeforeSpendOrGateMutation() {
    assertThatThrownBy(
            () ->
                service.decide(
                    "run-1",
                    new PlanApprovalService.Command(true, null, "REALTIME", "stale", null, null),
                    actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verifyNoInteractions(
        managedAiUsageService, governanceGuard, planContractGate, runLifecycleService);
  }

  @Test
  void confirmedCreditsFailClosedWhenCanonicalEstimateStateIsMissing() {
    when(stateStore.findRun("run-1")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.decide(
                    "run-1",
                    new PlanApprovalService.Command(true, 3, "REALTIME", null, null, null),
                    actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verifyNoInteractions(
        managedAiUsageService, governanceGuard, planContractGate, runLifecycleService);
  }

  @Test
  void governanceDenialDoesNotConsumeBreakingAcknowledgementOrResolveGate() {
    org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "admin required"))
        .when(governanceGuard)
        .assertCanApproveCost("org-1", 4.5, actor);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOf(ResponseStatusException.class);

    verify(planContractGate, never()).acknowledge(anyString(), any(), any(), any());
    verify(runLifecycleService, never()).resolvePlanGate(anyString(), anyBoolean());
  }

  @Test
  void rejectionSkipsEveryPositiveAdmissionPolicy() {
    when(runLifecycleService.resolvePlanGate("run-1", false))
        .thenReturn(RunLifecycleService.PlanApprovalOutcome.REJECTED);

    var result =
        service.decide(
            "run-1",
            new PlanApprovalService.Command(false, null, "REALTIME", null, null, null),
            actor);

    assertThat(result.outcome()).isEqualTo(RunLifecycleService.PlanApprovalOutcome.REJECTED);
    verifyNoInteractions(apiKeyResolver, managedAiUsageService, governanceGuard, planContractGate);
  }

  @Test
  void committedApprovalReplayRerunsAdmissionUntilDurableAdmissionReceiptExists() {
    when(actor.getUser()).thenReturn(AppUser.builder().id("admin-1").build());
    when(planContractGate.acknowledge("run-1", null, null, "admin-1"))
        .thenReturn(PlanContractGateService.AckOutcome.NOT_REQUIRED);
    when(runLifecycleService.resolvePlanGate("run-1", true))
        .thenReturn(RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED);

    var result = service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor);

    assertThat(result.outcome())
        .isEqualTo(RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED);
    verify(apiKeyResolver).resolveWithProvenance("owner-1");
    verify(managedAiUsageService).assertCanApproveEstimatedSpend("owner-1", "org-1", false, 4.5);
    verify(governanceGuard).assertCanApproveCost("org-1", 4.5, actor);
    verify(planContractGate).acknowledge("run-1", null, null, "admin-1");
  }

  @Test
  void breakingGateMissingFailsBeforeLifecycleResolution() {
    when(actor.getUser()).thenReturn(AppUser.builder().id("admin-1").build());
    when(planContractGate.acknowledge("run-1", null, null, "admin-1"))
        .thenReturn(PlanContractGateService.AckOutcome.MISSING);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verify(runLifecycleService, never()).resolvePlanGate(anyString(), anyBoolean());
  }

  @Test
  void ownKeysRunWithoutSelectedProviderKeyFailsBeforeManagedCreditExemption() {
    run.setAiProvider("GEMINI");
    ResolvedKeys anthropicOnly =
        new ResolvedKeys("anthropic-key", null, null, null, null, null, "OWN_KEYS", null);
    when(apiKeyResolver.resolveWithProvenance("owner-1"))
        .thenReturn(
            new ApiKeyResolver.CredentialResolution(
                anthropicOnly, "org-1", Map.of("anthropic", ApiKeyResolver.CredentialOrigin.USER)));

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

    verifyNoInteractions(managedAiUsageService, governanceGuard, planContractGate);
    verify(runLifecycleService, never()).resolvePlanGate(anyString(), anyBoolean());
  }

  @Test
  void invalidLifecycleOutcomeNeverLooksLikeSuccessfulApproval() {
    when(actor.getUser()).thenReturn(AppUser.builder().id("admin-1").build());
    when(planContractGate.acknowledge("run-1", null, null, "admin-1"))
        .thenReturn(PlanContractGateService.AckOutcome.NOT_REQUIRED);
    when(runLifecycleService.resolvePlanGate("run-1", true))
        .thenReturn(RunLifecycleService.PlanApprovalOutcome.INVALID_STATE);

    assertThatThrownBy(
            () -> service.decide("run-1", PlanApprovalService.Command.approveLegacy(), actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void retiredBatchFailsBeforeAnyAdmissionSideEffect() {
    assertThatThrownBy(
            () ->
                service.decide(
                    "run-1",
                    new PlanApprovalService.Command(true, null, "BATCH", null, null, null),
                    actor))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GONE));

    verifyNoInteractions(
        apiKeyResolver,
        managedAiUsageService,
        governanceGuard,
        planContractGate,
        runLifecycleService);
  }
}
