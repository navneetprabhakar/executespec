-- Validation-report share bearers predate the governed project-share subsystem and were stored as
-- permanent plaintext. Preserve existing URLs for a bounded grace period while removing plaintext
-- at rest. New links are minted through project_share_link; these columns are compatibility-only.

ALTER TABLE validation_report
  ADD COLUMN IF NOT EXISTS share_token_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS share_token_prefix VARCHAR(12),
  ADD COLUMN IF NOT EXISTS share_expires_at TIMESTAMP WITH TIME ZONE,
  ADD COLUMN IF NOT EXISTS share_revoked_at TIMESTAMP WITH TIME ZONE;

UPDATE validation_report
SET share_token_hash = encode(sha256(convert_to(share_token, 'UTF8')), 'hex'),
    share_token_prefix = LEFT(share_token, 8),
    share_expires_at = LEAST(
      COALESCE(shared_at, created_at, NOW()) + INTERVAL '90 days',
      NOW() + INTERVAL '30 days')
WHERE share_token IS NOT NULL
  AND share_token_hash IS NULL;

ALTER TABLE validation_report DROP CONSTRAINT IF EXISTS validation_report_share_token_key;
DROP INDEX IF EXISTS idx_validation_report_share_token;

CREATE UNIQUE INDEX IF NOT EXISTS idx_validation_report_share_token_hash
  ON validation_report(share_token_hash)
  WHERE share_token_hash IS NOT NULL;

-- A previous application version may still write share_token during a rolling deployment. Convert
-- that write atomically in the database, bound it to seven days, and clear plaintext before commit.
CREATE OR REPLACE FUNCTION normalize_legacy_validation_report_bearer()
RETURNS trigger AS $$
BEGIN
  IF NEW.share_token IS NOT NULL THEN
    NEW.share_token_hash := encode(sha256(convert_to(NEW.share_token, 'UTF8')), 'hex');
    NEW.share_token_prefix := LEFT(NEW.share_token, 8);
    NEW.share_expires_at := NOW() + INTERVAL '7 days';
    NEW.share_revoked_at := NULL;
    NEW.share_token := NULL;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_validation_report_bearer_normalize ON validation_report;
CREATE TRIGGER trg_validation_report_bearer_normalize
BEFORE INSERT OR UPDATE OF share_token ON validation_report
FOR EACH ROW EXECUTE FUNCTION normalize_legacy_validation_report_bearer();

UPDATE validation_report SET share_token = NULL WHERE share_token IS NOT NULL;
