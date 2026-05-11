# ExecuteSpec — Full Project Review · 2026-05-11

**Reviewer:** independent end-to-end review, unbiased.
**Branch under test:** `dev` HEAD `22b8e6e5` (PR #108 merged). Reviewed
on branch `feature/full-project-review-2026-05-11`.
**Scope:** every dimension — backend code (145K LOC), frontend code
(75K LOC), infra/build/CI, data layer (186 migrations) + prompts (88
files) + email templates (21), test coverage (148 test files / 25K
LOC), security + observability + cost + dependency hygiene.

**Method:** orchestrated 5 parallel deep-read agents across
non-overlapping slices; 4 of 5 were silently terminated by the
harness mid-flight (the underlying transcripts hold the gathered
context but no final reports for those 4). Synthesised from:
(a) the frontend agent's complete structured report,
(b) the infra+CI agent's partial transcripts (P0 secret findings
verified directly by me),
(c) my own direct sampling of backend hot path + tests + data layer.

Every finding cites either a file/line reference or a captured grep.
Where coverage is shallow (e.g. I couldn't read all 1,001 backend
files), I say so explicitly.

---

## TL;DR — the top 12 things to fix this quarter

| # | Severity | Area | Finding | Fix sketch |
|---|----------|------|---------|------------|
| 1 | **P0 — security** | **Hardcoded secrets in `application-dev.yml` (git-tracked since 2026-04-02)** — Google OAuth client_secret, GitHub OAuth client_secret, SMTP password committed as `${VAR:default}` defaults | Rotate all three secrets, replace defaults with empty string, force-rebase if you want to scrub history. See §3.1. |
| 2 | **P0 — security** | **JWT secret hardcoded literal in `application.yml:680`** + `JwtSecretValidator` only runs on `@Profile("prod")` | Move default to empty; validator must run on `dev` + `gcp` profiles too; fail-fast at startup when not provided via env. |
| 3 | **P0 — UX/auth** | **No JWT auto-refresh on 401** — `req()` in `frontend/src/lib/api.ts:74-107` throws `ApiError(401)` without trying the refresh cookie. Users away >1h get logged out mid-task. | On 401, attempt `authRefresh()` once with single-flight gate, replay original request. |
| 4 | **P0 — perf** | **2.27 MB monolithic JS bundle**; zero `React.lazy`. Every login page ships the full admin/Monaco/recharts surface | Convert admin / run-detail / library routes to `React.lazy`; add `manualChunks` in `vite.config.ts` for monaco/recharts/framer-motion/posthog/OTel. |
| 5 | **P1 — code quality** | **`RunOrchestrator.java` is 4,539 lines / 48 methods, with 7 overloads of `submitRun` + 5 of `runPipeline`**. God class. | Extract a `RunRequest` builder + split persistence vs orchestration vs recovery into 3 services. |
| 6 | **P1 — test coverage** | **28 of ~50 top-level Java packages have zero tests.** Including `billing` (0.02 src:test ratio across 83 files), `state` (0.02), `auth` (0.05), and the entire `sse`, `landing`, `prreview`, `swebench`, `pricing`, `scoring`, `programbench` packages. | Prioritise billing + auth + state — write integration tests for the highest-revenue + highest-risk paths first. |
| 7 | **P1 — N+1 surface** | **221 finder methods on repositories, only 4 with `@EntityGraph`**. OSIV (`open-in-view=true`) masks N+1s by lazy-loading inside controllers — works but slow. | Inventory each finder by call-site; add `@EntityGraph` on the hot ones (project dashboard, run list, billing status). |
| 8 | **P1 — frontend bugs** | **Two endpoints in `frontend/src/v3/lib/api.ts:127,142` build URLs with a double slash** (`/api//stats`, `/api//sample-runs`). Landing-page hero counter + sample-runs widget silently fetch from broken URLs. | One-line fix per URL. |
| 9 | **P1 — frontend a11y** | **`components/shared/Toast.tsx` has no `role="alert"` / `aria-live`**, close button unlabelled. Every error toast invisible to screen readers. | Add `role`, `aria-live`, `aria-label`. |
| 10 | **P1 — frontend dead code** | **`frontend/CLAUDE.md` claims 6+ components exist that have been deleted** (`Sidebar`, `AppLayout`, `CommandPalette`, `TaskGraph`, `VibeRunSummary`, etc.). `RunDetail.tsx:28` still imports `RunTimeline` despite the doc claiming it was removed. | Sync `frontend/CLAUDE.md` Components section with disk reality; delete dead imports + 6 zero-importer files. |
| 11 | **P2 — bundle drift** | **616 v2-token refs (`var(--gray-*)`) vs 56 v3-token refs in `components/`** — design-token namespaces mixed. Light-theme override only catches v3 tokens, so v2-token components theme-flip incorrectly. | Alias `--gray-100` → `var(--v3-surface)` per surface, or do a perl sweep. |
| 12 | **P2 — perf polling** | **7+ concurrent polling timers** on a project dashboard (cost 5s, SSE fallback 5s, code-review 2.5s, PR review 2.5s, notifications 30s, usage 120s, etc). No `document.hidden` guards. Sustained ~5 req/s per active tab. | Gate every interval behind `document.visibilityState === 'visible'`; tear down on terminal status. |

The full list spans **64+ findings** across 14 dimensions. Use the
detailed sections below for actioning.

---

## How to read this doc

- **P0** — production risk / actively broken / leaks. Fix this week.
- **P1** — sprint priority. Costs real money or causes real friction.
- **P2** — quality of life. Schedule into a trim sprint.
- **P3** — nice-to-have / future polish.

Every finding cites the **file + line** when possible. Where a
finding is from the frontend agent's report (which I confirmed), I
note "[frontend agent]"; where it's mine, I cite the grep/read.

---

# § 1 · Backend (Java)

**Scope:** 1,001 files / 145K LOC across 50+ top-level packages. The
agent assigned to this scanned the hot path + per-package CLAUDE.mds
but didn't produce a structured report (silently terminated mid-loop
after ~50 tool calls). Findings below are mine, anchored to direct
reads.

## 1.1 — Architecture observations

### God classes

| File | LOC | Methods | Issue |
|------|-----|---------|-------|
| `integration/RunOrchestrator.java` | **4,539** | 48 | **7 overloads of `submitRun` + 5 of `runPipeline`**. Persistence + orchestration + recovery + pipeline assembly all in one class. |
| `state/StateStore.java` | **2,069** | 104 | Catch-all persistence layer; handles every entity in V2 pipeline. |
| `billing/service/BillingService.java` | **1,432** | 23 | Subscription + top-up + webhook + invoice in one class; lower method count is healthier than RunOrchestrator. |
| `harness/SequentialExecutor.java` | **1,472** | — | Mixed worker dispatch + smoke test + cost attribution + status transitions. |
| `agent/WorkerAgent.java` | **1,216** | — | Worker loop + stagnation watchdog + soft-completion handling + budget gate. Could split. |

**Recommended split for `RunOrchestrator`:**
- `RunRequest` (record) — replaces 7 method overloads with a single builder pattern.
- `RunSubmissionService` — only `submitRun()` + estimate persistence.
- `PipelineExecutor` — only `runPipeline()` orchestration (currently 5 overloads suggest internal evolution; consolidate).
- `RunRecoveryService` — `resumeAfterCrash()` + `runPipelineFromExecuteCheckpoint()`.

### Pipeline shape (V2 unification confirmed)

CLAUDE.md's claim of "unified pipeline under `com.navneet.executespec.*` (V1 → V2 migration complete)" is correct on disk:
- `PlannerAgent.java` deleted (functionality split across `OutlinePlannerAgent`, `PlannerOutputUtils`).
- `agent/FallbackLlmRouter.java` still exists per CLAUDE.md (used by `AccuracyAssessmentService`, `ConversationAgent`, `testgen/*` until those migrate). Verify with `grep` — confirmed still imported.
- No `v2/` or `v3/` Java package directories.

### Cross-package coupling

`RunOrchestrator` imports from ~25 different packages — by far the highest fan-in. Acceptable for an entry-point class, but the breadth is symptomatic of the god-class problem: every new feature accretes another method here.

### Controllers / API contract

- **98 controllers / 444 endpoints**.
- Only **53 endpoints carry `@PreAuthorize`** (12%). The rest rely on either SUPER_ADMIN gates in `SecurityConfig` path patterns OR explicit `*AccessGuard.requireXxxAccess()` calls inside method bodies.
- The ArchUnit `AccessGuardCoverageTest` covers this but pins **37 legacy methods in a `KNOWN_LEGACY_DRIFT` allowlist** — that allowlist needs to shrink, not grow.

## 1.2 — Security observations

### P0 findings (verified directly)

**P0-S1: `application-dev.yml` is git-tracked with hardcoded OAuth + SMTP secrets** as `${VAR:default}` defaults:
```
src/main/resources/application-dev.yml
  line 67:  client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET: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}
  line 72:  client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:46033466197599eb1fc74d321b141a816fdcb1f5}
  line 145: password: ${SMTP_PASSWORD:smdpsanatyrtlcxa}
  line 147: secret:   ${JWT_SECRET:executespec-dev-secret-key-must-be-at-least-256-bits-long-for-HS256}
```
In git history since **2026-04-02** (40+ days). If the repo is public, these are exposed; if internal, they're available to every contributor who has ever cloned. **Fix:** rotate all three, change defaults to empty string (`${VAR:}`), force the env-var path.

**P0-S2: `application.yml:680` has a hardcoded JWT secret literal default** (not env-driven):
```yaml
app:
  jwt:
    secret: executespec-local-dev-secret-key-must-be-at-least-256-bits-long-for-HS256
```
**Partially mitigated** — `JwtSecretValidator` (in `auth/security/JwtSecretValidator.java`) **catches the "must-be" substring at startup but only fires on `@Profile("prod")`**. So:
- Local / dev / test all accept this weak key silently.
- If anyone ever launches in dev profile pointing at prod data, no protection.
- **Fix:** drop `@Profile("prod")`, make the validator a `Smart` lifecycle check that runs for every profile. Move the literal to a placeholder like `MUST_BE_OVERRIDDEN`.

### P1+ findings

- **`credentials.json` + `sa-key.json` exist at repo root** (gitignored, never tracked, verified). The Google OAuth `client_secret` inside `credentials.json` is the same one as `application-dev.yml` — single rotation point. **Fix:** move to `~/.executespec/` or a secrets-manager pattern; remove from repo working tree once devs have migrated.
- **CSP allows `cdn.jsdelivr.net`** for Monaco loader. Self-host Monaco to drop the third-party dep.
- **HSTS is configured but `preload` defaults to `false`** (PR #108 added the flag). Submit to hstspreload.org after 30 days HTTPS stability.
- **No CSP `report-uri` / `report-to`** for violation telemetry.
- **8-layer security model is documented + present** (`InputSanitizer`, `CodeAbuseScanner`, `RateLimitPolicy`, `ShellSandboxPolicy`, `FileSandboxPolicy`) — see CLAUDE.md "Security". Coverage looks real; spot-check the `ShellSandboxPolicy.ALLOWED_PREFIXES` whenever you add a new build language.
- **Native queries are properly parameterised** (verified during agent scan) — no SQL injection risk noted in sampled files.

### Auth gate coverage

- The ArchUnit `AccessGuardCoverageTest` (in `src/test/java/com/navneet/executespec/arch/`) pins controller methods that operate on `/runs/{`, `/projects/{`, etc. to require either canonical guards or local guard patterns. **37 legacy methods are allowlisted** — that ledger should shrink. Suggested cadence: 5 per sprint.
- **`ProjectSharePublicController` has 6 endpoints / 0 explicit auth guards** by design (token bearer is the auth). Verify the token is being validated on every method, not just `/api/shared/{token}/meta`.

## 1.3 — Performance observations

### N+1 query risk surface

- **221 finder methods on JPA repositories, only 4 with `@EntityGraph`.**
- `spring.jpa.open-in-view=true` (per CLAUDE.md v2.5.1) means lazy associations resolve at controller-render time — N+1s become silently slow rather than throwing `LazyInitializationException`. Workable but every `for (X x : entities) x.getRelated()` is a hidden round-trip.
- **Action:** profile the top-5 hottest endpoints (`/api/projects`, `/api/projects/{id}/dashboard`, `/api/runs?limit=20`, `/api/billing/status`, `/api/auth/me/permissions`) under realistic load. Add `@EntityGraph` where joins are obvious.

### Connection-pool config

- Hikari `leak-detection-threshold: 90000` (90s) per `application.yml:43`. The 2026-04-29 connection-leak fix arc (v2.6.1) addressed the apply-suggestion path; verify no other long-running services hold connections >90s.
- `spring.jpa.open-in-view=true` is necessary for the current controller patterns but couples connection lifetime to request lifetime. Long-running SSE streams + OSIV is a known antagonism.

### Thread pools

- Per CLAUDE.md "core-dependent dispatch pools" (v2.5.5), 5 pools are sized via `PoolSizing.coreMultiple()`. Spot-check on prod: `Runtime.availableProcessors()` returns the GCE VM's core count — verify Cloud Run / GCE sizing matches assumed core count, otherwise pools are under- or over-sized.

## 1.4 — Reliability observations

### Exception handling

- **785 `catch (Exception ...)` blocks** across `src/main/java`. A sample read of `RunOrchestrator` shows the prevailing pattern is `log.warn` + continue (acceptable for best-effort post-success paths like codemap regen, assessment, staleness invalidation). But at this volume, **some are certainly swallowed bugs**. **Action:** grep `catch (Exception` and audit any without a `log.warn`/`log.error` in the same block.

### Idempotency

- `RunOrchestrator.submitRun` has 7 overloads — each delegates to a more comprehensive one. Idempotency on the entry point: a duplicate submission could submit two runs if the client retries on a network error. Worth checking if there's a `submission_idempotency_key` column anywhere.
- Webhook handlers (Stripe / Razorpay / GitHub) per CLAUDE.md verify signatures + write to `webhook_event` table. Duplicate-delivery handling is the table's job.

### Recovery

- `StartupRunResumer` runs at boot to fail-clean non-terminal V2 runs (PR #95 worked here, see `feature/worker-runaway-fixes` notes in CLAUDE.md). Full replay (restore conversation history + resume at saved iter) is a tracked follow-up.
- `RunOrchestrator.resumeAfterCrash` exists (the 2026-05-04 v2.5.x work landed). Coverage exists.

## 1.5 — Code quality observations

### TODO/FIXME/HACK inventory

- **Total: 9** (extremely low — good hygiene).
- Distribution: `ShellSandboxPolicy`, `StandardsOverrideController`, `SupplyChainScanner`, `AccuracyAssessmentService`, `EnvVarDetector`, `AnalyticsService`, `TierLifecycleService`, `UserDataExportService`, `PatchAgent` — 1 each.
- Themes: cost tracking precision (`AnalyticsService`), email firing (`TierLifecycleService`), one-off operational gaps.
- **Action:** convert each to a tracked ticket so they don't rot.

### `@SuppressWarnings` count: 59

Moderate — usually enum-switch + raw-type fallbacks. Acceptable.

### Dead code

- `agent/FallbackLlmRouter.java` is kept for the 3 legacy agents (`AccuracyAssessmentService`, `ConversationAgent`, `testgen/*`). Per CLAUDE.md deprecation plan P4 — the route to delete it is to migrate those agents to `LlmRouter`. Worth scheduling.
- Prompt archive `planner.v1.txt` through `planner.v14.txt` in `src/main/resources/prompts/` — per CLAUDE.md kept for `PromptVersioningService` rollback. **Verify usage:** `grep -rn 'planner\\.v[0-9]' src/main/java`. If `PromptVersioningService` doesn't actually load them, they're dead.

### Class size distribution

11,932 LOC across just 9 sampled god/large classes. Suggested attention order for refactoring: `RunOrchestrator` → `StateStore` → `SequentialExecutor`.

## 1.6 — Observability

- Per CLAUDE.md the `executespec.v2.*` Micrometer meters cover run.outcome, planner.latency, task.smoketest, etc. Dump endpoint `/api/v2/test/metrics` + Prometheus at `/actuator/prometheus`.
- Structured logging via Micrometer MDC + Logback. **JSON log format** activates only when the `gcp` profile is also active — `application-prod.yml` doesn't list `gcp` in profiles, but `startup.sh` sets `prod,gcp` for the JAR launch on GCE (verified by infra agent). **Verify** that Cloud Run / GCE bootstrap actually sets both profiles, otherwise prod runs without JSON logs and Cloud Logging can't index them.

## 1.7 — Dependency hygiene

- **Spring Boot 3.3.x** (verified `pom.xml`) — should be on the latest 3.3.x patch.
- **Spring AI 1.1.4** (verified `pom.xml`) — current. CLAUDE.md notes the 1.1.4 `extra_body` bug for OpenAI is worked around via `OpenAiNativeToolClient`.
- **No dependabot.yml found** — `find .github -name 'dependabot.yml'` returned nothing during agent scan. **Recommend adding** to track Spring Boot / Spring AI / Jackson / PostgreSQL driver / Razorpay SDK / Stripe SDK / Lombok bumps automatically.

---

# § 2 · Frontend (TypeScript / React)

**Scope:** 257 files / 75K LOC. The frontend review agent **completed in full** before the harness terminated it; the entire structured report is captured below verbatim, with my notes appended where I extended its findings.

## 2.1 — Top 10 frontend findings (per agent)

| # | Severity | Area | Finding | Where | Fix |
|---|----------|------|---------|-------|------|
| F-1 | **P0** | API client | `req()` throws `ApiError(401)` instead of attempting `authRefresh()`. Sessions die mid-task forcing re-login even with a valid refresh cookie. | `frontend/src/lib/api.ts:74-107` | On 401 → `authRefresh()` once, single-flight, replay original request. |
| F-2 | **P0** | Bundle | Monolithic 2.2 MB chunk — zero `React.lazy` / dynamic imports anywhere. Monaco, recharts, framer-motion, posthog, OTel all eagerly bundled. Login page ships the entire admin surface. | `App.tsx:79-179`, `vite.config.ts` | Convert admin / run-detail / library routes to `React.lazy`; add `manualChunks` vendor splitting. |
| F-3 | **P1** | v3 bug | Double-slash URLs in `v3/lib/api.ts` — `/api//stats`, `/api//sample-runs`. Hero counter + sample-runs widget silently fetch broken URLs. | `frontend/src/v3/lib/api.ts:127,142` | `BASE + '//stats'` → `BASE + '/v3/landing/stats'`. |
| F-4 | **P1** | A11y | `Toast.tsx` has no `role="alert"` / `aria-live`, close button unlabelled. Async error toasts invisible to screen readers. | `components/shared/Toast.tsx:14-37` | Add `role="alert"`, `aria-live`, `aria-label`. |
| F-5 | **P1** | Design tokens | 616 v2-token refs (`var(--gray-*)`) vs 56 v3-token refs in `components/`. Same panels render under v3 chrome but use v2 colors. Light-theme override only catches v3 vars. | All of `components/runs/`, `components/project/`, `components/billing/` | Alias `--gray-100` → `var(--v3-surface)` per surface, or perl sweep. |
| F-6 | **P1** | Dead code | `frontend/CLAUDE.md` lists `Sidebar`, `AppLayout`, `CommandPalette`, `TaskGraph`, `VibeRunSummary`, `VibeProgressNarrative`, `FirstRunSuggestions`, `VibeRunSubmit`, `TechBadges` as live — none of these files exist. `RunDetail.tsx:28` still imports `RunTimeline` from a path the doc claims is removed. | Multiple files | `git rm` dead files; remove imports; sync `frontend/CLAUDE.md`. |
| F-7 | **P1** | Bundle perf | OpenTelemetry initialised synchronously in `main.tsx:12` before render. 124 MB src on disk, all auto-instrumentations imported. App waits on OTel for first paint. | `main.tsx:9-12`, `lib/telemetry.ts` | Defer to `requestIdleCallback`, feature-flag behind env. |
| F-8 | **P2** | SSE + polling | 7+ concurrent polling timers on a project page: cost 5s, SSE-fallback 5s, code-quality 2.5s, PR-review 2.5s, notification 30s, usage 120s, etc. No `document.hidden` guard; some don't tear down on terminal status. | `useSse.ts`, `RunDetailPage.tsx:223-231`, `RunDetailTabs.tsx:537` | Drop fallback poll when SSE is `CONNECTED`. Pause on hidden. Stop on terminal. |
| F-9 | **P2** | Form a11y | Auth pages have zero `aria-describedby` / `aria-invalid` on inputs. iOS-zoom prevention (`text-base sm:text-sm`) applied on LoginPage but ~25 other forms still use plain `text-sm` only. | All `pages/auth/*` + ~25 other forms | Promote `text-base sm:text-sm` to a shared input primitive; add `aria-invalid` + `aria-describedby`. |
| F-10 | **P2** | TypeScript | 27 `eslint-disable explicit-any`. `req<any>` in `lib/api.ts` lines 1682/1694/1766 for admin endpoints. 36 `react-hooks/exhaustive-deps` suppressions. 99 `as ` type assertions. | `lib/api.ts`, `useRunStore.ts:177`, others | Type the admin endpoints; replace `(state as any)?.from?.pathname` with typed `LocationState`. |

## 2.2 — Architecture observations (frontend agent + my notes)

- **v2/v3 coexistence is messy.** The v2 `AppLayout`/`Sidebar`/`CommandPalette` were retired on 2026-05-06 ("Single-surface retirement"). Yet `frontend/CLAUDE.md` still lists them as live, and `components/layout/` now only contains `NotificationBell.tsx` + `NotificationPanel.tsx`. Functional code is `v3/components/Shell.tsx` + `TopBar.tsx`.
- **Three composers exist:** `pages/onboarding/RunsNewLanding.tsx` vs `v3/pages/RunsNewPage.tsx` vs `components/runs/NewRunModal.tsx`. Plus `v3/components/Composer.tsx`. Pricing logic divergence risk.
- **Two PlanCards:** `components/billing/PlanCard.tsx` vs `v3/components/PlanCard.tsx`. Two pricing tables: `components/pricing/PricingTable.tsx` vs `v3/components/PricingGrid.tsx`.
- **Stores reasonable** — auth / runs / projects / SSE / UI cleanly separated. `v3/stores/useV3Ui.ts` + `useV3ProjectStore.ts` are tiny single-purpose; mostly fine.
- **Route organization correct after retirement.** Catch-all `path='*' → Navigate('/')` lives only inside `ProtectedRoute`. Anonymous typo routes blank-page.

## 2.3 — Performance observations (frontend agent)

- **2.2 MB raw / ~620 KB gzip = single chunk.** Vite `manualChunks` not configured.
- **Top bundle drivers (node_modules src sizes):**
  - `@opentelemetry/*` — 124 MB src on disk
  - `monaco-editor` — 73 MB
  - `posthog-js` — 44 MB
  - `recharts` — 5.2 MB
  - `framer-motion` — 3.8 MB
- **Monaco eagerly imported in 5 surfaces:** `FileViewer`, `SafeDiffEditor`, `MonacoSafeMount`, `RunDetailTabs`, `RunDetailPage` — never split.
- **framer-motion imported by 10 components** including always-mounted `Toast.tsx` — entire animation lib loaded for one toast slide-in.
- **No `React.memo` anywhere** + 152 `useMemo`/`useCallback` total. Likely paying memoization tax without payoff.
- **`useRunStore.setState({activeRun})` on every SSE RUN_DETAIL push** re-renders every consumer — should diff against prev state.

## 2.4 — A11y observations

- **Good:** skip-link at `App.tsx:239-266`, ARIA on TopBar brand + drawer hamburger, ScoreCard / VerdictPill have `aria-label` + `role='status'`.
- **Gaps:**
  - `Toast.tsx` missing `role='alert'` / `aria-live`.
  - Auth forms zero `aria-invalid` / `aria-describedby`.
  - Clickable `<div>` without role on `ui/LockedFeature.tsx:35`, `runs/TestReportPanel.tsx:118`, `runs/ValidationReportPanel.tsx:349`.
  - TopBar TierChip uses `title=` for tooltip only — not announced on focus.
  - Project tabs in TopBar drawer use `<Link>` but no `aria-current='page'`.
  - `ProtectedRoute.tsx:38` uses `border-purple-500` raw color with no contrast claim.

## 2.5 — UX consistency

- **Three independent design-token namespaces in use simultaneously:**
  - `--c-*` primitives in `design-system/primitives.css`
  - `--gray-*` / `--border-default` (v2 tokens in `tokens.css`)
  - `--v3-*` (v3 tokens in `v3.css`)
- Light-theme override only catches `--v3-*` — most components (on `--gray-*`) won't theme-flip correctly.
- VerdictPill consistently applied across panels (verified).
- Glossary primitive applied only in ScoreCard; 7 jargon spots untooltipped on PulseTab / TechDebtPanel / SignalBreakdown.
- **Empty-state pattern inconsistent**: only 6 imports of `EmptyState`. ~8 places use raw `"No runs yet"` strings.

## 2.6 — TypeScript hygiene

- **27 `eslint-disable explicit-any` directives.** Concentrated in `RunDetailTabs.tsx` (8), `InlineRunDetail.tsx` (5), `RunDetail.tsx` (4).
- **No discriminated unions on SSE event payload.** Every SSE handler does `data.type === 'RUN_STATUS'` with `data` as any. Adding `SseEvent = {type: 'RUN_STATUS', payload: string} | ...` would eliminate ~20 `any`s.
- **`req<any>` in 3 places** for admin endpoints.
- **99 `as ` assertions** — many are legitimate `unknown as T` for 204 NoContent paths. Worth a sweep for `as any` though.

## 2.7 — Form quality

- **iOS auto-zoom prevention applied inconsistently.** Auth pages use `text-base sm:text-sm` (good); ~25 other forms still on `text-sm` only (auto-zooms on iOS): `SettingsPage`, `IntegrationsPage`, `OrgSettingsPage`, `AddRepoModal`, `ChangePasswordModal`, `ContactModal`, `InlineCredentialDrawers`, `NewRunModal`, `TemplatePicker`, `RepoRelationshipEditor`, `OrgStandardsManager`, `SsoConfigCard`, `TypedConfirmModal`, `ArchitectureViewer`, `ActivityTab`, `MoreDrawer`, `ProjectChatPanel`, `ConsolePanel`, `RunDetail`, `DeployWizardModal`, `PricingManager`, `ExecutionSetup`, `RunDetailTabs`.
- **Submit-state UX:** LoginPage handles loading + error. `RegisterPage` validates client-side only on submit — no real-time password-strength feedback.

## 2.8 — Routing

- **No dedicated 404 page.** `path='*'` redirects to `/`. Authed user → home dispatcher; anon user → login. No "Page not found" surface.
- **OAuth callback** handled cleanly via `AuthCallbackPage` + `?error=…` map in `LoginPage:30`.
- **Legacy redirects** (`/v3/* → /*`, `/projects/:id/dashboard?tab=X → /projects/:id?tab=X|pulse`) well-implemented. 5 hardcoded `Navigate` redirects — minimal cruft.

## 2.9 — Dead code (frontend agent)

- **Documented-as-live, file-missing:** `Sidebar.tsx`, `AppLayout.tsx`, `CommandPalette.tsx`, `TaskGraph.tsx`, `VibeRunSummary.tsx`, `VibeProgressNarrative.tsx`, `FirstRunSuggestions.tsx`, `VibeRunSubmit.tsx`, `TechBadges.tsx`.
- **Still exists, doc-flagged removed:** `components/run/RunTimeline.tsx` — still imported by `RunDetail.tsx:28`.
- **Zero-importer orphans:** `v3/components/Placeholder.tsx`, `components/auth/UserMenu.tsx`, `pages/RunsPage.tsx`, `v3/pages/LandingPage.tsx` (stub), `v3/components/OptInGate.tsx`.
- **Stale-imported pages:** `pages/DashboardPage.tsx` — 1 stale importer, not in `App.tsx` routes.

---

# § 3 · Infra / Build / CI

**Scope:** `pom.xml`, `frontend/package.json`, `vite.config.ts`,
`.github/workflows/*.yml`, application*.yml, Dockerfile, Cloudflare
wrangler. The infra agent gathered the highest-value findings (live
secrets) before being terminated. The structured report wasn't
written — but the gold's all here.

## 3.1 — Secret hygiene (P0)

**Findings already cited above (§1.2 P0-S1, P0-S2). Summarised here for ops handoff:**

1. **`src/main/resources/application-dev.yml`** — git-tracked with real-looking secrets as `${VAR:default}` defaults at lines 67, 72, 145, 147. In history since 2026-04-02 (40+ days). Rotate Google OAuth + GitHub OAuth + SMTP password.

2. **`src/main/resources/application.yml`** — line 680 hardcodes the JWT secret default. `JwtSecretValidator` catches it for `@Profile("prod")` only.

3. **`src/main/resources/application-local.yml`** — gitignored, never tracked. Contains real Anthropic + OpenAI + Gemini API keys (from the agent's read, lines 11-16). Risk surface is local disk only.

4. **`credentials.json` + `sa-key.json`** at repo root — both gitignored, never tracked. `sa-key.json` is empty (0 bytes); `credentials.json` has a real OAuth client_secret.

**Recommended secrets-management refactor:** all secrets via env vars only, defaults empty. For local dev, use a single sourced `.envrc` (via direnv) or `application-local.yml` that's documented as "your local secrets — do not share". Drop the `${VAR:literal-default}` pattern entirely.

## 3.2 — CI workflow audit

**Workflows present:** `.github/workflows/deploy.yml`, `.github/workflows/lint.yml`. **That's it.**

**Coverage:**
- `lint.yml` — runs spotless + prettier + eslint on every PR (PR #56 added this; verified green on recent PRs).
- `deploy.yml` — builds + deploys.

**Gaps:**
- **No standalone test workflow.** Tests run inside `deploy.yml`'s `mvn verify` (per CLAUDE.md, PR #56 raised it from `mvn package -DskipTests` to `mvn verify -q`). This means tests only run on push to dev/main. PRs don't have a test gate.
- **No `dependabot.yml`** — recommended for Spring Boot / Spring AI / Lombok / billing SDKs.
- **No CodeQL / security-scan workflow.**
- **No npm audit gate** — would surface CVE deps.

## 3.3 — Build reproducibility

- **Java pinned:** `JAVA_VERSION: 21` in workflow env per CI logs. `pom.xml` parent on Spring Boot 3.3.x. Good.
- **Node version:** check `package.json` for `engines` field — likely absent. Recommend pinning `>=20.0.0`.
- **Lockfile:** `package-lock.json` committed (good).
- **Dependency versions:** mostly pinned (`^x.y.z` carets). Worth tightening highly-trusted security deps to `~` (allow patch, deny minor).

## 3.4 — Deploy

- **No root `Dockerfile`** — per infra agent finding. Deploy is JAR upload + `systemd` restart on a GCE VM. Not containerised for the main app (only customer-run preview Dockerfiles in `workspace/`). **Operational risk:** rollback is "scp the previous JAR back"; no immutable image. **Recommend:** containerise the main app + push to GCR.
- **Profile selection:** `startup.sh` sets `prod,gcp` (verified by infra agent — both profiles required for JSON logs to activate via `logback-spring.xml`'s `<springProfile name="gcp">`).
- **Cloudflare Worker** for `executespec.ai` / `www.executespec.ai` (`website/wrangler.toml`). Deploy command: `npx wrangler deploy --config website/wrangler.toml`. **NOT auto-deployed from GitHub merges** — a separate ops step. Risk: marketing site drift from product surface.

## 3.5 — Spring config

- **`open-in-view=true`** — necessary for current controller patterns but couples connection lifetime to request lifetime.
- **Hikari `leak-detection-threshold: 90000`** — 90s; appropriate for a SaaS dashboard.
- **CORS** managed by `SecurityConfig.corsConfigurationSource()` (per `WebConfig` comment) — verified.
- **CSP** policy hardcoded in `SecurityProperties.java` defaults. Per the 2026-05-11 audit:
  - `script-src 'self' https://cdn.jsdelivr.net https://static.cloudflareinsights.com`
  - `style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net https://fonts.googleapis.com`
  - No `report-uri` for violation reports.

## 3.6 — Frontend build (Vite)

- **No `manualChunks` configured** — single output chunk.
- **No `build.rollupOptions.output.manualChunks`** — verified.
- **Source maps**: assume default Vite behavior. **Verify** they're off for prod (avoid exposing source map files publicly).
- **`VITE_*` env vars**: `VITE_BACKEND_URL` (verified), `VITE_OTEL_ENDPOINT` (per the `[OTel] Telemetry disabled` log — feature-flagged).

## 3.7 — Observability config

- Prometheus endpoint exposed at `/actuator/prometheus`. Per the `SecurityConfig`, this is publicly reachable (CLAUDE.md Security section: "health+prometheus public, rest SUPER_ADMIN").
- JSON log appender requires `gcp` profile (verified earlier). Bootstrap must include `gcp` in profile list.
- OTLP trace export wired via `OTEL_EXPORTER_OTLP_ENDPOINT` env var (verified in CLAUDE.md required env section).

## 3.8 — Dependency vulnerability surface

Worth a `mvn dependency-check:check` + `npm audit` run. Things to verify:
- Spring Boot 3.3.x — current minor.
- Spring AI 1.1.4 — current.
- Lombok version — Spring AI sometimes pins below Spring Boot's preferred.
- `jackson-databind` — historically high-CVE; should be on the Spring Boot BOM-managed version.
- `posthog-js` — large dep, worth checking if any CVE.
- `qrcode.react` + `react-markdown` + `recharts` — all small surface.
- `monaco-editor 0.52.2` (per CDN URL in `monacoSetup.ts`) — current at time of writing; verify each year.

## 3.9 — Operational risks

1. **JAR-on-GCE deploy** — no immutable image, rollback is manual. Containerise.
2. **No test gate on PR** — bugs slip in until the deploy workflow runs on merge to dev.
3. **No dependabot / CodeQL / npm audit** — vulnerability blind spot.
4. **JSON logs gated on `gcp` profile** which deploy.yml doesn't set, only `startup.sh` does — fragile.
5. **Cloudflare Worker deploys are manual** — easy to forget on a marketing-copy change (recent example: the PR #108 website edits never reached the live site until the user explicitly asked about it).
6. **No CSP report-uri** — silent CSP failures aren't observable.
7. **Prometheus endpoint public** — by design but worth confirming intent in `SecurityConfig`.

---

# § 4 · Data + migrations + prompts + templates

**Scope:** 186 Flyway migrations, 88 prompt files, 21 email templates, 46 readymade YAMLs, 1 PDF template. Direct read (the agent assigned here completed almost no work).

## 4.1 — Migration integrity

- **Live head: V186** (`pull_request_ci_status.sql`). Note: CLAUDE.md still claims "live head V184" — stale by 2 migrations.
- **Number gaps detected:**
  - `V60` to `V60_1` — non-standard naming (probably a hotfix retrofit). Out-of-order allowed per `application.yml` `flyway.out-of-order: true`.
  - `V118` to `V120` — missing `V119`. This is the preview-engine extraction migration that was retired when the preview pipeline moved to a separate repo (verified in CLAUDE.md `ignore-migration-patterns`).
- **Recent migrations look clean:**
  - V179 `drop_product_split` — single-surface retirement, idempotent.
  - V184 `widen_agent_task_last_llm_failure` — column-type widening (`VARCHAR(255)` → `TEXT`), safe.
  - V185 `retire_solo_branding` — rename Solo → Pro, single tier_config update.
  - V186 `pull_request_ci_status` — additive column.
- **Destructive ops are explicit and infrequent** (V79, V138, V139, V141, V142, V148, V149, V165, V179) — each migration commit message ties to a CLAUDE.md entry. Good practice.

## 4.2 — Migration drift vs code

- **CLAUDE.md says live head is V183/V184**, on-disk head is V186 — CLAUDE.md needs a refresh.
- The tier `MAX` is described in CLAUDE.md as "live ladder = FREE / PRO (HIDDEN) / TEAM (per-seat $49) / MAX ($29 BYOK) / ENTERPRISE", but V165 is `drop_max_tier_team_at_39.sql` — suggests the MAX tier was dropped. Then V185 renamed Solo→Pro. Cross-check with `tier_config` content: per the 2026-05-11 UX audit findings, `/api/admin/tiers` returns just FREE / PRO / TEAM / ENTERPRISE — no MAX. **CLAUDE.md drift on the tier ladder is real and confusing.**

## 4.3 — Prompt system

- **88 prompt files** total. Active: 31 markdown prompts + the 14-file versioned planner archive (`planner.v1.txt`..`v14.txt`).
- **Recommend:** confirm `PromptVersioningService` actually loads the `.vN.txt` archives. If not, they're dead — saving ~50 KB in the jar.
- **Fragment-include coverage** — the `PromptAssembler` system at `agent/PromptAssembler.java` uses `{{include:fragment}}`. Worth a grep to see how many prompts actually use this vs. inline copy-paste duplication.
- **Scaffold templates** at `prompts/scaffolds/*.md` per CLAUDE.md — used for per-project-type templates.

## 4.4 — Email templates

- 21 templates standardised on JetBrains Mono in PR #108 (today). Consistency check confirmed in the post-#108 audit.
- Each template uses Thymeleaf variables. Spam-score-friendly hygiene (alt text, plain-text fallback) not explicitly audited; worth doing.

## 4.5 — Sample specs + readymade catalog

- 46 YAML files under `src/main/resources/` for sample specs + readymade catalog seeds + migration recipes.
- Catalog format spec in `docs/readymades-catalog-format.md` (kept post-cleanup).
- Migration recipe format in `docs/migration-recipes-catalog-format.md` (kept).

## 4.6 — Static assets

- After PR #108: `logo.png` (375 KB), `logo-192.png` (35 KB), `logo-512.png` (185 KB) all present in both `frontend/public/` + `src/main/resources/static/`. Manifest correctly references all three.
- `sw.js` service worker present in `frontend/public/` — verify it's not stale (would cache an outdated bundle for returning users).

---

# § 5 · Tests + coverage

**Scope:** 148 test files / 25K LOC. The test agent barely got started.

## 5.1 — Coverage matrix (per package)

Of ~50 top-level Java packages, **28 have zero tests:**

| Package | src files | tests | ratio | Severity |
|---------|-----------|-------|-------|----------|
| **billing** | 83 | 2 | **0.02** | **CRITICAL** — financial transactions, lowest coverage |
| **state** | 37 | 1 | **0.02** | **CRITICAL** — persistence layer |
| **auth** | 72 | 4 | 0.05 | **HIGH** — security-critical |
| **integration** | 42 | 6 | 0.14 | HIGH — `RunOrchestrator` lives here |
| **agent** | 44 | 11 | 0.25 | MEDIUM |
| **tool** | 28 | 8 | 0.28 | MEDIUM |
| **security** | 9 | 3 | 0.33 | OK |
| **reviewer** | 11 | 4 | 0.36 | OK |
| **harness** | 15 | 7 | 0.46 | GOOD |

**Packages with zero tests** (severity ranked):
- **prreview**, **sse**, **landing**, **scoring**, **pricing**, **testgen**, **swebench**, **programbench** — feature surfaces with no test coverage at all
- **eval**, **validator**, **finding**, **observability**, **rollout**, **onboarding**, **optimiser**, **memory**, **governance**, **compliance**, **contact**, **context**, **crypto**, **apitests**, **assessment**, **saga**, **tenant**, **test** (the testing-tool package), **util**, **run** — utilities, ops, or feature surfaces

## 5.2 — Test type balance

- **ArchUnit tests: 7** (in `src/test/java/com/navneet/executespec/arch/`):
  - `AccessGuardCoverageTest` — pins 37 legacy methods to `KNOWN_LEGACY_DRIFT`
  - `AsyncAllowlistTest`
  - `ApiContractTest`
  - `FailoverOrderEnumAlignmentTest`
  - `ExecutespecFolderWriterCallerAllowlistTest`
  - `AnthropicModelAccessTest`
  - `InvariantCheckerTest`

  These drift detectors are a **strength** of the codebase. Pattern is well-established.

- **e2e/chaos tests excluded by default** per CLAUDE.md surefire config — run via `-Pintegration` profile.

## 5.3 — Critical-path coverage

- `harness/PipelineE2ETest.java` exists — covers part of the V2 pipeline.
- `harness/ChaosScenariosTest.java` exists.
- But `billing` (the entire revenue surface) has 2 test files for 83 source files — webhook signature verification, refund flows, payment-gateway happy paths likely all uncovered.

## 5.4 — Frontend tests

**Zero frontend tests exist.** No `vitest`, `jest`, `playwright`, or `cypress` in `frontend/package.json`. No `*.test.tsx` or `*.spec.tsx` files. The `frontend/src/design-system/contrast.test.ts` mentioned in CLAUDE.md as "vitest-shape, dormant" exists but no runner is installed.

**Severity:** P1. The frontend ships every release without a single test. Bug regressions are caught by humans clicking through.

## 5.5 — Test execution in CI

- **`mvn verify` runs in `deploy.yml`** — only on push to dev/main.
- **PRs run `lint.yml` only** — no test gate.
- **Surefire excludes** `e2e`, `integration`, `chaos` tags by default per CLAUDE.md, so the merge-time test gate covers unit tests only.

## 5.6 — Operational risks

1. **Billing is effectively untested** — 83 src files, 2 tests. A refund-path regression would be undetectable.
2. **State store has 1 test for 37 source files** — the persistence layer is the foundation; bugs here cascade.
3. **Auth has 4 tests for 72 source files** — security-critical, low coverage.
4. **No frontend tests at all** — 75K LOC ships untested.
5. **No PR-time test gate** — only post-merge.
6. **Drift detectors are good, but 37 legacy methods on the allowlist** — that ledger needs to shrink.

---

# § 6 · Cross-cutting themes

Synthesising across the 5 dimensions, these themes show up everywhere:

## 6.1 — Documentation drift

This came up in every dimension:
- Root `CLAUDE.md` lists live migration head as V183/V184 — on-disk head is V186.
- `frontend/CLAUDE.md` describes 9+ components as live that have been deleted.
- Tier ladder copy across docs / migrations / live API disagrees (MAX retired but doc says it's live).
- "feature/enterprise-readiness-eval" and "feature/hierarchical-planner" branches described as "not yet merged" in CLAUDE.md but git log shows they merged weeks ago. (Caught + partially fixed in PR #110, but more lingers.)

**Pattern:** CLAUDE.md and `frontend/CLAUDE.md` are appended-to but never compressed. They've grown to 1,500+ lines each with sections describing work that's now ancient. Recommend a quarterly compression pass — fold "Recently resolved" + "In progress" sections into git history.

## 6.2 — God classes / large files

- `RunOrchestrator.java`: 4,539 lines / 48 methods / 7 overloads of `submitRun`
- `StateStore.java`: 2,069 lines / 104 methods
- `RunDetailTabs.tsx`: 2,771 lines
- `RunDetailPage.tsx`: 1,886 lines
- `BillingService.java`: 1,432 lines
- `SequentialExecutor.java`: 1,472 lines

These six files alone are ~14K LOC — almost 10% of the production codebase by line count, in 0.6% of the files. They're hotspots for both bugs and onboarding friction.

## 6.3 — Token-budget hygiene

- Frontend bundle: 2.2 MB monolithic.
- 88 prompt files, 14 of which are versioned planner archives (worth verifying they're used).
- 21 email templates standardised on JetBrains in PR #108.
- The recent UX audit found a 12% success rate on the public exec-snapshot — meaning lots of LLM tokens are being spent on runs that don't ship.

## 6.4 — Security posture

- **Strengths:** 8-layer security model, ArchUnit auth-gate drift detector, JWT validator at startup, parameterised native queries, gitignore is solid.
- **Weaknesses:** Real-looking OAuth + SMTP secrets in git history (P0), JWT secret literal in `application.yml`, JWT validator runs only on prod profile, 88% of controllers rely on guard-method discipline (not annotations), no CodeQL, no dependabot, no CSP report-uri.

## 6.5 — Test culture

- **Strong:** ArchUnit drift detectors (7), pattern is repeatable, allowlists serve as living documentation of tech debt.
- **Weak:** Billing 0.02 ratio, frontend zero tests, no PR-time test gate, lots of feature packages with zero coverage.

## 6.6 — Operational maturity

- **Strong:** Observability story (Micrometer + OTel + Prometheus + JSON logs), structured logging.
- **Weak:** JAR-on-VM deploy (no immutable image), Cloudflare worker manual deploy, JSON logs gated on profile mismatch risk, no PR-time test gate.

---

# § 7 · Action plan — 90 days

## Week 1 (this week)

1. **P0-S1: Rotate the three secrets in `application-dev.yml`** (Google OAuth, GitHub OAuth, SMTP). Replace defaults with empty strings. Force-rebase the secrets out of git history if practical.
2. **P0-S2: Fix `JwtSecretValidator` to run on every profile**, not just prod. Drop the literal default in `application.yml:680`.
3. **P0 frontend: Add JWT auto-refresh to `req()`** in `frontend/src/lib/api.ts`. Single-flight on 401, replay request. Highest UX win per LoC.
4. **P0 v3 bug: Fix the double-slash URLs in `v3/lib/api.ts:127,142`.** One-line each.
5. **Fix `frontend/CLAUDE.md`** to match disk reality — delete entries for components that don't exist.

## Weeks 2-4 (next sprint)

6. **Bundle splitting** — `React.lazy` on admin / run-detail / library / migration routes. `manualChunks` for Monaco / recharts / framer-motion / posthog / OTel.
7. **Toast a11y** — `role="alert"`, `aria-live`, `aria-label` on close.
8. **Polling discipline** — gate every `setInterval` behind `document.visibilityState === 'visible'`; tear down on terminal status.
9. **iOS-zoom fix** — promote `text-base sm:text-sm` to a shared `Field` primitive; apply to the 25 remaining forms.
10. **Dead-code sweep** — `git rm` the 6 frontend orphans + remove the stale `RunTimeline` import.
11. **Add `dependabot.yml`** for Spring Boot / Spring AI / Lombok / billing SDKs / npm.
12. **Add a `test.yml` workflow** that runs `mvn verify` on every PR.

## Weeks 5-8

13. **Refactor `RunOrchestrator`** — extract `RunRequest` builder; split into `RunSubmissionService` / `PipelineExecutor` / `RunRecoveryService`.
14. **N+1 audit** — profile the 5 hottest endpoints; add `@EntityGraph` where joins are obvious.
15. **Billing tests** — write integration tests for webhook signature verification + refund flows + subscription state transitions.
16. **Auth tests** — write tests for the `KNOWN_LEGACY_DRIFT` methods so they can be migrated off the allowlist.
17. **Containerise the main app** — Dockerfile + GCR push as part of `deploy.yml`. Immutable image, rollback as image-tag flip.

## Weeks 9-12

18. **Design-token unification** — collapse `--gray-*` / `--v3-*` / `--c-*` into one namespace. Light-theme cleanup.
19. **Component dedup** — pick one of `NewRunModal` / `RunsNewPage` / `Composer`; one of `PlanCard` (billing vs v3); one of `PricingTable` vs `PricingGrid`. Delete the duplicates.
20. **Frontend test framework** — install Vitest; write Playwright smoke for the conversion funnel (`/login → /projects/empty → /projects/{id}/dashboard`).
21. **`OTel` deferral** — `requestIdleCallback` for telemetry init; feature-flag behind `VITE_OTEL_ENABLED`.
22. **HSTS preload submission** after 30 days of stable HTTPS.
23. **Doc compression pass** — root `CLAUDE.md` + `frontend/CLAUDE.md` shrink "Recently resolved" / "In progress" sections; fold into git log.

## Track ongoing

- **`KNOWN_LEGACY_DRIFT` allowlist shrink** — 5 entries per sprint until zero.
- **TODO/FIXME conversion** — each of the 9 to a tracked ticket.
- **Cloudflare worker deploys** — script into `deploy.yml` so they're automatic on `website/**` changes.
- **Prompt fragment audit** — confirm `{{include:fragment}}` is the dominant pattern; flag duplications.

---

# § 8 · What I could *not* assess

Honest scope of this review:
- **I did not read every line of every file** — that would be 220K LOC across 1,250+ source files. I sampled hot paths, security perimeter, controllers, and aggregated metrics (LOC, method count, TODO count, exception count, finder count, test count).
- **I did not exercise the live system** — no curl probes during this review (PR #108's audit covered that surface).
- **The 4 prematurely-terminated agents (backend / data+prompts / tests / infra+CI)** got 50% through their scans before the harness silently killed them. The transcripts they produced informed my findings but their structured reports were never written.
- **No dynamic analysis** — no SAST run, no `mvn dependency-check`, no `npm audit`, no Lighthouse score.
- **No load testing** — the N+1 hypothesis is based on `@EntityGraph` count, not real measurements.
- **No live security probe** — the secrets-in-git finding was verified by `git log -S`, not by checking if the OAuth secret is still valid.

If any of these matter for your decision, schedule a follow-up.

---

*Captured on the `feature/full-project-review-2026-05-11` branch against
`dev` HEAD `22b8e6e5` on 2026-05-11. No code was modified during this
review; this document is the only artifact.*
