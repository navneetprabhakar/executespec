package com.navneet.executespec.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunIterationServiceTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final RunOrchestrator orchestrator = mock(RunOrchestrator.class);
  private final RunIdempotencyService idempotency = mock(RunIdempotencyService.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final InputSanitizer sanitizer = mock(InputSanitizer.class);
  private final ModelAccessService modelAccess = mock(ModelAccessService.class);
  private final AiProviderPreferenceService providers = mock(AiProviderPreferenceService.class);
  private final ManagedAiUsageService managedAi = mock(ManagedAiUsageService.class);
  private final ApiKeyResolver keys = mock(ApiKeyResolver.class);
  private final TierEnforcementService tiers = mock(TierEnforcementService.class);
  private final UsageTrackingService usage = mock(UsageTrackingService.class);
  private final ProjectRepoService projectRepos = mock(ProjectRepoService.class);
  private final RunIterationService service =
      new RunIterationService(
          runRepo,
          orchestrator,
          idempotency,
          flags,
          sanitizer,
          modelAccess,
          providers,
          managedAi,
          keys,
          tiers,
          usage,
          projectRepos);

  private AppUserDetails actor;

  @BeforeEach
  void setUp() {
    AppUser user = mock(AppUser.class);
    when(user.getId()).thenReturn("user-1");
    when(user.getOrgId()).thenReturn("actor-org");
    when(user.getGithubPat()).thenReturn("actor-pat");
    when(user.getGithubOwner()).thenReturn("actor-owner");
    actor = mock(AppUserDetails.class);
    when(actor.getUser()).thenReturn(user);
    when(providers.normalizeProvider(anyString())).thenReturn("ANTHROPIC");
    when(idempotency.claim(anyString(), any(), anyString()))
        .thenReturn(RunIdempotencyClaim.disabled());
  }

  @Test
  void startsLineageAwareIterationThroughOrchestrator() {
    PipelineRun source = source("COMPLETE");
    PipelineRun child =
        PipelineRun.builder().runId("child-1").sourceRunId("source-1").status("PLANNING").build();
    when(runRepo.findById("source-1")).thenReturn(java.util.Optional.of(source));
    when(orchestrator.submitIterationRun(
            source, "add edge cases", "user-1", "actor-org", "actor-pat", "actor-owner"))
        .thenReturn(child);

    RunIterationService.IterationResult result =
        service.iterate("source-1", " add edge cases ", null, actor);

    assertThat(result.run()).isSameAs(child);
    assertThat(result.replayed()).isFalse();
    verify(modelAccess).assertModelAllowed("user-1", null);
    verify(modelAccess).assertProviderModelPair("ANTHROPIC", null);
    verify(managedAi).assertCanStartRun(actor, false);
    verify(tiers).incrementRunCountForAnalytics("user-1");
    verify(usage).increment("user-1", "RUNS", 1);
  }

  @Test
  void duplicateIdempotencyKeyReturnsExistingChildWithoutNewSpend() {
    PipelineRun source = source("COMPLETE");
    PipelineRun child =
        PipelineRun.builder()
            .runId("child-1")
            .sourceRunId("source-1")
            .createdByUserId("user-1")
            .build();
    when(runRepo.findById("source-1")).thenReturn(java.util.Optional.of(source));
    when(idempotency.claim(anyString(), eq("idem-1"), anyString()))
        .thenReturn(
            RunIdempotencyClaim.resolved(
                RunIdempotencyStatus.DUPLICATE, "user-1", "idem-1", "child-1", null));
    when(runRepo.findById("child-1")).thenReturn(java.util.Optional.of(child));

    RunIterationService.IterationResult result =
        service.iterate("source-1", "add edge cases", "idem-1", actor);

    assertThat(result.replayed()).isTrue();
    assertThat(result.run()).isSameAs(child);
    verifyNoInteractions(orchestrator, managedAi);
  }

  @Test
  void rejectsNonTerminalSourceBeforeProviderOrSpendChecks() {
    when(runRepo.findById("source-1")).thenReturn(java.util.Optional.of(source("WORKING")));

    assertThatThrownBy(() -> service.iterate("source-1", "add edge cases", null, actor))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    verifyNoInteractions(orchestrator, managedAi, modelAccess);
  }

  private static PipelineRun source(String status) {
    return PipelineRun.builder()
        .runId("source-1")
        .status(status)
        .requestedProvider("ANTHROPIC")
        .aiProvider("ANTHROPIC")
        .routePinned(false)
        .orgId("host-org")
        .executionMode("PRODUCTION")
        .selectedRepoIds("[]")
        .build();
  }
}
