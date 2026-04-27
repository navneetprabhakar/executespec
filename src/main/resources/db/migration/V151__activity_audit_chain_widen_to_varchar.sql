-- V151 — widen V147's CHAR(64) hash columns to VARCHAR(64).
--
-- Why: V147 declared `prev_hash CHAR(64)` + `row_hash CHAR(64)`,
-- which Postgres stores as `bpchar`. Hibernate's schema validator
-- expects VARCHAR for `@Column(length=64)`-mapped fields and threw
-- `wrong column type encountered ... found [bpchar (Types#CHAR)],
-- but expecting [char(64) (Types#VARCHAR)]` at boot.
--
-- Per the standing rule (`feedback_flyway_never_modify_applied.md`),
-- we don't edit V147 retroactively. This is the follow-up that makes
-- the column type match what the entity declares.
--
-- ALTER COLUMN TYPE on a CHAR→VARCHAR widening is a metadata-only
-- change in Postgres (no full table rewrite); the column data is
-- already representable in VARCHAR(64).
--
-- Reverse migration:
--   ALTER TABLE activity_audit
--       ALTER COLUMN prev_hash TYPE CHAR(64),
--       ALTER COLUMN row_hash  TYPE CHAR(64);

ALTER TABLE activity_audit
    ALTER COLUMN prev_hash TYPE VARCHAR(64),
    ALTER COLUMN row_hash  TYPE VARCHAR(64);
