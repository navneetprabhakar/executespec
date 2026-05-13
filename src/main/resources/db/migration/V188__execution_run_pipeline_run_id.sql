-- V188 (2026-05-13): unify V3 execution onto V2 pipeline run.
--
-- Before this migration, every V3 ExecutionRun dispatched one V2 PipelineRun
-- *per ExecutionSolution* (10 V2 runs for a 10-solution launch). Net effect: the
-- V2 hierarchical planner (LARGE/LEGACY + TaskExpanderAgent + CoherencePlannerAgent)
-- never got to decompose multi-file work because each Solution arrived at the V2
-- classifier as a small focused spec and got tagged SMALL/MEDIUM = single-task.
-- Workers couldn't converge on multi-controller edits, ran out of iters, PARTIAL'd,
-- and the retry loop wasted tokens producing identical code.
--
-- After this migration: one V3 execution dispatches ONE V2 run with the full
-- requirement + all linked repos. V2's existing multi-repo plumbing
-- (ScmPushService.pushMultiRepo) opens per-repo PRs; V2's hierarchical planner
-- handles per-file task decomposition. The V3 surface keeps the arch-review +
-- plan-approval gates on top.
--
-- The new column points at the canonical V2 pipeline_run that drives execution.
-- Nullable so legacy rows (Solution-dispatched executions) keep their current
-- shape; new dispatches populate it.
ALTER TABLE execution_run ADD COLUMN pipeline_run_id VARCHAR(64);
COMMENT ON COLUMN execution_run.pipeline_run_id IS
  '2026-05-13 — links V3 ExecutionRun to the single V2 pipeline_run that drives '
  'execution after the unified-dispatch rewrite. NULL for legacy rows that '
  'fanned out into per-Solution V2 runs.';
