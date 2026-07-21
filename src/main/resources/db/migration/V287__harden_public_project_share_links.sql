-- Public project-share links are bearer credentials. Store only SHA-256(token), require a bounded
-- expiry for every active link, and append a tamper-resistant access/lifecycle ledger.

ALTER TABLE project_share_link
  ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64),
  ADD COLUMN IF NOT EXISTS token_prefix VARCHAR(12);

-- PostgreSQL's built-in sha256(bytea) keeps the plaintext inside this one atomic migration. Existing
-- URLs remain valid because incoming tokens are hashed before lookup after this migration.
UPDATE project_share_link
SET token_hash = encode(sha256(convert_to(token, 'UTF8')), 'hex'),
    token_prefix = LEFT(token, 8)
WHERE token_hash IS NULL AND token IS NOT NULL;

-- Historical no-expiry links receive a conservative 30-day grace period from this migration. A
-- revoked link stays revoked. New links are bounded by the service to 1..90 days (default 7).
UPDATE project_share_link
SET expires_at = LEAST(created_at + INTERVAL '90 days', NOW() + INTERVAL '30 days')
WHERE expires_at IS NULL;

ALTER TABLE project_share_link
  ALTER COLUMN token DROP NOT NULL,
  ALTER COLUMN token_hash SET NOT NULL,
  ALTER COLUMN token_prefix SET NOT NULL,
  ALTER COLUMN expires_at SET NOT NULL;

DROP INDEX IF EXISTS idx_project_share_link_token;
CREATE UNIQUE INDEX IF NOT EXISTS idx_project_share_link_token_hash
  ON project_share_link(token_hash);

-- The plaintext is no longer needed after the digest is durable. Keep the nullable legacy column
-- for rolling-deploy schema compatibility; application code never writes or reads it.
UPDATE project_share_link SET token = NULL WHERE token IS NOT NULL;

CREATE TABLE IF NOT EXISTS project_share_link_audit (
  id                   UUID PRIMARY KEY,
  link_id              UUID NOT NULL REFERENCES project_share_link(id),
  project_id           VARCHAR(255) NOT NULL REFERENCES project(id),
  event_type           VARCHAR(16) NOT NULL,
  outcome              VARCHAR(48) NOT NULL,
  surface              VARCHAR(32) NOT NULL,
  actor_user_id        VARCHAR(255),
  client_ip_hash       VARCHAR(64),
  user_agent_hash      VARCHAR(64),
  occurred_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_project_share_link_audit_event
    CHECK (event_type IN ('CREATE', 'ACCESS', 'DENIED', 'REVOKE'))
);

CREATE INDEX IF NOT EXISTS idx_project_share_link_audit_link_time
  ON project_share_link_audit(link_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_share_link_audit_project_time
  ON project_share_link_audit(project_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_project_share_link_audit_denied_time
  ON project_share_link_audit(project_id, occurred_at DESC)
  WHERE event_type = 'DENIED';

-- Audit facts are append-only at the database boundary, not merely by repository convention.
CREATE OR REPLACE FUNCTION reject_project_share_link_audit_mutation()
RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'project_share_link_audit is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_project_share_link_audit_append_only ON project_share_link_audit;
CREATE TRIGGER trg_project_share_link_audit_append_only
BEFORE UPDATE OR DELETE ON project_share_link_audit
FOR EACH ROW EXECUTE FUNCTION reject_project_share_link_audit_mutation();
