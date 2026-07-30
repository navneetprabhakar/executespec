-- Persist the route explanation resolved at task-dispatch time.
--
-- provider/model remain the historical requested route columns and effective_provider/
-- effective_model remain the physical-attempt projection. These fields make the tier, semantic
-- task category, and source of the requested selection durable across configuration changes;
-- historical route evidence is never inferred on replay from the current matrix.
ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS routing_tier VARCHAR(24),
    ADD COLUMN IF NOT EXISTS routing_task_category VARCHAR(48),
    ADD COLUMN IF NOT EXISTS routing_source VARCHAR(48);

COMMENT ON COLUMN agent_task.routing_tier IS
    'Resolved worker tier at dispatch: LIGHTWEIGHT, WORKHORSE, or PLANNING.';
COMMENT ON COLUMN agent_task.routing_task_category IS
    'Persisted semantic task category used by routing/continuation policy.';
COMMENT ON COLUMN agent_task.routing_source IS
    'DEFAULT_MATRIX, ORG_OVERRIDE, RUN_OVERRIDE, EVIDENCE_ROUTING, or CONTINUATION_DECISION.';
