-- V59: Bitbucket SCM provider support
-- Adds Bitbucket credentials to app_user and SCM-agnostic fields to pipeline_run.

-- ── app_user: Bitbucket credentials (user-level) ─────────────────────────────
ALTER TABLE app_user ADD COLUMN bitbucket_username VARCHAR(255);
ALTER TABLE app_user ADD COLUMN bitbucket_app_password VARCHAR(512);
ALTER TABLE app_user ADD COLUMN bitbucket_workspace VARCHAR(255);
ALTER TABLE app_user ADD COLUMN bitbucket_repo_slug VARCHAR(255);

-- ── pipeline_run: SCM-agnostic PR URL + Bitbucket credentials ────────────────
ALTER TABLE pipeline_run ADD COLUMN scm_pr_url VARCHAR(1024);
ALTER TABLE pipeline_run ADD COLUMN scm_provider VARCHAR(20);
ALTER TABLE pipeline_run ADD COLUMN bitbucket_username VARCHAR(255);
ALTER TABLE pipeline_run ADD COLUMN bitbucket_app_password VARCHAR(512);
ALTER TABLE pipeline_run ADD COLUMN bitbucket_workspace VARCHAR(255);
ALTER TABLE pipeline_run ADD COLUMN bitbucket_repo_slug VARCHAR(255);

-- Backfill scm_pr_url from existing GitHub data
UPDATE pipeline_run SET scm_pr_url = github_pr_url WHERE github_pr_url IS NOT NULL;
UPDATE pipeline_run SET scm_provider = 'GITHUB' WHERE github_pat IS NOT NULL;
