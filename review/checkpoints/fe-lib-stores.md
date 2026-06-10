# fe-lib-stores — raw findings

> Scope: `frontend/src/lib/` (30 files), `frontend/src/stores/` (7), `frontend/src/hooks/` (11), `frontend/src/types/` (2), build/tooling configs, `public/sw.js` + `manifest.webmanifest`. Every file read in full; all dead-code claims grep-verified repo-wide over `frontend/src`. Conclusions from code only (no docs). Branch `benchmark`, working tree as of 2026-06-10.

## 1. Inventory

| Area | Files | LOC | Notes |
|---|---|---|---|
| `lib/api/` | 16 | ~3,800 | `core.ts` 1,977 (≈140 LOC client logic + ≈1,840 LOC types), `projects.ts` 521, `runs.ts` 243, 13 smaller domain modules, `index.ts` barrel re-exporting everything |
| `lib/` (non-api) | 14 | ~1,900 | analytics, telemetry, glossary, verdict, utils, constants, techDetect, monaco×3, billing-api, invoice-api, execution-api |
| `stores/` | 7 | 781 | useAuthStore, useUiStore, useRunStore, useProjectStore, useExecutionStore, useMetricsStore, useSseStore |
| `hooks/` | 11 | 720 | useSse, useExecutionSse, useTierPermissions, useAsyncAction, useApi, useIsMobile, useBreakpoint, useIsProjectAdmin, useKeyboardScroll, useKeyboardShortcuts, useViewMode |
| `types/` | 2 | 407 | project.ts 156, execution.ts 251 |
| Total in scope | 60 | ~7,900 | |

The shipped bundle (built Jun 9, `src/main/resources/static/assets/`): `index-*.js` 446 KB + `vendor-*.js` 263 KB + `react-vendor-*.js` 142 KB = **~851 KB raw JS in 3 eager chunks**, CSS ~102 KB. Exactly 3 JS assets ⇒ zero route-level lazy chunks.

## 2. Findings

### [BUG] `req()` spreads `opts` after `headers` — custom headers silently drop the Authorization header
`lib/api/core.ts:97-101`:
```ts
res = await fetch(BASE + url, {
  headers: {'Content-Type': ..., ...authHeader(), ...opts?.headers},
  credentials: 'include',
  ...opts,          // <-- opts.headers REPLACES the merged headers object
});
```
JS object-literal semantics: the trailing `...opts` re-assigns the `headers` key wholesale when the caller passes one (verified with node: result is `{"Content-Type":"application/json"}` only — no `Authorization`). The pre-merge `...opts?.headers` inside the first `headers` value is then pointless. **One live caller is broken today:** `submitContactForm` (`lib/api/runs.ts:129-134`) passes `headers: {'Content-Type': 'application/json'}` → the request goes out unauthenticated → backend `ContactController.submit` (`@AuthenticationPrincipal AppUserDetails`, `/api/contact` not in any permitAll list in `SecurityConfig`) → 401. The Account "Contact / Feedback" form (`ide/IdeMore.tsx:314`) fails on every submit. Same latent hazard for `credentials` if a caller ever passes it. Fix: spread `...opts` first, then assign merged `headers` last (and drop the inner `...opts?.headers`).

### [BUG] Duplicate `interface ValidationResult` declarations merge into a lying type
`lib/api/core.ts:435-440` declares `ValidationResult {valid, issues, gcpProjectId, permissionChecks}` (GCP deploy shape); `core.ts:762-765` declares `ValidationResult {valid, message}` (key/integration-validate shape) **in the same module** → TypeScript declaration-merges them into one 5-member interface. Both shapes are consumed: `ide/IdeOrg.tsx:104-105` reads `.message` from `validateAiKey`; `signal/components/GcpCredentialPanel.tsx:61` reads `.issues`/`.permissionChecks` from `validateGcpCredential`. Each endpoint returns only its own subset, so the merged type lets you read `.permissionChecks` off an AI-key validation (undefined at runtime) without a compile error. Split into two named interfaces.

### [BUG] No token refresh and no 401 recovery — a tab older than 1h degrades into permanent error-spam
- `useAuthStore.refreshAccessToken` (`stores/useAuthStore.ts:66-82`) is called from exactly one place: `loadMe()` line 88, and **only when localStorage has no token at all**. Grep confirms zero other callers.
- `req()` (`core.ts:109-112`) maps 401 → `throw ApiError(401, "Your session has expired…")` — no refresh-and-retry, no logout dispatch, no redirect.
- `isAuthenticated` is just `!!localStorage.getItem('access_token')` (`useAuthStore.ts:31`), so an *expired* token keeps `ProtectedRoute` (`signal/components/auth/ProtectedRoute.tsx:53`) rendering the app.
Net: access token = 1h; refresh cookie = 7d sits unused; after 1h every API call in an open tab fails with the session-expired toast forever. Recovery requires a manual full reload (then `authMe()` 401s → `loadMe` catch → `isAuthenticated:false` → bounce to /login — the refresh cookie is *still* not tried because the dead token is present, `useAuthStore.ts:85-90`). This is the single biggest data-layer architecture gap.

### [BUG] `useAuthStore.isLoading` is never set — ProtectedRoute's loading branch is unreachable and cookie-restore is racy
`isLoading` is declared (`useAuthStore.ts:10`) and initialized `false` (`:32`) but no `set({isLoading: ...})` exists anywhere (grep-verified). Consequences in `ProtectedRoute.tsx:40-46,48-56`: the spinner branch never renders, and a returning user with only the refresh cookie (no localStorage token) is synchronously `<Navigate to='/login'>`-ed before the async `loadMe()` cookie refresh can resolve — the 7-day "remember me" path can't actually restore a session on a protected URL.

### [BUG] PWA manifest `start_url` points at a deleted chrome
`public/manifest.webmanifest`: `"start_url": "/v3/home"`. The `/v3/*` tree was deleted; the URL only survives via the `LegacyRedirect` catcher (`App.tsx:218`). Installed-app launches work only by accident of the redirect shim and will 404 the day the legacy catchers are pruned. Should be `/home`.

### [RISK] 200-with-non-JSON crashes out of the typed error channel
`core.ts:117` — `return JSON.parse(text)` is uncaught: any 200 response with an HTML/garbage body (proxy interstitial, Cloudflare error page with 200, misconfigured endpoint) throws a raw `SyntaxError`, not `ApiError`. `errMsg()` then surfaces "Unexpected token < …" to users and `isRetryableError()` returns false on it.

### [RISK] No timeout / AbortSignal anywhere in the client
`req`/`reqText` (`core.ts:85-142`) pass no `signal`; none of the 13+ polling loops abort in-flight requests on unmount (e.g. `IdePlan.tsx:22-42` uses a `stop` flag but the fetch itself continues). A hung backend connection hangs the UI state forever; overlapping slow polls can resolve out-of-order (last-write-wins by luck, not design).

### [RISK] Report-swallowing catch-alls hide auth/server failures as "no data"
`getValidationReport` (`lib/api/projects.ts:141-147`), `getTestReport` (`:171-177`), `getSharedReport` (`:161-169`), `getTestPlan` (`lib/api/runs.ts:190-196`), `getTestGraph` (`lib/api/misc.ts:123-129`) catch **everything** (401, 403, 500, network) and return `null`. A tenant-guard regression or expired session reads as "report not generated yet" in the UI.

### [RISK] `useSse` falls back to an unauthenticated SSE connect on token-mint failure
`hooks/useSse.ts:67-74` — when `getSseToken` fails, it connects to `/api/runs/{id}/events` with no token ("connecting without auth"), which the backend rejects → `onerror` → reconnect loop (backoff capped 30s, `BACKOFF` line 12) that re-mints forever. Harmless but noisy; an auth failure should stop after N attempts.

### [RISK] `useExecutionSse` leaks its reconnect timer
`hooks/useExecutionSse.ts:42-48` — the `setTimeout(connect, 3000)` id is never stored/cleared; cleanup (`:155-158`) only closes the EventSource. A reconnect scheduled before unmount fires `getSseToken` (an authenticated API call) after unmount; the `cancelled` check only runs *after* the token fetch.

### [INCONSISTENT] Three competing error/transport conventions in one layer
1. `req()`/`ApiError` (the intended chokepoint, `core.ts:85`).
2. Plain `new Error(...)` raw-fetch helpers: `downloadWorkspace` (`projects.ts:79`), `uploadProjectDocument` (`projects.ts:419`), `downloadInvoicePdf` (`lib/invoice-api.ts:145`) — these bypass `parseErrorMessage`, so `errMsg(e)` shows "Upload failed: 403" instead of the friendly message; two of them also hardcode `/api/...` instead of `BASE` (`projects.ts:384,414`) and re-read localStorage instead of `authHeader()` (`projects.ts:383,413`).
3. Raw `fetch()` inside components: 10 sites — `ide/IdeBilling.tsx:85,545`, `ide/IdeSettings.tsx:1131`, `ide/IdeProjectMore.tsx:160,2067`, `ide/IdeMore.tsx:334,361,388`, `signal/components/CodeReviewPanel.tsx:180`, `signal/pages/MiscPages.tsx:1196` — each hand-rolls auth headers (16 separate `localStorage.getItem('access_token')` reads across 10 files). IdeBilling's own header comment admits it mirrors "raw payment-history fetch / raw billing/subscribe fetch" instead of using the client.

### [INCONSISTENT] Two exported `TaskSummary` types with different shapes
`lib/api/core.ts:216-244` (run-task shape: `taskId/title/status/assignedModel/...`) vs `types/project.ts:69-76` (dashboard shape: `taskId/runId/name/score`). Both are exported package-wide (`lib/api/index.ts:2` re-exports core's). No current mis-import (project's is only used intra-file), but it's a loaded foot-gun for the aggregator work.

### [INCONSISTENT] Polling cadence and hidden-tab guards are per-surface folklore
All polls except one check `document.hidden`; `ide/IdePlan.tsx:37` polls `getRun` every **2s** with **no hidden-tab guard** — the most aggressive interval in the app runs even when backgrounded. Cadences in the wild: 2s, 2.5s, 3s, 5s, 8s, 12s, 15s, 30s — all hand-picked per file (see §Polling inventory below).

### [INCONSISTENT] `billing-api.ts` tier enum disagrees with the live ladder
`lib/billing-api.ts:8` documents `tier: // FREE, STARTER, PRO, ENTERPRISE` — `STARTER` doesn't exist anywhere else; `core.ts:687` (AuthUser) says `FREE | PRO | TEAM | ENTERPRISE`. Module is dead anyway (§3) but if revived it would re-introduce a stale enum.

### [DUPLICATION] Same endpoint wrapped twice in `lib/api/billing.ts`
`getPricing` (`billing.ts:14`) ≡ `getAllPricing` (`billing.ts:46`) — byte-identical calls to `/pricing`. `updatePricing` (`billing.ts:16`) ≡ `updateModelPricing` (`billing.ts:40`) — same `PUT /pricing/{provider}/{model}` with marginally different body typing. Both duplicates are in the same file.

### [DUPLICATION] RunDetail re-declares members it already inherits
`core.ts:297-366` — `RunDetail extends RunSummary` yet re-declares `githubPrUrl` (:338 vs :213), `scmPrUrl` (:340 vs :207), `actualCostUsd` (:308 vs :192), `approvedExecutionMode` (:353 vs :178). Legal TS, but two places to update per field.

### [DUPLICATION] Hand-rolled localStorage persistence in every store
No zustand `persist` middleware anywhere (grep-verified). `useUiStore` hand-writes 6 different keys (`es:theme` :184, `es:uiMode` :204, `es:viewMode` :177, `es:sidebarExpanded` :162, `es:runListWidth` :172) each with its own try/catch-or-not; `useAuthStore` does the same for `access_token` in 5 places (:37, :57, :69, :78, :121).

### [SMELL] `useUiStore.viewMode` initializer is a constant dressed as logic
`stores/useUiStore.ts:151-158` — `if (saved === 'pro') return 'pro'; return 'pro';` — both branches return `'pro'`. The whole IIFE + the `es:viewMode` read is a no-op. Together with dead `useViewMode` hook (§3) the entire viewMode subsystem is vestigial.

### [SMELL] `useSse` mixes store-push and refetch strategies, with a phantom store
`hooks/useSse.ts:92-100` parses `RUN_DETAIL` pushes into `useRunStore.setState({activeRun})` and calls `fetchRun` on 6 other event types — but **nothing in the live tree reads `useRunStore.activeRun`** (the store's only importer is useSse itself, §3). Every `RUN_DETAIL` payload is parsed and written into state with zero subscribers; IdeLive renders from `useSseStore.entries` instead.

### [SMELL] `STATUS_MESSAGES[410]` markets internal jargon to users
`core.ts:30` — "This feature is not available in the current harness." — "harness" is internal vocabulary leaking into a customer-facing error string.

### [SMELL] `fetchFounderOffer` guards an impossible branch
`lib/billing-api.ts:129-133` checks `status === 204` inside the catch — `req()` returns `undefined` on 204 (`core.ts:113`), it never throws it. Dead branch (module is dead anyway).

### [PERF] OTel SDK is bundled for everyone, enabled for no one
`main.tsx:12-15` statically imports + unconditionally calls `initTelemetry()`; `lib/telemetry.ts:23-27` no-ops without `VITE_OTEL_ENDPOINT`, but the 5 statically-imported OTel packages (`sdk-trace-web`, `exporter-trace-otlp-http`, `context-zone`, `resources`, `semantic-conventions`) are in the vendor chunk for every visitor regardless. If browser tracing isn't a real goal, deleting `telemetry.ts` + deps trims the vendor chunk; if it is, gate behind a dynamic `import()`.

### [PERF] Dead highlight.js theme CSS shipped on every page
`main.tsx:10` imports `highlight.js/styles/github-dark-dimmed.css` "for the IDE file viewer" — but zero `hljs` class emitters exist in src (grep: no `hljs`, no `from 'highlight.js'`). Pure dead CSS bytes.

### [PERF] No route-level code splitting
Zero `React.lazy` in the tree (grep-verified); `App.tsx:9-49` eagerly imports every IDE surface, Console shell, and all public Signal pages into the 446 KB app chunk. `vite.config.ts:39-55` *does* have `manualChunks` (react-vendor / vendor) since RL-2026-05-041 — so the "single 802 KB bundle" framing is stale — but all 3 chunks load eagerly, so first-paint wire weight is unchanged (~851 KB raw); the split only buys cache stability. The admin console, benchmark dashboard, billing, legal pages etc. are all in the first byte a FREE user downloads.

## 3. Dead code & junk — grep-verified

### 3a. Exported API functions with ZERO component callers
Method: extracted all `export const|function` from `lib/api/*.ts` (**316 symbols**), matched word-boundary against all of `frontend/src` minus `lib/api/` itself. **100 symbols have zero references outside lib/api**; minus 5 infrastructure exports consumed within the api layer itself (`BASE`, `STATUS_MESSAGES`, `authHeader`, `parseErrorMessage`, `reqText`) ⇒ **95 endpoint wrappers (≈30% of the API surface) are unwired**:

`addProjectMember, addProjectSlackChannel, adminApproveInvite, adminExportPaymentAuditCsv, adminGetTierConfig, adminGetUser, adminInviteUser, adminListEscalated, adminListPaymentAudit, adminListWebhookEvents, adminRejectInvite, adminUpdateOrgApiKeys, adminUpdateTierConfig, analyseProject, analyzeForDeploy, analyzePreview, annotateArchitecture, applyToRun, authChangePassword, authResendVerification, checkContractDrift, confirmDeploy, createOrg, createOrgSpecTemplate, createOrgStandard, createRepo, deleteOrgStandard, deleteRepo, disconnectSlack, dismissPostMergeBar, engineDestroy, engineRollback, execPreviewCommand, getAllPricing, getAvailableModels, getDeploymentDetail, getFilesWithStatus, getIntegrationRuns, getOpenApiSpec, getPlanVersions, getPostMergeBar, getPrCommentPendingCount, getProjectAdr, getProjectLedger, getProjectSlackChannels, getProjectSummary, getPublicFeatureFlags, getPullRequestSnapshot, getRepoCodeMap, getRequiredPermissions, getRunPrs, getSandboxStatus, getTaskGraph, getTestGraph, isRetryableError, listCodeReviewsForProject, listGcpCredentials, listGitHubRepos, listOrgStandards, listPrReviewsForProject, listProjectInvites, listSlackChannels, parse… (infra excluded), previewProjectInvite, probeApiDocs, pushToGitHub, removeProjectSlackChannel, requiresCostCap, restoreProject, restoreRun, retryValidationReport, revokeProjectInvite, sendSlackTestMessage, shareLinkPublicUrl, syncRun, syncRunToOriginal, triggerConfluence, triggerJira, unarchiveProject, uninstallGitHubApp, updateModelPricing, updateOrgConfluenceSettings, updateOrgJiraSettings, updateOrgKeySourceSettings, updateOrgStandard, updatePricing, updateRepoRelationships, updateRunProject, updateSlackChannelVerbosity, updateTierLifecycle, validateBitbucket, validateConfluence, validateGitHub, validateJira, validateOrgConfluence, validateOrgJira`

Notable losses implied: per-user GitHub/Jira/Confluence/Bitbucket **validate** buttons, Slack channel management, contract drift checks, ADR detail, org standards CRUD, run plan-graph/test-graph, restore (un-soft-delete) for runs & projects, `retryValidationReport` — all live backend endpoints with no UI path. (`isRetryableError` — the audit-r5 retry-affordance helper — is itself unused, i.e. the retry-UX pattern it was built for was never wired.)

### 3b. Entire dead modules (zero importers in the live tree, grep-verified)
| Module | LOC | Evidence |
|---|---|---|
| `lib/analytics.ts` + dep `posthog-js` | 168 | zero imports of any of its 8 exports; `initAnalytics` never called → PostHog never initializes; the opt-out toggle has no UI caller either |
| `lib/constants.ts` | 161 | `NAV_ITEMS`/`STATUS_COLORS`/`ACTIVE_STATUSES`/`V2_LEGACY_UI`/`ROUTE_PATHS` referenced nowhere outside the file (consumers died with the V2 sidebar) |
| `lib/techDetect.ts` | 283 | zero importers (RunCard tech badges gone with old chrome) |
| `lib/billing-api.ts` | 213 | zero importers; IdeBilling uses `lib/api` fns + raw fetch instead |
| `lib/invoice-api.ts` | 165 | zero importers (invoices surface dropped in IDE port) |
| `lib/monacoSetup.ts` + `monacoGeistTheme.ts` + `monacoErrorPatterns.ts` + dep `@monaco-editor/react` | 232 | `monacoSetup` imported by nothing; the other two only by monacoSetup |
| `lib/execution-api.ts` | 80 | only importers are the two dead execution modules below |
| `stores/useExecutionStore.ts` | 184 | only importer is `hooks/useExecutionSse.ts` |
| `hooks/useExecutionSse.ts` | 169 | zero importers (V3 execution lane unreachable — `App.tsx:75` redirects `/executions/*` away) |
| `types/execution.ts` | 251 | only imported by the two dead execution modules |
| `stores/useMetricsStore.ts` | 65 | zero importers |
| `stores/useProjectStore.ts` | 93 | zero importers |
| `hooks/useApi.ts` | 31 | zero importers |
| `hooks/useAsyncAction.ts` | 75 | zero importers — yet `.eslintrc.cjs` `no-restricted-syntax` (lines ~140-170) actively instructs developers to use it |
| `hooks/useIsMobile.ts` | 46 | zero importers (mobile layouts are pure CSS) |
| `hooks/useBreakpoint.ts` | 66 | zero importers |
| `hooks/useIsProjectAdmin.ts` | 30 | zero importers (the P-25 CTA-gating it implemented is gone) |
| `hooks/useKeyboardScroll.ts` | 46 | zero importers |
| `hooks/useKeyboardShortcuts.ts` | 20 | zero importers (IdeWorkspace has its own ⌘K handler) |
| `hooks/useViewMode.ts` | 89 | zero importers |
| **Zombie:** `stores/useRunStore.ts` | 208 | exactly one importer (`hooks/useSse.ts`) which only *writes* (`patchRunStatus`, `fetchRun`, `setState({activeRun})`); zero readers of any of its state → filters/pagination/createRun/approveRun/retryTask/pause/resume actions all unreachable |

**Dead + zombie total ≈ 2,675 LOC ≈ 34% of the in-scope surface.** Root cause is visible in `tsconfig.json:15-16` — `noUnusedLocals/noUnusedParameters: false` — plus no dead-export linting (`eslint-plugin-import` not installed, placeholder comment in `.eslintrc.cjs` overrides block).

### 3c. Unused npm dependencies (grep over `frontend/src` for any import)
| Package | Verdict |
|---|---|
| `d3` (+ `@types/d3` dev) | **zero imports** — removable |
| `framer-motion` | **zero imports** — removable |
| `qrcode.react` | **zero imports** — removable |
| `cmdk` | **zero imports** — removable (palette is hand-rolled in IdeKit) |
| `@radix-ui/react-tabs` | **zero imports** — removable |
| `lucide-react` | **zero imports** — removable (icons are hand-rolled `IdeIcons.tsx`; `constants.ts` icon *strings* are dead data) |
| `class-variance-authority` | **zero imports** — removable |
| `recharts` | **zero TS imports** (only a CSS comment mentions it, `index.css:147`) — removable |
| `posthog-js` | imported only by dead `lib/analytics.ts` — removable with it |
| `@monaco-editor/react` | imported only by dead `lib/monacoSetup.ts` — removable with it |
| `@opentelemetry/auto-instrumentations-web` | **zero imports** (telemetry.ts doesn't use it) — removable |
| `@opentelemetry/api` | no direct import (transitive peer of the SDK) — removable if the OTel decision (§2 PERF) goes to delete |
| `highlight.js` | only its **CSS** imported (`main.tsx:10`); JS never — CSS is dead too (no `hljs` classes emitted) |
Used-and-fine: react, react-dom, react-router-dom, zustand, clsx, tailwind-merge, react-markdown, remark-gfm, the 5 imported OTel packages (see PERF).

Tree-shaking means most of these don't bloat the bundle — the cost is install weight, audit surface, Dependabot noise, and misleading signal about what the app uses.

### 3d. Misc junk
- `.eslintrc.cjs` `ignorePatterns` references `src/v3/lib/tierDisplay.test.ts` — **path doesn't exist** (src/v3 deleted). Also ignores `src/design-system/contrast.test.ts` as a "dormant vitest fixture" — it's now a live CI test (§6), so the one real test file is lint-exempt.
- `public/sw.js:24` cache name `v3-shell-v1` (cosmetic legacy naming; SW logic itself is sound — network-first navigations, no API caching).
- `tailwind.config.ts:4` `darkMode: 'class'` is dead config — zero `dark:` variants keyed on a class; theming runs through `@custom-variant dark ([data-theme='dark'])` in `index.css:13`.

## 4. Provider/model/tier leakage (feeds the aggregator redesign)

The api layer has **no provider/model enum anywhere — everything is loose strings**:
- `RunSummary.aiProvider?: string | null` with the comment "(ANTHROPIC, OPENAI, GOOGLE)" (`core.ts:179-180`) — the comment itself contradicts the project's "gemini not google" naming rule and omits DEEPSEEK/OPENROUTER entirely.
- `TaskSummary.provider?: string`, `.model?: string` (`core.ts:221-224`); `AgentTokenEntry.provider/model: string | null` (`core.ts:289-295`); `ProjectChatInfo.provider/model: string` (`core.ts:1631-1635`); `ModelComparisonData.models[].provider/model: string` (`core.ts:484-493`); `benchmark.ts:12-13` `provider: string; model: string`.
- The only structured model metadata is `AvailableModelsResponse` (`core.ts:505-518`): `ProviderEntry {id, label, tag?, models: ModelEntry[{id, tier, label}]}` — `tier` here is the **chain tier** (string, e.g. planning/workhorse/lightweight), and **`getAvailableModels` has zero callers** (§3a), so the IDE compose surface isn't even using the typed matrix endpoint.
- `PricingRow.chainTier: string | null` + `displayLabel` (`core.ts:378-393`) is the YAML-derived pricing view; `ModelMatrixView` (`core.ts:872-880`) is the F3 org-override grid keyed by `"<provider>:<tier>"` **strings** with `Record<string,string>` cells — no provider union type to keep keys honest.
- Subscription tier is the one place with real unions: `AuthUser.tier: 'FREE'|'PRO'|'TEAM'|'ENTERPRISE'` (`core.ts:687`) + legacy-MAX coercion centralized in `selectNormalizedTier` (`stores/useAuthStore.ts:149-158`). But `billing-api.ts:8` (dead) still documents a `STARTER` tier, and `MigrationRecipeSummary.tier`/`ReadymadeSolutionSummary.tier` re-declare their own inline unions (`core.ts:1662,1760`).
**Implication for the aggregator:** there is no single `Provider` type to extend when OpenRouter/Fireworks land — today you'd touch N independent `string` fields and one dead endpoint. Cheap pre-work: define `type ProviderId` + `type ChainTier` in one module and thread them through `RunSummary/TaskSummary/AgentTokenEntry/ProviderEntry/ModelMatrixView/benchmark.ts`.

## 5. Build/tooling

- **vite.config.ts** — `manualChunks` EXISTS (`:39-55`, react-vendor + vendor catch-all) contrary to the older "no manualChunks" framing; sane dev proxy with changeOrigin (`:20-29`); output into the Spring static dir. Missing: route-level splitting (no `React.lazy` anywhere), no `build.sourcemap`, no bundle-size budget/analyzer.
- **tsconfig.json** — `strict: true` (good); but `noUnusedLocals: false`, `noUnusedParameters: false` (`:15-16`) — directly enables the §3 dead-code accumulation.
- **.eslintrc.cjs** — thoughtful Google-TS profile, but: `react-hooks/rules-of-hooks: 'warn'` (a genuine-bug class downgraded, comment admits ~6 standing violations), `no-explicit-any: 'warn'`, and the npm `lint` script runs `--max-warnings 9999` (`package.json:12`) ⇒ **warnings are decorative in CI**. The `no-restricted-syntax` async-onClick rule points at a hook nothing imports (§3b). Stale ignorePatterns (§3d).
- **tailwind.config.ts** — wired via `@config` from index.css (Tailwind 4 compat path, real); dead `darkMode:'class'`; token-aware var() palette is clean.
- **.prettierrc.json** — fine; enforced in CI (lint.yml + deploy.yml).
- **Bundle reality** — 3 eager JS chunks ≈ 851 KB raw (446 app / 263 vendor / 142 react) + 102 KB CSS. Everything (admin, billing, benchmark dashboard, legal pages, Console shell, dead-CSS hljs theme, OTel SDK) ships to every visitor up-front. The manualChunks split improves cache reuse only.

## 6. Test coverage — vitest reality check

- `vitest.config.ts` exists (jsdom, globals, `passWithNoTests: false`), `npm test` = `vitest run`.
- **Exactly one test file in the entire frontend:** `src/design-system/contrast.test.ts`. Executed locally: `1 passed (18 tests), 424ms` — it runs and passes.
- CI runs it: `.github/workflows/lint.yml:79` (`cd frontend && npm test`). `deploy.yml` runs only format+lint, **not** tests.
- So: not "configured but testless," but effectively token coverage — 18 pure-function WCAG assertions, **zero tests** for `req()`/ApiError, auth store, SSE reconnect, verdict derivation (`lib/verdict.ts` — a perfect pure-function test target), or any store/hook. The two bugs in §2 (`req` header override, ValidationResult merge) would both have been caught by a 20-line client test.
- Backend pin: `src/test/java/com/navneet/executespec/arch/ApiContractTest.java` **exists and is live** (plain JUnit, scans `frontend/src/lib/api/*.ts` — correctly updated post god-file split, `:53`; empty ALLOWLIST `:59-62`). **Gap:** it does NOT scan `lib/billing-api.ts`, `lib/invoice-api.ts`, `lib/execution-api.ts` (they sit in `lib/`, not `lib/api/`) — execution-api is dead, but if billing/invoice modules are ever revived they're unpinned. Manual spot-check of 11 paths (POST /runs, event-log, /v2/test/benchmark, /contact, billing/status, billing/verify, billing/profile, accounting/revenue, executions/{id}, projects/{id}/launches, user-runs-summary) — **all resolve to live `@*Mapping`s**, including the un-pinned modules. No URL drift found.

## 7. Security notes (parked)

- **Token storage:** access JWT in `localStorage('access_token')` (`core.ts:18`, `useAuthStore.ts:30-31`) — XSS-readable by design choice; refresh token is a cookie (`credentials: 'include'`, `core.ts:99`). 16 scattered direct localStorage token reads across 10 files widen the change surface (§2 INCONSISTENT).
- **XSS surface:** exactly one `dangerouslySetInnerHTML` — `ide/IdeIcons.tsx:516`, fed from self-authored static SVG constants (`g.inner`), not user data. Low risk as-is; keep it constant-only.
- **SSE token in URL** (`useSse.ts:71`) — scoped 5-min token by design; acceptable, but it lands in server access logs. The no-token fallback connect (§2 RISK) sends an unauthenticated request on purpose.
- **PAT plaintext handling** (`lib/api/auth.ts:87-91`) — returned once, not persisted client-side per the comment; consistent with the design.
- **sw.js** — does not cache `/api/*` (`sw.js:58`), network-first navigations; no token ever cached. Sound.
- **Analytics consent** — moot: analytics never initializes (§3b), so no tracking happens at all; the privacy posture is accidentally maximal while the activation-funnel instrumentation silently doesn't exist client-side.

---
### Top-priority shortlist
1. Fix `req()` opts-spread header override (core.ts:97-101) — restores the Contact form and removes a whole class of future auth-drop bugs.
2. Add 401 → refresh-cookie retry (or at minimum global logout) in `req()`; set/maintain `isLoading` in `useAuthStore.loadMe` so cookie session-restore actually works.
3. Delete the ~2,675 LOC dead layer (incl. 8-10 npm deps) and flip `noUnusedLocals` on so it can't regrow.
4. Split the merged `ValidationResult`; introduce `ProviderId`/`ChainTier` union types ahead of the aggregator redesign.
5. Extract one `usePoll(fn, ms)` hook (hidden-tab guard + abort) and put `IdePlan`'s unguarded 2s poll on it.
