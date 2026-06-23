ALTER TABLE agent_action_event
    ADD COLUMN IF NOT EXISTS action_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_agent_action_event_action_created
    ON agent_action_event(session_id, action_id, created_at);
