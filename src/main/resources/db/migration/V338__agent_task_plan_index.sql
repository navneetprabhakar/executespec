-- RR-1446 — persist the planner's task order so the approval gate shows the intended execution
-- sequence. Tasks were saved in plan-iteration order but stored no index; the repository lookup was
-- unordered and the mapper sorted only by status. At the plan gate every task is PENDING, so the
-- status sort was a no-op and the displayed sequence fell back to the unspecified database row
-- order — a human could approve an order different from the planner's intent/dependencies.
--
-- Additive, nullable column only. plan_index is the 0-based position of the task within its plan,
-- captured on the write path. Legacy rows stay NULL and sort last (status remains a tiebreaker).
ALTER TABLE agent_task
  ADD COLUMN IF NOT EXISTS plan_index INTEGER;

COMMENT ON COLUMN agent_task.plan_index IS
  'Zero-based planner-intent order of this task within its plan; used as the primary DTO sort key '
  'so status is decoration only. NULL for legacy/append rows, which sort after indexed tasks.';
