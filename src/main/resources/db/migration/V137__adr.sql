-- E3.12 — Enterprise readiness, Phase E3 (ADRs as first-class)
--
-- Per plan §5.11: ADRs live in customer git as
-- .executespec/arch/adrs/<NNNN>-<slug>.md per repo (project-namespaced
-- for multi-project repos). DB caches the parsed frontmatter so the
-- planner-injection path (E3.13) doesn't re-fetch + re-parse on every
-- run; reads are project-scoped.
--
-- Reverse migration:
--   DROP TABLE IF EXISTS adr CASCADE;

CREATE TABLE IF NOT EXISTS adr (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id          VARCHAR(64) NOT NULL,
  repo_id             UUID NOT NULL REFERENCES project_repo(id),
  adr_id_in_repo      INTEGER NOT NULL,        -- the NNNN sequence number
  title               VARCHAR(255) NOT NULL,
  status              VARCHAR(32) NOT NULL,    -- PROPOSED | ACCEPTED | DEPRECATED | SUPERSEDED
  decision_date       DATE NOT NULL,
  deciders            TEXT,                    -- comma-joined for portability across drivers
  supersedes          TEXT,                    -- comma-joined adr_id_in_repo
  superseded_by       TEXT,
  related_adrs        TEXT,
  related_standards   TEXT,
  tags                TEXT,
  body                TEXT,                    -- markdown body (sans frontmatter)
  file_path           VARCHAR(512),
  created_at          TIMESTAMP NOT NULL DEFAULT now(),
  updated_at          TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (repo_id, adr_id_in_repo)
);

CREATE INDEX IF NOT EXISTS idx_adr_project ON adr(project_id);
CREATE INDEX IF NOT EXISTS idx_adr_status ON adr(status);
CREATE INDEX IF NOT EXISTS idx_adr_project_status_accepted
  ON adr(project_id) WHERE status = 'ACCEPTED';
