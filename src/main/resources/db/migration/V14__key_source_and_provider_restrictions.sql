-- Key source mode and provider restrictions for orgs
ALTER TABLE org ADD COLUMN key_source_mode VARCHAR(20) NOT NULL DEFAULT 'HYBRID';
ALTER TABLE org ADD COLUMN enabled_providers TEXT DEFAULT NULL;

-- Billing flag on pipeline runs
ALTER TABLE pipeline_run ADD COLUMN key_source VARCHAR(20) DEFAULT NULL;
