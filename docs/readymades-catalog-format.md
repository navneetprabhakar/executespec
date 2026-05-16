# Readymade Solutions — Catalog Format

> Spec-driven shortcut library. A "Readymade" is a prebuilt vertical slice
> (e.g. "Login with Google", "Stripe checkout", "Image upload to S3") that a
> caller can drop into a target repo without writing a free-form spec from
> scratch. Catalog content is YAML on disk; database tables are the
> runtime-readable mirror seeded from disk at boot.

This document describes the on-disk format, the database schema it seeds
into, the runtime resolution / instantiation flow, and the operator playbook
for adding a new solution.

Source-of-truth code lives under
`src/main/java/com/navneet/executespec/readymade/` (entities, DTOs,
repositories, seeder, services, controllers) and on-disk YAML lives under
`src/main/resources/readymades/*.yml`. The schema lands in DB via Flyway
migration `V157__readymade_solution.sql`. Library-instantiated runs are
tagged on `execution_run` by `V160__execution_run_readymade.sql`.

---

## 1. Entity overview

Five tables, one composition graph. Solutions are abstract entries;
variants are the concrete codegen cells; slots are user-fillable
parameters; dependencies are the composition graph; favourites are
per-user pins. All five live under
`com.navneet.executespec.readymade.entity` and are seeded together by
`ReadymadeSeeder` from a single YAML file per solution.

```
              readymade_solution
              (abstract entry; tier, status, source)
                       |
   +--------------+----+----+----------------------------+
   |              |         |                            |
   v              v         v                            v
readymade_       readymade_  readymade_dependency      readymade_favourite
variant          slot       (solution -> solution,    (user pins for
(language,       (TEXT,      required vs               UI gallery)
 framework,       SELECT,    recommended)
 spec_template)   etc.)
```

### 1.1 `readymade_solution` — the abstract library entry

Schema in
[`ReadymadeSolution.java`](../src/main/java/com/navneet/executespec/readymade/entity/ReadymadeSolution.java)
plus V157.

| Column            | Type             | Notes                                                                                                  |
| ----------------- | ---------------- | ------------------------------------------------------------------------------------------------------ |
| `id`              | UUID             | PK. Default `gen_random_uuid()`.                                                                       |
| `slug`            | VARCHAR(64)      | UNIQUE; stable identifier referenced by callers (e.g. `login-with-google`).                            |
| `name`            | VARCHAR(160)     | NOT NULL. Gallery card title.                                                                          |
| `tagline`         | VARCHAR(255)     | One-liner under the title.                                                                             |
| `description`    | TEXT             | Long-form description; rendered in detail view.                                                        |
| `category`        | VARCHAR(64)      | Top-level facet (`Auth`, `Payments`, `Email`, ...). Used by `GET /api/library/categories`.             |
| `macro_group`     | VARCHAR(32)      | Coarser bucket (`PRIMITIVE`, `STACK`, `INTEGRATION`); separate from `Kind` enum.                       |
| `tags`            | jsonb            | Free-form string array, used for keyword search.                                                       |
| `kind`            | VARCHAR(16)      | Enum `Kind {PRIMITIVE, STACK, INTEGRATION}`. Defaults `PRIMITIVE` if YAML omits it.                    |
| `tier`            | VARCHAR(16)      | Enum `Tier {FREE, PRO, TEAM, ENTERPRISE}`. Default `PRO`. Drives the lock decoration.                  |
| `source`          | VARCHAR(16)      | Enum `Source {FIRST_PARTY, COMMUNITY}`. Default `FIRST_PARTY`.                                         |
| `status`          | VARCHAR(16)      | Enum `Status {LISTED, HIDDEN, ARCHIVED}`. Default `LISTED`. Only LISTED appears in the gallery.        |
| `icon_url`        | TEXT             | Optional badge URL.                                                                                    |
| `thumbnail_url`   | TEXT             | Optional larger thumbnail for the detail view.                                                         |
| `sample_pr_url`   | TEXT             | Optional link to a reference PR demonstrating the solution applied.                                    |
| `est_files_low`   | INTEGER          | Lower bound on files-changed forecast.                                                                 |
| `est_files_high`  | INTEGER          | Upper bound.                                                                                           |
| `est_credits_low` | NUMERIC(10,2)    | Lower bound on credits-charged forecast (used by the UI pre-launch confirmation).                      |
| `est_credits_high`| NUMERIC(10,2)    | Upper bound.                                                                                           |
| `author_user_id`  | VARCHAR(36)      | Optional. Populated for community-authored entries (W7-pending).                                       |
| `author_org_id`   | VARCHAR(36)      | Optional. Companion to above for org-authored.                                                         |
| `created_at`      | Instant          | NOT NULL, defaults to `Instant.now()`.                                                                 |
| `updated_at`      | Instant          | NOT NULL. Refreshed via JPA `@PreUpdate`.                                                              |

Indexes from V157: `(category, status)`, `(macro_group, status)`,
`(tier, status)`, `(source, status)`, and a GIN FTS index over
`to_tsvector('english', name || coalesce(description,'') || coalesce(tagline,''))`.
The list endpoint today does FTS in-memory (catalog is at most ~130 entries;
native FTS query is deferred to W9 per
[`ReadymadeService.java`](../src/main/java/com/navneet/executespec/readymade/service/ReadymadeService.java)).

### 1.2 `readymade_variant` — concrete codegen cell

Schema in
[`ReadymadeVariant.java`](../src/main/java/com/navneet/executespec/readymade/entity/ReadymadeVariant.java).

| Column             | Type             | Notes                                                                                       |
| ------------------ | ---------------- | ------------------------------------------------------------------------------------------- |
| `id`               | UUID             | PK.                                                                                         |
| `solution_id`      | UUID             | NOT NULL. ON DELETE CASCADE to `readymade_solution`.                                        |
| `language`         | VARCHAR(32)      | Canonical lowercase (`typescript`, `python`, `java`, ...). Normalised by `StackNormalizer`. |
| `framework`        | VARCHAR(64)      | Canonical hyphenated (`spring-boot`, `fastapi`, `nestjs`, ...).                             |
| `options`          | jsonb            | Free-form variant discriminator beyond (lang, framework).                                   |
| `spec_template`    | TEXT             | NOT NULL. The Markdown spec the run will receive after `{{slot_name}}` substitution.        |
| `scaffold_key`     | VARCHAR(64)      | Optional reference into `prompts/scaffolds/*.md` (e.g. `node-frontend`, `spring-boot`).     |
| `est_credits_low`  | NUMERIC(10,2)    | Per-variant override of solution-level estimate.                                            |
| `est_credits_high` | NUMERIC(10,2)    | Per-variant upper bound.                                                                    |
| `created_at`       | Instant          | Default `Instant.now()`.                                                                    |

Indexes: `(solution_id)`, `(language, framework)`.

### 1.3 `readymade_slot` — user-fillable parameter

Schema in
[`ReadymadeSlot.java`](../src/main/java/com/navneet/executespec/readymade/entity/ReadymadeSlot.java).

| Column           | Type              | Notes                                                                                       |
| ---------------- | ----------------- | ------------------------------------------------------------------------------------------- |
| `id`             | UUID              | PK.                                                                                         |
| `solution_id`    | UUID              | NOT NULL. ON DELETE CASCADE.                                                                |
| `name`           | VARCHAR(64)       | Substitution token used in `spec_template` (e.g. `{{redirect_uri}}`). UNIQUE per solution.  |
| `display_name`   | VARCHAR(160)      | Label shown above the input in the launch dialog.                                           |
| `type`           | VARCHAR(32)       | Enum `Type {TEXT, SELECT, SECRET, REPO_PICKER, FILE_PATH, NUMBER, BOOLEAN}`.                |
| `required`       | BOOLEAN           | Default `false`. Validator rejects launch if empty.                                         |
| `default_value`  | TEXT              | Optional pre-filled value.                                                                  |
| `options`        | jsonb             | For `SELECT`: array of `{value, label}` objects. For others: null.                          |
| `description`    | TEXT              | Help text shown under the input.                                                            |
| `display_order`  | INTEGER           | NOT NULL, default `0`. Slot order in the launch dialog.                                     |

UNIQUE constraint on `(solution_id, name)`. Slot values are substituted
into the resolved variant's `spec_template` by `SpecComposer` at
instantiate time. Slot enum mirrors `MigrationSlot.Type` so the shared
`SlotValidator` works across both packages.

#### 1.3.1 Slot type semantics

| Type          | UI control                  | Validator behaviour                                                       |
| ------------- | --------------------------- | ------------------------------------------------------------------------- |
| `TEXT`        | single-line text            | Free-form string; non-empty when `required=true`.                         |
| `SELECT`      | dropdown                    | Value must be in `options[].value`.                                       |
| `SECRET`      | masked text                 | Not echoed back; same shape as TEXT for validation.                       |
| `REPO_PICKER` | repo dropdown               | Value must be a repo ID linked to the launching project.                  |
| `FILE_PATH`   | text plus workspace browser | Relative path, no `..` traversal.                                         |
| `NUMBER`      | numeric input               | Parses as integer.                                                        |
| `BOOLEAN`     | toggle                      | `true` / `false` literal.                                                 |

### 1.4 `readymade_dependency` — composition graph

Schema in
[`ReadymadeDependency.java`](../src/main/java/com/navneet/executespec/readymade/entity/ReadymadeDependency.java).
Composite PK `(solution_id, depends_on_id)`.

| Column           | Type     | Notes                                                                            |
| ---------------- | -------- | -------------------------------------------------------------------------------- |
| `solution_id`    | UUID     | Id (FK on solution; CASCADE on delete from V157).                                |
| `depends_on_id`  | UUID     | Id (FK on solution). The required / recommended partner.                         |
| `required`       | BOOLEAN  | NOT NULL, default `TRUE`. `true` means auto-install; `false` means recommended.  |

The seeder defaults `required` to `true` if YAML omits it. Rows that
reference an unseeded slug are skipped with a warning and re-resolved on
the next reseed.

### 1.5 `readymade_favourite` — per-user pins

Schema in
[`ReadymadeFavourite.java`](../src/main/java/com/navneet/executespec/readymade/entity/ReadymadeFavourite.java).
Composite PK `(user_id, solution_id)`.

| Column        | Type         | Notes                                                                  |
| ------------- | ------------ | ---------------------------------------------------------------------- |
| `user_id`     | VARCHAR(36)  | Caller's user ID.                                                      |
| `solution_id` | UUID         | ON DELETE CASCADE.                                                     |
| `created_at`  | Instant      | Default `Instant.now()`.                                               |

Repo
[`ReadymadeFavouriteRepository.java`](../src/main/java/com/navneet/executespec/readymade/repository/ReadymadeFavouriteRepository.java)
carries `@Transactional` directly on the delete because the
favourite/unfavourite path is invoked from the controller without a
service layer between.

---

## 2. Where solutions live on disk

```
src/main/resources/readymades/
  <slug>.yml          (one file per solution; multiple variants/slots/deps inside)
```

`ReadymadeSeeder` runs at boot (`@PostConstruct onStartup()`) and is also
called on-demand from `ReadymadeAdminController.reseed()`. It uses
`PathMatchingResourcePatternResolver` to enumerate
`classpath:readymades/*.yml`, parses each via `SafeYamlLoader`, and upserts.

Behaviour summary (from `ReadymadeSeeder.java`):

- Solutions upserted by `slug` (insert-if-missing or update-by-slug).
- Variants / slots / dependencies use a delete-and-replace strategy per
  parent on every reseed — the children are rewritten in full so a YAML
  edit cleanly removes a deleted variant.
- Malformed YAML failures are logged and skipped per file; one bad file
  does not block the rest of the catalog.
- Default enums: `Kind.PRIMITIVE`, `Tier.PRO`, `Source.FIRST_PARTY`,
  `Status.LISTED`, slot `Type.TEXT`, dependency `required=true`.

---

## 3. YAML schema (one solution = one file)

A complete example (annotated; not a real solution):

```yaml
# Required identity
slug: login-with-google
name: "Login with Google"
tagline: "Add Google OAuth2 login to your app in one PR."
description: |
  Wires Google OAuth2 into your auth surface. Backend gets the callback
  controller + token-exchange + user upsert; frontend gets the
  "Sign in with Google" button + redirect handler.

# Faceting
category: "Auth"
macro_group: "INTEGRATION"          # PRIMITIVE | STACK | INTEGRATION
tags: ["oauth", "google", "login", "auth"]

# Classification + gating
kind: "INTEGRATION"                 # Maps to ReadymadeSolution.Kind
tier: "PRO"                         # FREE | PRO | TEAM | ENTERPRISE; default PRO
source: "FIRST_PARTY"               # FIRST_PARTY | COMMUNITY; default FIRST_PARTY
status: "LISTED"                    # LISTED | HIDDEN | ARCHIVED; default LISTED

# Presentation
icon_url: "https://cdn.executespec.ai/icons/google.svg"
thumbnail_url: "https://cdn.executespec.ai/thumbs/google-login.png"
sample_pr_url: "https://github.com/example/demo/pull/42"

# Forecast bands (solution-level fallback; variants can override)
est_files_low: 4
est_files_high: 9
est_credits_low: 1.0
est_credits_high: 3.0

# Variants — one per (language, framework, options) cell
variants:
  - language: "typescript"
    framework: "nextjs"
    options: { app_router: true }
    scaffold_key: "node-frontend"
    spec_template: |
      Add Google OAuth2 sign-in to the Next.js app.
      Redirect URI: {{redirect_uri}}
      Client ID env var: {{client_id_env}}
      After login, route the user to {{post_login_path}}.
    est_credits_low: 1.0
    est_credits_high: 2.0

  - language: "java"
    framework: "spring-boot"
    options: {}
    scaffold_key: "spring-boot"
    spec_template: |
      Add Spring Security OAuth2 client for Google.
      Redirect URI: {{redirect_uri}}
      ...

# Slots — user-fillable parameters substituted into variant.spec_template
slots:
  - name: "redirect_uri"
    display_name: "OAuth Redirect URI"
    type: "TEXT"
    required: true
    description: "Public URL Google should redirect to after login."
    display_order: 1

  - name: "client_id_env"
    display_name: "Env var for Client ID"
    type: "TEXT"
    required: true
    default_value: "GOOGLE_OAUTH_CLIENT_ID"
    display_order: 2

  - name: "post_login_path"
    display_name: "Post-login redirect"
    type: "TEXT"
    required: false
    default_value: "/dashboard"
    display_order: 3

# Dependencies — composition with other solutions (by slug, resolved on next reseed)
dependencies:
  - depends_on: "user-session-cookie"
    required: true                  # auto-install before this one
  - depends_on: "audit-log-table"
    required: false                 # recommended pairing only
```

Key parser notes (from `ReadymadeSeeder.applySolution` and helpers):

- `tags`, `variants[].options`, `slots[].options`, and `dependencies` are
  serialised to JSON before persistence; nested objects inside an
  `options` value are written as `toString`'d quoted strings (lossy for
  deep nesting, fine for the shallow shape current catalog uses).
- Booleans and numbers in `options` are preserved raw; strings are
  quoted.
- Missing fields fall through to default enums listed above.
- Enum strings are case-insensitive in YAML; parsed via `parseEnum`.

---

## 4. Slot types (enum)

The slot `type` column corresponds to `ReadymadeSlot.Type`:

```
TEXT, SELECT, SECRET, REPO_PICKER, FILE_PATH, NUMBER, BOOLEAN
```

Same enum exists on `MigrationSlot.Type` so the shared `SlotValidator`
and `SpecComposer` work across both libraries. The frontend renders
each as described in section 1.3.1.

---

## 5. Variant matching (`VariantResolver`)

When a caller opens the detail page for a solution against a specific
project + repo, the UI asks the backend to pre-select which variant best
matches the user's detected stack. The endpoint is
`POST /api/library/{slug}/resolve?projectId=&repoId=` and the
implementation is in
[`VariantResolver.java`](../src/main/java/com/navneet/executespec/readymade/service/VariantResolver.java).

The algorithm:

```
resolve(solutionId, projectId, repoId):
  1. Look up project_repo_link(projectId, repoId)
     - if missing -> reason=REPO_NOT_LINKED, bestMatch=null
  2. Read project_repo.detected_stack JSON
     - if blank/null -> reason=STACK_NOT_DETECTED, bestMatch=null
  3. Normalise (language, framework) via StackNormalizer
     - "TypeScript/JS" -> "typescript"
     - "Spring Boot" -> "spring-boot"
  4. Query readymade_variant WHERE solution_id=? AND language=? AND framework=?
     - if 1+ matches -> reason=OK, bestMatch = matches[0]
     - if zero matches -> reason=NO_VARIANT_FOR_STACK, bestMatch=null
  5. Always include availableVariants[] = all variants for the solution
     (so the UI can offer manual override)
```

Reason codes returned in `VariantResolutionDto`:

| Reason                   | Meaning                                                            |
| ------------------------ | ------------------------------------------------------------------ |
| `OK`                     | Variant found for the repo's detected (language, framework).       |
| `NO_VARIANT_FOR_STACK`   | Repo stack known but no matching variant exists yet.               |
| `STACK_NOT_DETECTED`     | Repo linked but no `detected_stack` rows.                          |
| `REPO_NOT_LINKED`        | `(projectId, repoId)` not in `project_repo_link`.                  |

`StackNormalizer` is a pure static utility (no Spring component) with
canonicalisation rules baked in. The "TypeScript/JS" compound collapses to
`typescript` (tsc's `allowJs` path) so a JS repo that pulled the TS
toolchain still matches TS variants.

---

## 6. Dependencies — required vs recommended

A solution can declare other solutions it composes with. The relationship
is one-directional via `readymade_dependency`:

| `required` | Behaviour                                                                            |
| ---------- | ------------------------------------------------------------------------------------ |
| `true`     | Listed as a hard prerequisite. UI shows "Requires: X" and (W7-pending) auto-installs.|
| `false`    | Listed as "Pairs well with: X". Surface only; no auto-install.                       |

Today both flavours are surfaced in the detail-page DTO
(`ReadymadeDetailDto.dependencies`); the orchestrator does not yet
walk the graph at instantiate time. Auto-install is on the queued
work-list for W7 community/composition.

---

## 7. Tier gating

Each solution declares the minimum tier needed to instantiate it (the
`tier` column). The ladder is

```
FREE  <  PRO  <  TEAM  <  ENTERPRISE
```

Gate enforcement points:

1. **List endpoint** (`GET /api/library`) — every gallery row carries a
   `locked` boolean and an `unlockTier` hint (the tier needed to unlock).
   Locked rows still render, just with the lock chip and an upgrade CTA.
   The hydration helpers are `ReadymadeListItemDto.locked(s, requiredTier)`
   and `ReadymadeListItemDto.unlocked(s)`.
2. **Detail endpoint** (`GET /api/library/{slug}`) — `ReadymadeDetailDto`
   carries the same `locked` + `unlockTier`. The variants / slots / deps
   payload is returned regardless of lock so the detail UI can render the
   full preview.
3. **Instantiate endpoint** (`POST /api/library/{slug}/instantiate`) —
   hard 403 if caller tier is below the solution's tier. Logic is in
   `ReadymadeOrchestrator.isLockedFor(solution, user)`.

The static comparator is
`ReadymadeService.isLocked(Tier requiredTier, Tier callerTier)` (ordinal
check against `Tier {FREE, PRO, TEAM, ENTERPRISE}`). Null `requiredTier`
returns false (treated as unlocked); null caller maps to FREE.

The DTO field naming is deliberate:

- The pure `ReadymadeSolutionDto` has no `is_locked` field — lock state
  is caller-tier-dependent and never cached on the entity.
- `ReadymadeListItemDto` / `ReadymadeDetailDto` are wrappers that
  decorate with `locked` + `unlockTier` per request.

---

## 8. Instantiation flow (`/resolve` + `/instantiate`)

The two endpoints together implement the W3 instantiate sequence.

### 8.1 Endpoint surface

| Method | Path                                | Description                                                                       |
| ------ | ----------------------------------- | --------------------------------------------------------------------------------- |
| GET    | `/api/library`                      | Gallery list. Filters: `category`, `macro_group`, `tier`, `search` (in-memory).   |
| GET    | `/api/library/{slug}`               | Detail bundle (solution + variants + slots + deps + lock).                        |
| GET    | `/api/library/categories`           | Facet counts (`{categories, macroGroups}`) for the filter chips.                  |
| POST   | `/api/library/{slug}/resolve`       | Returns best-match variant for caller's (projectId, repoId).                      |
| POST   | `/api/library/{slug}/instantiate`   | 202 Accepted; dispatches an `ExecutionRun`. Body = `InstantiateRequest`.          |
| POST   | `/api/library/favourite/{slug}`     | 204; idempotent pin (no-op if already favourited).                                |
| DELETE | `/api/library/favourite/{slug}`     | 204; unpin.                                                                       |
| POST   | `/api/admin/readymades/reseed`      | SUPER_ADMIN-only hot reload of `classpath:readymades/*.yml`.                      |

Endpoints live in
[`ReadymadeController.java`](../src/main/java/com/navneet/executespec/readymade/controller/ReadymadeController.java)
and
[`ReadymadeAdminController.java`](../src/main/java/com/navneet/executespec/readymade/controller/ReadymadeAdminController.java).

Note: the Javadoc on `ReadymadeController.instantiate` still claims
"Stubbed in W3 — returns 501; the SpecComposer + V2 wiring lands in W5".
The implementation actually dispatches to `ReadymadeOrchestrator` and
returns 202 with `executionRunId`. The docstring is stale relative to the
W5 wiring landing.

### 8.2 `/resolve` request / response

```
POST /api/library/{slug}/resolve?projectId=<uuid>&repoId=<uuid>

200 OK -> VariantResolutionDto {
  bestMatch?: ReadymadeVariantDto,
  availableVariants: ReadymadeVariantDto[],
  detectedLanguage?: string,
  detectedFramework?: string,
  reason: "OK" | "NO_VARIANT_FOR_STACK" | "STACK_NOT_DETECTED" | "REPO_NOT_LINKED"
}
```

### 8.3 `/instantiate` request / response

```
POST /api/library/{slug}/instantiate
Body = InstantiateRequest {
  projectId: string,
  repoIds: UUID[],
  slotValues: { [slotName: string]: string },   // null coalesces to {}
  variantId?: UUID,                              // optional explicit override
  branch?: string                                // optional branchOverride
}

202 Accepted -> {
  executionRunId: string,
  status: string,
  message: string
}
401 -> caller is anonymous
403 -> caller tier below solution tier
404 -> unknown slug / project / variant
400 -> required slot missing or invalid SELECT value
```

### 8.4 Dispatch sequence (inside `ReadymadeOrchestrator.instantiate`)

```
1. Load ReadymadeSolution by slug                   (404 if missing)
2. Resolve caller AppUser from callerUserId         (401 if unknown)
3. Tier-gate: isLockedFor(solution, user)           (403 on fail)
4. Pick variant:
     - explicit variantId from request, else
     - VariantResolver.resolve(projectId, firstRepoId).bestMatch, else
     - first variant for the solution
   (400 if no variants exist at all)
5. Validate slot values via SlotValidator           (400 on fail)
6. SpecComposer assembles the final spec:
     - load variant.spec_template
     - substitute {{slot_name}} with slotValues
     - prepend a "Readymade: <name> (slug=<slug>)" header for reviewer
       traceability without joins
7. Build CreateExecutionRequest:
     - userRequest = composed spec
     - projectId, selectedRepoIds = req.repoIds
     - branch = req.branch (passes through to multi-repo push)
8. ExecutionOrchestrator.createExecution(req)       (one V2 pipeline_run)
9. Stamp execution_run.readymade_solution_id +
        execution_run.readymade_variant_id           (V160)
10. Trigger architecture/codemap analysis async
11. Return ExecutionRun (controller wraps as 202 + executionRunId)
```

Tracking columns added by `V160__execution_run_readymade.sql`:

```sql
ALTER TABLE execution_run ADD COLUMN readymade_solution_id UUID;
ALTER TABLE execution_run ADD COLUMN readymade_variant_id UUID;
```

Both nullable; only library-instantiated runs have them set.

---

## 9. Operator playbook — adding a new solution

1. **Decide identity and category.** Pick a stable `slug` (lowercase
   hyphenated, at most 64 chars). Decide `category`, `macro_group`,
   `kind`, `tier` (default `PRO`).

2. **Write the spec_template for each (language, framework) cell you
   want to support.** Use `{{slot_name}}` placeholders for any
   user-supplied parameter. Keep the template Markdown — it goes
   straight into the V2 outline planner's `{{SPEC}}` variable.

3. **Define slots.** Pick `type` per section 1.3.1. Mark `required=true`
   for anything the spec_template cannot fall back to a sensible default
   for.

4. **Optional: declare dependencies.** Reference other solutions by
   slug. `required=true` if the user must install it first;
   `required=false` for "pairs well with" surface only.

5. **Drop the file at** `src/main/resources/readymades/<slug>.yml`.

6. **Reseed.** On a running server:

   ```bash
   curl -sS -X POST -H "Authorization: Bearer $SUPER_ADMIN_JWT" \
     "$BASE/api/admin/readymades/reseed"
   ```

   Returns `{loaded: N, status: "ok"}`. Boot-time reseed runs the same
   path so a server restart works too.

7. **Verify.**
   - `GET /api/library?search=<your-slug>` — confirm the row appears
     with the right lock state for your tier.
   - `GET /api/library/<slug>` — confirm variants + slots + deps.
   - `POST /api/library/<slug>/resolve?projectId=<p>&repoId=<r>` against
     a repo whose `detected_stack` matches one of your variants —
     confirm `reason=OK`.
   - `POST /api/library/<slug>/instantiate` with valid slot values and
     a real project — confirm 202, then poll the resulting
     `executionRunId` for terminal status.

8. **Reseed semantics caveat.** The seeder uses delete-and-replace for
   variants / slots / deps per parent on every reseed. If your YAML
   has a typo that resolves to a different `slug`, you will end up with
   two solution rows (the old slug never gets deleted; it just stays as
   stale `LISTED`). Mark stale rows as `status: HIDDEN` in the YAML on
   a follow-up reseed to take them out of the gallery, or hand-delete
   the DB row.

9. **Do not forget the migration cross-link.** Adding a new variant
   `Type` enum value or a new column requires a Flyway migration —
   never modify V157 itself; add a follow-up `V_{next}__...sql`.

---

## 10. Known gaps and caveats (engineering-honest)

- `ReadymadeController.instantiate` Javadoc says "Stubbed in W3 — returns
  501". The implementation does dispatch (return 202). The docstring is
  stale.
- `VariantResolver` field-initialises `ObjectMapper` instead of injecting
  it. Minor style drift; behaviour fine.
- `ReadymadeSeeder.reseed()` is `@Transactional` but also called via
  `this.reseed()` from `@PostConstruct onStartup()`. The self-invocation
  bypasses the Spring proxy. The seeder relies on the child-row
  repositories' own `@Transactional` annotations on `deleteBy*` methods
  to make the delete-and-replace path correct. This is intentional and
  called out in repo Javadoc.
- The catalog FTS today is in-memory in `ReadymadeService.list()`. The
  V157 GIN index exists but is not consulted from a native query yet —
  deferred to W9 once the catalog exceeds the ~130-row in-memory budget.
- `tags` and `variants[].options` JSON serialisation in the seeder is
  shallow (booleans / numbers raw, everything else quoted-string). Do
  not put deeply nested objects in `options` — they will be serialised
  as toString'd strings.
- Dependency graph traversal is surface-only. The orchestrator does not
  auto-install `required=true` deps at instantiate time; the UI just
  shows the relationship.
- W7 community submissions: `author_user_id` + `author_org_id` columns
  exist but the moderator-approval surface is not yet wired. All
  current catalog entries are `source=FIRST_PARTY`.

---

## 11. Cross-references

- Flyway migration **V157** — `readymade_solution.sql` (schema).
- Flyway migration **V160** — `execution_run_readymade.sql` (tracking).
- Seeder — `readymade/seeder/ReadymadeSeeder.java`.
- Public read API — `readymade/controller/ReadymadeController.java`.
- Admin reseed — `readymade/controller/ReadymadeAdminController.java`.
- Orchestrator (instantiate dispatch) —
  `readymade/service/ReadymadeOrchestrator.java`.
- Variant resolution — `readymade/service/VariantResolver.java`,
  `readymade/service/StackNormalizer.java`.
- Read-side gallery / detail — `readymade/service/ReadymadeService.java`.
- Sister catalog (Migrations) — `docs/migration-recipes-catalog-format.md`.
