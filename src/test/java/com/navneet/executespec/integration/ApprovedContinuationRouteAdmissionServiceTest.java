package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;

import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class ApprovedContinuationRouteAdmissionServiceTest {

  private final ModelAccessService modelAccess = mock(ModelAccessService.class);
  private final AvailableProviderResolver availableProviders =
      mock(AvailableProviderResolver.class);
  private final ApprovedContinuationRouteAdmissionService service =
      new ApprovedContinuationRouteAdmissionService(modelAccess, availableProviders);

  @Test
  void admitsOnlyCurrentPolicyAllowedProviderModelWithUsableCredentials() {
    when(availableProviders.resolve("run-1")).thenReturn(Set.of(LlmProvider.OPENROUTER));

    service.assertAvailable(run(), "openrouter", "grok-4.20");

    verify(modelAccess).assertProviderAllowed("user-1", "openrouter");
    verify(modelAccess).assertModelAllowed("user-1", "grok-4.20");
    verify(modelAccess).assertProviderModelPair("openrouter", "grok-4.20");
  }

  @Test
  void unavailableOrOrgPolicyDeniedProviderFailsClosed() {
    when(availableProviders.resolve("run-1")).thenReturn(Set.of(LlmProvider.ANTHROPIC));

    assertThatThrownBy(() -> service.assertAvailable(run(), "openrouter", "grok-4.20"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable under current org policy or credentials");
  }

  @Test
  void modelPolicyDenialFailsBeforeAvailabilityCanAuthorizeTheProvider() {
    org.mockito.Mockito.doThrow(new IllegalStateException("tier denied"))
        .when(modelAccess)
        .assertModelAllowed("user-1", "grok-4.20");

    assertThatThrownBy(() -> service.assertAvailable(run(), "openrouter", "grok-4.20"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("denied by current model/provider policy");
  }

  private static PipelineRun run() {
    return PipelineRun.builder().runId("run-1").createdByUserId("user-1").build();
  }
}
