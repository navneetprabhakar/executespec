# ExecuteSpec — Greenfield Redesign · Master Action List

**Source of truth:** `/tmp/executespec-greenfield-ux-redesign.md` (39pp PDF, 24 screens, 17 surfaces deleted, 3-rung tier ladder).
**Branch:** `feature/conversion-redesign` (off `dev` @ `f1d04532`).
**Cohabitation:** Option 1 — `/v3/*` route prefix. New screens at `/v3/home`, `/v3/p/{slug}`, etc. Old screens stay at current paths until cutover. Per-persona dogfooding via deep links; no flag plumbing tax.
**Versioning suffix on every commit:** `feat(v3):` / `fix(v3):` / `chore(v3):` / `docs(v3):` + the `R-###` ID.

---

## Decisions needed from user (stop list)

The following four product calls are **non-trivial** and need a real human decision before they get baked into copy / pricing logic / IA. These will be batched in the first dispatch update so the user can answer in one go.

| Tag | Question | Default I'll use if no answer |
|---|---|---|
| **D-001 textarea-copy** | First signed-in textarea hero/headline copy. Doc says *"What do you want to build?"*. User may want a different framing (more conversion-y, or more "for engineers"). | *"What do you want to build?"* with sub-hint *"Describe it in plain English — we'll ship a draft PR you can merge."* |
| **D-002 lifetime-credit-math** | Doc says **5 lifetime runs + 5 per friend, cap 25**. Confirm vs. (a) `5 + 3·invite·cap=15` or (b) `5 + 5·invite·cap=30`. Affects `tier_config.FREE.monthly_run_limit`, V125 referral counter, and the upgrade nudge thresholds. | **5 lifetime + 5 per invite, cap 25** (matches doc). DB needs new field `app_user.lifetime_run_credit_balance` (additive). |
| **D-003 referral-cap-mechanic** | When a Free user invites a friend who signs up but never runs anything, does the +5 credit grant fire? Anti-fraud-wise the safe answer is *no — fires on referee's first run*. But that delays the inviter's reward. | **Fires on referee's first run.** Inviter sees a "pending" credit row until then. |
| **D-004 build-vs-vibe-split** | Doc deletes the Vibe View entirely. User said dropping Vibe is on the table but not confirmed. Vibe code lives in `frontend/src/components/runs/Vibe*.tsx` and `useViewMode.ts`. | **Delete Vibe path entirely** in v3 (no toggle). v2 code stays untouched until cutover, so no live regression. |

I'll proceed with the defaults above so execution doesn't block. User can override at any point and I'll rip-and-redo.

---

## Master action list (R-100 series — greenfield-redesign-specific)

R-IDs in the **100s** are greenfield-implementation items (this plan). The 0-99 series remain reserved for the prior remediation plan (still useful as a separate workstream if user comes back to it).

> Format per row:
> **R-NNN · Title** — Severity · Effort (T-shirt) · Wave · Maps to §X.Y of the redesign doc · Persona unlock
> Description, AC, Dependencies, Current state, Decision-needed flag.

### Section A — v3 scaffolding (the plumbing for everything else)

#### R-101 · `/v3/*` route shell + dark-only theme lock — High · S · Wave 1 · §2.1, §3
**Description:** Add a `<V3RouterShell>` that wraps every `/v3/*` route. Mounts a top-bar (logo + project switcher placeholder + ⌘K placeholder + avatar placeholder) and renders children in a centered max-width-960 container. No sidebar. Lock dark theme by force-setting `data-theme="dark"` on the v3 shell's root node so v3 screens always render against the off-black palette while v2 keeps its theme toggle.
**AC:** Visiting `/v3/home` (placeholder body) renders only the top-bar + body. v2 sidebar is *not* in the DOM. No console errors. Theme toggle on /settings (v2) doesn't affect /v3 visuals.
**Deps:** —
**Current:** No `/v3` routes exist.

#### R-102 · `<TopBar v3>` component — High · S · Wave 1 · §2.1
**Description:** New `frontend/src/v3/components/TopBar.tsx`. Layout: logo+wordmark left, `<ProjectSwitcher>` placeholder middle, `<CmdKButton>` icon + `<AvatarMenu>` placeholder right. 56px tall, sticky.
**AC:** Renders on every `/v3/*` page. ARIA-labelled buttons. Tab-navigable.
**Deps:** R-101.

#### R-103 · `<CmdK>` palette skeleton — High · M · Wave 1 · §2.5
**Description:** Modal triggered by Cmd-K / Ctrl-K. Three modes: default fuzzy-search (recent runs, projects), `>` actions, `?` glossary. Implement only the shell + the action mode (with `> Open settings`, `> Sign out`, `> Switch theme`). Recent-runs and glossary land in Wave 2.
**AC:** Cmd-K opens. Type `>` and "Open settings" appears. Press Enter → navigates to `/v3/settings`. Esc closes. Focus-trap.
**Deps:** R-101.

#### R-104 · `<AvatarMenu>` dropdown — High · S · Wave 1 · §2.4
**Description:** Top-right avatar dropdown. Items: Account / Billing & plan / Team (Pro+ only) / Integrations / Insights (ORG_ADMIN+ only) / Help / Sign out. Role-gated via existing `useAuthStore` selectors.
**AC:** Visible on every v3 page. Each item routes to the right `/v3/*` URL (placeholders OK if target page isn't built yet — Wave 1 builds the destinations; this lands later in Wave 1 once a couple destinations exist).
**Deps:** R-101, R-102.

#### R-105 · v3 terminology source-of-truth — High · XS · Wave 1 · §2.6
**Description:** New `frontend/src/v3/lib/terminology.ts` exports `RUN_*`, `PROJECT_*`, `PROVIDER_DISPLAY`, `STATUS_LABELS`, etc. Every v3 screen imports from here. Lint rule (eslint `no-restricted-syntax`) targets v3 dir only — fails on bare `'Mission'` / `'Launch'` / `'Execution'` strings.
**AC:** v3 screens never display "Mission" / "Launch" / "Execution" / `ANTHROPIC` (all-caps). All such text comes from terminology.ts. ESLint passes.
**Deps:** —

#### R-106 · v3 `<Pill>` + `<Term>` primitives — High · S · Wave 1 · §3.7
**Description:** Two new shared primitives in `frontend/src/v3/components/`. `<Pill tone="ok|warn|err|neutral|info">label</Pill>` replaces every status chip variant in v3. `<Term name="run">Run</Term>` wraps glossary terms with the `?` tooltip.
**AC:** Both render correctly. Glossary tooltip data lives in `frontend/src/v3/lib/glossary.ts`. Storybook entries optional.
**Deps:** R-101.

---

### Section B — Marketing landing + auth (anonymous → signed-in)

#### R-110 · `/v3/landing` — chat-first landing for anonymous visitors — Critical · M · Wave 1 · §4.1
**Description:** Public route. Single textarea above the fold + 3 example chips. CTA "Submit — free, no signup". Below fold: 30s demo loop placeholder, 3 testimonials placeholder, pricing-card preview, footer. Marketing-quality copy verbatim from doc §4.1.
**AC:** Visiting `/v3/landing` (logged-out) renders the textarea hero. Click a chip → textarea fills. Submit (anonymous) calls a new `POST /api/v3/anonymous-runs` endpoint (see R-111).
**Deps:** R-101, R-105.

#### R-111 · Anonymous run endpoint (backend) — Critical · M · Wave 1 · §4.1
**Description:** New `AnonymousRunController` exposing `POST /api/v3/anonymous-runs` + `GET /api/v3/anonymous-runs/{sessionToken}`. Creates a guest project tied to a signed session cookie, dispatches a one-shot Anthropic LIGHTWEIGHT run with hard cost cap $0.20. Rate-limit 3/IP/hour.
**AC:** Anonymous POST returns `{sessionToken, runShort, sseUrl}`. SSE streams progress. Rate-limit returns 429. Cost cap enforced (terminate over-budget run).
**Deps:** R-110.
**Decision-needed:** D-002 (does an anonymous trial count toward lifetime cap when the session converts to signup? — default: yes, signup binding adds it to balance).

#### R-112 · `/v3/auth` — OAuth-first signup screen — Critical · S · Wave 1 · §4.2
**Description:** Single-card "Save this run" framing. GitHub + Google OAuth buttons + magic-link fallback. After auth, anonymous run binds to account, project auto-named.
**AC:** OAuth roundtrips work. Magic-link sends + verifies. Anonymous run survives signup (session token → user_id binding).
**Deps:** R-105, R-111.

---

### Section C — Project home (the dominant screen)

#### R-120 · `/v3/p/{slug}` — project conversation thread — Critical · L · Wave 1 · §4.4
**Description:** The home screen. Slack-shaped: pulse-summary one-liner on top, message thread in body, composer pinned to bottom. Each ASSISTANT message is either a Plan card, Result card, or Status update. Existing project-API endpoints reused (`/api/projects/{id}/launches`, `/dashboard`, `/prs`, `/post-merge-bar`).
**AC:** Empty project: textarea visible immediately. Project with runs: thread populated, last run on bottom. Composer submit creates a new run that appears as a message bubble.
**Deps:** R-101, R-102, R-105, R-106.
**Decision-needed:** D-001 (textarea placeholder copy).

#### R-121 · `<PlanCard>` inline in conversation — High · M · Wave 1 · §4.4
**Description:** When run hits AWAITING_APPROVAL, render an inline plan card with file list, cost estimate, "Approve & ship" + "Adjust plan" + "Cancel" buttons. Replaces the v2 modal blocking gate.
**AC:** Click "Approve & ship" → run transitions to WORKING; card morphs to "Building…" with per-task progress.
**Deps:** R-120.

#### R-122 · `<ResultCard>` inline in conversation — High · M · Wave 1 · §4.4
**Description:** When a run completes, render a compact result card linking to the PR-shaped detail page. Includes status, evidence chips, "Open PR / Open run / Re-run / Share".
**AC:** Click "Open run" → navigates to `/v3/p/{slug}/r/{runShort}`. Status pill matches the new vocabulary (Ready / Needs review / Failed).
**Deps:** R-120, R-130 (run detail page must exist for the link).

#### R-123 · `<Composer>` v3 — single textarea + advanced — Critical · M · Wave 1 · §4.4
**Description:** Pinned-bottom composer. Single textarea + "[Attach repo: ▾]" + "[Quick run]" chip + Submit. Everything else (provider, branch, batch flag, TDD, JIRA, Figma) is in `<details>▾ Advanced</details>`. Persists per-user expand state.
**AC:** First-time user submits in 1 textarea + 1 click. Power user opens Advanced once and it stays open.
**Deps:** R-120.

#### R-124 · `<ProjectSwitcher>` popover — High · S · Wave 1 · §4.7
**Description:** Top-bar dropdown. Search projects + "+ New project". Inline new-project form (name + repo selector).
**AC:** Click switcher → project list. Click project → `/v3/p/{slug}` loads. "+ New project" → inline form → submit → drops into new project's empty state.
**Deps:** R-102, R-120.

---

### Section D — Run detail (the PR-shaped result)

#### R-130 · `/v3/p/{slug}/r/{runShort}` — PR-shaped run detail — Critical · L · Wave 1 · §4.5
**Description:** Three-zone layout: status header (Ready/Needs review/Failed + 4 evidence chips), file tree on left, Monaco diff in middle, inline annotations on right. Reuses `FileTree.tsx`, `FileViewer.tsx` from v2 where possible. Verbatim copy from doc §4.5.
**AC:** COMPLETE run shows "Ready to merge" + diff. Clicking a file loads its diff. PARTIAL run shows "Needs review" + suggested-fix CTA (from R-131).
**Deps:** R-101, R-105, R-106.

#### R-131 · `<RunFailureSummary>` — failure UI with evidence-based CTA — High · M · Wave 1 · §4.6
**Description:** When `verdict !== CLEAN` or `finalBuildPassed === false`, render the structured failure summary. Inline "Apply suggested fix" (calls existing `taskRetry` endpoint) + "Re-prompt with this context" (opens composer with error tail pre-filled).
**AC:** PARTIAL run shows failure summary above diff. "Apply suggested fix" dispatches a patch run. No "Verified N/100" headline; no "Shippable" chip.
**Deps:** R-130.

#### R-132 · Honest-Verdict computation backend patch — Critical · S · Wave 1 · §4.5
**Description:** **Two-line fix** in `report/ValidationReportService.computeVerifiedScore` so the "tests pass" weight returns 0 (not full credit) when `testsTotal === 0`. Stops the v2 lie at the API source. Both v2 and v3 immediately surface honest scores.
**AC:** Run `e48399bf` (testsTotal=null) returns `verifiedScore` reflecting no test credit. Existing v2 `ScoreCard` already handles this via `verifiedScore=null` paths.
**Deps:** —

#### R-125 · Repo-attach popover (OAuth scope + repo-picker + auto-name) — High · M · Wave 2 · §4.3, §4.4, §4.7
**Description:** The connective tissue between R-124 (project switcher surface) and the OAuth-scope-grant popover §4.3 promised. Today the "+ Connect a GitHub repo" item in R-124's switcher routes to v2's `OrgProjectsPage` as a placeholder — that's the cohabitation gap this R-### closes. Ships the **happy path only**:
  1. Click "+ Connect a GitHub repo" → in-popover OAuth scope check (reuse v2's GitHub PAT path or trigger the GitHub-App install flow if no PAT).
  2. Repo-picker list (consume existing `listGitHubRepos` from v2 `lib/api.ts`).
  3. On select → auto-name new project after the repo (greenfield §4.3 implication) and `linkRepoToProject` via existing v2 endpoint.
  4. Drop the user into `/v3/p/{newProjectId}` with the chat thread empty-state.

Also wires the `[+ Attach a GitHub repo]` chip from §4.1 (anonymous landing) to the same popover when a session token exists, so an anonymous trial can be re-bound to a real repo on signup.

**Decisions parked — revisit before shipping for production traffic:**
* **Private repo with no GitHub App access** — fallback to PAT prompt in the popover, or push the user to `/v3/integrations` for org-wide install? Default: fallback to PAT prompt; TODO log entry for a follow-up that decides between the two.
* **Mono-repo subdirectory selection** — design currently doesn't ask. Default: assume root; expose a `▾ Advanced` later.
* **Repo-too-big quota / first-fetch limit** — no design decision yet. Default: no client-side gate; backend's existing clone-size limits apply.
* **Per-run repo override beyond the composer's `[Attach repo: ▾]` chip** — design has the chip but no add-from-composer-flow. Default: chip stays project-level; "+ Connect a repo" is a separate explicit action.

Each of these gets a TODO comment in the code referencing this section so they can't slip silently.

**AC:**
* Authed user opens project switcher → "+ Connect a GitHub repo" → popover shows their GitHub repos within 2s.
* Pick a repo → new project created auto-named after the repo (e.g. `hostel-be-v3`) → drop into `/v3/p/{newId}`.
* "+ Attach a GitHub repo" on `/v3/landing` opens the same popover when authed; same auto-name behaviour.
* No navigation away from the current page during the flow.
* TODO comments for the 4 deferred design questions present in `RepoAttachPopover.tsx`.

**Deps:** R-124 (switcher surface) · R-104 (auth context) · existing v2 `listGitHubRepos` + `linkRepoToProject` API helpers.

---

### Section E — Settings, billing, team (avatar dropdown destinations)

#### R-140 · `/v3/settings` — single-page card stack — High · M · Wave 1 · §4.8
**Description:** Account, AI provider, Repos (GitHub PAT + GitHub App), Notifications, Danger zone. No sub-routes; everything inline-edits.
**AC:** Display name editable. Theme dropdown (dark/light/system). PAT manageable. All cards render even when fields are empty.
**Deps:** R-101.

#### R-141 · `/v3/billing` — 3-tier card grid + usage + receipts — Critical · L · Wave 2 · §4.9, §7
**Description:** Three sections: current plan card, usage this month, full pricing grid (Free/Pro/Team with Enterprise as footer line). Geo-gates GST below for India-only. Reads from new `/api/v3/entitlements` (R-142).
**AC:** ENTERPRISE org sees "You're on Enterprise · Unlimited" — no soft-budget banner. Free org sees "3 of 5 lifetime runs used · invite friends for more →". Pricing grid current-tier badged.
**Deps:** R-142.

#### R-142 · `GET /api/v3/entitlements` — single-source tier API — Critical · M · Wave 1 · §4.9
**Description:** New endpoint collapsing org tier + plan + usage + caps into one read. Backed by new `EntitlementResolver` service. Used by v3 billing, v3 sidebar nudges, v3 pricing CTA gating, v3 run-cap enforcement. v2 still reads via legacy paths (no breakage).
**AC:** Returns `{tierKey, displayName, runLimitMonthly, projectsLimit, storageLimitMb, softCostBudgetUsd, hardCostCapUsd, currentPeriodRunCount, currentPeriodCostUsd, billingCycleAnchor, isUnlimited, lifetimeRunCreditBalance}`. ENTERPRISE returns `isUnlimited:true`. Free returns `lifetimeRunCreditBalance:N`.
**Deps:** —
**Decision-needed:** D-002 lifetime credit math.

#### R-143 · `/v3/team` — members + seats + invite — High · M · Wave 2 · §4.10
**Description:** Member list, empty seat rows visible, invite-by-email, role dropdown, SSO panel for Enterprise.
**AC:** Pro user sees "Upgrade to Team to invite teammates" CTA. Team admin sees member table + add-seat inline + Stripe checkout for seat changes. Enterprise admin sees SSO panel above member table.
**Deps:** R-142.

#### R-144 · `/v3/integrations` — card grid — Medium · S · Wave 2 · §4.12
**Description:** Cards for GitHub App, GitHub PAT, Slack, Bitbucket, Jira, Confluence, ServiceNow (coming soon), PagerDuty (coming soon). Reuses existing v2 OAuth flows.
**AC:** Visiting page renders all 8 cards with state chips ("✓ Connected" / "Connect →" / "Coming soon"). Each click goes through the existing v2 OAuth handler.
**Deps:** R-101.

---

### Section F — Pricing, trust, help (public/secondary)

#### R-150 · `/v3/pricing` — public 3-tier grid + FAQ + cost calc — High · M · Wave 2 · §4.14
**Description:** Public route, 3-card pricing grid identical shape to billing's plans block, currency + period toggles, FAQ accordion, "How is cost calculated?" expander that reveals the per-1M-token model rate matrix (folds today's `/pricing` page into a drawer).
**AC:** Geo-detects INR for India. Defaults Pro card "Most popular" badged. Drawer reveals model rates from `/api/models/available`.
**Deps:** —

#### R-151 · `/v3/trust` — procurement-grade Trust page — High · M · Wave 2 · §4.13
**Description:** Public, footer-linked. Bullet-dense list of SOC2 status, data residency, encryption, sub-processors, auth, audit, data export, deletion SLA, IP ownership, code retention, DPA + sub-processor PDF download, security@ contact.
**AC:** Page renders. `/trust` reachable without auth. Trust Pack PDF downloadable (initial PDF can be a simple Pandoc render of the same content; full design pass later).
**Deps:** —

#### R-152 · `/v3/insights` — collapsed analytics screen (ORG_ADMIN+) — Medium · M · Wave 2 · §4.11
**Description:** Single screen replacing today's `/dashboard` + `/exec` + `/admin/analytics`. Sections: weekly summary tiles, spend by project, top wins, needs attention, activity feed, audit log expander.
**AC:** ORG_ADMIN sees the page; non-admin gets a 404. SUPER_ADMIN sees an additional "Platform analytics" tab.
**Deps:** R-142.

#### R-153 · `<HelpModal>` — Cmd-K `?` mode — Medium · S · Wave 2 · §4.15
**Description:** Modal with three tabs (Glossary, Shortcuts, Docs). Glossary content from `frontend/src/v3/lib/glossary.ts`.
**AC:** Cmd-K + `?` opens modal. Glossary tab lists every term from the lib. Shortcut tab lists the bound shortcuts.
**Deps:** R-103, R-106.

---

### Section G — Conversion plumbing (instrumentation + nudges)

#### R-160 · PostHog v3 event registration — High · S · Wave 1 · §11
**Description:** Add v3 event names to `EventName.java` and `frontend/src/lib/analytics.ts`: `landing_textarea_submit`, `first_run_started`, `first_run_ready`, `repeat_run_submitted`, `tier_upgraded`, `team_seat_added`, `enterprise_inquiry_submitted`, `trust_page_pdf_downloaded`. Funnel rule: every conversion event in the doc fires.
**AC:** New events visible in PostHog. Existing v2 events keep firing (no regression).
**Deps:** —

#### R-161 · Lifetime-credit balance schema (V143) — Critical · M · Wave 1 · §7.5
**Description:** New migration `V143__app_user_lifetime_run_credit_balance.sql`. Adds `app_user.lifetime_run_credit_balance INT NOT NULL DEFAULT 5`. Backfill: every existing user gets 5. New `LifetimeCreditService` decrements on run start (Free tier only), increments by 5 on referral fulfilment.
**AC:** New Free user has 5 credits. Run consumed → 4. Anti-double-decrement via row-lock. ENTERPRISE/PRO/TEAM unaffected (service no-ops for non-Free).
**Deps:** R-142.
**Decision-needed:** D-002.

#### R-162 · Referral mechanism (basic) — Medium · M · Wave 2 · §7.5
**Description:** Backend: `POST /api/v3/referrals/invite {email}`, idempotent. Sends a magic-link signup email. On referee's `first_run_ready`, +5 credits to inviter (capped via `lifetime_run_credit_balance` ≤25). Frontend: invite-by-email field on the run-cap-hit modal + on `/v3/p/{slug}` empty state.
**AC:** Pro user invites a friend who signs up + ships a run → inviter's balance goes 5→10. Cap enforced.
**Deps:** R-161.
**Decision-needed:** D-003.

#### R-163 · Free-cap upgrade nudge modal — High · S · Wave 2 · §7.6
**Description:** When a Free user's lifetime balance hits 1, show a one-time toast on result page. When balance hits 0, show a blocking modal on next run submit: "Get unlimited for $20/mo · or invite a friend for +5".
**AC:** Toast at #4 (balance==1). Blocking modal at #5+ submit. Modal links to `/v3/billing` and to invite UI.
**Deps:** R-141, R-162.

---

### Section H — Mobile / PWA

#### R-170 · v3 mobile responsive primitives — High · M · Wave 2 · §6
**Description:** Audit every v3 component for `<lg` breakpoint behavior. Top-bar collapses to icon-only. Run detail switches to single-column scroll. Composer becomes full-screen on focus. New `useV3Mobile()` hook gates layout shifts.
**AC:** All v3 screens usable on 390px viewport. No truncation. Composer expands to full screen on focus, collapses on submit/blur.
**Deps:** R-101 through R-130.

#### R-171 · PWA manifest + service worker — Medium · S · Wave 3 · §6.3
**Description:** Add `frontend/public/manifest.webmanifest` + a tiny service worker for offline shell. iOS "Add to Home Screen" gives an app-quality experience.
**AC:** PWA installable on iOS Safari. Manifest icons render. SW caches the v3 shell + last-visited project.
**Deps:** R-170.

---

### Section I — Migration / cutover

#### R-180 · v2 → v3 redirect map (preview only) — Medium · S · Wave 3 · §10.2
**Description:** Add a 302 redirect for users with `useUiStore.v3OptIn === true` from `/`, `/runs/{id}`, `/dashboard`, `/exec`, `/projects`, `/welcome`, `/runs/new` to their v3 equivalents. Opt-in only; no forced redirects until cutover decision.
**AC:** Toggle in v2 settings "Try the new ExecuteSpec (beta)". Toggling on → next page load goes to v3.
**Deps:** every Wave 1 R-1xx (destinations exist).

#### R-181 · Changelog page + "What changed" banner — Low · S · Wave 3 · §10.2
**Description:** New `/v3/changelog` listing every IA change with a screenshot. Banner on first visit ("New here? 2-minute tour →") deep-links to a Loom (out of scope to record).
**AC:** Page exists. Banner dismissible. Loom URL is a placeholder until recording shipped.
**Deps:** —

---

### Section J — Throw-aways (deletion / hide)

> Per execution protocol: **don't delete v2 screens during this branch**. These items track what to delete *after cutover*. Captured here for completeness.

#### R-190 · Vibe View deletion (post-cutover) — Low · S · Wave-Backlog · §9
**Description:** After cutover, delete `useViewMode`, `Vibe*` components, `viewMode` enum branches.
**AC:** `grep -r 'Vibe\\|vibe' frontend/src/v3` returns nothing in user code. `frontend/src` (v2) only has the file we moved/deleted.
**Deps:** Cutover complete.
**Decision-needed:** D-004 (confirm Vibe is dead).

#### R-191 · Sidebar / breadcrumb / Mission vocabulary purge (post-cutover) — Medium · M · Wave-Backlog · §9
**Description:** After cutover, delete `Sidebar.tsx`, the dynamic-breadcrumb logic in `Header.tsx`, and run the terminology lint over `frontend/src/**/*.tsx` to purge "Mission/Launch/Execution" from v2 paths too.
**AC:** No user-facing surface uses old vocabulary.
**Deps:** Cutover complete.

---

## Wave 1 / 2 / 3 sequencing

### Wave 1 — Build the v3 spine end-to-end (target: 1-2 weeks)

Goal: a logged-in user can submit a run from the v3 home, watch it complete, and read the PR-shaped result.

```
Plumbing first:
  R-101 v3 router shell
  R-105 terminology source-of-truth
  R-102 TopBar
  R-106 Pill + Term primitives
  R-103 CmdK skeleton (action mode)
  R-104 AvatarMenu

Honest scoring early so v2 stops lying immediately:
  R-132 Honest-Verdict backend patch

Project + run loop:
  R-124 ProjectSwitcher
  R-120 /v3/p/{slug} home
  R-123 Composer
  R-121 PlanCard
  R-122 ResultCard
  R-130 Run detail
  R-131 RunFailureSummary

Auth + entitlements:
  R-142 /api/v3/entitlements
  R-161 Lifetime credit schema (V143)
  R-140 /v3/settings
  R-160 PostHog v3 events

Anonymous trial path:
  R-110 /v3/landing
  R-111 Anonymous run endpoint
  R-112 /v3/auth
```

**Wave 1 count:** **20 R-items.**

### Wave 2 — Conversion mechanics + procurement-readiness (target: 1-2 weeks after Wave 1)

```
Pricing/billing:
  R-141 /v3/billing
  R-150 /v3/pricing
  R-163 Free-cap upgrade nudge modal

Team + collaboration:
  R-143 /v3/team
  R-162 Referral mechanism

Procurement:
  R-151 /v3/trust
  R-152 /v3/insights
  R-144 /v3/integrations

Polish:
  R-153 HelpModal
  R-170 Mobile responsive primitives
```

**Wave 2 count:** 11 R-items (added R-125 repo-attach popover after the user's "how does someone add a repo?" review surfaced the gap between R-124's switcher action and a real OAuth-scope-grant flow).

### Wave 3 — Migration + polish (target: 1 week after Wave 2)

```
  R-171 PWA manifest + SW
  R-180 v3 opt-in redirect map
  R-181 Changelog page + tour banner
```

**Wave 3 count:** 3 R-items.

### Backlog (post-cutover)

```
  R-190 Vibe deletion
  R-191 Sidebar / Mission vocabulary purge
```

**Backlog count:** 2 R-items.

---

## Total

* **Wave 1:** 20 items
* **Wave 2:** 10 items
* **Wave 3:** 3 items
* **Backlog:** 2 items
* **Decisions needed:** 4 (D-001 through D-004)

Execution starts with Wave 1, R-101.

---

*Branch: `feature/conversion-redesign` · Cohabitation: `/v3/*` route prefix · Defaults applied for D-001/2/3/4 unless user overrides.*
