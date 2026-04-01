# Frontend API Usage Audit

> Every API call made from the frontend, organized by file. Use this to understand which backend endpoints the frontend depends on.

## API Client Architecture

### Core Client: `src/lib/api.ts`

- **Base URL:** `/api` (relative — uses Vite proxy in dev, same-origin in prod)
- **Auth:** `Authorization: Bearer {token}` from `localStorage.getItem('access_token')`
- **Credentials:** `credentials: 'include'` (sends httpOnly `refresh_token` cookie)
- **Error handling:** Parses JSON error body, falls back to HTTP status message

### Specialized Clients

| File | Purpose |
|------|---------|
| `src/lib/billing-api.ts` | Billing, subscription, top-up endpoints |
| `src/lib/invoice-api.ts` | Invoices, accounting, PDF downloads |

### Hooks

| File | Purpose |
|------|---------|
| `src/hooks/useApi.ts` | Generic wrapper for async calls with loading/error state |
| `src/hooks/useSse.ts` | SSE (Server-Sent Events) connection for live run updates |

---

## API Functions Defined in `src/lib/api.ts`

### Auth (lines 803-900)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `authRegister(data)` | POST | `/auth/register` | `{email, firstName, lastName, password}` | `UserDto` |
| `authLogin(data)` | POST | `/auth/login` | `{email, password}` | `{user, accessToken}` |
| `authRefresh()` | POST | `/auth/refresh` | _(cookie)_ | `{user, accessToken}` |
| `authLogout()` | DELETE | `/auth/logout` | | |
| `authMe()` | GET | `/auth/me` | | `UserDto` |
| `authPermissions()` | GET | `/auth/me/permissions` | | `TierPermissions` |
| `authVerifyEmail(token)` | GET | `/auth/verify-email?token=` | | `{message}` |
| `authResendVerification(email)` | POST | `/auth/resend-verification` | `{email}` | `{message}` |
| `authForgotPassword(email)` | POST | `/auth/forgot-password` | `{email}` | `{message}` |
| `authResetPassword(data)` | POST | `/auth/reset-password` | `{token, newPassword}` | `{message}` |
| `authChangePassword(data)` | POST | `/auth/change-password` | `{currentPassword, newPassword}` | `{message}` |

### User Settings (lines 843-900)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `updateGithubSettings(data)` | PUT | `/auth/settings/github` | `{githubPat, githubOwner}` | `UserDto` |
| `updateAiKeySettings(data)` | PUT | `/auth/settings/ai-keys` | `{openAiApiKey, anthropicApiKey, geminiKey}` | `UserDto` |
| `updateJiraSettings(data)` | PUT | `/auth/settings/jira` | `{jiraBaseUrl, jiraEmail, jiraApiToken}` | `UserDto` |
| `updateConfluenceSettings(data)` | PUT | `/auth/settings/confluence` | `{confluenceBaseUrl, confluenceEmail, confluenceApiToken}` | `UserDto` |
| `updateViewMode(viewMode)` | PUT | `/auth/settings/view-mode` | `{viewMode}` | `UserDto` |
| `validateGitHub(data)` | POST | `/auth/settings/github/validate` | `{pat}` | `{valid, message}` |
| `validateJira(data)` | POST | `/auth/settings/jira/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |
| `validateConfluence(data)` | POST | `/auth/settings/confluence/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |

### Runs (lines 330-403)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getRuns(params)` | GET | `/runs?status=&projectId=&scope=&page=&size=` | _(query)_ | `PagedResponse<RunSummary>` |
| `getRun(id)` | GET | `/runs/{id}` | | `RunDetail` |
| `createRun(data)` | POST | `/runs` | `{userRequest, executionMode?, skipApproval?, autoMerge?, modelName?, projectId?, repoMode?, repoUrl?}` | `{runId}` |
| `approveRun(id, approved)` | POST | `/runs/{id}/approve` | `{approved}` | |
| `clarifyRun(id, response)` | POST | `/runs/{id}/clarify` | `{response}` | |
| `mergeRun(id, approved)` | POST | `/runs/{id}/merge` | `{approved}` | |
| `cancelRun(id)` | DELETE | `/runs/{id}` | | |
| `retryTask(runId, taskId)` | POST | `/runs/{runId}/tasks/{taskId}/retry` | | |
| `retryRun(runId)` | POST | `/runs/{runId}/retry` | | `RunSummary` |
| `reopenMergeGate(runId)` | POST | `/runs/{runId}/reopen-merge` | | |
| `implementSuggestions(runId)` | POST | `/runs/{runId}/implement-suggestions` | | |
| `reworkRun(runId)` | POST | `/runs/{runId}/rework` | | |
| `pauseRun(id)` | POST | `/runs/{id}/pause` | | |
| `editAndRestartRun(runId, req)` | POST | `/runs/{runId}/edit-and-restart` | `{userRequest}` | `RunSummary` |
| `resumeRun(runId)` | POST | `/runs/{runId}/resume` | | |
| `editPlan(runId, feedback)` | POST | `/runs/{runId}/edit-plan` | `{feedback}` | `{status, version}` |
| `getPlanVersions(runId)` | GET | `/runs/{runId}/plan-versions` | | `any[]` |
| `updateRunProject(runId, projectId)` | PATCH | `/runs/{runId}/project` | `{projectId}` | `RunSummary` |

### Run Content (lines 592-648)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getTimeline(runId)` | GET | `/runs/{runId}/timeline` | | `TimelineData` |
| `getFiles(runId)` | GET | `/runs/{runId}/files` | | `string[]` |
| `downloadWorkspace(runId)` | GET | `/runs/{runId}/download` | | _Blob (zip)_ |
| `getFileContent(runId, path)` | GET | `/runs/{runId}/file?path=` | | `string` (text/plain) |
| `getMessages(runId)` | GET | `/runs/{runId}/messages` | | `MessageResponse[]` |
| `getOpenApiSpec(runId)` | GET | `/runs/{runId}/openapi.yaml` | | `string` (text/plain) |
| `getTaskGraph(runId)` | GET | `/runs/{runId}/task-graph` | | `TaskGraphNode[]` |

### Cost & Feasibility (lines 441-447)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getRunCost(runId)` | GET | `/runs/{runId}/cost` | | `{runId, costUsd}` |
| `checkFeasibility(data)` | POST | `/feasibility` | `{goal, provider?, model?, budgetUsd?}` | `FeasibilityReport` |

### Models & Pricing (lines 363, 434-437)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getAvailableModels()` | GET | `/models/available` | | `{providers[]}` |
| `getPricing()` | GET | `/pricing` | | `PricingRow[]` |
| `updatePricing(provider, model, data)` | PUT | `/pricing/{provider}/{model}` | _partial_ | `PricingRow` |

### Preview (lines 682-700)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `analyzePreview(runId)` | POST | `/runs/{runId}/preview/analyze` | | `{databaseType, requiredEnvVars[]}` |
| `getPreviewStatus(runId)` | GET | `/runs/{runId}/preview/status` | | `PreviewStatus` |
| `startPreview(runId, envVars?)` | POST | `/runs/{runId}/preview/start` | `{envVars?}` | `PreviewStatus` |
| `stopPreview(runId)` | DELETE | `/runs/{runId}/preview/stop` | | |
| `getPreviewLogs(runId, lines?, filter?)` | GET | `/runs/{runId}/preview/logs?lines=&filter=` | | `{lines[]}` |
| `probeApiDocs(runId)` | POST | `/runs/{runId}/preview/probe-api-docs` | | `{found, path, contentType}` |

### Deployment (lines 451-485)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getDeployments()` | GET | `/deployments` | | `DeploymentSummary[]` |
| `createDeployment(data)` | POST | `/deployments` | `{runId, strategy?}` | `DeploymentSummary` |
| `deleteDeployment(id)` | DELETE | `/deployments/{id}` | | |
| `rollbackDeployment(id)` | POST | `/deployments/{id}/rollback` | | `DeploymentSummary` |
| `analyzeForDeploy(runId)` | POST | `/deployments/analyze?runId=` | | `{manifest, decision, hasGcpCredentials}` |
| `confirmDeploy(data)` | POST | `/deployments/confirm` | `{runId, deploymentPath, region?, envVars?, manifestJson?, healthCheckPath?, appPort?}` | `DeploymentSummary` |
| `engineRollback(id)` | POST | `/deployments/{id}/engine-rollback` | | |
| `engineDestroy(id)` | DELETE | `/deployments/{id}/engine-destroy` | | |
| `getDeploymentDetail(id)` | GET | `/deployments/{id}` | | `DeploymentSummary` |

### GCP Credentials (lines 489-515)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getRequiredPermissions()` | GET | `/credentials/gcp/required-permissions` | | `RequiredPermission[]` |
| `storeGcpCredential(json, projectId?)` | POST | `/credentials/gcp?projectId=` | _raw JSON_ | `CredentialSummary` |
| `getGcpCredential(projectId?)` | GET | `/credentials/gcp?projectId=` | | `CredentialSummary?` |
| `listGcpCredentials()` | GET | `/credentials/gcp/all` | | `CredentialSummary[]` |
| `validateGcpCredential(projectId?)` | GET | `/credentials/gcp/validate?projectId=` | | `{valid, issues[]}` |
| `deleteGcpCredential(projectId?)` | DELETE | `/credentials/gcp?projectId=` | | `{message}` |

### Integrations (lines 520-588)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getIntegrationStatus()` | GET | `/integrations/status` | | `{enabledSources[]}` |
| `getIntegrationRuns(source?)` | GET | `/integrations/runs?source=` | | `RunSummary[]` |
| `triggerFromConfluence(data)` | POST | `/integrations/confluence/trigger` | `{pageId, goal}` | `{runId}` |
| `triggerFromJira(data)` | POST | `/integrations/jira/trigger` | `{issueKey, goal?}` | `{runId}` |
| `getSlackStatus()` | GET | `/integrations/slack/status` | | `SlackStatus` |
| `getSlackInstallUrl()` | GET | `/integrations/slack/install` | | `{installUrl}` |
| `disconnectSlack()` | DELETE | `/integrations/slack/disconnect` | | |
| `getSlackChannels()` | GET | `/integrations/slack/channels` | | `SlackChannel[]` |
| `getProjectSlackChannels(projectId)` | GET | `/integrations/slack/projects/{projectId}/channels` | | `ProjectChannelDto[]` |
| `addProjectSlackChannel(projectId, data)` | POST | `/integrations/slack/projects/{projectId}/channels` | `{channelId, channelName, verbosity}` | `ProjectChannelDto` |
| `removeProjectSlackChannel(projectId, channelId)` | DELETE | `/integrations/slack/projects/{projectId}/channels/{channelId}` | | |
| `updateChannelVerbosity(projectId, channelId, verbosity)` | PUT | `.../{channelId}/verbosity` | `{verbosity}` | |
| `sendSlackTestMessage(data)` | POST | `/integrations/slack/test` | `{channelId}` | `{ok, message}` |

### Chat & Conversation (lines 709-762)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `conversationChat(messages)` | POST | `/conversation` | `{messages[]}` | `{reply}` |
| `getRunChat(runId)` | GET | `/runs/{runId}/chat` | | `ChatMessage[]` |
| `sendRunChatMessage(runId, message)` | POST | `/runs/{runId}/chat` | `{message}` | `{reply, messageCount, maxMessages}` |
| `applyRunSuggestion(runId, data)` | POST | `/runs/{runId}/apply` | `{suggestion, source}` | `{taskCount, tasks[]}` |
| `iterateRun(runId, userRequest)` | POST | `/runs/{runId}/iterate` | `{userRequest}` | `{runId}` |

### Metrics (lines 407-430)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getMetrics(days?)` | GET | `/metrics?days=` | | `MetricsResponse` |
| `getModelComparison(days?)` | GET | `/model-comparison?days=` | | `ModelComparisonData` |
| `getUserRunsSummary(days?)` | GET | `/user-runs-summary?days=` | | `UserRunsSummaryData` |

### Validation & Test Reports (lines 1286-1401)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getValidationReport(runId)` | GET | `/runs/{runId}/report` | | `ValidationReportData?` |
| `shareValidationReport(runId)` | POST | `/runs/{runId}/report/share` | | `{shareToken, shareUrl}` |
| `getSharedReport(shareToken)` | GET | `/reports/shared/{shareToken}` | | `SharedReportData?` |
| `getTestReport(runId)` | GET | `/runs/{runId}/test-report` | | `TestReportData?` |
| `getTestPlan(runId)` | GET | `/runs/{runId}/test-plan` | | `TestPlanData?` |
| `getTestGraph(runId)` | GET | `/runs/{runId}/test-graph` | | `TestGraphData?` |

### Organization (lines 952-1020)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getMyOrg()` | GET | `/org/me` | | `OrgDto` |
| `createOrg(data)` | POST | `/org` | `{name, domains[], adminEmail}` | `OrgDto` |
| `updateOrg(data)` | PUT | `/org/me` | `{name?, domains?}` | `OrgDto` |
| `updateOrgAiKeySettings(data)` | PUT | `/org/me/settings/ai-keys` | `{anthropicApiKey, openAiApiKey, geminiApiKey}` | `OrgDto` |
| `updateOrgKeySourceSettings(data)` | PUT | `/org/me/settings/key-source` | `{keySourceMode, enabledProviders[]}` | `OrgDto` |
| `updateOrgJiraSettings(data)` | PUT | `/org/me/settings/jira` | `{baseUrl, email, apiToken}` | `OrgDto` |
| `validateOrgJira(data)` | POST | `/org/me/settings/jira/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |
| `updateOrgConfluenceSettings(data)` | PUT | `/org/me/settings/confluence` | `{baseUrl, email, apiToken}` | `OrgDto` |
| `validateOrgConfluence(data)` | POST | `/org/me/settings/confluence/validate` | `{baseUrl, email, apiToken}` | `{valid, message}` |
| `getOrgMembers()` | GET | `/org/me/members` | | `OrgMemberDto[]` |
| `removeOrgMember(userId)` | DELETE | `/org/me/members/{userId}` | | |
| `changeOrgMemberRole(userId, role)` | PUT | `/org/me/members/{userId}/role` | `{role}` | |
| `suspendOrgMember(userId, suspended)` | PUT | `/org/me/members/{userId}/suspend` | `{suspended}` | |
| `inviteToOrg(data)` | POST | `/org/invite` | `{email, orgRole?, projectIds?}` | `{message, status}` |
| `getOrgInvitations()` | GET | `/org/me/invitations` | | `OrgInvitation[]` |
| `resendInvitation(id)` | POST | `/org/me/invitations/{id}/resend` | | `{message}` |
| `acceptInvite(token)` | POST | `/org/accept-invite?token=` | | `{message}` |

### Projects (lines 1042-1063)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `getProjects()` | GET | `/projects` | | `ProjectDto[]` |
| `createProject(data)` | POST | `/projects` | `{name, description?, aiProvider?}` | `ProjectDto` |
| `updateProjectSettings(id, data)` | PATCH | `/projects/{id}/settings` | `{aiProvider}` | `ProjectDto` |
| `archiveProject(id)` | PUT | `/projects/{id}/archive` | | `ProjectDto` |
| `getProjectMembers(projectId)` | GET | `/projects/{projectId}/members` | | `ProjectMemberDto[]` |
| `addProjectMember(projectId, data)` | POST | `/projects/{projectId}/members` | `{userId, role}` | |
| `removeProjectMember(projectId, userId)` | DELETE | `/projects/{projectId}/members/{userId}` | | |

### Admin (lines 1067-1166)

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `adminListUsers()` | GET | `/admin/users` | | `UserDto[]` |
| `adminGetUser(id)` | GET | `/admin/users/{id}` | | `UserDto` |
| `adminUpdateUserStatus(id, status)` | PUT | `/admin/users/{id}/status` | `{status}` | `UserDto` |
| `adminUpdateUserTier(id, tier)` | PUT | `/admin/users/{id}/tier` | `{tier}` | `UserDto` |
| `adminListOrgs()` | GET | `/admin/orgs` | | `OrgDto[]` |
| `adminUpdateOrgTier(id, tier)` | PUT | `/admin/orgs/{id}/tier` | `{tier}` | `OrgDto` |
| `adminUpdateOrgStatus(id, status)` | PUT | `/admin/orgs/{id}/status` | `{status}` | `OrgDto` |
| `adminListEscalatedInvites()` | GET | `/admin/invitations/escalated` | | `OrgInvitation[]` |
| `adminApproveInvite(id)` | POST | `/admin/invitations/{id}/approve` | | `{message}` |
| `adminRejectInvite(id)` | POST | `/admin/invitations/{id}/reject` | | `{message}` |
| `adminGetTierConfig()` | GET | `/admin/tier-config` | | `TierConfig[]` |
| `adminUpdateTierConfig(tier, data)` | PUT | `/admin/tier-config/{tier}` | `{monthlyRunLimit}` | `TierConfig` |
| `adminUpdateOrgRunLimit(id, limit)` | PUT | `/admin/orgs/{id}/run-limit` | `{limit}` | `OrgDto` |
| `adminUpdateOrgApiKeys(id, provided)` | PUT | `/admin/orgs/{id}/api-keys` | `{provided}` | `OrgDto` |
| `adminInviteUser(data)` | POST | `/admin/invite` | `{email, firstName, lastName, tier, orgId?, newOrgName?, orgRole?}` | `{message, status}` |
| `adminGrantUserRuns(userId, data)` | POST | `/admin/users/{userId}/grant-runs` | `{quantity, expiryDays?, reason?}` | |
| `adminGrantOrgRuns(orgId, data)` | POST | `/admin/orgs/{orgId}/grant-runs` | `{quantity, expiryDays?, reason?}` | |
| `adminListTopupPackages()` | GET | `/admin/topup-packages` | | `TopupPackage[]` |
| `adminCreateTopupPackage(data)` | POST | `/admin/topup-packages` | `{name, runCount, amountInr, amountUsd?, sortOrder?}` | `TopupPackage` |
| `adminUpdateTopupPackage(id, data)` | PUT | `/admin/topup-packages/{id}` | _partial_ | `TopupPackage` |
| `adminDeleteTopupPackage(id)` | DELETE | `/admin/topup-packages/{id}` | | `{message}` |
| `adminTriggerUsageReport(period)` | POST | `/admin/usage-reports/trigger` | `{period}` | `{emailsSent, period}` |
| `adminListFeatureFlags()` | GET | `/admin/feature-flags` | | `FeatureFlagDto[]` |
| `adminUpdateFeatureFlag(key, value)` | PUT | `/admin/feature-flags/{key}` | `{value}` | `FeatureFlagDto` |
| `getAuditLogs(params, orgScoped)` | GET | `/admin/audit-logs` or `/org/me/audit-logs` | _(query)_ | `AuditLogPage` |

---

## API Functions Defined in `src/lib/billing-api.ts`

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `fetchBillingPlans(keyMode?)` | GET | `/billing/plans?keyMode=` | | `BillingPlan[]` |
| `fetchBillingStatus()` | GET | `/billing/status` | | `BillingStatus` |
| `createSubscription(planId)` | POST | `/billing/subscribe` | `{planId}` | `SubscriptionInitResult` |
| `verifyPayment(data)` | POST | `/billing/verify` | `{razorpaySubscriptionId, razorpayPaymentId, razorpaySignature}` | `BillingStatus` |
| `cancelSubscription()` | POST | `/billing/cancel` | | |
| `updateSeats(totalSeats)` | POST | `/billing/seats` | `{totalSeats}` | `BillingStatus` |
| `fetchTopupPackages()` | GET | `/billing/topup/packages` | | `TopupPackage[]` |
| `purchaseTopup(packageId)` | POST | `/billing/topup/purchase` | `{packageId}` | `TopupOrderResult` |
| `verifyTopup(data)` | POST | `/billing/topup/verify` | `{razorpayOrderId, razorpayPaymentId, razorpaySignature}` | `BillingStatus` |

---

## API Functions Defined in `src/lib/invoice-api.ts`

| Function | Method | Endpoint | Request | Response |
|----------|--------|----------|---------|----------|
| `fetchBillingProfile()` | GET | `/billing/profile` | | `BillingProfile` |
| `updateBillingProfile(data)` | PUT | `/billing/profile` | `BillingProfile` | `BillingProfile` |
| `fetchInvoices(page?, size?)` | GET | `/billing/invoices?page=&size=` | | `Page<Invoice>` |
| `fetchInvoiceDetail(id)` | GET | `/billing/invoices/{id}` | | `InvoiceDetail` |
| `fetchAdminInvoices(page?, size?)` | GET | `/admin/invoices?page=&size=` | | `Page<Invoice>` |
| `adminIssueCreditNote(id, data)` | POST | `/admin/invoices/{id}/credit-note` | `{amountPaise, reason}` | `Invoice` |
| `adminVoidInvoice(id)` | POST | `/admin/invoices/{id}/void` | | `Invoice` |
| `adminResendInvoice(id)` | POST | `/admin/invoices/{id}/send` | | |
| `fetchSellerConfig()` | GET | `/admin/seller-config` | | `SellerConfig` |
| `updateSellerConfig(data)` | PUT | `/admin/seller-config` | `SellerConfig` | `SellerConfig` |
| `fetchRevenueMetrics(from, to)` | GET | `/accounting/revenue?from=&to=` | | `RevenueMetrics` |
| `fetchGstSummary(fy, quarter)` | GET | `/accounting/gst-summary?fy=&quarter=` | | `GstSummary` |
| `fetchTrialBalance(from, to)` | GET | `/accounting/trial-balance?from=&to=` | | `TrialBalanceEntry[]` |
| `downloadInvoicePdf(id, admin?)` | GET | `/billing/invoices/{id}/pdf` or `/admin/invoices/{id}/pdf` | | _Blob (PDF)_ |
| `gstr1ExportUrl(fy, quarter)` | _builds URL_ | `/accounting/gstr1-export?fy=&quarter=` | | _URL string_ |

---

## Component-Level API Usage

### Stores (Zustand)

#### `src/stores/useAuthStore.ts`
| Action | Calls |
|--------|-------|
| `login()` | `authLogin()` → stores `accessToken` in localStorage |
| `register()` | `authRegister()` |
| `refreshAccessToken()` | `authRefresh()` → updates stored token |
| `loadMe()` | `authMe()` → populates `user` state |
| `logout()` | `authLogout()` → clears localStorage |

#### `src/stores/useRunStore.ts`
| Action | Calls |
|--------|-------|
| `fetchRuns(params)` | `getRuns(status, projectId, scope, page, size)` |
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

### Pages

#### `src/pages/SettingsPage.tsx`
- `validateGitHub()` → `validateGitHub()`
- `updateGithubSettings()` — save or clear
- `updateAiKeySettings()` — save or clear
- `updateOrgAiKeySettings()` — save or clear
- `updateOrgKeySourceSettings()` — save mode & providers
- `getMetrics()` — system health display

#### `src/pages/BillingPage.tsx`
- `fetchBillingStatus()` — on mount
- `fetchTopupPackages()` — on mount
- `cancelSubscription()` — user action
- `purchaseTopup()` → Razorpay checkout → `verifyTopup()`

#### `src/pages/IntegrationsPage.tsx`
- Jira: `validateOrgJira()`, `updateOrgJiraSettings()` (save/clear)
- Confluence: `validateOrgConfluence()`, `updateOrgConfluenceSettings()` (save/clear)
- Slack: `getSlackStatus()`, `getSlackInstallUrl()`, `sendSlackTestMessage()`
- Status: `getIntegrationStatus()`, `getIntegrationRuns()`

#### `src/pages/DeploymentsPage.tsx`
- `getDeployments()` — on mount

#### `src/pages/admin/AdminPage.tsx`
- Loads: `adminListUsers()`, `adminListOrgs()`, `adminGetTierConfig()`, `adminListEscalatedInvites()`, `adminListTopupPackages()`
- Actions: `adminUpdateUserStatus()`, `adminUpdateUserTier()`, `adminUpdateOrgStatus()`, `adminUpdateOrgTier()`, `adminUpdateOrgRunLimit()`, `adminUpdateOrgApiKeys()`, `adminUpdateTierConfig()`, `adminApproveInvite()`, `adminRejectInvite()`, `adminTriggerUsageReport()`, `adminCreateTopupPackage()`, `adminUpdateTopupPackage()`, `adminDeleteTopupPackage()`

#### `src/pages/org/OrgSettingsPage.tsx`
- `createOrg()`, `getMyOrg()`, `getOrgMembers()`, `getOrgInvitations()`, `resendInvitation()`

#### `src/pages/org/OrgProjectsPage.tsx`
- `getProjects()`, `createProject()`, `getProjectMembers()`, `updateProjectSettings()`, `getOrgMembers()`

#### `src/pages/SharedReportPage.tsx`
- `getSharedReport(shareToken)` — public, no auth

#### `src/pages/auth/VerifyEmailPage.tsx`
- `authVerifyEmail(token)`

#### `src/pages/auth/ResetPasswordPage.tsx`
- `authResetPassword(token, newPassword)`

#### `src/pages/auth/ForgotPasswordPage.tsx`
- `authForgotPassword(email)`

### Components

#### `src/components/runs/NewRunModal.tsx`
- `conversationChat(messages)` — pre-run clarification
- `createRun(data)` — submit run
- `getAvailableModels()` — populate model selector

#### `src/components/runs/RunConversation.tsx`
- `getRunChat(runId)` — load history on mount
- `sendRunChatMessage(runId, message)` — send message

#### `src/components/runs/PreviewTab.tsx`
- `getPreviewStatus(runId)` — poll status
- `startPreview(runId, envVars)` — start container
- `stopPreview(runId)` — stop container
- `getPreviewLogs(runId, lines, filter)` — poll logs every 5s
- `analyzePreview(runId)` — check DB/env requirements

#### `src/components/runs/ValidationReportPanel.tsx`
- `getValidationReport(runId)` — load report
- `shareValidationReport(runId)` — generate share link

#### `src/components/runs/RunList.tsx`
- `getProjects()` — populate project filter

#### `src/components/deployments/DeployWizardModal.tsx`
- `getGcpCredential()`, `getRequiredPermissions()` — GCP setup
- `validateGcpCredential()` — validate
- `analyzeForDeploy(runId)` — analyze readiness
- `confirmDeploy(data)` — deploy
- `getDeploymentDetail(id)` — poll status

#### `src/components/auth/ProjectSelector.tsx`
- `getProjects()` — populate dropdown
- `createProject(data)` — inline create

#### `src/components/auth/ChangePasswordModal.tsx`
- `authChangePassword(data)`

#### `src/components/layout/Sidebar.tsx`
- `getProjects()` — populate project navigation

---

## SSE (Server-Sent Events)

#### `src/hooks/useSse.ts`
- **Endpoint:** `/api/runs/{runId}/events`
- **Auth:** `?token={accessToken}` query parameter
- **Events:** `PLAN_READY`, `TASK_STARTED`, `TASK_COMPLETED`, `TASK_FAILED`, `TOOL_CALL`, `COMPILE_STARTED`, `COMPILE_DONE`, `MERGE_STARTED`, `MERGE_DONE`, `REVIEW_STARTED`, `REVIEW_DONE`, `RUN_COMPLETE`, `RUN_FAILED`, `PREVIEW_STATUS`, `TEST_STATUS`, `VALIDATION_REPORT_READY`, `CLARIFICATION_NEEDED`

---

## Summary Statistics

| Metric | Count |
|--------|-------|
| API functions in `api.ts` | ~120 |
| API functions in `billing-api.ts` | 9 |
| API functions in `invoice-api.ts` | 15 |
| **Total API functions** | **~144** |
| Unique backend endpoints used | ~110 |
| Files making API calls | ~25 |
| SSE event types | 17 |
