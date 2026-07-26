-- Reconcile historical run summaries from the immutable physical-attempt ledger.
--
-- Planning calls can finish before a run parks at AWAITING_APPROVAL, and cancellation can race
-- an in-flight provider response. Older application versions persisted pipeline_run usage only
-- at later phase/terminal boundaries, leaving actual_cost_usd NULL or below already-recorded
-- physical spend. Current code raises this floor before the plan gate and after terminal races;
-- this migration repairs existing rows with the same raise-only semantics.
WITH usage_floor AS (
    SELECT
        run_id,
        LEAST(
            COALESCE(SUM(input_tokens) FILTER (WHERE attempt_status = 'SUCCESS'), 0),
            2147483647
        )::integer AS input_tokens,
        LEAST(
            COALESCE(SUM(output_tokens) FILTER (WHERE attempt_status = 'SUCCESS'), 0),
            2147483647
        )::integer AS output_tokens,
        COALESCE(
            SUM(cost_usd) FILTER (
                WHERE attempt_status = 'SUCCESS'
                  AND NOT (
                      cost_usd = 0
                      AND (input_tokens > 0 OR output_tokens > 0)
                  )
            ),
            0
        )::double precision AS known_cost_usd
    FROM run_step_usage
    WHERE run_id IS NOT NULL
    GROUP BY run_id
)
UPDATE pipeline_run AS run
SET
    total_input_tokens = GREATEST(COALESCE(run.total_input_tokens, 0), usage.input_tokens),
    total_output_tokens = GREATEST(COALESCE(run.total_output_tokens, 0), usage.output_tokens),
    actual_cost_usd = GREATEST(COALESCE(run.actual_cost_usd, 0), usage.known_cost_usd)
FROM usage_floor AS usage
WHERE run.run_id = usage.run_id
  AND (
      COALESCE(run.total_input_tokens, 0) < usage.input_tokens
      OR COALESCE(run.total_output_tokens, 0) < usage.output_tokens
      OR run.actual_cost_usd IS NULL
      OR run.actual_cost_usd < usage.known_cost_usd
  );
