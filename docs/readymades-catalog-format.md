# Readymades Catalog Format

> Recreated 2026-06-10 from `readymade/` code. Readymades are first-party spec templates loaded from YAML and instantiated as execution runs.

## Runtime Shape

| Layer | Code |
| --- | --- |
| Public API | `readymade/controller/ReadymadeController.java` |
| Admin reseed API | `readymade/controller/ReadymadeAdminController.java` |
| Seeder | `readymade/seeder/ReadymadeSeeder.java` |
| Gallery/service | `readymade/service/ReadymadeService.java` |
| Variant selection | `readymade/service/VariantResolver.java` |
| Instantiation | `readymade/service/ReadymadeOrchestrator.java` |
| Entities | `ReadymadeSolution`, `ReadymadeVariant`, `ReadymadeSlot`, `ReadymadeDependency`, `ReadymadeFavourite` |

YAML files are loaded from:

```text
src/main/resources/readymades/*.yml
```

The seeder upserts parent solutions by `slug`. Variants, slots, and dependencies are delete-and-replace per solution on reseed. Missing YAML files do not delete existing rows; retire an entry by setting `status: ARCHIVED` or `HIDDEN`.

## Required YAML Fields

```yaml
slug: login-with-google
name: Login with Google
tagline: Add OAuth login
description: Add Google OAuth login to an existing app.
category: auth
macro_group: primitive
kind: PRIMITIVE
tier: PRO
source: FIRST_PARTY
status: LISTED
tags: [auth, oauth, google]
est_files: [2, 5]
est_credits: [1, 2]

variants:
  - language: typescript
    framework: react
    options:
      router: react-router
    spec_template: |
      Implement Google OAuth login...

slots:
  - name: callback_url
    display_name: Callback URL
    type: TEXT
    required: true
    description: OAuth callback URL to configure in Google Console.

dependencies:
  - slug: user-session
    required: true
```

Defaults:

- `kind`: `PRIMITIVE`
- `tier`: `PRO`
- `source`: `FIRST_PARTY`
- `status`: `LISTED`

## Public API

| Endpoint | Purpose |
| --- | --- |
| `GET /api/library` | List with `category`, `macro_group`, `tier`, `search` filters |
| `GET /api/library/{slug}` | Detail |
| `GET /api/library/categories` | Facet counts |
| `POST /api/library/{slug}/resolve?projectId=&repoId=` | Read-only variant resolution; project read guard |
| `POST /api/library/{slug}/instantiate` | Compose spec and dispatch an execution run; project write guard |
| `POST /api/library/favourite/{slug}` | Add favourite |
| `DELETE /api/library/favourite/{slug}` | Remove favourite |

## Tier Gating

The list/detail responses decorate locked rows for the caller tier. Instantiation must still enforce project write access and tier policy server-side before submitting real work.

Tier order:

```text
FREE < PRO < TEAM < ENTERPRISE
```

## Maintenance Rules

- Never rename a published `slug`; add a new row and hide the old one.
- Keep `spec_template` concrete enough to submit directly.
- Add slots only for values the user should actually choose.
- Do not use readymades for arbitrary long-form specs; use normal run submission.
- After changing YAML, reseed locally and verify `/api/library`.

## Related Frontend

- `frontend/src/lib/api/discovery.ts`
- `frontend/src/lib/api/core.ts` readymade DTOs
