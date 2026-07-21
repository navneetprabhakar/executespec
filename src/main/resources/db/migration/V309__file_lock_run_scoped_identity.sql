-- RR-1544 — file_lock identity is (run_id, file_path), not the bare relative path.
--
-- V1 made `file_path` the sole PRIMARY KEY. Because every run uses workspace-RELATIVE paths, two
-- concurrent runs — including runs belonging to different orgs — collided on ordinary paths such as
-- `src/index.ts`: the second claim was refused by an unrelated tenant's lock, the row was silently
-- reattributed to the newer run, `getOwner` disclosed a foreign run's taskId, and `releaseAll(runId)`
-- then deleted rows that run did not own.
--
-- file_lock holds transient in-flight worker coordination state only; it carries no customer content
-- and is rebuilt by the running pipeline. Rows with a NULL run_id cannot be addressed under the new
-- key at all, so they are removed rather than backfilled with a synthetic identity.

DELETE FROM file_lock WHERE run_id IS NULL;

-- Guard against pre-existing duplicates before the composite key is applied. `file_path` was unique
-- under the old PK, so this is a no-op on a consistent database; it keeps the migration safe on a
-- database whose PK was previously dropped by hand.
DELETE FROM file_lock a
      USING file_lock b
      WHERE a.ctid < b.ctid
        AND a.run_id = b.run_id
        AND a.file_path = b.file_path;

ALTER TABLE file_lock ALTER COLUMN run_id SET NOT NULL;

ALTER TABLE file_lock DROP CONSTRAINT IF EXISTS file_lock_pkey;
ALTER TABLE file_lock ADD CONSTRAINT file_lock_pkey PRIMARY KEY (run_id, file_path);

-- releaseAll(runId) / findByRunId remain the hot lookups; the composite PK already leads with
-- run_id, so no additional index is required. task_id lookups stay unindexed as before.
