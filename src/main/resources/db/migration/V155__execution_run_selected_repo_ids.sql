-- V155 — R-324 Checkpoint 1 (multi-repo "finish the flow" mini-wave).
--
-- Persist the user's launch-time repo selection on the ExecutionRun so
-- (a) the planner can scope its decomposition to those repos, and
-- (b) per-solution V2 dispatches can prefer the selected repo over a
-- silent "first repoMappings entry" fallback when needed.
--
-- Today the orchestrator reads {@code req.repoIds()}, builds an
-- {@code effectiveRepoIds} list (including any new-repo specs), but
-- never writes it to the DB. The selection is dropped on the floor.
-- This migration closes that gap.
--
-- NULL is "no explicit user selection" — every linked project repo is
-- in scope (today's default).  An empty array is the same as NULL.
ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS selected_repo_ids JSONB;

COMMENT ON COLUMN execution_run.selected_repo_ids IS
    'R-324 — JSON array of project_repo IDs the user selected at Launch
     time. NULL/empty means every linked project repo is in scope.';
