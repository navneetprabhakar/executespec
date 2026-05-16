# Product Requirements — ExecuteSpec

> Source-of-truth: this document is derived strictly from the code under `src/main/java/com/navneet/executespec/` and `frontend/src/`. Any claim that disagrees with the code is wrong.
> Last regenerated: 2026-05-16 against migration head **V199** and main-branch HEAD.

## 1. Product statement

ExecuteSpec is a spec-driven AI coding platform. A signed-in user submits a natural-language spec at `POST /api/runs` (see `RunController.submit` at `src/main/java/com/navneet/executespec/api/controller/RunController.java:98`). The backend orchestrates a multi-agent pipeline (`RunOrchestrator.submitRun`, `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`) that classifies the spec, plans tasks, pauses at a plan-approval gate, charges credits at approval, executes the plan against a per-run workspace, runs a reviewer / wiring pass, computes a verified score, opens a pull request (single-repo or multi-repo via `ScmPushService.pushMultiRepo`), and surfaces the result on `/result/:id` for human review. The same machinery powers four customer-facing autofix surfaces (PR Review, tech-debt, CI-failure webhook, code-review severity buckets), all of which route through `FindingPatchSubmitter` and dispatch a PATCH-mode run.

Two run modes coexist:

- **`BUILD`** — classifier → outline planner → sequential per-task worker → reviewer → push → PR. Used by every product entry point.
- **`PATCH`** — single thin-agent loop against a focused failing-test / finding spec. Used by bench adapters (SWE-bench, ProgramBench) and the four autofix surfaces.

Project workbench surfaces (`/projects/:id/*`) bundle repos, runs, codemap, architecture docs, RAG-indexed user docs, tech-debt rollups, PR-review history, project chat, and member management around a long-lived project entity (`auth.entity.Project` + `project_member` + `project_repo`).

The product is delivered as a single Spring Boot 3.5.14 / Java 21 JAR with a Vite-built React 18 frontend served from `static/`. Postgres + Redis are the durable substrate; Cloud Run handles 5-minute preview deploys.

## 2. Personas

### Customer-account tiers

The tier ladder is enforced in `auth/entity/TierConfig.java` + `auth/service/TierEnforcementService.java` and seeded by migrations `V6` → `V21` → `V122` → `V140` → `V141` → `V144` → `V164` → `V165` → `V167` → `V198`. Current live ladder per `V165__drop_max_tier_team_at_39.sql`:

| Tier           | Lifecycle state                | Monthly runs (`tier_config.monthly_run_limit`) | Concurrent runs (`concurrent_run_limit`) | Notes                                                                                                                              |
| -------------- | ------------------------------ | ---------------------------------------------- | ---------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **FREE**       | `LISTED`                       | 5 (V167; was 10 in V145, reverted)             | 1                                        | 1-credit specs only (`agent.cost.max-credits-free: 1`). `application.yml app.tiers.free.monthly-run-limit=5`. No card required.    |
| **PRO**        | `HIDDEN` (V122, grandfathered) | 20 (V140)                                      | 3                                        | Hidden from `/api/billing/plans` for new buyers (`V122__team_tier_and_pro_hidden.sql:40`). Existing subscribers renew untouched.   |
| **TEAM**       | `LISTED` (V165)                | -1 (unlimited, per V165)                       | 5 (V122)                                 | Min 2 / max 50 seats (V165 raised `max_org_members` 20 → 50). `$39/seat/mo`, BYOK-or-managed. Shared org pool, `seat_count` field. |
| **ENTERPRISE** | `LISTED`                       | -1 (V140)                                      | 10                                       | Custom contract. `execution_view_enabled=TRUE`. No platform cost cap. SSO + audit log + SCIM.                                      |
| ~~MAX~~        | Dropped by V165                | n/a                                            | n/a                                      | MAX rows remapped to TEAM in V165 (idempotent UPDATE). Solo flow rolled back in V185 (`retire_solo_branding.sql`).                 |

`TierConfig` carries the per-tier flags surfaced through `/api/auth/me/permissions` (see `getMyPermissions` in `frontend/src/lib/api.ts:1184`):

- `maxApplySuggestions` — FREE 2 / PRO 10 / TEAM -1 / ENTERPRISE -1 (per V122 + V165 seeds).
- `maxPreviewStarts` — FREE 2 / PRO 5 / TEAM -1 / ENTERPRISE -1.
- `maxRunAutoRetries` — FREE 0 / PRO 2 / TEAM 2 (V122 seed) / ENTERPRISE 3.
- `canDeploy` — FREE false / PRO true / TEAM true (V164 fixed inversion) / ENTERPRISE true.
- `maxDeployments` — FREE 0 / PRO 5 / TEAM -1 (V164) / ENTERPRISE -1.
- `maxRunCostUsd` — platform-derived hard ceiling. FREE $3 / PRO $5 / TEAM $25 (per `ComposePage.tsx` `tierMaxCap`) / ENTERPRISE unlimited (`tierMaxCap === 1000`).
- `retentionDays` — FREE 30 / PRO 90 / TEAM 180 (V122) / ENTERPRISE 365.
- `executionViewEnabled` — only ENTERPRISE (per V140).
- `maxProjectsPerOrg` / `maxReposPerProject` — FREE 1/1 / PRO 2/2 / TEAM 5/5 / ENTERPRISE -1/-1 (V140 seed).
- `prReviewEnabled` / `codeReviewEnabled` — FREE false; PRO/TEAM/ENTERPRISE true (V172, `TierConfig.java:133`).
- `prReviewMonthlyQuota` / `codeReviewMonthlyQuota` — FREE 0, PRO 10/4, TEAM/ENTERPRISE -1 (V172).
- `shareLinkActiveCap` — FREE 3, PRO 25, TEAM/ENTERPRISE -1 (V175).

### Org-level roles (RBAC)

Defined in `auth.entity.AppUser.orgRole` + `auth.entity.OrgMember.orgRole`. Enforced through `auth/security/RunAccessGuard`, `auth/security/OrgGuard`, `auth/security/ProjectAccessGuard`. The set is fixed:

- **`SUPER_ADMIN`** — global role on `AppUser.globalRole`. Reads every run / org / project via `RunVisibilitySpec.forUser`. Exclusive endpoints: `/api/admin/users`, `/api/admin/orgs`, `/api/admin/tiers/{tier}/lifecycle`, `/api/admin/runs/halt-all`, `/api/admin/payment-audit`, `/api/admin/audit-chain/verify`, `/api/admin/activation-funnel`, `/api/admin/feature-flags`, `/api/admin/executions/reconcile`, `/api/admin/topup-packages`.
- **`ORG_ADMIN`** — manages org settings, members, BYOK keys, audit log access, external-sharing flag (V198, `OrgController` PUT `/org/me/settings/external-sharing`). Required to invite cross-org collaborators (per V175 invite creation, plus V198 per-org `external_sharing_enabled` toggle).
- **`BILLING_ADMIN`** — narrower than ORG_ADMIN; can read invoices + change payment method but cannot rename the org / change tier / invite. Gated by `ProjectAccessGuard.requireBillingAdmin`. UI: BillingPage actions.
- **`DEVELOPER`** — base org member. May submit runs against the org's projects, view team activity, fork teammates' runs (`POST /api/runs/{id}/fork`).
- **`PROJECT_ADMIN`** — per-project role on `project_member`. Can invite teammates to the project, manage project repos, regenerate architecture, install GitHub webhook.
- **`PROJECT_DEVELOPER`** — per-project: submit runs against the project, run reviewer/code-review, apply autofix.
- **`PROJECT_VIEWER`** — read-only on every project surface (V175 added; cross-org-safe).

### Self-serve solo users

Migrations V162 + V163 auto-provision a personal org (`org.is_personal = true`) on `POST /api/auth/register` so a fresh user can create their first project without the OrgService FREE-tier block (see `V162__org_is_personal.sql`). Filtered out of admin org listings by `WHERE is_personal = false`.

## 3. Core jobs-to-be-done

| JTBD                                                        | Surface (frontend)              | API entry point                                                          | Service / agent                                                       |
| ----------------------------------------------------------- | ------------------------------- | ------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| Submit a spec and watch the agent execute it                | `/compose` → `/plan` → `/live`  | `POST /api/runs`, SSE `/api/runs/{id}/events`                            | `RunOrchestrator.submitRun`, `SequentialExecutor`                     |
| Approve or reject the plan before credits get charged       | `/plan/:runId` (PlanPage.tsx)   | `POST /api/runs/{id}/approve`                                            | `RunController.approvePlan`, `GateStore.tryClaimPlanGate`             |
| Review the resulting PR + verified score                    | `/result/:id` (ResultPage.tsx)  | `GET /api/runs/{id}`, `GET /api/runs/{id}/report`                        | `ValidationReportService`, `ScoreCard` / `VerdictPill`                |
| Run a spec against an existing GitHub repo (or multi-repo)  | `/compose` + project selector   | `POST /api/runs` with `repoUrl` / `selectedRepoIds`                      | `RunOrchestrator` clone path, `ScmPushService.pushMultiRepo`          |
| Bundle repos + runs + memory + chat into a long-lived scope | `/projects` and `/projects/:id` | `POST /api/projects`, `GET /api/projects/{id}/dashboard`                 | `ProjectController`, `ProjectDashboardController`                     |
| Autofix a PR review / code-review / tech-debt / CI failure  | Various panels on `/projects`   | `POST /api/pr-reviews/{id}/autofix`, `POST /api/code-reviews/{id}/...`   | `FindingPatchSubmitter`, `PrReviewAutofixService`                     |

## 4. Run modes

Source: `state/RunMode.java`.

- **`BUILD`** (default) — full pipeline: `SmartClassifierAgent` → `OutlinePlannerAgent` (with parallel `TaskExpanderAgent` + `CoherencePlannerAgent` for LARGE / LEGACY specs) → plan gate (`PRODUCTION` pauses at `AWAITING_APPROVAL`; `PROTOTYPE` bypasses) → `SequentialExecutor` per-task `WorkerAgent` → `ReviewerAgent` → push + PR + `ValidationReportGenerator`. All product code paths submit `BUILD`.
- **`PATCH`** — one `WorkerAgent` loop on the whole spec, no plan / no reviewer / no per-task split, force PLANNING-tier model so it doesn't downgrade to Haiku, 75-iter cap, relaxed stagnation guard. Used by `swebench/SwebenchController`, `programbench/ProgramBenchController`, and the four autofix dispatchers (`PrReviewAutofixService`, `TechDebtFixService`, `CiFailureAutoFixService`, `CodeReviewAutofixService`) all routed through `finding/FindingPatchSubmitter`.

## 5. Tier capability matrix

Cells reference the entity column or migration row. `✓` = supported on the tier; `–` = not available; `gated` = available but quota-capped.

| Capability                                                            | FREE                              | PRO (HIDDEN)        | TEAM                | ENTERPRISE          | Source                                                                   |
| --------------------------------------------------------------------- | --------------------------------- | ------------------- | ------------------- | ------------------- | ------------------------------------------------------------------------ |
| Monthly run quota                                                     | 5 (V167)                          | 20 (V140)           | unlimited (V165)    | unlimited (V140)    | `tier_config.monthly_run_limit`                                          |
| Concurrent run limit                                                  | 1                                 | 3                   | 5                   | 10                  | `tier_config.concurrent_run_limit`                                       |
| 1-credit specs only                                                   | yes                               | no                  | no                  | no                  | `agent.cost.max-credits-free: 1`                                         |
| Hard per-run cost ceiling                                             | $3 (`maxRunCostUsd=3`)            | $5                  | $25                 | unlimited           | `TierConfig.maxRunCostUsd` + `ComposePage.tsx:47` `tierMaxCap`           |
| Multi-repo run (`selectedRepoIds`)                                    | 1 repo (`maxReposPerProject=1`)   | 2 repos             | 5 repos             | unlimited           | `tier_config.max_repos_per_project` (V140)                               |
| Projects per org                                                      | 1                                 | 2                   | 5                   | unlimited           | `tier_config.max_projects_per_org` (V140)                                |
| BYOK (per-user + per-org)                                             | – (hidden in sidebar)             | ✓                   | ✓                   | ✓                   | Shell.tsx `opts.tier !== 'FREE'` for `/api-keys`                         |
| Apply suggestion (chat copilot)                                       | 2/run                             | 10/run              | unlimited           | unlimited           | `tier_config.max_apply_suggestions`                                      |
| Preview deploys (Cloud Run, 5-min TTL)                                | 2/run                             | 5/run               | unlimited           | unlimited           | `tier_config.max_preview_starts` + `canDeploy`                           |
| Persistent deployments                                                | – (`canDeploy=false`)             | up to 5 (V122)      | unlimited (V164)    | unlimited           | `tier_config.can_deploy` + `max_deployments`                             |
| Project chat                                                          | 0 messages (`maxChatMessages=0`)  | 10 / run            | 10 / run            | 10 / run            | `tier_config.max_chat_messages`                                          |
| PR Review agent                                                       | – (V172)                          | 10/mo               | unlimited           | unlimited           | `tier_config.pr_review_monthly_quota`                                    |
| Whole-repo Code Review agent                                          | – (V172)                          | 4/mo                | unlimited           | unlimited           | `tier_config.code_review_monthly_quota`                                  |
| Public read-only share links                                          | 3 active (`shareLinkActiveCap=3`) | 25 active           | unlimited           | unlimited           | `tier_config.share_link_active_cap` (V175)                               |
| Cross-org project invites (V175)                                      | – (until org enables V198 flag)   | gated by V198 flag  | gated by V198 flag  | gated by V198 flag  | `org.external_sharing_enabled` + tier PRO+ + ORG_ADMIN (V198)            |
| Multi-repo Execution View                                             | –                                 | –                   | –                   | ✓                   | `tier_config.execution_view_enabled` (V140 only ENTERPRISE)              |
| Audit log (`/audit`)                                                  | –                                 | –                   | ✓                   | ✓                   | `Shell.tsx:63` gates `/audit` on TEAM/ENTERPRISE/SUPER_ADMIN             |
| Data retention                                                        | 30 days                           | 90 days             | 180 days (V122)     | 365 days            | `tier_config.retention_days`                                             |
| GDPR data export (`/api/auth/me/data-export`)                         | ✓                                 | ✓                   | ✓                   | ✓                   | `UserDataExportService`                                                  |
| SSO + SCIM                                                            | –                                 | –                   | ✓ (V127)            | ✓                   | `SsoConfigController`                                                    |
| Support level                                                         | Email only (`MiscPages.tsx` copy) | Email               | Email + chat        | Dedicated CSM       | Public pricing card copy in `MiscPages.tsx`                              |

## 6. Quota and credit model

### Credits formula

```
credits = max(1, ceil(bufferedEstimate / $5.0))
```

Hard-coded via `agent.cost.credit-multiplier-usd: 5.0` (`application.yml`). `estimation-buffer: 1.10` (10% retry buffer) and `budget-margin: 1.5` (silent hard cap multiplier) apply on top. Source: `agent.CostEstimator` + `RunOrchestrator.persistEstimate`.

### Charge timing

Charged at plan approval, not submission. `RunController.approvePlan` (`api/controller/RunController.java:317`) calls `TierEnforcementService.assertAndIncrementRunCount(userId, credits)` only after the plan is approved. This is the TOCTOU-safe path documented in `state/CLAUDE.md` — pessimistic lock + atomic check+increment in a single transaction (`TierEnforcementService.java:139`).

Exception: `RunController.submit` also increments the monthly counter at submit time (`api/controller/RunController.java:122`, SDET BUG-006 patch) — this prevents `executionMode=PROTOTYPE+skipApproval=true` from bypassing the FREE 5-runs cap. The submit-time increment is the FREE-tier gate; the approval-time increment is the multi-credit charge for paid tiers.

### Refunds on FAILURE

`billing/service/RunQuotaRefundListener` listens for the `RunFailedEvent` and calls `tierEnforcementService.decrementRunCount(userId, credits)` so a failed run doesn't burn quota. Refund is symmetric to the charge (`RunQuotaRefundListener.java:76`). Cancelled runs also refund.

### Hard cost cap

`RunRequest.userCostCap` (DTO at `api/dto/RunRequest.java:112`, validated `0.10 ≤ x ≤ $1000`) lets the customer set a tighter ceiling than the platform cap (`credits × $5 × 1.5`). Effective cap = `min(platformCap, userCostCap)`. The `WorkerAgent` budget guard force-terminates with `Outcome.BUDGET_EXCEEDED` the moment cumulative spend hits the cap. BYOK orgs (`keySourceMode=OWN_KEYS`) skip the platform cap entirely (per `RunOrchestrator.java:1550` "PATCH run={} BYOK exempt").

### Top-up packages

`POST /api/billing/topup/purchase` (`BillingController`) buys non-expiring run credits. Consumed automatically by `runTopupService.consumeBonusRun` when the monthly counter exceeds the tier limit (`TierEnforcementService.java:115`).

## 7. Run lifecycle states

All transitions are written through `RunOrchestrator.setStatus` or `StateStore.saveRun`. The complete state set (grep `pipeline_run.setStatus` + `r.setStatus` in `RunOrchestrator.java`):

- **`QUEUED`** — initial state after `POST /api/runs`. Picked up by the orchestrator's executor pool.
- **`CLASSIFYING`** — `SmartClassifierAgent` running (LOW / MEDIUM / HIGH / LEGACY). Sub-second to ~10s.
- **`PLANNING`** — `OutlinePlannerAgent` (and for LARGE / LEGACY, the hierarchical `TaskExpanderAgent` + `CoherencePlannerAgent` phases).
- **`AWAITING_APPROVAL`** — plan gate is open; `GateStore.awaitPlanGate` blocks until `POST /api/runs/{id}/approve` claims it. Only entered for `executionMode=PRODUCTION`.
- **`WORKING`** (alias `EXECUTING` in UI status badges) — `SequentialExecutor` is iterating per-task `WorkerAgent` calls. Set at `RunOrchestrator.java:1589` and `:2091` and `:2655`.
- **`REVIEWING`** — `ReviewerAgent` running (set at `RunOrchestrator.java:2794`). Reviewer multiplier feeds into `verifiedScore`.
- **`PROVIDER_SWITCHING`** — transient, fired on failover via `LlmRouter`'s YAML failover chain. UI maps to "Switching provider" (`LivePage.tsx:196`).
- **`PAUSED`** — user-initiated pause via `POST /api/runs/{id}/pause` (feature flag `v2.pause.enabled`, default OFF). Set at `RunOrchestrator.java:2032`.
- **`COMPLETE`** (alias `COMPLETED` in some surfaces) — terminal happy path.
- **`PARTIAL`** — terminal-with-degradation. Always carries a `status_reason` from V107: `CLEAN_COMPLETION`, `CLEAN_WITH_INTERMEDIATE_WARNINGS`, `FINAL_BUILD_FAILED`, `TESTS_FAILED:N_OF_M`, `REVIEWER_UNRESOLVED`, `REVIEWER_FAILED`, `ALL_TASKS_FAILED`, `LEGACY_UNKNOWN`. Surfaces as a tooltip on `StatusBadge` / `Glossary("status reason")`.
- **`FAILED`** — terminal-hard. `failureReason` is populated.
- **`CANCELLED`** — user-initiated via `DELETE /api/runs/{id}` → `RunOrchestrator.cancel(runId)`.

Task-level statuses (`agent_task.status`): `PENDING`, `RUNNING` / `EXECUTING`, `COMPLETED` / `DONE`, `FAILED`, `PARTIAL`, `BLOCKED_DEPS`. Mapped to UI via `frontend/src/signal/pages/HomePage.tsx:39` `statusToDot`.

Soft-delete (V191): `POST /api/runs/{id}/soft-delete` tombstones a terminal run with a 30-day restore window (`POST /api/runs/{id}/restore`). `DELETE /api/runs/{id}` remains the in-flight cancel.

Archive (V191 reversible): `POST /api/runs/{id}/archive` + `/api/runs/{id}/unarchive` toggles `pipeline_run.archived_at`.

## 8. Verified score (the headline number)

Computed in `report/ValidationReportService.computeVerifiedScoreMultiplicative` (line 974, signature with `testCoverageLevel` from PR-R 2026-05-14).

```
raw = 100 × buildFactor × testFactor × reviewerFactor × endpointFactor × criticalDamper
verifiedScore = clamp(0, 100, round(raw))
```

| Factor             | Domain                                                                                                                                          | Source                                                              |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `buildFactor`      | `finalBuildPassed=TRUE → 1.0`, else `0.0` (zero collapses score regardless of others)                                                           | `ValidationReportService.java:983`                                  |
| `testFactor`       | If `testsTotal > 0`: linear `testsPassed/testsTotal`. If `testsTotal = 0`: `testFactorFromCoverageLevel(testCoverageLevel)` (PR-R)              | `ValidationReportService.java:986`                                  |
| `reviewerFactor`   | `CLEAN → 1.0`, `FIXED_ISSUES → 0.85`, `UNRESOLVED_ISSUES → 0.4`, `REVIEW_FAILED → 0.2`, unknown → `0.5`                                         | `ValidationReportService.java:999`                                  |
| `endpointFactor`   | `endpointCoveragePercent / 100` (null = 1.0). Trust-gated: if reviewer ∈ {CLEAN, FIXED_ISSUES}, `endpointFactor ≥ reviewerFactor` (2026-05-10)  | `ValidationReportService.java:1009`                                 |
| `criticalDamper`   | `max(0.5, 1 - 0.05 × unfixedCriticalCount)` — each unfixed critical knocks 5% off, floored at 50%                                                | `ValidationReportService.java:1081`                                 |

Test-factor coverage mapping (PR-R, when no concrete tests ran):

- `COMPREHENSIVE` / `GOOD` → 1.0
- `PARTIAL` → 0.7
- `NONE` → 0.3
- `null` / unknown → 0.5 (legacy neutral)

Test-coverage trust gate (2026-05-16, this branch): when `testsTotal == 0` AND reviewer ∈ {CLEAN, FIXED_ISSUES} AND `endpointCoveragePercent ≥ 95`, `testFactor` is floored at `reviewerFactor`. This was the root cause for the OpenAI todo-app run scoring 60 vs Anthropic/Gemini's 85 on the same spec — OpenAI delivered exactly the one test the spec asked for and the LLM judge marked it PARTIAL.

### Score → verdict bands

Source: `frontend/src/signal/pages/ResultPage.tsx:216`, `AllRunsPage.tsx:42`, `HomePage.tsx:55`, `VerdictPill`.

- **≥ 85 — Shippable** (mint / good)
- **70–84 — Needs review** (amber / warn)
- **< 70 — Failed** (coral / fail)

The previous 90-threshold mismatch on `ResultPage` was corrected in PR #138 (2026-05-16); all surfaces now align at 85.

## 9. Non-goals

What the system explicitly does **not** do (validated by absence in the codebase):

- **No live IDE.** No `vscode`, `monaco`, in-browser editor under `frontend/src/`. Closest is `CodeView.tsx`, a regex-tokenised read-only highlighter (replaced V3's Monaco; cf. `frontend/CLAUDE.md`).
- **No realtime co-edit / shared cursors.** Project chat uses turn-based POST `/api/projects/{id}/chat/messages` (`ProjectChatPage.tsx`). No WebRTC, no Yjs, no operational-transform code.
- **No inline collaboration on runs.** SSE event stream is one-way push (`/api/runs/{id}/events`); no chat between viewers.
- **No model training on customer code.** Trust page copy explicitly says so (`ExtraPages.tsx:773-779`); architecture has no training pipeline.
- **No always-on agent / scheduled runs from product UI.** Runs are explicitly submitted. The Telegram bridge is operator-only (not a product feature).
- **No multi-tenant white-label.** Single `executespec.ai` surface; V143 `product_id` discriminator was rolled back by V179 + V185.
- **No native mobile apps.** PWA manifest + service worker (`frontend/public/sw.js`), but no React Native / iOS / Android binaries.
- **No automatic merge.** Runs always stop at the PR; the user merges on GitHub. (V2 retired the V1 `/api/runs/{id}/merge` endpoint — see `RunController.java:454` "V2 auto-merges; explicit merge gate removed".)
- **No CI bot.** GitHub `check_run` webhook autofix (`CiFailureAutoFixService`) is feature-flagged OFF (`ci.autofix.enabled` default false).
- **No GraphQL / RPC.** Every backend boundary is REST + JSON (or SSE). gRPC mentioned nowhere.
- **No SOC 2 audit shipped.** Trust page says "SOC 2 Type II Ready" (`ExtraPages.tsx:742`), not "certified". The DPA + sub-processors list are real artefacts under `docs/`.
</content>
</invoke>