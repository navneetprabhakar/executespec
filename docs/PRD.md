# ExecuteSpec — Product Requirements Document

> Engineering-honest PRD derived from source code. Concrete file paths cited inline. Not marketing copy.

---

## 1. Product statement

ExecuteSpec is a spec-driven coding platform. A customer submits a natural-language requirement, the system plans the work, asks the customer to approve the plan (and a credit budget), executes the plan against one or more linked repositories, runs build + tests + reviewer, opens a pull request, and persists a deterministic 0–100 verified score against the result.

The submit surface is [`POST /api/runs`](../src/main/java/com/navneet/executespec/api/controller/RunController.java) and the request body is [`RunRequest`](../src/main/java/com/navneet/executespec/api/dto/RunRequest.java). Validation: `userRequest` is `@NotBlank @Size(max=50_000)`; `repoUrl` is constrained to HTTPS GitHub/GitLab/Bitbucket; `aiProvider` is one of `ANTHROPIC | GEMINI | GOOGLE | OPENAI` (`GOOGLE` is a legacy alias for `GEMINI`); optional `userCostCap` is `@DecimalMin(0.10) @DecimalMax(1000.00)`; optional `selectedRepoIds` scopes a multi-repo run.

After validation the controller calls `tierEnforcementService.assertAndIncrementRunCount(userId, credits)` ([`TierEnforcementService`](../src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java)) to atomically claim a run slot, then hands off to [`RunOrchestrator.submitRun`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java) which persists a `pipeline_run` row at status `PLANNING` and schedules the background pipeline future. The HTTP response is 202 with the freshly-minted `runId`.

The end-to-end pipeline, owned by `RunOrchestrator.runPipeline`, is:

```
submit (POST /api/runs)
    |
    v
[0a] resolve BYOK keys (RunKeyContextStore)
[0a-bis] init workspace; clone N linked repos under <workspace>/<runId>/repos/<name>/
[0b] per-run codemap (existing-repo, PRO+)
[0c] feature assessment (Pilot R4)
    |
    v
[1] PLAN — classify (heuristic + SmartClassifier shadow) -> outline planner
       SMALL/MEDIUM: FULL_PLAN in one call
       LARGE/LEGACY: skeleton -> parallel TaskExpander -> CoherencePlanner
       LEGACY: extra depth-2 epic expansion
    |
    v
[1b] PLAN GATE (PRODUCTION only)
     status = AWAITING_APPROVAL
     gateStore.openPlanGate(runId, planSummary, creditsRequired, costEstimate)
     ----- wait for POST /api/runs/{id}/approve -----
    |
    v
[2] EXECUTE — SequentialExecutor: per-task tier-routed WorkerAgent
       per-task build smoke after writes
    |
    v
[2.5] lint auto-fix pass (flag-gated: lint.autofix.enabled)
    |
    v
[3] REVIEW — ReviewerAgent general + wiring passes + PostEditFormatter
    |
    v
[4]  ensure README
[4b] derive (status, statusReason) — independent of any feature flag
    |
    v
[5] VALIDATION REPORT
       AccuracyAssessmentService.assess (LLM-judged spec adherence)
       ValidationReportService.generateAndPersist (verifiedScore + rubricScore)
       background: TestGenOrchestrator OR CliSmokeRunner
    |
    v
[6]  final git add -A && git commit --allow-empty (commitWorkspaceOutputs)
[6b] project memory ledger (RunLedgerAgent) + staleness invalidate
    |
    v
[7]  terminate: persist actualCostUsd, totals, statusReason
     publish PipelineEvent.RunCompleted / RunFailed
     ReferralCompletionHook.onTerminalStatus
     CostHistoryService.recordOnTerminal
    |
    v
[7b] auto-push (ScmPushService.push) for COMPLETE/PARTIAL with EXISTING repo
     single-repo: branch + PR
     multi-repo: per-repo branch + per-repo PR + cross-linked PR bodies
```

The pipeline runs on a dedicated `ExecutorService` (`Thread.ofVirtual()`-friendly via `PoolSizing.coreMultiple(1,4,16)`) and is interruptible by `RunOrchestrator.cancel`, `pause` (flag `v2.pause.enabled`), and `haltAllActiveRuns` (SUPER_ADMIN).

Two run modes (`pipeline_run.run_mode`, V182): **BUILD** (the diagram above — default) and **PATCH** (single thin-agent loop against a failing-test spec; skips classifier/planner/reviewer; used by SWE-bench + ProgramBench adapters and four customer-facing autofix surfaces). Source: [`state/RunMode.java`](../src/main/java/com/navneet/executespec/state/RunMode.java).

---

## 2. Personas + tiers

Tiers are configured at runtime via the `tier_config` table ([`TierConfig`](../src/main/java/com/navneet/executespec/auth/entity/TierConfig.java)) and seeded by Flyway migrations. SUPER_ADMIN can edit cell values live through `PUT /api/admin/tier-config/{tier}`. Lifecycle state (`LISTED | HIDDEN | SUNSETTING`) is managed by [`TierLifecycleService`](../src/main/java/com/navneet/executespec/billing/lifecycle/TierLifecycleService.java) with a 14-day observation window between `HIDDEN` and `SUNSETTING`.

The current live ladder (as of V199):

### FREE — "Try ExecuteSpec"

- **Monthly cap:** 5 runs/month (`tier_config.monthly_run_limit=5`, set by V167 reverting V145/V166).
- **Per-run cost ceiling:** $3.00 USD (`max_run_cost_usd=3.00`).
- **Lifetime run credit balance:** 5 starter credits at signup, cap 25 (`app_user.lifetime_run_credit_balance`, V152). Referral grants top this up (R-161).
- **Concurrent runs:** 1.
- **Apply-suggestion cap:** 2 per run.
- **Preview starts:** 2.
- **Deploy:** disabled.
- **Org membership:** none (FREE users get a personal org auto-provisioned at registration, `org.is_personal=true`, V162/V163).
- **PR Review agent:** disabled. Code Review agent: disabled. (`tier_config.pr_review_enabled=false`, V172.)
- **Share-link active cap:** 3 (V175).
- **Capabilities:** can submit BUILD-mode runs against the sandbox or attached repos, can use the pre-flight conversation refiner, can use Library / Migrations only as static catalogs (no execution from there).
- **Gates that fire:** out-of-quota → `BillingException` (HTTP 402) from `TierEnforcementService.assertAndIncrementRunCount`; complexity gate → `assertComplexityAllowed` rejects HIGH-complexity plans for FREE.

### PRO — "Solo" (HIDDEN since V185 retired the "Solo" branding, grandfathered only)

- **Monthly cap:** 30 runs/month at $29/mo USD or ₹2499/mo INR (`billing_plan.run_limit=30`, V176).
- **Per-run cost ceiling:** $5.00 USD.
- **Concurrent runs:** 3.
- **Apply-suggestion cap:** 10.
- **Preview starts:** 5.
- **Deploy:** enabled; `max_deployments=5` per run.
- **PR Review agent:** enabled, 10 reviews/month. Code Review: enabled, 4 scans/month.
- **Share-link active cap:** 25.
- **Founder pricing:** 50% off lifetime for the first 100 subscribers via `FOUNDER50_SOLO_LIFETIME` (V177).
- **Status:** `tier_config.lifecycle_state = HIDDEN`. New buyers can no longer pick it from the pricing page (`billingService.listPlans` filters by `lifecycle_state=LISTED`). Existing PRO subscribers remain on the plan.

### TEAM — "$39/seat/month" (BYOK)

- **Monthly cap:** unlimited runs (`monthly_run_limit=-1`, set by V165 collapsing the prior MAX tier into TEAM).
- **Concurrent runs:** unlimited (`concurrent_run_limit=10` on the seeded row, treated as effectively unlimited).
- **Seats:** min 2, max 20 self-serve (`OrgService.TEAM_MIN_SEATS=2`, `TEAM_MAX_SEATS=20`). Seats sync to Stripe via `StripePaymentGateway.updateSubscriptionQuantity` with `CREATE_PRORATIONS`.
- **Pricing:** $39 per seat per month USD. INR-equivalent INR billing via Razorpay.
- **Key source:** BYOK (Bring Your Own Keys) only — the org provides Anthropic / OpenAI / Gemini API keys via `PUT /api/org/me/settings/ai-keys`. BYOK orgs (`org.key_source_mode=OWN_KEYS`) are exempt from the platform run-cost cap (`TierEnforcementService.requiresCostCap` returns false). HYBRID + SYSTEM key-source orgs stay capped.
- **Apply-suggestion cap:** unlimited (-1).
- **Preview starts:** -1.
- **Deploy:** enabled with `max_deployments=-1` (V164 corrected the inversion bug that had set TEAM to `can_deploy=false`).
- **PR Review + Code Review:** unlimited quota.
- **Share-link active cap:** unlimited.
- **External project sharing:** available, gated by per-org `org.external_sharing_enabled` flag (V198, default `false`; ORG_ADMIN-of-own-org or SUPER_ADMIN may flip via `PUT /api/org/me/settings/external-sharing`).
- **Audit log:** visible to ORG_ADMIN.

### ENTERPRISE — custom

- **Monthly cap:** unlimited.
- **Concurrent runs:** unlimited.
- **Per-run cost cap:** unbounded if BYOK; otherwise per-customer.
- **Org membership:** unlimited (`max_org_members=-1`).
- **SSO / SAML:** schema-level configuration via `PUT /api/auth/sso/config` (OKTA_SAML / AZURE_AD_SAML / GOOGLE_OIDC); SAML handshake itself is roadmap, [`SsoConfigController`](../src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java) refuses to flip `sso_enforced=true` on SAML providers today.
- **Domain enforcement:** `org.domain_enforcement_mode=ENFORCED` by default at create (V21); `block_common_domains=true` blocks 50+ consumer email providers from invites ([`InvitationService`](../src/main/java/com/navneet/executespec/auth/service/InvitationService.java)).
- **GSTR-1 + accounting:** invoicing pipeline (V28) generates GST-compliant invoices with `seller_config` snapshot, double-entry `accounting_entry` rows, financial-year-scoped invoice numbers.
- **PR Review + Code Review:** unlimited.
- **Audit chain verification:** hash-chained `activity_audit` (V147 + V151) verifier surface in admin console (`GET /api/admin/audit-chain/verify`).

The MAX tier was retired in V165; references in code/UI map to TEAM.

---

## 3. Roles

Three orthogonal role dimensions exist. They are stored on different rows, checked in different places, and a single human user can have all three set simultaneously.

### 3.1 Global role — `AppUser.globalRole`

Source: [`AppUser`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java) column `global_role` (default `USER`). Authority: `ROLE_<globalRole>` on Spring Security ([`AppUserDetails`](../src/main/java/com/navneet/executespec/auth/security/AppUserDetails.java)).

| globalRole    | Granted to                              | What it unlocks                                                                                                                                                                                  |
| ------------- | --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `SUPER_ADMIN` | ExecuteSpec platform operators          | Full bypass on `RunAccessGuard`, full `RunVisibilitySpec` (all rows), all `/api/admin/**` endpoints incl. tier lifecycle, halt-all-runs, force-fail-task, re-review, prompt-rollback, audit-chain verify, executions reconcile, user/org status mutation, payment audit export. |
| `USER`        | Every signed-up user                    | Default. Visibility scoped to own row or own-org rows depending on org-membership; mutating endpoints subject to org-role gates.                                                                  |

### 3.2 Org role — `OrgMember.orgRole`

Source: [`OrgMember`](../src/main/java/com/navneet/executespec/auth/entity/OrgMember.java) column `org_role` (default `DEVELOPER`). Set when a user accepts an org invite or is added by an existing admin. Authority: `ROLE_<orgRole>` is also added to authorities.

| orgRole          | Granted to                                    | What it unlocks                                                                                                                                                            |
| ---------------- | --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ORG_ADMIN`      | Org owner / appointed admin                   | Update org profile + domains, add/remove/suspend members, change member roles, manage SSO, toggle V198 external-sharing flag, manage AI keys + JIRA/Confluence integrations, install GitHub App, manage spend cap, view audit log, adjust TEAM seats. Cannot self-suspend; last-admin guard prevents demotion. |
| `BILLING_ADMIN`  | Finance / billing operator                    | View `/api/billing/payment-history`, payment audits (org-scoped), invoices, accounting endpoints (GSTR-1 export, trial balance, revenue metrics) via the FINANCE permission path in `TierPermissionService`. Read-only on other domains. |
| `DEVELOPER`      | Standard team member                          | Read all org-scoped runs (`RunVisibilitySpec` admits `orgId` match), submit own runs, manage own integrations.                                                              |

`OrgService.removeMember` and `changeMemberRole` enforce a last-`ORG_ADMIN` guard. `OrgService.suspendMember` refuses self-suspension.

### 3.3 Project role — `ProjectMember.projectRole`

Source: [`ProjectMember`](../src/main/java/com/navneet/executespec/auth/entity/ProjectMember.java) column `project_role` (default `PROJECT_DEVELOPER`). V175 added `is_external` for cross-org collaborators. Project-role gate lives in [`ProjectAccessGuard`](../src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java).

| projectRole          | What they can do                                                                                                                                                                                |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `PROJECT_ADMIN`      | Read + write project, manage project members (add/remove), set settings (`aiProvider`, `githubOwner`), archive/unarchive/soft-delete/restore, dispatch tech-debt fix runs.                       |
| `PROJECT_DEVELOPER`  | Read project, submit runs against it, link repos, upload RAG documents, see PR reviews + code reviews.                                                                                          |
| `PROJECT_VIEWER`     | Read-only (V198): rejected by `assertCanWrite`. Can see runs, validation reports, architecture docs, code reviews, but cannot submit runs or mutate project state.                              |

Project access decision tree in `ProjectAccessGuard.assertCanRead`:

```
SUPER_ADMIN ----------------------------------------------> allow
caller.orgId == project.orgId ----------------------------> allow
ProjectMember row exists for (projectId, caller.userId) --> allow (incl. external)
otherwise --------------------------------------------------> 403
```

Cross-org `ProjectMember` invites (V175) plus the V198 `org.external_sharing_enabled` flag together gate "external sharing": to invite an outside user, the inviter must be tier PRO+ AND ORG_ADMIN of their own org (or SUPER_ADMIN) AND the org-level flag must be `true`. The public read-only share-link path is independent of the V198 flag and is governed by `tier_config.share_link_active_cap`.

Seven roles total: 2 global + 3 org + 3 project. Authority composition for a given user is `ROLE_<globalRole>` ∪ `ROLE_<orgRole>` (project roles are not Spring authorities — they are looked up explicitly per-request by `ProjectAccessGuard`).

---

## 4. Run modes

### BUILD mode (default)

Full pipeline above. Triggered by every standard `POST /api/runs` call when `runMode` is omitted or `"BUILD"`. Goes through classifier, planner, plan-approval gate (PRODUCTION mode), executor, reviewer, validation report, push.

### PATCH mode (V182)

Single thin-agent loop against a failing-test spec; classifier/planner/reviewer are skipped. The per-provider iteration caps are Anthropic 150, OpenAI 100, Gemini 120 (set in `SequentialExecutor.tierConfigFor`). Patch mode is dispatched by [`RunOrchestrator.runPatchPipeline`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java) and uses [`PatchAgent`](../src/main/java/com/navneet/executespec/agent/PatchAgent.java) directly.

Outcome → status mapping in `runPatchPipeline`:

| `PatchAgent` outcome                                      | `pipeline_run.status` |
| --------------------------------------------------------- | --------------------- |
| `COMPLETED` / `COMPLETED_BY_STAGNATION` / `STOPPED_WITHOUT_COMPLETE` | `COMPLETE`            |
| `ITERATION_LIMIT_EXCEEDED` / `BUDGET_EXCEEDED`            | `PARTIAL`             |
| `ERROR`                                                   | `FAILED`              |

PATCH-mode callers:

| Caller                                       | Source                                                                                                                                    | Auto-push |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- | --------- |
| **SWE-bench Lite adapter** (bench-only)      | `swebench/`                                                                                                                               | no        |
| **ProgramBench adapter** (bench-only)        | `programbench/`                                                                                                                           | no        |
| **PR Review autofix** (Phase 1)              | [`PrReviewAutofixService`](../src/main/java/com/navneet/executespec/prreview/) → [`FindingPatchSubmitter`](../src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java) | yes (existing branch via `branchOverride`) |
| **TechDebt autofix** (Phase 2)               | [`TechDebtFixService`](../src/main/java/com/navneet/executespec/project/techdebt/TechDebtFixService.java)                                  | yes (fresh PR per category) |
| **CI failure autofix** (Phase 3)             | [`CiFailureAutoFixService`](../src/main/java/com/navneet/executespec/project/pr/CiFailureAutoFixService.java) (flag `ci.autofix.enabled`, default OFF; max 3 attempts/PR) | yes |
| **Code Review autofix** (Phase 4)            | [`CodeReviewAutofixService`](../src/main/java/com/navneet/executespec/codereview/)                                                          | yes (one PR per severity) |

All four autofix surfaces go through `FindingPatchSubmitter`, which dedupes per finding-key via `pipeline_run.triggered_by_finding` (V183) before dispatching. PATCH-mode runs whose `source` starts with `"finding:"` get the post-edit formatter pass + auto-push at terminate.

---

## 5. Tier capability matrix

Cell values derived from V21/V27/V29/V35/V37/V42/V58/V145/V152/V164/V167/V172/V175 migrations + [`TierEnforcementService`](../src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java) + [`TierPermissionService`](../src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java) + [`TierConfig`](../src/main/java/com/navneet/executespec/auth/entity/TierConfig.java) + frontend gating in [`ResultPage`](../frontend/src/signal/pages/ResultPage.tsx), [`ComposePage`](../frontend/src/signal/pages/ComposePage.tsx), [`MorePage`](../frontend/src/signal/pages/MiscPages.tsx), [`ProjectMembersPage`](../frontend/src/signal/pages/project/ProjectMembersPage.tsx).

Notation: `OK` = supported; `—` = not supported; `lim N` = limited to N per period; `BYOK` = bring-your-own-keys; `flag` = also gated by per-org flag; `grandfathered` = no new sign-ups.

| Capability                                              | FREE                | PRO (HIDDEN, grandfathered) | TEAM ($39/seat)          | ENTERPRISE              |
| ------------------------------------------------------- | ------------------- | --------------------------- | ------------------------ | ----------------------- |
| **Submit a run (BUILD mode)**                           | OK                  | OK                          | OK                       | OK                      |
| **Submit a run (PATCH mode, autofix surfaces)**         | OK                  | OK                          | OK                       | OK                      |
| **Monthly run cap**                                     | 5                   | 30                          | unlimited                | unlimited               |
| **Concurrent runs**                                     | 1                   | 3                           | 10                       | 10                      |
| **High-complexity plans**                               | —                   | OK                          | OK                       | OK                      |
| **Per-run cost ceiling (USD)**                          | $3.00               | $5.00                       | $8.00 (or unlimited BYOK) | per-contract; BYOK unlimited |
| **User-supplied cost cap (`userCostCap` field)**        | OK (≤ platform cap) | OK                          | OK                       | OK                      |
| **BYOK exemption from platform cap**                    | —                   | —                           | OK                       | OK                      |
| **Plan-approval gate (PRODUCTION mode)**                | OK                  | OK                          | OK                       | OK                      |
| **Skip approval (`skipApproval`, PROTOTYPE mode)**      | —                   | OK                          | OK                       | OK                      |
| **Pause/resume run (V168, flag `v2.pause.enabled`)**    | —                   | OK                          | OK                       | OK                      |
| **Forks of existing runs**                              | OK                  | OK                          | OK                       | OK                      |
| **Retry single failed task**                            | OK                  | OK                          | OK                       | OK                      |
| **Apply-suggestion calls per run**                      | 2                   | 10                          | unlimited                | unlimited               |
| **Preview start (Cloud Run / Docker, 5-min TTL)**       | 2 / run             | 5 / run                     | unlimited                | unlimited               |
| **Deploy to Cloud Run (permanent)**                     | —                   | OK (max 5 deployments)      | OK (unlimited)           | OK (unlimited)          |
| **Auto-retry failed run**                               | —                   | up to 2                     | up to 3                  | up to 3                 |
| **GitHub PR creation (single-repo)**                    | OK                  | OK                          | OK                       | OK                      |
| **Multi-repo runs (cross-linked PRs)**                  | —                   | OK                          | OK                       | OK                      |
| **Validation report (PDF + share link)**                | OK                  | OK                          | OK                       | OK                      |
| **Pre-flight conversation refiner** (`/api/conversation`) | OK (50-msg cap)   | OK                          | OK                       | OK                      |
| **Per-project codemap + architecture doc**              | —                   | OK                          | OK                       | OK                      |
| **Per-project RAG (Tika + pgvector)**                   | —                   | OK                          | OK                       | OK                      |
| **Project memory + project chat**                       | —                   | OK                          | OK                       | OK                      |
| **PR Review agent**                                     | —                   | 10/month                    | unlimited                | unlimited               |
| **Code Review agent (whole-repo scan)**                 | —                   | 4/month                     | unlimited                | unlimited               |
| **Tech-debt rollup + fix dispatch**                     | —                   | OK                          | OK                       | OK                      |
| **CI failure auto-fix (Phase 3 webhook)**               | —                   | flag-gated                  | flag-gated               | flag-gated              |
| **Cross-org `ProjectMember` invites**                   | —                   | OK                          | OK + flag (V198)         | OK + flag (V198)        |
| **Public read-only share links**                        | 3 active            | 25 active                   | unlimited                | unlimited               |
| **Org membership (max members)**                        | n/a (personal org)  | 10                          | seat-bound (2–20)        | unlimited               |
| **Org invitations (domain enforcement)**                | n/a                 | OPEN                        | OPEN                     | ENFORCED + common-domain block |
| **SSO / SAML config**                                   | —                   | —                           | —                        | OK (schema; SAML handshake roadmap) |
| **Audit log view (own org)**                            | —                   | —                           | OK (ORG_ADMIN)           | OK (ORG_ADMIN)          |
| **Hash-chained audit verifier**                         | —                   | —                           | —                        | OK (SUPER_ADMIN endpoint) |
| **GDPR data export (Article 15+20)**                    | OK                  | OK                          | OK                       | OK                      |
| **Right-to-erasure (soft-delete account)**              | OK                  | OK                          | OK                       | OK                      |
| **GST invoicing (India)**                               | —                   | OK (Razorpay)               | OK (Razorpay/Stripe)     | OK                      |
| **Run top-up credit packs**                             | OK (purchase)       | OK                          | OK                       | OK                      |
| **Slack notifications (project channel mapping)**       | —                   | OK                          | OK                       | OK                      |
| **JIRA / Confluence / PagerDuty / ServiceNow integrations** | —               | partial                     | OK                       | OK                      |

Notes on enforcement:

- "Monthly cap" enforcement is in `TierEnforcementService.assertAndIncrementRunCount` with a pessimistic write lock on `app_user` (solo path) or `org` (org path). At-quota requests throw `BillingException` mapped to HTTP 402 by [`ValidationAdvice.onBilling`](../src/main/java/com/navneet/executespec/api/advice/ValidationAdvice.java).
- "Per-run cost ceiling" is the platform cap. `RunOrchestrator.clampHardCap(platformCap, userCostCap)` takes the min. BYOK orgs (`key_source_mode=OWN_KEYS`) skip the platform cap; HYBRID + SYSTEM stay capped.
- Cost-cap stop is a soft stop — the WorkerAgent gets `BUDGET_EXCEEDED` outcome and the run terminates `PARTIAL`. There is no mid-LLM-call abort today.
- 80% quota warning is emitted via `QUOTA_WARNING_THRESHOLD=0.80` in `TierEnforcementService`, surfaces as an in-app notification.

---

## 6. Quota + credit model

Three independent meters track usage:

### Meter 1 — Monthly run counter (calendar-window or billing-cycle-aware)

Stored at `app_user.personal_run_count` (solo path) or `org.monthly_run_count` (org path). Reset by `MonthlyRunCounterResetJob` (cron `0 0 0 1 * *` Asia/Kolkata, ShedLock'd) for PRO/TEAM/ENT users + every org. FREE users are skipped — they use the lifetime balance instead.

The billing-cycle-aware variant uses the active subscription's `current_period_start` so a PRO user whose subscription rolls over on the 17th gets their counter reset on the 17th, not the 1st.

### Meter 2 — Lifetime credit balance (FREE only, R-161)

`app_user.lifetime_run_credit_balance` (default 5 at signup, cap 25, V152). Top-up sources: registration grant (5), accepted referral (5 per friend), admin grant (`POST /api/admin/users/{id}/grant-runs`). When the monthly cap is hit, FREE users can still burn lifetime credits if available — `TierEnforcementService.assertAndIncrementRunCount` consumes from the bonus topup table next, then falls through to lifetime balance.

### Meter 3 — Bonus run topups (paid + admin-granted)

[`run_topup`](../src/main/java/com/navneet/executespec/billing/entity/RunTopup.java) rows with `source IN (PURCHASE, ADMIN_GRANT)`, FIFO-consumed by `RunTopupService.consumeBonusRun` (oldest active first). Purchase: never expires. Admin grant: 90-day default TTL. `RunTopupRepository.decrementRemaining` is an atomic `@Modifying` JPQL update guarded by `remaining > 0`.

### Credit formula

```
credits = max(1, ceil(bufferedEstimate / 5.0))
bufferedEstimate = estimateHigh * estimation-buffer    (estimation-buffer = 1.10 from application.yml)
```

`agent.cost.credit-multiplier-usd=5.0` is the dollars-per-credit knob. `estimation-buffer=1.10` is the 10% safety margin on top of the planner's high estimate.

Credits are quoted at plan submission, **charged at plan approval**, and refunded on FAILURE or CLEAN_WITH_INTERMEDIATE_WARNINGS PARTIAL.

`POST /api/runs/{id}/approve` requires `creditsConfirmed` to match the server-side computed credits (409 Conflict if stale); this closes the race where the user clicks Approve on an old quote after the planner has re-estimated higher.

### Refund flow

Implemented in [`RunQuotaRefundListener`](../src/main/java/com/navneet/executespec/billing/service/RunQuotaRefundListener.java) — a Spring `@EventListener` for `PipelineEvent.RunFailed` and `RunCompleted`. Refund fires when `pipeline_run.status=FAILED` OR `(status=PARTIAL && statusReason=CLEAN_WITH_INTERMEDIATE_WARNINGS)`. Refund quantity = `creditsCharged` (or `batchCredits` in batch mode). The listener calls `tierEnforcementService.decrementRunCount(userId, credits)` and `usageTrackingService.decrement(userId, "RUNS", credits)` to walk back both counters atomically.

### BYOK cost-cap exemption

Set in `RunOrchestrator.runPipeline` (and `runPatchPipeline`) via `tierEnforcementService.requiresCostCap(tier, hasByok)`. Returns `false` for `key_source_mode=OWN_KEYS` orgs, so `clampHardCap` skips the platform cap. HYBRID + SYSTEM orgs stay capped at $5–$8 depending on tier (`resolveRunCostBudget`).

---

## 7. Run lifecycle states

`pipeline_run.status` is the authoritative state. Values are string constants (not a Java enum), checked at multiple places in `StateStore` / `RunOrchestrator` / `SequentialExecutor`. Companion column `pipeline_run.status_reason` (V107) explains why a non-COMPLETE run ended.

```
                     +-----------+
   submit (202) ---->|  PLANNING |
                     +-----+-----+
                           |
                           |  classifier + planner emit Plan
                           v
                  +------- + -------+  (PROTOTYPE skips this)
                  | AWAITING_APPROVAL|  status_reason: null
                  +------- + -------+
                  approve | reject  | timeout (4h ExecutionRun)
                          v         v
                     +---------+   +--------+
              .------|EXECUTING |  | FAILED |  (reason = APPROVAL_TIMEOUT)
              |      | WORKING  |  +--------+
              |      +----+-----+
              |           |  per-task loop
              |           v
              |     +-----+-----+
              |     | REVIEWING |
              |     +-----+-----+
              |           |
              |           v
              |     +-----+-----+
              |     | VALIDATING|  (background; status stays REVIEWING in some paths)
              |     +-----+-----+
              |           |
              |     +-----+-----+        +-----------+
              |     | COMPLETE  | <----- | PARTIAL   |  status_reason in:
              |     +-----------+        +-----------+  CLEAN_COMPLETION /
              |                                          CLEAN_WITH_INTERMEDIATE_WARNINGS /
              |                                          FINAL_BUILD_FAILED /
              |                                          TESTS_FAILED:N_OF_M /
              |                                          REVIEWER_UNRESOLVED /
              |                                          REVIEWER_FAILED /
              |                                          ALL_TASKS_FAILED /
              |                                          LEGACY_UNKNOWN
              |
              |  cancel any time (RunOrchestrator.cancel) ------> +-----------+
              `------------------------------------------------>  | CANCELLED |
                                                                   +-----------+

   pause (flag v2.pause.enabled, only on WORKING/REVIEWING) ----> +--------+
                                                                  | PAUSED |
                                                                  +---+----+
                                                                      | resume (same JVM only)
                                                                      v
                                                                  back to previous status
```

### Status definitions

| Status               | Set by                                                                                          | Meaning                                                                                                                                       |
| -------------------- | ----------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `PLANNING`           | `RunOrchestrator.submitRun` on row creation                                                     | Classifier + outline planner running. SmartClassifier may run as a shadow.                                                                    |
| `AWAITING_APPROVAL`  | `StateStore.savePlan` after planner emits `Plan`; sets `current_gate=PLAN_APPROVAL`              | Plan persisted. PRODUCTION-mode runs wait here for `POST /api/runs/{id}/approve` body `{approved, creditsConfirmed}`. PROTOTYPE bypasses.    |
| `WORKING` / `EXECUTING` | `StateStore.markApproved` (legacy alias) → `SequentialExecutor.execute`                       | Per-task worker loop active. Tasks transition PENDING → LOCKED → WORKING → COMPLETE/PARTIAL/FAILED/BLOCKED.                                    |
| `REVIEWING`          | `StateStore.markReviewing`                                                                       | ReviewerAgent general + wiring passes. Project-type-aware. Reviewer can also fire on `POST /api/runs/{id}/re-review`.                          |
| `PAUSED`             | `RunOrchestrator.pause` (flag-gated, V168)                                                       | Pipeline future interrupted; `pause_requested=true`, `paused_at` stamped, `pause_count++`. Resume only same-JVM.                              |
| `COMPLETE`           | `StateStore.markComplete` — all non-gap tasks COMPLETE                                          | Terminal success. `status_reason` either null or `CLEAN_COMPLETION`.                                                                          |
| `PARTIAL`            | `StateStore.markComplete` when any non-gap task is BLOCKED/PARTIAL, OR `markPartial(runId, reason)` | Terminal partial. Always carries a `status_reason`. See list in 7.1 below.                                                                  |
| `FAILED`             | `StateStore.markFailed` or `RunQuotaRefundListener` event                                       | Terminal failure. Pre-execution failures (planner blew up, complexity gate rejected) and execution failures both land here.                   |
| `CANCELLED`          | `RunOrchestrator.cancel` via `StateStore.cancelRun`                                              | User cancellation. Signals plan gate, interrupts pipeline future, flips non-terminal tasks to CANCELLED. PR (if opened) is closed.            |

### 7.1 `status_reason` enumeration

Set by `RunOrchestrator.resolveTerminalStatus` / `derivedStatusReason` — derived **independent of any feature flag** (audit 2026-05-13).

| status_reason                          | Pipeline meaning                                                                                |
| -------------------------------------- | ----------------------------------------------------------------------------------------------- |
| `CLEAN_COMPLETION`                     | All tasks COMPLETE, reviewer CLEAN, final build passed.                                          |
| `CLEAN_WITH_INTERMEDIATE_WARNINGS`     | Tasks COMPLETE but a transient warning was raised mid-run. Credits **are refunded**.            |
| `FINAL_BUILD_FAILED`                   | Reviewer's wiring pass finished, but final smoke build failed.                                  |
| `TESTS_FAILED:N_OF_M`                  | N integration tests of M failed at the test-gen runtime check.                                  |
| `REVIEWER_UNRESOLVED`                  | Reviewer ran but verdict was `NEEDS_FIXES` and the wiring pass did not converge.                |
| `REVIEWER_FAILED`                      | Reviewer LLM call itself threw.                                                                  |
| `REVIEWER_INTERRUPTED_CODE_PRESERVED`  | Pause/cancel during reviewer; partial state retained.                                            |
| `ALL_TASKS_FAILED`                     | Every task in the plan terminated FAILED.                                                       |
| `RE_REVIEW_FAILED`                     | Manual `/re-review` re-run failed.                                                              |
| `RE_REVIEW_NO_WORKSPACE`               | `/re-review` could not locate the original workspace (e.g. GCS archived + reclaimed).            |
| `NO_REVIEW_RESULT`                     | Reviewer skipped (single-task / single-repo fast path) but pipeline did not land a verdict.     |
| `LEGACY_UNKNOWN`                       | Pre-V107 row; reconciliation path falls through. Should not occur for new runs.                  |

`ValidationReportService.reconcileWithRunStatus` (R-09) trusts `status_reason` over heuristic mapping when both are present, ensuring the dashboard and PDF agree.

### Cancel + pause + resume

- `DELETE /api/runs/{id}` → `RunOrchestrator.cancel(runId)` → cancels gate, interrupts future, closes any opened PR.
- `POST /api/runs/{id}/pause` → `RunOrchestrator.pause(runId)` (flag `v2.pause.enabled`, default OFF). Only valid on WORKING/REVIEWING. Sets `pause_requested=true`, `paused_at`, `pause_count++`, interrupts future. UI shows pause attempts via `pause_count`.
- `POST /api/runs/{id}/resume` → `RunOrchestrator.resume(runId)`. Returns `ResumeOutcome` enum: `OK | NOT_PAUSED | WORKSPACE_GONE | NOT_FOUND`. Same-JVM only — cross-JVM workspace shipping is roadmap. Workspace freshness check: if `<workspace>/<runId>/` is gone (e.g. GCE restart, GCS archive), the resume returns `WORKSPACE_GONE`.
- `POST /api/runs/{id}/sync` and `/api/runs/{id}/sync/original` — reset workspace to current `origin/HEAD` of each linked repo, or to the SHAs captured at run start (from `run_repo_snapshot`).

### Crash recovery

[`StartupRunResumer`](../src/main/java/com/navneet/executespec/recovery/StartupRunResumer.java) fires on `ApplicationReadyEvent`, finds non-terminal runs from previous JVM lifetimes (`resume_attempts < MAX_RESUME_ATTEMPTS`), and calls `RunOrchestrator.resumeAfterCrash(runId)` per row. Returns `CrashResumeOutcome` enum: `OK | NOT_FOUND | NOT_APPLICABLE | EXHAUSTED | WORKSPACE_GONE | PLAN_MISSING`. The `agent.recovery.auto-resume-on-startup` flag controls whether this fires (default `false` on prod, `true` on dev).

---

## 8. Verified score

Source of truth: [`ValidationReportService.computeVerifiedScoreMultiplicative`](../src/main/java/com/navneet/executespec/report/ValidationReportService.java). Persisted to `validation_report.verified_score` (V106) and surfaced everywhere a run's headline number is shown.

### Formula

```
verifiedScore = clamp(0, 100,
                      100 × buildFactor
                          × testFactor
                          × reviewerFactor
                          × endpointFactor
                          × criticalDamper)
```

| Factor             | Range     | Computation                                                                                                                                                |
| ------------------ | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `buildFactor`      | 0.0 / 1.0 | `1.0` if `finalBuildPassed == true`, else `0.5` (we still ship a number even on broken build; the reviewer captures the failure).                          |
| `testFactor`       | 0.3 – 1.0 | `(testsPassed / testsTotal)` if `testsTotal > 0`; otherwise derived from `testCoverageLevel`: COMPREHENSIVE/GOOD=1.0, PARTIAL=0.7, NONE=0.3, default=0.5.   |
| `reviewerFactor`   | 0.5 – 1.0 | CLEAN=1.0, FIXED_ISSUES=0.95, UNRESOLVED_ISSUES=0.75, REVIEW_FAILED=0.5.                                                                                  |
| `endpointFactor`   | 0.5 – 1.0 | `endpointCoveragePercent / 100`, floored at 0.5.                                                                                                            |
| `criticalDamper`   | 0.4 – 1.0 | `1.0 - 0.1 × unfixedCriticalCount` (clamped to ≥ 0.4). Excludes synthesized `SYNTH-ACC-*` accuracy hallucinations.                                          |

### Trust gates (factor floors)

Two trust gates lift specific factors back to `reviewerFactor` when the reviewer is confident; they prevent the AccuracyAssessment LLM's read-budget-limited counters from dragging down a run that the reviewer says is fine.

**Gate 1 — `endpointFactor` floor** (since 2026-05-10, PR #95):

```
if (reviewerVerdict in {CLEAN, FIXED_ISSUES}) {
    endpointFactor = max(endpointFactor, reviewerFactor)
}
```

**Gate 2 — `testFactor` floor** (since 2026-05-16):

```
if (testsTotal == 0
    && endpointCoveragePercent >= 95
    && reviewerVerdict in {CLEAN, FIXED_ISSUES}
    && testCoverageLevel == PARTIAL) {
    testFactor = max(testFactor, reviewerFactor)
}
```

Gate 2 fires only when the spec asked for minimal tests, the AccuracyAssessment endpoint count is high (≥95% coverage), and the reviewer is happy — closing the false-negative class where a run shipped a clean PR but `testCoverageLevel=PARTIAL` was scoring it 70 instead of 95.

### 4-band thresholds

The frontend buckets runs by score for the All Runs filter tabs in [`AllRunsPage`](../frontend/src/signal/pages/AllRunsPage.tsx) and the verdict copy in [`ResultPage`](../frontend/src/signal/pages/ResultPage.tsx):

| Band            | Score range  | UI label                | Filter tab          |
| --------------- | ------------ | ----------------------- | ------------------- |
| Shippable       | ≥ 85         | "Shippable"             | `/runs?filter=ship` |
| Needs review    | 70 – 84      | "Needs review"          | `/runs?filter=needs`|
| Failed          | < 70         | "Failed"                | `/runs?filter=fail` |
| Live            | (no score yet — non-terminal) | "In progress" | `/runs?filter=live` |

(`MiscPages.SharedPage` uses 90/70 historically — drift acknowledged in research note; aligned to 85/70 in `AllRunsPage` and `ResultPage`.)

### Companion: `rubricScore` (V194 / PR-E)

Additive sibling to the multiplicative score, persisted to `validation_report.rubric_score`. Computed by `ValidationReportService.computeRubricScore`:

```
rubricScore = buildPoints (25)
            + testPoints (20)
            + reviewerPoints (20)
            + endpointPoints (15)
            + criticalDamperPoints (10)
            + qualityPoints (10)
            = 100 total
```

Spec-aware via `rubricTestPointsFromCoverageLevel` when `testsTotal==0` (PR-R, 2026-05-14). The multiplicative `verifiedScore` remains the headline number; `rubricScore` is shown alongside for transparency and is the easier number to explain to a non-technical stakeholder ("you lost 5 of the 20 reviewer points because there were two unresolved minor issues").

### Backfill

[`VerifiedScoreBackfillService`](../src/main/java/com/navneet/executespec/report/VerifiedScoreBackfillService.java) — SUPER_ADMIN-triggered idempotent recompute of every row using the corrected formula plus the `SYNTH-ACC-*` filter for hallucinated accuracy issues. Caveat: the backfill currently calls the 6-arg overload (without `testCoverageLevel`), so the 2026-05-16 Gate-2 floor is **not** retroactively applied to historical rows; only Gate-1 (endpoint) fires for them.

---

## 9. Non-goals

What ExecuteSpec explicitly does **not** do today:

- **Real-time multi-user co-editing of specs.** Spec composition is single-user. Pre-flight refinement via `/api/conversation` is a 50-message single-session helper, not a collaborative editor.
- **Mid-LLM-call abort on cost-cap stop.** When the budget is exceeded, the WorkerAgent finishes its current turn, gets `BUDGET_EXCEEDED` outcome, and the run terminates PARTIAL. There is no signal that interrupts an in-flight LLM HTTP request. The user-facing "Stop" button on the live page cancels at the next iteration boundary.
- **Cross-JVM workspace shipping for resume.** Pause/resume and crash-recovery only work when the same JVM still has the workspace dir on local disk. Cross-MIG-node resume via GCS workspace shipping is on the backlog; today, `RunOrchestrator.resumeAfterCrash` returns `WORKSPACE_GONE` if the dir is missing.
- **Pre-merge "review again before push" gate.** The reviewer fires once. The auto-push at terminate is on a successful COMPLETE/PARTIAL. There is no second review gate after the push, only the four customer-facing autofix surfaces that can rescue a PR post-push.
- **V2 batch execution.** The `batch/` package (`BatchCoordinator`, `BatchResumeService`, `BatchClientRouter`) is V2 batch-mode scaffolding. `submit(...)` and `resume(...)` both throw `UnsupportedOperationException` today. Flag: `harness.v2.batch_enabled` (off by default).
- **SAML SSO handshake.** [`SsoConfigController`](../src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java) persists SSO config (provider, metadata URL, domain) but refuses `sso_enforced=true` on SAML providers and returns 409. The Spring Security OIDC paths (Google, GitHub) are the only working SSO surfaces today. OKTA_SAML / AZURE_AD_SAML are schema-only.
- **CI failure auto-fix in production-default.** The Phase 3 webhook ([`CiFailureAutoFixService`](../src/main/java/com/navneet/executespec/project/pr/CiFailureAutoFixService.java)) is gated by feature flag `ci.autofix.enabled` (default OFF), per-PR cap 3 attempts, and is not enabled until SWE-bench Lite resolved% justifies the unattended-bot loop. The other three autofix surfaces (PR Review, TechDebt, Code Review) are human-triggered.
- **Auto-merge of opened PRs.** ExecuteSpec opens the PR and, when configured (`project.default_reviewers`), requests reviewers. Merging is the customer's decision. The `PostMergeCascadeService` fires on the GitHub webhook for `closed.merged`, not before.
- **Per-task mid-LLM-call provider failover within a turn.** Provider failover happens between LLM calls via `LlmRouter`'s YAML `failover-order`. If the assistant has already emitted a partial tool call and the connection drops mid-stream, the turn ends with the partial result and the next iteration retries from the saved conversation state.
- **In-house payment processing.** Payments are processed exclusively through Razorpay (INR-default in dev) and Stripe (USD-default in prod). HMAC signatures verified on every webhook. There is no PCI scope on ExecuteSpec's side beyond webhook verification.
- **Auto-rollback of deployed previews on cost overrun.** Cloud Run previews carry a 5-min TTL and self-destruct. Permanent deployments (`/api/deployments`) do not auto-roll-back. `CostOutlierDetector` emits a metric + log at `actualCost > 2× costEstimateHigh` but does not refund or cancel.
- **Public anonymous run submission with persistence beyond a session.** [`AnonymousRunController`](../src/main/java/com/navneet/executespec/api/AnonymousRunController.java) accepts anonymous trials, gated by `v3.anonymous-trial.real-dispatch` flag. Session tokens TTL out; there is no anonymous-to-anonymous shareable runId.
- **A model "always-best" router.** The `model-matrix` YAML maps `(agent, provider, tier)` → model deterministically. `ModelResolver.selectAdaptiveProvider` does adapt provider based on historical success rate, but this is a known leak past the `-Dexecutespec.strict-provider=true` flag (tracked as Bug #1c). It is not a "model marketplace" with continuous evaluation.
- **Native mobile app.** The frontend is responsive (mobile-first verified per memory rule), but there is no React Native or native iOS/Android build. Marketing references to "Go mobile" point to the `expo-app-shell` readymade scaffold for the *customer's* product, not ExecuteSpec itself.
- **Per-project billing or chargeback.** Spend caps are per-org (`org.spend_cap_usd_monthly`, M-032) and per-project (`project.spend_cap_usd_monthly`, M-033). The dashboards aggregate, but invoices are issued to the org/user, not split by project.

---

## Appendix — Concrete file references

| Concern                                | File                                                                                                                                                                                                                                  |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Run submit surface                     | [`api/controller/RunController.java`](../src/main/java/com/navneet/executespec/api/controller/RunController.java)                                                                                                                       |
| Run request body                       | [`api/dto/RunRequest.java`](../src/main/java/com/navneet/executespec/api/dto/RunRequest.java)                                                                                                                                            |
| Run orchestrator                       | [`integration/RunOrchestrator.java`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)                                                                                                                          |
| Pipeline state mutations               | [`state/StateStore.java`](../src/main/java/com/navneet/executespec/state/StateStore.java)                                                                                                                                                 |
| Run mode constants                     | [`state/RunMode.java`](../src/main/java/com/navneet/executespec/state/RunMode.java)                                                                                                                                                       |
| Patch-mode agent                       | [`agent/PatchAgent.java`](../src/main/java/com/navneet/executespec/agent/PatchAgent.java)                                                                                                                                                 |
| Tier capability data                   | [`auth/entity/TierConfig.java`](../src/main/java/com/navneet/executespec/auth/entity/TierConfig.java)                                                                                                                                     |
| Tier capability lookup                 | [`auth/service/TierPermissionService.java`](../src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java)                                                                                                              |
| Tier quota enforcement                 | [`auth/service/TierEnforcementService.java`](../src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java)                                                                                                            |
| Tier lifecycle state machine           | [`billing/lifecycle/TierLifecycleService.java`](../src/main/java/com/navneet/executespec/billing/lifecycle/TierLifecycleService.java)                                                                                                      |
| Billing core                           | [`billing/service/BillingService.java`](../src/main/java/com/navneet/executespec/billing/service/BillingService.java)                                                                                                                      |
| Billing entities                       | [`billing/entity/BillingPlan.java`](../src/main/java/com/navneet/executespec/billing/entity/BillingPlan.java), [`BillingSubscription.java`](../src/main/java/com/navneet/executespec/billing/entity/BillingSubscription.java), [`RunTopup.java`](../src/main/java/com/navneet/executespec/billing/entity/RunTopup.java) |
| Refund listener                        | [`billing/service/RunQuotaRefundListener.java`](../src/main/java/com/navneet/executespec/billing/service/RunQuotaRefundListener.java)                                                                                                       |
| Top-up service                         | [`billing/service/RunTopupService.java`](../src/main/java/com/navneet/executespec/billing/service/RunTopupService.java)                                                                                                                    |
| Run/PipelineRun entity                 | [`state/entity/PipelineRun.java`](../src/main/java/com/navneet/executespec/state/entity/PipelineRun.java)                                                                                                                                  |
| AppUser entity                         | [`auth/entity/AppUser.java`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java)                                                                                                                                            |
| Org entity                             | [`auth/entity/Org.java`](../src/main/java/com/navneet/executespec/auth/entity/Org.java)                                                                                                                                                    |
| OrgMember entity                       | [`auth/entity/OrgMember.java`](../src/main/java/com/navneet/executespec/auth/entity/OrgMember.java)                                                                                                                                        |
| ProjectMember entity                   | [`auth/entity/ProjectMember.java`](../src/main/java/com/navneet/executespec/auth/entity/ProjectMember.java)                                                                                                                                |
| Run access guard                       | [`auth/security/RunAccessGuard.java`](../src/main/java/com/navneet/executespec/auth/security/RunAccessGuard.java)                                                                                                                          |
| Project access guard                   | [`project/security/ProjectAccessGuard.java`](../src/main/java/com/navneet/executespec/project/security/ProjectAccessGuard.java)                                                                                                            |
| Run visibility spec                    | [`auth/security/RunVisibilitySpec.java`](../src/main/java/com/navneet/executespec/auth/security/RunVisibilitySpec.java)                                                                                                                    |
| Verified score formula                 | [`report/ValidationReportService.java`](../src/main/java/com/navneet/executespec/report/ValidationReportService.java) (method `computeVerifiedScoreMultiplicative`)                                                                       |
| Validation report entity               | [`report/entity/ValidationReport.java`](../src/main/java/com/navneet/executespec/report/entity/ValidationReport.java)                                                                                                                      |
| Score backfill                         | [`report/VerifiedScoreBackfillService.java`](../src/main/java/com/navneet/executespec/report/VerifiedScoreBackfillService.java)                                                                                                            |
| Sharing (cross-org invites + share links) | V175, V198 migrations; [`auth/sso/SsoConfigController.java`](../src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java); `OrgService.updateExternalSharing`                                                            |
| Application config (tiers, models)     | [`src/main/resources/application.yml`](../src/main/resources/application.yml) (`model-matrix.*`, `app.tiers.*`)                                                                                                                            |
| Tier seed migrations                   | V21 (caps + concurrent runs), V27 (apply-suggestion), V29 (preview-starts), V35 (deploy), V42 (auto-retries), V58 (FREE max cost USD), V145 → V167 (FREE = 5 runs flop), V164 (TEAM deploy fix), V165 (drop MAX), V172 (PR/Code Review quotas), V175 (share-link cap), V198 (external sharing flag) |
| Finding patch submitter (4 autofix)    | [`finding/FindingPatchSubmitter.java`](../src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java)                                                                                                                       |
| Multi-repo PR push                     | [`integration/scm/ScmPushService.java`](../src/main/java/com/navneet/executespec/integration/scm/ScmPushService.java)                                                                                                                      |

Head migration: **V199** (org/user tier drift backfill, closes BUG-009).
