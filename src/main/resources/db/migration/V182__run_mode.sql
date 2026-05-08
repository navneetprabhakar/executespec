-- V182 — runMode discriminator on pipeline_run.
--
-- Two pipeline shapes share the V2 orchestrator now:
--
--   BUILD (default) — the existing planner → SequentialExecutor → reviewer flow.
--                     Right shape for greenfield specs and multi-task work.
--
--   PATCH — single thin-agent loop: one conversation, one model, full context.
--           Right shape for bug-fix work where the input is a specific failing
--           test or a targeted edit. Today only the swebench + programbench
--           bench services submit with this mode; product entry points keep
--           the BUILD default. Future work may surface PATCH in the UI.
--
-- Backfill: every existing row defaults to BUILD via the column DEFAULT, so
-- in-flight runs across the rollout window keep their behaviour. Additive only.

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS run_mode VARCHAR(16) NOT NULL DEFAULT 'BUILD';

-- Partial index — most rows are BUILD; PATCH is the rare bench/automation case.
-- Speeds up reporting / debugging queries that filter by mode.
CREATE INDEX IF NOT EXISTS idx_pipeline_run_run_mode_patch
    ON pipeline_run (run_mode)
    WHERE run_mode <> 'BUILD';
