-- V223 — Code-RAG: per-repo SOURCE-code chunk store for project-chat semantic search.
--
-- Deliberately a SEPARATE corpus from project_document_chunk (uploaded docs, V95): the doc store
-- joins through project_document and filters status='READY', which makes no sense for source code.
-- Keeping them apart means doc-search (search_project_docs) and code-search (search_code) never
-- pollute each other's nearest-neighbour results.
--
-- Keyed by (project_id, repo_id, file_path, file_sha):
--   * repo_id          → repo-scoped retrieval when a project links multiple repos
--   * file_sha         → per-file content SHA so CodeIndexingService can skip unchanged files
--                        (incremental reindex) and bust a single file without re-embedding the repo
-- Freshness tracks the indexing pass (hooked into the codemap/architecture clone-walk + a lazy
-- trigger on a search_code miss). RL-2026-05-052 (T3.8 code-RAG).
--
-- pgvector extension already created by V95; vector(1536) matches text-embedding-3-small.

CREATE TABLE IF NOT EXISTS project_code_chunk (
    id              VARCHAR(36)   PRIMARY KEY,
    project_id      VARCHAR(36)   NOT NULL,
    repo_id         VARCHAR(36)   NOT NULL,
    repo_full_name  VARCHAR(255),
    file_path       VARCHAR(1000) NOT NULL,
    file_sha        VARCHAR(64)   NOT NULL,
    chunk_index     INT           NOT NULL,
    content         TEXT          NOT NULL,
    token_count     INT           NOT NULL,
    embedding       vector(1536)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pcc_project_repo ON project_code_chunk (project_id, repo_id);
CREATE INDEX IF NOT EXISTS idx_pcc_file         ON project_code_chunk (repo_id, file_path);
CREATE INDEX IF NOT EXISTS idx_pcc_embedding
    ON project_code_chunk USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
