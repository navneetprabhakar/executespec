# Migration Recipes Catalog Format

> Recreated 2026-06-10 from `migration/` code. Migration recipes are structured, reusable migration specs with dry-run and apply flows.

## Runtime Shape

| Layer | Code |
| --- | --- |
| Public API | `migration/controller/MigrationController.java` |
| Admin reseed API | `migration/controller/MigrationAdminController.java` |
| Seeder | `migration/seeder/MigrationRecipeSeeder.java` |
| Listing/detail | `migration/service/MigrationService.java` |
| Dry run | `migration/service/MigrationDryRunService.java` |
| Stack matching | `migration/service/MigrationStackMatcher.java` |
| Apply | `migration/service/MigrationOrchestrator.java` |
| Entities | `MigrationRecipe`, `MigrationVariant`, `MigrationSlot`, `MigrationDependency`, `MigrationDryRunCache` |

YAML files are loaded from:

```text
src/main/resources/migration-recipes/*.yml
```

The seeder upserts recipes by `slug`, then delete-and-replaces variants, slots, and dependencies for that recipe.

## Required YAML Fields

```yaml
slug: express-to-fastify
name: Express to Fastify
tagline: Migrate routing and middleware
description: Move an Express service to Fastify.
category: node
tags: [node, express, fastify]
tier: TEAM
source: FIRST_PARTY
status: LISTED

source_stack:
  language: javascript
  frameworks: [express]

target_stack:
  language: javascript
  frameworks: [fastify]

affected_files_glob: "**/*.{js,ts}"
preservation_contract:
  keep_public_routes: true
  keep_error_shapes: true

est_files: [5, 20]
est_credits: [2, 5]

variants:
  - options_signature: default
    options: {}
    spec_template: |
      Migrate this service from Express to Fastify...

slots:
  - name: router_prefix
    display_name: Router prefix
    type: TEXT
    required: false

dependencies:
  - slug: node-test-modernization
    kind: RECOMMENDED_AFTER
```

Defaults:

- `tier`: `PRO`
- `source`: `FIRST_PARTY`
- `status`: `LISTED`
- variant `options_signature`: `default`
- slot type: `TEXT`

## Public API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/migrations` | List with `category`, `tier`, `search` filters |
| `GET /api/migrations/{slug}` | Detail |
| `POST /api/migrations/{slug}/dry-run` | Project read-guarded applicability/cost check |
| `POST /api/migrations/{slug}/apply` | Project write-guarded execution-run dispatch |

Dry-run enforces recipe tier before returning applicability details. Apply requires an authenticated user and project write permission.

## Tier Gating

Tier order:

```text
FREE < PRO < TEAM < ENTERPRISE
```

Dry-run and apply must both respect the recipe's required tier. Do not leak detailed migration applicability for locked recipes.

## Maintenance Rules

- Never rename a published `slug`.
- Hide or archive retired recipes; do not rely on absent YAML deleting rows.
- Keep `source_stack` and `target_stack` machine-readable and narrow enough for `MigrationStackMatcher`.
- Put deterministic transforms in `deterministic_recipe_ref` when available.
- Use `ai_fallback_prompt_path` only when deterministic migration cannot cover the case.
- After changing YAML, reseed locally and verify `/api/migrations`.

## Related Frontend

- `frontend/src/lib/api/discovery.ts`
- `frontend/src/lib/api/core.ts` migration DTOs
