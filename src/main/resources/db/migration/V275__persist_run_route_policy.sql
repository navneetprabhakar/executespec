-- Persist the caller's route contract so restart, retry, reviewer, chat and background work cannot
-- silently fall back to a different provider/model after the submit thread disappears.
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS requested_provider VARCHAR(20);
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS requested_model VARCHAR(120);
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS routing_mode VARCHAR(24) NOT NULL DEFAULT 'PROVIDER_PREFERRED';
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS route_pinned BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE pipeline_run
SET requested_provider = ai_provider
WHERE requested_provider IS NULL AND ai_provider IS NOT NULL;

ALTER TABLE pipeline_run DROP CONSTRAINT IF EXISTS chk_pipeline_run_routing_mode;
ALTER TABLE pipeline_run
  ADD CONSTRAINT chk_pipeline_run_routing_mode
  CHECK (routing_mode IN ('AUTO', 'PROVIDER_PREFERRED', 'EXACT_LOCK'));
