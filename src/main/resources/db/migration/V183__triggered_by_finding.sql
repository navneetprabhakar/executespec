-- V183 — finding-triggered patch runs.
--
-- When a patch-mode run is dispatched in response to a project-side "finding"
-- (a PR review comment, a tech-debt issue, a code-review issue, a CI failure),
-- we stamp the originating finding id on the pipeline_run row so:
--
--   * The dashboard can render "Triggered by code review issue #abc"
--     instead of an orphan run.
--   * The FindingPatchSubmitter service can dedupe — a finding that already
--     has an open patch run won't dispatch a second one until the first is
--     terminal.
--   * Operational telemetry can attribute spend per finding source.
--
-- Format: "<TYPE>:<id>", e.g. "PR_REVIEW:42a3-9c01-…", "TECH_DEBT:proj-id|category",
-- "CODE_REVIEW_ISSUE:abc-123", "CI_FAILURE:check-789".

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS triggered_by_finding VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_pipeline_run_triggered_by_finding
    ON pipeline_run (triggered_by_finding)
    WHERE triggered_by_finding IS NOT NULL;
