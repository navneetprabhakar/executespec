# Audit: Frontend core — entry + stores + `lib/` + whole-app security sweep (Tier 8, part 1)

Status: **PARTIAL** (foundation done; `ide/`+`signal/` components pending) · Updated 2026-05-29 · 23 of 95 TS files read in full + a security grep across all 95 · React 18 / Vite 5 / Zustand / TS 5.3

## Scope of this checkpoint
`main.tsx`, `App.tsx`, all 7 Zustand stores, the entire `lib/` (14 files incl. the 3,690-line `api.ts`), plus a security-pattern sweep over **all 95** frontend files. Component dirs (`ide/` 18, `signal/` 31, `hooks/` 11, `console/` 2, `types/` 2, `design-system/` 2) are the remaining Tier-8 read.

## Findings — clean, well-factored foundation; a few real items.

### 🟢 Security posture (swept across all 95 files)
- 🟢 **One `dangerouslySetInnerHTML` in the whole app** — `ide/IdeIcons.tsx` injecting **static developer-defined SVG** markup (`g.inner` from a hardcoded icon map, never user data). Markdown renders via `react-markdown` (safe). No `innerHTML`, no `eval`. Effectively no XSS sink.
- 🟢 **No hardcoded secrets** in source (grep clean).
- 🟢 **Token model is the standard SPA pattern** — short-lived access token in `localStorage` (XSS-exposable but 1h TTL), refresh via httpOnly cookie (`credentials:'include'`); `req()` throws typed `ApiError(status)` with a network-error sentinel (status 0). The refresh isn't auto-triggered on a mid-session 401 (a 401 surfaces as "session expired") — minor UX, not a security gap.
- 🟡 **Link safety — minor.** ~8 `target=_blank` anchors lack explicit `rel=noopener`/`noreferrer` (several others have `rel=noreferrer`, which is sufficient). Modern browsers default `_blank` anchors to noopener, so reverse-tabnabbing is largely mitigated; the one real gap is `IdeSettings.tsx:44` `window.open(installUrl,'_blank')` (window.open doesn't get the implicit noopener) — but `installUrl` is the backend OAuth install URL (trusted). Low severity; add `rel="noopener noreferrer"` for hygiene.

### 🟡 ~8 components bypass the centralized `api.ts` client with raw `fetch()`
`IdeProjectMore` (project chat L161, settings L2069), `IdeBilling` (payment-history L87, subscribe L547), `IdeMore` (data-export L335, dsar L362, data-erasure L389), `IdeSettings` (admin reconcile L1137) call `fetch()` directly instead of `api.req()`. **Confirmed against the call sites (2026-05-29):** they DO add `Authorization: Bearer ${access_token}` manually and DO check `!resp.ok` — so they're authenticated + error-aware (NOT a security hole). The real issues: (a) **their URLs escape `ApiContractTest`** (the ArchUnit pin that asserts every `api.ts` URL resolves to a registered backend `@*Mapping`) — these 8 endpoints can silently drift from the backend; (b) **some duplicate existing typed clients** — `IdeProjectMore` hand-rolls `PATCH /projects/{id}/settings` though api.ts already exports `updateProjectSettings`, and `IdeBilling` re-fetches `/billing/subscribe` though `billing-api.ts` already exports `subscribe`; (c) others (project-chat **send** `POST /projects/{id}/chat`) hit endpoints simply **missing** from api.ts; (d) they throw raw `Failed (${status})` instead of `req()`'s friendly STATUS_MESSAGES. The split clients `billing-api.ts`/`execution-api.ts`/`invoice-api.ts` correctly route through `req`. Recommend: route (b) through the existing fns, add (c) to api.ts, move the blob ones to a helper. (`api.ts`'s own raw fetches are all legitimate blob downloads + multipart upload + the public shared-report GET.)

### 🟡 Dead code: the `monaco*` trio (~232 LOC) + likely two npm deps
`lib/monacoSetup.ts` + `monacoGeistTheme.ts` + `monacoErrorPatterns.ts` form a **self-referential island with zero external importers** — the whole-frontend grep finds no `@monaco-editor` / `MonacoSafeMount` / `DiffEditor` import anywhere. They were orphaned when `CodeView` (regex tokenizer) replaced Monaco "to save ~600 KB" (per frontend CLAUDE.md); the consumer components were deleted but these helpers remain (incl. a window-level error suppressor that never installs because nothing imports the module). Deletable; also drop `monaco-editor` + `@monaco-editor/react` from `package.json` if still listed (install weight). Joins the backend dead-code theme.

### 🟡 Privacy: analytics opt-out default mismatch (from [analytics.ts])
`analytics.ts` comments claim opt-out is "the default for new users," but `isOptedOut()` returns `localStorage[CONSENT_KEY]==='true'` → an **unset** flag means **opted-IN**, so new users are tracked by default when `VITE_POSTHOG_API_KEY` is built in. GDPR-consent-relevant; mild (key-gated, and PostHog autocapture/session-recording are disabled). Either set the flag true for new users or fix the comment.

### 📝 Minor / stale
- 📝 `constants.ts` `NAV_ITEMS` still lists retired V2-sidebar routes (`/dashboard`, `/exec`, `/invoices`, `/accounting`, `/admin/analytics`) and `V2_LEGACY_UI` 410-gated flags — stale after the IDE-only switch (the IDE chrome has its own nav; `LegacyRedirect` collapses these paths). Harmless, but dead config.
- 📝 `useExecutionStore` drives the V3 multi-solution lane (`/api/executions`) which is **flag-OFF** (`executionViewEnabled=false`) — dormant UI state for a disabled feature. Harmless.

### 🟢 The good
- 🟢 **`lib/verdict.ts` is the canonical single-source run verdict** (`deriveVerdict`) — fixed a documented credibility-class bug where 4 surfaces disagreed on the same run; thresholds align with the backend additive scorer (≥85 ship / 50–84 review / <50 blocked) and the ≥85 `VerdictPill`. Returns `null` for in-flight/no-signal so the UI never fabricates a verdict.
- 🟢 **`api.ts` is a clean, fully-typed single client** (3,690 lines, ~300 endpoint wrappers) — uniform `req`/`reqText`, `ApiError` with status→friendly-message map, `PagedResponse<T>`, every DTO typed. The `ApiContractTest` ArchUnit pins its URLs to backend mappings (modulo the in-component fetches above).
- 🟢 Stores are thin Zustand slices (auth/run/ui/sse/project/execution/metrics); `useSseStore` caps the live log at 500 entries (no unbounded growth); legacy `'vibe'`/`MAX` values normalized at the parse boundary.

## Cross-refs
Backend IDOR confirmed UI-reachable → [readymade-migration-test.md](readymade-migration-test.md) (`instantiateLibrarySolution`/`migrationApply`/`migrationDryRun` pass user `projectId` with no guard). Analytics opt-in default → [tier7-longtail.md](tier7-longtail.md) (`analytics/` backend). api.ts contract pin → `arch/ApiContractTest` (Tier 9 tests). Dead-code theme → [INDEX.md](INDEX.md) cross-cutting log. Auth token/refresh model → [auth.md](auth.md) (JWT 1h + refresh cookie 7d). **TODO next:** `ide/` (18) + `signal/` (31) components, `hooks/` (11), `console/` (2), `types/` (2), `design-system/` (2).
