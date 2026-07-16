-- RL-2026-07-007: retain local-hands intent across JVM restarts.
-- The Redis marker remains a one-shot compatibility signal, but a client-owned run must never
-- silently downgrade to server-side shell execution after a crash or deploy rollover.
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS local_execution_requested BOOLEAN NOT NULL DEFAULT FALSE;
