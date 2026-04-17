-- V115 — Per-repo architecture doc layer.
--
-- Replaces the "codemaps → single combined architecture.md" shortcut with a
-- richer 3-step flow:
--   1. Per-repo codemap (existing — project_code_map table, idempotent if SHA
--      unchanged after V115 service rewrite)
--   2. Per-repo architecture doc (NEW — this table) — HLD + LLD in natural
--      language, derived from the codemap
--   3. Combined project architecture doc (existing project_architecture_doc)
--      now synthesised from per-repo arch docs instead of raw codemaps
--
-- Versioned per (project_id, repo_id). Disk mirror at
-- workspace/projects/<id>/repos/<shortName>/architecture.md.

CREATE TABLE IF NOT EXISTS repo_architecture_doc (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id             VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    repo_id                UUID NOT NULL REFERENCES project_repo(id) ON DELETE CASCADE,
    version                INTEGER NOT NULL DEFAULT 1,
    content                TEXT NOT NULL,
    source_code_map_id     UUID REFERENCES project_code_map(id) ON DELETE SET NULL,
    generated_from_sha     VARCHAR(64),
    generated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    generated_by           VARCHAR(255),
    model_used             VARCHAR(64),
    input_tokens           INTEGER NOT NULL DEFAULT 0,
    output_tokens          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_repo_architecture_doc_project_repo_version
    ON repo_architecture_doc (project_id, repo_id, version DESC);

CREATE INDEX IF NOT EXISTS idx_repo_architecture_doc_project
    ON repo_architecture_doc (project_id);

COMMENT ON TABLE repo_architecture_doc IS
    'Per-repo HLD + LLD architecture doc (natural language). Step 2 of the 3-step arch generation pipeline. Latest version by (project_id, repo_id, version DESC).';
