-- RCA-15: bind generated-test evidence to the admitted source and exact product diff.
ALTER TABLE test_plan ADD COLUMN IF NOT EXISTS source_fingerprint VARCHAR(64);
ALTER TABLE test_plan ADD COLUMN IF NOT EXISTS workspace_fingerprint VARCHAR(64);
ALTER TABLE test_plan ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(32);
