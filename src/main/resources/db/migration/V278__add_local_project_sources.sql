-- First-class LOCAL project sources use opaque client identifiers and short leases.
-- No filesystem path is accepted or persisted. Existing GitHub contracts remain additive/read-compatible.
ALTER TABLE project_repo
    ALTER COLUMN github_repo_full_name DROP NOT NULL,
    ALTER COLUMN github_owner DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(16) NOT NULL DEFAULT 'SCM',
    ADD COLUMN IF NOT EXISTS scm_provider VARCHAR(24),
    ADD COLUMN IF NOT EXISTS source_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS source_display_name VARCHAR(160),
    ADD COLUMN IF NOT EXISTS local_folder_id UUID,
    ADD COLUMN IF NOT EXISTS local_installation_id UUID,
    ADD COLUMN IF NOT EXISTS source_revision VARCHAR(128),
    ADD COLUMN IF NOT EXISTS source_capabilities JSONB,
    ADD COLUMN IF NOT EXISTS source_metadata JSONB,
    ADD COLUMN IF NOT EXISTS source_last_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS source_lease_expires_at TIMESTAMPTZ;

UPDATE project_repo
SET scm_provider = COALESCE(scm_provider, 'GITHUB'),
    source_key = COALESCE(source_key, github_repo_full_name),
    source_display_name = COALESCE(source_display_name, github_repo_full_name)
WHERE source_type = 'SCM' AND github_repo_full_name IS NOT NULL;

ALTER TABLE project_repo
    ADD CONSTRAINT chk_project_repo_source_type
        CHECK (source_type IN ('SCM', 'LOCAL')),
    ADD CONSTRAINT chk_project_repo_local_identity
        CHECK (source_type <> 'LOCAL'
               OR (scope = 'USER'
                   AND owner_user_id IS NOT NULL
                   AND owner_org_id IS NULL
                   AND local_folder_id IS NOT NULL
                   AND local_installation_id IS NOT NULL
                   AND source_key IS NOT NULL
                   AND scm_provider IS NULL
                   AND github_repo_full_name IS NULL
                   AND github_owner IS NULL));

CREATE UNIQUE INDEX IF NOT EXISTS ux_project_repo_user_local_folder
    ON project_repo(owner_user_id, local_folder_id)
    WHERE source_type = 'LOCAL';

CREATE INDEX IF NOT EXISTS idx_project_repo_local_installation
    ON project_repo(local_installation_id)
    WHERE source_type = 'LOCAL';

CREATE INDEX IF NOT EXISTS idx_project_repo_local_lease
    ON project_repo(source_lease_expires_at)
    WHERE source_type = 'LOCAL';
