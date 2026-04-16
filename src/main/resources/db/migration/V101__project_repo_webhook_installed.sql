-- V101 — Track whether a PR webhook has been installed on a project_repo.
-- Default FALSE so existing rows show a "Install webhook" button until the user
-- (or an auto-provision on create-new-repo) flips it to TRUE.

ALTER TABLE project_repo
    ADD COLUMN IF NOT EXISTS webhook_installed BOOLEAN NOT NULL DEFAULT FALSE;
