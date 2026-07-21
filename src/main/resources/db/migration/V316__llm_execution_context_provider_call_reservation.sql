-- RR-321 (2026-07-20) — atomic provider-call reservation for durable LLM execution contexts.
--
-- Before this column, LlmExecutionDispatchGuard authorized a provider call by running three
-- INDEPENDENT read-only aggregates over run_step_usage (call count, token sums, cost sum) and then
-- returning permission. Usage rows are written only AFTER the provider responds, so N concurrent
-- authorizers on the same context each observed the same remaining allowance and were all admitted:
-- the advertised hard ceilings (max_provider_calls / max_*_tokens / max_cost_usd) were advisory
-- under any concurrency, including the router's own parallel read batches, retries and failover.
--
-- reserved_provider_calls is an append-only count of AUTHORIZED dispatches, incremented inside the
-- same locked transaction that evaluates the budget. It is a tighten-only upper bound on recorded
-- usage: an authorized call either produces a usage row or fails, and a failed attempt legitimately
-- consumed its authorization.
ALTER TABLE llm_execution_context
  ADD COLUMN IF NOT EXISTS reserved_provider_calls BIGINT NOT NULL DEFAULT 0;

-- Existing rows: seed the reservation counter from the terminal facts already recorded, so a
-- context created before this migration does not get a second full allowance.
UPDATE llm_execution_context c
SET reserved_provider_calls = COALESCE(
  (SELECT COUNT(*) FROM run_step_usage u WHERE u.llm_execution_context_id = c.id), 0)
WHERE c.reserved_provider_calls = 0;

ALTER TABLE llm_execution_context
  ADD CONSTRAINT llm_execution_context_reserved_calls_nonneg
  CHECK (reserved_provider_calls >= 0);
