-- V192 (PR-M, 2026-05-13) — pin the branch each per-repo codemap was generated FROM, so the
-- staleness probe can compare HEAD of the SAME branch instead of always assuming defaultBranch.
--
-- Bug. User report: "the architecture screen keeps showing STALE."  Root cause: a project
-- regenerated with `?branch=feature-x` writes a codemap whose generated_from_sha is the SHA of
-- feature-x's HEAD. StalenessService later probes `repo.getDefaultBranch()` (typically `main`),
-- gets a totally different SHA, and the dashboard pins the STALE badge forever — no amount of
-- regenerating helps because every regen captures the feature-branch SHA, never main's.
--
-- Fix. Persist a JSON map {repoFullName: branch} alongside the existing generated_from_shas
-- column. StalenessService reads this and probes the same branch the codemap came from. Legacy
-- rows without this column fall back to defaultBranch (existing behaviour) so the staleness
-- result for pre-V192 docs is unchanged.
--
-- Additive only — nullable. Backfill is left for the next regen cycle: every legacy row will
-- eventually carry the new map when its project is next regenerated, and any never-regenerated
-- row keeps the legacy defaultBranch-assumes-main behaviour.

ALTER TABLE project_architecture_doc
    ADD COLUMN generated_from_branches jsonb;
