package com.navneet.executespec.execution.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRunRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-A (2026-05-13) — recomputes {@link ExecutionRun#getTotalCostUsd()} and {@link
 * ExecutionRun#getTotalActualTokens()} from the underlying {@code pipeline_run} rows.
 *
 * <p>Replaces the legacy per-Solution incremental accumulator ({@code
 * SolutionExecutionService.accumulateExecutionRunTotals}) which dropped contributions on CAS
 * conflict + only counted COMPLETE attempts (skipping PARTIAL + FAILED that ran on the customer's
 * dime). Observed live on exec {@code 85778e63}: rollup reported {@code $0.81}, real spend computed
 * from attempts was {@code $5.55} (6.8× under-report).
 *
 * <p>Source of truth: every {@code pipeline_run.actual_cost_usd} for runs linked to this execution
 * via either (a) {@link
 * com.navneet.executespec.execution.entity.ExecutionSolutionRun#getPipelineRunId()} for legacy
 * per-Solution dispatch (every attempt counted, regardless of terminal status) or (b) {@link
 * ExecutionRun#getPipelineRunId()} for the V188 unified dispatch path.
 *
 * <p>Idempotent + safe to call from any phase; recomputes from scratch each time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionCostAggregator {

  private final ExecutionRunRepository runRepo;
  private final ExecutionSolutionRunRepository solutionRunRepo;
  private final PipelineRunRepository pipelineRunRepo;
  private final ExecutionRunCasUpdater runCas;

  /** Result tuple: cost in USD + summed input+output tokens. */
  public record Totals(BigDecimal costUsd, long actualTokens) {
    public static final Totals ZERO = new Totals(BigDecimal.ZERO, 0L);
  }

  /**
   * Compute totals for an execution without persisting. Returns {@link Totals#ZERO} when the
   * execution has no linked pipeline runs (e.g. failed at planner before dispatch).
   */
  public Totals compute(String execRunId) {
    ExecutionRun er = runRepo.findById(execRunId).orElse(null);
    if (er == null) return Totals.ZERO;

    Set<String> pipelineRunIds = collectPipelineRunIds(er, execRunId);
    if (pipelineRunIds.isEmpty()) return Totals.ZERO;

    Object[] agg = pipelineRunRepo.aggregateCostAndTokens(pipelineRunIds);
    if (agg == null) return Totals.ZERO;

    // JPA returns a single Object[] row with 3 columns: [Double cost, Long in, Long out].
    // Defensive coercion — Hibernate can return Integer, Long, or BigDecimal depending on dialect.
    double cost = agg[0] == null ? 0.0d : ((Number) agg[0]).doubleValue();
    long inTok = agg[1] == null ? 0L : ((Number) agg[1]).longValue();
    long outTok = agg[2] == null ? 0L : ((Number) agg[2]).longValue();

    return new Totals(BigDecimal.valueOf(cost), inTok + outTok);
  }

  /**
   * Compute + persist totals onto the {@link ExecutionRun} row via CAS. Idempotent. Logs at INFO
   * when totals actually changed.
   */
  public Totals recomputeAndPersist(String execRunId) {
    Totals fresh = compute(execRunId);
    boolean ok =
        runCas.casUpdate(
            execRunId,
            r -> {
              BigDecimal prevCost =
                  r.getTotalCostUsd() == null ? BigDecimal.ZERO : r.getTotalCostUsd();
              long prevTok = r.getTotalActualTokens() == null ? 0L : r.getTotalActualTokens();
              r.setTotalCostUsd(fresh.costUsd());
              r.setTotalActualTokens(fresh.actualTokens());
              if (fresh.costUsd().compareTo(prevCost) != 0 || fresh.actualTokens() != prevTok) {
                log.info(
                    "[exec={}] cost+tokens recomputed: cost ${}→${}, tokens {}→{}",
                    execRunId,
                    prevCost.toPlainString(),
                    fresh.costUsd().toPlainString(),
                    prevTok,
                    fresh.actualTokens());
              }
            });
    if (!ok) {
      log.warn("[exec={}] recomputeAndPersist: CAS failed after 3 retries (stale row)", execRunId);
    }
    return fresh;
  }

  /**
   * Union of (a) the unified V188 {@code execution_run.pipeline_run_id} when set, and (b) every
   * legacy {@code execution_solution_run.pipeline_run_id} ever attempted. An execution can have
   * BOTH (legacy executions migrated mid-flight); the set semantics dedupe automatically.
   */
  private Set<String> collectPipelineRunIds(ExecutionRun er, String execRunId) {
    Set<String> ids = new HashSet<>();
    if (er.getPipelineRunId() != null && !er.getPipelineRunId().isBlank()) {
      ids.add(er.getPipelineRunId());
    }
    List<String> legacyAttempts = solutionRunRepo.findPipelineRunIdsByExecutionRunId(execRunId);
    for (String id : legacyAttempts) {
      if (id != null && !id.isBlank()) ids.add(id);
    }
    return ids;
  }
}
