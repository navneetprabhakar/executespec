-- V93 — project-level chat (v2.4.0). ENTERPRISE + MAX premium feature. Each
-- turn feeds the compacted project memory + arch doc as system context and
-- calls the PLANNING tier. BYO tokens at the project's aiProvider. No limits.
CREATE TABLE IF NOT EXISTS project_chat_message (
    id          VARCHAR(36) PRIMARY KEY,
    project_id  VARCHAR(36) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    org_id      VARCHAR(36) NOT NULL,
    user_id     VARCHAR(36) NOT NULL,
    role        VARCHAR(20) NOT NULL,
    content     TEXT        NOT NULL,
    tokens_in   INTEGER,
    tokens_out  INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_project_chat_project_created
    ON project_chat_message (project_id, created_at);
