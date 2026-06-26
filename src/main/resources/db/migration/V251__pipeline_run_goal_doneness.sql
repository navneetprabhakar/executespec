ALTER TABLE pipeline_run
  ADD COLUMN IF NOT EXISTS run_goal_doneness_json TEXT;
