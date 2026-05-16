# Readymade Solutions — catalog format

Operator + developer reference for the Readymade Solutions library that powers the `/library` surface. Use this when you want to seed a new solution, audit an existing one, or extend the schema.

The library is a list of pluggable building blocks (sign-in, payments, AI chat, analytics, etc.) that each ship a real PR adapted to the user's stack. Each card in the gallery is a `ReadymadeSolution` row. Selecting it fills a small form (slots), then dispatches a V2 run that opens a PR.

Source of truth: YAML files under `src/main/resources/readymades/*.yml`. The `ReadymadeSeeder` reads them at boot (`@PostConstruct`) and on demand from the SUPER_ADMIN reseed endpoint, upserting each into Postgres.

---

## 1. File system layout

| Concern                           | Location                                                                                   |
| --------------------------------- | ------------------------------------------------------------------------------------------ |
| Solution YAML files               | `src/main/resources/readymades/*.yml`                                                      |
| Boot-time + on-demand seeder      | `src/main/java/com/navneet/executespec/readymade/seeder/ReadymadeSeeder.java`              |
| Entities                          | `src/main/java/com/navneet/executespec/readymade/entity/`                                  |
| Library read API                  | `src/main/java/com/navneet/executespec/readymade/controller/ReadymadeController.java`      |
| Admin reseed endpoint             | `src/main/java/com/navneet/executespec/readymade/controller/ReadymadeAdminController.java` |
| Read-side service (list / detail) | `src/main/java/com/navneet/executespec/readymade/service/ReadymadeService.java`            |
| Variant resolver (stack matching) | `src/main/java/com/navneet/executespec/readymade/service/VariantResolver.java`             |
| Stack normaliser                  | `src/main/java/com/navneet/executespec/readymade/service/StackNormalizer.java`             |
| Instantiate orchestrator          | `src/main/java/com/navneet/executespec/readymade/service/ReadymadeOrchestrator.java`       |
| Slot validator (shared)           | `src/main/java/com/navneet/executespec/composer/SlotValidator.java`                        |
| Spec composer (shared)            | `src/main/java/com/navneet/executespec/composer/SpecComposer.java`                         |
| Database schema (Flyway W1)       | `src/main/resources/db/migration/V157__readymade_solution.sql`                             |
| Execution-run tracking columns    | `src/main/resources/db/migration/V160__execution_run_readymade.sql`                        |

The seeder discovers files by classpath glob (`classpath:readymades/*.yml`). The directory is flat — one file per solution, named to match the `slug`.

YAML only. JSON is not parsed. The loader uses `SafeYamlLoader.load(InputStream)`; nested objects (like a slot's `options` or a variant's `options`) are decoded as Java `Map` / `List` and re-serialised as JSONB strings by the seeder before writing to Postgres.

---

## 2. Entity overview

Five tables live under the readymade product, all created in `V157__readymade_solution.sql`. Cascading delete from `readymade_solution` to its children is configured at the DB layer.

### 2.1 `readymade_solution` — abstract library entry

The "card" in the gallery. One row per unique slug.

| Column             | Type             | Required | Purpose                                                                                                                                                  |
| ------------------ | ---------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`               | UUID PK          | y        | Generated at insert. Referenced by variant / slot / dependency child rows.                                                                               |
| `slug`             | VARCHAR(64) U    | y        | Stable, URL-safe ID. Used in API paths (`/api/library/{slug}`) and as the upsert key for the seeder.                                                     |
| `name`             | VARCHAR(160)     | y        | Display title on the card. Falls back to `slug` if YAML omits it.                                                                                        |
| `tagline`          | VARCHAR(255)     | n        | Single-line hook shown under the title.                                                                                                                  |
| `description`      | TEXT             | n        | Long-form blurb on the detail page.                                                                                                                      |
| `category`         | VARCHAR(64)      | y        | Facet key used by sidebar filters (`sign-in-and-users`, `take-payments`, etc.). Defaults `uncategorised`.                                                |
| `macro_group`      | VARCHAR(32)      | y        | High-level grouping above category (e.g. `people-and-money`, `smart-and-reach`). Drives the gallery's top-level chrome. Defaults `uncategorised`.        |
| `tags`             | JSONB            | n        | JSON array of strings. Surfaced as chips on cards; supports keyword search.                                                                              |
| `kind`             | VARCHAR(16)      | y        | Enum: `PRIMITIVE` (single drop-in), `STACK` (composition of others), `INTEGRATION` (wires an external service in). Defaults `PRIMITIVE`.                  |
| `tier`             | VARCHAR(16)      | y        | Enum: `FREE` / `PRO` / `TEAM` / `ENTERPRISE`. Drives the lock icon + upgrade CTA. Defaults `PRO`.                                                        |
| `source`           | VARCHAR(16)      | y        | Enum: `FIRST_PARTY` (curated) / `COMMUNITY` (user submitted). Defaults `FIRST_PARTY`.                                                                    |
| `status`           | VARCHAR(16)      | y        | Enum: `LISTED` / `HIDDEN` / `ARCHIVED`. Only `LISTED` rows appear in the gallery. Defaults `LISTED`.                                                     |
| `icon_url`         | TEXT             | n        | Small inline icon for the card / detail header.                                                                                                          |
| `thumbnail_url`    | TEXT             | n        | Larger preview image for the detail page.                                                                                                                |
| `sample_pr_url`    | TEXT             | n        | Link to a real demo PR. "This is what your PR will look like."                                                                                           |
| `est_files_low`    | INT              | n        | Lower bound of "how many files this PR will touch."                                                                                                      |
| `est_files_high`   | INT              | n        | Upper bound. Both surfaced as a band on the card.                                                                                                        |
| `est_credits_low`  | NUMERIC(10,2)    | n        | Lower bound of "how many credits this run will burn." Falls back to V2 cost estimate at apply time.                                                      |
| `est_credits_high` | NUMERIC(10,2)    | n        | Upper bound.                                                                                                                                             |
| `author_user_id`   | VARCHAR(36)      | n        | Set when `source = COMMUNITY`. NULL for FIRST_PARTY entries.                                                                                             |
| `author_org_id`    | VARCHAR(36)      | n        | Same, but for org attribution.                                                                                                                           |
| `created_at`       | TIMESTAMPTZ      | y        | Auto. `DEFAULT NOW()`.                                                                                                                                   |
| `updated_at`       | TIMESTAMPTZ      | y        | Auto. Refreshed by `@PreUpdate` on the entity.                                                                                                           |

Indexed by `(category, status)`, `(macro_group, status)`, `(tier, status)`, `(source, status)`, plus a GIN FTS index over `name + description + tagline`. The FTS index is wired up at the SQL level; the read service does in-memory substring matching today since the v1 catalog (~30 entries) is small enough.

### 2.2 `readymade_variant` — concrete (language, framework, options) cell

A solution like "Login with Google" has one variant per supported (language, framework) pairing. The variant carries the spec template that the V2 pipeline actually executes.

| Column             | Type             | Required | Purpose                                                                                                                          |
| ------------------ | ---------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `id`               | UUID PK          | y        | Generated.                                                                                                                       |
| `solution_id`      | UUID FK          | y        | `ON DELETE CASCADE` to parent solution.                                                                                          |
| `language`         | VARCHAR(32)      | y        | Normalised language key — `typescript`, `python`, `java`, `go`. Defaults `unknown` if YAML omits.                                |
| `framework`        | VARCHAR(64)      | y        | Normalised framework key — `express`, `fastify`, `spring-boot`, `fastapi`, `nextjs`. Defaults `unknown`.                         |
| `options`          | JSONB            | n        | Free-form variant-specific options like `{"db":"postgres","router":"app"}`. Surfaced in resolver match.                          |
| `spec_template`    | TEXT             | y        | The prompt the worker sees, with `{{slot}}` placeholders. Substituted by `SpecComposer` at apply time.                           |
| `scaffold_key`     | VARCHAR(64)      | n        | Optional reference into `prompts/scaffolds/<key>.md` (e.g. `node-backend`, `node-frontend`, `python`). Used by the V2 worker.    |
| `est_credits_low`  | NUMERIC(10,2)    | n        | Per-variant cost override. Falls back to the parent solution's value when null.                                                  |
| `est_credits_high` | NUMERIC(10,2)    | n        | Same.                                                                                                                            |
| `created_at`       | TIMESTAMPTZ      | y        | Auto.                                                                                                                            |

Indexed by `(solution_id)` for child lookups and `(language, framework)` for stack-driven resolution.

### 2.3 `readymade_slot` — user-fillable parameter

The form fields the user fills before launch. Each slot's `type` drives both the UI control and the validation rule.

| Column          | Type            | Required | Purpose                                                                                                       |
| --------------- | --------------- | -------- | ------------------------------------------------------------------------------------------------------------- |
| `id`            | UUID PK         | y        | Generated.                                                                                                    |
| `solution_id`   | UUID FK         | y        | `ON DELETE CASCADE`.                                                                                          |
| `name`          | VARCHAR(64)     | y        | Stable identifier used in `{{slot_name}}` placeholders in the variant's `spec_template`.                       |
| `display_name`  | VARCHAR(160)    | y        | Form-field label.                                                                                             |
| `type`          | VARCHAR(32)     | y        | One of `TEXT` / `SELECT` / `SECRET` / `REPO_PICKER` / `FILE_PATH` / `NUMBER` / `BOOLEAN`. Defaults `TEXT`.    |
| `required`      | BOOLEAN         | y        | `false` allows blank submission. Defaults `false`.                                                            |
| `default_value` | TEXT            | n        | Pre-populated value. `SpecComposer` falls back to this when the user value is blank.                          |
| `options`       | JSONB           | n        | For `SELECT`: JSON array of allowed string values (e.g. `["USD","EUR","INR","GBP"]`).                         |
| `description`   | TEXT            | n        | Help text under the form field.                                                                               |
| `display_order` | INT             | y        | Render-order within the form (ascending). Defaults `0`.                                                       |

Unique constraint on `(solution_id, name)`. Indexed by `(solution_id, display_order)` so the detail-page query is a single ordered range scan.

### 2.4 `readymade_dependency` — solution-on-solution composition graph

Declares "this solution needs that solution." Two flavours encoded by the `required` boolean column.

| Column          | Type           | Required | Purpose                                                                                                                                          |
| --------------- | -------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `solution_id`   | UUID FK        | y        | The dependent solution. `ON DELETE CASCADE` from parent.                                                                                         |
| `depends_on_id` | UUID FK        | y        | The solution it depends on.                                                                                                                       |
| `required`      | BOOLEAN        | y        | `true` (default) → auto-install if missing during instantiate. `false` → "recommended pairing" only — surfaced in the UI but never auto-applied. |

Composite primary key `(solution_id, depends_on_id)`. Indexed by `(depends_on_id)` for the reverse query "what depends on this?"

### 2.5 `readymade_favourite` — per-user pin

Per-user saved-list so frequently used solutions surface on the gallery. Cross-cutting; not authored in YAML.

| Column         | Type           | Required | Purpose                                                                                                                |
| -------------- | -------------- | -------- | ---------------------------------------------------------------------------------------------------------------------- |
| `user_id`      | VARCHAR(36)    | y        | App user that pinned the solution.                                                                                      |
| `solution_id`  | UUID FK        | y        | The pinned solution. `ON DELETE CASCADE` so removing a solution scrubs every favourite of it.                          |
| `created_at`   | TIMESTAMPTZ    | y        | Auto.                                                                                                                  |

Composite primary key `(user_id, solution_id)`. Indexed by `(solution_id)` for the reverse query "who pinned this solution?"

---

## 3. YAML schema

Top-level keys map roughly one-to-one with `readymade_solution` columns. Three nested arrays — `slots`, `variants`, `dependencies` — populate the child tables.

### 3.1 Solution-level keys

| YAML key           | Type             | Required | Default          | Purpose                                                                                                                            |
| ------------------ | ---------------- | -------- | ---------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `slug`             | string           | y        | —                | If absent the file is skipped with a warning. Upsert key.                                                                          |
| `name`             | string           | n        | `slug`           | Display title.                                                                                                                     |
| `tagline`          | string           | n        | null             | Single-line hook.                                                                                                                  |
| `description`      | string (block)   | n        | null             | Long-form blurb. Use `description: |` for block scalar.                                                                            |
| `category`         | string           | n        | `uncategorised`  | Facet bucket.                                                                                                                      |
| `macro_group`      | string           | n        | `uncategorised`  | Higher-level facet bucket.                                                                                                         |
| `tags`             | list[string]     | n        | null             | Free-form tags.                                                                                                                    |
| `kind`             | enum             | n        | `PRIMITIVE`      | `PRIMITIVE` / `STACK` / `INTEGRATION`. Case-insensitive; hyphens accepted.                                                          |
| `tier`             | enum             | n        | `PRO`            | `FREE` / `PRO` / `TEAM` / `ENTERPRISE`. Defaults `PRO` for safety (PRO+ is the most common cell).                                  |
| `source`           | enum             | n        | `FIRST_PARTY`    | `FIRST_PARTY` / `COMMUNITY`.                                                                                                       |
| `status`           | enum             | n        | `LISTED`         | `LISTED` / `HIDDEN` / `ARCHIVED`. Use `ARCHIVED` to retire a solution; the seeder never auto-deletes.                              |
| `icon_url`         | string           | n        | null             | Card icon.                                                                                                                         |
| `thumbnail_url`    | string           | n        | null             | Detail-page hero image.                                                                                                            |
| `sample_pr_url`    | string           | n        | null             | Real demo PR link.                                                                                                                 |
| `est_files`        | [int, int]       | n        | null             | Two-element list — `[low, high]`. Seeder splits into `est_files_low` + `est_files_high`. Lists with a different size are ignored.   |
| `est_credits`      | [number, number] | n        | null             | Same shape, for the credit band.                                                                                                   |
| `author_user_id`   | string           | n        | null             | Set when `source: COMMUNITY`.                                                                                                      |
| `author_org_id`    | string           | n        | null             |                                                                                                                                    |
| `slots`            | list[map]        | n        | empty            | See §3.2.                                                                                                                          |
| `variants`         | list[map]        | n        | empty            | See §3.3.                                                                                                                          |
| `dependencies`     | list[map]        | n        | empty            | See §3.4.                                                                                                                          |

Unknown top-level keys are silently ignored. Parse errors of a single file are logged and skipped — they never fail the seeder boot. The seeder logs `[R-328] seeded readymade <slug>` for each successful upsert, `[R-328] skipping <file> — <error>` for each failure.

### 3.2 Slot keys (`slots[]`)

| YAML key        | Type           | Required | Default       | Purpose                                                                                                       |
| --------------- | -------------- | -------- | ------------- | ------------------------------------------------------------------------------------------------------------- |
| `name`          | string         | y        | empty         | Stable identifier. Used as the `{{name}}` placeholder key. Must be unique within the solution.                |
| `display_name`  | string         | n        | `name`        | Form-field label.                                                                                             |
| `type`          | enum           | n        | `TEXT`        | `TEXT` / `SELECT` / `SECRET` / `REPO_PICKER` / `FILE_PATH` / `NUMBER` / `BOOLEAN`.                            |
| `required`      | boolean        | n        | `false`       | When `true`, instantiate rejects blank submission with a `REQUIRED` violation.                                |
| `default_value` | string         | n        | null          | `SpecComposer` falls back to this when the user's value is blank or absent.                                   |
| `options`       | list[string]   | depends  | null          | Required for `SELECT`. For `BOOLEAN` / `NUMBER` etc. it's ignored.                                            |
| `description`   | string         | n        | null          | Inline help text.                                                                                             |
| `display_order` | int            | n        | `0`           | Ascending render order in the form.                                                                           |

### 3.3 Variant keys (`variants[]`)

| YAML key           | Type             | Required | Default     | Purpose                                                                                                                          |
| ------------------ | ---------------- | -------- | ----------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `language`         | string           | y        | `unknown`   | Normalised language. Stack matching is case-insensitive and goes through `StackNormalizer`.                                       |
| `framework`        | string           | y        | `unknown`   | Normalised framework. Empty / omit means "any framework" — only legal when language alone disambiguates.                          |
| `options`          | map              | n        | null        | Variant-specific options like `{db: postgres, router: app}`. Free-form; surfaced in the DTO.                                     |
| `spec_template`    | string (block)   | y        | empty       | The prompt the V2 worker sees. May contain `{{slot_name}}` placeholders.                                                          |
| `scaffold_key`     | string           | n        | null        | Reference into `prompts/scaffolds/<key>.md`. The V2 worker auto-loads the scaffold when set.                                     |
| `est_credits`      | [number, number] | n        | null        | Per-variant override of the parent solution's cost band.                                                                          |

The seeder regenerates every variant from scratch on each reseed (delete-by-`solution_id` then insert). Don't rely on variant IDs being stable across reseeds — they aren't.

### 3.4 Dependency keys (`dependencies[]`)

| YAML key       | Type           | Required | Default    | Purpose                                                                                                                                                                                       |
| -------------- | -------------- | -------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `slug`         | string         | y        | empty      | Slug of the solution this one depends on. The seeder resolves it to a UUID; if the target hasn't been seeded yet on this pass, the row is skipped with a warning + retried on the next reseed. |
| `required`     | boolean        | n        | `true`     | `true` → auto-install the dependency at instantiate time if missing. `false` → "recommended pairing" only.                                                                                    |

---

## 4. Slot types

The slot's `type` drives the UI control, the validator in `SlotValidator`, and the JSON serialisation of `options`. All seven supported types come from `ReadymadeSlot.Type` and `MigrationSlot.Type` (they share the same enum surface; the validator works off the type name string so both products can use it).

| Type          | UI control        | Validator rule (`SlotValidator`)                                                          | Authored options                                       |
| ------------- | ----------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| `TEXT`        | single-line input | Length ≤ 4096 chars.                                                                       | none                                                   |
| `SELECT`      | dropdown          | Value must be a member of `options` array (when non-empty).                                | required — JSON array of strings                       |
| `SECRET`      | masked input      | Length ≥ 4 and ≤ 4096. Never logged.                                                       | none                                                   |
| `REPO_PICKER` | repo dropdown     | Allows UUID or `owner/repo` shape. Full FK check happens at the orchestrator layer.        | none                                                   |
| `FILE_PATH`   | text input        | Rejects `/`-prefixed paths and `..` to prevent escapes from the workspace root.            | none                                                   |
| `NUMBER`      | numeric input     | `Double.parseDouble(value)` succeeds.                                                      | none                                                   |
| `BOOLEAN`     | toggle / checkbox | Value (lowercased) is `"true"` or `"false"`.                                               | none                                                   |

Validation runs in `SlotValidator.validateAll(slotDefs, userValues)`. Two extra rules apply across all types:

- `required: true` plus blank value → `REQUIRED` violation.
- A user-submitted slot key that doesn't appear in the solution's slot list → `UNKNOWN_SLOT` violation. This catches typos / drift.

Violations bubble up from `ReadymadeOrchestrator.instantiate` as HTTP 400 with a semicolon-joined summary string.

---

## 5. Variants — multiple implementations of the same solution

A solution like "Login with Google" needs different code for an Express + TypeScript backend vs a Next.js App Router app vs a FastAPI Python service. Each goes into its own `ReadymadeVariant` row under the same parent solution.

The runtime picks one variant per (solution, repo) tuple via `VariantResolver.resolve(solutionId, projectId, repoId)`:

1. Read the linked `ProjectRepo.detected_stack` JSON column. It's populated by the project's `StackDetector` at attach + sync time.
2. Normalise the detected `language` + `framework` through `StackNormalizer`. The detector emits human-friendly strings like `"TypeScript/JS"` and `"Spring Boot"`; variants are authored with `"typescript"` and `"spring-boot"`. The normaliser collapses the compound `TypeScript/JS` to `typescript` (the TS-side of the choice — safer because tsc's `allowJs` path handles the JS files).
3. Look up `findBySolutionIdAndLanguageAndFramework(solutionId, language, framework)`. Exact match returns the variant; the DTO carries a `reason` field of `OK`.
4. Outcomes when no exact match:
   - `STACK_NOT_DETECTED` — the repo has no detected_stack yet (newly attached, or detection failed). UI lists all variants and asks the user to pick.
   - `NO_VARIANT_FOR_STACK` — stack detected but no variant authored for it. Same UX — user picks manually.
   - `REPO_NOT_LINKED` — the repo isn't actually linked to this project. Controller-layer 400.

`ReadymadeOrchestrator.pickVariant` has a three-tier fallback: explicit `variantId` from the UI → auto-resolve via stack → first variant. So a solution that ships only one variant works without any stack-detection logic firing.

---

## 6. Dependencies — solution-on-solution graph

A dependency row in `readymade_dependency` is one directed edge. `required: true` means "auto-install if not already present in the project"; `required: false` means "recommended pairing only — surface in the UI but never auto-run."

Example shape, in YAML, on a `login-with-google` solution that wraps the User entity:

```yaml
dependencies:
  - slug: user-entity
    required: true       # auto-install if missing
  - slug: email-verify
    required: false      # nice-to-have follow-up
```

The seeder resolves each `slug` to its parent UUID and inserts the edge. If the target slug hasn't been seeded yet on this pass, the dependency row is skipped with a warning and picked up on the next reseed (the seeder is fully idempotent, so this is safe).

The graph today is wired up at the data layer; the auto-install path (executing dependent solutions before the primary one) is scoped for a future wave — `ReadymadeOrchestrator` doesn't traverse the graph yet. Recommended pairings are surfaced on the gallery card.

---

## 7. Tier gating

Each solution declares its required tier. The ladder is `FREE` < `PRO` < `TEAM` < `ENTERPRISE` (defined by `ReadymadeSolution.Tier` enum ordinal).

A solution is **locked** for a caller when the required tier is strictly above the caller's tier. The check lives in `ReadymadeService.isLocked(requiredTier, callerTier)`.

| Caller tier | Sees | Can launch                          |
| ----------- | ---- | ----------------------------------- |
| FREE        | all  | FREE only                           |
| PRO         | all  | FREE, PRO                           |
| TEAM        | all  | FREE, PRO, TEAM                     |
| ENTERPRISE  | all  | all                                 |
| anonymous   | all  | none (auth required to instantiate) |

Lock state surfaces in three response shapes:

- `ReadymadeListItemDto` carries `locked` boolean and `unlockTier` (set when locked) — `"PRO"` / `"TEAM"` / `"ENTERPRISE"` — so the gallery can render a lock chip + upgrade-CTA pointing at the right tier.
- `ReadymadeDetailDto` carries the same two fields at the top level so a deep-link to a locked solution renders the upgrade CTA without an extra round-trip.
- `ReadymadeOrchestrator.instantiate` enforces the tier gate hard — caller below required tier → HTTP 403 with body `"This solution requires the <TIER> tier. Upgrade to use."`

The lock UX is "show but lock" — every tier sees every card. The lock icon + upgrade CTA is the buying signal. The orchestrator is the only enforcement point.

---

## 8. Instantiation flow

The library has two write endpoints. The first is read-only; the second submits a V2 run.

### 8.1 `POST /api/library/{slug}/resolve`

Read-only variant resolution against a project + repo. No LLM call, no DB write. Safe to invoke while the user types in the gallery's repo picker.

Request:

```json
{
  "projectId": "<uuid>",
  "repoId": "<uuid>"
}
```

Response (`VariantResolutionDto`):

```json
{
  "bestMatch": { "id": "...", "language": "typescript", "framework": "express", "...": "..." },
  "availableVariants": [ "..." ],
  "detectedLanguage": "typescript",
  "detectedFramework": "express",
  "reason": "OK"
}
```

`reason` codes: `OK` / `STACK_NOT_DETECTED` / `NO_VARIANT_FOR_STACK` / `REPO_NOT_LINKED`. The UI uses these to decide whether to auto-select the matched variant or render the variant picker.

### 8.2 `POST /api/library/{slug}/instantiate`

The actual launch path. Implemented by `ReadymadeOrchestrator.instantiate(slug, req, callerUserId)`.

Request body (`ReadymadeOrchestrator.InstantiateRequest`):

```json
{
  "projectId": "<uuid>",
  "repoIds": ["<uuid>", "..."],
  "slotValues": {
    "client_id": "1234.apps.googleusercontent.com",
    "client_secret": "<secret>",
    "callback_path": "/auth/google/callback"
  },
  "variantId": null,
  "branch": "feature/oauth-google"
}
```

Steps:

1. Resolve the solution by slug. 404 if missing.
2. Resolve the caller's `AppUser`. 401 if missing.
3. Tier-gate check via `isLockedFor(solution, user)`. 403 if locked.
4. Validate slot values against the slot definitions. `SlotValidator.validateAll` returns a list of violations; non-empty → 400 with semicolon-joined summary.
5. Pick the variant via `pickVariant(solutionId, variantId, projectId, firstRepoId)` — explicit > auto-resolve > first.
6. Build the defaults map from each slot's `default_value`. Compose the spec via `SpecComposer.compose(template, slotValues, defaults)`.
7. Prepend a tracking header to the composed spec so a human reviewer can trace the PR back: `"Readymade solution: <name> (slug=<slug>)\nVariant: <language> · <framework>\n\n<spec>"`.
8. Build a `CreateExecutionRequest` carrying the composed spec as `requirement`, the selected repo IDs, and the branch override.
9. Dispatch via `ExecutionOrchestrator.createExecution(project, user, createReq)`. This is the existing multi-repo Execution View path — runs spawn one V2 pipeline per selected repo.
10. Stamp `execution_run.readymade_solution_id` + `.readymade_variant_id` on the created row (columns added in `V160__execution_run_readymade.sql`). These power the project dashboard's "this run was a Readymade of <slug>" display and per-solution cost attribution.
11. Trigger analysis via `executionOrchestrator.triggerAnalysis(runId)` to start the pipeline.

Response (HTTP 202):

```json
{
  "executionRunId": "<uuid>",
  "status": "PENDING",
  "message": "Solution instantiated across 2 repo(s). Track progress on the run detail page."
}
```

The UI navigates to the run-detail surface using `executionRunId`; the rest of the V2 lifecycle (plan gate → execute → reviewer → PR) is unchanged.

### 8.3 Other library endpoints

| Method | Path                            | Purpose                                                                              |
| ------ | ------------------------------- | ------------------------------------------------------------------------------------ |
| GET    | `/api/library`                  | Gallery list with optional `category` / `macro_group` / `tier` / `search` filters.   |
| GET    | `/api/library/{slug}`           | Detail page payload (solution + variants + slots + dependencies + lock state).       |
| GET    | `/api/library/categories`       | Facet counts: `{categories: {...}, macroGroups: {...}}` sorted by count desc.        |
| POST   | `/api/library/favourite/{slug}` | Pin per user.                                                                        |
| DELETE | `/api/library/favourite/{slug}` | Unpin.                                                                               |
| POST   | `/api/admin/readymades/reseed`  | SUPER_ADMIN-only on-demand reseed (no JVM restart needed).                           |

---

## 9. Annotated example

A complete `login-with-google.yml`, the simplest first-party solution (FREE tier, no dependencies, three variants):

```yaml
# Stable id. Picked by URL path and seeder upsert.
slug: login-with-google

# Display title.
name: Login with Google

# Single-line hook shown under the title on the card.
tagline: One-click sign-in via Google OAuth

# Block scalar — use `|` to preserve linebreaks.
description: |
  Adds a "Sign in with Google" button + the OAuth callback handler
  to your existing app. Reuses your User entity if one is detected;
  otherwise auto-installs the User-entity sub-solution.

# Facet bucket — drives sidebar filtering.
category: sign-in-and-users

# Higher-level grouping.
macro_group: people-and-money

# Tags surface as chips and feed keyword search.
tags: [auth, oauth, social, google]

# Solution shape. PRIMITIVE = single drop-in; STACK = composition; INTEGRATION = wires external service.
kind: PRIMITIVE

# Required tier. FREE here means every signed-in user can launch this.
tier: FREE

# Curated by us. Use COMMUNITY for user-submitted entries.
source: FIRST_PARTY

# Only LISTED rows appear in the gallery. ARCHIVED retires the row.
status: LISTED

# Icon rendered on the card. URL or relative path.
icon_url: /icons/google.svg

# 2-tuple: [low, high]. Surfaced as a band.
est_files: [3, 8]
est_credits: [1.0, 2.0]

# Form fields. Render order is by display_order ascending.
slots:
  - name: client_id                          # → {{client_id}} placeholder
    display_name: Google OAuth Client ID
    type: SECRET                              # masked input + length ≥ 4 validation
    required: true
    description: From your Google Cloud Console OAuth credentials.
    display_order: 0
  - name: client_secret
    display_name: Google OAuth Client Secret
    type: SECRET
    required: true
    description: From your Google Cloud Console OAuth credentials.
    display_order: 1
  - name: callback_path
    display_name: Callback URL path
    type: TEXT                                # plain single-line input
    required: false
    default_value: /auth/google/callback      # SpecComposer falls back to this when blank
    description: Path your app receives the OAuth callback on.
    display_order: 2

# One variant per supported (language, framework) cell.
variants:
  - language: typescript
    framework: express
    options: {db: postgres}                   # surfaced in the DTO; not currently used for matching
    spec_template: |                          # block scalar with {{slot}} placeholders
      Add Google OAuth sign-in to this Express app.
      Use {{client_id}} as the OAuth client ID and {{client_secret}}
      for the secret. Mount the callback at {{callback_path}}.
      Persist the user in the existing User table.
    scaffold_key: node-backend                # → prompts/scaffolds/node-backend.md
  - language: typescript
    framework: nextjs
    options: {router: app}
    spec_template: |
      Add Google OAuth sign-in to this Next.js (App Router) app.
      Wire {{client_id}} + {{client_secret}} via environment
      variables; route the callback through {{callback_path}}.
      Use NextAuth.js or @auth/core; reuse the existing User model.
    scaffold_key: node-frontend
  - language: python
    framework: fastapi
    options: {db: postgres}
    spec_template: |
      Add Google OAuth sign-in to this FastAPI app.
      Use {{client_id}} + {{client_secret}}; mount the callback at
      {{callback_path}}. Use authlib for the OAuth flow.
    scaffold_key: python

# Optional. Solution-on-solution edges; auto-install or recommend.
# Omitted here because login-with-google has no hard deps.
```

A more typical PRO-tier entry: see `src/main/resources/readymades/stripe-checkout.yml`. It declares a `SELECT` slot with `options` (currencies) and uses `est_credits: [2.0, 4.0]`.

---

## 10. How to add a new solution

Quick path: drop a YAML, run reseed, verify.

1. **Pick a slug.** Lowercase, hyphenated, unique across the catalog. Check `src/main/resources/readymades/` for collisions. The slug becomes the URL path (`/api/library/<slug>`) and the file name (by convention).
2. **Author the YAML.** Drop a `<slug>.yml` under `src/main/resources/readymades/`. The minimum-viable file needs `slug`, `name`, at least one variant with a `spec_template`, and probably a `category` + `tier`. Everything else has sensible defaults.
3. **Decide tier.** `FREE` should be a primitive that doesn't cost much (≤ 2 credits, ≤ 8 files). `PRO` is the default. `TEAM` / `ENTERPRISE` only when the solution legitimately requires those tiers (e.g. wraps an enterprise-only integration).
4. **Write each variant.** One per supported (language, framework) pairing. Keep the `spec_template` precise — the V2 pipeline executes it as-is after slot substitution. Reference `prompts/scaffolds/<key>.md` via `scaffold_key` when the worker should pull in a scaffold (e.g. `node-backend`, `node-frontend`, `python`).
5. **Declare slots.** Each unique `{{placeholder}}` in any of your variant `spec_template`s should have a matching slot. Mark `required: true` only when the spec literally cannot render without it. Provide `default_value` for anything the user might reasonably want to skip.
6. **Set `est_files` + `est_credits`.** These drive the cost-band chip on the card. Eyeball from comparable solutions; the V2 cost tracker calibrates over time. Both are optional but recommended.
7. **Reseed.** Two options:
   - Restart the JVM. The `ReadymadeSeeder.@PostConstruct` hook reseeds on boot.
   - Hit `POST /api/admin/readymades/reseed` as a SUPER_ADMIN. Response shape is `{loaded: N, status: "ok"}`. The seeder logs `[R-328] seeded readymade <slug>` on success; per-file failures are logged but don't fail the request.
8. **Verify.** `GET /api/library?search=<slug-substring>` should return your card. `GET /api/library/<slug>` should show the variants + slots + (locked / unlocked) state. From the UI, the gallery should render the card and clicking it should open the detail page with the right form.
9. **Test instantiate.** With a test project + repo, `POST /api/library/<slug>/instantiate` with valid slot values. The response is HTTP 202 with `executionRunId`. Visit the run detail and let the V2 pipeline execute. The expectation is that a PR opens against the target repo with the spec's intent realised.
10. **Calibrate.** After the first few runs, look at the actual `verifiedScore` + cost burn on those runs. If the actuals consistently exceed `est_credits_high`, bump the band. If they're under, tighten it. Per-variant `est_credits_low` / `est_credits_high` override the solution-level value.

### Retiring a solution

Set `status: ARCHIVED` in the YAML, then reseed. The seeder upserts (it never deletes), so this is the safe path. `ARCHIVED` rows are excluded from the gallery list query (`findByStatus(LISTED)`). Existing favourites + execution runs that reference the solution keep working — the rows persist; they just don't surface in the gallery.

To delete entirely: drop the row in psql (`DELETE FROM readymade_solution WHERE slug = '...'`) — the FK cascade scrubs variants, slots, dependencies, and favourites. Be sure no existing `execution_run.readymade_solution_id` rows reference it, because that column is not FK-bound and would silently dangle.

### Editing an existing solution

Solutions are upserted by `slug`, so editing the YAML and reseeding updates the row in place. Variants, slots, and dependencies are delete-and-replace per parent solution — a reseed wipes them and re-inserts from the YAML. This means:

- Changing a slot's `name` invalidates any in-progress launch drafts on the frontend that reference the old name (slot values aren't stored — they're consumed at instantiate time — so the impact is limited).
- Variant IDs are regenerated. The `execution_run.readymade_variant_id` column captures the variant used at instantiate time, so historical runs still display correctly. New runs use new IDs.
- Dependencies pointing at unseeded slugs get skipped with a warning and resolve on the next reseed. The order of YAMLs on disk doesn't matter for correctness — just sometimes for warning noise.
