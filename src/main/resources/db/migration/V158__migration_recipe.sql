-- V158 — Migrations product Wave 1 (R-336): recipe schema.
--
-- Sibling product to Readymade Solutions (V157).  While Readymades
-- ADD new code, Migrations TRANSFORM existing code (Express → Fastify,
-- JavaScript → TypeScript, Mongoose → Prisma, REST → tRPC, etc.).
-- See docs/plans/pending-migrations-plan.md for the full product
-- shape; this migration is the W1 schema only.
--
-- 5 tables shipped here:
--   migration_recipe         — abstract recipe entry
--   migration_variant        — concrete (options) cell when source/target stack varies
--   migration_slot           — parameter the user fills before launch
--   migration_dependency     — recipe-on-recipe ordering graph
--   migration_dry_run_cache  — sample-diff + cost-estimate cache (W3)
--
-- Note: V158 (this) was originally reserved as V159 in the plan doc;
-- bumped to sequential V158 since V157 was Readymades W1 and skipping
-- numbers serves no purpose.

-- ── migration_recipe ───────────────────────────────────────────────────
CREATE TABLE migration_recipe (
    id                       UUID PRIMARY KEY,
    slug                     VARCHAR(64)  UNIQUE NOT NULL,
    name                     VARCHAR(160) NOT NULL,
    tagline                  VARCHAR(255),
    description              TEXT,
    -- Source-stack predicate: { language, framework, min_version }
    source_stack             JSONB NOT NULL,
    -- Target-stack target spec: { language, framework, target_version }
    target_stack             JSONB NOT NULL,
    -- File scope predicate (e.g. "src/**/*.{js,ts}"). Glob-only in v1;
    -- content-predicate added later if first 3 recipes need it.
    affected_files_glob      TEXT NOT NULL,
    category                 VARCHAR(64) NOT NULL,
    tags                     JSONB,
    tier                     VARCHAR(16) NOT NULL DEFAULT 'PRO',
    source                   VARCHAR(16) NOT NULL DEFAULT 'FIRST_PARTY',
    status                   VARCHAR(16) NOT NULL DEFAULT 'LISTED',
    -- W8 hybrid AST hook. NULL = AI-only path; populated when a
    -- deterministic AST recipe is wired alongside the AI fallback.
    deterministic_recipe_ref VARCHAR(255),
    ai_fallback_prompt_path  VARCHAR(255),
    -- Preservation contract declared by recipe author; reviewer enforces.
    -- e.g. {"preserve_routes": true, "preserve_types": true}
    preservation_contract    JSONB,
    est_files_low            INT,
    est_files_high           INT,
    est_credits_low          NUMERIC(10,2),
    est_credits_high         NUMERIC(10,2),
    icon_url                 TEXT,
    thumbnail_url            TEXT,
    sample_pr_url            TEXT,
    author_user_id           VARCHAR(36),
    author_org_id            VARCHAR(36),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_migration_category     ON migration_recipe (category, status);
CREATE INDEX idx_migration_tier         ON migration_recipe (tier, status);
CREATE INDEX idx_migration_source       ON migration_recipe (source, status);
CREATE INDEX idx_migration_source_stack ON migration_recipe USING GIN (source_stack);

-- Full-text search index for the searchable migration library:
CREATE INDEX idx_migration_fts ON migration_recipe USING GIN (
    to_tsvector(
        'english',
        name || ' ' || coalesce(description, '') || ' ' || coalesce(tagline, '')
    )
);

COMMENT ON TABLE migration_recipe IS
    'R-336 — abstract migration recipe (Express → Fastify, JS → TS,
     etc.).  Variants live below for recipes whose source/target stack
     has internal variation (e.g. Mongoose → Prisma where the target
     DB could be postgres / mysql / sqlite).';

-- ── migration_variant ──────────────────────────────────────────────────
CREATE TABLE migration_variant (
    id                       UUID PRIMARY KEY,
    recipe_id                UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
    -- Concise human-readable signature for the (options) cell so
    -- callers can route by name without parsing the JSONB.
    options_signature        VARCHAR(160) NOT NULL,
    -- e.g. {"target_db":"postgres","target_version":"5.x"}
    options                  JSONB NOT NULL,
    -- Migration spec text with {{slot}} placeholders.
    spec_template            TEXT NOT NULL,
    -- Per-variant override of the recipe's ai_fallback_prompt_path.
    ai_fallback_prompt_path  VARCHAR(255),
    est_credits_low          NUMERIC(10,2),
    est_credits_high         NUMERIC(10,2),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (recipe_id, options_signature)
);

CREATE INDEX idx_migration_variant_recipe ON migration_variant (recipe_id);

COMMENT ON TABLE migration_variant IS
    'R-336 — concrete (options) cell of a migration recipe.  Most
     recipes have a single variant because source-stack is fixed;
     variants exist for recipes where target-stack options branch.';

-- ── migration_slot ─────────────────────────────────────────────────────
CREATE TABLE migration_slot (
    id            UUID PRIMARY KEY,
    recipe_id     UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
    name          VARCHAR(64) NOT NULL,
    display_name  VARCHAR(160) NOT NULL,
    type          VARCHAR(32) NOT NULL,
    required      BOOLEAN NOT NULL DEFAULT FALSE,
    default_value TEXT,
    options       JSONB,
    description   TEXT,
    display_order INT NOT NULL DEFAULT 0,
    UNIQUE (recipe_id, name)
);

CREATE INDEX idx_migration_slot_recipe ON migration_slot (recipe_id, display_order);

COMMENT ON TABLE migration_slot IS
    'R-336 — parameter the user fills before instantiating a recipe.
     Mirrors readymade_slot semantics; the W3 SlotValidator + W5
     SpecComposer service classes are shared across both products.';

-- ── migration_dependency ───────────────────────────────────────────────
CREATE TABLE migration_dependency (
    recipe_id     UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
    depends_on_id UUID NOT NULL REFERENCES migration_recipe(id),
    -- PREREQUISITE = "X must have been applied first"
    -- RECOMMENDED_AFTER = "Y is suggested as a follow-up"
    kind          VARCHAR(16) NOT NULL,
    PRIMARY KEY (recipe_id, depends_on_id)
);

CREATE INDEX idx_migration_dep_depends_on ON migration_dependency (depends_on_id);

COMMENT ON TABLE migration_dependency IS
    'R-336 — recipe ordering graph.  PREREQUISITE = hard precedence
     (gate-block on apply); RECOMMENDED_AFTER = surface as suggestion.';

-- ── migration_dry_run_cache ────────────────────────────────────────────
CREATE TABLE migration_dry_run_cache (
    recipe_id        UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
    -- Hash of detected_stack so the cache is one-row-per-(recipe, stack).
    -- Major-version of detected stack participates; minor doesn't.
    stack_signature  VARCHAR(255) NOT NULL,
    -- Truncated (~10 KB cap) sample diff from one representative file.
    sample_diff      TEXT,
    est_files_low    INT,
    est_files_high   INT,
    est_cost_low     NUMERIC(10,2),
    est_cost_high    NUMERIC(10,2),
    generated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (recipe_id, stack_signature)
);

COMMENT ON TABLE migration_dry_run_cache IS
    'R-336 — sample-diff + cost-estimate cache for the W3 two-phase
     halt-and-inspect plan-gate.  Survives across customers — same
     recipe + same stack signature = same sample.';
