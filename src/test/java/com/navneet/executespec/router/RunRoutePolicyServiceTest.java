package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class RunRoutePolicyServiceTest {

  @Test
  void loadsAndCachesTheImmutableExactRoute() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-exact")
            .requestedProvider("DEEPSEEK")
            .requestedModel("deepseek-v4-pro-native")
            .routingMode("EXACT_LOCK")
            .routePinned(true)
            .build();
    when(repository.findById("run-exact")).thenReturn(Optional.of(run));
    RunRoutePolicyService service = new RunRoutePolicyService(repository);

    assertThat(service.forRun("run-exact").exactLock()).isTrue();
    assertThat(service.forRun("run-exact").requestedModel()).isEqualTo("deepseek-v4-pro-native");
    verify(repository, times(1)).findById("run-exact");
  }

  @Test
  void doesNotCacheAReadBeforeTheRunRowExists() {
    PipelineRunRepository repository = mock(PipelineRunRepository.class);
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-race")
            .requestedProvider("OPENAI")
            .routingMode("PROVIDER_PREFERRED")
            .routePinned(false)
            .build();
    when(repository.findById("run-race")).thenReturn(Optional.empty()).thenReturn(Optional.of(run));
    RunRoutePolicyService service = new RunRoutePolicyService(repository);

    assertThat(service.forRun("run-race").exactLock()).isFalse();
    assertThat(service.forRun("run-race").requestedProvider()).isEqualTo("OPENAI");
    verify(repository, times(2)).findById("run-race");
  }
}
