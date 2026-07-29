package com.navneet.executespec.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.PlanGateBypassPolicy;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CustomerRunSubmissionServiceTest {

  @Mock RunOrchestrator orchestrator;
  @Mock FeatureFlagService flags;
  @Mock InputSanitizer sanitizer;
  @Mock PlanGateBypassPolicy bypassPolicy;
  @Mock ProjectAccessGuard projectGuard;
  @Mock ProjectRepoService projectRepos;
  @Mock ModelAccessService modelAccess;
  @Mock AiProviderPreferenceService providerPreference;
  @Mock ManagedAiUsageService managedAiUsage;
  @Mock TierEnforcementService tierEnforcement;
  @Mock UsageTrackingService usageTracking;
  @Mock ApiKeyResolver apiKeyResolver;
  @Mock RunIdempotencyService idempotency;
  @Mock StateStore stateStore;
  @Mock RunLocalExecutionFlag localExecutionFlag;
  @Mock com.navneet.executespec.integration.RunLifecycleService runLifecycleService;

  private CustomerRunSubmissionService service;
  private AppUserDetails actor;

  @BeforeEach
  void setUp() {
    service =
        new CustomerRunSubmissionService(
            orchestrator,
            flags,
            sanitizer,
            bypassPolicy,
            projectGuard,
            projectRepos,
            modelAccess,
            providerPreference,
            managedAiUsage,
            tierEnforcement,
            usageTracking,
            apiKeyResolver,
            idempotency,
            stateStore,
            localExecutionFlag,
            runLifecycleService);
    actor = new AppUserDetails(AppUser.builder().id("user-1").orgId("org-home").build(), null);
  }

  @Test
  void rejectsReservedAutomationSourceBeforeClaimOrSpend() {
    when(bypassPolicy.isReservedAutomationSource("BENCHMARK")).thenReturn(true);

    assertThatThrownBy(() -> service.submit(command().source("BENCHMARK").build(), actor))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));

    verifyNoInteractions(idempotency, managedAiUsage, orchestrator);
  }

  @Test
  void rejectsUnresolvedProjectBoundaryBeforeClaimOrSpend() {
    UUID repoId = UUID.randomUUID();
    when(projectGuard.assertCanWrite("project-1", actor))
        .thenReturn(Project.builder().id("project-1").orgId("org-host").build());
    org.mockito.Mockito.doThrow(
            new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "repository is not linked"))
        .when(projectRepos)
        .resolveForRunStrict("project-1", List.of(repoId));

    assertThatThrownBy(
            () ->
                service.submit(
                    command().projectId("project-1").selectedRepoIds(List.of(repoId)).build(),
                    actor))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not linked");

    verifyNoInteractions(idempotency, managedAiUsage, orchestrator);
  }

  @Test
  void rejectsMissingRepositorySelectionBeforeClaimOrSpend() {
    when(projectGuard.assertCanWrite("project-1", actor))
        .thenReturn(Project.builder().id("project-1").orgId("org-host").build());
    org.mockito.Mockito.doThrow(
            new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY, "Select at least one linked repository"))
        .when(projectRepos)
        .resolveForRunStrict("project-1", null);

    assertThatThrownBy(
            () ->
                service.submit(
                    command().projectId("project-1").selectedRepoIds(null).build(), actor))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Select at least one linked repository");

    verifyNoInteractions(idempotency, managedAiUsage, orchestrator);
  }

  @Test
  void externalCollaboratorSubmissionCarriesHostOrgIntoAtomicSubmitPolicy() {
    UUID repoId = UUID.randomUUID();
    AtomicReference<RunOrchestrator.SubmitPolicy> policy = new AtomicReference<>();
    RunOrchestrator capturingOrchestrator =
        org.mockito.Mockito.mock(
            RunOrchestrator.class,
            invocation -> {
              if ("submitRunWithLocalExecution".equals(invocation.getMethod().getName())
                  && invocation.getArguments().length == 25) {
                policy.set(invocation.getArgument(24));
                return PipelineRun.builder()
                    .runId("run-host-owned")
                    .createdByUserId("user-1")
                    .orgId("org-host")
                    .build();
              }
              return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
    service =
        new CustomerRunSubmissionService(
            capturingOrchestrator,
            flags,
            sanitizer,
            bypassPolicy,
            projectGuard,
            projectRepos,
            modelAccess,
            providerPreference,
            managedAiUsage,
            tierEnforcement,
            usageTracking,
            apiKeyResolver,
            idempotency,
            stateStore,
            localExecutionFlag,
            runLifecycleService);
    when(projectGuard.assertCanWrite("project-1", actor))
        .thenReturn(Project.builder().id("project-1").orgId("org-host").build());
    when(idempotency.claim("user-1", "agent-key", "agent-fingerprint"))
        .thenReturn(RunIdempotencyClaim.claimed("user-1", "agent-key", "owner", 1L));
    when(idempotency.complete(
            org.mockito.ArgumentMatchers.any(RunIdempotencyClaim.class),
            org.mockito.ArgumentMatchers.eq("run-host-owned")))
        .thenReturn(
            new RunIdempotencyService.Completion(
                RunIdempotencyService.CompletionStatus.PUBLISHED, "run-host-owned"));

    var result =
        service.submit(
            command()
                .source("AGENT_ORCHESTRATOR")
                .projectId("project-1")
                .selectedRepoIds(List.of(repoId))
                .idempotencyKey("agent-key")
                .requestFingerprint("agent-fingerprint")
                .build(),
            actor);

    assertThat(result.run().getOrgId()).isEqualTo("org-host");
    assertThat(policy.get()).isNotNull();
    assertThat(policy.get().tenantOrgId()).isEqualTo("org-host");
    assertThat(policy.get().billingOrgId()).isEqualTo("org-home");
    verify(projectRepos).resolveForRunStrict("project-1", List.of(repoId));
  }

  @Test
  void duplicateReturnsOwnedRunWithoutChargingOrDispatchingAgain() {
    PipelineRun prior = PipelineRun.builder().runId("run-1").createdByUserId("user-1").build();
    when(idempotency.claim("user-1", "key-1", "fingerprint"))
        .thenReturn(
            RunIdempotencyClaim.resolved(
                RunIdempotencyStatus.DUPLICATE, "user-1", "key-1", "run-1", null));
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(prior));

    var result =
        service.submit(
            command().idempotencyKey("key-1").requestFingerprint("fingerprint").build(), actor);

    assertThat(result.run()).isSameAs(prior);
    assertThat(result.created()).isFalse();
    verify(managedAiUsage, never())
        .assertCanStartRun(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    verifyNoInteractions(orchestrator, usageTracking);
  }

  @Test
  void orchestratorFailureDoesNotInflatePostDispatchCounters() {
    when(idempotency.claim("user-1", "key-1", "fingerprint"))
        .thenReturn(RunIdempotencyClaim.claimed("user-1", "key-1", "owner", 1L));
    submitStub().thenThrow(new IllegalStateException("dispatch failed"));

    assertThatThrownBy(
            () ->
                service.submit(
                    command().idempotencyKey("key-1").requestFingerprint("fingerprint").build(),
                    actor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dispatch failed");

    verify(tierEnforcement, never()).incrementRunCountForAnalytics("user-1");
    verifyNoInteractions(usageTracking);
  }

  @Test
  void postDispatchUsageFailureDoesNotHideTheCreatedRun() {
    PipelineRun created =
        PipelineRun.builder().runId("run-created").createdByUserId("user-1").build();
    stubCreatedSubmission(created);
    when(idempotency.complete(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("run-created")))
        .thenReturn(
            new RunIdempotencyService.Completion(
                RunIdempotencyService.CompletionStatus.PUBLISHED, "run-created"));
    org.mockito.Mockito.doThrow(new IllegalStateException("usage db unavailable"))
        .when(usageTracking)
        .increment("user-1", "RUNS", 1);

    CustomerRunSubmissionService.Submission result =
        service.submit(
            command().idempotencyKey("key-1").requestFingerprint("fingerprint").build(), actor);

    assertThat(result.run()).isSameAs(created);
    assertThat(result.created()).isTrue();
    verify(tierEnforcement).incrementRunCountForAnalytics("user-1");
    verify(usageTracking).increment("user-1", "RUNS", 1);
  }

  @Test
  void fencedCompletionCancelsCreatedAttemptAndReturnsCanonicalRun() {
    PipelineRun created =
        PipelineRun.builder().runId("run-created").createdByUserId("user-1").build();
    PipelineRun canonical =
        PipelineRun.builder().runId("run-canonical").createdByUserId("user-1").build();
    when(idempotency.claim("user-1", "key-1", "fingerprint"))
        .thenReturn(RunIdempotencyClaim.claimed("user-1", "key-1", "owner", 1L));
    submitStub().thenReturn(created);
    when(idempotency.complete(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("run-created")))
        .thenReturn(
            new RunIdempotencyService.Completion(
                RunIdempotencyService.CompletionStatus.FENCED_TO_OTHER_RUN, "run-canonical"));
    when(runLifecycleService.cancel("run-created")).thenReturn(true);
    when(stateStore.findRun("run-canonical")).thenReturn(Optional.of(canonical));

    CustomerRunSubmissionService.Submission result =
        service.submit(
            command().idempotencyKey("key-1").requestFingerprint("fingerprint").build(), actor);

    assertThat(result.run()).isSameAs(canonical);
    assertThat(result.created()).isFalse();
    verify(runLifecycleService).cancel("run-created");
    verify(tierEnforcement, never()).incrementRunCountForAnalytics("user-1");
    verifyNoInteractions(usageTracking);
  }

  @Test
  void fencedCompletionDoesNotHideLosingAttemptWhenCancellationIsUnconfirmed() {
    PipelineRun created =
        PipelineRun.builder().runId("run-created").createdByUserId("user-1").build();
    stubCreatedSubmission(created);
    when(idempotency.complete(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("run-created")))
        .thenReturn(
            new RunIdempotencyService.Completion(
                RunIdempotencyService.CompletionStatus.FENCED_TO_OTHER_RUN, "run-canonical"));
    when(runLifecycleService.cancel("run-created")).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.submit(
                    command().idempotencyKey("key-1").requestFingerprint("fingerprint").build(),
                    actor))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
        .hasMessageContaining("run-created")
        .hasMessageContaining("could not be confirmed cancelled")
        .hasMessageContaining("losing attempt remains unaccounted for");
    verify(stateStore, never()).findRun("run-canonical");
    verify(tierEnforcement, never()).incrementRunCountForAnalytics("user-1");
    verifyNoInteractions(usageTracking);
  }

  @Test
  void unresolvedCompletionLossCancelsAttemptAndReturnsExplicitRetryableFailure() {
    PipelineRun created =
        PipelineRun.builder().runId("run-created").createdByUserId("user-1").build();
    stubCreatedSubmission(created);
    when(idempotency.complete(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("run-created")))
        .thenReturn(
            new RunIdempotencyService.Completion(
                RunIdempotencyService.CompletionStatus.FENCED_UNRESOLVED, null));
    when(runLifecycleService.cancel("run-created")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.submit(
                    command().idempotencyKey("key-1").requestFingerprint("fingerprint").build(),
                    actor))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
        .hasMessageContaining("run-created")
        .hasMessageContaining("was cancelled")
        .hasMessageContaining("Retry the same Idempotency-Key");
    verify(tierEnforcement, never()).incrementRunCountForAnalytics("user-1");
    verifyNoInteractions(usageTracking);
  }

  private void stubCreatedSubmission(PipelineRun created) {
    when(idempotency.claim("user-1", "key-1", "fingerprint"))
        .thenReturn(RunIdempotencyClaim.claimed("user-1", "key-1", "owner", 1L));
    submitStub().thenReturn(created);
  }

  private org.mockito.stubbing.OngoingStubbing<PipelineRun> submitStub() {
    return when(
        orchestrator.submitRunWithLocalExecution(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            any()));
  }

  private static CustomerRunSubmissionService.Command.CommandBuilder command() {
    return CustomerRunSubmissionService.Command.builder()
        .userRequest("build a safe change")
        .source("REST");
  }
}
