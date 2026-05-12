# ExecuteSpec — Product Requirements Document

> **Version:** 5.0
> **Last updated:** 2026-05-12 (full refresh against `dev`/`main` HEAD + the four in-flight feature branches).
> **Status:** 1.0.0-M1 (deployed to `dev.executespec.ai`; `main` synced).
> **Author:** Navneet Prabhakar.

## 1. Tier ladder (live)

| Tier | Price | Runs (credits) | Seats | LLM keys | Retention | Lifecycle |
|------|-------|----------------|-------|----------|-----------|-----------|
| **FREE** | $0 | 5 / month, 1-credit specs only | 1 | Managed | 30d | LISTED |
| **PRO** | $20 / mo | 20 / month | 1 | Managed | 90d | **HIDDEN** (grandfathered; new buyers cannot select) |
| **TEAM** | $39 / seat / mo, min 2 seats | Unlimited (BYOK) | 2-20 self-serve | BYOK | 180d | LISTED |
| **ENTERPRISE** | Custom (from $1,500/mo) | Unlimited + SLA | Unlimited | BYOK / SYSTEM | Unlimited + CSV export | LISTED |

**The Solo / MAX tiers do not exist on `dev`/`main` today.** V176/V177 prototyped a "Solo @ $29/mo" tier; V179 + V185 retired both Solo and the V143 product-split discriminator. The 4-tier ladder above is canonical.

Per-tier hard limits (from `tier_config`):

| Limit | FREE | PRO | TEAM | ENTERPRISE |
|-------|------|-----|------|------------|
| Max projects / org | 1 | 2 | 5 | -1 (unlimited) |
| Max repos / project | 1 | 2 | 5 | -1 |
| Max deployments | 1 | 3 | -1 | -1 |
| Share-link active cap (V175) | 3 | 25 | -1 | -1 |
| PR Review monthly quota (V172) | 0 (blocked) | 10 | -1 | -1 |
| Code Review monthly quota (V172) | 0 (blocked) | 4 | -1 | -1 |

## 2. Value proposition

> *Ship AI-written PRs you can actually merge.*

Customer submits a natural-language spec → V2 pipeline plans, codes across one or more linked repos, builds + tests, opens a PR with a deterministic verified score. Human approves once at the **plan gate**; everything after is automatic.

Competitive positioning vs Cursor / Devin / Bolt / v0 / Lovable: ExecuteSpec is **spec-first** (not chat-first) and **PR-first** (not preview-first). The output is git history — code your reviewer can approve in five minutes — not an iframe.

## 3. Capabilities (1.0.0-M1)

### 3.1 Run pipeline

- **V2 multi-agent pipeline** (the only execution path). Smart classifier → outline planner → sequential executor with per-task tier-routed worker → reviewer (general + wiring passes) → final smoke + validation report + auto-preview. Hierarchical planning for LARGE/LEGACY specs (skeleton outline → parallel task expansion → coherence patch).
- **Two run modes:** `BUILD` (default — full pipeline above) and `PATCH` (single thin-agent loop against a failing-test spec; powers SWE-bench / ProgramBench adapters and the four autofix surfaces).
- **Worker resilience levers:** Lever A (force-edit watchdog at 0.7×maxIter) + Lever B (best-guess salvage on iter-limit / budget-exceeded / stagnation-force).
- **Deterministic verified score** — multiplicative: `clamp(0,100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)`. No LLM self-rating in the score path. `accuracyScore` retained as a legacy fallback for pre-V106 rows.
- **Status reasons** disambiguate PARTIAL terminals — surfaced as `StatusBadge` tooltip + `SignalBreakdown` chip.

### 3.2 Project workbench

- **Per-project workspace** at `workspace/projects/<projectId>/`. DB blobs are authoritative; disk is ephemeral.
- **3-step architecture pipeline** — per-repo codemap → per-repo HLD/LLD → cross-repo synthesis. Idempotent by SHA. Versioned. Lazy-refreshed via `StalenessService`.
- **Generate Docs** — on-demand EXPLANATION + SETUP markdown synthesised from project state.
- **Project memory** — every completed run appends a digest; compaction rewrites a versioned snapshot every 10 runs. Planner reads it as `{{PROJECT_MEMORY}}`.
- **RAG corpus** — Tika + pgvector + OpenAI embeddings. User-uploaded docs indexed; planner + chat read narrowed retrieval at run-time.
- **Project chat** with compaction (summarise older N messages, keep last 6 verbatim), reset, copy-on-hover per bubble, branch override on launch.
- **Multi-repo runs** — clone each linked repo into per-repo subdirs; per-repo codemap; per-repo PR with cross-link.
- **Tech debt panel** — issues by category + severity + file hotspots + dimension trend. "Fix" per category dispatches autofix Phase 2.
- **Workspace health rollup** — per-repo SHA drift + webhook + open-PR count.
- **Post-merge cascade banner** — "✓ PR #N merged — Start next requirement →".

### 3.3 Four customer-facing autofix surfaces (V183)

Routed through one chokepoint `finding/FindingPatchSubmitter`. Submitter dedupes per finding key, dispatches with `runMode=PATCH`, and pushes a PR on terminal.

| Phase | Surface | UX |
|-------|---------|-----|
| 1 | PR Review autofix | PR Review verdict popover → "Autofix N comments" |
| 2 | Tech-debt fix-by-category | TechDebtPanel category rows → "Fix" per category |
| 3 | CI failure auto-fix | GitHub `check_run` webhook (flag `ci.autofix.enabled`, default OFF; max 3 attempts/PR) |
| 4 | Code-review severity fix | CodeQualityPanel chips → "N CRITICAL" / "N MAJOR" → one PR per severity |

### 3.4 External project sharing (V175)

- **Cross-org `ProjectMember` invites** — paid-tier admins invite by email; new `PROJECT_VIEWER` role (read-only).
- **Public read-only share links** — 6 surface kinds (PROJECT_OVERVIEW / RUN_DETAIL / CODE_REVIEW / PR_REVIEW / VALIDATION_REPORT / ARCHITECTURE_DOC). Tier-gated by `tier_config.share_link_active_cap`.

### 3.5 PR Review + Code Review agents (V172, PRO+)

- **PR Review** — single-call PLANNING-tier review of one PR's diff. Emits verdict (`APPROVE` / `COMMENT` / `REQUEST_CHANGES`) + line-anchored comments. Per-tier monthly quota.
- **Code Review** — whole-repo two-stage scan. Stage 1 semgrep + Stage 2 LLM augmentation. Single markdown report download.

### 3.6 Billing

- **Cost-proportional run credits** — `credits = max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval (not submission). Refunded on FAILURE.
- **Customer cost cap** — voluntary `userCostCap` ($0.10–$1000) lowers the ceiling at submission time.
- **BYOK exemption** — `keySourceMode=OWN_KEYS` orgs skip the platform cap (customer pays their own API bill); `userCostCap` still honoured.
- **Multi-gateway** — Razorpay (INR default) + Stripe (USD default in prod). `PaymentGatewayRouter` selects per request.
- **Subscriptions + run top-up credits + invoicing with GST** + full payment-audit trail with CSV export.
- **Tier lifecycle state machine** — LISTED / HIDDEN / SUNSETTING per `tier_config` row.

### 3.7 Integrations

- **GitHub** — create repos, push code, create branches + auto-open PRs, webhook flips PR status (HMAC SHA-256). `PullRequestSnapshot.headSha` powers CI check-run polling.
- **Bitbucket** — same surface via `BitbucketRepoService` + `ScmProvider` abstraction.
- **Slack** — org-level OAuth, per-project channel routing, Block Kit messages.
- **JIRA + Confluence** — inbound webhooks create runs; outbound comments on MergeCompleted.
- **PagerDuty + ServiceNow** — incident integrations.
- **Figma URL** — optional on `RunRequest`; drives design-compliance dimension on `ReviewScoringService`.

### 3.8 Onboarding + activation

- **Personal org** auto-provisioned on self-registration (V162/V163).
- **Sandbox onboarding** — `/welcome` page after first signup. 3 sample specs (web-api, frontend, cli) provision a sandbox project + pre-filled spec — target median TTFV under 3 minutes. `SandboxCleanupJob` archives abandoned sandboxes hourly.
- **`FirstRunCelebration` banner** on first complete run.
- **PostHog 6-event activation funnel** (SIGNUP / PROJECT_CREATED / SPEC_SUBMITTED / PLAN_APPROVED / RUN_COMPLETE / PR_MERGED). Composite-PK idempotency ledger.

### 3.9 Security + compliance

- **8-layer defense-in-depth** for running untrusted user input through LLM agents.
- **Guardrail levels** (LOW / MEDIUM / HIGH) flippable per-org via `security.guardrail_level` feature flag.
- **GDPR data export** — `GET /api/auth/me/data-export` returns ZIP (manifest + payload + README) with sensitive fields stripped.
- **Force-logout** via feature-flag epoch JWT invalidation.
- **Audit chain** — `activity_audit` with cryptographic chain verification (SUPER_ADMIN endpoint).
- **`AccessGuardCoverageTest`** ArchUnit pin + 10-site known-legacy ledger.

### 3.10 Operations + observability

- **5 SLOs** published in [`docs/operations/slos.md`](operations/slos.md).
- **Local stack** — Grafana + Prometheus + Tempo via `observability/docker-compose.yml`.
- **GCP exporters** — Cloud Trace + Cloud Monitoring (`@Profile("gcp")`).
- **Custom `executespec.v2.*` metrics** — run / planner / task / worker / llm / reviewer / context.
- **Operator Telegram bridge** — out-of-band channel for long-running matrix tracking (not customer-facing).

## 4. Customer journeys

### 4.1 First-time signup → first PR

1. Land on `executespec.ai` → click "Get started free".
2. Sign up via email + password OR Google OAuth OR GitHub OAuth.
3. Personal org auto-provisioned.
4. Land on `/welcome` → pick a sample spec or attach own GitHub repo.
5. Click "Generate" → spec submits, plan gate opens.
6. Approve plan → pipeline runs, preview spins up.
7. Click "Push to GitHub" → PR opens.
8. `FirstRunCelebration` banner with verified score + "Run another" + "Link your own repo".

Target median TTFV: <3 minutes from signup to first preview.

### 4.2 Paid customer recurring flow

1. Open project dashboard → click "New run" → enter spec → optional `userCostCap`.
2. Heuristic estimate chip shows ~M-N tasks · ~$X-Y.
3. Submit → classifier picks strategy → planner emits plan.
4. Plan gate: review tasks / requirements / cost / repos touched → approve.
5. Sequential per-task execution with live cost progress bar + SSE event stream.
6. Reviewer pass → final smoke → terminal status (COMPLETE / COMPLETE_WITH_CONCERNS / FAILED) with status_reason tooltip.
7. Auto-preview spins up; auto-push opens per-repo PR with cross-links.
8. Validation report + tech-debt panel update on dashboard.

### 4.3 PR Review autofix (Phase 1 surface)

1. PR opens (any provenance — ExecuteSpec run or human).
2. Customer requests PR Review from project dashboard or chat tool.
3. Agent emits verdict + line-anchored comments → persisted to `pr_review_comment`.
4. Customer clicks "Autofix N comments" → `FindingPatchSubmitter` dispatches a patch-mode run with `branchOverride` = PR head branch.
5. Patch lands on the same branch → CI re-runs → reviewer sees updated PR.

## 5. Roadmap

Tracked in [`docs/plans/pending.md`](plans/pending.md):

| # | Plan | Status |
|---|------|--------|
| 1 | Partial / follow-up work (Mobile RN+Expo · Figma v2 · Harness V2 gaps · PARTIAL-status removal) | Partials shipped; follow-ups not scheduled |
| 2 | Backlog deferred items (M-035..M-052) — multi-org · custom roles · SCIM · per-team RBAC · ENT floor pricing | Owner-needed |
| 3 | V2 batch execution | Not started; unblocked |
| 4 | Distributed run processing (Phase A NFS + Phase B worker pool) | Draft for review |
| 5 | VS Code extension | Deferred to external repo `executespec-vscode` |
| 6 | Project-surface Vite split (Phase 2 of v2.7 product split) | Pending; needs DNS + maintenance window |
| 7 | Readymade Solutions library | W1–W5 shipped on `feature/readymades-and-migrations`; W6–W9 reserved |
| 8 | Migrations product (codemod recipes) | W1–W7 shipped on same branch; W8–W10 reserved |

## 6. Constraints + non-goals

- **No inline code suggestions** — that's Cursor / Copilot territory. ExecuteSpec is spec-first.
- **No re-introduction of V1 "progressive merge"** — auto-merge is structural.
- **No parallel task execution in the sequential path** — sequential-by-design.
- **No XML tool calls** — provider-native end-to-end since 1.0.0-M1.

## 7. References

- Architecture: [`docs/ARCHITECTURE.md`](ARCHITECTURE.md).
- Structural code index: [`docs/CODEMAP.md`](CODEMAP.md).
- Roles + tier matrix: [`docs/RBAC.md`](RBAC.md).
- User-visible flows: [`docs/USER_MANUAL.md`](USER_MANUAL.md) + [`docs/user-journeys.md`](user-journeys.md) + [`docs/project-user-journeys.md`](project-user-journeys.md).
- Workflow diagrams: [`docs/WORKFLOWS.md`](WORKFLOWS.md).
- Deployment: [`docs/DEPLOYMENT.md`](DEPLOYMENT.md).
- API surface: [`docs/openapi.yaml`](openapi.yaml) + [`docs/frontend-api-usage.md`](frontend-api-usage.md).
- Enterprise: [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) + [`docs/enterprise-sla.md`](enterprise-sla.md) + [`docs/dpa-flow.md`](dpa-flow.md) + [`docs/sso-saml-wiring.md`](sso-saml-wiring.md).
- Operations: [`docs/operations/slos.md`](operations/slos.md) + [`docs/status-page.md`](status-page.md) + [`docs/payment-history.md`](payment-history.md) + [`docs/stripe-price-ids-ops.md`](stripe-price-ids-ops.md).
- Pending plans: [`docs/plans/pending.md`](plans/pending.md).
