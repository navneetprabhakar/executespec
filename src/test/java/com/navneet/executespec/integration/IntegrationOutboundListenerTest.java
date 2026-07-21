package com.navneet.executespec.integration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.config.OperationalReleasePolicy;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.repository.IntegrationRunRepository;

import org.junit.jupiter.api.Test;

class IntegrationOutboundListenerTest {

  @Test
  void disabledReleasePolicyReturnsBeforeRepositoryLookupOrVirtualThreadDispatch() {
    IntegrationRunRepository repository = mock(IntegrationRunRepository.class);
    StateStore stateStore = mock(StateStore.class);
    OutboundAdapter adapter = mock(OutboundAdapter.class);
    OperationalReleasePolicy policy = mock(OperationalReleasePolicy.class);
    when(policy.outboundIntegrationDeliveryEnabled()).thenReturn(false);
    IntegrationOutboundListener listener =
        new IntegrationOutboundListener(repository, stateStore, List.of(adapter), policy);

    listener.onRunStarted(new PipelineEvent.RunStarted("run-1"));

    verify(repository, never()).findByRunId("run-1");
    verify(adapter, never())
        .onRunStarted(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }
}
