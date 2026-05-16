# Migration Recipes — catalog format

Operator + developer reference for the Migration Recipes library that powers the `/migrations` surface. Use this when you want to seed a new recipe, audit an existing one, or extend the schema.

The library is a list of stack-to-stack transformations (Express → Fastify, JS → TS, Mongoose → Prisma, REST → tRPC, etc.). Each card is a `MigrationRecipe` row. Selecting it runs a dry-run (per-repo applicability + cost band + sample diff, no LLM call) then optionally applies — apply dispatches a V2 run that opens a PR.

Source of truth: YAML files under `src/main/resources/migration-recipes/*.yml`. The `MigrationRecipeSeeder` reads them at boot (`@PostConstruct`) and on demand from the SUPER_ADMIN reseed endpoint, upserting each into Postgres. The seeder mirrors `ReadymadeSeeder` — same idempotency model, same per-file try/catch, same YAML-only parser.

Where this differs from Readymades: a migration *transforms what's already there* rather than adding new code, so the schema carries a `source_stack` + `target_stack` predicate, a glob narrowing the affected files, and a `preservation_contract` that the reviewer enforces post-edit.

---

## 1. File system layout

| Concern                           | Location                                                                                     |
| --------------------------------- | -------------------------------------------------------------------------------------------- |
| Recipe YAML files                 | `src/main/resources/migration-recipes/*.yml`                                                 |
| Boot-time + on-demand seeder      | `src/main/java/com/navneet/executespec/migration/seeder/MigrationRecipeSeeder.java`          |
| Entities                          | `src/main/java/com/navneet/executespec/migration/entity/`                                    |
| Library read API + dry-run        | `src/main/java/com/navneet/executespec/migration/controller/MigrationController.java`        |
| Admin reseed endpoint             | `src/main/java/com/navneet/executespec/migration/controller/MigrationAdminController.java`   |
| Read-side service (list / detail) | `src/main/java/com/navneet/executespec/migration/service/MigrationService.java`              |
| Dry-run service                   | `src/main/java/com/navneet/executespec/migration/service/MigrationDryRunService.java`        |
| Stack matcher                     | `src/main/java/com/navneet/executespec/migration/service/MigrationStackMatcher.java`         |
| Apply orchestrator                | `src/main/java/com/navneet/executespec/migration/service/MigrationOrchestrator.java`         |
| Slot validator (shared)           | `src/main/java/com/navneet/executespec/composer/SlotValidator.java`                          |
| Spec composer (shared)            | `src/main/java/com/navneet/executespec/composer/SpecComposer.java`                           |
| Database schema (Flyway W1)       | `src/main/resources/db/migration/V158__migration_recipe.sql`                                 |
| Execution-run tracking columns    | `src/main/resources/db/migration/V159__execution_run_migration_recipe.sql`                   |

The seeder discovers files by classpath glob (`classpath:migration-recipes/*.yml`). The directory is flat — one file per recipe, named to match the `slug`.

YAML only. Nested objects (slot `options`, variant `options`, `source_stack`, `target_stack`, `preservation_contract`) come in as Java `Map` / `List` and get re-serialised to JSONB strings by the seeder before writing to Postgres.

---

## 2. Entity overview

Five tables under the migration product, all created in `V158__migration_recipe.sql`. Cascading delete from `migration_recipe` to children at the DB layer.

### 2.1 `migration_recipe` — abstract recipe entry

The "card" in the gallery. One row per unique slug.

| Column                     | Type           | Required | Purpose                                                                                                                                                                                                                       |
| -------------------------- | -------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`                       | UUID PK        | y        | Generated at insert. Referenced by variant / slot / dependency child rows.                                                                                                                                                    |
| `slug`                     | VARCHAR(64) U  | y        | Stable, URL-safe ID. Used in API paths (`/api/migrations/{slug}`) and as the upsert key for the seeder.                                                                                                                       |
| `name`                     | VARCHAR(160)   | y        | Display title.                                                                                                                                                                                                                |
| `tagline`                  | VARCHAR(255)   | n        | Single-line hook.                                                                                                                                                                                                             |
| `description`              | TEXT           | n        | Long-form blurb on the detail page.                                                                                                                                                                                           |
| `source_stack`             | JSONB          | y        | The stack the recipe transforms FROM. JSON object like `{"language":"typescript","framework":"express","min_version":"4.x"}`. Stack matching against `project_repo.detected_stack` reads this column. Seeder rejects null.    |
| `target_stack`             | JSONB          | y        | The stack the recipe transforms TO. Same shape as `source_stack`, e.g. `{"language":"typescript","framework":"fastify","target_version":"5.x"}`. Surfaced in the gallery for "X → Y" labels.                                  |
| `affected_files_glob`      | TEXT           | y        | Glob predicate narrowing which files the recipe touches. E.g. `src/**/*.{js,ts}`. Glob-only in v1; content predicates not supported. Empty string treated as "all files."                                                       |
| `category`                 | VARCHAR(64)    | y        | Facet bucket — `framework-migration`, `language-migration`, `orm-migration`, etc. Defaults `uncategorised`.                                                                                                                    |
| `tags`                     | JSONB          | n        | JSON array of strings.                                                                                                                                                                                                        |
| `tier`                     | VARCHAR(16)    | y        | Enum: `FREE` / `PRO` / `TEAM` / `ENTERPRISE`. Drives the lock icon + upgrade CTA. Defaults `PRO`.                                                                                                                             |
| `source`                   | VARCHAR(16)    | y        | Enum: `FIRST_PARTY` / `COMMUNITY`. Defaults `FIRST_PARTY`.                                                                                                                                                                    |
| `status`                   | VARCHAR(16)    | y        | Enum: `LISTED` / `HIDDEN` / `ARCHIVED`. Only `LISTED` rows appear in the gallery. Defaults `LISTED`.                                                                                                                          |
| `deterministic_recipe_ref` | VARCHAR(255)   | n        | Hybrid AST hook. NULL = "AI-only path." Populated when a deterministic AST recipe is wired alongside the AI fallback (e.g. `ast-grep:./recipes/express-to-fastify.yml`). Not yet wired into the executor.                       |
| `ai_fallback_prompt_path`  | VARCHAR(255)   | n        | Path to the recipe-specific AI fallback prompt (e.g. `prompts/migrations/express-to-fastify-fallback.md`). Loaded by the V2 worker when a file routes through the AI fallback rather than the deterministic pass.              |
| `preservation_contract`    | JSONB          | n        | Recipe-author declaration of invariants the reviewer must enforce. JSON object of `{preserve_*: bool}` flags. Example: `{"preserve_routes": true, "preserve_request_body_schema": true}`.                                      |
| `est_files_low`            | INT            | n        | Lower bound of "how many files this PR will touch." Used by dry-run cost estimate.                                                                                                                                            |
| `est_files_high`           | INT            | n        | Upper bound.                                                                                                                                                                                                                  |
| `est_credits_low`          | NUMERIC(10,2)  | n        | Lower bound of credit burn.                                                                                                                                                                                                   |
| `est_credits_high`         | NUMERIC(10,2)  | n        | Upper bound. Overridden by `migration_dry_run_cache.est_cost_*` once a cached dry-run row exists for the (recipe, stack signature) pair.                                                                                       |
| `icon_url`                 | TEXT           | n        | Card icon.                                                                                                                                                                                                                    |
| `thumbnail_url`            | TEXT           | n        | Detail-page hero image.                                                                                                                                                                                                       |
| `sample_pr_url`            | TEXT           | n        | Link to a real demo PR.                                                                                                                                                                                                       |
| `author_user_id`           | VARCHAR(36)    | n        | Set when `source: COMMUNITY`.                                                                                                                                                                                                 |
| `author_org_id`            | VARCHAR(36)    | n        |                                                                                                                                                                                                                               |
| `created_at`               | TIMESTAMPTZ    | y        | Auto.                                                                                                                                                                                                                         |
| `updated_at`               | TIMESTAMPTZ    | y        | Auto. Refreshed by `@PreUpdate`.                                                                                                                                                                                              |

Indexed by `(category, status)`, `(tier, status)`, `(source, status)`, a GIN index on `source_stack`, and a GIN FTS index over `name + description + tagline`.

### 2.2 `migration_variant` — concrete (options) cell

Most recipes have a single variant because the source stack is fixed — the recipe is the transformation. Variants exist when the *target* stack branches. Example: Mongoose → Prisma has variants for `target_db=postgres`, `target_db=mysql`, `target_db=sqlite`.

| Column                    | Type            | Required | Purpose                                                                                                                                          |
| ------------------------- | --------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `id`                      | UUID PK         | y        | Generated.                                                                                                                                       |
| `recipe_id`               | UUID FK         | y        | `ON DELETE CASCADE`.                                                                                                                             |
| `options_signature`       | VARCHAR(160)    | y        | Human-readable id for this (options) cell. E.g. `"target_db=postgres"` or `"default"`. Apply endpoint routes by this string. Unique per recipe.   |
| `options`                 | JSONB NOT NULL  | y        | Variant-specific options. E.g. `{"target_db":"postgres"}`. JSONB is `NOT NULL`; seeder defaults to `"{}"` when YAML omits.                       |
| `spec_template`           | TEXT            | y        | The prompt the V2 worker sees. May contain `{{slot_name}}` placeholders. Substituted by `SpecComposer` at apply time.                            |
| `ai_fallback_prompt_path` | VARCHAR(255)    | n        | Per-variant override of the recipe-level `ai_fallback_prompt_path`. NULL → inherit from parent recipe.                                            |
| `est_credits_low`         | NUMERIC(10,2)   | n        | Per-variant cost override.                                                                                                                       |
| `est_credits_high`        | NUMERIC(10,2)   | n        | Same.                                                                                                                                            |
| `created_at`              | TIMESTAMPTZ     | y        | Auto.                                                                                                                                            |

Unique constraint on `(recipe_id, options_signature)`. Indexed by `(recipe_id)`.

A migration variant is structurally simpler than a Readymade variant — no `language` / `framework` columns, because the source stack is already pinned by the parent recipe's `source_stack`. Only the output options vary.

### 2.3 `migration_slot` — user-fillable parameter

Same shape as `readymade_slot`. The slot enum lives in two packages but the validator (`SlotValidator`) works off the type name string so both products share validation.

| Column          | Type           | Required | Purpose                                                                                                       |
| --------------- | -------------- | -------- | ------------------------------------------------------------------------------------------------------------- |
| `id`            | UUID PK        | y        | Generated.                                                                                                    |
| `recipe_id`     | UUID FK        | y        | `ON DELETE CASCADE`.                                                                                          |
| `name`          | VARCHAR(64)    | y        | Stable identifier used in `{{slot_name}}` placeholders in the variant's `spec_template`.                       |
| `display_name`  | VARCHAR(160)   | y        | Form-field label.                                                                                             |
| `type`          | VARCHAR(32)    | y        | One of `TEXT` / `SELECT` / `SECRET` / `REPO_PICKER` / `FILE_PATH` / `NUMBER` / `BOOLEAN`. Defaults `TEXT`.    |
| `required`      | BOOLEAN        | y        | Defaults `false`.                                                                                             |
| `default_value` | TEXT           | n        | `SpecComposer` falls back to this when the user value is blank.                                               |
| `options`       | JSONB          | n        | For `SELECT`: JSON array of allowed string values.                                                            |
| `description`   | TEXT           | n        | Help text under the form field.                                                                               |
| `display_order` | INT            | y        | Ascending render order. Defaults `0`.                                                                         |

Unique constraint on `(recipe_id, name)`.

### 2.4 `migration_dependency` — recipe ordering graph

Declares ordering between recipes. Two kinds, encoded by the `kind` column.

| Column          | Type           | Required | Purpose                                                                                                                                  |
| --------------- | -------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `recipe_id`     | UUID FK        | y        | The dependent recipe. `ON DELETE CASCADE`.                                                                                               |
| `depends_on_id` | UUID FK        | y        | The recipe it depends on.                                                                                                                |
| `kind`          | VARCHAR(16)    | y        | `PREREQUISITE` — hard precedence; apply path blocks until the prerequisite has shipped a successful run on this project. `RECOMMENDED_AFTER` — surfaced as a suggestion; never auto-runs. |

Composite primary key `(recipe_id, depends_on_id)`. Indexed by `(depends_on_id)`.

The two kinds are stored on the `kind` column, in contrast to `readymade_dependency` which uses a `required` boolean. The migration product needed a clearer name for the `RECOMMENDED_AFTER` semantics — recipes recommend *follow-ups*, not pairings.

### 2.5 `migration_dry_run_cache` — sample-diff + cost-estimate cache

Sample diffs cost an LLM call (~$0.05 per recipe) to generate; this table caches them per (recipe, stack-signature) so the same recipe + same stack on different customers returns the same sample.

| Column            | Type           | Required | Purpose                                                                                                                                          |
| ----------------- | -------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `recipe_id`       | UUID FK        | y        | `ON DELETE CASCADE`.                                                                                                                             |
| `stack_signature` | VARCHAR(255)   | y        | SHA-256 prefix (first 32 hex chars) of the repo's `detected_stack` JSON. Captures any change to the stack blob.                                  |
| `sample_diff`     | TEXT           | n        | Truncated (~10 KB cap) sample diff from one representative file. Surfaced on the recipe detail page as "what your PR will look like."             |
| `est_files_low`   | INT            | n        | Per-(recipe, stack) file count band. Overrides the recipe-level band when present.                                                               |
| `est_files_high`  | INT            | n        | Same.                                                                                                                                            |
| `est_cost_low`    | NUMERIC(10,2)  | n        | Per-(recipe, stack) cost band. Overrides the recipe-level band when present.                                                                     |
| `est_cost_high`   | NUMERIC(10,2)  | n        | Same.                                                                                                                                            |
| `generated_at`    | TIMESTAMPTZ    | y        | Auto.                                                                                                                                            |

Composite primary key `(recipe_id, stack_signature)`. Populated by the apply path when a real run lands (today the seeder leaves this empty; the v1 dry-run never writes here).

---

## 3. YAML schema

Top-level keys map roughly one-to-one with `migration_recipe` columns. Three nested arrays — `slots`, `variants`, `dependencies` — populate the child tables.

### 3.1 Recipe-level keys

| YAML key                   | Type             | Required | Default          | Purpose                                                                                                                       |
| -------------------------- | ---------------- | -------- | ---------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `slug`                     | string           | y        | —                | If absent the file is skipped. Upsert key.                                                                                    |
| `name`                     | string           | n        | `slug`           | Display title.                                                                                                                |
| `tagline`                  | string           | n        | null             | Single-line hook.                                                                                                             |
| `description`              | string (block)   | n        | null             | Long-form blurb.                                                                                                              |
| `source_stack`             | map              | y        | —                | See §3.2. JSON object; seeder rejects null and aborts the file.                                                               |
| `target_stack`             | map              | y        | —                | See §3.2. JSON object; seeder rejects null and aborts the file.                                                               |
| `affected_files_glob`      | string           | y        | empty            | Glob predicate narrowing the file scope.                                                                                      |
| `category`                 | string           | n        | `uncategorised`  | Facet bucket. Conventions: `framework-migration`, `language-migration`, `orm-migration`, `api-migration`, `tooling-migration`. |
| `tags`                     | list[string]     | n        | null             | Free-form tags.                                                                                                               |
| `tier`                     | enum             | n        | `PRO`            | `FREE` / `PRO` / `TEAM` / `ENTERPRISE`.                                                                                       |
| `source`                   | enum             | n        | `FIRST_PARTY`    | `FIRST_PARTY` / `COMMUNITY`.                                                                                                  |
| `status`                   | enum             | n        | `LISTED`         | `LISTED` / `HIDDEN` / `ARCHIVED`.                                                                                              |
| `deterministic_recipe_ref` | string           | n        | null             | Hybrid AST hook (not yet executed).                                                                                            |
| `ai_fallback_prompt_path`  | string           | n        | null             | Path to a `prompts/migrations/*.md` file. See §6.                                                                              |
| `preservation_contract`    | map              | n        | null             | See §5.                                                                                                                       |
| `est_files`                | [int, int]       | n        | null             | `[low, high]`. Two-element list — other sizes ignored.                                                                         |
| `est_credits`              | [number, number] | n        | null             | Same shape, for the credit band.                                                                                              |
| `icon_url`                 | string           | n        | null             | Card icon.                                                                                                                    |
| `thumbnail_url`            | string           | n        | null             | Detail-page hero image.                                                                                                       |
| `sample_pr_url`            | string           | n        | null             |                                                                                                                               |
| `author_user_id`           | string           | n        | null             |                                                                                                                               |
| `author_org_id`            | string           | n        | null             |                                                                                                                               |
| `slots`                    | list[map]        | n        | empty            | See §3.3.                                                                                                                     |
| `variants`                 | list[map]        | y        | empty            | See §3.4. Recipe with zero variants will fail apply (`no variant available`).                                                  |
| `dependencies`             | list[map]        | n        | empty            | See §3.5.                                                                                                                     |

Unknown top-level keys are silently ignored. Recipe file-level failures are logged and skipped per-file; the seeder logs `[R-337] seeded migration recipe <slug>` on success, `[R-337] skipping <file> — <error>` on failure.

### 3.2 `source_stack` / `target_stack`

Both blobs are free-form JSON objects but the matcher (`MigrationStackMatcher.match`) only reads `language` + `framework`. Other fields like `min_version` / `target_version` / `target_orm` are surfaced in the DTO but not enforced in v1.

| YAML key         | Type   | Required (in `source_stack`) | Purpose                                                                                                          |
| ---------------- | ------ | ---------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `language`       | string | y                            | Normalised through `StackNormalizer.normalizeLanguage`. Repo's `detected_stack.language` must equal this.        |
| `framework`      | string | n                            | Normalised. If present, repo must match. If absent, recipe applies regardless of repo framework (language-only). |
| `min_version`    | string | n                            | Documented but not enforced. Surfaces in detail DTO.                                                              |
| `source_version` | string | n                            | Documented; reserved for hybrid AST work.                                                                         |

`target_stack` carries the same key set, plus any recipe-specific keys (e.g. `target_orm: prisma` for Mongoose → Prisma). The matcher doesn't read this column — it's purely informational for the UI's "X → Y" label.

Matcher outcomes (`MigrationStackMatcher.Outcome`):

- `APPLICABLE` — language matches; framework matches or absent in recipe.
- `INCOMPATIBLE_STACK` — language or framework differs.
- `UNKNOWN_STACK` — repo has no `detected_stack` populated, or the JSON didn't parse.

`UNKNOWN_STACK` blocks dry-run with a message asking the user to analyse the repo first; `INCOMPATIBLE_STACK` blocks with "pick a different recipe."

### 3.3 Slot keys (`slots[]`)

Same shape as `readymade_slot` (see the readymades doc for the full table). Recap:

| YAML key        | Type           | Required | Default       | Purpose                                                                                                       |
| --------------- | -------------- | -------- | ------------- | ------------------------------------------------------------------------------------------------------------- |
| `name`          | string         | y        | empty         | Stable identifier. Used as the `{{name}}` placeholder key.                                                    |
| `display_name`  | string         | n        | `name`        | Form-field label.                                                                                             |
| `type`          | enum           | n        | `TEXT`        | `TEXT` / `SELECT` / `SECRET` / `REPO_PICKER` / `FILE_PATH` / `NUMBER` / `BOOLEAN`.                            |
| `required`      | boolean        | n        | `false`       |                                                                                                               |
| `default_value` | string         | n        | null          |                                                                                                               |
| `options`       | list[string]   | depends  | null          | Required for `SELECT`.                                                                                        |
| `description`   | string         | n        | null          |                                                                                                               |
| `display_order` | int            | n        | `0`           |                                                                                                               |

Migration slots are typically smaller in number than readymade slots — most recipes have 0-2 slots, because the source stack is already pinned. Slots usually parameterise variant choice (e.g. `target_version` for Express → Fastify) or low-risk knobs (`tsconfig_strict` for JS → TS).

Validation is identical to readymades — runs through `SlotValidator.validateAll(slotDefs, userValues)`. Violations bubble up from `MigrationOrchestrator.apply` as HTTP 400.

### 3.4 Variant keys (`variants[]`)

| YAML key                  | Type             | Required | Default     | Purpose                                                                                                                                                                                            |
| ------------------------- | ---------------- | -------- | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `options_signature`       | string           | y        | `default`   | Human-readable cell id, unique per recipe. The apply endpoint takes this string to pick a specific variant. Convention: `key=value` pairs or `default` for single-variant recipes.                  |
| `options`                 | map              | n        | `{}`        | Variant-specific options. JSON-serialised to the `options` JSONB column. Empty map becomes `{}` (column is `NOT NULL`).                                                                            |
| `spec_template`           | string (block)   | y        | empty       | The prompt the V2 worker sees. May contain `{{slot_name}}` placeholders.                                                                                                                            |
| `ai_fallback_prompt_path` | string           | n        | null        | Per-variant override of the recipe's fallback prompt path. NULL → inherit.                                                                                                                          |
| `est_credits`             | [number, number] | n        | null        | Per-variant override of the parent recipe's cost band.                                                                                                                                              |

The seeder regenerates every variant from scratch on each reseed. Don't rely on variant IDs being stable.

### 3.5 Dependency keys (`dependencies[]`)

| YAML key       | Type           | Required | Default              | Purpose                                                                                                                                                                                                                  |
| -------------- | -------------- | -------- | -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `slug`         | string         | y        | empty                | Slug of the recipe this one depends on. Seeder resolves to UUID; if the target hasn't been seeded yet, the row is skipped with a warning and picked up on the next reseed.                                                |
| `kind`         | enum           | n        | `RECOMMENDED_AFTER`  | `PREREQUISITE` — hard precedence; apply path blocks until target has shipped successfully on this project. `RECOMMENDED_AFTER` — UI suggestion only.                                                                      |

---

## 4. `source_stack` vs `target_stack` JSON blobs

Two free-form JSON columns. The seeder rejects null on either — recipes without a stack predicate don't make sense.

Examples from `src/main/resources/migration-recipes/`:

**`express-to-fastify.yml`** — framework migration (TS preserved):

```yaml
source_stack:
  language: typescript
  framework: express
  min_version: "4.x"
target_stack:
  language: typescript
  framework: fastify
  target_version: "5.x"
```

**`js-to-typescript.yml`** — language migration (no framework constraint):

```yaml
source_stack:
  language: javascript
target_stack:
  language: typescript
```

When `framework` is absent in `source_stack` the recipe matches *any* framework — so the JS → TS recipe will apply against an Express app, a Next.js app, a Vue app, etc.

**`mongoose-to-prisma.yml`** — ORM migration (target carries a custom key):

```yaml
source_stack:
  language: typescript
  framework: express
target_stack:
  language: typescript
  framework: express
  target_orm: prisma     # custom; not matched by anything in v1
```

The matcher reads `language` and `framework` only. Other keys are surface in the DTO so the UI can render rich "from → to" labels.

`min_version` is documented but not enforced in v1. It's reserved for hybrid AST work (W8) where versions actually drive recipe behaviour. Today, the recipe authors should still set `min_version` to communicate intent.

---

## 5. `preservation_contract` JSON

A recipe-author declaration of "things this migration must NOT break." The reviewer's wiring pass enforces these deterministically post-edit.

Free-form JSON object. Convention is `{preserve_*: bool}` flags. Examples:

| Recipe                  | preservation_contract                                                                                                                  |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `express-to-fastify`    | `{preserve_routes: true, preserve_middlewares: true, preserve_response_codes: true, preserve_request_body_schema: true}`               |
| `mongoose-to-prisma`    | `{preserve_query_semantics: true, preserve_index_definitions: true, preserve_validation_rules: true}`                                  |
| `js-to-typescript`      | `{preserve_runtime_behaviour: true, preserve_export_surface: true}`                                                                    |
| `tailwind-v3-to-v4`     | `{preserve_visual_output: true, preserve_responsive_breakpoints: true}`                                                                |

Convention notes:

- Boolean flags only in v1. Future-compatible with richer types (paths, regex predicates) but the reviewer reads booleans today.
- Naming is `preserve_<thing>: true` so a "no constraint" recipe simply omits the key — the absence of a flag means the reviewer doesn't gate on it.
- The reviewer's wiring pass is project-type aware. For Express → Fastify, `preserve_routes` triggers a route-table comparison between source and target HEAD; mismatch → reviewer verdict drops to UNRESOLVED.
- The contract is surfaced in the gallery so users see "what's protected" before applying.

The contract goes hand-in-hand with the AI fallback prompt at `ai_fallback_prompt_path` — the prompt should call out the contract and instruct the model to preserve those properties; the reviewer then verifies.

---

## 6. `ai_fallback_prompt_path` — when deterministic falls back to AI

The migration product's v1 path is AI-only. The `deterministic_recipe_ref` column reserves space for the future hybrid AST path (W8) where ~70% of files convert mechanically via a deterministic recipe (e.g. `ast-grep`) and the residual 30% routes to AI.

Today, every file flows through the AI fallback. The recipe-level `ai_fallback_prompt_path` points at the prompt that the V2 worker loads when an AI pass is needed. Convention: `prompts/migrations/<slug>-fallback.md`.

Variants can override per-cell via the variant-level `ai_fallback_prompt_path`. NULL on the variant inherits the recipe-level path.

The prompt itself should:

- Restate the preservation contract.
- Identify the input shape (current file content + relevant context).
- Spell out the expected output shape (replacement content; no shell-outs).
- Cite at least one before/after example.

These prompt files live alongside the rest of the platform's prompts at `src/main/resources/prompts/`. They're not authored in the YAML — only referenced by path.

---

## 7. Tier gating

Same ladder as Readymades — `FREE` < `PRO` < `TEAM` < `ENTERPRISE`, defined by `MigrationRecipe.Tier`. Lock check in `MigrationService.isLocked(requiredTier, callerTier)`.

| Caller tier | Sees | Can dry-run                        | Can apply                          |
| ----------- | ---- | ---------------------------------- | ---------------------------------- |
| FREE        | all  | FREE only                          | FREE only                          |
| PRO         | all  | FREE, PRO                          | FREE, PRO                          |
| TEAM        | all  | FREE, PRO, TEAM                    | FREE, PRO, TEAM                    |
| ENTERPRISE  | all  | all                                | all                                |
| anonymous   | all  | none (auth required)               | none                               |

Two enforcement points, both 403:

- `MigrationController.dryRun` enforces the tier gate at the dry-run boundary. Sprint 2 audit finding [F-05] (2026-04-30) added this — dry-run is the buying signal (per-repo applicability + cost band + sample diff), and leaking the full value to FREE users on locked recipes was the classic SaaS-buyer mistake. Now mirrors apply's tier gate.
- `MigrationOrchestrator.apply` enforces it at the apply boundary with the same upgrade-to-tier message shape.

Lock state surfaces in two response shapes:

- `MigrationListItemDto.locked` boolean + `unlockTier` string. Drives gallery lock icon.
- `MigrationDetailDto` carries the same two fields so a deep-link to a locked recipe renders the upgrade CTA without an extra round-trip.

---

## 8. `POST /api/migrations/{slug}/dry-run`

Phase 1 of the two-phase halt-and-inspect plan gate. Implemented by `MigrationDryRunService.dryRun(slug, projectId, repoIds)`.

**No LLM call.** Safe to invoke on every page load of the recipe-detail page.

Request body (`MigrationController.DryRunRequest`):

```json
{
  "projectId": "<uuid>",
  "repoIds": ["<uuid>", "..."]
}
```

Steps per repo:

1. Confirm the repo is linked to the project. If not → `NOT_LINKED`.
2. Load the repo and check `detected_stack`. If absent → `UNKNOWN_STACK`.
3. Run `MigrationStackMatcher.match(recipe.source_stack, repo.detected_stack)`. Outcome:
   - `APPLICABLE` — populate per-repo file count + cost band. Falls back to the recipe-level `est_*` columns when no `migration_dry_run_cache` row exists for the (recipe, stack signature) pair.
   - `INCOMPATIBLE_STACK` — null bands; reason explains "recipe doesn't match this repo's detected stack."
   - `UNKNOWN_STACK` — null bands; reason explains "analyse the repo first."
4. For the first APPLICABLE repo, pull `sample_diff` from `migration_dry_run_cache` (when present). Sample-diff generation is reserved for the apply path's first run on a given (recipe, stack); the v1 dry-run never recomputes it.

Response (`MigrationDryRunDto`):

```json
{
  "slug": "express-to-fastify",
  "name": "Express → Fastify",
  "estCostLowTotal": 5.0,
  "estCostHighTotal": 30.0,
  "estFilesLowTotal": 10,
  "estFilesHighTotal": 60,
  "sampleDiff": "<truncated diff or null>",
  "perRepo": [
    {
      "repoId": "<uuid>",
      "fullName": "acme/api",
      "status": "APPLICABLE",
      "reason": null,
      "estFilesLow": 10,
      "estFilesHigh": 60,
      "estCostLow": 5.0,
      "estCostHigh": 30.0
    }
  ]
}
```

The totals (`estCostLow/HighTotal`, `estFilesLow/HighTotal`) are sums across APPLICABLE repos only. The UI surfaces these as a single chip ("≈45 files across 3 repos for ≈$12–$30"). The per-row bands drive per-repo chips.

Tier gate fires at this endpoint too (added by audit finding [F-05]). Caller below required tier → HTTP 403.

---

## 9. `POST /api/migrations/{slug}/apply`

Phase 2 of the plan gate. Implemented by `MigrationOrchestrator.apply(slug, req, callerUserId)`.

Request body (`MigrationOrchestrator.ApplyRequest`):

```json
{
  "projectId": "<uuid>",
  "repoIds": ["<uuid>", "..."],
  "slotValues": {
    "target_version": "5.x"
  },
  "optionsSignature": "target_version=5.x",
  "branch": "feature/fastify-migration"
}
```

Steps:

1. Resolve recipe by slug. 404 if missing.
2. Resolve caller's `AppUser`. 401 if missing.
3. Tier-gate check via `isLockedFor(recipe, user)`. 403 if locked.
4. Validate slot values against slot definitions. `SlotValidator.validateAll` returns violations; non-empty → 400 with semicolon-joined summary.
5. Pick the variant via `pickVariant(recipeId, optionsSignature)` — explicit signature > first variant.
6. Build the defaults map from each slot's `default_value`. Compose the spec via `SpecComposer.compose(template, slotValues, defaults)`.
7. Prepend a tracking header: `"Migration recipe: <name> (slug=<slug>)\nVariant: <options_signature>\n\n<spec>"`.
8. Build a `CreateExecutionRequest` carrying the composed spec as `requirement`, the selected repo IDs, and the branch override.
9. Dispatch via `ExecutionOrchestrator.createExecution(project, user, createReq)`. Multi-repo Execution View handles parallel V2 dispatch per repo.
10. Stamp `execution_run.migration_recipe_id` + `.migration_variant_id` on the created row (columns added in `V159__execution_run_migration_recipe.sql`). These power the project dashboard's "this run was migration X" display and per-recipe cost attribution.
11. Trigger analysis via `executionOrchestrator.triggerAnalysis(runId)`.

Response (HTTP 202):

```json
{
  "executionRunId": "<uuid>",
  "status": "PENDING",
  "message": "Migration started across 2 repo(s). Track progress on the run detail page."
}
```

The UI navigates to the run-detail surface using `executionRunId`; the rest of the V2 lifecycle (plan gate → execute → reviewer → PR) is unchanged.

The cost-cap UX (FREE / PRO see a per-launch cap input; TEAM / ENTERPRISE see info-only) lives in the controller-frontend; the V2 cost tracker enforces budget at runtime via the existing credit system.

---

## 10. Other library endpoints

| Method | Path                                | Purpose                                                                       |
| ------ | ----------------------------------- | ----------------------------------------------------------------------------- |
| GET    | `/api/migrations`                   | Gallery list with optional `category` / `tier` / `search` filters.            |
| GET    | `/api/migrations/{slug}`            | Detail page (recipe + variants + slots + dependencies + lock state).          |
| POST   | `/api/migrations/{slug}/dry-run`    | Phase 1 plan gate (no LLM call). See §8.                                      |
| POST   | `/api/migrations/{slug}/apply`      | Phase 2 — dispatches V2 run. See §9.                                          |
| POST   | `/api/admin/migrations/reseed`      | SUPER_ADMIN-only on-demand reseed (no JVM restart needed).                    |

---

## 11. Annotated example

A complete `express-to-fastify.yml` — the canonical first-party recipe with two variants:

```yaml
# Stable id. Picked by URL path and seeder upsert.
slug: express-to-fastify

# Display title.
name: Express → Fastify

# Single-line hook.
tagline: Migrate your Express app to Fastify for 2-3x throughput

# Block scalar — use `|` for multi-line content.
description: |
  Rewrites Express route handlers + middleware into Fastify
  equivalents. Preserves every public route + payload contract;
  the W8 hybrid AST pass converts ~70% mechanically and routes
  the residual to AI fallback.

# Stack the recipe transforms FROM. Required.
source_stack:
  language: typescript                   # normalised through StackNormalizer
  framework: express
  min_version: "4.x"                     # documented but not enforced in v1

# Stack the recipe transforms TO. Required. Surfaced in the gallery.
target_stack:
  language: typescript
  framework: fastify
  target_version: "5.x"

# Glob narrowing affected files. Glob-only in v1; no content predicates.
affected_files_glob: "src/**/*.{js,ts}"

# Facet bucket.
category: framework-migration

# Free-form tags.
tags: [express, fastify, backend]

# Required tier. PRO is the default; FREE for low-risk language migrations.
tier: PRO

source: FIRST_PARTY
status: LISTED

# Path to the AI fallback prompt. Used when the deterministic path
# (when wired) routes a file to AI. Today every file goes through this prompt.
ai_fallback_prompt_path: prompts/migrations/express-to-fastify-fallback.md

# Invariants the reviewer enforces post-edit.
# Boolean flags only in v1. See §5.
preservation_contract:
  preserve_routes: true
  preserve_middlewares: true
  preserve_response_codes: true
  preserve_request_body_schema: true

# 2-tuple: [low, high]. Surfaced on the card + dry-run.
est_files: [10, 60]
est_credits: [5.0, 30.0]

# User-fillable parameters. Most migrations have 0-2 slots.
slots:
  - name: target_version                 # → {{target_version}} placeholder
    display_name: Target Fastify version
    type: SELECT                          # dropdown; options required
    required: true
    default_value: "5.x"                  # SpecComposer falls back to this when blank
    options: ["5.x", "4.x-compat"]
    description: 5.x is the latest stable; 4.x-compat preserves Express-style middleware shape.
    display_order: 0

# Two variants — one per (options) cell. Apply endpoint routes by options_signature.
variants:
  - options_signature: target_version=5.x  # unique within this recipe
    options: {target_version: "5.x"}        # JSON-serialised to migration_variant.options
    spec_template: |                         # block scalar with {{slot}} placeholders
      Migrate this Express application to Fastify {{target_version}}.
      - Replace `app.get/post/put/delete` with Fastify's
        `fastify.route` / `fastify.get` etc., preserving the URL,
        HTTP method, and response contract for every existing route
      - Convert Express middleware (`(req,res,next) => {}`) to
        Fastify hooks (`onRequest` / `preHandler`) preserving order
      - Replace `req.body` with Fastify's request validation schema
      - Replace `res.status().json()` with `reply.code().send()`
      - Update package.json: remove express, add fastify ^5
      - Preserve ALL existing routes + status codes + body schemas
        exactly; the reviewer will verify via the preservation
        contract

  - options_signature: target_version=4.x-compat
    options: {target_version: "4.x-compat"}
    spec_template: |
      Migrate this Express application to Fastify with the
      `@fastify/middie` Express-compat shim so existing
      `(req,res,next)` middleware continues to work unchanged.
      - Mount `@fastify/middie` so all existing middleware modules
        attach via `fastify.use(...)`
      - Convert routes to `fastify.route` but keep the request /
        response shape Express-style for backwards compatibility
      - This is a transitional path; the v5 native route shape is
        recommended once middleware is migrated

# Optional follow-up suggestions. PREREQUISITE blocks apply; RECOMMENDED_AFTER suggests.
dependencies:
  - slug: js-to-typescript
    kind: RECOMMENDED_AFTER                  # surfaced as "consider also:" in the UI
```

A simpler shape: `src/main/resources/migration-recipes/js-to-typescript.yml` — single `default` variant, one `BOOLEAN` slot, no dependencies. Even simpler: `src/main/resources/migration-recipes/tailwind-v3-to-v4.yml` — zero slots, single `default` variant.

---

## 12. How to add a new recipe

Quick path: drop a YAML, run reseed, verify.

1. **Pick a slug.** Lowercase, hyphenated, descriptive of the source → target transformation (`cjs-to-esm`, `pages-router-to-app-router`, `mocha-to-vitest`). Check `src/main/resources/migration-recipes/` for collisions. The slug becomes the URL path (`/api/migrations/<slug>`) and the file name (by convention).
2. **Author the YAML.** Drop a `<slug>.yml` under `src/main/resources/migration-recipes/`. The minimum-viable file needs `slug`, `name`, `source_stack`, `target_stack`, `affected_files_glob`, and at least one variant with a `spec_template`.
3. **Pin the source stack carefully.** Set `language` to the canonical normalised form (`typescript`, `javascript`, `python`, etc.). Set `framework` only if the recipe truly requires that framework (e.g. Express → Fastify). Omit `framework` for language-only migrations (e.g. JS → TS) so the recipe applies regardless of the user's framework.
4. **Declare the preservation contract.** What MUST the migration preserve? Routes? Response schemas? Index definitions? Runtime behaviour? Each `preserve_*: true` flag becomes a check in the reviewer's wiring pass.
5. **Write the AI fallback prompt.** Drop a markdown file at the path you reference in `ai_fallback_prompt_path` (convention: `src/main/resources/prompts/migrations/<slug>-fallback.md`). The prompt should restate the preservation contract, identify the file shape, and cite at least one before/after example.
6. **Write each variant.** Most recipes have one variant (`options_signature: default`). Add variants only when the target stack legitimately branches (e.g. Mongoose → Prisma where target DB varies). Per-variant `spec_template` is what the V2 worker actually executes.
7. **Declare slots.** Each `{{placeholder}}` in any variant's `spec_template` needs a matching slot. Migration slots are typically smaller than readymade slots — most recipes need 0-2. Mark `required: true` only when the spec literally cannot render without it.
8. **Set `est_files` + `est_credits`.** These drive the cost-band chip and the dry-run's per-repo estimates. Eyeball from comparable recipes (Express → Fastify: 10-60 files / 5-30 credits; JS → TS: 20-200 / 10-80).
9. **Reseed.** Two options:
   - Restart the JVM. The `MigrationRecipeSeeder.@PostConstruct` hook reseeds on boot.
   - Hit `POST /api/admin/migrations/reseed` as a SUPER_ADMIN. Response shape is `{loaded: N, status: "ok"}`. The seeder logs `[R-337] seeded migration recipe <slug>` on success; per-file failures are logged but don't fail the request. A recipe with null `source_stack` or `target_stack` will be rejected by `applyRecipe` with `IllegalArgumentException("recipe <slug> missing source_stack or target_stack")`.
10. **Verify.** `GET /api/migrations?search=<slug-substring>` should return the card. `GET /api/migrations/<slug>` should show variants + slots + (locked / unlocked) state. From the UI, the gallery should render and clicking the card should open the detail page with the preservation contract surfaced.
11. **Test dry-run.** Against a test project with one repo whose `detected_stack` matches your `source_stack`, `POST /api/migrations/<slug>/dry-run` should return `APPLICABLE` for that repo with file + cost bands. Try with a non-matching repo — should return `INCOMPATIBLE_STACK`. Try with a repo that has no detected_stack — should return `UNKNOWN_STACK`.
12. **Test apply.** `POST /api/migrations/<slug>/apply` with valid slot values returns HTTP 202 with `executionRunId`. Let the V2 pipeline execute. A PR opens against the target repo with the migration realised; the reviewer's wiring pass enforces the preservation contract; if any flag fails the run drops to UNRESOLVED status.
13. **Calibrate.** After first runs, look at actuals vs `est_credits_high`. Bump or tighten the band. Per-variant overrides take precedence.

### Retiring a recipe

Set `status: ARCHIVED` in the YAML and reseed. The seeder upserts; the row stays in the DB but is excluded from gallery queries (`findByStatus(LISTED)`). Existing `execution_run.migration_recipe_id` rows keep working.

To delete entirely: `DELETE FROM migration_recipe WHERE slug = '...'`. The FK cascade scrubs variants, slots, dependencies, and dry-run cache rows. Existing `execution_run.migration_recipe_id` columns are not FK-bound and will silently dangle — leave the row in place (ARCHIVED) when historical attribution matters.

### Editing an existing recipe

Recipes are upserted by `slug`. Variants, slots, and dependencies are delete-and-replace per parent recipe on each reseed. This means:

- Changing `source_stack` invalidates the `migration_dry_run_cache` rows for this recipe — the cached samples + cost bands assume a specific source stack. Manually purge the cache (`DELETE FROM migration_dry_run_cache WHERE recipe_id = '...'`) when changing the stack predicate.
- Changing `affected_files_glob` doesn't invalidate the cache, but the next dry-run + apply may compute different file counts than the cached estimate.
- Changing `preservation_contract` retroactively affects the reviewer's wiring pass on *new* runs only; historical runs aren't re-judged.
- Variant IDs are regenerated on every reseed; the `execution_run.migration_variant_id` column captures the variant used at apply time, so historical runs continue to display correctly.
- Dependencies pointing at unseeded slugs are skipped with a warning and resolve on the next reseed.

### Authoring tip — share with Readymades where it makes sense

The slot validator (`SlotValidator`), spec composer (`SpecComposer`), and stack normaliser (`StackNormalizer`) are all shared across both products. When you're authoring a migration recipe, you're authoring against the same validation + substitution machinery as readymades — the slot type list, the `{{placeholder}}` syntax, the fallback-to-default semantics are all identical. Read the readymades catalog doc for any of those details and assume they apply here too.
