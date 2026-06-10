# Documentation Map

> Authoritative index of every document under `docs/` plus the four entry-point files at the repo root.
> Updated 2026-06-10 as part of the review-driven execution/model-matrix cleanup sweep.

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
| Status and health | [`docs/status-page.md`](status-page.md) | Current health, readiness, Actuator, Prometheus, and incident-check surfaces. Notes that a separate public status-page product is not implemented here. | When health checks, probes, metrics, or incident handling are in scope. |
| Tech-debt register | [`docs/tech-debt.md`](tech-debt.md) | Current live-risk register after the 2026-06-10 cleanup: benchmark proof, preview/verifyBoot, payment proof, tenant ORM isolation, orchestration/frontend debt, and doc drift. | When planning a hardening cleanup pass, before a big refactor, or when deciding what technical debt still matters. |

## 3. Product / UX

The user-shaped view of the system. Useful for PMs, support, and anyone writing customer-facing copy.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| PRD | [`docs/PRD.md`](PRD.md) | The product requirements doc — problem statement, target personas, must-have / should-have / won't-have feature buckets, success metrics. | When questioning whether a feature belongs in scope. |
| User manual | [`docs/USER_MANUAL.md`](USER_MANUAL.md) | Customer-facing operator's guide: how to submit a spec, what the plan gate is, how to read the validation report, how to retry a failed task, where credits go. | When writing customer-facing help text or onboarding emails. |
| Workflows | [`docs/WORKFLOWS.md`](WORKFLOWS.md) | The canonical workflow catalogue: BUILD mode, PATCH mode, the four autofix surfaces (PR Review / TechDebt / CI-failure webhook / Code Review), multi-repo runs, project chat, PR review submission. | When you need a step-by-step of a specific user workflow. |
| Feature docs index | [`docs/features/README.md`](features/README.md) | Current-state feature map replacing stale point-in-time audit/review docs. Links run execution, project workbench, models/BYOK, billing/access, and benchmarks. | When you need current product capability truth rather than a future plan. |
| Run execution feature | [`docs/features/run-execution.md`](features/run-execution.md) | Current BUILD/PATCH run pipeline, lifecycle invariants, worker/reviewer/scoring capabilities, and proof gaps. | Before changing run orchestration, worker tools, lifecycle, scoring, or run-detail behavior. |
| Project workbench feature | [`docs/features/project-workbench.md`](features/project-workbench.md) | Current project workspace, code intelligence, RAG, memory, chat, tech-debt, reviews, sharing, contracts, and standards surfaces. | Before changing project-scoped UX/API or project intelligence. |
| Models and routing feature | [`docs/features/models-and-routing.md`](features/models-and-routing.md) | Current direct-provider vs aggregator model routing, BYOK key lanes, model matrix layers, OpenRouter latency finding, and remaining route decisions. | Before changing model matrix, provider routing, BYOK, pricing, OpenRouter, Fireworks, or DeepSeek. |
| Billing and access feature | [`docs/features/billing-and-access.md`](features/billing-and-access.md) | Current tier, billing, quota, top-up, payment, PAT, sharing, and rate-limit behavior. | Before changing money, auth, roles, quotas, or org settings. |
| Benchmarks feature | [`docs/features/benchmarks.md`](features/benchmarks.md) | Current benchmark execution/tracking split, implemented harnesses, `/benchmark` dashboard, blockers, and benchmark server shape. | Before changing benchmark harnesses, event tracking, dashboard, or benchmark deployment. |
| Project user journeys | [`docs/project-user-journeys.md`](project-user-journeys.md) | Project-scoped journeys: create project, link repos, run architecture refresh, project chat, tech-debt fix, code review, share-link invite. | When designing or QA-ing a project-workspace feature. |
| User journeys | [`docs/user-journeys.md`](user-journeys.md) | Non-project journeys: anonymous landing, register, first run, upgrade, billing change, account close. | When designing or QA-ing the unauthenticated and solo-user surfaces. |
| Benchmark strategy | [`docs/plans/benchmark-strategy.md`](plans/benchmark-strategy.md) | Proposed plan for publishable PATCH (SWE-bench Lite/Verified) and BUILD (Commit0/BaxBench/DevBench) benchmarks — ranked by publishability, with the cost model and a staged rollout. | When deciding which benchmark to run, what it costs, or how to publish the result. |
| SWE-bench harness | [`docs/plans/swebench-harness.md`](plans/swebench-harness.md) | Clean-slate design + decision log for the blind SWE-bench harness (`benchmark/` package): real production pipeline, sandbox-only, blind. §12 = paper-review decisions; §13 = the as-built BUILD→PATCH pivot (RL-2026-05-028). Branch `feature/swebench-harness`. | When working on the `benchmark/` package or interpreting a SWE-bench number. |
| Terminal-Bench adapter | [`src/main/java/com/navneet/executespec/terminalbench/CLAUDE.md`](../src/main/java/com/navneet/executespec/terminalbench/CLAUDE.md) + [`docs/reason-log/2026-05.md`](reason-log/2026-05.md) RL-2026-06-004 | Operator-only Terminal-Bench 2.0 harness: ExecuteSpec's own `WorkerAgent` runs as the remote brain while the Harbor adapter in `~/tbench` executes tools as local hands inside TB containers. DeepSeek first-20 moved from 7/20 baseline to 12/20 after terminal-mode tuning; current prompt work targets `/tests` visibility and golden-benchmark misses. | When running or modifying the Terminal-Bench harness, the remote-tool protocol, or the DeepSeek first-20 benchmark plan. |
| SWE-bench exec brief | [`docs/reports/swebench-exec-brief.html`](reports/swebench-exec-brief.html) / `.pdf` | **⚠️ SUPERSEDED 2026-06-10 — numbers invalid, do not quote.** The harness didn't pin/track the worker model (LOW tasks silently ran the LIGHTWEIGHT tier); the eval is being re-run on the rebuilt harness (RL-2026-06-001). Banner added to the how-to-read companion. | Historical reference only, until the re-run lands. |
| Launch readiness (truth pass + ops) | [`docs/launch-readiness-2026-06-10.md`](launch-readiness-2026-06-10.md) | RL-2026-06-019 companion: the 2026-06-10 pricing/claims truth pass across every public surface (what changed, was→now table) + the ops checklist that can't be done from the repo (prod tier-ladder check, founder-pricing coupons, website deploy to `app.executespec.ai` CTAs, alerting/status, proof assets). | Before announcing launch; when touching any public pricing/claims surface. |
| SWE-bench eval env (GCP) | [`infra/benchmark/EVAL-ENV-PLAN.md`](../infra/benchmark/EVAL-ENV-PLAN.md) | Parallel/overnight SWE-bench eval on a stop/start GCP VM: sharded 8-way runner, GCS results bucket, one-command `eval-campaign.sh`, the validated flag config. Standalone Terraform (`infra/benchmark/`, local state — never touches prod) + `scripts/`. RL-2026-05-061. | When running the full-50/300 eval off the laptop, or standing up / tearing down the eval VM. |
| Benchmark environment (deploy + runbook) | [`docs/operations/benchmark-environment.md`](operations/benchmark-environment.md) | The **ephemeral, isolated GCP benchmark env** (VM + Cloud SQL + Redis + LB at `benchmark.executespec.ai`) tracking the `benchmark` branch cut from `main`. Full change inventory (tfvars / env-validation / `deploy.yml` wiring / `application-benchmark.yml`), stand-up + teardown runbook, the issues-and-fixes journey, and the 2026-06-09 gcloud audit. Distinct from the eval-runner box above (this is a deployed app env with UI/DB). | When standing up / tearing down the benchmark env, reproducing the cluster deploy, or wiring a new GCP environment. |
| Review handoff 2026-06-09 | [`docs/HANDOFF-2026-06-09.md`](HANDOFF-2026-06-09.md) | Handoff for the review-driven fix batch, Chinese-model/OpenRouter latency context, Fireworks gap, and benchmark branch state. | When resuming this exact 2026-06-09/10 review execution thread. |
| Review-driven execution plan 2026-06-10 | [`docs/plans/review-driven-execution-plan-2026-06-10.md`](plans/review-driven-execution-plan-2026-06-10.md) | Execution plan derived from the `review/` docs only, with the model matrix work intentionally placed at the end. | When auditing why this fix batch was ordered the way it was. |
| Review execution fix report 2026-06-10 | [`docs/plans/review-execution-fix-report-2026-06-10.md`](plans/review-execution-fix-report-2026-06-10.md) | Detailed fix report and verification ledger for the review-driven changes, including model/aggregator latency findings and remaining proof gaps. | When checking what this agent changed, what was verified, and what still needs live/sandbox validation. |
| Benchmark server creation/deployment plan 2026-06-10 | [`docs/plans/benchmark-server-creation-deployment-2026-06-10.md`](plans/benchmark-server-creation-deployment-2026-06-10.md) | Deployment plan for the dedicated benchmark server, event-ingest dashboard, harness hooks, and environment lifecycle. | When building or deploying the benchmark environment and closing the dashboard event loop. |
| System docs + cleanup plan 2026-06-10 | [`docs/plans/system-docs-and-docs-cleanup-plan-2026-06-10.md`](plans/system-docs-and-docs-cleanup-plan-2026-06-10.md) | Scoped cleanup plan for root/system docs and generated/obsolete artifacts. Preserves `review/` by instruction. | When continuing documentation cleanup without deleting review inputs. |
| Plans disposition 2026-06-10 | [`docs/plan-disposition-2026-06-10.md`](plan-disposition-2026-06-10.md) | Evaluation of every `docs/plans/*` file with keep, fold, or delete recommendations after the review-driven implementation. | Before deleting, moving, or trimming a plan. |
| _(cleared 2026-05-26)_ | — | The dated SWE-bench intermediates under `docs/benchmarks/` (Live-2026-05-24, provider-matrix, fix-plan, oracle-correction, CORRECTED-report, lite-baseline-validation, unresolved-deepdive) and `docs/reports/exec-report-2026-05-25.md` were point-in-time working artefacts with superseded/discarded (oracle-mode) numbers. Cleared to git history per the policy at the bottom of this file; the exec brief above is the canonical replacement. | `git log -- docs/benchmarks/` to recover any. |
| Change-impact guardrail | [`docs/plans/change-impact-guardrail.md`](plans/change-impact-guardrail.md) | Proposed plan to surface, at the plan-approval gate, what shared code a run will touch and who depends on it — grounded in the contract dependency graph. v1 is warn-only with an acknowledged override. | When designing the plan-gate impact warning, the steward policy, or the contract blast-radius surfaces. |
| Competitive analysis | [`docs/competitive-analysis/`](competitive-analysis/) | Reverse-engineered design analysis of **Claude Code** + **opencode** + a 3-way comparison with our harness (`COMPARISON.md`). What to adopt into our code-gen engine + CLI/VS Code clients. RL-2026-05-051. | When designing the code-gen engine extraction, the worker loop, the tool contract, multi-provider routing, or the CLI/VS Code clients. |
| Code-gen engine extraction | [`docs/plans/codegen-engine-extraction.md`](plans/codegen-engine-extraction.md) | Design + migration plan to isolate code-generation as a provider-agnostic core **engine** (`solve(spec, workspace, tools, model) → diff + verification + transcript + cost`), with the pipeline (classifier/planner/reviewer/scoring/governance) as upstream/downstream layers. Strangler-fig phases; lands harness adoptions #5/#7/#8/#9. RL-2026-05-051. | When working on the engine seam, the worker loop, or where #5/#7/#8/#9 land. |
| Web control-plane reframe | [`docs/plans/web-control-plane-reframe.md`](plans/web-control-plane-reframe.md) | Design + migration plan to reframe the **web app as a control plane** (mission control: submit → approve gate → review PR) and retire the VS Code-clone IDE chrome; the VS Code extension owns the in-editor IDE metaphor (VS Code + CLI continue in parallel). Re-shell not rewrite; strangler-fig P1–P4; flag `ui.console.enabled`. RL-2026-05-052. | When working on the web shell / dashboard / plan-gate indicator / onboarding, or the web↔VS Code↔CLI client split. |
| CLI + VS Code unified client | [`docs/adr/ADR-0001-unified-spec-client.md`](adr/ADR-0001-unified-spec-client.md) + [`docs/design/cli-vscode-unified-client-2026-05-30.md`](design/cli-vscode-unified-client-2026-05-30.md) | The decision record (ADR-0001 — the repo's first ADR), technical design (Feature Matrix + execution model + explicit in/out-of-scope) for the unified `spec` client (the standalone execution plan was removed 2026-06-09 with the `executespec-cli` split; open work in [`pending.md`](pending.md) §J1): **one shared core, two renderers (CLI + VS Code) with strict feature parity**, chat-first default *plus* retained deterministic verbs, local-hands/remote-brain execution. Amends the founder-locked [`design/cli-plugin-spec-shape-b-2026-05-28.md`](design/cli-plugin-spec-shape-b-2026-05-28.md); open CLI/VS Code work is tracked in [`docs/pending.md`](pending.md) §J, while the operator-only Terminal-Bench proof landed separately in RL-2026-06-004. Per-folder `clients/cli/{CODEMAP,ARCHITECTURE}.md` + `clients/vscode/{CODEMAP,ARCHITECTURE}.md` document each client standalone (for the planned repo split). RL-2026-05-052. | When building the CLI or VS Code client, deciding chat-vs-verbs, scoping local-hands execution, or tracking the unified-client roadmap. |

| V2 batch execution plan | [`docs/plans/v2-batch-execution-plan.md`](plans/v2-batch-execution-plan.md) | B1 batch — the `BatchClient` contract + the 3 provider clients (Anthropic/OpenAI/Gemini; P2/P3a/P3b done, unit-tested, inert) + the remaining P4 coordinator wiring + human submit-gate. ~50% LLM-cost lever. | When building/wiring V2 batch execution or adding a provider batch client. |
| Latency reduction (phased) | [`docs/plans/latency-reduction-phased.md`](plans/latency-reduction-phased.md) | Phased plan to cut HIGH-run wall-clock (36–87 min). Phase 0 quick wins (context-compaction default-ON, skip redundant per-task builds, budget re-tune); Phase 1 = parallel task execution (the RL-2026-05-006 `TaskWaveScheduler`, eval-gated, default-OFF flag). RL-2026-05-063. | When working on run latency, the executor loop, or the parallel-execution re-introduction. |
| Execution roadmap (prioritized) | [`docs/ROADMAP.md`](ROADMAP.md) | Current ordered roadmap: P0 proof gaps, P1 benchmark server, P2 preview/verifyBoot, P3 quality and latency, P4 architecture debt, P5 UX and adoption. | When deciding what to work on next or sequencing the 2026-06 work. |
| Pending items register | [`docs/pending.md`](pending.md) | Short open-work register after the 2026-06-10 root-doc refresh. Feature truth lives in `docs/features/`; this file tracks what remains unfinished. | When checking what's still owed on any track, or picking up a deferred item. |
| Preview / live-environment rebuild | [`docs/plans/preview-live-environment-rebuild.md`](plans/preview-live-environment-rebuild.md) | Verdict + execution plan to rebuild the preview *primitive* (agent-authored, verified, build-once artifact; run-same-everywhere; buildpack default) and DELETE the old heuristic path (`DevDockerfileGenerator` 3,514 LOC + the `vm` mode) as each replacement lands. First-class security model (egress deny-by-default, authenticated previews, least-privilege SA, supply-chain pinning, mobile/EAS token scoping) + web/mobile target matrix. Mobile = Expo/EAS + device clouds, never self-hosted simulators. RL-2026-05-066. | When working on preview/deploy, the agent run-and-observe loop, or planning the preview rebuild + old-code cleanup. |
| Preview Phase 0/1 verifyBoot spec | [`docs/plans/preview-phase1-verifyboot-spec.md`](plans/preview-phase1-verifyboot-spec.md) | Code-level implementation spec for the preview keystone — `verifyBoot` (agent run-recipe + in-loop boot/health-check feeding the BUILD score). Mirrors the `smoketest/` seam; pure core `BootHealthEvaluator` already built + validated. Tool/runner/scoring/flag/migration wiring + security + build order. RL-2026-05-066. | When implementing the verifyBoot keystone under a compiler. |
| Tenant ORM isolation (C3) | [`docs/plans/c3-tenant-orm-isolation.md`](plans/c3-tenant-orm-isolation.md) | Handoff for the deferred Hibernate `@Filter` tenant read-isolation: approach + the 5 exemption landmines (SUPER_ADMIN / share-links / cross-org members / async threads / native queries) + flag-gated rollout. C2 covers the hard requirement. | Before a tenant ORM-isolation pass. |
| A1 worker-lever flag eval | [`docs/operations/a1-flag-eval-2026-05-29.md`](operations/a1-flag-eval-2026-05-29.md) | The all-5-worker-flags OFF vs ON × 3-provider eval: flags-ON = same-or-better verifiedScore at −9% to −30% cost. N=1 caveats + the "widen to N≥3 across LOW/MED/HIGH before any prod flip" recommendation. | When deciding whether to flip the worker levers (tool-compactor / history-compaction / build-tdd / provider-prompt-tuning / cross-provider-fallback). |

## 4. Auth + Enterprise

The plumbing that gates everything, plus the contracts that wrap an enterprise sale.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| RBAC | [`docs/RBAC.md`](RBAC.md) | The seven roles, the access-guard ladder, `AccessGuardCoverageTest` pin, cross-org `ProjectMember` semantics, the V198 per-org `org.external_sharing_enabled` flag, the legacy-drift ledger. | Before adding a new controller method or relaxing an existing guard. |
| SSO / SAML wiring | [`docs/sso-saml-wiring.md`](sso-saml-wiring.md) | Current SSO config CRUD and the explicit SAML boundary: SAML providers can be stored, but SAML login/enforcement is not implemented. | Before changing SSO config, making SAML claims, or debugging org SSO settings. |
| DPA / DSAR flow | [`docs/dpa-flow.md`](dpa-flow.md) | Current personal export, DSAR JSON export, sub-processor, retention, and erasure-boundary reference. | When answering privacy/export questions or designing account deletion. |
| Enterprise one-pager | [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) | Engineering-truth enterprise capability summary: what ENTERPRISE gets, what it does not yet get, and the model/key policy. | When drafting enterprise copy or checking if a claim is real. |
| Enterprise SLA | [`docs/enterprise-sla.md`](enterprise-sla.md) | Code-backed SLOs vs commercial-only SLA terms, availability boundaries, recovery posture, and incident flow. | Before signing, negotiating, or claiming an enterprise SLA. |
| Cookie/session model | [`docs/cookie-domain-switch.md`](cookie-domain-switch.md) | Current access JWT, refresh cookie, OAuth redirect cookie, PAT, SSE token, CORS, and session invalidation behavior. | When auth works in one environment but not another. |

## 5. Billing

Two operational guides for the money plumbing. Both are short by design.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Payment history | [`docs/payment-history.md`](payment-history.md) | Current payment audit, webhook-event, subscription, invoice, and top-up reconciliation map. | When reconciling invoices, webhooks, top-ups, or missing payment events. |
| Stripe price-ID ops | [`docs/stripe-price-ids-ops.md`](stripe-price-ids-ops.md) | Current Stripe price ID env vars and resolution order, including `STRIPE_TEAM_PRICE_ID` and DB-column precedence. | Before creating, retiring, or rotating a Stripe price. |

## 6. Catalog formats

Two YAML/Markdown catalogue schemas that ship with the product. Both are loaded at boot and validated.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Readymades catalog format | [`docs/readymades-catalog-format.md`](readymades-catalog-format.md) | Current YAML schema, seeder behavior, public API, tier gating, and maintenance rules for readymade solutions. | When adding, retiring, reseeding, or debugging a readymade. |
| Migration recipes catalog format | [`docs/migration-recipes-catalog-format.md`](migration-recipes-catalog-format.md) | Current YAML schema, seeder behavior, dry-run/apply API, tier gating, and maintenance rules for migration recipes. | When adding, retiring, reseeding, or debugging a migration recipe. |

## 7. Frontend

The frontend has its own conventions because the IDE-first protected app, the opt-in Console shell, and the surviving public Signal pages share stores, tokens, and API clients. Read all three before touching `frontend/src/`.

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Frontend API usage | [`docs/frontend-api-usage.md`](frontend-api-usage.md) | Current `frontend/src/lib/api/` domain-module layout, request contract, SSE rules, and endpoint-addition checklist. | Before adding or changing frontend API calls. |
| Code style | [`docs/design/code-style.md`](design/code-style.md) | Frontend code-style commitments — file layout, naming, prop-shape conventions, when to reach for a hook vs a store, mobile-first rules. | Before writing a new React component. |
| UI tokens | [`docs/design/ui-tokens.md`](design/ui-tokens.md) | The design-token surface — Tailwind tokens, color ramps, spacing scale, radius scale, the V2 vs V3 chrome differences, a11y minima. | Before introducing a new color, spacing, or radius. |

## 8. API surface

| Doc | Path | What | When to read |
| --- | --- | --- | --- |
| Javadoc | [`docs/JAVADOC.md`](JAVADOC.md) | Compact current backend API/package reference, replacing the stale generated-style file. It names the important packages, contracts, and code paths; use `CODEMAP.md` plus `rg` for exhaustive lookup. | When you need a high-level backend surface map or a starting point for class-level search. |

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
| Look up a class signature | `docs/JAVADOC.md` | `docs/CODEMAP.md`, then `rg` in `src/main/java` |
| Understand why a change was made / what's in flight | `docs/reason-log/2026-05.md` (newest-first decision log) | per-package `CLAUDE.md` |

---

## Documents intentionally omitted from this map

- **Deleted audit/review working docs** are point-in-time artefacts. They are kept in git history for traceability but are not re-indexed here once action items are merged or moved into `review/`, `docs/pending.md`, `docs/tech-debt.md`, or canonical system docs.
- **`docs/reason-log/2026-05.md`** is the dated engineering decision log. Volatile by design (newest-first, immutable `RL-YYYY-MM-NNN` ids), it lives outside this map so the map can stay stable across PRs.
- **`docs/reports/*`** (model-mapping audits, matrix-tracking dumps) is tooling output, not curated docs.

If you need any of those, look in git history — they were last touched on the audit-review branches.
