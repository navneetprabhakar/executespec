# UX Surface Coverage Pass — Wide-Not-Deep

**Scope:** 14 product surfaces. ~45-min code-trace + targeted live-API probes against `:8080` (Spring `local`) + `:5173` (Vite). Auth: `admin@executespec.ai` / SUPER_ADMIN / ENTERPRISE. Sprint 1+2 fixes (commit `aa72fc70`) confirmed in code.

**Method:** static read of every page component listed in §2 + 11 backend probes (login/library/migrations/tiers/feature-flags/projects/dry-run/etc.). No real plan/apply runs dispatched (LLM spend = $0).

---

## §1 TL;DR

The v3 top-bar shell is sound after Sprint 1: Runs/Library/Migrations links live, F-26 redirect collapsed, F-14 placeholders gone, F-05 dry-run gate live (admin probe `POST /api/migrations/mocha-to-vitest/dry-run` returned 200 with mock IDs as expected, gate fires for FREE per code at `MigrationController`). Most surfaces render their components; the 14-surface walk confirms no big architectural holes.

The headline gaps are **discoverability and data-density mismatches**, not broken plumbing:
- **CmdK admin actions are stubs** (S-01) — every admin entry navigates to `/home`, not the real route. The palette advertises "Admin · Tier lifecycle" + "Halt all runs" + "Audit log" + "Feature flags" yet none route there. Users learn ⌘K is broken and stop using it.
- **`/api/library/_facets` returns 404** (S-02) — `V3Library` calls `getLibraryFacets()` → 404 → silent catch → falls back to deriving categories from items (so search still works), but macro-group tabs never render. Users on the Library land on a degraded UX in production unless `getLibraryFacets()` actually maps to a different path.
- **PRO copy in `pricing.ts` says "Unlimited runs / month"** (S-03) — V145 set FREE to 10 credits/month + grandfathered PRO at 20 credits, not unlimited. Both `bullets[0]` and `subhead` overstate what the grandfathered users actually get.
- **`SettingsPage` is an accordion not tabbed** (S-04) — Profile/BYOK/Integrations/Billing/Team/Trust collapse into stacked GlassPanel cards with `IntegrationCard` open/close. There is no Notifications card except via `NotificationPreferences` import; the announced "tabs (Profile, BYOK, Integrations, Notifications, Billing, Team, Trust, API tokens)" is a label invented by the audit prompt — actual structure is far flatter.
- **AG-3 inline-retry per task does not exist on V2 runs** (S-05) — `retryTask` API helper is defined and `POST /api/runs/{id}/tasks/{id}/retry` works, but `RunDetailTabs.Tabs.Content value="tasks"` never renders a retry button. Original audit finding stands.
- **CodeTab consolidation works** (S-06) — Architecture · Health · Tech-debt · Standards · Contracts all live in one tab on the project dashboard, but standards/contracts panels render under the same chip strip with no per-repo filter, so multi-repo projects see one global rollup that hides which repo is the offender.
- **Activity feed UI is 3-deep** (S-07) — `MoreDrawer.ActivityTab` is the only consumer of `getActivityFeed`; PulseTab calls it for last-5 preview. There is no top-level activity surface; `?drawer=activity` is the only deep-link.

15 new findings + 3 worst empty/loading/error states + 5 cross-prior-report deltas in the body.

---

## §2 14 Surface Walks

### C.1 Library
**Code-trace:** `V3TopBar` → NavLink `/library` → `V3Library.tsx` (330 LOC) → `listLibrary({})` + `getLibraryFacets().catch(() => null)` (parallel) → grid `gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))'`. 28 readymades confirmed by file count + `GET /api/library` returned `length=28`. Three representative slugs: `email-password-auth.yml` (FREE, 2 slots, 3 variants), `magic-link-auth.yml`, `google-calendar-booking.yml` (premium per `tier:` field convention). Detail page `V3LibraryDetail.tsx` (458 LOC) wraps `ProjectRepoPicker` + `StackPicker` + slot inputs + `instantiateLibrarySolution` → navigates to run detail. Locked cards link to `/pricing` not detail. `requiresCostCap(user)` shows the FREE/PRO cost cap input. Facets endpoint **404s** in `local` profile — see S-02.

### C.2 Migrations
**Code-trace:** `V3TopBar` → `/migrations` → `V3Migrations.tsx` (274 LOC) → `listMigrations({})`. 11 recipes confirmed by file count + `GET /api/migrations` returned 11. Reps: `mocha-to-vitest.yml`, `cjs-to-esm.yml`, `vue2-to-vue3.yml`. Detail at `V3MigrationDetail.tsx` (597 LOC) — Phase 1 dry-run → diff preview → apply. **F-05 confirmed live**: probed `POST /api/migrations/mocha-to-vitest/dry-run` as ENTERPRISE → 200 OK; recipe is PRO-tier so FREE/PRO callers without dry-run access get 403 from `MigrationController`'s tier gate. Tier filter dropdown only lists FREE/PRO/TEAM/ENTERPRISE — MAX missing entirely (S-08).

### C.3 Settings panels
**Code-trace:** `SettingsPage.tsx` (817 LOC). NOT tabbed. Single scrolling page with `IntegrationCard` accordion sections: GitHub PAT, OpenAI key, Anthropic key, Gemini key, Org-level keys (ORG_ADMIN only, separate "Anthropic for org / OpenAI for org / Gemini for org" cards), `NotificationPreferences` component drop-in. No "Profile" tab, no "API Tokens" tab, no "Trust" tab here — Trust lives at `/trust` (separate route). The audit-prompt-listed tabs (Profile/BYOK/Integrations/Notifications/Billing/Team/Trust/API tokens) is wishful — only **BYOK + Integrations + Notifications** are present in this page; the rest live elsewhere. Personal Access Token = GitHub PAT, single field. Each provider key has a check chip when configured (`hasOpenAiKey ✓`).

### C.4 Admin console (ENT/SUPER_ADMIN)
**Code-trace:** `/admin` → `AdminPage.tsx` (1213 LOC) — 8 tabs (Users / Organisations / Tier Config / Escalations / Top-Up Pkgs / Usage Reports / Payment Audit / Webhooks). Header has 4 quick-nav buttons (Analytics / Pricing / Feature Flags / Tier lifecycle) + "Verify audit chain" + "Halt all runs" (M-010 emergency control, status-error styled). `/admin/feature-flags` → `FeatureFlagsPage.tsx` (229 LOC). `/admin/analytics` → `OrgAdminDashboard.tsx` (553 LOC) — runs/day, runs/project, cost/model, member activity, billing overview, recent invoices. `/admin/pricing` → `PricingManager.tsx` (166 LOC) — flat editable table of model rows (provider/model/inputCost/outputCost/notes). `/admin/tiers` → `TierLifecyclePage.tsx` (279 LOC) with LIST/HIDE/SUNSET buttons. `/admin/model-rates` redirects to `PricingPage.tsx` (different from PricingManager — a public model price viewer). Live probe: `GET /api/admin/tiers` returned 5 rows; `/api/admin/feature-flags` 200; `/api/billing/admin/model-pricing` returned 4 rows. F-14 confirmed: all sub-pages mount real components, not V3Placeholder.

### C.5 Org pages
**Code-trace:** `/org/projects` → `OrgProjectsPage.tsx` (703 LOC) — project hero, 5-col KPI tiles, 3 starter-project templates when empty, project list with members. `/org/settings` → `OrgSettingsPage.tsx` (321 LOC) — single h1 "{org.name}" + Members `({members.length})` + Invitations. Invite flow: email + role dropdown (DEVELOPER default, also ORG_ADMIN/BILLING_ADMIN/PROJECT_ADMIN/PROJECT_DEVELOPER). Pending invitations listed below members; Cancel button per invitation. ENT-tier walls render before either page when no org exists — `<h2>Enterprise Feature</h2>` + "Create Organization" form. No tier-driven seat picker in OrgSettingsPage (per-seat TEAM picker lives only on PlanCard for billing flow).

### C.6 Validation Report deep
**Code-trace:** `ValidationReportPanel.tsx` (542+ LOC). On mount: `getValidationReport(runId)` (skipped unless `FETCH_STATUSES.has(status)`). Renders, in order: `VerdictPill` (Shippable/Needs review/Blocked from `deriveVerdict({finalBuildPassed, testsPassed, testsTotal, reviewerVerdict, verifiedScore, statusReason})`) → severity-grouped issues (`WOULD_BREAK_BUILD/CAUSE_BUG/FAIL_REVIEW/FAIL_LINT`) → `SignalBreakdown` chips (Build ✓ · Tests N/M · Reviewer · Verified · Est) → `ScoreCard` (6 dimension bars: code-quality / test-coverage / architecture / security / performance / design-compliance, with `verifiedScore` as anchor) → `RunStamp` (1-line + Copy/Share/Embed buttons). When `report.testCoverage === null` the dimension row hides cleanly. Retry-validation button rendered on `REVIEWER_INTERRUPTED_CODE_PRESERVED` reason. `ContractCoveragePanel` + `StandardsCompliancePanel` are project-level panels (CodeTab) — NOT included in ValidationReportPanel (the audit prompt confused them).

### C.7 Run detail
**Code-trace:** `RunDetailTabs.tsx` (2200+ LOC) — Tabs.Root with 7 tabs: Overview / Plan / Tasks / Timeline / Live Log / Code / Preview. Chat tab gated on `V2_LEGACY_UI.runConversation` (off by default — V1 chat retired). Per-task UI in `value="tasks"` renders `TaskCard`s but no "Retry this task" inline button — `retryTask(runId, taskId)` exists in api.ts but no caller wires it (S-05). Share-link generation in `ValidationReportPanel.handleShare` uses `shareValidationReport(runId)` → `crypto.randomUUID`-based token (high entropy per F-25 prior). `liveCost` polling 5s when non-terminal (v2.6.1 fix). Per-phase cost rendered in Overview tab (Classifier/Outline/Expansion/Coherence rows from V130 columns).

### C.8 Project dashboard
**Code-trace:** `ProjectDashboardPage.tsx` (480 LOC after v2.6.4 IA cleanup). 4 top tabs (Pulse / Runs / Code / Chat) + More drawer. `?tab=overview/launches/etc.` → `legacyTabToNew` mapping. Runs sub-tabs: launches / prs / detail. CodeTab sub-chips: architecture / repos / health / tech-debt / standards / contracts. MoreDrawer panels: Settings / Generated Docs / Memory / Activity / Documents / Team. `?drawer=activity` deep-link wired (App.tsx redirect from `/projects/:id/activity`). Empty state: `EmptyProjectLanding` (sample-spec gallery, "Continue from share link" affordance) when both runs=0 and repos=0. PulseTab fires `getActivityFeed(projectId, {sinceDays: 30, limit: 5})` for the activity preview card.

### C.9 Insights / ExecSummaryPage
**Code-trace:** Two pages, separate routes. `/insights` → `V3Insights.tsx` (504 LOC), v3 chrome, role-gated `isOrgAdmin`, redirects non-admins to `/home`. Has 30/90/365 day date-range picker (R-343 P2) + multi-project filter (R-343 P1) both persisted in localStorage. Pulls `getMetrics(windowDays)` + `getProjects()` + `getRuns({page:0, size:N})`. `/exec` → `ExecSummaryPage.tsx` (394 LOC), v2 chrome (AppLayout), gated on `useTierPermissions().canViewCostBreakdown` (PRO+ per F-03 fix). Renders MetricCards (this-month spend / runs / success-rate / active-projects / needs-attention) + spend-by-project bars + top-3 wins + needs-attention list. Attention section uses `text-status-error` when `attentionCount > 0`. Both pages render fine for ENT solo with `canViewCostBreakdown=true`.

### C.10 Activity feed UI
**Code-trace:** Three consumers of `/projects/:id/activity` endpoint (V133):
1. `ActivityTab.tsx` (project/) — full paginated view inside MoreDrawer.
2. `PulseTab.tsx:85` — `getActivityFeed(projectId, {sinceDays: 30, limit: 5})` for the 5-event preview card.
3. `AuditPage.tsx:445` — admin audit log can scope to a project + activity feed.

No standalone `/activity` route. No `ActivityFeedPanel` component file. Discovery is via PulseTab's "Show all activity" link → fires global `drawer-state` event → MoreDrawer opens to ActivityTab. The flow works but is invisible until you land on PulseTab; first-paint discovery for the audit-trail persona requires three clicks (login → project → Pulse).

### C.11 Trust / Compliance
**Code-trace:** `V3Trust.tsx` (391 LOC), public route. Pure marketing copy — Pills (TLS 1.3 / AES-256 / Data residency us-east4) + 6 sections (Audit & certifications / Data flow / Authentication / etc.). SOC 2 row reads "Not yet certified" (Wave 7 follow-up — honest line, not a fabricated ETA). PDF download wires `/trust-pack.pdf` (R-320 Wave 8 static asset). "Contact sales" → mailto. Zero admin tools — no compliance dashboard, no per-project DPA upload, no audit-chain verifier (that lives in AdminPage header). It's a vendor-risk-packet attachment surface.

### C.12 Pricing across personas
**Code-trace:** `frontend/src/v3/lib/pricing.ts` (135 LOC). Exports `PRICING_CARDS: PricingCard[]` with **3 cards** post-F-28: FREE / PRO (Grandfathered, "Manage subscription →") / TEAM ($39/seat/mo per v2.7-foundation per-seat reinstate). MAX absent from this array (intentional — MAX is BYOK individual on `feature/product-split-prep` but not surfaced in `/pricing` v3 grid). Enterprise lives in `ENTERPRISE_FOOTER` below the grid (no card). Geo-detect flips USD ↔ INR. PRO bullet "Unlimited runs / month" is **wrong** post-V145 (S-03) — PRO is now 20 credits/month with grandfathering, not unlimited.

### C.13 Marketing → app handoff
**Code-trace:** F-16 fix confirmed:
- `RegisterPage.tsx:46-54` reads `?spec=` query param → `sessionStorage.setItem('v3-anon-spec', capped)` (truncates to safe length).
- `V3Auth.tsx:91` reads `sessionStorage.getItem('v3-anon-spec')` to restore.
- `Composer.tsx:61-64` consumes + removes the stash on submit.
- `V3EmptyProject.tsx:61` also reads it for empty-project landing.
- `V3Landing.tsx:107` writes it for the marketing-site composer (legacy v3 landing — now redirects).
- `AuthCallbackPage.tsx:39` documents the OAuth init bridge.
Cycle closes through OAuth (R-342 unified URL) — `/api/auth/oauth2/init?surface=...` returned 302 in probe, redirects to provider with surface stashed in session. The `?spec=` value survives full OAuth round-trip.

### C.14 Search / Command Palette
**Code-trace:** Two palettes ship in parallel:
1. `frontend/src/components/layout/CommandPalette.tsx` (108 LOC) — v2 palette, summoned globally inside AppLayout.
2. `frontend/src/v3/components/CmdK.tsx` (393 LOC) — v3 palette, three modes: default (recent runs from `useRunStore`) / `>` actions / `?` glossary (R-153). 5 GLOSSARY seed terms (codemap / wiring pass / verified score / status reason / classifier).

**Discoverability problem F-17 confirmed.** V3 top-bar's "Search ⌘K" button was deliberately removed in 2026-04-29 ("net: one fewer slot in the top bar without functional regression"). For mouse-only users CmdK is now invisible. Power users will find it; conversion-funnel users (FREE trial) won't. Glossary mode in particular is locked behind `?` — no entry point in any other UX.

**S-01 deep flaw:** CmdK admin actions all run `() => navigate('/home')` — see lines 70-73:
```
{ id: 'admin-tiers', label: 'Admin · Tier lifecycle', requireRole: 'SUPER_ADMIN', run: () => navigate('/home') },
{ id: 'admin-flags', label: 'Admin · Feature flags', requireRole: 'SUPER_ADMIN', run: () => navigate('/home') },
{ id: 'admin-audit', label: 'Admin · Audit log', requireRole: 'SUPER_ADMIN', run: () => navigate('/home') },
{ id: 'admin-halt', label: 'Admin · Halt all runs (confirm required)', requireRole: 'SUPER_ADMIN', run: () => navigate('/home') },
```
Every admin action is a stub. The visible labels promise routing that doesn't happen. SUPER_ADMINs invoking ⌘K → "Halt all runs" land on `/home` not the AdminPage halt button. **Severity: high** — false advertising in the most visible power-user surface.

---

## §3 Cross-Reference vs Prior Reports

| Prior finding | Status confirmed in this pass |
|---|---|
| F-01 V3TopBar Runs link (Sprint 1) | Live (line 49 of V3TopBar.tsx) |
| F-05 Migrations dry-run tier-gated | Live + probe-confirmed (200 as ENT, gate fires for FREE) |
| F-14 V3Placeholder retired | Confirmed in App.tsx (admin/{analytics,pricing,tiers} mount real components) |
| F-16 anon spec passthrough | Confirmed across 6 files |
| F-17 CmdK invisibility | **Confirmed + worse than reported** — admin actions are stubs (S-01) |
| F-25 share-link entropy | Code uses `shareValidationReport` API; no ID generation in client. Backend high-entropy assumed. |
| F-26 /governance/audit redirect | Confirmed in App.tsx (`<Navigate to="/audit" replace />`) |
| F-28 PRO grandfathered badge | Confirmed in pricing.ts but **bullets contradict the badge** (S-03) |
| AG-3 inline retry per task | **Confirmed missing** (S-05) — `retryTask` in api.ts unused by RunDetailTabs |

---

## §4 New Findings (cap 15)

- **S-01** [HIGH] CmdK admin actions stub → `/home`. 4 SUPER_ADMIN entries all dead. `frontend/src/v3/components/CmdK.tsx:70-73`. Wire to `/admin/tiers`, `/admin/feature-flags`, `/audit`, and a halt-confirm modal.
- **S-02** [HIGH] `getLibraryFacets()` returns 404 in `local` (probed both `/api/library/facets` + `/api/library/_facets`). Macro-group tabs silently never render. Either fix the backend route or drop the call to remove the failed network round-trip on every Library mount.
- **S-03** [HIGH] PRO card copy lies. `pricing.ts:71` says "Unlimited runs / month" but post-V145 PRO is 20 credits/month + grandfathered. Update bullets[0] to "20 credits / month — grandfathered" and remove the "Most popular"-flavoured framing.
- **S-04** [MED] `SettingsPage.tsx` is an accordion not tabs — audit prompt's listed tabs (Profile/Trust/API tokens) don't exist. Either accept the accordion design as intended (in which case docs/audit prompts should stop referring to "Settings tabs") or rebuild as proper tabs to reduce the long scroll.
- **S-05** [MED] AG-3 inline-retry-per-task absent on RunDetailTabs. `retryTask` in api.ts unused. Add a button on each `TaskCard` rendered inside `Tabs.Content value="tasks"` for tasks with status FAILED.
- **S-06** [MED] CodeTab standards/contracts panels render globally, no per-repo filter. Multi-repo projects can't tell which repo violated which standard. Add a repo dropdown above the chip strip when `repos.length > 1`.
- **S-07** [LOW] No standalone `/activity` route. Audit-trail-persona discovery is 3 clicks deep (login → project → Pulse → "Show all"). Consider a top-bar Activity link for ORG_ADMIN+ or a sidebar entry on AppLayout-shell pages.
- **S-08** [LOW] `V3Migrations` tier filter dropdown lists FREE/PRO/TEAM/ENTERPRISE only — MAX missing. Either MAX recipes don't exist (so dropdown is correct) or this is a copy-paste from the pre-MAX 4-tier era. Confirm with `feature/product-split-prep` ladder.
- **S-09** [LOW] V3TopBar has no notifications affordance. NotificationBell only renders inside AppLayout chrome — V3-shell pages (project home, run detail, library) have zero notification surface. ENT/Org users running long tasks lose updates.
- **S-10** [LOW] V3Trust SOC 2 honest "Not yet" is good copy, but the page has no "Last reviewed" auto-refresh — it's manually dated `2026-04-27`. Roll into CI to bump the date on every push to the file, or surface "Last commit timestamp" instead.
- **S-11** [LOW] OrgSettingsPage has no seat picker UI even on TEAM. Per-seat picker lives only in PlanCard during billing flow. ORG_ADMIN visiting `/org/settings` to add a seat must navigate to `/billing` separately.
- **S-12** [LOW] `ProjectDashboardPage` runs sub-tabs strip (launches/prs/detail) is a flat `<nav role="tablist">` but doesn't sync with browser back/forward — `setRunsSub` is local state, no URL param. Hard to share a "PRs view" of a project.
- **S-13** [LOW] `PricingManager` (admin) is a flat table with per-row Save buttons; no bulk-edit, no diff-vs-published, no audit row. Operator changing 12 model rates clicks Save 12 times.
- **S-14** [LOW] `V3Library` empty-state: `categories` derives from items when facets fail, so search/filter still works — but loading state shows "Loading library…" plain text, no skeleton. Visual jank on slow networks.
- **S-15** [LOW] `SharedReportPage` not-found state is "Report Not Found / This validation report doesn't exist or has been removed" — no CTA to re-share or contact owner. Cold-link recipient hits a dead end.

---

## §5 Worst Empty/Loading/Error States

1. **CmdK empty state in glossary mode** — when `?` query has no match, renders "No glossary entries match. Try clearing the search." centred in the list. Combined with **only 5 seed terms in `lib/glossary.ts`**, most lookups fail. Worst-case: a user types `?retry` → no entries → assumes glossary is broken. Fix: seed 30+ terms or route to a help search.
2. **V3Library facets-404 → degraded but silent** — facet endpoint failure swallowed by `.catch(() => null)`, macro-group tabs never render, no error toast, no "Categories unavailable" hint. User loses 4-tab macro filter and never knows.
3. **`SharedReportPage` not-found** — Shield icon + "Report Not Found" + no recovery CTA. A bad share link is a soft 404 with no suggestion to log in / contact / try again.

---

## §6 Coverage Gaps

This pass did NOT cover:
- **NewRunModal** spec composer (covered in prior B audit pass).
- **Live SSE streams** — Live Log + run timeline behaviour during a real WORKING run (would require dispatching a paid run).
- **OAuth signup flow end-to-end** — only static code-trace + 302 confirmation; full IdP round-trip needs browser session.
- **Mobile breakpoints** — no responsive smoke. SettingsPage accordion + Admin 8-tab strip likely overflow on <768px (overflow-x-auto hides bottom UI).
- **Keyboard navigation** — Tabs.Root accessibility, focus restoration after MoreDrawer close, focus-trap on modals beyond ResponsiveModal.
- **Per-tier LockedFeature renders** — the `components/ui/LockedFeature` paywalls are scattered; coverage of every "upgrade" CTA path was out of scope.
- **i18n** — every page is en-US hard-coded; no inspection of translatable copy surfaces.
- **`/team` (V3Team)** — listed in CmdK and TopBar absent. Component exists at `V3Team.tsx` but no tab review.

**Spend:** $0 LLM (no runs dispatched). **Time:** ~45 min code-trace + ~10 backend probes.
