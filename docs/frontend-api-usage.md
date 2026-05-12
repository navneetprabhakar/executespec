# Frontend API Usage Audit

> **Last updated:** 2026-05-12 (1.0.0-M1).
> **Purpose:** which backend endpoints the frontend depends on, organised by capability. Source of truth: `frontend/src/lib/api.ts` (single API client file).
> **Audit rigour:** `arch/ApiContractTest` (ArchUnit + bytecode scan) asserts every URL in `lib/api.ts` resolves to a registered backend `@*Mapping`. Caught one drift on first run (frontend export with no backend handler) and forced cleanup.

## How to read this doc

The frontend is organised by **feature surfaces**, not by HTTP verb. Each surface section below lists the api.ts helper, the backend endpoint, and the store / page that consumes it.

For the canonical endpoint inventory in HTTP-verb order, see [`CLAUDE.md`](../CLAUDE.md) § API Contracts. For the OpenAPI spec, see [`openapi.yaml`](openapi.yaml).

---

## Auth + session

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `register(payload)` | `POST /api/auth/register` | Body includes optional `signupSource`, `utmCampaign`, `utmMedium`, `utmSource`, `referrer`. Personal org auto-provisioned. |
| `login(payload)` | `POST /api/auth/login` | Returns access + refresh JWTs. |
| `refresh()` | `POST /api/auth/refresh` | Refresh token in HTTP-only cookie. |
| `logout()` | `DELETE /api/auth/logout` | Clears refresh cookie. |
| `forgotPassword(email)` | `POST /api/auth/forgot-password` | Sends reset email. |
| `resetPassword(token, newPassword)` | `POST /api/auth/reset-password` | Token from email link. |
| `verifyEmail(token)` | `GET /api/auth/verify-email` | Public. |
| `oauth2Init(surface)` | `GET /api/auth/oauth2/init?surface=<x>` | Stashes surface, redirects to OAuth provider authorize. |
| `oauth2Authorize(provider)` | `GET /api/auth/oauth2/authorize?provider=<p>` | Spring Security entry. |
| `me()` | `GET /api/auth/me` | Current user. |
| `mePermissions()` | `GET /api/auth/me/permissions` | Tier-based permission map (`canManageAiKeys`, `canViewAudit`, etc). |
| `dataExport()` | `GET /api/auth/me/data-export` | GDPR ZIP download. |
| `validateAiKey(provider, key)` | `POST /api/auth/settings/ai-keys/validate` | Test button per provider in Settings. |

Consumed by: `useAuthStore`, `LoginPage`, `RegisterPage`, `SettingsPage`.

---

## Runs

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `submitRun(req)` | `POST /api/runs` | Body fields: `aiProvider`, `executionMode`, `runMode` (BUILD/PATCH), `tddMode`, `userCostCap?`, `figmaUrl?`, `selectedRepoIds[]?`, `projectId?`. Returns 202. |
| `listRuns(params)` | `GET /api/runs` | Paginated, filtered by visibility + tier. |
| `getRun(id)` | `GET /api/runs/{id}` | Includes `verifiedScore`, `statusReason`, `planStrategy`, per-phase token + cost columns. |
| `approveRun(id, body)` | `POST /api/runs/{id}/approve` | `{approved, creditsConfirmed?}`. Charges credits. |
| `cancelRun(id)` | `DELETE /api/runs/{id}` | Triggers compensation (closes opened PRs). |
| `archiveRun(id)` | `POST /api/runs/{id}/archive` | |
| `retryTask(runId, taskId)` | `POST /api/runs/{id}/tasks/{taskId}/retry` | Requires `requireBuildVerification=true`. Does NOT auto-push. |
| `pauseRun(id)` | `POST /api/runs/{id}/pause` | Flag `v2.pause.enabled`, default OFF. |
| `resumeRun(id)` | `POST /api/runs/{id}/resume` | Same-JVM only. |
| `runTests(id)` | `POST /api/runs/{id}/run-tests` | Manual test execution (Beta). |
| `runCost(id)` | `GET /api/runs/{id}/cost` | Live cost snapshot — used by `CostProgressBar` + `RunDetailTabs` (polled 5s while non-terminal). |
| `runPrs(id)` | `GET /api/runs/{id}/prs` | PRs opened by this run. |
| `syncRun(id, branch?)` | `POST /api/runs/{id}/sync` | Reset workspace to current origin HEAD. |
| `syncRunOriginal(id)` | `POST /api/runs/{id}/sync/original` | Reset to SHAs captured at run start (from `run_repo_snapshot`). |
| `sseToken(id)` | `GET /api/runs/{id}/sse-token` | Scoped JWT, 5-min TTL, `scope=sse` + `runId` claim. |
| `runEvents(id, token)` | `GET /api/runs/{id}/events?token=<jwt>` (EventSource) | Server-Sent Events. |

Consumed by: `useRunStore`, `RunDetail`, `RunDetailTabs`, `InlineRunDetail`, `RunCard`, `RunsListPage`, `NewRunModal`.

SSE event types: `PLAN_READY`, `RUN_STATUS`, `TASK_STATUS`, `LIVE_LOG`, `RUN_COST`, `EXEC_COMPENSATION_UPDATE`, `EXEC_COMPENSATION_DONE`, `COMPLETE` (payload `{status, statusReason}`).

---

## Validation report

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `getReport(runId)` | `GET /api/runs/{id}/report` | |
| `downloadReportPdf(runId)` | `GET /api/runs/{id}/report/pdf` | Content-Disposition attachment. |
| `createReportShare(runId)` | `POST /api/runs/{id}/report/share` | Returns share token. |
| `getSharedReport(token)` | `GET /api/reports/shared/{token}` | Public. |

Consumed by: `ValidationReportPanel`, `SharedReportPage`.

---

## Projects

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listProjects()` | `GET /api/projects` | |
| `createProject(req)` | `POST /api/projects` | Body: `name`, `description?`, `aiProvider`, `scaffoldTemplate?`, `figmaUrl?`. Tier-gated by `TierEnforcementService.assertCanCreateProject`. |
| `getProject(id)` | `GET /api/projects/{id}` | |
| `updateProject(id, req)` | `PUT /api/projects/{id}` | |
| `archiveProject(id)` | `POST /api/projects/{id}/archive` | |
| `projectDashboard(id)` | `GET /api/projects/{id}/dashboard` | Aggregated metrics + recent runs/PRs + arch-doc status. |
| `projectMemory(id)` | `GET /api/projects/{id}/memory` | |
| `projectMemoryLedger(id)` | `GET /api/projects/{id}/memory/ledger` | |
| `projectScores(id)` | `GET /api/projects/{id}/scores` | |
| `projectWorkspaceHealth(id)` | `GET /api/projects/{id}/workspace-health` | |
| `projectEstimate(id, requirement)` | `GET /api/projects/{id}/estimate?requirement=<text>` | Heuristic; no LLM call. |
| `projectTechDebt(id, sinceDays)` | `GET /api/projects/{id}/tech-debt?since=<days>` | |
| `projectActivity(id, params)` | `GET /api/projects/{id}/activity` | Partitioned-by-month feed. |
| `projectExport(id)` | `POST /api/projects/{id}/export` | ZIP of `.executespec/` + DB-only counterparts. |

Consumed by: `useProjectStore`, `useV3ProjectStore`, `ProjectDashboardPage`, `V3ProjectHome`, `OrgProjectsPage`.

---

## Project repos + architecture

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listProjectRepos(id)` | `GET /api/projects/{id}/repos` | |
| `linkRepoToProject(id, repoId)` | `POST /api/projects/{id}/repos/{repoId}` | Tier-gated by `assertCanLinkRepoToProject`. |
| `unlinkRepoFromProject(id, repoId)` | `DELETE /api/projects/{id}/repos/{repoId}` | |
| `updateRepoRelationships(id, repoId, tags)` | `PUT /api/projects/{id}/repos/{repoId}/relationships` | |
| `analyseProject(id, branch?)` | `POST /api/projects/{id}/analyse?branch=<name>` | Per-branch arch refresh with caller PAT. |
| `getProjectArchitecture(id)` | `GET /api/projects/{id}/architecture` | |
| `listProjectArchitectureVersions(id)` | `GET /api/projects/{id}/architecture/versions` | |
| `archDiff(id, from, to)` | `GET /api/projects/{id}/architecture/diff?from=&to=` | LCS + GitHub `compareCommits`. |
| `getRepoArchitecture(id, repoId)` | `GET /api/projects/{id}/repos/{repoId}/architecture` | Per-repo HLD/LLD. |
| `regenerateArchitecture(id)` | `POST /api/projects/{id}/architecture/generate` | |
| `annotateArchitecture(id, annotations)` | `PUT /api/projects/{id}/architecture/annotate` | |
| `getProjectStaleness(id)` | `GET /api/projects/{id}/architecture/staleness` | Per-repo SHA drift report. |

Consumed by: `ArchitectureViewer`, `WorkspaceHealthPanel`, `RepoCard`.

---

## Project documents + RAG

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `uploadDocument(id, file)` | `POST /api/projects/{id}/documents` | Multipart upload. |
| `listDocuments(id)` | `GET /api/projects/{id}/documents` | Status: UPLOADED / EXTRACTING / EMBEDDING / READY / FAILED. |
| `deleteDocument(id, docId)` | `DELETE /api/projects/{id}/documents/{docId}` | |

Consumed by: Knowledge tab on `ProjectDashboardPage`.

---

## Project chat

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `getProjectChat(id)` | `GET /api/projects/{id}/chat` | Full message history. |
| `postProjectChat(id, message)` | `POST /api/projects/{id}/chat` | |
| `applyChatSuggestion(id, body)` | `POST /api/projects/{id}/chat/apply` | Async via `applyExecutor` (4 threads). |
| `clearProjectChat(id)` | `DELETE /api/projects/{id}/chat` | |
| `compactProjectChat(id)` | `POST /api/projects/{id}/chat/compact` | Summarise older N messages, keep last 6 verbatim. |

Consumed by: `ProjectChatPanel`.

---

## Project docs (Generate Docs)

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `generateDoc(id, type)` | `POST /api/projects/{id}/docs/{EXPLANATION\|SETUP}/generate` | 201 + row, 409 if no architecture. |
| `getDoc(id, type)` | `GET /api/projects/{id}/docs/{type}` | 204 if not yet generated. |
| `downloadDoc(id, type)` | `GET /api/projects/{id}/docs/{type}/download` | text/markdown attachment. |

Consumed by: `GeneratedDocsPanel`.

---

## Spec templates

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listProjectTemplates(id)` | `GET /api/projects/{id}/templates` | Merged project + org-scoped. |
| `createProjectTemplate(id, req)` | `POST /api/projects/{id}/templates` | |
| `createOrgTemplate(orgId, req)` | `POST /api/orgs/{orgId}/templates` | |
| `deleteTemplate(id, templateId)` | `DELETE /api/projects/{id}/templates/{templateId}` | Soft delete. |

Consumed by: `TemplatePicker` (above the requirement textarea on `ExecutionSetup`).

---

## Pull requests

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listProjectPrs(id)` | `GET /api/projects/{id}/prs` | Multi-repo PR rollup. |
| `refreshPrs(id)` | `POST /api/projects/{id}/prs/refresh` | Polls GitHub open/merged state + CI check-runs (V186). |
| `getPostMergeBar(id)` | `GET /api/projects/{id}/post-merge-bar` | Transient banner marker. |
| `dismissPostMergeBar(id)` | `DELETE /api/projects/{id}/post-merge-bar` | |
| `installRepoWebhook(repoId)` | `POST /api/repos/{repoId}/webhook` | Uses caller's PAT (`admin:repo_hook`). |

Consumed by: `ProjectPrRollup`, `PostMergeBar`, `RepoCard`.

---

## Repos (ORG/USER registry)

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listRepos(scope)` | `GET /api/repos?scope=ORG\|USER\|ALL` | |
| `createRepo(req)` | `POST /api/repos` | ORG scope requires ORG_ADMIN. |
| `syncRepo(id)` | `POST /api/repos/{id}/sync` | Re-detect stack + refresh `last_synced_sha`. |
| `deleteRepo(id)` | `DELETE /api/repos/{id}` | Must not be linked to any project. |

Consumed by: `OrgReposPage`, `ProjectRepoManager`, `AddRepoModal`.

---

## PR Review + Code Review (V172, PRO+)

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `submitPrReview(req)` | `POST /api/pr-reviews` | Body `{projectId, repoFullName, prNumber}`. 202 + QUEUED. |
| `getPrReview(id)` | `GET /api/pr-reviews/{id}` | Includes comments. |
| `listPrReviewsForPr(repo, number)` | `GET /api/pr-reviews?repo=&number=` | |
| `listProjectPrReviews(projectId)` | `GET /api/projects/{projectId}/pr-reviews` | |
| `prReviewAutofix(id)` | `POST /api/pr-reviews/{id}/autofix` | Phase 1 autofix dispatch via `FindingPatchSubmitter`. |
| `submitCodeReview(req)` | `POST /api/code-reviews` | Body `{projectId, repoId?, repoFullName}`. 202 + QUEUED. |
| `getCodeReview(id)` | `GET /api/code-reviews/{id}` | Includes issues + markdown report. |
| `listProjectCodeReviews(projectId)` | `GET /api/projects/{projectId}/code-reviews` | |
| `latestCodeReviewForRepo(projectId, repo)` | `GET /api/projects/{projectId}/code-reviews/latest?repo=` | 204 if none. |
| `downloadCodeReviewReport(id)` | `GET /api/code-reviews/{id}/report` | text/markdown attachment. |
| `codeReviewAutofix(id, severity)` | `POST /api/code-reviews/{id}/autofix?severity=` | Phase 4 autofix dispatch. |

Consumed by: `PrReviewButton` (inline on PR rows), `CodeQualityPanel` (Code tab sub-tab).

---

## External sharing (V175)

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `createProjectInvite(id, body)` | `POST /api/projects/{id}/invites` | Body `{email, role}`. Sends email link. |
| `listProjectInvites(id)` | `GET /api/projects/{id}/invites` | PENDING invites. |
| `revokeInvite(id, inviteId)` | `DELETE /api/projects/{id}/invites/{inviteId}` | |
| `previewInvite(token)` | `GET /api/invites/preview?token=` | Public. |
| `acceptInvite(token)` | `POST /api/invites/accept?token=` | Authenticated; email-match required. |
| `createShareLink(id, body)` | `POST /api/projects/{id}/share-links` | Body `{surface, scopeId?, label?, expiresInDays?}`. Tier-gated by `share_link_active_cap`. |
| `listShareLinks(id)` | `GET /api/projects/{id}/share-links` | |
| `revokeShareLink(id, linkId)` | `DELETE /api/projects/{id}/share-links/{linkId}` | Idempotent. |
| `getSharedMeta(token)` | `GET /api/shared/{token}/meta` | Public — surface metadata, no payload. |
| `getSharedRun(token)` | `GET /api/shared/{token}/run` | Public. |
| `getSharedValidationReport(token)` | `GET /api/shared/{token}/validation-report` | |
| `getSharedCodeReview(token)` | `GET /api/shared/{token}/code-review` | |
| `getSharedPrReview(token)` | `GET /api/shared/{token}/pr-review` | |
| `getSharedArchitecture(token)` | `GET /api/shared/{token}/architecture` | |

Consumed by: `ProjectMembersPanel`, `SharePopover`, public `Shared*Page` components.

---

## Org + invites

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `getOrg()` | `GET /api/org` | Current user's org. |
| `updateOrg(req)` | `PUT /api/org` | |
| `inviteOrgMember(req)` | `POST /api/org/invite` | Same-org invite (not cross-org sharing). |
| `acceptOrgInvite(token)` | `POST /api/org/invite/accept?token=` | |
| `listOrgMembers()` | `GET /api/org/members` | |
| `updateOrgMember(id, role)` | `PUT /api/org/members/{id}/role` | ORG_ADMIN or SUPER_ADMIN. |
| `removeOrgMember(id)` | `DELETE /api/org/members/{id}` | |
| `suspendOrgMember(id)` | `POST /api/org/members/{id}/suspend` | |
| `adjustOrgSeats(seats)` | `POST /api/org/me/seats/adjust` | TEAM only. |

Consumed by: `OrgSettingsPage`, `OrgTeamPage`, `OrgAdminDashboard`.

---

## Billing

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listPlans()` | `GET /api/billing/plans` | YAML-driven. |
| `subscribe(planId, gateway, successUrl, cancelUrl, seats?)` | `POST /api/billing/subscribe` | TEAM passes `seats` 2-20. |
| `cancelSubscription()` | `POST /api/billing/subscribe/cancel` | |
| `purchaseTopup(packageId)` | `POST /api/billing/topup/purchase` | Buy run credits. |
| `paymentHistory()` | `GET /api/billing/payment-history` | Org-scoped. |
| `getInvoices()` | `GET /api/invoices` | |
| `downloadInvoice(id)` | `GET /api/invoices/{id}/download` | PDF. |

Consumed by: `PlansPage`, `PlanCard`, `BillingPage`, `InvoicesPage`.

---

## Onboarding (sandbox)

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listOnboardingSamples()` | `GET /api/onboarding/sample-specs` | 3 sample specs: web-api / frontend / cli. |
| `pickOnboardingSample(sampleId)` | `POST /api/onboarding/pick-sample` | Provisions sandbox + pre-fills spec. |
| `getSandboxStatus()` | `GET /api/onboarding/sandbox-status` | |

Consumed by: `WelcomePage`, `FirstRunCelebration`.

---

## Notifications

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `listNotifications(params)` | `GET /api/notifications` | Paginated. |
| `unreadCount()` | `GET /api/notifications/unread-count` | |
| `markRead(id)` | `POST /api/notifications/{id}/read` | |
| `markAllRead()` | `POST /api/notifications/read-all` | |
| `getNotificationPrefs()` | `GET /api/notification-prefs` | Per-event-type × channel matrix. |
| `updateNotificationPrefs(prefs)` | `PUT /api/notification-prefs` | |

Consumed by: `NotificationBell` (Header), `NotificationPanel`.

---

## Admin (SUPER_ADMIN)

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `fetchTiersLifecycle()` | `GET /api/admin/tiers` | |
| `updateTierLifecycle(tier, body)` | `PUT /api/admin/tiers/{tier}` | |
| `paymentAudit(params)` | `GET /api/admin/payment-audit` | |
| `exportPaymentAudit(params)` | `GET /api/admin/payment-audit/export` | CSV. |
| `webhookEvents(params)` | `GET /api/admin/webhook-events` | |
| `reconcileExecutions(body)` | `POST /api/admin/executions/reconcile` | Mark orphan EXECUTING rows FAILED. |
| `runSwebench(taskId, provider)` | `POST /api/admin/swebench/run` | |
| `runProgrambench(taskId, provider)` | `POST /api/admin/programbench/run` | |
| `migrateProjectCodemaps(projectId)` | `POST /api/admin/projects/{id}/migrate-codemaps` | |
| `listFeatureFlags()` | `GET /api/feature-flags` | |
| `updateFeatureFlag(key, body)` | `POST /api/feature-flags/{key}` | |

Consumed by: `AdminPage`, `TierLifecyclePage`, `PaymentAuditPage`.

---

## Models + integrations

| Helper | Endpoint | Notes |
|--------|----------|-------|
| `availableModels()` | `GET /api/models/available` | Model matrix YAML-derived. |
| `listIntegrations()` | `GET /api/integrations` | Slack / JIRA / Confluence / Bitbucket / PagerDuty / ServiceNow. |
| `slackOauthInit()` | `GET /api/integrations/slack/oauth/init` | |
| `slackOauthCallback(code, state)` | `GET /api/integrations/slack/oauth/callback` | Public. |
| `disconnectIntegration(provider)` | `DELETE /api/integrations/{provider}` | |

Consumed by: `IntegrationsPage`, `NewRunModal` (provider selector), `ProviderModelSelector`.

---

## Theme + analytics (no backend)

- `useUiStore.ts` — `theme: 'dark' | 'light'` slice; pre-paint applies `<html data-theme>` + persists to `localStorage`.
- `analytics.ts` — thin wrapper over `posthog-js`. Enabled only when `VITE_POSTHOG_KEY` is set. Events mirror the 6-event activation funnel (`SIGNUP`, `PROJECT_CREATED`, `SPEC_SUBMITTED`, `PLAN_APPROVED`, `RUN_COMPLETE`, `PR_MERGED`).

---

## ApiContractTest regression guard

ArchUnit test at `src/test/java/com/navneet/executespec/arch/ApiContractTest.java` extracts:
- Every URL referenced from `frontend/src/lib/api.ts` (regex over the literal string args of `fetch`, `useApi`, etc.).
- Every backend `@*Mapping` annotation on every controller method (bytecode scan).

The test asserts every frontend URL resolves to at least one backend mapping. ~321 backend patterns × ~200 api.ts URLs, runs in 2.6s.
