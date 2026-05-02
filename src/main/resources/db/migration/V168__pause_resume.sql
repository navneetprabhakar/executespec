-- =====================================================================
-- V168: Pause / resume support for V2 runs.
--
-- Background: V1 had a working pause + resume path; V2's run orchestrator
-- (March 2026 cutover) deleted the path entirely and the public endpoints
-- have returned HTTP 410 Gone since. CLAUDE.md tracks this as a known
-- gap "blocked on V2 full-replay recovery". 2026-05-02 declares it a
-- shipping blocker; this migration is the first move toward a complete
-- implementation (chunks 1+2+4+5+6+7 per the plan; chunk 3 — cross-JVM
-- workspace shipping to GCS — is deferred).
--
-- Schema changes:
--
--   1. pipeline_run.pause_requested BOOLEAN NOT NULL DEFAULT FALSE
--      Set by RunController.pauseRun. Read by SequentialExecutorV2 +
--      WorkerAgentV2 at every task / iteration boundary. When TRUE the
--      executor persists a checkpoint and transitions the run to PAUSED
--      cleanly (no orphaned futures, no partial commits).
--
--   2. pipeline_run.paused_at TIMESTAMP NULL
--      Wall-clock timestamp the executor wrote when it observed the
--      pause request. Used by StartupRunResumerV2 to bound how stale a
--      paused run can be before it's considered abandoned (>24h ⇒
--      treat as a stalled checkpoint and FAIL with a clear reason).
--
--   3. pipeline_run.resumed_at TIMESTAMP NULL
--      Last resume timestamp. Multiple resumes are allowed; this column
--      tracks the most recent. Useful for support diagnostics.
--
--   4. pipeline_run.paused_task_id VARCHAR(64) NULL
--      The agent_task ID the worker was on when pause took effect. Lets
--      resume restart the executor at the right task index.
--
--   5. pipeline_run.paused_at_iteration INT NULL
--      Worker iteration count within the paused task. Used together with
--      ContextStoreV2's per-(runId,taskId) snapshot so resume picks up
--      from the exact iteration the worker was on, not the start of the
--      task.
--
--   6. pipeline_run.pause_count INT NOT NULL DEFAULT 0
--      Hard cap on number of pauses per run. Keeps an adversarial /
--      buggy client from pause-spamming a run forever. Default cap is
--      enforced at the controller (configurable via feature flag —
--      v2.pause.max_pauses_per_run, default 10).
--
-- Status machine impact:
--   The PAUSED status is a new non-terminal state. is_terminal() in
--   V2RunOrchestrator stays false for it; isTerminal()-gated paths (like
--   broadcaster.complete + cancel + halt-all) skip PAUSED unless the
--   caller explicitly intends a terminal flip.
--
-- Idempotent: every column is added with IF NOT EXISTS so re-running
-- the migration on a partially-applied DB is a no-op.
-- =====================================================================

ALTER TABLE pipeline_run
  ADD COLUMN IF NOT EXISTS pause_requested      BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS paused_at            TIMESTAMP   NULL,
  ADD COLUMN IF NOT EXISTS resumed_at           TIMESTAMP   NULL,
  ADD COLUMN IF NOT EXISTS paused_task_id       VARCHAR(64) NULL,
  ADD COLUMN IF NOT EXISTS paused_at_iteration  INTEGER     NULL,
  ADD COLUMN IF NOT EXISTS pause_count          INTEGER     NOT NULL DEFAULT 0;

-- Hot-path index: support tooling + dashboards filter by `status = 'PAUSED'`
-- to surface stuck-checkpoint runs to operators. Partial index keeps the
-- index tiny (most rows are terminal). Auto-resume-on-boot is intentionally
-- NOT supported — paused runs always require a deliberate human click after
-- a deploy, both because the workspace state could be stale + because
-- silently re-running long jobs after a restart is its own footgun.
CREATE INDEX IF NOT EXISTS idx_pipeline_run_paused
  ON pipeline_run(paused_at)
  WHERE status = 'PAUSED';

-- Feature flag (single — auto-resume-on-boot was scoped out). Defaults OFF
-- so existing behaviour is preserved on first deploy; ops flips it on after
-- smoke-verifying pause/resume on dev. Schema mirrors V124 / V128 / V150 —
-- flag_key (VARCHAR), flag_value (VARCHAR — boolean stored as 'true'/'false'),
-- value_type ('BOOLEAN'), scope ('PLATFORM'), description.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES
  ('v2.pause.enabled',
   'false',
   'BOOLEAN',
   'PLATFORM',
   'V2 pause/resume endpoints. When OFF, /api/runs/{id}/pause + /resume return 503 with the flag name. When ON, the endpoints accept requests from runs in WORKING / REVIEWING and PAUSED statuses respectively. Resume is same-JVM-only today; cross-JVM resume requires GCS workspace shipping (deferred follow-up).')
ON CONFLICT (flag_key) DO NOTHING;
