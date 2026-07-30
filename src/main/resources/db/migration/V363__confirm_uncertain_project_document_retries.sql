ALTER TABLE project_generated_doc_job
  DROP CONSTRAINT project_generated_doc_job_status_ck,
  DROP CONSTRAINT project_generated_doc_job_terminal_ck;

ALTER TABLE project_generated_doc_job
  ALTER COLUMN status TYPE VARCHAR(32),
  ADD COLUMN provider_started_at TIMESTAMPTZ,
  ADD CONSTRAINT project_generated_doc_job_status_ck
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','NEEDS_CONFIRMATION')),
  ADD CONSTRAINT project_generated_doc_job_terminal_ck CHECK (
    (status IN ('QUEUED','RUNNING') AND completed_at IS NULL)
    OR
    (status IN ('SUCCEEDED','FAILED','NEEDS_CONFIRMATION') AND completed_at IS NOT NULL));

DROP INDEX uq_project_generated_doc_job_active;
CREATE UNIQUE INDEX uq_project_generated_doc_job_active
  ON project_generated_doc_job(project_id, doc_type)
  WHERE status IN ('QUEUED','RUNNING','NEEDS_CONFIRMATION');

CREATE TABLE project_generated_doc_job_retry_confirmation (
  id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES project_generated_doc_job(id) ON DELETE CASCADE,
  project_id VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  confirmed_by_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
  confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_project_generated_doc_retry_confirmation_job
  ON project_generated_doc_job_retry_confirmation(job_id, confirmed_at);

COMMENT ON COLUMN project_generated_doc_job.provider_started_at IS
  'Durable fence proving that provider-bearing document execution may have started.';
COMMENT ON TABLE project_generated_doc_job_retry_confirmation IS
  'Immutable user confirmation before replaying an uncertain generated-document operation.';
