-- V304: per-attempt physical route attribution for benchmark task results.
--
-- benchmark_task_result previously carried no provider/model at all: the only route identity lived
-- on the parent benchmark_run and was supplied by the CLIENT harness at POST /start. That made
-- every campaign artifact report the REQUESTED route even when cross-provider failover, a
-- managed-AI tier lock or a model step-down caused a different physical provider to serve the
-- attempt -- which invalidates any cross-provider comparison built on those numbers.
--
-- These columns are derived SERVER-SIDE only, from the canonical run_step_usage physical-attempt
-- ledger via benchmark_task_result.es_run_id. The client event contract deliberately gains no route
-- fields: a harness-supplied route is exactly the untrustworthy input this column set exists to
-- eliminate.
--
-- All three are nullable because historical rows have no derivable attribution, and because a
-- MIXED attempt intentionally leaves provider/model NULL rather than collapsing a multi-route
-- attempt to one misleading provider.
--
--   route_attribution = 'LEDGER'       exactly one successful (provider, model) served the attempt
--                     = 'MIXED'        more than one distinct successful route served the attempt
--                     = 'UNATTRIBUTED' no successful physical attempt is recorded

ALTER TABLE benchmark_task_result
    ADD COLUMN IF NOT EXISTS provider VARCHAR(40),
    ADD COLUMN IF NOT EXISTS model VARCHAR(120),
    ADD COLUMN IF NOT EXISTS route_attribution VARCHAR(24);

-- Structural backstop so a future "simplification" cannot store a single provider on a MIXED
-- attempt, which would defeat the attribution exactly where it matters most.
ALTER TABLE benchmark_task_result
    DROP CONSTRAINT IF EXISTS ck_benchmark_task_route_attribution;

ALTER TABLE benchmark_task_result
    ADD CONSTRAINT ck_benchmark_task_route_attribution CHECK (
        route_attribution IS NULL
        OR (route_attribution = 'LEDGER' AND provider IS NOT NULL AND model IS NOT NULL)
        OR (route_attribution IN ('MIXED', 'UNATTRIBUTED') AND provider IS NULL AND model IS NULL)
    );
