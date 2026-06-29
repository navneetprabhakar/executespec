ALTER TABLE agent_task
  ADD COLUMN IF NOT EXISTS continuation_actions_json TEXT;
