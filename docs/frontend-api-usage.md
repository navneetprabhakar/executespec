# Frontend → Backend API usage

This document is the single reference for **how the React frontend talks to the Spring Boot backend**. It answers:

- Where the bearer token comes from on every request.
- What a 4xx error envelope looks like and how the frontend maps it to user-visible copy.
- Which `api.*` function calls which controller, and which Signal page consumes it.
- How SSE event streams are authenticated and reconnected.
- Where polling intervals live (live cost, run status, file refresh, etc.).
- The minimum checklist for adding a new endpoint without drift.

Scope: all REST calls from `frontend/src/`. The authoritative client is `frontend/src/lib/api.ts` (~3,620 lines); namespaced sub-clients live alongside it (`execution-api.ts`, `billing-api.ts`, `invoice-api.ts`). Pages under `frontend/src/signal/pages/` and components under `frontend/src/signal/components/` consume those exports.

Last meaningful refresh: 2026-05-16.

---

## 1. Bearer token injection

Every authenticated REST call carries `Authorization: Bearer <jwt>` where `<jwt>` is the **JWT access token** stored in `localStorage` under the key `access_token`. The refresh token is **not** stored in `localStorage` — it is an HttpOnly cookie owned by the backend (the OAuth callback page explicitly strips any legacy `refresh_token` key from `localStorage`).

### How it works

In `frontend/src/lib/api.ts`:

```
const BASE = '/api';

function authHeader(): Record<string, string> {
  const token = localStorage.getItem('access_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function req<T>(url: string, opts: RequestInit = {}): Promise<T> {
  const headers = {
    'Content-Type': 'application/json',
    ...authHeader(),
    ...(opts.headers || {}),
  };
  let res: Response;
  try {
    res = await fetch(`${BASE}${url}`, { ...opts, headers });
  } catch (e) {
    throw new ApiError(0, 'Network error');
  }
  if (!res.ok) throw new ApiError(res.status, await parseErrorMessage(res, res.status));
  // ... JSON or text parse
}
```

Behaviour:

- The header is read **at the moment of the call**, not at module load — so a freshly-stored token (after `/login` or `/auth/refresh`) is picked up by the very next request without a page reload.
- If the user is anonymous, `authHeader()` returns `{}` and the request goes through unauthenticated. The backend resolves `permitAll` paths; everything else 401s through `ValidationAdvice` and bubbles up as `ApiError(401)`.
- `fetch` failures (network down, DNS, CORS) throw `ApiError(0, 'Network error')` so call sites can distinguish "server didn't respond" from "server responded with 401".

### Where tokens come from

Source paths that write `access_token` into `localStorage`:

- `frontend/src/stores/useAuthStore.ts` — sets the token in the `login` and `refreshAccessToken` actions, clears it in `logout`/`clearAuth`.
- `frontend/src/signal/pages/AuthCallbackPage.tsx` — after the OAuth2 backend redirect to `/auth/callback?token=…`, the callback page persists the token and immediately calls `loadMe()` to populate the auth store.
- `frontend/src/signal/pages/AuthPages.tsx::LoginPage` — calls `store.login(email, password)` which internally hits `POST /api/auth/login` and stores the returned token.

### Known footgun

`api.uploadProjectDocument` (used by `ProjectSettingsPage.tsx`) reads `localStorage.getItem('token')` instead of `'access_token'` when constructing its custom `multipart/form-data` `Authorization` header — likely a copy-paste regression. Document uploads will hit the backend unauthenticated until the key is corrected.

### Scoped JWTs for SSE

The full access token is **not** sent as a query parameter to SSE endpoints (it would leak into proxy logs). Instead, the frontend mints a short-lived scoped token first via `getSseToken(runId)` (5-min TTL, `scope=sse` + `runId` claim) and passes it as `?token=…` on `GET /api/runs/{runId}/events`. See section 4.

---

## 2. Error envelope

### Canonical 4xx shape

`src/main/java/com/navneet/executespec/api/advice/ValidationAdvice.java` registers at `@Order(HIGHEST_PRECEDENCE)` and forces `application/json` on every error response so a malformed JSON body or an `IllegalArgumentException` never leaks as a 500 with `text/plain`.

Every 4xx response body looks like:

```json
{
  "timestamp": "2026-05-16T08:42:03Z",
  "status": 400,
  "error": "bad_request",
  "message": "Human-readable description of what went wrong",
  "fields": {
    "email": "must be a well-formed email address",
    "userRequest": "must not be blank"
  }
}
```

Field semantics:

- `timestamp` — ISO-8601 UTC; emitted by the backend, not the client clock.
- `status` — integer matching the HTTP status line (200-class never reaches this advice).
- `error` — short slug derived from the status: `bad_request` (400), `unauthorized` (401), `payment_required` (402), `forbidden` (403), `not_found` (404), `conflict` (409), `gone` (410), `unprocessable_entity` (422), `too_many_requests` (429), `internal_server_error` (500), `bad_gateway` (502), `service_unavailable` (503). The slug map lives in `ValidationAdvice.slugForStatus`.
- `message` — surfaced to the user; this is the field the frontend's `parseErrorMessage` reads.
- `fields` — present only on bean-validation failures (`MethodArgumentNotValidException`, `ConstraintViolationException`). Keys are field names (e.g. `userRequest`, `userCostCap`), values are the violation message. The frontend uses this to highlight specific form inputs.

### Specific 4xx handlers in ValidationAdvice

| Handler                                       | Status | Triggered when                                                          |
| --------------------------------------------- | ------ | ----------------------------------------------------------------------- |
| `onValidation(MethodArgumentNotValidException)` | 400  | `@Valid` body fails (e.g. blank `userRequest`, `userCostCap` out of range) |
| `onResponseStatus(ResponseStatusException)`    | varies | Controllers explicitly throw `ResponseStatusException` (e.g. 409, 503) |
| `onAccessDenied(AccessDeniedException)`        | 403  | RBAC guard rejects the call                                              |
| `onBadJson(HttpMessageNotReadableException)`   | 400  | Malformed JSON body / wrong content type                                |
| `onTypeMismatch(MethodArgumentTypeMismatchException)` | 400 | Path/query param coerces to wrong type                              |
| `onIllegalArgument(IllegalArgumentException)`  | 400  | Enum coercion / range checks (was leaking as 500 pre-2026-05-16)        |
| `onConstraintViolation(ConstraintViolationException)` | 400 | Method-level validation (e.g. `@Min` on query param)                |
| `onMethodNotAllowed(HttpRequestMethodNotSupportedException)` | 405 | Wrong verb; response also lists supported methods             |
| `onMissingParam(MissingServletRequestParameterException)` | 400 | Required `@RequestParam` absent                                  |
| `onBilling(BillingException)`                  | 402  | Quota exceeded / payment required                                       |

### Additional advice handlers

- `OptimisticLockAdvice` (`api/advice/OptimisticLockAdvice.java`) — translates `ObjectOptimisticLockingFailureException` to **409 Conflict** with body `{error, message, entity, id}` and message "Reload and retry." Introduced with V112 + `@Version` on `ExecutionRun`.
- `GlobalExceptionAdvice` (`api/advice/GlobalExceptionAdvice.java`) — runs at `@Order(Integer.MAX_VALUE)` as the last-chance catch. Handles `ErrorResponseException` (Spring 6 supertype for 4xx), `NoResourceFoundException` (canonical 404), and any other `Throwable` (returns 500 with an opaque 8-char UUID `errorId`; the full stack is logged server-side under that same id).

### Frontend mapping: `parseErrorMessage` + `STATUS_MESSAGES`

In `frontend/src/lib/api.ts`:

```
const STATUS_MESSAGES: Record<number, string> = {
  400: 'Request was invalid. Check the form and try again.',
  401: 'Sign in to continue.',
  402: 'Payment required. Check your plan or top up credits.',
  403: 'You don’t have permission to do that.',
  404: 'We couldn’t find that.',
  409: 'Conflict — someone else changed this. Reload and try again.',
  410: 'That action has been retired.',
  422: 'Validation failed.',
  429: 'Slow down — too many requests.',
  500: 'Something went wrong on our side. We’ve been notified.',
  502: 'Upstream service is unavailable.',
  503: 'Service temporarily unavailable.',
};

async function parseErrorMessage(res: Response, status: number): Promise<string> {
  try {
    const body = await res.json();
    if (body?.message) return body.message;
    if (body?.error)   return body.error;
  } catch { /* not JSON */ }
  return STATUS_MESSAGES[status] || `Request failed (${status}).`;
}
```

`ApiError` itself is intentionally minimal:

```
export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}
```

There is no `cause`/`details` slot — callers that need richer error info today would have to inspect the raw response themselves (none currently do).

The exported helper `isRetryableError(e: unknown): boolean` returns `true` when the error is `ApiError(0)` (network) or `status >= 500`. Two call sites use it: `useSse.ts` (to decide whether to reconnect) and the `SkeletonRow` background fetches (to decide whether to show "tap to retry").

---

## 3. Endpoint catalog

Every function exported from `frontend/src/lib/api.ts` (plus the namespaced clients in `lib/execution-api.ts`, `lib/billing-api.ts`, `lib/invoice-api.ts`) is listed below, grouped by domain. For each function:

- **Function name** as exported.
- **HTTP verb** + URL (prefixed with `/api` by the `req<T>` helper — written without the prefix below to match how it looks at the call site).
- **Consumed by** — the Signal pages or components that call it. Helper components rarely call the API directly; most consumption happens at page level.

### 3.1 Run lifecycle

All paths under `/api/runs/*`. The hub controller is `src/main/java/com/navneet/executespec/api/controller/RunController.java` (~1,394 lines, ~30 endpoints). Lifecycle, workspace, recovery, plan-versions, push, archive/soft-delete all live in one class.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `createRun(body)`                             | POST `/runs`                                   | `signal/pages/ComposePage.tsx`, `project/ProjectComposePage.tsx`, `ResultPage.tsx` (re-run row)      |
| `getRuns(params)`                             | GET `/runs` (paginated)                        | `AllRunsPage.tsx`, `HomePage.tsx`, `MiscPages.tsx::SearchPage`, `project/ProjectsPage.tsx`, `project/ProjectRunsPage.tsx`, `project/ProjectPulsePage.tsx`, `project/ProjectComposePage.tsx` |
| `getRun(id)`                                  | GET `/runs/{id}`                               | `LivePage.tsx` (8s poll), `PlanPage.tsx` (2s poll), `ResultPage.tsx`, `HomePage.tsx`, `project/ProjectRunsPage.tsx` |
| `approveRun(id, approved, creditsConfirmed?)` | POST `/runs/{id}/approve`                      | `PlanPage.tsx`                                                                                       |
| `cancelRun(id)`                               | DELETE `/runs/{id}`                            | `LivePage.tsx`                                                                                       |
| `pauseRun(runId)`                             | POST `/runs/{runId}/pause`                     | not yet wired into a Signal page (flag-gated)                                                        |
| `resumeRun(runId)`                            | POST `/runs/{runId}/resume`                    | not yet wired into a Signal page                                                                     |
| `archiveRun(runId)`                           | POST `/runs/{runId}/archive`                   | `AllRunsPage.tsx`, `project/ProjectRunsPage.tsx`                                                     |
| `unarchiveRun(runId)`                         | POST `/runs/{runId}/unarchive`                 | `AllRunsPage.tsx`                                                                                    |
| `softDeleteRun(runId)`                        | POST `/runs/{runId}/soft-delete`               | `AllRunsPage.tsx`                                                                                    |
| `restoreRun(runId)`                           | POST `/runs/{runId}/restore`                   | `AllRunsPage.tsx`                                                                                    |
| `forkRun(runId, overrides?)`                  | POST `/runs/{runId}/fork`                      | `ResultPage.tsx` (re-run on different provider)                                                       |
| `retryTask(runId, taskId)`                    | POST `/runs/{runId}/tasks/{taskId}/retry`      | `ResultPage.tsx`                                                                                     |
| `retryValidationReport(runId)`                | POST `/runs/{runId}/retry-validation`          | `ResultPage.tsx`                                                                                     |
| `updateRunProject(runId, projectId)`          | PATCH `/runs/{runId}/project`                  | `AllRunsPage.tsx` (move-to-project menu)                                                             |
| `iterateRun(runId, userRequest)`              | POST `/runs/{runId}/iterate`                   | `ResultPage.tsx` (legacy path; backend returns 410 Gone today)                                       |
| `editRunPlan(runId, feedback)`                | POST `/runs/{runId}/edit-plan`                 | not wired (backend returns 410 Gone)                                                                 |
| `getLiveCost(runId)`                          | GET `/runs/{runId}/cost`                       | `LivePage.tsx` (8s poll)                                                                             |
| `getTaskGraph(runId)`                         | GET `/runs/{runId}/task-graph`                 | `ResultPage.tsx` (Tasks tab)                                                                         |
| `getPlanVersions(runId)`                      | GET `/runs/{runId}/plan-versions`              | `ResultPage.tsx`                                                                                     |
| `getMessages(runId)`                          | GET `/runs/{runId}/messages`                   | `useSse.ts` (pre-populates the message buffer when SSE first connects)                               |
| `downloadWorkspace(runId)`                    | GET `/runs/{runId}/download` (blob)            | `ResultPage.tsx`                                                                                     |
| `getFiles(runId)`                             | GET `/runs/{runId}/files`                      | `ResultPage.tsx`                                                                                     |
| `getFilesWithStatus(runId)`                   | GET `/runs/{runId}/files/status`               | `ResultPage.tsx` (Code tab — ADDED/MODIFIED/UNCHANGED dots in `FileTree`)                            |
| `getFile(runId, filePath)`                    | GET `/runs/{runId}/file?path=` (text)          | `ResultPage.tsx` (file viewer)                                                                       |
| `getFileDiff(runId, filePath)`                | GET `/runs/{runId}/file/diff?path=`            | `ResultPage.tsx` (diff toggle)                                                                       |
| `getOpenApiSpec(runId)`                       | GET `/runs/{runId}/openapi.yaml` (text)        | `ResultPage.tsx` (API tab)                                                                            |
| `pushToGitHub(runId, repoMode?)`              | POST `/runs/{runId}/push-github` (deprecated)   | `ResultPage.tsx` (legacy single-repo button)                                                          |
| `pushToScm(runId)`                            | POST `/runs/{runId}/push-scm`                  | `ResultPage.tsx` (multi-repo path)                                                                   |
| `syncRun(runId, branch?)`                     | POST `/runs/{runId}/sync`                      | `ResultPage.tsx`                                                                                     |
| `syncRunToOriginal(runId)`                    | POST `/runs/{runId}/sync/original`             | `ResultPage.tsx`                                                                                     |
| `getRunPrs(runId)`                            | GET `/runs/{runId}/prs`                        | `ResultPage.tsx` (PR tab)                                                                            |

#### Chat / apply (post-run conversation)

Controller: `src/main/java/com/navneet/executespec/api/controller/RunConversationController.java`. Uses a dedicated `applyExecutor` (4-thread pool) so the 25-iter worker loop in `ApplyService` doesn't burn a Tomcat handler thread.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getRunChat(runId)`                           | GET `/runs/{runId}/chat`                       | `ResultPage.tsx` (Chat tab)                                                                          |
| `sendRunChatMessage(runId, message)`          | POST `/runs/{runId}/chat`                      | `ResultPage.tsx`                                                                                     |
| `applyToRun(runId, suggestion, source?)`      | POST `/runs/{runId}/apply` (async)             | `ResultPage.tsx` (apply-suggestion CTA)                                                              |

#### Removed-in-V2 endpoints (backend returns 410 Gone)

`POST /runs/{id}/clarify`, `/merge`, `/retry` (general), `/edit-and-restart`, `/reopen-merge`, `/rework`, `/implement-suggestions`, `/edit-plan`, `/iterate` are all wired explicitly by `RunController` / `RunConversationController` to throw 410. Old clients get a clean error envelope instead of a silent 404.

### 3.2 Auth + onboarding

Controllers: `auth/security/SecurityConfig.java` registers the filter chain; per-feature endpoints live under `auth/*` packages.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `authRegister(body)`                          | POST `/auth/register`                          | `AuthPages.tsx::SignupPage`                                                                          |
| `authLogin(body)`                             | POST `/auth/login`                             | `useAuthStore.login` (called by `AuthPages.tsx::LoginPage`)                                          |
| `authRefresh()`                               | POST `/auth/refresh`                           | `useAuthStore.refreshAccessToken`                                                                    |
| `authLogout()`                                | DELETE `/auth/logout`                          | `useAuthStore.logout` (called by `AccountPage.tsx`)                                                  |
| `authMe()`                                    | GET `/auth/me`                                 | `useAuthStore.loadMe` (called by `AuthCallbackPage.tsx`, app bootstrap)                              |
| `getMyPermissions()`                          | GET `/auth/me/permissions`                     | `useTierPermissions.ts` hook (consumed by `AccountPage.tsx`, `ComposePage.tsx`, `ResultPage.tsx`, `BillingPage.tsx`, `Shell.tsx`, …) |
| `authVerifyEmail(token)`                      | GET `/auth/verify-email?token=`                | `AuthPages.tsx::VerifyPage`                                                                          |
| `authResendVerification(email)`               | POST `/auth/resend-verification`               | `AuthPages.tsx::VerifyPage`                                                                          |
| `authForgotPassword(email)`                   | POST `/auth/forgot-password`                   | `ExtraPages.tsx::ForgotPasswordPage`                                                                 |
| `authResetPassword(token, newPassword)`       | POST `/auth/reset-password`                    | `ExtraPages.tsx::ResetPasswordPage`                                                                  |
| `authChangePassword(currentPassword, newPassword)` | POST `/auth/change-password`               | `AccountPage.tsx`                                                                                    |
| `updateGithubSettings(pat, owner)`            | PUT `/auth/settings/github`                    | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `validateGitHub(pat)`                         | POST `/auth/settings/github/validate`          | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `getGitHubAppStatus()`                        | GET `/auth/github-app/status`                  | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `getGitHubAppInstallUrl()`                    | GET `/auth/github-app/install-url`             | `AuthPages.tsx::WelcomePage`, `MiscAccountPages.tsx::IntegrationsPage`                               |
| `uninstallGitHubApp()`                        | DELETE `/auth/github-app/install`              | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `updateAiKeySettings(openai, anthropic, gemini)` | PUT `/auth/settings/ai-keys`                | `MiscAccountPages.tsx::ApiKeysPage`                                                                  |
| `validateAiKey(provider, apiKey)`             | POST `/auth/settings/ai-keys/validate`         | `MiscAccountPages.tsx::ApiKeysPage`                                                                  |
| `updateJiraSettings(...)`                     | PUT `/auth/settings/jira`                      | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `validateJira(...)`                           | POST `/auth/settings/jira/validate`            | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `updateConfluenceSettings(...)`               | PUT `/auth/settings/confluence`                | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `validateConfluence(...)`                     | POST `/auth/settings/confluence/validate`      | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `updateBitbucketSettings(...)`                | PUT `/auth/settings/bitbucket`                 | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `validateBitbucket(...)`                      | POST `/auth/settings/bitbucket/validate`       | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `updateViewMode(viewMode)`                    | PUT `/auth/settings/view-mode`                 | `useViewMode.ts` hook                                                                                |
| `getSsoConfig()`                              | GET `/auth/sso/config`                         | `LegalPages.tsx::OrgSettingsPage`                                                                    |
| `updateSsoConfig(body)`                       | PUT `/auth/sso/config`                         | `LegalPages.tsx::OrgSettingsPage`                                                                    |
| `clearSsoConfig()`                            | DELETE `/auth/sso/config`                      | `LegalPages.tsx::OrgSettingsPage`                                                                    |
| `listOnboardingSamples()`                     | GET `/onboarding/sample-specs`                 | `AuthPages.tsx::WelcomePage`                                                                         |
| `pickOnboardingSample(sampleId)`              | POST `/onboarding/pick-sample`                 | `AuthPages.tsx::WelcomePage`                                                                         |
| `getSandboxStatus()`                          | GET `/onboarding/sandbox-status`               | `AuthPages.tsx::WelcomePage`                                                                         |

OAuth2 redirect flow: `/api/auth/oauth2/init?surface=signal&provider=google` and `/api/auth/oauth2/authorize/{google,github}` are full-page redirects (not `fetch`), handled in `AuthPages.tsx::LoginPage` via `window.location.href`. The callback comes back to `AuthCallbackPage.tsx`.

### 3.3 Organization + members

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getMyOrg()`                                  | GET `/org/me`                                  | `LegalPages.tsx::OrgSettingsPage`, `MiscAccountPages.tsx::OrgMembersPage`, `project/ProjectMembersPage.tsx` |
| `createOrg(body)`                             | POST `/org`                                    | `AccountPage.tsx` (new-org flow)                                                                     |
| `updateOrg(body)`                             | PUT `/org/me`                                  | `LegalPages.tsx::OrgSettingsPage`                                                                    |
| `adjustOrgSeats(newSeats)`                    | POST `/org/me/seats/adjust`                    | not yet wired                                                                                        |
| `updateOrgAiKeySettings(anthropic, openai, gemini)` | PUT `/org/me/settings/ai-keys`           | `MiscAccountPages.tsx::ApiKeysPage` (ORG_ADMIN path)                                                 |
| `updateOrgKeySourceSettings(keySourceMode, enabledProviders)` | PUT `/org/me/settings/key-source` | `MiscAccountPages.tsx::ApiKeysPage` (BYOK toggle)                                                    |
| `updateExternalSharing(enabled)`              | PUT `/org/me/settings/external-sharing`        | `LegalPages.tsx::OrgSettingsPage` (V198 toggle)                                                      |
| `updateOrgJiraSettings(...)`                  | PUT `/org/me/settings/jira`                    | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `validateOrgJira(...)`                        | POST `/org/me/settings/jira/validate`          | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `updateOrgConfluenceSettings(...)`            | PUT `/org/me/settings/confluence`              | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `validateOrgConfluence(...)`                  | POST `/org/me/settings/confluence/validate`    | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `getOrgMembers()`                             | GET `/org/me/members`                          | `MiscAccountPages.tsx::OrgMembersPage`                                                               |
| `removeMember(userId)`                        | DELETE `/org/me/members/{userId}`              | `MiscAccountPages.tsx::OrgMembersPage`                                                               |
| `changeMemberRole(userId, role)`              | PUT `/org/me/members/{userId}/role`            | `MiscAccountPages.tsx::OrgMembersPage`                                                               |
| `orgSuspendMember(userId, suspended)`         | PUT `/org/me/members/{userId}/suspend`         | not yet wired                                                                                        |
| `inviteMember(body)`                          | POST `/org/invite`                             | `MiscAccountPages.tsx::OrgMembersPage`                                                               |
| `getOrgInvitations()`                         | GET `/org/me/invitations`                      | `MiscAccountPages.tsx::OrgMembersPage`                                                               |
| `resendInvitation(id)`                        | POST `/org/me/invitations/{id}/resend`         | `MiscAccountPages.tsx::OrgMembersPage`                                                               |
| `acceptInvite(token)`                         | POST `/org/accept-invite?token=`               | `ExtraPages.tsx::AcceptInvitePage`                                                                   |

### 3.4 Project workspace

The project-scoped surface is the V3 multi-repo flow. Controllers spread across `project/*`, `codeintel/*`, `memory/*`, `contracts/*`, `standards/*`, `adr/*`, `arch/invariants/*`, `activity/*`. The Signal `ProjectTabs.tsx` strip drives all of them.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getProjects()`                               | GET `/projects`                                | `ProjectsPage.tsx`, `ComposePage.tsx`, `HomePage.tsx`, `MiscPages.tsx::SearchPage`, `MiscPages.tsx::MorePage`, `ExtraPages.tsx::SpecTemplatesPage` |
| `getProject(id)`                              | GET `/projects/{id}`                           | `useProjectMeta` hook (every project-scoped page)                                                    |
| `createProject(body)`                         | POST `/projects`                               | `ProjectsPage.tsx`                                                                                   |
| `updateProjectSettings(id, body)`             | PATCH `/projects/{id}/settings`                | `project/ProjectSettingsPage.tsx`                                                                    |
| `archiveProject(id)`                          | PUT `/projects/{id}/archive`                   | `project/ProjectSettingsPage.tsx`                                                                    |
| `unarchiveProject(id)`                        | PUT `/projects/{id}/unarchive`                 | `ProjectsPage.tsx` (Archived tab)                                                                    |
| `deleteProject(id)`                           | DELETE `/projects/{id}`                        | `project/ProjectSettingsPage.tsx`                                                                    |
| `restoreProject(id)`                          | POST `/projects/{id}/restore`                  | `ProjectsPage.tsx`                                                                                   |
| `getProjectDashboard(projectId)`              | GET `/projects/{projectId}/dashboard`          | `project/ProjectPulsePage.tsx`, `project/ProjectsPage.tsx`, `project/ProjectRunsPage.tsx`, `project/ProjectTabs.tsx` |
| `getProjectScores(projectId)`                 | GET `/projects/{projectId}/scores`             | not yet wired into a Signal page                                                                     |
| `getProjectMemory(projectId)`                 | GET `/projects/{projectId}/memory`             | `project/ProjectChatPage.tsx` (30s poll), `project/ProjectSettingsPage.tsx` (Memory sub-tab)         |
| `getProjectLedger(projectId)`                 | GET `/projects/{projectId}/memory/ledger`      | not yet wired                                                                                        |
| `compactProjectMemory(projectId)`             | POST `/projects/{projectId}/memory/compact`    | `project/ProjectSettingsPage.tsx`                                                                    |
| `getActivityFeed(projectId, opts?)`           | GET `/projects/{projectId}/activity`           | `project/ProjectActivityPage.tsx` (30s poll)                                                         |
| `getWorkspaceHealth(projectId)`               | GET `/projects/{projectId}/workspace-health`   | `project/ProjectPulsePage.tsx`, `project/ProjectReposPage.tsx`, `project/ProjectCodePage.tsx`        |
| `getPostMergeBar(projectId)`                  | GET `/projects/{projectId}/post-merge-bar`     | not yet wired                                                                                        |
| `dismissPostMergeBar(projectId)`              | DELETE `/projects/{projectId}/post-merge-bar`  | not yet wired                                                                                        |
| `refreshProjectPrs(projectId)`                | POST `/projects/{projectId}/prs/refresh`       | `project/ProjectRunsPage.tsx`                                                                        |
| `estimateRun(projectId, requirement)`         | GET `/projects/{projectId}/estimate`           | `ComposePage.tsx`, `project/ProjectComposePage.tsx` (debounced)                                      |
| `getProjectSummary(projectId)`                | GET `/projects/{projectId}/summary`            | not yet wired                                                                                        |
| `listProjectDocuments(projectId)`             | GET `/projects/{projectId}/documents`          | `project/ProjectSettingsPage.tsx` (Documents sub-tab)                                                |
| `uploadProjectDocument(projectId, file)`      | POST `/projects/{projectId}/documents` (multipart) | `project/ProjectSettingsPage.tsx` — **bug**: reads `localStorage['token']` not `'access_token'`  |
| `deleteProjectDocument(projectId, documentId)` | DELETE `/projects/{projectId}/documents/{documentId}` | `project/ProjectSettingsPage.tsx`                                                              |
| `getProjectMembers(projectId)`                | GET `/projects/{projectId}/members`            | `project/ProjectMembersPage.tsx`, `project/ProjectTabs.tsx`                                          |
| `addProjectMember(projectId, userId, role)`   | POST `/projects/{projectId}/members`           | not yet wired (Signal uses invite-by-email instead)                                                  |
| `removeProjectMember(projectId, userId)`      | DELETE `/projects/{projectId}/members/{userId}` | `project/ProjectMembersPage.tsx`                                                                     |

#### Architecture pipeline

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getArchitectureDoc(projectId)`               | GET `/projects/{projectId}/architecture`       | `project/ProjectArchitecturePage.tsx`                                                                |
| `getArchitectureVersions(projectId)`          | GET `/projects/{projectId}/architecture/versions` | `project/ProjectArchitecturePage.tsx`                                                             |
| `annotateArchitecture(projectId, annotation)` | PUT `/projects/{projectId}/architecture/annotate` | not yet wired                                                                                     |
| `generateArchitecture(projectId)`             | POST `/projects/{projectId}/architecture/generate` | `project/ProjectArchitecturePage.tsx`                                                            |
| `analyseProject(projectId, branch?)`          | POST `/projects/{projectId}/analyse`           | not yet wired                                                                                        |
| `getArchitectureDiff(projectId, from, to)`    | GET `/projects/{projectId}/architecture/diff`  | `project/ProjectArchitecturePage.tsx`                                                                |
| `getArchitectureStaleness(projectId)`         | GET `/projects/{projectId}/architecture/staleness` | `project/ProjectArchitecturePage.tsx`                                                            |

#### Repos (per project)

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `listRepos(scope?)`                           | GET `/repos?scope=`                            | `project/ProjectReposPage.tsx`                                                                       |
| `listGitHubRepos(owner?)`                     | GET `/repos/github`                            | not yet wired                                                                                        |
| `createRepo(data)`                            | POST `/repos`                                  | not yet wired                                                                                        |
| `deleteRepo(repoId)`                          | DELETE `/repos/{repoId}`                       | not yet wired                                                                                        |
| `syncRepo(repoId)`                            | POST `/repos/{repoId}/sync`                    | `project/ProjectReposPage.tsx`                                                                       |
| `installRepoWebhook(repoId)`                  | POST `/repos/{repoId}/webhook`                 | `project/ProjectReposPage.tsx`                                                                       |
| `getProjectRepos(projectId)`                  | GET `/projects/{projectId}/repos`              | `ComposePage.tsx`, `project/ProjectComposePage.tsx`, `project/ProjectReposPage.tsx`, `project/ProjectPulsePage.tsx`, `project/ProjectCodePage.tsx`, `project/ProjectArchitecturePage.tsx`, `project/ProjectTabs.tsx` |
| `linkProjectRepo(projectId, repoId)`          | POST `/projects/{projectId}/repos/{repoId}`    | `project/ProjectReposPage.tsx`                                                                       |
| `unlinkProjectRepo(projectId, repoId)`        | DELETE `/projects/{projectId}/repos/{repoId}`  | `project/ProjectReposPage.tsx`                                                                       |
| `updateRepoRelationships(projectId, repoId, relationships)` | PUT `/projects/{projectId}/repos/{repoId}/relationships` | not yet wired                                                              |
| `listRepoFiles(projectId, repoId)`            | GET `/projects/{projectId}/repos/{repoId}/files` | `project/ProjectCodePage.tsx::BrowseTab`                                                          |
| `readRepoFile(projectId, repoId, path)`       | GET `/projects/{projectId}/repos/{repoId}/file` | `project/ProjectCodePage.tsx::BrowseTab`                                                           |
| `getRepoCodeMap(projectId, repoId)`           | GET `/projects/{projectId}/repos/{repoId}/codemap` | not yet wired                                                                                    |

#### Contracts, standards, ADRs, invariants (enterprise readiness)

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getContractCoverage(projectId, repoId)`      | GET `/projects/{projectId}/repos/{repoId}/contract-coverage` | `project/ProjectArchitecturePage.tsx`                                                  |
| `rescanContractCoverage(projectId, repoId)`   | POST `/projects/{projectId}/repos/{repoId}/contract-coverage/rescan` | `project/ProjectArchitecturePage.tsx`                                          |
| `checkContractDrift(projectId, contractId)`   | POST `/projects/{projectId}/contracts/{contractId}/check-drift` | not yet wired                                                                         |
| `checkRepoDrift(projectId, repoId)`           | POST `/projects/{projectId}/repos/{repoId}/check-drift` | `project/ProjectArchitecturePage.tsx`                                                         |
| `checkProjectDrift(projectId)`                | POST `/projects/{projectId}/check-drift`       | `project/ProjectArchitecturePage.tsx`                                                                |
| `refreshContractFromSource(projectId, contractId)` | POST `/projects/{projectId}/contracts/{contractId}/refresh-from-source` | not yet wired                                                            |
| `dismissContractDrift(projectId, contractId, rationale)` | POST `/projects/{projectId}/contracts/{contractId}/dismiss-drift` | not yet wired                                                            |
| `getStandardsReport(projectId, orgId, sinceDays?)` | GET `/projects/{projectId}/standards/report` | `project/ProjectCodePage.tsx` (Standards sub-tab)                                                  |
| `listProjectAdrs(projectId, acceptedOnly?)`   | GET `/projects/{projectId}/adrs`               | `project/ProjectArchitecturePage.tsx`                                                                |
| `getProjectAdr(projectId, id)`                | GET `/projects/{projectId}/adrs/{id}`          | not yet wired                                                                                        |
| `listProjectInvariants(projectId)`            | GET `/projects/{projectId}/invariants`         | `project/ProjectArchitecturePage.tsx`                                                                |
| `listProjectInvariantViolations(projectId)`   | GET `/projects/{projectId}/invariants/violations` | `project/ProjectArchitecturePage.tsx`                                                             |
| `listOrgStandards(orgId)`                     | GET `/orgs/{orgId}/standards`                  | not yet wired                                                                                        |
| `createOrgStandard(orgId, body)`              | POST `/orgs/{orgId}/standards`                 | not yet wired                                                                                        |
| `updateOrgStandard(orgId, standardId, body)`  | PUT `/orgs/{orgId}/standards/{standardId}`     | not yet wired                                                                                        |
| `deleteOrgStandard(orgId, standardId)`        | DELETE `/orgs/{orgId}/standards/{standardId}`  | not yet wired                                                                                        |

#### Tech debt + generated docs

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getTechDebt(projectId, sinceDays?)`          | GET `/projects/{projectId}/tech-debt`          | `project/ProjectCodePage.tsx`                                                                        |
| `dispatchTechDebtFix(projectId, category)`    | POST `/projects/{projectId}/tech-debt/fix`     | `project/ProjectCodePage.tsx`                                                                        |
| `generateProjectDoc(projectId, docType)`      | POST `/projects/{projectId}/docs/{docType}/generate` | `project/ProjectCodePage.tsx` (Docs sub-tab)                                                  |
| `getProjectDoc(projectId, docType)`           | GET `/projects/{projectId}/docs/{docType}`     | `project/ProjectCodePage.tsx`                                                                        |
| `downloadProjectDoc(projectId, docType)`      | GET `/projects/{projectId}/docs/{docType}/download` (blob) | `project/ProjectCodePage.tsx`                                                            |

#### Project chat

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getProjectChatInfo(projectId)`               | GET `/projects/{projectId}/chat/info`          | `project/ProjectChatPage.tsx` (also detects 403 → "Upgrade card" gate)                               |
| `compactProjectChat(projectId)`               | POST `/projects/{projectId}/chat/compact`      | `project/ProjectChatPage.tsx`                                                                        |
| `clearProjectChat(projectId)`                 | DELETE `/projects/{projectId}/chat`            | `project/ProjectChatPage.tsx`                                                                        |
| `compileProjectChatToSpec(projectId)`         | POST `/projects/{projectId}/chat/compile`      | `project/ProjectChatPage.tsx`                                                                        |

Note: the send-message call (`POST /api/projects/{id}/chat`) is not currently wrapped in `lib/api.ts`. `ProjectChatPage.tsx` uses a raw `fetch(...)` with manual `Authorization` header. Same pattern for `MiscPages.tsx::AdminPage`'s reconcile call, `ProjectMembersPage.tsx`'s invite + share-link calls, `ProjectCodePage.tsx`'s code-reviews submit, `BillingPage.tsx`'s payment-history call, and `ProjectSettingsPage.tsx`'s extended settings PATCH. These are gaps the contract test (section 6) flags.

#### Project sharing (V175 + V198)

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `createProjectInvite(projectId, body)`        | POST `/projects/{projectId}/invites`           | typed wrapper exists; `project/ProjectMembersPage.tsx` currently calls via raw `fetch`                |
| `listProjectInvites(projectId)`               | GET `/projects/{projectId}/invites`            | `project/ProjectMembersPage.tsx`                                                                     |
| `revokeProjectInvite(projectId, inviteId)`    | DELETE `/projects/{projectId}/invites/{inviteId}` | `project/ProjectMembersPage.tsx`                                                                  |
| `previewProjectInvite(token)`                 | GET `/invites/preview`                         | `ExtraPages.tsx::AcceptInvitePage`                                                                   |
| `acceptProjectInvite(token)`                  | POST `/invites/accept`                         | `ExtraPages.tsx::AcceptInvitePage`                                                                   |
| `createShareLink(projectId, body)`            | POST `/projects/{projectId}/share-links`       | `project/ProjectMembersPage.tsx`                                                                     |
| `listShareLinks(projectId)`                   | GET `/projects/{projectId}/share-links`        | `project/ProjectMembersPage.tsx`                                                                     |
| `revokeShareLink(projectId, linkId)`          | DELETE `/projects/{projectId}/share-links/{linkId}` | `project/ProjectMembersPage.tsx`                                                                |
| `getSharedReport(shareToken)`                 | GET `/reports/shared/{shareToken}` (no auth)   | `MiscPages.tsx::SharedPage`                                                                          |
| `shareLinkPublicUrl(token)`                   | (local URL builder, no HTTP)                   | `project/ProjectMembersPage.tsx`                                                                     |

### 3.5 Validation reports + tests

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getValidationReport(runId)`                  | GET `/runs/{runId}/report`                     | `ResultPage.tsx`, `project/ProjectRunsPage.tsx`                                                      |
| `downloadValidationReportPdf(runId)`          | GET `/runs/{runId}/report/pdf` (blob)          | `ResultPage.tsx`                                                                                     |
| `shareValidationReport(runId)`                | POST `/runs/{runId}/report/share`              | `ResultPage.tsx`                                                                                     |
| `getTestReport(runId)`                        | GET `/runs/{runId}/test-report`                | `ResultPage.tsx` (Tests tab)                                                                         |
| `getTestPlan(runId)`                          | GET `/runs/{runId}/test-plan`                  | not yet wired                                                                                        |
| `getTestGraph(runId)`                         | GET `/runs/{runId}/test-graph`                 | not yet wired                                                                                        |
| `markTestInvalid(testResultId)`               | PUT `/test-results/{testResultId}/mark-invalid` | `ResultPage.tsx`                                                                                    |
| `applyTestFixes(runId, signature?)`           | POST `/runs/{runId}/apply-test-fixes`          | `ResultPage.tsx`                                                                                     |
| `runTests(runId)`                             | POST `/runs/{runId}/run-tests`                 | `ResultPage.tsx` (manual re-test button)                                                             |

### 3.6 PR comments + reviews

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getPrComments(runId)`                        | GET `/runs/{runId}/pr-comments`                | `ResultPage.tsx`                                                                                     |
| `getPrCommentPendingCount(runId)`             | GET `/runs/{runId}/pr-comments/pending-count`  | `ResultPage.tsx`                                                                                     |
| `dismissPrComment(runId, commentId)`          | POST `/runs/{runId}/pr-comments/{commentId}/dismiss` | `ResultPage.tsx`                                                                               |
| `applyPrComment(runId, commentId)`            | POST `/runs/{runId}/pr-comments/{commentId}/apply` | `ResultPage.tsx`                                                                                 |
| `fetchPrReviewComments(projectId, prNumber, repoFullName)` | GET `/projects/{projectId}/prs/{prNumber}/reviews` | `project/ProjectRunsPage.tsx`                                                          |
| `submitPrReview(body)`                        | POST `/pr-reviews`                             | not yet wired (would land on `ProjectRunsPage` PR tab)                                               |
| `getPrReview(id)`                             | GET `/pr-reviews/{id}`                         | not yet wired                                                                                        |
| `listPrReviewsForPr(repoFullName, prNumber)`  | GET `/pr-reviews`                              | not yet wired                                                                                        |
| `listPrReviewsForProject(projectId)`          | GET `/projects/{projectId}/pr-reviews`         | not yet wired                                                                                        |
| `dispatchPrReviewAutofix(reviewId)`           | POST `/pr-reviews/{reviewId}/autofix`          | not yet wired (Phase 1 autofix surface)                                                              |
| `submitCodeReview(body)`                      | POST `/code-reviews`                           | `project/ProjectCodePage.tsx` (currently via raw fetch)                                              |
| `getCodeReview(id)`                           | GET `/code-reviews/{id}`                       | not yet wired                                                                                        |
| `listCodeReviewsForProject(projectId)`        | GET `/projects/{projectId}/code-reviews`       | not yet wired                                                                                        |
| `getLatestCodeReviewForRepo(projectId, repoFullName)` | GET `/projects/{projectId}/code-reviews/latest` | `project/ProjectCodePage.tsx` (currently via raw fetch)                                  |
| `codeReviewReportDownloadUrl(id)`             | (local URL builder)                            | not yet wired                                                                                        |
| `dispatchCodeReviewAutofix(scanId, severity)` | POST `/code-reviews/{scanId}/autofix`          | not yet wired (Phase 4 autofix surface)                                                              |

### 3.7 Library + migrations

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `listLibrary(filters?)`                       | GET `/library`                                 | not yet wired into Signal (the demo Library page in `ExtraPages.tsx` uses a hard-coded list)         |
| `getLibraryDetail(slug)`                      | GET `/library/{slug}`                          | not yet wired                                                                                        |
| `getLibraryFacets()`                          | GET `/library/categories`                      | not yet wired                                                                                        |
| `resolveLibraryVariant(slug, projectId, repoId)` | POST `/library/{slug}/resolve`              | not yet wired                                                                                        |
| `instantiateLibrarySolution(slug, body)`      | POST `/library/{slug}/instantiate`             | not yet wired                                                                                        |
| `favouriteLibrarySolution(slug)`              | POST `/library/favourite/{slug}`               | not yet wired                                                                                        |
| `unfavouriteLibrarySolution(slug)`            | DELETE `/library/favourite/{slug}`             | not yet wired                                                                                        |
| `listMigrations(filters?)`                    | GET `/migrations`                              | not yet wired into Signal (`MigrationsPage` in `ExtraPages.tsx` is hard-coded)                       |
| `getMigrationDetail(slug)`                    | GET `/migrations/{slug}`                       | not yet wired                                                                                        |
| `migrationDryRun(slug, body)`                 | POST `/migrations/{slug}/dry-run`              | not yet wired                                                                                        |
| `migrationApply(slug, body)`                  | POST `/migrations/{slug}/apply`                | not yet wired                                                                                        |

Both `LibraryPage` / `MigrationsPage` / their detail variants ship today with static demo data — flagged BETA via `Shell.tsx` sidebar badges. The typed `api.*` wrappers exist in anticipation of the backend going live.

### 3.8 Spec templates

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `listSpecTemplates(projectId)`                | GET `/projects/{projectId}/templates`          | `ExtraPages.tsx::SpecTemplatesPage`                                                                  |
| `createProjectSpecTemplate(projectId, body)`  | POST `/projects/{projectId}/templates`         | `ExtraPages.tsx::SpecTemplatesPage`                                                                  |
| `createOrgSpecTemplate(orgId, body)`          | POST `/orgs/{orgId}/templates`                 | not yet wired                                                                                        |
| `deleteSpecTemplate(projectId, templateId)`   | DELETE `/projects/{projectId}/templates/{templateId}` | `ExtraPages.tsx::SpecTemplatesPage`                                                           |

### 3.9 Deployments + previews

Controllers: `DeploymentController.java` (gated by `agent.deployment.enabled`), `PreviewController.java`.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getDeployments()`                            | GET `/deployments`                             | `ExtraPages.tsx::DeploymentsPage`                                                                    |
| `createDeployment(body)`                      | POST `/deployments`                            | not yet wired in Signal                                                                              |
| `getDeploymentDetail(id)`                     | GET `/deployments/{id}`                        | not yet wired                                                                                        |
| `deleteDeployment(id)`                        | DELETE `/deployments/{id}`                     | `ExtraPages.tsx::DeploymentsPage`                                                                    |
| `rollbackDeployment(id)`                      | POST `/deployments/{id}/rollback`              | `ExtraPages.tsx::DeploymentsPage`                                                                    |
| `analyzeForDeploy(runId)`                     | POST `/deployments/analyze`                    | not yet wired                                                                                        |
| `confirmDeploy(body)`                         | POST `/deployments/confirm`                    | not yet wired                                                                                        |
| `engineRollback(id)`                          | POST `/deployments/{id}/engine-rollback`       | not yet wired                                                                                        |
| `engineDestroy(id)`                           | DELETE `/deployments/{id}/engine-destroy`      | not yet wired                                                                                        |
| `getRequiredPermissions()`                    | GET `/credentials/gcp/required-permissions`    | not yet wired                                                                                        |
| `storeGcpCredential(json, projectId?)`        | POST `/credentials/gcp`                        | not yet wired                                                                                        |
| `getGcpCredential(projectId?)`                | GET `/credentials/gcp`                         | not yet wired                                                                                        |
| `listGcpCredentials()`                        | GET `/credentials/gcp/all`                     | not yet wired                                                                                        |
| `validateGcpCredential(projectId?)`           | GET `/credentials/gcp/validate`                | not yet wired                                                                                        |
| `deleteGcpCredential(projectId?)`             | DELETE `/credentials/gcp`                      | not yet wired                                                                                        |
| `analyzePreview(runId)`                       | GET `/runs/{runId}/preview/analyze`            | `ResultPage.tsx`                                                                                     |
| `getPreviewStatus(runId)`                     | GET `/runs/{runId}/preview/status`             | `ResultPage.tsx`                                                                                     |
| `startPreview(runId, envVars?)`               | POST `/runs/{runId}/preview/start`             | `ResultPage.tsx`                                                                                     |
| `stopPreview(runId)`                          | DELETE `/runs/{runId}/preview/stop`            | `ResultPage.tsx`                                                                                     |
| `rebuildPreview(runId)`                       | POST `/runs/{runId}/preview/rebuild`           | `ResultPage.tsx`                                                                                     |
| `getPreviewLogs(runId, lines?, filter?)`      | GET `/runs/{runId}/preview/logs`               | `ResultPage.tsx`                                                                                     |
| `execPreviewCommand(runId, cmd, args)`        | POST `/runs/{runId}/preview/exec`              | not yet wired                                                                                        |
| `probeApiDocs(runId)`                         | GET `/runs/{runId}/preview/probe-api-docs`     | `ResultPage.tsx`                                                                                     |

### 3.10 Billing + topup (`lib/billing-api.ts` + `lib/api.ts`)

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getPricing()` / `getAllPricing()`            | GET `/pricing`                                 | `PlansPage.tsx`                                                                                      |
| `updatePricing(provider, model, body)` / `updateModelPricing(...)` | PUT `/pricing/{provider}/{model}` | not yet wired into Signal (SUPER_ADMIN)                                                              |
| `fetchPlans(keyMode?)`                        | GET `/billing/plans`                           | not yet wired in Signal (the source-of-truth for PlansPage today)                                    |
| `fetchFounderOffer(tier)`                     | GET `/billing/founder-pricing/{tier}`          | not yet wired                                                                                        |
| `fetchBillingStatus()`                        | GET `/billing/status`                          | not yet wired                                                                                        |
| `subscribe(planId, gateway?, ...)`            | POST `/billing/subscribe`                      | `PlansPage.tsx` uses a raw `fetch('/api/billing/subscribe', POST)` instead of the typed helper       |
| `verifyPayment(...)`                          | POST `/billing/verify`                         | not yet wired                                                                                        |
| `cancelSubscription()`                        | POST `/billing/cancel`                         | not yet wired                                                                                        |
| `updateSeats(totalSeats)`                     | POST `/billing/seats`                          | not yet wired                                                                                        |
| `fetchTopupPackages()` / `adminListTopupPackages()` | GET `/billing/topup/packages` / `/admin/topup-packages` | `MiscAccountPages.tsx::TopupPage`                                              |
| `purchaseTopup(packageId)`                    | POST `/billing/topup/purchase`                 | `MiscAccountPages.tsx::TopupPage` uses raw `fetch(...)`                                              |
| `verifyTopup(...)`                            | POST `/billing/topup/verify`                   | not yet wired                                                                                        |
| `getAnalyticsBilling(entityType, entityId)`   | GET `/admin/analytics/billing`                 | `BillingPage.tsx`                                                                                    |
| `getAnalyticsUsage(entityType, entityId, period?)` | GET `/admin/analytics/usage`              | `BillingPage.tsx`                                                                                    |
| `getAnalyticsMembers(orgId, period?)`         | GET `/admin/analytics/members`                 | not yet wired                                                                                        |

Note: Razorpay vs Stripe gateway selection is routed server-side by `PaymentGatewayRouter`; the frontend only passes `{gateway: 'stripe'|'razorpay'}` on subscribe.

### 3.11 Invoices + accounting (`lib/invoice-api.ts`)

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getBillingProfile()` / `updateBillingProfile(...)` | GET / PUT `/billing/profile`              | not yet wired in Signal                                                                              |
| `getMyInvoices(page?, size?)`                 | GET `/billing/invoices`                        | `BillingPage.tsx`                                                                                    |
| `getInvoiceDetail(id)`                        | GET `/billing/invoices/{id}`                   | not yet wired                                                                                        |
| `getAllInvoices(page?, size?)`                | GET `/admin/invoices`                          | not yet wired                                                                                        |
| `resendInvoice(invoiceId)`                    | POST `/admin/invoices/{invoiceId}/send`        | not yet wired                                                                                        |
| `getSellerConfig()` / `updateSellerConfig(...)` | GET / PUT `/admin/seller-config`             | not yet wired                                                                                        |
| `getRevenueMetrics(from, to)`                 | GET `/accounting/revenue`                      | not yet wired                                                                                        |
| `getGstSummary(fy, quarter)`                  | GET `/accounting/gst-summary`                  | not yet wired                                                                                        |
| `getTrialBalance(from, to)`                   | GET `/accounting/trial-balance`                | not yet wired                                                                                        |
| `downloadInvoicePdf(id, invoiceNumber, admin?)` | GET `/{admin|billing}/invoices/{id}/pdf`     | not yet wired                                                                                        |
| `getGstr1ExportUrl(fy, quarter)`              | (URL builder for direct download)              | not yet wired                                                                                        |

### 3.12 Integrations (Slack + Jira + Confluence + GitHub App)

`api.ts` exposes a single canonical set; `SlackIntegrationController` mounts at `/api/integrations/slack/*` and is gated by `agent.integrations.slack.enabled`.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getIntegrationStatus()`                      | GET `/integrations/status`                     | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `getIntegrationRuns(source?)`                 | GET `/integrations/runs`                       | not yet wired                                                                                        |
| `triggerConfluence(body)`                     | POST `/integrations/confluence/trigger`        | not yet wired                                                                                        |
| `triggerJira(body)`                           | POST `/integrations/jira/trigger`              | not yet wired                                                                                        |
| `getSlackStatus()`                            | GET `/integrations/slack/status`               | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `getSlackInstallUrl()`                        | GET `/integrations/slack/install`              | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `disconnectSlack()`                           | DELETE `/integrations/slack/disconnect`        | `MiscAccountPages.tsx::IntegrationsPage`                                                             |
| `listSlackChannels()`                         | GET `/integrations/slack/channels`             | not yet wired (admin-only)                                                                           |
| `getProjectSlackChannels(projectId)`          | GET `/integrations/slack/projects/{projectId}/channels` | not yet wired                                                                               |
| `addProjectSlackChannel(...)`                 | POST `/integrations/slack/projects/{projectId}/channels` | not yet wired                                                                              |
| `removeProjectSlackChannel(projectId, channelId)` | DELETE `/integrations/slack/projects/{projectId}/channels/{channelId}` | not yet wired                                                       |
| `updateSlackChannelVerbosity(...)`            | PUT `/integrations/slack/projects/{projectId}/channels/{channelId}/verbosity` | not yet wired                                                  |
| `sendSlackTestMessage(channelId)`             | POST `/integrations/slack/test`                | not yet wired                                                                                        |

### 3.13 Admin (`/admin/*` — SUPER_ADMIN)

Most consumed by `MiscPages.tsx::AdminPage`.

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `adminListUsers()`                            | GET `/admin/users`                             | `MiscPages.tsx::AdminPage`                                                                           |
| `adminGetUser(id)`                            | GET `/admin/users/{id}`                        | not yet wired                                                                                        |
| `adminUpdateUserStatus(id, status)`           | PUT `/admin/users/{id}/status`                 | not yet wired                                                                                        |
| `adminUpdateUserTier(id, tier)`               | PUT `/admin/users/{id}/tier`                   | not yet wired                                                                                        |
| `adminUpdateUserRole(id, role)`               | PUT `/admin/users/{id}/role`                   | not yet wired                                                                                        |
| `haltAllActiveRuns(reason?)`                  | POST `/admin/runs/halt-all`                    | not yet wired                                                                                        |
| `verifyAuditChain()`                          | GET `/admin/audit-chain/verify`                | not yet wired                                                                                        |
| `getActivationFunnel(sinceDays?)`             | GET `/admin/activation-funnel`                 | `MiscPages.tsx::AdminPage`                                                                           |
| `adminListOrgs()`                             | GET `/admin/orgs`                              | `MiscPages.tsx::AdminPage`                                                                           |
| `adminUpdateOrgTier(id, tier)`                | PUT `/admin/orgs/{id}/tier`                    | not yet wired                                                                                        |
| `adminUpdateOrgStatus(id, status)`            | PUT `/admin/orgs/{id}/status`                  | not yet wired                                                                                        |
| `adminUpdateOrgRunLimit(id, limit)`           | PUT `/admin/orgs/{id}/run-limit`               | not yet wired                                                                                        |
| `adminUpdateOrgApiKeys(id, provided)`         | PUT `/admin/orgs/{id}/api-keys`                | not yet wired                                                                                        |
| `adminListEscalated()`                        | GET `/admin/invitations/escalated`             | not yet wired                                                                                        |
| `adminApproveInvite(id)`                      | POST `/admin/invitations/{id}/approve`         | not yet wired                                                                                        |
| `adminRejectInvite(id)`                       | POST `/admin/invitations/{id}/reject`          | not yet wired                                                                                        |
| `adminGetTierConfig()`                        | GET `/admin/tier-config`                       | not yet wired                                                                                        |
| `adminUpdateTierConfig(tier, monthlyRunLimit)` | PUT `/admin/tier-config/{tier}`               | not yet wired                                                                                        |
| `adminInviteUser(body)`                       | POST `/admin/invite`                           | not yet wired                                                                                        |
| `adminGrantUserRuns(userId, qty, expiryDays?, reason?)` | POST `/admin/users/{userId}/grant-runs` | not yet wired                                                                                  |
| `adminGrantOrgRuns(orgId, qty, expiryDays?, reason?)` | POST `/admin/orgs/{orgId}/grant-runs`   | not yet wired                                                                                  |
| `fetchTiersLifecycle()`                       | GET `/admin/tiers`                             | `MiscPages.tsx::AdminPage`                                                                           |
| `updateTierLifecycle(tier, body)`             | PUT `/admin/tiers/{tier}/lifecycle`            | not yet wired                                                                                        |
| `adminCreateTopupPackage(body)`               | POST `/admin/topup-packages`                   | not yet wired                                                                                        |
| `adminUpdateTopupPackage(id, body)`           | PUT `/admin/topup-packages/{id}`               | not yet wired                                                                                        |
| `adminDeleteTopupPackage(id)`                 | DELETE `/admin/topup-packages/{id}`            | not yet wired                                                                                        |
| `adminTriggerUsageReport(period)`             | POST `/admin/usage-reports/trigger`            | not yet wired                                                                                        |
| `adminListFeatureFlags()`                     | GET `/admin/feature-flags`                     | `MiscPages.tsx::AdminPage`                                                                           |
| `getPublicFeatureFlags()`                     | GET `/admin/feature-flags/public`              | not yet wired                                                                                        |
| `adminUpdateFeatureFlag(key, value)`          | PUT `/admin/feature-flags/{key}`               | not yet wired                                                                                        |
| `adminListPaymentAudit(page?, size?, orgId?, userId?)` | GET `/admin/payment-audit`            | not yet wired                                                                                        |
| `adminExportPaymentAuditCsv()`                | GET `/admin/payment-audit/export` (blob)       | not yet wired                                                                                        |
| `adminListWebhookEvents(page?, size?, gateway?)` | GET `/admin/webhook-events`                 | not yet wired                                                                                        |
| `getAuditLogs(params)`                        | GET `/admin/audit-logs` OR `/org/me/audit-logs` (depending on `orgScoped` param) | `ExtraPages.tsx::AuditLogPage`                                       |

### 3.14 Metrics + diagnostics

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getMetrics(days?)`                           | GET `/metrics`                                 | `ExtraPages.tsx::InsightsPage`                                                                       |
| `getModelComparison(days?)`                   | GET `/model-comparison`                        | `ExtraPages.tsx::InsightsPage`                                                                       |
| `getUserRunsSummary(days?)`                   | GET `/user-runs-summary`                       | `ExtraPages.tsx::InsightsPage`                                                                       |
| `getTimeline(runId)`                          | GET `/runs/{runId}/timeline`                   | `ResultPage.tsx`                                                                                     |
| `getAvailableModels()`                        | GET `/models/available`                        | `ComposePage.tsx`, `project/ProjectComposePage.tsx`                                                  |

### 3.15 Notifications

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `getNotifications(page?, size?)`              | GET `/notifications`                           | `MiscPages.tsx::NotificationsPage`                                                                   |
| `getUnreadCount()`                            | GET `/notifications/unread-count`              | `MiscPages.tsx::MorePage`                                                                            |
| `markNotificationRead(id)`                    | POST `/notifications/{id}/read`                | `MiscPages.tsx::NotificationsPage`                                                                   |
| `markAllNotificationsRead()`                  | POST `/notifications/read-all`                 | `MiscPages.tsx::NotificationsPage`                                                                   |
| `getNotificationPreferences()`                | GET `/notifications/preferences`               | `AccountPage.tsx`                                                                                    |
| `updateNotificationPreference(eventType, channel, enabled)` | PUT `/notifications/preferences` | `AccountPage.tsx`                                                                                    |

### 3.16 Misc + chat refinement

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `conversationChat(messages)`                  | POST `/conversation`                           | `SpecChat.tsx` (composer side-panel — available to FREE tier)                                        |
| `submitContactForm(subject, body)`            | POST `/contact`                                | not yet wired into a Signal page                                                                     |
| `getPullRequestSnapshot(repoFullName, prNumber, maxChars?)` | GET `/prs/snapshot`              | not yet wired                                                                                        |

### 3.17 Execution-run client (`lib/execution-api.ts`)

The V3 multi-repo execution surface lives under `executionApi.*`. These routes share the project namespace (not `/api/runs`).

| Function                                      | Verb + path                                    | Consumed by                                                                                          |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `executionApi.create(projectId, body)`        | POST `/projects/{projectId}/executions`        | `useExecutionStore.createExecution`                                                                  |
| `executionApi.get(id)`                        | GET `/executions/{id}`                         | `useExecutionStore.fetchExecution`                                                                   |
| `executionApi.list(projectId, page?, size?)`  | GET `/projects/{projectId}/executions`         | not yet wired into Signal                                                                            |
| `executionApi.triggerAnalysis(id)`            | POST `/executions/{id}/analyse`                | `useExecutionStore.triggerAnalysis`                                                                  |
| `executionApi.approveArch(id, body)`          | PUT `/executions/{id}/arch-review`             | `useExecutionStore.approveArch`                                                                      |
| `executionApi.generatePlan(id)`               | POST `/executions/{id}/plan`                   | `useExecutionStore.generatePlan`                                                                     |
| `executionApi.approvePlan(id)`                | PUT `/executions/{id}/plan/approve`            | `useExecutionStore.approvePlan`                                                                      |
| `executionApi.decide(id, solutionId, body)`   | POST `/executions/{id}/solutions/{solutionId}/decide` | `useExecutionStore.decide`                                                                     |
| `executionApi.signalManualReady(id, solutionId)` | POST `/executions/{id}/solutions/{solutionId}/manual-ready` | `useExecutionStore.signalManualReady`                                            |
| `executionApi.abort(id)`                      | POST `/executions/{id}/abort`                  | `useExecutionStore.abort`                                                                            |
| `executionApi.resume(id)`                     | POST `/executions/{id}/resume`                 | not yet wired                                                                                        |
| `executionApi.getReport(id)`                  | GET `/executions/{id}/report`                  | not yet wired                                                                                        |
| `executionApi.getSseToken(id)`                | GET `/executions/{id}/sse-token`               | `useExecutionSse.ts` hook                                                                            |
| `executionApi.listScaffolds()`                | GET `/scaffolds`                               | not yet wired                                                                                        |
| `executionApi.listSolutionRuns(id, solutionId)` | GET `/executions/{id}/solutions/{solutionId}/runs` | not yet wired                                                                                |
| `executionApi.listProjectLaunches(projectId, page?, size?)` | GET `/projects/{projectId}/launches` | not yet wired                                                                                  |
| `executionApi.listProjectPrs(projectId)`      | GET `/projects/{projectId}/prs`                | not yet wired                                                                                        |
| `executionApi.projectOverview(projectId)`     | GET `/projects/{projectId}/overview`           | not yet wired                                                                                        |
| `executionApi.validateOwner(projectId, githubOwner)` | POST `/projects/{projectId}/launches/validate-repo-access` | not yet wired                                                            |

### 3.18 V3 entitlements + referrals + anonymous trial

These live under `/api/v3/*` so V2 surface remains untouched. They are wired only into V3 chrome — not Signal.

| Path                                          | Controller                                     | Notes                                                                                                |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| GET `/v3/entitlements`                        | `EntitlementsController`                       | Single-source entitlements (`R-142`)                                                                 |
| POST `/v3/referrals/invite`                   | `ReferralController`                           | R-162 Free-tier growth loop                                                                          |
| GET `/v3/referrals/state`                     | `ReferralController`                           |                                                                                                      |
| GET `/v3/referrals/lookup?token=` (public)    | `ReferralController`                           | No auth — token-keyed                                                                                |
| POST `/v3/anonymous-runs`                     | `AnonymousRunController`                       | R-111 anonymous trial; rate-limited                                                                  |
| GET `/v3/anonymous-runs/{sessionToken}`       | `AnonymousRunController`                       |                                                                                                      |
| POST `/v3/auth/claim-anonymous-session`       | `SignupClaimController`                        | Binds anon session to authenticated user                                                             |

---

## 4. SSE handling

There are two parallel SSE surfaces today: the V2 run stream (Signal-consumed) and the V3 execution stream (V3 chrome only). Both follow the same pattern.

### 4.1 Token minting

The frontend never sends the user's full `access_token` on an SSE URL — it would leak into proxy access logs. Instead:

1. The page calls `api.getSseToken(runId)` → backend `SseController.getSseToken` mints a 5-min JWT with `scope=sse` and `runId=<id>` claims and returns it as `{token: "<jwt>"}`.
2. The page (or `useSse` hook) opens `new EventSource('/api/runs/{runId}/events?token=<jwt>')`.
3. Backend validates the scoped token, asserts `runId` claim matches `@PathVariable`, and starts emitting events.

The scoped token expires before the long-lived access token, limiting the damage if a stream URL leaks.

### 4.2 `useSse` hook (V2 runs)

`frontend/src/hooks/useSse.ts`:

- Pre-populates the message buffer by calling `api.getMessages(runId)` when a new `runId` is supplied — so the live log starts with history, not an empty box.
- Mints a scoped token via `api.getSseToken(runId)`.
- Opens `EventSource('/api/runs/{runId}/events?token=…')`.
- Dispatches typed events into `useSseStore`: `RUN_DETAIL`, `RUN_STATUS`, `GATE_OPENED`, `RUN_RESTARTED`, `BUDGET_EXCEEDED`, `BATCH_PROGRESS`, `BATCH_COMPLETE`, `BATCH_FAILED`, `FILE_WRITTEN`.
- Reconnect uses **exponential backoff**: `[1000, 2000, 4000, 8000, 16000, 30000]` ms. After 30s it stays at 30s and keeps trying. The `useSseStore` `status` field cycles `DISCONNECTED → CONNECTING → CONNECTED → RECONNECTING` so the UI can show a banner.
- Buffer cap: `useSseStore` keeps the last 500 entries; older ones are dropped on the way in.

Consumed by: `LivePage.tsx` (the main customer-facing live-watch surface).

ASCII view of the connection lifecycle:

```
   page mount        token mint          stream open
       |                 |                    |
       v                 v                    v
   [hook init] --> getSseToken(id) --> new EventSource(url?token=...)
                                              |
                                              v
                                       receive event ----> dispatch to useSseStore
                                              |
                                              v
                                   on('error') | on('close')
                                              |
                                              v
                                  schedule reconnect with BACKOFF[n]
                                              |
                                              v
                                       (loop until unmount)
```

### 4.3 `useExecutionSse` hook (V3 executions)

`frontend/src/hooks/useExecutionSse.ts`:

- Mints token via `executionApi.getSseToken(executionId)`.
- Opens `/api/executions/{id}/events?token=…`.
- Dispatches V3-specific events into `useExecutionStore`: `EXEC_REPO_CODEMAP_STARTED/DONE`, `EXEC_REPO_ARCH_STARTED/DONE`, `EXEC_ANALYSIS_COMPLETE`, `EXEC_PLANNING_STARTED`, `EXEC_PLAN_READY`, `EXEC_STARTED`, `EXEC_SOLUTION_RUNNING|VALIDATING|PR_RAISED|DONE|FAILED|SKIPPED|LOG`, `EXEC_FINAL_PR_CREATED`, `EXEC_COMPLETE`, `EXEC_ABORTED`, `EXEC_RESUMED`, `EXEC_COMPENSATION_UPDATE`, `EXEC_COMPENSATION_DONE`.
- Reconnect: simple 3s retry on error (V3 path predates the exponential backoff design from `useSse`).

Not consumed by any Signal page; only the V3 execution view uses it.

### 4.4 Backend resume-on-reconnect

`SseController.subscribe` accepts a `Last-Event-ID` header. When present, the broadcaster replays events from after that id so a reconnected client doesn't miss messages. Neither `useSse` nor `useExecutionSse` currently sends `Last-Event-ID` — that's a TODO if SSE drops become user-visible.

---

## 5. Polling cadences

Where SSE isn't enough (or isn't available), pages fall back to interval polling. All current polling sites and their intervals:

| Page / hook                                   | Endpoint                                       | Interval                                                                                             |
| --------------------------------------------- | ---------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `LivePage.tsx`                                | `api.getRun(runId)` + `api.getLiveCost(runId)` | **8 seconds** while run is non-terminal — safety net behind SSE                                      |
| `PlanPage.tsx`                                | `api.getRun(runId)`                            | **2 seconds** while status is CLASSIFYING / PLANNING; stops once the page navigates away             |
| `HomePage.tsx`                                | `api.getRun(liveRunId)`                        | **5 seconds** when a live run is present, **30 seconds** otherwise                                   |
| `project/ProjectChatPage.tsx`                 | `api.getProjectMemory(projectId)`              | **30 seconds**                                                                                       |
| `project/ProjectActivityPage.tsx`             | `api.getActivityFeed(projectId, {…})`          | **30 seconds**                                                                                       |
| `RunController.getLiveCost` (server-side cache) | n/a — TTL of the in-memory `costCache`        | **30 seconds** for non-terminal runs (terminal runs read straight from `RunCostTracker` snapshot)    |
| `HealthController` (server-side cache)        | n/a — health probe cache                       | **30 seconds** background refresh of DB / Redis / metrics                                            |

`PlanPage.tsx` deliberately uses the tightest interval because the plan gate is a hard-stop UX — the user is staring at the screen waiting to approve. `LivePage` widens to 8s because SSE is the primary channel; the poll is just a safety net for the case where the stream drops without recovering. The home page's 30s cadence is purely for the "last active run" hero; per-run pages own their own polling.

There is no global polling client — every page schedules its own `setInterval` and clears it in the `useEffect` cleanup. No `Page Visibility` API gating today, so a backgrounded tab still polls.

---

## 6. ApiContractTest — does it exist?

**No backend ArchUnit / contract test currently asserts that every URL string in `lib/api.ts` resolves to a real `@RequestMapping` on the backend.** The closest cousin is `AccessGuardCoverageTest` in `src/test/java/com/navneet/executespec/arch/`, which pins that every controller method whose URL contains `/runs/{`, `/projects/{`, `/executions/{`, `/launches/{`, or `/repos/{` calls a tenant access guard (with 10 explicitly-ledgered legacy sites in `KNOWN_LEGACY_DRIFT`).

What we rely on instead today:

- The frontend lists every endpoint in one file (`lib/api.ts`) so endpoints are diffable in PR review.
- TypeScript catches DTO shape drift at compile time when the response interface changes — but it doesn't catch URL typos or removed endpoints.
- `ApiError` propagates 404s from missing routes cleanly to the UI, so a broken call surfaces on first user click rather than silently doing nothing.

This is **technical debt**. A `tools/check-api-contract.ts` script that grep's `lib/api.ts` for `${BASE}/…` literals and crosschecks each against the Java `@*Mapping` annotations would catch this class of drift cheaply. Until that lands, the gaps below are the known soft spots:

- `uploadProjectDocument` reads `localStorage['token']` instead of `'access_token'` — silently unauthenticated uploads.
- `ValidationResult` is declared twice in `lib/api.ts` with different shapes (Deploy validators vs credential validators) — the second declaration masks the first.
- Several pages use raw `fetch` instead of the typed `api.*` wrapper: `ProjectMembersPage.tsx` (invites + share-links), `ProjectChatPage.tsx` (send message), `ProjectCodePage.tsx` (code-reviews list + submit), `MiscPages.tsx::AdminPage` (executions reconcile), `MiscAccountPages.tsx::TopupPage` (purchase), `PlansPage.tsx` (subscribe), `BillingPage.tsx` (payment-history), `ProjectSettingsPage.tsx` (extended settings PATCH), `AccountPage.tsx` (GDPR data-export). These are the most likely sources of future drift because the URL string isn't tracked anywhere typed.

When you add a contract test (recommended), it should also assert that every wrapper in the table above has a real `@*Mapping` and that every controller method has a wrapper (so a backend-only endpoint can't ship without a frontend call site, and vice versa).

---

## 7. Adding a new endpoint

The minimum checklist for landing a new endpoint without breaking either side:

1. **Backend** — add the controller method.

   - Pick the right controller package (`api/controller/` for cross-cutting, `project/*` for project-scoped, `billing/`, `prreview/`, etc.).
   - Use constructor injection via Lombok `@RequiredArgsConstructor`. **No** `@Autowired` on fields.
   - Annotate the path with the correct `@GetMapping` / `@PostMapping` / etc. — under `/api/<resource>` to match the frontend `BASE`.
   - If the path matches `/runs/{`, `/projects/{`, `/executions/{`, `/launches/{`, or `/repos/{`, call a tenant access guard (e.g. `runAccessGuard.assertCanRead(runId, userDetails)`). The build pins this via `AccessGuardCoverageTest`.
   - Validate inputs with `@Valid` on the body record. Add `@NotBlank` / `@Size` / `@Pattern` / `@Min` / `@Max` annotations so `ValidationAdvice` can produce a 400 with field-level error keys.
   - Throw `ResponseStatusException` for explicit non-200s (or let the appropriate exception bubble up — `ValidationAdvice` will map it).
   - Return a `record` DTO (or a Lombok `@Data @Builder` class if the DTO needs to evolve additively — `RunDetail` and `RunSummary` are precedent).

2. **Frontend** — add a wrapper in `lib/api.ts` (or the matching namespaced client).

   - Add a typed response interface near the related interfaces.
   - Add the function under the right domain section. Match the existing style: `async function`, calls `req<T>(url, opts?)`, encodes path params via template literals, encodes query params via `URLSearchParams`.
   - For blob downloads, return the raw `Blob` (see `downloadValidationReportPdf`, `downloadProjectDoc`, `adminExportPaymentAuditCsv` for shape).
   - Export from `lib/api.ts` (no default export — everything is named).

3. **Frontend** — consume from a page.

   - Prefer `useEffect` + `useState` for one-shot loads.
   - Use `useAsyncAction` (in `hooks/useAsyncAction.ts`) for action buttons that need pending state + debounce.
   - Show errors via the in-place coral error banner pattern (search for `<FormError>` in Signal pages). Avoid `window.alert` — `AccountPage.tsx::onExport` is the lone legacy survivor.
   - For SSE-backed updates, dispatch to `useSseStore` so multiple components stay in sync; for one-page polling, use `setInterval` in `useEffect` and clear it in the cleanup.

4. **Tier gating** — if the endpoint is paid:

   - Backend: enforce in the controller using `tierEnforcementService.assertCanX(...)` or `tierPermissionService.assertCanX(userDetails)`. Never trust the client.
   - Frontend: read `useTierPermissions()` to gate the UI. Use it for *disable + show upgrade CTA* rather than *hide entirely* so users discover the feature.

5. **Documentation pass** — required, per the standing 2026-04-25 rule:

   - Add the function row to this file (`docs/frontend-api-usage.md`) under the right domain table.
   - If the new endpoint mounts under a new top-level path (e.g. a new `/api/<resource>`), add it to `docs/CODEMAP.md`.
   - If the backend behaviour change is user-visible, update `README.md`.
   - If you touched a per-package surface, update that package's `CLAUDE.md`.

6. **Migration trail** — if the endpoint reads / writes a new entity:

   - Add the `Vxxx__*.sql` migration. Migration head as of this writing is **V199**.
   - Cross-link it from the entity row in the touching package's `CLAUDE.md`.

7. **Tests**:

   - At minimum, a JUnit test on the controller hitting the happy path + the most likely 4xx (e.g. missing param → 400, unauthorized → 401, forbidden → 403).
   - For multi-tenant endpoints, a test that proves a user can't read another tenant's data (mirrors `AccessGuardCoverageTest` expectations).
   - If the endpoint touches credit cost, a test on the multiplier path in `RunCostTracker` / `verifiedScore`.

8. **Final spot-check**:

   - Boot locally (`mvn spring-boot:run` + `npm --prefix frontend run dev`), hit the new endpoint from the page, verify the JWT goes out and the response shows up.
   - Watch the network panel — make sure the response is JSON, the status is what you expected, and a forced 4xx renders the canonical error envelope rather than a stack trace.

If you're touching `lib/api.ts` and not landing the matching backend change in the same PR, document that explicitly so a reviewer can flag it (the frontend will 404 silently otherwise).

---

## Appendix A — referenced files

Frontend:

- `frontend/src/lib/api.ts` — the canonical client, 3,620 lines.
- `frontend/src/lib/execution-api.ts` — V3 execution-run client.
- `frontend/src/lib/billing-api.ts` — billing + subscription client.
- `frontend/src/lib/invoice-api.ts` — invoice + accounting client.
- `frontend/src/hooks/useSse.ts` — V2 run SSE hook.
- `frontend/src/hooks/useExecutionSse.ts` — V3 execution SSE hook.
- `frontend/src/hooks/useTierPermissions.ts` — tier-derived permissions.
- `frontend/src/hooks/useAsyncAction.ts` — pending-state wrapper.
- `frontend/src/stores/useAuthStore.ts` — JWT + user state.
- `frontend/src/stores/useSseStore.ts` — SSE log + connection status.
- `frontend/src/signal/pages/*.tsx` — Signal page surfaces (consumers).
- `frontend/src/signal/components/*.tsx` — Signal primitives (mostly no API).

Backend:

- `src/main/java/com/navneet/executespec/api/controller/RunController.java` — run lifecycle hub.
- `src/main/java/com/navneet/executespec/api/controller/RunConversationController.java` — post-run chat + apply.
- `src/main/java/com/navneet/executespec/api/controller/PreviewController.java` — preview lifecycle.
- `src/main/java/com/navneet/executespec/api/controller/SseController.java` — SSE token + subscribe.
- `src/main/java/com/navneet/executespec/api/controller/DeploymentController.java` — deployment CRUD.
- `src/main/java/com/navneet/executespec/api/controller/MetricsController.java`, `TimelineController.java`, `UserRunsController.java`, `AdminAnalyticsController.java` — analytics surfaces.
- `src/main/java/com/navneet/executespec/api/controller/ConversationController.java` — pre-flight spec refinement.
- `src/main/java/com/navneet/executespec/api/controller/SlackIntegrationController.java`, `IntegrationController.java`, `IntegrationStatusController.java` — integrations.
- `src/main/java/com/navneet/executespec/api/controller/ModelController.java`, `PricingController.java` — model + pricing matrix.
- `src/main/java/com/navneet/executespec/api/controller/HealthController.java` — health + readiness.
- `src/main/java/com/navneet/executespec/api/controller/FeasibilityController.java` — retired 410 stub.
- `src/main/java/com/navneet/executespec/api/EntitlementsController.java`, `ReferralController.java`, `SignupClaimController.java`, `AnonymousRunController.java` — V3 surface.
- `src/main/java/com/navneet/executespec/api/advice/ValidationAdvice.java` — 4xx envelope.
- `src/main/java/com/navneet/executespec/api/advice/OptimisticLockAdvice.java` — 409 on stale write.
- `src/main/java/com/navneet/executespec/api/advice/GlobalExceptionAdvice.java` — 500 last-chance.
- `src/main/java/com/navneet/executespec/api/RunMapper.java` — entity → DTO mapping for run endpoints.
