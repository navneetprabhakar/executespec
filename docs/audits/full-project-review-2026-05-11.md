# ExecuteSpec — Full Project Review · v2 · 2026-05-11

**Reviewer:** independent end-to-end review, unbiased.
**Branch under test:** `dev` HEAD `22b8e6e5` (PR #108 merged). Reviewed
on branch `feature/full-project-review-2026-05-11`.
**Scope:** every dimension — backend (145K LOC / 1,001 files / ~50
packages), frontend (75K LOC / 257 files), infra/build/CI, data layer
(186 migrations + 88 prompts + 21 email templates + 46 sample YAMLs),
tests (148 files / 25K LOC), security + observability + cost +
dependency hygiene.

**Method (this v2):** direct in-loop review by the model. **The v1 of
this doc** (preserved at `docs/audits/full-project-review-2026-05-11-v1.md`)
was assembled from 5 parallel deep-read agents — 4 of 5 were silently
terminated by the harness before producing structured reports. The
user asked for a fresh, deeper pass. This v2 is the result: per-package
`CLAUDE.md` reads for all 9 documented packages, structural sampling of
the largest undocumented packages (`billing` 83 files, `auth` 72 files,
`integration` 42 files), targeted code-smell metrics + direct security
verification (git history of secrets), live config inspection, and a
deeper cut at the frontend bundle / polling / test-coverage shape.

Live progress (this run) tracked at `/tmp/review-progress.md` with
Telegram heartbeats every 30 minutes.

---

## TL;DR — top 15 things to fix

| # | Severity | Area | Finding | Where | Fix sketch |
|---|----------|------|---------|-------|------------|
| 1 | **P0 — security** | **3 real-looking secrets in git-tracked `application-dev.yml`** as `${VAR:default}` defaults, in history since **2026-04-02** (commit `c51ce15c`). `GOOGLE_OAUTH_CLIENT_SECRET=GOCSPX-...`, `GITHUB_OAUTH_CLIENT_SECRET=46033466...`, `SMTP_PASSWORD=smdpsanatyrtlcxa` | `src/main/resources/application-dev.yml:67, 72, 145` | Rotate all three immediately. Change defaults to `${VAR:}` (empty). Force-rebase history if practical. |
| 2 | **P0 — security** | **JWT secret hardcoded literal in `application.yml:680`**; `JwtSecretValidator` only fires on `@Profile("prod")` — dev / local / test profiles silently accept the placeholder. | `src/main/resources/application.yml:680`, `auth/security/JwtSecretValidator.java` | Drop the literal default; make the validator run on every profile; configure via `${JWT_SECRET:}` (env-required). |
| 3 | **P0 — UX/auth** | **No JWT auto-refresh on 401 in frontend `req()`** — users away >1h get logged out mid-task even though their 7-day refresh cookie is valid. | `frontend/src/lib/api.ts:74-107` | On 401 → call `authRefresh()` once with single-flight gate, replay the original request. |
| 4 | **P0 — perf** | **2.27 MB monolithic JS bundle** — zero `React.lazy`/dynamic imports anywhere (`grep -rn 'React.lazy\|import(' frontend/src` returns nothing). Login page ships entire admin/Monaco/recharts/framer-motion/posthog/OTel surface. Vite config has no `manualChunks`. | `frontend/vite.config.ts`, `App.tsx` (54 eager imports) | Convert admin/run-detail/library/migration routes to `React.lazy`; add `manualChunks` for `monaco-editor`, `recharts`, `framer-motion`, `posthog-js`, the 6 `@opentelemetry/*` packages. |
| 5 | **P1 — code quality** | **`RunOrchestrator.java` is 4,539 lines / 48 methods**, with **7 overloads of `submitRun`** + 5 overloads of `runPipeline`. Classic builder-aversion god class. | `integration/RunOrchestrator.java` | Extract `RunRequest` record; split into `RunSubmissionService` / `PipelineExecutor` / `RunRecoveryService`. |
| 6 | **P1 — code quality** | **`StateStore.java` is 2,069 lines / 104 methods** — catch-all persistence layer. | `state/StateStore.java` | Split by aggregate root (PipelineRunStateStore, AgentTaskStateStore, etc.). |
| 7 | **P1 — test coverage** | **28 of ~50 Java packages have zero tests.** `billing` 0.02 ratio (83 src files / 2 tests). `state` 0.02 (37/1). `auth` 0.05 (72/4). **Frontend has zero test framework installed** — no vitest, jest, playwright, cypress, or testing-library. | `frontend/package.json` + per-package surface | Prioritise billing webhook signature + refund tests; auth tier-enforcement tests; install Vitest for frontend with smoke tests on the auth + run-creation funnels. |
| 8 | **P1 — N+1 surface** | **221 finder methods on JPA repositories, only 4 with `@EntityGraph`**. OSIV (`open-in-view=true`) masks N+1s by lazy-loading at render time — works but every `for (X x : list) x.getRelated()` is a hidden round-trip. | 50+ `*Repository.java` files | Profile the 5 hottest endpoints; add `@EntityGraph` where joins are obvious. |
| 9 | **P1 — frontend bug** | **Two endpoints in `v3/lib/api.ts` build `/api//stats` and `/api//sample-runs`** (double-slash from `BASE + '//stats'`). Landing-page hero counter + sample-runs widget silently fetch from broken URLs. | `frontend/src/v3/lib/api.ts:127, 142` | `BASE + '/v3/landing/stats'` and `BASE + '/v3/landing/sample-runs'`. |
| 10 | **P1 — frontend a11y** | **`Toast.tsx` has no `role="alert"` / `aria-live`**, close button unlabelled. Every async error toast is invisible to screen readers. | `frontend/src/components/shared/Toast.tsx:14-37` | Add `role="alert"` (error) / `role="status"` (success), `aria-live="polite"` on container, `aria-label="Dismiss notification"` on X. |
| 11 | **P1 — frontend dead code** | **`frontend/CLAUDE.md` lists 9+ components as live that don't exist on disk** (`Sidebar`, `AppLayout`, `CommandPalette`, `TaskGraph`, `VibeRunSummary`, `VibeProgressNarrative`, `FirstRunSuggestions`, `VibeRunSubmit`, `TechBadges`). `RunDetail.tsx:28` still imports `RunTimeline` from a path the doc claims was removed. | `frontend/CLAUDE.md`, multiple files | Sync `frontend/CLAUDE.md` Components section with disk reality. `git rm` the 6 zero-importer files. |
| 12 | **P1 — backend doc drift** | **Per-package `agent/CLAUDE.md` still describes `FallbackLlmRouter` + XML `<tool_call>` parsing as canonical**, but root `CLAUDE.md` says V2 `LlmRouter` (native tool calling) is canonical and `FallbackLlmRouter` is legacy used only by 3 surfaces. **`harness/CLAUDE.md` has V1 `PipelineHarness` interleaved with V2 updates** — hard for new contributors to parse the live state. **`state/CLAUDE.md` lists migrations up to V184**; live head is V186. | `src/main/java/com/navneet/executespec/{agent,harness,state}/CLAUDE.md` | Refresh per-package docs to match disk. Drop V1 references; mark explicitly "V2 canonical". |
| 13 | **P2 — design-token drift** | **616 v2-token refs (`var(--gray-*)`, `var(--border-default)`) vs 56 v3-token refs** in `components/`. Same panels render under v3 chrome but use v2 colors. Light-theme override (`[data-theme='light']`) only catches v3 vars — most components theme-flip incorrectly. | All of `components/runs/`, `components/project/`, `components/billing/` | Alias `--gray-100` → `var(--v3-surface)` per surface, OR do a perl sweep. |
| 14 | **P2 — perf polling** | **15 files use `setInterval`**, only **3 of those check `document.hidden` / `visibilityState`**. Sustained ~5 req/s per active project-page tab; background tabs keep firing. | 15 files (see §2.5) | Gate every `setInterval` behind `document.visibilityState === 'visible'`; tear down on terminal status. |
| 15 | **P2 — known tech debt explicitly called out** | **Bug #1c** (per `router/CLAUDE.md`): `ModelResolver.selectAdaptiveProvider` silently overrides user-locked provider even under `-Dexecutespec.strict-provider=true`. Plus: planner/reviewer don't use tier resolution (run uses Haiku-default regardless of matrix). Both flagged in per-package CLAUDE.md; both still unfixed. | `router/ModelResolver.java` + `agent/OutlinePlannerAgent.java` | Strict-mode guard inside `ModelResolver.resolve()`; thread resolved planner model into `PlannerRequest`. |

Total findings in this doc: **~75 across 14 dimensions**. The full list spans §1 through §6 below.

---

## How to read

- **P0** — production risk, leaks, or actively broken. Fix this week.
- **P1** — sprint priority. Costs real money or causes real friction.
- **P2** — quality of life. Schedule into a trim sprint.
- **P3** — polish / future-work.

All findings cite file + line where possible.

---

# § 1 · Backend — deeper findings

## 1.1 — Documented packages (per-package `CLAUDE.md` survey)

9 packages have a `CLAUDE.md`. I read each in full:

| Package | LOC | Has CLAUDE.md | Doc state |
|---------|-----|---------------|-----------|
| `agent/` | 9,522 | yes | **STALE** — describes `FallbackLlmRouter` + XML tool calls; root says V2 `LlmRouter` (native tool calling) is canonical |
| `state/` | 4,547 | yes | up-to-date through V184; **live head V186** (drift) |
| `router/` | 1,560 | yes | accurate; explicitly flags 2 known tech debt items as unfixed |
| `harness/` | 3,646 | yes | **interleaved V1 / V2** — hard to parse the live state |
| `deployment/` | 16,747 | yes | up-to-date — triple-mode preview + 9 language strategies clearly documented |
| `prreview/` | 1,909 | yes | up-to-date (v2.6.4 — paid tier feature) |
| `sharing/` | (in `project/`) | yes | up-to-date (V175 sharing surface) |
| `validator/` | 2,000 | yes | up-to-date (gate chain) |
| `codereview/` | (paid-tier) | yes | up-to-date (v2.6.4) |

**Documented gaps** worth fixing in the docs:
- `agent/CLAUDE.md` updates: replace XML-tool-call language with native-tool-call, mark `FallbackLlmRouter` as legacy (3 callers).
- `harness/CLAUDE.md` updates: remove V1 `PipelineHarness` / `SequentialTaskExecutor` references unless explicitly flagged as "historical, deleted on 2026-04-13".
- `state/CLAUDE.md` updates: extend migration list to V186; root CLAUDE.md says V184 too.

## 1.2 — Undocumented critical packages

These packages have **no `CLAUDE.md`** despite being among the largest by LOC. Risk: onboarding burden is significant, drift is undetectable.

| Package | LOC | Files | Why it matters | Recommend |
|---------|-----|-------|----------------|-----------|
| `auth/` | 10,990 | 72 | security gates, OAuth, tier resolution, GDPR data-export | **must have CLAUDE.md** |
| `billing/` | 9,000 | 83 | revenue surface — Stripe + Razorpay webhooks, subscriptions, refunds, founder pricing | **must have CLAUDE.md** |
| `integration/` | 9,488 | 42 | `RunOrchestrator` lives here — the god class | **must have CLAUDE.md** |
| `project/` | 5,470 | 42 | project workbench, repo registry, architecture docs | should have |
| `api/` | 5,263 | 47 | controllers + DTOs + advice + filters | should have |
| `execution/` | 4,357 | 37 | Execution View (multi-repo Launches) | should have |
| `testgen/` | 3,631 | 29 | test-generation orchestrator + report | nice-to-have |
| `tool/` | 5,206 | 28 | V2 tools (10 tools + CompleteTaskTool) + sandbox policies | should have |
| `contracts/` | 3,367 | 32 | cross-repo contract registry (v2.6.3 enterprise readiness) | nice-to-have |
| `codeintel/` | 2,936 | 21 | code-map generator + architecture doc | nice-to-have |

## 1.3 — God classes

| File | LOC | Methods | Smell |
|------|-----|---------|-------|
| `integration/RunOrchestrator.java` | **4,539** | 48 | 7 overloads of `submitRun` + 5 of `runPipeline` — needs a builder + 3-way service split |
| `state/StateStore.java` | **2,069** | 104 | Single class handling every entity's mutations — split by aggregate root |
| `billing/service/BillingService.java` | **1,432** | 23 | 3 overloads of `subscribe`; webhook processors; status; cancel; updateSeats — borderline (low method count) |
| `auth/service/OrgService.java` | **870** | — | Org CRUD + member management + seat math — could split off `OrgSeatService` |
| `auth/service/TierEnforcementService.java` | **810** | 17 | Single gate for all tier limits — design is sound; size is healthy |
| `harness/SequentialExecutor.java` | **1,472** | — | Worker dispatch + smoke test + cost attribution + status transitions |
| `agent/WorkerAgent.java` | **1,216** | — | Worker loop + stagnation watchdog + soft-completion + budget gate |

## 1.4 — Code smell metrics (rerun for v2)

| Metric | Value | Assessment |
|--------|-------|------------|
| TODO / FIXME / HACK comments | **9 total** | **Excellent hygiene** — top file has just 1. |
| `@SuppressWarnings` | 59 | Moderate; mostly enum-switch + raw types. |
| `catch (Exception|Throwable)` blocks | **785** | **High volume.** Sample shows `log.warn`+continue pattern. Audit recommended: find any with no log statement in the same block. |
| Controllers | 98 | |
| REST endpoints | 444 | |
| Endpoints with `@PreAuthorize` | **53 (12%)** | Other 88% rely on per-method `*AccessGuard` calls + ArchUnit `AccessGuardCoverageTest` |
| `KNOWN_LEGACY_DRIFT` allowlist size on `AccessGuardCoverageTest` | **37 methods** | Should shrink, not grow |
| JPA finder methods | 221 | |
| Finders with `@EntityGraph` | **4 (1.8%)** | Real N+1 surface |

## 1.5 — Known tech debt explicitly called out in per-package docs

- **Bug #1c** (`router/CLAUDE.md`): `ModelResolver.selectAdaptiveProvider` silently overrides user-locked provider even under `-Dexecutespec.strict-provider=true`. Evidence: Proj-OpenAI run logged `provider=anthropic model=claude-sonnet-4-6` despite `project.aiProvider=OPENAI`. **Not fixed.**
- **Planner/reviewer don't use tier resolution** (`router/CLAUDE.md`): `PlannerAgent.plannerViaLlm` uses `req.model()` (RUN-level default = Haiku for Anthropic) instead of `ModelResolver.resolve("PlannerAgent", provider).getModel()`. Swapping the matrix has no effect on the planner. Same likely for reviewer. **Not fixed.**

## 1.6 — Security perimeter (re-verified)

Per CLAUDE.md, 8-layer defense-in-depth is documented. Per-layer files exist:
- `InputSanitizer.java` ✓
- `CodeAbuseScanner.java` ✓
- `RateLimitFilter.java` ✓
- `ShellSandboxPolicy.java` ✓
- `FileSandboxPolicy.java` ✓
- `WebhookSignatureVerifier.java` (billing) ✓
- `JwtSecretValidator.java` (gated `@Profile("prod")` only — flagged P0)
- `SecurityConfig.java` (294 LOC) ✓

Sample spot-checks:
- Native SQL queries are parameterised (sampled in `RunOrchestrator` per v1 agent finding).
- Webhook handlers take `(rawPayload, signature)` — signature verification happens before parsing (verified at `BillingService:650, 886`).
- 444 REST endpoints / 53 `@PreAuthorize` → other 88% rely on guard-method discipline. The ArchUnit `AccessGuardCoverageTest` pins this, but its `KNOWN_LEGACY_DRIFT` ledger has 37 entries.

## 1.7 — Observability

- Per-V2-component meters: `executespec.v2.run.outcome`, `planner.latency`, `task.smoketest`, `worker.iterations`, `llm.latency`, etc. Dump endpoint `/api/v2/test/metrics`.
- Prometheus exposed at `/actuator/prometheus`.
- Structured-logging-via-Logback `<springProfile name="gcp">` — activates only when `gcp` profile is also active. `application-prod.yml` doesn't include `gcp` in profiles; `startup.sh` sets `prod,gcp`. **Verify** that Cloud Run / GCE bootstrap actually launches with both, otherwise prod runs without JSON logs.

---

# § 2 · Frontend — deeper findings

## 2.1 — Bundle composition (verified live)

- **Zero `React.lazy`**, zero dynamic `import()` for code-splitting. `grep -rn 'React.lazy\|import(' frontend/src` returns no matches.
- **Vite config has no `manualChunks`**: `frontend/vite.config.ts` has only `outDir: '../src/main/resources/static'` + `emptyOutDir: true`, plus a dev-server `proxy` block. No `build.rollupOptions`.
- **App.tsx has 54 eager imports** — every route is loaded into the main chunk.
- **Heavy deps installed** (per `frontend/package.json`):
  - `@monaco-editor/react: 4.7.0` — code editor
  - `@opentelemetry/*` × 6 packages — sdk-trace-web, auto-instrumentations-web, exporter-trace-otlp-http, context-zone, resources, semantic-conventions
  - `framer-motion: 11.0.0` — imported by 10 components incl. `Toast.tsx`
  - `recharts: 2.12.0` — imported by 4 surfaces
  - `posthog-js: 1.370.1` — analytics
  - `cmdk: 0.2.1` — command palette
  - `highlight.js: 11.11.1`, `react-markdown: 8.0.7`, `qrcode.react: 4.2.0` — used in narrower scopes

## 2.2 — Top file sizes (top-25 frontend files / 8K+ LOC each)

| File | LOC | Notes |
|------|-----|-------|
| `lib/api.ts` | 3,525 | API client — single source of truth, but huge |
| `v3/components/RunSidePanel.tsx` | **3,327** | Side panel — larger than RunDetailTabs |
| `components/runs/RunDetailTabs.tsx` | 2,771 | |
| `v3/pages/RunDetailPage.tsx` | 1,886 | |
| `pages/admin/AdminPage.tsx` | 1,683 | |
| `v3/components/RunPreviewPane.tsx` | 1,641 | |
| `pages/IntegrationsPage.tsx` | 1,342 | |
| `components/runs/RunDetail.tsx` | 1,144 | imports the "removed" `RunTimeline` at line 28 |
| `pages/SettingsPage.tsx` | 1,119 | |
| `components/runs/PreviewTab.tsx` | 1,053 | |
| `v3/pages/LandingPage.tsx` | 1,026 | "stub" per v1 review, but 1k LOC |
| `components/runs/NewRunModal.tsx` | 973 | |
| `components/runs/RunConversation.tsx` | 968 | |
| `components/deployments/DeployWizardModal.tsx` | 953 | |
| `pages/project/ProjectDashboardPage.tsx` | 898 | |
| `v3/pages/TeamPage.tsx` | 867 | |
| `components/project/PulseTab.tsx` | 861 | |
| `v3/components/RepoAttachPopover.tsx` | 847 | |
| `pages/org/OrgProjectsPage.tsx` | 799 | |
| `v3/components/Composer.tsx` | 797 | |
| `components/project/ProjectChatPanel.tsx` | 770 | |
| `components/runs/ValidationReportPanel.tsx` | 706 | |
| `pages/AuditPage.tsx` | 702 | |
| `pages/admin/OrgAdminDashboard.tsx` | 698 | |

**Pattern:** RunDetail / RunDetailTabs / RunSidePanel / RunPreviewPane / NewRunModal / RunConversation / RunsNewPage / RunsNewLanding / Composer / RunDetail all touch the same "run" surface — a heavy amount of code with overlapping responsibilities.

## 2.3 — Routing structure

App.tsx has these route groups (verified by reading line 269+):
- Public routes (login, register, forgot-password, reset-password, verify-email, accept-invite, auth/callback, reports/shared, projects/snapshot, terms, privacy, dpa).
- `<Shell>` routes (post-auth standard chrome): `/home`, `/settings`, `/billing`, `/pricing`, `/integrations`, `/team`, `/trust`, `/insights`, `/migrations`, `/library`, `/exec-demo`, `/claim`, `/projects/empty`, `/projects/:projectId/runs/:runShort`, `/runs/:runShort`.
- `<ProtectedRoute>` group with `welcome` + further routes including `runs/new`, `runs/new/sandbox`, `legacy/billing`, an index that redirects to `/home`, `/runs`.
- Legacy redirect: `/v3/*` → modern paths.

No dedicated 404 page — `path='*'` does `<Navigate to='/' replace />` (v1 finding confirmed).

## 2.4 — Verified broken endpoints (v1 finding, re-verified)

```ts
// frontend/src/v3/lib/api.ts:127
export const getV3LandingStats = () =>
  fetch(BASE + '//stats').then(...)  // ← '//stats' has a double slash

// frontend/src/v3/lib/api.ts:142
export const getV3SampleRuns = () =>
  fetch(BASE + '//sample-runs').then(...)  // ← same
```

Confirmed: `BASE` resolves to `/api`, so the final URLs are `/api//stats` + `/api//sample-runs`. Reverse-engineering the intent from CLAUDE.md (LandingController exposes `/api/v3/landing/stats` + `/api/v3/landing/sample-runs`), the fix is `BASE + '/v3/landing/stats'` and `BASE + '/v3/landing/sample-runs'`.

## 2.5 — Polling pile-up

**15 frontend files use `setInterval`** (verified):
1. `components/layout/NotificationBell.tsx`
2. `components/project/ProjectDocumentsPanel.tsx`
3. `components/project/CodeQualityPanel.tsx`
4. `components/project/ProjectPrRollup.tsx`
5. `components/execution/ExecutionStepper.tsx`
6. `components/runs/PreviewTab.tsx`
7. `components/project/PrReviewButton.tsx`
8. `components/billing/UsageBadge.tsx`
9. `components/deployments/DeployWizardModal.tsx`
10. `v3/components/RunPreviewPane.tsx`
11. `components/runs/RunDetailTabs.tsx`
12. `v3/components/CodeReviewPanel.tsx`
13. `v3/pages/LandingPage.tsx`
14. `v3/pages/RunDetailPage.tsx`
15. `pages/DeploymentsPage.tsx`

**Only 3 of those check `document.hidden` / `visibilityState`** (`PreviewTab`, `RunPreviewPane`, `DeploymentsPage`). The other 12 keep firing requests on background tabs.

## 2.6 — State stores

- `stores/`: `useAuthStore`, `useExecutionStore`, `useMetricsStore`, `useProjectStore`, `useRunStore`, `useSseStore`, `useUiStore` — 7 stores, reasonable separation.
- `v3/stores/`: `useV3ProjectStore`, `useV3Ui` — 2 v3-specific.
- **No react-query / SWR** — no stale-while-revalidate. Every page reload triggers fresh fetch.

## 2.7 — Frontend test framework status

**Zero frontend test framework installed.** `grep -E '"(vitest|jest|playwright|cypress|@testing-library)"' frontend/package.json` returns nothing. No `*.test.tsx`, no `*.spec.tsx`. 75K LOC ships untested; bug regressions are caught by humans clicking through.

CLAUDE.md mentions a `frontend/src/design-system/contrast.test.ts` as "vitest-shape, dormant" — file exists but no runner is installed.

---

# § 3 · Infra + build + CI

## 3.1 — Secret hygiene (P0)

**Verified directly via `git log -S` on the secret value** — the leaked Google OAuth client_secret has been in `application-dev.yml` since commit `c51ce15c` on **2026-04-02** (40+ days ago):

```
src/main/resources/application-dev.yml
  line 67:   client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET: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}
  line 72:   client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:46033466197599eb1fc74d321b141a816fdcb1f5}
  line 145:  password: ${SMTP_PASSWORD:smdpsanatyrtlcxa}
  line 147:  secret:   ${JWT_SECRET:executespec-dev-secret-key-must-be-at-least-256-bits-long-for-HS256}
```

And `application.yml:680`:
```yaml
app:
  jwt:
    secret: executespec-local-dev-secret-key-must-be-at-least-256-bits-long-for-HS256
```

**`JwtSecretValidator`** (`src/main/java/com/navneet/executespec/auth/security/JwtSecretValidator.java`, 49 LOC) catches the "must-be" substring at startup BUT only fires on `@Profile("prod")`. Dev / local / test profiles silently accept the placeholder.

**Action checklist:**
1. Rotate Google OAuth client_secret (it's the same one in `credentials.json` at repo root, gitignored but on disk).
2. Rotate GitHub OAuth client_secret.
3. Rotate SMTP password.
4. Change defaults to `${VAR:}` (empty).
5. Drop `@Profile("prod")` from `JwtSecretValidator` — fail-fast on every profile when the placeholder is detected.
6. Audit `git log -p` for any other `${VAR:literal}` patterns matching credential shapes.

## 3.2 — CI workflows

**Only 2 workflows exist**: `lint.yml` + `deploy.yml`.

`lint.yml` (verified):
- Triggers: `pull_request` to dev/main + `workflow_dispatch`.
- Runs spotless + prettier + eslint.
- Currently the only PR-time gate.

`deploy.yml`:
- Triggers: `push` to dev + `workflow_dispatch`.
- Runs `mvn verify` (which includes tests). `main` is deliberately excluded — "Keep 'main' out of the branches list until GCP prod credentials are provisioned."
- **No separate test workflow on PRs.** PRs only gate on lint.

**Missing CI gates** (recommended):
- `test.yml` running `mvn verify` on every PR.
- `dependabot.yml` for Spring Boot / Spring AI / Lombok / billing SDKs / npm.
- `codeql.yml` for security scanning.
- `npm-audit.yml` for frontend CVE checks.

## 3.3 — Deploy artifact

**Confirmed: no root `Dockerfile`** for the main app. (`./src/main/java/com/navneet/executespec/deployment/DockerfileGenerator.java` is the CUSTOMER preview-Dockerfile generator, not a Dockerfile for the app.)

Deploy is JAR upload to a GCE VM + `systemd` restart (per `deploy.yml` shape). **Operational risk:** no immutable image, no instant rollback. Recommend containerising the main app + pushing to GCR.

## 3.4 — Spring config

- **OSIV `open-in-view=true`** — necessary for current controller patterns; couples connection lifetime to request lifetime.
- **Hikari `leak-detection-threshold: 90000`** (90s).
- **CSP** hardcoded in `SecurityProperties.java`; no `report-uri` for violation telemetry.
- **HSTS preload flag** added in PR #108 (default `false`). Flip on after 30 days of HTTPS stability.

## 3.5 — Cloudflare worker (marketing site)

`website/wrangler.toml` exists; deploy command is `npx wrangler deploy --config website/wrangler.toml`. **Not auto-deployed from GitHub merges** — separate ops step. Risk: marketing site drift (recent example: PR #108 included JetBrains font updates to `website/index.html` but the live site still served DM Sans until manually deployed).

---

# § 4 · Data + migrations + prompts + templates

## 4.1 — Migrations

- **Live head: V186** (`pull_request_ci_status.sql`). Root `CLAUDE.md` still claims V184 — **stale by 2 migrations**.
- **186 total migrations**, naming convention strict (`V###__description.sql`).
- **Number gaps:**
  - `V60` → `V60_1` (hotfix retrofit; out-of-order allowed per `flyway.out-of-order: true`).
  - `V118` → `V120` (V119 was the preview-engine extraction migration, retired when preview moved to a separate repo).
- **Destructive ops** (DROP TABLE/COLUMN, TRUNCATE) cleanly named: V79, V138, V139, V141, V142, V148, V149, V165, V179. Each ties to a CLAUDE.md entry.
- **V179** (`drop_product_split.sql`) is the single-surface retirement; well-documented.
- Latest 10:
  ```
  V177  solo_annual_and_founder_pricing
  V178  user_cost_cap_on_pipeline_run
  V179  drop_product_split  (single-surface retirement)
  V180  lint_autofix_flag
  V181  pr_comment_inbox
  V182  run_mode  (BUILD vs PATCH discriminator)
  V183  triggered_by_finding
  V184  widen_agent_task_last_llm_failure (VARCHAR(255) → TEXT)
  V185  retire_solo_branding  (Solo → Pro rename)
  V186  pull_request_ci_status
  ```

## 4.2 — Prompts

- **31 active `.md` / `.txt` prompts** + 14 versioned planner archives (`planner.v1.txt` – `planner.v14.txt`).
- **`PromptVersioningService` IS used** (3 importers: `SuperAdminController`, `PromptOptimiserAgent`, the service itself). Versioned archives are not dead code.
- **One stray backup file** in `src/main/resources/prompts/`: `worker-anthropic-native.txt.bak`. **Recommend `git rm`** — it's just clutter.

## 4.3 — Email templates

21 templates standardised on JetBrains Mono in PR #108 (already merged). Each template uses Thymeleaf variables. Spam-score-friendly hygiene (alt text, plain-text fallback) not explicitly audited.

## 4.4 — Static assets

- Post-PR #108: `logo.png` (375 KB), `logo-192.png` (35 KB), `logo-512.png` (185 KB) all present in both `frontend/public/` + `src/main/resources/static/`.
- `sw.js` (service worker) present — verify it's not stale (could cache an outdated bundle for returning users).
- Manifest declares the 3 icon variants + orientation: `any`.

---

# § 5 · Tests + coverage

## 5.1 — Coverage matrix

**28 of ~50 Java packages have zero tests.** The most concerning by stakes:

| Package | src files | tests | ratio | Stakes |
|---------|-----------|-------|-------|--------|
| **billing** | 83 | 2 | **0.02** | **CRITICAL** — financial transactions |
| **state** | 37 | 1 | **0.02** | **CRITICAL** — persistence layer |
| **auth** | 72 | 4 | **0.05** | **HIGH** — security |
| **integration** | 42 | 6 | 0.14 | HIGH — `RunOrchestrator` |
| **agent** | 44 | 11 | 0.25 | MEDIUM |
| **tool** | 28 | 8 | 0.28 | MEDIUM |
| **security** | 9 | 3 | 0.33 | OK |
| **reviewer** | 11 | 4 | 0.36 | OK |
| **harness** | 15 | 7 | 0.46 | GOOD |

**Packages with zero tests (severity ranked):**
- Feature surfaces: `prreview`, `sse`, `landing`, `scoring`, `pricing`, `testgen`, `swebench`, `programbench`
- Utilities + ops: `eval`, `validator` (only ArchUnit), `finding`, `observability`, `rollout`, `onboarding`, `optimiser`, `memory`, `governance`, `compliance`, `contact`, `context`, `crypto`, `apitests`, `assessment`, `saga`, `tenant`, `test`, `util`, `run`

## 5.2 — ArchUnit drift detectors

**5 detectors found in `src/test/java/com/navneet/executespec/arch/`** (plus 2 invariants):
- `ExecutespecFolderWriterCallerAllowlistTest` — pins 4 approved callers to `ExecutespecFolderWriter#writeFiles`.
- `AnthropicModelAccessTest` — pins approved Anthropic model identifiers.
- `AsyncAllowlistTest` — pins approved `@Async` callers.
- `AccessGuardCoverageTest` — pins controller methods to require auth guards; `KNOWN_LEGACY_DRIFT` allowlist has 37 entries.
- `ApiContractTest` — pins every URL in `frontend/src/lib/api.ts` to a backend `@*Mapping`.
- `FailoverOrderEnumAlignmentTest` — pins YAML failover-order to `LlmProvider` enum.

**Pattern is a strength** of the codebase. The drift detectors are a living-documentation form of tech debt.

## 5.3 — Frontend tests

**Zero installed test framework.** Confirmed by `grep -E '"(vitest|jest|playwright|cypress|@testing-library)"' frontend/package.json` returning empty. 75K LOC ships untested.

## 5.4 — CI test execution

- `lint.yml` on PRs.
- `deploy.yml` (which runs `mvn verify`) only on push-to-dev.
- **No PR-time test gate.** Bugs slip into dev before tests run.

---

# § 6 · Cross-cutting themes

## 6.1 — Documentation drift (the biggest meta-finding)

Examples gathered across this review:
1. Root `CLAUDE.md` lists live migration head as V184; actual is V186.
2. `frontend/CLAUDE.md` lists 9+ components as live that don't exist on disk; one (`RunTimeline`) is still imported despite the doc claiming it was removed.
3. `agent/CLAUDE.md` describes `FallbackLlmRouter` as canonical; root says V2 `LlmRouter` is canonical.
4. `harness/CLAUDE.md` has V1 `PipelineHarness` mixed with V2 `WorkerAgent` updates.
5. Tier ladder copy: root CLAUDE.md says ladder is FREE/PRO(HIDDEN)/TEAM/MAX/ENTERPRISE but the live tier_config (per the 2026-05-11 UX audit) has just FREE/PRO/TEAM/ENTERPRISE — MAX was retired (V165) then Solo was renamed to Pro (V185). CLAUDE.md hasn't caught up.

**Root cause:** root CLAUDE.md is appended-to but never compressed. It's now 2,000+ lines with sections describing work from 2026-04 mixed with sections describing work from 2026-05-10. Per-package `CLAUDE.md`s drift the same way.

**Recommend:** quarterly compression pass. Fold "Recently resolved" / "In progress" sections into git log + dated audit docs. Keep only "what is live today" in `CLAUDE.md`.

## 6.2 — God classes / hot files

Six files are ~14K LOC combined (10% of production code by line count, 0.6% of files by count):
- `RunOrchestrator.java` (4,539)
- `lib/api.ts` (3,525)
- `v3/components/RunSidePanel.tsx` (3,327)
- `RunDetailTabs.tsx` (2,771)
- `StateStore.java` (2,069)
- `RunDetailPage.tsx` (1,886)

These are bug + onboarding hotspots.

## 6.3 — Security posture summary

**Strengths:**
- 8-layer defense documented + present.
- ArchUnit `AccessGuardCoverageTest` enforces auth-gate discipline.
- Parameterised native queries (sampled).
- Solid `.gitignore` (verified `application-local.yml`, `credentials.json`, `sa-key.json` all gitignored).
- JWT secret startup validator (limited).

**Weaknesses:**
- Real-looking OAuth + SMTP secrets in git history since 2026-04-02.
- JWT secret literal in `application.yml`; validator only on prod profile.
- 88% of controllers rely on guard-method discipline, not annotations.
- No CodeQL / dependabot / npm audit gate.
- No CSP `report-uri` for violation telemetry.

## 6.4 — Test culture summary

**Strengths:**
- 6 ArchUnit drift detectors — repeatable pattern, living documentation of tech debt.
- `harness` test ratio 0.46.

**Weaknesses:**
- 28 of 50 packages have zero tests.
- Billing 0.02, state 0.02, auth 0.05 — highest-stakes packages have lowest coverage.
- Frontend has zero test framework installed.
- No PR-time test gate.

## 6.5 — Operational maturity summary

**Strengths:**
- Observability story (Micrometer + OTel + Prometheus + Logback).
- `@SchedulerLock` on every `@Scheduled` job.
- Bounded thread pool with `CallerRunsPolicy` backpressure.
- Run-ownership stamped pre-async (race-condition fix).
- Idempotent webhook handlers with signature verification.

**Weaknesses:**
- JAR-on-VM deploy (no immutable image, manual rollback).
- Cloudflare worker manual deploy.
- JSON logs gated on `gcp` profile which deploy.yml doesn't set (only `startup.sh` does — fragile).
- No PR test gate.
- No `dependabot`.

---

# § 7 · 90-day action plan

## Week 1 — P0s

1. **Rotate the three leaked secrets** + change defaults to `${VAR:}` empty + audit git log for similar patterns. (P0-S1)
2. **Drop `@Profile("prod")` from `JwtSecretValidator`** — fail-fast on every profile. (P0-S2)
3. **Add JWT auto-refresh on 401** in `frontend/src/lib/api.ts` `req()`. (P0-3)
4. **Fix the two double-slash URLs** in `v3/lib/api.ts:127,142`. (P1-9)

## Weeks 2-4 — high-impact P1s

5. **Bundle splitting** — `React.lazy` on admin/run-detail/library/migration routes; `manualChunks` in vite.config.ts for monaco-editor / recharts / framer-motion / posthog-js / `@opentelemetry/*`. Target: drop login-page bundle to <300 KB gzipped.
6. **`Toast.tsx` a11y fix** — `role`, `aria-live`, `aria-label`.
7. **Polling discipline sweep** — gate the 12 unhidden `setInterval` timers behind `document.visibilityState === 'visible'`.
8. **iOS auto-zoom fix** on the 25 remaining forms — promote `text-base sm:text-sm` to shared `Field`.
9. **Add `dependabot.yml`** for Spring Boot / Spring AI / Lombok / billing SDKs / npm.
10. **Add a `test.yml` workflow** that runs `mvn verify` on every PR.

## Weeks 5-8 — structural cleanups

11. **Refactor `RunOrchestrator`** — `RunRequest` record + split into `RunSubmissionService` / `PipelineExecutor` / `RunRecoveryService`.
12. **N+1 audit** — profile top-5 endpoints, add `@EntityGraph` where joins are obvious.
13. **Billing tests** — webhook signature, refund flows, subscription state transitions.
14. **Auth tests** — `TierEnforcementService` quota/limit paths; help shrink `KNOWN_LEGACY_DRIFT` allowlist.
15. **Containerise the main app** — Dockerfile + GCR push as part of deploy.yml.
16. **Write `CLAUDE.md`** for `auth/`, `billing/`, `integration/`, `api/` — the four undocumented heavyweight packages.

## Weeks 9-12 — polish + observability

17. **Design-token unification** — collapse `--gray-*` / `--v3-*` / `--c-*` into one namespace.
18. **Component dedup** — pick one of `NewRunModal` / `RunsNewPage` / `Composer`; one of `PlanCard` (billing vs v3); one of `PricingTable` vs `PricingGrid`. Delete duplicates.
19. **Frontend test framework** — install Vitest; write Playwright smoke for auth + run-creation funnels.
20. **`OTel` deferral** — `requestIdleCallback` for telemetry init; feature-flag behind `VITE_OTEL_ENABLED`.
21. **HSTS preload submission** after 30 days of stable HTTPS.
22. **Doc compression pass** — root + frontend `CLAUDE.md` shrink old sections; fold to git log + audit docs.

## Track ongoing

- **`KNOWN_LEGACY_DRIFT` allowlist shrink** — 5 entries per sprint to zero.
- **9 TODO/FIXME** → tracked tickets.
- **Cloudflare worker deploys** auto-fire on `website/**` changes in CI.
- **Per-package `CLAUDE.md` audits** — every PR that touches a package without one starts by writing it.
- **Prompt fragment audit** — confirm `{{include:fragment}}` is the dominant pattern.

---

# § 8 · Scope honesty (what I could *not* assess)

- **Did not read every line of every file.** 220K LOC across 1,250+ source files would take 10× the context budget. Approach was strategic sampling: hot path + per-package `CLAUDE.md` + aggregated metrics (LOC, method count, TODO count, exception count, finder count, test ratios). Where coverage was thin, I said so.
- **No dynamic analysis** — no SAST, no `mvn dependency-check`, no `npm audit`, no Lighthouse audit, no live load test.
- **No live system probes** — that work was done in PR #108's UX audit; this review is structural / code-level.
- **The 5 parallel-agent attempt** (v1 of this doc) had 4/5 agents silently terminated mid-loop. v2 (this doc) was re-done first-person to avoid that failure mode but is therefore a smaller-N sample.
- **N+1 hypothesis** is based on `@EntityGraph` count (4/221), not real measurements.
- **Secret rotation** — I verified the secrets are in git history but did NOT verify whether they're still active credentials.

---

# § 9 · Diff vs v1 of this doc

The v1 (preserved at `docs/audits/full-project-review-2026-05-11-v1.md`) had:
- 622 lines, 64 findings, 12 in the TL;DR.
- Synthesised from 1 complete agent + 4 partial agents + my fill-in.

This v2 has:
- **~750 lines**, **~75 findings**, **15 in the TL;DR**.
- Synthesised from direct in-loop reads of every per-package `CLAUDE.md` + sampled hot path + verified git history of secrets + verified live URLs / polling counts / test-framework absence.
- **Confirmed and extended every v1 P0** + added **doc-drift as the meta-finding (now §6.1)**.
- **Added §1.5** (known tech debt explicitly called out in per-package docs but never fixed: Bug #1c, planner/reviewer not using tier resolution).
- **Added precise migration head V186 vs CLAUDE.md's V184 drift.**
- **Verified the JwtSecretValidator scope limitation** by reading the source.
- **Listed 28 packages with zero tests** (was: "28 of ~50" in v1; now with full enumeration).

---

*Captured on the `feature/full-project-review-2026-05-11` branch
against `dev` HEAD `22b8e6e5` on 2026-05-11. No code was modified
during this review.*
