# ExecuteSpec — Product Review

**Reviewer:** External, single-pass, code-first walkthrough.
**Method:** Local servers up (`spring.profiles.active=local` + `vite dev`),
fresh user registered via the actual UI, real spec submitted, real run completed.
Backend on `:8080`, frontend on `:5173`. Postgres 18 + Redis local.
**Stance:** Documentation in `CLAUDE.md` and per-package `CLAUDE.md` files
was deliberately ignored — observations come only from the code on disk and
the running app.
**Date:** 2026-05-06.

---

## 1. Executive Summary

ExecuteSpec is positioned as **"Ship AI-written PRs you can actually merge."**
Under the hood it is a multi-tenant Spring Boot 3 backend (~1000+ Java files,
93 REST controllers, 173 Flyway migrations on disk + 178 applied to local DB,
50+ packages) driving a React 18 + Vite frontend with two coexisting UI
surfaces ("v2" sidebar, "v3" top-bar) that share the same Zustand stores and
API client.

The platform is **technically alive end-to-end**: a fresh user can register,
log in, type a spec into a composer, and get a working multi-file project
checked out by an LLM agent in under a minute. I did this. The output Python
CLI was real, type-hinted, idiomatic, and built clean on first pass
(`pipelineResult: PASSED`, `accuracyScore: 95`).

**But the product layer is fractured.** A single completed run shows up as
"Ready" in one place, "Shippable" in another, and "Needs review" in a third.
The plan-approval gate — the entire commercial signal of the product — is
broken on the v3 chat surface that the dispatcher routes new users to.
Onboarding silently produces 401s. Tier numbers in the marketing copy
disagree with the database. Five Flyway migrations are applied to the live
DB but are missing from the codebase, and the build silently tolerates this.

Net read: **the engine works; the product wrapper around it is several
overlapping half-merged redesigns deep, and nothing is treating "what the
new user actually sees" as a coherent surface.**

---

## 2. What the product actually does

Reading the controllers + entities (not the docs), the platform offers **six
distinct customer-facing capabilities**, in roughly this order of investment:

1. **Spec → PR runs.** User types English (`POST /api/runs`). A planner
   classifies the spec (SmartClassifier → SINGLE_SHOT/MEDIUM/LARGE/LEGACY),
   produces a task graph, gates on user approval, then a sequential worker
   agent calls native LLM tools (read/write/edit file, shell, git, build,
   completeTask) until it self-terminates. A reviewer agent runs general
   + wiring passes. A validation report records build/test/reviewer signals
   plus an LLM "accuracy" score. The run can auto-push to GitHub as a PR.
   Source: `integration/RunOrchestrator.java`,
   `agent/{PlannerAgent, WorkerAgent}.java`,
   `harness/SequentialExecutor.java`,
   `reviewer/ReviewerAgent.java`,
   `report/ValidationReportService.java`.

2. **Project workspace.** A `Project` belongs to an `Org`, holds a list of
   `ProjectRepo` links (ORG- or USER-scoped), per-repo codemaps, project-wide
   architecture doc (versioned), generated docs (EXPLANATION + SETUP),
   project memory (compacted ledger of prior runs), project chat (separate
   from runs), uploaded RAG documents (pgvector). All the "Knowledge" and
   "Memory" surfaces are about understanding the codebase well enough to
   write good plans against it.
   Source: `project/`, `codeintel/`, `memory/`, V81–V97 migrations.

3. **Pre-built solutions ("Library") + migration recipes ("Migrations").**
   Two product lines aimed at converting "what do I want to build?" into
   "pick a card, fill the slots, ship a PR." Library = features (Login with
   GitHub, Stripe Checkout, Mapbox, etc.). Migrations = framework jumps
   (Mocha→Vitest, Webpack→Vite, JS→TS, etc.). Most are paywalled to PRO+.
   Source: `readymade/`, `migration/` packages, `frontend/src/v3/pages/Library*.tsx`,
   `MigrationsPage.tsx`.

4. **Project-level "Execution View" — multi-repo, multi-solution launches.**
   A heavier flow than a single `/api/runs` call: picks repos, runs an
   architectural assessment, decomposes a requirement into per-repo
   `ExecutionSolution`s, sequences them with dependency resolution and file
   conflict detection, opens per-repo PRs cross-linked back. Tier-gated
   to TEAM+/ENTERPRISE. Source: `execution/` package, V89/V90.

5. **Code-quality agents.** PR Review (line-anchored verdicts on a single
   PR) and whole-repo Code Review (semgrep + LLM triage, markdown report).
   Tier-gated, separate quotas.
   Source: `prreview/`, `codereview/`, V172.

6. **Enterprise governance.** Engineering standards (org-level + project
   override), ADRs (`.executespec/arch/adrs/<n>-<slug>.md`), architectural
   invariants, cross-repo contracts (OpenAPI / GraphQL / Proto / JSON
   Schema), drift detection, activity feed (partitioned), DSAR export, SSO
   stub, GitHub App stub. Most of this is schema-and-controller; UI surface
   is sparse.
   Source: `standards/`, `adr/`, `arch/invariants/`, `contracts/`,
   `activity/`, `governance/`, V131–V139.

Plus the universal supports: tier system (FREE/PRO/TEAM/ENTERPRISE),
multi-gateway billing (Razorpay + Stripe), feature flags, rate limiting,
8-layer security pipeline, observability stack.

**The pitch that holds together:** "Type what you want; we'll turn it into a
draft PR. Pick a recipe; we'll fork it for your stack. Connect a repo; the
planner will have read the codebase first."

**The pitch that doesn't:** "And we also do code review, and PR review, and
enterprise contract drift, and ADRs, and standards, and architecture
invariants, and a chat-with-your-codebase, and an Execution View for
multi-repo squads…" — the surface area is large for a tool whose primary
moment-of-truth (a clean spec → a merged PR) still has wiring bugs in its
default UX path.

---

## 3. The new-user journey, walked

### 3.1 Marketing → Login

Tagline at `/login`: **"Ship AI-written PRs you can actually merge."**

Login page is clean: dark theme, Continue with Google / GitHub, then
email/password fallback, Forgot link, Sign-up-free CTA, legal links in the
footer. Zero clutter. (`frontend/src/pages/auth/LoginPage.tsx`.)

### 3.2 Register

`/register` shows a persona toggle (Solo dev / Team 2+ devs / Enterprise) +
copy: **"We'll send you straight to the composer."** Password validation has
live ✓ checks (10+ chars, upper, lower, digit, special, no spaces). This is
the highest-quality screen in the entire app.

**Bug 1 — silent 500 on register.** I filled valid data, submitted, and got
a generic red banner: **"Something went wrong on our end. Please try
again later."** Backend was actually OOM-dead. The frontend's
`useAuthStore.register` swallows everything that isn't a recognized
validation error and renders that string. There is no retry, no diagnostic,
no error code. From the user's seat this is indistinguishable from a real
"the product is broken." (Cause traced to JVM crashing silently — see §6.1.)

After a backend restart with `-Xmx2g` + OTel exporters disabled, register
worked: `POST /api/auth/register → 201`.

### 3.3 Login (and the email-verification trap)

`POST /api/auth/login → 401 {"error":"unauthorized","message":"Invalid
credentials"}` on a freshly-registered, unverified user.

**Bug 2 — misleading auth error.** The user just registered; the password
is correct. The actual reason for 401 is `status=PENDING_VERIFICATION` on
`app_user`. The UI never showed a "check your email" success state on the
register flow (probably hidden by Bug 1's catch-all error banner), and the
login error claims credentials are wrong.

Workaround during this review: flip `status='ACTIVE'` and
`email_verified=true` directly in Postgres.

### 3.4 First minute in the app — `/projects/empty`

After login the dispatcher (`HomeRedirectPage`) lands a zero-project user
on `/projects/empty`:

> ✦
> **What do you want to build, Reviewer?**
> Describe what you want to build and we'll ship a draft PR you can
> actually merge. Your free plan covers 5 PRs / month — about 5 small
> features or 3 large refactors.
> [ Create my first project → ]
> *Already have a GitHub repo? Attach it first →*

This is good. Personal greeting, on-brand promise, two clear paths.

**Click 1 — "Create my first project".** Auto-creates a project named
`"Reviewer's first project"`, provider Anthropic, no repos linked, drops
the user on the v3 ProjectHomePage with a chat-thread composer. No naming
prompt, no template chooser. **One click → working composer.** This is
correct for a throw-away first run.

**Click 2 — "Attach it first →".** This link (a `<a>` to `/org/projects`)
yanks the user from the v3 top-bar surface into the v2 AppLayout sidebar
surface — a different chrome, different IA, different visual language.
A first-time user has no idea this is the same product.

### 3.5 Submitting the first run

Composer reads:
> *"What do you want to build? Describe it in plain English — we'll ship
> a draft PR you can merge."*
> [ Plan & review · Advanced ]   [ Submit ]
> Suggestions: "Add OAuth login to my Next.js app" / "Refactor a React
> component to TypeScript" / "Build a Stripe checkout endpoint"
> ⌘ + Enter to submit

I typed: *"Build a small Python CLI that converts CSV files to JSON."*
Submit.

The thread immediately shows:
- A user bubble with the spec (right-aligned).
- An assistant bubble with `● Planning…`.

Within ~5 seconds the assistant bubble morphs into a card with `Plan ready`
+ run title + **`Open run`** button. So far so good.

### 3.6 The plan-approval gate is broken on the default surface

`GET /api/runs/{runId}` reports `status: "AWAITING_APPROVAL"`. The backend
wants the user to confirm 1 credit / $0.05–$0.09 cost before it spends.
A `PlanCard` component exists at `frontend/src/v3/components/PlanCard.tsx`
designed exactly for this — it lazy-loads `getRun`, shows the plan + cost
estimate, and has Approve / Adjust / Cancel buttons. It is **never rendered**
on this run.

Why: in `frontend/src/v3/components/Composer.tsx:148-154`, the optimistic
bubble is pushed with `status: 'PLANNING'` (the React-side string, not the
backend status). In `frontend/src/v3/pages/ProjectHomePage.tsx:313-323`,
`PlanCard` only renders when `bubble.status === 'AWAITING_APPROVAL'`. There
is **no SSE listener anywhere in `frontend/src/v3/`** that updates a bubble's
status as the run progresses (`grep -rn 'EventSource\|useSse' frontend/src/v3`
returns nothing). The store's `seedFromRuns` only re-fetches on a `loaded`
miss, and re-navigating to the same project hits the cache.

**Result:** the user sees `Plan ready · Open run`, clicks Open run, lands on
the v3 RunDetailPage… which **also has no `AWAITING_APPROVAL` handling**
(`grep -n AWAITING_APPROVAL frontend/src/v3/pages/RunDetailPage.tsx` →
zero hits, except in a docstring 71). The page renders only the changed-
files diff (which at this stage is just `.gitattributes` + `.gitignore`
scaffold) and a navigation header. There is **no Approve button on the
default v3 surface anywhere**.

The v2 component `frontend/src/components/runs/RunDetail.tsx` does have the
gate UI, but no route in `App.tsx` mounts it for runs (the only run-detail
route is the v3 one). The user is fully stuck.

I unblocked the run by curl-ing `POST /api/runs/{id}/approve` directly with
a JWT. **A new user has no such escape hatch.**

This is the single biggest UX bug in the product.

### 3.7 Run completes — and the verdict is wildly inconsistent

After ~30s the run was COMPLETE. Backend report payload (verbatim from
`GET /api/runs/{id}/report`):

```json
{ "pipelineResult": "PASSED",
  "issues": [], "checksPassed": ["COMPILATION","CODE_REVIEW","ACCURACY_CHECK"],
  "accuracyScore": 95, "accuracyGrade": "A",
  "reportMarkdown": "## Validation Summary\n6 files created (Python, JSON), 1 task completed,
  COMPILATION and CODE_REVIEW checks passed on first attempt." }
```

**Bug 3 — three surfaces show three different verdicts for the same run.**

| Surface | URL | Verdict |
| --- | --- | --- |
| v2 Project Dashboard "Last Run" card | `/projects/{id}/dashboard` | **● READY** (green) |
| v2 Runs list row | `/runs` | **● Shippable** (green) |
| v3 Run Detail header | `/projects/{slug}/runs/{shortId}` | **Needs review** (orange) |

Each surface computes its own verdict from a different mix of signals
(`buildPassed`, `reviewerVerdict`, `verifiedScore`, `accuracyScore`,
`statusReason`). There is no single verdict resolver.

**Bug 4 — the v3 detail page renders a "What went wrong" panel on a
CLEAN_COMPLETION run.** Verbatim text:

> What went wrong
> The run terminated with status COMPLETE. Reason: CLEAN_COMPLETION.
> [ Re-prompt with this context ] [ ← Back to project ]

There was nothing wrong. `RunFailureSummary` (the component rendering this)
either has an inverted condition or the page mounts it unconditionally for
the not-`READY` verdict bucket. Same root cause as Bug 3.

**Bug 5 — verifiedScore vs accuracyScore disagree by 70 points.** The v2
dashboard's score ring shows **75** (verified) with **est. 25** (accuracy
estimate, struck through). But `accuracyScore=95` in the backend payload.
Some surface is reading the wrong field, or the score-ring's "est." label
is fed by the wrong source.

**Bug 6 — placeholder string leaks to UI.** The completed-run bubble in the
v3 thread says: **"Reviewer fixed N issues"** — the literal letter N, not
the count. Template variable not interpolated. `frontend/src/v3/components/ResultCard.tsx`.

**Bug 7 — Insights success-rate shows 0% in red while a run is in flight.**
While my run was WORKING, `/insights` rendered `Success rate · 0% (red)` /
`Runs · 1`. With one run not-yet-terminal, the right value is `—` or
`pending`, not `0%`.

### 3.8 The actual generated code

Despite all the verdict noise above, the run actually shipped good code.
`csv_to_json.py` had a shebang, a real docstring, type hints
(`csv_file_path: str, json_file_path: str = None`), `if __name__ ==
'__main__'` wiring, error handling, and tests. The README/Dockerfile/.gitignore
came along for free. The platform's spec→code engine is genuinely working.
This is the product's actual moat. The wrapper UX is what's hiding it.

### 3.9 The rest of the surface

Continuing the walk on a logged-in FREE user on a single project:

- **`/library`.** Solid concept-execution. Card grid with category chips
  (People & Money 8, Marketing & Reach 7, UI & Growth 7, Data &
  Infrastructure 6), per-card credits estimate ("≈3–7 credits"), file count
  ("6–14 files"), tier lock badges. Most cards are 🔒 PRO. A FREE user has
  **3 unlocked cards out of ~28** which is harsh — at this discoverability
  level the page mostly serves to advertise the upgrade.

- **`/migrations`.** Same pattern. 12 recipes, all 🔒 PRO. Zero unlocked
  for FREE — the page is purely an upsell wall.

- **`/insights`.** Five KPI tiles (Runs / Spend / Success rate / Active
  projects / Needs attention) + Spend by project + Needs attention list.
  Clean. Bug 7 above. Time-range chips work. No drill-down on any tile —
  click a number, nothing happens.

- **`/settings`.** Long page. Tier banner ("Free · 10 runs / month · no
  credit card") — **wrong**, see Bug 8 below. GitHub Integration form (PAT
  + owner). Org-level AI keys section ("No org keys"). Key Source &
  Provider Policy = "Hybrid". Notification preferences with **30+ event
  types in a flat list** (RUN_FAILED, RUN_PLAN_APPROVED, RUN_PLAN_READY,
  RUN_SUBMITTED, STANDARDS_EXCEPTION_ADDED, STANDARDS_EXCEPTION_REMOVED,
  STANDARDS_OVERRIDE_APPLIED, STANDARDS_VIOLATION_DETECTED,
  AI_PROVIDER_CHANGED, ARCHITECTURE_DOC_REGENERATED, AUTO_DETECTION_RAN,
  CODEMAP_REGENERATED, MEMORY_LEDGER_COMPACTED, QUOTA_APPROACHING_LIMIT…).
  Privacy: PostHog opt-in toggle. System Health card.

- **`/billing`.** Tier card "You're on Free · 5 of 5 runs left this month"
  (correct). Plans grid: **Free $0 ("10 runs / month")** vs **Pro $20
  GRANDFATHERED ("Existing subscribers only — new buyers, choose Team")**
  vs **Team $39/seat** vs Enterprise. The new-buyer Upgrade arrow at the
  top points at PRO, which is closed to new buyers. The user has to scroll
  past the "real" upgrade to reach Team.

- **Avatar dropdown** (the only entry point to settings/billing/integrations
  /insights since they aren't in the top nav): Account / Billing & plan /
  Integrations / Insights / Help / Sign out. Notably **missing** the
  surfaces that *do* have routes wired in `App.tsx`: `/team`, `/trust`,
  `/pricing`, `/runs/new/sandbox`, `/exec`, `/audit`, `/invoices`,
  `/accounting`, `/admin/*`. A user with permissions to those routes
  cannot find them without typing the URL. Same goes for the v2 sidebar's
  `Analytics`, `Dashboard`, `Invoices`, `Settings` items — they only show
  up when the user is on a v2-AppLayout route.

- **`/projects/{id}/dashboard`** (v2). Sidebar chrome appears. Project
  header shows ACTIVE / ANTHROPIC / REPOS 0 / DONE 1 / SCORE 75 / SPEND
  $0.25. Five tabs (Pulse / Repos / Runs / Code / Chat) — note the
  frontend `CLAUDE.md` claims "4 tabs" but I counted 5. Pulse tab cards:
  Last Run hero / Verified-score ring / Health & debt / Knowledge /
  Activity. **Information-rich, useful**. Same project as the v3
  ProjectHomePage chat thread but a totally different mental model.

- **`/runs`** (v2). Status filter chips: ALL / PLANNING / WORKING /
  AWAITING APPROVAL / COMPLETE / **PAUSED** / FAILED / ARCHIVED. The
  PAUSED chip is interesting — V168 added pause/resume support to the
  schema, but the v2/v3 user-flow surfaces don't expose pause/resume
  buttons on a run anywhere I could find. Schema-ahead-of-product.

I did not exhaustively cover `/team`, `/trust`, `/exec`, `/admin/*`,
`/audit`, `/invoices`, `/accounting`, `/integrations` because they don't
appear in the default new-user navigation. They exist as code; whether
they're live is not visible from the main UX surface.

---

## 4. Critical issues — wiring + UX

Ranked by "would I block ship of a real GA on this?"

### P0 — block-ship

**P0-1 — v3 plan-approval gate is non-functional on the default surface.**
Section §3.6. Every new user submitting a spec from the chat composer hits
this. Fix: make `useV3ProjectStore` either (a) poll `/api/runs/{runId}`
every N seconds while a bubble is non-terminal, or (b) subscribe to the
existing scoped SSE token endpoint and morph bubble status on
`RUN_STATUS_CHANGED`. PlanCard already handles the rest correctly.

**P0-2 — register surface eats backend errors.** Section §3.2. The
`AuthService` register flow returned a 500 because the JVM died, and the
user saw an opaque banner with no signal that anything was retryable. Fix:
on 5xx, the register form should show "Service unavailable, retrying in
N…" and actually retry once. On 4xx, it should show the message body's
`error`/`message` fields, which the backend already populates correctly.

**P0-3 — verdict inconsistency across run surfaces.** Section §3.7
Bugs 3+5. There's no canonical `VerdictResolver` shared by `RunDetailPage`,
`RunCard` (v2), `RunCard` (v3 ResultCard), `ScoreCard`, `PulseTab`. Each
re-derives. Fix: a single `lib/verdict.ts` that takes
`(detail, report) → V3Verdict` and is the only thing any surface calls.
This is also the root cause of Bug 4 (the "What went wrong" panel on a
clean run).

### P1 — significant

**P1-1 — phantom Flyway migrations in local DB.** `flyway_schema_history`
has rows for **V174–V178** (`add cost to arch doc artefacts`, `external
project sharing`, `unhide pro as solo tier`, `solo annual and founder
pricing`, `user cost cap on pipeline run`). The codebase only contains
`V1–V173`. The application.yml setting
`spring.flyway.ignore-migration-patterns: "*:missing"` (line 116) silently
swallows this. **It also disables detection of any other accidentally-
deleted migration.** Some entity in the JPA model may already match the
applied-but-missing schema (e.g. cost columns on arch doc artefacts) and
will quietly disagree with a fresh DB. Either restore the missing
migrations or do a `flyway repair` and remove the `*:missing` pattern.

**P1-2 — tier copy drift.** Three independent disagreements in production
copy vs DB facts:
- `/billing` Free plan card: "10 runs / month" — DB `tier_config.FREE.monthly_run_limit = 5`.
- `/settings` tier banner: "10 runs / month · no credit card" — same drift.
- `/billing` Pro plan card: "$20 / 20 credits / month (grandfathered cap)"
  — DB `tier_config.PRO.monthly_run_limit = 30`.

Strings are hardcoded in `frontend/src/pages/SettingsPage.tsx` and
`frontend/src/v3/pages/BillingPage.tsx`. Fix: drive every plan-card number
from `GET /api/billing/plans` (or `tier_config` via a dedicated
entitlements endpoint), not from inline JSX strings. There is already a
`EntitlementsController` shaped for exactly this.

**P1-3 — Pro tier is the upsell target on Billing for new users, but it's
closed to new buyers.** "Existing subscribers only — new buyers, choose
Team." The Upgrade button on the FREE banner should link to TEAM, not PRO,
or PRO should not visually anchor the column it sits in. `BillingPage.tsx`.

**P1-4 — run-detail page mounts a `RunFailureSummary` panel for clean runs.**
Bug 4. Either gate the component on `verdict === 'FAILED'`, or rename it
to `RunReasonPanel` and have it render a positive variant on
CLEAN_COMPLETION. As-is the component name and the data path are both
contradictory.

**P1-5 — placeholder string `N` leaks to UI.** `ResultCard.tsx`. Easy fix.

**P1-6 — backend dies silently under default `local` profile.** The
JVM crashed mid-session with no error log, no stack trace, no exit code
visible in the `mvn spring-boot:run` output. Likely OOM cascade from the
OTel span exporter retrying every 5–10 seconds against a non-existent
local Tempo collector at `:4318` for the entire session, plus the
launch.json default of `-Xms4g -Xmx12g` against a Mac with constrained
memory. Two fixes:
- Default the OTel exporters to `none` when `executespec.observability.enabled`
  is not explicitly set, instead of letting them spin endless `Connection
  refused` errors.
- Reduce `local`-profile heap to `-Xms256m -Xmx2g`. There's nothing in
  local dev that needs 12GB.

### P2 — quality of life

**P2-1 — Insights "Success rate 0% (red)" while a run is in flight.** Should
be `—` until the first terminal run.

**P2-2 — login error for unverified user says "Invalid credentials"**.
Should say "Verify your email first — we sent a link to <email>. [ Resend ]".
The v3 register flow should also show a verify-email confirmation screen on
201 instead of returning the user silently to the form.

**P2-3 — top nav vs avatar-dropdown vs deep-only-URL routes.** A new-user
audit found at least 9 protected routes that aren't reachable from the v3
top nav or avatar dropdown: `/team`, `/trust`, `/pricing`, `/exec`,
`/audit`, `/invoices`, `/accounting`, `/admin/*`, `/runs/new/sandbox`.
Either these should appear in the avatar drop-down (filtered by role/tier)
or the codebase should explicitly retire them.

**P2-4 — v2/v3 surface schism.** Same project lives at two URLs with
incompatible UIs. `/projects/{id}/dashboard` (v2: 5 tabs, sidebar, KPIs,
score ring) and `/projects/{slug}` (v3: chat thread, top bar, no
KPIs). They cross-link to each other. New users have no way to know
they're the same product; veterans have to mentally context-switch. This
is the long pole — not a bug per se, but the largest UX cost in the app.

**P2-5 — Library + Migrations are mostly tier walls for FREE users.** Of
~28 cards across both surfaces, ~5 are unlocked. A FREE user lands on
either page and sees mostly padlocks. Either give FREE 1–2 macro-groups
unlocked, or hide locked cards behind a "Show all → upgrade" expander so
the page doesn't feel like a 90%-paywall.

**P2-6 — Notification prefs flat-list of 30+ event types.** Group by
"Runs / Standards / Architecture / Quota / Admin". Default sensible
opts (most users don't need `MEMORY_LEDGER_COMPACTED`).

**P2-7 — `/projects/empty` "Attach it first →" link sends user to v2
chrome.** `/org/projects` is a v2 AppLayout-wrapped route. Within ten
seconds of registering, the user has been to two visually-different
products. Either rebuild the attach-repo flow as a v3 modal/drawer, or
have the link route to `/projects/{slug}#attach-repo` and have the v3
ProjectHomePage open the existing "Attach repo" drawer.

---

## 5. What's missing — product layer

These aren't bugs. They're things a product reviewer notices the absence
of, given the depth of the rest of the platform.

1. **No "what's the cost going to be?" preview before submit.** The user
   types into the composer, hits Submit, and the cost-estimate gate
   appears later — but on a surface that's broken (P0-1). Even on the
   Execution View flow, the estimate chip lives in `ExecutionSetup` and
   appears only after debounce. A pre-submit estimate inline in the
   composer ("This looks like a small / medium / large run · ~$X–$Y · 1
   credit") would close the loop.

2. **No "this run will modify these files" preview at plan time.** The
   plan JSON has `touchesFiles: []` — empty for SINGLE_SHOT runs. For
   MULTI_TASK runs there's a hint of file paths but it's not surfaced in
   the v3 PlanCard. Users approving plans for production projects need to
   see the blast radius.

3. **No cancel-while-running affordance on the v3 surface.** v2 has a
   persistent Cancel button on the Launch header (per the agent notes).
   v3 does not.

4. **No way to see "what does my repo look like to ExecuteSpec?" before
   running.** The codemap + architecture doc + project memory exist server-
   side. There's a Knowledge tab in the v2 dashboard that touches this,
   and `/projects/{slug}` mentions "Generate write-ups", but for a new
   user there's no "preview-the-context" step before the first run.
   Especially relevant given the 95-vs-75 score discrepancy I saw — if
   the LLM grades itself differently than the deterministic verifier,
   showing the user the inputs to both would help calibrate trust.

5. **No on-platform diff/preview before pushing to GitHub.** The auto-
   push-on-success default is on (V96/V97 migrations). For a FREE user
   trying to evaluate the product, the rapid path is "type spec → see
   committed PR." There's no "stage as draft, let me look first" mode in
   the default UI. Workspace files are visible in the run-detail page but
   they're already commit-tied.

6. **No way to retry a run with a tweaked plan.** "Re-prompt with this
   context" exists. There's no "edit the plan, keep the workspace,
   re-run" path. For a one-shot product whose first plan might be 80%
   right, this would save credits.

7. **The "Execution View" (multi-repo, multi-solution) is invisible to a
   new user.** It only appears under `/projects/{id}/executions/new` —
   never linked from any nav or empty state. Whole product line buried.

8. **The "Code Review" + "PR Review" agents (`/api/code-reviews`,
   `/api/pr-reviews`, V172) have no top-level UI surface at all.** The
   frontend `ProjectPrRollup` and `CodeQualityPanel` exist on a feature
   branch (`feature/code-review-agents`, per CLAUDE.md mentions). On the
   merged code today, a FREE-or-paid user trying to use these has to know
   to call the API directly. This is the largest "shipped backend, no
   frontend" gap I saw.

9. **No first-run onboarding overlay on the composer.** A coachmark
   tooltip — "Type a small task here. Try one of the chips. Plan +
   approve takes ~30s. Free runs reset on the 1st." — would dramatically
   reduce the abandonment risk if the run-approval gate is fixed (P0-1).

10. **No `/exec-demo` link from the public landing.** The route exists,
    is auth-free, and is meant to be the procurement-conversion path
    ("Selling-point #6 — public anonymized exec snapshot"). It is
    unlinked from anywhere I could find.

---

## 6. Backend strengths and concerns

### 6.1 Strengths

- **The orchestration is real.** I submitted a spec, watched the planner
  classify (`SINGLE_SHOT`), generate a 1-task plan, gate, hand to the
  worker, return COMPLETE in ~30 seconds with build + reviewer + accuracy
  signals. This is what the product is selling. It works.
- **Cost tracking is honest.** `costEstimateLow=$0.05`,
  `costEstimateHigh=$0.0875`, `creditsCharged=1`, `hardCapUsd=$5.00`,
  actual cost surfaced after run. Per-phase accumulator + per-task tokens
  on rows. This is enterprise-grade.
- **Plan validation graph (V109) is shipped.** Each task has
  `meetsRequirementsJson` linking back to plan-level
  `RequirementSpec`s. This is the right shape for "did we do what the
  user asked?" — the surface layer just isn't using it yet.
- **Tier system is well-modeled.** `tier_config` has `monthly_run_limit`,
  `max_projects_per_org`, `max_repos_per_project`, `pr_review_enabled`,
  `code_review_enabled`, `pr_review_monthly_quota`,
  `code_review_monthly_quota`, `lifecycle_state`. Enforcement is in
  `TierEnforcementService` + `TierPermissionService` with reasonable
  fallback constants. The data layer is more mature than the UI exposes.
- **Eight-layer security pipeline + RBAC.** Input sanitizer, code abuse
  scanner (crypto, reverse shells, PowerShell, DNS exfil, metadata),
  shell sandbox, file sandbox, process isolation, container hardening,
  configurable CSP/HSTS, rate limiting per endpoint group + tier. CSP
  was visible on every response of my walkthrough.
- **Two payment gateways routed by region** (Razorpay default in INR,
  Stripe in USD). Webhooks HMAC-verified. Audit trail.
- **Dual-write to `activity_audit` + `activity_feed` is correctly
  separated** by surface (compliance vs customer-facing). Partitioning by
  month in V133 is right.

### 6.2 Concerns

- **93 controllers is a lot.** Several look like test scaffolds left in
  prod (`NativeToolsTestController`, `ExecutorTestController`,
  `PlannerTestController`, `ReviewerTestController`, `WorkerTestController`,
  `ContextStoreTestController`, `ToolRegistryTestController`,
  `MetricsTestController`, `RouterTestController`). They're under
  `/api/v2/test/*` and registered as `@RestController` — they ship to
  production. Either gate on `@Profile("dev|local")` or move out of
  `controller`/`api` packages.
- **`OTLP exporter` permanently retrying against unreachable `:4318`
  in local dev** is a production-quality smell — the same code path runs
  in dev/prod. If the collector is down in prod the same loop will run
  forever, leaking memory and adding latency to every span context. Use
  the OTel SDK's resilience config to drop spans rather than retry
  indefinitely.
- **OAuth credentials hardcoded into `application-local.yml` line 24,
  29.** These are real-looking client IDs and secrets (presumably the
  developer's personal accounts). A `git grep` would surface them. They
  should be `${GOOGLE_OAUTH_CLIENT_SECRET:}` with a no-default and the
  dev should source-`.env`. Same for `app.mail.smtp.password` (`smdpsanatyrtlcxa`)
  and Razorpay test keys.
- **`spring.flyway.ignore-migration-patterns: "*:missing"`.** Already
  flagged P1-1. This is a permanent foot-gun in CI. The justification in
  the comment ("the V119 orphan from the preview-engine repo split") is
  reasonable; the fix shouldn't be a blanket `*:missing`. Targeted
  `flyway baseline` or repair would be cleaner.
- **Per-package `CLAUDE.md` files contradict the live state.** I was
  shown one from `frontend/CLAUDE.md` claiming "4 top tabs (Pulse / Runs
  / Code / Chat)" — the actual app has 5 (Pulse / Repos / Runs / Code /
  Chat). The doc is the source of truth for nobody.
- **The base `CLAUDE.md` in the repo root is 191kB.** It is the largest
  single file in the project after `package-lock.json` (and several
  `.idea` blobs). It is stale by self-admission of the user. Recommend
  retiring it and starting a one-page operating doc.

---

## 7. Architecture concerns

- **Two ongoing parallel UI rewrites.** v2 sidebar and v3 top-bar share
  routes. There is no evidence of a depreciation plan ("v2 retires on X
  date"). The cohabitation invariant — both surfaces always work, both
  surfaces always render — multiplies frontend complexity by ~1.7x. Half
  the components in `frontend/src/components/runs/` (v2) are mirrored
  with subtly-different versions in `frontend/src/v3/components/`. This
  is sustainable for one quarter; it's tech-debt cancer for a year.
- **Two project-home concepts.** ProjectHomePage (v3, chat) and
  ProjectDashboardPage (v2, KPI grid) target the same data. The v3
  composer is the new-user moment; the v2 dashboard is the daily-driver
  moment. They share no state. Cross-linking each as the "escape hatch"
  for the other tells me nobody is sure which one is the canonical home.
- **Feature surface > validation surface.** The platform has `arch/
  invariants/`, `contracts/`, `standards/`, `adr/` packages, all with
  controllers, all with migrations. The UI exposure of these is a single
  panel here and there. The build-don't-ship cycle has clearly outpaced
  the product surface. Either pull these in until they have UI, or
  schedule the UI sprint.
- **Three "next-thing-on-the-roadmap" branches active in the docs**
  (`feature/product-split-prep`, `feature/code-review-agents`,
  `feature/enterprise-readiness-eval`, `feature/hierarchical-planner`).
  None merged to main. The cumulative gap between what's on disk and what
  ships is a source of ambient confusion (e.g. the V172/V173 schema is
  applied locally per the agent's report, but the controllers and
  frontends for `/api/code-reviews` are not on the merged branch I'm
  reviewing). Recommend: aggressively merge or aggressively delete.

---

## 8. Recommendations — priority-ranked

### Ship within a week
1. **Fix v3 plan-approval flow** (P0-1). Polling or SSE — either is fine.
   This is one component change. Without this, the marketing tagline
   "Ship AI-written PRs you can actually merge" is technically false on
   the default surface.
2. **Single VerdictResolver** (P0-3). Replace inline derivation in
   ResultCard, RunDetailPage, RunCard (v2), PulseTab, RunsList row with a
   shared util. Pin with a unit test against a small matrix of report
   payloads.
3. **Drive plan-card numbers from the backend** (P1-2). Even just
   reading `tier_config` rows once on app load and threading limits into
   `useAuthStore` would prevent the 5/10/20/30 drift.
4. **Restore the missing Flyway migrations** (P1-1) and remove the
   `*:missing` blanket pattern. Or write a CI check that fails when the
   DB has rows for migrations not on disk.
5. **Tame the OTel exporter** in local. `OTEL_TRACES_EXPORTER=none` by
   default unless explicitly enabled.

### Ship within a month
6. **Retire one of v2 or v3.** Pick one project-home concept. Pick one
   run-detail concept. Pick one navigation chrome. The surface schism is
   the largest UX cost; halving it is the largest UX win. My read is
   v3 should win for new-user/composer flows, v2 panels should be
   ported to v3 chrome (the v2 dashboard's KPI ring + Health & debt cards
   *are* better than the v3 chat for daily-driver work — port them, don't
   delete them).
7. **Add a single `lib/glossary.ts`-style content-source for plan copy,
   tier copy, and verdict copy.** Stop hardcoding "5 runs / month" in
   eight places. There's already a `glossary.ts` per the doc — extend
   the pattern.
8. **Group the notification prefs.** And opt the FREE user in to only
   `RUN_FAILED` + `QUOTA_APPROACHING_LIMIT` by default.
9. **Surface the "Code Review" and "PR Review" agents** (V172). These
   are paid-tier features that have no UI on the merged code; they're
   the strongest commercial differentiator after the spec→PR core. Even
   a single button on each PR row in `ProjectPrRollup` would close the
   loop.

### Ship within a quarter
10. **First-run coachmark on the composer**: spec → plan → approve →
    diff → cost. ~5 short tooltips. Reduces the abandonment risk on
    the run-approval flow even after P0-1 lands.
11. **Pre-submit cost estimate inline in composer.** The data is already
    available via `EstimationService`.
12. **Decide what's in vs out for v1.0 GA.** Standards / ADRs /
    Invariants / Contracts are interesting B2B differentiators. They
    should either get UI (sufficient for a sales demo) or be marked
    explicitly as enterprise-only schema-back features. Right now they
    sit in an in-between "shipped backend, hidden frontend" state that
    doesn't help any persona.
13. **Audit the 410-Gone v1 routes** (`/runs/{id}/clarify`, `/chat`,
    `/apply`, `/iterate`, `/merge`, `/retry`, `/pause`, `/resume`,
    `/rework`, `/implement-suggestions`, `/edit-plan`, `/api/feasibility`)
    in the frontend. Either remove the client call sites entirely or
    re-implement the V2 equivalents. Today the client has dead code that
    will silently 410 if anyone wires it up.

---

## 9. Closing read

ExecuteSpec is one of those products where the **engine is genuinely
ahead of the wrapping**. The `RunOrchestrator` → `PlannerAgent` →
`SequentialExecutor` → `WorkerAgent` → `ReviewerAgent` chain produced
working, idiomatic, tested Python in under a minute on first try, with
honest cost accounting, on a $0 budget. That's hard to do.

What's holding the product back is that the layer between *that engine*
and *the user* is currently three half-merged redesigns deep, with no
single owner of "what does the FREE user see in the first 60 seconds?"
The plan-approval gate — the literal point at which the product asks the
user "is this worth $0.05 of credits?" — is broken on the surface that
new users land on, and a single line in `useV3ProjectStore` is the
reason.

Pick the v3 surface, finish it, port over the v2 panels that are better,
delete the rest. Drive plan/tier copy from the database. Build one verdict
resolver. Do that and the product matches the engine.

— end —
