# Migration Recipes — catalog file format

> **Status:** authoritative.
> **Last reviewed:** 2026-05-12 (1.0.0-M1).
> **Branch:** Migrations product W1-W7 shipped on `feature/readymades-and-migrations` (not yet merged to dev/main). See [`plans/pending.md`](plans/pending.md) §8.
> **Audience:** internal authors + community contributors who add new migration recipes.

## Where catalog files live

`src/main/resources/migration-recipes/<slug>.yml` — **one file per
recipe**, indexed by `slug`. The seeder
(`MigrationRecipeSeeder`) loads every `*.yml` under that classpath
location at boot and on-demand via the admin reseed endpoint.

## File schema

A single recipe YAML file looks like this:

```yaml
slug: express-to-fastify             # required, unique, [a-z0-9-]+, ≤ 64 chars
name: Express → Fastify              # required, ≤ 160 chars
tagline: Migrate your Express app to Fastify v5
description: |
  Rewrites Express route handlers + middleware into Fastify
  equivalents. Preserves every public route + payload contract;
  the W8 hybrid AST pass converts ~70% mechanically and routes
  the residual to AI fallback.
source_stack:                        # required
  language: typescript
  framework: express
  min_version: "4.x"
target_stack:                        # required
  language: typescript
  framework: fastify
  target_version: "5.x"
affected_files_glob: "src/**/*.{js,ts}"   # required
category: framework-migration        # required
tags: [express, fastify, backend]
tier: PRO                            # required
source: FIRST_PARTY
status: LISTED
deterministic_recipe_ref: ast-grep:./recipes/express-to-fastify.yml   # W8 hook (NULL until W8)
ai_fallback_prompt_path: prompts/migrations/express-to-fastify-fallback.md
preservation_contract:
  preserve_routes: true
  preserve_middlewares: true
  preserve_response_codes: true
est_files: [10, 60]
est_credits: [5.0, 30.0]
slots:
  - name: target_version
    display_name: Target Fastify version
    type: SELECT
    required: true
    default_value: "5.x"
    options: ["5.x", "4.x-compat"]
    display_order: 0
variants:
  - options_signature: target_version=5.x
    options: {target_version: "5.x"}
    spec_template: |
      Convert this Express app to Fastify {{target_version}}.
      Rewrite every route handler...
dependencies:
  - slug: js-to-typescript
    kind: RECOMMENDED_AFTER          # PREREQUISITE | RECOMMENDED_AFTER
```

## Field reference

### Top-level

| Field | Type | Required | Notes |
|---|---|---|---|
| `slug` | string | yes | URL-safe natural key. Unique. |
| `name`, `tagline`, `description` | string | name yes | Card + detail page text. |
| `source_stack` | object | yes | `{language, framework, min_version}`; must match the user's repo for the recipe to be applicable. Stored as JSONB; queried via the V158 GIN index. |
| `target_stack` | object | yes | `{language, framework, target_version}`. |
| `affected_files_glob` | string | yes | e.g. `"src/**/*.{js,ts}"`. Glob-only in v1; content predicates added later. |
| `category` | string | yes | Sub-category within the migrations product. |
| `tags` | string[] | no | Free-form. |
| `tier` | enum | yes | `FREE` / `PRO` / `TEAM` / `ENTERPRISE`. |
| `source` | enum | no | `FIRST_PARTY` (default) / `COMMUNITY`. |
| `status` | enum | no | `LISTED` (default). |
| `deterministic_recipe_ref` | string | no | W8 hybrid AST hook. NULL = AI-only path. Format `ast-grep:<path>` or `openrewrite:<recipe-id>`. |
| `ai_fallback_prompt_path` | string | yes | e.g. `prompts/migrations/<slug>-fallback.md`. |
| `preservation_contract` | object | yes | What the migration must preserve (declared by author, enforced by reviewer wiring pass). e.g. `{preserve_routes: true, preserve_types: true}`. |
| `est_files`, `est_credits` | `[low, high]` | no | Cost-transparency chip data. |
| `slots`, `variants`, `dependencies` | array | variants ≥ 1 | See below. |

### Slot

Same shape as Readymade slots — `name`, `display_name`, `type`
(`TEXT` / `SELECT` / `SECRET` / `REPO_PICKER` / `FILE_PATH` /
`NUMBER` / `BOOLEAN`), `required`, `default_value`, `options`,
`description`, `display_order`. Cross-product service classes
(`SlotValidator`, `SpecComposer`) ship in Readymades W5 and are
shared with Migrations.

### Variant

| Field | Type | Required | Notes |
|---|---|---|---|
| `options_signature` | string | yes | Concise human-readable signature for the (options) cell. e.g. `target_db=postgres,target_version=5.x`. Unique per recipe. |
| `options` | object | yes | Arbitrary key/value. |
| `spec_template` | string | yes | Migration spec text with `{{slot}}` placeholders. |
| `ai_fallback_prompt_path` | string | no | Per-variant override of recipe's path. |
| `est_credits` | `[low, high]` | no | Per-variant override. |

### Dependency

| Field | Type | Required | Notes |
|---|---|---|---|
| `slug` | string | yes | Slug of the depends-on recipe. |
| `kind` | enum | yes | `PREREQUISITE` (hard precedence; gate-block on apply) or `RECOMMENDED_AFTER` (surface as suggestion). |

## Author workflow

1. Drop a new file at `src/main/resources/migration-recipes/<slug>.yml`.
2. Author the AI fallback prompt at
   `src/main/resources/prompts/migrations/<slug>-fallback.md`.
3. Run the seeder once (`POST /api/admin/migrations/reseed` as
   SUPER_ADMIN).
4. Open `/v3/migrations` — your card should appear.
5. Submit as a PR; CI boots the catalog as part of integration.

## Seeder semantics

Mirror `ReadymadeSeeder`: idempotent upsert by slug,
delete-and-replace for variants/slots/dependencies, per-file
try/catch for tolerance, no auto-delete (use `status: ARCHIVED`).
