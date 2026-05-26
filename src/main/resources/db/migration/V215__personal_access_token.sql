-- 2026-05-26 — Personal Access Tokens (programmatic auth for the CLI + VS Code extension).
--
-- The browser uses a short-lived access JWT (1h, Authorization: Bearer) + a refresh token
-- delivered as an httpOnly cookie scoped to /api/auth. That cookie flow is browser-shaped and
-- does not fit a headless client (CLI / CI) or an editor extension, and OAuth-only users have
-- no password to log in with from a terminal at all.
--
-- A PAT is a long-lived, revocable credential the user generates in the web UI (Settings → API
-- tokens) and pastes into a client. It is presented exactly like a JWT — `Authorization: Bearer
-- es_pat_…` — and JwtAuthenticationFilter resolves it to the SAME AppUserDetails (same global +
-- org role, same tenant) as a JWT for that user, so every downstream access guard behaves
-- identically.
--
-- Storage: the plaintext token is shown to the user exactly once at creation and never persisted.
-- Only token_hash = SHA-256(plaintext) is stored, and it is the lookup key (high-entropy random
-- token ⇒ a fast digest is appropriate; bcrypt would prevent the indexed lookup). token_prefix is
-- the first chars of the plaintext, kept for display in the token list ("es_pat_AbCd…").

CREATE TABLE IF NOT EXISTS personal_access_token (
    id            VARCHAR(64) PRIMARY KEY,
    user_id       VARCHAR(64) NOT NULL,
    -- User-chosen label, e.g. "laptop CLI" or "CI". Shown in the management list.
    name          VARCHAR(100) NOT NULL,
    -- SHA-256 hex of the full plaintext token. The credential itself is never stored.
    token_hash    VARCHAR(64) NOT NULL,
    -- First chars of the plaintext ("es_pat_" + a few random chars) — display only, not a secret.
    token_prefix  VARCHAR(16) NOT NULL,
    -- Comma-separated scope list. v1 issues "full" (acts as the user); reserved for future
    -- per-scope enforcement without a follow-up migration.
    scopes        VARCHAR(255) NOT NULL DEFAULT 'full',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Throttled (≤ once / 5 min) on use, so this is an audit hint, not an exact last-call clock.
    last_used_at  TIMESTAMPTZ,
    -- NULL ⇒ never expires.
    expires_at    TIMESTAMPTZ,
    -- Set on revoke; a revoked row is kept for the audit trail but never authenticates.
    revoked_at    TIMESTAMPTZ
);

-- Hot path: every PAT-authenticated request looks the token up by its hash, excluding revoked rows.
CREATE UNIQUE INDEX IF NOT EXISTS idx_personal_access_token_hash
    ON personal_access_token (token_hash);

-- Management list: a user's tokens, newest first.
CREATE INDEX IF NOT EXISTS idx_personal_access_token_user
    ON personal_access_token (user_id, created_at DESC);
