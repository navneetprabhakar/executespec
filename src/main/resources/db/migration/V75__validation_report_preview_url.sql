-- V75: Add preview_url to validation_report
-- Stores the preview URL captured at report generation time.
-- Null for batch runs (which skip auto-preview) and runs where the preview expired
-- before the validation report was generated.

ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS preview_url VARCHAR(512);
