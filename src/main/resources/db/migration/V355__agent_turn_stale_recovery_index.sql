-- Bounded fail-closed recovery for agent turns abandoned in STREAMING after a JVM/process loss.
-- The partial index matches the recovery predicate and deterministic oldest-first ordering.
CREATE INDEX IF NOT EXISTS idx_agent_turn_stale_streaming
  ON agent_turn(created_at ASC, id ASC)
  WHERE status = 'STREAMING';
