-- V305 — per-task effective route truth (Adaptive C1, Step 4 / gap G2).
--
-- `agent_task.provider` / `agent_task.model` remain the REQUESTED, tier-resolved route captured
-- from SequentialExecutor.tierConfigFor. They are deliberately NOT redefined: RunTokenAttributor
-- history already lives in those columns and callers read them as "what the executor asked for".
--
-- These new columns carry the PHYSICAL route projected server-side from the canonical
-- run_step_usage ledger, filtered to successful attempts for (run_id, task_id). Cross-provider
-- failover and managed-AI tier locks rebuild the request before dispatch, so the two can
-- legitimately diverge; recording only the requested route fed a wrong quality prior into
-- ModelProviderCandidateService.
--
-- All nullable: historical rows predate the ledger projection and must stay honestly unattributed
-- rather than being backfilled with a requested route we cannot prove served the task.

ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS effective_provider VARCHAR(40),
    ADD COLUMN IF NOT EXISTS effective_model VARCHAR(128),
    ADD COLUMN IF NOT EXISTS effective_route_attribution VARCHAR(24);

-- A MIXED task is multi-route by definition. Storing a single provider/model against MIXED would
-- collapse exactly the case this projection exists to make visible, so the database refuses it.
ALTER TABLE agent_task
    DROP CONSTRAINT IF EXISTS agent_task_effective_route_attribution_chk;

ALTER TABLE agent_task
    ADD CONSTRAINT agent_task_effective_route_attribution_chk CHECK (
        effective_route_attribution IS NULL
        OR (
            effective_route_attribution = 'LEDGER'
            AND effective_provider IS NOT NULL
            AND effective_model IS NOT NULL
        )
        OR (
            effective_route_attribution = 'MIXED'
            AND effective_provider IS NULL
            AND effective_model IS NULL
        )
        OR effective_route_attribution = 'UNATTRIBUTED'
    );

COMMENT ON COLUMN agent_task.effective_provider IS
    'V305 — physical provider projected from run_step_usage successful attempts. NULL when MIXED or UNATTRIBUTED.';
COMMENT ON COLUMN agent_task.effective_model IS
    'V305 — physical model projected from run_step_usage successful attempts. NULL when MIXED or UNATTRIBUTED.';
COMMENT ON COLUMN agent_task.effective_route_attribution IS
    'V305 — LEDGER | MIXED | UNATTRIBUTED. MIXED is never collapsed to a single provider.';
