-- V191 (PR-B-part-2, 2026-05-13) — reversible archive + 30-day undo soft-delete
-- for projects.
--
-- Background. Project lifecycle was binary: status ACTIVE | ARCHIVED with
-- archive documented as a one-way trip.  Three gaps shipped from this:
--   1. archive carried no timestamp, so a future cleanup sweeper has nothing
--      to gate "old enough to hard-delete" against;
--   2. there was no soft-delete (delete-button-with-undo) for projects at all;
--   3. UI users have asked repeatedly to unarchive a project they archived
--      by mistake. The existing endpoint refused.
--
-- This migration adds the two timestamp columns that let the service layer
-- flip both flows into reversible operations + gives a sweeper job (PR-H)
-- something to query on for hard-delete after the 30/90-day windows lapse.
--
-- Companion changes (this PR):
--   * Project entity gains @Column-mapped archivedAt + deletedAt Instant fields
--   * ProjectService gains unarchiveProject() / softDeleteProject() / restoreProject()
--   * ProjectController exposes PUT /unarchive + DELETE + POST /restore
--   * ProjectAccessGuard is asserted on every new mutation endpoint
--
-- pipeline_run already carries archivedAt + deletedAt from V60 / V60.1 era —
-- no DDL change there; the service layer just gains the matching lifecycle
-- methods.
--
-- Backfill semantic: every legacy status='ARCHIVED' row gets archivedAt
-- stamped from updatedAt (best-effort — there is no per-row audit row
-- for the archive event in older history). NULL on deleted_at on every
-- existing row — nothing was soft-deleted before this migration.

ALTER TABLE project ADD COLUMN archived_at TIMESTAMP;
ALTER TABLE project ADD COLUMN deleted_at TIMESTAMP;

-- Best-effort backfill so the new sweeper job has something to gate on.
-- updated_at is the closest available signal to "when did this transition
-- happen" for legacy archived rows.
UPDATE project
   SET archived_at = updated_at
 WHERE status = 'ARCHIVED'
   AND archived_at IS NULL;

-- Active-project lookups are the hottest path (every dashboard load, every
-- listProjectsForUser). A partial index keeps the working set tiny even as
-- the cumulative archive + tombstone history grows.
CREATE INDEX IF NOT EXISTS idx_project_active
    ON project(org_id)
 WHERE archived_at IS NULL
   AND deleted_at IS NULL;

-- Hard-delete sweeper (PR-H) will scan this index.
CREATE INDEX IF NOT EXISTS idx_project_deleted_at
    ON project(deleted_at)
 WHERE deleted_at IS NOT NULL;
