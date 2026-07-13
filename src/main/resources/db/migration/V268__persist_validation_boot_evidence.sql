-- V268: Persist deterministic boot-verification provenance on validation reports.
-- Applied migrations are immutable. Nullable columns preserve historical reports and the
-- compile-only scoring path when verifyBoot was unavailable.
ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS boot_verdict VARCHAR(32),
    ADD COLUMN IF NOT EXISTS boot_healthy BOOLEAN,
    ADD COLUMN IF NOT EXISTS boot_reason TEXT;
