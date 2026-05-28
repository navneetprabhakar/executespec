-- =====================================================================
-- V218: spec_chat_audit table — audit log for the CLI/Plugin chat endpoint.
--
-- RL-2026-05-052 — CLI/Plugin Phase 1 (per docs/design/cli-plugin-spec-shape-b-2026-05-28.md
-- decision #2: chat persistence is LOCAL-ONLY). The server never persists
-- conversation content (no message bodies). Only an audit-row per session
-- is kept, so we can:
--   - measure usage + cost + provider mix without seeing the content
--   - revoke a session forensically if a PAT is compromised
--   - bill enterprise customers per chat session
--
-- Schema is intentionally minimal: nothing here references message text.
-- =====================================================================

-- 2026-05-28 fix on uncommitted V218: project / app_user / org all use VARCHAR(255) PKs (the
-- entities declare String id, not UUID). FKs must match the referenced column's type, so the
-- VARCHAR widths here mirror the existing convention used by V102/V113/V115 and friends.
-- session_id stays a string too — the client picks the UUID format on the wire, but the column
-- accepts any opaque session identifier.
CREATE TABLE spec_chat_audit (
    id            VARCHAR(64)  PRIMARY KEY,
    session_id    VARCHAR(64)  NOT NULL,                      -- client-generated, persisted locally
    project_id    VARCHAR(255) REFERENCES project(id) ON DELETE SET NULL,
    user_id       VARCHAR(255) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    org_id        VARCHAR(255) NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_turn_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    turn_count    INTEGER      NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(12,4) NOT NULL DEFAULT 0,
    model_used    VARCHAR(128),
    ip_hash       VARCHAR(64),                                -- SHA-256(ip), no raw IP retained
    closed_at     TIMESTAMPTZ
);

CREATE INDEX idx_spec_chat_audit_session  ON spec_chat_audit (session_id);
CREATE INDEX idx_spec_chat_audit_user     ON spec_chat_audit (user_id, started_at DESC);
CREATE INDEX idx_spec_chat_audit_org      ON spec_chat_audit (org_id, started_at DESC);
CREATE INDEX idx_spec_chat_audit_project  ON spec_chat_audit (project_id, started_at DESC)
    WHERE project_id IS NOT NULL;

COMMENT ON TABLE spec_chat_audit IS
  'CLI/Plugin chat audit (V218, RL-2026-05-052). Per-session row; NO message content stored.';
COMMENT ON COLUMN spec_chat_audit.session_id IS
  'Client-generated session UUID, persisted locally in ~/.executespec/sessions/<uuid>.json';
COMMENT ON COLUMN spec_chat_audit.ip_hash IS
  'SHA-256 of the client IP — for revoke / abuse correlation. Raw IP is never retained.';
COMMENT ON COLUMN spec_chat_audit.turn_count IS
  'Number of user-assistant turn pairs in the session. Updated each /api/spec/chat POST.';
