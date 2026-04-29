# Readymade Solutions — catalog file format

> **Status:** authoritative as of 2026-04-29 (R-328 / W2 of the
> Readymade Solutions plan).
> **Audience:** internal authors + community contributors who add
> new solutions to the Readymade Solutions library.

## Where catalog files live

`src/main/resources/readymades/<slug>.yml` — **one file per
solution**, indexed by `slug`. The seeder
(`ReadymadeSeeder`) loads every `*.yml` under that classpath
location at boot and on-demand via the admin reseed endpoint.

One-file-per-solution chosen over one-big-`catalog.yml` for diff
hygiene + community-contribution friendliness — adding a solution
is one new file; updating one is one modified file.

## File schema

A single solution YAML file looks like this (all required fields
flagged):

```yaml
slug: login-with-google              # required, unique, [a-z0-9-]+, ≤ 64 chars
name: Login with Google              # required, ≤ 160 chars
tagline: One-click sign-in via Google OAuth   # ≤ 255 chars
description: |                       # rich text shown on detail page
  Adds a "Sign in with Google" button + the OAuth callback handler
  to your existing app. Reuses your User entity if one is detected;
  otherwise auto-installs the User-entity sub-solution.
category: sign-in-and-users          # required, must match an entry in the 15-category list
macro_group: people-and-money        # required, must match one of the 4 macro-groups
tags: [auth, oauth, social, google]
kind: PRIMITIVE                      # required: PRIMITIVE | STACK | INTEGRATION
tier: FREE                           # required: FREE | PRO | TEAM | ENTERPRISE
source: FIRST_PARTY                  # FIRST_PARTY (default) | COMMUNITY
status: LISTED                       # LISTED (default) | HIDDEN | ARCHIVED
icon_url: /icons/google.svg
sample_pr_url: https://github.com/executespec/samples/pull/12
est_files: [3, 8]                    # [low, high] — drives card chip
est_credits: [1.0, 2.0]              # [low, high] — drives cost-transparency chip
slots:
  - name: client_id
    display_name: Google OAuth Client ID
    type: SECRET
    required: true
    description: From your Google Cloud Console OAuth credentials.
  - name: callback_path
    display_name: Callback URL path
    type: TEXT
    required: false
    default_value: /auth/google/callback
    description: Path your app receives the OAuth callback on.
dependencies:
  - slug: user-entity
    required: true                   # auto-install if missing
variants:
  - language: typescript
    framework: express
    options: {db: postgres}          # arbitrary JSON; surfaces on detail page
    spec_template: |
      Add Google OAuth sign-in to this Express app.
      Use {{client_id}} as the OAuth client ID; mount the callback
      at {{callback_path}}. Persist the user in the existing User
      table.
    scaffold_key: node-backend       # optional ref to prompts/scaffolds/<key>.md
  - language: typescript
    framework: nextjs
    options: {router: app}
    spec_template: |
      Add Google OAuth sign-in to this Next.js (App Router) app.
      Wire {{client_id}} via env, route the callback through
      {{callback_path}}.
    scaffold_key: node-frontend
  # ... more variants per supported (language, framework) cell
```

## Field reference

### Top-level

| Field | Type | Required | Notes |
|---|---|---|---|
| `slug` | string | yes | URL-safe natural key. Unique across the catalog. |
| `name` | string | yes | Card title. |
| `tagline` | string | no | Card subtitle. |
| `description` | string (multi-line) | no | Detail-page body. Markdown allowed (rendered client-side). |
| `category` | enum | yes | One of the 15 user-friendly categories. |
| `macro_group` | enum | yes | One of the 4 macro-groups. |
| `tags` | string[] | no | Free-form; drives faceted search in W3. |
| `kind` | enum | yes | `PRIMITIVE` / `STACK` / `INTEGRATION`. |
| `tier` | enum | yes | `FREE` / `PRO` / `TEAM` / `ENTERPRISE`. Lock-state on FREE/PRO derives from this. |
| `source` | enum | no | `FIRST_PARTY` (default) / `COMMUNITY`. |
| `status` | enum | no | `LISTED` (default) / `HIDDEN` / `ARCHIVED`. |
| `icon_url`, `thumbnail_url`, `sample_pr_url` | URL | no | Card + detail-page assets. |
| `est_files` | `[int, int]` | no | `[low, high]` for the card chip. |
| `est_credits` | `[number, number]` | no | `[low, high]` for FREE/PRO cost chip. |
| `slots` | array | no | User-fillable parameters (see below). |
| `dependencies` | array | no | Slug references; `required: true` auto-installs. |
| `variants` | array | yes (≥ 1) | Per-(language, framework) cell. |

### Slot

| Field | Type | Required | Notes |
|---|---|---|---|
| `name` | string | yes | Stable identifier for `{{slot}}` substitution. |
| `display_name` | string | yes | Form-field label. |
| `type` | enum | yes | `TEXT` / `SELECT` / `SECRET` / `REPO_PICKER` / `FILE_PATH` / `NUMBER` / `BOOLEAN`. |
| `required` | bool | no | Default false. |
| `default_value` | string | no | Pre-filled in the form. |
| `options` | string[] | for `SELECT` | Allowed values list. |
| `description` | string | no | Help text under the form field. |
| `display_order` | int | no | Default 0; ascending. |

### Variant

| Field | Type | Required | Notes |
|---|---|---|---|
| `language` | string | yes | `typescript` / `python` / `java` / `go` / `rust` / `ruby` / `php` / `csharp` / `kotlin` / `swift` / `dart`. |
| `framework` | string | yes | `express` / `fastify` / `nextjs` / `nestjs` / `fastapi` / `django` / `spring-boot` / `actix` / `rails` / `laravel` / `dotnet` / etc. |
| `options` | object | no | Arbitrary key/value, e.g. `{db: postgres, router: app}`. |
| `spec_template` | string (multi-line) | yes | Spec text with `{{slot}}` placeholders. The W5 `SpecComposer` substitutes user-supplied slot values into this template before submitting to the V2 pipeline. |
| `scaffold_key` | string | no | Optional reference to `prompts/scaffolds/<key>.md` (existing infrastructure). |
| `est_credits` | `[number, number]` | no | Per-variant cost override; falls back to top-level `est_credits` when absent. |

### Dependency

| Field | Type | Required | Notes |
|---|---|---|---|
| `slug` | string | yes | Slug of the depends-on solution. Must exist in the catalog. |
| `required` | bool | no | Default true. `true` = auto-install if missing; `false` = recommended pairing only (UI surface). |

## Author workflow

1. Drop a new file at `src/main/resources/readymades/<slug>.yml`.
2. Run the seeder once to verify (boot the app, or hit
   `POST /api/admin/readymades/reseed` as SUPER_ADMIN).
3. Open the gallery (`/v3/library`) — your card should appear.
4. Submit the change as a PR; CI will boot-load the catalog as
   part of the integration check.

## Seeder semantics

- **Idempotent** — keyed on `slug`. Existing entries are updated;
  new ones inserted; absent slugs are NOT auto-deleted (use
  `status: ARCHIVED` to retire a solution).
- **Variants / slots / dependencies are delete-and-replace** per
  parent solution row on every reseed (cleaner than diffing).
- **Failures per file are logged + skipped** — one bad YAML doesn't
  bring down the whole catalog load.
