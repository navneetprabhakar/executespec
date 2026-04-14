-- V81 — Pilot R1/R2 foundation.
--
-- 1. Project settings: per-project git workflow defaults (branch naming,
--    target branch, default reviewers) — used by R5 multi-PR creation.
--
-- 2. project_repo: dual-scope repo registry. A repo can be owned at ORG
--    (enterprise — shared across every project in the org, managed by
--    ORG_ADMIN) or USER (personal — private to the linking user) scope.
--    The CHECK constraint enforces exactly one owner column populated.
--
-- 3. project_repo_link: many-to-many between projects and repos with
--    per-link relationship metadata. A project may link to ORG repos in
--    its own org or USER repos owned by any project member.

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS branch_naming_pattern VARCHAR(255) DEFAULT 'executespec/{runId}/{description}';

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS target_branch VARCHAR(100) DEFAULT 'main';

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS default_reviewers JSONB;

CREATE TABLE IF NOT EXISTS project_repo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope VARCHAR(8) NOT NULL,
    owner_org_id VARCHAR(255),
    owner_user_id VARCHAR(255),
    github_repo_full_name VARCHAR(255) NOT NULL,
    github_owner VARCHAR(255) NOT NULL,
    default_branch VARCHAR(100) DEFAULT 'main',
    last_synced_sha VARCHAR(40),
    detected_stack JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_project_repo_scope CHECK (scope IN ('ORG', 'USER')),
    CONSTRAINT chk_project_repo_owner CHECK (
        (scope = 'ORG' AND owner_org_id IS NOT NULL AND owner_user_id IS NULL)
        OR
        (scope = 'USER' AND owner_user_id IS NOT NULL AND owner_org_id IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_repo_org_name
    ON project_repo(owner_org_id, github_repo_full_name)
    WHERE scope = 'ORG';

CREATE UNIQUE INDEX IF NOT EXISTS uq_project_repo_user_name
    ON project_repo(owner_user_id, github_repo_full_name)
    WHERE scope = 'USER';

CREATE INDEX IF NOT EXISTS idx_project_repo_owner_org ON project_repo(owner_org_id);
CREATE INDEX IF NOT EXISTS idx_project_repo_owner_user ON project_repo(owner_user_id);

CREATE TABLE IF NOT EXISTS project_repo_link (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id VARCHAR(255) NOT NULL,
    repo_id UUID NOT NULL REFERENCES project_repo(id) ON DELETE CASCADE,
    relationships JSONB,
    linked_at TIMESTAMP NOT NULL DEFAULT NOW(),
    linked_by VARCHAR(255) NOT NULL,
    CONSTRAINT uq_project_repo_link UNIQUE (project_id, repo_id)
);

CREATE INDEX IF NOT EXISTS idx_project_repo_link_project ON project_repo_link(project_id);
CREATE INDEX IF NOT EXISTS idx_project_repo_link_repo ON project_repo_link(repo_id);
