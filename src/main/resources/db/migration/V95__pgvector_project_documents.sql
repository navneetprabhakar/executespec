-- V95 — RAG: per-project document ingestion + pgvector chunk store.
-- Documents are physically uploaded to workspace/projects/<projectId>/docs/ and
-- their normalised text is chunked + embedded for always-on retrieval.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS project_document (
    id              VARCHAR(36)  PRIMARY KEY,
    project_id      VARCHAR(36)  NOT NULL,
    path_relative   VARCHAR(500) NOT NULL,
    source          VARCHAR(32)  NOT NULL DEFAULT 'USER_UPLOAD',
    filename        VARCHAR(255),
    mime_type       VARCHAR(100),
    size_bytes      BIGINT,
    sha256          VARCHAR(64),
    uploaded_by     VARCHAR(64),
    status          VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    failure_reason  TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_project_document_path UNIQUE (project_id, path_relative)
);

CREATE INDEX IF NOT EXISTS idx_pd_project_status ON project_document (project_id, status);

CREATE TABLE IF NOT EXISTS project_document_chunk (
    id            VARCHAR(36) PRIMARY KEY,
    document_id   VARCHAR(36) NOT NULL REFERENCES project_document(id) ON DELETE CASCADE,
    chunk_index   INT         NOT NULL,
    content       TEXT        NOT NULL,
    token_count   INT         NOT NULL,
    embedding     vector(1536) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pdc_doc ON project_document_chunk (document_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_pdc_embedding
    ON project_document_chunk USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
