-- =====================================================================
-- V249: Seed execution.goal-decision.enforcement.enabled = false.
--
-- RL-2026-06-198 — goal-doneness / waste-guard continuation control.
--
-- SequentialExecutor now attaches the conservative GoalDonenessEvaluator
-- decision to each terminal TaskOutcome. When this flag is ON, negative
-- stop decisions (STOP_BLOCKED / STOP_FAILED / STOP_WASTE_GUARD) abort
-- the remaining sequential tasks at the next task boundary and mark the
-- aggregate execution result ABORTED.
--
-- Why default OFF: the evaluator is intentionally conservative, and this
-- is the first runtime enforcement hook after the observability-only
-- slice. Operators can enable it after watching the emitted decision logs
-- on real runs.
--
-- STOP_DONE remains informational at this stage; proving a per-task goal
-- is done is not the same as proving an entire multi-task plan is complete.
-- =====================================================================

INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'execution.goal-decision.enforcement.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Enforce conservative goal-doneness stop decisions at SequentialExecutor task boundaries. Default OFF.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
