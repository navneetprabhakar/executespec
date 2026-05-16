# Migration Recipes — Catalog Format

> Migrations product. A "Migration Recipe" is a packaged, deterministic
> codemod over an existing codebase: Express to Fastify, JavaScript to
> TypeScript, Mongoose to Prisma, axios to fetch, and so on. Recipes are
> first-party YAML on disk; the database tables are the runtime-readable
> mirror, seeded from disk at boot. Recipes are the sister surface to
> Readymade Solutions and intentionally share the same shape (variants,
> slots, dependencies, tier-lock decoration).

This document describes the on-disk format, the database schema it seeds
into, the two-phase plan-gate (dry-run plus apply) runtime flow, and the
operator playbook for adding a new recipe.

Source-of-truth code lives under
`src/main/java/com/navneet/executespec/migration/` and on-disk YAML lives
under `src/main/resources/migration-recipes/*.yml`. The schema lands in
DB via Flyway migration `V158__migration_recipe.sql` (with widening
follow-up `V161__widen_migration_dependency_kind.sql`).
Recipe-instantiated runs are tagged on `execution_run` by
`V159__execution_run_migration_recipe.sql`.

---

## 1. Entity overview

Five tables in `migration/entity/`. The shape mirrors the Readymade
package so the shared `SlotValidator` plus `SpecComposer` work on both,
plus an extra **dry-run cache** table for the plan-gate path that does
not exist in Readymades.

```
                migration_recipe
                (abstract entry; tier, source_stack, target_stack)
                       |
   +------------+------+-------+-----------------------+
   |            |              |                       |
   v            v              v                       v
migration_     migration_     migration_dependency   migration_dry_run_cache
variant        slot           (recipe -> recipe,     (per (recipe_id,
(options       (TEXT,          PREREQUISITE vs        stackSignature)
 signature,    SELECT,         RECOMMENDED_AFTER)     sample-diff +
 spec_template,etc.)                                  cost-estimate cache)
 ai_fallback_
 prompt_path)
```

### 1.1 `migration_recipe` — the abstract catalog entry

Schema in
[`MigrationRecipe.java`](../src/main/java/com/navneet/executespec/migration/entity/MigrationRecipe.java)
plus V158.

| Column                       | Type             | Notes                                                                                              |
| ---------------------------- | ---------------- | -------------------------------------------------------------------------------------------------- |
| `id`                         | UUID             | PK. Default `gen_random_uuid()`.                                                                   |
| `slug`                       | VARCHAR(64)      | UNIQUE; stable identifier (e.g. `express-to-fastify`).                                             |
| `name`                       | VARCHAR(160)     | NOT NULL. Gallery card title.                                                                      |
| `tagline`                    | VARCHAR(255)     | One-liner under the title.                                                                         |
| `description`                | TEXT             | Long-form rendered in detail view.                                                                 |
| `source_stack`               | jsonb            | NOT NULL. The "from" stack predicate — `{language, framework, min_version?}`. See section 4.        |
| `target_stack`               | jsonb            | NOT NULL. The "to" stack — `{language, framework, version?}`. See section 4.                       |
| `affected_files_glob`        | TEXT             | NOT NULL. Filename glob the codemod is scoped to (e.g. `**/*.{js,ts}`).                            |
| `category`                   | VARCHAR(64)      | Top-level facet (`Framework Migration`, `Language Migration`, `Library Swap`, ...).                |
| `tags`                       | jsonb            | Free-form string array.                                                                            |
| `tier`                       | VARCHAR(16)      | Enum `Tier {FREE, PRO, TEAM, ENTERPRISE}`. Default `PRO`. Drives the lock decoration.              |
| `source`                     | VARCHAR(16)      | Enum `Source {FIRST_PARTY, COMMUNITY}`. Default `FIRST_PARTY`.                                     |
| `status`                     | VARCHAR(16)      | Enum `Status {LISTED, HIDDEN, ARCHIVED}`. Default `LISTED`.                                        |
| `deterministic_recipe_ref`   | VARCHAR(255)     | W8 hook for the hybrid-AST deterministic engine (codemod library + recipe name). Nullable today.   |
| `ai_fallback_prompt_path`    | VARCHAR(255)     | Path under `src/main/resources/prompts/` for the AI fallback prompt when the codemod cannot apply. |
| `preservation_contract`      | jsonb            | Behaviour the recipe must preserve (preserve-route-paths, preserve-response-shape, ...).           |
| `est_files_low`              | INTEGER          | Files-changed forecast lower bound.                                                                |
| `est_files_high`             | INTEGER          | Upper bound.                                                                                       |
| `est_credits_low`            | NUMERIC(10,2)    | Credits forecast lower bound.                                                                      |
| `est_credits_high`           | NUMERIC(10,2)    | Upper bound.                                                                                       |
| `icon_url`                   | TEXT             | Optional badge URL.                                                                                |
| `thumbnail_url`              | TEXT             | Optional larger thumbnail.                                                                         |
| `sample_pr_url`              | TEXT             | Optional reference PR.                                                                             |
| `author_user_id`             | VARCHAR(36)      | Optional (community).                                                                              |
| `author_org_id`              | VARCHAR(36)      | Optional (community).                                                                              |
| `created_at`                 | Instant          | NOT NULL, default `Instant.now()`.                                                                 |
| `updated_at`                 | Instant          | NOT NULL. JPA `@PreUpdate` refreshes.                                                              |

Indexes from V158: `(category, status)`, `(tier, status)`,
`(source, status)`, a GIN index over `source_stack`, and a GIN FTS index
over `to_tsvector('english', name || ' ' || coalesce(tagline,'') || ' ' || coalesce(description,''))`.
Today the list endpoint does in-memory filtering and FTS in
[`MigrationService.java`](../src/main/java/com/navneet/executespec/migration/service/MigrationService.java).

### 1.2 `migration_variant` — concrete codemod cell

Schema in
[`MigrationVariant.java`](../src/main/java/com/navneet/executespec/migration/entity/MigrationVariant.java).

| Column                       | Type             | Notes                                                                                            |
| ---------------------------- | ---------------- | ------------------------------------------------------------------------------------------------ |
| `id`                         | UUID             | PK.                                                                                              |
| `recipe_id`                  | UUID             | NOT NULL. ON DELETE CASCADE.                                                                     |
| `options_signature`          | VARCHAR(160)     | NOT NULL. Stable string key for the variant (e.g. `default`, `with-helmet-and-cors`).            |
| `options`                    | jsonb            | NOT NULL. Free-form variant discriminator (overlays the recipe's stack with concrete choices).   |
| `spec_template`              | TEXT             | NOT NULL. The Markdown spec that goes into the V2 planner after slot substitution.               |
| `ai_fallback_prompt_path`    | VARCHAR(255)     | Per-variant override of the recipe-level AI fallback prompt. Optional.                           |
| `est_credits_low`            | NUMERIC(10,2)    | Per-variant override.                                                                            |
| `est_credits_high`           | NUMERIC(10,2)    | Per-variant override.                                                                            |
| `created_at`                 | Instant          | Default `Instant.now()`.                                                                         |

UNIQUE constraint on `(recipe_id, options_signature)`. Indexes:
`(recipe_id)`.

### 1.3 `migration_slot` — user-fillable parameter

Schema in
[`MigrationSlot.java`](../src/main/java/com/navneet/executespec/migration/entity/MigrationSlot.java).
Same shape and same `Type` enum as `ReadymadeSlot`:

```
Type = { TEXT, SELECT, SECRET, REPO_PICKER, FILE_PATH, NUMBER, BOOLEAN }
```

| Column          | Type        | Notes                                                                  |
| --------------- | ----------- | ---------------------------------------------------------------------- |
| `id`            | UUID        | PK.                                                                    |
| `recipe_id`     | UUID        | NOT NULL. ON DELETE CASCADE.                                           |
| `name`          | VARCHAR(64) | Substitution token in `spec_template`. UNIQUE per recipe.              |
| `display_name`  | VARCHAR(160)| Label in the launch dialog.                                            |
| `type`          | VARCHAR(32) | Enum `Type` (see above).                                               |
| `required`      | BOOLEAN     | Default `false`.                                                       |
| `default_value` | TEXT        | Optional pre-filled value.                                             |
| `options`       | jsonb       | For `SELECT`: array of `{value, label}` objects.                       |
| `description`   | TEXT        | Help text under the input.                                             |
| `display_order` | INTEGER     | NOT NULL, default `0`.                                                 |

The shared `SlotValidator` works on both `migration_slot` and
`readymade_slot` because the entity shape and enum match.

### 1.4 `migration_dependency` — recipe-on-recipe ordering

Schema in
[`MigrationDependency.java`](../src/main/java/com/navneet/executespec/migration/entity/MigrationDependency.java).
Composite PK `(recipe_id, depends_on_id)`. Differs from
`readymade_dependency` in that the discriminator is a `Kind` enum, not a
boolean.

| Column          | Type        | Notes                                                                  |
| --------------- | ----------- | ---------------------------------------------------------------------- |
| `recipe_id`     | UUID        | Id (FK to recipe; CASCADE on delete).                                  |
| `depends_on_id` | UUID        | Id (FK to recipe).                                                     |
| `kind`          | VARCHAR(32) | Enum `Kind {PREREQUISITE, RECOMMENDED_AFTER}`. Widened from 16 in V161. |

`PREREQUISITE` (hard precedence): the apply path blocks if the prereq has
not been applied. `RECOMMENDED_AFTER` is surface-only ("after migrating
to Fastify, you may also want to migrate auth middleware").

Note: V158 originally declared `kind VARCHAR(16)` which was too narrow
for `RECOMMENDED_AFTER` (17 chars). V161 widens to VARCHAR(32). This is
a real "follow-up migration after applied migration was too narrow"
example — never modify V158; always add a follow-up.

### 1.5 `migration_dry_run_cache` — plan-gate cache

Schema in
[`MigrationDryRunCache.java`](../src/main/java/com/navneet/executespec/migration/entity/MigrationDryRunCache.java).
Composite PK `(recipe_id, stack_signature)`. Cache is global across
customers.

| Column             | Type           | Notes                                                                                |
| ------------------ | -------------- | ------------------------------------------------------------------------------------ |
| `recipe_id`        | UUID           | Id (FK; CASCADE on delete).                                                          |
| `stack_signature`  | VARCHAR(255)   | Id. SHA-256 prefix (32 hex chars) of the repo's `detected_stack` JSON. See section 8.|
| `sample_diff`      | TEXT           | Sample diff to show the user at plan-gate time.                                      |
| `est_files_low`    | INTEGER        | Per-stack files-changed lower bound.                                                 |
| `est_files_high`   | INTEGER        | Upper bound.                                                                         |
| `est_cost_low`     | NUMERIC(10,2)  | Per-stack cost lower bound.                                                          |
| `est_cost_high`    | NUMERIC(10,2)  | Upper bound.                                                                         |
| `generated_at`     | Instant        | Default `Instant.now()`.                                                             |

`MigrationDryRunService` reads from this cache first; falls back to
recipe-level bands when the cache misses. The v1 stackSignature is
SHA-256 over the whole detected-stack JSON; minor/patch deltas
invalidate the cache. v2 will refine to "major-version-only", flagged in
the service Javadoc.

---

## 2. Where recipes live on disk

```
src/main/resources/migration-recipes/
  <slug>.yml          (one file per recipe; multiple variants/slots/deps inside)
```

`MigrationRecipeSeeder` runs at boot (`@PostConstruct onStartup()`) and
is also called on-demand from `MigrationAdminController.reseed()`. It
mirrors the Readymade seeder shape:

- Recipes upserted by `slug`.
- Variants / slots / dependencies use delete-and-replace per parent on
  every reseed.
- Malformed YAML is logged and skipped per file.
- Missing `source_stack` or `target_stack` triggers
  `IllegalArgumentException` — these are non-optional.
- Dependency rows that reference an unseeded slug are skipped with a
  warning and re-resolved on the next reseed.
- The seeder also injects `MigrationDryRunCacheRepository` (annotated
  `@SuppressWarnings("unused")`) — placeholder for W2.5 "bust cache on
  recipe definition change". Currently held but never read.

---

## 3. YAML schema (one recipe = one file)

Annotated example for the canonical Express to Fastify migration:

```yaml
# Required identity
slug: express-to-fastify
name: "Express to Fastify"
tagline: "Migrate a Node.js Express app to Fastify with preserved routes + middleware."
description: |
  Replaces Express with Fastify. Preserves route paths, response shapes,
  and middleware order. Common Express idioms (req/res, next, mounted
  routers) are rewritten to Fastify's reply/request/preHandler model.

# Faceting
category: "Framework Migration"
tags: ["node", "express", "fastify", "performance"]

# Source -> Target stack (REQUIRED, both)
source_stack:
  language: "javascript"
  framework: "express"
  min_version: "4.0"

target_stack:
  language: "javascript"
  framework: "fastify"
  version: "5"

# Scope
affected_files_glob: "**/*.{js,ts,mjs,cjs}"

# Classification + gating
tier: "PRO"                       # FREE | PRO | TEAM | ENTERPRISE; default PRO
source: "FIRST_PARTY"             # FIRST_PARTY | COMMUNITY; default FIRST_PARTY
status: "LISTED"                  # LISTED | HIDDEN | ARCHIVED; default LISTED

# Hybrid-AST / AI fallback wiring
deterministic_recipe_ref: "jscodeshift:express-to-fastify"   # W8 hook (nullable)
ai_fallback_prompt_path: "migrations/express-to-fastify.md"  # under prompts/

# Behaviour we promise not to break
preservation_contract:
  preserve_route_paths: true
  preserve_response_shapes: true
  preserve_middleware_order: true
  must_not_break_tests: true

# Forecast bands
est_files_low: 12
est_files_high: 40
est_credits_low: 3.0
est_credits_high: 12.0

# Presentation
icon_url: "https://cdn.executespec.ai/icons/fastify.svg"
thumbnail_url: "https://cdn.executespec.ai/thumbs/express-to-fastify.png"
sample_pr_url: "https://github.com/example/demo/pull/87"

# Variants — one per (options_signature) cell
variants:
  - options_signature: "default"
    options: { keep_helmet: true, keep_cors: true }
    spec_template: |
      Migrate this Express 4 application to Fastify 5.
      - Replace `app.use(helmet())` with `app.register(import('@fastify/helmet'))`.
      - Preserve route prefix: {{api_prefix}}
      - Default port: {{port}}
      Tests must keep passing.
    ai_fallback_prompt_path: "migrations/express-to-fastify.md"
    est_credits_low: 3.0
    est_credits_high: 6.0

  - options_signature: "minimal"
    options: { keep_helmet: false, keep_cors: false }
    spec_template: |
      Migrate this Express 4 application to Fastify 5 with minimal addons.
      ...

# Slots
slots:
  - name: "api_prefix"
    display_name: "API route prefix"
    type: "TEXT"
    required: true
    default_value: "/api"
    display_order: 1
  - name: "port"
    display_name: "Listen port"
    type: "NUMBER"
    required: false
    default_value: "3000"
    display_order: 2

# Dependencies — by slug
dependencies:
  - depends_on: "js-to-ts"
    kind: "RECOMMENDED_AFTER"      # PREREQUISITE | RECOMMENDED_AFTER
```

Notable parser behaviour (from `MigrationRecipeSeeder`):

- `source_stack`, `target_stack`, `tags`, `preservation_contract`,
  `variants[].options`, `slots[].options` all serialise to JSON. The
  serialiser is shallow — booleans / numbers raw, strings quoted;
  nested objects inside an `options` value are written as `toString`'d
  quoted strings (lossy for deep nesting).
- Missing fields fall through to default enums
  (`tier=PRO`, `source=FIRST_PARTY`, `status=LISTED`, slot
  `type=TEXT`, dependency `kind=PREREQUISITE`).
- Missing `source_stack` or `target_stack` throws — these are mandatory.

---

## 4. `source_stack` vs `target_stack` JSON blobs

Both are JSONB columns on `migration_recipe`. They are predicates over
the repo's `detected_stack` (for source) and declarations of the
post-apply state (for target).

### 4.1 Source-stack predicate shape

```json
{
  "language": "javascript",
  "framework": "express",
  "min_version": "4.0"
}
```

- `language` (required): matched case-insensitively after
  `StackNormalizer.normalizeLanguage` — `"JavaScript"`, `"javascript"`,
  and `"js"` all canonicalise the same way.
- `framework` (optional in the predicate): if omitted, the recipe
  matches any framework that uses the listed language. If present,
  must match canonicalised `repo.detected_stack.framework` exactly.
- `min_version` (optional): in v1 of `MigrationStackMatcher` this is
  **ignored**. v2 will gate on semver compare; documented in the
  service Javadoc.

### 4.2 Target-stack declaration shape

```json
{
  "language": "javascript",
  "framework": "fastify",
  "version": "5"
}
```

Stored verbatim; the apply path passes it to the V2 planner as part of
the spec context. No runtime predicate logic walks this side.

### 4.3 `MigrationStackMatcher` outcome table

`MigrationStackMatcher.match(recipeSourceStack, repoDetectedStack)`
returns one of three enum values:

| Outcome              | Condition                                                                          |
| -------------------- | ---------------------------------------------------------------------------------- |
| `APPLICABLE`         | Recipe source-stack null/blank, **or** language matches and framework matches.     |
| `INCOMPATIBLE_STACK` | Language mismatch.                                                                 |
| `UNKNOWN_STACK`      | Repo `detected_stack` null/blank or unparseable JSON.                              |

JSON parse exceptions are swallowed and downgraded to `UNKNOWN_STACK`
(logged at debug level). Recipe-side missing `framework` is treated as
"match any framework with the same language".

---

## 5. `preservation_contract` JSON

Free-form JSON on `migration_recipe.preservation_contract`. The planner
gets it surfaced as part of the recipe spec template; the reviewer is
expected to enforce. Common keys (not exhaustive):

```yaml
preservation_contract:
  preserve_route_paths: true            # routes must keep their public paths
  preserve_response_shapes: true        # JSON response shape unchanged
  preserve_middleware_order: true       # ordering before/after auth matters
  must_not_break_tests: true            # all existing tests still pass
  preserve_env_vars: true               # env-var names not renamed
  preserve_database_schema: true        # codemod does not migrate DB
```

The catalog enforces by surfacing these to the reviewer, not by parsing
them programmatically — `preservation_contract` is documentation-as-data.
Adding a new key is a doc-only change unless the reviewer prompt is
updated to look for it.

---

## 6. `ai_fallback_prompt_path` — AI fallback when deterministic fails

A migration recipe has two engines:

1. **Deterministic** (W8-pending): a hybrid-AST codemod referenced by
   `deterministic_recipe_ref` (e.g. `jscodeshift:express-to-fastify`).
   When the deterministic recipe applies cleanly, the run is a pure
   AST transform — no LLM tokens spent.
2. **AI fallback**: when the deterministic engine bails (unhandled
   pattern, malformed AST, custom abstraction the codemod cannot
   handle), the apply pipeline dispatches a V2 PATCH-mode run with
   `MigrationOrchestrator.apply`. The system prompt for the patch-mode
   agent is loaded from `src/main/resources/prompts/<path>`.

`ai_fallback_prompt_path` can be set at two levels:

- Recipe-level (`migration_recipe.ai_fallback_prompt_path`).
- Variant-level (`migration_variant.ai_fallback_prompt_path`) — overrides
  the recipe-level if non-null.

Today the deterministic engine is W8-pending, so every recipe-driven
apply takes the AI fallback path. `deterministic_recipe_ref` is stored
but not yet consulted by the apply pipeline.

The prompt file itself follows the `PromptAssembler` convention used
across `prompts/` (Markdown, `{{VAR}}` substitution, `{{include:fragment}}`
expansion). Recipe-specific variables are unwrapped from the spec
template at compose time.

---

## 7. Tier gating

Each recipe declares the minimum tier needed to launch. The ladder is
identical to Readymades:

```
FREE  <  PRO  <  TEAM  <  ENTERPRISE
```

Gate enforcement points:

1. **List endpoint** (`GET /api/migrations`) — every gallery row carries
   `locked` + `unlockTier`. Helpers: `MigrationListItemDto.locked(r, t)`
   and `.unlocked(r)`.
2. **Detail endpoint** (`GET /api/migrations/{slug}`) — same shape.
3. **Dry-run endpoint** (`POST /api/migrations/{slug}/dry-run`) — 403
   if caller tier below recipe tier. This is **[F-05]** (2026-04-30
   Sprint 2 audit fix) — before F-05 a FREE caller could compute
   applicability / cost / sample-diff on locked recipes by skipping the
   gate. Tier-gate now mirrors `/apply`.
4. **Apply endpoint** (`POST /api/migrations/{slug}/apply`) — hard 403
   in `MigrationOrchestrator.apply` via `isLockedFor(recipe, user)`.

The static comparator is `MigrationService.isLocked(requiredTier,
callerTier)` (ordinal check; null `requiredTier` is treated as unlocked;
null caller maps to FREE). `MigrationController.callerTier(userDetails)`
defaults to FREE on null or unparseable tier.

---

## 8. Dry-run endpoint (per-repo applicability)

The **plan-gate Phase 1** endpoint. Called by the UI before launching
an apply so the user can see "this recipe touches ~17 files in repo A,
costs ~$1.20-$2.40, and is not applicable to repo B (wrong stack)".

### 8.1 Surface

```
POST /api/migrations/{slug}/dry-run
Body = DryRunRequest {
  projectId: string,
  repoIds: UUID[]
}

200 OK -> MigrationDryRunDto {
  slug: string,
  name: string,
  estCostLowTotal: BigDecimal,
  estCostHighTotal: BigDecimal,
  estFilesLowTotal: number,
  estFilesHighTotal: number,
  sampleDiff?: string,
  perRepo: RepoApplicability[]
}

400 -> missing projectId
403 -> caller tier below recipe tier ([F-05] gate)
404 -> unknown slug
```

### 8.2 `RepoApplicability` shape

| Field            | Type            | Notes                                                                                  |
| ---------------- | --------------- | -------------------------------------------------------------------------------------- |
| `repoId`         | UUID            | Repo ID inside the project.                                                            |
| `fullName`       | string          | `owner/repo` for display.                                                              |
| `status`         | string          | One of `APPLICABLE`, `INCOMPATIBLE_STACK`, `UNKNOWN_STACK`, `NOT_LINKED`.               |
| `reason`         | string          | Human-readable explanation.                                                            |
| `estFilesLow`    | number          | Per-repo files-changed lower bound.                                                    |
| `estFilesHigh`   | number          | Upper bound.                                                                           |
| `estCostLow`     | BigDecimal      | Per-repo cost lower bound.                                                             |
| `estCostHigh`    | BigDecimal      | Upper bound.                                                                           |

Per-repo `status` codes:

| Status                  | Meaning                                                                       |
| ----------------------- | ----------------------------------------------------------------------------- |
| `APPLICABLE`            | Repo matches the recipe's `source_stack`. Will be included in the apply run.  |
| `INCOMPATIBLE_STACK`    | Repo's language mismatches the recipe's source-language predicate.            |
| `UNKNOWN_STACK`         | Repo linked but no `detected_stack` populated.                                |
| `NOT_LINKED`            | `(projectId, repoId)` not in `project_repo_link`.                             |

### 8.3 Implementation (`MigrationDryRunService.dryRun`)

```
dryRun(slug, projectId, repoIds):
  1. Load MigrationRecipe by slug                       (404 if missing)
  2. For each repoId in request:
       a. Look up project_repo (joined via project_repo_link)
          - if not linked -> NOT_LINKED row
          - if detected_stack blank -> UNKNOWN_STACK row
       b. Run MigrationStackMatcher.match(recipe.source_stack,
                                          repo.detected_stack)
          - INCOMPATIBLE_STACK -> incompatible row
          - APPLICABLE -> applicable row
       c. Look up migration_dry_run_cache for (recipe_id, stackSignature):
          - hit -> use cached est_files_*, est_cost_*, sample_diff
          - miss -> fall back to recipe-level estimate bands
            (sample_diff stays null; no LLM call in v1)
  3. Sum per-repo bands into estCostLowTotal / estCostHighTotal /
     estFilesLowTotal / estFilesHighTotal
  4. Return MigrationDryRunDto
```

The dry-run **never spends an LLM call in v1**. Sample diff comes from
cache only. If the cache misses, sample_diff is null and the UI shows
"sample diff not yet cached".

### 8.4 stackSignature computation

`MigrationDryRunService.stackSignature(detectedStackJson)` returns a
SHA-256 prefix (32 hex chars) of the full detected-stack JSON. The v1
implementation invalidates the cache on **every** version bump — minor
or patch. v2 will refine to "major-version-only" so a Spring Boot
3.5.0 to 3.5.1 bump does not bust the cache. Flagged in
`MigrationDryRunService` Javadoc; not yet implemented.

`"unknown"` sentinel is returned when `detectedStackJson` is null /
blank / unparseable.

---

## 9. Apply endpoint (V2 dispatch sequence)

The **plan-gate Phase 2** endpoint. Once the user reviews the dry-run
and confirms, the apply endpoint composes the spec and hands off to the
V2 pipeline.

### 9.1 Surface

```
POST /api/migrations/{slug}/apply
Body = ApplyRequest {
  projectId: string,
  repoIds: UUID[],
  slotValues: { [slotName: string]: string },   // null coalesces to {}
  optionsSignature?: string,                     // pick a specific variant
  branch?: string                                // optional branchOverride
}

202 Accepted -> {
  executionRunId: string,
  status: string,
  message: string
}
400 -> missing projectId / repoIds / slot validation failure
401 -> caller anonymous
403 -> caller tier below recipe tier
404 -> unknown recipe / project
```

### 9.2 Dispatch sequence (`MigrationOrchestrator.apply`)

```
1. Load MigrationRecipe by slug                       (404 if missing)
2. Resolve caller AppUser from callerUserId           (401 if unknown)
3. Tier-gate: isLockedFor(recipe, user)               (403 on fail)
4. Pick variant:
     - prefer req.optionsSignature exact match, else
     - fall back to first variant for the recipe
   (400 if no variants exist at all)
5. Validate slot values via SlotValidator             (400 on fail)
6. SpecComposer assembles the final spec:
     - load variant.spec_template
     - substitute {{slot_name}} with slotValues
     - prepend a "Migration recipe: <name> (slug=<slug>)\n
        Variant: <signature>\n\n" header for reviewer traceability
        without joins
7. Build CreateExecutionRequest:
     - userRequest = composed spec
     - projectId, selectedRepoIds = req.repoIds
     - branch = req.branch
8. ExecutionOrchestrator.createExecution(req)         (one V2 pipeline_run)
9. Stamp execution_run.migration_recipe_id +
        execution_run.migration_variant_id            (V159)
10. Trigger analysis async
11. Return ExecutionRun (controller wraps as 202 + executionRunId)
```

Tracking columns added by `V159__execution_run_migration_recipe.sql`:

```sql
ALTER TABLE execution_run ADD COLUMN migration_recipe_id UUID;
ALTER TABLE execution_run ADD COLUMN migration_variant_id UUID;
```

Both nullable; only recipe-applied runs have them set.

Note on the `ApplyRequest` record compact constructor: it coalesces null
`slotValues` to an empty `Map.of()` and null `repoIds` to a mutable empty
`ArrayList`. The controller still 400s on empty `repoIds` before reaching
the record, but the defensive default exists.

---

## 10. Endpoint surface — full table

Endpoints live in
[`MigrationController.java`](../src/main/java/com/navneet/executespec/migration/controller/MigrationController.java)
and
[`MigrationAdminController.java`](../src/main/java/com/navneet/executespec/migration/controller/MigrationAdminController.java).

| Method | Path                                  | Description                                                                  |
| ------ | ------------------------------------- | ---------------------------------------------------------------------------- |
| GET    | `/api/migrations`                     | Gallery list. Filters: `category`, `tier`, `search`.                         |
| GET    | `/api/migrations/{slug}`              | Detail bundle (recipe + variants + slots + deps + lock). 404 if missing.     |
| POST   | `/api/migrations/{slug}/dry-run`      | Per-repo applicability + cost/file bands. **[F-05] tier-gated.**             |
| POST   | `/api/migrations/{slug}/apply`        | 202 Accepted; dispatches an `ExecutionRun`. Body = `ApplyRequest`.           |
| POST   | `/api/admin/migrations/reseed`        | SUPER_ADMIN-only hot reload of `classpath:migration-recipes/*.yml`.          |

---

## 11. Operator playbook — adding a new recipe

1. **Decide identity + scope.** Pick a stable `slug` (lowercase
   hyphenated, at most 64 chars). Decide `category`, `tier` (default
   `PRO`), `affected_files_glob`.

2. **Define source_stack + target_stack JSON.** Mandatory — the seeder
   throws `IllegalArgumentException` if either is missing. Use canonical
   lowercase language / hyphenated framework names so
   `MigrationStackMatcher` works against `StackNormalizer`-canonicalised
   repo stacks.

3. **Author the spec_template per variant.** Each variant is one
   (`options_signature`, `options`) cell. The template is the Markdown
   spec that goes into the V2 patch-mode planner. Use `{{slot_name}}`
   placeholders for user inputs.

4. **Author the AI fallback prompt** at
   `src/main/resources/prompts/<ai_fallback_prompt_path>`. Use
   `{{include:fragment}}` for shared rules; use `{{VAR}}` for any
   spec-derived variables. Until the W8 deterministic engine ships, this
   is the **only** engine for the recipe.

5. **Optionally pin `deterministic_recipe_ref`.** Format is
   `<library>:<recipe>` (e.g. `jscodeshift:express-to-fastify`,
   `ts-migrate:js-to-ts`). The apply pipeline does not consult this
   field yet; W8-pending.

6. **Define slots + dependencies.** Same shape as Readymade Solutions.
   Use `kind: PREREQUISITE` for hard precedence and
   `kind: RECOMMENDED_AFTER` for suggestion-only.

7. **Drop the file at** `src/main/resources/migration-recipes/<slug>.yml`.

8. **Reseed.**

   ```bash
   curl -sS -X POST -H "Authorization: Bearer $SUPER_ADMIN_JWT" \
     "$BASE/api/admin/migrations/reseed"
   ```

   Returns `{loaded: N, status: "ok"}`. Boot-time reseed runs the same
   path.

9. **Verify.**
   - `GET /api/migrations?search=<your-slug>` — confirm the row
     appears with the right lock state.
   - `GET /api/migrations/<slug>` — confirm variants + slots + deps.
   - `POST /api/migrations/<slug>/dry-run` with a project containing
     a repo whose `detected_stack` matches your `source_stack`:
     - Should return `status=APPLICABLE` for that repo.
     - Should return `status=INCOMPATIBLE_STACK` for a repo with a
       different language.
     - Should return `status=UNKNOWN_STACK` for a linked repo that has
       not been analysed.
   - `POST /api/migrations/<slug>/apply` with valid slot values —
     confirm 202, then poll `executionRunId` for terminal status.

10. **Reseed semantics caveat.** Same as Readymades: delete-and-replace
    for variants / slots / deps per parent. A slug typo leaves the old
    row as stale `LISTED`. Mark stale rows `status: HIDDEN` in a
    follow-up YAML reseed, or hand-delete the DB row.

11. **Migration cross-link rule.** Any new enum value or schema column
    requires a new Flyway migration — never modify V158, V159, V161; add
    a follow-up `V_{next}__...sql`. This rule is the reason V161 exists
    (V158 declared `migration_dependency.kind VARCHAR(16)` which was too
    narrow for `RECOMMENDED_AFTER`).

---

## 12. Known gaps + caveats (engineering-honest)

- **Deterministic engine is W8-pending.** `deterministic_recipe_ref` is
  stored but never consulted by the apply pipeline. Today every apply
  goes through the AI fallback, which costs LLM tokens per run.
- **`min_version` in source_stack is ignored.** `MigrationStackMatcher`
  v1 does not gate on version; documented in the matcher Javadoc.
- **stackSignature is v1.** SHA-256 over the whole JSON; minor / patch
  bumps bust the cache. v2 refinement to "major-version-only" is flagged
  but not implemented.
- **No LLM spend in dry-run v1.** Sample diff comes from
  `migration_dry_run_cache` only. Cache miss = null sample_diff; the
  user sees "sample diff not yet cached".
- **`MigrationRecipeSeeder.toJsonObject` is lossy for nested objects.**
  Same caveat as Readymade seeder — booleans / numbers raw, strings
  quoted, nested objects toString'd. Keep `options` / `tags` /
  `preservation_contract` shallow.
- **W2.5 cache-bust on recipe definition change is wired but not
  implemented.** The seeder injects
  `MigrationDryRunCacheRepository` only via `@SuppressWarnings("unused")`
  — held but never read.
- **[F-05] tier-gate on dry-run.** Audit fix landed 2026-04-30 Sprint 2.
  Before F-05, a FREE caller could compute applicability / cost /
  sample-diff on locked recipes. If you see a `MigrationService
  .requiredTierFor(slug)` call in the controller, that is the
  [F-05] hook.
- **Default `Kind` for dependency is `PREREQUISITE`.** YAML omitting
  `kind:` flips to hard-precedence by default; double-check this is
  what you want.

---

## 13. Cross-references

- Flyway migration **V158** — `migration_recipe.sql` (schema).
- Flyway migration **V159** — `execution_run_migration_recipe.sql`
  (tracking).
- Flyway migration **V161** — `widen_migration_dependency_kind.sql`
  (kind VARCHAR(16) -> VARCHAR(32)).
- Seeder — `migration/seeder/MigrationRecipeSeeder.java`.
- Public read API + dry-run + apply — `migration/controller/MigrationController.java`.
- Admin reseed — `migration/controller/MigrationAdminController.java`.
- Dispatch (apply) — `migration/service/MigrationOrchestrator.java`.
- Plan-gate (dry-run) — `migration/service/MigrationDryRunService.java`.
- Stack matcher — `migration/service/MigrationStackMatcher.java`.
- Stack normaliser (shared with Readymades) — `readymade/service/StackNormalizer.java`.
- Read-side gallery / detail — `migration/service/MigrationService.java`.
- Sister catalog (Readymades) — `docs/readymades-catalog-format.md`.
