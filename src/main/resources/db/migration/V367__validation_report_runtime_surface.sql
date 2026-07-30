ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS runtime_surface VARCHAR(24);

COMMENT ON COLUMN validation_report.runtime_surface IS
    'Runtime surface bound at report generation; null means historical/unknown, never non-HTTP.';
