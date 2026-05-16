# ExecuteSpec — Document map

Index of every doc in this repo, grouped by purpose. The path on the left is canonical. The right column says when you'd read it.

---

## Entry points

| Path | Read when |
|---|---|
| [`../README.md`](../README.md) | First 60 seconds — elevator pitch, stack, run-it-locally, build/test commands. |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Need the system shape — layering, run lifecycle, run modes, frontend chrome, storage, model resolution, observability, security model. |
| [`CODEMAP.md`](CODEMAP.md) | Mechanical index — every Java package, controller, agent, tool, entity, migration, prompt, feature flag, frontend page. |
| [`document_map.md`](document_map.md) | This file. Pointer to every doc in the repo. |

---

## Operational

| Path | Read when |
|---|---|
| [`DEPLOYMENT.md`](DEPLOYMENT.md) | Deploying — GCP project layout, GCE VM setup, Cloudflare wiring, GCS bucket layout, Workload Identity Federation. |
| [`operations/local-test-playbook.md`](operations/local-test-playbook.md) | Smoke-testing locally before pushing — submit a run, verify SSE, watch metrics, check Flyway head. |
| [`operations/slos.md`](operations/slos.md) | Reviewing or revising the published SLOs (run terminal latency, verified-score correctness, cost-estimate accuracy, webhook ack, hot-path 5xx). |
| [`status-page.md`](status-page.md) | Updating the public status page; what each surface monitors. |

---

## Product and UX

| Path | Read when |
|---|---|
| [`PRD.md`](PRD.md) | Need the product-level requirements doc (full vision and scope). |
| [`USER_MANUAL.md`](USER_MANUAL.md) | Customer-facing manual — how to use the platform end-to-end. |
| [`WORKFLOWS.md`](WORKFLOWS.md) | Sequenced user workflows (submit → approve → review → merge) with screenshots. |
| [`project-user-journeys.md`](project-user-journeys.md) | Project-workbench user journeys (Pulse, Repos, Chat, Code, etc.). |
| [`user-journeys.md`](user-journeys.md) | High-level cross-feature user journeys. |
| [`billing-workflows.html`](billing-workflows.html) | HTML diagram of the billing-workflow paths (Stripe + Razorpay + topup + refund). |

---

## Auth and Enterprise

| Path | Read when |
|---|---|
| [`RBAC.md`](RBAC.md) | Auth roles, tier gates, external sharing rules (V198 per-org flag), and per-endpoint guard ledger. |
| [`sso-saml-wiring.md`](sso-saml-wiring.md) | Wiring SAML SSO for an enterprise tenant. |
| [`dpa-flow.md`](dpa-flow.md) | Data Processing Agreement intake + signing flow. |
| [`enterprise-one-pager.md`](enterprise-one-pager.md) | The sales-side enterprise one-pager content + maintenance. |
| [`enterprise-sla.md`](enterprise-sla.md) | The published enterprise SLA (uptime targets, support windows, escalation). |
| [`cookie-domain-switch.md`](cookie-domain-switch.md) | Cookie-domain runbook (e.g. moving `.executespec.ai` → app-only). |

---

## Billing

| Path | Read when |
|---|---|
| [`payment-history.md`](payment-history.md) | Reconstructing a customer's payment history; admin payment-audit endpoint. |
| [`stripe-price-ids-ops.md`](stripe-price-ids-ops.md) | Operating the Stripe price IDs — what each price ID is for, how to rotate. |

---

## Catalog formats

| Path | Read when |
|---|---|
| [`migration-recipes-catalog-format.md`](migration-recipes-catalog-format.md) | Authoring or validating a migration recipe (the schema for `migration_recipe` entries). |
| [`readymades-catalog-format.md`](readymades-catalog-format.md) | Authoring or validating a readymade solution (`readymade_solution` schema). |

---

## Frontend

| Path | Read when |
|---|---|
| [`frontend-api-usage.md`](frontend-api-usage.md) | Auditing which frontend file consumes which backend endpoint. Cross-checked by ArchUnit `ApiContractTest`. |
| [`design/code-style.md`](design/code-style.md) | Resolving an eslint/spotless complaint or wanting to know the documented deviations (`D-J1` wildcards, `D-T1` default exports for `*.tsx`, etc.). |
| [`design/ui-tokens.md`](design/ui-tokens.md) | Adding or changing a colour / typography token; the canonical Graphite + Signal palette + the 4-band score colour scale. |

---

## API surface

| Path | Read when |
|---|---|
| [`openapi.yaml`](openapi.yaml) | Machine-readable OpenAPI definition of the public REST surface. |
| [`JAVADOC.md`](JAVADOC.md) | Curated rich Javadoc on the headline classes. Last comprehensive refresh predates several recent waves — treat as historical context unless the section is dated. |

---

## Per-package CLAUDE.md (code-adjacent context)

These live under `src/main/java/com/navneet/executespec/<pkg>/CLAUDE.md` and serve as code-adjacent context for an LLM (or human) navigating that package. They are not part of the user-facing documentation set but are kept in sync with the code in the same PR as the change.

Present today:

| Path | Covers |
|---|---|
| [`../src/main/java/com/navneet/executespec/agent/CLAUDE.md`](../src/main/java/com/navneet/executespec/agent/CLAUDE.md) | Agents, `LlmRouter`, prompt assembler, tier map, patterns. |
| [`../src/main/java/com/navneet/executespec/codereview/CLAUDE.md`](../src/main/java/com/navneet/executespec/codereview/CLAUDE.md) | `CodeReviewAgent` + autofix Phase 4. |
| [`../src/main/java/com/navneet/executespec/deployment/CLAUDE.md`](../src/main/java/com/navneet/executespec/deployment/CLAUDE.md) | Cloud Run + Cloud Build + Artifact Registry deploy paths + preview engine. |
| [`../src/main/java/com/navneet/executespec/harness/CLAUDE.md`](../src/main/java/com/navneet/executespec/harness/CLAUDE.md) | `SequentialExecutor`, `GateStore`, tier configs. |
| [`../src/main/java/com/navneet/executespec/prreview/CLAUDE.md`](../src/main/java/com/navneet/executespec/prreview/CLAUDE.md) | `PrReviewAgent` + autofix Phase 1. |
| [`../src/main/java/com/navneet/executespec/router/CLAUDE.md`](../src/main/java/com/navneet/executespec/router/CLAUDE.md) | `ModelRegistry`, `ModelResolver`, adaptive routing. |
| [`../src/main/java/com/navneet/executespec/sharing/CLAUDE.md`](../src/main/java/com/navneet/executespec/sharing/CLAUDE.md) | Cross-org invites + public share links. |
| [`../src/main/java/com/navneet/executespec/state/CLAUDE.md`](../src/main/java/com/navneet/executespec/state/CLAUDE.md) | `StateStore`, `PipelineRun`, `AgentTask`, migration trail. |
| [`../src/main/java/com/navneet/executespec/validator/CLAUDE.md`](../src/main/java/com/navneet/executespec/validator/CLAUDE.md) | Validation v3 strict-mode requirement graph. |

There is also a root [`../CLAUDE.md`](../CLAUDE.md) (project-wide context) and a [`../frontend/CLAUDE.md`](../frontend/CLAUDE.md) (frontend context). These are intentionally kept terse and refreshed with every PR; they are the live operational handbook for working in this tree.

---

## What's no longer here (historical)

- `docs/audits/` removed — point-in-time audit reports were superseded by source-of-truth in `CLAUDE.md` and `ARCHITECTURE.md`.
- `docs/plans/` consolidated into the per-feature thread of `CLAUDE.md` + per-package CLAUDE.md files; standalone planning docs no longer live here.
- `docs/reports/` removed — short-lived investigation reports are kept in commit messages and PR descriptions instead.

If you need an old version of any of these, the git history retains the deleted files.

---

## Doc-refresh rule

Standing rule from 2026-04-25: every commit must keep documentation in lockstep with code. Before staging files for any commit, walk this checklist:

1. Root [`../CLAUDE.md`](../CLAUDE.md) — if architecture / API contracts / file locations / agent tiers change.
2. Per-package `CLAUDE.md` — if entities, repository methods, agents, or migrations change in that package.
3. [`CODEMAP.md`](CODEMAP.md) — if top-level packages, entities, agents, or DB migrations are added/removed.
4. [`document_map.md`](document_map.md) — if a doc is added or removed under `docs/`.
5. [`../README.md`](../README.md) — if a user-visible feature, env var, or build step changes.
6. [`design/*`](design/) — if tokens / theming / a11y commitments change.
7. Migration trail — every new `Vxxx__*.sql` cross-linked from the touching package + entity row.

Trivial typo fixes may carry `skip doc-refresh: trivial typo` in the commit message. Everything else ships the doc-refresh in the same commit as the code, not a follow-up.
