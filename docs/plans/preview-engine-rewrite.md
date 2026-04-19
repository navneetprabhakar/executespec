# Preview pipeline rewrite — now lives in `preview-engine`

> **Decision recorded:** 2026-04-19
> **Status:** Phase-0 scaffold shipped; Phase-1 (real async architecture) pending.
> **Source of truth:** [github.com/navneetprabhakar/preview-engine](https://github.com/navneetprabhakar/preview-engine)

## Why this doc exists

The "generate a preview" feature is being rewritten from scratch. It **moves out
of this repo** and into a dedicated `preview-engine` repo because:

- Independent deploy cadence — preview changes shouldn't drag main-app redeploys.
- Multi-language stack (Java control plane + Go worker) doesn't fit one Spring Boot repo.
- Dedicated VM + load balancer — operationally owned there.
- Clear team + CI/CD boundary.

## Where things live now

| Concern                                | Lives in                                               |
| -------------------------------------- | ------------------------------------------------------ |
| Preview-engine code, infra, docs, tests | `github.com/navneetprabhakar/preview-engine`          |
| Design doc (combo matrix, decisions)    | `preview-engine/docs/preview-platform-redesign.md`    |
| Product requirements                    | `preview-engine/docs/PRD.md`                          |
| System architecture                     | `preview-engine/docs/architecture.md`                 |
| Codemap                                 | `preview-engine/docs/codemap.md`                      |
| Auth design                             | `preview-engine/docs/auth.md` (deferred)              |

## Key decisions (locked 2026-04-19)

| # | Question                     | Decision                                                     |
| - | ---------------------------- | ------------------------------------------------------------ |
| 1 | Repo shape                   | Separate repo `preview-engine`                               |
| 2 | Languages                    | Java 21 + Spring Boot 3.3 (control plane) · Go 1.22+ (worker)|
| 3 | Queue                        | Redis Streams                                                 |
| 4 | Auth between apps (Phase 1)  | Service-to-service API key (`X-Preview-Api-Key`)             |
| 5 | Preview URL scheme           | Subdomain — `<id>.preview.<domain>`                           |
| 6 | Tier concurrency caps        | FREE=1 · PRO=3 · MAX=10 · ENTERPRISE=configurable            |
| 7 | Cleanup cadence              | Single global idle-TTL + activity-extend + disk-pressure eviction |
| 8 | Mobile streaming             | WebRTC preferred · noVNC fallback                             |
| 9 | iOS strategy                 | Expo Web + QR only; Android AVD on Linux workers              |
| 10| Shared infra with main app   | Same Postgres + Redis; preview-engine uses `preview` schema  |

## What this implies for `executespec`

- The existing `deployment/preview/*` path **will be decommissioned** once
  preview-engine is production-ready. Not deleted in this PR; still serves
  today's preview traffic.
- Eventually, `POST /api/runs/{id}/preview/start` will proxy to
  preview-engine's `POST /api/previews` with a service API key instead of
  running Docker/Cloud Run here.
- No schema work needed in this repo for Phase 1 — preview-engine owns its
  own Postgres schema + Flyway history.

## Cross-references

- [`preview-engine/README.md`](https://github.com/navneetprabhakar/preview-engine/blob/main/README.md) — quick start
- [`preview-engine/scripts/smoke-test-results/CONSOLIDATED.md`](https://github.com/navneetprabhakar/preview-engine/blob/main/scripts/smoke-test-results/CONSOLIDATED.md) — latest end-to-end test matrix (15/15 green across 5 suites as of 2026-04-19)

This file will be updated as preview-engine phases land.
