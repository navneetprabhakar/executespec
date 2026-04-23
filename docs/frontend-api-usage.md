# Frontend API Usage Audit

> Every API call made from the frontend, organized by file. Use this to understand which backend endpoints the frontend depends on.
> Last updated: 2026-04-23 (v2.6.1 — Waves 1-6 persona roadmap: TEAM tier + lifecycle, onboarding+activation, SSO + team-trial, startup program shells)

## v2.6.1 additions (Waves 1-6)

**Wave 1 — TEAM tier + 3-state lifecycle**
- **`adjustOrgSeats(newSeats)`** → `POST /api/org/me/seats/adjust` body `{newSeats}` → `OrgDto`. Used by `OrgAdminDashboard`'s Seats panel on TEAM orgs. Rejects values outside `[2,20]`, below `activeMemberCount`, or non-TEAM tier with 400.
- **`fetchTiersLifecycle()`** → `GET /api/admin/tiers` → `{tiers: TierLifecycleRow[]}`. Used by the new `TierLifecyclePage` under `/admin/tiers`. Each row includes `tier`, `lifecycleState` (`LISTED|HIDDEN|SUNSETTING`), `sunsettingStartedAt`, `sunsettingEndsAt`, `hiddenSinceTs`, `metadataJson`.
- **`updateTierLifecycle(tier, body)`** → `PUT /api/admin/tiers/{tier}` body `{state, reason?, observationDays?}`. SUNSETTING transitions require a 14-day `HIDDEN` observation window — backend enforces 409 if not met.
- **Billing subscribe seats param** — `subscribe(planId, gateway, successUrl, cancelUrl, seats?)` now accepts an optional `seats` (2-20). Stripe path threads it through `/billing/subscribe` → `SubscriptionUpdateParams` → Checkout Session line_items quantity.
- **`PlanCard.tsx`** — introduces TIER_RANK slot `TEAM=2.5` between PRO and MAX; adds a seat picker (2-20) on the TEAM card wired to `subscribe`.
- **`PlansPage.tsx`** — tier display order locked as FREE → PRO (hidden when `LISTED`-only is on) → TEAM → MAX → ENTERPRISE.

**Wave 2 — onboarding + activation funnel**
- **`listOnboardingSamples()`** → `GET /api/onboarding/sample-specs` → `SampleSpec[]` (`{id, title, surface, description, tags[], expectedTimeMinutes}`). Reads from `sample-specs/{web-api,frontend,cli}.yml` shipped on the backend classpath.
- **`pickOnboardingSample(sampleId)`** → `POST /api/onboarding/pick-sample` body `{sampleId}` → `{runId, projectId}`. Provisions (or reuses) the sandbox project, drops the sample spec into the new run, dispatches via `V2RunOrchestrator.submitRun`.
- **`getSandboxStatus()`** → `GET /api/onboarding/sandbox-status` → `{provisioned, projectId?, lastActivity?, expiresAt?}`. Called by `WelcomePage` + `FirstRunCelebration` to decide whether to deep-link into the sandbox.
- **`RegisterPayload`** — adds `signupSource`, `utmCampaign`, `utmMedium`, `utmSource`, `referrer` (optional). `RegisterPage.tsx` reads `document.referrer` + querystring UTM params before the submit; backend writes to `app_user.signup_*` columns (Wave 2 V125 ledger + V126 sandbox).
- **`analytics.ts`** — thin wrapper over `posthog-js` (`initAnalytics`, `identify`, `page`, `reset`, `track`). Enabled only when `VITE_POSTHOG_KEY` is set. Events mirror the 6-event funnel (SIGNUP, PROJECT_CREATED, SPEC_SUBMITTED, PLAN_APPROVED, RUN_COMPLETE, PR_MERGED).
- **New pages** — `WelcomePage` (post-signup), `RunsNewLanding` (empty-state aware of sandbox), `FirstRunCelebration` (fires on first `RUN_COMPLETE`).

**Wave 3 — SSO + team-trial shells** (schema + docs live; UI lands post-launch)
- **`requestTeamTrial(body)`** → `POST /api/org/trial-request` body `{orgId, contactEmail, intendedSeats, notes?}` → `{id, status: 'PENDING'}`. Backed by `team_trial_request` table (V127). Not yet wired to a UI surface.
- **Org SSO preview** — `OrgDto` gains read-only `samlMetadataUrl`, `ssoRequired`, `scimProvisioning` fields. Admin UI is deferred; see `docs/plans/wave-3-sso-githubapp-procurement-plan.md`.

**Wave 4-6** — landing proof, routing heuristic scaffold, changelog, startup program application (`POST /api/startup-program/apply`), CLI bootstrap — all ship as schema + docs in v2.6.1; no new frontend-facing endpoints beyond the documented shells.

**Carryover from v2.6.0**
- **Theme state** — `useUiStore.ts` `theme: 'dark' | 'light'` slice with `setTheme(t)` / `toggleTheme()`. Pre-paint applies `<html data-theme>` + persists to `localStorage`. No backend API involved.
- **`ExecSummaryPage` (`/exec`)** — reads `/api/billing/usage`, `/api/runs?scope=org&limit=30`, `/api/projects?scope=org`, cross-project AWAITING_APPROVAL digest. Gated behind `canViewCostBreakdown` (PRO/TEAM/MAX/ENTERPRISE).
- **Provider name** — send `GEMINI` (preferred); `GOOGLE` accepted as input alias, rewritten server-side.
- **ScoreRing consumer** — `ValidationReportPanel` hero renders `ScoreRing` with `report.verifiedScore`.


## API Client Architecture

### Core Client: `src/lib/api.ts`

- **Base URL:** `/api` (relative — uses Vite proxy in dev, same-origin in prod)
- **Auth:** `Authorization: Bearer {token}` from `localStorage.getItem('access_token')`
- **Credentials:** `credentials: 'include'` (sends httpOnly `refresh_token` cookie)
- **Error handling:** Parses JSON error body for `message` or `error` fields, falls back to status-based friendly messages (400-503 mapped), then raw body text
- **Empty body handling:** Returns `undefined` for 204 and empty 200 responses (Spring endpoints like approve/merge)

### Specialized Clients

| File | Purpose |
|------|---------|
| `src/lib/billing-api.ts` | Billing, subscription (multi-gateway: Stripe + Razorpay), seat management, top-up endpoints |
| `src/lib/invoice-api.ts` | Invoices, billing profile, accounting (revenue, GST, trial balance), PDF downloads |

### Utility Libraries (no API calls)

| File | Purpose |
|------|---------|
| `src/lib/telemetry.ts` | OpenTelemetry browser tracing (OTLP export, SSE span tracking) |
| `src/lib/techDetect.ts` | Tech stack detection from run request text / backend `techStack` field (renders badges on RunCard) |
| `src/lib/vibeNarrative.ts` | Maps SSE log entries to human-readable narrative items for Vibe mode |
| `src/lib/monacoSetup.ts` | Monaco editor CDN loader initialization + Geist theme registration (idempotent) |
| `src/lib/monacoGeistTheme.ts` | Geist-inspired dark theme definition for Monaco editor |
| `src/lib/constants.ts` | Shared constants |
| `src/lib/utils.ts` | General utility functions |

### Hooks

| File | Purpose |
|------|---------|
| `src/hooks/useApi.ts` | Generic wrapper for async calls with loading/error state |
| `src/hooks/useSse.ts` | SSE (Server-Sent Events) connection for live run updates |
| `src/hooks/useTierPermissions.ts` | Tier-based permission checking |
| `src/hooks/useViewMode.ts` | Vibe/Pro view mode switching |
| `src/hooks/useKeyboardShortcuts.ts` | Global keyboard shortcut bindings |
| `src/hooks/useKeyboardScroll.ts` | Keyboard-driven scroll behavior |
| `src/hooks/useIsMobile.ts` | Mobile viewport detection |

---

## API Functions Defined in `src/lib/api.ts`

### Auth (lines 818-852)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `authRegister(body)` | POST | `/auth/register` | `{email, firstName, lastName, password}` | `AuthUser` |
| `authLogin(body)` | POST | `/auth/login` | `{email, password}` | `LoginResponse` |
| `authRefresh()` | POST | `/auth/refresh` | _(cookie)_ | `LoginResponse` |
| `authLogout()` | DELETE | `/auth/logout` | | |
| `authMe()` | GET | `/auth/me` | | `AuthUser` |
| `getMyPermissions()` | GET | `/auth/me/permissions` | | `TierPermissions` |
| `authVerifyEmail(token)` | GET | `/auth/verify-email?token=` | | `{message}` |
| `authResendVerification(email)` | POST | `/auth/resend-verification` | `{email}` | `{message}` |
| `authForgotPassword(email)` | POST | `/auth/forgot-password` | `{email}` | `{message}` |
| `authResetPassword(token, newPassword)` | POST | `/auth/reset-password` | `{token, newPassword}` | `{message}` |
| `authChangePassword(currentPassword, newPassword)` | POST | `/auth/change-password` | `{currentPassword, newPassword}` | `{message}` |

### User Settings (lines 858-915)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `updateGithubSettings(githubPat, githubOwner)` | PUT | `/auth/settings/github` | `{githubPat, githubOwner}` | `AuthUser` |
| `updateAiKeySettings(openAiApiKey, anthropicApiKey, geminiApiKey)` | PUT | `/auth/settings/ai-keys` | `{openAiApiKey, anthropicApiKey, geminiApiKey}` | `AuthUser` |
| `updateJiraSettings(jiraBaseUrl, jiraEmail, jiraApiToken)` | PUT | `/auth/settings/jira` | `{jiraBaseUrl, jiraEmail, jiraApiToken}` | `AuthUser` |
| `updateConfluenceSettings(...)` | PUT | `/auth/settings/confluence` | `{confluenceBaseUrl, confluenceEmail, confluenceApiToken}` | `AuthUser` |
| `updateViewMode(viewMode)` | PUT | `/auth/settings/view-mode` | `{viewMode}` | `AuthUser` |
| `validateGitHub(pat)` | POST | `/auth/settings/github/validate` | `{pat}` | `{valid, message}` |
| `validateJira(baseUrl, email, apiToken)` | POST | `/auth/settings/jira/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |
| `validateConfluence(baseUrl, email, apiToken)` | POST | `/auth/settings/confluence/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |

### Runs (lines 334-411)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getRuns(params)` | GET | `/runs?status=&projectId=&scope=&archived=&page=&size=` | _(query)_ | `PagedResponse<RunSummary>` |
| `archiveRun(runId)` | POST | `/runs/{runId}/archive` | | `{message}` |
| `getRun(id)` | GET | `/runs/{id}` | | `RunDetail` |
| `createRun(body)` | POST | `/runs` | `{userRequest, executionMode?, skipApproval?, autoMerge?, modelName?, aiProvider?, projectId?, repoMode?, repoUrl?}` | `{runId}` |
| `approveRun(id, approved)` | POST | `/runs/{id}/approve` | `{approved}` | |
| `clarifyRun(id, response)` | POST | `/runs/{id}/clarify` | `{response}` | **410 Gone** (V2) |
| `mergeRun(id, approved)` | POST | `/runs/{id}/merge` | `{approved}` | **410 Gone** (V2 auto-merges) |
| `cancelRun(id)` | DELETE | `/runs/{id}` | | |
| `retryTask(runId, taskId)` | POST | `/runs/{runId}/tasks/{taskId}/retry` | | |
| `retryRun(runId)` | POST | `/runs/{runId}/retry` | | **410 Gone** (V2 — submit fresh run) |
| `reopenMergeGate(runId)` | POST | `/runs/{runId}/reopen-merge` | | **410 Gone** (V2) |
| `implementSuggestions(runId)` | POST | `/runs/{runId}/implement-suggestions` | | **410 Gone** (V2) |
| `reworkRun(runId)` | POST | `/runs/{runId}/rework` | | **410 Gone** (V2) |
| `pauseRun(runId)` | POST | `/runs/{runId}/pause` | | **410 Gone** (V2) |
| `editAndRestartRun(runId, userRequest)` | POST | `/runs/{runId}/edit-and-restart` | `{userRequest}` | **410 Gone** (V2) |
| `resumeRun(runId)` | POST | `/runs/{runId}/resume` | | **410 Gone** (V2) |
| `editRunPlan(runId, feedback)` | POST | `/runs/{runId}/edit-plan` | `{feedback}` | `{status, version}` |
| `getPlanVersions(runId)` | GET | `/runs/{runId}/plan-versions` | | `any[]` |
| `updateRunProject(runId, projectId)` | PATCH | `/runs/{runId}/project` | `{projectId}` | `RunSummary` |
| `runTests(runId)` | POST | `/runs/{runId}/run-tests` | | `{runId, testPlanId, previewUrl}` |

### Run Content (lines 600-661)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getTimeline(runId)` | GET | `/runs/{runId}/timeline` | | `TimelineData` |
| `getFiles(runId)` | GET | `/runs/{runId}/files` | | `string[]` |
| `downloadWorkspace(runId)` | GET | `/runs/{runId}/download` | | _Blob (zip)_ |
| `pushToGitHub(runId, repoMode)` | POST | `/runs/{runId}/push-github?repoMode=` | | `{status, repoUrl}` |
| `getFile(runId, filePath)` | GET | `/runs/{runId}/file?path=` | | `string` (text/plain) |
| `getMessages(runId)` | GET | `/runs/{runId}/messages` | | `MessageResponse[]` |
| `getOpenApiSpec(runId)` | GET | `/runs/{runId}/openapi.yaml` | | `string` (text/plain) |
| `probeApiDocs(runId)` | GET | `/runs/{runId}/preview/probe-api-docs` | | `ApiDocsProbeResult` |
| `getTaskGraph(runId)` | GET | `/runs/{runId}/task-graph` | | `TaskGraphNode[]` |

### Metrics (lines 415-438)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getMetrics(days?)` | GET | `/metrics?days=` | | `MetricsResponse` |
| `getModelComparison(days?)` | GET | `/model-comparison?days=` | | `ModelComparisonData` |
| `getUserRunsSummary(days?)` | GET | `/user-runs-summary?days=` | | `UserRunsSummaryData` |

### Cost & Feasibility (lines 449-455)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getLiveCost(runId)` | GET | `/runs/{runId}/cost` | | `{runId, costUsd}` |
| `feasibility(body)` | POST | `/feasibility` | `{goal, provider?, model?, budgetUsd?}` | `FeasibilityReport` |

### Models & Pricing (lines 371-372, 442-445, 1471-1474)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getAvailableModels()` | GET | `/models/available` | | `AvailableModelsResponse` |
| `getPricing()` | GET | `/pricing` | | `PricingRow[]` |
| `updatePricing(provider, model, body)` | PUT | `/pricing/{provider}/{model}` | _partial_ | `PricingRow` |
| `getAllPricing()` | GET | `/pricing` | | `PricingRow[]` |
| `updateModelPricing(provider, model, data)` | PUT | `/pricing/{provider}/{model}` | `{inputCostPer1m, outputCostPer1m, notes?}` | `PricingRow` |

### Preview (lines 697-715)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `analyzePreview(runId)` | POST | `/runs/{runId}/preview/analyze` | | `PreviewAnalysis` |
| `getPreviewStatus(runId)` | GET | `/runs/{runId}/preview/status` | | `LocalPreviewStatus` |
| `startPreview(runId, envVars?)` | POST | `/runs/{runId}/preview/start` | `{envVars?}` | `LocalPreviewStatus` |
| `stopPreview(runId)` | DELETE | `/runs/{runId}/preview/stop` | | |
| `getPreviewLogs(runId, lines?, filter?)` | GET | `/runs/{runId}/preview/logs?lines=&filter=` | | `PreviewLogs` |

### Deployment (lines 459-493)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getDeployments()` | GET | `/deployments` | | `DeploymentSummary[]` |
| `createDeployment(body)` | POST | `/deployments` | `{runId, strategy?}` | `DeploymentSummary` |
| `deleteDeployment(id)` | DELETE | `/deployments/{id}` | | |
| `rollbackDeployment(id)` | POST | `/deployments/{id}/rollback` | | `DeploymentSummary` |
| `analyzeForDeploy(runId)` | POST | `/deployments/analyze?runId=` | | `DeployAnalysisResult` |
| `confirmDeploy(body)` | POST | `/deployments/confirm` | `{runId, deploymentPath, region?, envVars?, manifestJson?, healthCheckPath?, appPort?}` | `DeploymentSummary` |
| `engineRollback(id)` | POST | `/deployments/{id}/engine-rollback` | | |
| `engineDestroy(id)` | DELETE | `/deployments/{id}/engine-destroy` | | |
| `getDeploymentDetail(id)` | GET | `/deployments/{id}` | | `DeploymentSummary` |

### GCP Credentials (lines 497-524)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getRequiredPermissions()` | GET | `/credentials/gcp/required-permissions` | | `RequiredPermission[]` |
| `storeGcpCredential(json, projectId?)` | POST | `/credentials/gcp?projectId=` | _raw JSON_ | `CredentialSummary` |
| `getGcpCredential(projectId?)` | GET | `/credentials/gcp?projectId=` | | `CredentialSummary?` |
| `listGcpCredentials()` | GET | `/credentials/gcp/all` | | `CredentialSummary[]` |
| `validateGcpCredential(projectId?)` | GET | `/credentials/gcp/validate?projectId=` | | `ValidationResult` |
| `deleteGcpCredential(projectId?)` | DELETE | `/credentials/gcp?projectId=` | | `{message}` |

### Integrations (lines 528-596)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getIntegrationStatus()` | GET | `/integrations/status` | | `{enabledSources[]}` |
| `getIntegrationRuns(source?)` | GET | `/integrations/runs?source=` | | `RunSummary[]` |
| `triggerConfluence(body)` | POST | `/integrations/confluence/trigger` | `{pageId, goal}` | `{runId}` |
| `triggerJira(body)` | POST | `/integrations/jira/trigger` | `{issueKey, goal?}` | `{runId}` |
| `getSlackStatus()` | GET | `/integrations/slack/status` | | `SlackStatus` |
| `getSlackInstallUrl()` | GET | `/integrations/slack/install` | | `{installUrl}` |
| `disconnectSlack()` | DELETE | `/integrations/slack/disconnect` | | |
| `listSlackChannels()` | GET | `/integrations/slack/channels` | | `SlackChannel[]` |
| `getProjectSlackChannels(projectId)` | GET | `/integrations/slack/projects/{projectId}/channels` | | `ProjectSlackChannelDto[]` |
| `addProjectSlackChannel(projectId, ...)` | POST | `/integrations/slack/projects/{projectId}/channels` | `{channelId, channelName, verbosity}` | `ProjectSlackChannelDto` |
| `removeProjectSlackChannel(projectId, channelId)` | DELETE | `/integrations/slack/projects/{projectId}/channels/{channelId}` | | |
| `updateSlackChannelVerbosity(projectId, channelId, verbosity)` | PUT | `.../{channelId}/verbosity` | `{verbosity}` | |
| `sendSlackTestMessage(channelId)` | POST | `/integrations/slack/test` | `{channelId}` | `{ok, message}` |

### Chat & Conversation (lines 724-777)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `conversationChat(messages)` | POST | `/conversation` | `{messages[]}` | `{reply}` |
| `getRunChat(runId)` | GET | `/runs/{runId}/chat` | | `RunChatMessage[]` |
| `sendRunChatMessage(runId, message)` | POST | `/runs/{runId}/chat` | `{message}` | `ChatResponse` |
| `applyToRun(runId, suggestion, source)` | POST | `/runs/{runId}/apply` | `{suggestion, source}` | `ApplyResult` |
| `iterateRun(runId, userRequest)` | POST | `/runs/{runId}/iterate` | `{userRequest}` | `{runId}` |

### Validation & Test Reports (lines 1301-1422)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getValidationReport(runId)` | GET | `/runs/{runId}/report` | | `ValidationReportData?` |
| `shareValidationReport(runId)` | POST | `/runs/{runId}/report/share` | | `{shareToken, shareUrl}` |
| `getSharedReport(shareToken)` | GET | `/reports/shared/{shareToken}` | | `SharedReportData?` |
| `downloadValidationReportPdf(runId)` | GET | `/runs/{runId}/report/pdf` | | _Blob (PDF)_ |
| `getTestReport(runId)` | GET | `/runs/{runId}/test-report` | | `TestReportData?` |
| `getTestPlan(runId)` | GET | `/runs/{runId}/test-plan` | | `TestPlanData?` |
| `getTestGraph(runId)` | GET | `/runs/{runId}/test-graph` | | `TestGraphData?` |
| `markTestInvalid(testResultId)` | PUT | `/test-results/{testResultId}/mark-invalid` | | |
| `applyTestFixes(runId, signature?)` | POST | `/runs/{runId}/apply-test-fixes?signature=` | | |

### Onboarding (v2.6.1 Wave 2)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `listOnboardingSamples()` | GET | `/onboarding/sample-specs` | | `SampleSpec[]` |
| `pickOnboardingSample(sampleId)` | POST | `/onboarding/pick-sample` | `{sampleId}` | `{runId, projectId}` |
| `getSandboxStatus()` | GET | `/onboarding/sandbox-status` | | `{provisioned, projectId?, lastActivity?, expiresAt?}` |

### Tier Lifecycle (v2.6.1 Wave 1, SUPER_ADMIN)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `fetchTiersLifecycle()` | GET | `/admin/tiers` | | `{tiers: TierLifecycleRow[]}` |
| `updateTierLifecycle(tier, body)` | PUT | `/admin/tiers/{tier}` | `{state, reason?, observationDays?}` | `TierLifecycleRow` |

### Organization (lines 967-1034)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getMyOrg()` | GET | `/org/me` | | `OrgDto` |
| `createOrg(body)` | POST | `/org` | `{name, domains[], adminEmail}` | `OrgDto` |
| `updateOrg(body)` | PUT | `/org/me` | `{name?, domains?}` | `OrgDto` |
| `updateOrgAiKeySettings(...)` | PUT | `/org/me/settings/ai-keys` | `{anthropicApiKey, openAiApiKey, geminiApiKey}` | `OrgDto` |
| `updateOrgKeySourceSettings(...)` | PUT | `/org/me/settings/key-source` | `{keySourceMode, enabledProviders[]}` | `OrgDto` |
| `updateOrgJiraSettings(...)` | PUT | `/org/me/settings/jira` | `{baseUrl, email, apiToken}` | `OrgDto` |
| `validateOrgJira(...)` | POST | `/org/me/settings/jira/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |
| `updateOrgConfluenceSettings(...)` | PUT | `/org/me/settings/confluence` | `{baseUrl, email, apiToken}` | `OrgDto` |
| `validateOrgConfluence(...)` | POST | `/org/me/settings/confluence/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |
| `getOrgMembers()` | GET | `/org/me/members` | | `OrgMemberDto[]` |
| `removeMember(userId)` | DELETE | `/org/me/members/{userId}` | | |
| `changeMemberRole(userId, role)` | PUT | `/org/me/members/{userId}/role` | `{role}` | |
| `orgSuspendMember(userId, suspended)` | PUT | `/org/me/members/{userId}/suspend` | `{suspended}` | |
| `inviteMember(body)` | POST | `/org/invite` | `{email, orgRole?, projectIds?}` | `{message, status}` |
| `getOrgInvitations()` | GET | `/org/me/invitations` | | `OrgInvitation[]` |
| `resendInvitation(id)` | POST | `/org/me/invitations/{id}/resend` | | `{message}` |
| `acceptInvite(token)` | POST | `/org/accept-invite?token=` | | `{message}` |
| `adjustOrgSeats(newSeats)` | POST | `/org/me/seats/adjust` | `{newSeats}` | `OrgDto` |
| `requestTeamTrial(body)` | POST | `/org/trial-request` | `{orgId, contactEmail, intendedSeats, notes?}` | `{id, status}` |

### Projects (lines 1057-1078)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getProjects()` | GET | `/projects` | | `ProjectDto[]` |
| `createProject(body)` | POST | `/projects` | `{name, description?, aiProvider?}` | `ProjectDto` |
| `updateProjectSettings(id, body)` | PATCH | `/projects/{id}/settings` | `{aiProvider}` | `ProjectDto` |
| `archiveProject(id)` | PUT | `/projects/{id}/archive` | | `ProjectDto` |
| `getProjectMembers(projectId)` | GET | `/projects/{projectId}/members` | | `ProjectMemberDto[]` |
| `addProjectMember(projectId, userId, role)` | POST | `/projects/{projectId}/members` | `{userId, role}` | |
| `removeProjectMember(projectId, userId)` | DELETE | `/projects/{projectId}/members/{userId}` | | |

### Admin (lines 1082-1181)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `adminListUsers()` | GET | `/admin/users` | | `AuthUser[]` |
| `adminGetUser(id)` | GET | `/admin/users/{id}` | | `AuthUser` |
| `adminUpdateUserStatus(id, status)` | PUT | `/admin/users/{id}/status` | `{status}` | `AuthUser` |
| `adminUpdateUserTier(id, tier)` | PUT | `/admin/users/{id}/tier` | `{tier}` | `AuthUser` |
| `adminListOrgs()` | GET | `/admin/orgs` | | `OrgDto[]` |
| `adminUpdateOrgTier(id, tier)` | PUT | `/admin/orgs/{id}/tier` | `{tier}` | `OrgDto` |
| `adminUpdateOrgStatus(id, status)` | PUT | `/admin/orgs/{id}/status` | `{status}` | `OrgDto` |
| `adminListEscalated()` | GET | `/admin/invitations/escalated` | | `OrgInvitation[]` |
| `adminApproveInvite(id)` | POST | `/admin/invitations/{id}/approve` | | `{message}` |
| `adminRejectInvite(id)` | POST | `/admin/invitations/{id}/reject` | | `{message}` |
| `adminGetTierConfig()` | GET | `/admin/tier-config` | | `TierConfig[]` |
| `adminUpdateTierConfig(tier, monthlyRunLimit)` | PUT | `/admin/tier-config/{tier}` | `{monthlyRunLimit}` | `TierConfig` |
| `adminUpdateOrgRunLimit(id, limit)` | PUT | `/admin/orgs/{id}/run-limit` | `{limit}` | `OrgDto` |
| `adminUpdateOrgApiKeys(id, provided)` | PUT | `/admin/orgs/{id}/api-keys` | `{provided}` | `OrgDto` |
| `adminInviteUser(body)` | POST | `/admin/invite` | `{email, firstName, lastName, tier, orgId?, newOrgName?, orgRole?}` | `{message}` |
| `adminGrantUserRuns(userId, quantity, ...)` | POST | `/admin/users/{userId}/grant-runs` | `{quantity, expiryDays?, reason?}` | |
| `adminGrantOrgRuns(orgId, quantity, ...)` | POST | `/admin/orgs/{orgId}/grant-runs` | `{quantity, expiryDays?, reason?}` | |
| `adminListTopupPackages()` | GET | `/admin/topup-packages` | | `TopupPackageAdmin[]` |
| `adminCreateTopupPackage(body)` | POST | `/admin/topup-packages` | `{name, runCount, amountInr, amountUsd?, sortOrder?}` | `TopupPackageAdmin` |
| `adminUpdateTopupPackage(id, body)` | PUT | `/admin/topup-packages/{id}` | _partial_ | `TopupPackageAdmin` |
| `adminDeleteTopupPackage(id)` | DELETE | `/admin/topup-packages/{id}` | | `{message}` |
| `adminTriggerUsageReport(period)` | POST | `/admin/usage-reports/trigger` | `{period}` | `{emailsSent, period}` |
| `adminListFeatureFlags()` | GET | `/admin/feature-flags` | | `FeatureFlagDto[]` |
| `adminUpdateFeatureFlag(key, value)` | PUT | `/admin/feature-flags/{key}` | `{value}` | `FeatureFlagDto` |
| `getAuditLogs(params)` | GET | `/admin/audit-logs` or `/org/me/audit-logs` | _(query: entityType, eventType, actorId, entityId, from, to, page, size)_ | `AuditLogPage` |

### Admin Analytics (lines 1462-1469)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getAnalyticsUsage(entityType, entityId, period)` | GET | `/admin/analytics/usage?entityType=&entityId=&period=` | | `UsageMetrics` |
| `getAnalyticsMembers(orgId, period)` | GET | `/admin/analytics/members?orgId=&period=` | | `MemberUsage[]` |
| `getAnalyticsBilling(entityType, entityId)` | GET | `/admin/analytics/billing?entityType=&entityId=` | | `BillingOverviewData` |

### Payment Audit (lines 1530-1560)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `adminListPaymentAudit(page, size, orgId?, userId?)` | GET | `/admin/payment-audit?page=&size=&orgId=&userId=` | | `PagedResponse<PaymentAuditEntry>` |
| `adminExportPaymentAuditCsv()` | GET | `/admin/payment-audit/export` | | _Blob (CSV)_ |
| `adminListWebhookEvents(page, size, gateway?)` | GET | `/admin/webhook-events?page=&size=&gateway=` | | `PagedResponse<WebhookEventEntry>` |

---

## API Functions Defined in `src/lib/billing-api.ts`

Multi-gateway support: `subscribe()` accepts optional `gateway` param (`'stripe'` or `'razorpay'`). Stripe returns `checkoutUrl` + `sessionId`; Razorpay returns `gatewaySubscriptionId` + `razorpayKeyId` for client SDK.

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `fetchPlans(keyMode?)` | GET | `/billing/plans?keyMode=` | | `BillingPlan[]` |
| `fetchBillingStatus()` | GET | `/billing/status` | | `BillingStatus` |
| `subscribe(planId, gateway?, successUrl?, cancelUrl?, seats?)` | POST | `/billing/subscribe` | `{planId, gateway, successUrl, cancelUrl, seats?}` | `SubscriptionInitResult` |
| `verifyPayment(razorpaySubscriptionId, razorpayPaymentId, razorpaySignature)` | POST | `/billing/verify` | `{razorpaySubscriptionId, razorpayPaymentId, razorpaySignature}` | `BillingStatus` |
| `cancelSubscription()` | POST | `/billing/cancel` | | |
| `updateSeats(totalSeats)` | POST | `/billing/seats` | `{totalSeats}` | `BillingStatus` |
| `fetchTopupPackages()` | GET | `/billing/topup/packages` | | `TopupPackage[]` |
| `purchaseTopup(packageId)` | POST | `/billing/topup/purchase` | `{packageId}` | `TopupOrderResult` |
| `verifyTopup(razorpayOrderId, razorpayPaymentId, razorpaySignature)` | POST | `/billing/topup/verify` | `{razorpayOrderId, razorpayPaymentId, razorpaySignature}` | `BillingStatus` |

### Key Types in billing-api.ts

- `SubscriptionInitResult` includes both Razorpay fields (`gatewaySubscriptionId`, `razorpayKeyId`, `shortUrl`) and Stripe fields (`gateway`, `checkoutUrl`, `sessionId`)
- `BillingStatus` contains `subscription` (with `paymentGateway` field), `plan`, `usage[]`, and `bonusRuns`
- `BillingPlan` includes dual-currency amounts (`baseAmountInr`/`baseAmountUsd`, `perSeatInr`/`perSeatUsd`), founder pricing flag, and `limits` map

---

## API Functions Defined in `src/lib/invoice-api.ts`

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getBillingProfile()` | GET | `/billing/profile` | | `BillingProfile` |
| `updateBillingProfile(profile)` | PUT | `/billing/profile` | `BillingProfile` | `BillingProfile` |
| `getMyInvoices(page?, size?)` | GET | `/billing/invoices?page=&size=` | | `Page<Invoice>` |
| `getInvoiceDetail(id)` | GET | `/billing/invoices/{id}` | | `InvoiceDetail` |
| `getAllInvoices(page?, size?)` | GET | `/admin/invoices?page=&size=` | | `Page<Invoice>` |
| `issueCreditNote(invoiceId, amountPaise, reason)` | POST | `/admin/invoices/{id}/credit-note` | `{amountPaise, reason}` | `Invoice` |
| `voidInvoice(invoiceId)` | POST | `/admin/invoices/{id}/void` | | `Invoice` |
| `resendInvoice(invoiceId)` | POST | `/admin/invoices/{id}/send` | | |
| `getSellerConfig()` | GET | `/admin/seller-config` | | `SellerConfig` |
| `updateSellerConfig(config)` | PUT | `/admin/seller-config` | `SellerConfig` | `SellerConfig` |
| `getRevenueMetrics(from, to)` | GET | `/accounting/revenue?from=&to=` | | `RevenueMetrics` |
| `getGstSummary(fy, quarter)` | GET | `/accounting/gst-summary?fy=&quarter=` | | `GstSummary` |
| `getTrialBalance(from, to)` | GET | `/accounting/trial-balance?from=&to=` | | `TrialBalanceEntry[]` |
| `downloadInvoicePdf(id, invoiceNumber, admin?)` | GET | `/billing/invoices/{id}/pdf` or `/admin/invoices/{id}/pdf` | | _Blob (PDF)_ |
| `getGstr1ExportUrl(fy, quarter)` | _builds URL_ | `/accounting/gstr1-export?fy=&quarter=` | | _URL string_ |

### Helper in invoice-api.ts

- `formatPaise(paise)` -- formats paise to INR currency string

---

## Component-Level API Usage

### Stores (Zustand)

#### `src/stores/useAuthStore.ts`
| Action | Calls |
|--------|-------|
| `login()` | `authLogin()` -- stores `accessToken` in localStorage |
| `register()` | `authRegister()` |
| `refreshAccessToken()` | `authRefresh()` -- updates stored token |
| `loadMe()` | `authMe()` -- populates `user` state |
| `logout()` | `authLogout()` -- clears localStorage |

#### `src/stores/useRunStore.ts`
| Action | Calls |
|--------|-------|
| `fetchRuns(params)` | `getRuns(params)` |
| `fetchRun(id)` | `getRun(id)` |
| `createRun(data)` | `createRun(data)` |
| `cancelRun(id)` | `cancelRun(id)` |
| `approveRun(id, approved)` | `approveRun(id, approved)` |
| `clarifyRun(id, response)` | `clarifyRun(id, response)` |
| `mergeRun(id, approved)` | `mergeRun(id, approved)` |
| `retryTask(runId, taskId)` | `retryTask(runId, taskId)` |
| `retryRun(runId)` | `retryRun(runId)` |
| `reopenMergeGate(runId)` | `reopenMergeGate(runId)` |
| `implementSuggestions(runId)` | `implementSuggestions(runId)` |
| `reworkRun(runId)` | `reworkRun(runId)` |
| `pauseRun(id)` | `pauseRun(id)` |
| `editAndRestartRun(runId, req)` | `editAndRestartRun(runId, req)` |
| `resumeRun(runId)` | `resumeRun(runId)` |

#### `src/stores/useMetricsStore.ts`
| Action | Calls |
|--------|-------|
| `fetchMetrics(days)` | `getMetrics(days)` |
| `fetchModelComparison(days)` | `getModelComparison(days)` |
| `fetchUserRunsSummary(days)` | `getUserRunsSummary(days)` |

#### `src/stores/useSseStore.ts`
No API calls. Zustand store for SSE connection state + log entries.

#### `src/stores/useUiStore.ts`
No API calls. UI state (sidebar, modals).

### Pages

#### `src/pages/SettingsPage.tsx`
- `validateGitHub()` -- validate PAT
- `updateGithubSettings()` -- save or clear
- `updateAiKeySettings()` -- save or clear
- `updateOrgAiKeySettings()` -- save or clear
- `updateOrgKeySourceSettings()` -- save mode & providers
- `getMetrics()` -- system health display

#### `src/pages/BillingPage.tsx`
- `fetchBillingStatus()` -- on mount
- `fetchTopupPackages()` -- on mount
- `cancelSubscription()` -- user action
- `purchaseTopup()` -- Razorpay checkout -- `verifyTopup()`

#### `src/pages/PlansPage.tsx`
- `fetchPlans()` -- on mount
- `subscribe(planId, gateway, successUrl, cancelUrl)` -- initiates checkout (Stripe redirect or Razorpay SDK)
- `verifyPayment()` -- Razorpay post-checkout verification

#### `src/pages/IntegrationsPage.tsx`
- Jira: `validateOrgJira()`, `updateOrgJiraSettings()` (save/clear)
- Confluence: `validateOrgConfluence()`, `updateOrgConfluenceSettings()` (save/clear)
- Slack: `getSlackStatus()`, `getSlackInstallUrl()`, `sendSlackTestMessage()`
- Status: `getIntegrationStatus()`, `getIntegrationRuns()`

#### `src/pages/DeploymentsPage.tsx`
- `getDeployments()` -- on mount

#### `src/pages/admin/AdminPage.tsx`
- Loads: `adminListUsers()`, `adminListOrgs()`, `adminGetTierConfig()`, `adminListEscalated()`, `adminListTopupPackages()`, `adminListFeatureFlags()`
- Actions: `adminUpdateUserStatus()`, `adminUpdateUserTier()`, `adminUpdateOrgStatus()`, `adminUpdateOrgTier()`, `adminUpdateOrgRunLimit()`, `adminUpdateOrgApiKeys()`, `adminUpdateTierConfig()`, `adminApproveInvite()`, `adminRejectInvite()`, `adminTriggerUsageReport()`, `adminCreateTopupPackage()`, `adminUpdateTopupPackage()`, `adminDeleteTopupPackage()`, `adminUpdateFeatureFlag()`
- Payment audit: `adminListPaymentAudit()`, `adminExportPaymentAuditCsv()`, `adminListWebhookEvents()`

#### `src/pages/org/OrgSettingsPage.tsx`
- `createOrg()`, `getMyOrg()`, `getOrgMembers()`, `getOrgInvitations()`, `resendInvitation()`

#### `src/pages/org/OrgProjectsPage.tsx`
- `getProjects()`, `createProject()`, `getProjectMembers()`, `updateProjectSettings()`, `getOrgMembers()`

#### `src/pages/SharedReportPage.tsx`
- `getSharedReport(shareToken)` -- public, no auth

#### `src/pages/auth/VerifyEmailPage.tsx`
- `authVerifyEmail(token)`

#### `src/pages/auth/ResetPasswordPage.tsx`
- `authResetPassword(token, newPassword)`

#### `src/pages/auth/ForgotPasswordPage.tsx`
- `authForgotPassword(email)`

### Components

#### `src/components/runs/NewRunModal.tsx`
- `conversationChat(messages)` -- pre-run clarification
- `createRun(data)` -- submit run
- `getAvailableModels()` -- populate model selector

#### `src/components/runs/RunConversation.tsx`
- `getRunChat(runId)` -- load history on mount
- `sendRunChatMessage(runId, message)` -- send message

#### `src/components/runs/PreviewTab.tsx`
- `getPreviewStatus(runId)` -- poll status
- `startPreview(runId, envVars)` -- start container
- `stopPreview(runId)` -- stop container
- `getPreviewLogs(runId, lines, filter)` -- poll logs every 5s
- `analyzePreview(runId)` -- check DB/env requirements

#### `src/components/runs/ValidationReportPanel.tsx`
- `getValidationReport(runId)` -- load report
- `shareValidationReport(runId)` -- generate share link
- `downloadValidationReportPdf(runId)` -- download PDF

#### `src/components/runs/RunList.tsx`
- `getProjects()` -- populate project filter
- `archiveRun(runId)` -- archive from list

#### `src/components/runs/TechBadges.tsx`
- `resolveTechStack()` from `techDetect.ts` (no API call; resolves from local data)

#### `src/components/runs/VibeProgressNarrative.tsx`
- `mapEventToNarrative()` from `vibeNarrative.ts` (no API call; maps SSE events)

#### `src/components/runs/RunDetailTabs.tsx`
- Uses `monacoGeistTheme.ts` for code viewer theme (no API call)

#### `src/components/run/FileViewer.tsx`
- Uses `monacoGeistTheme.ts` for code viewer theme (no API call)

#### `src/components/deployments/DeployWizardModal.tsx`
- `getGcpCredential()`, `getRequiredPermissions()` -- GCP setup
- `validateGcpCredential()` -- validate
- `analyzeForDeploy(runId)` -- analyze readiness
- `confirmDeploy(data)` -- deploy
- `getDeploymentDetail(id)` -- poll status

#### `src/components/auth/ProjectSelector.tsx`
- `getProjects()` -- populate dropdown
- `createProject(data)` -- inline create

#### `src/components/auth/ChangePasswordModal.tsx`
- `authChangePassword(data)`

#### `src/components/layout/Sidebar.tsx`
- `getProjects()` -- populate project navigation
- `fetchBillingStatus()` -- usage badge display

#### `src/components/billing/UsageBadge.tsx`
- `fetchBillingStatus()` -- on mount

#### `src/components/billing/PlanCard.tsx`
- Uses `BillingPlan` type from billing-api.ts (no API call; receives data via props)

---

## SSE (Server-Sent Events)

#### `src/hooks/useSse.ts`
- **Endpoint:** `/api/runs/{runId}/events`
- **Auth:** `?token={accessToken}` query parameter
- **Reconnect:** Exponential backoff (1s, 2s, 4s, 8s, 16s, 30s cap)
- **On reconnect:** Full `fetchRun()` to catch missed events
- **Pre-populate:** Loads historical `getMessages(runId)` on mount
- **Refetch triggers** (via `RUN_STATUS` payload): `COMPLETE`, `FAILED`, `CANCELLED`, `PAUSED`, `AWAITING_APPROVAL`, `AWAITING_MERGE`, `COMPILING`, `MERGING`, `PARTIAL`
- **Additional refetch triggers:** `TASK_STATUS`, `GATE_OPENED`, `COMPLETE`, `RUN_RESTARTED`, `BUDGET_EXCEEDED`

#### `src/lib/telemetry.ts`
- **SSE span tracking:** `createSseSpan(runId)` creates OpenTelemetry spans for SSE connection lifecycle (connect, reconnect, error, end)
- **Requires:** `VITE_OTEL_ENDPOINT` env var (disabled by default)

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| API functions in `api.ts` | ~130 |
| API functions in `billing-api.ts` | 9 |
| API functions in `invoice-api.ts` | 15 |
| **Total API functions** | **~154** |
| Unique backend endpoints used | ~120 |
| Files making API calls | ~28 |
| SSE event types handled | 5 (RUN_STATUS, TASK_STATUS, GATE_OPENED, COMPLETE, RUN_RESTARTED, BUDGET_EXCEEDED) |
| Utility lib files (no API) | 5 (telemetry, techDetect, vibeNarrative, monacoSetup, monacoGeistTheme) |

---

## Pilot v3.0 — Project workspace endpoints

| Endpoint | Frontend caller | Component / store |
|---|---|---|
| `GET /api/projects/{id}` | `getProject` | `ProjectDashboardPage` (header) |
| `GET /api/projects/{id}/dashboard` | `getProjectDashboard` | `useProjectStore.fetchDashboard` → metric tiles + Overview tab |
| `GET /api/projects/{id}/scores` | `getProjectScores` | `useProjectStore.fetchScoreTrend` → `ProjectScoreTrend` (recharts) |
| `POST /api/repos` | `createRepo` | `AddRepoModal` (Create-new tab) |
| `GET /api/repos?scope=ORG\|USER\|ALL` | `listRepos` | `AddRepoModal` (Pick-existing tab) |
| `POST /api/repos/{repoId}/sync` | `syncRepo` | `ProjectRepoManager` (RepoCard sync button) |
| `DELETE /api/repos/{repoId}` | `deleteRepo` | (planned admin UI; not yet wired) |
| `GET /api/projects/{id}/repos` | `getProjectRepos` | `useProjectStore.fetchRepos`, `NewRunModal` multi-select |
| `POST /api/projects/{id}/repos/{repoId}` | `linkProjectRepo` | `AddRepoModal.link` |
| `DELETE /api/projects/{id}/repos/{repoId}` | `unlinkProjectRepo` | `ProjectRepoManager.handleUnlink` |
| `PUT /api/projects/{id}/repos/{repoId}/relationships` | `updateRepoRelationships` | `RepoCard` editor |
| `POST /api/projects/{id}/architecture/generate` | `generateArchitecture` | `ArchitectureViewer.generate` |
| `GET /api/projects/{id}/architecture` | `getArchitectureDoc` | `ArchitectureViewer.refresh` |
| `GET /api/projects/{id}/architecture/versions` | `getArchitectureVersions` | `ArchitectureViewer` version dropdown |
| `PUT /api/projects/{id}/architecture/annotate` | `annotateArchitecture` | `ArchitectureViewer.saveAnnotation` |
| `GET /api/projects/{id}/architecture/staleness` | `getArchitectureStaleness` | `ArchitectureViewer` amber stale badge |
| `GET /api/projects/{id}/repos/{repoId}/codemap` | `getRepoCodeMap` | (planned `CodeMapPanel`; endpoint live) |
| `GET /api/runs/{id}/prs` | `getRunPrs` | `RunDetailTabs.RunPrList` |
| `POST /api/runs` extends | `createRun` | now accepts `selectedRepoIds[]` and `figmaUrl` |

### New types (`src/types/project.ts`)
`ProjectRepo`, `ProjectRepoLink`, `RepoScope`, `StackInfo`, `ProjectDashboardDTO`,
`ScoreEntry`, `PrSummary`, `TaskSummary`, `ArchitectureDoc`, `AssessmentResult`,
`LinkRepoRequest`. Plus `StalenessReport` / `RepoStaleness` exported from
`src/lib/api.ts`.

### Backend-only endpoint
`POST /webhooks/github` (HMAC SHA-256 verified; secret in `github.webhook.secret`)
— not called from the frontend; configured per-repo in GitHub settings.

