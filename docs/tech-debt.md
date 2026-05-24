# ExecuteSpec — Tech-Debt Register

> **Audit date:** 2026-05-24 · `1.0.0-M1` · migration head **V208**.
> Read-only audit of `src/main/java` (~155K LOC) + `frontend/src` (~75K LOC).
> Severity = production risk, not effort. **Living doc** — strike items as they're paid down and cross-link the fixing PR / RL id.

Severity key: **HIGH** = security / correctness / scaling risk or a major maintainability blocker · **MED** = real debt, contained · **LOW** = cleanup / cosmetic.

---

## Backend (Java 21 / Spring Boot)

### HIGH

1. ✅ **RESOLVED** (RL-2026-05-027, PR #170, 2026-05-24) — ~~`/api/v2/test/**` was `permitAll()` in production~~. The nine `test/*TestController`s (Planner/Router/Executor/Worker/Reviewer/NativeTools/ToolRegistry/ContextStore/Metrics), several of which drive the real LLM pipeline, were reachable with no auth. Now gated to `SUPER_ADMIN` in [`auth/security/SecurityConfig.java`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) (verified no frontend / eval-script / test relied on anonymous access).
2. **`RunOrchestrator` God class** — [`integration/RunOrchestrator.java`](../src/main/java/com/navneet/executespec/integration/RunOrchestrator.java), **4,828 lines / 72 methods**. The central pipeline coordinator; every concern funnels through it → the highest-coupling file in the codebase, hard to test or change safely.

### MED

3. **`SupplyChainScanner` advertised but un-wired** — `security/dependency/SupplyChainScanner.java:30` has **zero callers**; a documented defense layer that never runs.
4. **Two silent OPENAI→ANTHROPIC reroutes + in-memory `run.aiProvider` mutation** — `integration/RunOrchestrator.java:3446,3510`, to dodge a "Spring AI 1.1.4 `extra_body` bug". The project is on Spring AI **1.1.6** now, so the workaround may be stale, and mutating the run's provider in-memory muddies billing attribution (self-flagged at :3520).
5. **`ActivityEventEmitter` dependency cycle** — lazily `ObjectProvider`-injected at **5** contracts/standards call sites; a band-aid over a cyclic design. (13 `ObjectProvider` lazy fields total — each marks a wiring cycle worth a structural look.)
6. **Bare swallowed exceptions in lint scope-detection** — `lint/LintPass.java:200,236` (`catch (Exception ignored) {}`, no log) return wrong mtime / touched-file counts that feed lint-scope decisions; violates the "no swallowed exceptions" rule. (Minor swallows also at `memory/ProjectChatService.java:589`, `integration/jira/JiraAdapter.java:147`.)
7. **Saga compensation incomplete** — `saga/SideEffectExecutor.java:199` branch-deletion is a no-op (`GitHubRepoService` has no `deleteBranch`); failed runs leave orphan branches.
8. **Billing/tier migration churn** — V140–V167 + V200 set→loosen→revert→re-bump the FREE/TEAM limits; the V143 product-split was rolled back by V179/V185. Each reversal is a permanent immutable migration — process debt around pricing decisions, not a code bug.
9. **`DevDockerfileGenerator` — 3,538-line file with a 3,138-line escaped-JS-string method** (`crViteAllowedHostsPatch`), self-flagged at :395 as "should be a real file."

### LOW

- Live `@Deprecated` `push-github` endpoint (`api/controller/RunController.java:619`) still called by the frontend (`lib/api.ts`); superseded by `/push-scm` — migrate + retire.
- Dead `@Deprecated` reference methods (`ContextAgent.commitToMain`, `PromptOptimiserAgent.commitToMain`, `PatchAgent.buildSystemPromptInlineDeprecated`) — delete candidates.
- `billing/service/AccountingService.java:123` (`findAll()` + in-memory filter for GST) + `AnalyticsService.java:176` (cost approximated from call duration) — scaling + accuracy, analytics-only.
- ~11 scattered TODOs across `integration/`, `standards/`, `auth/service/UserDataExportService.java` surfaces.

### Documentation drift — corrected in the 2026-05-24 pass

- ~~**Bug #1c** — `ModelResolver` switches provider even under `-Dexecutespec.strict-provider=true`~~ → **mostly mitigated**: `router/ModelResolver.java:84-88` now gates adaptive routing behind `!strict && featureFlagService.isEnabled("routing.adaptive-routing.enabled")` (flag default **OFF**). Residual debt: the switch is still silent when the flag is ON, and there is no per-org opt-out. (Root + `router/CLAUDE.md` updated.)
- ~~**"10 legacy access-guard sites remain"**~~ → **resolved**: `AccessGuardCoverageTest.KNOWN_LEGACY_DRIFT` is now an empty, closed set (35/35 cleared). (Root CLAUDE.md updated.)
- `docs/JAVADOC.md` was pinned to migration head `V202` (actual V208) — corrected; full rewrite still queued.

---

## Frontend (React 18 / Vite 5 / TS 5.3)

### HIGH

1. **No code-splitting — single ~802 KB JS bundle (233 KB gz)** — [`frontend/vite.config.ts`](../frontend/vite.config.ts) has no `build.rollupOptions.output.manualChunks` and no `chunkSizeWarningLimit`; **zero** `React.lazy` / `Suspense` / route-level dynamic imports. Login + marketing pages download the entire authenticated IDE (d3, recharts, framer-motion, highlight.js, posthog, the OTel web SDK — all eager). The biggest, lowest-effort perf win. (One bright spot: Monaco is CDN-loaded, so it is _not_ in the bundle.)
2. **Zero executing frontend tests** — `frontend/package.json` has no test runner (no vitest/jest) and no `test` script; CI (`.github/workflows/lint.yml`) runs only prettier + eslint (`--max-warnings 9999`, so warnings never fail the build). The lone `src/design-system/contrast.test.ts` cannot run (imports `vitest` behind a `@ts-expect-error`). ~75K LOC ships with no unit safety net.
3. **`lib/api.ts` = 3,679 LOC / 307 exported functions** — the API-client god-module; blocks both code-splitting and review. Split by domain.
4. **`signal/pages/ExtraPages.tsx` = 2,782 LOC bundling 14 unrelated pages** (Insights, Library, Migrations, Trust, Pricing, ForgotPassword, AcceptInvite, AuditLog, Deployments, SpecTemplates…) — split per-page.

### MED

5. **OTel web SDK + charts eager on every page** — `main.tsx` `initTelemetry()` pulls the full OTel web stack (`sdk-trace-web` + `auto-instrumentations-web` + exporter) before render, including on `/login`. Gate behind a dynamic import / authenticated-route boundary.
6. **109 `as unknown as` double-casts** — DTO types are incomplete, so callers punch through them inline (heaviest in `MiscPages`, `IdeSystem`, `IdeProjectTabs`). Each is a latent runtime-shape bug the compiler can't catch. (Plus 68 `any` — sanctioned warn-mode per `docs/design/code-style.md` D-T2, but accumulating.)
7. **Three dual-variant panels still ship dead `'signal'` branches** — `PrReviewPanel`, `CodeReviewPanel`, `PrCommentInbox` (`variant: 'signal' | 'ide'`) only ever receive `'ide'` since the Signal chrome was deleted (2026-05-23); collapse the prop. (`GcpCredentialPanel` still has one live `'signal'` caller on a public page.)
8. **9 orphaned Signal-era files** — `signal/components/{BrandLogos,CodeView,FileTree,FileIcon,Glossary,UpgradeModal,UsageMeter}.tsx` + `signal/hooks/{useProjectMeta,useSignalTheme}.ts`: exported, imported by nothing live. Safe deletions (residue of the 2026-05-23 Signal removal).
9. **Duplicated notification-normalization block** — an identical ~50-line `as unknown as {…}` ladder + paging + mark-read in `signal/pages/MiscPages.tsx` ↔ `ide/IdeSystem.tsx`.
10. **`.pa11yci.json` dormant** — well-configured (WCAG2AA, 8 public URLs) but there is no `npm run a11y` script and it never runs in CI; the `_doc` points at a retired `docs/plans/a11y-coverage-plan.md`. Public-page a11y regressions ship undetected; authed routes uncovered.

### LOW

- 12 more IDE files in the 700–2,445 LOC band (`ide/IdeProjectMore.tsx` 2,445, `ide/IdeDiscovery.tsx` 1,749, `ide/IdeRunResult.tsx` 1,571, …).
- `eslint --max-warnings 9999` makes lint warnings non-blocking → warn-mode `any` accumulates unchecked.

---

## Cross-cutting

- **Branch hygiene** — ~25 merged-but-undeleted `feature/*` + `fix/*` remote branches, plus ~8 stale dependabot branches. Prune to keep the branch list honest.
- **No frontend test harness + warn-only lint** — every refactor (especially the bundle-split and `api.ts` split) ships blind. Wiring vitest is a prerequisite for safely paying down the frontend HIGH items.

---

## If you fix five things

1. ✅ ~~Lock down `/api/v2/test/**`~~ — **done** (RL-2026-05-027, PR #170): the 9 test controllers are now `SUPER_ADMIN`-gated.
2. **Code-split the frontend bundle** — `manualChunks` + a handful of `React.lazy` route boundaries + lazy OTel.
3. **Wire a frontend test runner** (vitest) into CI and un-dormant `contrast.test.ts`.
4. **Decompose the two god-files** — `RunOrchestrator.java` (backend) and `lib/api.ts` (frontend).
5. **Wire or delete `SupplyChainScanner`; finish saga branch-deletion compensation.**
