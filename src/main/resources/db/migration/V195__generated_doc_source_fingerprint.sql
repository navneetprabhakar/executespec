-- V195 (PR-Q, 2026-05-13) — short-circuit cache key on project_generated_doc.
--
-- Bug audit (carried over from the PR-K + PR-M arch-regen design conversation, 2026-05-13):
-- ProjectGeneratedDoc (EXPLANATION + SETUP) had no SHA / fingerprint columns, so every user
-- click on "Generate docs" ran the full 15-40s LLM call even when nothing relevant had
-- changed. PR-K shipped the analogous short-circuit for the combined-arch LLM call against
-- ProjectArchitectureDoc.generated_from_shas; this migration closes the same gap one layer
-- up.
--
-- Cache key shape:
--   * source_arch_version — the ProjectArchitectureDoc.version this doc was generated against.
--     Composite key so a single arch-version bump (the most common reason to re-doc) invalidates.
--   * source_shas_fingerprint — canonical serialised {repoFullName: sha} fingerprint, identical
--     in form to PR-K's ProjectArchitectureService.serialiseFingerprint output. Comparing this
--     against the current per-repo SHA map lets us detect "arch was regenerated against new
--     SHAs but produced byte-identical content" — rare but possible after a no-op refactor.
--
-- Both columns nullable: legacy rows fall through (treated as "no cache key, regenerate") so
-- the live behaviour is the same as today for any doc written before this migration.
--
-- Companion code (this PR):
--   * ProjectGeneratedDoc entity gains the two fields
--   * ProjectGeneratedDocRepository.findFirstByProjectIdAndDocTypeAndSourceArchVersionAnd...
--   * ProjectDocGeneratorService.generate short-circuits when both keys match a recent doc

ALTER TABLE project_generated_doc
    ADD COLUMN source_arch_version INTEGER;

ALTER TABLE project_generated_doc
    ADD COLUMN source_shas_fingerprint VARCHAR(255);
