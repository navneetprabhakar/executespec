-- V302: Static UI preview render — durable job + admitted bundles.
--
-- The preview render agent produces a self-contained HTML approximation of a run's screens without
-- building a container or deploying anything. Two facts make the storage shape what it is:
--
--  1. The render is decorative. It never feeds verifiedScore, the verification ledger, or any
--     validation evidence, so there is deliberately no foreign key into scoring tables and no
--     column any scorer reads. An LLM's drawing of a screen is not proof the screen works.
--  2. Only guard-admitted bundles are stored. A bundle that failed the offline/credential gate is
--     recorded as a FAILED surface row with a category, never with its HTML, so rejected model
--     output does not persist anywhere it could later be served.

CREATE TABLE IF NOT EXISTS preview_render_job (
    id                UUID PRIMARY KEY,
    run_id            VARCHAR(64)  NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    requested_by      VARCHAR(64),
    provider          VARCHAR(32),
    model             VARCHAR(160),
    surfaces_found    INT          NOT NULL DEFAULT 0,
    surfaces_rendered INT          NOT NULL DEFAULT 0,
    failure_reason    VARCHAR(160),
    -- Restart recovery. The instance executing a render claims the row by writing lease_owner and
    -- a bounded lease_expires_at, and renews while it works. Every later transition is fenced on
    -- lease_owner, so a job whose instance died is reclaimed once the lease lapses instead of
    -- staying RENDERING forever. Written and read by PreviewRenderService / the sweeper; the
    -- active-run uniqueness that makes this single-flight arrives in V306.
    lease_owner       VARCHAR(120),
    lease_expires_at  TIMESTAMPTZ,
    requested_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_preview_render_job_run ON preview_render_job (run_id);
CREATE INDEX IF NOT EXISTS idx_preview_render_job_status ON preview_render_job (status);

-- One row per discovered surface. Surviving rows with status RENDERED carry admitted HTML.
CREATE TABLE IF NOT EXISTS preview_render_bundle (
    id              UUID PRIMARY KEY,
    job_id          UUID         NOT NULL REFERENCES preview_render_job (id) ON DELETE CASCADE,
    run_id          VARCHAR(64)  NOT NULL,
    surface_id      VARCHAR(120) NOT NULL,
    source_path     TEXT         NOT NULL,
    display_name    TEXT         NOT NULL,
    render_target   VARCHAR(32)  NOT NULL,
    fidelity        VARCHAR(24)  NOT NULL,
    viewport_width  INT          NOT NULL,
    viewport_height INT          NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    -- NULL whenever status <> 'RENDERED'. Rejected output is never persisted.
    html            TEXT,
    failure_reason  VARCHAR(160),
    display_order   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_preview_render_bundle_run ON preview_render_bundle (run_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_preview_render_bundle_job_surface
    ON preview_render_bundle (job_id, surface_id);

-- Seeded OFF. The render spends managed AI credits on a WORKHORSE pass per surface, so it stays
-- dark until an operator enables it deliberately:
--   PUT /api/admin/feature-flags/preview.render-agent.enabled  {"value":"true"}
-- Never flip this with a direct UPDATE: FeatureFlagService caches in-memory and invalidates via
-- Redis pub/sub, so a raw write neither takes effect live nor propagates across instances.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES (
    'preview.render-agent.enabled',
    'false',
    'BOOLEAN',
    'GLOBAL',
    'On-demand static UI preview render (no container, no deploy). Produces a sandboxed HTML approximation of a run''s screens. Decorative only — never contributes to verifiedScore or validation evidence.'
)
ON CONFLICT (flag_key) DO UPDATE
    SET description = EXCLUDED.description;
