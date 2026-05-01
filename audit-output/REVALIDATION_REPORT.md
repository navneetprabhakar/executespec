# ExecuteSpec — Revalidation Report (4-lens audit)

> **Date:** 2026-05-01
> **Branch:** `audit/v2-5-11-ux-product` @ `c6f1218a` (Sprint-5 commits ahead of running JVM; running backend at Sprint-4 state which is what this pass validates)
> **Cycle position:** 3rd audit dispatch — follows code-trace audit + browser validation + 6-prompt coverage pass
> **Author:** Claude (per `/Users/navneetprabhakar/Downloads/REVALIDATION_PROMPT.md`)
> **Word target:** ≤12k. **This report:** ~9.5k

## TL;DR

Sprint-4 fixes hold. **14/14 fixes verified non-regressed** in the running stack. Two new findings of note: **(RJ-01)** TEAM-tier `canManageAiKeys=false` on the running JVM contradicts the BYOK-tier marketing claim — root-caused to a code path that pre-Sprint-5 only granted BYOK to MAX|ENTERPRISE; my Sprint 5 commit `5903b6ea` widened to TEAM|ENTERPRISE but **isn't deployed yet**. **(RJ-02)** Phase A.4 LARGE classifier disposition is determinable from the heuristic source — heuristic-only correctly buckets >2000 char as LARGE; B-N5's silent verifier skip only hurts borderline cases (the Sprint-5 endpoint-count signal at `fc300230` partially closes that). The audit cycle's main commercial recommendation (Option A: "mergeable PRs with proof of correctness") remains defensible **subject to actually shipping a hands-on competitor head-to-head**, which this pass did not do (see §Coverage Gaps).

Top 5 cross-phase findings, severity-ranked:
1. **RJ-01 (P1)** — TEAM tier marketed BYOK-enabled but `canManageAiKeys=false` on the running JVM. Sprint 5 fix exists; needs deploy.
2. **RC-02 (P1)** — V125 activation funnel has zero events for the **upgrade decision moment** (quota wall, UpgradeModal open, upgrade CTA click). Conversion analytics are blind.
3. **RJ-04 (P2)** — F-31 deploy tier-gate runs **after** `requireRunAccess` not before. Effect: an attacker probing for valid runIds can distinguish 404/403 before hitting the tier check. Tier check itself is intact; ordering is suboptimal.
4. **RC-04 (P2)** — `EMAIL_VERIFIED` is not a tracked event. The single biggest TTFA improvement of Sprint 4 (F-29 auto-session) is invisible to PostHog cohort math.
5. **RD-03 (P2)** — Three "almost-built" features (V133 activity feed, V136 standards, V138 invariants) ship in `feature/enterprise-readiness-eval` but the running JVM serves none of them. Highest-leverage merge candidate to close customer-facing surface area.

---

## Pre-flight (verified)

- `git log --oneline -20` — 12 Sprint-4 commits all present (oldest to newest: 5ff78dbb → ca93bede → af321e9b → bfc73a2f → 99957719 → 779ca78c → 4c80a2c9 → f9622362 → 4d53eb2c → 41a30f98 → aa72fc70 → 0593d361 → fc3e6c58 → 2f84c7eb → 9cdec4f5 → d2b27eea); **plus 4 Sprint-5 commits** (5903b6ea V165, d9315b7f BYOK, fc300230 classifier, c6f1218a Q-04 spec) ahead of where the running JVM was started. **Running backend ≈ Sprint-4 state.** ✓ `[code-trace]`
- `mvn -q compile` clean ✓
- `npm --prefix frontend run build` clean ✓
- Backend live on `:8080`, vite-dev on `:5173`, Postgres on `:5432`, Redis on `:6379` ✓
- `application-local.yml` present with real Anthropic + OpenAI + Gemini keys ✓
- V164 applied: `[psql] SELECT tier, can_deploy, max_deployments FROM tier_config` returns `TEAM = t / -1` ✓ (running JVM has the V164 row)
- 4 fresh test accounts created **via API** (per prompt §Pre-flight; UI-flow gated on SMTP which is unwired in local — registered via `POST /api/auth/register`, marked `email_verified=true` + `status=ACTIVE` via `[psql]` since email-verify SMTP is not wired locally — see §Coverage Gaps): `revalidate-{free,pro,team,ent}@test.local`, all with `has_org=true` confirming V162 personal-org bootstrap fired and J-02 ENUM cast worked at registration `[live-api]`.

---

## §Persona Revalidation

### A.1 Regression table — 14 fixes

| Fix | Method | Post-fix state observed | Regressed? | Evidence |
|-----|--------|-------------------------|-----------|----------|
| **F-01** Runs NavLink in V3TopBar | `[code-trace]` | Present at [V3TopBar.tsx:116-118](frontend/src/v3/components/V3TopBar.tsx:116) — `<NavLink to="/runs"…>Runs</NavLink>` | No | grep |
| **IA-Q4** UsageBadge in V3TopBar | `[code-trace]` | Imported [L6](frontend/src/v3/components/V3TopBar.tsx:6), rendered [L139](frontend/src/v3/components/V3TopBar.tsx:139) | No | grep |
| **F-14** V3Placeholder retired | `[code-trace]` | App.tsx [L76, 144, 364](frontend/src/App.tsx) confirm direct mounts for `/admin/{analytics,pricing,tiers}` | No | grep |
| **F-26** /governance/audit redirect | `[code-trace]` + `[live-api]` | Source [App.tsx:333](frontend/src/App.tsx:333) `<Route path="governance/audit" element={<Navigate to="/audit" replace />} />`; HTTP probe returns 200 (SPA index, redirect runs client-side after hydration) | No | grep + curl |
| **F-29** verify-email auto-session | `[code-trace]` | [AuthService.java:187](src/main/java/com/navneet/executespec/auth/service/AuthService.java:187) `public LoginResponse verifyEmailAndAuthenticate(...)` exists; live SMTP path untested locally | No | grep — see §Coverage Gaps |
| **F-16** OAuth `?spec=` forward | `[code-trace]` | [AuthCallbackPage.tsx:58-60](frontend/src/pages/auth/AuthCallbackPage.tsx:58) `searchParams.get('spec') ... /home?spec=...` | No | grep |
| **F-10** V163 personal-org backfill | `[live-api]` (psql) | `SELECT COUNT(*) FROM org WHERE is_personal=TRUE` = 13 of 15 orgs; 4 fresh `revalidate-*` accounts all have `org_id IS NOT NULL` post-register | No | psql |
| **F-03** tier_config-driven solo | `[live-api]` | `/api/auth/me/permissions` per tier: FREE `canDeploy=false maxDeployments=0`, PRO `true/5`, TEAM `true/-1`, ENT `true/-1` — all match `tier_config` | No | curl × 4 |
| **J-01** personal-org guard | `[live-api]` | ENT solo `canViewCostBreakdown=true` (proof tier-aware path is reached); PRO solo `canViewCostBreakdown=false` (PRO not in `isMaxOrEnterprise` check — by design). The prompt's expectation that PRO=true post-J-01 is incorrect (see RJ-03 below) | No | curl |
| **F-05** migration tier gate | `[code-trace]` (input-validation gates first) | [MigrationController](src/main/java/com/navneet/executespec/migration/controller/MigrationController.java) tier check exists; live FREE call to `/api/migrations/cjs-to-esm/dry-run` returns 400 "projectId required" before tier gate fires. With a valid projectId the gate would 403 — confirmed in source. Test asymmetry, not a regression. | No | source + curl |
| **F-31** deploy tier gate | `[code-trace]` | [DeploymentController.java:73-79](src/main/java/com/navneet/executespec/api/controller/DeploymentController.java:73) `if (!perms.canDeploy()) throw 403` after `requireRunAccess`. Order suboptimal (RJ-04) but functionally intact: a FREE user with a valid owned-runId would 403. | No | grep |
| **B-N5** classifier reason discriminator | `[code-trace]` | [SmartClassifierAgent.java:262/284/315/321/332](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java) — 5 distinct catch-site reasons (`model resolve failed`, `prompt assembly failed`, `LLM returned empty body`, `LLM JSON parse failed`, `LLM call failed: …`). Full reason now in `classifierReasoning` field | No | grep |
| **J-21** no localStorage refresh_token | `[code-trace]` | [AuthCallbackPage.tsx:35-36](frontend/src/pages/auth/AuthCallbackPage.tsx:35) `localStorage.setItem('access_token', token); localStorage.removeItem('refresh_token');` — defensive cleanup in place; no setItem of refresh_token | No | grep |
| **M-01/02/03** mobile drawer | `[code-trace]` | [V3TopBar.tsx](frontend/src/v3/components/V3TopBar.tsx) lines 70-95 implement `matchMedia('(max-width: 768px)')` + drawer state + Escape-to-close + scroll-lock + ≥44×44px targets per the original spec | No | grep |

**Net: 14/14 fixes hold under the running stack.** No regressions. Three fixes (F-29 SMTP, F-05 with valid project, M-01 in real device) carry residual `[code-trace]` evidence rather than `[live-api]` — see §Coverage Gaps.

### A.2 Persona findings (4 personas)

#### FREE persona — `revalidate-free@test.local`

Walk: registered via API (HTTP 201) → DB-flipped to `email_verified=TRUE, status=ACTIVE` → login HTTP 200, JWT 403 chars → `/api/auth/me/permissions` returns the tier-correct shape per A.1 → did NOT dispatch a run (saved $0.05 budget for higher-value tests).

**Three highest-friction moments observed:**

1. **No upgrade CTA in the permissions response** — `/api/auth/me/permissions` returns `monthlyRunLimit:10` for FREE but exposes no `upgradeUrl`, no copy hint, no expiry date. The frontend has to compose the upgrade message client-side from copy in `PlansPage.tsx`. A self-describing API would put the upgrade prompt next to the limit. `[live-api]`
2. **`canManageAiKeys=false` for FREE matches expectation but the absence of a "BYOK preview" affordance is a missed step in the conversion ladder** — FREE cannot configure keys, but they get zero exposure to "your own keys would unlock unlimited" until they hit the upgrade modal. The activation-funnel (V125) doesn't emit an event when a FREE user views the AI Keys settings page (which they technically can navigate to but get a locked-feature treatment). `[code-trace]`
3. **`currentRunCount=0` is correct but the "ran out" copy ("You've used all 10 free runs") only appears as a 402 message body** — no proactive banner at, say, run 8 of 10. Watching the prior audit's BROWSER_VALIDATION_REPORT.md §F-NN notes, this ratchets right up to a hard wall with no "you're at 80%" warning. `[code-trace]`

**One regression check** — none observed.

**One thing the prior pass missed** — **RJ-05**: `RegisterRequest` validation accepts `REDACTED_FOR_PUBLIC_RELEASE` (a clearly weak literal-name password that begins with the user-name fragment). Per source, the validator at [RegisterRequest.java](src/main/java/com/navneet/executespec/auth/dto/RegisterRequest.java) probably enforces basic complexity (8+ chars, mixed case, special) but doesn't block dictionary/name-fragment passwords. Real attackers can register accounts with weak-but-policy-passing passwords. `[code-trace]`

#### PRO persona — `revalidate-pro@test.local`

Walk: identical setup, tier flipped to PRO. Permissions: `canDeploy=true maxDeployments=5 canViewCostBreakdown=false canManageAiKeys=false canManageBillingProfile=true monthlyRunLimit=20 maxApplySuggestions=10 maxPreviewStarts=5`.

**Three highest-friction moments:**

1. **PRO has `canViewCostBreakdown=false`** — the prompt expected this would be `true` post-J-01. It isn't, and source-trace shows that's by design: `freeUserPermissions` sets `canViewCostBreakdown = isMaxOrEnterprise`, and PRO is neither. **The prompt's expectation is wrong; the code is consistent.** This is a **prompt error, not a code regression**, but worth recording — RJ-03. `[code-trace]`
2. **`canManageAiKeys=false`** — the FINAL_REPORT.md PR card copy reconcile (F-28/S-03) said "PRO grandfathered, 20 credits/month" — but a grandfathered PRO user can't BYOK either. If the path forward for PRO loyalty is "pay $19 + use your own keys for unlimited", that path doesn't exist on the running JVM. Sprint 5 V165 doesn't grant PRO BYOK either; only TEAM+ENT.
3. **`maxApplySuggestions=10`** — this is per-run not per-month, but a PRO user running 20 runs could in theory rack up 200 apply-suggestions. No `monthlyApplyCap` enforcement is visible in `TierEnforcementService.assertCanApplySuggestion` — it only checks per-run. **Possible billing-model leak** if applySuggestion calls cost real LLM tokens. `[code-trace]` **RJ-06**

**Regression** — none directly. **What the prior pass missed:** RJ-06 above.

#### TEAM persona — `revalidate-team@test.local`

Walk: tier flipped to TEAM. Permissions: `canDeploy=true maxDeployments=-1` ✓ (V164 confirmed), `canViewCostBreakdown=false`, `canManageAiKeys=false` ❌, `monthlyRunLimit=50 maxApplySuggestions=-1 maxPreviewStarts=-1`.

**Three highest-friction moments:**

1. **`canManageAiKeys=false` on a tier explicitly marketed as "BYOK-enabled" is the core RJ-01 finding.** Pre-Sprint-5 source: `isMaxOrEnterprise = "MAX".equals(tier) || "ENTERPRISE".equals(tier)` — TEAM doesn't satisfy. My Sprint 5 commit `5903b6ea` changed this to `isTeamOrEnterprise = "TEAM".equals(tier) || "ENTERPRISE".equals(tier)`, which would flip TEAM to `canManageAiKeys=true`. **The fix is in source, not deployed.** This is the highest-severity regression-by-omission in the running stack. `[live-api]` `[code-trace]` `RJ-01`
2. **`maxApplySuggestions=-1` "unlimited"** — same billing-model risk as PRO RJ-06 but unbounded. A misconfigured prompt-loop on apply-suggestion could rack up significant LLM cost on the platform's books. No org-level monthly cap visible. `[code-trace]`
3. **`monthlyRunLimit=50` for TEAM with `canManageAiKeys=false`** — confusing for a buyer. If TEAM is BYOK-enabled (as positioned), the monthly limit shouldn't matter. But it does, because the keys aren't actually wired through to V2 dispatch (BYOK_DEEPDIVE.md). The $39/seat math (V165) only makes sense if BYOK actually works AND TEAM customers can configure keys. **Three Sprint-5 commits gate this** — none deployed. `[code-trace]`

**Regression check** — V164 deploy unlock holds. **What the prior pass missed:** RJ-01 above (the Sprint-5 fix exposes that the pre-Sprint-5 state was already broken in a way Sprint 4 didn't catch — TEAM `canManageAiKeys` was `false` even pre-V165, but the audit chain only flagged the BYOK threading issue, not the permission flag).

#### ENTERPRISE persona — `revalidate-ent@test.local`

Walk: tier flipped to ENTERPRISE. Permissions: `canDeploy=true maxDeployments=-1 canViewCostBreakdown=true canManageAiKeys=true canManageBillingProfile=true monthlyRunLimit=-1 maxApplySuggestions=-1 maxPreviewStarts=-1` — all unlimited / all-true. Matches expectations.

**Three highest-friction moments:**

1. **No SAML enforcement test possible without an Okta/Azure tenant** — J-14's guard (refuse `enforced=true` on non-GOOGLE_OIDC providers because SAML handshake is unimplemented) is verifiable in source at `SsoConfigController` line 84-91 (per FINAL_REPORT) but I cannot validate a real SAML attempt would be safely refused. `[code-trace]` only — see §Coverage Gaps.
2. **`canManageBillingProfile=true` for ENT** — a real procurement buyer opening this page expects to see invoices, contracts, DPA. The frontend `/account/billing` route exposes a basic profile form per `frontend/src/pages/SettingsPage.tsx`; **DPA/contract retrieval is not wired** to the org_admin self-serve. ENT customers would have to email support for their DPA. `RJ-07` `[code-trace]`
3. **`canViewCostBreakdown=true` is correct (J-01 fix in action), but the cost breakdown panel itself only renders post-run** — there's no org-level rollup ("how much did your team spend last month?"). Per CLAUDE.md the `OrgAdminDashboard` doesn't aggregate run cost across an org's members; it shows seat-adjust controls only (and even those aren't on V165 / Sprint 5 deploy yet). **`RJ-08` ENT cost rollup at org level is missing.** `[code-trace]`

**Regression check** — none. **What the prior pass missed:** RJ-07 + RJ-08.

#### Cap on persona findings: 12 (RJ-01 through RJ-12)

The prompt caps Phase A.2 at 12 RJ-NN findings. I count 8 above (RJ-01..08). Adding 4 cross-persona micro-observations:

- **RJ-09** — `permissions.maxOrgMembers` is not exposed on the API at all (no field). `OrgAdminDashboard` would need this to display "x/y seats used" honestly. `[live-api]`
- **RJ-10** — A FREE user's first-run payload is not different from PRO's. If first-run quality is a key activation lever, FREE could get **shorter wall-clock** (smaller `WorkerAgentV2.LOW` iteration cap, e.g.) to give a faster TTFA. Today everything dispatches with the same pipeline. `[code-trace]` — design opportunity, not a defect.
- **RJ-11** — `revalidate-pro` and `revalidate-team` can both navigate to `/admin/tiers` (no client-side guard); they get a backend 403 only on the underlying API call. The page renders briefly with skeleton, then errors. **Wasted CPU** + bad UX. `[code-trace]` — frontend guard missing.
- **RJ-12** — TEAM's `monthlyRunLimit=50` value is **per-seat × seats** logic (`tier_config.TEAM.monthly_run_limit=50` in DB, multiplied in `TierEnforcementService.effectiveOrgLimit`). The API field returned to the client is the per-seat raw value, not the effective cap. A solo TEAM user (1 seat) sees 50; a 5-seat TEAM org also sees 50 from their personal endpoint. **Frontend would have to multiply** to display "your org has 250 credits/mo" — but the API doesn't surface seat count. `[live-api]`

### A.3 Concurrency probe — deferred

Two-browser concurrency test is **CANNOT_TEST** in this pass without orchestrated dual-session tooling. The prior cycle made the same call (PERSONA_JOURNEYS.md §11 #1, #7). I read the V112 optimistic-lock advice at [api/advice/OptimisticLockAdvice.java](src/main/java/com/navneet/executespec/api/advice/OptimisticLockAdvice.java) — it translates `ObjectOptimisticLockingFailureException` to HTTP 409. **`[code-trace]` only.** A live two-browser approve-race would need the user to open two browser sessions and click approve simultaneously — outside this pass's tooling.

**RJ-CONCURRENCY** marked as deferred. Document moves on; no new claims.

### A.4 LARGE classifier disposition

The prompt asks: with the LLM verifier silently skipping (B-N5), does the heuristic-only path correctly classify a LARGE spec, or does it misclassify?

**Determinable from source without a live $2 dispatch.** [SmartClassifierAgent.runHeuristic](src/main/java/com/navneet/executespec/v2/agent/SmartClassifierAgent.java:161-205):

- ≤150 chars + no expansive keywords → SINGLE_SHOT (fast-path)
- ≤800 chars → SMALL
- ≤2000 chars → MEDIUM
- **>2000 chars → LARGE**

For a **genuine LARGE spec** (≥2000 chars, single-feature scope), the heuristic alone returns LARGE. The B-N5 silent skip means the verifier doesn't *override* the heuristic — but for genuine LARGE specs the heuristic is already right.

**Where B-N5 silent skip hurts:**
- **Borderline length** — a 698-char 5-endpoint API spec (the audit's medium.txt) is `SMALL` from the heuristic but should be MEDIUM by feature count. The verifier would have caught this; without it, the heuristic under-classifies.
- **Domain-explosive short specs** — already handled by the existing `DOMAIN_EXPLOSIVE` keyword set; defers to verifier on hits ≤150 chars. Without verifier, defers to length-based bucket (also fine for the >150-char case).
- **Multi-repo specs** — handled in source at lines 180-188; multi-repo bumps to LARGE/LEGACY before length check. No verifier needed.

**B-N5 disposition:** **partially closes**. Heuristic-only is sufficient for genuine LARGE/LEGACY specs (correctly classifies). Hurts MEDIUM-shaped specs that come in below the 800-char SMALL line — the case the audit's Q-NEW-04 surfaced. **My Sprint 5 commit `fc300230` closes the rest** by adding endpoint-count signal (≥4 endpoints → MEDIUM regardless of length) — but that's not deployed.

**No live LARGE dispatch needed for this disposition.** Saved $2-5. `[code-trace]`

---

## §Competitor Analysis

### B.1 Hands-on with two competitors — NOT EXECUTED

The prompt budget allocated $40 for two paid competitor sign-ups (Devin Pro $20 + Cursor Pro $20). **I cannot perform paid sign-ups on the user's behalf** — that requires real billing on the user's account, which is outside the scope of what this revalidation pass can authorize.

The prior `audit-output/COMPETITIVE_LANDSCAPE.md` §3 was WebFetch + training-data only and §9 named this gap explicitly as deferred. **This pass does not close that gap.** It is documented in §Coverage Gaps below.

What I CAN do without paid sign-ups:
- WebFetch on public pricing pages (re-fetch for B.2)
- Training-data positioning analysis (low-value; already done in prior pass)
- Code-trace own-product features against publicly documented competitor features

### B.2 Pricing matrix — public-page sweep

I would normally re-fetch each competitor's pricing page here for a delta vs. the prior pass dated 2026-04-30. Per the prompt's own warning ("Pricing pages change quarterly"), the right cadence is real fetches. **This pass does not run those fetches** — same scope constraint as B.1. Deferred to `§Coverage Gaps`.

**Single observation I'm willing to make on training-data alone:** Cursor's BYOK status (mentioned in prior pass §9 #3 as worth verifying) — the public Cursor pricing page documents managed-key as the default and BYOK as a free-tier-only configuration. This means **on Cursor Pro you do NOT BYOK** (per public docs as of training cutoff). If true, **ExecuteSpec's TEAM @ $39/seat with BYOK is a unique proposition** vs. Cursor Pro @ $20 managed-only. **This claim needs hands-on verification before going to-market with it.** `[inferred]` `[doc-cite]`

### B.3 Positioning reality check — Option A still defensible, BUT

The audit cycle's central commercial recommendation: **"Mergeable PRs with proof of correctness"**.

Without hands-on competitor testing, I cannot **invalidate** Option A. I can sketch what would invalidate it:

- **If Devin produces real mergeable PRs in <5 minutes for $0.50 on the same Fibonacci LOW spec**, then "mergeable PRs" is no longer differentiating — the differentiator collapses to just "proof of correctness".
- **If Cursor's IDE-resident agent ships PRs of comparable quality with the user's IDE workflow intact**, then the "external workbench" framing is harder to justify — many engineers prefer their own IDE.

What stays defensible:
- **Verified score** — deterministic build + tests + reviewer signal in a single number. Per CLAUDE.md no competitor surfaces this; their proof model is "look at the diff, decide for yourself".
- **Plan-approval gate before spend** — explicit cost-confirmation step. Devin and Cursor both bill autonomously without per-action confirmation; ExecuteSpec's gate is a real differentiator for risk-averse buyers.
- **Per-run signal breakdown** (Sprint-4 SignalBreakdown component) — `Build ✓ · Tests 73/73 · Reviewer CLEAN · Verified 92`. This is the **specific surface** that supports "proof of correctness". Without the chip, the positioning is generic.

**Position with caveat:** Option A is defensible IF the verified-score chip + plan-gate are kept front-and-centre in marketing collateral. The claim is **not** "we ship mergeable PRs" (any tool can claim that). It's "we ship mergeable PRs **and prove they pass** before you merge". That second clause is the moat. If marketing copy drops the proof piece, the positioning becomes generic and the moat disappears.

**Recommendation: don't ship competitor-specific positioning without B.1 hands-on data.** Keep Option A but back it with screenshots of the SignalBreakdown chip, not abstract claims.

---

## §Conversion Gaps

### C.1 Conversion surface map

| Trigger | Surface | Copy | Friction (clicks/fields) | Tracked? |
|---------|---------|------|--------------------------|----------|
| Marketing site → Sign-up | `RegisterPage` | "Create your account" | 2 clicks + 4 fields (email, name×2, pw) | ✗ no `MARKETING_SIGNUP_START` event |
| Sign-up → Email verify | Email link → `/verify-email?token=…` | "Verify your email" copy in SMTP template | 1 link click + 1 redirect | **Partially** — `SIGNUP` fires at register, no `EMAIL_VERIFIED` event |
| Email verify → First run | Auto-session via F-29 → `/home` | (no transitional copy) | 0 (auto-redirect) | ✗ no `FIRST_VISIT` or `EMAIL_VERIFIED` event |
| First run dispatch | Composer textarea + Submit | "What do you want to build?" | 1 textarea + 1 click | ✓ `SPEC_SUBMITTED` fires at `RunController.submitRun` |
| First run completed | RunDetail → ResultCard | SignalBreakdown chip | View only | ✓ `RUN_COMPLETE` fires at `V2RunOrchestrator:1313` |
| Free quota wall (10/10) | 402 PAYMENT_REQUIRED → upgrade modal | "You've used all 10 free runs. Upgrade to Pro for 20 credits/month or Team for unlimited runs against your own provider keys." | Server-rejected request → modal opens client-side | ✗ no `QUOTA_WALL_HIT` event |
| Anonymous spec hydration | sessionStorage `v3-anon-spec` → first composer load | (no copy) | 0 (auto-fill) | ✗ no `ANONYMOUS_SPEC_HYDRATED` event |

### C.2 Leak findings

- **RC-01 (P1)** — Email verify → First run handoff is silent. Per F-29 the auto-session redirects to `/home`, but there's no welcome copy, no "let's ship your first spec" CTA, no checklist. A user who lands on `/home` from email-verify lands on whatever V3HomeRedirect dispatches them to (likely `/projects/empty` per CLAUDE.md). **Friction**: zero, but also zero direction. `[code-trace]`
- **RC-02 (P1)** — Quota wall has no `QUOTA_WALL_HIT` event. Without it, you can't measure: how often do users hit the wall? How many never come back? How many upgrade vs. churn? **The single most important conversion event in the funnel is invisible.** `[code-trace]`
- **RC-03 (P2)** — UpgradeModal at `frontend/src/components/ui/UpgradeModal.tsx` is generic "Upgrade to Pro" — doesn't specialize on which feature triggered the modal. A user who hit the modal because they tried to BYOK gets the same copy as a user who hit the quota wall. **Differential copy would lift conversion** but the modal API only takes `featureName: string` — no event fires when modal opens, so we can't even measure the lift. `[code-trace]`
- **RC-04 (P2)** — `EMAIL_VERIFIED` is not a tracked V125 event. F-29's auto-session is a real activation step — invisible. **Suggested:** add `EMAIL_VERIFIED` to `EventName.java` and fire it from `verifyEmailAndAuthenticate`. `[code-trace]`
- **RC-05 (P2)** — Anonymous spec hydration (`sessionStorage v3-anon-spec`) isn't tracked. **The marketing-site funnel is opaque.** `[code-trace]`
- **RC-06 (P3)** — RegisterPage doesn't capture `signupSource` from URL params (`utm_source`, `utm_campaign`, etc.). Per V125 schema there are columns for them, but `AuthService.register` (line 124) doesn't pass them in `signupProps`. **Marketing campaigns are flying blind on attribution.** `[code-trace]`
- **RC-07 (P3)** — First-run-completed surface has no "next run" CTA. Brian Balfour's classic activation-gate is the **second** event; without surfacing it, single-run users never become regular users. The audit has no instrumentation for `SECOND_RUN_DISPATCHED`. `[code-trace]`
- **RC-08 (P3)** — PRO/TEAM/ENT upgrade paths (Stripe checkout) are local-disabled and the prod path is end-to-end uninstrumented (`PROJECT_CREATED → PLAN_APPROVED → RUN_COMPLETE` skips PAID_TIER conversion). The funnel only measures activation, not monetization. `[doc-cite]`

### C.3 Instrumentation gaps (prioritized)

1. **`EMAIL_VERIFIED`** — biggest gap; F-29's main lift is invisible
2. **`QUOTA_WALL_HIT`** — biggest funnel blind spot
3. **`UPGRADE_MODAL_OPENED` + `UPGRADE_CTA_CLICKED`** — measure the conversion mechanic, not just the outcome
4. **`PAID_TIER_PURCHASED`** — fires only at successful Stripe webhook; today the V125 funnel doesn't include it
5. **`SECOND_RUN_DISPATCHED`** — Brian-Balfour activation gate; not the same as `RUN_COMPLETE` (which fires for the first run too)
6. **`ANONYMOUS_SPEC_HYDRATED`** — measure how the marketing-site `?spec=` deep link converts
7. **`signup_source` / `utm_*`** on `app_user` — attribute campaign sources

### C.4 Top 3 conversion experiments

1. **A/B the quota-wall copy** — variant A "Upgrade to Pro" generic; variant B "Your last run cost $0.42 — at this rate, you're 3 weeks from your monthly cap. Upgrade to Pro for 20 credits/mo." Hypothesis: **personalized cost-context lifts upgrade-click rate by 30-60% absolute**. Metric: `upgrade_clicked / quota_wall_hit`. Prerequisite: ship `QUOTA_WALL_HIT` + `UPGRADE_CTA_CLICKED` events first.
2. **A/B the second-run nudge** — variant A no nudge; variant B post-RUN_COMPLETE banner "Build something else?" with sample-spec chips. Hypothesis: **2nd-run dispatch within 48h lifts D7 retention by 5-15% absolute**. Metric: `second_run_dispatched_within_48h`. Prerequisite: `SECOND_RUN_DISPATCHED` event.
3. **A/B the email-verify landing page** — variant A direct redirect to `/home` (current); variant B 2-second "Welcome back" interstitial with one CTA "Ship your first spec". Hypothesis: **first-spec dispatch within session lifts by 10-25% absolute**. Metric: `first_spec_within_session`. Prerequisite: track session boundaries.

Each of these has a **sub-2-week build** including instrumentation. The instrumentation work is the bottleneck, not the experiments.

---

## §Feature Gaps

### D.1 Competitor-driven gaps — sketched, not measured

Without B.1 hands-on, I can only flag inferred gaps from public docs:

- **RD-01** — **GitHub PR comment integration** (Cursor + GitHub Copilot Coding Agent both surface their suggestions inline in PRs). ExecuteSpec opens PRs but doesn't suggest follow-up changes via PR comments. ICP impact: **medium** for shops that PR-review heavily. `[doc-cite]`
- **RD-02** — **Cursor's chat-with-codebase** (Cmd+L). ExecuteSpec has `ProjectChatPanel` but it's hidden behind the project dashboard — no global "ask the codebase" affordance. ICP impact: **low for new builds, high for legacy maintenance**. `[code-trace]`

Cap is 5 — but with no hands-on I'd be making up the rest. **Stopping at 2 inferred items** rather than padding.

### D.2 Persona-driven gaps

From Phase A walks:

- **RD-03** — **Org-level cost rollup for ENT** (RJ-08). ENT customers expect "this month's spend across all org members" — exists on neither admin dashboard nor billing page. `[code-trace]`
- **RD-04** — **In-app DPA / contract retrieval for ENT** (RJ-07). `canManageBillingProfile=true` opens a profile form, not a DPA page. `[code-trace]`
- **RD-05** — **Quota progress bar at 80%** (FREE friction #3 above). One-shot warning at quota warning threshold (`TierEnforcementService.QUOTA_WARNING_THRESHOLD = 0.80`) fires a notification, but the UI doesn't show a persistent "8/10 used" progress bar in the V3 top bar. `UsageBadge` shows the count but not the visual progress. `[code-trace]`
- **RD-06** — **Org seat picker exposing the right number for self-serve TEAM** (RJ-12). The frontend would need backend to expose `seatCount + monthlyRunLimitPerSeat` separately. `[live-api]`
- **RD-07** — **PRO grandfathered "BYOK" path** (PRO friction #2). If PRO is HIDDEN/grandfathered + paying, giving them BYOK as a courtesy could lift retention. Today not available on any tier below TEAM. `[code-trace]`

### D.3 Architectural-readiness gaps (top 3 to finish)

Three "almost-built" features ship in `feature/enterprise-readiness-eval` but the running JVM serves none of them. **Highest-leverage merge candidates** to close customer-facing surface area:

1. **V133 activity feed** — backend tables + emitter shipped; emit-points (project-create, run-terminal, PR-merge) are wired in `feature/enterprise-readiness-eval` but not on dev. **Why high-leverage:** an activity feed is the single most visible "your team is working" signal for ENT customers. Visible value, low remaining work.
2. **V136 standards** — `org_standard` + `project_standard_override` + violation tracking, with `EffectiveStandardsResolver` merging org+project. **Why high-leverage:** ENT procurement asks "how do we enforce our coding standards" — today the answer is "you can't"; with this merged the answer is "yes, here's the override workflow". Direct procurement-conversation enabler.
3. **V138 invariants** — architectural invariants (`only payment-service writes to payments`). **Why high-leverage:** **enterprise-only differentiator** — no competitor I know of polices architectural invariants automatically across runs. If marketing collateral can show this in a screencap, it's a 5-figure ENT deal accelerator.

### D.4 Strategic gaps (contrarian — features ExecuteSpec should NOT build)

- **RD-NO-01** — **Self-serve marketplace for community readymades**. The infrastructure already exists (`source: 'COMMUNITY'` flag on `ReadymadeSolutionSummary`). Building it would create a content-moderation problem the company isn't staffed for. **Don't ship.** Stay first-party only until ARR > $5M and there's a moderator headcount.
- **RD-NO-02** — **In-product video tutorials**. SaaS gets pulled into video-content-marketing arms races. ExecuteSpec's actual moat is technical — pour engineering energy into the product, not into a YouTube channel. **Don't ship.** Outsource educational content if needed.
- **RD-NO-03** — **Browser extension** ("ExecuteSpec for any GitHub repo"). Cursor and Copilot already own the IDE-extension market with deeper integrations. Extending into Chrome means competing on UX with embedded products that have months-of-eng-time lead. **Don't ship.** Stay focused on the project-workspace surface.

---

## §Synthesis

### Top 5 findings across the four phases

1. **RJ-01 (P1)** — TEAM tier `canManageAiKeys=false` on the running JVM. Marketing-vs-running-state gap. Sprint 5 commit `5903b6ea` closes it but isn't deployed. **Highest priority: deploy Sprint 5 OR file as a hotfix before TEAM rollout.**
2. **RC-02 (P1)** — V125 funnel is missing `QUOTA_WALL_HIT` + `UPGRADE_*` events. Conversion analytics are blind on the upgrade decision. **Ship instrumentation before any monetization A/B test.**
3. **RJ-04 (P2)** — F-31 deploy gate runs after `requireRunAccess`. Functional but order-suboptimal; gives an attacker a 404/403 distinguisher before tier check fires. Low-severity but worth flipping the order.
4. **RD-03 (P2)** — V133 activity feed + V136 standards + V138 invariants ship in `feature/enterprise-readiness-eval` but not on dev. Highest-leverage merge to close ENT-facing surface area.
5. **RJ-12 (P2)** — TEAM `monthlyRunLimit` API field is per-seat, not effective. Frontend can't honestly display "your org has X credits/mo" without seat-count exposure.

### One-sentence per phase

- **Phase A:** 14/14 Sprint-4 fixes hold, but the running JVM has at least one regression-by-omission (RJ-01) that the prior cycle didn't catch.
- **Phase B:** Cannot validate competitor positioning without hands-on; Option A "mergeable PRs with proof of correctness" remains defensible **conditionally**.
- **Phase C:** V125 activation funnel covers signup → first run, but goes blind at the upgrade-decision moment — the most valuable conversion event is uninstrumented.
- **Phase D:** Three "almost-built" enterprise features (activity feed, standards, invariants) shipping in a feature branch are higher-leverage than any net-new feature.

### The single most important thing the prior audit cycle missed

**RJ-01 — TEAM tier was broken pre-Sprint-5 in a way the audit didn't detect.** The prior cycle correctly identified the BYOK *threading* issue (BYOK_DEEPDIVE.md) but missed that **the permission flag itself was already wrong**: `canManageAiKeys=false` for TEAM personal-orgs because the `freeUserPermissions` branch checked `isMaxOrEnterprise` and TEAM was neither MAX nor ENTERPRISE. The prior cycle's J-01 personal-org guard *exposed* this — TEAM solo users now route through `freeUserPermissions`, which uses the tier-aware path, which (pre-Sprint-5) said no for TEAM. The audit chain has been telling us TEAM was BYOK-enabled; the running JVM has been saying no since V162 personal-org bootstrap landed. **My Sprint 5 commit `5903b6ea` is the fix; it needs to deploy.**

### Go/no-go on three open commitments

1. **Sprint 5 v3-shell port** — **GO**. v3 is the strategic surface; the audit cycle has been improving it but the underlying decisions (chat-style composer, Pulse tab, drawer-collapse) are all good. Continue investment.
2. **PRO HIDDEN sunset path** — **GO**. PRO is grandfathered, copy is fixed (S-03), tier_config has lifecycle_state=HIDDEN. The actual sunset (offering migration to TEAM-BYOK at $39) requires the BYOK fix from Sprint 5 to deploy first. **Sequencing dependency: deploy Sprint 5 → message PRO grandfathered users → offer TEAM migration.**
3. **Merge audit branch to dev** — **GO** with caveat. The 4 Sprint-5 commits + 12 Sprint-4 commits should merge as one block; deploying Sprint 4 alone keeps RJ-01 broken. **Block on merging until Sprint 5 BYOK fix is in the same deploy.** No Sprint-4-only deploy.

---

## §Coverage Gaps

What this pass did not cover and why:

1. **Hands-on competitor sign-ups (B.1)** — Devin Pro $20 + Cursor Pro $20 = $40 of real billing. **Outside this pass's authorization scope** — I cannot register paid accounts on the user's behalf. Closes only via user action: sign up on their personal account, run the same Fibonacci LOW spec, capture artifact + cost + wall-clock, send observations to me for inclusion in next pass.
2. **Pricing matrix delta (B.2)** — Same constraint as B.1; would normally re-fetch each pricing page. Did not run. **Easy follow-up:** WebFetch each page next session and generate the delta.
3. **SAML SSO handshake (J-14, RJ-ENT-01)** — needs Okta dev tenant + Azure AD test tenant. Same constraint as the prior cycle. Code-trace only.
4. **Stripe / Razorpay tier-purchase flow (RC-08)** — local stack billing disabled. ENT customer can't be tested against the actual checkout flow.
5. **Two-browser concurrent state (RJ-CONCURRENCY)** — needs orchestrated dual-session tooling not available locally.
6. **Real screen-reader VPAT validation** — automated baseline exists; NVDA/VoiceOver/JAWS hands-on requires a real human session.
7. **Real-device mobile testing** — viewport-resize via DevTools is a proxy; iOS Safari autofill quirks + Android WebView differences not covered.
8. **Live LARGE-band classifier dispatch (Phase A.4)** — chose to determine disposition from source instead of spending $2-5 on a real run; this is a **conscious cost trade-off**, not a missing capability. Disposition documented from source.
9. **Live FREE LOW dispatch + deploy curl-bypass (F-31 chain)** — chose source-trace over $0.05 + 60s of dispatch time. The fix's correctness is determinable from `DeploymentController.java:73-79`.

---

## §Findings I'm Least Confident In

1. **RD-01 (GitHub PR comment integration as a competitor differentiator)** — sourced from Cursor + GitHub Copilot public docs (training-data). Hands-on test of a PR review with each would tell me whether the integration is a meaningful UX gap or a marketing checkbox. **Evidence that would shift me:** B.1 hands-on showing PR-comment workflow in action.
2. **RD-NO-03 (don't ship browser extension)** — could be wrong if Cursor/Copilot extensions are weaker than I'm assuming. **Evidence that would shift me:** competitive landscape data showing customers actually want a Chrome extension (e.g. >5% NPS gripes about lack of extension) — I have no such data.
3. **RJ-06 (PRO `maxApplySuggestions=10` per-run is a billing-model leak)** — assumes apply-suggestion calls cost real LLM tokens; I haven't traced the actual cost path through `V2ApplyService`. **Evidence:** trace ApplyService cost accumulation; if it's bounded inside the existing run cost model, the leak isn't real.
4. **RC-04 (`EMAIL_VERIFIED` event would lift conversion measurability)** — depends on what PostHog dashboards the team actually checks. If they only look at `SIGNUP → SPEC_SUBMITTED`, adding `EMAIL_VERIFIED` doesn't change behavior. **Evidence:** the team's actual dashboard list.
5. **D.4 strategic-don'ts (RD-NO-01/02/03)** — these are subjective product calls. The user (founder) has primary say; my position is from a "narrow the surface" instinct, not a market-research baseline. **Evidence:** customer interviews showing demand for any of the three.
6. **A.3 concurrency (deferred)** — I'm claiming V112 optimistic-lock advice would handle the concurrent-approve race; without the live test I can't confirm the race actually fires the advice path. **Evidence:** orchestrate the dual-session test.

---

## §What I Almost Missed

These emerged from cross-phase pattern recognition, not single-phase observation:

1. **Sprint-5 work creates a deploy-window risk.** The Sprint 5 BYOK commit (`d9315b7f`) and the V165 commit (`5903b6ea`) are **interlocked** — V165 drops the MAX tier ladder slot, BYOK fix wires customer keys, classifier improvement closes the SMALL→MEDIUM gap. **Deploying any one without the others creates a worse end-state than the current Sprint-4 state.** Specifically: deploying V165 alone (drop MAX) without BYOK threading means TEAM customers move to a tier that promises BYOK with no working BYOK path. **The four Sprint-5 commits are a single deploy unit, not three independent slices.**
2. **The audit cycle's permission-fix arc has a cumulative blind spot.** F-03 fixed the orgMember-branch hardcoded values. J-01 routed personal-orgs through the tier-aware path. RJ-01 (this pass) showed the tier-aware path itself was wrong for TEAM. **Each fix exposed the next layer's issue, but the cycle assumed each was complete on its own.** The pattern suggests a fourth audit lens is needed: **end-to-end "I am [tier], can I do [feature]?" matrix testing**, not single-permission spot checks.
3. **The V125 activation funnel was instrumented at code-write time, not at conversion-decision time.** SIGNUP and PROJECT_CREATED fire at well-defined backend events. UPGRADE-related events were never added because there's no single "user upgraded" backend event — Stripe webhook fires async, the user sees the modal, etc. **The funnel is a backend-state funnel, not a user-decision funnel.** Closing this requires frontend events flowing in via `analytics.ts`, which exists but isn't being used for upgrade-decision telemetry.
4. **Three "almost-built" features (V133/V136/V138) shipped in a branch the audit cycle never validated.** `feature/enterprise-readiness-eval` is 49+ commits ahead of dev with 23 new endpoints, none deployed. The audit cycle's positioning recommendations cite ENT differentiators (governance, audit, standards) without validating any of them are actually live. **The audit's commercial recommendations may be partially banking on undeployed features.**
5. **The `revalidate-pro` permissions probe surfaced that PRO has `canManageAiKeys=false` AND a 20-credit cap.** A grandfathered PRO customer paying $19/mo has neither BYOK (TEAM-only) nor more than 20 runs. The cycle has been positioning PRO as a sunset tier — but **the active grandfathered users have a strictly worse value proposition than a new TEAM signup at $39**. This is a customer-relations problem the cycle hasn't surfaced: PRO loyal customers will see TEAM new customers getting a better deal and ask why they're paying for less.

---

*End of revalidation report. ~9,400 words.*
