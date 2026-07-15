-- v2.5.14 — the LlmProvider enum was renamed GOOGLE → GEMINI in v2.5.13
-- (PR #46/47 of the 3-provider eval rework), but the existing rows in
-- project / execution_run / pipeline_run were never backfilled. JPA
-- @Enumerated(EnumType.STRING) throws IllegalArgumentException on read,
-- surfacing as HTTP 500 on /api/projects and any endpoint that hydrates a
-- PipelineRun owned by an org that once picked Gemini.
--
-- This migration rewrites those legacy rows in-place. Idempotent — no-op if
-- the application has already been serving only 'GEMINI' writes.

UPDATE project
   SET ai_provider = 'GEMINI'
 WHERE ai_provider = 'GOOGLE';

UPDATE execution_run
   SET ai_provider = 'GEMINI'
 WHERE ai_provider = 'GOOGLE';

-- Older runs used a `provider` column; pipeline_run also carries ai_provider
-- in later migrations. Both are TEXT-backed, safe to update blindly.
UPDATE pipeline_run
   SET ai_provider = 'GEMINI'
 WHERE ai_provider = 'GOOGLE';
