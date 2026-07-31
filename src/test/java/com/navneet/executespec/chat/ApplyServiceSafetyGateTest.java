package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.engine.WorkerAgent;
import com.navneet.executespec.integration.scm.ScmPushService;
import com.navneet.executespec.reviewer.PostEditFormatter;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class ApplyServiceSafetyGateTest {

  @Test
  void disabledTransactionalApplyStopsBeforeWorkspaceMutationModelSpendOrPublication() {
    PipelineRunRepository runRepository = mock(PipelineRunRepository.class);
    FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    WorkerAgent worker = mock(WorkerAgent.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    ScmPushService pushService = mock(ScmPushService.class);
    PostEditFormatter formatter = mock(PostEditFormatter.class);
    when(featureFlags.isEnabled(ApplyService.TRANSACTIONAL_APPLY_FLAG)).thenReturn(false);

    ApplyService service =
        new ApplyService(
            runRepository,
            featureFlags,
            worker,
            modelResolver,
            mock(AgentProperties.class),
            mock(ApplyResultStore.class),
            pushService,
            formatter);

    assertThatThrownBy(() -> service.apply("run-1", "change the endpoint", "COPILOT"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CORRECTION_TRANSACTIONAL_APPLY_DISABLED")
        .hasMessageContaining("gated correction run");

    verifyNoInteractions(runRepository, worker, modelResolver, pushService, formatter);
  }
}
