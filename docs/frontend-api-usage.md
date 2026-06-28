# Frontend API Usage

> Recreated 2026-06-10 from `frontend/src/lib/api/`. This document describes the current API-client shape, not the old Signal/V3-era god file.

## Client Layout

The public import surface is the barrel:

- `frontend/src/lib/api/index.ts`

Domain modules:

| Module             | Responsibility                                                                                                                                           |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `core.ts`          | `BASE`, auth headers, `ApiError`, `req<T>`, shared DTOs/types                                                                                            |
| `runs.ts`          | run lifecycle, plan gate, files, chat/apply/iterate                                                                                                      |
| `projects.ts`      | project dashboard, repos, architecture, docs, memory, contracts, standards, project invites                                                              |
| `billing.ts`       | plans, subscription, top-up, profile/invoices                                                                                                            |
| `auth.ts`          | login/session/user, account provider preference (`PUT /auth/me/provider-preference`), PAT mint (`POST /auth/me/tokens`)                                  |
| `org.ts`           | org settings, members, model matrix, API keys                                                                                                            |
| `models.ts`        | available models/provider metadata                                                                                                                       |
| `reviews.ts`       | PR review and code review                                                                                                                                |
| `sharing.ts`       | org invite acceptance/admin invite helpers and project share links                                                                                       |
| `notifications.ts` | notifications and preferences                                                                                                                            |
| `discovery.ts`     | readymades, migration recipes, spec templates                                                                                                            |
| `benchmark.ts`     | benchmark tracking/dashboard APIs                                                                                                                        |
| `agent.ts`         | agent-session control plane APIs, gate cards, action planning, transcript replay, run-proposal confirmation; re-exports stream helpers for compatibility |
| `agentStream.ts`   | POST-SSE agent turn streaming request/event DTOs, named frame parser, auth-bearing stream fetch, typed handler dispatch                                  |
| `billing.ts`       | billing analytics/pricing helpers plus managed-AI balance, usage events, and policy                                                                      |
| `admin.ts`         | admin analytics, payment audit, tier controls                                                                                                            |
| `deploy.ts`        | deploy/preview helpers                                                                                                                                   |
| `integrations.ts`  | GitHub/Slack/Jira/etc. helpers                                                                                                                           |
| `misc.ts`          | SSO and smaller shared endpoints                                                                                                                         |

New frontend code should import from `@/lib/api` unless it has a reason to import a domain module directly.

Scope notes:

- The web app reads `preferredAiProvider` off the `/auth/me` user DTO (`core.ts`) and only ever _writes_ it through `updateProviderPreference` (`auth.ts`); there is no separate GET helper. The compose, project-chat, run-result, and Build-my-Spec surfaces all share this one account-scoped write.
- `createPat` (`auth.ts`) is consumed only by the `/cli-login` device-login page (`signal/pages/CliLoginPage.tsx`), which hands the plaintext `es_pat_…` straight to the CLI/VS Code client; the web app does not list or revoke tokens.
- The agent-session protocol (`/api/agent/sessions/*`) is now shared by web, CLI, and VS Code. Browser code must use `agent.ts`; CLI/VS Code-only local-hands capabilities (`local_code_task`, local git/shell/file actions) must not appear in WEB capability results.
- Agent action lifecycle calls (`/api/agent/v2/capabilities`, `/api/agent/v2/sessions/{id}/actions/*`) are part of the same public protocol and are documented in `docs/openapi.yaml`. Do not add ad hoc client-only action names outside the server action catalog.
- Web route components should use `ide/agentSession.ts` for session scope matching/create request construction instead of hand-rolled `listAgentSessions().find(...)` checks. Project-scoped sessions require `projectId` and no `runId`; run-scoped sessions require the exact `runId`.
- Run-scoped web routes must create/select sessions with `runId`, not a prose prefix. `/runs/:runId/agent` uses `agent_session.run_id` so reloads keep the same run-scoped control-plane context.
- `/projects/:projectId/chat` keeps reading legacy project-chat history for continuity, but new sends must go through `streamAgentTurn` on a WEB agent session with `projectId`. Do not add new frontend calls to `POST /api/projects/{id}/chat`.
- The Run result Chat tab keeps reading legacy `/api/runs/{runId}/chat` history for continuity, but new sends must go through `streamAgentTurn` on a WEB agent session with `runId`. Do not add new frontend calls to `POST /api/runs/{runId}/chat`.
- `POST /api/orchestrator/turn` is the decision-only route/policy endpoint. It persists an `ORCHESTRATOR_DECISION` trace and never creates a run; clients must explicitly confirm any returned execution proposal before using run/lifecycle APIs.
- `POST /api/orchestrator/turn/confirm` is the only web/agent confirmation path for a stored `CODE_RUN_REQUIRED` proposal. It rejects non-run routes, missing confirmation, and duplicate turn submissions before touching the run pipeline. `confirmAgentRunProposal` may include `lowComplexityWorkerStartTier?: 'WORKHORSE' | 'LIGHTWEIGHT'` to start LOW tasks cheap without setting a route-pinning `modelName`.
- `streamAgentTurn` is implemented in `agentStream.ts` and re-exported through `agent.ts` / `@/lib/api`. It handles `route_decision` and `proposal` events in addition to token/tool/done frames. Web action cards should use those typed events and `confirmAgentRunProposal`; route components must not infer mutations from prose.
- `POST /api/admin/eval/orchestrator-routing/run` is an operator-only live model-prompt eval over the routing corpus. It is not a frontend/customer endpoint; the response includes `markdownReport`, and `scripts/orchestrator-routing-eval.sh` writes that report with a jq fallback for older servers.
- Managed-AI usage reads live in `billing.ts`: `getManagedAiBalance`, `getManagedAiEvents`, and `getManagedAiPolicy`. Agent/billing UI should consume those APIs instead of inferring credit state from run summaries.

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

Agent-session turn streaming uses POST SSE:

1. `POST /api/agent/sessions/{sessionId}/turns/stream`
2. pass the normal bearer header; do not use browser `EventSource` for this route
3. parse named frames: `ack`, `route_decision`, `proposal`, `token`, `tool_call`, `tool_result`, `done`, `error`

Use `streamAgentTurn` from `@/lib/api` (implemented in `agentStream.ts`); do not duplicate the parser in route components.

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
- Protected routes now render inside the Console shell by default; keep route-level components shell-neutral and avoid reintroducing `localStorage es:console` or `uiMode` branching.
- Do not expose run event tokens outside their run scope.
- Keep binary downloads (`report/pdf`, invoices, exports) explicit about response type.

## Code Pointers

- `frontend/src/lib/api/core.ts`
- `frontend/src/lib/api/index.ts`
- `frontend/src/hooks/`
- `frontend/src/stores/`
- `src/main/java/com/navneet/executespec/api/controller/`
- `src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`
