-- Persist honest retrieval mode and vector provenance for uploaded project documents.
--
-- The original V95 corpus required every chunk to contain an OpenAI vector. That forced a
-- DeepSeek-only / unsupported-provider Enterprise org into one of two bad states: secretly use a
-- platform OpenAI key, or fail every document. Nullable vectors let the same durable corpus hold
-- lexical-only chunks while semantic queries explicitly filter to non-null embeddings.

ALTER TABLE project_document
  ADD COLUMN IF NOT EXISTS retrieval_mode VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(32),
  ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(128);

ALTER TABLE project_document_chunk
  ALTER COLUMN embedding DROP NOT NULL;

-- Historical READY was only ever produced by the pre-V290 OpenAI text-embedding-3-small path.
-- Preserve that fact rather than inventing provider provenance for non-terminal rows.
UPDATE project_document
SET retrieval_mode = 'SEMANTIC',
    embedding_provider = 'openai',
    embedding_model = 'text-embedding-3-small'
WHERE status = 'READY'
  AND retrieval_mode = 'PENDING';

UPDATE project_document
SET retrieval_mode = 'LEXICAL',
    embedding_provider = NULL,
    embedding_model = NULL
WHERE status = 'LEXICAL_READY';

CREATE INDEX IF NOT EXISTS idx_project_document_retrieval_ready
  ON project_document(project_id, retrieval_mode, status)
  WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_project_document_chunk_lexical
  ON project_document_chunk(document_id, chunk_index)
  WHERE embedding IS NULL;

CREATE INDEX IF NOT EXISTS idx_project_document_chunk_lexical_fts
  ON project_document_chunk
  USING GIN (to_tsvector('simple'::regconfig, content));
