-- V370 aligns the V369 digest columns with the JPA mapping. V369 created them as CHAR(64)
-- (PostgreSQL bpchar) while the CorrectionAttempt entity maps them as VARCHAR(64), so Hibernate
-- schema-validation failed on every boot and the dev deployment crash-looped. Applied migrations
-- are immutable, so this follow-up converts the type in place. Every stored value is an exact
-- 64-character lowercase hex digest (enforced by the V369 CHECK constraints), so the conversion
-- is content-preserving and the existing constraints and indexes remain valid.
ALTER TABLE correction_attempt
    ALTER COLUMN before_generation TYPE VARCHAR(64),
    ALTER COLUMN after_generation TYPE VARCHAR(64),
    ALTER COLUMN evidence_workspace_generation TYPE VARCHAR(64),
    ALTER COLUMN evidence_snapshot_digest TYPE VARCHAR(64),
    ALTER COLUMN proposal_digest TYPE VARCHAR(64);
