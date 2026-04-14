-- V86 — Pilot v3.0 deferral #1 (multi-repo PRs).
-- The matching @Column was added on PipelineRun.java when wiring multi-repo
-- push, but the DDL was missed in V85. Hibernate schema validation fails on
-- boot without it (Schema-validation: missing column [selected_repo_ids]).

ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS selected_repo_ids JSONB;
