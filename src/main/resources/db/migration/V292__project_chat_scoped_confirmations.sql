-- CHAT-08: one human confirmation authorizes one matching project-chat action only.
-- Bearer tokens are returned once; only SHA-256 digests are stored.

CREATE TABLE project_chat_confirmation (
    id UUID PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    org_id VARCHAR(255) NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    actor_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    action VARCHAR(48) NOT NULL,
    capability VARCHAR(96) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(160) NOT NULL,
    target_json JSONB NOT NULL,
    target_hash VARCHAR(64) NOT NULL,
    scope_hash VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    issued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_project_chat_confirmation_status
        CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT ck_project_chat_confirmation_lifecycle
        CHECK ((status = 'CONSUMED' AND consumed_at IS NOT NULL)
            OR (status <> 'CONSUMED' AND consumed_at IS NULL)),
    CONSTRAINT ck_project_chat_confirmation_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_project_chat_confirmation_hashes CHECK (
        target_hash ~ '^[0-9a-f]{64}$'
        AND scope_hash ~ '^[0-9a-f]{64}$'
        AND token_hash ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX uq_project_chat_confirmation_token_hash
    ON project_chat_confirmation (token_hash);
CREATE INDEX idx_project_chat_confirmation_actor_pending
    ON project_chat_confirmation (project_id, actor_user_id, expires_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE project_chat_confirmation IS
    'Hashed, actor/tenant/project/route/target-scoped one-time chat action authority';
COMMENT ON COLUMN project_chat_confirmation.target_json IS
    'Canonical action-specific identifiers only; no prompt, credential, PAT, or provider key';

-- The existing activity_audit hash chain was application-conventional, not DB-immutable. Reject
-- all UPDATE/DELETE operations at the database boundary. V292 application writers also take a
-- transaction-scoped advisory lock before reading the chain tail, making append order cross-JVM
-- safe while preserving every existing read API and row shape.
CREATE OR REPLACE FUNCTION reject_activity_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'activity_audit is append-only';
END;
$$;

CREATE TRIGGER trg_activity_audit_append_only
    BEFORE UPDATE OR DELETE ON activity_audit
    FOR EACH ROW EXECUTE FUNCTION reject_activity_audit_mutation();
