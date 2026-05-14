package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PR-A regression guard — proves {@link ExecutionCostAggregator} sums cost + tokens across every
 * underlying pipeline_run, covering both the V188 unified-dispatch path ({@link
 * ExecutionRun#getPipelineRunId()}) and the legacy per-Solution attempt history. Replaces the
 * deleted SolutionExecutionServiceAccumulateCasTest which tested the broken incremental accumulator
 * removed in this PR.
 */
@ExtendWith(MockitoExtension.class)
class ExecutionCostAggregatorTest {

  @Mock ExecutionRunRepository runRepo;
  @Mock ExecutionSolutionRunRepository solutionRunRepo;
  @Mock PipelineRunRepository pipelineRunRepo;

  ExecutionRunCasUpdater runCas;
  ExecutionCostAggregator aggregator;

  @BeforeEach
  void wire() {
    runCas = new ExecutionRunCasUpdater(runRepo);
    aggregator = new ExecutionCostAggregator(runRepo, solutionRunRepo, pipelineRunRepo, runCas);
  }

  @Test
  void compute_unifiedPath_singlePipelineRun_returnsThatRunsCostAndTokens() {
    ExecutionRun er = ExecutionRun.builder().id("exec-1").pipelineRunId("v2-run-a").build();
    when(runRepo.findById("exec-1")).thenReturn(Optional.of(er));
    when(solutionRunRepo.findPipelineRunIdsByExecutionRunId("exec-1")).thenReturn(List.of());
    when(pipelineRunRepo.aggregateCostAndTokens(any()))
        .thenReturn(new Object[] {2.50d, 100_000L, 30_000L});

    ExecutionCostAggregator.Totals out = aggregator.compute("exec-1");

    assertThat(out.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(2.50d));
    assertThat(out.actualTokens()).isEqualTo(130_000L);
  }

  @Test
  void compute_legacyPath_walksAttemptHistory_includingPartialAndFailed() {
    // No unified pipelineRunId on the ExecutionRun — pure legacy path. Three attempts on two
    // solutions, including a PARTIAL and a FAILED that the OLD accumulator skipped entirely.
    ExecutionRun er = ExecutionRun.builder().id("exec-2").pipelineRunId(null).build();
    when(runRepo.findById("exec-2")).thenReturn(Optional.of(er));
    when(solutionRunRepo.findPipelineRunIdsByExecutionRunId("exec-2"))
        .thenReturn(List.of("v2-att1-COMPLETE", "v2-att2-PARTIAL", "v2-att3-FAILED"));
    when(pipelineRunRepo.aggregateCostAndTokens(any()))
        .thenReturn(new Object[] {5.55d, 600_000L, 150_000L});

    ExecutionCostAggregator.Totals out = aggregator.compute("exec-2");

    // Real exec 85778e63 number — confirms the fix produces the right total, not the $0.81 the
    // broken accumulator wrote.
    assertThat(out.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(5.55d));
    assertThat(out.actualTokens()).isEqualTo(750_000L);
  }

  @Test
  void compute_bothPaths_dedupesPipelineRunIds() {
    // Execution carries BOTH a unified V188 pipelineRunId AND legacy attempt history — possible
    // when a launch was migrated mid-flight. The set semantics must dedupe so we don't
    // double-count a run that appears in both lists.
    ExecutionRun er = ExecutionRun.builder().id("exec-3").pipelineRunId("v2-shared").build();
    when(runRepo.findById("exec-3")).thenReturn(Optional.of(er));
    when(solutionRunRepo.findPipelineRunIdsByExecutionRunId("exec-3"))
        .thenReturn(List.of("v2-shared", "v2-extra")); // v2-shared duplicates the unified id
    when(pipelineRunRepo.aggregateCostAndTokens(argThat(ids -> ids.size() == 2)))
        .thenReturn(new Object[] {1.00d, 50_000L, 10_000L});

    ExecutionCostAggregator.Totals out = aggregator.compute("exec-3");
    assertThat(out.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(1.00d));
    // verify aggregateCostAndTokens was called exactly once with a 2-element set (deduped)
    verify(pipelineRunRepo).aggregateCostAndTokens(argThat(ids -> ids.size() == 2));
  }

  @Test
  void compute_noLinkedPipelineRuns_returnsZero_noQueryFired() {
    // Execution that failed before dispatch (planner crashed). No linked runs at all.
    ExecutionRun er = ExecutionRun.builder().id("exec-4").pipelineRunId(null).build();
    when(runRepo.findById("exec-4")).thenReturn(Optional.of(er));
    when(solutionRunRepo.findPipelineRunIdsByExecutionRunId("exec-4")).thenReturn(List.of());

    ExecutionCostAggregator.Totals out = aggregator.compute("exec-4");

    assertThat(out).isEqualTo(ExecutionCostAggregator.Totals.ZERO);
    // Don't fire an aggregate query on an empty set — saves a DB round-trip.
    verify(pipelineRunRepo, never()).aggregateCostAndTokens(any());
  }

  @Test
  void compute_executionNotFound_returnsZero() {
    when(runRepo.findById("nope")).thenReturn(Optional.empty());

    ExecutionCostAggregator.Totals out = aggregator.compute("nope");

    assertThat(out).isEqualTo(ExecutionCostAggregator.Totals.ZERO);
    verifyNoInteractions(pipelineRunRepo);
    verifyNoInteractions(solutionRunRepo);
  }

  @Test
  void recomputeAndPersist_writesTotalsViaCas_evenWhenZero() {
    ExecutionRun er = ExecutionRun.builder().id("exec-5").pipelineRunId("v2-x").build();
    er.setTotalCostUsd(BigDecimal.valueOf(99.99d)); // stale wrong value pre-recompute
    er.setTotalActualTokens(123L);
    when(runRepo.findById("exec-5")).thenReturn(Optional.of(er));
    when(runRepo.save(any(ExecutionRun.class))).thenAnswer(inv -> inv.getArgument(0));
    when(solutionRunRepo.findPipelineRunIdsByExecutionRunId("exec-5")).thenReturn(List.of());
    when(pipelineRunRepo.aggregateCostAndTokens(any()))
        .thenReturn(new Object[] {0.50d, 10_000L, 5_000L});

    ExecutionCostAggregator.Totals out = aggregator.recomputeAndPersist("exec-5");

    assertThat(out.costUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.50d));
    assertThat(out.actualTokens()).isEqualTo(15_000L);
    // CAS mutator must have set both fields to the freshly-computed values, overwriting the stale
    // 99.99 + 123.
    assertThat(er.getTotalCostUsd()).isEqualByComparingTo(BigDecimal.valueOf(0.50d));
    assertThat(er.getTotalActualTokens()).isEqualTo(15_000L);
    verify(runRepo, atLeastOnce()).save(any(ExecutionRun.class));
  }

  @Test
  void compute_aggregateReturnsNullColumns_treatsAsZero() {
    // PostgreSQL SUM() over empty set returns NULL, COALESCE protects but the JPA mapping can
    // still surface null in a column. Verify we don't NPE.
    ExecutionRun er = ExecutionRun.builder().id("exec-6").pipelineRunId("v2-y").build();
    when(runRepo.findById("exec-6")).thenReturn(Optional.of(er));
    when(solutionRunRepo.findPipelineRunIdsByExecutionRunId("exec-6")).thenReturn(List.of());
    when(pipelineRunRepo.aggregateCostAndTokens(any())).thenReturn(new Object[] {null, null, null});

    ExecutionCostAggregator.Totals out = aggregator.compute("exec-6");
    assertThat(out.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(out.actualTokens()).isEqualTo(0L);
  }
}
