-- Stores the resolved ui_style configuration for a run (JSON).
-- Propagated from spec to all downstream frontend tasks.
ALTER TABLE pipeline_run ADD COLUMN ui_style TEXT;
