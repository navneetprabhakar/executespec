-- E3.1 — Enterprise readiness, Phase E3 (engineering standards)
--
-- Four-table model for the standards-as-binding-policy feature per
-- plan §5:
--
--   org_config_repo            — where the org's config-as-code lives
--                                (REPO mode = dedicated org-config repo;
--                                 DATABASE mode = stored in org_standard rows)
--   org_standard               — org-level standards (apply to all the
--                                org's projects unless overridden)
--   project_standard_override  — per-project SEVERITY_DOWNGRADE or
--                                NOT_APPLICABLE override
--   standards_violation        — recorded violation (per run / launch)
--                                with optional override metadata
--
-- The actual project-level standards live in
-- .executespec/config/<project>/standards.yaml on the customer git;
-- this DB stores org-level + per-project overrides + violation history.
--
-- Reverse migration:
--   DROP TABLE IF EXISTS standards_violation CASCADE;
--   DROP TABLE IF EXISTS project_standard_override CASCADE;
--   DROP TABLE IF EXISTS org_standard CASCADE;
--   DROP TABLE IF EXISTS org_config_repo CASCADE;

CREATE TABLE IF NOT EXISTS org_config_repo (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id            VARCHAR(64) NOT NULL UNIQUE,
  github_full_name  VARCHAR(255),                -- when storage_mode = REPO
  storage_mode      VARCHAR(16) NOT NULL,        -- REPO | DATABASE
  created_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS org_standard (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id              VARCHAR(64) NOT NULL,
  standard_id         VARCHAR(128) NOT NULL,    -- the YAML 'id' field
  standard_yaml       TEXT NOT NULL,            -- full YAML serialised
  org_level_locked    BOOLEAN NOT NULL DEFAULT false,
  created_at          TIMESTAMP NOT NULL DEFAULT now(),
  updated_at          TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (org_id, standard_id)
);

CREATE TABLE IF NOT EXISTS project_standard_override (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id            VARCHAR(64) NOT NULL,
  standard_id           VARCHAR(128) NOT NULL,    -- references org_standard.standard_id
  override_type         VARCHAR(32) NOT NULL,     -- SEVERITY_DOWNGRADE | NOT_APPLICABLE
  new_severity          VARCHAR(16),              -- BLOCKING | WARNING | ADVISORY (only when SEVERITY_DOWNGRADE)
  rationale             TEXT NOT NULL,
  created_by_user_id    VARCHAR(64),
  created_at            TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, standard_id)
);

CREATE TABLE IF NOT EXISTS standards_violation (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id                UUID,                     -- pipeline_run.id when run-scoped
  launch_id             UUID,                     -- execution_run.id when launch-scoped
  project_id            VARCHAR(64) NOT NULL,
  standard_id           VARCHAR(128) NOT NULL,
  enforcement_type      VARCHAR(32) NOT NULL,     -- LINT | FILE_PATTERN | LLM_REVIEW | AST_CHECK
  severity              VARCHAR(16) NOT NULL,     -- effective severity at detection time
  violation_summary     TEXT NOT NULL,
  violation_payload     JSONB,                    -- structured (file, line, rule-id, etc.)
  detected_at           TIMESTAMP NOT NULL DEFAULT now(),
  override_applied      BOOLEAN NOT NULL DEFAULT false,
  override_by_user_id   VARCHAR(64),
  override_rationale    TEXT,
  override_at           TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_org_standard_org
  ON org_standard(org_id);
CREATE INDEX IF NOT EXISTS idx_project_standard_override_project
  ON project_standard_override(project_id);
CREATE INDEX IF NOT EXISTS idx_standards_violation_run
  ON standards_violation(run_id) WHERE run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_standards_violation_project_time
  ON standards_violation(project_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_standards_violation_unresolved
  ON standards_violation(project_id) WHERE override_applied = false;
