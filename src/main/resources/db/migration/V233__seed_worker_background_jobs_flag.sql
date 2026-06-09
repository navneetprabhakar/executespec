-- =====================================================================
-- V233: Seed worker.background-jobs.enabled = false (DEFAULT OFF).
--
-- W4.2 (P6, vix-leverage) — provider-agnostic background command jobs. The
-- worker can start a long-running shell job (a server, a slow build/test) via
-- the `backgroundJob` tool (action=start) and get a jobId back immediately, then
-- poll status / tail logs / kill it (action=status|logs|kill) — instead of
-- blocking a foreground `shell` call (capped at 300s). Backed by `JobRegistry`
-- (host-side execution, sandbox-validated, combined stdout+stderr to a temp log).
--
-- Schema-safety (RL-2026-05-061): when OFF (this seed row), `ToolRegistry.bind*`
-- does NOT bind `backgroundJob` at all — it is ABSENT from the model's tool
-- schema, not merely inert. A new tool merely PRESENT in the schema can cause
-- reasoning-only stalls on schema-sensitive providers (DeepSeek), so default-OFF
-- keeps the worker tool set byte-identical to today, including the DeepSeek
-- Terminal-Bench path.
--
-- Pre-flip TODO (documented in RL-2026-06-012): before flipping this ON, wire
-- `JobRegistry.cleanup(runId)` at run-end (SequentialExecutor, beside
-- `contextStore.deleteAll`) so an enabled run can't leak a background process;
-- and add container/remote routing for the in-container Terminal-Bench case
-- (host-side jobs are correct only for local product runs).
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'worker.background-jobs.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'Background command jobs (W4.2, vix-leverage P6). When ON, binds the `backgroundJob` worker tool (start/status/logs/kill a long-running shell job via JobRegistry). When OFF (default), the tool is NOT bound — absent from the model schema (RL-2026-05-061 schema-safety). Pre-flip: wire JobRegistry.cleanup at run-end + container/remote routing.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET flag_value = EXCLUDED.flag_value,
        description = EXCLUDED.description;
