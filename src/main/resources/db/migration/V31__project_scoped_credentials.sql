-- ════════════════════════════════════════════════════════════════════════════
-- V31: Project-scoped credentials — different teams/projects can use
--      different GCP projects for deployment.
-- ════════════════════════════════════════════════════════════════════════════

-- 1. Add project_id to deployment_credential (nullable — null means user-level default)
ALTER TABLE deployment_credential ADD COLUMN IF NOT EXISTS project_id VARCHAR(64);

-- 2. Drop the old unique constraint (one credential per user per provider)
ALTER TABLE deployment_credential DROP CONSTRAINT IF EXISTS uq_credential_user_provider;

-- 3. New unique constraint: one credential per user per project per provider
--    (project_id NULL = user-level default credential)
ALTER TABLE deployment_credential
    ADD CONSTRAINT uq_credential_user_project_provider
    UNIQUE (user_id, project_id, cloud_provider);

-- 4. Index for project-level lookups
CREATE INDEX IF NOT EXISTS idx_credential_project ON deployment_credential(project_id);

-- 5. Add deploy permissions to tier_config
ALTER TABLE tier_config ADD COLUMN IF NOT EXISTS can_deploy BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tier_config ADD COLUMN IF NOT EXISTS max_deployments INTEGER NOT NULL DEFAULT 0;

-- Set deploy limits: FREE=0 (no deploy), PRO=5, ENTERPRISE=unlimited (-1)
UPDATE tier_config SET can_deploy = FALSE, max_deployments = 0 WHERE tier = 'FREE';
UPDATE tier_config SET can_deploy = TRUE, max_deployments = 5 WHERE tier = 'PRO';
UPDATE tier_config SET can_deploy = TRUE, max_deployments = -1 WHERE tier = 'ENTERPRISE';
