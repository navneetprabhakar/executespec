-- RL-2026-07-008: durable, monotonic cursors for agent-session event replay.
-- A database sequence keeps cursors monotonic across JVMs and leaves historical event
-- ordering intact while making reconnect/backfill deterministic.
CREATE SEQUENCE IF NOT EXISTS agent_action_event_stream_seq;

ALTER TABLE agent_action_event
    ADD COLUMN IF NOT EXISTS stream_sequence BIGINT;

WITH ranked AS (
    SELECT id, nextval('agent_action_event_stream_seq') AS cursor
    FROM agent_action_event
    WHERE stream_sequence IS NULL
    ORDER BY created_at, id
)
UPDATE agent_action_event e
SET stream_sequence = ranked.cursor
FROM ranked
WHERE e.id = ranked.id;

ALTER TABLE agent_action_event
    ALTER COLUMN stream_sequence SET DEFAULT nextval('agent_action_event_stream_seq'),
    ALTER COLUMN stream_sequence SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_action_event_stream_sequence
    ON agent_action_event(stream_sequence);

CREATE INDEX IF NOT EXISTS idx_agent_action_event_session_sequence
    ON agent_action_event(session_id, stream_sequence);
