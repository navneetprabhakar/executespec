# Synthesis — Product, Tech, and User-persona action plan

> Date: 2026-04-16. Sources: every report in `docs/reports/` plus the live
> walkthrough described in each. Cumulative LLM spend across the live eval
> matrix + project Launch: **~$0.34** (cap was $25, so 1.4 % used).
>
> Companion docs:
> - [`ux-review.md`](ux-review.md) — Run vs Project visual dialect mismatch.
> - [`projects-usability.md`](projects-usability.md) — Project journeys + status-enum gaps.
> - [`eval-scenarios-v2.md`](eval-scenarios-v2.md) — strict eval rubric + 5 scenarios.
> - [`eval-results.md`](eval-results.md) — live results across 3 providers.
> - [`persona-non-technical.md`](persona-non-technical.md) — Maya persona walkthrough.

## Headline findings (the five things to act on this sprint)

1. **The Anthropic chat path is broken in production.** Every project that
   defaults to Anthropic returns `400 — temperature is deprecated for this
   model` from `POST /api/projects/{id}/chat`. Source:
   [memory/ProjectChatService.java:107](src/main/java/com/navneet/executespec/memory/ProjectChatService.java:107),
   [:158](src/main/java/com/navneet/executespec/memory/ProjectChatService.java:158).
2. **Project executions silently strand.** Five zombie `ExecutionRun` rows
   were stuck for 12-24 h until I called the manual reconcile endpoint. A
   sixth one stranded during this same session — solution 2 failed after 3
   PARTIAL retries and the run is now PAUSED. There is no `@Scheduled`
   reconcile.
3. **Gemini is not production-ready in the V2 pipeline.** The Flash Preview
   model still emits `MALFORMED_FUNCTION_CALL` (Bug A in eval-results), and
   the reviewer hangs indefinitely on otherwise-complete runs (Bug C). The
   `salvagePlanFromText` regex fallback didn't trigger.
4. **Run vs Project surfaces look like different products.** Tabs, headers,
   status pills, card radii, and font-size scale all diverge. Run uses
   `font-mono uppercase tracking-wider`; Project uses sentence-case sans
   with arbitrary `text-[Npx]` values that bypass the design system.
5. **Backend can emit statuses the frontend can't render.** `ANALYSING`,
   `ARCH_REVIEW`, `SETUP` are emitted by `ExecutionOrchestrator` but missing
   from `STATUS_COLORS` and `StatusBadge.DISPLAY_NAMES`. Live launches
   render an uncoloured "Analysing" pill while every other status pops.

## Action plan by audience

### A. Product action items

#### A1. Persona-driven copy rewrite (1 sprint)
- Rename status terms in the UI (no backend change required):
  - `AWAITING APPROVAL` → `Plan ready — start?`
  - `ANALYSING` → `Reading code`
  - `ARCH_REVIEW` → `Checking architecture`
  - `EXECUTING` → `Building`
  - `REVIEWING` → `Reviewing changes`
- Rename submit-form fields:
  - `User request` → `What do you want me to build?`
  - hide `Source` selector for non-admin users (REST/MANUAL is plumbing)
  - explain `PROTOTYPE | PRODUCTION` in plain English on hover
- Owner: Product + Frontend. Why: the live walkthrough showed Maya cannot
  parse 60% of the surface terms.

#### A2. First-run onboarding (1 sprint)
- New users land on a *"Try a sample run"* card with a pre-filled spec, a
  one-paragraph explanation of what each gate means, and a guarantee that
  the first run is free.
- The current dashboard for a brand-new account is a list of *other people's*
  historical runs — meaningless.
- Owner: Product. Why: persona report Section "Verdict on Journey 1".

#### A3. Surface estimate before submit, not at plan approval (≤ 1 week)
- The estimation service exists at `GET /api/projects/{id}/estimate` (v2.5.2).
- Wire it into the global `New Run` modal *before* submit so the user knows
  *"this will cost ~1 credit"* up front.
- Today the user sees the cost only at `AWAITING APPROVAL`, after one LLM
  round-trip is already paid for.
- Owner: Product + Frontend. Why: persona report + projects-usability "Plan
  approval is the second gate".

#### A4. Disable `Launch` until repos are linked (½ day)
- One-line change in `ProjectDashboardPage.tsx`. Tooltip *"Link a repo to
  launch"* on hover.
- Today the brightest button on the empty-project dashboard is a guaranteed
  failure path.

#### A5. Single empty-state primitive (1 week)
- 8 hand-rolled empty states across Project tabs use 3 different framings
  (*"Nothing submitted yet"*, *"No X yet"*, *"No X yet. Click Y"*). One
  shared `<EmptyState />` component, one voice.
- Owner: Frontend. Why: ux-review #7 + projects-usability "Empty-state
  inventory".

#### A6. Per-launch repo-count chip + filters + grouped retries (1-2 weeks)
- A 5-repo launch and a 1-repo launch should not look identical in the
  Launches list.
- Add date / status / user filters above the list.
- Group retried solutions under their original launch (collapsible).
- Owner: Frontend. Why: projects-usability "Multiple launches" section.

### B. Tech action items

#### B1. Fix Anthropic chat (today)
- Symptom: `POST /api/projects/{id}/chat` returns
  `400 — temperature is deprecated for this model` for every Anthropic-default
  project.
- Root cause: the live JVM is calling an Opus-4.7-class model even though
  `application.yml` already pins `anthropic.planning` to `claude-sonnet-4-6`
  (which supports `temperature` 0.2 / 0.3 used by `ProjectChatService`).
- Fix: restart the backend so the YAML matrix is authoritative; verify
  `/api/models/available` returns `claude-sonnet-4-6` for ANTHROPIC PLANNING.
- **Do NOT remove the `.temperature(...)` calls** at
  [memory/ProjectChatService.java:107](src/main/java/com/navneet/executespec/memory/ProjectChatService.java:107)
  and [:158](src/main/java/com/navneet/executespec/memory/ProjectChatService.java:158)
  — temperature is required for our flows. We are explicitly NOT migrating
  to Opus 4.7 yet.
- If the live API still shows `opus-4-7` after restart, hunt the runtime
  override: check `@PostConstruct` / `@EventListener(ApplicationReadyEvent)`
  on any `ModelMatrixProperties` consumer that mutates `getProviders()`, and
  scan classpath jars for an additional `application.yml` shading our copy.

#### B2. Scheduled reconcile job (≤ 1 day)
- Today: `POST /api/admin/executions/reconcile` is a manual SUPER_ADMIN
  endpoint added in v2.5.2.
- Add `@Scheduled(fixedDelay = 15 minutes)` + `@SchedulerLock` job that
  invokes the same logic with a default `staleMinutes=60`.
- Today five execution rows had been stuck for 12-24 h — that's reachable
  from the admin endpoint, but only if a human remembers to call it.
- This is a one-class change in `execution/service/AdminReconciliationService.java`.

#### B3. Reviewer per-pass timeout (≤ 1 day)
- Bug C in eval-results: S1 Gemini run had 3/3 worker tasks COMPLETE for >5
  minutes, but was hung in `REVIEWING`. No timeout in
  [reviewer/ReviewerAgentV3.java](src/main/java/com/navneet/executespec/v2/reviewer/ReviewerAgentV3.java).
- Fix: 90 s wall-clock per pass, treat timeout as `REVIEW_FAILED`, let
  `resolveTerminalStatus` derive run status from build + worker outcomes
  (already does this — v2.5.10).
- Otherwise: a single LLM hang freezes the whole run and the user can't see
  the verified score (it's only persisted at terminate).

#### B4. Extend Gemini planner-output salvage (1 day)
- Bug A: `salvagePlanFromText` only matches text-blob outputs. The malformed
  call this run produced was a `Malformed function call: call:default_api:submitPlan{...`
  prefix.
- Either extend the regex to include this envelope, or add a second-pass
  `PlannerOutputRepairAgent` (LIGHTWEIGHT tier) that takes the broken text
  and re-emits a `submitPlan` call.
- Owner: Backend. Tracked as part of the validation-v3 series.

#### B5. Populate `failureReason` on every FAILED transition (1 day)
- Bug D: S2 Anthropic T3 had `failureReason: ""` (empty string). The harness
  retried as T4. Empty string is worse than null because the UI's null-check
  may treat it as "set".
- Add a default `failureReason: "unknown — see logs trace_id=..."` on every
  FAILED state write that doesn't carry a reason.

#### B6. Solution-retry budget instead of fixed N=3 (1 week)
- Today's project launch ended PAUSED with one solution `FAILED` and reason
  `V2 returned PARTIAL after 3 retries`. The 3 retries each spent budget;
  for an 8-solution multi-repo launch, this is up to 24 wasted V2 runs.
- Fix: cap retries by *cumulative cost* on the parent execution rather than
  count. E.g. *"abandon a solution if it has eaten > 30 % of the launch's
  hard cap"*.

#### B7. Status enums end-to-end test (1 day)
- Single ArchUnit-style test that asserts every value of `ExecutionRun.status`
  + `PipelineRun.status` appears as a key in `frontend/src/lib/constants.ts`
  STATUS_COLORS and `StatusBadge.DISPLAY_NAMES`.
- Today the gap is silent: `SETUP / ANALYSING / ARCH_REVIEW` ship to the UI
  without a colour or display name.

#### B8. Strip dead `*_BATCH` enums (½ day)
- `QUEUED_BATCH / IN_PROGRESS_BATCH / BATCH_COMPLETE` still mapped in
  `STATUS_COLORS` and `DISPLAY_NAMES`. CLAUDE.md says V2 backend never emits
  them. Remove + delete the constants.

#### B9. Use `<StatusBadge>` on Project surfaces (½ day)
- `ProjectTaskList.tsx:82` and `ProjectDashboardPage.tsx:456` reimplement
  the status pill inline with `font-mono` + a colormap. Replace with the
  shared `<StatusBadge />` component — gains the v2.5.10 statusReason
  tooltip on Project pages too.

#### B10. Model-matrix fix: planner uses run-level model, not tier model
- CLAUDE.md flags this as known tech debt; today's eval confirmed it. The
  planner LLM in our OpenAI runs was `gpt-5.4-mini` (run-level default),
  not whatever the YAML matrix says for `PlannerAgentV2 × OPENAI`. Same for
  reviewer. Changing the matrix has no effect.
- Fix: have `PlannerAgentV2` and `ReviewerAgentV3` resolve their own
  models via `ModelResolver.resolve("...AgentV2", provider).getModel()`
  instead of `req.model()`.

### C. User-persona action items (UX surface for non-technical users)

#### C1. Sample-run gallery on `/` for new accounts (1 sprint)
- Per persona report: "she has nothing to model her input on". A gallery of
  3-5 working sample runs (TODO app, calculator, landing page) with one-tap
  *"Try this"* buttons.
- Owner: Product + Design.

#### C2. Plain-English status copy (½ sprint)
- Mapping in A1 above.
- Pre-launch: A/B test on a small cohort to verify the new copy actually
  reduces gate-hesitation time.

#### C3. Cost & quality at a glance (1 week)
- Replace `Verified 90 · Est. 88` (two scores, no context) with
  *"Build passed · ~88 % quality estimate"*. Tooltip shows both.
- Replace stepper *"Planning · Approval · Tasks · Compilation · Review ·
  Merge · Preview · Testing · Report"* with *"Plan → Build → Review → Done"*
  collapsed; click expands to the 9-step engineer view.

#### C4. Inline citations on chat replies (1 week)
- Today: chat returns prose. Backend retrieves k=3 chunks; surface them as
  `(from arch.md §3, codemap for hostel-be-v3)` inline.
- Owner: Backend (return citations) + Frontend (render).

#### C5. Carry chat → run provenance (½ sprint)
- `LaunchFromChatModal` produces a spec; the resulting run loses its origin.
- Persist `originatedFromChatId` on the new run; the run detail header
  shows *"Launched from chat thread X"* with a back-link.

## Items already on the backlog or known tech debt — confirm priority

The following items appear in CLAUDE.md "Known Tech Debt" or `docs/plans/`
and are reinforced by this round of testing:

- **V2 startup replay** for WORKING/REVIEWING runs (CLAUDE.md Gap 2). Today
  we hit a similar pattern: 5 zombie executions on boot. The per-run
  `StartupRunResumerV2` covers PipelineRun, but ExecutionRun zombies are
  separate and only the manual reconcile endpoint clears them.
- **Bug #1c (CLAUDE.md):** `ModelResolver.selectAdaptiveProvider` bypasses
  strict-provider mode. Today's runs confirm provider routing is honoured at
  the run-submission layer; we did not exercise the bypass.
- **`spring.jpa.open-in-view=true`** (CLAUDE.md "v2.5.1"). Today's testing
  did not surface lazy-init crashes — but the connection pool pressure cost
  is invisible without load. Worth quantifying once the `Analyse` SSE
  streaming work (B-related) lands.
- **V2 batch implementation** (`docs/plans/v2-batch-execution-plan.md`).
  Not exercised today; eval ran in realtime mode.

## Suggested order of execution

| Week | Bucket | Items |
|---|---|---|
| **1** | hot fixes | B1 (chat temperature), B2 (scheduled reconcile), B3 (reviewer timeout), B5 (failureReason populated), A4 (Launch button gating), B8 + B9 (dead enums, shared status badge), B7 (enum parity test) |
| **2** | persona copy | A1 (status/term rename), A3 (estimate before submit), C2 (status copy mapping in PR with A1) |
| **3** | onboarding + chat | A2 (first-run guide), C4 (citations), C5 (chat→run provenance), B1 regression test |
| **4** | structural | A5 (empty-state primitive), A6 (launches list), B4 (Gemini salvage), B10 (planner tier model), C1 (sample gallery) |
| **5+** | bigger | B6 (cost-bound retries), V2 startup replay (existing plan), validation-v3 P2-P5 (existing plan) |

This sequence front-loads the items that fix existing failures (Bug B/C/E),
then leans into the user-perceived cost (terminology + onboarding), then
addresses the structural mismatches in the design system.

## Cost note

This entire round of testing — 6 eval runs across 3 providers, plus a
2-solution multi-repo project launch, plus 2 chat probes, plus a reconcile
sweep — cost approximately **$0.34** in LLM spend. The $25 cap was set
generously and is intact. Further eval rounds (S3/S4/S5 from the strict
spec) are well within budget when the team chooses to commission them.
