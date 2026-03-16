-- ════════════════════════════════════════════════════════════════════════════
-- V30: Deploy Engine — credential storage, deployment path support, analyzer
-- ════════════════════════════════════════════════════════════════════════════

-- ── 1. Per-user GCP credentials (AES-256 encrypted at rest) ─────────────────
CREATE TABLE IF NOT EXISTS deployment_credential (
    id                  VARCHAR(64)     PRIMARY KEY,
    user_id             VARCHAR(64)     NOT NULL,
    cloud_provider      VARCHAR(10)     NOT NULL DEFAULT 'GCP',
    credential_type     VARCHAR(20)     NOT NULL DEFAULT 'SERVICE_ACCOUNT_KEY',
    encrypted_data      BYTEA           NOT NULL,
    gcp_project_id      VARCHAR(100),
    service_account_email VARCHAR(200),
    validated_at        TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_credential_user_provider UNIQUE (user_id, cloud_provider)
);

CREATE INDEX idx_credential_user ON deployment_credential(user_id);

-- ── 2. Extend deployment table for deploy engine (3 paths) ──────────────────
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS deployment_path VARCHAR(30);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS credential_id VARCHAR(64);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS manifest_json JSONB;
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS user_id VARCHAR(64);

-- Path B (Compose on VM)
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS vm_name VARCHAR(200);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS vm_zone VARCHAR(50);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS vm_external_ip VARCHAR(50);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS vm_machine_type VARCHAR(50);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS compose_services JSONB;

-- Path C (Terraform)
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS terraform_state_bucket VARCHAR(200);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS terraform_outputs JSONB;

-- Lifecycle
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS auto_destroy_at TIMESTAMP;
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS health_check_path VARCHAR(200);
ALTER TABLE deployment ADD COLUMN IF NOT EXISTS app_port INT;

-- FK to credential
ALTER TABLE deployment ADD CONSTRAINT fk_deployment_credential
    FOREIGN KEY (credential_id) REFERENCES deployment_credential(id)
    ON DELETE SET NULL;

CREATE INDEX idx_deployment_user ON deployment(user_id);
CREATE INDEX idx_deployment_auto_destroy ON deployment(auto_destroy_at)
    WHERE auto_destroy_at IS NOT NULL AND status = 'LIVE';
