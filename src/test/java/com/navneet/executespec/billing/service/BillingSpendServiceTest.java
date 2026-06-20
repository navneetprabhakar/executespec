package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class BillingSpendServiceTest {

  private final PipelineRunRepository pipelineRunRepository = mock(PipelineRunRepository.class);
  private final ExecutionRunRepository executionRunRepository = mock(ExecutionRunRepository.class);
  private final BillingSpendService service =
      new BillingSpendService(pipelineRunRepository, executionRunRepository);

  @Test
  void sumsPipelineAndExecutionSpend() {
    Instant since = Instant.parse("2026-06-01T00:00:00Z");
    when(pipelineRunRepository.sumActualCostByUserAndSince("u1", since)).thenReturn(1.25);
    when(executionRunRepository.sumTotalCostByUserAndSince("u1", since)).thenReturn(2.75);

    assertThat(service.spentUsdSince("u1", since)).isEqualTo(4.0);
  }

  @Test
  void treatsNullRepositorySumsAsZero() {
    Instant since = Instant.parse("2026-06-01T00:00:00Z");
    when(pipelineRunRepository.sumActualCostByUserAndSince("u1", since)).thenReturn(null);
    when(executionRunRepository.sumTotalCostByUserAndSince("u1", since)).thenReturn(null);

    assertThat(service.spentUsdSince("u1", since)).isZero();
  }

  @Test
  void keepsAvailableSpendWhenOneRepositoryFails() {
    Instant since = Instant.parse("2026-06-01T00:00:00Z");
    when(pipelineRunRepository.sumActualCostByUserAndSince("u1", since))
        .thenThrow(new RuntimeException("db offline"));
    when(executionRunRepository.sumTotalCostByUserAndSince("u1", since)).thenReturn(3.5);

    assertThat(service.spentUsdSince("u1", since)).isEqualTo(3.5);
  }
}
