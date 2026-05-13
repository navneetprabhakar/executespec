-- V187 — Stage 2 of the cost-estimation revamp (audit 2026-05-13 run 6024f510).
--
-- Persists one row per terminal run with the features needed to score similarity and
-- retrieve the actual cost. Used by `cost/CostHistoryService` (next-PR consumer) to
-- compute k-NN-style estimates: for a new run, find the K most similar past runs by
-- (planStrategy, taskCount bucket, repoCount, projectType, provider) and return P25/P75
-- of their actual cost as the estimate range. Stage 1 formula remains the bootstrap
-- fallback when fewer than 10 similar runs exist.
--
-- Schema is intentionally narrow — each column is a feature for the similarity score.
-- Add more features in V_next as we learn what correlates with cost.

CREATE TABLE IF NOT EXISTS run_cost_history (
    run_id              TEXT PRIMARY KEY,
    org_id              TEXT,
    project_id          TEXT,
    plan_strategy       TEXT,                 -- SINGLE_SHOT | SMALL | MEDIUM | LARGE | LEGACY
    classification      TEXT,                 -- SINGLE_SHOT | MEDIUM | MULTI_TASK (V1 enum)
    task_count          INTEGER,
    repo_count          INTEGER,              -- # of linked repos in the workspace
    project_type        TEXT,                 -- inferred type ('spring_boot', 'node_frontend', etc)
    provider            TEXT,                 -- ANTHROPIC | OPENAI | GEMINI
    spec_length         INTEGER,              -- char count of the user request
    actual_cost_usd     NUMERIC(10,4),
    actual_tokens_in    BIGINT,
    actual_tokens_out   BIGINT,
    verified_score      INTEGER,
    status              TEXT,
    status_reason       TEXT,
    started_at          TIMESTAMPTZ NOT NULL,
    completed_at        TIMESTAMPTZ NOT NULL,
    schema_version      SMALLINT NOT NULL DEFAULT 1
);

-- Similarity-search indexes. The k-NN consumer filters first on (planStrategy,
-- providerKey) then narrows by repoCount + projectType. Composite covers the common path.
CREATE INDEX IF NOT EXISTS idx_run_cost_history_similarity
    ON run_cost_history (plan_strategy, provider, repo_count, task_count, completed_at DESC);

-- Per-org cost profile lookup — TEAM/ENTERPRISE BYOK orgs price-shift differently.
CREATE INDEX IF NOT EXISTS idx_run_cost_history_org
    ON run_cost_history (org_id, completed_at DESC)
    WHERE org_id IS NOT NULL;

-- Per-project profile (drives "this project usually costs $X" UI surfaces).
CREATE INDEX IF NOT EXISTS idx_run_cost_history_project
    ON run_cost_history (project_id, completed_at DESC)
    WHERE project_id IS NOT NULL;

COMMENT ON TABLE run_cost_history IS
    'Per-run cost outcomes for k-NN cost estimation (Stage 2 of 2026-05-13 audit).';
COMMENT ON COLUMN run_cost_history.schema_version IS
    'Bump when feature columns change so the k-NN query can filter to compatible rows.';
