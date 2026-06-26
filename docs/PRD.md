# ExecuteSpec PRD

> Current-state product requirements for `1.0.0-M1`, refreshed 2026-06-19 from code, migrations, and public surfaces. This is an engineering PRD, not launch copy.

## Product Statement

ExecuteSpec is a spec-driven coding platform. A user writes a natural-language requirement, the system creates a task plan, pauses for plan and cost approval, executes the work against one or more repositories, validates the result, and opens or updates a pull request with a deterministic verified score.

The product promise is: **delegate a bounded software change and get back a reviewable, test-backed PR with cost, traceability, and rollback boundaries.**

The longer-term product vision is a combined software foundry: one agentic
control plane for building, deploying, governing, observing, remediating, and
operating software and data workflows end to end.

## Primary Users

| Persona | Goal |
| --- | --- |
| Solo developer | Turn a small product/change request into a PR without hand-driving every file edit. |
| Team developer | Run project-aware changes across one or more linked repos with architecture, docs, memory, and review context. |
| Org admin | Manage seats, BYOK keys, provider defaults, org settings, external sharing, and billing. |
| Super admin/operator | Run benchmark/eval surfaces, feature flags, payment audits, and environment checks. |

## Current Product Surface

| Area | Status | Current doc |
| --- | --- | --- |
| Run execution | Live | [`features/run-execution.md`](features/run-execution.md) |
| Project workbench | Live with flagged enterprise/governance surfaces | [`features/project-workbench.md`](features/project-workbench.md) |
| Models, routing, BYOK | Live; direct DeepSeek and Fireworks lanes are present and need continued live-route proof | [`features/models-and-routing.md`](features/models-and-routing.md) |
| Billing, access, quota | Live; payment flows still need stronger sandbox proof | [`features/billing-and-access.md`](features/billing-and-access.md) |
| Benchmarks | Operator/dev harnesses and tracking exist; official/publishable claims remain gated by external scorer artifacts | [`features/benchmarks.md`](features/benchmarks.md) |
| CLI / VS Code agent sessions | Terminal CLI released and working; server protocol live; standalone clients live outside this repo | [`features/run-execution.md`](features/run-execution.md) |

## Core Requirements

### R1. Spec To Plan

- Accept validated specs through `POST /api/runs`.
- Stamp `createdByUserId`, `orgId`, and `projectId` before async execution starts.
- Support project-linked multi-repo context through `selectedRepoIds`.
- Classify complexity and create a task plan.
- Pause production runs at `AWAITING_APPROVAL`.
- Show estimated cost and required credits before approval.
- Charge run credits only on approval and refund failed runs.

### R2. Plan To Code

- Execute through `SequentialExecutor` and `WorkerAgent`.
- Route worker model by task complexity through `ModelResolver`.
- Execute file, shell, build, test, git, and completion tools through the tool registry, sandbox, and abuse scanners.
- Support server workspaces and local-hands workspaces. Local-hands requires PAT auth and proxies worker tool calls to the CLI/VS Code client.
- Keep run/task status durable enough for run detail, event replay, and recovery.
- Never let late pipeline writes overwrite `CANCELLED` or `PAUSED`.

### R3. Code To Verified Result

- Run reviewer passes and final build/test checks where available.
- Compute `verifiedScore` through the additive scoring model, then apply trust caps when build, tests, reviewer, coverage, or critical-issue evidence makes a high headline indefensible.
- Expose `verificationEvidence[]` on validation reports so users can distinguish deterministic checks, LLM reviewer/assessment signals, hybrid calculations, and internal score summaries.
- Persist validation reports and issues.
- Show cost, tokens, reasoning tokens, events, tasks, files, and PR status in run detail.

### R4. Project Context

- Keep project repos, codemaps, architecture docs, generated docs, uploaded docs, RAG chunks, project memory, and activity tied to a project.
- Allow project chat to inspect architecture, docs, memory, repos, runs, PRs, tech debt, and code search.
- Allow PR review, code review, tech-debt fix, and CI-failure autofix surfaces to dispatch PATCH runs.
- Keep cross-org project access scoped by `ProjectMember` role and host-org entitlement.

### R5. Model Choice

- Direct providers expose only models they directly support: Anthropic, OpenAI, Gemini, DeepSeek.
- Aggregators expose curated model choices: OpenRouter and Fireworks.
- System defaults live in `application.yml`.
- Account-scoped provider preference lives on `app_user.preferred_ai_provider`.
- Enterprise org admins may override org matrix cells.
- BYOK users may use their own keys where their tier allows.
- FREE users receive the configured free-tier default only; no model matrix.

### R6. Commercial And Admin Controls

- Tier gates must be enforced server-side, not only in UI.
- FREE supports 5 monthly 1-credit runs with a $3 per-run platform cap.
- PRO supports 30 monthly managed-key runs at $29/month or $289/year, with a $5 per-run platform cap unless BYOK is configured.
- TEAM supports 1-20 self-serve seats at $39/seat/month, BYOK, unlimited runs against the customer's provider quota, and no platform per-run cap.
- ENTERPRISE supports custom contract terms, org model matrix overrides, aggregator/provider org keys, and expanded admin controls.
- Payment, quota, top-up, and refund paths must be auditable and tenant-scoped.
- External project sharing requires the org flag plus role/tier checks.

## Expansion Product Lines

These lines are roadmap scope, not current live product claims.

### E1. Data Pipeline Builder

- Build pipelines across SQL, NoSQL, data warehouse, lakehouse, HDFS, Apache
  Iceberg, Databricks, Snowflake, and unstructured sources.
- Own the compute/orchestration layer; keep customer source and destination
  storage customer-owned by default.
- Offer a managed or partner-backed destination only as an explicitly priced
  add-on.
- Provide connector configuration, schema discovery, profiling, transformation
  planning, execution, validation, lineage, drift detection, and governance.
- Add an analysis product on top so users can slice, dice, visualize, and inspect
  processed data.

### E2. DevOps, Deployment, And Governance Agent

- Provide a terminal/web agent for Terraform, Docker, deployments, release
  workflows, environment drift, and governance.
- Package it as a distinct product line inside the current ExecuteSpec platform.
- Reuse current primitives: terminal/local-hands execution, standards,
  contracts, previews, deployment flows, project context, PR creation, and
  evidence ledger.
- Require approval boundaries for destructive infrastructure or deployment
  actions.

### E3. Virtual SRE

- Integrate with observability, monitoring, alerting, logs, metrics, and traces.
- Detect, cluster, and triage incidents with evidence and blast-radius context.
- Raise issues from confirmed findings.
- Route remediation work back into ExecuteSpec Core so the output is a reviewable
  PR, not an unaudited production mutation.
- Produce post-incident summaries and recurring-issue tracking.

## Tier Shape

| Tier | Current intent | Key constraints |
| --- | --- | --- |
| FREE | Trial/evaluation tier | 5 runs/month, small 1-credit specs only, fixed model default, $3/run cap, 1 project, 1 repo. |
| PRO | Solo paid tier | $29/month or $289/year, 30 managed runs/month, 2 projects, 2 repos/project, PR/code review quotas, $5/run cap, BYOK lifts cap. |
| TEAM | Self-serve team tier | $39/seat/month, 1-20 seats, BYOK, unlimited runs against provider quota, no platform per-run markup, shared org controls. |
| ENTERPRISE | Contract tier | Custom pricing, unlimited policy subject to contract/safety, org model matrix, OpenRouter/Fireworks/deep provider lanes, DPA/SLA/security review path. |

## Success Metrics

| Metric | Why it matters |
| --- | --- |
| Plan approval rate | Measures whether users trust the generated plan and cost estimate. |
| Run terminal rate within 15 minutes | Maps to the published run-latency SLO. |
| PR opened or updated rate | Measures whether the product produces reviewable output, not just agent activity. |
| Verified-score trust accuracy | Measures whether the headline score matches actual review/build/test reality. |
| Cost estimate error | Protects managed-key gross margin and buyer trust. |
| First successful PR by new account | Activation event that matters more than signup. |
| Paid conversion by tier | Validates Free->Pro and Pro/Team upgrade boundaries. |

## Non-Goals

- Do not expose every aggregator catalog model as product UI.
- Do not claim official benchmark results without official harness/scorer evidence.
- Do not turn the web app back into the primary coding IDE; the web app is the control plane.
- Do not rely on point-in-time audit/review docs as current product truth.
- Do not promise SOC 2, SAML enforcement, dedicated region, or physical tenant isolation until those are implemented and proven.
- Do not claim the Data Pipeline Builder, DevOps/Governance Agent, or Virtual SRE as shipped product lines until their PRDs, integrations, and runtime paths exist.

## Proof Gaps

The product has substantial code coverage and local compile/test proof, but these claims still need live proof before stronger investor/customer language:

- Real Stripe/Razorpay sandbox checkout, abandonment, webhook ordering, top-up-to-invoice path.
- VM-side latency and reliability A/B across OpenRouter, Fireworks, and native DeepSeek.
- Continued end-to-end regression evidence for the released terminal CLI against this server's local-hands path.
- Real SCM auto-push PATCH autofix run after cancellation/terminal-state hardening.
- Full benchmark event hooks from live harness runs into the tracking dashboard.
- Publishable benchmark artifacts from official scorers, separated from app display.
