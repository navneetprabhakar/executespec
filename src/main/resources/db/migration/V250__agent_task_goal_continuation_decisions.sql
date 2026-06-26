ALTER TABLE agent_task
  ADD COLUMN IF NOT EXISTS goal_doneness_json TEXT;

ALTER TABLE agent_task
  ADD COLUMN IF NOT EXISTS continuation_decision_json TEXT;
