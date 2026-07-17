-- Provider repository UUID/numeric ID is the immutable identity for all new SCM attaches.
-- Legacy rows remain nullable because their slug alone cannot safely be upgraded without a live
-- provider lookup. Clone URLs are public transport coordinates; credentials are never persisted.

ALTER TABLE project_repo
  ADD COLUMN IF NOT EXISTS scm_repository_id VARCHAR(160),
  ADD COLUMN IF NOT EXISTS scm_clone_url TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_repo_org_scm_identity
  ON project_repo(owner_org_id, scm_provider, scm_repository_id)
  WHERE scope = 'ORG' AND scm_repository_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_repo_user_scm_identity
  ON project_repo(owner_user_id, scm_provider, scm_repository_id)
  WHERE scope = 'USER' AND scm_repository_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_project_repo_scm_display_identity
  ON project_repo(scm_provider, source_key);
