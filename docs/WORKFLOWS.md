# ExecuteSpec — End-to-end workflows

> Traced from the code on this branch (migration head V199, 2026-05-16). Every endpoint and service citation is load-bearing — flows are described in terms of which file owns each step.

## 1. Signup → first PR

1. **Register.** `POST /api/auth/register` (`auth/controller/AuthController`) creates the `AppUser` with `globalRole=USER` + `tier=FREE`. Optional attribution fields: `signupSource`, `utmSource`, `utmCampaign` (Wave 2.1 funnel).
2. **Personal org auto-provision.** `auth/service/AuthService.register` invokes `OrgService.bootstrapPersonalOrg(userId)` which writes a row with `is_personal=TRUE` (V162) so the FREE-tier `OrgService.createOrg` block doesn't trip on the user's first project. Backfill: pre-V162 users got their personal org created lazily.
3. **Email verify.** Verification email mailed via `app.mail.transport=smtp` (or `gmail-api` locally). `GET /api/auth/verify-email?token=...` returns a LoginResponse-shaped payload (`{accessToken, user}`) — caller stores token + redirects, no password re-entry ([F-29], 2026-04-30).
4. **Pick a sandbox (optional).** `GET /api/onboarding/sample-specs` lists sample specs; `POST /api/onboarding/pick-sample {sampleId}` (`onboarding/OnboardingController`) provisions a sandbox project under the user's personal org (`is_sandbox=TRUE`) and seeds a sample spec body. UI: `/welcome` (`ExtraPages.tsx EmptyProjectPage`).
5. **Optional: create a project.** `POST /api/projects {name, description?, aiProvider?, githubOwner?}` (`auth/controller/ProjectController`). Capped by `tier_config.max_projects_per_org` (FREE=1, V140).
6. **Compose the spec.** `frontend/src/signal/pages/ComposePage.tsx` posts `POST /api/runs {userRequest, aiProvider, executionMode: 'PRODUCTION', projectId?, selectedRepoIds?, userCostCap?}`. `RunController.submit` (`api/controller/RunController.java:98`) performs `InputSanitizer.scan(userRequest)` + `tierEnforcementService.assertAndIncrementRunCount(userId)` (atomic check+increment, pessimistic lock) + `usageTrackingService.assertWithinLimits` + `apiKeyResolver.validateKeyAvailability`. Returns 202 + RunSummary.
7. **Pipeline boots.** `RunOrchestrator.submitRun` writes the `PipelineRun` row with status `QUEUED`, then dispatches a virtual-thread task that walks `CLASSIFYING → PLANNING → AWAITING_APPROVAL`. SSE events emit at every phase boundary via `SseBroadcaster.emit(runId, RunEvent.*)`.
8. **Approve the plan.** UI: `PlanPage.tsx` polls `GET /api/runs/{id}` every 2s; on `AWAITING_APPROVAL` shows the plan. Click "Approve · charge N credits" calls `POST /api/runs/{id}/approve {approved: true, creditsConfirmed, executionMode}`. `RunController.approvePlan` calls `governanceGuard.assertCanApproveCost` + `assertProjectSpendCap`, then `gateStore.tryClaimPlanGate(runId, true)` (atomic — second concurrent approver gets 409). Fires `EventName.PLAN_APPROVED` to the activation funnel.
9. **Worker loop runs.** `SequentialExecutor.executeOne` per task. Each `WorkerAgent` call uses the tier-routed model (Anthropic Sonnet 4.6, OpenAI gpt-5.4-mini, Gemini 3 Flash for WORKHORSE). Per-task `buildProject` smoke between tasks. Status flips to `WORKING`.
10. **Reviewer pass.** `ReviewerAgent` does a general review + a project-type-aware wiring pass + `PostEditFormatter`. Status flips to `REVIEWING`. Verdict captured on `validation_report.reviewer_verdict`.
11. **Push + PR.** `ScmPushService.push(run)` for single-repo, `ScmPushService.pushMultiRepo` for multi-repo. Each clone in `workspace/<runId>/repos/<name>/` gets pushed to a new `executespec/<runIdShort>` branch (or `RunRequest.branch` override). PR opened via `GitHubRepoService.createPullRequest` (or `BitbucketRepoService`).
12. **Validation report + score.** `ValidationReportGenerator` populates `validation_report`. `ValidationReportService.computeVerifiedScoreMultiplicative` (line 974) returns the 0–100 score. PARTIAL runs carry `status_reason` (V107). Status flips to terminal `COMPLETE` / `PARTIAL` / `FAILED`.
13. **Auto-preview kickoff.** If `tier_config.can_deploy=TRUE` and `tier_config.max_preview_starts > 0`, `previewService.startAuto` boots a 5-min Cloud Run preview in the background.
14. **Review the PR.** UI: `/result/:id` (`ResultPage.tsx`). User clicks "Open on GitHub" to review the PR and merge.

## 2. Multi-repo run

1. User picks a project at `/compose`. UI calls `api.getProjectRepos(projectId)` and pre-selects all linked repos. Toggle chips drop repos from the set.
2. Submit posts `selectedRepoIds: [uuid1, uuid2, …]` on `POST /api/runs`. `RunController.submit` forwards them to `RunOrchestrator.submitRun(..., List<UUID> selectedRepoIds, …)`.
3. `RunOrchestrator` resolves each repo (must already be linked to `projectId` — `ProjectRepoRepository`-checked), then for each repo clones into `workspace/<runId>/repos/<name>/` using the user's GitHub PAT. Branch override per `RunRequest.branch` if set, else each repo's default branch.
4. `run_repo_snapshot` rows capture the starting SHA per repo (used by `POST /api/runs/{id}/sync/original`).
5. Planner sees a multi-repo workspace; task descriptions reference per-repo paths. Per-task `WorkerAgent` reads/writes scoped to the right subdirectory.
6. On terminal: `ScmPushService.pushMultiRepo(run)` opens **one PR per repo**, cross-links them in each PR body via `GitHubRepoService.updatePullRequestBody`, and writes each PR to `pull_request_record` (with `kind` field V169 + `project_id` link).
7. UI shows the multi-PR set on `/result/:id` PR tab.

## 3. Plan-gate approval (PRODUCTION mode)

1. `RunOrchestrator` writes status `AWAITING_APPROVAL` and emits `RunEvent.planReady(runId, plan)` over SSE.
2. UI `PlanPage.tsx` polls until status flips, renders plan + classifier verdict + cost cap.
3. User clicks "Approve · charge N credits". UI calls `POST /api/runs/{id}/approve {approved: true, creditsConfirmed: N, executionMode: 'REALTIME'}`.
4. `RunController.approvePlan` runs:
   - `creditsConfirmed` mismatch → HTTP 409 ("Cost estimate may have changed — please review").
   - `governanceGuard.assertCanApproveCost(orgId, estUsd, userDetails)` and `assertProjectSpendCap(projectId, estUsd)` (Wave 3 governance, M-032 + M-033).
   - `gateStore.isPlanGateWaiting(runId)` — if the pipeline thread is gone (e.g. server restart), persists the gate decision + flips run to `FAILED` + raises HTTP 409 with "submit a new run" copy.
   - `gateStore.tryClaimPlanGate(runId, true)` — atomic claim. Concurrent claims get HTTP 409.
   - Fires `EventName.PLAN_APPROVED` to PostHog.
5. Pipeline thread inside `RunOrchestrator` unblocks from `gateStore.awaitPlanGate(runId)` and transitions to `WORKING`.

Rejection: `approved: false` claims the gate with rejection. `RunOrchestrator` flips status to `CANCELLED` + skips credit charge.

## 4. PR review autofix (autofix phase 1)

1. User runs `/api/pr-reviews {projectId, repoFullName, prNumber}` (`prreview/PrReviewController`). PRO+ only, capped by `tier_config.pr_review_monthly_quota`.
2. `PrReviewService` polls GitHub for review comments + diff hunks, dispatches `PrReviewAgent` (PLANNING tier), persists verdict to `pr_review`.
3. UI surfaces the verdict popover on the PR card (`PrReviewButton` in `components/runs/PrReviewButton.tsx`).
4. Click "Autofix N comments" → `POST /api/pr-reviews/{id}/autofix`. `prreview/PrReviewAutofixService.dispatch` builds a synthetic spec from the comments, calls `FindingPatchSubmitter.submit(...)` with `runMode=PATCH` + `source="finding:PR_REVIEW"` + `branchOverride=<PR head branch>` so commits land on the same branch.
5. Patch-mode `RunOrchestrator.runPatchPipeline` runs the single thin-agent loop and auto-pushes via `scmPushService.push(run)` on terminal.
6. New commits appear on the existing PR. UI refreshes the verdict the next time the page loads.

## 5. Tech-debt autofix (autofix phase 2)

1. UI: `/projects/:projectId/code` → Tech debt sub-tab → `TechDebtPanel`.
2. Data from `GET /api/projects/{id}/tech-debt?since=30`. Categories × severities × file hotspots × dimension trend computed from `validation_report.issues_json` rollup.
3. Per-category "Fix" button → `POST /api/projects/{id}/tech-debt/dispatch-fix {category}` (`project/techdebt/TechDebtFixService`).
4. `TechDebtFixService` aggregates the open items in that category into a focused spec, then `FindingPatchSubmitter.submit(...)` with `runMode=PATCH` + `source="finding:TECH_DEBT_<CATEGORY>"`. New PR per category.

## 6. CI failure autofix (autofix phase 3, webhook-driven)

1. GitHub `check_run` webhook delivered to `POST /webhooks/github` (`project/pr/GitHubWebhookController`). HMAC SHA-256 verified against `GITHUB_WEBHOOK_SECRET`.
2. `GitHubWebhookController` decodes the payload, looks up the matching `pull_request_record` by `head_sha`, sees `conclusion=failure`.
3. Feature flag check: `featureFlagService.isEnabled("ci.autofix.enabled")` (default OFF). When OFF, returns 202 + skip log line. When ON, proceeds:
4. `project/pr/CiFailureAutoFixService.handle(prRecord, checkRun)` increments `pull_request_record.ci_auto_fix_attempts` (max 3 attempts/PR per V170 column). If still under cap, builds a spec from the failing job's log tail and dispatches via `FindingPatchSubmitter.submit(...)` with `runMode=PATCH` + `source="finding:CI_FAILURE"` + `branchOverride=<PR head branch>`.
5. New commits push to the PR; CI re-runs.

## 7. Code review autofix (autofix phase 4)

1. UI: `/projects/:projectId/code` → Code review sub-tab. PRO+ only, capped by `tier_config.code_review_monthly_quota` (PRO 4/mo, TEAM/ENTERPRISE unlimited).
2. Trigger a scan: `POST /api/code-reviews {projectId, repoFullName}` (`codereview/CodeReviewController`). `CodeReviewAgent` (PLANNING tier) walks the repo and writes findings to `code_review_finding` with severities CRITICAL / MAJOR / MINOR.
3. UI `CodeQualityPanel` shows severity chips ("3 CRITICAL", "8 MAJOR").
4. Click a severity chip → `POST /api/code-reviews/{scanId}/autofix {severity}`. `codereview/CodeReviewAutofixService.dispatch` builds a focused spec from that severity's findings, dispatches via `FindingPatchSubmitter.submit(...)` with `runMode=PATCH` + `source="finding:CODE_REVIEW_<SEVERITY>"`. One PR per severity.

## 8. Architecture regeneration

1. UI: `/projects/:projectId/architecture` → "Regenerate".
2. `POST /api/projects/{id}/architecture/generate` (`codeintel/ProjectArchitectureController`). Optional `?branch=<branch>` (`POST /api/projects/{id}/analyse?branch=<name>`).
3. `codeintel/ProjectArchitectureService.regenerate` walks the 3-step pipeline:
   - **Step 1: per-repo codemap.** `CodeMapGenerator` (idempotent by SHA — no-op when the repo HEAD hasn't moved since the last codemap).
   - **Step 2: per-repo HLD/LLD.** `RepoArchitectureGenerator` runs on PLANNING tier and writes `repo_architecture_doc` rows.
   - **Step 3: cross-repo synthesis.** `ArchitectureDocGenerator` runs on PLANNING tier, consumes all per-repo docs, writes the project-level architecture markdown + a new `project_architecture_doc` version row.
4. `StalenessService` invalidates its Redis-backed cache so the dashboard "STALE" badge clears (V192 — captures `generated_from_branches` so non-default-branch regenerations don't permanent-stale).
5. UI: `ArchitectureViewer` renders the new version; "Diff" lets the user compare any two versions via `GET /api/projects/{id}/architecture/diff?from=&to=` (LCS + GitHub `compareCommits` for source-file changes).

## 9. Library solution apply (BETA)

Note: the Signal `/library` surface is a static catalogue today; live wiring is exposed via `readymade/controller/ReadymadeController`.

1. UI: `/library` → pick a solution card.
2. `GET /api/readymade/solutions/{slug}` returns the solution spec template + slot definitions.
3. User fills slot values (e.g. for Stripe checkout: currency, env-var prefix).
4. `POST /api/readymade/solutions/{slug}/instantiate {slots, projectId}` renders the spec template with slot substitutions and submits a V2 BUILD-mode run via `RunOrchestrator.submitRun(...)`. Returns the new `runId`.
5. UI routes to `/plan/:runId`. From here the flow is the same as workflow #1.

## 10. Migration apply (BETA)

1. UI: `/migrations` → pick a recipe.
2. `GET /api/migrations/recipes/{slug}` returns the recipe (`migration/controller/MigrationController`).
3. **Dry-run:** `POST /api/migrations/recipes/{slug}/dry-run {projectId, repoId, config}` simulates the migration on a scratch branch and returns the per-cluster plan (file count, estimated cost, estimated time).
4. **Apply:** `POST /api/migrations/recipes/{slug}/apply {projectId, repoId, config}` dispatches a multi-PR migration via `RunOrchestrator.submitRun(...)` — one PR per cluster (~30 files) so CI stays green throughout.
5. Each cluster pauses at the plan gate (configurable). Heads-up copy from `MigrationDetailPage.tsx`: "We pause at every plan gate. You're never further than one approval away from the next batch."

## 11. Project chat (TEAM+ for unlimited; tier-gated)

1. UI: `/projects/:projectId/chat` (`ProjectChatPage.tsx`).
2. `GET /api/projects/{id}/chat/messages` loads compacted thread (`memory/ProjectChatService`). Compaction summarizes older N messages keeping last 6 verbatim.
3. `POST /api/projects/{id}/chat/messages {message}` invokes `ProjectChatCompiler` (PLANNING tier). Replies stream back synchronously.
4. Per-bubble copy button. "Reset" clears the thread.
5. **Apply suggestion**: on assistant replies with actionable content, "Apply" calls `POST /api/projects/{id}/chat/apply {messageId, suggestion, branchOverride?}` which dispatches a project-scoped run via the dedicated `applyExecutor` (4 threads) so the worker loop doesn't pin a Tomcat handler. Counts against `tier_config.max_apply_suggestions` (FREE 2 / PRO 10 / TEAM unlimited).
6. Chat tier gate: `tier_config.max_chat_messages` (FREE=0 blocks the panel entirely, PRO/TEAM/ENTERPRISE=10 per run scope).

## 12. Cost cap + refund

1. Submit: user sets `userCostCap=5.0` on `POST /api/runs`. DTO validates `0.10 ≤ x ≤ $1000`. `RunController.submit` writes the cap via `stateStore.setUserCostCap(runId, userCostCap)` (`RunController.java:170`).
2. `RunOrchestrator.persistEstimate` (called after planner emits the plan) sets `hard_cap_usd = min(platformCap, userCostCap)` where `platformCap = credits × $5 × 1.5` (`agent.cost.budget-margin`).
3. `RunCostTracker` (`cost/RunCostTracker.java`) accumulates per-call cost using the YAML model-pricing table. Every `LlmRouter.call(...)` flows through it.
4. `WorkerAgent` budget guard checks `costTracker.snapshot(runId).costUsd() >= hardCapUsd` between iterations. When crossed, returns `Outcome.BUDGET_EXCEEDED` and the orchestrator terminates with status `FAILED`, reason "BUDGET_EXCEEDED".
5. Refund on FAILED: `billing/service/RunQuotaRefundListener` (event listener on `RunFailedEvent`) calls `tierEnforcementService.decrementRunCount(userId, credits)` so the failed run doesn't burn quota.
6. BYOK exemption: when `org.keySourceMode='OWN_KEYS'` the orchestrator skips the platform cap entirely (`RunOrchestrator.java:1550` log line). User-provided cap still applies.
7. UI: `CostProgressBar` on `LivePage.tsx` shows `spent / hardCapUsd` with green (0–80%) / amber (80–100%) / red (≥100%) banding.

## 13. Preview deploy (Cloud Run, 5-min TTL)

1. UI: `/result/:id` → "Start" on the Preview deploy row, or auto-kicked off after `COMPLETE` if the tier allows it.
2. `POST /api/runs/{id}/preview/start` (`api/controller/PreviewController`). Tier-gated: requires `tier_config.can_deploy=TRUE` AND `tier_config.max_preview_starts > 0` AND `usageTrackingService.assertWithinLimits(userId, 'PREVIEW_STARTS')`.
3. `deployment/preview/PreviewService.start(runId)` picks a mode based on `preview.mode` config:
   - **`docker`** — local Docker Compose with Caddy + Cloudflare Origin Certificate.
   - **`cloud-run`** — builds + pushes the image to Artifact Registry, deploys to Cloud Run with `--allow-unauthenticated`, returns `https://preview-<id>.executespec.ai`.
   - **`vm`** — SSHes into a GCE VM preview-gw and starts the container behind a Caddy reverse proxy.
4. Returns `{previewUrl, ttlMinutes}`. UI surfaces the URL in a toast.
5. After TTL (default 30 min for PRODUCTION-class runs, 5 min for the auto-kickoff), `cleanup-cron` (`0 3 * * * *`) tears the preview down.
6. `DELETE /api/runs/{id}/preview/stop` early-terminates.

## 14. Push to GitHub (manual)

1. UI: `/result/:id` → PR tab → "Push to GitHub" (when no PR yet exists, e.g. `RunRequest.skipAutoPush=true`).
2. `POST /api/runs/{id}/push-scm?repoMode=EXISTING&repoUrl=<optional>` (`RunController.pushToScm`).
3. Requires terminal status (`COMPLETE` or `PARTIAL`). 409 otherwise.
4. `ScmPushService.push(run)` routes by user credentials: Bitbucket if `user.bitbucketUsername` is set, else GitHub if `user.githubPat` is set, else 400 ("Connect GitHub under Settings → Integrations and retry").
5. Returns `{status: "pushed", repoUrl, prUrl, scmProvider}`. If `prUrl` is blank (no GitHub installation), `status` is honestly reported as `"no_install"` with a remediation message (SDET BUG-018 fix at `RunController.java:645`).
6. The legacy `POST /api/runs/{id}/push-github` is `@Deprecated` but still wired for backward compat. Same body shape, GitHub-only.

## 15. Sync workspace from branch

1. UI: `/result/:id` → "Sync to origin HEAD" (operator action; only on terminal runs).
2. `POST /api/runs/{id}/sync?branch=<optional>` (`RunController.syncRun`). Requires terminal status; 409 otherwise.
3. `RunSyncService.sync(runId, branch)` hard-resets every cloned subdirectory to `origin/<branch>` (falls back to that repo's default branch when the supplied branch doesn't exist on origin). Uncommitted local edits are wiped.
4. Returns `{status: "synced" | "partial" | "failed", reposSynced, details: [...]}`. The status branch was a SDET fix (BUG-019, `RunController.java:704`) — previously "synced" was returned even when every clone failed.
5. Sibling endpoint `POST /api/runs/{id}/sync/original` resets to the SHAs captured at run start (from `run_repo_snapshot` per V100). Returns 409 if the run predates v2.5.2 and has no snapshot rows.

## 16. Cancel an in-flight run

1. UI: `/live/:runId` → cancel icon → ConfirmModal.
2. `DELETE /api/runs/{id}` → `RunController.cancel` → `RunOrchestrator.cancel(runId)`:
   - Signals the plan gate so any waiting `awaitPlanGate` unblocks.
   - Interrupts the pipeline future.
   - Closes any half-open PR via `GitHubRepoService.closePullRequest`.
3. Status flips to `CANCELLED`. Credits refunded via `RunQuotaRefundListener`.

## 17. Archive / soft-delete / restore (V191)

1. `POST /api/runs/{id}/archive` → flips `archived_at`; row drops out of default list.
2. `POST /api/runs/{id}/unarchive` → reversible.
3. `POST /api/runs/{id}/soft-delete` → tombstones the run with `deleted_at`; 30-day undo window.
4. `POST /api/runs/{id}/restore` → undo within window.
5. After 30 days, the PR-H cleanup sweeper hard-deletes the row + workspace.

Same surface exists on projects: `archiveProject`, `unarchiveProject`, `deleteProject`, `restoreProject` (`api.ts:1586-1605`).

## 18. Notifications

1. In-app: events written to `notification` (V61). `NotificationController` exposes `GET /api/notifications`, `GET /api/notifications/unread-count`, `POST /api/notifications/{id}/read`, `POST /api/notifications/read-all`.
2. Triggers: PLAN_READY (plan gate opened), RUN_COMPLETE / RUN_FAILED, QUOTA_WARNING (`TierEnforcementService.maybeFireQuotaWarning` at 80% of cap), BILLING_ failure, INVITE_RECEIVED, MEMBER_ADDED.
3. Per-event channel preferences via `notification/pref/NotificationPrefController` (`GET /api/notifications/preferences`, `PUT /api/notifications/preferences/{eventType}/{channel}`). Channels: IN_APP, EMAIL.
4. UI: `/notifications` (MiscPages.tsx `NotificationsPage`) filters by category, deep-links by event type.

## 19. SSE event stream

1. UI: `/live/:runId` calls `GET /api/runs/{id}/sse-token` first to mint a scoped JWT (`scope=sse`, `runId` claim, 5-min TTL).
2. `EventSource('/api/runs/{id}/events?token=<jwt>')` opens the stream. `SseController` validates the scoped token (no general session JWT, no cookies).
3. `SseBroadcaster` emits typed events:
   - `PLAN_READY` — plan gate opened.
   - `RUN_STATUS` — status flip.
   - `TASK_STATUS` — per-task transition.
   - `LIVE_LOG` — tool call / LLM completion.
   - `RUN_COST` — running cost snapshot.
   - `EXEC_COMPENSATION_UPDATE` / `EXEC_COMPENSATION_DONE` — cancel rollback progress.
   - `RUN_PROVIDER_SWITCHED` — failover.
   - `COMPLETE` — terminal `{status, statusReason}`.
4. `useSse(runId)` hook (`frontend/src/hooks/useSse.ts`) dispatches events into `useSseStore` (Zustand). UI re-renders sub-second.

## 20. External project sharing (V175 + V198)

1. Org admin enables external sharing (V198): UI `Org Settings → Compliance → External Sharing` → `PUT /api/org/me/settings/external-sharing {enabled: true}` → `OrgController` toggles `org.external_sharing_enabled`. Audit row `ORG_EXTERNAL_SHARING_CHANGED` written.
2. Invite an external collaborator: UI `/projects/:projectId/members` → Invite popover. Gated by tier PRO+ AND ORG_ADMIN-of-own-org (or SUPER_ADMIN) AND `org.externalSharingEnabled=true` (V198). Calls `POST /api/projects/{id}/invites {email, projectRole}` (`sharing/controller/ProjectInviteController`). Writes `project_invite` (V175) with unique token + `expires_at`.
3. Recipient clicks invite link → `/accept-invite?token=<token>`. UI calls `GET /api/invites/preview?token=` to preview (no auth needed), then `POST /api/invites/accept?token=` (email-match validated).
4. On accept: `ProjectMember` row created with `is_external=TRUE` + matching `project_role`. `ProjectAccessGuard` now consults this table when the org-id check fails so the external user sees the project.
5. Public share links (independent of V198): UI `/projects/:projectId/members` → Share Link popover. `POST /api/projects/{id}/share-links {surface, scopeId?, label?, expiresAt?}` writes a high-entropy token to `project_share_link`. Tier-gated by `tier_config.share_link_active_cap` (FREE 3 / PRO 25 / TEAM-ENT unlimited).
6. Public access: `/shared/{token}` (Signal `SharedPage`) → `GET /api/shared/{token}/{meta,run,validation-report,code-review,pr-review,architecture}` (`sharing/controller/ProjectSharePublicController`). No auth, whitelisted in `SecurityConfig`.

## 21. BYOK key management

1. UI: `/api-keys` (`MiscAccountPages.tsx ApiKeysPage`). PRO+ only.
2. Per-user keys via `PUT /api/auth/settings/ai-keys {openAiApiKey, anthropicApiKey, geminiApiKey}`. Per-org keys via `PUT /api/org/me/settings/ai-keys` (ORG_ADMIN only). KMS-wrapped in storage (`executespec.crypto.enabled=true` in prod).
3. Validate before save: `POST /api/auth/settings/ai-keys/validate {provider, apiKey}` pings the provider's models endpoint and returns `{valid, message}`.
4. Key source mode: `PUT /api/org/me/settings/key-source {keySourceMode, enabledProviders}` switches the org between `SYSTEM` (platform keys, capped cost), `HYBRID` (org keys with platform fallback, still cost-capped), `OWN_KEYS` (org keys only, no platform cost cap).
5. `agent/ApiKeyResolver.validateKeyAvailability(userId)` runs as a pre-flight on `POST /api/runs` for OWN_KEYS users and returns HTTP 400 if no enabled provider has a key.
</content>
</invoke>