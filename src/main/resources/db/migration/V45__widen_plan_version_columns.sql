-- Widen plan_version VARCHAR(36) columns to VARCHAR(255) to match other tables.
-- UUID strings are exactly 36 chars; any edge case (non-UUID id, longer source) breaks inserts.
ALTER TABLE plan_version ALTER COLUMN id TYPE VARCHAR(255);
ALTER TABLE plan_version ALTER COLUMN run_id TYPE VARCHAR(255);
ALTER TABLE plan_version ALTER COLUMN created_by TYPE VARCHAR(255);
ALTER TABLE plan_version ALTER COLUMN source TYPE VARCHAR(64);
