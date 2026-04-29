-- V161 — Migrations W7+ follow-up (R-334): widen migration_dependency.kind.
--
-- The original V158 schema set kind VARCHAR(16) but the natural enum
-- value RECOMMENDED_AFTER is 17 chars and the seeder hits a
-- DataIntegrityViolation on every recipe that declares a recommended
-- predecessor (e.g. express-to-fastify -> js-to-typescript).
--
-- VARCHAR(32) leaves headroom for future kinds (CONFLICTS_WITH,
-- SUPERSEDES, REQUIRES_BEFORE) without another widen migration.
ALTER TABLE migration_dependency
    ALTER COLUMN kind TYPE VARCHAR(32);

COMMENT ON COLUMN migration_dependency.kind IS
    'Edge kind: PREREQUISITE | RECOMMENDED_AFTER (V161 widened from
     VARCHAR(16) -> VARCHAR(32) to accommodate the 17-char
     RECOMMENDED_AFTER value).';
