# ExecuteSpec — Claude Code Handoff: Remaining Issues (Post-Rerun)

**Context:** This follows the rerun verification from `rerun-verification-2026-05-01.md`. The previous sprint shipped 14 of 22 actionable fixes cleanly. This doc covers what's left: 4 net-new findings, 4 partially-completed tasks, and the items that were correctly blocked on design/product but now have decisions or need to be unblocked.

**Build branch suggestion:** `audit/v2-5-12-followup`

**Same general guidelines as before** (single-purpose commits, type-check + lint pass, persona-test verification, don't widen scope). Persona test fixtures unchanged: `revalidate-{free,pro,team,ent}@test.local` + `admin@executespec.ai`.

---

## How this doc is organized

1. **Section A — Code-ready tasks.** Hand to Claude Code without further input. Six tasks.
2. **Section B — Blocked tasks awaiting human decisions.** Don't start until decision recorded.
3. **Section C — Closed / deferred.** Document trail for items that won't ship.

---

## SECTION A — CODE-READY TASKS

### Task R1 — Fix auth-gated route redirect destination (NF-1)

**Priority:** P1.

**Files (likely):** `frontend/src/components/RequireAuth.tsx`, `frontend/src/router.tsx`, or whatever wraps protected routes. Grep:
```
rg -n 'Navigate.*projects/empty' frontend/src/
rg -n 'canViewAudit|canManageIntegrations' frontend/src/
```

**Problem:** When a user lacks permission for a route, they're redirected to `/projects/empty`, which renders "Welcome, Reval. You don't have any projects yet — let's create your first one." The user clicked an Audit (or other gated) link and ended up on an onboarding screen with no explanation.

Verified in `snapshots.json`:
```
{ persona: "free", requested: "/audit", final: "/projects/empty", snippet: "Welcome, Reval..." }
{ persona: "ent",  requested: "/audit", final: "/projects/empty", snippet: "Welcome, Reval..." }
```

This is the side effect of correctly fixing Task 2 (audit-page hooks-order crash + error boundary). The crash is fixed; the redirect target is wrong.

**Fix:**

1. Find the redirect logic for permission-gated routes. It's likely a `<Navigate to="/projects/empty" />` inside a `RequireAuth` or `RequirePermission` HOC/wrapper.
2. Replace with one of these strategies (in order of preference):

   **Strategy 1 (preferred): redirect to a dedicated 403 page.**
   - Create `frontend/src/pages/ForbiddenPage.tsx`.
   - Content: page title "Not available on your plan", body "This feature is part of our [Team / Enterprise] plan. [See plans →] [Go back]".
   - The destination tier should be parametrized — pass it via route state or query param. E.g., `<Navigate to="/403?feature=audit&requires=team" replace />`.
   - The page reads the param and shows the right upsell text.
   - Include a "Go back" button using `navigate(-1)`.

   **Strategy 2 (lighter): redirect to `/billing` with a flash toast.**
   - `<Navigate to="/billing" state={{ flash: 'Audit log requires Team or Enterprise' }} replace />`
   - The billing page reads `location.state.flash` and shows a toast.
   - Less work but coarser — every gated-route redirect lands on billing, even when the user just wanted to "go back."

3. Apply the chosen strategy to ALL permission-gated routes — not just `/audit`. Grep for every `canView*`, `canManage*`, `canInvite*` permission check that does a redirect.

4. Add a Cypress / Playwright (or your preferred E2E) test:
   - Login as FREE → visit `/audit` → assert URL ends with `/403` (or `/billing`) AND assert the message text is visible.
   - Login as ENT → visit `/audit` → assert same (until Task R2 lands; see below).

**Acceptance criteria:**
- FREE user navigating to `/audit` sees a meaningful "this requires Team/Enterprise" message, NOT a "you don't have any projects yet" screen.
- The redirect message tells the user (a) what feature they tried to access, (b) what tier they need.
- A "Go back" or equivalent escape affordance is present.
- Test added that catches future regressions to the redirect destination.

**Notes:** Strategy 1 is the right call long-term; Strategy 2 is a faster ship if there's a deadline. If you go Strategy 2 today, file a follow-up ticket to migrate to Strategy 1.

---

### Task R2 — Re-scope Task 18 to V3 top-bar only

**Priority:** P2.

**Files:** `frontend/src/v3/TopBar.tsx` or wherever the V3 top-bar `Upgrade ↗` button is rendered. (V2 sidebar version was correctly fixed in the previous sprint — do NOT touch it.)

**Problem:** PRO users see two CTAs simultaneously:
- V2 sidebar footer: "Upgrade to Team" + "Top-up Runs" — explicit, correct.
- V3 top-bar: bare "Upgrade ↗" + "+ Top-up" — ambiguous, unchanged.

Verified in screenshot `pro-runs.png`. The previous sprint's Task 18 only touched the V2 sidebar.

**Fix:**

1. Find the V3 top-bar `Upgrade` button rendering logic.
2. Make the label tier-aware:
   - If `me.tier === 'FREE'`: keep "Upgrade ↗" (path is Pro-or-Team, ambiguity acceptable since pricing page is the destination).
   - If `me.tier === 'PRO'`: change to "Upgrade to Team →" (explicit destination).
   - If `me.tier === 'TEAM'`: hide the upgrade button entirely (only path forward is Enterprise sales, handled via "Schedule a demo" elsewhere).
   - If `me.tier === 'ENTERPRISE'`: hide.
3. Add a unit/RTL test that renders the top-bar for each persona and asserts the correct label (or absence).

**Acceptance criteria:**
- FREE top-bar: "Upgrade ↗" (unchanged).
- PRO top-bar: "Upgrade to Team →".
- TEAM top-bar: no upgrade button.
- ENT top-bar: no upgrade button.

**Notes:** The V2 sidebar was already fixed in the previous sprint (`pro-runs.png` shows it reading "Upgrade to Team" correctly). This task is narrowly the V3 top-bar component — don't widen scope.

---

### Task R3 — Seat-count copy consistency between TEAM and ENT (NF-2)

**Priority:** P3.

**Files:** `frontend/src/v3/pages/V3Team.tsx`, the workspace-header sub-component.

**Problem:** TEAM and ENT show different phrasings for the same thing:
- TEAM `/team`: "1 of 1 seat used"
- ENT `/team`: "1 member"

**Fix:**

1. Pick one phrasing. Recommended: "**1 of N seats used**" (more informative for billing-aware users; tells them how many seats they have versus how many are filled).
2. Apply to both TEAM and ENT views.
3. For unlimited-seat ENT plans (if any exist), use "**N members**" instead.
4. Verify on `/team` for both personas after the change.

**Acceptance criteria:**
- TEAM and ENT `/team` workspace-header use consistent phrasing.
- Phrasing matches the actual seat model for the tier.

**Notes:** Trivial copy change, no logic changes expected.

---

### Task R4 — Verify Tier.MAX backend cleanup is complete (Task 7 follow-up)

**Priority:** P2.

**Files:** Backend Spring Boot sources. Run:
```
rg -n 'Tier\.MAX|"MAX"|TIER_MAX' backend/src/
rg -n 'Tier.MAX' backend/src/main/resources/db/migration/
psql ... -c "SELECT DISTINCT tier FROM users;"  # verify no MAX tier rows in DB
```

**Problem:** The frontend MAX cleanup landed in the previous sprint (Task 7), but the backend `Tier.MAX` enum status was flagged as 🚧 BLOCKED on a backend audit. Need to confirm whether MAX is fully purged or if there are dormant references.

**Fix:**

1. Grep backend Java/Kotlin sources for `Tier.MAX`, `"MAX"`, `TIER_MAX` (and any synonyms).
2. Check the database: are there any user rows still tagged MAX? Any subscription rows? Any audit-log rows with MAX in their tier field?
3. Three possible outcomes:

   **(a) Zero references found → ✅ task is done.** Mark closed.

   **(b) Code references found but no live data → safe to remove enum value.** Submit a PR that:
   - Removes the `MAX` enum variant.
   - Removes any dead branches that reference it.
   - Adds a migration that confirms zero MAX rows (a `SELECT COUNT(*) FROM users WHERE tier = 'MAX';` assertion that fails the migration if any exist).

   **(c) Live data found → halt and escalate.** File a separate ticket: "Migrate live MAX users to TEAM (or appropriate tier) before removing enum." Do NOT remove the enum until those rows are migrated.

**Acceptance criteria:**
- `rg -n 'MAX' backend/src/` returns zero hits in user-facing code paths (Math.max, MAX_INT, etc. are fine).
- Database has zero rows with `tier = 'MAX'`.
- Either the enum is removed, or there's a follow-up ticket explaining why it can't be yet.

---

### Task R5 — Close Task 22 as not-reproducible

**Priority:** P3 (cleanup).

**Files:** None — this is a tracker-hygiene task.

**Problem:** Task 22 (Sign-in button first-click no-fire) was filed based on one observation in the original persona test. The rerun's `snapshots.json` shows all five personas (logout → free, pro, team, ent, admin) authenticated successfully. The bug doesn't reproduce.

**Fix:**

1. Mark Task 22 as **CLOSED — CANNOT REPRODUCE** in the tracker.
2. Add a comment: "Re-rerun on 2026-05-01 successfully authenticated all 5 personas. Likely a flaky one-off in the original capture. Re-open if it surfaces again."
3. Add a Playwright/Cypress regression test (defensive): cold-load `/login`, fill credentials, click Sign-in once, assert `POST /api/auth/login` fires within 200ms. This is cheap insurance.

**Acceptance criteria:**
- Ticket closed with the not-reproducible note.
- Regression test added (so if it ever does reproduce, CI catches it).

---

### Task R6 — Capture screenshots of New Run modal to verify Tasks 15 & 20

**Priority:** P3 (verification).

**Files:** None — this is a QA-capture task.

**Problem:** Tasks 15 (capitalize "Gemini") and 20 (`aria-label="Close"` on modal) couldn't be verified from the rerun screenshots because the New Run modal isn't captured. Tasks may already be done; we just don't know.

**Fix:**

1. Add the New Run modal to the persona-test capture script. Likely a step like:
   ```
   await page.goto('/runs');
   await page.click('button:has-text("New run")');
   await page.screenshot({ path: 'snapshots/new-run-modal.png' });
   ```
2. Run for FREE and PRO personas (both should have modal access).
3. Verify visually:
   - Provider chips read "Anthropic", "Gemini", "OpenAI" (capital G).
   - Close button has `aria-label="Close"` (inspect via DOM, or use `await page.locator('[aria-label="Close"]').click()` as the assertion).
4. If either is still wrong, file a tiny follow-up PR for that specific issue.

**Acceptance criteria:**
- Screenshot of the New Run modal added to the capture script's output.
- Both Tasks 15 and 20 either marked verified, or follow-up PRs filed.

---

## SECTION B — BLOCKED TASKS AWAITING HUMAN DECISIONS

These need a decision before code starts. Each entry tells you (a) what's blocking, (b) who likely owns the decision, (c) what to do once it's resolved.

### Task R7 — Button contrast WCAG fix (was Task 12)

**Blocked on:** Design lead. Decision needed: Option A (darken `--c-signal-blue` from `#5B8DEF` to `#3B6FCC`) vs Option B (use graphite text `#0B0D10` on existing blue).

**Why this matters:** Affects EVERY primary CTA in the product (Sign in, Submit, Launch run, Add seats, Upgrade). Current contrast is 3.23 (WCAG AA fail for body text). Hover state `#7AA5F5` is *worse* (2.7).

**Once decision arrives:**
- Option A: update `--c-signal-blue` token in `tokens.css`. Update hover state to be DARKER not lighter.
- Option B: keep blue, change all `.btn-primary` text rules from `color: white` to `color: var(--c-graphite-0)`.
- Either way: re-run the in-app contrast checker on the updated palette and verify ≥4.5 contrast on every primary CTA.

**Visual smoke test:** login Sign-in, billing Upgrade, runs "+ New run", billing Add seats. All should pass AA.

**Suggested nudge to design:** "10-minute decision is blocking AA compliance for every primary CTA in the product."

---

### Task R8 — `canViewAudit` for ENT ORG_ADMIN (was Task 9, NF-4)

**Blocked on:** Product. Decision needed: should ENT `ORG_ADMIN` get `canViewAudit=true` by default?

**Context:** Task 9 in the previous sprint was supposed to grant ENT ORG_ADMIN all six flagged permissions. Five landed (`canInviteMembers`, `canViewOrgMembers`, `canManageIntegrations`, `canViewAccounting`, `canViewPricing`). `canViewAudit` did not — ENT users still get redirected from `/audit` (verified in `snapshots.json`: `requested: /audit, final: /projects/empty`).

**Two interpretations:**

- **Miss:** Engineer applied Option A (auto-grant) but missed `canViewAudit`. Easy to fix — one line in `TierPermissionService.enterpriseUserPermissions`.
- **Intentional:** Audit log access requires a separate sub-role (e.g., COMPLIANCE_ADMIN) above ORG_ADMIN. If so, this is a documentation gap and a feature gap, not a permission-map bug.

**Once decision arrives:**

If "miss":
- Set `canViewAudit = true` for ENT in `TierPermissionService`.
- Update test fixtures.
- Verify ENT user can navigate to `/audit` and see the log.

If "intentional":
- Document the COMPLIANCE_ADMIN sub-role (or whatever it is).
- Update the redirect from Task R1 to show a helpful message: "Audit log requires Compliance Admin role. Contact your org admin."
- Add a separate ticket to build the sub-role assignment UI.

**Suggested nudge to product:** "Most procurement evaluators expect org admins to see audit logs. Is gating it intentional?"

---

### Task R9 — Settings "Danger zone" semantics (was Task 14)

**Blocked on:** Product. Decision needed: Option A (add real destructive ops: delete account, revoke sessions) vs Option B (rename "Danger zone" → "Account actions" since Sign-out isn't actually destructive).

**Once decision arrives:**

If A:
- Add backend endpoints for account-deletion + session-revocation (file as separate ticket if not already present).
- Add UI: each destructive op gets a typed-confirmation modal (use Task 10's Halt modal as the reference pattern — that one shipped well).
- Audit-log every destructive op.

If B:
- One-line copy change: section heading "Danger zone" → "Account actions".
- Visually de-emphasize: keep the red border on Sign-out specifically, but the section heading shouldn't imply danger.

**Suggested nudge to product:** "Sign-out alone in a Danger zone is a category-error. 1-line rename or a real account-deletion flow — your call."

---

### Task R10 — Pop orange decision (was Task 23)

**Blocked on:** Design. Decision needed: replacement for `--c-pop-orange #F97316`.

**Why:** Identical hue family to `--c-warning` and `--status-partial`. Login hero "spec." accent reads as a warning.

**Suggested options for design:**
- `--c-pop-pink #EC4899` (already in palette)
- A teal complementing the brand-grad
- A desaturated orange that breaks from the warning hue family

**Once decision arrives:**
- Update `--c-pop-orange` token.
- Verify accent appearances on `/login`, `/pricing`, marketing surfaces.
- Run the in-app contrast checker — accent text on background should still hit AA-large minimum.

**Note:** Task R11 (`--accent-blue` vs `--status-executing` collision) is a separate design decision but lives in the same review session. Bundle.

---

### Task R11 — Accent blue vs status-executing color collision (was Task 24)

**Blocked on:** Design.

**Why:** `--accent-blue #5B8DEF` and `--status-executing #5B8DEF` are identical. A "running" pill looks like a CTA.

**Suggested:** `--status-executing #4A77D6` (slightly desaturated). Bundle this decision with Task R10 — same review session.

**Once decision arrives:** update token, verify status-pill rendering across `/runs` and `/insights`.

---

### Task R12 — Border contrast (was Task 25)

**Priority:** P3, but unblocked — this is a code-ready task with a clear fix.

**Files:** `frontend/src/styles/tokens.css`.

**Problem:** `--border-default #242A33` has 1.35 contrast vs background. Borders barely visible — the UI looks frameless by accident.

**Fix:**

1. Bump `--border-default` to `#333B46` (the existing `--c-graphite-5` value) or `#3D454E` if more separation is wanted.
2. Run the in-app contrast checker — target ≥1.5:1 for non-text borders (WCAG SC 1.4.11).
3. Visual check on cards (`/billing` Plans, `/integrations` cards), dividers (`/settings` rows), input outlines.
4. If any UI element relied on near-invisible borders for a specific design effect, surface those for design review before bumping.

**Acceptance criteria:**
- `--border-default` ≥1.5:1 contrast.
- Cards and dividers visibly distinct from background.
- No design-intent regressions.

**Notes:** Don't need design approval to start — this is a token bump that a designer would have asked for anyway. Worst case design pushes back on the exact hex; bump and ship, iterate if needed.

---

### Task R13 — Login page hero rewrite (was Task 28)

**Blocked on:** Product/marketing approval of new copy.

**Why:** "Sign in to ship a spec." uses internal jargon. Body copy below already has the better line: "ExecuteSpec ships AI-written pull requests you can actually merge."

**Suggested replacement (for product to approve):**
- H1: "Ship AI-written PRs you can actually merge."
- Sub: "ExecuteSpec turns your specs into draft pull requests — across one repo or twenty."

**Once approved:**
- Replace H1 in `LoginPage.tsx`.
- Demote or remove the existing body copy (now redundant).
- Verify the right-side form header "Welcome back" still reads cleanly without duplication.

**Suggested nudge to product:** "10-minute copy change. The replacement is already on the page below the hero — just promote it."

---

### Task R14 — Recipe-shaped run templates (was Task 29)

**Blocked on:** Product picking 4–6 recipes to feature.

**Why:** Current templates ("Portfolio Website", "Expense Tracker", "REST API", "SaaS Landing Page") undersell the product. ExecuteSpec's actual capability is migrations and library compositions, not generic web-dev examples.

**Suggested set (for product to approve):**
- "Migrate Express → Fastify"
- "Add Stripe Checkout to Next.js"
- "Convert REST → tRPC"
- "Add SAML SSO to your auth flow"
- "Migrate Webpack → Vite"
- "Add feature flags via GrowthBook"

**Once approved:**
- Replace template list (likely a constant or backend endpoint).
- Each template should link to the corresponding migration recipe or library entry.
- Verify the templates render with the same card layout as today.

---

### Task R15 — Demo project seeding (was Task 30)

**Blocked on:** Product approval of demo content.

**Why:** Empty `/insights` and `/runs` make new accounts feel dead. Evaluators bounce.

**Suggested demo content (for product to approve):**
- Project named "Demo: Onboarding Walkthrough" (clearly marked as demo).
- 3 historical runs:
  - One Build run (e.g., "Add dark mode toggle") — completed, low cost.
  - One Migration run (e.g., "Mocha → Vitest, 12 files") — completed, medium cost.
  - One Awaiting Approval run — shows the plan-review gate.
- Insights pre-populated: 3 runs / 2 successful / $0.85 spend / 1 active project.
- "Demo" badge on the project so users know it's not theirs.
- Settings flow to "Delete demo project" once they've seen it.

**Implementation once approved:**
- Backend `UserOnboardingService` creates the demo project on user creation.
- Demo runs don't count against tier limits.
- Frontend renders the "Demo" badge.
- All demo content marked with `is_demo=true` for safety/audit.

---

### Task R16 — Trust page surfacing on homepage (was Task 31)

**Blocked on:** Marketing site team (likely a separate repo).

**Why:** `/trust` is procurement-grade and currently buried.

**Once unblocked:**
- Add "Trust + compliance" link to homepage nav, footer, or a dedicated CTA block.
- Coordinate with marketing on placement.

**Notes:** This may not even be in the same repo as the app. Check before starting.

---

## SECTION C — CLOSED / DEFERRED

Items deliberately not in the active backlog:

### V2/V3 chrome unification (was Task 19)

**Status:** Multi-week migration, not a single ticket. Continue as planned: 19a inventory → 19b pick canonical → 19c port pages individually → 19d delete V2 components. Engineering leadership owns sequencing.

### Live agent preview UX (from competitor assessment)

**Status:** Quarter-scale roadmap project. Needs design + research. Don't try to scope as a Claude Code task.

### Homepage redesign / re-positioning

**Status:** Product/marketing decision. Not a code task.

### Public PR showcase

**Status:** Content + customer permissions. Multi-team coordination.

### BYOK pricing-card hierarchy rewrite

**Status:** Pricing strategy decision before any code change. **Note:** the rerun showed a partial implementation of this — the PRO `/team` upsell now leads with BYOK ("Bring your team in. Bring your own keys."). Whoever did this got it. Worth recognizing in retro and considering whether to extend the BYOK-lead pattern to the `/pricing` Team card next.

---

## Suggested ship order

**Day 1:**
1. Task R5 — close not-reproducible (5 min, tracker hygiene).
2. Task R6 — capture New Run modal screenshots (verify Tasks 15 + 20 along the way).
3. Task R12 — border contrast token bump (no design approval needed, just ship).

**Day 2-3 (P1 work):**
4. Task R1 — auth-gated route redirect destination (the highest-impact remaining issue).
5. Task R2 — V3 top-bar Upgrade label.
6. Task R3 — seat-count copy consistency.

**Day 4 onwards (assuming decisions come back):**
7. Task R7 — button contrast (after design picks A or B).
8. Task R8 — ENT `canViewAudit` (after product confirms intent).
9. Task R9 — Danger zone (after product picks A or B).
10. Tasks R10, R11 — pop orange + accent blue (bundle in one design review).
11. Tasks R13, R14, R15 — login hero + templates + demo seeding (paced by product reviews).

**Ongoing:**
12. Task R4 — backend MAX cleanup verification.
13. Task R16 — homepage trust link (marketing site).

**Total scope:** 16 follow-up tasks. Six are immediately code-ready. Ten are blocked on humans.

---

## Final notes for whoever runs this sprint

- **The previous sprint's pattern of "engineer ships beyond spec" is worth encouraging.** The PRO `/team` BYOK-lead upsell and the Halt modal's audit-reason field both went beyond what was asked, and both improvements are exactly right. Don't discourage that.
- **Push hard on the design and product unblocks early in the sprint.** Five of these tasks are 10-minute decisions that gate days of code. Get them done day 1.
- **Task R1 is the highest-impact unblocked work.** It fixes a confusing UX dead-end that affects every gated route, not just `/audit`. Prioritize.
- **If you only ship three things:** R1, R7 (post-design), R8 (post-product). Those three close the most-noticed gaps.
