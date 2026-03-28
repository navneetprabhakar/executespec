-- User-selected LLM provider per project. Copied to pipeline_run at submission time
-- so changing the project's provider doesn't affect in-flight runs.
ALTER TABLE project ADD COLUMN ai_provider VARCHAR(20) NOT NULL DEFAULT 'ANTHROPIC';
ALTER TABLE pipeline_run ADD COLUMN ai_provider VARCHAR(20) NOT NULL DEFAULT 'ANTHROPIC';
