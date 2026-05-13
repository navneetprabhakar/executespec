-- V189 (2026-05-13, PR-A): repair execution_run.total_cost_usd + total_actual_tokens for every
-- historical row, replacing the values written by the broken per-Solution incrementer.
--
-- Bug history: SolutionExecutionService.accumulateExecutionRunTotals(...) used a CAS retry loop
-- that silently dropped contributions on conflict + only fired on COMPLETE attempts (skipping
-- every PARTIAL + FAILED V2 run that still burned tokens). Live signature on exec 85778e63:
-- rollup reported total_cost_usd=$0.81 while the sum of underlying pipeline_run.actual_cost_usd
-- across all attempts was $5.55 — a 6.8× under-report.
--
-- This migration runs the same SQL aggregate the new ExecutionCostAggregator does in code:
-- collect every pipeline_run_id linked to the execution via either (a) the V188 unified-dispatch
-- ExecutionRun.pipeline_run_id, or (b) any ExecutionSolutionRun attempt across any solution,
-- then write the SUM back onto the execution_run row.
--
-- Idempotent: if the rollup is already correct, the UPDATE is a no-op. Safe to re-run.

UPDATE execution_run er
SET
  total_cost_usd = COALESCE((
    SELECT SUM(pr.actual_cost_usd)
    FROM pipeline_run pr
    WHERE pr.run_id IN (
      SELECT er.pipeline_run_id WHERE er.pipeline_run_id IS NOT NULL
      UNION
      SELECT esr.pipeline_run_id
      FROM execution_solution_run esr
      JOIN execution_solution es ON es.id = esr.solution_id
      WHERE es.execution_run_id = er.id
        AND esr.pipeline_run_id IS NOT NULL
    )
  ), 0),
  total_actual_tokens = COALESCE((
    SELECT SUM(COALESCE(pr.total_input_tokens, 0) + COALESCE(pr.total_output_tokens, 0))
    FROM pipeline_run pr
    WHERE pr.run_id IN (
      SELECT er.pipeline_run_id WHERE er.pipeline_run_id IS NOT NULL
      UNION
      SELECT esr.pipeline_run_id
      FROM execution_solution_run esr
      JOIN execution_solution es ON es.id = esr.solution_id
      WHERE es.execution_run_id = er.id
        AND esr.pipeline_run_id IS NOT NULL
    )
  ), 0);

-- Post-condition: every execution_run.total_cost_usd matches SUM(linked pipeline_run.actual_cost_usd).
-- Verify with the sibling query in ExecutionCostAggregator.compute() — values must agree.
