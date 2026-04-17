# Deep-dive audit — Tech / UX / Product / Usability

> Date: 2026-04-17. Branch: `feature/validation-v3-pillar1-requirement-graph`.
> Scope: every major feature surface in the backend + frontend, evaluated
> against four lenses: **Tech** (correctness + reliability), **UX** (visual
> + interaction consistency), **Product** (terminology + information
> architecture + signals), **Usability** (what a real user actually
> experiences, tech + non-tech).
>
> Method: four parallel source-level audits + the live project end-to-end
> test captured in [`project-e2e-bugs.md`](project-e2e-bugs.md) + the
> earlier reviews ([`ux-review.md`](ux-review.md),
> [`projects-usability.md`](projects-usability.md),
> [`eval-results.md`](eval-results.md), [`synthesis.md`](synthesis.md)).
>
> Cumulative LLM spend during the audit: **~$1.50** (cap was $25, 6% used).

## Executive summary

ExecuteSpec's V2 pipeline is structurally sound (one harness, honest
PARTIAL scoring, verified score now surfaced end-to-end per the fixes
shipped earlier today in `f8201b5c`, `3a17e9aa`, `8e9524e3`, `c2e4445e`).
The remaining issues are concentrated in three bands:

1. **Async + transactional hygiene** — several @Async methods still touch
   lazy JPA collections or save un-versioned rows, creating classes of
   bugs like the one fixed in `3a17e9aa` (stuck-EXECUTING). The pattern
   is likely to recur elsewhere until the @Async/@Transactional rule is
   codified.
2. **Design-system adoption is ~60 %** — design tokens exist but are
   bypassed by ~40 components using arbitrary `text-[Npx]`, raw Tailwind
   hex, and one-off modal/empty-state/button patterns. This is what the
   user sees as "validation report looks different from the rest of the
   platform" — and it's the same visual dialect mismatch on 15+ surfaces.
3. **Product affordances for non-technical users** — the Vibe view is a
   partial answer; jargon still leaks (enum names in tooltips, engineer
   vocabulary in templates, no cost preview before the plan gate).

Nothing here is a blocker for shipping. Every item has a concrete fix
and a severity. The **top 10 action items** are consolidated at the end.

## Key for severity + effort

| Tag | Meaning | Typical effort |
|---|---|---|
| `blocker` | Data loss, auth bypass, or obvious user-visible crash | hours |
| `high` | User-visible bug or major UX gap that makes the product feel broken | days |
| `med` | Quality-of-life, inconsistency, minor data error | days |
| `low` | Polish, cleanup, future-proofing | hours |

---

## Tech — backend

Source: parallel deep-dive + live log analysis during the project E2E.

### Run & execution lifecycle

- **`high` — `V2RunOrchestrator.terminate()` writes status without
  pessimistic lock or `@Version`.**
  [`v2/integration/V2RunOrchestrator.java:625-634`](src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java:625).
  Two concurrent `cancel` + planner-terminate paths can race and both
  succeed. Same shape as the `approvePlanAndExecute` race we fixed in
  `c2e4445e` (still open as bug #3).
  **Fix:** add `@Version` to `PipelineRun`, or wrap each terminal
  transition in a dedicated `@Transactional` method with an explicit
  `findByIdForUpdate`.

- **`high` — `ExecutionOrchestrator.approvePlanAndExecute` is `@Async` and
  clobbers `planApprovedAt` on concurrent writes.** Observed live on the
  E2E test: first approve (20 s after `/plan`) returned HTTP 202 but
  the timestamp didn't persist; second approve (2 min later) worked.
  [`execution/service/ExecutionOrchestrator.java:193-199`](src/main/java/com/navneet/executespec/execution/service/ExecutionOrchestrator.java:193).
  **Fix (design decision required):** (a) make `/plan` synchronous,
  (b) return 409 from `/plan/approve` when `executionPlan == null`, or
  (c) add `@Version`. Tracked as bug #3 in `project-e2e-bugs.md`.

- **`med` — Cache-invalidate exception swallowed at run finalization.**
  [`v2/integration/V2RunOrchestrator.java:611-613`](src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java:611):
  `catch (Exception ignore) {}` on `stalenessService.invalidate(...)`.
  On failure, Redis keeps stale state and the next planner reads a
  wrong project brief.
  **Fix:** log at WARN + emit a metric; don't break the run but make the
  failure observable.

### Billing & webhooks

- **`high` — No credit refund on PARTIAL (only on FAILED).**
  `billing/service/RunQuotaRefundListener.java`. Per the v2.5.10 honest
  PARTIAL work, a run can terminate PARTIAL with `CLEAN_WITH_INTERMEDIATE_WARNINGS`
  (i.e. build passed, reviewer clean, one flaky smoke) and the user still
  pays full credits.
  **Fix:** extend the refund listener to refund when
  `statusReason=CLEAN_WITH_INTERMEDIATE_WARNINGS` or the verified score
  crosses some threshold. Partial refund policy is a product decision.

- **`high` — Stripe webhook dedup is best-effort, not enforced.**
  [`billing/controller/BillingWebhookController.java:37-100`](src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java:37)
  logs the event to `BillingWebhookEvent` for audit but doesn't short-circuit
  on replay. Razorpay/Stripe retries on timeout → possible double
  charge/refund.
  **Fix:** `findByGatewayAndEventId → 200 (already processed)` before
  dispatching downstream. Add a composite index on `(gateway, event_id)`.

- **`med` — GitHub webhook status code masks auth-vs-parse failure.**
  [`project/pr/GitHubWebhookController.java:37-77`](src/main/java/com/navneet/executespec/project/pr/GitHubWebhookController.java:37)
  returns 400 on any exception, including signature mismatch.
  **Fix:** 401 for HMAC mismatch, 400 for parse errors, 500 for unexpected.

### Auth & security

- **`high` — `/api/v2/runs/**` still permits-all in `SecurityConfig`.**
  [`auth/security/SecurityConfig.java:144-145`](src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java:144).
  Comment says "Phase 9.x" (temporary). It's live today and lets anyone
  poke V2 internals.
  **Fix:** remove the permit-all; add `@Authenticated` + feature flag
  guard while whatever canary work finishes.

- **`med` — Rate-limit bypass via X-Forwarded-For.**
  [`ratelimit/RateLimitFilter.java:225-232`](src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java:225).
  Hard-codes "trust the rightmost entry". With a 2-proxy chain the
  client's 2nd-to-last entry lands in the bucket key.
  **Fix:** make the trusted-proxy count configurable; default to "no
  trust" (use the direct peer IP).

- **`med` — SSE token scope not re-checked at the event endpoint.**
  [`auth/security/JwtUtil.java:86-97`](src/main/java/com/navneet/executespec/auth/security/JwtUtil.java:86)
  correctly embeds `runId`, but the handler at `/api/runs/{id}/events`
  doesn't verify the claim matches the path.
  **Fix:** explicit `claims.runId == pathVar.id` check inside the SSE
  subscribe handler; reject 403 otherwise.

- **`low` — Symlink traversal on `PathSafety.safeResolve`.**
  [`v2/tool/PathSafety.java:25-40`](src/main/java/com/navneet/executespec/v2/tool/PathSafety.java:25).
  `toRealPath()` follows symlinks, so a workspace-local symlink pointing
  to `/etc` passes the `startsWith` check.
  **Fix:** `normalize()` + explicit bounds check before `toRealPath`.

### Threading & async hygiene

- **`high` — Two executor pools never shut down gracefully.**
  [`v2/integration/V2RunOrchestrator.java:152-160`](src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java:152)
  + [`v2/integration/PerkyMessenger.java:48-50`](src/main/java/com/navneet/executespec/v2/integration/PerkyMessenger.java:48).
  Daemon threads hide the leak at local dev, but on SIGTERM the JVM
  drops ongoing runs mid-flight after billing was already charged.
  **Fix:** `@PreDestroy pool.shutdownNow()` + `awaitTermination(grace)`
  on both. Or migrate to Spring `TaskExecutor`/`TaskScheduler` which
  Spring already drains on shutdown.

- **`med` — @Async + lazy JPA is still live elsewhere.** Fixed one
  instance in `executeApprovedPlan` (`3a17e9aa`), but the shape recurs in
  every `@Async` method that touches a proxied collection. There is no
  ArchUnit/test guard against adding more.
  **Fix:** ArchUnit rule: `@Async` methods may not call `get*` on any
  `@OneToMany`/`@ManyToMany` return without explicit fetch in the query.

### Observability

- **`med` — No counter for V2 phase transitions.**
  `V2RunOrchestrator` is full of `log.info` but has no
  `Counter.builder("executespec.v2.run.phase", "phase", name).increment()`.
  Prometheus can't alert on a queue backing up at PLANNING.
  **Fix:** one Counter per `setStatus(...)` call.

- **`low` — INFO logging for per-run chatter floods prod.**
  Multiple `log.info("V2 run={} cloned {} project repos", ...)` fire on
  every run. At 100 runs/min this is a lot.
  **Fix:** demote to DEBUG; keep INFO for terminal outcomes + alerts.

### Known-debt that we chose not to fix today

- Bug #3 (plan/approve race) — design decision needed.
- Planner/reviewer tier-model resolution
  ([`PlannerAgentV2.plannerViaLlm`](src/main/java/com/navneet/executespec/v2/agent/PlannerAgentV2.java) uses
  `req.model()` not `ModelResolver.resolve(tier, provider)`).
  `ModelResolver` fix today (`c2e4445e`) closes adaptive-routing but
  the planner still picks up the run-level default instead of the tier
  model. Still flagged in CLAUDE.md.
- V2 batch execution (whole plan in `docs/plans/v2-batch-execution-plan.md`).

---

## Tech — frontend

Source: parallel deep-dive + 2 earlier audits.

### Dead V1 code still shipped

- **`high` — V1 endpoints still defined + called in `api.ts` but return
  HTTP 410.**
  [`frontend/src/lib/api.ts:444-469`](frontend/src/lib/api.ts:444):
  `clarifyRun`, `mergeRun`, `implementSuggestions`, `reworkRun`, `pauseRun`,
  `resumeRun`. `useRunStore` still destructures them. No 410 handling in
  `STATUS_MESSAGES` ([`api.ts:24`](frontend/src/lib/api.ts:24)), so the
  client surfaces a raw "HTTP 410" error.
  **Fix:** delete the V1 endpoints from `api.ts` + `useRunStore`;
  grep the UI for call sites and strip the dead branches (chat/iterate
  modals). Add a friendly 410 → toast just in case.

- **`med` — Batch UI wired but V2 never emits batch events.**
  [`frontend/src/components/runs/RunDetail.tsx:392,546`](frontend/src/components/runs/RunDetail.tsx)
  +
  [`frontend/src/lib/api.ts:250`](frontend/src/lib/api.ts:250).
  `QUEUED_BATCH`, `IN_PROGRESS_BATCH`, `BATCH_COMPLETE` render paths are
  live but unreachable under V2.
  **Fix:** gate with a feature flag (`v2.batch.enabled`) so the code can
  stay for the future batch work without shipping dead UI to users.

- **`low` — `RunTimeline.tsx` imported but never rendered.**
  [`frontend/src/components/runs/RunDetail.tsx:12`](frontend/src/components/runs/RunDetail.tsx:12).
  **Fix:** delete the file + import.

### Responsive + mobile

- **`high` — 375 px viewport breaks the project dashboard tab strip.**
  10 tabs (Overview / Launches / PRs / Tasks / Repos / Architecture /
  Docs / Memory / Chat / Team) don't fit and don't scroll horizontally.
  [`frontend/src/pages/project/ProjectDashboardPage.tsx:241-245`](frontend/src/pages/project/ProjectDashboardPage.tsx:241).
  **Fix:** wrap the strip in `overflow-x-auto scrollbar-hide`; collapse
  to a dropdown under `sm` breakpoint.

- **`med` — `useIsMobile` returns `true` for tablets too.**
  [`frontend/src/hooks/useIsMobile.ts:3`](frontend/src/hooks/useIsMobile.ts:3):
  threshold is 1024 px. Tablet (768 px) is labelled "mobile", which
  drives the wrong branch in multiple layouts.
  **Fix:** split into `useIsMobile` (<768 px) and `useIsCompactLayout`
  (<1024 px).

### Stores + state sync

- **`med` — `useRunStore.activeRun` can show stale data after SSE gap.**
  [`frontend/src/hooks/useSse.ts:70`](frontend/src/hooks/useSse.ts:70):
  reconnect triggers `fetchRun`, but without debounce it fires on every
  retried event burst.
  **Fix:** `lodash.debounce(fetchRun, 300)` to coalesce.

- **`low` — Cross-store fetch coupling.**
  `ProjectDashboardPage` calls `api.getRun()` directly instead of via
  `useRunStore`, so the run is cached in two places.
  **Fix:** route the fetch through `useRunStore.fetchRun`.

### Error handling

- **`med` — `req()` silently coerces 204 to `undefined as unknown as T`.**
  [`frontend/src/lib/api.ts:59-62`](frontend/src/lib/api.ts:59).
  If a `Promise<RunDetail>` consumer gets `undefined`, the next field
  access crashes on an unrelated line.
  **Fix:** dedicated `reqVoid()` for 204 endpoints; runtime guard on
  typed ones.

- **`med` — No global 402 handler.** `NewRunModal` parses
  `"402: RUNS limit reached..."` by string-matching
  ([`NewRunModal.tsx:211-222`](frontend/src/components/runs/NewRunModal.tsx:211)).
  Any other endpoint hitting 402 shows the raw error.
  **Fix:** add 402 to `STATUS_MESSAGES` with a friendly copy + link to
  `/plans`.

### Forms

- **`med` — Submit-while-invalid possible on `NewRunModal`.**
  [`NewRunModal.tsx:550`](frontend/src/components/runs/NewRunModal.tsx:550):
  button disables on some checks but not all; paths that bypass render
  still reach `createRun()`.
  **Fix:** guard at the top of `handleSubmit` with a single
  `if (!isValid(state)) return;`.

- **`low` — Figma URL field has no format validation.**
  [`NewRunModal.tsx:361-368`](frontend/src/components/runs/NewRunModal.tsx:361).
  **Fix:** `URL.canParse(v) && v.includes('figma.com')` before submit.

### Accessibility

- **`med` — No focus trap on modals.** `NewRunModal`, `DeployWizardModal`,
  `FailureDecisionModal`.
  **Fix:** `react-focus-lock` wrapper at the shared `ResponsiveModal`.

- **`low` — Icon-only buttons missing `aria-label`.** Header hamburger,
  modal close buttons, run archive button.
  **Fix:** add labels; audit via `eslint-plugin-jsx-a11y`.

---

## UX — design-system adoption

Source: the UX consistency agent + the earlier `ux-review.md`.

This section focuses on what the user sees as *"the validation report
looks different from the rest of the platform"* — it's the same visual
dialect mismatch across 15+ surfaces. The validation panel itself was
fixed today (`8e9524e3`), but the pattern still exists on most of the
Project surface.

### Design tokens exist; adoption is spotty

From [`frontend/src/tokens.css`](frontend/src/tokens.css) the system
defines:
- Greys `--gray-100 … --gray-1000`
- Borders `--border-default / --border-hover`
- Status `--status-success / executing / thinking / waiting / partial / error / blocked / queued`
- Accent `--accent-blue / harness / planner`
- Radius `--radius-sm (2) / md (4) / lg (6)`

Tailwind extends these as `text-status-*`, `bg-status-*`,
`text-accent-*`, `rounded-sm/md/lg`. Every component *could* consume
them. Roughly 40 don't.

### Quantified inconsistencies

- **`high` — ~40 components use `text-[Npx]` arbitrary values** instead
  of the Tailwind scale. Most common: `text-[10px]`, `text-[11px]`,
  `text-[9px]`, `text-[12px]`, `text-[13px]`, `text-[14px]`. Audit hit
  sites include
  [`NotificationPanel.tsx:95`](frontend/src/components/layout/NotificationPanel.tsx:95),
  [`DiffView.tsx:23`](frontend/src/components/shared/DiffView.tsx:23),
  and every Project component I cited in `ux-review.md`. **Fix:** ESLint
  rule banning bracket pixel sizes for `text-*`/`p-*` outside
  `tailwind.config.ts`.

- **`high` — Empty-state primitive exists but only 2-3 call sites use
  it.** [`components/shared/EmptyState.tsx:15-25`](frontend/src/components/shared/EmptyState.tsx:15)
  is the intended shared. ~18 components hand-roll empty states instead:
  [`ProjectTaskList.tsx:40`](frontend/src/components/project/ProjectTaskList.tsx:40),
  [`NotificationPanel.tsx:3`](frontend/src/components/layout/NotificationPanel.tsx:3),
  `ProjectDocumentsPanel`, `ProjectPrList`, `RunConversation`, etc.
  **Fix:** replace the 18 sites with `<EmptyState />`; lint rule to
  catch future regressions.

- **`med` — Status badges reimplemented outside `StatusBadge`.**
  [`ValidationReportPanel.tsx`](frontend/src/components/runs/ValidationReportPanel.tsx)
  was fixed today, but status pills are still hand-rolled on
  [`ProjectTaskList.tsx:74`](frontend/src/components/project/ProjectTaskList.tsx:74),
  [`ProjectDashboardPage.tsx:456`](frontend/src/pages/project/ProjectDashboardPage.tsx:456),
  [`DeployWizardModal.tsx:583`](frontend/src/components/deployments/DeployWizardModal.tsx:583),
  `TechBadges.tsx`. Each uses its own colour map — risk of drift.
  **Fix:** one `<StatusBadge />`; score-colour goes via the same
  `scoreTone()` helper.

- **`med` — Button hierarchy is invisible.** No shared `<Button>` with
  `variant="primary|secondary|tertiary|ghost"`. Every component picks
  its own hover/focus/disabled states.
  [`NewRunModal.tsx:552`](frontend/src/components/runs/NewRunModal.tsx:552)
  primary uses `bg-accent-blue`;
  [`DeploymentsPage.tsx:66`](frontend/src/pages/DeploymentsPage.tsx:66)
  also `bg-accent-blue`; secondary/tertiary diverge per author.
  **Fix:** extract a single primitive with the three variants.

- **`med` — Page padding inconsistent.** `px-4`, `px-6`, `px-4 sm:px-6`,
  and no padding appear across page roots. Target: `const PAGE_PADDING = 'px-4 sm:px-6'`.

- **`med` — Modal close buttons vary by 30-50% in hit target.**
  [`ResponsiveModal.tsx:106`](frontend/src/components/shared/ResponsiveModal.tsx:106)
  uses `p-1.5`;
  [`NewRunModal.tsx:279`](frontend/src/components/runs/NewRunModal.tsx:279)
  uses `p-2 -m-1`;
  [`FailureDecisionModal.tsx:64`](frontend/src/components/execution/FailureDecisionModal.tsx:64)
  uses a bare `×` glyph. All modals should go through `ResponsiveModal`.

- **`med` — Checkboxes/radios use native inputs.** No wrapper means the
  OS default ships. Looks different on macOS vs Windows vs iOS.
  **Fix:** build (or adopt) a `<Checkbox />` + `<Radio />`.

- **`med` — Timestamps leak raw ISO strings in ~18 places.**
  [`ProjectTaskList.tsx:78`](frontend/src/components/project/ProjectTaskList.tsx:78)
  does `new Date(...).toLocaleString()` (browser-locale unpredictable).
  `RunCard`/`StatusBadge` use `relativeTime(...)` correctly — that
  helper should be everywhere.

- **`low` — Monaco theme bundled, not dark-mode aware.** The app is dark;
  Monaco matches because the theme is hard-coded
  ([`monacoGeistTheme.ts`](frontend/src/lib/monacoGeistTheme.ts)). If a
  user ever gets a light mode we'll mismatch.

### Reuse the Validation Report playbook

The single-PR fix in `8e9524e3` is the template: switch every raw
`emerald-*/red-*/yellow-*` class to `status-success/error/waiting`,
replace inline pills with `StatusBadge`, replace hand-rolled empty
strings with `EmptyState`, replace `text-[Npx]` with scale tokens,
replace ad-hoc button styles with `<Button variant="…" />`. Do it one
component at a time, with visual-regression screenshots.

---

## Product — terminology, messaging, affordances

Source: product-signals agent + persona report + live walkthrough.

### Terminology fragmentation

- **`med` — "Run" vs "Execution" vs "Launch" vs "Solution".** Each has
  a technical meaning (`PipelineRun`, `ExecutionRun`, per-repo
  `ExecutionSolution`) but the UI mixes them. The title of the global
  new-run modal says *"Launch New Run"*
  ([`NewRunModal.tsx:264`](frontend/src/components/runs/NewRunModal.tsx:264)),
  which conflates a Launch (project-scoped, multi-repo) with a Run
  (single-repo). Maya can't tell which she's starting.
  **Fix (copy only):** one vocabulary. Recommend: "Run" (user-facing,
  single artefact), "Launch" (project-scoped cross-repo run). Retire
  "Execution" / "Solution" from the UI; keep in DTOs only.

- **`low` — Enum leakage in tooltips.** `StatusBadge.statusReason`
  tooltip copy covers most cases but the first round of eval runs saw
  raw `FINAL_BUILD_FAILED` / `TESTS_FAILED:5_OF_12` in tooltips. The
  copy dict exists (already synced into
  [`ValidationReportPanel`](frontend/src/components/runs/ValidationReportPanel.tsx)
  today); ensure `StatusBadge` uses the same.

### Cost signals

- **`high` — Cost isn't previewed before submit.** The estimate endpoint
  (`/api/projects/{id}/estimate`) is wired into the Launch flow
  (`ExecutionSetup` debounced chip) but **not** the global New Run modal.
  Users submit, pay for one round-trip, then see the cost at plan
  approval. Observed on every normal run.
  **Fix:** Port the Launch debounced chip to
  [`NewRunModal.tsx:184-224`](frontend/src/components/runs/NewRunModal.tsx:184).

- **`med` — Quota bar has no upgrade CTA.**
  [`QuotaUsageBar.tsx:7-35`](frontend/src/components/billing/QuotaUsageBar.tsx:7):
  warns at 70 %, red at 90 %, no link to `/plans`. Users who proactively
  check quota get no next step.
  **Fix:** `{pct >= 70 && <Link to="/plans">Upgrade</Link>}` in the bar.

- **`med` — No credit refund on PARTIAL-but-clean.** Mirror of the
  backend finding above. Product decision: should
  `CLEAN_WITH_INTERMEDIATE_WARNINGS` count as a success?

### Notifications

- **`med` — Three high-value events unsent.**
  - "Plan ready — start?" when run flips to `AWAITING_APPROVAL`.
  - "Credits 80 % used" threshold alert.
  - "Your PR was merged" from the GitHub webhook.
  
  None of these produce `Notification` rows today. Users must
  manually refresh to discover time-sensitive gates.
  **Fix:** emit from the orchestrator + webhook handler; settings page
  opt-in.

### First-run + onboarding

- **`high` — New user landing is a list of *other people's* runs.**
  [`RunsPage.tsx:119`](frontend/src/pages/RunsPage.tsx:119) shows
  "Select a run to view details" against a list that may include org
  runs — not helpful for a brand-new user with zero runs.
  **Fix:** an empty-state hero card with a "Try a sample run" CTA that
  pre-fills `FirstRunSuggestions`.

- **`med` — `NewRunModal.tsx:303` language-support hint sits below the
  textarea.** Hint is *"Supported: Java, TypeScript, React, Python, Go,
  Rust, HTML/CSS"* but placement is after submit in the visual hierarchy.
  **Fix:** move above the textarea; add example specs.

- **`med` — `executionMode` default is `PRODUCTION`.**
  [`NewRunModal.tsx:30`](frontend/src/components/runs/NewRunModal.tsx:30).
  First-time users get the expensive, gated path. Should default to
  PROTOTYPE.

### Vibe view

- **`med` — Language-support list leaks into Vibe.** The NewRunModal's
  technical list is reused under Vibe. Maya doesn't know what
  "TypeScript" is.
  **Fix:** per-mode hint (`VibeRunSubmit` gets "Web · Mobile · API ·
  Backend"; Pro gets the precise list).

- **`low` — `FirstRunSuggestions` uses engineer names.** *"Expense
  Tracker"* → *"Spending logger"*; *"Portfolio"* → *"Personal website"*.

---

## Usability — what a real user actually experiences

Two persona-oriented walkthroughs.

### Engineer (Pro tier, submits runs weekly)

**What works:**
- Validation report (post-`8e9524e3`) — verified score primary, signal
  breakdown chip, honest statusReason narrative, token-aligned colours.
- Dashboard (post-`f8201b5c`) — AVG SCORE is now driven by verifiedScore
  not LLM hallucinations. Matches intuition ("my run passed, score
  reflects it").
- Retry dedup (post-`eb3b55b8`) — deterministic failures now abort
  after 2 attempts instead of 4. Cost savings visible per launch.
- Launches tab (post-`3a17e9aa`) — finally transitions EXECUTING →
  COMPLETE instead of sticking forever.

**What jars:**
- Plan-approve race (bug #3 still open) — approve at T+20 s after /plan
  sometimes silently fails.
- Provider switch observed: requesting OpenAI, planner ran on Gemini
  (adaptive routing, now fixed in `c2e4445e` behind
  `-Dexecutespec.strict-provider=true`).
- No notification when AWAITING_APPROVAL arrives.
- V1 dead endpoints occasionally show as 410 if clicked.

### Non-technical PM ("Maya" persona)

**What works:**
- Preview tab is accessible; running a generated app in-product is the
  moment of magic.
- Cost badge at plan-approval is readable.
- Chat on OpenAI-defaulted projects gives grounded, cited answers
  (post-backend restart landing sonnet-4-6 for Anthropic projects too).

**What stops her cold:**
- Dashboard at 375 px mobile: 10 tabs overflow invisibly.
- Jargon: `ANALYSING / ARCH_REVIEW / SETUP`. The v2.5.10 copy dict
  handles most, but a few surfaces still show raw enum.
- Stepper has 9 steps (Planning · Approval · Tasks · Compilation ·
  Review · Merge · Preview · Testing · Report). She expects 3-4.
- "Source: REST / MANUAL / GITHUB_NEW" dropdown in the New Run modal
  leaks the API-internal label.
- No cost preview before submit — the estimate exists (Launch uses it);
  it's just not on the regular New Run.
- `FirstRunSuggestions` samples have engineer-friendly names.

### Cross-persona

- Both see stuck/zombie executions regularly (per the reconcile endpoint
  that had 5 orphans on a 24-h-old DB). The scheduled reconcile is
  already there ([`AdminReconciliationService.scheduledReconcile`](src/main/java/com/navneet/executespec/execution/service/AdminReconciliationService.java));
  frequency (15 min) is sensible, keep.
- Both see the retry loop dedup message on the Failed-solution card —
  readable ("Same failure signature 'FINAL_BUILD_FAILED' on 2 consecutive
  attempts; aborting retries"). Works per `eb3b55b8`.

---

## Top 10 action items (recommended sprint order)

1. **`high` — Port the launch-time estimate chip to the global New Run
   modal.** Product signal #1. ~4 h engineering.
2. **`high` — Extract `<Button>`, `<EmptyState>`, `<StatusBadge>`
   primitives + sweep 40+ component call sites.** Closes the
   "validation report looks different" complaint everywhere, not just
   on the report. ~3 days.
3. **`high` — Delete V1 dead endpoints from `api.ts` + `useRunStore`.**
   Stops HTTP 410 leaking into UI. ~half day + grep.
4. **`high` — Add `@PreDestroy` to the three unmanaged executor pools.**
   Stops SIGTERM from abandoning paid-for runs. ~1 h.
5. **`high` — Fix mobile dashboard tab overflow + `useIsMobile` breakpoint.**
   Unblocks non-trivial mobile traffic. ~2 h.
6. **`high` — Emit notifications for AWAITING_APPROVAL + quota 80 % +
   PR merged.** Three event types, one backend change + one settings
   opt-in. ~1 day.
7. **`med` — Decide + ship plan-approve race fix (bug #3).**
   Synchronous `/plan` is the simplest. ~2 h + integration test.
8. **`med` — Refund credits on `CLEAN_WITH_INTERMEDIATE_WARNINGS`.**
   Product decision first; implementation is a one-line if in
   `RunQuotaRefundListener`.
9. **`med` — Migrate planner + reviewer off `req.model()` onto
   `ModelResolver.resolve(tier, provider)`.** So the model matrix
   actually controls the planner. CLAUDE.md tech debt.
10. **`med` — Add ArchUnit rule: `@Async` methods may not touch proxied
    collections without explicit fetch in the query.** Locks in the
    `3a17e9aa` fix; prevents future stuck-EXECUTING regressions.

### Longer-term (next 1-2 sprints)

- Refactor `V2RunOrchestrator` (1675 LOC) and `StateStore` (1647 LOC)
  into bounded-context services.
- Visual-regression CI on five key pages (`/`, `/runs/{id}`,
  `/org/projects`, `/projects/{id}/dashboard`, `/projects/{id}/executions/new`).
- Keyboard + focus accessibility pass (focus trap, aria-label on icon
  buttons, visible focus rings).
- First-run guided tour for Vibe users.

---

## What this audit did NOT cover

- Live LLM cost-shape regression (eval matrix was run earlier; see
  `eval-results-v2.md`).
- SSE back-pressure load testing (flagged in `high` but not measured).
- End-to-end billing webhook replay test (flagged, not exercised).
- Deep DB index audit (only the obvious ones surfaced).
- Infrastructure / terraform review.

These are worth follow-up but require more than a source-level read.

---

## Appendix — fixes shipped in the last 4 commits on this branch

| SHA | What |
|---|---|
| `f8201b5c` | dashboard uses verifiedScore + 6 h JWT TTL + V110 enables `scoring.v2-verified` |
| `eb3b55b8` | PARTIAL retry dedup via V107 statusReason signature + V111 |
| `be110b7a` | `failureReason` populated on every worker-FAILED terminal outcome |
| `8e9524e3` | ValidationReportPanel token-aligned + surfaces statusReason narrative |
| `3a17e9aa` | LazyInit fix on `solution.repoMappings` — stuck-EXECUTING resolved |
| `c2e4445e` | solution.prUrl sync + maxRetries DTO + ModelResolver strict-provider + /projects/:id redirect |

Push target: `feature/validation-v3-pillar1-requirement-graph` (last push
`c2e4445e` at 2026-04-17T03:10).
