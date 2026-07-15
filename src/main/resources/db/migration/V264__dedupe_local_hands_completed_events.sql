-- Local-hands result delivery is retried by clients and can race across JVMs. The append-only
-- stream permits several lifecycle states for one call, but it must have at most one terminal
-- COMPLETED event. Keep the earliest durable result before adding the first-writer-wins index.
WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY session_id, action_id
           ORDER BY stream_sequence NULLS LAST, created_at, id
         ) AS duplicate_rank
  FROM agent_action_event
  WHERE type = 'LOCAL_HANDS_COMPLETED' AND action_id IS NOT NULL
)
DELETE FROM agent_action_event event
USING ranked
WHERE event.id = ranked.id AND ranked.duplicate_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_action_event_local_hands_completed
  ON agent_action_event(session_id, action_id)
  WHERE type = 'LOCAL_HANDS_COMPLETED' AND action_id IS NOT NULL;
