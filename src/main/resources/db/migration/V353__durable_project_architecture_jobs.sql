CREATE TABLE project_architecture_job (
  id UUID PRIMARY KEY,
  project_id VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  org_id VARCHAR(255) NOT NULL REFERENCES org(id) ON DELETE CASCADE,
  requested_by_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
  requested_branch VARCHAR(255),

  -- PREPARING resolves this immutable, canonical source snapshot. Admission never calls an SCM.
  source_snapshot_json JSONB,
  source_snapshot_digest VARCHAR(64),

  -- Exact routes are durable. Repository and combined architecture use the configured PLANNING
  -- route while retaining separate execution contexts and budgets.
  requested_provider VARCHAR(32) NOT NULL,
  repo_architecture_model VARCHAR(160) NOT NULL,
  project_architecture_model VARCHAR(160) NOT NULL,
  repo_execution_context_id VARCHAR(255),
  project_execution_context_id VARCHAR(255),

  status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
  phase VARCHAR(16) NOT NULL DEFAULT 'PREPARING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  deadline_at TIMESTAMPTZ NOT NULL DEFAULT (clock_timestamp() + INTERVAL '2 hours'),
  lease_owner VARCHAR(255),
  lease_token BIGINT NOT NULL DEFAULT 0,
  lease_expires_at TIMESTAMPTZ,
  cancel_requested_at TIMESTAMPTZ,

  -- Provider output is invisible until a fenced promotion transaction copies this stage into the
  -- canonical code-map / repository-architecture / project-architecture tables.
  staged_artifacts_json JSONB,
  staged_artifacts_digest VARCHAR(64),
  quality_status VARCHAR(16),
  quality_evidence_json JSONB,

  -- RESTRICT preserves the success proof while the terminal job is retained. Terminal-job
  -- retention must delete the proof row before an old architecture version becomes pruneable.
  result_architecture_id UUID REFERENCES project_architecture_doc(id) ON DELETE RESTRICT,
  result_architecture_version INTEGER,
  result_source_fingerprint VARCHAR(64),
  error_code VARCHAR(96),
  error_message VARCHAR(512),
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  promoted_at TIMESTAMPTZ,

  CONSTRAINT project_architecture_job_status_ck
    CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','NEEDS_CONFIRMATION')),
  CONSTRAINT project_architecture_job_phase_ck
    CHECK (phase IN ('PREPARING','EXECUTING','PUBLISHING')),
  CONSTRAINT project_architecture_job_attempt_ck CHECK (attempt_count >= 0),
  CONSTRAINT project_architecture_job_lease_token_ck CHECK (lease_token >= 0),
  CONSTRAINT project_architecture_job_deadline_ck CHECK (deadline_at > created_at),
  CONSTRAINT project_architecture_job_source_digest_ck CHECK (
    source_snapshot_digest IS NULL OR source_snapshot_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT project_architecture_job_stage_digest_ck CHECK (
    staged_artifacts_digest IS NULL OR staged_artifacts_digest ~ '^[0-9a-f]{64}$'),
  CONSTRAINT project_architecture_job_result_digest_ck CHECK (
    result_source_fingerprint IS NULL OR result_source_fingerprint ~ '^[0-9a-f]{64}$'),
  CONSTRAINT project_architecture_job_quality_ck CHECK (
    quality_status IS NULL OR quality_status IN ('QUALIFIED','WARNING','FAILED')),
  CONSTRAINT project_architecture_job_lease_ck CHECK (
    (status='RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
    OR
    (status<>'RUNNING' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
  CONSTRAINT project_architecture_job_prepared_ck CHECK (
    phase='PREPARING'
    OR
    (source_snapshot_json IS NOT NULL AND source_snapshot_digest IS NOT NULL
     AND repo_execution_context_id IS NOT NULL AND project_execution_context_id IS NOT NULL)),
  CONSTRAINT project_architecture_job_stage_ck CHECK (
    phase<>'PUBLISHING' OR status IN ('FAILED','CANCELLED','NEEDS_CONFIRMATION')
    OR
    (staged_artifacts_json IS NOT NULL AND staged_artifacts_digest IS NOT NULL
     AND quality_status IN ('QUALIFIED','WARNING'))),
  CONSTRAINT project_architecture_job_terminal_ck CHECK (
    (status IN ('QUEUED','RUNNING') AND completed_at IS NULL)
    OR
    (status IN ('SUCCEEDED','FAILED','CANCELLED','NEEDS_CONFIRMATION') AND completed_at IS NOT NULL)),
  CONSTRAINT project_architecture_job_success_ck CHECK (
    status<>'SUCCEEDED'
    OR
    (phase='PUBLISHING' AND quality_status IN ('QUALIFIED','WARNING')
     AND result_architecture_id IS NOT NULL AND result_architecture_version IS NOT NULL
     AND result_source_fingerprint=source_snapshot_digest
     AND promoted_at IS NOT NULL AND completed_at IS NOT NULL
     AND error_code IS NULL AND error_message IS NULL)),
  CONSTRAINT project_architecture_job_non_success_result_ck CHECK (
    status='SUCCEEDED'
    OR
    (result_architecture_id IS NULL AND result_architecture_version IS NULL AND promoted_at IS NULL))
);

-- One durable project-wide serialization boundary. All whole-project refresh entry points enqueue
-- here; a second request returns the existing active job instead of duplicating provider spend.
CREATE UNIQUE INDEX uq_project_architecture_job_one_active
  ON project_architecture_job(project_id)
  WHERE status IN ('QUEUED','RUNNING');

CREATE INDEX ix_project_architecture_job_claim
  ON project_architecture_job(status, next_attempt_at, created_at)
  WHERE status IN ('QUEUED','RUNNING');

CREATE INDEX ix_project_architecture_job_project_created
  ON project_architecture_job(project_id, created_at DESC);

-- PostgreSQL CHECK constraints cannot contain cross-table subqueries. This trigger is the database
-- tenant-coherence constraint: application code cannot pair a project with another organization,
-- nor publish a result belonging to another project.
CREATE OR REPLACE FUNCTION enforce_project_architecture_job_tenant_coherence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM project p WHERE p.id=NEW.project_id AND p.org_id=NEW.org_id
  ) THEN
    RAISE EXCEPTION 'project_architecture_job project/org mismatch'
      USING ERRCODE='23514';
  END IF;
  IF NEW.result_architecture_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM project_architecture_doc d
    WHERE d.id=NEW.result_architecture_id AND d.project_id=NEW.project_id
  ) THEN
    RAISE EXCEPTION 'project_architecture_job result/project mismatch'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_project_architecture_job_tenant_coherence
  BEFORE INSERT OR UPDATE OF project_id, org_id, result_architecture_id
  ON project_architecture_job
  FOR EACH ROW EXECUTE FUNCTION enforce_project_architecture_job_tenant_coherence();

COMMENT ON TABLE project_architecture_job IS
  'V353 durable refresh jobs. Terminal rows are retained for 30 days; cleanup must never remove an active row.';

-- Extra provider spend after an uncertain outcome requires the original requester's explicit
-- confirmation. Receipts are append-only evidence; the job identity is reused for every retry.
CREATE TABLE project_architecture_job_retry_confirmation (
  id UUID PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES project_architecture_job(id) ON DELETE CASCADE,
  project_id VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  confirmed_by_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
  confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_project_architecture_job_confirmation_job
  ON project_architecture_job_retry_confirmation(job_id, confirmed_at DESC);

CREATE OR REPLACE FUNCTION enforce_project_architecture_retry_confirmation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM project_architecture_job j
    WHERE j.id=NEW.job_id
      AND j.project_id=NEW.project_id
      AND j.requested_by_user_id=NEW.confirmed_by_user_id
  ) THEN
    RAISE EXCEPTION 'project architecture retry confirmation authority mismatch'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_project_architecture_retry_confirmation
  BEFORE INSERT OR UPDATE
  ON project_architecture_job_retry_confirmation
  FOR EACH ROW EXECUTE FUNCTION enforce_project_architecture_retry_confirmation();

COMMENT ON TABLE project_architecture_job_retry_confirmation IS
  'Immutable receipts for explicit retries after uncertain paid project-architecture execution.';
-- Multi-repository architecture fingerprints are canonical JSON maps of full repository names to
-- source revisions. V195's VARCHAR(255) can overflow with two long names or any ordinary 4+ repo
-- project, failing generated-document publication after the provider has already been charged.
ALTER TABLE project_generated_doc
    ALTER COLUMN source_shas_fingerprint TYPE TEXT;
