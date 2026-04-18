# Personas, journeys, and persona-driven test cases

> Companion to [`e2e-all-features-test-plan.md`](e2e-all-features-test-plan.md).
> The E2E plan covers every feature surface from a system perspective. This
> doc covers the same surface from a *user* perspective: who uses
> ExecuteSpec, what they're trying to do, and what to test along their
> realistic path.
>
> Last updated: 2026-04-18 (post v2.5.12 — 3-step architecture pipeline
> + Generate Docs feature live on dev).

---

## 1 · Personas

Each persona is one paragraph of who they are + a list of what matters to
them and what they avoid. Test cases later in this doc reference these by
ID (P1..P6).

### P1 · Senior Backend Engineer ("Aarav")

Builds APIs in Java + Node, has been on the team five years, knows the
codebase deeply. Wants ExecuteSpec to amplify, not replace, their judgment:
they read the **plan** carefully, scrutinise **diffs**, and reject runs
that drift from the team's idioms. They're the one who notices when
`ReviewerAgentV3` flags a spurious issue or when a smoke-test passes for
the wrong reason.

- **Cares about:** correctness, idempotency, repo conventions, plan-gate
  approval, validation report's `verifiedScore`, per-task token cost.
- **Avoids:** PROTOTYPE mode for anything non-throwaway, auto-merge,
  Vibe view.
- **Tier:** ENTERPRISE (org seat).
- **Dominant surface:** Pro view, Run Detail tabs (Plan / Code / Graph),
  Reviewer issues.

### P2 · Tech Lead / Engineering Manager ("Priya")

Leads a 6-person backend team. Spends most of her ExecuteSpec time setting
up projects, linking repos, reviewing PRs created by other team members,
and watching the project dashboard for cost + quality trends. Doesn't
write code in ExecuteSpec herself often.

- **Cares about:** project memory snapshot, score trend, PR throughput,
  cost-per-feature, team's quota burn rate, who broke the dev env.
- **Avoids:** plan-level execution detail, debugging individual runs.
- **Tier:** ENTERPRISE, ORG_ADMIN role.
- **Dominant surface:** Project Dashboard (Overview, PRs, Memory, Score
  Trend), Org Settings, Billing.

### P3 · Product Manager (non-technical) ("Maya")

Defines features in plain English, can't read code. Uses ExecuteSpec to
prototype ideas before handing them to engineering. The Vibe view exists
specifically for her workflow.

- **Cares about:** the live preview URL, "did the thing I described work",
  shareable share-link of the report, screenshots.
- **Avoids:** plan approval (skipped), provider selection, anything labelled
  "advanced".
- **Tier:** PRO (personal seat) or ENTERPRISE (org member).
- **Dominant surface:** Vibe view, NewRunModal (compose + chat), preview
  iframe, validation report `accuracyScore` shown as a single grade.

### P4 · Org Admin / Billing Owner ("Rahul")

Owns the org account. Buys subscriptions, invites users, sets domain
patterns, monitors monthly usage, and gets paged when quota crosses 80 %.
Rarely runs specs themselves.

- **Cares about:** invoices, GST line, payment audit trail, monthly run
  count vs limit, tier upgrade / downgrade timing, ESCALATED invites
  needing approval.
- **Avoids:** technical UI surfaces (Plan / Graph / Code).
- **Tier:** ENTERPRISE, BILLING_ADMIN + ORG_ADMIN roles.
- **Dominant surface:** Billing, Plans, Org Settings, Admin pages,
  Notification bell.

### P5 · External Contractor ("Wei")

Time-boxed contractor brought in to ship one feature. Has access to a
single project, no billing visibility, may not be on the org's email
domain. Logs in via Google OAuth.

- **Cares about:** getting credentials, finding the spec template their
  client provided, running PROTOTYPE quickly, sharing the preview link with
  the client.
- **Avoids:** anything outside their assigned project, complaining about
  quotas (it's the org's problem).
- **Tier:** PRO seat under client's org, PROJECT_DEVELOPER role.
- **Dominant surface:** Single Project Dashboard, NewRunModal scoped to
  that project.

### P6 · Platform / DevOps Engineer ("Sam")

Owns deploys + observability for ExecuteSpec itself, not its outputs.
Watches GCP, checks Grafana, investigates 503s, manages CI workflow.
Uses ExecuteSpec generated apps in dev/staging.

- **Cares about:** dev env health, Build & Deploy CI runs, Flyway state
  on the GCE instance, JVM logs, scheduled jobs (StackRefreshJob,
  MonthlyRunCounterResetJob), payment webhook signatures.
- **Avoids:** application-layer feature work.
- **Tier:** SUPER_ADMIN.
- **Dominant surface:** Admin pages (`/admin`, `/admin/payment-audit`,
  `/admin/feature-flags`), Grafana, GitHub Actions, GCE console.

---

## 2 · User journeys

Each journey is a complete end-to-end flow tied to a persona. Test cases in
§3 reference journeys by ID (J1..J8).

### J1 · First-run trial (P3 Maya)

> *"I want to see if this thing works for me."*

1. Lands on landing page from a tweet, clicks "Try it free".
2. Registers with email + password. Receives verification email; clicks
   link.
3. Lands on `/dashboard`, signed in. FREE tier. View defaults to **Vibe**.
4. Hits **New Run**, types: "A simple landing page for a dog-walking app
   with a hero photo, 3 testimonial cards, and a sign-up form."
5. Clicks Launch. Plan auto-approves (PROTOTYPE), worker runs ~2 min.
6. Status flips to COMPLETE. Preview URL appears; iframe renders the page.
7. Maya screenshots the result, shares the preview link with a teammate.
8. Notification bell shows "Run complete: …".

### J2 · Setting up a multi-repo project (P2 Priya, P1 Aarav)

> *"We're adding a new feature that touches both the BE API and the React
> frontend — set up a project so my whole team can launch runs against
> both."*

1. Priya logs in, ENTERPRISE org, ORG_ADMIN role. Switches to **PROJECT**
   view.
2. Clicks **New Project**, names it "Hostel Platform v3", picks Anthropic
   as default provider.
3. Opens Repos tab → **Add repository**. Picks `pplai-app/hostel-be-v3`
   from the GitHub list (PAT already configured in Settings → GitHub).
4. Adds `pplai-app/hostel-fe-v3` similarly.
5. Switches to Architecture tab → clicks **Analyse**. Watches status:
   ANALYSING → 3-step pipeline runs (codemap × 2, per-repo arch × 2,
   combined arch).
6. Reviews the combined `architecture.md` for accuracy; opens each
   per-repo arch via the new endpoint to verify HLD/LLD detail.
7. Clicks **Generate Explanation** + **Generate Setup** docs.
   Downloads each as `.md` to share with new joiners.
8. Org Settings → invites Aarav (already in the org) to the project as
   PROJECT_DEVELOPER. Hands over.

### J3 · Prototyping with Vibe view (P3 Maya)

> *"Add a 'thank you' page that shows after sign-up."*

1. From the Project Dashboard, Maya hits **Launch**.
2. ExecutionSetup modal — types her requirement in plain English.
3. Sees the **EstimateChip**: "~3–5 tasks · ~$0.40–$0.80".
4. Clicks Launch. PROTOTYPE skipped to plan-approve. Stepper progresses
   through Analysing → Building → Reviewing → Code generated.
5. PR opens on `pplai-app/hostel-fe-v3`. Preview comes up.
6. Maya clicks the preview link, signs up, sees the new page.
7. Approves the PR on GitHub. PostMergeBar appears next visit ("✓ PR
   merged — Start next requirement →").

### J4 · Power-user run with strict review (P1 Aarav)

> *"Add an idempotency-key column to /todos POST. Don't break existing
> callers."*

1. Aarav switches to **PRO view**. Hits Launch on the project.
2. Selects PRODUCTION mode (plan approval required), Anthropic provider,
   Sonnet model.
3. Pastes a 4-paragraph spec with explicit constraints + status code
   contracts.
4. EstimateChip shows "~4–6 tasks · ~$1.50–$2.50". Approves.
5. Plan opens at AWAITING_APPROVAL. Aarav reads each task, notices one
   is missing a dependency on the migration. Rejects with feedback
   (or cancels, refines the spec, relaunches).
6. On second pass, plan looks right. Approves.
7. Run completes; ScoreCard shows Verified 92, Reviewer CLEAN.
8. Aarav opens the per-file diff in the Code tab, scrutinises the
   migration, approves the PR on GitHub.
9. Memory snapshot updates with the new pattern Aarav established.

### J5 · Fresh-pull Sync after upstream merge (P1 Aarav)

> *"My teammate just merged a refactor on `hostel-be-v3`. I want
> ExecuteSpec to see the new code before I launch."*

1. From Project → Repos tab, Aarav clicks **Sync** on the
   `hostel-be-v3` card.
2. Backend runs the v2.5.12 enhanced Sync: metadata refresh +
   `regenerateForRepo` (codemap + per-repo arch v2 in this case).
3. Toast: "Synced — codemap v2 + arch v2 generated".
4. Aarav opens the Architecture tab, expands the per-repo arch viewer
   for `hostel-be-v3`, confirms the new module is described.
5. Now hits Launch with confidence.

### J6 · Scaling up: hit FREE quota → upgrade (P5 Wei) (P4 Rahul)

> *Wei has used 4 of 5 FREE runs. Submits a 5th — succeeds. Submits a 6th
> a few hours later — blocked.*

1. Wei tries to submit run #6. Backend returns 402 with
   `{metric:"RUNS", current:5, max:5}`.
2. NewRunModal closes, **UpgradeNudge** opens with three tier options
   (UpgradeModal — migrated to ResponsiveModal in v2.5.12).
3. Wei doesn't own billing — pings Rahul on Slack.
4. Rahul gets the QuotaWarning in-app notification (fired at 80 % cross
   per v2.5.12 — already fired at run #4).
5. Rahul opens Billing → upgrades to PRO. Razorpay flow with test card
   `5500 6700 0000 1002`. After webhook, tier flips. Wei's quota expands
   to 100 runs/month.
6. Wei retries run #6 — succeeds.

### J7 · Generate onboarding docs for a new joiner (P2 Priya)

> *"New engineer starts Monday. I want them to read a project explanation
> + run the SETUP doc by Tuesday."*

1. Priya, on the project Overview tab, looks at the **Generated Docs**
   panel.
2. EXPLANATION row shows v1, generated 3 days ago, Verified 90.
3. Hits **Regenerate** because two PRs landed since v1. Wait ~30 s for
   v2.
4. Hits **View** → reads the markdown inline. Confirms it covers both
   repos.
5. Hits **Download** → `explanation.md` saves locally; shares via Slack
   to the new joiner.
6. Repeats for SETUP — downloads `setup.md` with clone commands + env
   vars table.

### J8 · DevOps incident: dev env down (P6 Sam)

> *Slack alert fires: "Deploy FAILED — dev". Sam investigates.*

1. Sam clicks the GitHub Actions link in the Slack message.
2. Build & Deploy run shows red on the `Build JAR` step (or the
   `Health check` step).
3. Reads the failed log — for example, the V113 `project_id type
   mismatch` from 2026-04-17. Identifies a Flyway migration crashed
   the JVM.
4. Checks SSH'd app logs: `journalctl -u executespec | tail -200`.
5. If a fix is rolling, monitors `gh run watch` until the next deploy
   completes.
6. Posts an incident note in #engineering (timeline, root cause, fix
   commit, customer impact).

---

## 3 · Persona-driven test cases

Each case is short: ID, persona, journey, preconditions, steps, expected
result. Status column is left empty for QA to fill in per env.

### Block A — Onboarding & first-run

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-A1** | P3 Maya | J1 | Register `maya@example.com` → verify email → login → Vibe view default | FREE tier user, default view = Vibe, NotificationBell visible | |
| **PJ-A2** | P3 Maya | J1 | Submit a Vibe run "simple landing page for a coffee shop" | Run completes within 3 min, preview URL clickable, status = COMPLETE | |
| **PJ-A3** | P3 Maya | J1 | Click preview link in new tab | Iframe / redirect renders the generated page; no console errors | |
| **PJ-A4** | P3 Maya | J1 | Notification bell shows new entry | "Run complete: ..." with timestamp; click → routes to run detail | |
| **PJ-A5** | P5 Wei | — | Login via Google OAuth as `wei@contractor.example` (NOT executespec.ai) | OAuth callback succeeds; account created at FREE; `oauth_provider=google` populated on app_user | |

### Block B — Multi-repo project setup

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-B1** | P2 Priya | J2 | Create project "Hostel v3", default provider Anthropic | Project row in DB; redirected to project dashboard | |
| **PJ-B2** | P2 Priya | J2 | Set GitHub PAT (classic, `repo` + `read:org` scopes) in Settings → GitHub; set owner override `pplai-app` | PAT stored on `app_user.github_pat`, owner on `app_user.github_owner` | |
| **PJ-B3** | P2 Priya | J2 | AddRepoModal → click Refresh → repos from `pplai-app` org appear | Repo list populated; private repos have lock icon; classic-PAT only — fine-grained PATs without org access return empty | |
| **PJ-B4** | P2 Priya | J2 | Link `hostel-be-v3` and `hostel-fe-v3` | Two ProjectRepoLink rows; both visible in Repos tab; webhook chip shows install status | |
| **PJ-B5** | P2 Priya | J2 | Architecture → Analyse | Three-step pipeline runs: per-repo codemap × 2, per-repo arch × 2, combined arch v1; logs show `3-step arch generation complete: 2 repos, combined v1` | |
| **PJ-B6** | P2 Priya | J2 | `GET /api/projects/{id}/repos/{repoId}/architecture` for each repo | 200 with HLD + LLD markdown; `model` and token counts populated | |
| **PJ-B7** | P2 Priya | J2 | Workspace dir on the GCE instance | `workspace/projects/<id>/repos/<short>/architecture.md` exists per repo, plus `architecture.md` + `summary.md` at project root | |

### Block C — Generate Docs (v2.5.12)

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-C1** | P2 Priya | J7 | On project with completed Analyse, open Overview → Generated Docs panel | Two cards (Explanation + Setup), both showing `Generate` CTA | |
| **PJ-C2** | P2 Priya | J7 | Click Generate Explanation | LLM call ~20–40 s; success toast; row flips to v1 with model + token counts; View toggle visible | |
| **PJ-C3** | P2 Priya | J7 | Click View | Inline markdown rendered with HLD-style sections; max-h scroll | |
| **PJ-C4** | P2 Priya | J7 | Click Download | `explanation.md` downloads via blob URL with `Content-Disposition: attachment` and `text/markdown` MIME | |
| **PJ-C5** | P2 Priya | J7 | Click Regenerate | New LLM call; row bumps to v2; previous v1 still in `project_generated_doc` table for audit | |
| **PJ-C6** | P3 Maya | — | Try Generate Explanation on a project with no Analyse run | 409 Conflict with friendly message ("call /analyse first"); UI surfaces it as toast | |
| **PJ-C7** | P5 Wei | — | Try Generate via API: `POST /api/projects/{X}/docs/EXPLANATION/generate` for project Wei lacks access to | 403 from RBAC (or 404 if project not visible to user) | |

### Block D — Sync (v2.5.12)

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-D1** | P1 Aarav | J5 | Click Sync on a RepoCard whose origin HEAD has not changed | Metadata refresh runs; `ensureCodeMap` reuses latest; logs show `up-to-date @ sha=..., reusing v{n}`; arch row reused | |
| **PJ-D2** | P1 Aarav | J5 | Push a new commit to `hostel-be-v3` upstream, then click Sync | New codemap vN+1 + new repo-arch vN+1 created; combined arch deliberately not regenerated (saved for Analyse) | |
| **PJ-D3** | P1 Aarav | J5 | Disk inspection after Sync | `workspace/projects/<id>/codemaps/hostel-be-v3.md` and `repos/hostel-be-v3/architecture.md` reflect new content; combined `architecture.md` unchanged | |
| **PJ-D4** | P5 Wei | — | Click Sync without a PAT configured | Metadata refresh succeeds against public repos only; private-repo codemap fetch returns empty; arch regen logs warning + uses fallback (raw codemap as content) | |

### Block E — Plan approval + scrutiny (P1 power-user)

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-E1** | P1 Aarav | J4 | Submit PRODUCTION run with a 4-paragraph spec | Status hangs at AWAITING_APPROVAL; SSE `GATE_OPENED` event with credits + cost; in-app notification fires | |
| **PJ-E2** | P1 Aarav | J4 | Open plan, scrutinise tasks for correct ordering | Plan tasks visible, each with description + complexity + dependsOn list; coverage map cites `requirements[].id` per task (Validation v3 Pillar 1) | |
| **PJ-E3** | P1 Aarav | J4 | Concurrently approve from two browser tabs (race condition) | One tab gets 202 Accepted; other gets 409 Conflict via `OptimisticLockAdvice` (V112 @Version on ExecutionRun) | |
| **PJ-E4** | P1 Aarav | J4 | Reject + provide feedback | Run flips to CANCELLED with reason; quota refunded by `RunQuotaRefundListener` | |
| **PJ-E5** | P1 Aarav | J4 | Run completes — open Code tab | Per-task diffs visible with file paths, additions/deletions chips, line-by-line view | |
| **PJ-E6** | P1 Aarav | J4 | ValidationReport panel | Primary badge: `Verified N/100` (V106 deterministic); secondary `Est. N` (LLM); SignalBreakdown chip shows Build ✓ · Tests N/N · Reviewer CLEAN · Verified N · Est. N | |
| **PJ-E7** | P1 Aarav | J4 | Spec contains "invalid import" → LLM hallucinates accuracyScore=0 | RunDetail top banner + ScoreCard header still show **Verified** as primary, not the 0 (v2.5.12 fix) | |

### Block F — Notifications

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-F1** | P1 Aarav | J4 | Plan opens (PRODUCTION run) | `PipelineEvent.GateOpened` fires → `RunCompletionNotifier.onGateOpened` creates `GATE_OPENED_PLAN_APPROVAL` notification | |
| **PJ-F2** | P5 Wei | J6 | Wei's run #4 of 5 (crosses 80 % of FREE quota) | `QuotaEvent.QuotaWarning` fires → `onQuotaWarning` creates "Approaching run limit — 80 % used" notification, dedupe key embeds YearMonth | |
| **PJ-F3** | P5 Wei | J6 | First day of next month (Asia/Kolkata midnight) | `MonthlyRunCounterResetJob` resets `personal_run_count`; next 80 % crossing fires a fresh notification | |
| **PJ-F4** | P2 Priya | — | A PR opened by an ExecuteSpec run is merged on GitHub | GitHub webhook → `PullRequestRecordService` updates status; PR_MERGED notification (v2.5.12 — `ed706bb7`) fires for the project members | |
| **PJ-F5** | any | — | Notification bell unread badge | Reflects DB count; `POST /api/notifications/read-all` zeros it | |

### Block G — Billing & quota

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-G1** | P4 Rahul | J6 | Subscribe to PRO via Razorpay | Checkout opens with INR amount + GST line; webhook flips org tier; PaymentAudit row created | |
| **PJ-G2** | P4 Rahul | — | Subscribe via Stripe (USD) | Same flow; `stripe listen --forward-to ...` validates the webhook signature path | |
| **PJ-G3** | P4 Rahul | — | Buy 10 run top-ups | After webhook: `runs_topup_balance` increases by 10; tier unchanged; FREE user can submit a previously-blocked spec | |
| **PJ-G4** | P4 Rahul | — | Run that ends FAILED | `RunQuotaRefundListener` refunds N credits; quota counter decreases | |
| **PJ-G5** | P4 Rahul | — | Run ends PARTIAL with status_reason `CLEAN_WITH_INTERMEDIATE_WARNINGS` (v2.5.10) | **No refund** (run effectively succeeded); regression guard for the v2.5.12 fix | |
| **PJ-G6** | P4 Rahul | — | Open Admin → Payment audit, export CSV | CSV downloads with one row per transaction including GST, gateway, amount, status | |

### Block H — Invitations + RBAC

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-H1** | P2 Priya | J2 | Invite `aarav@executespec.ai` (matches org domain) | Email sent; user accepts; appears in org members + project members | |
| **PJ-H2** | P2 Priya | — | Invite `wei@contractor.example` (consumer domain), org tier ENTERPRISE | Domain-gating BLOCKS consumer domains for ENTERPRISE; invite goes to ESCALATED status; SUPER_ADMIN gets `sendEscalationToAdmin` email; original recipient gets nothing until SUPER_ADMIN approves | |
| **PJ-H3** | P6 Sam | — | SUPER_ADMIN approves the escalated invite | Approval transitions invite to PENDING; `sendOrgInviteEmail` fires; recipient lands at `/auth/accept-invite?token=...` | |
| **PJ-H4** | P5 Wei | J6 | Wei accepts the invite | Account created joined to org as DEVELOPER; assigned project visible | |
| **PJ-H5** | P5 Wei | — | Wei navigates to a different org's project | 403; Project list only shows projects Wei is a member of | |
| **PJ-H6** | P5 Wei | — | Wei tries to add a repo to the project | 403 if PROJECT_DEVELOPER (only PROJECT_ADMIN + ORG_ADMIN can link repos) — verify in `ProjectRepoController.linkRepo` guards | |

### Block I — Architecture v2.5.12 specifics

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-I1** | P2 Priya | J2 | Click Analyse a second time, no upstream changes | Each repo logs `codemap up-to-date @ sha=..., reusing v{n}` and `repo-arch up-to-date @ codemap v{n}, reusing v{n}`; combined arch still bumps to v{n+1} (always regenerates) | |
| **PJ-I2** | P2 Priya | — | Open the combined `architecture.md` after Analyse | Markdown is LLM-synthesised (not deterministic concatenation); contains Overview / Repository layer / Cross-repo flows / Shared contracts / Open questions sections | |
| **PJ-I3** | P1 Aarav | J5 | Trigger Sync, check ModelResolver tier | RepoArchitectureGenerator → LIGHTWEIGHT (v2.5.12 mapping); ArchitectureDocGenerator → WORKHORSE; no `No tier mapping` WARN in logs | |
| **PJ-I4** | P6 Sam | — | Run Analyse with ~3 repos and watch Hikari logs | No `Apparent connection leak detected` WARN (v2.5.12 — `@Transactional` removed from `generate()`); each save runs in its own auto-tx | |
| **PJ-I5** | P6 Sam | — | Open Project Dashboard with a private-org project linked | Logs do NOT show `getBranchHeadSha ... status 404` (v2.5.12 — `StalenessService.report` now threads caller PAT) | |

### Block J — Validation report + scoring

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-J1** | P3 Maya | J1 | Open completed Vibe run | Single accuracy grade visible (no Verified/Est. confusion in Vibe view) | |
| **PJ-J2** | P1 Aarav | J4 | Open completed Pro run | Verified primary, Est. secondary (when LLM disagrees) | |
| **PJ-J3** | P1 Aarav | — | Run touches only README.md, no source code | `testCoverage` is **null** (hidden in UI), not 20 (v2.5.12 fix); other dimensions equal anchor with the "honest scoring" helper note | |
| **PJ-J4** | P3 Maya | — | Run finishes; `POST /api/runs/{id}/report/share` | Returns share token; `/reports/shared/{token}` renders the report unauthenticated | |
| **PJ-J5** | P1 Aarav | — | Reviewer flags 2 critical + 3 minor issues | `verifiedScore` recomputes per V106 formula: `clamp(0,100, 40 + 20 + round(20*pass/total) + verdict − 5*unfixedCritical)` | |

### Block K — Deploy + observability (P6 Sam)

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-K1** | P6 Sam | J8 | Push to dev branch | GitHub Actions Build & Deploy fires; succeeds within 5 min; deploy summary printed | |
| **PJ-K2** | P6 Sam | J8 | Force a Flyway migration crash (e.g. column-type mismatch) on a feature branch | App fails to boot; CI's curl-wrapper health check has a known false-positive bug; `journalctl` shows the real cause; rollback by reverting the migration on a follow-up commit | |
| **PJ-K3** | P6 Sam | — | Deploy succeeds, dev URL still 503 | Cloudflare/LB issue (not app); SSH check `sudo ss -lntp \| grep 8080` shows the app is listening locally; remediation outside ExecuteSpec | |
| **PJ-K4** | P6 Sam | — | Manual workflow_dispatch on `Build & Deploy` | Workflow runs, picks up latest dev SHA, deploys; useful when no commit but want to redeploy | |
| **PJ-K5** | P6 Sam | — | Open `/actuator/prometheus`, scrape from local Prometheus | Custom v2 metrics visible (`executespec.v2.run.outcome`, `task.smoketest`, etc.); per-tier deploy metric, planner latency histograms | |

### Block L — Cross-cutting quality (any persona)

| ID | Persona | Journey | Steps | Expected | Status |
|---|---|---|---|---|---|
| **PJ-L1** | any | any | Submit two PRODUCTION approvals concurrently from same user | OptimisticLockAdvice → 409 on the loser; no silent overwrite | |
| **PJ-L2** | any | any | Submit a run while another is WORKING | Allowed up to tier's `concurrent_runs` limit; over-limit submission queues or 429 | |
| **PJ-L3** | P5 Wei | — | Hit the WRITE rate limit (e.g. 10 POST /api/runs in a minute) | After Nth: 429 with rate-limit headers; backoff window respected | |
| **PJ-L4** | any | any | Restart the dev VM mid-run (kill JVM) | `StartupRunResumerV2` marks WORKING/REVIEWING/AWAITING_APPROVAL runs FAILED with reason "interrupted at boot"; SSE terminal emitted; UI surfaces the interruption | |
| **PJ-L5** | any | any | Close the browser during a long PRODUCTION run | Run continues server-side (SSE channel detached); reopen — RunDetail rehydrates with current state via REST + reattaches SSE | |

---

## 4 · Smoke pack (10-minute happy path)

After every dev deploy, run these in order. If any fail, halt promotion.

- [ ] **PJ-A1, PJ-A2** — Maya can sign up + run a Vibe spec to COMPLETE.
- [ ] **PJ-B5, PJ-B6, PJ-B7** — Analyse on a 2-repo project produces all
      three artefact tiers (codemap, per-repo arch, combined) on disk.
- [ ] **PJ-C2, PJ-C4** — Generate Explanation succeeds; download saves the
      markdown.
- [ ] **PJ-D2** — Sync after upstream commit produces a new codemap +
      arch version.
- [ ] **PJ-E3** — Concurrent plan-approve returns 409 to the loser.
- [ ] **PJ-F1, PJ-F2** — GateOpened + QuotaWarning notifications appear
      in the bell.
- [ ] **PJ-J1, PJ-J3** — Vibe report shows accuracy; doc-only run hides
      Test coverage.
- [ ] **PJ-K1** — Trivial dev push deploys cleanly.

If all 8 pass: dev is healthy enough to onboard a new user without
warning them about a known issue.

---

## 5 · Persona × feature coverage matrix

A quick sanity grid — which test blocks each persona exercises. Useful
when you want to QA a specific persona's flow rather than the whole plan.

| Persona | A | B | C | D | E | F | G | H | I | J | K | L |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P1 Aarav | | | | ✓ | ✓ | ✓ | | | ✓ | ✓ | | ✓ |
| P2 Priya | | ✓ | ✓ | | | ✓ | | ✓ | ✓ | | | |
| P3 Maya | ✓ | | ✓ | | | | | | | ✓ | | ✓ |
| P4 Rahul | | | | | | ✓ | ✓ | ✓ | | | | |
| P5 Wei | ✓ | | ✓ | ✓ | | ✓ | ✓ | ✓ | | | | ✓ |
| P6 Sam | | | | | | | ✓ | ✓ | ✓ | | ✓ | ✓ |

---

## Revision log

| Date | Who | Change |
|---|---|---|
| 2026-04-18 | post-v2.5.12 sprint | Initial doc — 6 personas, 8 journeys, 12 test blocks (~55 cases) |
