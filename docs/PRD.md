# ExecuteSpec PRD

> Current-state product requirements for `1.0.0-M1`, refreshed 2026-06-10 from code, migrations, and the recreated feature docs. This is not a marketing page.

## Product Statement

ExecuteSpec is a spec-driven coding platform. A user submits a natural-language requirement, the system plans the work, asks for approval at the plan gate, executes the approved plan against one or more repositories, verifies the result, and opens or updates a pull request with a verified score.

The core promise is not "chat with code." The core promise is: **turn a spec into a reviewable, test-backed PR with cost, traceability, and rollback boundaries.**

## Primary Users

| Persona | Goal |
| --- | --- |
| Solo developer | Turn a small product/change request into a PR without hand-driving every file edit. |
| Team developer | Run project-aware changes across one or more linked repos with architecture, docs, and memory context. |
| Org admin | Manage seats, BYOK keys, org settings, external sharing, and model-matrix overrides. |
| Super admin/operator | Run benchmark/eval surfaces, feature flags, payment audits, and environment checks. |

## Current Product Surface

| Area | Status | Current doc |
| --- | --- | --- |
| Run execution | Live | [`features/run-execution.md`](features/run-execution.md) |
| Project workbench | Live with some flagged enterprise/governance surfaces | [`features/project-workbench.md`](features/project-workbench.md) |
| Models, routing, BYOK | Live, with DeepSeek/Fireworks additions pending live-key proof | [`features/models-and-routing.md`](features/models-and-routing.md) |
| Billing, access, quota | Live | [`features/billing-and-access.md`](features/billing-and-access.md) |
| Benchmarks | Operator/dev surfaces live; benchmark server deployment still planned | [`features/benchmarks.md`](features/benchmarks.md) |

## Core Requirements

### R1. Spec To Plan

- Accept a validated spec through `POST /api/runs`.
- Support project-linked multi-repo context through `selectedRepoIds`.
- Classify run complexity and create a plan with tasks.
- Pause production runs at `AWAITING_APPROVAL`.
- Enforce credit confirmation and cost ceiling at approval.

### R2. Plan To Code

- Execute through `SequentialExecutor` and `WorkerAgent`.
- Route worker model by task complexity.
- Execute file/shell/build/test tools through sandbox and abuse scanners.
- Keep task/run status durable enough for UI and recovery.
- Never let late pipeline writes overwrite `CANCELLED` or `PAUSED`.

### R3. Code To Verified Result

- Run reviewer passes and final build/test checks where available.
- Compute `verifiedScore` through the additive scoring model, then apply trust
  caps when build, tests, reviewer, coverage, or critical-issue evidence makes a
  high headline indefensible.
- Expose a `verificationLedger[]` on validation reports so users can distinguish
  deterministic checks, LLM reviewer/assessment signals, LLM-assisted counts,
  and internal score summaries.
- Persist validation report and issues.
- Show cost, tokens, events, tasks, files, and PR status in run detail.

### R4. Project Context

- Keep project repos, architecture docs, generated docs, uploaded docs, RAG chunks, project memory, and activity tied to a project.
- Allow project chat to inspect architecture, docs, memory, repos, runs, PRs, tech debt, and code search.
- Allow PR review, code review, tech-debt fix, and CI-failure autofix surfaces to dispatch PATCH runs.

### R5. Model Choice

- Direct providers expose only models they directly support: Anthropic, OpenAI, Gemini, DeepSeek.
- Aggregators expose curated model choices: OpenRouter, Fireworks.
- System defaults live in YAML.
- Enterprise org admins may override org matrix cells.
- BYOK users may use their own keys where their tier allows.
- FREE users receive the configured free-tier default only; no matrix.

### R6. Commercial And Admin Controls

- Tier gates must be enforced server-side.
- TEAM supports 1-20 seats.
- ENTERPRISE supports org-level model overrides and aggregator keys.
- Payment, quota, top-up, and refund paths must be auditable and tenant-scoped.
- External project sharing requires the org flag plus role/tier checks.

## Tier Shape

| Tier | Current intent |
| --- | --- |
| FREE | Limited monthly runs, fixed default model from properties, no model matrix. |
| PRO | Hidden/grandfathered; keep compatibility but do not sell as a new plan. |
| TEAM | Self-serve BYOK team tier, 1-20 seats. |
| ENTERPRISE | Custom tier, org model matrix override, aggregator/provider org keys, expanded admin controls. |

## Non-Goals

- Do not expose every aggregator catalog model as product UI.
- Do not claim official benchmark results without official harness/scorer evidence.
- Do not turn the web app back into the primary coding IDE; the web app is the control plane.
- Do not rely on point-in-time audit/review docs as current product truth.

## Proof Gaps

The product works at compile/test/local-smoke level, but the following still need live proof before stronger claims:

- VM-side Chinese-model latency A/B across OpenRouter, Fireworks, and native DeepSeek.
- Authenticated model-matrix UI/API smoke with real org/user sessions.
- Real payment gateway ordering and abandonment flows.
- Real SCM auto-push PATCH run after cancellation/terminal-state hardening.
- Full benchmark event hooks from live harness runs into the tracking dashboard.
- Qualitative spot checks for externally published HIGH-5 or product claims; the
  verified score is a platform trust signal, not an external human audit.
