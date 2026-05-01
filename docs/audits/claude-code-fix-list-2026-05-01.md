# ExecuteSpec — Claude Code Handoff: Fix List

**Source audits:** `persona-test-2026-05-01.md` + screenshot review + competitor assessment.
**Target stack:** React + Vite + TypeScript frontend (port 5173), Spring Boot backend (port 8080, `local` profile).
**Build branch:** `audit/v2-5-11-ux-product`.

This doc is a backlog. Each task is self-contained — you can hand the whole file to Claude Code, or copy individual tasks into separate sessions. Tasks marked **🚧 BLOCKED** need a human decision before code starts; everything else can ship with normal review.

---

## 0. General guidelines for every task

Before claiming a task complete:

1. **Run the existing test suite** for the touched area. If no tests exist for the surface being changed, add at least one regression test.
2. **Type-check passes** (`tsc --noEmit` or whatever the frontend script is — likely `pnpm typecheck` or `npm run typecheck`).
3. **Lint passes** (`pnpm lint` / `npm run lint`).
4. **Visual smoke test** at `localhost:5173` for any UI change. If the task references a specific persona, log in as that persona and verify.
5. **Single-purpose commits.** One task = one commit (or one PR with cohesive commits). Don't bundle unrelated fixes.
6. **Commit message format:** `fix(area): summary` or `feat(area): summary`. Include the task ID from this doc, e.g. `fix(billing): handle unlimited tier in remaining-runs math (Task 1)`.
7. **Don't fix more than the task asks for.** If you spot a bug while working on Task N, file a note for it and keep Task N scoped. Drift is the enemy of reviewable diffs.
8. **Search before assuming.** Many tasks reference file paths inferred from the audit (e.g. `BillingPage.tsx`). Verify with `grep` / `rg` before editing — the actual path may differ.

**Persona test fixtures (for verification):**

- `revalidate-free@test.local` — FREE
- `revalidate-pro@test.local` — PRO
- `revalidate-team@test.local` — TEAM
- `revalidate-ent@test.local` — ENTERPRISE
- `admin@executespec.ai` — SUPER_ADMIN

**Single source of truth for tier limits:** `tier_config.monthly_run_limit` (backend). Anywhere you see a frontend reading `usage[].max` or `billing_plan.run_limit` for limit display, it's wrong. See Task 3.

---

## P0 — Critical path, ship first

### Task 1 — Fix `Integer.MAX_VALUE` leak on `/billing` hero

**Files (likely):** `frontend/src/pages/BillingPage.tsx`, possibly a `formatRemaining()` helper.

**Problem:** PRO/TEAM/ENT users see `2147483647 of 25 lifetime runs left` on the billing page hero. Root cause: the hero computes `remaining = max - used` where `max = -1` (the backend's "unlimited" sentinel) gets coerced into `Integer.MAX_VALUE` somewhere up the stack — likely a `Math.max(0, max - used)` or an overflow when `max - used` is rendered as an unsigned int.

**Fix:**
1. Find the component rendering "X of Y lifetime runs left" — it's the hero of the `/billing` page.
2. Before computing `remaining`, branch on `max`:
   - If `max === -1` (or whatever the unlimited sentinel is), render "Unlimited runs" (or "No run limit on your plan") and skip the math entirely.
   - If `max === 0` or `max == null`, treat as not-yet-loaded, render a skeleton.
   - Only compute `max - used` when `max > 0`.
3. Add a unit test for the formatter / helper covering: `max=-1`, `max=0`, `max=null`, `max=10, used=3`, `max=10, used=10`.

**Acceptance criteria:**
- Logged in as PRO (`revalidate-pro@test.local`), `/billing` hero does **not** show `2147483647`.
- Logged in as TEAM (`revalidate-team@test.local`), `/billing` hero shows "Unlimited" or equivalent.
- Logged in as ENT (`revalidate-ent@test.local`), `/billing` hero shows "Unlimited" or equivalent.
- Logged in as FREE (`revalidate-free@test.local`), `/billing` hero shows correct fraction (after Task 3 lands, "X of 10 runs this month").

**Depends on:** none. Can ship before Task 3 with a temporary "Unlimited" string for non-FREE.

---

### Task 2 — Add error boundary to V2 + V3 app shells, fix `AuditPage` hooks-order violation

**Files:** `frontend/src/pages/AuditPage.tsx` (line ~128), `frontend/src/v3/AppLayout.tsx`, the V2 layout equivalent (likely `frontend/src/v2/Layout.tsx` or similar).

**Problem (two parts):**

**Part A — Hooks-order violation in AuditPage:**
`AuditPage.tsx:128` throws `React has detected a change in the order of Hooks` because `useState` calls happen *after* a conditional `<Navigate to="..." />` early-return. Any user with `canViewAudit=false` crashes the entire route.

**Part B — No error boundary:**
Both V2 and V3 layouts lack an error boundary, so when any page in the app shell throws, the user gets a black/white screen with no recovery affordance. Confirmed visually on `/audit` for ENT (screenshot is a fully blank dark canvas — no chrome, no error message).

**Fix:**

For Part A:
1. Open `AuditPage.tsx`.
2. Move ALL hook calls (`useState`, `useEffect`, `useMemo`, custom hooks) to the top of the component body, before any conditional logic.
3. Compute the auth gate as a regular boolean *after* the hooks.
4. Conditionally render `<Navigate />` (or `null` plus an effect-based redirect) instead of early-returning before hooks.
5. Add a unit test or React Testing Library test that mounts `<AuditPage />` with a fixture user where `canViewAudit=false` and asserts no crash.

For Part B:
1. Create or import an `ErrorBoundary` component (if one exists, find it; otherwise build a minimal class component with `componentDidCatch`).
2. Wrap the `<Outlet />` (or equivalent) inside `AppLayout.tsx` and the V2 layout file.
3. Fallback UI: "Something went wrong. [Retry] [Go home]". Log the error to console (and the existing telemetry pipeline if present).
4. Add a Storybook story or test that renders the boundary's fallback to verify it's wired.

**Acceptance criteria:**
- Logged in as ENT (`canViewAudit=false`), navigating to `/audit` redirects cleanly (no crash, no blank screen).
- Logged in as SUPER_ADMIN (`canViewAudit=true`), `/audit` renders the audit page normally.
- Manually throwing a render error from any page (insert a `throw new Error('test')` temporarily) shows the error boundary fallback, not a blank screen.

**Notes:** The two parts are intentionally bundled because they protect each other — if the audit fix has a regression, the boundary catches it. Ship together.

---

### Task 3 — Single-source-of-truth audit for run limits

**Files (likely):**
- Backend: wherever `BillingPlan` / `tier_config` is defined (Spring Boot, likely `BillingPlanService.java`, `TierConfigService.java`, or migration SQL files).
- Frontend: `BillingPage.tsx`, `PricingPage.tsx`, `UsageBadge.tsx` (or wherever the "X/Y runs" badge in the top-bar lives), `V2 sidebar usage component`.

**Problem:** The FREE tier run limit is shown three different ways on a single page:
- V3 top-bar: `0/5 runs` (reads `usage[].max`)
- `/pricing` Free card: "5 lifetime runs" (hardcoded)
- `/billing` hero: "X of 25 lifetime runs left" (reads from a third source)
- Backend `permissions.monthlyRunLimit`: 10 (the *correct* value per V145)

V145 migrated FREE from "5 lifetime" to "10 credits/month, no credit card." The migration touched `tier_config` but missed `billing_plan.run_limit` (still says 5) and the frontend stale-source readers.

**Fix:**

1. **Backend:** update the `billing_plan` row for the FREE plan to set `run_limit = 10`. Write a migration. Verify no other code reads this column for tier-limit logic — if it does, point it at `tier_config.monthly_run_limit` instead.
2. **Frontend audit:** grep for every reader of run-limit data:
   ```
   rg -n 'monthlyRunLimit|runLimit|usage\[.*\]\.max|lifetime runs'
   ```
   For every hit, ensure it sources from `permissions.monthlyRunLimit` (the canonical source). Replace `usage[].max` reads with `permissions.monthlyRunLimit` reads.
3. **Frontend copy:** update the `/pricing` Free card to read "10 runs / month, no credit card" (also Task 8).
4. **Add a constant** at the frontend boundary if helpful: `const RUN_LIMIT_SOURCE = 'permissions.monthlyRunLimit'`. Comment why this is the source of truth.
5. **Test:** integration test or persona-test fixture that loads `/billing`, `/pricing`, `/runs` (top-bar), and `/team` for a FREE user and asserts the same number ("10") appears in all four places.

**Acceptance criteria:**
- FREE user (`revalidate-free@test.local`) sees "10" in: top-bar usage badge, billing-page hero, pricing-card Free copy, and any sidebar usage indicator. Zero references to "5", "5 lifetime", or "25" remain in surfaces visible to a FREE user.
- PRO user sees "20" in the same places (or "Unlimited" / "Top-up" surfaces if applicable).
- TEAM/ENT users see "Unlimited" or equivalent (no number; depends on Task 1's handling).

**Depends on:** Task 1 (so non-FREE users don't show garbage). Can ship in parallel.

**Notes:** This is the highest-leverage cleanup task in the entire backlog. Do it carefully — every other tier-display bug downstream depends on this being right.

---

### Task 4 — Fix `/billing` "You're on Free." headline for non-FREE users

**Files:** `BillingPage.tsx` (the hero section that renders the tier headline + tier badge).

**Problem:** PRO/TEAM/ENT users with no active Stripe subscription record (test fixtures have `billing.plan.name = "Free"` for these personas due to fixture stale-state) see the headline "You're on Free." plus a "Free" badge, contradicting their actual `me.tier`.

**Fix:**

1. The hero tier headline must source from `me.tier` (the canonical user tier), NOT from `billing.plan.name`.
2. If `billing.subscription` is missing or `status === 'FREE'` but `me.tier !== 'FREE'`, treat it as "subscription record stale, fall back to me.tier."
3. The "Current plan" badge on the plan cards (Free/Pro/Team) below should highlight whichever card matches `me.tier`, not whichever matches `billing.plan.name`.
4. Add a snapshot test for each tier's hero state.

**Acceptance criteria:**
- PRO user (`revalidate-pro@test.local`) sees "You're on Pro." headline and the Pro plan card has the "Current" badge.
- TEAM user sees "You're on Team."
- ENT user sees "You're on Enterprise."
- FREE user sees "You're on Free." (unchanged).

**Depends on:** none. Independent of Tasks 1 and 3 but ship together if convenient — they're all on `BillingPage.tsx`.

---

### Task 5 — Fix `/team` page tier-gating AND pitch copy

**Files:** `frontend/src/v3/pages/V3Team.tsx`.

**Problem:** Logged-in TEAM users (and PRO users) currently see this on `/team`:

> Paid tiers · Upgrade to bring teammates in.
> Free is a one-developer plan. Pro ($20/mo) gives you unlimited managed runs solo. Team ($39/seat) adds per-seat collaboration with shared spec templates, audit log, SSO — and runs go through your own Anthropic / OpenAI / Google keys (BYOK), so there's no per-run charge from us beyond the seat fee.
> [See plans →]

A TEAM user is being sold the plan they already pay for. Two bugs combined:
- Inverted gate (TEAM should not see "Upgrade to bring teammates in").
- Pitch copy says "Pro gives you unlimited managed runs solo" — but `/pricing` says PRO has "20 credits/month (grandfathered cap)". Direct contradiction.

**Fix:**

1. Tier gate: if `me.tier === 'TEAM' || me.tier === 'ENTERPRISE'`, render the actual team-management UI (members list, seats, invitations, SSO config link). If gate logic is inverted, fix the comparison.
2. For FREE/PRO users, the "upgrade to bring teammates in" pitch is fine — but rewrite the body copy to remove the "Pro gives you unlimited" claim. Replace with: "Pro ($20/mo, grandfathered) is for solo developers — 20 runs / month. Team ($39/seat) adds per-seat collaboration, shared spec templates, audit log, SSO, and BYOK billing."
3. Verify the `/pricing` Pro card and the `/team` Pro mention now agree on the run-limit number.
4. Add a Storybook story or test that renders V3Team for each persona and asserts: TEAM/ENT see the team-management UI; FREE/PRO see the corrected upsell.

**Acceptance criteria:**
- TEAM user (`revalidate-team@test.local`) lands on `/team` and sees member management UI, NOT the upsell.
- ENT user lands on `/team` and sees member management UI.
- FREE/PRO users see an upsell whose Pro mention says "20 runs / month" (or whatever number Task 3 settles on), not "unlimited."

**Includes:** Task 11 from the action items file (PRO copy contradiction). Ship as one PR.

**Depends on:** Task 3 (so the copy uses the canonical limit). Acceptable to ship copy first, then Task 3 reconciles other surfaces.

---

## P1 — Ship this sprint

### Task 6 — Tier-suppress V2 sidebar's `UpgradeCta` footer + usage progress bar

**Files:** likely `frontend/src/v2/components/Sidebar.tsx` or `SidebarFooter.tsx`. Grep for `Upgrade to Pro` to find it.

**Problem:** The V2 left-sidebar renders an "Upgrade to Pro" CTA + a "0/5 runs" progress bar at the bottom for ALL tiers, including TEAM and ENT. The sidebar items list (Runs, Library, Migrations, etc.) IS tier-aware (TEAM correctly sees "Exec summary"); only the footer is broken.

**Fix:**

1. Find the `UpgradeCta` footer component in the V2 sidebar.
2. Wrap with `if (me.tier === 'FREE')` — only render for FREE users.
3. The "0/5 runs" progress bar should also conditionally render — only when `me.tier === 'FREE'`. For PRO with metered Top-up, decide whether to show a different progress indicator (probably yes — show "X / 20 runs" + a "Top-up" link). For TEAM/ENT (`monthlyRunLimit === -1`), hide entirely.
4. Add a test that renders the sidebar for each persona and asserts the upgrade CTA appears only for FREE.

**Acceptance criteria:**
- FREE user `/runs` page: V2 sidebar footer shows "Upgrade to Pro" + "0/10 runs" progress (after Task 3 lands).
- PRO user `/runs` page: footer shows usage indicator + "Top-up" link, NOT "Upgrade to Pro."
- TEAM user `/runs` page: footer is hidden or shows org-name only.
- ENT user `/runs` page: footer is hidden or shows org-name only.

**Notes:** Don't widen the change. The sidebar items list is fine; only touch the footer component.

---

### Task 7 — Strip every reference to "MAX" tier from the codebase

**Files:** everywhere. Run:
```
rg -ni 'MAX' --type ts --type tsx --type java --type sql
rg -ni '\bmax\b' --type=md docs/
```

**Problem:** The MAX tier was retired in V165, but stale references remain — most visibly in `/pricing` Pro card body copy ("Existing subscribers only — new buyers, choose TEAM **or MAX**").

**Fix:**

1. Run the greps above. Filter out false positives (`Math.max`, CSS `max-width`, `MAX_INT`, etc.).
2. For each true positive, rewrite to remove the MAX reference. The most common case is replacing "TEAM or MAX" with just "TEAM" or "Team."
3. Search backend Java sources for `Tier.MAX` enum values — if the enum still has a `MAX` variant, check whether removing it is safe (any DB rows still use it? Any subscriptions still mapped to it?). **🚧 If MAX still has live references in the database, file a separate ticket and only remove frontend strings for now.**
4. Verify pricing card, `/team` page, settings page, and any tooltip / help text are all MAX-free.

**Acceptance criteria:**
- `rg -ni 'MAX' frontend/src/` returns zero results referring to the tier (only `Math.max`, `MAX_INT`, etc.).
- Pricing card no longer says "TEAM or MAX."
- No user-facing string anywhere mentions MAX.

**🚧 BLOCKED:** if backend `Tier.MAX` enum has live DB references — needs product confirmation before removing the enum value.

---

### Task 8 — Update FREE tier pricing copy

**Files:** `PricingPage.tsx` (the Free card), possibly i18n strings file.

**Problem:** Free card copy still says:
- Header: "5 lifetime runs · invite friends for +5 each"
- Bullet: "5 lifetime runs"
- Bullet: "+5 per friend (cap 25)"

Per V145, FREE is now "10 credits / month, no credit card required." The "+5 per friend" referral mechanic is also a holdover that needs to die alongside the lifetime model — confirm with product.

**Fix:**

1. Replace the Free card header description with: "10 runs / month · no credit card."
2. Update bullets to reflect the new model:
   - "10 runs / month"
   - "1 project · 1 repo"
   - "All providers available"
   - "Anonymous trial — no signup"
3. Remove "+5 per friend (cap 25)" UNLESS product confirms the referral mechanic is still active. **🚧 If the referral system is still alive (check backend `referral_credits` table or similar), keep the line but rephrase: "Earn +N runs per friend referred."**
4. Update the "Try free →" CTA to whatever the current onboarding flow expects.

**Acceptance criteria:**
- Logged-out `/pricing` Free card shows "10 runs / month" copy, zero references to "lifetime."
- FREE user logged-in `/pricing` Free card shows "Current" badge with the same copy.

**🚧 BLOCKED:** "+5 per friend" — needs product confirmation on whether referrals are still active.

---

### Task 9 — Fix ENTERPRISE RBAC: org-admin permissions

**Files:** `TierPermissionService.java` (backend, the `enterpriseUserPermissions` method).

**Problem:** ENT ORG_ADMIN currently gets `false` for all of these critical permissions:
- `canInviteMembers`
- `canViewOrgMembers`
- `canViewAudit`
- `canManageIntegrations`
- `canViewAccounting`
- `canViewPricing`

Result: a self-serve ENT admin literally cannot manage their own org. This contradicts the persona doc and the `/trust` page claim ("SAML/OIDC available on Enterprise. Configure from Team page.").

**🚧 BLOCKED — needs product decision before code:**

There are two ways to fix this:
- **Option A:** ENT ORG_ADMIN auto-gets all of the above as `true`. Simple. Matches user expectation.
- **Option B:** Introduce a new sub-role within ENT (e.g., `ENT_ORG_ADMIN` vs `ENT_BILLING_ADMIN`) with separate permission maps.

**Wait for product to choose A or B.** Then:

If A:
1. Update `enterpriseUserPermissions` to set all six permissions to `true` for ORG_ADMIN.
2. Update integration tests / fixtures.
3. Verify `/team`, `/audit`, `/integrations`, `/admin` all become accessible for `revalidate-ent@test.local`.

If B:
1. Define the sub-role enum.
2. Update `TierPermissionService` with branching logic.
3. Update the test fixture for `revalidate-ent@test.local` to assign the appropriate sub-role.
4. Backfill any existing ENT users in production to the new sub-role (separate migration ticket).

**Acceptance criteria (post-decision):**
- ENT user (`revalidate-ent@test.local`) can navigate to `/team`, see member list, click "Invite member."
- ENT user can navigate to `/audit` and see audit log.
- ENT user can navigate to `/integrations` and connect/disconnect services.
- ENT user can navigate to `/admin` only if SUPER_ADMIN — verify this gate is unchanged.

---

### Task 10 — Add typed-confirmation modal to `/admin` "Halt all runs"

**Files:** `frontend/src/admin/SuperAdminConsole.tsx` or wherever the "Halt all runs" button is rendered (the page header on the `/admin` route).

**Problem:** "Halt all runs" is a 1-click destructive global op with no confirmation. Industry standard is a typed-confirmation modal ("type HALT to confirm").

**Fix:**

1. Wrap the button's click handler in a confirmation modal.
2. Modal contents:
   - Title: "Halt all running operations?"
   - Body: "This will stop every active run across all orgs. Users will see their runs end immediately. This cannot be undone."
   - Input: "Type **HALT** to confirm" (case-sensitive).
   - Submit button disabled until input matches "HALT".
   - Cancel button always enabled.
3. On submit, fire the existing halt-all API call.
4. Success toast: "All runs halted. N runs were active."
5. Add a test that the API call doesn't fire unless the typed input matches.

**Acceptance criteria:**
- Clicking "Halt all runs" opens a modal, doesn't fire the action.
- Modal submit is disabled until "HALT" is typed.
- Cancel closes the modal without firing.
- After successful halt, a toast confirms.

---

### Task 11 — (Subsumed by Task 5)

PRO copy reconciliation between `/team` and `/pricing` is bundled into Task 5 since the contradicting string lives on `/team`. No separate ticket.

---

### Task 12 — Fix WCAG-AA failure: white text on Signal Blue button

**Files:** likely `frontend/src/styles/tokens.css` or `theme.ts`, plus any button component using `--c-signal-blue`.

**Problem:** White text on `#5B8DEF` Signal Blue scores 3.23 contrast — AA-large only, FAIL for normal body text. Affects EVERY primary CTA in the product (Sign in, Submit, Launch Run, Add seats, Upgrade). Worse: the hover state `#7AA5F5` makes contrast WORSE (2.7).

**🚧 PARTIALLY BLOCKED — needs design call before final hex:**

Two paths, design needs to pick:

- **Option A (preferred):** Darken Signal Blue. Try `#3B6FCC` (gives ~5.5 contrast white-on-blue). Keep gradient destination teal `#7AD1C8` for brand continuity.
- **Option B:** Keep blue, darken text. Use `#0B0D10` (graphite-0) for button text instead of white. Gives ~6.0 contrast.

Once design picks:

1. Update `--c-signal-blue` (Option A) or update the button text color rule (Option B).
2. Update the hover state to be DARKER, not lighter — `--c-signal-blue-hover` should give equal or better contrast than the rest state.
3. Run the in-app contrast checker (the JS snippet from the audit's section 3.2) on the updated palette.
4. Visual smoke-test every primary CTA: login Sign-in, billing Upgrade, runs Launch, billing Add seats, etc.

**Acceptance criteria:**
- White-on-button contrast ≥ 4.5 (full AA pass).
- Hover state contrast ≥ rest contrast.
- No CTA in the product fails AA.

**🚧 BLOCKED:** awaits design pick of Option A or B.

---

## P2 — Ship this month

### Task 13 — Remove "v2" version-name leakage in user-facing copy

**Files:** grep for `v2` in frontend strings.
```
rg -n 'v2|V2' frontend/src/ | grep -v '\.tsx\.snap'
```

**Problem:** Users see "Theme (v2 surfaces)" and "Open v2 integrations →" in Settings. They don't know what "v2" means.

**Fix:**

1. Find all user-facing strings containing "v2" or "V2" (excluding code identifiers, snapshot files, etc.).
2. Replace with neutral language:
   - "Theme (v2 surfaces)" → "Theme" or "Display theme"
   - "Open v2 integrations →" → "Open integrations →" or "Manage integrations →"
3. Code identifiers / route paths can stay as-is — only user-facing strings change.

**Acceptance criteria:**
- No user-facing string contains "v2", "V2", "v3", or "V3" (versions are internal).
- Settings page reads cleanly per persona.

---

### Task 14 — Settings "Danger zone" — add real destructive ops or rename

**Files:** `SettingsPage.tsx`, the Danger zone section.

**Problem:** "Danger zone" currently contains only "Sign out." Sign-out isn't destructive — it misuses the convention (Stripe / GitHub / shadcn use Danger zone for delete-account, revoke-tokens, transfer-ownership).

**🚧 BLOCKED — needs product decision:**

Two paths:

- **Option A:** Add real destructive operations: "Delete account," "Revoke all sessions," "Export then delete data" (GDPR-friendly).
- **Option B:** Rename "Danger zone" → "Account actions" and keep Sign-out there.

Option A is more work (requires backend support for account deletion); Option B is a 1-line copy change.

Once product picks:

If A:
1. Add backend endpoints for account-deletion + session-revocation (separate ticket if not already present).
2. Add UI: each destructive op gets a typed-confirmation modal (see Task 10 pattern).
3. Audit-log every destructive op.

If B:
1. Rename the section heading.
2. Move Sign-out to a less alarming visual treatment (the red border can stay, but the section name should match contents).

**Acceptance criteria (post-decision):**
- Section name matches contents — destructive ops live in "Danger zone," non-destructive ops live in "Account actions" or similar.

---

### Task 15 — Capitalize "gemini" in New Run modal

**Files:** the New Run modal component, likely `NewRunModal.tsx` or wherever provider chips are rendered.

**Problem:** Provider chips read "Anthropic / Premium", "**gemini** / Cheapest", "OpenAI / Fastest". Lowercase "gemini" sticks out.

**Fix:**

1. Find the provider list (likely a constant or enum).
2. Change `'gemini'` → `'Gemini'`.
3. Also audit "Premium" / "Cheapest" / "Fastest" subtitles — verify these are not steering users toward Anthropic disproportionately. **🚧 If the subtitles have business intent (e.g., promote BYOK), confirm with product before reframing.**

**Acceptance criteria:**
- Provider chips read "Anthropic", "Gemini", "OpenAI" with consistent capitalization.

---

### Task 16 — Fix Library category-name slug-Title-Case rendering

**Files:** `LibraryPage.tsx` or wherever the category chips are rendered.

**Problem:** Category chips read "People And Money", "Smart And Reach", "Ui And Growth", "Data And Infra" — Title Case applied naively to slugs. Reads computer-generated.

**Fix:**

1. Find where the category labels are rendered.
2. If the source is a slug (e.g., `people-and-money`), add a label-mapping layer that produces human-readable strings:
   - `people-and-money` → "People & Money"
   - `smart-and-reach` → "Marketing & Reach"
   - `ui-and-growth` → "UI & Growth"
   - `data-and-infra` → "Data & Infrastructure"
3. Don't auto-Title-Case slugs ever again — always use an explicit label map.

**Acceptance criteria:**
- Library chips show human-written labels (with proper "&", "UI", etc.).
- No "And" between words; no Title-Case-of-conjunctions.

---

### Task 17 — Library category-chip row visibility differs by tier

**Files:** `LibraryPage.tsx`.

**Problem:** FREE users see the category-chip row above the search bar. TEAM users do not — the chips are missing entirely. (Verify on `free-library.png` vs `team-library.png`.)

**Fix:**

1. Find the conditional rendering the chip row.
2. **🚧 BLOCKED — needs product decision:** are the chips supposed to show for everyone, or only FREE? If only FREE, document why. If everyone, fix the gate.
3. Once decided, ship the fix and add a test asserting the chip row visibility matches expectation per persona.

**Acceptance criteria (post-decision):**
- FREE, PRO, TEAM, ENT all see consistent Library page chrome — either all see chips, or none do.

---

### Task 18 — Disambiguate "Upgrade ↗" CTA on PRO top-bar

**Files:** the V3 top-bar component, likely `frontend/src/v3/TopBar.tsx`.

**Problem:** PRO users see two top-bar CTAs side by side: `Upgrade ↗` and `+ Top-up`. Top-up is good — but bare "Upgrade" doesn't say *where to*. PRO's only upgrade path is TEAM.

**Fix:**

1. For PRO users, replace "Upgrade ↗" with "Upgrade to Team →" (explicit destination).
2. Alternative: hide the Upgrade CTA entirely for PRO and let Top-up be the only conversion path. **🚧 Product call — confirm preference before shipping.**
3. For FREE users, "Upgrade ↗" stays (path is Pro-or-Team, ambiguity is acceptable since pricing page is the destination).

**Acceptance criteria:**
- PRO top-bar reads "Upgrade to Team →" + "+ Top-up" (or just "+ Top-up" if hide path is chosen).
- FREE top-bar unchanged.

**🚧 BLOCKED:** product preference between explicit-label vs hide.

---

### Task 19 — Unify V2 sidebar and V3 top-bar chrome

**🚧 SCOPE TOO LARGE FOR A SINGLE CLAUDE CODE TASK.** This is a multi-week migration. Break into sub-tickets:

- **19a:** Inventory every page that renders V2 layout vs V3 layout. Produce a list.
- **19b:** Pick canonical chrome (V3 top-bar based on the audit's framing).
- **19c:** For each V2-only page, port to V3 layout. Each port is its own PR.
- **19d:** Once all pages use V3, delete V2 layout components.

**This task should be planned with engineering leadership, not handed to Claude Code as a single backlog item.** Filed here for visibility.

---

### Task 20 — Add `aria-label="Close"` to New Run modal close button

**Files:** the New Run modal close button.

**Problem:** The close affordance does not match `[aria-label="Close"]` selector — accessibility regression.

**Fix:**

1. Find the close button (likely an `<button>` with an X icon).
2. Add `aria-label="Close"`.
3. Verify keyboard nav: Escape closes the modal, Tab reaches the close button.
4. Audit other modal components for the same issue while you're at it.

**Acceptance criteria:**
- New Run modal close button has `aria-label="Close"`.
- Screen reader announces "Close, button" when focused.

---

### Task 21 — Fix `/admin/tiers` deep-link redirect

**Files:** the project-store / session-stash logic, likely `useProjectStore.ts` or similar.

**Problem:** Navigating directly to `/admin/tiers` redirects the user into `/projects/<UUID>` (auto-loads the first project from session storage).

**Fix:**

1. Find the redirect logic. It's probably an effect that reads session storage and pushes to a project route.
2. Add an exclusion: don't redirect if the current path starts with `/admin`.
3. Verify other deep-link routes still work (e.g., `/billing`, `/settings`).

**Acceptance criteria:**
- `/admin/tiers` direct nav stays on `/admin/tiers`.
- `/admin` direct nav stays on `/admin`.
- `/projects/<id>` direct nav still loads the correct project.

---

### Task 22 — Login Sign-in button: investigate first-click no-fire

**Files:** `LoginPage.tsx`.

**Problem:** During the persona test, the Sign-in button click did not fire `POST /api/auth/login` on the first attempt. Reproducibility risk — this might be a hydration race, a missing form-submit handler, or an event-bubbling issue.

**Fix:**

1. Reproduce: open `/login` cold (clear session storage), enter credentials, click Sign-in once.
2. Inspect the network tab for `POST /api/auth/login`. If it doesn't fire on click 1 but does on click 2, it's likely a form-init issue.
3. Likely fix: ensure the form's `onSubmit` is wired *before* hydration, or convert button-click handlers to use a stable callback ref.
4. Add a Cypress / Playwright test: `/login` cold, fill creds, click Sign-in, assert API call fires within 100ms.

**Acceptance criteria:**
- First click on Sign-in always fires the login API call.
- E2E test added that catches regressions.

---

## P3 — Backlog / polish

### Task 23 — Move `--c-pop-orange` out of the warning hue family

**🚧 BLOCKED — needs design call.**

**Problem:** `--c-pop-orange #F97316` is visually identical to `--status-partial #F5A524` and `--c-warning #F59E0B`. The hero "spec." accent reads as a warning.

**Fix (after design picks new hex):**

1. Update `--c-pop-orange` to the new value.
2. Verify accent appearances on `/login`, `/pricing`, marketing surfaces.
3. Ensure no contrast regressions (run the in-app checker).

**Suggested options for design:** `--c-pop-pink #EC4899` (already in palette), or a teal accent that complements brand-grad.

---

### Task 24 — Differentiate `--accent-blue` from `--status-executing`

**🚧 BLOCKED — needs design call.**

**Problem:** `--accent-blue #5B8DEF` and `--status-executing #5B8DEF` are identical. A "running" status pill looks like a CTA.

**Fix (after design picks):**

Suggested: `--status-executing #4A77D6` (slightly desaturated). Update token, verify status-pill appearances across `/runs`.

---

### Task 25 — Fix `--border-default` contrast

**Files:** `tokens.css` / theme file.

**Problem:** `--border-default #242A33` has 1.35 contrast vs background — borders barely visible, UI looks frameless by accident.

**Fix:**

1. Bump `--border-default` to a lighter graphite. Try `#333B46` (currently `--c-graphite-5`) or `#3D454E`.
2. Test on cards, dividers, input outlines.
3. Run the contrast checker — target ≥ 1.5:1 for non-text borders (WCAG SC 1.4.11).

**Acceptance criteria:**
- Border contrast ≥ 1.5:1.
- Cards and dividers visibly distinct from bg.

---

### Task 26 — Re-evaluate "spec." orange in dark theme

Subsumed by Task 23. Skip.

---

### Task 27 — `/admin` user-table: mark PRO with "(grandfathered)" hint

**Files:** the admin user-table tier dropdown.

**Problem:** The tier dropdown lists PRO without indicating it's hidden/grandfathered. Admins editing user tiers should know PRO is no longer a sellable tier.

**Fix:**

1. In the dropdown, render the PRO option as "Pro (grandfathered)".
2. Optional: visually de-emphasize (greyer text) since it's a legacy state.
3. Don't disable selection — admins still need to assign existing grandfathered users.

**Acceptance criteria:**
- Tier dropdown PRO option reads "Pro (grandfathered)."
- Dropdown still allows selection.

---

## Strategic / tactical-enough items from competitor assessment

These came out of the screenshot-based competitor review and aren't in the original audit's bug inventory. They're tactical enough for Claude Code in most cases.

### Task 28 — Rewrite login page hero copy

**Files:** `LoginPage.tsx`.

**Problem:** Hero "Sign in to ship a **spec**." uses internal jargon ("spec") that first-time visitors don't understand. The body copy below already has the better line ("ExecuteSpec ships AI-written pull requests you can actually merge").

**Fix:**

1. Replace H1 from "Sign in to ship a spec." to: "Ship AI-written PRs you can actually merge." (Or a similarly concrete alternative that product approves.)
2. Demote the existing body copy to a sub-paragraph or remove it (now redundant).
3. Update the right-side form header "Welcome back" — keep, or remove duplication with the left-side hero.

**🚧 PARTIALLY BLOCKED:** product/marketing should approve final copy. Submit a PR with the suggested replacement and request review.

**Acceptance criteria:**
- Hero H1 has zero ExecuteSpec-internal jargon.
- A first-time visitor can describe what the product does in one sentence after reading the hero.

---

### Task 29 — Replace generic templates on `/runs` with recipe-shaped templates

**Files:** wherever the four Runs templates are defined ("Portfolio Website", "Expense Tracker", "REST API", "SaaS Landing Page").

**🚧 PARTIALLY BLOCKED — needs product input on which recipes to feature.**

**Problem:** Current templates are CS101-tier and undersell the product. The migrations catalog already has sophisticated recipes that better demonstrate range.

**Fix (after product picks 4-6 recipes):**

1. Replace the template list with the chosen recipes — pull from the migrations catalog or the Library.
2. Suggested set: "Add Stripe Checkout to Next.js," "Migrate Express → Fastify," "Convert REST → tRPC," "Add SAML SSO to your auth flow."
3. Verify each template links to a corresponding migration recipe or library entry.

**Acceptance criteria:**
- Templates demonstrate ExecuteSpec's actual capabilities, not generic web-dev examples.

---

### Task 30 — Seed a demo project per fresh account

**Files:** likely backend `UserOnboardingService.java` + a sample-project fixture.

**🚧 BLOCKED — needs product approval on demo content.**

**Problem:** New accounts see empty dashboards (`/insights`, `/runs`, projects-empty) — evaluators bounce because there's nothing to look at.

**Fix (after product approves demo content):**

1. On account creation, seed a "Demo project" with:
   - 2-3 historical runs (one Build, one Migration, one with a non-trivial diff)
   - Populated cost/spend/success-rate data for `/insights`
   - A spec template
2. Mark it visually with a "Demo" badge so users know it's not theirs.
3. Allow users to delete it from Settings.
4. Confirm the demo runs don't count against tier limits.

**Acceptance criteria:**
- Fresh account `/insights` shows non-zero data.
- Fresh account `/runs` shows demo runs.
- "Demo" label visible; delete-demo flow works.

---

### Task 31 — Surface trust page from homepage

**Files:** the marketing homepage component (may live in a separate marketing-site repo — confirm before starting).

**Problem:** `/trust` is a procurement-grade asset reachable only via a footnote on the pricing card's Enterprise section. Should be a homepage CTA.

**Fix:**

1. Add a "Trust + compliance" link to the homepage nav, footer, or a CTA block.
2. The exact placement is a marketing decision — propose 2-3 options as a PR.

**🚧 PARTIALLY BLOCKED:** marketing/design should approve placement.

**Acceptance criteria:**
- `/trust` is reachable from the homepage in ≤ 1 click.

---

## Items deliberately NOT in this list

The following came out of the audits but are NOT Claude-Code-shaped tasks:

- **Live agent preview UX** — quarter-scale roadmap project. Needs design + research.
- **Homepage redesign / re-positioning around migrations catalog** — product/marketing decision. Strategic, not tactical.
- **Lift TCO estimator to homepage** — marketing-site work, depends on homepage redesign.
- **Public showcase of real PRs** — content + legal + customer permissions. Multi-team coordination.
- **BYOK pricing-card hierarchy rewrite** — pricing strategy decision before any code change.

These are tracked separately. Don't try to handle them via Claude Code.

---

## Suggested ship order

**Day 1-2 (P0):**
1. Task 1 (billing MAX_VALUE) + Task 4 (billing headline) — same file, ship as one PR.
2. Task 2 (audit page + error boundary).
3. Task 3 (run-limit single source of truth) — depends on/enables Tasks 5, 6, 8, 18.

**Day 3-5 (P0 + P1):**
4. Task 5 (`/team` gate + copy) — includes Task 11.
5. Task 6 (V2 sidebar UpgradeCta).
6. Task 8 (FREE pricing copy).
7. Task 7 (strip MAX) — quick wins.

**Week 2 (P1):**
8. Task 9 (ENT RBAC) — after product confirms Option A or B.
9. Task 10 (Halt-all confirmation).
10. Task 12 (button contrast) — after design picks Option A or B.

**Weeks 3-4 (P2):**
11. Tasks 13-22 in any order; most are independent.

**Ongoing (P3 + strategic):**
12. P3 design tokens batched into the next design-system pass.
13. Tasks 28-31 paced by product/marketing reviews.

---

## Final notes for Claude Code

- **If a task is marked 🚧 BLOCKED, do not start coding.** Confirm with the human owner that the blocker is resolved before opening a PR.
- **If you discover a task is bigger than estimated**, flag it back — don't silently expand scope. Several tasks here say "likely files: X" — verify before assuming.
- **For every PR, link back to this doc and the task ID.** Helps reviewers know what acceptance criteria to check.
- **Don't combine tasks across priorities.** A P0 PR and a P3 PR shouldn't share a branch.
- **The persona-test markdown file (`persona-test-2026-05-01.md`) is the source of truth for visual evidence.** When in doubt, re-read the relevant section before editing.

Total tasks: 31 (29 actionable, 2 strategically scoped: 19 and items in the "deliberately NOT" section).
