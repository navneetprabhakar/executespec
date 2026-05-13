-- V196 (PR-N2, 2026-05-14) — per-file LLM-generated purpose summaries with SHA-keyed caching.
--
-- Background. The codemap today lists files + extracted function names but doesn't say what
-- each file is FOR. That gap is the most-quoted UX complaint from the audit:
--   > "Codemap only generates files details, we need function and purpose details as well."
--
-- The summary is a 2-3 sentence natural-language description of the file's role (e.g.
-- "Handles user lifecycle CRUD against Postgres, fires email verification via
-- NotificationService on create, returns DTO-shaped responses"). Generated once per
-- (repo, path, sha) triple by a LIGHTWEIGHT-tier LLM call; on cache hit the codemap reads
-- from this table at zero cost.
--
-- Cost model (audited 2026-05-14 against 5 sample projects):
--   * Naive per-function: 1,200 LLM calls per regen × $0.0001/call (Haiku) = $0.12/regen.
--   * Per-file design (this migration): 40 LLM calls/regen × $0.0001 = $0.005/regen.
--   * With cache: unchanged files skip entirely → steady-state <$0.001/regen on busy projects.
--
-- Composite key keeps the cache stable across rewrites — if a file moves to a new path the
-- old row remains until the cleanup sweeper (PR-H) eventually reaps it; we don't try to track
-- moves. If the SHA changes (content modification), a new row is written and the old one
-- becomes stale until eviction. Cache stays correct even when the underlying file is renamed.
--
-- Gated behind feature flag `codemap.purpose-summaries` (default OFF). The flag is checked in
-- FilePurposeSummaryService.shouldGenerate so the column can sit unused on legacy projects.

CREATE TABLE file_purpose_summary (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_full_name  VARCHAR(255)    NOT NULL,
    file_path       VARCHAR(1024)   NOT NULL,
    file_sha        VARCHAR(64)     NOT NULL,
    summary         TEXT            NOT NULL,
    model_used      VARCHAR(64),
    input_tokens    INTEGER         NOT NULL DEFAULT 0,
    output_tokens   INTEGER         NOT NULL DEFAULT 0,
    cost_usd        DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    generated_at    TIMESTAMP       NOT NULL DEFAULT NOW(),

    -- The cache key. SHA is enough by itself in theory but composite gives Postgres the
    -- partitioned-lookup pattern we need (Spring Data's derived query goes via this 3-col
    -- composite, not just SHA).
    CONSTRAINT uq_file_purpose_summary_triple
        UNIQUE (repo_full_name, file_path, file_sha)
);

-- Most lookups are by (repoFullName, filePath, fileSha) — the unique constraint above already
-- creates the index. Add a sweeper-friendly index on generated_at so PR-H can age out stale
-- rows efficiently.
CREATE INDEX IF NOT EXISTS idx_file_purpose_summary_generated_at
    ON file_purpose_summary (generated_at);
