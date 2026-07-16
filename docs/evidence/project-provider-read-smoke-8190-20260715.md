# Authenticated project/provider read smoke — 2026-07-15

## Scope

This is a redacted local smoke against the current backend artifact on port
`8190`. It exercises authenticated, read-only project and provider surfaces.
The process was started from the `codex/executespec-2.0` worktree with the
`local` Spring profile and placeholder provider values solely to make the
application context boot. No LLM request was dispatched.

| Field | Value |
| --- | --- |
| Backend commit at capture | `089260d319c7e9d1e1b9a9ee062492adc7e2b350` |
| Backend tree at capture | `1d35f2d38812a065e8457a237a1c24c20c8ea2c0` |
| Base URL | `http://localhost:8190` |
| Auth principal | SUPER_ADMIN (email/password redacted) |
| Selected project | `9bd60bd8-a9fa-4175-838e-0515fdc43937` |
| Project list count | `83` |
| Linked repository count | `2` |
| Provider calls | `0` |
| Data mutations | `0` |

## Endpoint results

Every request below used a short-lived authenticated bearer token. Tokens,
passwords, and environment values are intentionally absent from this file.

| Endpoint | HTTP | Response bytes | Interpretation |
| --- | ---: | ---: | --- |
| `/actuator/health` | 200 | — | Application healthy |
| `/api/projects` | 200 | — | 83 projects returned |
| `/api/models/available` | 200 | — | Provider matrix returned |
| `/api/auth/me/provider-preference` | 200 | — | Account preference returned (`OPENAI`) |
| `/api/projects/{projectId}` | 200 | 509 | Project detail |
| `/api/projects/{projectId}/repos` | 200 | 1439 | Two linked repositories |
| `/api/projects/{projectId}/dashboard` | 200 | 326 | Dashboard aggregate |
| `/api/projects/{projectId}/memory` | 200 | 163 | Project memory |
| `/api/projects/{projectId}/memory/ledger` | 200 | 2 | Empty/valid ledger response |
| `/api/projects/{projectId}/scores` | 200 | 2 | Empty/valid score response |
| `/api/projects/{projectId}/architecture` | 200 | 7682 | Cross-repository architecture |
| `/api/projects/{projectId}/architecture/versions` | 200 | 981 | Architecture history |
| `/api/projects/{projectId}/tech-debt?since=30` | 200 | 124 | Tech-debt roll-up |
| `/api/projects/{projectId}/templates` | 200 | 2 | Empty/valid template response |
| `/api/projects/{projectId}/activity` | 200 | 1128 | Activity feed |
| `/api/projects/{projectId}/repos/{repoId}/architecture` | 200 | 6279 | Per-repository architecture |
| `/api/projects/{projectId}/repos/{repoId}/contract-coverage` | 200 | 1505 | Contract coverage |
| `/api/projects/{projectId}/documents` | 200 | 2 | Empty/valid document list |
| `/api/projects/{projectId}/prs` | 200 | 453 | PR listing |
| `/api/projects/{projectId}/workspace-health` | 200 | 743 | Workspace health |

Two negative/validation probes were intentionally included:

| Endpoint | HTTP | Reason |
| --- | ---: | --- |
| `/api/projects/{projectId}/contracts` | 404 | Collection route is not exposed; contract routes require a contract identifier |
| `/api/projects/{projectId}/standards/report?sinceDays=30` | 400 | Expected validation failure because the required `orgId` parameter was omitted |

## Provider matrix observed

`GET /api/models/available` returned these active providers, with no Sarvam
entry:

| Provider | Model label observed |
| --- | --- |
| Anthropic | Claude Sonnet 5 |
| Gemini | Gemini 3.5 Flash |
| OpenAI | GPT-5.4 |
| OpenRouter | DeepSeek V4 Pro (OpenRouter) |
| DeepSeek | DeepSeek V4 Pro (Native) |
| Fireworks | DeepSeek V4 Pro (Fireworks) |

## Limits of this evidence

- This proves authenticated routing, serialization, access control, and
  read-path health only; it does not establish coding quality.
- The smoke intentionally avoided chat, compile, run submission, document
  generation, architecture regeneration, or any provider dispatch.
- The independent live provider coding cohort remains the authoritative
  provider-quality artifact in
  `docs/evidence/provider-quality-cohort-20260715/`.
- The selected project and repository identifiers are non-secret local test
  data; no customer or production endpoint was contacted.
