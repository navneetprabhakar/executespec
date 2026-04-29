-- V157 — Readymade Solutions Wave 1 (R-327): library schema.
--
-- A library of pluggable, searchable building blocks (sign-in,
-- payments, notifications, data, AI, etc.) that ship PRs adapting
-- to the user's stack.  Per the plan at
-- docs/plans/pending-readymade-solutions-plan.md.
--
-- 5 tables shipped here:
--   readymade_solution     — abstract library entry
--   readymade_variant      — concrete (language, framework, options) cell
--   readymade_slot         — parameter the user fills before launch
--   readymade_dependency   — solution-on-solution composition graph
--   readymade_favourite    — per-user pin / saved-list

-- ── readymade_solution ─────────────────────────────────────────────────
CREATE TABLE readymade_solution (
    id               UUID PRIMARY KEY,
    slug             VARCHAR(64)  UNIQUE NOT NULL,
    name             VARCHAR(160) NOT NULL,
    tagline          VARCHAR(255),
    description      TEXT,
    category         VARCHAR(64)  NOT NULL,
    macro_group      VARCHAR(32)  NOT NULL,
    tags             JSONB,                              -- ["auth","oauth","social"]
    kind             VARCHAR(16)  NOT NULL,              -- PRIMITIVE | STACK | INTEGRATION
    tier             VARCHAR(16)  NOT NULL DEFAULT 'PRO',
    source           VARCHAR(16)  NOT NULL DEFAULT 'FIRST_PARTY',
    status           VARCHAR(16)  NOT NULL DEFAULT 'LISTED',
    icon_url         TEXT,
    thumbnail_url    TEXT,
    sample_pr_url    TEXT,
    est_files_low    INT,
    est_files_high   INT,
    est_credits_low  NUMERIC(10,2),
    est_credits_high NUMERIC(10,2),
    author_user_id   VARCHAR(36),
    author_org_id    VARCHAR(36),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_readymade_category ON readymade_solution (category, status);
CREATE INDEX idx_readymade_macro    ON readymade_solution (macro_group, status);
CREATE INDEX idx_readymade_tier     ON readymade_solution (tier, status);
CREATE INDEX idx_readymade_source   ON readymade_solution (source, status);

-- Full-text search index for the searchable library:
CREATE INDEX idx_readymade_fts ON readymade_solution USING GIN (
    to_tsvector(
        'english',
        name || ' ' || coalesce(description, '') || ' ' || coalesce(tagline, '')
    )
);

COMMENT ON TABLE readymade_solution IS
    'R-327 — abstract entry in the Readymade Solutions library; concrete
     code generation lives in readymade_variant rows referencing this id.';

-- ── readymade_variant ──────────────────────────────────────────────────
CREATE TABLE readymade_variant (
    id               UUID PRIMARY KEY,
    solution_id      UUID NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
    language         VARCHAR(32)  NOT NULL,             -- typescript / python / java / go / ...
    framework        VARCHAR(64)  NOT NULL,             -- express / fastify / spring-boot / fastapi / ...
    options          JSONB,                             -- {provider:"google", db:"postgres", style:"server-action"}
    spec_template    TEXT NOT NULL,                     -- spec text with {{slot}} placeholders
    scaffold_key     VARCHAR(64),                       -- optional ref to prompts/scaffolds/<key>.md
    est_credits_low  NUMERIC(10,2),                     -- per-variant override; falls back to parent solution
    est_credits_high NUMERIC(10,2),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_readymade_variant_solution ON readymade_variant (solution_id);
CREATE INDEX idx_readymade_variant_stack    ON readymade_variant (language, framework);

COMMENT ON TABLE readymade_variant IS
    'R-327 — concrete (language, framework, options) cell of a solution.
     Runtime variant resolution (W3) picks one based on the user repo''s
     detected stack.';

-- ── readymade_slot ─────────────────────────────────────────────────────
CREATE TABLE readymade_slot (
    id            UUID PRIMARY KEY,
    solution_id   UUID NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
    name          VARCHAR(64)  NOT NULL,
    display_name  VARCHAR(160) NOT NULL,
    type          VARCHAR(32)  NOT NULL,                -- TEXT|SELECT|SECRET|REPO_PICKER|FILE_PATH|NUMBER|BOOLEAN
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value TEXT,
    options       JSONB,                                -- for SELECT: ["USD","EUR","INR","GBP"]
    description   TEXT,
    display_order INT          NOT NULL DEFAULT 0,
    UNIQUE (solution_id, name)
);

CREATE INDEX idx_readymade_slot_solution ON readymade_slot (solution_id, display_order);

COMMENT ON TABLE readymade_slot IS
    'R-327 — parameter the user fills before instantiating a solution.';

-- ── readymade_dependency ───────────────────────────────────────────────
CREATE TABLE readymade_dependency (
    solution_id   UUID    NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
    depends_on_id UUID    NOT NULL REFERENCES readymade_solution(id),
    required      BOOLEAN NOT NULL DEFAULT TRUE,        -- false = "recommended pairing"
    PRIMARY KEY (solution_id, depends_on_id)
);

CREATE INDEX idx_readymade_dep_depends_on ON readymade_dependency (depends_on_id);

COMMENT ON TABLE readymade_dependency IS
    'R-327 — composition graph. Required = auto-install if missing.
     Optional (required=false) = recommended pairing surfaced in UI.';

-- ── readymade_favourite ────────────────────────────────────────────────
CREATE TABLE readymade_favourite (
    user_id     VARCHAR(36) NOT NULL,
    solution_id UUID        NOT NULL REFERENCES readymade_solution(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, solution_id)
);

CREATE INDEX idx_readymade_fav_solution ON readymade_favourite (solution_id);

COMMENT ON TABLE readymade_favourite IS
    'R-327 — per-user pin so users can keep frequently-used solutions
     close at hand on the gallery surface.';
