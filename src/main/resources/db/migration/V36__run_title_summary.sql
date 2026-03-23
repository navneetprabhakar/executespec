-- Add run_title and run_summary columns for RunCard display
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS run_title VARCHAR(200);
ALTER TABLE pipeline_run ADD COLUMN IF NOT EXISTS run_summary VARCHAR(500);

-- Backfill run_summary from existing plan_json (plan_title won't exist in old plans)
UPDATE pipeline_run
SET run_summary = plan_json::jsonb ->> 'plan_summary'
WHERE plan_json IS NOT NULL
  AND plan_json != ''
  AND run_summary IS NULL;
