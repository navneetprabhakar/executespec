-- V197 (Scoring Revamp Phase A, 2026-05-14) — per-run rubric row table.
--
-- Background. The 2026-05-13 audit ("I don't trust the scoring") flagged five problems with
-- the existing single-number verifiedScore. The full design lives at
-- docs/plans/scoring-revamp-design.md; this migration is the Phase A foundation.
--
-- Phase A is non-breaking + dual-write:
--   * If the planner emits a rubric (via a new submitRubric tool — wired in a follow-up), each
--     row lands here at plan time with status=PENDING.
--   * After task execution, RunRubricVerifier walks every PENDING row and dispatches a
--     verification handler by verification_type, flipping the row to PASS / FAIL / UNKNOWN +
--     persisting the evidence.
--   * Existing verifiedScore + rubric_score keep being computed unchanged — Phase A adds the
--     plumbing without removing anything. Phase B will swap the UI; Phase C will retire the
--     legacy numbers.
--
-- Verification types (mirror the design doc):
--   shell         — run a command, assert exit code
--   file-exists   — check workspace tree for a file
--   file-content  — grep file content for a pattern
--   endpoint      — HTTP probe against the preview
--   pr-state      — PR opened / merged / CI green via GitHub API
--   manual        — reviewer-LLM judgement (fallback only)
--
-- For Phase A the verifier handles `shell` and `file-exists` natively; the rest are scaffolded
-- with TODO handlers that mark UNKNOWN + evidence "verification type not yet implemented"
-- (so a rubric using those types doesn't fail the verification pass — it just doesn't add
-- value yet).

CREATE TABLE run_rubric_row (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id              VARCHAR(64)     NOT NULL,
    -- planner-supplied stable id (e.g. "R1", "R2") — lets the UI render the rubric in the
    -- same order the planner emitted it across regenerations
    rubric_row_id       VARCHAR(32)     NOT NULL,
    criterion           TEXT            NOT NULL,
    verification_type   VARCHAR(32)     NOT NULL,
    verification_spec   TEXT,
    -- BUILD | TESTS | LINT_FORMAT | CONTRACT | FEATURE | DOCS | STRUCTURE | VCS
    category            VARCHAR(32)     NOT NULL,
    -- 1-10; weight*PASS gets summed for the headline %
    weight              INTEGER         NOT NULL DEFAULT 5,
    -- PENDING | PASS | FAIL | UNKNOWN
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    evidence            TEXT,
    verified_at         TIMESTAMP,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

    -- One row per (run, rubricRowId). Planner emits a stable row id so re-runs / retries can
    -- align verification evidence to the same rubric line.
    CONSTRAINT uq_run_rubric_row UNIQUE (run_id, rubric_row_id)
);

CREATE INDEX idx_run_rubric_row_run_id ON run_rubric_row(run_id);
