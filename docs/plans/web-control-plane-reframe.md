# Web control-plane reframe — design + migration plan

> Founder directive (2026-05-27): act on repeated user feedback that the web UI/UX is confusing +
> unintuitive. Reframe the **web app as a control plane** (mission control for *delegate-and-oversee*),
> while the **VS Code extension** and **CLI** continue in parallel (founder: *"we are not stopping on VS
> Code and CLI"*). The 2026-05-22 IDE-only switch chased a VS Code *aesthetic* and got it — but the
> *metaphor* (a code editor) mismatches the product's job (submit a spec → approve a plan → get a
> reviewed PR). RL-2026-05-052.

## 1. Why (the case, in one paragraph)

ExecuteSpec is a *delegate-and-oversee* product: the agent writes the code; the human submits a spec,
approves the plan gate, and reviews the resulting PR. The web chrome (`ide/IdeWorkspace.tsx`) is a
faithful VS Code clone — activity bar + an "Explorer" + an "editor area" + an "IDE · Beta" status badge —
which tells every user *"you edit code here."* They don't: the "Explorer" shows **runs and projects, not
files**, so the borrowed metaphor actively misleads. Three downstream harms: (a) the **primary action**
(submit a spec) is one of four equal activity-bar icons instead of the unmistakable hero CTA it should
be; (b) the **plan gate** — the single human-in-the-loop moment the whole funnel hinges on — has **no
global indicator**, so a run can sit `AWAITING_APPROVAL` blocked on a user who never notices; (c) the web
app **competes with our own VS Code extension** (`clients/vscode`), which is the thing that *should* own
the IDE metaphor. Net: the chrome optimizes aesthetic at the cost of the mental model. **(Real-user feedback, 2026-05-27,
sharpens this — see §3.5:** users explicitly *want* the live telemetry — tokens, actual $,
estimate-utilisation, live log, tasks+status — they want it **always-on, real-time, persisted**, NOT
stripped. So the reframe keeps the technical depth and elevates the in-flight run to a first-class *live
run cockpit*; the real confusion is in the *flows* — entry, chat, spec-builder, plan/timeline — and in
*staleness*, e.g. the side-menu "Recent runs" never updates live.)

## 2. The thesis — three clients, three jobs (all advancing in parallel)

| Client | Job | Metaphor | Status |
|--------|-----|----------|--------|
| **Web app (this reframe)** | **Mission control** — submit, watch, **approve the gate**, review PR + score, manage projects/repos/org/billing | a product *console*, not an editor | reframe starts now |
| **VS Code extension** (`clients/vscode`) | the **in-editor** experience — runs/approvals/PRs where developers already live | a real IDE (because it *is* one) | continues (not paused) |
| **CLI** (`clients/cli`) | headless / CI | a terminal | continues (not paused) |

This is **additive, not a reprioritization**: the web reframe does not slow the clients. The web app stops
*imitating* an IDE and becomes the best possible control plane; the IDE metaphor lives where it's actually
true (the extension). All three share the backend + `clients/core` typed REST/SSE client where applicable;
the web app keeps using the split `frontend/src/lib/api/` client.

## 3. The primary flows (make these unmissable)

The activation funnel is already instrumented (`lib/analytics.ts` — events mirror the backend
`getActivationFunnel()` 6-stage funnel): SIGNUP → PROJECT_CREATED → SPEC_SUBMITTED → PLAN_APPROVED →
RUN_COMPLETE → PR_MERGED. The reframe optimizes the chrome around exactly this path:

1. **Start a run** — a hero CTA on Home (not a peer icon). Routes to `/compose` (`IdeCompose`).
2. **Approve the plan** — a **persistent global "N awaiting your approval" indicator** in the top bar,
   visible from every screen, one click to the gate (`/plan/:id` → `IdePlan`). Highest-leverage single
   change; directly lifts SPEC_SUBMITTED → PLAN_APPROVED.
3. **Review the PR + score** — the existing run-detail file-tree + code viewer (`IdeRunResult`) is kept;
   reviewing the agent's diff *is* a control-plane job.

## 3.5 Real-user feedback (2026-05-27) → treatment

Strong, specific feedback from several users. Verbatim items, mapped to where they live today and how the
reframe answers them:

| # | User said | Lives in today | Treatment |
|---|-----------|----------------|-----------|
| 1 | Show **token usage** in real-time + total at the end | `RUN_COST` SSE / `getLiveCost` / `RunSummary.total*Tokens` | Cockpit: live token counter, persisted; final total on the terminal view |
| 2 | Show **estimate** + **utilisation against it** | `EstimateChip` (p25/p75) + `CostProgressBar` (vs hard cap only) | Add a **spend-vs-estimate** utilisation bar (not just vs the platform cap) |
| 3 | Show **actual cost** in real-time + updates around it | `getLiveCost` poll + `RUN_COST` SSE | Cockpit: always-visible $ + threshold updates (50 / 80 / 100% of estimate) |
| 4 | Keep showing the **live log** | `IdeLive` in-mem console + `IdeRunResult` Logs tab (Redis replay) | Cockpit log, **persistent** — never vanishes on navigate / after terminal |
| 5 | Keep showing **tasks + status** | `TASK_STATUS` SSE + task list | Cockpit task list, always-on, live status |
| 6 | **Combine Plan + Timeline**; show status via the timeline | `IdePlan` (gate) + Timeline tab (in `IdeRunResult`) | Merge into one **status-driven timeline** (gate = first node; tasks / build / review light up live) |
| 7 | **Run / Project chat** UX needs work | `ChatService`/`RunChatTools`, `ProjectChatService`/`ProjectChatTools` | Chat UX pass (threading, streaming, copy, grounding) |
| 8 | **Build-my-Spec** chat is bad UX; stop the 5 standard design questions — **suggest first, then ask** | `SpecChat.tsx` + its backend spec-builder agent | Behavior change: lead with a **proposed spec** from our side (one-liner + project context), then ask what to adjust; drop the 5-question script |
| 9 | **Project run / launch tracking** needs work | `IdeProjectRuns` (Runs / PRs split) | Real-time launch + run tracking in the project surface |
| 10 | Side-menu **"Recent runs" not real-time** | `IdeWorkspace` fetches once (`useEffect([], …)`) | Live recent-runs (poll + SSE-driven), in the new shell |

**The cross-cutting principle — a "live run cockpit."** Items 1–5 (+10) say the same thing: users *value*
the telemetry and want it **always-on, real-time, persisted**. So the reframe does NOT strip detail — it
elevates the in-flight run to a first-class cockpit (**tokens · actual $ + estimate-utilisation · live
log · tasks+status**), driven by the SSE events the app already dispatches (`RUN_COST` / `LIVE_LOG` /
`TASK_STATUS` / `RUN_STATUS`), persisted to the run record so final totals survive to the terminal view,
and surfaced live across the chrome (no more stale lists).

**Plan + Timeline → one surface (item 6).** Merge `IdePlan` and the Timeline tab into a single
status-driven timeline: the plan gate is the first node; as the run proceeds, each task / build / review
step lights up live on the same timeline. One place to approve *and* to watch.

**Chat UX (items 7–8).** (a) Run + Project chat get a UX pass (clearer threading, streaming, per-bubble
copy, visible grounding). (b) **Spec-builder behavior change:** `SpecChat` stops asking the 5 standard
design questions up front; instead it **proposes a spec/design first** (from the user's one-liner + project
context), then asks what to change. Requires a backend tweak to the spec-builder agent's prompt/flow — the
only non-trivial backend change in the whole reframe.

## 4. Information architecture + shell anatomy

**Object model made legible:** Org → Project → Repos → Run → PR. A consistent header + breadcrumbs on
every run/project surface so the user always knows where a run came from and what it produced.

**New shell** (`console/ConsoleShell.tsx`) — regions:

- **Top bar** (persistent, every screen): brand/home link · **global plan-gate indicator** (§6) ·
  primary **New run** button · `⌘K` command palette (reuse `IdeCommandPalette` from `IdeKit`) ·
  notifications bell (reuse `IdeSystem` notifications) · account menu (Settings / Billing / Org / Admin /
  theme / logout tucked here).
- **Left nav** (slim, *sections* — NOT a file "Explorer"): **Home** · **Runs** · **Projects** (+ a
  super-admin-only **Admin** entry). Collapsible; remembers width in `localStorage` (reuse the existing
  `es:ideSideW` pattern). This replaces the activity-bar + Explorer split with a single product nav.
- **Content area:** the routed surface (`<Outlet/>`), unchanged surfaces re-mounted here.
- **No** "editor area" framing, **no** "IDE · Beta" badge, **no** file-tree Explorer at the chrome level.

**Responsive:** desktop = top bar + left nav + content; mobile/tablet = top bar (keeps the plan-gate
indicator) + bottom tab bar (Home / Runs / Projects / Account) + drawer for overflow. (Same dual-layout
discipline as today, but a dashboard, not an IDE.)

## 5. What changes vs. what's reused (re-shell, NOT rewrite)

| Current (`ide/*`) | Fate |
|---|---|
| `IdeWorkspace` (activity bar / Explorer / editor / status bar) | **Replaced** by `console/ConsoleShell` |
| `IdeWelcome` (in `IdeSurfaces`) | **Replaced** by `console/ConsoleHome` (the dashboard) |
| `IdeRunResult` (file tree + code viewer + run info) | **Kept**, re-shelled — reviewing the diff is core control-plane |
| `IdeProjectTabs` / `IdeProjectMore` / `IdeProjects` / `IdeProject` | **Kept**, re-shelled (legibility pass: headers / breadcrumbs) |
| `IdeCompose` / `IdeLive` / `IdePlan` | **Kept**, surfaced more prominently (compose = hero; plan = gate target) |
| `IdeBilling` / `IdeOrg` / `IdeSystem` / `IdeDiscovery` / `IdeSettings` | **Kept**, reached from the account menu (Settings / Billing / Org / Admin / Integrations) |
| `IdeKit` (code viewer / palette / tokenizer), `Markdown`, `CostProgressBar`, `PrReviewPanel`, `CodeReviewPanel`, `PrCommentInbox`, `VerdictPill` | **Kept** as-is |
| `ide.css` tokens + dark aesthetic + JetBrains Mono | **Kept** (port the token bridge to `console/console.css`; aesthetic survives, metaphor changes) |

Most of the ~15K LOC of surfaces survives. Net-new is concentrated in: the shell, the Home/dashboard, the
global plan-gate indicator, onboarding, breadcrumb/legibility, and the mobile pass.

## 6. The global plan-gate indicator (the highest-leverage piece — spec)

- **Data source — zero backend change.** `getRuns({status: 'AWAITING_APPROVAL', size: 50})` already exists
  (`lib/api.ts:500`, the list endpoint takes a `status` filter). Surface the **count** as a badge and the
  **list** in a popover.
- **Placement:** top bar, always visible (desktop + mobile). When `count > 0`: an amber badge "N awaiting
  approval"; when `0`: hidden (no nag).
- **Freshness:** poll every ~20s while the app is focused, **and** invalidate immediately on the SSE
  `PLAN_READY` / `COMPLETE` events the app already consumes (`useSse`). A lightweight shared store
  (`useGateStore` or fold into `useRunStore`) holds the count so every surface reads one source.
- **Interaction:** click → popover listing the waiting runs (title + project + age) → each row routes to
  `/plan/:id` (`IdePlan`). Optional inline **Approve** / **Reject** straight from the popover via
  `approveRun(id, approved, creditsConfirmed?, executionMode?)` (`api.ts:590`) and `editRunPlan(runId,
  feedback)` (`api.ts:1875`) — reusing the gate logic `IdePlan` already implements.
- **Accessibility:** badge is a button with `aria-label="N runs awaiting approval"`; popover is a
  focus-trapped menu.

## 7. Home / dashboard (`console/ConsoleHome.tsx`) — sections + data sources

- **Start a new run** hero → `/compose`. (For repeat users, an inline one-line spec box that deep-links
  into `IdeCompose` with the text prefilled.)
- **Needs your attention:**
  - plan-gate runs — `getRuns({status:'AWAITING_APPROVAL'})` (shared with §6),
  - failed runs needing a look — `getRuns({status:'FAILED'})`,
  - PRs with inbound review comments — reuse `PrCommentInbox` data (`getPrComments`).
- **Active runs** — non-terminal runs with live status + cost (`getRuns()` + per-run `getLiveCost(runId)` +
  the `RUN_STATUS`/`RUN_COST` SSE the app already dispatches).
- **Recent runs** — terminal runs with `VerdictPill` (≥85 Shippable / 70–84 Needs review / <70 Failed) +
  `verifiedScore`.
- **Your projects** — `getProjects()` cards (last-run verdict, open-PR count).
- **Empty state** → the onboarding checklist (§8).

## 8. Onboarding / first-run (`console/OnboardingChecklist.tsx`)

A dismissible checklist driven off real state (not a separate flag): step completion derives from
`getActivationFunnel().stages[KEY].windowed` + `getProjects()`/`getRuns()` presence, so it self-checks.

1. **Connect a repo** (GitHub PAT / App) → `/integrations` (`IdeSettings` `IdeIntegrations`).
2. **Create a project** → `/projects`.
3. **Submit your first spec** → `/compose`.
4. **Approve the plan** → the gate indicator / `/plan/:id`.
5. **Get your PR** → run detail PR tab.

Auto-collapses once all five are done; a one-line "Getting started" link re-opens it.

## 9. Mobile (per-surface)

- **Shell:** top bar (brand + gate indicator + `⌘K`) + bottom tabs (Home / Runs / Projects / Account);
  left nav becomes the drawer.
- **Home:** single-column feed — Needs-attention first, then Active, then Recent, then Projects.
- **Run detail:** lead with the verdict + score + a mobile-friendly diff viewer (the `IdeKit` viewer
  already tokenizes); the file-tree collapses behind a toggle (no desktop-IDE three-pane on a phone).
- **Plan gate:** full-width approve/reject sheet.
- Verified at **375×667** + **768×1024** per the standing mobile-first rule before any phase flips green.

## 10. Migration plan (strangler-fig — incremental, each phase ships green)

No big-bang. Each phase keeps every existing surface mounted + working, behind a flag until green.

**Flag mechanism:** a backend `FeatureFlagService` flag **`ui.console.enabled`** (default **OFF**),
surfaced to the SPA at bootstrap (same path the retired `ui.ide.default` flag used before the IDE-only
switch). When ON, `App.tsx` mounts `console/ConsoleShell` for protected routes instead of
`ide/IdeWorkspace`; when OFF, today's chrome is byte-identical. This is a **build/rollout flag, not a
user-facing A/B toggle** — we deliberately do not reintroduce the `uiMode` mode split removed on
2026-05-22.

**Quick wins (frontend-only, shippable independently — even before the new shell).** Several feedback
items are cheap + high-impact and need not wait for the reframe: (a) **fix the stale "Recent runs"**
(item 10 — `IdeWorkspace` fetches once; add poll + SSE refresh); (b) **persist + always-show** the live
log / tasks / cost / tokens through navigation and to the terminal final totals (items 1, 3, 4, 5);
(c) the **spend-vs-estimate utilisation bar** (item 2). These can land on the current chrome and then
carry straight into the cockpit.

- **Phase 0 — Design** (this doc) [+ optional current-state screenshots]. No code.
- **Phase 1 — Shell + Home + gate indicator.**
  - *New:* `console/ConsoleShell.tsx`, `console/ConsoleHome.tsx`, `console/PlanGateIndicator.tsx`,
    `console/console.css` (port the `ide.css` token bridge), gate count in a shared store.
  - *Wire:* `App.tsx` flag branch; reuse `IdeCommandPalette`, notifications, account menu.
  - *Acceptance:* every protected route renders inside `ConsoleShell`; the gate indicator shows the real
    `AWAITING_APPROVAL` count and routes to `/plan/:id`; Home shows Needs-attention / Active / Recent /
    Projects; `npm run build` + `lint` + live smoke of all protected routes green.
- **Phase 2 — Home depth + legibility.** "Needs your attention" (gates/failures/PR comments), onboarding
  checklist, breadcrumbs (project → run → PR), object-model clarity on headers.
- **Phase 3 — Mobile pass.** Bottom tabs, single-column Home, mobile run-detail + plan-gate; verify both
  breakpoints.
- **Phase 4 — Retire the IDE chrome.** Flip `ui.console.enabled` default ON, delete `IdeWorkspace` + the
  IDE-specific labels, fold `useUiStore.initUiMode()` to the console, polish.

## 11. Analytics + funnel

Keep the 6 funnel events; add lightweight UI events: `GATE_INDICATOR_CLICKED`, `HOME_NEW_RUN_CLICKED`,
`ONBOARDING_STEP_DONE`. The reframe's success metric is **SPEC_SUBMITTED → PLAN_APPROVED conversion** +
time-to-first-approval (the gate indicator should move both).

## 12. Risk controls

- **Pure frontend.** Verified: the gate indicator reuses the existing `GET /api/runs?status=` filter — **no
  backend change**. The only backend touch is registering the `ui.console.enabled` flag (a one-row seed).
- Every existing surface stays reachable + working through every phase — this is a re-shell. The
  2026-05-22 backend×UI feature inventory (`docs/audit/`) is the no-regression checklist.
- Flag-gated shell swap; flip default only when `npm run build` + `lint` + a live smoke of **every**
  protected route is green (+ both mobile breakpoints).
- Keep the tokens + dark aesthetic + JetBrains Mono + ⌘K; no theming/a11y regression (WCAG-AA body text).
- Independent of the benchmark + engine-extraction work in flight (different layer entirely), and
  independent of the VS Code/CLI tracks (which continue).

## 13. What we preserve (the depth)

The deep surfaces (run-detail code viewer, the project workbench's 10 sub-tabs, billing, org + the F3
model-matrix grid, admin, PR/Code-review panels, PR-comment inbox, run-management actions), the dark
technical aesthetic, ⌘K, and every backend capability already wired. The reframe changes the *shell +
entry experience + IA*, not the product's depth.

## 14. Decisions (resolved 2026-05-27)

- **Nav pattern + identity:** slim **left sidebar** of product sections + a persistent top bar; the web app
  is renamed the **Console** (drops "IDE"). *(Founder delegated: "go with your gut.")*
- **Real user feedback:** folded in — see §3.5.
- **VS Code + CLI:** NOT paused — all three clients advance in parallel.
- **Branch:** built on the current `feat/benchmark-codegen-harness` *(founder: "execute complete plan in
  this branch")*, frontend-only, riding PR #187 — not a separate branch.
- **Spec-builder backend change (#8):** sequenced **last**, after the benchmark eval completes — it needs a
  backend restart, which would kill the running re-run. Every other item is frontend-only and safe now.

## 15. Status

Design done; **execution in progress** (2026-05-27) on `feat/benchmark-codegen-harness` — autonomous, slice
by slice (commit + push + Telegram per slice). Order: quick wins (real-time Recent-runs · persistent
telemetry · spend-vs-estimate) → run cockpit + merged Plan/Timeline → Console shell + Home + plan-gate
indicator → chat UX + project run tracking → spec-builder suggest-first (backend part last). RL-2026-05-052.
