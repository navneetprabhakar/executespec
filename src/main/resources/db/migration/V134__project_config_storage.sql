-- E1.7 — Enterprise readiness, Phase E1 baseline
--
-- Two-part schema for multi-project repo support + the confidential-project
-- opt-out:
--
-- 1. project.config_storage — when a Project's standards.yaml /
--    detection-overrides.yaml / scopes.yaml shouldn't go into the
--    customer's git (e.g. confidential, security-sensitive), this column
--    flips to DATABASE and ProjectConfigPathResolver routes the writes to
--    the project_config table below instead of .executespec/config/.
--
-- 2. project_config — small typed key-value store for the database-mode
--    opt-out. One row per (project_id, config_key); content stored as
--    JSONB so future config types can evolve without schema changes.
--
-- Reverse migration:
--   ALTER TABLE project DROP COLUMN IF EXISTS config_storage;
--   DROP TABLE IF EXISTS project_config CASCADE;

ALTER TABLE project
  ADD COLUMN IF NOT EXISTS config_storage VARCHAR(16);
    -- NULL | FOLDER | DATABASE   (NULL == FOLDER via prModeEnum-style normaliser)

CREATE TABLE IF NOT EXISTS project_config (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id  VARCHAR(64) NOT NULL,           -- matches project.id (VARCHAR per current schema)
  config_key  VARCHAR(128) NOT NULL,          -- e.g. "standards.yaml", "scopes.yaml"
  content     JSONB NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT now(),
  updated_at  TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, config_key)
);

CREATE INDEX IF NOT EXISTS idx_project_config_project ON project_config(project_id);
