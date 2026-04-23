# ExecuteSpec — Document Map

> Which docs to update for each type of change.
> Last updated: 2026-04-23 (v2.6.1 Waves 1-6 merged to dev + main + deployed to dev.executespec.ai; v2.6.0: UX v3 Graphite+Signal + pricing v3 + US-first pivot + PH launch kit; v2.5.x: earlier releases)
>
> **v2.6.1 Wave 1 (2026-04-22 → 2026-04-28): TEAM tier + tier-lifecycle tooling shipped together (Option Z). See [docs/plans/team-tier-plan.md](plans/team-tier-plan.md) + [docs/plans/tier-lifecycle-plan.md](plans/tier-lifecycle-plan.md). PRO grandfathered to `HIDDEN` via V122 migration — existing subscribers unaffected. SUNSETTING gateway side-effects gated by `billing.lifecycle.sunsetting.enabled` feature flag (default OFF).**
>
> **v2.6.0 docs to know about:**
> - [`docs/design/ui-tokens.md`](design/ui-tokens.md) — new single source of truth for every color in the app. Semantic palette (`--status-success/executing/waiting/partial/error/...`), graduated 4-band score palette, accent palette, radius/spacing scale, Tailwind mapping, chip patterns, MUST/MUST NOT rules. If you add a component, start here.
> - [`docs/reports/session-handoff-2026-04-21.md`](reports/session-handoff-2026-04-21.md) — detailed handoff: commits landed, pricing v3 rationale (Option C over B), US-first pivot, Stripe prod default, blocking list for PH launch, deferred follow-ups (Stripe top-up wiring, country-aware routing, currency-gateway-map, Stripe refund path, Gemini token bloat fix, mobile accuracy floor, Bug #1c strict-provider bypass).
> - [`docs/reports/launch-checklist-2026-04-21.md`](reports/launch-checklist-2026-04-21.md) — PH launch readiness tracker; website + press-kit + OG hero + screenshots.
> - `website/tier-decision.md` / `website/pricing-analysis.md` / `website/product-hunt-launch.md` — external-facing source of truth for pricing + launch messaging.
>
> **Persona roadmap docs (2026-04-22 session — 3 buyer personas × 4 tiers × PM lens → 6 waves of shipping):**
> - [`docs/plans/persona-roadmap-2026-04-22.md`](plans/persona-roadmap-2026-04-22.md) — **master roadmap**. All persona × tier interventions, PM-view action items, 6-wave shipping calendar (Apr 28 → Jul 15), open decisions, non-goals. Start here.
> - [`docs/plans/team-tier-plan.md`](plans/team-tier-plan.md) — Wave 1. TEAM tier at $49/seat (2-20 devs, 50 credits/seat shared pool) + bundled tier-lifecycle tooling (Option Z). Ships 2026-04-28 → 2026-05-06.
> - [`docs/plans/tier-lifecycle-plan.md`](plans/tier-lifecycle-plan.md) — standalone reference for the 3-state (LISTED/HIDDEN/SUNSETTING) lifecycle machine. Used by team-tier launch to grandfather PRO; reusable for any future tier retirement.
> - [`docs/plans/activation-instrumentation-plan.md`](plans/activation-instrumentation-plan.md) — Wave 2a. 6-event PostHog funnel (signup → project_created → spec_submitted → plan_approved → run_complete → pr_merged). Ships 2026-05-07 → 2026-05-20.
> - [`docs/plans/ttfv-reduction-plan.md`](plans/ttfv-reduction-plan.md) — Wave 2b (parallel with activation). Sandbox project + pre-filled sample spec on signup; target median TTFV under 3 min. Ships 2026-05-07 → 2026-05-20.
> - [`docs/plans/wave-3-sso-githubapp-procurement-plan.md`](plans/wave-3-sso-githubapp-procurement-plan.md) — **Wave 3** (schema + docs shipped 2026-04-23; controllers in follow-up). SSO/SAML on MAX, GitHub App install, team trial CTA, procurement one-pager. Ships 2026-05-21 → 2026-06-03.
> - [`docs/plans/wave-4-landing-proof-routing-changelog-plan.md`](plans/wave-4-landing-proof-routing-changelog-plan.md) — **Wave 4** (scaffold shipped). `ProviderRecommendationService` skeleton + 2 A/B feature flags + live-demo hero + enterprise proof pack + changelog/status pages. Ships 2026-06-04 → 2026-06-17.
> - [`docs/plans/vscode-extension-plan.md`](plans/vscode-extension-plan.md) — Wave 4 external-repo stub; `executespec-vscode` with device-code OAuth flow.
> - [`docs/plans/wave-5-startup-and-polish-plan.md`](plans/wave-5-startup-and-polish-plan.md) — **Wave 5** (schema shipped). Startup program (50% off MAX for <20-employee companies) + 14-day MAX trial on signup + free-tier 2-teammate invites + progressive disclosure + BYOK credit meter + shareable report links on FREE. Ships 2026-06-18 → 2026-07-01.
> - [`docs/plans/wave-6-cli-enterprise-depth-plan.md`](plans/wave-6-cli-enterprise-depth-plan.md) — **Wave 6** (docs shipped). ExecuteSpec CLI (external repo) + audit CSV export on ENTERPRISE + CSM/SLA docs + customer logos page. Ships 2026-07-02 → 2026-07-15.
> - [`docs/plans/cli-plan.md`](plans/cli-plan.md) — Wave 6 external-repo stub; `executespec-cli` Node CLI with device-code OAuth.
> - [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) — procurement-ready security posture + tier comparison + data-handling Q&A (Wave 3 deliverable).
> - [`docs/enterprise-sla.md`](enterprise-sla.md) — ENTERPRISE-tier support SLA (P1-P4 definitions + 99.9% uptime credit schedule + CSM engagement) (Wave 6 deliverable).
> - [`docs/plans/complimentary-pro-promo-2026-04-30-plan.md`](plans/complimentary-pro-promo-2026-04-30-plan.md) — founder thank-you promo for recent FREE signups; 30-day PRO upgrade. Executes 2026-04-30 (reminder scheduled).
>
>
> **v2.5.12 docs to know about:**
> - [`docs/test-scenarios/personas-journeys-and-cases.md`](test-scenarios/personas-journeys-and-cases.md) — 6 personas, 8 user journeys, ~55 test cases organised in 12 blocks, 10-minute smoke pack, persona × feature coverage matrix.
> - [`docs/test-scenarios/persona-test-analysis-2026-04-18.md`](test-scenarios/persona-test-analysis-2026-04-18.md) — single-session smoke pass against local; covers what passed (3-step arch idempotency, Generate Docs end-to-end, regression markers from v2.5.12), what was deferred (full run lifecycle, OAuth, payments), 10 numbered gaps with severity + fix; system rating **7.4/10** across 11 dimensions.
> - [`docs/test-scenarios/adversarial-test-report-2026-04-18.md`](test-scenarios/adversarial-test-report-2026-04-18.md) — unbiased adversarial pass (~85 probes) against the same local build; covers auth/RBAC/tier, all 3 LLM providers end-to-end, fuzz/SSRF/XSS/JWT-tampering, webhook signatures. 19 numbered findings including **1 CRITICAL IDOR cluster** across 12 project sub-resource endpoints. System rating **7.2/10** — drops 0.6 vs smoke pass because the IDOR finding wasn't surfaced earlier.
> - [`docs/test-scenarios/e2e-all-features-test-plan.md`](test-scenarios/e2e-all-features-test-plan.md) — 17-section, ~50-scenario plan covering every user-facing surface (the system-perspective companion to the persona-perspective doc above).
>
> **v2.5.x docs:**
> - [`docs/project-user-journeys.md`](project-user-journeys.md) — end-to-end user journeys + workflow map for the project workbench (Setup → Launch → PR merge → continue building, docs upload, chat, etc.).
> - [`docs/plans/project-codemap-arch-rag-plan.md`](plans/project-codemap-arch-rag-plan.md) — feature design (workspace layout, RAG invariants, phasing).
> - [`docs/plans/project-codemap-arch-rag-execution.md`](plans/project-codemap-arch-rag-execution.md) — concrete per-phase implementation order.
> - `docs/plans/project-codemap-rag-backlog.md` — **retired 2026-04-18**; Items 0 + 2 shipped; Items 1 + 3 captured in [`docs/plans/open-work-2026-04-18.md`](plans/open-work-2026-04-18.md) §D2 / §D3.
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
| `frontend/src/tokens.css` + `tailwind.config.ts` | [`docs/design/ui-tokens.md`](design/ui-tokens.md) |
| `frontend/src/stores/useUiStore.ts` (theme slice) | USER_MANUAL §v2.6.0 quick links + `docs/design/ui-tokens.md` |
| `frontend/src/pages/ExecSummaryPage.tsx` | USER_MANUAL §v2.6.0; CLAUDE.md §Key File Locations (v2.6.0 UX v3 row) |
| `website/` | `docs/reports/session-handoff-2026-04-21.md` + `docs/reports/launch-checklist-2026-04-21.md` (external launch-facing assets) |
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
