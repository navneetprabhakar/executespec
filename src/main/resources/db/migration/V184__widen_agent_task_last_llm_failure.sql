-- V184 — widen agent_task.last_llm_failure from VARCHAR(255) to TEXT.
--
-- Root cause confirmed by VM journalctl on dev run 7483896a (2026-05-10).
-- Three of four problem tasks (T12 FAILED, T17 PARTIAL, T19 PARTIAL) hit:
--
--     ERROR: value too long for type character varying(255)
--     [update agent_task set ..., last_llm_failure=?, ... where task_id=?]
--
-- The overflow is a routine consequence of the soft-completion path in
-- SequentialExecutor.executeOne (line ~513): when the worker stops
-- without calling completeTask but writes files, the executor falls
-- through to the smoke gate and stamps a `softWarnReason` of the form
--
--   "Worker stopped without completion but wrote N files — "
--     + describeWorkerFailure(primary)
--
-- For Outcome.STOPPED_WITHOUT_COMPLETE, describeWorkerFailure includes a
-- 240-char `finalAssistantText` tail, so the full reason can hit ~390
-- chars. That's the lastLlmFailure value being persisted, and the
-- column is VARCHAR(255).
--
-- Effect of the original bug: the COMPLETE/PARTIAL flip never landed in
-- the DB; persistTaskStatus's `try { saveAndFlush } catch { log.warn }`
-- swallowed the exception. The orphaned WORKING row was then resolved
-- to FAILED/PARTIAL by the run-terminal resolver, and the user saw a
-- task that "looked failed" even though the worker had actually
-- completed it.
--
-- TEXT is the right type here. The other String columns on agent_task
-- that hold worker-generated content (taskJson, workerReportJson,
-- completionNotes, meetsRequirementsJson) are already columnDefinition
-- = "TEXT". This was an oversight — `lastLlmFailure` was added later
-- without the explicit annotation and inherited Hibernate's VARCHAR(255)
-- default.
--
-- A companion defensive truncation lands in SequentialExecutor's
-- persistTaskStatus path (cap at 4000 chars before save) so the
-- column never grows unbounded even after the widening.
--
-- Idempotent: re-running this on a TEXT column is a no-op (Postgres
-- ignores ALTER TYPE TEXT when already TEXT).

ALTER TABLE agent_task
  ALTER COLUMN last_llm_failure TYPE TEXT;

COMMENT ON COLUMN agent_task.last_llm_failure IS
  'Worker premature-exit reason or LlmFailure type. Up to 4000 chars '
  '(soft cap in SequentialExecutor.persistTaskStatus); column itself '
  'is unbounded TEXT to absorb router-error messages without ever '
  'hitting a length constraint.';
