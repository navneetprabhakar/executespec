-- V2__add_tenant_id.sql
-- Adds tenant_id to tenant-scoped tables.
-- model_pricing, prompt_audit_entry, compaction_event remain global (shared across tenants).

ALTER TABLE pipeline_run         ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE agent_task           ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE agent_message        ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE run_recovery         ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE gate_event           ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE deployment           ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE run_repo             ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE integration_run      ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE integration_event_log ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- Indexes for tenant-scoped queries (fast per-tenant lookups)
CREATE INDEX IF NOT EXISTS idx_pipeline_run_tenant       ON pipeline_run(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_task_tenant         ON agent_task(tenant_id);
CREATE INDEX IF NOT EXISTS idx_deployment_tenant         ON deployment(tenant_id);
CREATE INDEX IF NOT EXISTS idx_integration_run_tenant   ON integration_run(tenant_id);
