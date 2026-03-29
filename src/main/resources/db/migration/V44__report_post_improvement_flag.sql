ALTER TABLE validation_report
  ADD COLUMN IF NOT EXISTS generated_after_improvements BOOLEAN NOT NULL DEFAULT FALSE;
