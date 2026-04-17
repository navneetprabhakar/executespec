-- V113 — Project-level generated docs (EXPLANATION + SETUP).
--
-- Complement to the existing summary.md auto-generated during analyse():
-- these are user-invoked, user-facing documents (onboarding explanation +
-- local-setup README) that team members download / share.
--
-- Disk under workspace/projects/<id>/docs/<type>.md remains the authoritative
-- read path; this table is the index so we know (a) which types have been
-- generated, (b) when, (c) which version, without walking the filesystem
-- (Cloud Run workspaces are ephemeral).

CREATE TABLE IF NOT EXISTS project_generated_doc (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    doc_type        VARCHAR(32) NOT NULL,
    content         TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    generated_by    VARCHAR(36),
    generated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    model_used      VARCHAR(64),
    input_tokens    INTEGER NOT NULL DEFAULT 0,
    output_tokens   INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT project_generated_doc_type_chk
        CHECK (doc_type IN ('EXPLANATION', 'SETUP'))
);

CREATE INDEX IF NOT EXISTS idx_project_generated_doc_project_type_version
    ON project_generated_doc (project_id, doc_type, version DESC);

COMMENT ON TABLE project_generated_doc IS
    'User-invoked project-level docs (explanation + setup). Versioned; latest version by (project_id, doc_type).';
