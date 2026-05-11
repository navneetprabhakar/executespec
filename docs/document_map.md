# ExecuteSpec — Document Map

> Which docs to update for each type of change.
>
> **Last cleanup: 2026-05-11** — pruned 17 obsolete docs (v3-conversion
> handoff cluster, greenfield trio, parked plans, one-shot snapshots)
> + retired the entire `docs/audits/` folder (9 markdown reports +
> 141 screenshot assets). Past audit/RCA breadcrumbs now live in git
> log; operational truth lives in [`CLAUDE.md`](../CLAUDE.md). Branch
> `feature/docs-cleanup-2026-05-11`.
>
> **Codemap consolidation (2026-04-25):** the duplicate root-level
> `CODEBASE_MAP.md` was removed. `docs/CODEMAP.md` is the only
> codemap; `README.md`'s docs table points there.

## Plans folder

`docs/plans/` holds the plans still actively useful:

| File | Type | What it is |
|------|------|------------|
| [`pending-ga-readiness-90-day-execution-plan.md`](plans/pending-ga-readiness-90-day-execution-plan.md) | proposed | 90-day Plan B execution doc operationalising the 2026-05-04 GA-readiness audit. Phase 0 (land in-flight branches) → P1 quality scoring fix → P2 pricing ladder → P3 cost ceiling + HIGH-band reliability → P4 observability + ArchUnit guard pin → P5 beta program + SOC 2 Type I + CLI → P6 GA cutover. Target GA 2026-08-04. Awaiting founder approval before kickoff. |
| [`partial-work.md`](plans/partial-work.md) | consolidated | Plans with partial implementations / pending follow-ups (Mobile RN+Expo native-Android gap, Figma v2, Harness V2 remaining gaps, PARTIAL-status removal proposal). |
| [`pending-v2-batch-execution-plan.md`](plans/pending-v2-batch-execution-plan.md) | not started | Re-implement batch execution under V2 (V1 batch was deleted). Realtime-only today. |
| [`pending-distributed-processing-plan.md`](plans/pending-distributed-processing-plan.md) | not started | Phase A NFS workspace + Phase B worker-lease pool. |
| [`pending-vscode-extension-plan.md`](plans/pending-vscode-extension-plan.md) | not started | External-repo VS Code extension; device-code OAuth. |
| [`pending-readymade-solutions-plan.md`](plans/pending-readymade-solutions-plan.md) | follow-ups only | Readymades library — W1-W5 + W7 shipped; remaining v1.5 follow-ups documented inside. |
| [`pending-migrations-plan.md`](plans/pending-migrations-plan.md) | follow-ups only | Migrations product — W1-W7 shipped; remaining items documented inside. |
| [`backlog-deferred-items.md`](plans/backlog-deferred-items.md) | backlog | Cross-plan deferred items captured during 2026-04-28 cleanup. |
| [`README.md`](plans/README.md) | index | Plans folder status index. |

## Reference docs (still authoritative)

- [`docs/reports/model-mapping-audit-2026-05-06.md`](reports/model-mapping-audit-2026-05-06.md) — full audit of every place model identifiers / pricing / context-window metadata live in the codebase. 8 hardcoded sources catalogued (1 active drift on `gpt-5.4` context window, 7 stale-or-redundant). Includes a live 3-provider LOW-band cost eval (Anthropic $0.10 / OpenAI $0.04 / Gemini Pro $1.16) + 3-commit single-source-of-truth proposal. Ground reference for all model-related changes.
- [`docs/operations/slos.md`](operations/slos.md) — GA-readiness P4.2 (2026-05-06). Five SLOs ExecuteSpec commits to: run terminal in 15min ≥95%, verified-score correctness ≥90% (manual sample audit), cost estimate within 30% on ≥80% of runs, webhook in 60s ≥99%, hot-path 5xx <0.5%. Includes alert thresholds, owners, and measurement queries.
- [`docs/design/ui-tokens.md`](design/ui-tokens.md) — single source of truth for every color/radius/spacing token. Semantic palette, 4-band score palette, MUST/MUST NOT rules.
- [`docs/design/code-style.md`](design/code-style.md) — Java + TS style policy + spotless wiring + 7 documented deviations from Google style.
- [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) — procurement-ready security posture + tier comparison.
- [`docs/enterprise-sla.md`](enterprise-sla.md) — ENTERPRISE-tier P1–P4 SLA + 99.9% uptime credit schedule + CSM engagement.
- [`docs/project-user-journeys.md`](project-user-journeys.md) — end-to-end user journeys for the project workbench.
- [`docs/user-journeys.md`](user-journeys.md) — UI user journeys by tier.
- [`docs/dpa-flow.md`](dpa-flow.md) — DPA request/signature runbook.
- [`docs/payment-history.md`](payment-history.md) — Razorpay + Stripe payment_audit reference.
- [`docs/sso-saml-wiring.md`](sso-saml-wiring.md) — SSO/SAML wire-in spec (SAML2 handshake is the pending piece).
- [`docs/status-page.md`](status-page.md) — Better Stack status-page commitment.
- [`docs/stripe-price-ids-ops.md`](stripe-price-ids-ops.md) — Stripe env-var checklist for ops.
- [`docs/migration-recipes-catalog-format.md`](migration-recipes-catalog-format.md) — YAML format spec for migration-recipe seeder files.
- [`docs/readymades-catalog-format.md`](readymades-catalog-format.md) — YAML format spec for readymade-solutions catalog.

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
| `plans/backlog-deferred-items.md` | First-slice + owner-needed + when-to-re-open per deferred Backlog item (M-035/036/037/040/041/048/050/052) | When picking up any of those items |
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
| `sharing/` | CLAUDE.md v2.6.4 entry (V175) — external project sharing: cross-org `ProjectMember` invites + public read-only share links. Tier-gated. |
| `util/` | `PoolSizing.coreMultiple(multiplier, min, max)` — shared helper for sizing per-service dispatch pools (CodeReview, PrReview, RunService, RunOrchestrator, RunConversationController.applyExecutor). |
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
