-- =====================================================================
-- V232: Seed worker.edit-diagnostics.enabled = false (DEFAULT OFF).
--
-- RL-2026-06 (vix-leverage plan W1.1, "B1") — the standout differentiator.
-- After a successful editFile/writeFile, run a fast, scoped syntax check on the
-- touched file and append the first diagnostics to the tool result the model
-- sees, closing the edit -> "here's what you just broke" loop. Directly targets
-- the wrong-half-fix class that dominates the SWE-bench residuals: a broken edit
-- now reports its own syntax error on the same turn instead of surfacing later.
--
-- First cut: Python (`python3 -m py_compile`) — fast, file-scoped,
-- deterministic, zero false positives, and the SWE-bench target language. A
-- single-file `javac` is intentionally NOT done (no project classpath -> noise).
-- Runs IN the run's cleanroom container when one is registered (SWE-bench /
-- ProgramBench) so it uses the task's own Python; host fallback otherwise.
--
-- When OFF (this seed row): EditDiagnosticsService.diagnose() short-circuits to
-- empty -> editFile/writeFile results are byte-identical to today. No subprocess,
-- no behaviour change. Production is unaffected until an eval justifies flipping.
--
-- When ON: every successful .py edit/write triggers one bounded (15s),
-- fail-open syntax check; on a syntax error the first ~6 salient lines
-- (File "...", line N + the *Error: message) are appended to the result.
--
-- Migration note: the plan reserved V232 for worker.compaction.enabled and V233
-- for this flag, but compaction is founder-approval-gated and was not created
-- first; under Flyway's no-out-of-order rule a reserved-but-absent V232 could
-- never be filled after a higher version applied, so B1 takes V232 (next
-- sequential). Compaction re-allocates to the next free version when approved.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.edit-diagnostics.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Auto-inject post-edit syntax diagnostics (B1, vix-leverage W1.1). After a successful editFile/writeFile on a .py file, run python3 -m py_compile (in the run container when present, else host) and append the first syntax error to the tool result. Bounded + fail-open. Default OFF; production byte-identical until flipped.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
