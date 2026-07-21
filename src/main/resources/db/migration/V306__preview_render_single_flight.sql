-- V306: Single-flight for preview render jobs.
--
-- V302 created preview_render_job with an active-state lifecycle but no uniqueness on it, so
-- single-flight rested on a read-then-write in application code. Two concurrent requests could both
-- observe no active job and both start a render, spending managed AI credits twice for one run.
--
-- The guarantee has to live in the database: an in-JVM lock is not a guarantee across instances.
-- The partial unique index below makes "at most one unfinished render per run" a schema fact. The
-- loser of a race gets a constraint violation and is handed the winner's job.
--
-- Terminal rows (READY / FAILED / NO_SURFACES) are deliberately outside the index, so a run can be
-- re-rendered any number of times once its previous render has finished.

-- Defensive: this prototype ships with the flag off, so in practice there are no rows. If an
-- environment somehow holds duplicate unfinished jobs, terminalize all but the newest per run
-- rather than failing the migration, and record why.
UPDATE preview_render_job j
   SET status = 'FAILED',
       failure_reason = 'SUPERSEDED_DUPLICATE',
       completed_at = NOW(),
       lease_owner = NULL,
       lease_expires_at = NULL
 WHERE j.status IN ('QUEUED', 'RENDERING')
   AND EXISTS (
       SELECT 1 FROM preview_render_job newer
        WHERE newer.run_id = j.run_id
          AND newer.status IN ('QUEUED', 'RENDERING')
          AND (newer.requested_at, newer.id) > (j.requested_at, j.id)
   );

CREATE UNIQUE INDEX IF NOT EXISTS uq_preview_render_job_active_run
    ON preview_render_job (run_id)
    WHERE status IN ('QUEUED', 'RENDERING');

-- Supports the lease sweeper's scan for renders abandoned by a dead JVM.
CREATE INDEX IF NOT EXISTS idx_preview_render_job_lease
    ON preview_render_job (lease_expires_at)
    WHERE status IN ('QUEUED', 'RENDERING');
