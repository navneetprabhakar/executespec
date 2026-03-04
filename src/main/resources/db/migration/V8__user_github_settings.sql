-- V8: Per-user GitHub credentials
-- Allows each user to store their own GitHub PAT + owner (username or org)
-- so run output is pushed to their personal GitHub after merge.
-- Also captures these on pipeline_run at submission time for immutability.

ALTER TABLE app_user
    ADD COLUMN IF NOT EXISTS github_pat   VARCHAR(255) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS github_owner VARCHAR(100) DEFAULT NULL;

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS github_pat   VARCHAR(255) DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS github_owner VARCHAR(100) DEFAULT NULL;
