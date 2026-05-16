# Frontend API usage

> Authoritative map of which frontend file consumes which backend endpoint. Source of truth: [`frontend/src/lib/api.ts`](../frontend/src/lib/api.ts) — the **single** API client module for the entire React app. Every backend call funnels through it.

This document is generated from the code (not from product docs). If `lib/api.ts` and this doc disagree, the code wins.

## 1. How the client works

### 1.1 Module layout

[`frontend/src/lib/api.ts`](../frontend/src/lib/api.ts) (3620 lines) exports:

- A typed `req<T>` / `reqText` core that wraps `fetch`.
- A typed `ApiError extends Error` (carries `status: number`).
- One exported function per backend endpoint — always typed via TypeScript generics.
- DTO interfaces inline with the endpoints that produce them.

The base URL is the constant `BASE = '/api'`. In dev, Vite proxies `/api` to `http://localhost:8080` (see `frontend/vite.config.ts`); in prod, the Spring Boot JAR serves the built SPA from `src/main/resources/static/` so the call is same-origin.

### 1.2 Bearer-token injection

`authHeader()` (lines 6–9 of `api.ts`):

```ts
function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? {Authorization: `Bearer ${token}`} : {};
}
```

Every `req()` call composes `Authorization` automatically from `localStorage.access_token`. The token is written by `authLogin` / `authRefresh` / `authVerifyEmail` call sites in `stores/useAuthStore.ts` and read on every subsequent request without explicit plumbing.

Refresh tokens flow via a `credentials: 'include'` cookie — `req()` passes `credentials: 'include'` so the `refresh_token` HttpOnly cookie rides along on every call (POST `/auth/refresh` uses it to mint a new access token).

### 1.3 Error envelope

Every backend 4xx lands on `com.navneet.executespec.api.advice.ValidationAdvice` (see [`src/main/java/com/navneet/executespec/api/advice/ValidationAdvice.java`](../src/main/java/com/navneet/executespec/api/advice/ValidationAdvice.java)). The canonical JSON envelope is:

```json
{
  "timestamp": "2026-05-16T10:42:08.123Z",
  "status": 400,
  "error": "validation_failed",
  "message": "2 validation error(s)",
  "fields": {"email": "must be a valid email", "password": "must not be blank"}
}
```

`fields` is included only on bean-validation failures. `globalErrors` may appear for class-level constraints.

`ValidationAdvice` is annotated `@Order(Ordered.HIGHEST_PRECEDENCE)` so it runs before any catch-all `@ExceptionHandler(Throwable.class)`. It maps:

| Exception                                  | HTTP status | `error` slug             |
| ------------------------------------------ | ----------- | ------------------------ |
| `MethodArgumentNotValidException`          | 400         | `validation_failed`      |
| `ConstraintViolationException`             | 400         | `validation_failed`      |
| `HttpMessageNotReadableException`          | 400         | `bad_request`            |
| `MethodArgumentTypeMismatchException`      | 400         | `bad_request`            |
| `MissingServletRequestParameterException`  | 400         | `bad_request`            |
| `IllegalArgumentException`                 | 400         | `bad_request`            |
| `ResponseStatusException`                  | (as raised) | derived from status code |
| `AccessDeniedException`                    | 403         | `forbidden`              |
| `HttpRequestMethodNotSupportedException`   | 405         | `method_not_allowed`     |
| `BillingException`                         | 402         | `payment_required`       |

Every error body is forced to `Content-Type: application/json` (see `jsonError`) so content-type negotiation never fails over to 500.

### 1.4 `parseErrorMessage`

Lines 50–72 of `api.ts`. Resolution order:

1. Parse the body as JSON; if `message` is a non-empty string, use it.
2. Otherwise, if `error` is a non-empty string AND not the bare Spring default phrases `"Payment Required"` or `"Forbidden"`, use it.
3. Otherwise, fall back to `STATUS_MESSAGES[status]` (a human-readable dictionary in `api.ts`, lines 11–25).
4. Otherwise, return `Request failed (${status}). Please try again.`

The friendly defaults cover 400, 401, 402, 403, 404, 409, 410, 422, 429, 500, 502, 503.

### 1.5 Network errors

`fetch()` itself can reject with `TypeError: Failed to fetch` (DNS, backend crash, network drop). `req()` wraps the call in a `try` and rethrows as `new ApiError(0, ...)` so call sites can branch on `status === 0` as a sentinel for "couldn't reach server". The helper `isRetryableError(e)` (lines 114–118) returns true for `status === 0` or `status >= 500`.

### 1.6 Empty bodies

`req()` returns `undefined as T` for 204 No Content responses and for 200 OK with empty body (Spring sometimes returns empty `200` from approve / merge endpoints).

### 1.7 Binary download helpers

`downloadWorkspace`, `downloadValidationReportPdf`, `adminExportPaymentAuditCsv`, `downloadProjectDoc` use raw `fetch()` (not `req()`) because they consume blobs, not JSON. They still inject `authHeader()` and surface `ApiError` on non-2xx.

### 1.8 Plain-text helpers

`reqText(url)` (lines 121–131) is for `text/plain` endpoints — file content (`/runs/{id}/file`), OpenAPI YAML (`/runs/{id}/openapi.yaml`). Returns a `string`, not a parsed JSON object.

### 1.9 Per-module breakdown

Other files under `frontend/src/lib/`:

| File                 | Purpose                                                                                            |
| -------------------- | -------------------------------------------------------------------------------------------------- |
| `api.ts`             | The single backend client. **All HTTP calls live here.**                                           |
| `analytics.ts`       | Thin PostHog wrapper. Not a backend client.                                                        |
| `billing-api.ts`     | Billing-specific helpers built on top of `api.ts` (no direct fetch).                               |
| `execution-api.ts`   | Execution-specific helpers (no direct fetch).                                                      |
| `invoice-api.ts`     | Invoice-specific helpers.                                                                          |
| `verdict.ts`         | Pure-function verdict derivation. No I/O.                                                          |
| `glossary.ts`        | Static dictionary. No I/O.                                                                         |
| `telemetry.ts`       | OTel browser SDK init.                                                                             |
| `monaco*.ts`         | Monaco editor setup (lazy module).                                                                 |
| `techDetect.ts`      | Client-side tech-stack heuristics.                                                                 |
| `constants.ts`       | Compile-time constants.                                                                            |
| `utils.ts`           | Misc utilities (className helpers etc.).                                                           |

## 2. ArchUnit contract test

The Java test [`src/test/java/com/navneet/executespec/arch/ApiContractTest.java`](../src/test/java/com/navneet/executespec/arch/ApiContractTest.java) asserts every URL referenced from `frontend/src/lib/api.ts` resolves to a registered backend `@*Mapping`. The test:

- Scans the classpath via ArchUnit's `ClassFileImporter` for every `@Controller` / `@RestController` class.
- Walks `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` on both classes and methods.
- Normalises path placeholders (`{id}`, `${runId}`, `{x}`) so they compare structurally.
- Extracts URLs from `api.ts` via a regex that catches `req(...)`, `reqText(...)`, and `fetch(...)` first-string-argument literals.
- Fails the build (Maven `verify` phase) if any URL in `api.ts` has no matching backend route.

The `ALLOWLIST` set is currently empty — every URL in `api.ts` resolves to a real backend mapping. Drift is caught at CI time, not at runtime.

## 3. Endpoint catalogue

The tables below group every exported function by domain. Columns:

- **Function** — symbol name exported from `lib/api.ts`.
- **Method** — HTTP verb.
- **Path** — relative to `/api`.
- **Consumed by** — TSX/TS files that import the symbol (sampled via `grep`; not exhaustive when used by indirect re-exports, but every direct site is listed).

### 3.1 Runs — lifecycle

| Function           | Method | Path                              | Consumed by                                                                                                                                            |
| ------------------ | ------ | --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `getRuns`          | GET    | `/runs`                           | `stores/useRunStore.ts`, `pages/ExecSummaryPage.tsx`, `signal/pages/AllRunsPage.tsx`, `signal/pages/MiscPages.tsx`, `signal/pages/HomePage.tsx`         |
| `getRun`           | GET    | `/runs/{id}`                      | `stores/useRunStore.ts`, `pages/project/ProjectDashboardPage.tsx`, `signal/pages/ResultPage.tsx`, `signal/pages/LivePage.tsx`, `signal/pages/PlanPage.tsx`, `components/project/ProjectTaskList.tsx` |
| `createRun`        | POST   | `/runs`                           | `stores/useRunStore.ts`, `signal/pages/ComposePage.tsx`, `signal/pages/project/ProjectComposePage.tsx`                                                  |
| `forkRun`          | POST   | `/runs/{runId}/fork`              | `signal/pages/ResultPage.tsx`                                                                                                                           |
| `approveRun`       | POST   | `/runs/{id}/approve`              | `stores/useRunStore.ts`, `signal/pages/PlanPage.tsx`                                                                                                    |
| `cancelRun`        | DELETE | `/runs/{id}`                      | `signal/pages/LivePage.tsx`, `signal/pages/PlanPage.tsx`, `signal/pages/ResultPage.tsx`                                                                 |
| `pauseRun`         | POST   | `/runs/{runId}/pause`             | `signal/pages/LivePage.tsx` (behind `v2.pause.enabled` flag)                                                                                            |
| `resumeRun`        | POST   | `/runs/{runId}/resume`            | `signal/pages/LivePage.tsx`                                                                                                                             |
| `archiveRun`       | POST   | `/runs/{runId}/archive`           | `signal/pages/AllRunsPage.tsx`                                                                                                                          |
| `unarchiveRun`     | POST   | `/runs/{runId}/unarchive`         | `signal/pages/AllRunsPage.tsx`                                                                                                                          |
| `softDeleteRun`    | POST   | `/runs/{runId}/soft-delete`       | `signal/pages/AllRunsPage.tsx` (V191 delete with undo)                                                                                                  |
| `restoreRun`       | POST   | `/runs/{runId}/restore`           | `signal/pages/AllRunsPage.tsx`                                                                                                                          |
| `retryTask`        | POST   | `/runs/{runId}/tasks/{taskId}/retry` | `components/project/InlineRunDetail.tsx`, `signal/pages/ResultPage.tsx`                                                                              |
| `getSseToken`      | GET    | `/runs/{runId}/sse-token`         | `signal/hooks/useSse.ts` (then opens an `EventSource` directly to `/runs/{id}/events?token=...`)                                                        |
| `getLiveCost`      | GET    | `/runs/{runId}/cost`              | `signal/pages/LivePage.tsx`, `signal/components/CostProgressBar.tsx`                                                                                    |
| `updateRunProject` | PATCH  | `/runs/{runId}/project`           | `signal/pages/AllRunsPage.tsx`                                                                                                                          |

### 3.2 Runs — plan editing and graph

| Function          | Method | Path                          | Consumed by                                                                          |
| ----------------- | ------ | ----------------------------- | ------------------------------------------------------------------------------------ |
| `editRunPlan`     | POST   | `/runs/{runId}/edit-plan`     | `signal/pages/PlanPage.tsx`                                                          |
| `getPlanVersions` | GET    | `/runs/{runId}/plan-versions` | `signal/pages/PlanPage.tsx`                                                          |
| `getTaskGraph`    | GET    | `/runs/{runId}/task-graph`    | `signal/pages/ResultPage.tsx` (when Code tab is opened)                              |

### 3.3 Runs — files, diff, push

| Function             | Method | Path                                       | Consumed by                                                                                                  |
| -------------------- | ------ | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------ |
| `getFiles`           | GET    | `/runs/{runId}/files`                      | (legacy; superseded by `getFilesWithStatus`)                                                                  |
| `getFilesWithStatus` | GET    | `/runs/{runId}/files/status`               | `signal/pages/ResultPage.tsx`, `components/runs/RunDetailTabs.tsx`                                            |
| `getFile`            | GET    | `/runs/{runId}/file?path=...`              | `signal/components/CodeView.tsx`, `signal/pages/ResultPage.tsx`                                               |
| `getFileDiff`        | GET    | `/runs/{runId}/file/diff?path=...`         | `signal/components/CodeView.tsx`                                                                              |
| `downloadWorkspace`  | GET    | `/runs/{runId}/download`                   | `signal/pages/ResultPage.tsx`, `components/project/InlineRunDetail.tsx`                                       |
| `pushToGitHub`       | POST   | `/runs/{runId}/push-github`                | (legacy single-repo path)                                                                                     |
| `pushToScm`          | POST   | `/runs/{runId}/push-scm`                   | `signal/pages/ResultPage.tsx`, `components/project/InlineRunDetail.tsx`                                       |
| `syncRun`            | POST   | `/runs/{runId}/sync`                       | `signal/pages/ResultPage.tsx`                                                                                 |
| `syncRunToOriginal`  | POST   | `/runs/{runId}/sync/original`              | `signal/pages/ResultPage.tsx`                                                                                 |
| `getRunPrs`          | GET    | `/runs/{runId}/prs`                        | `signal/pages/ResultPage.tsx`, `components/project/InlineRunDetail.tsx`                                       |
| `getMessages`        | GET    | `/runs/{runId}/messages`                   | `signal/pages/LivePage.tsx`                                                                                   |
| `getTimeline`        | GET    | `/runs/{runId}/timeline`                   | `signal/pages/ResultPage.tsx`                                                                                 |
| `getOpenApiSpec`     | GET    | `/runs/{runId}/openapi.yaml`               | `signal/pages/ResultPage.tsx` (API tab)                                                                       |
| `probeApiDocs`       | GET    | `/runs/{runId}/preview/probe-api-docs`     | `signal/pages/ResultPage.tsx`                                                                                 |

### 3.4 Runs — preview lifecycle

| Function              | Method | Path                                | Consumed by                                                                          |
| --------------------- | ------ | ----------------------------------- | ------------------------------------------------------------------------------------ |
| `getPreviewStatus`    | GET    | `/runs/{runId}/preview/status`      | `signal/pages/ResultPage.tsx`                                                        |
| `analyzePreview`      | GET    | `/runs/{runId}/preview/analyze`     | `signal/pages/ResultPage.tsx`                                                        |
| `startPreview`        | POST   | `/runs/{runId}/preview/start`       | `signal/pages/ResultPage.tsx`                                                        |
| `stopPreview`         | DELETE | `/runs/{runId}/preview/stop`        | `signal/pages/ResultPage.tsx`                                                        |
| `rebuildPreview`      | POST   | `/runs/{runId}/preview/rebuild`     | `signal/pages/ResultPage.tsx`                                                        |
| `getPreviewLogs`      | GET    | `/runs/{runId}/preview/logs`        | `signal/pages/ResultPage.tsx`                                                        |
| `execPreviewCommand`  | POST   | `/runs/{runId}/preview/exec`        | `signal/pages/ResultPage.tsx`                                                        |

### 3.5 Runs — chat (post-run iteration)

| Function               | Method | Path                                            | Consumed by                                                            |
| ---------------------- | ------ | ----------------------------------------------- | ---------------------------------------------------------------------- |
| `getRunChat`           | GET    | `/runs/{runId}/chat`                            | `signal/pages/ResultPage.tsx`                                          |
| `sendRunChatMessage`   | POST   | `/runs/{runId}/chat`                            | `signal/pages/ResultPage.tsx`                                          |
| `iterateRun`           | POST   | `/runs/{runId}/iterate`                         | `signal/pages/ResultPage.tsx`                                          |
| `applyToRun`           | POST   | `/runs/{runId}/apply`                           | `signal/pages/ResultPage.tsx`                                          |
| `conversationChat`     | POST   | `/conversation`                                 | `signal/components/SpecChat.tsx`                                       |

### 3.6 Runs — PR comment inbox

Incoming review comments on PRs ExecuteSpec opened. The backend webhook ingests these into `pr_comment_inbox`; the frontend polls.

| Function                  | Method | Path                                                  | Consumed by                                       |
| ------------------------- | ------ | ----------------------------------------------------- | ------------------------------------------------- |
| `getPrComments`           | GET    | `/runs/{runId}/pr-comments`                           | `signal/pages/ResultPage.tsx`                     |
| `getPrCommentPendingCount`| GET    | `/runs/{runId}/pr-comments/pending-count`             | `signal/pages/ResultPage.tsx`                     |
| `dismissPrComment`        | POST   | `/runs/{runId}/pr-comments/{commentId}/dismiss`       | `signal/pages/ResultPage.tsx`                     |
| `applyPrComment`          | POST   | `/runs/{runId}/pr-comments/{commentId}/apply`         | `signal/pages/ResultPage.tsx`                     |

### 3.7 Validation report

| Function                       | Method | Path                                  | Consumed by                                                                                                        |
| ------------------------------ | ------ | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `getValidationReport`          | GET    | `/runs/{runId}/report`                | `signal/pages/ResultPage.tsx`, `components/runs/ValidationReportPanel.tsx`                                         |
| `retryValidationReport`        | POST   | `/runs/{runId}/retry-validation`      | `signal/pages/ResultPage.tsx`                                                                                       |
| `shareValidationReport`        | POST   | `/runs/{runId}/report/share`          | `signal/pages/ResultPage.tsx`                                                                                       |
| `getSharedReport`              | GET    | `/reports/shared/{shareToken}`        | `signal/pages/MiscPages.tsx` (`SharedPage`)                                                                        |
| `downloadValidationReportPdf`  | GET    | `/runs/{runId}/report/pdf`            | `signal/pages/ResultPage.tsx`                                                                                       |

### 3.8 Test generation pipeline

| Function           | Method | Path                                          | Consumed by                       |
| ------------------ | ------ | --------------------------------------------- | --------------------------------- |
| `runTests`         | POST   | `/runs/{runId}/run-tests`                     | `signal/pages/ResultPage.tsx`     |
| `getTestReport`    | GET    | `/runs/{runId}/test-report`                   | `signal/pages/ResultPage.tsx`     |
| `getTestPlan`      | GET    | `/runs/{runId}/test-plan`                     | `signal/pages/ResultPage.tsx`     |
| `getTestGraph`     | GET    | `/runs/{runId}/test-graph`                    | `signal/pages/ResultPage.tsx`     |
| `markTestInvalid`  | PUT    | `/test-results/{testResultId}/mark-invalid`   | `signal/pages/ResultPage.tsx`     |
| `applyTestFixes`   | POST   | `/runs/{runId}/apply-test-fixes`              | `signal/pages/ResultPage.tsx`     |

### 3.9 Metrics, models, pricing

| Function                | Method | Path                          | Consumed by                                                                       |
| ----------------------- | ------ | ----------------------------- | --------------------------------------------------------------------------------- |
| `getMetrics`            | GET    | `/metrics`                    | `stores/useMetricsStore.ts`, `signal/pages/MiscPages.tsx` (`InsightsPage`)        |
| `getModelComparison`    | GET    | `/model-comparison`           | `signal/pages/MiscPages.tsx`                                                      |
| `getUserRunsSummary`    | GET    | `/user-runs-summary`          | `signal/pages/MiscPages.tsx`                                                      |
| `getAvailableModels`    | GET    | `/models/available`           | `signal/pages/ComposePage.tsx`, `signal/pages/project/ProjectComposePage.tsx`     |
| `getPricing`            | GET    | `/pricing`                    | `signal/pages/account/PlansPage.tsx`                                              |
| `getAllPricing`         | GET    | `/pricing`                    | admin model-rates view (duplicate of `getPricing`)                                |
| `updatePricing`         | PUT    | `/pricing/{provider}/{model}` | admin model-rates view                                                            |
| `updateModelPricing`    | PUT    | `/pricing/{provider}/{model}` | admin model-rates view (alias of `updatePricing`)                                 |

### 3.10 Deployments

| Function                  | Method | Path                                  | Consumed by                                            |
| ------------------------- | ------ | ------------------------------------- | ------------------------------------------------------ |
| `getDeployments`          | GET    | `/deployments`                        | `signal/pages/ExtraPages.tsx` (`DeploymentsPage`)      |
| `createDeployment`        | POST   | `/deployments`                        | `signal/pages/ExtraPages.tsx`                          |
| `getDeploymentDetail`     | GET    | `/deployments/{id}`                   | `signal/pages/ExtraPages.tsx`                          |
| `deleteDeployment`        | DELETE | `/deployments/{id}`                   | `signal/pages/ExtraPages.tsx`                          |
| `rollbackDeployment`      | POST   | `/deployments/{id}/rollback`          | `signal/pages/ExtraPages.tsx`                          |
| `analyzeForDeploy`        | POST   | `/deployments/analyze?runId=...`      | `signal/pages/ExtraPages.tsx`                          |
| `confirmDeploy`           | POST   | `/deployments/confirm`                | `signal/pages/ExtraPages.tsx`                          |
| `engineRollback`          | POST   | `/deployments/{id}/engine-rollback`   | `signal/pages/ExtraPages.tsx`                          |
| `engineDestroy`           | DELETE | `/deployments/{id}/engine-destroy`    | `signal/pages/ExtraPages.tsx`                          |

### 3.11 GCP credentials (for Deploy Engine)

| Function                  | Method | Path                                       | Consumed by                                  |
| ------------------------- | ------ | ------------------------------------------ | -------------------------------------------- |
| `getRequiredPermissions`  | GET    | `/credentials/gcp/required-permissions`    | `signal/pages/ExtraPages.tsx`                |
| `getGcpCredential`        | GET    | `/credentials/gcp`                         | `signal/pages/ExtraPages.tsx`                |
| `listGcpCredentials`      | GET    | `/credentials/gcp/all`                     | `signal/pages/ExtraPages.tsx`                |
| `storeGcpCredential`      | POST   | `/credentials/gcp`                         | `signal/pages/ExtraPages.tsx`                |
| `validateGcpCredential`   | GET    | `/credentials/gcp/validate`                | `signal/pages/ExtraPages.tsx`                |
| `deleteGcpCredential`     | DELETE | `/credentials/gcp`                         | `signal/pages/ExtraPages.tsx`                |

### 3.12 Auth

| Function                  | Method | Path                                  | Consumed by                                                                                          |
| ------------------------- | ------ | ------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `authRegister`            | POST   | `/auth/register`                      | `stores/useAuthStore.ts`, `signal/pages/AuthPages.tsx` (`SignupPage`)                                |
| `authLogin`               | POST   | `/auth/login`                         | `stores/useAuthStore.ts`, `signal/pages/AuthPages.tsx` (`LoginPage`)                                 |
| `authRefresh`             | POST   | `/auth/refresh`                       | `stores/useAuthStore.ts` (called on `authMe` 401 fallback)                                           |
| `authLogout`              | DELETE | `/auth/logout`                        | `stores/useAuthStore.ts`                                                                              |
| `authMe`                  | GET    | `/auth/me`                            | `stores/useAuthStore.ts` (on every page load)                                                         |
| `authVerifyEmail`         | GET    | `/auth/verify-email?token=...`        | `signal/pages/AuthPages.tsx` (`VerifyPage`)                                                          |
| `authResendVerification`  | POST   | `/auth/resend-verification`           | `signal/pages/AuthPages.tsx`                                                                          |
| `authForgotPassword`      | POST   | `/auth/forgot-password`               | `signal/pages/ExtraPages.tsx` (`ForgotPasswordPage`)                                                 |
| `authResetPassword`       | POST   | `/auth/reset-password`                | `signal/pages/ExtraPages.tsx` (`ResetPasswordPage`)                                                  |
| `authChangePassword`      | POST   | `/auth/change-password`               | `components/auth/ChangePasswordModal.tsx`                                                            |
| `getMyPermissions`        | GET    | `/auth/me/permissions`                | `stores/useAuthStore.ts`, `hooks/useTierPermissions.ts`                                              |

### 3.13 Per-user settings

| Function                    | Method | Path                                        | Consumed by                                                                        |
| --------------------------- | ------ | ------------------------------------------- | ---------------------------------------------------------------------------------- |
| `updateGithubSettings`      | PUT    | `/auth/settings/github`                     | `signal/pages/account/MiscAccountPages.tsx` (`IntegrationsPage`)                   |
| `updateAiKeySettings`       | PUT    | `/auth/settings/ai-keys`                    | `signal/pages/account/MiscAccountPages.tsx` (`ApiKeysPage`)                        |
| `validateAiKey`             | POST   | `/auth/settings/ai-keys/validate`           | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `validateGitHub`            | POST   | `/auth/settings/github/validate`            | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `updateJiraSettings`        | PUT    | `/auth/settings/jira`                       | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `validateJira`              | POST   | `/auth/settings/jira/validate`              | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `updateConfluenceSettings`  | PUT    | `/auth/settings/confluence`                 | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `validateConfluence`        | POST   | `/auth/settings/confluence/validate`        | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `updateBitbucketSettings`   | PUT    | `/auth/settings/bitbucket`                  | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `validateBitbucket`         | POST   | `/auth/settings/bitbucket/validate`         | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `updateViewMode`            | PUT    | `/auth/settings/view-mode`                  | `signal/pages/account/AccountPage.tsx`                                             |
| `getGitHubAppStatus`        | GET    | `/auth/github-app/status`                   | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `getGitHubAppInstallUrl`    | GET    | `/auth/github-app/install-url`              | `signal/pages/account/MiscAccountPages.tsx`                                        |
| `uninstallGitHubApp`        | DELETE | `/auth/github-app/install`                  | `signal/pages/account/MiscAccountPages.tsx`                                        |

### 3.14 Onboarding (sandbox + sample specs)

| Function                  | Method | Path                                  | Consumed by                                            |
| ------------------------- | ------ | ------------------------------------- | ------------------------------------------------------ |
| `listOnboardingSamples`   | GET    | `/onboarding/sample-specs`            | `signal/pages/AuthPages.tsx` (`WelcomePage`)           |
| `pickOnboardingSample`    | POST   | `/onboarding/pick-sample`             | `signal/pages/AuthPages.tsx`                           |
| `getSandboxStatus`        | GET    | `/onboarding/sandbox-status`          | `signal/pages/AuthPages.tsx`                           |

### 3.15 Organization

| Function                       | Method | Path                                          | Consumed by                                                                          |
| ------------------------------ | ------ | --------------------------------------------- | ------------------------------------------------------------------------------------ |
| `getMyOrg`                     | GET    | `/org/me`                                     | `stores/useAuthStore.ts`, `signal/pages/account/MiscAccountPages.tsx`                |
| `createOrg`                    | POST   | `/org`                                        | `signal/pages/AuthPages.tsx`                                                          |
| `updateOrg`                    | PUT    | `/org/me`                                     | `signal/pages/LegalPages.tsx` (`OrgSettingsPage`)                                     |
| `adjustOrgSeats`               | POST   | `/org/me/seats/adjust`                        | `signal/pages/LegalPages.tsx`                                                         |
| `updateOrgAiKeySettings`       | PUT    | `/org/me/settings/ai-keys`                    | `signal/pages/LegalPages.tsx`                                                         |
| `updateOrgKeySourceSettings`   | PUT    | `/org/me/settings/key-source`                 | `signal/pages/LegalPages.tsx`                                                         |
| `updateExternalSharing`        | PUT    | `/org/me/settings/external-sharing`           | `signal/pages/LegalPages.tsx` (V198, ORG_ADMIN toggle)                                |
| `updateOrgJiraSettings`        | PUT    | `/org/me/settings/jira`                       | `signal/pages/LegalPages.tsx`                                                         |
| `validateOrgJira`              | POST   | `/org/me/settings/jira/validate`              | `signal/pages/LegalPages.tsx`                                                         |
| `updateOrgConfluenceSettings`  | PUT    | `/org/me/settings/confluence`                 | `signal/pages/LegalPages.tsx`                                                         |
| `validateOrgConfluence`        | POST   | `/org/me/settings/confluence/validate`        | `signal/pages/LegalPages.tsx`                                                         |
| `getOrgMembers`                | GET    | `/org/me/members`                             | `signal/pages/account/MiscAccountPages.tsx` (`OrgMembersPage`)                        |
| `removeMember`                 | DELETE | `/org/me/members/{userId}`                    | `signal/pages/account/MiscAccountPages.tsx`                                            |
| `changeMemberRole`             | PUT    | `/org/me/members/{userId}/role`               | `signal/pages/account/MiscAccountPages.tsx`                                            |
| `orgSuspendMember`             | PUT    | `/org/me/members/{userId}/suspend`            | `signal/pages/account/MiscAccountPages.tsx`                                            |
| `inviteMember`                 | POST   | `/org/invite`                                 | `signal/pages/account/MiscAccountPages.tsx`                                            |
| `getOrgInvitations`            | GET    | `/org/me/invitations`                         | `signal/pages/account/MiscAccountPages.tsx`                                            |
| `resendInvitation`             | POST   | `/org/me/invitations/{id}/resend`             | `signal/pages/account/MiscAccountPages.tsx`                                            |
| `acceptInvite`                 | POST   | `/org/accept-invite?token=...`                | `signal/pages/ExtraPages.tsx` (`AcceptInvitePage`)                                     |

### 3.16 Projects — lifecycle

| Function                  | Method | Path                                  | Consumed by                                                                                                      |
| ------------------------- | ------ | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `getProjects`             | GET    | `/projects`                           | `stores/useProjectStore.ts`, `signal/pages/ProjectsPage.tsx`, `signal/pages/HomePage.tsx`, `signal/pages/MiscPages.tsx`, `signal/hooks/useProjectMeta.ts`, `pages/ExecSummaryPage.tsx`, `pages/org/OrgProjectsPage.tsx` |
| `getProject`              | GET    | `/projects/{id}`                      | `signal/pages/project/ProjectTabs.tsx`, `signal/pages/project/ProjectSettingsPage.tsx`                            |
| `createProject`           | POST   | `/projects`                           | `signal/pages/ProjectsPage.tsx`                                                                                   |
| `updateProjectSettings`   | PATCH  | `/projects/{id}/settings`             | `signal/pages/project/ProjectSettingsPage.tsx`                                                                    |
| `archiveProject`          | PUT    | `/projects/{id}/archive`              | `signal/pages/project/ProjectSettingsPage.tsx`                                                                    |
| `unarchiveProject`        | PUT    | `/projects/{id}/unarchive`            | `signal/pages/project/ProjectSettingsPage.tsx`                                                                    |
| `deleteProject`           | DELETE | `/projects/{id}`                      | `signal/pages/project/ProjectSettingsPage.tsx`                                                                    |
| `restoreProject`          | POST   | `/projects/{id}/restore`              | `signal/pages/project/ProjectSettingsPage.tsx`                                                                    |
| `getProjectMembers`       | GET    | `/projects/{id}/members`              | `signal/pages/project/ProjectMembersPage.tsx`, `pages/org/OrgProjectsPage.tsx`                                    |
| `addProjectMember`        | POST   | `/projects/{id}/members`              | `signal/pages/project/ProjectMembersPage.tsx`                                                                     |
| `removeProjectMember`     | DELETE | `/projects/{id}/members/{userId}`     | `signal/pages/project/ProjectMembersPage.tsx`                                                                     |

### 3.17 Projects — dashboard, scores, memory

| Function                  | Method | Path                                          | Consumed by                                                              |
| ------------------------- | ------ | --------------------------------------------- | ------------------------------------------------------------------------ |
| `getProjectDashboard`     | GET    | `/projects/{id}/dashboard`                    | `stores/useProjectStore.ts`, `signal/pages/project/ProjectPulsePage.tsx` |
| `getProjectScores`        | GET    | `/projects/{id}/scores`                       | `stores/useProjectStore.ts`, `components/project/PulseTab.tsx`           |
| `getWorkspaceHealth`      | GET    | `/projects/{id}/workspace-health`             | `components/project/WorkspaceHealthPanel.tsx`, `components/project/ResetWorkspaceSheet.tsx` |
| `getProjectSummary`       | GET    | `/projects/{id}/summary`                      | `signal/pages/project/ProjectPulsePage.tsx`                              |
| `getProjectMemory`        | GET    | `/projects/{id}/memory`                       | `components/project/ProjectMemoryPanel.tsx`                              |
| `getProjectLedger`        | GET    | `/projects/{id}/memory/ledger`                | `components/project/ProjectMemoryPanel.tsx`                              |
| `compactProjectMemory`    | POST   | `/projects/{id}/memory/compact`               | `components/project/ProjectMemoryPanel.tsx`                              |

### 3.18 Projects — chat

| Function                  | Method | Path                                  | Consumed by                                          |
| ------------------------- | ------ | ------------------------------------- | ---------------------------------------------------- |
| `getProjectChatInfo`      | GET    | `/projects/{id}/chat/info`            | `signal/pages/project/ProjectChatPage.tsx`           |
| `compileProjectChatToSpec`| POST   | `/projects/{id}/chat/compile`         | `components/project/ProjectChatPanel.tsx`            |
| `clearProjectChat`        | DELETE | `/projects/{id}/chat`                 | `components/project/ProjectChatPanel.tsx`            |
| `compactProjectChat`      | POST   | `/projects/{id}/chat/compact`         | `components/project/ProjectChatPanel.tsx`            |

### 3.19 Projects — architecture + codemap

| Function                       | Method | Path                                          | Consumed by                                                            |
| ------------------------------ | ------ | --------------------------------------------- | ---------------------------------------------------------------------- |
| `getArchitectureDoc`           | GET    | `/projects/{id}/architecture`                 | `components/project/ArchitectureViewer.tsx`, `signal/pages/project/ProjectArchitecturePage.tsx` |
| `getArchitectureVersions`      | GET    | `/projects/{id}/architecture/versions`        | `components/project/ArchitectureViewer.tsx`                            |
| `generateArchitecture`         | POST   | `/projects/{id}/architecture/generate`        | `components/project/ArchitectureViewer.tsx`                            |
| `annotateArchitecture`         | PUT    | `/projects/{id}/architecture/annotate`        | `components/project/ArchitectureViewer.tsx`                            |
| `getArchitectureDiff`          | GET    | `/projects/{id}/architecture/diff?from=&to=`  | `components/project/ArchitectureViewer.tsx`                            |
| `getArchitectureStaleness`     | GET    | `/projects/{id}/architecture/staleness`       | `components/project/ArchitectureViewer.tsx`, `signal/pages/project/ProjectArchitecturePage.tsx` |
| `analyseProject`               | POST   | `/projects/{id}/analyse?branch=...`           | `components/project/ResetWorkspaceSheet.tsx`, `components/project/WorkspaceHealthPanel.tsx` |
| `getRepoCodeMap`               | GET    | `/projects/{id}/repos/{repoId}/codemap`       | `signal/pages/project/ProjectCodePage.tsx`                             |
| `listRepoFiles`                | GET    | `/projects/{id}/repos/{repoId}/files`         | `signal/pages/project/ProjectCodePage.tsx` (Browse tab)                |
| `readRepoFile`                 | GET    | `/projects/{id}/repos/{repoId}/file?path=...` | `signal/pages/project/ProjectCodePage.tsx`                             |

### 3.20 Projects — documents (RAG)

| Function                    | Method | Path                                          | Consumed by                                          |
| --------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `listProjectDocuments`      | GET    | `/projects/{id}/documents`                    | `components/project/ProjectDocumentsPanel.tsx`       |
| `uploadProjectDocument`     | POST   | `/projects/{id}/documents` (multipart)        | `components/project/ProjectDocumentsPanel.tsx`       |
| `deleteProjectDocument`     | DELETE | `/projects/{id}/documents/{documentId}`       | `components/project/ProjectDocumentsPanel.tsx`       |

### 3.21 Projects — generated docs

| Function                  | Method | Path                                          | Consumed by                                          |
| ------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `getProjectDoc`           | GET    | `/projects/{id}/docs/{docType}`               | `components/project/GeneratedDocsPanel.tsx`, `components/project/PulseTab.tsx` |
| `generateProjectDoc`      | POST   | `/projects/{id}/docs/{docType}/generate`      | `components/project/GeneratedDocsPanel.tsx`          |
| `downloadProjectDoc`      | GET    | `/projects/{id}/docs/{docType}/download`      | `components/project/GeneratedDocsPanel.tsx`          |

### 3.22 Projects — PRs

| Function                  | Method | Path                                                  | Consumed by                                          |
| ------------------------- | ------ | ----------------------------------------------------- | ---------------------------------------------------- |
| `refreshProjectPrs`       | POST   | `/projects/{id}/prs/refresh`                          | `signal/pages/project/ProjectPulsePage.tsx`, `components/project/PostMergeBar.tsx` |
| `fetchPrReviewComments`   | GET    | `/projects/{id}/prs/{prNumber}/reviews?repo=...`      | `components/project/InlineRunDetail.tsx`             |
| `getPullRequestSnapshot`  | GET    | `/prs/snapshot?repo=&number=&maxChars=`               | `signal/pages/project/ProjectPulsePage.tsx`, `components/project/PostMergeBar.tsx` |
| `getPostMergeBar`         | GET    | `/projects/{id}/post-merge-bar`                       | `components/project/PostMergeBar.tsx`                |
| `dismissPostMergeBar`     | DELETE | `/projects/{id}/post-merge-bar`                       | `components/project/PostMergeBar.tsx`                |

### 3.23 Projects — estimate + tech debt + activity

| Function                    | Method | Path                                          | Consumed by                                                                       |
| --------------------------- | ------ | --------------------------------------------- | --------------------------------------------------------------------------------- |
| `estimateRun`               | GET    | `/projects/{id}/estimate?requirement=...`     | `signal/pages/project/ProjectComposePage.tsx`, `components/runs/EstimateChip.tsx` |
| `getTechDebt`               | GET    | `/projects/{id}/tech-debt?since=...`          | `components/project/TechDebtPanel.tsx` (via `ProjectPulsePage`)                   |
| `dispatchTechDebtFix`       | POST   | `/projects/{id}/tech-debt/fix`                | `components/project/TechDebtPanel.tsx` (autofix Phase 2)                          |
| `getActivityFeed`           | GET    | `/projects/{id}/activity`                     | `signal/pages/project/ProjectActivityPage.tsx`                                    |

### 3.24 Projects — spec templates

| Function                       | Method | Path                                          | Consumed by                                          |
| ------------------------------ | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `listSpecTemplates`            | GET    | `/projects/{id}/templates`                    | `signal/pages/ExtraPages.tsx` (`SpecTemplatesPage`)  |
| `createProjectSpecTemplate`    | POST   | `/projects/{id}/templates`                    | `signal/pages/ExtraPages.tsx`                        |
| `createOrgSpecTemplate`        | POST   | `/orgs/{orgId}/templates`                     | `signal/pages/ExtraPages.tsx`                        |
| `deleteSpecTemplate`           | DELETE | `/projects/{id}/templates/{templateId}`       | `signal/pages/ExtraPages.tsx`                        |

### 3.25 Projects — contracts + drift

| Function                       | Method | Path                                                          | Consumed by                                          |
| ------------------------------ | ------ | ------------------------------------------------------------- | ---------------------------------------------------- |
| `getContractCoverage`          | GET    | `/projects/{id}/repos/{repoId}/contract-coverage`             | `components/project/ContractCoveragePanel.tsx`       |
| `rescanContractCoverage`       | POST   | `/projects/{id}/repos/{repoId}/contract-coverage/rescan`      | `components/project/ContractCoveragePanel.tsx`       |
| `checkContractDrift`           | POST   | `/projects/{id}/contracts/{contractId}/check-drift`           | `components/project/ContractDriftModal.tsx`          |
| `checkRepoDrift`               | POST   | `/projects/{id}/repos/{repoId}/check-drift`                   | `components/project/ContractDriftModal.tsx`          |
| `checkProjectDrift`            | POST   | `/projects/{id}/check-drift`                                  | `components/project/ContractDriftModal.tsx`          |
| `refreshContractFromSource`    | POST   | `/projects/{id}/contracts/{contractId}/refresh-from-source`   | `components/project/ContractDriftModal.tsx`          |
| `dismissContractDrift`         | POST   | `/projects/{id}/contracts/{contractId}/dismiss-drift`         | `components/project/ContractDriftModal.tsx`          |

### 3.26 Projects — ADRs and invariants

| Function                          | Method | Path                                          | Consumed by                                          |
| --------------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `listProjectAdrs`                 | GET    | `/projects/{id}/adrs`                         | `components/project/AdrPanel.tsx`                    |
| `getProjectAdr`                   | GET    | `/projects/{id}/adrs/{id}`                    | `components/project/AdrPanel.tsx`                    |
| `listProjectInvariants`           | GET    | `/projects/{id}/invariants`                   | `components/project/InvariantPanel.tsx`              |
| `listProjectInvariantViolations`  | GET    | `/projects/{id}/invariants/violations`        | `components/project/InvariantPanel.tsx`              |

### 3.27 Standards (compliance report)

| Function                  | Method | Path                                                | Consumed by                                          |
| ------------------------- | ------ | --------------------------------------------------- | ---------------------------------------------------- |
| `getStandardsReport`      | GET    | `/projects/{id}/standards/report?orgId=&sinceDays=` | `components/settings/OrgStandardsManager.tsx`        |
| `listOrgStandards`        | GET    | `/orgs/{orgId}/standards`                           | `components/settings/OrgStandardsManager.tsx`        |
| `createOrgStandard`       | POST   | `/orgs/{orgId}/standards`                           | `components/settings/OrgStandardsManager.tsx`        |
| `updateOrgStandard`       | PUT    | `/orgs/{orgId}/standards/{standardId}`              | `components/settings/OrgStandardsManager.tsx`        |
| `deleteOrgStandard`       | DELETE | `/orgs/{orgId}/standards/{standardId}`              | `components/settings/OrgStandardsManager.tsx`        |

### 3.28 Repos (project-linked)

| Function                  | Method | Path                                          | Consumed by                                                                          |
| ------------------------- | ------ | --------------------------------------------- | ------------------------------------------------------------------------------------ |
| `listRepos`               | GET    | `/repos?scope=...`                            | `components/project/ProjectRepoManager.tsx`                                          |
| `listGitHubRepos`         | GET    | `/repos/github?owner=...`                     | `components/project/ProjectRepoManager.tsx`                                          |
| `createRepo`              | POST   | `/repos`                                      | `components/project/ProjectRepoManager.tsx`                                          |
| `deleteRepo`              | DELETE | `/repos/{repoId}`                             | `components/project/ProjectRepoManager.tsx`                                          |
| `syncRepo`                | POST   | `/repos/{repoId}/sync`                        | `components/project/ProjectRepoManager.tsx`                                          |
| `installRepoWebhook`      | POST   | `/repos/{repoId}/webhook`                     | `components/project/RepoCard.tsx`                                                    |
| `getProjectRepos`         | GET    | `/projects/{id}/repos`                        | `stores/useProjectStore.ts`, `components/project/ProjectChatPanel.tsx`, `signal/pages/project/ProjectReposPage.tsx`, `signal/pages/project/ProjectComposePage.tsx` |
| `linkProjectRepo`         | POST   | `/projects/{id}/repos/{repoId}`               | `signal/pages/project/ProjectReposPage.tsx`                                          |
| `unlinkProjectRepo`       | DELETE | `/projects/{id}/repos/{repoId}`               | `signal/pages/project/ProjectReposPage.tsx`                                          |
| `updateRepoRelationships` | PUT    | `/projects/{id}/repos/{repoId}/relationships` | `signal/pages/project/ProjectReposPage.tsx`                                          |

### 3.29 PR Review agent (V172)

| Function                          | Method | Path                                          | Consumed by                                          |
| --------------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `submitPrReview`                  | POST   | `/pr-reviews`                                 | `components/project/PrReviewButton.tsx`              |
| `getPrReview`                     | GET    | `/pr-reviews/{id}`                            | `components/project/PrReviewButton.tsx`              |
| `listPrReviewsForPr`              | GET    | `/pr-reviews?repo=&number=`                   | `components/project/PrReviewButton.tsx`              |
| `listPrReviewsForProject`         | GET    | `/projects/{id}/pr-reviews`                   | `components/project/PrReviewButton.tsx`              |
| `dispatchPrReviewAutofix`         | POST   | `/pr-reviews/{reviewId}/autofix`              | `components/project/PrReviewButton.tsx` (autofix Phase 1) |

### 3.30 Code Review agent (V172)

| Function                          | Method | Path                                          | Consumed by                                                              |
| --------------------------------- | ------ | --------------------------------------------- | ------------------------------------------------------------------------ |
| `submitCodeReview`                | POST   | `/code-reviews`                               | `components/project/CodeQualityPanel.tsx`                                |
| `getCodeReview`                   | GET    | `/code-reviews/{id}`                          | `components/project/CodeQualityPanel.tsx`                                |
| `listCodeReviewsForProject`       | GET    | `/projects/{id}/code-reviews`                 | `components/project/CodeQualityPanel.tsx`                                |
| `getLatestCodeReviewForRepo`      | GET    | `/projects/{id}/code-reviews/latest?repo=...` | `components/project/CodeQualityPanel.tsx`                                |
| `dispatchCodeReviewAutofix`       | POST   | `/code-reviews/{scanId}/autofix`              | `components/project/CodeQualityPanel.tsx` (autofix Phase 4)              |
| `codeReviewReportDownloadUrl`     | GET    | `/code-reviews/{id}/report`                   | `components/project/CodeQualityPanel.tsx` (direct anchor `href`)         |

### 3.31 External project sharing — invites + share links (V175 + V198)

| Function                  | Method | Path                                                  | Consumed by                                          |
| ------------------------- | ------ | ----------------------------------------------------- | ---------------------------------------------------- |
| `createProjectInvite`     | POST   | `/projects/{id}/invites`                              | `signal/pages/project/ProjectMembersPage.tsx`        |
| `listProjectInvites`      | GET    | `/projects/{id}/invites`                              | `signal/pages/project/ProjectMembersPage.tsx`        |
| `revokeProjectInvite`     | DELETE | `/projects/{id}/invites/{inviteId}`                   | `signal/pages/project/ProjectMembersPage.tsx`        |
| `previewProjectInvite`    | GET    | `/invites/preview?token=...`                          | `signal/pages/ExtraPages.tsx` (`AcceptInvitePage`)   |
| `acceptProjectInvite`     | POST   | `/invites/accept?token=...`                           | `signal/pages/ExtraPages.tsx`                        |
| `createShareLink`         | POST   | `/projects/{id}/share-links`                          | `signal/pages/project/ProjectMembersPage.tsx`        |
| `listShareLinks`          | GET    | `/projects/{id}/share-links`                          | `signal/pages/project/ProjectMembersPage.tsx`        |
| `revokeShareLink`         | DELETE | `/projects/{id}/share-links/{linkId}`                 | `signal/pages/project/ProjectMembersPage.tsx`        |
| `shareLinkPublicUrl`      | n/a    | window-local helper (no backend call)                 | `signal/pages/project/ProjectMembersPage.tsx`        |

### 3.32 Billing

`frontend/src/lib/billing-api.ts` and `frontend/src/lib/invoice-api.ts` wrap higher-level helpers. The contact form is the one direct billing-adjacent call in `api.ts`:

| Function                  | Method | Path                                  | Consumed by                                                         |
| ------------------------- | ------ | ------------------------------------- | ------------------------------------------------------------------- |
| `submitContactForm`       | POST   | `/contact`                            | `components/auth/ContactModal.tsx`                                  |

Most billing flows (plans, subscribe, top-up purchases, payment history, webhook acknowledgements) live in `frontend/src/lib/billing-api.ts`. The URLs there match the backend `@PostMapping` / `@GetMapping` annotations on `billing/` controllers and are also covered by `ApiContractTest`.

### 3.33 Notifications

| Function                       | Method | Path                                  | Consumed by                                                                          |
| ------------------------------ | ------ | ------------------------------------- | ------------------------------------------------------------------------------------ |
| `getNotifications`             | GET    | `/notifications`                      | `components/layout/NotificationPanel.tsx`, `signal/pages/MiscPages.tsx` (`NotificationsPage`) |
| `getUnreadCount`               | GET    | `/notifications/unread-count`         | `components/layout/NotificationBell.tsx`                                             |
| `markNotificationRead`         | POST   | `/notifications/{id}/read`            | `components/layout/NotificationPanel.tsx`                                            |
| `markAllNotificationsRead`     | POST   | `/notifications/read-all`             | `components/layout/NotificationPanel.tsx`                                            |
| `getNotificationPreferences`   | GET    | `/notifications/preferences`          | `components/settings/NotificationPreferences.tsx`                                    |
| `updateNotificationPreference` | PUT    | `/notifications/preferences`          | `components/settings/NotificationPreferences.tsx`                                    |

### 3.34 SSO config (M-004)

| Function           | Method | Path                          | Consumed by                                          |
| ------------------ | ------ | ----------------------------- | ---------------------------------------------------- |
| `getSsoConfig`     | GET    | `/auth/sso/config`            | `components/settings/SsoConfigCard.tsx`              |
| `updateSsoConfig`  | PUT    | `/auth/sso/config`            | `components/settings/SsoConfigCard.tsx`              |
| `clearSsoConfig`   | DELETE | `/auth/sso/config`            | `components/settings/SsoConfigCard.tsx`              |

### 3.35 Slack integration

| Function                       | Method | Path                                                                      | Consumed by                                          |
| ------------------------------ | ------ | ------------------------------------------------------------------------- | ---------------------------------------------------- |
| `getSlackStatus`               | GET    | `/integrations/slack/status`                                              | `signal/pages/account/MiscAccountPages.tsx`          |
| `getSlackInstallUrl`           | GET    | `/integrations/slack/install`                                             | `signal/pages/account/MiscAccountPages.tsx`          |
| `disconnectSlack`              | DELETE | `/integrations/slack/disconnect`                                          | `signal/pages/account/MiscAccountPages.tsx`          |
| `listSlackChannels`            | GET    | `/integrations/slack/channels`                                            | `signal/pages/account/MiscAccountPages.tsx`          |
| `getProjectSlackChannels`      | GET    | `/integrations/slack/projects/{projectId}/channels`                       | `signal/pages/project/ProjectSettingsPage.tsx`       |
| `addProjectSlackChannel`       | POST   | `/integrations/slack/projects/{projectId}/channels`                       | `signal/pages/project/ProjectSettingsPage.tsx`       |
| `removeProjectSlackChannel`    | DELETE | `/integrations/slack/projects/{projectId}/channels/{channelId}`           | `signal/pages/project/ProjectSettingsPage.tsx`       |
| `updateSlackChannelVerbosity`  | PUT    | `/integrations/slack/projects/{projectId}/channels/{channelId}/verbosity` | `signal/pages/project/ProjectSettingsPage.tsx`       |
| `sendSlackTestMessage`         | POST   | `/integrations/slack/test`                                                | `signal/pages/account/MiscAccountPages.tsx`          |

### 3.36 Jira / Confluence integrations

| Function                  | Method | Path                                          | Consumed by                                          |
| ------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `getIntegrationStatus`    | GET    | `/integrations/status`                        | `signal/pages/account/MiscAccountPages.tsx`          |
| `getIntegrationRuns`      | GET    | `/integrations/runs?source=...`               | `signal/pages/account/MiscAccountPages.tsx`          |
| `triggerConfluence`       | POST   | `/integrations/confluence/trigger`            | `signal/pages/account/MiscAccountPages.tsx`          |
| `triggerJira`             | POST   | `/integrations/jira/trigger`                  | `signal/pages/account/MiscAccountPages.tsx`          |

### 3.37 Migrations (R-336..R-339)

| Function                  | Method | Path                                  | Consumed by                                          |
| ------------------------- | ------ | ------------------------------------- | ---------------------------------------------------- |
| `listMigrations`          | GET    | `/migrations`                         | `signal/pages/ExtraPages.tsx` (`MigrationsPage`)     |
| `getMigrationDetail`      | GET    | `/migrations/{slug}`                  | `signal/pages/ExtraPages.tsx` (`MigrationDetailPage`)|
| `migrationDryRun`         | POST   | `/migrations/{slug}/dry-run`          | `signal/pages/ExtraPages.tsx`                        |
| `migrationApply`          | POST   | `/migrations/{slug}/apply`            | `signal/pages/ExtraPages.tsx`                        |

### 3.38 Readymade Solutions library (R-327..R-331)

| Function                       | Method | Path                                  | Consumed by                                                              |
| ------------------------------ | ------ | ------------------------------------- | ------------------------------------------------------------------------ |
| `listLibrary`                  | GET    | `/library`                            | `signal/pages/ExtraPages.tsx` (`LibraryPage`)                            |
| `getLibraryDetail`             | GET    | `/library/{slug}`                     | `signal/pages/ExtraPages.tsx` (`LibraryDetailPage`)                      |
| `getLibraryFacets`             | GET    | `/library/categories`                 | `signal/pages/ExtraPages.tsx`                                            |
| `resolveLibraryVariant`        | POST   | `/library/{slug}/resolve`             | `signal/pages/ExtraPages.tsx`                                            |
| `instantiateLibrarySolution`   | POST   | `/library/{slug}/instantiate`         | `signal/pages/ExtraPages.tsx`                                            |
| `favouriteLibrarySolution`     | POST   | `/library/favourite/{slug}`           | `signal/pages/ExtraPages.tsx`                                            |
| `unfavouriteLibrarySolution`   | DELETE | `/library/favourite/{slug}`           | `signal/pages/ExtraPages.tsx`                                            |
| `requiresCostCap`              | n/a    | Pure-function tier predicate (no HTTP)| `signal/pages/ExtraPages.tsx`, `components/runs/EstimateChip.tsx`        |

### 3.39 Audit logs

| Function           | Method | Path                                          | Consumed by                                          |
| ------------------ | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `getAuditLogs`     | GET    | `/admin/audit-logs` OR `/org/me/audit-logs`   | `signal/pages/ExtraPages.tsx` (`AuditLogPage`) — toggled via the `orgScoped` flag in the call site |

### 3.40 Admin — users, orgs, invitations

| Function                          | Method | Path                                          | Consumed by                                          |
| --------------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `adminListUsers`                  | GET    | `/admin/users`                                | `signal/pages/MiscPages.tsx` (`AdminPage`)           |
| `adminGetUser`                    | GET    | `/admin/users/{id}`                           | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateUserStatus`           | PUT    | `/admin/users/{id}/status`                    | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateUserTier`             | PUT    | `/admin/users/{id}/tier`                      | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateUserRole`             | PUT    | `/admin/users/{id}/role`                      | `signal/pages/MiscPages.tsx`                         |
| `adminGrantUserRuns`              | POST   | `/admin/users/{userId}/grant-runs`            | `signal/pages/MiscPages.tsx`                         |
| `adminGrantOrgRuns`               | POST   | `/admin/orgs/{orgId}/grant-runs`              | `signal/pages/MiscPages.tsx`                         |
| `adminListOrgs`                   | GET    | `/admin/orgs`                                 | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateOrgTier`              | PUT    | `/admin/orgs/{id}/tier`                       | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateOrgStatus`            | PUT    | `/admin/orgs/{id}/status`                     | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateOrgRunLimit`          | PUT    | `/admin/orgs/{id}/run-limit`                  | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateOrgApiKeys`           | PUT    | `/admin/orgs/{id}/api-keys`                   | `signal/pages/MiscPages.tsx`                         |
| `adminInviteUser`                 | POST   | `/admin/invite`                               | `signal/pages/MiscPages.tsx`                         |
| `adminListEscalated`              | GET    | `/admin/invitations/escalated`                | `signal/pages/MiscPages.tsx`                         |
| `adminApproveInvite`              | POST   | `/admin/invitations/{id}/approve`             | `signal/pages/MiscPages.tsx`                         |
| `adminRejectInvite`               | POST   | `/admin/invitations/{id}/reject`              | `signal/pages/MiscPages.tsx`                         |
| `adminGetTierConfig`              | GET    | `/admin/tier-config`                          | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateTierConfig`           | PUT    | `/admin/tier-config/{tier}`                   | `signal/pages/MiscPages.tsx`                         |

### 3.41 Admin — tier lifecycle (LISTED / HIDDEN / SUNSETTING)

| Function                  | Method | Path                                  | Consumed by                                          |
| ------------------------- | ------ | ------------------------------------- | ---------------------------------------------------- |
| `fetchTiersLifecycle`     | GET    | `/admin/tiers`                        | tier lifecycle admin view (under `signal/pages/MiscPages.tsx → AdminPage`) |
| `updateTierLifecycle`     | PUT    | `/admin/tiers/{tier}/lifecycle`       | tier lifecycle admin view                            |

### 3.42 Admin — top-up packages, halt-all, audit chain, activation funnel

| Function                          | Method | Path                                  | Consumed by                                          |
| --------------------------------- | ------ | ------------------------------------- | ---------------------------------------------------- |
| `adminListTopupPackages`          | GET    | `/admin/topup-packages`               | `signal/pages/MiscPages.tsx`                         |
| `adminCreateTopupPackage`         | POST   | `/admin/topup-packages`               | `signal/pages/MiscPages.tsx`                         |
| `adminUpdateTopupPackage`         | PUT    | `/admin/topup-packages/{id}`          | `signal/pages/MiscPages.tsx`                         |
| `adminDeleteTopupPackage`         | DELETE | `/admin/topup-packages/{id}`          | `signal/pages/MiscPages.tsx`                         |
| `haltAllActiveRuns`               | POST   | `/admin/runs/halt-all`                | `signal/pages/MiscPages.tsx`                         |
| `verifyAuditChain`                | GET    | `/admin/audit-chain/verify`           | `signal/pages/MiscPages.tsx`                         |
| `getActivationFunnel`             | GET    | `/admin/activation-funnel`            | `signal/pages/MiscPages.tsx`                         |
| `adminTriggerUsageReport`         | POST   | `/admin/usage-reports/trigger`        | `signal/pages/MiscPages.tsx`                         |

### 3.43 Admin — feature flags

| Function                  | Method | Path                                          | Consumed by                                          |
| ------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `adminListFeatureFlags`   | GET    | `/admin/feature-flags`                        | `signal/pages/MiscPages.tsx`                         |
| `getPublicFeatureFlags`   | GET    | `/admin/feature-flags/public`                 | `stores/useAuthStore.ts` (loaded post-login)         |
| `adminUpdateFeatureFlag`  | PUT    | `/admin/feature-flags/{key}`                  | `signal/pages/MiscPages.tsx`                         |

### 3.44 Admin — analytics

| Function                  | Method | Path                                          | Consumed by                                          |
| ------------------------- | ------ | --------------------------------------------- | ---------------------------------------------------- |
| `getAnalyticsUsage`       | GET    | `/admin/analytics/usage`                      | `signal/pages/MiscPages.tsx` (`AdminPage` analytics tab) |
| `getAnalyticsMembers`     | GET    | `/admin/analytics/members`                    | `signal/pages/MiscPages.tsx`                         |
| `getAnalyticsBilling`     | GET    | `/admin/analytics/billing`                    | `signal/pages/MiscPages.tsx`                         |

### 3.45 Admin — payment audit + webhook log

| Function                       | Method | Path                                  | Consumed by                                          |
| ------------------------------ | ------ | ------------------------------------- | ---------------------------------------------------- |
| `adminListPaymentAudit`        | GET    | `/admin/payment-audit`                | `signal/pages/MiscPages.tsx`                         |
| `adminExportPaymentAuditCsv`   | GET    | `/admin/payment-audit/export`         | `signal/pages/MiscPages.tsx`                         |
| `adminListWebhookEvents`       | GET    | `/admin/webhook-events`               | `signal/pages/MiscPages.tsx`                         |

## 4. Coverage check — every `api.ts` URL has a backend mapping

`ApiContractTest.everyApiTsUrlResolvesToRegisteredBackendRoute` runs in `mvn verify`. Last green at HEAD. The `ALLOWLIST` is empty — no orphans.

The Java test scans for these annotations on each `@Controller` / `@RestController`:

```java
RequestMapping, GetMapping, PostMapping, PutMapping, DeleteMapping, PatchMapping
```

…and asserts every URL literal extracted from `api.ts` matches one of the registered patterns after placeholder normalisation.

When a `lib/api.ts` URL no longer has a backend mapping (a controller was renamed or removed), the test fails with the listing:

```
URLs in frontend/src/lib/api.ts with no matching backend route (1 total):
  /runs/{id}/legacy-endpoint

Resolution: either
  (a) add the missing @*Mapping on a controller,
  (b) remove the dead frontend call site in lib/api.ts, or
  (c) add the URL template to ALLOWLIST in ApiContractTest with a one-line justification.
```

## 5. Server-sent events (not via api.ts)

SSE is not routed through `req()` — it uses the browser-native `EventSource` directly. The hook `frontend/src/signal/hooks/useSse.ts` does:

1. Call `getSseToken(runId)` to mint a scoped JWT (`scope=sse`, `runId` claim, 5-min TTL).
2. Open `new EventSource('/api/runs/' + runId + '/events?token=' + token)`.
3. Dispatch event types (`PLAN_READY`, `RUN_STATUS`, `TASK_STATUS`, `LIVE_LOG`, `RUN_COST`, `EXEC_COMPENSATION_UPDATE`, `EXEC_COMPENSATION_DONE`, `RUN_PROVIDER_SWITCHED`, `COMPLETE`) into `useRunStore`.
4. Reconnect with a fresh token if the EventSource errors.

The token endpoint (`getSseToken`) IS in `api.ts` and IS covered by `ApiContractTest`; the EventSource open is not.

## 6. WebSocket usage

None. The app is HTTP + SSE only.

## 7. Polling cadences (informational)

These are not API endpoints but call-site contracts worth knowing about:

| Surface                                       | Cadence                       | Calls                                              |
| --------------------------------------------- | ----------------------------- | -------------------------------------------------- |
| RunDetail cost chip                           | 5 s while non-terminal        | `getLiveCost(runId)`                               |
| RunDetail files panel                         | 5 s while non-terminal        | `getFilesWithStatus(runId)`                        |
| HomePage activity feed                        | 30 s                          | `getActivityFeed(projectId, ...)`                  |
| ProjectMemoryPanel                            | 30 s                          | `getProjectMemory(projectId)`                      |
| NotificationBell unread-count                 | 60 s                          | `getUnreadCount()`                                 |

## 8. Where to add a new endpoint

1. Add the typed exported function at the bottom of the appropriate section in `frontend/src/lib/api.ts`. Follow the existing pattern:

   ```ts
   export const fooBar = (id: string, body: FooBody) =>
     req<Foo>(`/projects/${id}/foo`, {method: 'POST', body: JSON.stringify(body)});
   ```

2. Add the matching `@*Mapping` on a controller under `src/main/java/com/navneet/executespec/`.
3. Run `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -Dtest=ApiContractTest test -q` — if the contract test passes, the URL resolves to a real route.
4. Import + call the function from a TSX page or component. The bearer token is auto-injected.

## 9. Where to add a new error case

`STATUS_MESSAGES` in `lib/api.ts` (lines 11–25) is the friendly-fallback dictionary. Each entry maps an HTTP status to a customer-facing one-liner. Add a new status by:

1. Adding a new entry to the constant.
2. Verifying the backend `ValidationAdvice` returns the canonical envelope at that status (otherwise the `parseErrorMessage` JSON path fails over to the status-message fallback).

The error envelope is fixed — changing `ValidationAdvice.baseBody` will break `parseErrorMessage`'s preferred path (it would fall through to the status-message dictionary, lossy).
