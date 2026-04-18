# ExecuteSpec — Document Map

> Which docs to update for each type of change.
> Last updated: 2026-04-18 (v2.5.12 — 3-step architecture + Generate Docs + Sync enhancement; v2.5.0: project workspace + RAG; pilot v3.0)
>
> **v2.5.12 docs to know about:**
> - [`docs/test-scenarios/personas-journeys-and-cases.md`](test-scenarios/personas-journeys-and-cases.md) — 6 personas, 8 user journeys, ~55 test cases organised in 12 blocks, 10-minute smoke pack, persona × feature coverage matrix.
> - [`docs/test-scenarios/persona-test-analysis-2026-04-18.md`](test-scenarios/persona-test-analysis-2026-04-18.md) — single-session smoke pass against local; covers what passed (3-step arch idempotency, Generate Docs end-to-end, regression markers from v2.5.12), what was deferred (full run lifecycle, OAuth, payments), 10 numbered gaps with severity + fix; system rating **7.4/10** across 11 dimensions.
> - [`docs/test-scenarios/e2e-all-features-test-plan.md`](test-scenarios/e2e-all-features-test-plan.md) — 17-section, ~50-scenario plan covering every user-facing surface (the system-perspective companion to the persona-perspective doc above).
>
> **v2.5.x docs:**
> - [`docs/project-user-journeys.md`](project-user-journeys.md) — end-to-end user journeys + workflow map for the project workbench (Setup → Launch → PR merge → continue building, docs upload, chat, etc.).
> - [`docs/plans/project-codemap-arch-rag-plan.md`](plans/project-codemap-arch-rag-plan.md) — feature design (workspace layout, RAG invariants, phasing).
> - [`docs/plans/project-codemap-arch-rag-execution.md`](plans/project-codemap-arch-rag-execution.md) — concrete per-phase implementation order.
> - [`docs/plans/project-codemap-rag-backlog.md`](plans/project-codemap-rag-backlog.md) — deferred items (vector spill, OCR / rerank / semantic chunking) + the DB-vs-disk decision rationale.
>
> **Pilot-v3.0 docs to know about:**
> - [`docs/plans/pilot-v3-plan.md`](plans/pilot-v3-plan.md) — full recap of
>   the project-workspace / multi-repo / codebase-intel / assessment /
>   scoring / PR-webhook / codemap-on-clone release.
> - `USER_MANUAL.md` §13 — user-facing walkthrough for project-scoped runs.
> - `frontend-api-usage.md` — new "Pilot v3.0 — Project workspace endpoints"
>   table lists every new endpoint with its frontend caller.
> - `DEPLOYMENT.md` — ops additions: `GITHUB_WEBHOOK_SECRET`,
>   `assessment.llm.enabled` feature flag, `StackRefreshJob` cron.

---

## How to Use This File

Find the **type of change** you are making in the table below. Update every document listed in the "Update" column. If a doc is marked "if relevant", only update it when your change directly affects that doc's scope.

---

## Change-to-Document Matrix

| Change Type | CLAUDE.md | ARCHITECTURE.md | JAVADOC.md | WORKFLOWS.md | document_map.md |
|-------------|-----------|-----------------|------------|--------------|-----------------|
| New agent or major agent change | §Key File Locations | §3.2 Agent Architecture | §5.x Agent System | §2 if it changes pipeline shape | — |
| New REST endpoint | §API Contracts | — | §17.x REST API Reference | If it adds a user-facing workflow | — |
| New scheduled job / background service | §Current State (Working/In Progress) | §8 Concurrency Model (if threaded) | §19 Recovery System (if recovery-related) or new section | §10 Task Retry & Recovery | — |
| Pipeline flow change | §Architecture (multi-agent pipeline bullet) | §4.2 Detailed Pipeline Steps | §8.1 PipelineHarness + §4 Core Pipeline Flow | §2 Pipeline Execution | — |
| New feature flag | §Feature Flags | §7.2 Feature Flag System | §12 Feature Flags | — | — |
| Auth / RBAC change | §Auth bullet in Architecture | §5 Security Architecture + §10 Auth | §10 Auth and Multi-Tenancy | — | — |
| Billing / payment change | §Billing bullet | §7 Billing Architecture | §14 Billing and Payment Gateway | §billing-workflows.html | — |
| Preview / deployment change | §Infra bullet + Key File Locations | §6 Deployment Architecture + §6.2 Preview System | §16 Deployment and Preview | §12 Deployment & Preview | — |
| New configuration property | §Build & Test (if env var) | — | §20 Configuration Reference | — | — |
| DB migration (new Flyway file) | §Patterns: MUST Follow (Flyway note) | §4 Data Architecture | §21.1 Flyway Migrations | — | — |
| LLM / model routing change | §Model Matrix table | §3.1 / §9 Model Routing | §9 Model Routing | — | — |
| Security hardening | §Security section | §5 Security Architecture | §25 Security Architecture | — | — |
| Frontend component (new page/panel) | — | — | — | If it surfaces a new workflow | — |
| Frontend API client (`api.ts`) | — | — | — | — | — |
| New doc file added | — | — | — | — | Add row here |
| Known tech debt resolved | §Known Tech Debt (remove entry) | If architectural | — | — | — |

---

## Document Index

| File | Owner Scope | When to Open It |
|------|-------------|-----------------|
| `CLAUDE.md` | Session-start instructions, architecture summary, API contracts, key file locations, tech debt | Any change that affects the "working" list, architecture bullets, API table, or tech debt list. The most frequently updated doc. |
| `ARCHITECTURE.md` | Deep-dive system design: packages, data flow, concurrency, security, billing, preview | Structural changes: new service, changed threading model, new infra component, DB schema overview |
| `JAVADOC.md` | Class-level reference: method signatures, configuration properties, Flyway history, entity fields | Adding/changing a public method, adding a config property, adding a Flyway migration, changing a recovery service |
| `WORKFLOWS.md` | End-to-end user journey flows (Mermaid diagrams) | Adding or changing a workflow a user initiates — new recovery flow, new gate, new billing step |
| `CODE_GEN_ARCHITECTURE.md` | Agent code generation pipeline internals | Changes to WorkerAgent tool dispatch, SubtaskDecomposer, CompilerAgent, ReviewerAgentV2 |
| `DEPLOYMENT.md` | GCP deploy runbook, CI/CD steps, Cloud Run config | Changes to deploy process, environment variables, Cloud Run flags |
| `RBAC.md` | Role definitions, permission matrix | Changes to roles, org membership rules, tier permissions |
| `PRD.md` | Product requirements and feature specs | New product feature decisions |
| `USER_MANUAL.md` | End-user-facing how-to guide | User-visible behaviour changes |
| `frontend-api-usage.md` | Frontend API consumption patterns | New API endpoints consumed by frontend |
| `openapi.yaml` | OpenAPI spec | New or changed REST endpoints |
| `run-workflow-sequence.md` | Sequence diagram for run lifecycle | Pipeline lifecycle changes |
| `requirements/*.md` | Feature-level requirements docs | Completing or changing a feature requirement |
| `document_map.md` | This file | Adding a new doc, adding a new change type |

---

## Per-Package Quick Reference

| Package | Primary Doc Sections |
|---------|----------------------|
| `harness/` | JAVADOC §8 Pipeline Harness; ARCHITECTURE §3.1; WORKFLOWS §2 |
| `harness/PartialRunResumeService` | JAVADOC §19.7; WORKFLOWS §10 |
| `agent/` | JAVADOC §5 Agent System; ARCHITECTURE §3.2 |
| `agent/LlmRouter`, `router/` | JAVADOC §9 Model Routing; ARCHITECTURE §Model Matrix |
| `deployment/preview/` | JAVADOC §16 Deployment and Preview; ARCHITECTURE §6.2 Preview System; WORKFLOWS §12 |
| `deployment/preview/PreviewService` | JAVADOC §16.1; ARCHITECTURE §6.2 |
| `state/` | JAVADOC §7 State Management |
| `recovery/` | JAVADOC §19 Recovery System; WORKFLOWS §10 |
| `auth/` | JAVADOC §10-11 Auth; ARCHITECTURE §5 Security |
| `billing/` | JAVADOC §14-15 Billing; ARCHITECTURE §7 Billing |
| `config/featureflag/` | JAVADOC §12 Feature Flags; ARCHITECTURE §7.2 |
| `ratelimit/` | JAVADOC §13 Rate Limiting |
| `batch/` | JAVADOC §8 (batch flow sub-sections); ARCHITECTURE pipeline bullet |
| `testgen/` | JAVADOC §5.10 Test Generation Agents; WORKFLOWS §2 (background validation) |
| `report/` | JAVADOC §17.10 Validation Report Endpoints |
| `integration/` | JAVADOC §17.11-17.12; WORKFLOWS §7-8 |
| `security/` | JAVADOC §25 Security Architecture |
| `eval/` | JAVADOC §24 Eval Framework |
| `frontend/src/components/runs/` | `frontend-api-usage.md`; WORKFLOWS if new workflow surface |
| `frontend/src/lib/api.ts` | `frontend-api-usage.md` |
| `src/main/resources/db/migration/` | JAVADOC §21.1 Flyway Migrations; CLAUDE.md Flyway note |
| `src/main/resources/application.yml` | JAVADOC §20 Configuration Reference; CLAUDE.md env vars |
| `src/main/resources/prompts/` | JAVADOC §5.x (per agent); CLAUDE.md Prompt architecture bullet |

---

## Reliability & Recovery Subsystem (Consolidated)

All three reliability mechanisms introduced in the sidecar-console-reliability-fixes PR:

| Mechanism | File | Doc Section |
|-----------|------|-------------|
| PlannerAgent retry | `harness/PipelineHarness.java` | JAVADOC §8.1; ARCHITECTURE §8 Concurrency |
| PARTIAL auto-resume | `harness/PartialRunResumeService.java` | JAVADOC §19.7; WORKFLOWS §10 |
| Task max-retries | `application.yml` → `concurrency.task-max-retries` | JAVADOC §20.2 |

---

## Rules for Keeping This File Current

1. When you add a new document to `docs/`, add it to the **Document Index** table.
2. When you add a new package under `src/main/java/`, add it to the **Per-Package Quick Reference**.
3. When you introduce a new class of change (e.g., "WebSocket endpoint"), add a row to the **Change-to-Document Matrix**.
4. This file itself does **not** need updating for routine bugfixes or config tweaks — only for structural additions.
