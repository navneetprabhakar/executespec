-- V99 — Backfill nullable execution_run.ai_provider rows added pre-V92.
-- Strategy: inherit project.ai_provider when the run is project-scoped; otherwise
-- default to OPENAI. Project.ai_provider is NOT NULL so the subquery is safe.
--
-- Column stays nullable for forward-compat; the runtime null-guard in
-- V2RunOrchestrator.normalizeProvider logs a WARN and falls back to project
-- default before finally defaulting to OPENAI.

UPDATE execution_run er
SET ai_provider = (
    SELECT p.ai_provider
    FROM project p
    WHERE p.id = er.project_id
)
WHERE er.ai_provider IS NULL
  AND er.project_id IS NOT NULL;

UPDATE execution_run
SET ai_provider = 'OPENAI'
WHERE ai_provider IS NULL;
