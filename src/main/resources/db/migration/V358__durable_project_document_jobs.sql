CREATE TABLE project_generated_doc_job (
  id UUID PRIMARY KEY,
  project_id VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  org_id VARCHAR(255) NOT NULL REFERENCES org(id) ON DELETE CASCADE,
  requested_by_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
  doc_type VARCHAR(32) NOT NULL,
  requested_provider VARCHAR(32) NOT NULL,
  requested_model VARCHAR(160) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  deadline_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '30 minutes'),
  lease_owner VARCHAR(255),
  lease_token BIGINT NOT NULL DEFAULT 0,
  lease_expires_at TIMESTAMPTZ,
  result_doc_id UUID REFERENCES project_generated_doc(id) ON DELETE SET NULL,
  result_doc_version INTEGER,
  error_code VARCHAR(96),
  error_message VARCHAR(512),
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  CONSTRAINT project_generated_doc_job_type_ck
    CHECK (doc_type IN ('EXPLANATION','SETUP')),
  CONSTRAINT project_generated_doc_job_status_ck
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  CONSTRAINT project_generated_doc_job_attempt_ck CHECK (attempt_count >= 0),
  CONSTRAINT project_generated_doc_job_lease_ck CHECK (
    (status='RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR
    (status<>'RUNNING' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
  CONSTRAINT project_generated_doc_job_terminal_ck CHECK (
    (status IN ('QUEUED','RUNNING') AND completed_at IS NULL)
    OR
    (status IN ('SUCCEEDED','FAILED') AND completed_at IS NOT NULL)),
  CONSTRAINT project_generated_doc_job_success_ck CHECK (
    status<>'SUCCEEDED'
    OR (result_doc_version IS NOT NULL AND error_code IS NULL AND error_message IS NULL))
);

CREATE UNIQUE INDEX uq_project_generated_doc_job_active
  ON project_generated_doc_job(project_id, doc_type)
  WHERE status IN ('QUEUED','RUNNING');

CREATE INDEX ix_project_generated_doc_job_claim
  ON project_generated_doc_job(status, next_attempt_at, created_at)
  WHERE status IN ('QUEUED','RUNNING');

CREATE INDEX ix_project_generated_doc_job_project_created
  ON project_generated_doc_job(project_id, created_at DESC);

CREATE OR REPLACE FUNCTION enforce_project_generated_doc_job_tenant_coherence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM project p WHERE p.id=NEW.project_id AND p.org_id=NEW.org_id
  ) THEN
    RAISE EXCEPTION 'project_generated_doc_job project/org mismatch'
      USING ERRCODE='23514';
  END IF;
  IF NEW.result_doc_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM project_generated_doc d
    WHERE d.id=NEW.result_doc_id AND d.project_id=NEW.project_id
      AND d.doc_type=NEW.doc_type AND d.version=NEW.result_doc_version
  ) THEN
    RAISE EXCEPTION 'project_generated_doc_job result/project/type mismatch'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_project_generated_doc_job_tenant_coherence
  BEFORE INSERT OR UPDATE OF project_id, org_id, doc_type, result_doc_id, result_doc_version
  ON project_generated_doc_job
  FOR EACH ROW EXECUTE FUNCTION enforce_project_generated_doc_job_tenant_coherence();

COMMENT ON TABLE project_generated_doc_job IS
  'V358 durable asynchronous project-document generation with exact route and reloadable outcome.';
