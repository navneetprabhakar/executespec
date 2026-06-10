# Frontend API Usage

> Recreated 2026-06-10 from `frontend/src/lib/api/`. This document describes the current API-client shape, not the old Signal/V3-era god file.

## Client Layout

The public import surface is the barrel:

- `frontend/src/lib/api/index.ts`

Domain modules:

| Module | Responsibility |
| --- | --- |
| `core.ts` | `BASE`, auth headers, `ApiError`, `req<T>`, shared DTOs/types |
| `runs.ts` | run lifecycle, plan gate, files, chat/apply/iterate |
| `projects.ts` | project dashboard, repos, architecture, docs, memory, contracts, standards |
| `billing.ts` | plans, subscription, top-up, profile/invoices |
| `auth.ts` | login/session/user/PAT helpers |
| `org.ts` | org settings, members, model matrix, API keys |
| `models.ts` | available models/provider metadata |
| `reviews.ts` | PR review and code review |
| `sharing.ts` | project invites and share links |
| `notifications.ts` | notifications and preferences |
| `discovery.ts` | readymades, migration recipes, spec templates |
| `benchmark.ts` | benchmark tracking/dashboard APIs |
| `admin.ts` | admin analytics, payment audit, tier controls |
| `deploy.ts` | deploy/preview helpers |
| `integrations.ts` | GitHub/Slack/Jira/etc. helpers |
| `misc.ts` | SSO and smaller shared endpoints |

New frontend code should import from `@/lib/api` unless it has a reason to import a domain module directly.

## Request Contract

All normal REST helpers should go through `req<T>` in `core.ts`.

Rules:

- send access JWT/PAT as `Authorization: Bearer ...`,
- let `req<T>` parse common error envelopes,
- do not call `fetch` directly from pages/components unless the endpoint has unusual streaming/binary needs,
- add DTOs in `core.ts` only when they are shared; otherwise keep narrow types in the domain module.

## SSE

Run SSE uses scoped tokens:

1. `GET /api/runs/{runId}/sse-token`
2. `GET /api/runs/{runId}/events`

The browser EventSource stream should not use the full access JWT in the URL. Reconnect/backfill logic should prefer the existing run event-log endpoint rather than inventing a second stream.

Benchmark tracking uses its own domain module and should stay separate from run SSE because benchmark events are suite/campaign oriented.

## Adding An Endpoint

1. Add/confirm the backend route and RBAC guard.
2. Add a narrow frontend helper in the matching `frontend/src/lib/api/*.ts` file.
3. Export it from `frontend/src/lib/api/index.ts`.
4. Add or reuse DTOs.
5. Update `docs/openapi.yaml` for public/customer-facing APIs.
6. If the endpoint touches models, billing, access, benchmarks, or run lifecycle, update the relevant `docs/features/*.md`.

## Common Footguns

- Do not put new endpoints back into `core.ts` just because it already has many types.
- Do not bypass model/BYOK DTOs in `models.ts`/`org.ts`; the model matrix has layered semantics.
- Do not assume every protected route renders in the same shell; the IDE shell is default and the Console shell is opt-in.
- Do not expose run event tokens outside their run scope.
- Keep binary downloads (`report/pdf`, invoices, exports) explicit about response type.

## Code Pointers

- `frontend/src/lib/api/core.ts`
- `frontend/src/lib/api/index.ts`
- `frontend/src/hooks/`
- `frontend/src/stores/`
- `src/main/java/com/navneet/executespec/api/controller/`
- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`
