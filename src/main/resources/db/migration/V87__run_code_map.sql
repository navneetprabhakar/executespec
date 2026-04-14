-- V87 — Pilot v3.0 follow-up.
-- For PRO/MAX/ENTERPRISE existing-repo runs we generate a codemap of the
-- single repo at submission time and feed it into the planner + assessment
-- prompts. Persisted per-run (no project required).
--
-- Captured fields:
--   raw_content   — the deterministic file-tree + route scan output
--   summary       — LLM-compressed (~3k tokens) version injected into prompts
--   file_count    — surfaced in AssessmentPanel ("N files scanned")
--   route_count   — surfaced in AssessmentPanel ("K routes detected")
--   tokens / cost — billing visibility for the extra LLM call

CREATE TABLE IF NOT EXISTS run_code_map (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id VARCHAR(255) NOT NULL,
    repo_url VARCHAR(500) NOT NULL,
    raw_content TEXT NOT NULL,
    summary TEXT,
    file_count INT NOT NULL DEFAULT 0,
    route_count INT NOT NULL DEFAULT 0,
    generated_from_sha VARCHAR(40),
    input_tokens INT NOT NULL DEFAULT 0,
    output_tokens INT NOT NULL DEFAULT 0,
    actual_cost_usd NUMERIC(10, 6),
    generated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_run_code_map_run_id ON run_code_map(run_id);
