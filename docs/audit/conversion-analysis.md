# ExecuteSpec — Conversion Analysis per Persona

> Authored: 2026-05-22. Grounded in the live code on `feature/openrouter-provider-2026-05-21`.
> Scope: a per-persona walk of the activation funnel + the conversion friction, gaps, and levers
> for each, ending in a cross-persona prioritized backlog. **Analysis only — no app code changed.**

## How to read this

The product's own funnel (`analytics/EventName.java` + `ActivationFunnelController`) is the spine:

```
SIGNUP → PROJECT_CREATED → SPEC_SUBMITTED → PLAN_APPROVED → RUN_COMPLETE → PR_MERGED
```

Each event fires at most once per user (`EventTrackingService.insertFirstTime`). The plan gate
(`PLAN_APPROVED`) is the monetisation boundary — credits are charged on approval, not submission
(`RunOrchestrator` line ~3595 `setCreditsCharged`; refunded on FAILURE / clean-PARTIAL via
`RunQuotaRefundListener`). So **the plan gate is where intent converts to spend**, and it is the
single most important screen in the whole product for conversion.

Key economic facts, from code:

- **Credits:** `credits = max(1, ceil(bufferedEstimate / $5))` — `RunCostTracker` lines 248/315
  (`USD_PER_CREDIT`). Charged at approval; FREE specs are restricted to 1-credit (see below).
- **FREE quota is LIFETIME, not monthly.** `TierEnforcementService.assertUserQuota` treats FREE as
  *"lifetime limit — never reset"* (line ~571 + `maybeResetUserCounter` early-returns for FREE).
  V200 bumped the number 5 → 15, and the migration comment + the marketing copy
  (`PlansPage` FREE bullets, `V200` description) both say **"15 runs / month"** — but the
  enforcement is **15 runs for the lifetime of the account**. This contradiction is load-bearing
  for the FREE persona below.
- **FREE per-run cost cap = $3** (`tier_config.max_run_cost_usd`, `DEFAULT_FREE_COST_BUDGET`).
- **BYOK lives on TEAM + ENTERPRISE only.** `TierPermissionService.freeUserPermissions` grants
  `canManageAiKeys` only when `tier ∈ {TEAM, ENTERPRISE}` (line ~305). PRO is HIDDEN/grandfathered.
- **PR Review / Code Review** gated FREE→403 via `assertTierAndQuota` (PRO 10/4 per month;
  TEAM/ENT unlimited).
- **Anonymous trial** (`/api/v3/anonymous-runs`, `AnonymousRunService`) is **fully built on the
  backend** — rate limiter, abuse heuristic, Redis session, and a `bindToUser` carry-over hook so a
  signup can claim the pre-signup work — but **real dispatch is flag-gated OFF**
  (`v3.anonymous-trial.real-dispatch`, default OFF → returns a `STUB_COMPLETE` stub) **and there is
  no frontend caller at all** (no function in `frontend/src/lib/api.ts`, no route in `App.tsx`).

---

## Persona 1 — Anonymous / Evaluator (no account)

### 1.1 Journey through the funnel

1. **Entry.** Lands on a marketing/legal surface. The public routes in `App.tsx` are `/welcome`,
   `/pricing`, `/trust`, `/terms`, `/privacy`, `/dpa`, plus public `/shared/:token` report links.
   `/` and any unknown path redirect to `/home`, which is behind `ProtectedRoute` → bounced to
   `/login`.
2. **First "value".** There is **no anonymous run path wired in the UI.** The backend
   `AnonymousRunController` exists and the funnel even has dedicated v3 events
   (`V3_LANDING_TEXTAREA_SUBMIT`, `V3_FIRST_RUN_STARTED`, `V3_FIRST_RUN_READY`), but nothing in the
   React app posts to `/api/v3/anonymous-runs`. So the evaluator's only "try before signup" is a
   **public shared report** if someone hands them a link, or the `/trust` Trust-Pack PDF.
3. **Funnel transition.** The intended path (`WelcomePage`, the 2-step starter-spec picker) is a
   **post-signup onboarding** — it lives behind the auth shell, uses `AuthLayout`, and step 2 asks
   the user to **install the GitHub App**. The "sandbox" specs it offers are stashed in
   `sessionStorage('signal:pending-spec')` and only honoured after the user is authenticated and
   reaches `ComposePage`.
4. **Habitual use / upgrade.** N/A — the evaluator cannot become habitual without an account.

### 1.2 Conversion friction / drop-off

- **The single biggest drop-off in the whole product is here.** An evaluator who wants to "see it
  do something" must create an account first. There is no zero-commitment "type a spec → watch a
  diff" loop on the public surface, even though the backend is ready for it.
- **Dead capability.** The anonymous trial's `bindToUser` carry-over (so pre-signup work follows the
  user in) is built and tested (`AnonymousRunServiceTest`) but unreachable — both because of the
  default-OFF flag and the missing frontend.
- **Stub-only even if flipped on.** With the flag OFF the endpoint returns a `STUB_COMPLETE`
  message *"Anonymous trial is gated this session — sign up to ship a real run."* — so even a
  hand-crafted call gives no real value.
- **No spec carry-over from public → signup.** Because the public surface has no composer, there is
  nothing to carry. The `signal:pending-spec` handoff only works *inside* the authed onboarding.

### 1.3 What's MISSING for conversion

- A **public composer on `/welcome` (or `/`)** that calls the existing `/api/v3/anonymous-runs` and
  shows a real (or near-real) plan + diff, then a "Sign up to ship this" CTA that calls
  `bindToUser`. The plumbing already exists; it needs (a) the flag flipped with a tight cost guard,
  and (b) a thin frontend.
- **Public proof:** a gallery of `/shared/:token` example runs with verified scores embedded on the
  landing page (social proof + "see the output" in one).
- **Spec carry-over across the signup boundary** wired end-to-end (anon session token → signup →
  claim → prefilled composer).

### 1.4 Top 3 conversion levers (ranked)

1. **Ship the anonymous trial loop (flag + frontend + carry-over).** Highest impact: it converts
   the top-of-funnel from "sign up to look" to "look, then sign up to keep." The asset is 80% built.
2. **Embed live example runs (shared reports) on the landing page** for instant proof-of-value with
   zero backend work beyond seeding a few public tokens.
3. **Make `/` a real marketing landing** (today it redirects straight into the auth wall), with the
   composer + examples + pricing above the fold.

---

## Persona 2 — FREE (revalidate-free@test.local)

Tier facts: 15 runs **lifetime** (not monthly — see §How-to-read), 1-credit specs only, `$3` cap,
`canDeploy=false`, PR/Code review → 403, no BYOK (`canManageAiKeys=false`), 1 project / 1 repo
(`max_projects_per_org` / `max_repos_per_project`).

### 2.1 Journey through the funnel

1. **SIGNUP.** Registers (email or OAuth); `AuthService` fires `SIGNUP` and V162 auto-provisions a
   personal org (`org.is_personal=true`), routed through `freeUserPermissions`.
2. **PROJECT_CREATED.** Either via the `WelcomePage` onboarding (pick a starter spec → install
   GitHub App → land in composer) or directly. The starter-spec picker pre-fills the composer.
3. **SPEC_SUBMITTED.** `ComposePage` → `POST /api/runs` (202) → navigates to `/plan/:runId`.
   `RunOrchestrator` fires `SPEC_SUBMITTED`. Sandbox mode (no repo) is the default chip, so a FREE
   user *can* run without connecting a repo.
4. **PLAN_APPROVED.** `PlanPage` shows the plan + "Approve · charge N credits". This is the spend
   moment. For FREE, N is forced to 1 (1-credit-only restriction).
5. **RUN_COMPLETE.** Pipeline runs; `ResultPage` shows verified score + diff. `RUN_COMPLETE` fires.
6. **PR_MERGED.** Multi-repo / real-repo runs open a PR; merge fires `PR_MERGED`. Sandbox runs can't
   produce a merge, so the **last funnel step is structurally unreachable for sandbox-only FREE
   users.**
7. **Upgrade trigger.** Hitting the 15-run lifetime wall, or wanting a gated feature
   (deploy / PR review / multi-repo / BYOK).

### 2.2 Conversion friction / drop-off

- **The "15 runs/month" promise is actually "15 runs ever."** A returning evaluator who used their
  15 in week 1 finds the counter **never resets** (`assertUserQuota` lifetime branch). The
  `ComposePage` "at cap" card says *"You've used all 15 runs in this billing cycle"* — implying a
  reset that never comes. This is a trust-eroding surprise at exactly the upgrade-decision moment.
- **Plan-gate credit anxiety.** `PlanPage` leads with "**N credits**" and "Approve · charge N
  credits" but never explains what a credit *is* (1 credit ≈ $5 of buffered LLM cost), what the
  user's balance is, or that FREE refunds on failure. The number is presented as a cost without a
  denominator.
- **Plan-gate fallback defaults can mislead.** When the run object hasn't populated credit/cap
  fields yet, `PlanPage` hard-codes `credits = 3` and `costCap = 2.0` (lines 159-166). A FREE user
  can briefly see "Approve · charge 3 credits" on a run that will actually charge 1 — noise on the
  most sensitive screen.
- **Gated features fail with a dead-end error, not an upgrade path.** FREE clicking deploy/preview
  on `ResultPage` gets a plain string *"Preview deploys are gated to PRO+ tiers. Upgrade your plan
  to enable."* with **no button to /plans**. `CodeReviewPanel` / `PrReviewPanel` on a FREE account
  set `gated=true`, disable the button, and surface the backend 403 verbatim — again **no upgrade
  CTA in the panel**.
- **The one upgrade nudge points at a hidden plan.** The FREE `BillingPage` nudge says *"Bring your
  own keys, bypass the $3 cap — PRO uses your keys directly"* with a **"See PRO"** button → `/plans`.
  But PRO is HIDDEN/grandfathered: on `PlansPage` the PRO card is a **disabled "Hidden plan"** and
  BYOK actually lives on **TEAM**. So the product's primary FREE→paid nudge **routes the user to a
  plan they can't buy**, advertising a benefit (BYOK) that isn't on that plan. This is a broken
  conversion path.
- **No celebration / momentum at first success.** `FirstRunCelebration` exists only in the deleted
  legacy `components/runs/` shell and is **not wired** into the live Signal/IDE chrome. The single
  highest-dopamine moment (first green verified score) passes with no "you did it → here's what's
  next / share this / invite a teammate" moment.
- **Sandbox dead-ends the funnel.** Sandbox runs can't open a PR, so a FREE user who only ever runs
  in sandbox can never reach `PR_MERGED` — the funnel's terminal "aha." Nothing nudges them from
  sandbox → real repo.

### 2.3 What's MISSING for conversion

- **A truthful, visible usage meter.** The "All runs" card on `HomePage` already shows
  `currentRunCount / monthlyRunLimit`, and `ComposePage` shows it too — but the **label "this
  cycle" is wrong for FREE** (lifetime). Either make FREE genuinely monthly, or change the copy +
  add a persistent "X of 15 lifetime runs left" meter with an upgrade CTA as it depletes.
- **An in-context upgrade modal at every gate** (quota wall, deploy click, PR/Code review click)
  that names the *correct* paid tier and deep-links to checkout — not a toast string.
- **Credit literacy on the plan gate:** "1 credit ≈ $5 of model spend · refunded if the run fails ·
  you have N left." A `Glossary` term for "credit" (the pattern already exists for "classifier",
  "verified score").
- **A first-success moment** (re-wire `FirstRunCelebration`) that converts the dopamine into a
  next-step: open a PR, connect a repo, or invite a teammate.
- **A sandbox→repo nudge** once a sandbox run completes ("Run this against your real repo to open a
  PR").
- **Fix the FREE→paid nudge target** so it points at TEAM (the plan that actually has BYOK), or
  un-hide a self-serve solo paid tier.

### 2.4 Top 3 conversion levers (ranked)

1. **Reconcile the FREE quota story (lifetime vs monthly) + add a real usage meter with upgrade
   CTA.** This is the difference between "free trial" and "free tier," and the current mismatch
   actively erodes trust at the upgrade moment.
2. **Replace dead-end gate errors with in-context upgrade modals that target the correct tier** —
   and fix the BillingPage "See PRO" nudge to point at TEAM. Today the product's main monetisation
   prompts either dead-end or route to an unbuyable plan.
3. **De-anxietise the plan gate:** explain credits, show balance, show "refunded on failure," and
   kill the misleading `credits=3 / cap=2.0` fallback defaults.

---

## Persona 3 — PRO (revalidate-pro@test.local)

Tier facts (HIDDEN/grandfathered — no new buyers): unlimited-ish runs (`FALLBACK_PRO=20`/mo via
tier_config), `canDeploy=true`, PR review 10/mo + code review 4/mo, BYOK allowed, multi-repo (2),
high-complexity plans allowed (`assertComplexityAllowed`).

### 3.1 Journey through the funnel

1. A PRO user is, by definition, an **existing** paying customer (the tier is HIDDEN; `V176`
   "unhide as solo tier" then `V185` retired solo branding — net: no new self-serve PRO signups).
2. They run the full BUILD pipeline against real repos, use deploy/preview, run PR Review (10/mo)
   and Code Review (4/mo), and use the 4 autofix surfaces.
3. **Upgrade trigger** is **lateral, to TEAM**: they want project sharing, audit log, external
   invites, or to add a teammate. `PlansPage` shows a dedicated PRO banner: *"You're on PRO
   (grandfathered)… Switch to TEAM for project sharing, audit log, and external invites."*

### 3.2 Conversion friction / drop-off

- **Quota cliffs with no in-context upgrade.** PRO's PR-review 10/mo and code-review 4/mo throw a
  429 (`QuotaExhaustedException`, *"Monthly PR-review quota exhausted (N/10). Resets on the 1st
  UTC."*). The panel disables the button + shows the verbatim message — **no "go unlimited on TEAM"
  CTA** at the exact moment the user wants more.
- **No usage runway visibility.** Nothing surfaces "7 of 10 PR reviews used this month" *before* the
  user hits the wall, so the quota is a surprise, not a planned upgrade.
- **The grandfathered story can read as a downgrade.** The "switch to TEAM" framing buries the *new*
  capabilities; a PRO user may not see why leaving their grandfathered rate is worth it.
- **BYOK is a benefit they may not have configured.** PRO can BYOK (collapsing the $5 cap), but if
  they never set keys, `requiresCostCap` keeps showing them the cost-cap input — friction without
  the payoff being advertised in-context.

### 3.3 What's MISSING for conversion

- **Per-feature usage meters** ("PR reviews: 7/10 this month") on the PR/Code review panels and the
  billing page, with an "unlimited on TEAM" upsell as they fill.
- **A TEAM upsell that leads with the new value** (sharing, audit, invites, seats) rather than
  framing it as leaving PRO.
- **A "you're leaving credits/perks on the table" prompt** if a PRO user has unused BYOK or
  unused deploy — surface the capability they're paying for.

### 3.4 Top 3 conversion levers (ranked)

1. **Quota-meter + in-context "go unlimited on TEAM" CTA on the PR/Code review panels.** This is the
   most natural PRO→TEAM trigger and it currently dead-ends in a 429 string.
2. **Reframe PRO→TEAM around net-new value** (sharing / audit / seats), not "switch off your
   grandfathered plan."
3. **Surface unused entitlements** (BYOK keys not set, deploy never used) to increase realised value
   and stickiness, reducing churn risk.

---

## Persona 4 — TEAM (revalidate-team@test.local)

Tier facts: $39/seat/mo, **min 2 seats**, BYOK (`canManageAiKeys=true`), unlimited runs
(`FALLBACK_TEAM=-1`), unlimited PR/Code review, project sharing + audit + external invites (gated
also by `org.external_sharing_enabled`), seats self-serve via `adjustOrgSeats` (range 2-20).

### 4.1 Journey through the funnel

1. **Subscribe** (`PlansPage` → `/api/billing/subscribe` → Stripe/Razorpay). TEAM is the
   "RECOMMENDED" card and the only self-serve paid tier.
2. **BYOK setup.** TEAM is BYOK-or-managed; an org admin sets provider keys via the Org Settings
   "AI provider keys" panel (`updateOrgAiKeySettings`). Blank = leave-unchanged so one-provider
   saves don't wipe others.
3. **Invite teammates** (`inviteMember`) + adjust seats (`adjustOrgSeats`). Each new teammate is a
   `SIGNUP` → their own funnel within the org.
4. **Habitual use:** unlimited runs against the org's own LLM keys, project sharing, audit log,
   PR/Code review with no cap.
5. **Upgrade trigger → ENTERPRISE:** SSO/SCIM, cross-org sharing, contracts/standards/ADRs, model
   matrix override, SLA + CSM, no cost cap at all.

### 4.2 Conversion friction / drop-off

- **The 2-seat minimum is a hard wall for the solo-founder / 2-person-team buyer.** TEAM is the
  *only* buyable paid tier, and it costs **$78/mo minimum** ($39 × 2) even for one person — because
  PRO is hidden and there is no single-seat paid option. A solo developer who has outgrown FREE has
  **no proportionate next step**; the cheapest upgrade doubles the seat they need. This is the
  steepest price discontinuity in the ladder.
- **BYOK setup friction is front-loaded onto the highest-intent moment.** A brand-new TEAM org has
  no value until an admin pastes provider keys (the panel UX is explicitly flagged "placeholder
  pending a design pass" in the frontend CLAUDE.md). If keys aren't set, runs fall back to managed
  keys (still capped) or stall — a poor first impression right after payment.
- **Seat-add is plumbed but not looped.** `adjustOrgSeats` + `inviteMember` exist, but there is no
  *growth loop* — no "invite your team, each gets X" incentive, no in-product prompt to fill empty
  seats the org is already paying for. Paid-for-but-empty seats are pure margin leak and a churn
  risk.
- **`adjustOrgSeats` rejects reducing below active members (400)** — correct, but if surfaced as a
  raw error it reads as a bug at renewal-tuning time.
- **Sharing is double-gated.** External invites need both tier PRO+ **and**
  `org.external_sharing_enabled=true` (V198, default false) **and** ORG_ADMIN-of-own-org. A TEAM
  admin who tries to invite externally and hits the disabled flag may conclude the feature is
  missing rather than off-by-default.

### 4.3 What's MISSING for conversion

- **A single-seat paid tier (or 1-seat TEAM entry).** The FREE→TEAM jump is too big; this is the
  most likely place high-intent solo users abandon. (Product decision, but the data point is real.)
- **A guided BYOK setup wizard** at first TEAM login (test-key → green check → "you're live"),
  replacing the placeholder panel. Convert the post-payment moment into an activation, not a chore.
- **A team-invite growth loop:** prompt to fill paid-but-empty seats, show "2 seats, 1 used,"
  per-invite value framing, and a teammate-onboarding path that lands them in a shared project.
- **Self-serve discovery of external sharing** (a one-click "enable external sharing for this org"
  inside the invite flow, instead of a separate Org Settings toggle the admin must know exists).
- **A usage/value dashboard** ("your team shipped N PRs / saved $X in review time this month") to
  justify renewal and seat expansion.

### 4.4 Top 3 conversion levers (ranked)

1. **Add a proportionate single-seat paid step (un-hide a solo paid tier, or 1-seat TEAM).** The
   2-seat / $78 minimum is the ladder's biggest gap and the clearest FREE→paid blocker.
2. **Guided BYOK activation wizard at first TEAM login.** BYOK is the gating dependency for *all*
   TEAM value; front-loading it as a placeholder panel kills momentum right after payment.
3. **Build the team-invite/seat-fill growth loop** so paid seats convert into active users
   (expansion revenue + retention), instead of sitting empty.

---

## Persona 5 — ENTERPRISE (revalidate-ent@test.local)

Tier facts: unlimited runs + no cost cap (`resolveRunCostBudget` returns -1), unlimited reviews,
**per-org model-matrix override** (`PUT /api/org/me/model-matrix`, F3/V205), SSO/SCIM, cross-org
sharing, contracts/standards/ADRs, all gates open. BYOK + OpenRouter provider.

### 5.1 Journey through the funnel

1. **Entry is sales-led, not self-serve.** Both `PlansPage` and the public pricing CTA route
   ENTERPRISE to `mailto:sales@executespec.ai`. The funnel has dedicated events
   (`V3_ENTERPRISE_INQUIRY_SUBMITTED`, `V3_TRUST_PAGE_PDF_DOWNLOADED`) but the conversion is a human
   handoff.
2. **Evaluation = procurement.** The `/trust` page (Trust Pack PDF), audit log, GDPR export, the
   `.executespec/` export bundle, contracts/standards/ADRs, and the per-org model matrix are the
   procurement-evaluator surfaces. `freeUserPermissions` even special-cases TEAM/ENT solo users to
   unlock audit + member management for the procurement persona ([R8] comment).
3. **Activation** is org-scoped: SSO config, model-matrix pinning, key provisioning, seat rollout.
4. **Upgrade/expansion** is seat + scope growth, renewals on SLA + CSM.

### 5.2 Conversion friction / drop-off

- **"Talk to sales" is the only door — and on the public page it's a dead button.** On the
  authed `PlansPage`, the ENTERPRISE CTA fires a `mailto:sales@executespec.ai`. But on the
  **public** `PublicPricingPage` (`ExtraPages.tsx`, the page a prospect actually hits at
  `/pricing`), the "Talk to sales" / "Start free" / "Start trial" buttons have **no `onClick` at
  all** — they are inert. So the highest-ACV persona's primary entry point literally does nothing
  when clicked. Even where it works, a `mailto:` fails silently for users without a configured mail
  client; there is no scheduling link, lead-capture form, or "request a sandbox."
- **Procurement assets are scattered and not packaged.** Trust Pack PDF (`/trust`), audit log,
  GDPR/DSAR export, `.executespec/` export, contracts/standards — all exist but there's no single
  "Security & Compliance" hub a buyer can hand to their security team.
- **Model-matrix override (the headline F3 ENTERPRISE differentiator) is invisible pre-purchase.**
  Non-ENTERPRISE orgs see the matrix grid **read-only with an upgrade note** (`OrgSettingsPage`),
  which is good in-app, but nothing markets this capability to a *prospect* who hasn't signed in.
- **No usage/ROI reporting for renewal.** Enterprise renewals hinge on demonstrated value; there's
  no org-level "PRs shipped, reviews run, cost vs. cap" exec summary.

### 5.3 What's MISSING for conversion

- **A real lead-capture + demo-request flow** (form → CRM, or a Calendly-style scheduler) replacing
  the `mailto:`, with the dedicated funnel events already defined.
- **A packaged Security/Compliance hub** that bundles Trust Pack, audit, DSAR/GDPR export,
  `.executespec/` bundle, SSO/SCIM docs, and the SLA — one URL for procurement.
- **Pre-sale visibility of ENTERPRISE differentiators** (model matrix, SSO, cross-org) on the
  pricing/trust pages, not just behind login.
- **An org-level ROI/exec dashboard** for the renewal conversation.
- **A guided ENTERPRISE onboarding** (SSO → keys → model matrix → seat rollout) so a signed contract
  activates fast.

### 5.4 Top 3 conversion levers (ranked)

1. **Replace `mailto:` with a lead-capture + demo-scheduling flow** wired to the existing
   `V3_ENTERPRISE_INQUIRY_SUBMITTED` event. The highest-ACV door is currently the flimsiest.
2. **Package a Security & Compliance hub** to shorten the procurement/security-review cycle (the #1
   enterprise sales-cycle tax) — all the assets already exist, they're just unbundled.
3. **Surface ENTERPRISE-only differentiators pre-sale** (model matrix / SSO / cross-org) + an
   org ROI dashboard for expansion & renewal.

---

## Cross-persona prioritized conversion-improvement backlog

Ranked by ROI (impact × how-close-the-asset-already-is). Each names the funnel stage it unblocks.

| # | Lever | Personas | Funnel stage | Why it's high-ROI |
|---|-------|----------|--------------|-------------------|
| **1** | **Ship the anonymous-trial loop** — flip `v3.anonymous-trial.real-dispatch` (with a tight cost guard), build the public composer, and wire the `bindToUser` carry-over. | Anonymous → FREE | **(pre-)SIGNUP → SPEC_SUBMITTED** | Unblocks the worst drop-off (top of funnel). Backend is ~80% built and tested; this is mostly a thin frontend + a flag + proof-gallery. Converts "sign up to look" into "look, then sign up." |
| **2** | **Fix the FREE quota truth + add a real usage meter with upgrade CTA.** Decide monthly-vs-lifetime, correct the "this cycle"/"15/month" copy, and surface a depleting meter that escalates to an upgrade prompt. | FREE | **RUN_COMPLETE → upgrade** | The current lifetime-vs-"monthly" mismatch breaks trust at the exact upgrade decision. Cheap copy/logic fix, large trust + conversion payoff. |
| **3** | **Replace dead-end gate errors with in-context upgrade modals that target the *correct* tier**, and fix the BillingPage "See PRO" nudge (PRO is hidden; BYOK is on TEAM). | FREE, PRO | **upgrade trigger** | The product's main monetisation prompts today either dead-end in a toast/429 or route to an unbuyable plan. Pure conversion leakage on already-built gates. |
| **4** | **Add a proportionate single-seat paid step** between FREE and the 2-seat/$78 TEAM minimum (un-hide a solo paid tier or 1-seat TEAM). | FREE → paid | **upgrade / PLAN_APPROVED** | The FREE→TEAM jump is the ladder's biggest price discontinuity; solo high-intent users have no next step. (Pricing decision, but the structural gap is real and quantifiable.) |
| **5** | **De-anxietise the plan gate** — explain credits (1 credit ≈ $5, refunded on failure), show balance, add a "credit" Glossary term, and kill the misleading `credits=3 / cap=2.0` fallback defaults in `PlanPage`. | All paid + FREE | **SPEC_SUBMITTED → PLAN_APPROVED** | This is the single highest-traffic monetisation screen; reducing per-approval hesitation compounds across every run by every user. |
| 6 | **Per-feature usage meters** (PR reviews 7/10, code reviews 2/4) on the review panels + billing, with "unlimited on TEAM" upsell. | PRO → TEAM | upgrade | Turns a 429 surprise into a planned upgrade. |
| 7 | **Guided BYOK activation wizard at first TEAM login** (replace the placeholder keys panel). | TEAM | post-PLAN_APPROVED activation | BYOK is the gating dependency for all TEAM value; activates the post-payment moment. |
| 8 | **Re-wire `FirstRunCelebration`** into the live chrome with a next-step CTA (open PR / connect repo / invite). | FREE, all | RUN_COMPLETE → PR_MERGED | Converts the highest-dopamine moment into momentum; component already exists in legacy code. |
| 9 | **Team-invite / seat-fill growth loop.** | TEAM | expansion + retention | Paid-but-empty seats = margin leak + churn risk; `inviteMember`/`adjustOrgSeats` already exist. |
| 10 | **ENTERPRISE lead-capture + Security/Compliance hub** (replace `mailto:`; bundle Trust/audit/DSAR/SSO). | ENTERPRISE | inquiry → close | Highest ACV, currently flimsiest door + scattered procurement assets. |

### Worst drop-off risk

**The top of the funnel — getting an anonymous evaluator to their first taste of value (pre-SIGNUP →
SPEC_SUBMITTED).** Today the React app forces account creation *before* any run is possible, even
though a complete anonymous-trial pipeline (rate-limited, abuse-screened, with a signup carry-over)
sits dormant behind a default-OFF flag and an unwired endpoint. Every other funnel stage assumes a
signed-in user; the largest population — people deciding whether to bother signing up at all — meets
a wall. Backlog item #1 attacks exactly this and is the highest-leverage change in the product.

A close second is the **FREE→paid upgrade boundary**, where three independent problems compound: the
quota copy lies (lifetime sold as monthly), the gates dead-end instead of upselling, and the one
upgrade nudge routes to a hidden, unbuyable plan advertising a benefit that lives on a different
tier (#2–#4).
