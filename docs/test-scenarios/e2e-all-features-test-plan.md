# ExecuteSpec — End-to-End Test Plan (all features)

> Version: v2.5.12 (2026-04-17)
> Status: ready to execute against a freshly migrated dev environment
> Scope: covers every user-facing feature surface documented in `CLAUDE.md`
> Format: numbered scenarios, explicit preconditions, verifiable steps

## How to use this plan

- Each scenario has an ID (`AUTH-01`, `RUN-04`, etc.). Keep ID stable across revisions.
- A run of this plan produces a filled-in copy per branch / environment.
- Mark each step **PASS** / **FAIL** / **BLOCKED** / **SKIPPED**. Attach screenshots / curl outputs for failures.
- Some scenarios depend on seeded state (e.g. `PROJ-01` needs a linked GitHub PAT). Preconditions are listed per scenario — run them once for the whole session.

## Environment setup

Before running any scenario:

```bash
# Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dexecutespec.strict-provider=true"

# Frontend (separate terminal)
npm --prefix frontend run dev    # :5173

# Postgres + Redis running (5432, 6379)
# Flyway migrations apply on boot (V1 — V112 as of this plan)
```

Required env vars: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`, `GITHUB_PAT` (for linked-repo scenarios), `RAZORPAY_KEY_*` or `STRIPE_SECRET_KEY` (for billing), plus JWT secret and OAuth2 creds.

Seed admin: `admin@executespec.ai` / `Admin@123` (SUPER_ADMIN, ENTERPRISE — from V5).

---

## 1 · Auth & Onboarding

### AUTH-01 · email + password register → verify → login
**Preconditions:** SMTP configured; email inbox reachable.
1. `POST /api/auth/register` with email + password + first/last name.
2. Confirm 201; verify email row in `app_user` table is UNVERIFIED.
3. Click the verification link (or `GET /api/auth/verify-email?token=…`).
4. `POST /api/auth/login` with the same credentials.
5. Expect 200 + `{accessToken, refreshToken, user: {tier:"FREE", ...}}`.

**Expected:** tokens returned; user tier = FREE; verified flag true.

### AUTH-02 · OAuth2 login (Google + GitHub)
**Preconditions:** OAuth2 client ids + secrets configured.
1. Visit `/api/auth/oauth2/authorize?provider=google`.
2. Complete the consent screen with a fresh Google account.
3. Land on `/auth/callback?code=…`; frontend exchanges for tokens.
4. Repeat with GitHub.

**Expected:** both providers land the user on `/dashboard`; `users.oauth_provider` + `oauth_sub` populated.

### AUTH-03 · forgot + reset password
1. `POST /api/auth/forgot-password` with a known email.
2. Receive reset email (check logs if SMTP disabled).
3. `POST /api/auth/reset-password` with token + new password.
4. Old password rejected on login; new password accepted.

### AUTH-04 · refresh + logout
1. Login to get `refreshToken`.
2. `POST /api/auth/refresh` with refresh token → new access token.
3. `DELETE /api/auth/logout` → refresh cookie cleared; reusing old refresh fails.

### AUTH-05 · `mustChangePassword` flow (admin-invited user)
**Preconditions:** admin invite seeded the user with `mustChangePassword=true`.
1. Login.
2. Modal (`ChangePasswordModal` — migrated to `ResponsiveModal` v2.5.12, has no close button or escape, `disableDismiss=true`) blocks the dashboard.
3. Set a strong password passing complexity rules.
4. `must_change_password` flips false; user lands on dashboard.

### AUTH-06 · scoped SSE token (5-min TTL)
1. Submit a run.
2. `GET /api/runs/{id}/sse-token` → scoped JWT (scope=sse, runId=<id>).
3. `GET /api/runs/{id}/events` with the scoped token → event stream opens.
4. Try the same token on a different run id → 403.
5. Wait 5 min + 30 s and retry → 401 (token expired).

---

## 2 · User, Org, RBAC

### ORG-01 · create org, invite developer
1. `POST /api/org` as an authenticated user (becomes ORG_ADMIN of the new org).
2. `POST /api/org/invite` with a new email + role=DEVELOPER.
3. Recipient accepts via `AcceptInvitePage`; joins the org with role DEVELOPER.

### ORG-02 · tier gating — ENTERPRISE feature behind PRO user
1. As a PRO user, try to open the Execution View (feature flag `execution.view.enabled` + `tier_config.execution_view_enabled` gate).
2. Expect the `LockedFeature` + `UpgradeModal` (migrated v2.5.12 — uses `ResponsiveModal`).
3. Upgrade to ENTERPRISE via Billing; feature becomes accessible.

### ORG-03 · admin SUPER_ADMIN reconciles orphan runs
1. Create an `ExecutionRun` stuck in non-terminal status > 30 min (fixture or manual insert).
2. `POST /api/admin/executions/reconcile` as SUPER_ADMIN.
3. Row flips to FAILED with reason `reconciled_orphan_v251`.

---

## 3 · Billing & Payments

### BILL-01 · subscribe to PRO via Razorpay (INR)
**Preconditions:** `RAZORPAY_KEY_ID`/`SECRET` configured.
1. `GET /api/billing/plans` → shows PRO.
2. `POST /api/billing/subscribe` (gateway=razorpay, plan=PRO).
3. Razorpay checkout loads; pay with test card 5500 6700 0000 1002.
4. Webhook `/api/webhooks/razorpay` (HMAC verified) flips the user tier to PRO.
5. `/api/billing/payment-history` shows the transaction.

### BILL-02 · subscribe via Stripe (USD)
**Preconditions:** `STRIPE_SECRET_KEY` + `STRIPE_WEBHOOK_SECRET`.
1. Same flow with gateway=stripe.
2. `stripe listen --forward-to localhost:8080/api/webhooks/stripe` locally.
3. Tier flips PRO; invoice number persisted.

### BILL-03 · run top-up purchase
1. As FREE user hitting quota.
2. `POST /api/billing/topup/purchase` with 10 runs.
3. After webhook, `GET /api/auth/me/permissions` reflects +10 bonus runs.
4. Submit a run that previously 402-ed; succeeds.

### BILL-04 · PARTIAL refund on failure
1. Submit a PRODUCTION run that will FAIL on the plan gate rejection path (approve=false).
2. `pipeline_run.credits_charged` was N; `RunQuotaRefundListener` decrements `monthly_run_count` by N after the run terminates.

### BILL-05 · CLEAN_WITH_INTERMEDIATE_WARNINGS keeps quota intact
**Regression guard for the v2.5.12 refund fix (`83bb4a54`).**
1. Submit a multi-task run that produces an intermediate smoke-test warning but builds at the end.
2. Final status = COMPLETE, status_reason = CLEAN_WITH_INTERMEDIATE_WARNINGS.
3. Refund listener must NOT fire; monthly_run_count unchanged.

---

## 4 · Run Lifecycle (V2)

### RUN-01 · single-shot PROTOTYPE happy path (Anthropic)
**Spec:** "Write a Python function that returns the first N primes."
1. `POST /api/runs` with executionMode=PROTOTYPE, aiProvider=ANTHROPIC.
2. SSE `PLAN_CREATED` → `TASK_STARTED` → `TASK_COMPLETED` → `REVIEW_COMPLETE` → `RUN_COMPLETE`.
3. Status chain: PLANNING → WORKING → REVIEWING → COMPLETE.
4. `GET /api/runs/{id}/report` returns verified_score ≥ 80, accuracy_llm present, reviewer_verdict=CLEAN.
5. Preview auto-starts; `PreviewTab` shows a URL.
6. Notification bell shows "Run complete: …".

### RUN-02 · PRODUCTION with plan-approve gate
1. Submit with executionMode=PRODUCTION.
2. Status hangs at AWAITING_APPROVAL; SSE `GATE_OPENED` with credits + cost.
3. **New v2.5.12 check:** notification bell fires GATE_OPENED_PLAN_APPROVAL notification (bell count increments in <2s).
4. Approve via UI; quota is charged; run proceeds.
5. Cancel instead of approving: `DELETE /api/runs/{id}` → CANCELLED + terminal SSE.

### RUN-03 · quota 80% warning (new v2.5.12)
**Preconditions:** FREE user with 4/5 personal runs used (80%).
1. Submit a 1-credit run (from 4 → 5).
2. Check that `QuotaEvent.QuotaWarning` fires → notification bell shows "Approaching run limit — 100% used".
3. Reset counter and repeat crossing 80% exactly (3 → 4 of 5) → single warning fires.
4. Submit again next month (reset counter) → fresh warning fires (dedupe key includes YearMonth).

### RUN-04 · cost-proportional credit charging
**Spec:** a multi-task spec that estimates > $5.
1. Submit; plan gate shows `creditsCharged=2, hardCapUsd=$15`.
2. Approve: `user.personal_run_count` or `org.monthly_run_count` increments by 2.
3. Actual cost exceeds $15 mid-run → budget-exceeded event; worker stops cleanly.

### RUN-05 · FREE tier blocked on multi-credit spec
1. As FREE user, submit a spec estimating > $5 (requires 2+ credits).
2. Plan gate returns a "FREE tier limited to 1-credit specs" hint in the UI; approve flow shows Upgrade + Top-up nudges.
3. Purchase top-up or upgrade → approve succeeds.

### RUN-06 · V1 legacy endpoints return 410
1. `POST /api/runs/{v2-run}/merge` → 410.
2. `POST /api/runs/{v2-run}/retry` → 410.
3. `POST /api/runs/{v2-run}/clarify` → 410.
4. Frontend's `STATUS_MESSAGES['410']` entry renders the "no-longer-available" copy.

### RUN-07 · concurrent plan-approve race (v2.5.12 root-cause fix)
**Regression guard for V112 `@Version` fix.**
1. Submit a PRODUCTION run; wait for AWAITING_APPROVAL.
2. Open two browser tabs; in both press Approve simultaneously.
3. One tab receives 202/accepted.
4. **The other tab receives 409 Conflict with `{error: "optimistic_lock_conflict", ...}`** from `OptimisticLockAdvice`. No silent overwrite.
5. Tab that got 409 reloads; run is already WORKING.

### RUN-08 · task retry
1. Take a PARTIAL run with one FAILED task.
2. `POST /api/runs/{id}/tasks/{taskId}/retry`.
3. Task re-enters WORKING; eventually COMPLETE. Run status may flip PARTIAL → COMPLETE.

### RUN-09 · cancel interrupts worker
1. Submit a long HIGH-complexity spec; wait for WORKING.
2. `DELETE /api/runs/{id}` → thread interrupted, CANCELLED written within 5 s.
3. Notification fires RUN_FAILED with reason "cancelled".

### RUN-10 · pro-active budget hard-cap
1. Submit a run where the worker generates ~$15 of tokens, but credits paid only $5.
2. At `costTracker.isOverBudget()` → `WorkerAgentV2` emits budgetExceeded and stops cleanly.
3. Run ends PARTIAL; refund path respects `CLEAN_WITH_INTERMEDIATE_WARNINGS` if applicable.

---

## 5 · Project Workspace

### PROJ-01 · create project + link existing GitHub repo
**Preconditions:** `GITHUB_PAT` in Settings with `repo` scope.
1. `POST /api/projects` with name + description.
2. `POST /api/projects/{id}/repos/{repoId}` → links an existing ProjectRepo.
3. Optional: install webhook via `POST /api/repos/{repoId}/webhook` → `webhookInstalled=true`; chip shows green.
4. `POST /api/projects/{id}/analyse` → codemaps + architecture.md generated, summary available.

### PROJ-02 · architecture diff (LCS) + source-file changes (v2.5.5)
1. Generate architecture version N.
2. Make a change in a linked repo (push new commit on default branch).
3. Re-analyse → new version N+1.
4. Open `/architecture` tab; click "Diff vs v{N-1}".
5. Text diff renders; under it, **source-file changes section** with per-repo added/modified/removed/renamed (`GET /api/projects/{id}/architecture/diff`).

### PROJ-03 · upload + index a document (pgvector)
1. Drag-drop a PDF into `ProjectDocumentsPanel`.
2. Status progresses UPLOADED → EXTRACTING → EMBEDDING → READY.
3. Trigger a run with the project; planner prompt includes `{{REFERENCE_DOCS}}` via `RetrievalService.retrieve`.

### PROJ-04 · chat panel Q&A over project + docs
1. Open `ProjectChatPanel`.
2. Ask "What endpoints does the backend expose?".
3. Reply cites codemap entries; token usage visible.

### PROJ-05 · memory tab — compaction at R20
1. Run 20 completed runs under the same project.
2. Memory tab shows compacted snapshot (≥ v1) plus recent ≤ 10 run ledger entries.
3. `GET /api/projects/{id}/memory` returns snapshot metadata + ledger count.

### PROJ-06 · workspace health rollup (v2.5.2)
1. Open project Overview.
2. `WorkspaceHealthPanel` shows per-repo SHA drift (codemap vs origin HEAD), webhook status, open-PR count.
3. Push a new commit to the upstream default branch.
4. Refresh after the 10-min Redis cache expires → "N commits ahead" chip.
5. Click "Analyse all" → re-runs analyse; drift clears.

### PROJ-07 · spec template CRUD (v2.5.2)
1. `ExecutionSetup` → "Save current as template".
2. Reload project; template in `TemplatePicker`.
3. Create an org-scoped template as ORG_ADMIN → visible to all project members.
4. Delete soft-deletes; no longer appears.

### PROJ-08 · tech-debt panel aggregates run history (v2.5.2)
1. Submit 5+ runs; at least 2 failing or with review issues.
2. Overview → `TechDebtPanel`.
3. Issues-by-category bar chart + severity chips + hotspot files.
4. Toggle "Last 30 days" vs "All-time".

### PROJ-09 · estimation chip (v2.5.2)
1. Type a requirement in `ExecutionSetup`.
2. After debounce, gray chip shows "~X tasks · ~$Y-$Z" derived from last 20 project runs.
3. Click basis string → tooltip explains the estimate source.

### PROJ-10 · reset workspace to run state (v2.5.2)
1. Launch. During the run, push a new commit to a linked repo.
2. After the run, open `InlineRunDetail` → "Reset to run state".
3. Workspace resets to the original SHAs captured at run start (from `run_repo_snapshot`).

---

## 6 · Execution View (Project Launch)

### EXEC-01 · happy path multi-repo Launch
**Preconditions:** ENTERPRISE or MAX tier; project with 2 linked repos.
1. `POST /api/executions` with requirement + repo IDs.
2. SETUP → ANALYSING → ARCH_REVIEW → (approve) → PLANNING → (approve) → EXECUTING → COMPLETE.
3. `ExecutionStepper` reflects each step; elapsed time on active step.
4. Each repo gets a PR; PR rollup shows both OPEN.
5. `verified_score ≥ 80` per solution; run notes captured.

### EXEC-02 · failure decision modal (v2.5.3 UX-5.2, v2.5.12 primitives)
1. Launch a multi-solution run where S2 depends on S1.
2. Force S1 to FAIL (e.g. malformed spec).
3. `FailureDecisionModal` opens (migrated v2.5.12 — uses ResponsiveModal + Button primitives).
4. "Blocked downstream" section lists S2.
5. Test each path: Retry / Skip / Fix manually / Attempt anyway.

### EXEC-03 · cancel rollback (v2.5.2 Phase 2.3)
1. Launch a multi-repo run; let it reach EXECUTING with one PR open.
2. Cancel via `CancellationPanel`.
3. `ExecutionOrchestrator.runCompensations` runs.
4. Each open PR gets closed on GitHub.
5. `EXEC_COMPENSATION_UPDATE` then `EXEC_COMPENSATION_DONE` on SSE; CancellationPanel ticks each step.

### EXEC-04 · post-merge cascade bar (v2.5.4)
1. Complete a Launch with an opened PR.
2. Merge the PR on GitHub.
3. Webhook flips PR status MERGED → `PostMergeCascadeService.writePostMergeBar` writes a Redis entry.
4. Dashboard shows `PostMergeBar` ("✓ PR #N merged — Start next requirement →"). Stacks up to 2 bars + "+N more merged" chip.
5. Dismissing the bar removes the Redis entry.

### EXEC-05 · strict-provider mode respects project ai_provider
**Regression guard for Bug #1c watcher.**
1. Set `project.ai_provider=OPENAI`.
2. Start server with `-Dexecutespec.strict-provider=true`.
3. Launch. Logs and ModelResolver traces show `provider=openai` on every agent call (planner/worker/reviewer).
4. No silent fallback to anthropic/gemini.

---

## 7 · Notification System

### NOTIF-01 · run completion notification
Covered under RUN-01.

### NOTIF-02 · run failure notification
1. Submit a spec that fails (e.g. an unsupported language).
2. Bell count increments; "Run failed: …" notification.
3. Click → navigates to run detail with terminal banner.

### NOTIF-03 · GATE_OPENED (v2.5.12, new)
Covered under RUN-02 step 3.

### NOTIF-04 · QuotaWarning at 80% (v2.5.12, new)
Covered under RUN-03.

### NOTIF-05 · PR_MERGED (v2.5.12, existing — ed706bb7)
1. Merge a PR opened by a run.
2. Webhook fires; `PullRequestRecordService` updates status; notification "PR #N merged" arrives.

### NOTIF-06 · mark-all-read + badge
1. Multiple unread notifications.
2. `POST /api/notifications/read-all` → unread count = 0; badge disappears.

---

## 8 · Preview & Deploy

### PREV-01 · PROTOTYPE auto-preview (Cloud Run dev)
Covered under RUN-01 step 5.

### PREV-02 · manual test execution
1. COMPLETE run with preview RUNNING.
2. Click "Run Tests" in `PreviewTab`.
3. `POST /api/runs/{id}/run-tests` → container spins up, tests run, results in `TestReportPanel`.

### PREV-03 · deploy to Cloud Run (permanent)
**Preconditions:** ENTERPRISE tier; deploy quota available.
1. COMPLETE run.
2. `DeployWizardModal` (TODO: migrate to ResponsiveModal in v2.5.13) — select env + provider.
3. Deploy succeeds; `Deployments` page lists the permanent URL.

### PREV-04 · preview stop + TTL expiry
1. PROTOTYPE run with preview running.
2. Wait 5 min 30 s; preview auto-stops.
3. Manual `DELETE /api/runs/{id}/preview/stop` before TTL also stops cleanly.

---

## 9 · Validation Report + Scoring

### VAL-01 · verified score on CLEAN build (v2.5.10)
1. Run a clean spec.
2. `ValidationReportPanel` shows **V {verifiedScore}/100** as primary badge.
3. `Est. N/100` secondary chip for `accuracyScore`.
4. `SignalBreakdown`: "Build ✓ · Tests N/N · Reviewer CLEAN · Verified N · Est. N".

### VAL-02 · PARTIAL with intermediate warnings (v2.5.10)
1. Trigger a run where an intermediate smoke fails but final build passes.
2. `StatusBadge` shows "Partial" with tooltip "CLEAN_WITH_INTERMEDIATE_WARNINGS".
3. No contradictory "PARTIAL · 97/100" — verified score reconciles with status.

### VAL-03 · 6-dimension score card (pilot R6)
1. Open validation report.
2. `ScoreCard` renders 6 colored progress bars (code quality / tests / architecture / security / performance / design compliance).
3. Overall accuracy matches `verifiedScore`.

### VAL-04 · critical issues render with citations (v2.5.10)
1. Run a spec that has known issues.
2. Reviewer issues section shows inline `[file:line-range]` citations.
3. No "file-tree plausibility" hallucinations.

### VAL-05 · PDF export + shareable report
1. `GET /api/runs/{id}/report/pdf` → 200 PDF with full report.
2. `POST /api/runs/{id}/report/share` → share token.
3. `GET /api/reports/shared/{token}` (no auth) renders SharedReportPage.

---

## 10 · Feature Flags + Admin

### FLAG-01 · flag CRUD (SUPER_ADMIN)
1. `GET /api/feature-flags` → list.
2. `POST /api/feature-flags/{key}` → toggle `scoring.v2-verified` off then on.
3. Redis pub/sub invalidates 60 s cache.
4. New run's validation report reflects the toggle.

### FLAG-02 · force-logout via epoch bump
1. SUPER_ADMIN increments `auth.epoch`.
2. All active users' JWTs become invalid on next refresh.

### FLAG-03 · feature-flag gating — `validation.v3-strict` (Pillar 1)
1. Enable `validation.v3-strict` globally.
2. Submit a run; planner must emit `requirements[]` covering every task (enforced by `PlannerOutputUtils.validatePlan`, called from `OutlinePlannerAgent`).
3. Disable flag; planner graph still runs but coverage violations don't fail the run.

### ADMIN-01 · payment audit CSV export
1. `GET /api/admin/payment-audit/export`.
2. CSV downloads with GST lines, gateway, amount, status.

### ADMIN-02 · webhook event log
1. Submit a test Razorpay webhook.
2. `GET /api/admin/webhook-events` shows the event with full payload.

---

## 11 · Integrations

### INT-01 · GitHub webhook PR lifecycle
Covered under EXEC-04.

### INT-02 · GitHub webhook HMAC rejection
1. Send a webhook with wrong signature.
2. 401 / 403 response; no DB write.
3. Logged at WARN.

### INT-03 · JIRA inbound webhook → run submission
1. `POST /webhooks/jira` with an issue.
2. Run auto-submits; handover context includes JIRA ticket.
3. On MergeCompleted, outbound comment posted to the ticket.

### INT-04 · Confluence inbound webhook
Analogous to INT-03 with Confluence pages.

### INT-05 · Slack notifications
1. Configure Slack app; complete a run.
2. Block Kit message with run summary posted to the configured channel.

---

## 12 · Design System (v2.5.12 primitives)

### DS-01 · Button variants + sizes render
1. Storybook OR manual — render `<Button variant=...>` for all 6 variants × 4 sizes.
2. Hover + focus states match design tokens (`--accent-blue` primary, `--gray-*` secondary, etc.).

### DS-02 · ResponsiveModal focus-trap
1. Open any migrated modal (e.g. `UpgradeModal`).
2. Tab cycles only through focusable elements inside the panel.
3. Shift+Tab at first element wraps to last; Tab at last wraps to first.
4. Escape closes the modal.
5. Modal opens with first focusable element focused; on close, focus returns to the triggering element.

### DS-03 · Checkbox / Radio primitives — OS default regression guard
1. Open `NewRunModal`.
2. "TDD mode" checkbox: native checkbox hidden, custom check icon visible.
3. Project repositories: each row uses `Checkbox` with label + scope tag.
4. GitHub repo choice: 3 radio options styled as cards; only one can be selected.

### DS-04 · Modal sweep coverage audit (v2.5.12 + follow-up)
1. Open each migrated modal once (Grant Runs, Admin Invite, Change Password, Contact, Upgrade, Disclaimer, Add Repo, Failure Decision, New Run).
2. Verify focus-trap + escape + backdrop dismiss (where enabled).
3. Resize to mobile (<768 px): modal becomes full-screen with slide-up animation.

---

## 13 · Error Handling & Concurrency

### ERR-01 · optimistic lock on ExecutionRun (v2.5.12)
Covered under RUN-07.

### ERR-02 · rate limit per endpoint group
1. As a PRO user, hammer `POST /api/runs` (group=RUNS).
2. After the per-minute cap, receive 429.
3. Rate-limit headers present on every response.

### ERR-03 · provider circuit breaker
1. Inject 5 consecutive provider failures (e.g. kill the API key temporarily).
2. `ProviderHealthTracker` opens the circuit for 60 s.
3. Subsequent calls bypass OR return 503 depending on mode.
4. After 60 s TTL, circuit resets.

### ERR-04 · gate wait timeout (24 h)
1. Submit a PRODUCTION run. Don't approve.
2. Wait 24 h (or advance clock via fixture).
3. Gate throws `InterruptedException`; run marked FAILED cleanly; SSE terminal emitted.

### ERR-05 · V2 startup resumer marks orphans FAILED
1. Kill the server during a WORKING run.
2. Restart.
3. `StartupRunResumerV2` flips the run to FAILED with "interrupted at boot" reason.
4. SSE terminal emits; UI surfaces the interruption.

### ERR-06 · AWAITING_APPROVAL no-thread detection
1. Submit PRODUCTION run; wait for AWAITING_APPROVAL.
2. Restart server (pipeline thread is gone).
3. `POST /api/runs/{id}/approve` returns 409 with clear message instead of hanging.

### ERR-07 · @PreDestroy thread-pool cleanup (v2.5.12)
1. Submit runs; let thread pools grow.
2. Send SIGTERM.
3. `V2RunOrchestrator`, `V2RunService`, `PerkyMessenger` each shut down their pools within the timeout.
4. No orphaned threads after JVM exit.

---

## 14 · Validation v3 Pillar 1 (v2.5.11 / 12)

### V3-01 · planner requirement graph validation
1. Enable `validation.v3-strict`.
2. Submit a spec with multiple acceptance criteria.
3. Plan JSON includes `requirements[{id, text, kind}]` and each task declares `meetsRequirements[]`.
4. Coverage audit: every `requirements[].id` is cited by ≥ 1 task.
5. Non-SINGLE_SHOT HTTP/CLI/LIBRARY surface → at least one TEST-kind requirement OR a test task.

### V3-02 · planner retry on coverage violation
1. Engineer a spec that causes the planner to miss a requirement.
2. First validate-plan call throws `PLAN_COVERAGE_INVALID` → planner retries once with violations as feedback.
3. Second failure → run ends FAILED with reason `PLAN_COVERAGE_INVALID`.

### V3-03 · SINGLE_SHOT synthesised coverage
1. Submit a tiny 1-file spec (e.g. fib).
2. Plan synthesises R1 (first 120 chars) + T1.meetsRequirements=[R1] without calling the planner LLM.
3. Validation passes.

### V3-04 · migration rollback resilience
1. Apply V109 migration on a fresh DB.
2. Verify `pipeline_run.plan_requirements_json` + `agent_task.meets_requirements_json` are nullable (default null for pre-V109 rows).
3. Rollback a single run; no NPE.

---

## 15 · Eval Harness (v2.5.12)

### EVAL-01 · strict S3 medium × 3 providers
**Preconditions:** server + API keys; strict-provider mode.
1. `./scripts/v2-eval-strict.sh --bands s3-medium`.
2. 3 runs submitted (anthropic/openai/gemini).
3. Each completes or errors within 20 min.
4. Markdown report written under `scripts/v2-eval-results/v2-eval-strict-*.md`.
5. Verified scores, status_reasons, costs captured.

### EVAL-02 · S4 URL shortener
Same flow with `--bands s4-large`. Expected ~$2/run; expect ≥ 85 Functional.

### EVAL-03 · S5 multi-repo integration
**Preconditions:** pre-seeded project with `*-be` + `*-fe` empty repos.
1. Requires a variant of the runner that passes projectId; today only single-run path is wired.
2. Fork this into `v2-eval-strict-project.sh` in a follow-up.

---

## 16 · Migration & Backfill

### MIG-01 · fresh DB → V112 applies
1. Drop + recreate the `executespec` database.
2. Start the app; Flyway applies V1..V112 in order.
3. No migration errors; schema ready.

### MIG-02 · V108 backfill (historical PARTIAL → COMPLETE when applicable)
1. Seed a pre-V107 PARTIAL run with CLEAN reviewer verdict + passing build + passing tests.
2. Apply V108.
3. Row flips to COMPLETE + status_reason=CLEAN_WITH_INTERMEDIATE_WARNINGS.

### MIG-03 · V112 backfill
1. Seed an execution_run pre-V112.
2. Apply V112.
3. `version` column defaults to 0; existing tenant_isolation policies unchanged.

---

## 17 · Smoke test checklist (quick regression pass)

Run after every dev deploy — 10 minutes max:

- [ ] Login as admin (AUTH-01).
- [ ] Submit a PROTOTYPE fib spec with anthropic (RUN-01). Verified score ≥ 80.
- [ ] Open Project Dashboard on a seeded project (PROJ-01).
- [ ] Launch a 2-repo execution; both PRs raised (EXEC-01).
- [ ] Plan approval gate notification fires (RUN-02).
- [ ] Concurrent approve returns 409 (RUN-07).
- [ ] Validation report panel shows verified + est + signals (VAL-01).
- [ ] ResponsiveModal opens, Tab cycles, Escape closes (DS-02).
- [ ] `GET /actuator/health` = UP; `/actuator/prometheus` scraped.

---

## 18 · Out of scope for this plan

- Load / stress testing (tracked separately in `docs/plans/load-testing.md` — TODO).
- Chaos engineering (network partition, DB kill) — run infra-only.
- Penetration testing — external vendor.
- Browser matrix beyond Chrome + Safari latest — not currently automated.
- Mobile app surfaces — no mobile app in scope.

## Revision log

| Date | Who | Change |
|---|---|---|
| 2026-04-17 | outstanding-items sprint | Initial plan covering v2.5.12 scope |
