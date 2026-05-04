# ExecuteSpec — Document Map

> Which docs to update for each type of change.
> Last updated: **2026-05-01** — audit r3 + Google-style refactor + CI lint enforcement merged via PRs #55+#56+#57. New artefacts registered below: `docs/design/code-style.md` (deviations doc), `frontend/A11Y.md` (a11y enablement guide), `.github/workflows/lint.yml` (PR lint gate), `frontend/.prettierrc.json` + `.prettierignore` + `.eslintrc.cjs` (style configs), `frontend/src/design-system/contrast.{ts,test.ts}` (vitest-shape WCAG regression test, dormant). Audit reports: `docs/audits/persona-test-2026-05-04-r4.md` (post-refactor regression check, supersedes r3) + `docs/audits/dev-verification-2026-05-04.md` (live dev super-admin walkthrough, 31 screenshots). Earlier 2026-04-28 R-326 cluster B refresh + 2026-04-26 cleanup-pass-1 history retained in git log.
>
> **App version:** v2.6.1 (deployed to dev.executespec.ai 2026-04-22; main synced 2026-04-23). Enterprise readiness (v2.6.3-track) **Phases E1+E2+E3+E4 (40 slices total) shipped on `feature/enterprise-readiness-eval`** (49+ commits ahead of `dev`, not yet merged); operational summary lives in [`CLAUDE.md`](../CLAUDE.md) under §Current State + §Recently Resolved. Hierarchical planner (v2.6.2-track) **all 5 slices shipped on `feature/hierarchical-planner`** (8 commits ahead of `dev`, not yet merged); operational summary in [`CLAUDE.md`](../CLAUDE.md) §0c (in `ARCHITECTURE.md`) and the v2/agent codemap row. Auto-replay re-trigger from outline checkpoint is the one remaining gap.
>
> **Codemap consolidation (2026-04-25):** the duplicate root-level `CODEBASE_MAP.md` was removed on `feature/hierarchical-planner`. `docs/CODEMAP.md` is now the only codemap; `README.md`'s docs table points there.

## Plans folder structure (post-2026-04-26 cleanup)

`docs/plans/` holds only the 5 plans that are still actively useful:

| File | Type | What it is |
|------|------|------------|
| [`partial-work.md`](plans/partial-work.md) | consolidated | All 4 plans with partial implementations / pending follow-ups: Mobile (RN+Expo native-Android gap), Figma v2 (design-only), Harness V2 remaining work (Gap 1+2+3), and the PARTIAL-status removal proposal. |
| [`pending-v2-batch-execution-plan.md`](plans/pending-v2-batch-execution-plan.md) | not started | Re-implement batch execution under V2 (V1 batch was deleted). Realtime-only today. |
| [`pending-distributed-processing-plan.md`](plans/pending-distributed-processing-plan.md) | not started | Phase A NFS workspace + Phase B worker-lease pool. |
| [`pending-vscode-extension-plan.md`](plans/pending-vscode-extension-plan.md) | not started | External-repo VS Code extension; device-code OAuth. |
| [`pending-complimentary-pro-promo-2026-04-30-plan.md`](plans/pending-complimentary-pro-promo-2026-04-30-plan.md) | not started | Founder thank-you 30-day PRO upgrade for early FREE signups. |

**Removed in 2026-04-26 cleanup:**
- `hierarchical-planner-plan.md` — all 5 slices shipped on `feature/hierarchical-planner`. Operational truth lives in `CLAUDE.md` and `ARCHITECTURE.md`.
- `pending-enterprise-readiness-execution-eval.md` — all 4 phases shipped on `feature/enterprise-readiness-eval`. Operational truth lives in `CLAUDE.md` §Current State + the new package summaries in `CODEMAP.md` + `ARCHITECTURE.md` §0d.
- `open-work-2026-04-18.md` — stale 8-day audit referencing deleted plans; superseded by `partial-work.md`.
- `pending-preview-control-plane-plan.md` + `preview-engine-rewrite.md` — preview pipeline moved to the separate [`preview-engine`](https://github.com/navneetprabhakar/preview-engine) repo; new plans will be authored there.

## Recently shipped (kept here as breadcrumbs; full plans deleted)

The following plans were merged + deployed and their plan files have been removed. Operational truth lives in [`CLAUDE.md`](../CLAUDE.md), [`ARCHITECTURE.md`](ARCHITECTURE.md), and [`PRD.md`](PRD.md).

- **v2.6.3-track Enterprise Readiness** (4 phases, 40 slices, V131-V139) — `.executespec/` folder write contract + activity feed + cross-repo contracts + engineering standards + ADRs + arch invariants + notification preferences + project export. Shipped on `feature/enterprise-readiness-eval`, not yet merged.
- **v2.6.2-track Hierarchical Planner** (5 slices, V130) — Phase 0 classifier + Phase 1 outline planner + Phase 2 parallel expander + Phase 3 coherence patcher. Shipped on `feature/hierarchical-planner`, not yet merged.
- **v2.6.1 Waves 1-6** — TEAM tier + 3-state lifecycle + PostHog activation funnel + sandbox onboarding + SSO/GitHub App schema + smart-routing scaffold + startup program + 14-day MAX trial + enterprise SLA reference + CLI plan stub. Migrations V121–V129.
- **v2.6.0** — UX v3 Graphite+Signal theme + LlmProvider GEMINI rename + pricing v3 ($1=1 credit per band) + US-first pivot + Stripe prod default. Migration V120.
- **v2.5.x series** — RN+Expo codegen (V118), 3-step architecture pipeline + Generate Docs (V112–V115), validation v3 Pillar 1 (V106–V109), guardrail levels (P1–P5).

## Reference docs (still authoritative)

- [`docs/design/ui-tokens.md`](design/ui-tokens.md) — single source of truth for every color/radius/spacing token. Semantic palette, 4-band score palette, MUST/MUST NOT rules.
- [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) — procurement-ready security posture + tier comparison.
- [`docs/enterprise-sla.md`](enterprise-sla.md) — ENTERPRISE-tier P1–P4 SLA + 99.9% uptime credit schedule + CSM engagement.
- [`docs/project-user-journeys.md`](project-user-journeys.md) — end-to-end user journeys for the project workbench.
- [`docs/user-journeys.md`](user-journeys.md) — UI user journeys by tier.

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
| `CODEMAP.md` | Per-package structural index of `src/main/java/com/navneet/executespec/` | Quick lookup of which package owns what; updated whenever a new top-level package is added |
| `JAVADOC.md` | Class-level reference: method signatures, configuration properties, Flyway history, entity fields | Adding/changing a public method, adding a config property, adding a Flyway migration, changing a recovery service |
| `WORKFLOWS.md` | End-to-end user journey flows (Mermaid diagrams) | Adding or changing a workflow a user initiates — new recovery flow, new gate, new billing step |
| `DEPLOYMENT.md` | GCP deploy runbook, CI/CD steps, Cloud Run config, env vars | Changes to deploy process, environment variables, Cloud Run flags |
| `RBAC.md` | Role definitions, permission matrix, tier enforcement | Changes to roles, org membership rules, tier permissions, tier limits |
| `PRD.md` | Product requirements and feature specs | New product feature decisions |
| `USER_MANUAL.md` | End-user-facing how-to guide | User-visible behaviour changes |
| `frontend-api-usage.md` | Frontend API consumption patterns | New API endpoints consumed by frontend |
| `openapi.yaml` | OpenAPI spec | New or changed REST endpoints |
| `user-journeys.md` | UI user journeys by tier | Changes to navigation, tier-gated screens, new pages |
| `project-user-journeys.md` | Project-workbench end-to-end journeys | Changes to project / launch / chat flows |
| `enterprise-one-pager.md` | Procurement / IT / security one-pager | Tier table, security posture, data-handling Q&A changes |
| `enterprise-sla.md` | ENTERPRISE-tier support SLA | P1–P4 definitions, uptime credit schedule changes |
| `status-page.md` | Better Stack status-page operator runbook (M-018) | Provider swap, component changes, canary-runner contract |
| `payment-history.md` | Razorpay + Stripe `payment_audit` runtime story (M-044) | Payment flow / webhook event subscriptions / gateway migration |
| `dpa-flow.md` | DPA request → drafting → signature → activation runbook (M-020) | Sub-processor add/remove, template updates, signing-tool changes |
| `sso-saml-wiring.md` | SAML wire-in spec + per-IdP setup notes + lockout-prevention guard rationale (M-016) | When SAML2 actually wires in, when adding a new IdP, when changing the lockout rules |
| `stripe-price-ids-ops.md` | Stripe env-var checklist + setup runbook + operator gotchas (M-029) | Price-id ops changes, gateway flag flips, setup procedure changes |
| `cookie-domain-switch.md` | Maintenance-window plan for the JWT cookie scope flip (M-031) | When the Phase-2 surface split actually executes |
| `backlog-deferred-items.md` | First-slice + owner-needed + when-to-re-open per deferred Backlog item (M-035/036/037/040/041/048/050/052) | When picking up any of those items |
| `execution-checkpoint.md` | Running execution log of the M-* batches on `feature/v2.7-foundation` | After every 3 items shipped + at every wave-boundary transition |
| `billing-workflows.html` | Rendered billing flow diagrams (subscribe, top-up, refund, webhook handling) | Billing / payment flow changes |
| `design/ui-tokens.md` | Single source of truth for color/radius/spacing tokens | Adding a UI primitive, tweaking the palette, theme work |
| `document_map.md` | This file | Adding a new doc, adding a new change type |

---

## Per-Package Quick Reference

> Mirrors the actual top-level packages under `src/main/java/com/navneet/executespec/` as of 2026-04-25. See [`CODEMAP.md`](CODEMAP.md) for the full structural map.

| Package | Primary Doc Sections |
|---------|----------------------|
| `v2/` (V2 harness — agent/, batch/, chat/, context/, cost/, harness/, integration/, observability/, recovery/, reviewer/, smoketest/, test/, tool/) | JAVADOC §8 Pipeline Harness; ARCHITECTURE §3 V2 pipeline; CLAUDE.md V2 bullets; CODEMAP.md §V2 |
| `agent/` (LlmRouter V1, PromptAssembler, ConversationAgent, CostEstimator) | JAVADOC §5 Agent System (V1 surface still used by chat/test-gen); CODEMAP.md §LLM router |
| `analytics/` (UserActivationEvent + EventTrackingService + PostHogClient) | ARCHITECTURE §0b Wave 2; CLAUDE.md activation funnel bullet |
| `api/` (RunMapper + advice/ + controller/ + dto/) | JAVADOC §17.x REST API Reference |
| `apitests/` (ApiTestSuiteAgent) | JAVADOC §apitests |
| `assessment/` (FeatureAssessmentService heuristic + LLM-gated) | ARCHITECTURE §pilot v3.0 (pre-plan assessment) |
| `auth/` | JAVADOC §10–11 Auth; ARCHITECTURE §5 Security; RBAC.md |
| `billing/` (incl. lifecycle/) | JAVADOC §14–15 Billing; ARCHITECTURE §7 Billing + §0b Wave 1; billing-workflows.html |
| `codeintel/` | JAVADOC §codeintel; ARCHITECTURE §3-step arch pipeline (v2.5.12) |
| `compliance/` (DataRetentionService — GDPR soft-delete) | JAVADOC §compliance; CLAUDE.md retention bullet |
| `config/featureflag/` | JAVADOC §12 Feature Flags; ARCHITECTURE §7.2 |
| `contact/` (public contact form) | JAVADOC §contact controller |
| `context/` (legacy V1 ContextAgent) | JAVADOC §context (legacy) |
| `crypto/` (KMS / encryption-at-rest) | ARCHITECTURE §5 Security |
| `deployment/`, `deployment/preview/` | JAVADOC §16; ARCHITECTURE §6.2; WORKFLOWS §12 |
| `eval/` | JAVADOC §24 Eval Framework |
| `execution/` (Execution View) | ARCHITECTURE §execution view (v2.3.0) |
| `governance/` (M-032/033/039 — `GovernanceGuard`) | CHANGELOG v2.7 / CODEMAP §v2.7 Foundation new packages |
| `auth/dsar/` (M-017 — `DsarController`) | CHANGELOG v2.7 / CODEMAP §v2.7 Foundation new packages |
| `auth/sso/` (M-016 — `SsoConfigController` lockout guard) | sso-saml-wiring.md |
| `harness/` (V1 — workspace, GateStore, design-system) | JAVADOC §8 (legacy V1 surface kept); ARCHITECTURE §V2 reuses these primitives |
| `integration/` (slack, jira, confluence, bitbucket, pagerduty, servicenow, scm) | JAVADOC §17.11–17.12; WORKFLOWS §7–8 |
| `memory/` | ARCHITECTURE §project memory (v2.2.0) |
| `notification/` | JAVADOC §notification; CLAUDE.md notification bullet |
| `onboarding/` | ARCHITECTURE §0b Wave 2 (sandbox); USER_MANUAL §v2.6.1 onboarding |
| `optimiser/` | JAVADOC §optimiser |
| `pricing/` | JAVADOC §pricing; ARCHITECTURE §0a v3 pricing |
| `project/` (incl. dashboard/, estimation/, pr/, rag/, scores/, techdebt/, template/, workspace/) | JAVADOC §project; ARCHITECTURE §pilot v3.0; project-user-journeys.md |
| `ratelimit/` | JAVADOC §13 Rate Limiting |
| `repo/` (GitHubRepoService, BitbucketRepoService, ScmProvider) | JAVADOC §repo (SCM clients) |
| `report/` | JAVADOC §17.10 Validation Report Endpoints |
| `reviewer/` (V1 surface — V2 uses v2/reviewer/) | JAVADOC §reviewer (legacy) |
| `router/` (LlmProvider enum, ModelResolver, ProviderHealthTracker, ProviderRecommendationService) | JAVADOC §9 Model Routing; ARCHITECTURE §Model Matrix |
| `run/` | JAVADOC §run controllers |
| `saga/` | JAVADOC §saga |
| `scoring/` | JAVADOC §scoring; ARCHITECTURE §6-dimension scoring |
| `security/` (incl. guardrail/) | JAVADOC §25 Security Architecture |
| `sse/` | JAVADOC §SSE channel (RunEvent, ExecutionEventTypes) |
| `state/` | JAVADOC §7 State Management |
| `tenant/` | JAVADOC §tenant isolation |
| `prreview/` | CLAUDE.md v2.6.4 entry — PR Review Agent (single-call PLANNING-tier review of one PR's diff). |
| `codereview/` | CLAUDE.md v2.6.4 entry — whole-repo Code Review Agent (semgrep + LLM augmentation, markdown report). |
| `testgen/` | JAVADOC §5.10 Test Generation Agents; WORKFLOWS §2 |
| `tool/` (V1 GitTool, etc.) | JAVADOC §tool (legacy V1 surface) |
| `validator/` (CodeValidator + execution/gate/language/prompt subpackages) | JAVADOC §validator |
| `frontend/src/` | frontend-api-usage.md; user-journeys.md; project-user-journeys.md |
| `frontend/src/tokens.css` + `tailwind.config.ts` | [`docs/design/ui-tokens.md`](design/ui-tokens.md) |
| `src/main/resources/db/migration/` | JAVADOC §21.1 Flyway Migrations; CLAUDE.md Flyway note; DEPLOYMENT.md migration ranges |
| `src/main/resources/application.yml` | JAVADOC §20 Configuration Reference; CLAUDE.md env vars; DEPLOYMENT.md env-var section |
| `src/main/resources/prompts/` | JAVADOC §5.x (per agent); CLAUDE.md Prompt architecture bullet |
| `src/main/resources/sample-specs/` | ARCHITECTURE §0b Wave 2 (sandbox onboarding); USER_MANUAL §v2.6.1 |
| `src/main/resources/templates/email/` + `templates/report/` | JAVADOC §email templates; ARCHITECTURE §0a UX v3 (palette swap) |

---

## Rules for Keeping This File Current

1. When you add a new document to `docs/`, add it to the **Document Index** table.
2. When you add a new package under `src/main/java/`, add it to the **Per-Package Quick Reference** AND to `CODEMAP.md`.
3. When you introduce a new class of change (e.g., "WebSocket endpoint"), add a row to the **Change-to-Document Matrix**.
4. When a plan ships and you delete its plan file, remove the link here too.
5. This file itself does **not** need updating for routine bugfixes or config tweaks — only for structural additions.
