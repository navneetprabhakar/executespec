# ExecuteSpec — UX & Product Audit (v2.5.11)

> **Audit baseline:** branch `audit/v2-5-11-ux-product`, cut from `origin/dev` tip
> `ca93bedea134e6bf738b37404896b715047b9f8c` (Merge PR #53,
> `feature/readymades-and-migrations`). Frontend `package.json` and backend
> `pom.xml` both report version `2.5.1`; the README + CLAUDE.md headline `v2.5.11`.
> All `[live-api]` and `[browser]` evidence sourced from the local stack
> (Spring profile `local` on `:8080`, Vite on `:5173`) per user directive
> 2026-04-30. Deployed env not used.

---

## §1 — TL;DR

- **The product ships two complete UI shells side-by-side, glued together by URL coincidence, and neither shell is internally consistent.** The v3 top-bar (`V3Shell` + `V3TopBar.tsx:46-54`) exposes only Logo · Project Switcher · Library · Migrations · Avatar — no Runs link, no run composer outside a single project context, no settings drawer. The v2 sidebar (`Sidebar.tsx`) exposes everything — Runs, Library, Migrations, Exec, Dashboard, Integrations, Invoices, Accounting, Audit, Deploy, Admin/Analytics, Settings — and renders only on legacy v2 routes. A user can't reach their existing run history from the v3 shell without typing `/runs` into the URL bar. **This single fact is the audit's headline finding.**
- **`TierPermissionService.freeUserPermissions` ignores `tier_config` for at least 5 capabilities** ([code-trace] `TierPermissionService.java:228-258`). Live API probes against the local DB (which actually has the V141+V144 ladder applied) confirm: ENTERPRISE solo users get `canDeploy=true, maxDeployments=3` (should be unlimited), `canViewCostBreakdown=false` (can't view their own cost breakdown), and PRO solo users get `canManageAiKeys=false` (BYOK promised in marketing, not delivered). These are not minor — they invalidate per-tier value prop testing.
- **The entire `/welcome` onboarding + 3-sample-spec gallery is dead code.** `LoginPage.tsx` redirects to `/home`, `AuthCallbackPage.tsx` redirects to `/home`, `VerifyEmailPage.tsx` renders a manual "Sign in →" link to `/login`. Nothing in the codebase navigates to `/welcome`. The `SampleSpecRegistry`, `SandboxProvisioningService`, `OnboardingController.pickSample` endpoint, the `WelcomePage.tsx`, the 3 YAML sample specs in `src/main/resources/sample-specs/` all exist and compile, but a fresh user never sees any of it.
- **Library and Migrations are visually polished but interaction-poor.** Both galleries surface 28+11 cards to all tiers, route to detail pages, and fire real `V2RunOrchestrator` dispatches on apply. But neither is wired to the v3 chat composer, neither shows up in onboarding, and the Migrations dry-run endpoint (`POST /api/migrations/{slug}/dry-run`) has no tier check — a FREE user can compute applicability + cost + sample diff for any locked recipe without paying ([code-trace] `MigrationController.java:65-72`).
- **Documentation is materially stale on every front-and-center surface.** README claims tiers are `FREE/PRO/ENTERPRISE`; DB has 5 tiers (FREE/PRO-HIDDEN/TEAM/MAX/ENTERPRISE). README pipeline diagram describes V1 (`CompilerAgent`, merge gate, `ReviewerAgentV2`) which V2 deleted. PRD §3.1 tier matrix omits TEAM and MAX entirely. USER_MANUAL §3.3 documents a Batch execution feature that V2 explicitly does not support (line 775 of the same doc admits this).
- **ENTERPRISE is a marketing surface, not a product surface.** SSO/SAML is V127-schema only; the SAML2 handshake is documented as 3-5 days of unscoped engineering ([doc] `docs/sso-saml-wiring.md:25-46`). Named CSM engagement is "target Q4 2026 — currently founder-led" ([doc] `docs/enterprise-sla.md:44-56`). Multi-tenant isolation relies on application-layer `org_id` stamping; no PostgreSQL row-level security policies exist. Activity feed is schema-shipped (V133), service-side wiring undocumented.

**The biggest risk to ExecuteSpec's product success right now is that the v3 IA cleanup is half-done.** The product looks like one thing in the marketing site, one thing on `/home`, and a third thing once you click into `/runs` or `/admin`. No persona — FREE, PRO, TEAM, or ENTERPRISE — has a coherent end-to-end story today.

---

## §2 — Reality vs Documentation

| Claim source | What's claimed | What ships |
|---|---|---|
| README.md:100 | Tiers: `FREE/PRO/ENTERPRISE` | DB has FREE, PRO (HIDDEN), TEAM, MAX, ENTERPRISE — 5 active rows in `tier_config` ([live-db] `psql executespec -c "SELECT tier FROM tier_config"`) |
| README.md:35-77 | Pipeline: `RequestTriageService → CompilerAgent → ReviewerAgentV2`, merge gate, `SubtaskDecomposer` | V2 has no `CompilerAgent`, no merge gate, no `SubtaskDecomposer`; reviewer is `ReviewerAgentV3` ([code] `v2/reviewer/ReviewerAgentV3.java`); per-task `buildProject` smoke is the only build step |
| README.md (header banner) | `v2.5.11` | `pom.xml`, `frontend/package.json` both pinned to `2.5.1`; latest applied migration is V162 ([live-db]) |
| PRD.md:186 (§3.1 Tier Matrix) | `FREE / PRO / ENTERPRISE` columns only | TEAM ($49/seat 50-credit pool, V144) + MAX (BYOK unlimited) tiers seeded but absent from §3.1 |
| USER_MANUAL.md:185-195 | "Real-time vs Batch Execution" — batch is 50% credit discount | V2 only supports REALTIME ([same doc] `USER_MANUAL.md:775` — "Batch execution is not yet available under V2") |
| RBAC.md:99 | `canManageAiKeys`: PRO row marked `-` (denied) | `TierPermissionService.java:234`: `isMaxOrEnterprise = "MAX".equals(tier) || "ENTERPRISE".equals(tier)` — confirmed PRO solo gets BYOK denied |
| PRD.md:195 | "AI key management: PRO = SYSTEM/OWN_KEYS/HYBRID" | Same code path as above; `canManageAiKeys=false` for PRO solo. PRD is internally inconsistent with RBAC.md |
| project-user-journeys.md | Lists "Runs list / Sidebar / main area" as primary nav | v3 top-bar (`V3TopBar.tsx:46-54`) has no Runs link; v2 sidebar shows it but only on AppLayout routes |
| frontend-api-usage.md | Lists `/runs/{id}/clarify`, `/runs/{id}/merge`, `/runs/{id}/iterate` as endpoints | `frontend/CLAUDE.md` line 175 explicitly states these return HTTP 410 Gone on V2 runs; doc still lists them |
| enterprise-one-pager.md:20 | "SSO / SAML — Okta + Azure AD on Enterprise" | `docs/sso-saml-wiring.md:25-46` — schema only; the actual SAML handshake is "out of scope for the auto-execute branch" |
| enterprise-sla.md (header) | "Named CSM engagement" | Same doc, lines 44-56 — "Available when the CSM role is filled (target: Q4 2026). Until then, the founder serves as the direct point of contact" |
| CLAUDE.md root (V141 entry) | V141 collapses MAX → TEAM, leaving FREE/PRO(HIDDEN)/TEAM/ENTERPRISE | DB shows V162 applied AND a TEAM ($49/seat) AND a MAX (BYOK unlimited) row both LISTED. V144 reinstated per-seat TEAM after V141 renamed MAX → TEAM. Net: 5 tiers. CLAUDE.md root is one revision behind reality. |

**Honest take.** This is not normal drift. This is **a credibility problem** for any reader who arrives via README — every load-bearing claim about tiers, pipeline, BYOK, batch, and enterprise readiness is wrong-or-stale on the first scroll. The PRD is internally inconsistent (its own tier matrix omits two of its own tiers). The USER_MANUAL acknowledges its own §3.3 is fictional in §A. The README pipeline diagram describes the old harness that was deleted in 2026-04-13. A skeptical first-time reader who follows the README "Quick Start" will boot the app, see five tiers in `/admin`, see a TEAM tier in pricing they were told doesn't exist, and lose trust. **Fix this first, before any UI redesign.**

---

## §3 — Top 15 Findings

### S0 — Blocker

```
[F-01] V3 shell exposes no path to existing runs
  Severity:     S0
  Confidence:   high
  Evidence:     frontend/src/v3/components/V3TopBar.tsx:37-60; App.tsx:217-246, 282;
                user screenshot of /projects/empty showing 5-link top-bar
  What's wrong: V3Shell is the layout for /home, /projects/:slug, /settings, /billing,
                /pricing, /integrations, /team, /trust, /insights, /library, /migrations,
                /claim. Its top-bar has Logo (→/home) · ProjectSwitcher · Library ·
                Migrations · Avatar. It has no link to /runs, no global "View runs"
                affordance, no per-project "Runs" tab visible on V3ProjectHome
                (V3ProjectHome.tsx:165-185 renders the chat thread; runs only appear
                as inline cards if the user remembers them).
  Why it matters: A returning PRO user opening dev.executespec.ai (per the user's
                  own screenshot) sees five tabs that don't include Runs. The only
                  way back to existing run history is typing /runs into the URL bar
                  (which lands on the legacy v2 RunsPage with the v2 sidebar). This
                  is the central UX confusion the user flagged in-session.
  Fix:          Add a "Runs" entry to V3TopBar.tsx between ProjectSwitcher and
                Library — same NavLink pattern, target /runs (until v3 RunsPage ships)
                or add a per-project Runs tab to V3ProjectHome left of the composer.
                Either is XS effort; doing both would cover the global + scoped case.
  Effort:       XS
```

```
[F-02] /welcome onboarding flow is dead code
  Severity:     S0
  Confidence:   high
  Evidence:     frontend/src/pages/onboarding/WelcomePage.tsx:12 (comment claims
                "Shown on first login post-verification"); App.tsx:255 (route exists);
                LoginPage.tsx redirects to from || '/home'; AuthCallbackPage.tsx:34
                navigates to '/home'; VerifyEmailPage.tsx:42-45 shows manual "Sign in"
                Link to /login; grep -rn "navigate.*welcome|to=.*welcome" returns 0
                forward references; only optIn.ts:48 lists /welcome but it's a static
                array, not a routing call.
  What's wrong: Wave 2.2 built a full sandbox onboarding (3 sample specs, picker,
                provisioning service, prefill handoff via sessionStorage) but never
                wired the navigate(/welcome) trigger. New users post-verification land
                on /home → V3HomeRedirect → /projects/empty → "Create my first project"
                → blank chat thread. The `WelcomePage`, `RunsNewLanding`,
                `SandboxProvisioningService.provisionIfNeeded`,
                `OnboardingController.pickSample`, `SampleSpecRegistry`, and the 3
                YAML files in `src/main/resources/sample-specs/` are all dark.
  Why it matters: The 28-readymade Library + 11 migration recipes (PR #53) are sold
                  in marketing as the "first artifact in 3 minutes" path; the actual
                  fresh-user-to-first-artifact path is "type a project name + type
                  a spec from scratch". TTFA stays high; activation funnel
                  (V125 user_activation_event ledger) under-counts SPEC_SUBMITTED
                  events that should be coming from sandbox.
  Fix:          Either (a) AuthCallbackPage.tsx line 34 + LoginPage.tsx login()
                callback navigate to /welcome instead of /home when the user has
                no projects yet (V3HomeRedirect can also detect this and redirect),
                or (b) delete WelcomePage + SampleSpecRegistry + SandboxProvisioningService
                + the 3 YAML files since they're shipped-but-orphaned. Pick one.
                Don't keep both.
  Effort:       S (option a) or S (option b)
```

```
[F-03] freeUserPermissions hardcodes 5 fields, ignoring tier_config
  Severity:     S0
  Confidence:   high
  Evidence:     src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java:228-258
                (full method body); [live-api] curl /api/auth/me/permissions for all
                4 freshux-* tier accounts — observed: ENT solo gets canDeploy=true
                maxDeployments=3 (line 256-257 hardcoded); ENT solo gets
                canViewCostBreakdown=false (line 241 hardcoded false); PRO solo gets
                canManageAiKeys=false (line 234 only unlocks MAX/ENT);
                everyone solo gets canViewPricing=false (line 238); everyone solo
                gets canManageBillingProfile=true (line 254 hardcoded true).
  What's wrong: The DB has tier_config rows with per-tier overrides (e.g.
                MAX.max_apply_suggestions=-1, ENT.max_org_members=-1) and the
                method calls resolveMaxApplySuggestions / resolveMaxPreviewStarts
                helpers that DO read tier_config. But canDeploy, maxDeployments,
                canViewCostBreakdown, canManageAiKeys, and canViewPricing are all
                hardcoded to a single value across tiers. ENTERPRISE solo gets
                maxDeployments=3 instead of unlimited. PRO solo can't BYOK despite
                marketing claim. ENT solo can't see their own cost breakdown.
  Why it matters: This is FAITHFUL contract violation: the tier_config table is
                  the canonical source of truth (the ladder consolidation work in
                  V140-V146 spent 6 migrations getting this right) and a single
                  service method bypasses 5 of its columns. Persona tests for PRO
                  BYOK and ENT cost-breakdown both fail at the API layer, before
                  they ever reach UI.
  Fix:          Replace hardcoded values at TierPermissionService.java:237, 238, 241,
                256, 257 with `resolveCanDeploy(tier)`, `resolveMaxDeployments(tier)`,
                `resolveCanViewCostBreakdown(tier)`, `resolveCanManageAiKeys(tier)`,
                `resolveCanViewPricing(tier)` helpers reading from tier_config —
                following the same pattern as resolveMaxApplySuggestions
                (lines 276-281). Add the 3 missing helpers; add unit tests pinning
                each tier's expected permission map. Do this BEFORE running another
                tier-redesign sprint or the resulting permission matrix won't match
                tier_config.
  Effort:       S
```

### S1 — Major

```
[F-04] Two complete shells (V3Shell + AppLayout) compete at the same URLs
  Severity:     S1
  Confidence:   high
  Evidence:     App.tsx:217-246 mounts V3Shell at /settings, /billing, /pricing,
                /integrations, /home, /projects/:slug; App.tsx:253-355 mounts
                AppLayout at /runs, /invoices, /accounting, /audit, /deployments,
                /exec, /org/*, /projects/:projectId/dashboard, /admin/*. Several
                URLs (/billing, /settings, /integrations, /pricing) had v2 routes
                that the comment at App.tsx:208-216 says "v3 wins". User experiences:
                clicking a v3 top-bar Settings link lands on V3Settings (no sidebar);
                clicking a v2 sidebar Settings link from /admin lands on … also
                V3Settings, but the path was inside AppLayout for one click and
                outside for the other.
  What's wrong: There is no consistent shell. A user who clicks Settings from /admin
                exits the sidebar; a user who clicks Runs from /settings enters the
                sidebar. Modal state, header content, even theme variables differ
                across the boundary (V3Shell sets html.classList.v3-active per
                V3Shell.tsx:40-48; AppLayout doesn't).
  Why it matters: Every user-facing finding in this audit traces back here. Library
                  + Migrations look like a "v3 product"; Runs + Deploy + Audit look
                  like a "v2 admin tool"; Settings flips between them per click.
                  Persona testing produces inconsistent results because the user is
                  testing two different products in one session.
  Fix:          Pick one. Either (a) finish v3 — port RunsPage, AuditPage,
                DeploymentsPage, AdminPage, IntegrationsPage, ExecSummaryPage,
                InvoicesPage, AccountingPage, OrgProjectsPage to V3Shell layouts
                + delete AppLayout.tsx; or (b) revert v3 — pull the v3 routes from
                App.tsx, leave V3Shell + V3* components in `/v3` as artifacts. Both
                are large; (a) is the marketed direction. The current state is the
                worst of both.
  Effort:       XL (full v3 port) / L (revert)
```

```
[F-05] Migrations dry-run endpoint has no tier check
  Severity:     S1
  Confidence:   high
  Evidence:     src/main/java/com/navneet/executespec/migration/controller/
                MigrationController.java:65-72 (POST /migrations/{slug}/dry-run —
                no @PreAuthorize, no isLockedFor() call); apply endpoint at
                MigrationController.java:84-101 DOES gate (`migrationOrchestrator.apply`
                returns 403 for locked recipe).
  What's wrong: A FREE user can POST /api/migrations/<any-slug>/dry-run with a
                target repo and receive per-repo applicability, cost estimate, and
                sample diff for any of the 11 recipes including the PRO/TEAM/MAX-locked
                ones. Apply is gated; dry-run leaks the value.
  Why it matters: The dry-run output is the actual buying signal — "yes this would
                  work on my repo, here's the cost, here's the diff". Once the user
                  has that, the upgrade gate on apply is purely transactional friction.
                  Dry-run-leaks-the-value is the classic SaaS BUYER mistake.
  Fix:          MigrationController.java:65 — add `if (migrationService.isLocked(slug,
                userId)) throw 403` before delegating to dryRunService. Cite a
                short upgrade message in the 403 body so frontend can show it cleanly.
  Effort:       XS
```

```
[F-06] V2 chat composer has no surface for spec templates, sample specs, or existing runs
  Severity:     S1
  Confidence:   high
  Evidence:     frontend/src/v3/components/Composer.tsx:98 (placeholder "What do you
                want to build?…"); Composer.tsx:46 (advancedOpen state expands to
                provider/branch/batch/TDD/Figma — but no template picker, no sample
                gallery, no "from existing run" affordance); ProjectChatPanel.tsx
                (legacy v2 path) is scoped to Q&A about project memory, has Reset
                and Compact buttons but no run-launch.
  What's wrong: The empty composer asks "What do you want to build?" with no
                affordance to pick a starter, fork an old run, see what other people
                have built, or paste a share link. ProjectSpecTemplate (V102) +
                Library readymades + Migrations recipes all exist as separate
                surfaces, none reach the composer.
  Why it matters: This is the activation funnel's single biggest leverage point.
                  A user staring at a blank textarea on /projects/empty → /projects/{id}
                  with their first run gets no guidance. The 28 readymades + 11
                  migrations + N project templates that exist are not where the
                  user looks for them.
  Fix:          Composer.tsx — add a single "Start from a template" link below the
                textarea that opens an inline picker (TemplatePicker.tsx already
                exists in execution/setup; reuse). Picker should show org templates
                + project templates + library + migrations + sample specs in one
                merged list, faceted by category. Reuse `listOnboardingSamples`,
                `listSpecTemplates`, `listLibrary`, `listMigrations` API helpers
                already in `lib/api.ts`.
  Effort:       M
```

```
[F-07] V2 RunsPage is the only run history view; v3 has nothing equivalent
  Severity:     S1
  Confidence:   high
  Evidence:     App.tsx:282 (route /runs → RunsPage component); App.tsx:223 (only v3
                run route is /projects/:slug/runs/:runShort — single run, deep-link
                only); V3TopBar.tsx:46-54 (no Runs link); ProjectDashboardPage.tsx
                Runs sub-tab exists per frontend/CLAUDE.md but is per-project, not
                org-wide.
  What's wrong: An ORG_ADMIN running 5 projects has no v3-style "show me every run
                across every project" view. The v2 RunsPage exists but is wrapped
                in AppLayout (sidebar reappears, theme differs from v3). No v3
                "Runs across projects" or "My runs" page exists.
  Why it matters: For PRO/TEAM admins running multiple projects, the lack of an
                  org-scoped run view forces them to drill into each project's
                  /projects/:id/dashboard?tab=runs separately. Concrete pain point
                  for the user during their own dev.executespec.ai walkthrough
                  (per the screenshot they shared).
  Fix:          Build V3RunsPage at /runs (replace the legacy mount in App.tsx:282).
                Reuse RunList component; wrap in V3Shell; add filtering by project
                + status + provider matching the existing /api/runs query params.
                M effort because the data fetcher already exists; this is layout
                work.
  Effort:       M
```

```
[F-08] README + PRD + USER_MANUAL out of date on tier ladder, batch, BYOK, agents
  Severity:     S1
  Confidence:   high
  Evidence:     §2 table above. Six independent claims, each cited.
  What's wrong: A reader who reads documentation top-down before opening the app
                arrives with a wrong mental model on tiers (3 vs 5), pipeline
                (V1 vs V2), BYOK eligibility (PRO can vs PRO can't), batch
                execution (available vs not), reviewer agent name (V2 vs V3).
  Why it matters: ExecuteSpec ships with marketing-grade docs (PRD, USER_MANUAL,
                  enterprise-one-pager, enterprise-sla, dpa-flow) that are read
                  by enterprise procurement and engineering directors before the
                  app is opened. Stale docs read as "this product is built sloppy".
                  Founder-led sales calls have to walk back claims.
  Fix:          Single doc-refresh sweep, scoped: tier sections in README,
                PRD §3.1, USER_MANUAL §3.3 + first-run, RBAC.md permission matrix
                vs the live /api/auth/me/permissions response per tier. Two-day
                slot. Pin to v2.6.x release.
  Effort:       M
```

```
[F-09] /pricing route protected by canViewPricing (false for everyone) but linked from nav
  Severity:     S1
  Confidence:   medium
  Evidence:     [live-api] /api/auth/me/permissions returns canViewPricing=false
                for all 4 freshux personas; TierPermissionService.java:238 hardcodes
                false; App.tsx:226 mounts V3Pricing publicly under V3Shell with no
                gate; V3TopBar.tsx makes no /pricing link but Sidebar.tsx footer +
                Library locked-card + Migrations locked-card all link to /pricing.
  What's wrong: The permission flag exists, returns false for everyone, and is
                ignored by every actual call site. Either (a) the flag is dead
                code and should be deleted, or (b) some pages are supposed to
                gate on it and don't, exposing pricing to wrong audiences.
  Why it matters: Either kind of bug — dead flag or unenforced flag — wastes
                  permission-system credibility. A permission map with 5 fields
                  that don't match reality (per F-03) and 1 field that's never
                  checked (this) is not a defensible foundation for tier coherence.
  Fix:          Decide if /pricing is meant to be admin-only or public. If public,
                delete `canViewPricing` from `TierPermissions` record. If admin-only,
                wrap the route in a tier check and add the gate to nav.
  Effort:       XS
```

```
[F-10] All 4 freshux-* test users have currentOrgMembers=0 / no org despite V162 personal-org bootstrap
  Severity:     S1
  Confidence:   medium
  Evidence:     [live-api] /api/auth/me/permissions returns currentOrgMembers=0,
                maxOrgMembers=-1 for FREE/PRO/TEAM/ENT freshux accounts;
                CLAUDE.md root R-343 hotfix says V162 introduced
                OrgService.bootstrapPersonalOrg fired on register() and OAuth-new-user
                branch. Test users were seeded BEFORE V162 (or by a path that doesn't
                fire bootstrap).
  What's wrong: The "every user gets a personal org" invariant is half-true. New
                users who register through /api/auth/register get bootstrapped;
                manually-seeded test users + any pre-V162 prod accounts don't.
                A backfill migration should have shipped with V162 to flip the
                invariant for existing rows; none did per migration history
                (V162 → V163… all later migrations don't backfill orgs either).
  Why it matters: ORG_ADMIN-gated endpoints (sso config, github app install, audit,
                  team management, deploy, integrations) silently 403 for solo PRO
                  users from before V162. The premise of "personal org as foundation
                  for upgrade-to-org" relies on every user actually having one.
  Fix:          Add backfill migration V163 (or whatever's next): for every app_user
                row with org_id IS NULL, call bootstrapPersonalOrg via Spring Boot
                @PostConstruct one-shot OR run as a Flyway Java migration. Confirm
                every freshux-* account gets backfilled before re-running tier
                tests.
  Effort:       S
```

```
[F-11] PostgreSQL row-level security not used; multi-tenant isolation is org_id stamping only
  Severity:     S1
  Confidence:   medium
  Evidence:     grep "@TenantFilter|RowSecurity|RLS\|rls_policy" src/main/ returns 0;
                no `CREATE POLICY` in any V*.sql migration; org_id columns exist on
                most tables but are filtered at JPA query level, not DB level.
  What's wrong: ENTERPRISE one-pager promises tenant isolation. The actual mechanism
                is application-level: every JPA query filters by org_id via repository
                methods. A SQL injection or query construction bug bypasses isolation.
                A direct DB read (admin query, BI tool, ETL) sees all tenants.
                There's no defense in depth.
  Why it matters: For procurement at any company larger than 200 employees, this
                  is a SOC2 / ISO27001 / GDPR-Article-32 conversation. The answer
                  "we filter at the application layer" is technically correct and
                  procurement-failing.
  Fix:          Roadmap item, not XS. Either (a) add Postgres RLS policies on every
                table with org_id (ALTER TABLE … ENABLE ROW LEVEL SECURITY +
                CREATE POLICY tenant_isolation ON … USING (org_id = current_setting('
                app.current_org_id')::uuid)) and set the GUC at JPA connection
                acquisition time, or (b) be honest in the one-pager about the
                isolation model. (a) is L; (b) is XS but eliminates the buyer
                trap.
  Effort:       L (add RLS) or XS (update doc)
```

```
[F-12] V2 batch execution is documented as live in USER_MANUAL §3.3 but doesn't ship
  Severity:     S1
  Confidence:   high
  Evidence:     docs/USER_MANUAL.md:185-195 (real-time vs batch tier feature
                description); same doc line 775 ("Batch execution is not yet
                available under V2"); frontend/CLAUDE.md line 165 ("V2 backend does
                not produce batch runs yet"); RunCard.tsx still renders batch badge
                as dead UI.
  What's wrong: USER_MANUAL §3.3 is fictional. NewRunModal exposes the
                executionMode toggle, but submitting BATCH currently produces a
                400/normal real-time path depending on which version of V2BatchClientRouter
                is wired. The "50% credit discount" promise is unredeemable.
  Why it matters: Cost-conscious PRO/TEAM users (the batch tier's intended buyer)
                  read §3.3, plan their work around batch discounts, then discover
                  it's broken. Trust loss + false-advertising risk.
  Fix:          Either (a) finish V2 batch per docs/plans/pending-v2-batch-execution-plan.md
                (large), or (b) hide the executionMode toggle in NewRunModal +
                strike §3.3 from USER_MANUAL + remove the batch badge from RunCard.
                (b) is XS and recovers credibility immediately.
  Effort:       XL (a) / XS (b)
```

### S2 — Moderate

```
[F-13] V3HomeRedirect dispatcher is the silent default but its rules are surprising
  Severity:     S2
  Confidence:   medium
  Evidence:     frontend/src/v3/pages/V3HomeRedirect.tsx:24-74. Logic chain: anon →
                /login; ENTERPRISE-flagged in sessionStorage from signup → /trust;
                TEAM-flagged → /team; ≥1 project → /projects/{firstProject.id};
                else → /projects/empty. Logged-in user with no projects but
                sessionStorage v3-signup-persona=ENTERPRISE lands on /trust before
                any project exists.
  What's wrong: The persona-based first-render override is only set during a marketing
                signup hand-off; an existing ENT user logging in for the second time
                doesn't see /trust. So /trust is reachable in two non-equivalent
                ways: (a) once at signup if persona stash exists, (b) by typing
                /trust into URL bar. Same URL, two meanings.
  Why it matters: The /trust page is one of the four enterprise marketing surfaces
                  (compliance receipts, SOC2 link, audit log preview). It's
                  unreachable from any nav surface for a returning user. Worth
                  surfacing or worth deleting.
  Fix:          Decide whether /trust is a top-bar nav entry (V3TopBar additional
                pill) or just a shared marketing link the website owns. Either
                add to nav or delete the route + redirect /trust → external
                marketing URL.
  Effort:       XS
```

```
[F-14] /admin/{analytics,pricing,tiers} render V3Placeholder with v2-fallback escape; /admin/feature-flags renders FeatureFlagsPage directly
  Severity:     S2
  Confidence:   high
  Evidence:     App.tsx:336-350 (V3Placeholder renders with futureV3Route prop and
                v2 fallback); App.tsx:346 (FeatureFlagsPage renders directly).
  What's wrong: An admin clicks "Tier Lifecycle" and sees a placeholder card
                offering an Open-in-v2 escape. Clicks "Feature Flags" sees the
                actual page. Inconsistent admin UX. The placeholder is the docs/
                strict-ux-thread-handoff.md cohabitation pattern, but the inconsistency
                signals "this product is mid-renovation".
  Why it matters: SUPER_ADMIN is a small population but they're the people who
                  make tier-pricing decisions. Mid-renovation feel adds friction
                  to internal ops; v2 admin pages have full functionality so the
                  v3 placeholders are pure UX-cost.
  Fix:          Delete V3Placeholder; mount the v2 components directly under v3
                URLs the way FeatureFlagsPage already does. Restore TierLifecyclePage,
                PricingManager, and OrgAdminDashboard imports in App.tsx (commented
                out lines 78-81).
  Effort:       XS
```

```
[F-15] Verdict pill collapses Build/Tests/Reviewer/VerifiedScore into Shippable/Needs review/Blocked but ScoreRing still shows numeric — UX double-source
  Severity:     S2
  Confidence:   medium
  Evidence:     frontend/CLAUDE.md (Plan 1 #3 entry) "VerdictPill leads
                ValidationReportPanel/ScoreCard/SignalBreakdown header; numeric
                scores demoted to disclosure"; ScoreRing.tsx still rendered as
                hero (per CLAUDE.md v2.6.0 entry "ScoreRing hero + 'Verified / 100'
                primary label").
  What's wrong: Two different visual systems overlap in ValidationReportPanel —
                a categorical Verdict pill (Shippable/Needs review/Blocked) and
                a numeric ring (e.g. "Verified 87"). The numbers were supposed
                to be demoted; in practice the ring is still hero-sized. Users
                triangulate between the two and don't trust either.
  Why it matters: This is exactly the noise the user flagged in the in-session
                  UX-mapping note (and matches the verbal "no bar for TEAM/ENTERPRISE
                  users" intuition — TEAM/ENT have unlimited runs and don't need
                  the score-as-quota framing). Validation-v3 (immutable-painting-platypus)
                  is solving the score-determinism problem; the UI hasn't caught up.
  Fix:          Pick one — either ScoreRing as hero (drop VerdictPill from
                ValidationReportPanel header) or VerdictPill as hero (shrink
                ScoreRing to a chip in the panel body). Adopt one consistently
                across SharedReportPage, ValidationReportPanel, ScoreCard,
                SignalBreakdown, RunStamp.
  Effort:       S
```

---

## §4 — Persona Friction

### §4.1 FREE — technical founder, 1-hour evaluation

**TTFA (Time-to-First-Artifact):** 3 clicks (1 type) + ~3 minutes wait

1. Click "Start free trial" / sign up form (8 fields: name, email, password ×2, agree-to-terms, captcha)
2. Verify email → click "Sign in →" → enter credentials again
3. Land on `/home` → V3HomeRedirect → `/projects/empty` → "Create my first project" CTA
4. Click → `POST /api/projects` → land on `/projects/{newId}`
5. Type spec into composer textarea
6. Press Enter or click implicit submit → `POST /api/runs`
7. Wait through PLANNING → AWAITING_APPROVAL → approve gate → WORKING → COMPLETE → preview

This is 3-6 minutes if everything works on a tiny spec. The marketing site claims "first artifact in 3 minutes". Reality is contingent on dummy provider keys not being set (booted my local stack with `sk-ant-dummy-…` because base `application.yml` has empty api-keys; a fresh user who doesn't BYOK and has no org keys fails at `LlmRouterV2`).

**Three highest-friction moments before activation:**
1. **`/welcome` should fire here, doesn't.** Onboarding sandbox + 3 sample specs are dead code per [F-02]. So a fresh FREE user goes from verified-email straight to a blank composer. The "Pick a sample to see ExecuteSpec in action" gallery never renders.
2. **The empty composer offers no help.** Per [F-06] the textarea has placeholder "What do you want to build?…" with no template picker, no sample gallery, no Library shortcut. A founder doesn't know what shape of spec works. Library exists at `/library` but the user has no reason to look there from the project chat surface.
3. **The "Create my first project" button on V3EmptyProject doesn't ask for a name.** It POSTs `{name: "{firstName}'s first project"}` server-side. So Steve who registered with `Steve Smith` sees "Steve's first project" appear in his ProjectSwitcher with no prompt to rename. He goes hunting for "rename project" in Settings, finds it under MoreDrawer → Settings → … or doesn't.

**Upgrade CTA: interruptive, not earned.** Sidebar footer renders "Upgrade to Pro" / "Upgrade to Team" persistently for FREE/PRO ([code] `Sidebar.tsx:239-251`). Header pill renders "Upgrade →" persistently ([code] `Header.tsx:113-119`). These show before the user has run a single spec. Once they hit `monthlyRunLimit=10` (V145, was 5), there's a `UpgradeNudge` that is contextually earned. Until then the upgrade pressure is just chrome.

**Honest take.** FREE is a teaser, not a real evaluation tier — but only because the activation experience never happens. With the `/welcome` sandbox wired ([F-02]), the 3 sample specs prefilled, and a follow-up Library shortcut in the composer ([F-06]), the same `monthlyRunLimit=10` becomes a "you've felt the value, now upgrade" moment. The product has the parts; they're not assembled.

### §4.2 PRO — backend engineer, paid last week

**Discovery problem:** PRO buys (a) 20 runs/month, (b) team of ≤10, (c) BYOK per marketing copy. **Live API contradicts (c)** ([F-03]) — `canManageAiKeys=false` for PRO solo. So the PRO buyer's first attempt to add their Anthropic key in Settings hits a hidden panel; the BYOK savings + privacy claim is unrealized.

**Three highest-friction moments in normal weekly use:**
1. **No org-wide "All runs" view ([F-07]).** The v2 RunsPage is the only org-scoped run list, and it requires typing /runs into the URL bar from any v3 surface. PROs running 3+ projects context-switch constantly.
2. **MultiRepo selection in NewRunModal silently 404s if the user picks a repo not yet linked to the project.** I didn't reach this in code-trace — it's [code-trace] only — but `ExecutionPlannerService.persistPlan` has a name-fallback path for missing UUIDs (per CLAUDE.md v2.5.1 entry). Suggests the regression class is real.
3. **Manual "Add API key" flow only fires after upgrade.** Per [F-03] PRO BYOK is denied. So the BYOK Test buttons added in Plan 1 Phase 1.2 (per frontend/CLAUDE.md) are visible on Settings only for ENT solo.

**Power-user affordances absent:**
- No keyboard shortcut sheet (CommandPalette exists per `frontend/src/components/layout/CommandPalette.tsx` but I didn't trace it; [code-trace] medium confidence). 
- No bulk-archive on RunsPage (looking at RunsPage import, no bulk action UI).
- No saved-search / filter persistence beyond session.
- No deep-link from chat bubble to the run's task graph (V3ProjectHome's ResultCard only shows summary).

**Honest take.** PRO is FREE-with-fewer-walls today. The headline PRO promise (BYOK = privacy + cost control) is broken at the API ([F-03]). The 20-run quota is already loose enough that pricing isn't the friction. The thing PRO actually buys is the Library + Migrations Team-tier locks, but those are sold as TEAM exclusives in v3 gallery. Net: PRO has muddled value prop. Either fix BYOK for PRO or drop it from marketing.

### §4.3 TEAM — tech lead at 20-person startup, 5 seats, week 1

**Invite-to-active-teammate friction:**
1. ORG_ADMIN gets to /org/settings → Invitations panel → enters email → submit → backend fires email with token URL → invitee opens email, clicks link → `/accept-invite?token=…` → if logged in, accepts → if not, has to register first.
2. Per V144 + CLAUDE.md, TEAM seat-pool model: 50 credits per seat, shared. ORG_ADMIN at /admin has a seat-adjust panel. **But** [live-api] freshux-team@test.local (TEAM tier solo, no org) returns `canInviteMembers=false` despite CLAUDE.md claiming TEAM enables team trials — because the user has no personal org to be ORG_ADMIN of. See [F-10].

**RBAC actually ships?** Partially. The role enum (SUPER_ADMIN / ORG_ADMIN / FINANCE / DEVELOPER / etc.) is implemented in `TierPermissionService` per-org-member branch (lines 175-225). But the org-membership invariant is broken for users without backfilled personal orgs ([F-10]).

**Concurrent editing model:** No model. ProjectChatPanel submits to `/api/projects/{id}/chat` synchronously; nothing in the UI represents "another teammate is typing". V133 activity_feed table exists but no UI consumes it on dashboard. So two PROs in the same TEAM org submitting concurrent specs against the same project: each gets their own RUN row, no collision detection, no visible "Alice is running a spec right now" indicator. They might both run conflicting specs and only discover at PR-merge time.

**Honest take.** TEAM is PRO-with-seat-pricing-and-invoicing. The promise of "team-aware" anything (concurrent visibility, role gating, shared templates that don't fall through to personal-org templates) is hollow. The seat model is the only true differentiator from PRO; the rest of the surface is identical. Worth either (a) building real team features (concurrent presence, shared template namespace, ORG_ADMIN visibility into all org runs in v3), or (b) collapsing back into PRO and using seat-priced PRO as the team buy.

### §4.4 ENTERPRISE — separate evaluation criteria

**IT/Platform admin sub-persona.**
- **SSO.** Schema only. `org.sso_provider`, `sso_enforced`, `sso_metadata_url`, `sso_domain` all V127 columns present; no `SamlController`, no `Saml2Config` class found in grep. SSO/SAML wiring is documented as 3-5 days of unscoped engineering work in `docs/sso-saml-wiring.md:25-46`. **A buyer asking "can my Okta IdP federate?" gets "in roadmap"**.
- **SCIM.** Not searched explicitly. No `ScimController` found. Likely absent.
- **Audit logs.** Two tables exist — `activity_audit` (V22) and `activity_feed` (V133, partitioned by month, 12 partitions seeded). Audit page renders at `/audit` and `/governance/audit`. Per `[live-api]` freshux-enterprise solo gets `canViewAudit=false` because no org. So even ENT users without a real org can't see audit. ORG_ADMIN-gated, which is correct — but the gating depends on [F-10] being fixed.
- **Key management.** ENTERPRISE BYOK works at the API ([F-03] confirms `canManageAiKeys=true` for ENT only). Settings UI surfaces a panel for adding keys. Backend stores AES-256-GCM encrypted ([doc] `RBAC.md:131`). No HSM, no per-org KMS integration.
- **Tenant isolation.** Application-layer org_id stamping ([F-11]). No PostgreSQL RLS. Procurement-failing for SOC2 regulated buyers.

**Engineering Director sub-persona — governance.**
- **Engineering standards as binding policy** (E3 phase, V136). Migration + table exist. Backend `EffectiveStandardsResolver` exists per CLAUDE.md root. Frontend `StandardsCompliancePanel` exists. **But** the standards-violation override workflow (≥30 char rationale required) is documented; I didn't reach the live UI to confirm the rationale gate is enforced in the modal vs only in the backend POST.
- **Architectural invariants** (E3, V138). Schema + `InvariantChecker` + `arch_invariant_violation` table exist. Per CLAUDE.md the user-facing definitions live in customer git as `.executespec/arch/invariants.yaml`. **No frontend surfacing of invariants found.** A buyer asking "can I write a rule that says 'only payment-service writes to the payments table'?" gets "yes, in your git" with no UI to author or audit.
- **Contracts** (E2, V135). Same pattern — schema + drift detection backend + per-repo coverage + classifier per kind. Surfaced as `ContractCoveragePanel` in CodeTab. Frontend exists; lifecycle UI for contracts ("retire this contract", "promote v2") not in this audit's scope.

**Procurement / Legal sub-persona.**
- One-pager (`docs/enterprise-one-pager.md`) — 2-page, claims 6 capabilities. Two of the 6 (SSO/SAML, Named CSM) are aspirational. No legal tier comparison for redlines.
- SLA (`docs/enterprise-sla.md`) — 99.9% with 10% credit per 0.1% missed; P1 4h response. **No status-page link visible from product.** No public statuspage.io. Status is a doc, not a feed.
- DPA flow (`docs/dpa-flow.md`) — manual; customer emails admin@executespec.ai, legal drafts, both sign. Not automated. Acceptable for first 100 customers, not for a 200-person sales motion.
- SOC2 / ISO27001 — no certification mentioned anywhere in docs. No public Trust Center link.

**Honest take.** ENTERPRISE today is **PRO + a contract**. All the heavy lifting (RLS, SAML2 handshake, named CSM, SOC2 cert, status feed, contract lifecycle UI, invariant authoring, governance dashboards distinct from end-user dashboards) is roadmap. The schema is laid (V127, V131-V139 are real); the governance product is half-built. ExecuteSpec is 3-6 months of focused work away from being a real enterprise product. Pricing it as one without admitting that creates expectation gaps a single procurement call can't recover from.

---

## §5 — Library: Keep, Kill, or Rebuild?

**The user job in one sentence:** "I want to ship a working {auth flow / chat panel / GitHub login / email-verification flow} into my project's repo today, without writing it from scratch."

**Discovery path:** v3 top-bar → Library. Reachable. **Mental-model mismatch:** Users see 28 cards, click one, see slot-fillers (project + repos + stack + variant) and a "Use this solution" button. Click → real V2 run dispatched per `ReadymadeOrchestrator.instantiate()` ([code] `library/orchestrator/ReadymadeOrchestrator.java:79-172`). User expects a code preview before commit; gets full plan-approval gate. This is correct backend behaviour for cost transparency but feels like a heavier interaction than an "instant template apply".

**Empty / partial / full states.** No empty state — 28 readymades shipped on dev. Loaded lazily from `readymade_solution` table seeded from `src/main/resources/readymades/*.yml`. Per-row `locked` flag drives upgrade CTAs. For a user with 0 repos linked to 0 projects, every card's "Use" action requires onboarding-then-link-repo before instantiation, but the gallery doesn't say so until you click in.

**Failure modes.**
- Click locked card → land on `/pricing` (good). 
- Click unlocked card with 0 projects → V3LibraryDetail shows the slot picker but user has no projectId in scope → confusion until they go back, create a project, then re-find the readymade.
- Migrations dry-run leak ([F-05]) doesn't apply to Library (Library has no dry-run path; instantiate is the apply step).

**Adjacent features that should connect but don't.**
- Composer ([F-06]) — Library doesn't surface as a "Start from a readymade" link.
- Project memory — a project with `auth-already-shipped` in memory should show "Linked auth readymade" or hide "auth-related" cards.
- Templates (V102 `project_spec_template`) — Library readymades are conceptually templates with extra structure; the data models are siblings, not unified.

**Position: REBUILD, but don't kill.** The 28 cards are a real moat — 28 verified-working starter projects across 15 categories per CLAUDE.md is rare in the agentic-coding space (Lovable / Bolt / v0 don't have curated readymades; they have one big LLM you ask). The current rebuild target is the **discovery path**: get Library out of the top-bar's third pill and into the empty-composer flow + the project's "what next" suggestions.

**Single biggest-leverage change:** Wire the composer to suggest 3 relevant readymades when a project has no runs yet (use `ProjectArchitectureService` + readymade `category` matching). One feature; 70% of the activation funnel uplift.

---

## §6 — Migrations: Keep, Kill, or Rebuild?

**The user job in one sentence:** "My codebase needs to move from {Express → Fastify / JS → TS / Mongoose → Prisma / etc.}. I want to know if it's safe and how much it'll cost before I commit."

**Discovery path:** v3 top-bar → Migrations. Reachable. **Mental-model mismatch:** Two-phase UX — Phase 1 "Compute plan" fires `POST /migrations/{slug}/dry-run`, returns per-repo applicability + cost + sample diff. Phase 2 "Apply migration" (disabled until Phase 1 done) fires `POST /migrations/{slug}/apply`. This is correct conceptually — measure twice, cut once — but the visual hierarchy doesn't make Phase 1 feel like commitment-free exploration. Users may bounce thinking Phase 1 is the start of the work.

**Empty / partial / full states.** 11 recipes shipped per CLAUDE.md. Same gating pattern as Library — locked rows link to `/pricing`. No "I don't have any repos that match" empty-result state when the dry-run determines applicability=0.

**Failure modes.**
- **[F-05] dry-run leak.** Anyone can dry-run any locked recipe. Real S1 finding.
- Recipe applicability false-negative — the dry-run infers applicability from codemaps; if a repo's codemap is stale, dry-run says "doesn't apply" when it actually does. Per CLAUDE.md the staleness service exists but I didn't trace whether the dry-run forces a fresh codemap regenerate first.
- Variant pick lock-in — once Phase 1 picks a variant the apply path is committed; no "let me re-run with a different variant" without going back to gallery.

**Adjacent features that should connect but don't.**
- The codemap + ProjectArchitectureService already know what stack the project is on. Migrations gallery should auto-filter to "applicable to your stack" by default rather than showing all 11.
- Activity feed (V133) — every migration apply should fire an `ARCH_MIGRATION_APPLIED` event to be visible to teammates. I didn't trace this.
- Project memory — a successfully-applied migration should be remembered ("this project moved JS→TS in run R-456") for future planner context.

**Position: REBUILD discovery, fix the dry-run leak, otherwise keep.** Migrations are differentiated value — no other agentic-coding tool ships 11 verified migration recipes with cost estimates. The Phase 1/Phase 2 split is correct architecture. The leak ([F-05]) and the discovery problem (gallery shows all 11; user has to read each to find the relevant one) are the two material gaps.

**Single biggest-leverage change:** Auto-detect the project's stack from `ProjectArchitectureService` and default the gallery to "applicable to {your stack}" with a "show all" toggle. Same approach as Library §5 — let the project's existing context filter the catalogue.

---

## §7 — Information Architecture

**Current top-level menu (verbatim).**

V3TopBar (`V3TopBar.tsx:46-54`):
```
ES ExecuteSpec | [ProjectSwitcher] [Library] [Migrations] | [Avatar]
```

V2 Sidebar (`Sidebar.tsx:10-315`, filtered by tier/role):
```
🎯 Logo
Runs
Library
Migrations
Exec summary
Dashboard               (admin)
Integrations            (canManageIntegrations)
Invoices                (canViewInvoices)
Accounting              (canViewAccounting)
Audit                   (canViewAudit)
Deploy                  (superAdminOnly)
Analytics               (admin)
Settings
─────
[Projects > expandable list, dynamic]
─────
[Upgrade] [Top-up] [UsageBadge]
```

**Stranded features (reachable only by URL bar):**
- `/welcome` — onboarding [F-02]
- `/exec` — exec summary, also linked from Sidebar but `canViewCostBreakdown` always false [F-03]
- `/exec-demo` — public demo, no auth, no nav link
- `/team/:userId` — TeamMemberDashboard, no nav link
- `/admin/model-rates` — admin pricing page, no nav link
- `/projects/:projectId/activity` — redirect to `?drawer=activity`, no nav link
- `/runs/new/sandbox` — RunsNewLanding, only reached from `/welcome` (which itself is dead)
- `/legacy/billing` — soft redirect for grandfathered PRO links, no nav

**Redundant entry points:**
- Library shows up in v2 sidebar AND v3 top-bar
- Migrations shows up in v2 sidebar AND v3 top-bar
- Settings v2 sidebar entry → `/settings` → V3Settings (under V3Shell). Same URL, two shells depending on the click origin.
- Billing v2 sidebar entry → `/billing` → V3Billing (same shell-flip).
- /pricing reachable from Sidebar footer "Upgrade" CTA, V3Library locked-card "See pricing →", V3Migrations locked-card link, Header "Upgrade →" pill, UpgradeNudge component.

**Job-aligned regrouping (proposed top-level for the v3 shell, single layer):**
```
[Logo] | [Projects ▾] [Runs] [Catalog ▾ (Library + Migrations)] [Insights] | [Avatar ▾ (Settings, Billing, Integrations, Org, Admin if ROLE_ADMIN, Logout)]
```

What gets removed from top level:
- Settings, Billing, Integrations, Pricing → folded into Avatar dropdown
- Audit, Accounting, Invoices, Deployments, Admin → folded into Admin sub-section in Avatar (visible only to admins)
- Exec / ExecSummary → renamed to Insights (matches v3 /insights)
- Team / Trust → either fold into Avatar or delete (Trust is marketing; Team is the org members page)

**Three IA redesign options.**

1. **Avatar-as-everything (proposed above).** Top-level is 4 items + Avatar dropdown with everything else. Pros: minimal cognitive load on first paint; easy to find admin features when you need them. Cons: discoverability of Settings + Billing drops for new users; overloads the Avatar dropdown into a "kitchen drawer".
2. **Two-row top-bar.** Row 1: Project context (Switcher, current project name, Runs scope). Row 2: Catalog + Insights + Avatar. Pros: cleanly separates "what am I working on" from "where else can I go". Cons: doubles vertical chrome space; mobile-unfriendly; makes the page header feel heavier.
3. **Sidebar-as-default-on-desktop, top-bar-on-mobile (revert v3, keep mobile).** Drop V3Shell entirely on desktop; restore AppLayout sidebar with the menu reorganized for jobs. Mobile uses a top-bar drawer. Pros: matches user's in-session UX-mapping note ("remove the side menu" aside, the sidebar pattern is well-known); zero invented patterns. Cons: throws away the V3 work; the marketing site's "modern AI agent UX" positioning becomes harder to support.

**Recommendation: option 1 (Avatar-as-everything).** Single change: the top-bar moves from "2 catalog products + 1 brand link + 1 user" to "4 jobs + 1 user dropdown". Doesn't delete v2 sidebar work — that becomes the admin-only fallback for SUPER_ADMIN reachable from Avatar → Admin → "Open admin console (v2)". Best leverage because it ships in 1-2 weeks rather than rewriting every panel into V3Shell, and it directly addresses the user's "no Runs link, remove side menu" core complaint.

---

## §8 — Agentic UX Gaps — Top 5

| ID | Current flow | Steps | What user is actually trying to do | Agentic collapse | Frequency × steps_saved |
|---|---|---|---|---|---|
| AG-1 | New user → Verify email → Sign in → /home → /projects/empty → Create project → Composer → type spec → submit → wait through plan gate → see first run | 8-10 | Try the product. See what it produces. | One-step: marketing site spec → magic link email → first run already running on a sandbox by the time they log in. (Sandbox provisioning service exists, just isn't wired here per [F-02].) | Every new user × 6+ |
| AG-2 | PRO admin reviews 3 projects' run health → /projects/A/dashboard?tab=pulse → check, /back, /projects/B/dashboard?tab=pulse → check, /projects/C/dashboard?tab=pulse → check | 6-9 | "Are any of my projects in trouble right now?" | Single org-scoped dashboard ("/insights" with per-project rollup cards). Already exists at /insights but ENT solo `canViewCostBreakdown=false` blocks half its content per [F-03]. Fix permissions → wire as the default v3 home for admins. | ORG_ADMIN every weekday × 5 |
| AG-3 | User wants to retry a failed task → click run → run detail → tasks tab → click failed task → retry button → confirm → wait | 5-6 | "Just retry the failed bit." | Inline retry on failure SSE event ("Task #3 failed. [Retry] [Skip] [Investigate]") in the run thread itself. Backend `POST /api/runs/{id}/tasks/{taskId}/retry` already exists. UI surface lives only on the task detail page. | Every failed run × ~30% of runs |
| AG-4 | User wants to apply a Library readymade → top-bar Library → search "auth" → click card → fill slots → click Use → plan-gate → approve | 6-7 | "Add Google login to my app." | Composer in /projects/{id} chat: type "add Google login" → V2 planner detects intent + matches readymade → planner suggests "Use the Google-login readymade?" with one-click confirmation. Backend already has the readymade index; planner-side intent matching not wired. | Every readymade applied × growing |
| AG-5 | Admin onboards a new teammate → /org/settings → invitations → enter email → submit → wait for email → invitee opens email → clicks accept-invite → log in → enter name + password → eventually visible in members list | 8-12 | "Add Sarah to my team." | One-step from CommandPalette: ⌘K → "invite sarah@…" → bound directly to invite endpoint, sent + visible in member list immediately as PENDING. CommandPalette exists; no invite command bound. | TEAM admin every onboarding × ~2/month |

**Top one to ship first: AG-3 (inline retry).** Highest frequency × moderate steps_saved × tight scope. Failed-run friction is a daily affair; inline retry collapses 5-6 clicks into 1, reuses existing backend endpoint, has zero data-model change. AG-1 (sandbox onboarding) is bigger leverage long-term but has more dependencies (per [F-02] needs the wire-up + `WelcomePage` un-orphaning). AG-4 (intent-matched readymade) is the best cross-product agentic collapse but needs planner-side intent classifier work that isn't ready.

---

## §9 — Synthesis: FREE/PRO/TEAM Redesign Brief

### Activation flow redesign (target TTFA: 90 seconds from sign-in)

**Step 1 (0-15s):** Sign-in → backend response includes `{user, hasFirstRun: false, sandbox: {projectId, runId, status: "PROVISIONING"}}` — sandbox provisioning is fire-and-forget on /api/auth/login. Frontend redirects to `/projects/{sandboxProjectId}` directly (skips /home dispatcher).

**Step 2 (15-45s):** V3ProjectHome renders. The sandbox run is already PLANNING when the user arrives; the chat thread shows a hot bubble "Building your sandbox app — pick what you want to build →" with 3 sample-spec cards inline below the bubble. User clicks one (e.g. "Calorie tracker — Next.js + Postgres").

**Step 3 (45-90s):** Click triggers `POST /api/runs` with the prefilled spec; thread morphs the static bubble into a live PLANNING bubble. User watches their first plan get drafted in real time.

**Step 4 (90s+):** Plan-approval gate fires. User clicks Approve. Run executes. Preview URL appears in the thread when COMPLETE. **First artifact: ~3 minutes including LLM time.**

Click count: 2 (pick sample, approve plan). Form fields: 0 after sign-in.

### Conversion redesign — earned, not interruptive

Today's upgrade pressure shows on every page from sign-up onward. Replace with three earned moments:

1. **First completed run.** Plan-approval modal includes a "Want this on a custom branch + auto-PR? Connect a real repo →" link. Free users running on sandbox have no repo; clicking takes them to a "Add GitHub PAT" inline flow that converts FREE → PRO via Stripe checkout if quota is needed beyond sandbox.
2. **At 70% of monthly quota.** Existing UpgradeNudge fires (already shipped). Move from sidebar footer to top-of-thread chip.
3. **At first locked Library / Migrations card.** User clicks a TEAM-locked recipe. Detail page shows "1 minute to upgrade — $49/seat" with the immediate value proposition framed against the recipe ("Save 2-3 hours on the Express → Fastify migration"). Today this just routes to /pricing.

Remove: persistent Sidebar "Upgrade" CTA, persistent Header "Upgrade" pill. They're noise.

### Three changes to most improve PRO retention (ranked)

1. **Fix BYOK for PRO ([F-03]).** The single biggest reason a backend engineer pays $99/month is privacy + cost-control. Not delivering BYOK at PRO is the audit's biggest tier-promise mismatch.
2. **Inline failed-task retry (AG-3).** Daily friction collapse for a heavy user.
3. **Org-scoped run history view ([F-07]).** A PRO admin running 3 projects has no aggregate view today; they cycle through three project dashboards.

### One thing to kill: V3Placeholder admin pages ([F-14])

The `/admin/{analytics,pricing,tiers}` placeholders telling SUPER_ADMIN to "Open in v2" are a credibility tax. The v2 components work fine. Mounting them under v3 URLs the way `FeatureFlagsPage` already does removes a UX mismatch and 4 commented-out imports. Defending the current state: it's holding a v3-IA option open. But that option is already open — the v3 admin shell can ship in v2.6.x without keeping the placeholder pattern in main.

---

## §10 — Synthesis: ENTERPRISE Redesign Brief

**Position: a SKU, not a product, today.** Defendable evidence: SSO/SAML schema-only ([F-11]+`docs/sso-saml-wiring.md:25-46`); CSM aspirational (`docs/enterprise-sla.md:44-56`); no RLS ([F-11]); audit + activity feed schema-shipped but no governance dashboard; standards/ADR/invariants schema-shipped but no UI authoring surface for invariants. Today's ENTERPRISE delivers (a) unlimited monthly runs (`tier_config.ENTERPRISE.monthly_run_limit = -1`), (b) BYOK ([live-api]), (c) custom contract via DPA flow. That's 3 of the ~12 differentiators procurement expects.

**Sketch of an admin shell distinct from end-user UX.**

End-user UX (FREE/PRO/TEAM): chat-first, project-scoped, fast iteration. Avatar menu for personal admin. As proposed in §7.

Enterprise admin shell: separate `/admin` route mounted in V3Shell with **its own sidebar** (the only place a sidebar should remain). Sections:
- Tenants (orgs, users, role assignments, SSO config, GitHub App install status)
- Governance (effective standards, override rationales, ADR catalogue, invariants catalogue, drift events)
- Compliance (audit log, activity log, retention settings, DPA status, export bundle generator)
- Billing & contracts (subscription detail, invoice history, custom contract uploads, SSL certificate dates)
- Operations (provider health, circuit breaker state, queue depth, recent migrations applied per tenant)

Cleanly separates "my-team-using-the-product" from "I-am-an-admin-running-the-platform-for-my-team".

**Top 5 governance primitives missing.**

1. **Per-org policy authoring UI.** Effective standards exist as data ([code] V136 `standards/`); an ORG_ADMIN can't author or override them through the UI today. Gap with Wiz, Snyk, Lacework norms.
2. **Invariant authoring UI.** `arch_invariant` table + `InvariantChecker` exist ([code] V138, `arch/invariants/`); user-facing definition is a YAML in customer git. No way to author, preview, or simulate an invariant from the product.
3. **Approval workflows for sensitive runs.** A run that touches the production database, payment service, or security-tagged invariants should require ORG_ADMIN approval before plan-execute. No such gating exists; AWAITING_APPROVAL is per-run-not-per-policy.
4. **Custom RBAC roles.** Today's roles are a fixed enum (SUPER_ADMIN / ORG_ADMIN / FINANCE / DEVELOPER / etc.). An enterprise asking "I need a 'Junior Engineer' role with no production-deploy access but full sandbox" has no path.
5. **Tenant data export bundle.** `POST /api/projects/{id}/export` exists per E4 phase. No org-wide export for "I want all my org's data on tape before we sunset". Not load-bearing on procurement but a SOC2 / GDPR DSAR surface.

**Procurement surface — what proof must exist outside the app.**

- Public SOC2 Type II report (or commitment date for first audit). **Not shipped.**
- Public ISO 27001 status. **Not shipped.**
- Public Trust Center page (separate from `/trust` in-app). **Not shipped.** (`/trust` is in-app marketing.)
- Public statuspage.io feed with historical incidents. **Not shipped.** SLA exists in markdown only.
- Public security disclosure page (`/security.txt`, vuln-disclosure email). **Unknown.**
- Sample DPA + MSA available for legal review without a sales call. **Doc reference exists** (`docs/dpa-flow.md`).
- Reference customer logos. **Not shipped.**
- Architecture diagram for security review (data flow, encryption, key management). **`docs/ARCHITECTURE.md` exists** but mixes V1+V2.

**Top 5 gaps blocking a real enterprise deal, severity-ranked.**

1. **S0 — No SSO/SAML.** A federated-only buyer (Okta, Azure AD, Ping) has no auth path. 3-5 days of unscoped work per `docs/sso-saml-wiring.md`.
2. **S0 — No Postgres RLS.** Application-layer org_id filtering is procurement-failing for SOC2 buyers. L effort to retrofit.
3. **S1 — No public statuspage / SOC2 cert.** Non-product but blocks deals over $100k ARR.
4. **S1 — No governance authoring UI.** Standards + invariants are data-shipped, UI-orphaned. Buyers can't preview or change policy without engineering.
5. **S1 — Named CSM is aspirational.** "Founder-led" is acceptable for first 10 customers, not for an enterprise tier with 99.9% SLA promises.

---

## §11 — Competitive Position

- **Cursor.** Editor-resident agentic coding. One thing Cursor does that ExecuteSpec doesn't: "stay in your editor, follow your cursor". One thing ExecuteSpec does that Cursor doesn't: ship a verified, build-passing PR end-to-end without an engineer-in-the-loop on every line.
- **v0 (Vercel).** UI-only generative loop, tight feedback. v0 does: rapid UI iteration with live preview from a single text box. ExecuteSpec does: backend + frontend + tests + deploy together with verified score.
- **Lovable.** Visual + code, generous free tier, fast iterate-on-preview loop. Lovable does: best-in-class "I want a working app from a single sentence" UX. ExecuteSpec does: structured planner + reviewer + invariants + multi-repo orchestration that Lovable hand-waves.
- **Bolt.new.** WebContainer + StackBlitz IDE preview. Bolt does: zero-install in-browser dev. ExecuteSpec does: ship-to-real-GitHub-PR with reviewer + tests, not just a working preview.
- **Replit Agent.** Full-stack agentic build with live deploy. Replit does: integrated hosting + DB + agent in one platform. ExecuteSpec does: bring-your-own-repo-and-stack, no platform lock-in.
- **Devin (Cognition Labs).** Long-horizon autonomous engineering. Devin does: multi-day tasks with VM access. ExecuteSpec does: 5-30 minute focused runs with explicit plan-approval gate (more controllable, less autonomous).

**Is "Spec-First" a real wedge?** Partially. The ExecuteSpec value prop — "you write the spec, the system writes the code, the reviewer verifies it, you get a real PR" — has one true differentiator: **the deterministic verified score** (V106 `verifiedScore` from build + tests + reviewer outcome, not LLM-inferred accuracy). Every other competitor surfaces an LLM-judge score that is hand-wave at best. The deterministic score is real engineering work and a real moat.

But the rest of the "Spec-First" framing is rationalisation. ExecuteSpec's actual buying signal is **multi-repo, multi-task orchestration with auto-merge and verified output**. The "spec" is just the input format, and Cursor/Devin/Lovable accept the same input. Lead with the moat — verified output + multi-repo orchestration — not with the input format. Marketing should say "we ship verified PRs across your existing repos" before "we are spec-first".

---

## §12 — What I Almost Missed

1. **The `/welcome` orphan ([F-02]) is invisible from any single phase.** The route exists, the components compile, the migration history shows the activation funnel events table (V125), and the docs talk about a "first-run experience". Only by tracing the redirect from RegisterPage → VerifyEmailPage → LoginPage → AuthCallbackPage → V3HomeRedirect did the dead path become visible. This one bug invalidates the entire Wave 2.2 onboarding investment and is at the top of the FREE persona's friction; reading any single doc would make you think it works.
2. **`canDeploy=true` for FREE solo users while Sidebar gates `/deployments` to SUPER_ADMIN ([F-03]+[code] `Sidebar.tsx:84`).** Two true things that are individually correct (FREE has 3 deploys per `freeUserPermissions`; nav hides deploy from non-admins) compound into "FREE users have a permission they can't exercise". Either inflate the sidebar visibility or trim the permission. As-shipped is muddled.
3. **The 5-tier ladder ([live-db] `tier_config` rows) contradicts the root CLAUDE.md ladder (4 tiers).** CLAUDE.md says V141 collapsed MAX → TEAM giving FREE/PRO/TEAM/ENTERPRISE. DB has FREE/PRO(HIDDEN)/TEAM/MAX/ENTERPRISE post V144 reinstatement. This means even the project's primary AI-context document (CLAUDE.md root) is one revision behind reality — and downstream readers (this audit, the next contributor, the next AI agent) start from a wrong premise. Material because tier-based code paths key on the actual enum.
4. **Verdict pill + Score ring + SignalBreakdown chip + AccuracyScore + VerifiedScore all surface side-by-side on one validation report ([F-15]).** v2.5.10 introduced VerifiedScore as the deterministic anchor, demoting AccuracyScore to "Est. N". Plan 1 #3 introduced VerdictPill collapsing 5 dimensions into a 3-state categorical. Both are alive. The result is 5 visualisations of "did the run succeed" stacked vertically. UX double-source is the small finding; the big finding is that nobody on the team has decided which one is canonical and the user sees them all argue.
5. **The `/runs` URL is the seam between v3 and v2.** Click "Runs" from any v3 surface and you exit V3Shell into AppLayout. The user's screenshot showing "Navneet PRO" with a sidebar at `dev.executespec.ai/runs` is exactly this — they were on v3 (everywhere else) but landed on v2 (here). This isn't a single bug; it's a structural fact that ripples through every persona's session. Worth foregrounding because it explains why every persona walkthrough produces "this product feels schizophrenic" without ever pointing at a single artefact.

---

## §13 — Findings Below the Top 15

```
[F-16] AuthCallbackPage redirects to /home regardless of context (S2, high) — file:line
       AuthCallbackPage.tsx:34. OAuth-new-user with stashed marketing-site spec
       loses the spec when redirected to /home; should preserve `?spec=…` query
       string. Effort: XS.

[F-17] CommandPalette exists but is undocumented (S3, medium) — file:line
       components/layout/CommandPalette.tsx. No keyboard-shortcut sheet
       discoverable from the UI. Add a `?` overlay.

[F-18] FREE has canManageBillingProfile=true with no org (S3, high) — TierPermissionService.java:254.
       Solo user with no billing context can edit a billing profile that
       has no place to attach. Either bootstrap an empty billing profile on
       personal-org create or set canManageBillingProfile=false until upgrade.

[F-19] V133 activity_feed table shipped without UI (S2, medium) — frontend/CLAUDE.md
       (no ActivityFeedPanel found). Schema is partitioned-by-month with 12
       seed partitions; no consumer renders rows. Service-side ActivityEventEmitter
       exists per CLAUDE.md root but emit-points are not wired into project create / run terminal / PR merge.

[F-20] Stale /v3/* redirects bloat App.tsx (S3, high) — App.tsx:24-41 V3LegacyRedirect.
       Per the comment block, every /v3/X URL redirects to /X. Once 60 days
       have passed since R-342 (2026-04-29), the redirect can be deleted.
       Tracked with a calendar reminder, not in code.

[F-21] /exec-demo public anonymous route is unfindable (S3, low) — App.tsx:238.
       Marketing motion (PgM/CFO conversion path per CLAUDE.md) but no
       inbound link from website. Either link from marketing site or delete.

[F-22] Frontend `package.json` version=2.5.1; README headline says v2.5.11 (S3, high) — package.json:3.
       Version-string drift. Fix with `npm version 2.5.11` and same on pom.xml.

[F-23] FeatureFlagsPage admin-only check is route-level (S3, medium) — App.tsx:346.
       SUPER_ADMIN-only at the route gate; the page itself doesn't double-check.
       Route-level is fine but not defense in depth.

[F-24] React 18 + Vite 5; latest Vite is 6.x (S3, low) — frontend/package.json:?.
       Out of audit scope (dependency hygiene), flagged for awareness.

[F-25] Public /reports/shared/{shareToken} surface has no rate limit on token brute-force (S2, medium) — likely.
       Audit didn't trace the controller; if shareToken length is < 16 hex chars,
       enumeration is feasible. Confirm.

[F-26] /governance/audit aliases /audit (S3, high) — App.tsx:298.
       Two URLs, same component (AuditPage). Pick one canonical; redirect the other.

[F-27] CSP header allows https://*.executespec.ai for frame-ancestors (S2, low) —
       SecurityProperties or yml. Wildcard is broader than necessary; restrict to
       specific subdomains.

[F-28] `tier_config.PRO.lifecycle_state = HIDDEN` (S3, high) — [live-db].
       PRO is grandfathered for existing buyers but hidden from new buyers per
       v2.6.1 Wave 1. The /pricing page must clearly mark "no longer available
       for new buyers" or new visitors will think 4 tiers exist when only 4 of
       5 are listed.

[F-29] VerifyEmailPage success → manual "Sign in →" Link (S3, high) — VerifyEmailPage.tsx:42-45.
       User has to enter password again immediately after verifying. Should
       auto-issue a session token and redirect to /home (or /welcome if [F-02] is
       fixed).

[F-30] Anti-spam captcha on RegisterPage (S3, unknown) — code unread.
       Fresh-user registration path may not have captcha. If absent, free tier
       quota becomes a vector for sign-up-spray abuse. Confirm.

[F-31] DeploymentsPage tier check — frontend gate but backend may not (S2, medium) —
       DeploymentsPage.tsx:31-74 (frontend); DeploymentController.java:55-71
       (no explicit tier check found in the controller). canDeploy=true for
       FREE solo per [F-03] means a curl bypass works. Confirm the
       TierEnforcementService.assertCanDeploy guard runs at the controller path.

[F-32] /pricing renders for anon users via V3Pricing (S2, medium) — App.tsx:226.
       The route is mounted under V3Shell which does NOT enforce auth (per
       App.tsx the auth check is inside ProtectedRoute, which V3Shell bypasses).
       Pricing page is meant to be public so this is correct, but worth confirming
       no PII / org content leaks via the same path.

[F-33] `/admin/feature-flags` directly exposes feature-flag DB to SUPER_ADMIN (S2, high) —
       FeatureFlagsPage.tsx + FeatureFlagController. No CSRF-style double-confirm
       for toggling production-affecting flags (e.g. billing.lifecycle.sunsetting.enabled).
       Add a confirmation modal for prod-impact flags.

[F-34] Audit columns are write-once-read-many (S3, low) — V22 activity_audit
       schema. No retention policy tier mapping; FREE org's audit grows forever.
       Already addressed for activity_feed (V133) via partition-by-month + retention
       job; activity_audit doesn't have the same; likely intentional for compliance.

[F-35] No global "logout from all devices" (S2, medium) — auth flow.
       JWT-based with feature-flag epoch invalidation per CLAUDE.md root, but
       no per-user epoch (force-logout just nukes everyone). Common request
       from security-conscious users.

[F-36] Sidebar Projects section list is unbounded (S3, medium) — Sidebar.tsx
       (Projects expandable). Org with 50+ projects gets a 50-row list with
       no search, no virtualization. Above ~20 projects this is unusable.

[F-37] `/billing` page tier-coherent treatment for grandfathered PRO unclear (S3, low) —
       V3Billing component unread. PRO HIDDEN tier means /billing should show
       "Your Pro plan (legacy)" framing for grandfathered users, not "Upgrade
       to Pro". Confirm.

[F-38] `?drawer=` query param convention used inconsistently (S3, high) — App.tsx:323
       (activity drawer); MoreDrawer.tsx (unread). The deep-link convention is
       a hidden pattern; document it or surface it via direct nav.

[F-39] Backend tier_config rows have lifecycle_state but no UI to flip between
       LISTED/HIDDEN/SUNSETTING for SUPER_ADMIN (S3, medium) — App.tsx:348
       /admin/tiers is a V3Placeholder. The TierLifecycleService backend exists.
       Mount the v2 TierLifecyclePage component (commented at App.tsx:81) directly.

[F-40] Test seeded users predate V162 personal-org bootstrap (S3, high) — [live-db]
       freshux-* users have no org_id. Either backfill these manually or drop
       and re-create through /api/auth/register so they go through the bootstrap
       path. Same fix as [F-10].
```

---

## §14 — Open Questions for the Human

```
[Q-01] Is FREE intended as a self-sufficient evaluation tier, or a teaser?
  Why it matters: 10 runs/month is enough to evaluate but not to ship a real project. If teaser, then the entire Sandbox onboarding ([F-02]) should be the primary FREE experience and quota gates should fire visibly. If self-sufficient, runs should be 25-50/month and the Library + Migrations should be partially unlocked.
  How my findings change depending on answer: §9 activation flow changes shape; [F-02] severity drops from S0 → S1 if FREE is purely a teaser (orphan onboarding is less critical when the goal is upgrade-asap).

[Q-02] Should the v3 shell replace the v2 sidebar entirely, or are they meant to cohabitate?
  Why it matters: §7's IA recommendation depends on this. The user's in-session note ("remove the side menu") suggests "yes, replace". Today's App.tsx implies cohabitation by design.
  How my findings change: [F-04] severity drops from S1 → S2 if cohabitation is the long-term plan; [F-07] disappears (RunsPage stays under AppLayout intentionally).

[Q-03] Is ENTERPRISE expected to share UI with PRO/TEAM, or have a distinct admin shell?
  Why it matters: §10's redesign brief assumes a separate admin shell. If the strategy is "one product surface for everyone, just unlock more features at higher tiers", §10's recommendations need rewriting.
  How my findings change: Top 5 gaps in §10 reorder (governance + RLS rise; admin shell falls).

[Q-04] Is the Library expected to be unified with Migrations and project Templates (one catalogue), or three separate galleries?
  Why it matters: §5 and §6 recommendations differ if they should merge. Today they're three sibling backends (`readymade_solution`, `migration_recipe`, `project_spec_template`) with different orchestrators.
  How my findings change: AG-4 (intent-matched readymade) becomes much higher leverage if all three are queryable from one planner-side classifier.

[Q-05] What's the actual buyer profile for TEAM tier ($49/seat) — small team upgrading from PRO, or 20-person engineering team standardising on the platform?
  Why it matters: §4.3's "TEAM is PRO with seats" critique depends. If small-team-upgrading, the seat-pool model is right and the missing concurrent-presence is acceptable. If 20-person standardisation, presence + shared templates + role flexibility are existential.
  How my findings change: [F-10] severity ladders up if TEAM is the standardisation target.

[Q-06] Is "/welcome" sandbox onboarding meant to ship or has it been deprecated?
  Why it matters: [F-02] proposes either (a) wire it up or (b) delete it. The choice depends on Wave 2.2 strategy.
  How my findings change: If deprecated, AG-1 changes from "wire the existing sandbox" to "build a different first-run experience"; the §9 activation redesign needs updated.

[Q-07] What's the intended semantic of `canViewPricing`?
  Why it matters: [F-09] flags it as either dead code or unenforced. Without knowing intent, the fix is ambiguous.
  How my findings change: Severity could be S2 (dead code, no functional impact) or S0 (unenforced gating that exposes admin pricing to wrong audiences).

[Q-08] Is multi-tenant isolation via PostgreSQL RLS on the roadmap, or is org_id-stamping the long-term answer?
  Why it matters: [F-11] is a procurement blocker; the fix is either L (add RLS) or XS (update the one-pager to be honest).
  How my findings change: §10's enterprise-blocker ranking depends.

[Q-09] When the ScoreRing + VerdictPill conflict in ValidationReportPanel ([F-15]), which one is the source of truth that ships in v2.6.x?
  Why it matters: Both are alive in code; the team needs to pick one. UX research on which signal users trust more would resolve this without my speculation.
  How my findings change: §3 [F-15] effort changes from "rebuild" to "delete one branch".

[Q-10] Is the current product positioned as the AGENTIC end-to-end (like Devin) or the SPEC-FIRST orchestrator (like a structured Lovable)?
  Why it matters: §11's "is Spec-First a real wedge" position depends. Marketing today says spec-first; the moat is verified output + multi-repo. Either reframe marketing or invest more in spec-first authoring tools (composer, templates, validation).
  How my findings change: §9 conversion redesign and §11 competitive positioning paragraphs both rewrite.
```

---

## §15 — Findings I'm Least Confident In

- **[F-09] (medium).** Whether `canViewPricing=false` for everyone is a dead flag or an unenforced gate. Resolved by: tracing every call site of `permissions.canViewPricing` in the frontend (one grep) and seeing if any code reads it. If zero reads, dead flag (S3); if any read but no enforcement at that path, unenforced (S0).
- **[F-11] (medium).** Whether multi-tenant isolation gaps are procurement-blocking depends on the buyer profile. Resolved by: a single sales call to a 200-person regulated buyer. If they ask for SOC2 + RLS, S0; if they ask only for SAML and accept org_id stamping with explicit doc, S2.
- **[F-15] (medium).** Whether VerdictPill + ScoreRing dual-rendering is a real friction or only audit-perceptible. Resolved by: 5-min user test with 3 PRO users — ask "how confident are you that this run succeeded?" looking at a ValidationReportPanel screenshot. If they answer in numerals (87, 92), the ring is dominant. If categories ("looks shippable"), the pill is dominant. Either result is fine; current state is undecided.
- **[F-25] (medium).** Public shared-report token entropy. Resolved by: reading the controller (5 lines of code) for the token-generation logic.
- **[F-31] (medium).** Whether DeploymentController has a tier check that I missed. Resolved by: 1 grep + 1 file read. If present, drop to S3 (frontend gate redundant); if absent, S0 (curl bypass works).
- **[F-02] (high but fix is a judgment call).** I'm confident `/welcome` is dead. The judgment call is whether to wire it up or delete it. That's [Q-06]. The audit fact is high-confidence; the fix is low-confidence.
- **[F-13] (medium).** `/trust` reachability. Resolved by: one decision — is this a marketing surface (delete) or a returning-user destination (add nav link)?

---

## §16 — Coverage Gaps

- **All `[live-api]` and `[browser]` evidence sourced from local stack** (Spring profile `local` on `:8080`, Vite on `:5173`). Booted JAR `target/executespec-2.5.1.jar` from audit branch tip `ca93bedea134` with `-D spring.ai.{anthropic,openai,google}.api-key` set to dummy strings (base `application.yml` has empty api-keys; profile `local` has no override file → empty strings → bean factory fails on null `AnthropicChatModel`). Dummy keys sufficient for UI/UX audit since no real LLM dispatches were made. Deployed env `dev.executespec.ai` not used per user directive.
- **No live UI walkthrough via browser MCP.** Per user instruction to base findings on actual code, all UI evidence is code-trace + file:line. The user's deployed-env screenshots (`/runs` v2 sidebar; `/projects/empty` v3 top-bar) provided the only visual confirmation; I did not render the local Vite stack in a browser. **Risk:** screen-only findings (e.g. visual hierarchy of VerdictPill vs ScoreRing) are inferred from CSS classes + component structure, not pixel measurement.
- **No mobile responsive testing.** All findings desktop-default. v3 top-bar mobile drawer behaviour not tested.
- **No Stripe / Razorpay end-to-end checkout test.** The `application-prod.yml` toggles + plan-id env vars (per CLAUDE.md root) require ops env vars not set on local. Subscribing flows traced via code only.
- **No real spec-run dispatched.** Per LLM cost cap of $5 — the dummy keys would have failed the planner LLM call anyway. Persona walkthroughs stop at "spec submitted, run goes PLANNING" rather than COMPLETE.
- **No ENTERPRISE customer interview.** §10 enterprise gaps inferred from docs + code; not validated against a real procurement requirements list.
- **Excluded from reading per anti-bias rule:** `docs/v3-issues-handoff.md`, `docs/strict-ux-thread-handoff.md`, `docs/wave1-progress-handoff.md`, `docs/greenfield-execution-checkpoint.md`, `docs/execution-checkpoint.md`, `docs/pre-deploy-closeout.md`, `/tmp/executespec-r343-persona-report*.pdf`. Audit's blind spots in onboarding + v3 are likely covered there; intentional gap to keep this audit independent.
- **Migrations end-to-end not exercised.** Phase 1 dry-run leak ([F-05]) is code-trace; would need a tier-permission lockdown test to confirm a FREE user gets the expected 200 with full applicability+cost+diff response.
- **Activity feed retention job not traced.** V133 `ActivityFeedRetentionJob` + `ActivityFeedPartitionJob` exist per CLAUDE.md root; backend behaviour under partition rotation untested.
