# Migrations Product — pending plan

> **Status:** PENDING (scope approved 2026-04-29; not yet started).
> **Branch when work begins:** `feature/v3-platform-expansion` (or
> a new branch off it; TBD at execution time).
> **R-### range reserved:** R-336 → R-345+ (catalog seed + polyglot
> expansion are multi-commit ongoing efforts).
> **Author:** 2026-04-29 thread, after codebase audit + competitor
> analysis + 4-angle scope discussion.

## One-line pitch

> *"Pick a migration recipe, see what it'll do across your repos
> before you commit, click apply, get PRs. Same recipe runs on
> every linked repo in your project — Express → Fastify, JS → TS,
> Tailwind v3 → v4. We show you file count, sample diff, and cost
> first."*

## What this is

A **separate product surface from Readymade Solutions**, focused
on **transforming existing code** (not adding new code).

Each migration:

- Is a **parametric recipe** declaring source-stack + target-stack +
  options (e.g. `express-to-fastify` with `{target_version: "5.x"}`).
- Reads the user's **codemap + detected stack** to confirm the
  source-stack matches before any LLM call.
- Can run across **every linked repo in a project** in one launch
  (multi-repo fan-out via the just-shipped Execution View).
- Goes through a **two-phase halt-and-inspect gate**: Phase 1
  enumerates files + estimates cost + shows a sample diff; Phase 2
  applies only after the user confirms.
- Eventually routes through a **hybrid pipeline**: deterministic
  AST/codemod pass first (cheap, fast); residual files go to V2
  worker with a recipe-specific AI fallback prompt. v1 is AI-only;
  hybrid lands in W8.
- Output is **PRs**, one per affected repo, consistent with our
  pipeline.

## Scope decisions (decided 2026-04-29)

| # | Question | Decision |
|---|----------|----------|
| 1 | **Hybrid AST + AI fallback** (Angle 1) | **In scope, staged.** v1 ships AI-only with parametric recipes; W8 layers in the deterministic-first pass per language. Schema designed with `deterministic_recipe_ref` field from W1 so W8 doesn't require re-architecture. |
| 2 | **Two-phase halt-and-inspect** (Angle 2) | **MUST in v1.** The wedge that distinguishes us from Cursor / Aider. Reuses existing plan-approval gate. |
| 3 | **Parametric + multi-repo fan-out** (Angle 3) | **MUST in v1.** The actual product. Reuses multi-repo Execution View just shipped in R-324. |
| 4 | **Continuous via PostMergeCascade** (Angle 4) | **In scope, v1.5.** Lands as W9 once catalog has 30+ recipes and notification discipline is designed. |
| 5 | Entity shape | **Sibling `migration_recipe` table; shared service classes.** Different scoping fields (source_stack, target_stack, affected_files_glob); same `VariantResolver` + `SlotValidator` + `SpecComposer` service engine as Readymades. |
| 6 | Per-language coverage | **TS/JS in v1; Python + Java in v1.5+.** ast-grep + jscodeshift integration paths are mature for TS/JS; OpenRewrite for Java is heaviest lift. |
| 7 | **Tier-stratified cost model** | **NEW — applies to BOTH this product AND Readymade Solutions.** See dedicated section below. |

## Tier-stratified cost model (cross-product)

The user clarified the billing model on 2026-04-29 in a way that
reshapes both this plan AND the Readymade Solutions plan:

- **FREE / PRO tiers — managed-key, credit-charged:**
  ExecuteSpec pays the LLM provider; user pays per-credit. **Cost
  caps are mandatory** — they protect us from runaway recipes that
  blow the user's wallet (and ours). UI shows estimated cost on
  every recipe card; Phase 1 plan-gate shows a hard cap input
  (default 5× typical × selected_repos).
- **TEAM / ENTERPRISE tiers — BYOK, fixed-subscription:**
  User brings their own LLM keys; pays a fixed monthly subscription
  for ExecuteSpec the **service** (not per-credit). The LLM cost
  is on them, billed direct by Anthropic / OpenAI / Google. **Cost
  caps are info-only** (we surface estimated cost so the user can
  budget against their LLM provider, but we don't *enforce* a cap
  — we're not the meter).

Implementation notes:

- `TierEnforcementService` already knows the user's tier; add a
  `requiresCostCap(tier)` predicate that returns true for
  FREE/PRO, false for TEAM/ENTERPRISE.
- `V2RunCostTracker` continues to track cost regardless (we want
  the data for analytics + recipe-cost calibration); the **gate**
  on `BudgetExceeded` events fires only when `requiresCostCap` is
  true.
- UI: cost-cap input → shown for FREE/PRO; for TEAM/ENTERPRISE,
  surface a "Estimated cost: $X (billed direct to your provider)"
  read-only chip.
- Per-recipe cost cards: visible to all tiers, but TEAM/ENTERPRISE
  see "Est. $X on your keys" framing instead of "X credits."
- BYOK enforcement is already in `ApiKeyResolver` + the existing
  TEAM/ENTERPRISE-only BYOK gate. No new wiring there.

This change carries to the **Readymade Solutions plan** too — same
tier-stratified UX. I'll update that plan in the same commit as
this one.

## Recipe seed list (v1 — TS/JS only)

The 11 recipes the v1 catalog ships with:

1. **JavaScript → TypeScript** — bulk file rename + tsconfig
   generation + per-file type inference + import path fixups.
2. **Express → Fastify** — route handler rewrites + middleware
   hook conversion + lifecycle hooks.
3. **Mongoose → Prisma** — schema introspection + Prisma model
   generation + query call-site rewrites.
4. **REST → tRPC** — REST endpoint enumeration + tRPC router
   scaffolding + type-safe client.
5. **Class components → hooks (React)** — `componentDidMount` →
   `useEffect`, `setState` → `useState`, lifecycle conversion.
6. **Next.js Pages Router → App Router** — `pages/` directory
   rewrite to `app/`, layout extraction, server-component split.
7. **Mocha → Vitest** — test file conversion, config swap,
   assertion-API compatibility shims where needed.
8. **Webpack → Vite** — `webpack.config.js` analysis + Vite config
   generation, loader → plugin mapping, dev-server swap.
9. **CommonJS → ESM** — `require` → `import`, `module.exports` →
   `export`, file extension fixups, package.json `"type": "module"`.
10. **Tailwind v3 → v4** — config migration, deprecated utility
    rewrites, color-token system update.
11. **Vue 2 → Vue 3** *(stretch)* — Options API → Composition API,
    `Vue.extend` → `defineComponent`, plugin compat.

Polyglot expansion (v1.5+):

- **Python:** Django 4 → 5, Flask → FastAPI, Mongo + PyMongo →
  PostgreSQL + SQLAlchemy.
- **Java:** Spring Boot 2 → 3, JUnit 4 → 5, Java 8 → 17/21 (per
  OpenRewrite recipe).
- **Go:** structural reorganisation recipes are an open question —
  Go's culture has fewer "migration" patterns than JS.

## Schema sketch (V159)

```sql
-- The abstract migration recipe. Variants live below.
CREATE TABLE migration_recipe (
  id              UUID PRIMARY KEY,
  slug            VARCHAR(64) UNIQUE NOT NULL,        -- "express-to-fastify"
  name            VARCHAR(160) NOT NULL,              -- "Express → Fastify"
  tagline         VARCHAR(255),                       -- card subtitle
  description     TEXT,                               -- detail page body
  source_stack    JSONB NOT NULL,                     -- { language:"typescript", framework:"express", min_version:"4.x" }
  target_stack    JSONB NOT NULL,                     -- { language:"typescript", framework:"fastify", target_version:"5.x" }
  affected_files_glob TEXT NOT NULL,                  -- e.g. "src/**/*.{js,ts}"
  category        VARCHAR(64) NOT NULL,               -- "framework-migration"
  tags            JSONB,                              -- ["express","fastify","backend"]
  tier            VARCHAR(16) NOT NULL DEFAULT 'PRO', -- FREE | PRO | TEAM | ENTERPRISE
  source          VARCHAR(16) NOT NULL DEFAULT 'FIRST_PARTY',
  status          VARCHAR(16) NOT NULL DEFAULT 'LISTED',
  -- W8 hybrid AST hook (NULL = AI-only path; populated when
  -- a deterministic AST recipe is wired alongside the AI fallback)
  deterministic_recipe_ref VARCHAR(255),              -- e.g. "ast-grep:./recipes/express-to-fastify.yml"
  ai_fallback_prompt_path VARCHAR(255),               -- prompts/migrations/<slug>-fallback.md
  -- Preservation contract: declared by recipe; reviewer enforces
  preservation_contract JSONB,                        -- e.g. { "preserve_routes": true, "preserve_types": true }
  est_files_low INT,
  est_files_high INT,
  est_credits_low NUMERIC(10,2),
  est_credits_high NUMERIC(10,2),
  icon_url        TEXT,
  thumbnail_url   TEXT,
  sample_pr_url   TEXT,
  author_user_id  VARCHAR(36),
  author_org_id   VARCHAR(36),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_migration_category ON migration_recipe (category, status);
CREATE INDEX idx_migration_tier     ON migration_recipe (tier, status);
CREATE INDEX idx_migration_source_stack ON migration_recipe USING GIN (source_stack);
CREATE INDEX idx_migration_fts      ON migration_recipe USING GIN
  (to_tsvector('english', name || ' ' || coalesce(description,'') || ' ' || coalesce(tagline,'')));

-- Variant = the concrete (language, framework, options) cell for
-- recipes whose source/target stack has internal variation.
-- (Unlike Readymades — most migrations have a single variant
-- because source-stack is fixed; variants exist for "Mongoose →
-- Prisma" where target db could be postgres / mysql / sqlite.)
CREATE TABLE migration_variant (
  id              UUID PRIMARY KEY,
  recipe_id       UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
  options_signature VARCHAR(160) NOT NULL,            -- "target_db=postgres,target_version=5.x"
  options         JSONB NOT NULL,                     -- { target_db:"postgres", target_version:"5.x" }
  spec_template   TEXT NOT NULL,                      -- migration spec with {{slot}} placeholders
  ai_fallback_prompt_path VARCHAR(255),               -- per-variant override of recipe's path
  est_credits_low  NUMERIC(10,2),
  est_credits_high NUMERIC(10,2),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (recipe_id, options_signature)
);

-- Slot = parameter the user fills before launch.
CREATE TABLE migration_slot (
  id           UUID PRIMARY KEY,
  recipe_id    UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
  name         VARCHAR(64) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  type         VARCHAR(32) NOT NULL,
  required     BOOLEAN NOT NULL DEFAULT FALSE,
  default_value TEXT,
  options      JSONB,
  description  TEXT,
  display_order INT NOT NULL DEFAULT 0,
  UNIQUE (recipe_id, name)
);

-- Dependency = "this migration requires recipe X to have been
-- applied first" or "this migration recommends running recipe X
-- afterward."
CREATE TABLE migration_dependency (
  recipe_id     UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
  depends_on_id UUID NOT NULL REFERENCES migration_recipe(id),
  kind          VARCHAR(16) NOT NULL,                  -- PREREQUISITE | RECOMMENDED_AFTER
  PRIMARY KEY (recipe_id, depends_on_id)
);

-- W3 dry-run cache — sample diffs cached per (recipe, stack-signature)
-- so we don't recompute every time the same recipe is previewed
-- against the same stack.
CREATE TABLE migration_dry_run_cache (
  recipe_id        UUID NOT NULL REFERENCES migration_recipe(id) ON DELETE CASCADE,
  stack_signature  VARCHAR(255) NOT NULL,             -- hash of detected_stack
  sample_diff      TEXT,                              -- truncated, ~10 KB
  est_files_low    INT,
  est_files_high   INT,
  est_cost_low     NUMERIC(10,2),
  est_cost_high    NUMERIC(10,2),
  generated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (recipe_id, stack_signature)
);
```

## Wave plan (10 waves)

### W1 — Schema + entity (R-336)

- V159 migration (5 tables above).
- JPA entities: `MigrationRecipe`, `MigrationVariant`,
  `MigrationSlot`, `MigrationDependency`, `MigrationDryRunCache`.
- Repos + DTOs.
- **Critical:** import (or move to a shared package) the
  `VariantResolver`, `SlotValidator`, `SpecComposer` services
  authored for Readymade Solutions Wave 5; both products use them.
- **Tests:** entity round-trip + repository basic queries.

### W2 — Recipe seeding infrastructure (R-337)

- New `MigrationRecipeSeeder` reads YAML catalog files at boot +
  on-demand reseeding via admin endpoint.
- Catalog format spec at `docs/migration-recipes-catalog-format.md`.
- Catalog file shape mirrors Readymades' format with
  migration-specific fields:
  ```yaml
  slug: express-to-fastify
  name: Express → Fastify
  source_stack: { language: typescript, framework: express, min_version: 4.x }
  target_stack: { language: typescript, framework: fastify, target_version: 5.x }
  affected_files_glob: "src/**/*.{js,ts}"
  category: framework-migration
  tier: PRO
  preservation_contract: { preserve_routes: true, preserve_middlewares: true }
  est_files: [10, 60]
  est_credits: [5, 30]
  slots:
    - { name: target_version, type: SELECT, options: ["5.x", "4.x-compat"], default: "5.x" }
  variants:
    - { options: { target_version: "5.x" }, spec_template: "..." }
  dependencies:
    - { slug: js-to-typescript, kind: RECOMMENDED_AFTER }
  ```
- Seed first 3 recipes (JS→TS, Express→Fastify, class→hooks) as
  proof-of-concept. Quality bar: each has source-stack detector,
  slot defaults, sample-diff fixture, end-to-end test against a
  fixture repo.

### W3 — Two-phase halt-and-inspect (Angle 2) (R-338)

- New `MigrationDryRunService` — light-weight scan + cost estimate:
  - **No LLM call yet** in v1 (cheap path). Just enumerate files
    matching `affected_files_glob` from the cached codemap.
  - **Cost estimate** from historical p25/p75 in
    `migration_dry_run_cache` (or seeded estimates from
    `migration_recipe.est_*` columns when no history exists).
  - **Sample diff** from `migration_dry_run_cache` if cached;
    otherwise: pick one representative file, run a single-file LLM
    call against the recipe's spec_template (one-shot, ~$0.05),
    cache the result. The cache survives across customers — same
    recipe + same stack signature = same sample.
- New endpoint: `POST /api/migrations/{slug}/dry-run` — returns
  Phase 1 plan: file count, cost estimate, sample diff, per-repo
  breakdown for multi-repo projects.
- Plan-approval gate (existing infra) handles the user's "Apply"
  click → Phase 2.

### W4 — Library API + multi-repo fan-out (Angle 3) (R-339)

- New `migrations/` package — `MigrationController`,
  `MigrationService`, `MigrationOrchestrator`.
- Endpoints:
  - `GET /api/migrations` — list with filters (source-stack,
    target-stack, category, tier, search, my-stack-only).
  - `GET /api/migrations/{slug}` — recipe detail + variants +
    slots + dependencies.
  - `POST /api/migrations/{slug}/dry-run` (W3, defined above).
  - `POST /api/migrations/{slug}/apply` — kicks off Phase 2.
    Multi-repo: same recipe, N parallel V2 runs, one per selected
    repo.
- Stack-aware filtering: `source_stack` matches detected stack;
  surface "won't work on this repo" hints if mismatched.
- Multi-repo dispatch reuses the multi-repo Execution View — each
  repo gets its own `ExecutionSolution` row, run in parallel.
- **Tests:** stack matching + multi-repo dispatch + dependency
  graph (e.g. RECOMMENDED_AFTER surfaces a follow-up suggestion).

### W5 — Frontend gallery + halt-and-inspect detail page (R-340)

- New route `/v3/migrations` — gallery with:
  - Source-stack facet rail ("I have Express", "I have Mongoose"...).
  - Target-stack secondary facet.
  - Free-text search.
  - Category badges + tier chip + lock icon for tier-gated.
  - Card: name, tagline, est. files, est. cost (FREE/PRO) /
    "Est. $X on your keys" (TEAM/ENTERPRISE).
- New route `/v3/migrations/:slug` — detail page with:
  - Two-phase UX: "Plan" tab (Phase 1) + "Apply" tab (Phase 2).
  - Plan tab shows: files matched per repo, sample diff (cached),
    cost estimate, repo selector multi-checkbox.
  - Apply CTA enabled only after Phase 1 has been computed.
  - Lock state: clicking a locked card → upgrade modal (existing
    flow; deep-link returns to migration detail).

### W6 — Tier-stratified cost model wiring (R-341)

- Extend `TierEnforcementService` with `requiresCostCap(tier)`
  predicate.
- Cost-cap input on Phase 1 page rendered conditionally on
  `requiresCostCap(callerTier)`.
- For TEAM/ENTERPRISE: read-only "Est. $X on your keys" chip
  instead.
- BYOK key usage: nothing new to wire (`ApiKeyResolver` already
  picks user-keys for TEAM/ENTERPRISE); just confirm the
  cost-tracker still records cost without enforcing a cap.
- **Apply same change in parallel to Readymade Solutions plan**
  (Wave 4 of that plan adds tier-stratified cost UX).

### W7 — Catalog seed (R-342, multi-commit ongoing)

- Author 8 of the 11 v1 TS/JS recipes:
  1. JS → TS
  2. Express → Fastify
  3. Mongoose → Prisma
  4. REST → tRPC
  5. Class components → hooks (React)
  6. Pages Router → App Router (Next.js)
  7. Mocha → Vitest
  8. Webpack → Vite
- Stretch in same wave: CommonJS → ESM, Tailwind v3 → v4, Vue 2 →
  Vue 3.
- Quality bar: each recipe ships with source-stack detector,
  preservation_contract, slot defaults, ai_fallback_prompt at
  `prompts/migrations/<slug>-fallback.md`, sample-diff fixture,
  end-to-end test against a fixture repo committed to the codebase.

### W8 — Hybrid AST + AI fallback (Angle 1, v1.5) (R-343)

- ast-grep integration:
  - Spawn ast-grep as subprocess via existing `ShellToolV2`
    sandbox (or include the Rust binary in the runtime image).
  - Per-recipe `deterministic_recipe_ref` column populated with
    `ast-grep:./recipes/<slug>.yml` reference.
- New `DeterministicPassRunner` service:
  - Run ast-grep first → captures residual files (where AST
    transform refused).
  - Hand off residuals only to V2 worker with the recipe's AI
    fallback prompt + tight per-file token cap.
- Cost-tracker: deterministic-pass files = $0; only LLM calls hit
  the meter.
- Verification: build + test pass + `preservation_contract`
  satisfied (declared by recipe; checked deterministically — e.g.
  for Express→Fastify, "every route in source is still served").

### W9 — Continuous via PostMergeCascade (Angle 4, v1.5) (R-344)

- New event type: `MigrationOpportunityDetected` in
  `harness/event/`.
- Recipe-matcher: when a Renovate-style PR merges, parse the dep
  changes (`package.json`, `pom.xml`, `requirements.txt` diffs) +
  find migration recipes whose source/target stack matches the
  bumped version range.
- Notification: in-app + email with one-click "Apply this migration"
  CTA → routes to `/v3/migrations/:slug` Phase-1 page.
- Notification dedup: max 1 suggestion per recipe per project per
  30 days. Rate-limit: max 3 migration suggestions per project per
  week.
- Settings: org-admin can disable continuous-suggestions per project.

### W10 — Polyglot expansion (R-345+, multi-commit ongoing)

- Python: Django 4→5, Flask→FastAPI, PyMongo→SQLAlchemy.
- Java: Spring Boot 2→3, JUnit 4→5, Java 17/21 (probably wraps
  OpenRewrite recipes since their JVM coverage is years deep —
  authoring against OpenRewrite is faster than reinventing).
- Go: deferred — fewer migration patterns in the ecosystem.

## Out of scope (v1) — explicit list

| Deferred to | Item |
|---|---|
| v1.5 (W8) | Hybrid AST + AI fallback per-language |
| v1.5 (W9) | Continuous suggestions via PostMergeCascade |
| v1.5+ (W10) | Polyglot recipes (Python, Java, Go) |
| v2 | Custom recipe authoring inside the UI (community + first-party) |
| v2 | Migration recipe versioning + rollback |
| v2 | Cross-recipe orchestration (run a sequence of migrations as one launch) |
| v2 | Migration "dry-run" that re-runs the full recipe LLM-side without applying (today's dry-run is sample-only) |
| Roadmap | Migration marketplace with revenue share |
| Roadmap | Migration recipe import from OpenRewrite / Codemod.com registries |

## Effort + cadence

- **10 waves**; W7 (catalog seed) and W10 (polyglot) are
  multi-commit ongoing.
- Plumbing (W1-W6, W8, W9) is ~10-12 commits, **6-8 weeks of
  focused work**.
- Catalog seed is **2-3 quarters** of authoring at the v1 quality
  bar. Can accelerate by importing community-contributed
  ast-grep / jscodeshift recipes once W8 lands.
- **L total** — comparable to Readymade Solutions in scale.

## Cross-references

- Codebase: V2 pipeline (`V2RunOrchestrator`, `WorkerAgentV2`),
  `StackDetector`, `ProjectArchitectureService`, multi-repo
  Execution View (R-324 just shipped),
  `PostMergeCascadeService`, `ApiKeyResolver` (BYOK).
- Schema: V159 (this plan); also touches future Wave 6 of
  Readymades plan for the shared tier-stratified cost wiring.
- Industry context: `nx migrate` halt-and-inspect, OpenRewrite
  recipe model, Codemod.com Registry, Sourcegraph Batch Changes.
- Related plans:
  - [pending-readymade-solutions-plan.md](pending-readymade-solutions-plan.md) —
    sibling product; shares VariantResolver/SlotValidator/
    SpecComposer service classes; tier-stratified cost UX shared.

## Open questions for execution-time (NOT blockers)

- **`affected_files_glob` semantics.** Glob (e.g. `src/**/*.tsx`)
  is simple. Some recipes need glob + content-predicate ("*.tsx
  files containing `class .* extends React.Component`"). Lean:
  ship glob-only in v1; add content-predicate in W7 if first 3
  recipes need it.
- **Sample-diff cache invalidation.** When does
  `migration_dry_run_cache` become stale? Per-recipe definition
  change is obvious (cache-bust on recipe update). What about
  changes to the user's stack? Lean: stack_signature includes
  major-version of detected stack; minor changes don't invalidate.
- **OpenRewrite integration plan.** W10's Java recipes can either
  reinvent (slow, low-quality) or wrap OpenRewrite (fast, high
  quality but adds a JVM-runtime dependency for ourselves). Lean:
  wrap OpenRewrite when W10 starts; don't re-litigate now.
- **Multi-repo cost-cap math.** Default is `est_cost_high ×
  selected_repos × 1.2`. For a 10-repo migration this can be $50+.
  Need a max-cap per FREE/PRO tier (probably $20 / $50) so a
  curious user doesn't accidentally trigger the ceiling.
- **Preservation-contract definition format.** Each recipe declares
  what must be preserved. Format: JSON dictionary in v1
  (`{ preserve_routes: true, preserve_types: true }`); recipe
  authors document the keys they support. v1.5: a typed schema +
  validators per key.
