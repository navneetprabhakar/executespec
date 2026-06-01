# Documentation Map

> Authoritative index of every document under `docs/` plus the four entry-point files at the repo root.
> Updated 2026-05-16 as part of the `feature/full-project-review-2026-05-11` doc-refresh sweep.

This file is the routing table for the documentation surface. Each entry below has a one-line description of what the document covers and a note on when you should reach for it. If you don't know which file to open first, start here.

## A note on CLAUDE.md vs. docs/

`CLAUDE.md` files live at the repository root and (historically) at every per-package directory under `src/main/java/com/navneet/executespec/`. They are **code-adjacent context for AI coding assistants** — written for Claude Code session bootstrapping, not for human onboarding. They are intentionally terse, contain in-flight-status notes, may carry preferences or rules-of-engagement, and may drift between commits because they're maintained as part of feature work rather than as standalone deliverables.

Treat the files under `/docs/` as authoritative for code-truth. If a `CLAUDE.md` and a `/docs/` file disagree, the `/docs/` file wins, and the `CLAUDE.md` is the bug — file the drift and move on.

The same rule applies the other direction: if `/docs/` is silent on a topic and a `CLAUDE.md` covers it, that's a documentation gap to file — not an excuse to leave the `CLAUDE.md` as the only source. Promote the content into `/docs/`, then trim the `CLAUDE.md` reference.

For humans: the documents below are the contract.
For LLM coding agents: read the relevant `CLAUDE.md` for session-bootstrap context, then come here for ground-truth on anything older than a week.

---

## 1. Entry points

The four files anyone (human or LLM) should be able to find in under thirty seconds.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| README | [`README.md`](../README.md) | One-page overview: what ExecuteSpec does, who it's for, how to run it locally, where the docs live. | First file anyone opens on the repo. |
| Architecture | [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | The 30-minute orientation: V2 pipeline diagram, package layout, async boundaries, data flows, resilience levers. | When you need to understand *how* the system executes a run end-to-end. |
| Codemap | [`docs/CODEMAP.md`](CODEMAP.md) | Per-package index — every top-level `com.navneet.executespec.*` package with its responsibilities, key classes, and migration trail. | When you need to locate code by responsibility ("where does cost tracking live?"). |
| Document map | [`docs/document_map.md`](document_map.md) | This file. Routing table for every document. | When you don't know which `/docs/` file to open. |

## 2. Operational

Everything you need to ship, run, and stay-up-to-the-SLO. Read in order on a first deploy.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Deployment | [`docs/DEPLOYMENT.md`](DEPLOYMENT.md) | How the prod + dev GCE VM is provisioned, how Cloud Run on-demand previews ship, Cloudflare in front of `*.executespec.ai`, secrets layout, rollback procedure. | Before any change that touches infra, env vars, or the deploy pipeline. |
| Local test playbook | [`docs/operations/local-test-playbook.md`](operations/local-test-playbook.md) | The local smoke-test recipe: Flyway migrate against a clean Postgres, Spring Boot startup, frontend `npm run dev`, end-to-end run submission, expected timing budgets. | Before pushing to `dev` — the rule is local-first, CI is not authoritative. |
| Service-level objectives | [`docs/operations/slos.md`](operations/slos.md) | The 5 published SLOs: terminal-in-15-min, verified-score correctness, cost-estimate accuracy, webhook latency, hot-path 5xx. With measurement methodology and on-call escalation. | When auditing reliability or pricing a customer SLA. |
| Cost optimization 2026-05-29 | [`docs/operations/cost-optimization-2026-05-29.md`](operations/cost-optimization-2026-05-29.md) | Handoff: dev-environment cost audit that found Cloud SQL + VM provisioned 4× over utilization. What was resized/deleted (Cloud SQL `db-custom-4-16384`→`db-custom-1-3840`, `preview-gw` VM + IP, `dev-workspaces` bucket → us-east4) and runbooks for the remaining `dev-deploy` + `tfstate` bucket migrations. Saved ~$158/mo. | When auditing GCP spend, planning further right-sizing, or completing the deferred bucket migrations. |
| Status page | [`docs/status-page.md`](status-page.md) | How the public status page is wired, what each surfaced metric means, where the cron + uptime probes live, how an incident is declared and resolved. | When the status page is wrong or when adding a new probe. |
| Tech-debt register | [`docs/tech-debt.md`](tech-debt.md) | Severity-ranked backend + frontend debt audit (god classes, the unauthenticated `/api/v2/test/**` surface, the un-split frontend bundle, the missing frontend test harness, dead Signal-era code). Living doc — strike items as paid down. | When planning a hardening / cleanup pass, or before a big refactor. Last audited 2026-05-24. |

## 3. Product / UX

The user-shaped view of the system. Useful for PMs, support, and anyone writing customer-facing copy.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| PRD | [`docs/PRD.md`](PRD.md) | The product requirements doc — problem statement, target personas, must-have / should-have / won't-have feature buckets, success metrics. | When questioning whether a feature belongs in scope. |
| User manual | [`docs/USER_MANUAL.md`](USER_MANUAL.md) | Customer-facing operator's guide: how to submit a spec, what the plan gate is, how to read the validation report, how to retry a failed task, where credits go. | When writing customer-facing help text or onboarding emails. |
| Workflows | [`docs/WORKFLOWS.md`](WORKFLOWS.md) | The canonical workflow catalogue: BUILD mode, PATCH mode, the four autofix surfaces (PR Review / TechDebt / CI-failure webhook / Code Review), multi-repo runs, project chat, PR review submission. | When you need a step-by-step of a specific user workflow. |
| Project user journeys | [`docs/project-user-journeys.md`](project-user-journeys.md) | Project-scoped journeys: create project, link repos, run architecture refresh, project chat, tech-debt fix, code review, share-link invite. | When designing or QA-ing a project-workspace feature. |
| User journeys | [`docs/user-journeys.md`](user-journeys.md) | Non-project journeys: anonymous landing, register, first run, upgrade, billing change, account close. | When designing or QA-ing the unauthenticated and solo-user surfaces. |
| Benchmark strategy | [`docs/plans/benchmark-strategy.md`](plans/benchmark-strategy.md) | Proposed plan for publishable PATCH (SWE-bench Lite/Verified) and BUILD (Commit0/BaxBench/DevBench) benchmarks — ranked by publishability, with the cost model and a staged rollout. | When deciding which benchmark to run, what it costs, or how to publish the result. |
| SWE-bench harness | [`docs/plans/swebench-harness.md`](plans/swebench-harness.md) | Clean-slate design + decision log for the blind SWE-bench harness (`benchmark/` package): real production pipeline, sandbox-only, blind. §12 = paper-review decisions; §13 = the as-built BUILD→PATCH pivot (RL-2026-05-028). Branch `feature/swebench-harness`. | When working on the `benchmark/` package or interpreting a SWE-bench number. |
| SWE-bench exec brief | [`docs/reports/swebench-exec-brief.html`](reports/swebench-exec-brief.html) / `.pdf` | **Canonical SWE-bench result of record.** Blind stratified-30 × 3 providers, scored by the official `swebench` harness: Anthropic 17/29 (59%), OpenAI 15/29 (52%) vs ~48% Lite SOTA; raw model alone 1–7/29 → the scaffold's lift quantified. Per-instance test-case table + raw-model results table. | When quoting any SWE-bench number, citing the lift to an advisor, or comparing providers. |
| SWE-bench eval env (GCP) | [`infra/benchmark/EVAL-ENV-PLAN.md`](../infra/benchmark/EVAL-ENV-PLAN.md) | Parallel/overnight SWE-bench eval on a stop/start GCP VM: sharded 8-way runner, GCS results bucket, one-command `eval-campaign.sh`, the validated flag config. Standalone Terraform (`infra/benchmark/`, local state — never touches prod) + `scripts/`. RL-2026-05-061. | When running the full-50/300 eval off the laptop, or standing up / tearing down the eval VM. |
| SWE-bench eval handoff (2026-06-01) | [`docs/handoff/2026-06-01-eval-gpt-crack.md`](handoff/2026-06-01-eval-gpt-crack.md) | Point-in-time handoff for the GPT-5.4 crack (symptom-anchored VERIFY lever, gap 0/11→4/11, ~3 reliable on re-run, 0 regression) + the owed full-50 net gate + the prioritized next-improvements backlog & kickoff prompt. Transient — RL-2026-05-061 is the durable record. | When resuming the SWE-bench eval-improvement work. |
| _(cleared 2026-05-26)_ | — | The dated SWE-bench intermediates under `docs/benchmarks/` (Live-2026-05-24, provider-matrix, fix-plan, oracle-correction, CORRECTED-report, lite-baseline-validation, unresolved-deepdive) and `docs/reports/exec-report-2026-05-25.md` were point-in-time working artefacts with superseded/discarded (oracle-mode) numbers. Cleared to git history per the policy at the bottom of this file; the exec brief above is the canonical replacement. | `git log -- docs/benchmarks/` to recover any. |
| Change-impact guardrail | [`docs/plans/change-impact-guardrail.md`](plans/change-impact-guardrail.md) | Proposed plan to surface, at the plan-approval gate, what shared code a run will touch and who depends on it — grounded in the contract dependency graph. v1 is warn-only with an acknowledged override. | When designing the plan-gate impact warning, the steward policy, or the contract blast-radius surfaces. |
| Competitive analysis | [`docs/competitive-analysis/`](competitive-analysis/) | Reverse-engineered design analysis of **Claude Code** + **opencode** + a 3-way comparison with our harness (`COMPARISON.md`). What to adopt into our code-gen engine + CLI/VS Code clients. RL-2026-05-051. | When designing the code-gen engine extraction, the worker loop, the tool contract, multi-provider routing, or the CLI/VS Code clients. |
| Code-gen engine extraction | [`docs/plans/codegen-engine-extraction.md`](plans/codegen-engine-extraction.md) | Design + migration plan to isolate code-generation as a provider-agnostic core **engine** (`solve(spec, workspace, tools, model) → diff + verification + transcript + cost`), with the pipeline (classifier/planner/reviewer/scoring/governance) as upstream/downstream layers. Strangler-fig phases; lands harness adoptions #5/#7/#8/#9. RL-2026-05-051. | When working on the engine seam, the worker loop, or where #5/#7/#8/#9 land. |
| Web control-plane reframe | [`docs/plans/web-control-plane-reframe.md`](plans/web-control-plane-reframe.md) | Design + migration plan to reframe the **web app as a control plane** (mission control: submit → approve gate → review PR) and retire the VS Code-clone IDE chrome; the VS Code extension owns the in-editor IDE metaphor (VS Code + CLI continue in parallel). Re-shell not rewrite; strangler-fig P1–P4; flag `ui.console.enabled`. RL-2026-05-052. | When working on the web shell / dashboard / plan-gate indicator / onboarding, or the web↔VS Code↔CLI client split. |
| CLI + VS Code unified client | [`docs/adr/ADR-0001-unified-spec-client.md`](adr/ADR-0001-unified-spec-client.md) + [`docs/design/cli-vscode-unified-client-2026-05-30.md`](design/cli-vscode-unified-client-2026-05-30.md) + [`docs/plans/cli-vscode-unified-client-execution-2026-05-30.md`](plans/cli-vscode-unified-client-execution-2026-05-30.md) | The decision record (ADR-0001 — the repo's first ADR), technical design (Feature Matrix + execution model + explicit in/out-of-scope), and phased execution plan (positive/negative scenario matrices, test plan, risk register, rollout) for the unified `spec` client: **one shared core, two renderers (CLI + VS Code) with strict feature parity**, chat-first default *plus* retained deterministic verbs, local-hands/remote-brain execution. Amends the founder-locked [`design/cli-plugin-spec-shape-b-2026-05-28.md`](design/cli-plugin-spec-shape-b-2026-05-28.md); see also the [CLI/Terminal-Bench handoff](plans/cli-and-terminal-bench-handoff-2026-05-29.md). Per-folder `clients/cli/{CODEMAP,ARCHITECTURE}.md` + `clients/vscode/{CODEMAP,ARCHITECTURE}.md` document each client standalone (for the planned repo split). RL-2026-05-052. | When building the CLI or VS Code client, deciding chat-vs-verbs, scoping local-hands execution, or tracking the unified-client roadmap. |

| Audit remediation 2026-05-29 | [`docs/audit/REMEDIATION-2026-05-29.md`](audit/REMEDIATION-2026-05-29.md) + [`docs/plans/audit-deferred-handoff-2026-05-30.md`](plans/audit-deferred-handoff-2026-05-30.md) | The no-bias full-codebase audit-remediation changelog (shipped + deferred + audit corrections) and the handoff index for every deferred item (RunOrchestrator split, B1 batch wiring, C3, B3, D-residual). | When tracking what the 2026-05-29 audit fixed vs deferred, or picking up a deferred item. |
| RunOrchestrator split plan | [`docs/plans/runorchestrator-god-class-split.md`](plans/runorchestrator-god-class-split.md) | Concrete 6-increment by-hand plan to split the 5.3k-LOC `RunOrchestrator` god class (automated extraction proved unsafe — corrupted the file twice). Invariants to preserve + per-increment full-test protocol. | Before attempting the RunOrchestrator refactor. |
| V2 batch execution plan | [`docs/plans/v2-batch-execution-plan.md`](plans/v2-batch-execution-plan.md) | B1 batch — the `BatchClient` contract + the 3 provider clients (Anthropic/OpenAI/Gemini; P2/P3a/P3b done, unit-tested, inert) + the remaining P4 coordinator wiring + human submit-gate. ~50% LLM-cost lever. | When building/wiring V2 batch execution or adding a provider batch client. |
| Latency reduction (phased) | [`docs/plans/latency-reduction-phased.md`](plans/latency-reduction-phased.md) | Phased plan to cut HIGH-run wall-clock (36–87 min). Phase 0 quick wins (context-compaction default-ON, skip redundant per-task builds, budget re-tune); Phase 1 = parallel task execution (the RL-2026-05-006 `TaskWaveScheduler`, eval-gated, default-OFF flag). RL-2026-05-063. | When working on run latency, the executor loop, or the parallel-execution re-introduction. |
| Tenant ORM isolation (C3) | [`docs/plans/c3-tenant-orm-isolation.md`](plans/c3-tenant-orm-isolation.md) | Handoff for the deferred Hibernate `@Filter` tenant read-isolation: approach + the 5 exemption landmines (SUPER_ADMIN / share-links / cross-org members / async threads / native queries) + flag-gated rollout. C2 covers the hard requirement. | Before a tenant ORM-isolation pass. |
| A1 worker-lever flag eval | [`docs/operations/a1-flag-eval-2026-05-29.md`](operations/a1-flag-eval-2026-05-29.md) | The all-5-worker-flags OFF vs ON × 3-provider eval: flags-ON = same-or-better verifiedScore at −9% to −30% cost. N=1 caveats + the "widen to N≥3 across LOW/MED/HIGH before any prod flip" recommendation. | When deciding whether to flip the worker levers (tool-compactor / history-compaction / build-tdd / provider-prompt-tuning / cross-provider-fallback). |

## 4. Auth + Enterprise

The plumbing that gates everything, plus the contracts that wrap an enterprise sale.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| RBAC | [`docs/RBAC.md`](RBAC.md) | The seven roles, the access-guard ladder, `AccessGuardCoverageTest` pin, cross-org `ProjectMember` semantics, the V198 per-org `org.external_sharing_enabled` flag, the legacy-drift ledger. | Before adding a new controller method or relaxing an existing guard. |
| SSO / SAML wiring | [`docs/sso-saml-wiring.md`](sso-saml-wiring.md) | How enterprise customers connect Okta / Azure AD / Google Workspace, IdP-initiated vs SP-initiated flow, JIT provisioning, and how SAML coexists with the OAuth2 (Google + GitHub) surface. | When wiring a new SSO customer or debugging a SAML callback. |
| DPA flow | [`docs/dpa-flow.md`](dpa-flow.md) | The data-processing-agreement signing flow, what's in the DPA, who can countersign, where the signed copy lands, retention terms. | When an enterprise prospect asks for a DPA. |
| Enterprise one-pager | [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) | The single-page enterprise pitch — what's in the tier, what's not, what the customer gets at signing, BYOK cost-cap exemption, dedicated support hours. | When sending the first enterprise reply or building a quote. |
| Enterprise SLA | [`docs/enterprise-sla.md`](enterprise-sla.md) | The contractual SLA: uptime, support hours, response targets, credit-back math, force-majeure carve-outs. Numbers must match `operations/slos.md`. | Before signing or negotiating an enterprise contract. |
| Cookie domain switch | [`docs/cookie-domain-switch.md`](cookie-domain-switch.md) | How the JWT + refresh cookie domain flips between `localhost` (dev), `dev.executespec.ai` (staging), and `executespec.ai` (prod), and what breaks when it's wrong. | When auth works in one environment but not another. |

## 5. Billing

Two operational guides for the money plumbing. Both are short by design.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Payment history | [`docs/payment-history.md`](payment-history.md) | The org-scoped payment-history surface — what columns exist, how it joins Razorpay and Stripe events, what's queryable from `/api/billing/payment-history`. | When reconciling invoices or debugging a missing payment. |
| Stripe price-ID ops | [`docs/stripe-price-ids-ops.md`](stripe-price-ids-ops.md) | The operational mapping between Stripe price IDs (`STRIPE_TEAM_MONTHLY_PRICE_ID`, `STRIPE_ENTERPRISE_PRICE_ID`, etc.) and the in-app pricing rows, plus how to rotate a price without breaking renewals. | Before creating, retiring, or rotating a Stripe price. |

## 6. Catalog formats

Two YAML/Markdown catalogue schemas that ship with the product. Both are loaded at boot and validated.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Readymades catalog format | [`docs/readymades-catalog-format.md`](readymades-catalog-format.md) | The on-disk schema for the "readymade" spec templates — YAML front matter, required keys, validation rules, how the loader resolves them. | When adding a new readymade or fixing one that won't load. |
| Migration recipes catalog format | [`docs/migration-recipes-catalog-format.md`](migration-recipes-catalog-format.md) | The on-disk schema for migration-recipe spec templates — separate from readymades, used for guided framework / library migrations. | When adding a migration recipe (Spring Boot 2 to 3, JUnit 4 to 5, etc.). |

## 7. Frontend

The frontend has its own conventions because the two coexisting chromes (V2 sidebar shell, V3 top-bar shell) share Zustand stores. Read all three before touching `frontend/src/`.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Frontend API usage | [`docs/frontend-api-usage.md`](frontend-api-usage.md) | The frontend's API client conventions — where `frontend/src/lib/api.ts` lives, how SSE tokens are minted, how the two chrome surfaces share Zustand stores, error-boundary patterns. | Before adding a new endpoint call from the frontend. |
| Code style | [`docs/design/code-style.md`](design/code-style.md) | Frontend code-style commitments — file layout, naming, prop-shape conventions, when to reach for a hook vs a store, mobile-first rules. | Before writing a new React component. |
| UI tokens | [`docs/design/ui-tokens.md`](design/ui-tokens.md) | The design-token surface — Tailwind tokens, color ramps, spacing scale, radius scale, the V2 vs V3 chrome differences, a11y minima. | Before introducing a new color, spacing, or radius. |

## 8. API surface

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Javadoc | [`docs/JAVADOC.md`](JAVADOC.md) | The generated-style API reference — every public class with package, role, key methods. **Known stale** since v2.4.0 (2026-04-14); subsequent deltas live in `ARCHITECTURE.md`, `CODEMAP.md`, and per-package `CLAUDE.md`. Rewriting `JAVADOC.md` is queued but not blocking. | When you need a class-level signature reference. Cross-check against `ARCHITECTURE.md` and `CODEMAP.md` for anything younger than v2.4.0. |

---

## Common starting points by task

Use this as a cheat-sheet when you know what you're trying to do but not which doc to open. All paths are relative to the repo root.

| You're trying to... | Start here | Then read |
| --- | --- | --- |
| Run the app locally for the first time | `README.md` | `docs/operations/local-test-playbook.md` |
| Understand the V2 pipeline before touching `agent/` or `harness/` | `docs/ARCHITECTURE.md` | `docs/CODEMAP.md` (for the relevant package) |
| Add a new REST endpoint | `docs/RBAC.md` | `docs/frontend-api-usage.md` |
| Add a new frontend screen | `docs/design/code-style.md` | `docs/design/ui-tokens.md`, `docs/frontend-api-usage.md` |
| Wire a new SSO customer | `docs/sso-saml-wiring.md` | `docs/RBAC.md`, `docs/cookie-domain-switch.md` |
| Quote or contract an enterprise sale | `docs/enterprise-one-pager.md` | `docs/enterprise-sla.md`, `docs/dpa-flow.md` |
| Debug a billing event | `docs/payment-history.md` | `docs/stripe-price-ids-ops.md` |
| Add a readymade or migration recipe | `docs/readymades-catalog-format.md` or `docs/migration-recipes-catalog-format.md` | — |
| Ship to prod or rollback | `docs/DEPLOYMENT.md` | `docs/operations/slos.md`, `docs/status-page.md` |
| Look up a class signature | `docs/JAVADOC.md` (caveat: stale post-v2.4.0) | `docs/CODEMAP.md`, then per-package `CLAUDE.md` |
| Understand why a change was made / what's in flight | `docs/reason-log/2026-05.md` (newest-first decision log) | per-package `CLAUDE.md` |

---

## Documents intentionally omitted from this map

- **Audit reports** under `docs/audits/` (line-by-line reviews, migration-and-library verification dumps) and **dated UX assessments** under `docs/design/` (mobile-UX audits, redesign drafts) are point-in-time artefacts. They are kept in git history for traceability but are not re-indexed here once the action items are merged.
- **`docs/reason-log/2026-05.md`** is the operational truth for in-flight tracks + dated engineering decisions (the retired `docs/plans/pending.md` was folded into it). Volatile by design (newest-first, immutable `RL-YYYY-MM-NNN` ids), it lives outside this map so the map can stay stable across PRs.
- **`docs/reports/*`** (model-mapping audits, matrix-tracking dumps) is tooling output, not curated docs.

If you need any of those, look in git history — they were last touched on the audit-review branches.
