# ExecuteSpec — Document Map

> Which docs to update for each type of change.
> Last updated: **2026-05-12** — docs cleanup pass on `feature/full-project-review-2026-05-11`. All `docs/audits/` content removed (point-in-time reports superseded by source-of-truth in `CLAUDE.md` / `ARCHITECTURE.md`); the 8 per-plan files under `docs/plans/` consolidated into a single [`docs/plans/pending.md`](plans/pending.md); the GA-readiness and complimentary-PRO-promo plans removed pending rewrite; the parked agent-builder plan, stale `phase-2-3-deferred.md` / `ux-overhaul-v4.md` / `pre-deploy-open-items.md` / `wave-7-issue-list.md` / `eval-2026-04-26-partial-and-openai-degradation.md` deleted; the expired thread-handoffs (`wave1-progress-handoff.md`, `v3-issues-handoff.md`, `strict-ux-thread-handoff.md`, `execution-checkpoint.md`, `pre-deploy-closeout.md`) and retired greenfield UX redesign tree (`greenfield-action-list.md`, `greenfield-execution-checkpoint.md`, `greenfield-ux-redesign.md`) and stale `local-db-sync-notes.md` all dropped.
>
> **App version:** v2.6.1 (deployed to dev.executespec.ai 2026-04-22; main synced 2026-04-23). Enterprise readiness (v2.6.3-track) **Phases E1+E2+E3+E4 (40 slices total) shipped on `feature/enterprise-readiness-eval`** (49+ commits ahead of `dev`, not yet merged); operational summary lives in [`CLAUDE.md`](../CLAUDE.md) under §Current State + §Recently Resolved. Hierarchical planner (v2.6.2-track) **all 5 slices shipped on `feature/hierarchical-planner`** (8 commits ahead of `dev`, not yet merged); operational summary in [`CLAUDE.md`](../CLAUDE.md) §0c (in `ARCHITECTURE.md`) and the v2/agent codemap row. Auto-replay re-trigger from outline checkpoint is the one remaining gap.
>
> **Codemap consolidation (2026-04-25):** the duplicate root-level `CODEBASE_MAP.md` was removed on `feature/hierarchical-planner`. `docs/CODEMAP.md` is now the only codemap; `README.md`'s docs table points there.

## Plans folder structure (post-2026-05-12 cleanup)

`docs/plans/` now holds a single consolidated file:

| File | Type | What it is |
|------|------|------------|
| [`pending.md`](plans/pending.md) | consolidated | All currently-pending plans in one document: (1) Partial/follow-up work — Mobile RN+Expo gaps, Figma v2, Harness V2 Gap 1/2/3, PARTIAL-status removal proposal; (2) Backlog deferred items (M-035..M-052); (3) V2 batch execution; (4) Distributed run processing (Phase A NFS + Phase B worker pool); (5) VS Code extension (deferred to external repo); (6) Project-surface Vite split (Phase 2 of v2.7 product split); (7) Readymade Solutions library (W1–W5 shipped on `feature/readymades-and-migrations`, W6–W9 reserved); (8) Migrations product (W1–W7 shipped on same branch, W8–W10 deferred). |

**Removed in 2026-05-12 cleanup:**
- `pending-ga-readiness-90-day-execution-plan.md` — to be rewritten.
- `pending-complimentary-pro-promo-2026-04-30-plan.md` — to be rewritten.
- `pending-agent-builder-plan.md` — parked 2026-04-29 ("wrong product shape" per founder); historical only.
- `phase-2-3-deferred.md` — self-stamps "PARTIALLY SUPERSEDED" 2026-04-27; still-pending items moved into `pending.md`.
- `ux-overhaul-v4.md` — self-stamps "SHIPPED on the branch"; superseded by later UX work.
- `pre-deploy-open-items.md` — only shippable item already shipped.
- `wave-7-issue-list.md` — all closed.
- `eval-2026-04-26-partial-and-openai-degradation.md` — proposed fixes shipped via v2.5.10 verified-score + hierarchical planner + worker-runaway-fixes.
- `README.md`, `partial-work.md`, `backlog-deferred-items.md`, `pending-v2-batch-execution-plan.md`, `pending-distributed-processing-plan.md`, `pending-vscode-extension-plan.md`, `pending-project-surface-vite-split.md`, `pending-migrations-plan.md`, `pending-readymade-solutions-plan.md` — content consolidated into `pending.md`.

**Removed in 2026-04-26 cleanup (history, kept here as breadcrumbs):**
- `hierarchical-planner-plan.md` — all 5 slices shipped on `feature/hierarchical-planner`. Operational truth lives in `CLAUDE.md` and `ARCHITECTURE.md`.
- `pending-enterprise-readiness-execution-eval.md` — all 4 phases shipped on `feature/enterprise-readiness-eval`. Operational truth lives in `CLAUDE.md` §Current State + the new package summaries in `CODEMAP.md` + `ARCHITECTURE.md` §0d.
- `open-work-2026-04-18.md` — stale 8-day audit referencing deleted plans; superseded by `partial-work.md` (now folded into `pending.md`).
- `pending-preview-control-plane-plan.md` + `preview-engine-rewrite.md` — preview pipeline moved to the separate [`preview-engine`](https://github.com/navneetprabhakar/preview-engine) repo; new plans will be authored there.

## Recently shipped (kept here as breadcrumbs; full plans deleted)

The following plans were merged + deployed and their plan files have been removed. Operational truth lives in [`CLAUDE.md`](../CLAUDE.md), [`ARCHITECTURE.md`](ARCHITECTURE.md), and [`PRD.md`](PRD.md).

- **v2.6.3-track Enterprise Readiness** (4 phases, 40 slices, V131-V139) — `.executespec/` folder write contract + activity feed + cross-repo contracts + engineering standards + ADRs + arch invariants + notification preferences + project export. Shipped on `feature/enterprise-readiness-eval`, not yet merged.
- **v2.6.2-track Hierarchical Planner** (5 slices, V130) — Phase 0 classifier + Phase 1 outline planner + Phase 2 parallel expander + Phase 3 coherence patcher. Shipped on `feature/hierarchical-planner`, not yet merged.
- **v2.6.1 Waves 1-6** — TEAM tier + 3-state lifecycle + PostHog activation funnel + sandbox onboarding + SSO/GitHub App schema + smart-routing scaffold + startup program + 14-day MAX trial + enterprise SLA reference + CLI plan stub. Migrations V121–V129.
- **v2.6.0** — UX v3 Graphite+Signal theme + LlmProvider GEMINI rename + pricing v3 ($1=1 credit per band) + US-first pivot + Stripe prod default. Migration V120.
- **v2.5.x series** — RN+Expo codegen (V118), 3-step architecture pipeline + Generate Docs (V112–V115), validation v3 Pillar 1 (V106–V109), guardrail levels (P1–P5).

## Reference docs (still authoritative)

- [`docs/reports/model-mapping-audit-2026-05-06.md`](reports/model-mapping-audit-2026-05-06.md) — full audit of every place model identifiers / pricing / context-window metadata live in the codebase. 8 hardcoded sources catalogued (1 active drift on `gpt-5.4` context window, 7 stale-or-redundant). Includes a live 3-provider LOW-band cost eval (Anthropic $0.10 / OpenAI $0.04 / Gemini Pro $1.16) + 3-commit single-source-of-truth proposal. Ground reference for all model-related changes.
- [`docs/operations/slos.md`](operations/slos.md) — GA-readiness P4.2 (2026-05-06). Five SLOs ExecuteSpec commits to: run terminal in 15min ≥95%, verified-score correctness ≥90% (manual sample audit), cost estimate within 30% on ≥80% of runs, webhook in 60s ≥99%, hot-path 5xx <0.5%. Includes alert thresholds, owners, and measurement queries.
- [`docs/operations/local-test-playbook.md`](operations/local-test-playbook.md) — PR-F (2026-05-13). Operational checklist for verifying changes locally before pushing to dev. Maps the standing rule "before every push: Flyway migrate + Spring boot + smoke request" into a step-by-step playbook with stop conditions, smoke tests, and common gotchas.
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

| Change Type | CLAUDE.md | ARCHITECTURE.md | CODEMAP.md | WORKFLOWS.md | Other |
|-------------|-----------|-----------------|------------|--------------|-------|
| New agent or major agent change | §Architecture + §Key File Locations + §Model Matrix | §3 V2 pipeline | §Agents | §1 if pipeline shape changes | — |
| New REST endpoint | §API Contracts table | §15 / §13 / §14 if bench-mode / autofix / sharing | — | §1-§15 if user-initiated | openapi.yaml + frontend-api-usage.md if frontend consumes |
| New scheduled job / background service | §Current State | §8 Concurrency model | — | If user-visible | — |
| Pipeline flow change | §V2 pipeline | §3 V2 pipeline | §Run pipeline | §1 + §2 | — |
| New feature flag | §Tier ladder / §Security / wherever it applies | §17 + relevant section | — | — | — |
| Auth / RBAC change | §Auth + RBAC bullet | §5 Security + §5 Auth + RBAC | §Auth | — | RBAC.md |
| Billing / payment change | §Tier ladder + §Current State | §7 Billing | §Billing | — | billing-workflows.html + payment-history.md + stripe-price-ids-ops.md if env vars change |
| Preview / deployment change | §Architecture bullet | §6 Deployment + preview | §Deployment | §12 | DEPLOYMENT.md §2 |
| New configuration property | §Required env vars | §Section relevant | — | — | DEPLOYMENT.md §1 env vars |
| DB migration (new Flyway file) | §Architecture (head V###) | §4 Data architecture | §Migrations index | — | DEPLOYMENT.md §1 migration table |
| LLM / model routing change | §Models, routing, cost (tier table) | §10 LLM routing | §LLM routing | — | reports/model-mapping-audit-2026-05-06.md if mapping changes |
| Security hardening | §Security bullet | §5 Security | §`security/` | — | — |
| `.executespec/` folder write (`folder/ExecutespecFolderWriter`) | — | §12 | §`folder/` | — | Caller-allowlist test must be updated |
| Frontend component (new page/panel) | — | §16 Frontend | — | If it surfaces a new workflow | frontend-api-usage.md if it calls new endpoints |
| Frontend API client (`api.ts`) | — | — | — | — | frontend-api-usage.md + ApiContractTest |
| New doc file added | — | — | — | — | document_map.md (this file — add row to Document Index) |
| Known tech debt resolved | §Known tech debt (remove entry) | §20 if architectural | — | — | — |
| New pending plan | — | — | — | — | `plans/pending.md` (add new §N) |

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
| `plans/pending.md` | Single consolidated doc for every pending plan (status, what's shipped, what remains, owner / branch / dependencies) | When picking up any pending work or adding a new plan section |
| `billing-workflows.html` | Rendered billing flow diagrams (subscribe, top-up, refund, webhook handling) | Billing / payment flow changes |
| `design/ui-tokens.md` | Single source of truth for color/radius/spacing tokens | Adding a UI primitive, tweaking the palette, theme work |
| `document_map.md` | This file | Adding a new doc, adding a new change type |

---

## Per-Package Quick Reference

> Mirrors the actual top-level packages under `src/main/java/com/navneet/executespec/` as of 2026-05-12 (post the 2026-05-07 unification — no V2/V3 prefixes). See [`CODEMAP.md`](CODEMAP.md) for the full structural map.

| Package | Primary doc sections |
|---------|----------------------|
| `activity/` (project activity feed, partitioned by month, V133) | ARCHITECTURE §12; CODEMAP §`activity/` |
| `adr/` (ADRs as first-class, V137) | ARCHITECTURE §12; CODEMAP §`adr/` |
| `agent/` (LlmRouter, FallbackLlmRouter, SmartClassifier / Outline / TaskExpander / Coherence / Worker / Patch agents, native-tool clients) | CLAUDE.md §V2 pipeline; ARCHITECTURE §3 + §10; CODEMAP §Agents |
| `analytics/` (UserActivationEvent + PostHog 6-event funnel) | PRD §3.8; USER_MANUAL §Sandbox; CLAUDE.md activation funnel bullet |
| `anonrun/` (anonymous no-auth run flow) | CODEMAP §`anonrun/` |
| `api/` (controllers + DTOs + advice) | JAVADOC §17; CODEMAP §Top-level layout |
| `apitests/` (ApiTestSuiteAgent) | CODEMAP §Agents |
| `arch/invariants/` (continuous architectural properties, V138) | ARCHITECTURE §12; CODEMAP §`arch/invariants/` |
| `assessment/` (pre-plan feature assessment) | ARCHITECTURE §3 + §11; CODEMAP §Project workbench |
| `auth/` (incl. `dsar/`, `sso/`, `oauth/`, `security/`) | RBAC.md; ARCHITECTURE §5; JAVADOC §5 |
| `batch/` (frozen V1 batch surface — V2 batch in `plans/pending.md` §3) | `plans/pending.md` §3 |
| `billing/` (incl. `lifecycle/`, `gateway/`, `webhook/`, `founder/`, `cost/`) | ARCHITECTURE §7; PRD §3.6; billing-workflows.html; stripe-price-ids-ops.md; payment-history.md |
| `chat/` (apply-suggestion service) | USER_MANUAL §Project chat; CLAUDE.md project chat bullet |
| `codeintel/` (3-step arch pipeline + Generate Docs + StalenessService) | ARCHITECTURE §11; CODEMAP §Project workbench |
| `codereview/` (whole-repo Code Review agent + autofix Phase 4) | CLAUDE.md §13; ARCHITECTURE §13; CODEMAP §Agents |
| `compliance/` (DataRetentionService — GDPR soft-delete) | ARCHITECTURE §5; CODEMAP §`compliance/` |
| `composer/` (Migration recipe slot validator + spec composer — cross-product helpers) | CODEMAP §Readymade + Migration |
| `config/featureflag/` | CLAUDE.md Feature flags bullet; ARCHITECTURE §17 |
| `contact/` (public contact form) | CODEMAP §`contact/` |
| `context/` (ContextAgent legacy + V2 ContextStore Redis snapshot) | ARCHITECTURE §3; CLAUDE.md Worker context bullet |
| `contracts/` (cross-repo registry — drift, consumers, coverage, V135) | ARCHITECTURE §12; CODEMAP §`contracts/` |
| `cost/` (RunCostTracker — cache-aware per-call accumulator) | ARCHITECTURE §10; CLAUDE.md Cost tracker bullet |
| `credit/` (legacy v3 credit grant + ledger) | CODEMAP §`credit/` |
| `crypto/` (KMS / encryption-at-rest) | ARCHITECTURE §5 |
| `deployment/` (incl. `preview/`) | DEPLOYMENT.md §2; ARCHITECTURE §6; WORKFLOWS §12 |
| `entitlements/` (EntitlementResolver tier rank + project scope) | RBAC.md; CLAUDE.md auth bullet |
| `eval/` (model evaluation framework) | JAVADOC §24 |
| `execution/` (Execution View — multi-repo, multi-solution) | ARCHITECTURE §15; CODEMAP §Execution View |
| `export/` (project export ZIP bundle, E4.7) | ARCHITECTURE §12 |
| `finding/` (FindingPatchSubmitter chokepoint for the 4 autofix surfaces, V183) | CLAUDE.md autofix surfaces table; ARCHITECTURE §3.4 |
| `folder/` (.executespec/ folder write chokepoint + bootstrap, E1) | ARCHITECTURE §12; CODEMAP §`folder/` |
| `governance/` (plan-approval policy + project spend cap + repo allowlist) | CODEMAP §`governance/` |
| `harness/` (SequentialExecutor + workspace + gate primitives) | ARCHITECTURE §3; CODEMAP §Run pipeline |
| `integration/` (Slack, JIRA, Confluence, Bitbucket, PagerDuty, ServiceNow, scm/ScmPushService, RunOrchestrator) | WORKFLOWS §7-14; CODEMAP §Integrations |
| `landing/` (legacy v3 landing surface) | CODEMAP §`landing/` |
| `lint/` (lint autofix flag, V180) | CODEMAP §`lint/` |
| `memory/` (project memory + chat + chat tools) | ARCHITECTURE §11; CLAUDE.md Project memory bullet |
| `migration/` (Migration Recipe orchestrator) | `plans/pending.md` §8; CODEMAP §Readymade + Migration; migration-recipes-catalog-format.md |
| `notification/` (incl. `pref/` per-user matrix V139) | CLAUDE.md notifications bullet; ARCHITECTURE §12 |
| `observability/` (Micrometer V2 metrics) | ARCHITECTURE §9 |
| `onboarding/` (sandbox + sample specs) | PRD §3.8; USER_MANUAL §Getting started |
| `optimiser/` (PromptOptimiserAgent) | CODEMAP §Agents |
| `pricing/` (PricingController — YAML-driven) | ARCHITECTURE §10 |
| `programbench/` (ProgramBench cleanroom adapter) | ARCHITECTURE §15; CODEMAP §Bench adapters |
| `project/` (incl. `dashboard/`, `estimation/`, `pr/`, `rag/`, `scores/`, `techdebt/`, `template/`, `workspace/`) | ARCHITECTURE §11; project-user-journeys.md |
| `prreview/` (PR Review agent + autofix Phase 1, V172) | CLAUDE.md §13; ARCHITECTURE §13 |
| `ratelimit/` (Redis per-tenant rate limit) | CLAUDE.md Rate limiting bullet; ARCHITECTURE §5 |
| `readymade/` (Readymade Solutions library, V157) | `plans/pending.md` §7; readymades-catalog-format.md |
| `recovery/` (StartupRunResumer — boot-time crash recovery) | ARCHITECTURE §3; CLAUDE.md In-progress + Recovery |
| `referral/` (referral codes + lifetime credit grants — legacy v3 surface) | CODEMAP §`referral/` |
| `repo/` (GitHubRepoService + BitbucketRepoService + ScmProvider) | ARCHITECTURE §11; CODEMAP §Integrations |
| `report/` (ValidationReport entity + PDF + share) | ARCHITECTURE §3; CLAUDE.md scoring bullet |
| `reviewer/` (ReviewerAgent + AccuracyAssessmentService) | ARCHITECTURE §3; CODEMAP §Agents |
| `rollout/` (RolloutRunController — gradual rollout surface) | CODEMAP §`rollout/` |
| `router/` (LlmProvider, ModelRegistry, ProviderHealthTracker, ProviderRecommendationService) | ARCHITECTURE §10; CLAUDE.md Model Matrix |
| `run/` (run sync helpers + run-aux services) | CODEMAP §`run/` |
| `saga/` (side-effect outbox / compensation) | CODEMAP §`saga/` |
| `scoring/` (6-dimension scoring + verifiedScore) | ARCHITECTURE §3; CLAUDE.md scoring bullet |
| `security/` (incl. `guardrail/`) | ARCHITECTURE §5; CLAUDE.md security bullet |
| `sharing/` (external project sharing — cross-org `ProjectMember` + public share links, V175) | ARCHITECTURE §14; CLAUDE.md §External project sharing |
| `smoketest/` (CliSmokeRunner) | CODEMAP §`smoketest/` |
| `sse/` (RunEvent + SseBroadcaster + ExecutionEventTypes) | WORKFLOWS §15; ARCHITECTURE §3 |
| `standards/` (engineering standards as binding policy + AST/LLM checks + override, V136) | ARCHITECTURE §12 |
| `state/` (StateStore + JPA entities for runs/tasks) | ARCHITECTURE §4; CODEMAP §State |
| `swebench/` (SWE-bench Lite adapter) | ARCHITECTURE §15 |
| `tenant/` (tenant filter + interceptor + thread context) | ARCHITECTURE §5 |
| `test/` (test controllers — V2 dev-only) | CODEMAP §`test/` |
| `testgen/` (TestGenOrchestrator + agents + ApiProber) | CODEMAP §Agents |
| `tool/` (11 worker tools + CompleteTaskTool + ShellSandboxPolicy + FileSandboxPolicy + ProcessExecutor) | ARCHITECTURE §3; CODEMAP §Run pipeline |
| `util/` (PoolSizing helper for per-service dispatch pools) | CODEMAP §Top-level layout |
| `validator/` (CodeValidator + execution/gate/language/prompt subpackages) | CODEMAP §`validator/` |
| `frontend/src/` | frontend-api-usage.md; user-journeys.md; project-user-journeys.md |
| `frontend/src/tokens.css` + `tailwind.config.ts` | [`design/ui-tokens.md`](design/ui-tokens.md) |
| `src/main/resources/db/migration/` | CLAUDE.md Flyway note; DEPLOYMENT.md §1 migration table; CODEMAP §Migrations |
| `src/main/resources/application.yml` | CLAUDE.md env vars; DEPLOYMENT.md §1 env vars |
| `src/main/resources/prompts/` (+ `fragments/`, `scaffolds/`) | CLAUDE.md Prompt architecture bullet |
| `src/main/resources/sample-specs/` | PRD §3.8; USER_MANUAL §Getting started |
| `src/main/resources/readymades/` + `migration-recipes/` | readymades-catalog-format.md + migration-recipes-catalog-format.md |
| `src/main/resources/templates/email/` + `templates/report/` | CLAUDE.md email templates bullet; ARCHITECTURE §6 |

---

## Rules for Keeping This File Current

1. When you add a new document to `docs/`, add it to the **Document Index** table.
2. When you add a new package under `src/main/java/`, add it to the **Per-Package Quick Reference** AND to `CODEMAP.md`.
3. When you introduce a new class of change (e.g., "WebSocket endpoint"), add a row to the **Change-to-Document Matrix**.
4. When a plan ships and you delete its plan file, remove the link here too.
5. This file itself does **not** need updating for routine bugfixes or config tweaks — only for structural additions.
