-- E2.1 — Enterprise readiness, Phase E2 (contract registry)
--
-- Four-table model for the cross-repo contract registry per plan §2.9:
--
--   contract_registry      — one row per detected/declared contract
--   contract_version       — versioned content snapshots, monotonic
--   contract_consumer      — declared consumers per project
--   contract_drift_event   — drift between .executespec/contracts/produced/
--                            and the source path it was detected from
--
-- The DB is a CACHE — the customer's .executespec/contracts/{produced,consumed}/
-- folders are git-authoritative. Indexes are tuned for the queries E2.3/E2.8/E2.11
-- will run: per-repo coverage, per-project consumer rollup, unresolved-drift
-- triage.
--
-- Reverse migration:
--   DROP TABLE IF EXISTS contract_drift_event CASCADE;
--   DROP TABLE IF EXISTS contract_consumer CASCADE;
--   DROP TABLE IF EXISTS contract_version CASCADE;
--   DROP TABLE IF EXISTS contract_registry CASCADE;

CREATE TABLE IF NOT EXISTS contract_registry (
  id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  repo_id                   UUID NOT NULL REFERENCES project_repo(id),
  defined_in_repo_id        UUID REFERENCES project_repo(id),
    -- *-contracts repo case (E2.5): contract is declared in a separate
    -- contracts-only repo but produced by `repo_id`. NULL when defined ==
    -- produced.
  name                      VARCHAR(255) NOT NULL,
  type                      VARCHAR(32) NOT NULL,
    -- OPENAPI | PROTO | GRAPHQL | JSON_SCHEMA | ASYNCAPI | EVENT_SCHEMA
  current_registry_version  INTEGER NOT NULL DEFAULT 1,
  current_content_hash      VARCHAR(64) NOT NULL,
  declared_semver           VARCHAR(32),               -- advisory only
  detected_path             VARCHAR(512),
  detection_method          VARCHAR(32) NOT NULL,
    -- AUTO_FILE | AUTO_BUILD_GRAPH | AUTO_RUNTIME_FETCH | IMPORTED_REGISTRY | DECLARED
  created_at                TIMESTAMP NOT NULL DEFAULT now(),
  updated_at                TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (repo_id, name)
);

CREATE TABLE IF NOT EXISTS contract_version (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id         UUID NOT NULL REFERENCES contract_registry(id) ON DELETE CASCADE,
  registry_version    INTEGER NOT NULL,
  content_hash        VARCHAR(64) NOT NULL,
  content_pointer     TEXT,                            -- GCS path for large bodies
  inline_content      JSONB,                           -- inline when <256KB
  detected_at         TIMESTAMP NOT NULL DEFAULT now(),
  created_by_run_id   UUID,                            -- FK to pipeline_run if known; nullable
  UNIQUE (contract_id, registry_version)
);

CREATE TABLE IF NOT EXISTS contract_consumer (
  id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id                 UUID NOT NULL REFERENCES contract_registry(id) ON DELETE CASCADE,
  consumer_repo_id            UUID NOT NULL REFERENCES project_repo(id),
  declared_version_constraint VARCHAR(64),
  declared_in_project_id      VARCHAR(64) NOT NULL,    -- project.id is VARCHAR
  declared_at                 TIMESTAMP NOT NULL DEFAULT now(),
  declared_by_user_id         VARCHAR(64),
  UNIQUE (contract_id, consumer_repo_id, declared_in_project_id)
);

CREATE TABLE IF NOT EXISTS contract_drift_event (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  contract_id           UUID NOT NULL REFERENCES contract_registry(id) ON DELETE CASCADE,
  detected_at           TIMESTAMP NOT NULL DEFAULT now(),
  source_diff           TEXT,
  resolved_action       VARCHAR(32),                   -- REFRESHED | DISMISSED | NULL
  resolved_at           TIMESTAMP,
  resolved_by_user_id   VARCHAR(64),
  resolved_rationale    TEXT
);

-- E2.3 coverage: per-repo lookup is the hottest read. E2.8 drift triage
-- needs unresolved-drift fast. E2.11 planner injection: per-project
-- consumer rollup hits contract_consumer (declared_in_project_id) hard.
CREATE INDEX IF NOT EXISTS idx_contract_registry_repo
  ON contract_registry(repo_id);
CREATE INDEX IF NOT EXISTS idx_contract_registry_defined_in
  ON contract_registry(defined_in_repo_id) WHERE defined_in_repo_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_contract_consumer_consumer
  ON contract_consumer(consumer_repo_id);
CREATE INDEX IF NOT EXISTS idx_contract_consumer_project
  ON contract_consumer(declared_in_project_id);
CREATE INDEX IF NOT EXISTS idx_contract_drift_unresolved
  ON contract_drift_event(contract_id) WHERE resolved_action IS NULL;
