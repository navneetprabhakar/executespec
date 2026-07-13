-- Agent Protocol v2: Web and CLI may retry the same terminal action result after a stream
-- disconnect. Keep one first-writer-wins result per (session, action), while preserving the full
-- append-only history for all non-terminal lifecycle events.
WITH ranked AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY session_id, action_id
               ORDER BY created_at ASC NULLS FIRST, id ASC
           ) AS rn
      FROM agent_action_event
     WHERE type = 'ACTION_RESULT'
       AND action_id IS NOT NULL
)
DELETE FROM agent_action_event e
 USING ranked r
 WHERE e.id = r.id
   AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_action_result_first_writer
    ON agent_action_event (session_id, action_id)
 WHERE type = 'ACTION_RESULT'
   AND action_id IS NOT NULL;
