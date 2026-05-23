-- V208 (2026-05-23) — Widen github_pat from VARCHAR(255) to TEXT.
--
-- Why: GitHub is moving installation tokens to a longer stateless format
-- (ghs_…, ~520 chars) and the broader trend is toward longer tokens / PATs.
-- The github_pat column (added in V8 as VARCHAR(255)) holds the caller's
-- GitHub PAT; 255 is a brittle "hardcoded length assumption" and is
-- inconsistent with every other credential column (AI keys, jira/confluence
-- tokens, slack bot_token, openrouter key, gcs key — all already TEXT).
-- TEXT removes the cap whether the value is stored plaintext or encrypted
-- (encryption expands the stored length, so 255 is tighter than it looks).
--
-- Safe + non-destructive: VARCHAR(255) -> TEXT is a metadata-only change in
-- PostgreSQL (no table rewrite, no truncation). Idempotent on re-apply
-- (ALTER … TYPE TEXT on an already-TEXT column is a no-op).

ALTER TABLE app_user     ALTER COLUMN github_pat TYPE TEXT;
ALTER TABLE pipeline_run ALTER COLUMN github_pat TYPE TEXT;
