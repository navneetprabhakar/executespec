-- v2.5.7 — per-Launch branch override.
-- Null = fall back to each repo's default_branch (current behaviour).
ALTER TABLE execution_run ADD COLUMN branch VARCHAR(255);
