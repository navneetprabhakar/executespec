# Merge Rail Implementation Plan

Date: 2026-06-24

Inputs:

- `theme-website-ui-audit.md`
- `deep-dive-directions.md`
- `mockups/index.html`
- `mockups/all-surfaces.html`
- `mockups/all-surface-screenshots/contact-sheet-all-surfaces.png`

Goal:

Move ExecuteSpec from generic AI SaaS/dashboard visuals to a coherent `verified merge pipeline` product language across public website and protected app.

## Non-Negotiable Outcome

The redesign is done only when these statements are visually true:

1. The website hero communicates "verified PR pipeline" within five seconds.
2. The app result screen makes merge readiness obvious before opening tabs.
3. Compose/start reads as a run contract, not a generic form.
4. Website and app look like one product.
5. Every score/verdict is tied to visible evidence and cost.

## Rollout Strategy

Do not start by rewriting every screen. Build reusable primitives first, then replace the highest-leverage surfaces.

Order:

1. Design-system primitives.
2. Run result screen.
3. Compose/start screen.
4. Website hero + trust section.
5. Console home + runs list.
6. Projects, architecture, billing, team/settings.
7. Press kit and OG/social assets.

Reason:

The run result screen is the product proof. Once it has a strong `VerdictCard + RunRail + EvidenceLedger`, the website can reuse that same object instead of inventing separate marketing art.

## Phase 0: Guardrails And Baseline

### Work

- Preserve current dirty work in unrelated frontend files.
- Add a tracking reason-log entry before production UI changes.
- Capture current baseline screenshots for:
  - website hero
  - compose
  - plan gate
  - console home
  - runs list
  - live run
  - run result
  - projects
  - project overview
  - architecture
  - billing
  - team/settings

### Commands / Evidence

- Use existing press-kit capture flow if current.
- Otherwise use Playwright screenshot capture against local dev server.
- Keep screenshots under `website/press-kit/current/` only after actual implementation.

### Acceptance

- Baseline screenshots exist.
- Scope is documented.
- No unrelated frontend work is mixed into the redesign commit.

## Phase 1: Merge Rail Design System

### Files

Add:

- `frontend/src/design-system/merge-rail-tokens.css`
- `frontend/src/design-system/RunRail.tsx`
- `frontend/src/design-system/VerdictCard.tsx`
- `frontend/src/design-system/EvidenceLedger.tsx`
- `frontend/src/design-system/CostLedgerMini.tsx`
- `frontend/src/design-system/mergeRail.ts`
- `frontend/src/design-system/mergeRail.test.ts`

Update:

- `frontend/src/index.css`
- `frontend/src/tokens.css`
- `frontend/src/design-system/primitives.css`

### Components

#### `RunRail`

Purpose:

Show lifecycle state everywhere.

Stages:

- Spec
- Plan
- Execute
- Review
- Validate
- PR

States:

- pending
- active
- verified
- waiting
- failed
- skipped

Use:

- Website hero artifact.
- Compose expected rail.
- Plan gate approval rail.
- Live run progress rail.
- Result screen.
- Console home row mini rail.
- Runs list.
- Project health history.

#### `VerdictCard`

Purpose:

Make merge readiness the first visible product object.

States:

- Ready to merge
- Needs review
- Failed
- Running
- Awaiting approval

Inputs:

- status
- statusReason
- verifiedScore
- githubPrUrl / scmPrUrl
- build state
- tests passed/total
- reviewer state
- critical count
- files changed
- actual cost / cap

#### `EvidenceLedger`

Purpose:

Make score evidence transparent.

Source labels:

- deterministic
- LLM reviewer
- LLM assessment
- hybrid
- formula
- provider ledger

Do not hide this under a tab only.

#### `CostLedgerMini`

Purpose:

Cost should sit beside verdict, not buried in billing/cost tabs.

Fields:

- actual cost
- approved cap
- provider
- planner / worker / reviewer split if present
- cache saved / cache read tokens if present

### Token Rules

Add semantic tokens:

- `--mr-rail-idle`
- `--mr-rail-active`
- `--mr-rail-verified`
- `--mr-rail-waiting`
- `--mr-rail-failed`
- `--mr-verdict-ready-bg`
- `--mr-verdict-ready-fg`
- `--mr-verdict-review-bg`
- `--mr-verdict-review-fg`
- `--mr-verdict-failed-bg`
- `--mr-verdict-failed-fg`
- `--mr-ledger-cost`
- `--mr-ledger-cache`
- `--mr-ledger-token`

### Tests

- Unit-test status-to-rail mapping.
- Unit-test verdict state mapping.
- Unit-test evidence source label mapping.
- Unit-test cost formatting edge cases: null, zero, under cap, over cap.

### Acceptance

- Components render independently.
- No page migration yet.
- Story/mock route or test fixture exists for each component.
- `npm --prefix frontend run build` passes.
- Focused frontend tests pass.

## Phase 2: Run Result Signature Screen

### Files

Update:

- `frontend/src/ide/IdeRunResult.tsx`
- `frontend/src/ide/runResultUtils.ts`
- `frontend/src/ide/ide.css`

Possibly add:

- `frontend/src/ide/runRailAdapter.ts`
- `frontend/src/ide/runRailAdapter.test.ts`

### Current Problem

`IdeRunResult.tsx` already has the right data:

- score
- verdict
- PR URL
- build breakers
- tests
- review failures
- issues
- cost
- provider
- credits
- verification ledger

But it renders those as scattered cards and tab content.

### New Layout

Top of Overview:

1. `VerdictCard`
2. `CostLedgerMini`
3. `RunRail`
4. Compact `EvidenceLedger`
5. Primary actions: Open PR / Download report / Iterate

Tabs remain below:

- Code
- Tasks
- Timeline
- Logs
- Evidence
- Cost
- Chat
- Tests

### Behavior

- If terminal and PR exists: primary action is `Open PR`.
- If terminal and no PR but files exist: primary action is `Push to GitHub`.
- If failed: VerdictCard becomes failure-focused and primary action is `Open logs` or `Fix failure`.
- If no score because patch/bench run: show honest "not scored by validation pipeline" state inside VerdictCard.

### Acceptance

- First visible object is verdict, not tabs.
- Cost is visible without clicking Cost.
- Evidence source labels are visible without clicking Evidence.
- Failed run has a clear recovery CTA.
- Existing tabs still work.
- Screenshot resembles `mockups/all-surface-screenshots/surface-07-result.png`.

### Verification

- Frontend build.
- Existing `runResultUtils` tests.
- Add adapter tests for:
  - READY run
  - NEEDS_REVIEW run
  - FAILED run
  - unscored patch/bench run
  - no PR but files present

## Phase 3: Compose / Create Verified PR

### Files

Likely:

- `frontend/src/ide/IdeSurfaces.tsx`
- `frontend/src/ide/ide.css`
- helper files for compose state if needed.

### Current Problem

The screen reads as a form:

- text area
- run setup
- review
- context selector

### New Layout

Use `Create verified PR` framing:

- Left: spec composer.
- Right: `Run contract`.
- Under/right: expected `RunRail`.

Run contract items:

- Output: PR with verdict.
- Approval: plan gate before execution.
- Validation: build + tests + reviewer + criticals.
- Cost cap: exact cap and provider.

### Copy

Replace:

- "Start work"

With:

- "Create verified PR"

Replace:

- "Create plan"

With:

- Keep `Create plan`, but visually explain it is the first checkpoint.

### Acceptance

- User can understand what will happen before submitting.
- Provider/cap/context are contract terms, not random setup controls.
- Screenshot resembles `surface-02-compose.png`.
- Mobile should show spec first, then contract.

## Phase 4: Plan Approval Gate

### Files

- `frontend/src/ide/IdePlan.tsx`
- `frontend/src/ide/planUtils.ts`
- `frontend/src/ide/ide.css`

### New Layout

- Header: `Plan approval required`.
- Center: task plan as a contract list.
- Right: approval contract summary.
- Rail: Spec done, Plan active, Execute pending.

### Must Show

- Mode.
- Provider.
- Cost cap.
- Expected output.
- Validation requirements.
- Number of tasks.

### Acceptance

- It is obvious no worker tokens are spent until approval.
- Approval and revision actions are clearly separated.
- Screenshot resembles `surface-03-approvals.png`.

## Phase 5: Website Hero And Trust Section

### Files

- `website/index.html`
- maybe `website/media/*`
- maybe generated assets in `website/images/`

### Current Problem

Product proof begins below the fold. The first viewport relies on copy.

### New Hero

Headline:

> The verified PR pipeline for AI coding work.

Supporting copy:

> Write the spec. Approve the plan. Watch agents execute. Merge only when build, tests, review, and token ledger agree.

Hero artifact:

- dark Verified Run Card
- score
- rail
- evidence
- PR URL
- cost ledger

### Nav

Reduce to:

- Product
- Docs
- Pricing
- Customers or Security
- Sign in
- Run sandbox PR

### Trust Section

Add immediately below hero:

- What "verified" means
- Build: deterministic
- Tests: deterministic
- Reviewer: LLM reviewer
- Criticals: formula/policy
- Ledger: provider usage

### Remove / De-emphasize

- Generic stats row.
- Emoji icons in core product explanation.
- Overlong competitor/SEO language in visible page.

### Acceptance

- Product artifact visible above fold at 1440x1024.
- Product artifact visible above fold at 390x844.
- Screenshot resembles `surface-01-website.png`.
- Lighthouse basics not regressed.

## Phase 6: Console Home And Runs List

### Files

- `frontend/src/console/ConsoleHome.tsx`
- `frontend/src/console/console.css`
- `frontend/src/ide/IdeMore.tsx` or current runs list surface.

### Console Home New Buckets

- Needs approval.
- Running now.
- Ready to merge.
- Blocked.

Rows should show:

- state pill
- title
- project
- mini rail
- score/cost
- next action

### Runs List

Sort/structure by merge readiness, not raw technical statuses.

Use labels:

- Ready
- Running
- Needs review
- Failed
- Awaiting approval

### Acceptance

- Home reads as merge-control room, not dashboard.
- Runs list shows lifecycle and next action.
- Screenshots resemble `surface-04-console-home.png` and `surface-05-runs-list.png`.

## Phase 7: Live Run

### Files

- `frontend/src/ide/IdeSurfaces.tsx`
- `frontend/src/ide/RunTimeline.tsx`
- `frontend/src/ide/ide.css`

### New Layout

- Top rail.
- Left: grouped agent stream.
- Right: live contract.
- Keep raw logs available but secondary.

Live contract:

- approved cap
- actual spend
- provider
- current task
- next gate

### Acceptance

- Current stage is obvious.
- Cost is visible while work runs.
- Tool stream is evidence, not the main story.
- Screenshot resembles `surface-06-live-run.png`.

## Phase 8: Project Surfaces

### Files

- `frontend/src/ide/IdeProjects.tsx`
- `frontend/src/ide/IdeProject.tsx`
- `frontend/src/ide/IdeProjectTabs.tsx`
- `frontend/src/ide/IdeProjectMore.tsx`
- `frontend/src/ide/projectArchitecturePanels.tsx`
- `frontend/src/ide/ide.css`

### Projects Index

Show:

- project health
- repo count
- architecture freshness
- average score
- mini rail/trend

### Project Overview

Show:

- average verified score
- ready PRs
- most common blocked stage
- cost this month
- merge health trend
- recent evidence

### Architecture

Show architecture freshness as evidence:

- generated from SHA
- drift files
- latest run impact
- affected package groups

### Acceptance

- Projects are not just metadata cards.
- They answer "is this project becoming safer to change?"
- Screenshots resemble surfaces 08-10.

## Phase 9: Billing And Team Governance

### Files

- `frontend/src/ide/IdeBilling.tsx`
- `frontend/src/ide/IdeOrg.tsx`
- `frontend/src/ide/IdeSettings.tsx`
- `frontend/src/ide/ide.css`

### Billing

Reframe as cost governance:

- month spend
- cache saved
- runs
- managed cap / BYOK mode
- provider ledger
- approval thresholds

### Team Settings

Reframe as governance:

- who can approve plans
- who can spend/run
- who can manage provider keys
- audit trail / external sharing / local execution policy

### Acceptance

- Billing is tied to run ledger.
- Team settings are tied to approvals and governance.
- Screenshots resemble surfaces 11-12.

## Phase 10: Press Kit, OG, And Docs

### Files

- `website/press-kit/current/*`
- `website/images/og-hero.png`
- `website/media/hero.webm`
- `website/press-kit/README.md`
- `docs/design/*`
- `docs/reason-log/2026-06.md`
- root/frontend `CLAUDE.md` if architecture/theme conventions change.

### Work

- Recapture app screenshots.
- Generate new OG image using Verified Run Card.
- Re-record product hero video if old surface still appears.
- Update docs to make Merge Rail the canonical visual system.

### Acceptance

- Press kit contact sheet looks like one product.
- OG image carries verified pipeline, not generic headline.
- Docs explain token/theme ownership.

## Testing And QA Gates

### Required Commands

Frontend:

```bash
npm --prefix frontend run build
npm --prefix frontend run lint
npm --prefix frontend run format:check
```

Focused tests:

```bash
npm --prefix frontend run test -- runResultUtils
npm --prefix frontend run test -- projectUi
```

Actual test names may differ; use available package scripts.

### Visual QA

Capture:

- desktop 1440x1024
- mobile 390x844

Pages:

- website hero
- compose
- plan gate
- console home
- runs
- live run
- result
- projects
- project overview
- architecture
- billing
- team/settings

Compare against:

- `mockups/all-surface-screenshots/contact-sheet-all-surfaces.png`

### Accessibility QA

Manual checks:

- Focus visible on all primary actions.
- Verdict and rail state are not color-only.
- Buttons and pills have readable text contrast.
- Mobile rail does not overflow.
- Tables degrade to stacked rows.

## Commit Strategy

Do not land this as one giant commit.

Recommended commits:

1. `Add Merge Rail design primitives`
2. `Redesign run result around verdict evidence`
3. `Reframe compose and plan approval surfaces`
4. `Rebuild website hero around verified run card`
5. `Rework console home and runs list lifecycle`
6. `Apply Merge Rail model to project, billing, and team surfaces`
7. `Refresh press kit and design docs`

## Risks

1. **Scope creep**
   - Mitigation: ship primitives + result first.

2. **Theme churn breaks older reused Signal components**
   - Mitigation: keep compatibility aliases until all consumers migrate.

3. **Dense app loses operator efficiency**
   - Mitigation: preserve density; improve hierarchy, not whitespace for its own sake.

4. **Marketing and app drift again**
   - Mitigation: website hero uses same Verified Run Card component visually, even if static HTML implementation differs.

5. **Score/verdict overclaims**
   - Mitigation: evidence source labels visible everywhere.

## Definition Of Done

Done requires all:

- Shared Merge Rail primitives exist.
- Run result uses VerdictCard, RunRail, CostLedger, EvidenceLedger.
- Compose uses Run Contract framing.
- Website hero shows product proof above fold.
- Console home/runs list show lifecycle and merge readiness.
- Project/billing/team surfaces use the same evidence/governance language.
- Press-kit screenshots refreshed.
- Build/lint/format/tests pass.
- Visual QA screenshots captured.

Anything less is a partial theme pass, not the redesign.
