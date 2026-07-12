-- A rolling restart can be observed by more than one JVM.  Each observer may try to mark the
-- same in-flight local-hands call WORKER_RESTARTED; keep one durable transition so reconnect
-- clients get a deterministic handoff record and do not see competing restart timestamps.
WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY session_id, action_id
           ORDER BY stream_sequence NULLS LAST, created_at, id
         ) AS duplicate_rank
  FROM agent_action_event
  WHERE type = 'LOCAL_HANDS_WORKER_RESTARTED' AND action_id IS NOT NULL
)
DELETE FROM agent_action_event event
USING ranked
WHERE event.id = ranked.id AND ranked.duplicate_rank > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_action_event_local_hands_worker_restarted
  ON agent_action_event(session_id, action_id)
  WHERE type = 'LOCAL_HANDS_WORKER_RESTARTED' AND action_id IS NOT NULL;
