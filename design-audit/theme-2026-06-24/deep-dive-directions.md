# ExecuteSpec Visual Direction Deep Dive

Date: 2026-06-24

Parent audit: `design-audit/theme-2026-06-24/theme-website-ui-audit.md`

Evidence:
- `design-audit/theme-2026-06-24/screenshots/contact-sheet-home-desktop.png`
- `design-audit/theme-2026-06-24/screenshots/contact-sheet-home-mobile.png`
- `design-audit/theme-2026-06-24/screenshots/contact-sheet-executespec-app.png`

This is not a "make it prettier" brief. The task is to move ExecuteSpec from a generic AI SaaS surface to an ownable developer-infra product language.

## North Star

ExecuteSpec should visually mean:

> Verified merge pipeline.

Not:

> AI dashboard.

Everything below flows from that. If a design decision does not make the verified pipeline, evidence, verdict, PR, or cost ledger clearer, it is probably decoration.

## Direction 1: Theme System

### Current Problem

The codebase has primitives, but the actual product still behaves like multiple visual systems:

- `frontend/src/design-system/primitives.css`: raw primitive colors.
- `frontend/src/tokens.css`: older app semantic layer.
- `frontend/src/console/console.css`: separate Console semantic layer.
- `frontend/src/ide/ide.css`: separate IDE semantic layer.
- `website/index.html`: embedded standalone marketing theme.

That creates visible drift:

- Website: light, roomy, rounded, Space Grotesk.
- Console: light/dark control-plane shell, Inter/system.
- IDE: dark dense workbench, system font plus JetBrains Mono.
- Brand: Merge Rail in assets and comments, but not in components.

The result is not a brand system. It is a set of local fixes.

### Desired Theme Model

Rename and design around `Merge Rail`.

The name should be literal. The product takes a spec and moves it along a rail until it reaches a merge verdict.

Core visual primitives:

| Primitive | Meaning | Where It Appears |
| --- | --- | --- |
| Rail | Progress through pipeline stages | Hero, live run, result page, project history |
| Checkpoint | A validation event happened | Plan gate, task completion, build, tests, review |
| Verdict | Merge-readiness summary | Hero artifact, run result, PR cards, reports |
| Evidence | Why the verdict is trustworthy | validation ledger, cost ledger, test/build cards |
| Ledger | Cost/token transparency | hero proof, run result, billing, reports |
| PR object | Final deliverable | hero artifact, result page, project history |

### Token Architecture

Create one semantic layer above primitives:

`frontend/src/design-system/merge-rail-tokens.css`

Suggested shape:

```css
:root {
  /* Brand surfaces */
  --mr-bg: var(--c-paper-0);
  --mr-surface: #ffffff;
  --mr-surface-raised: var(--c-paper-1);
  --mr-text: #111318;
  --mr-text-muted: #5f6673;
  --mr-border: var(--c-paper-3);

  /* Rail */
  --mr-rail-idle: #bfc6d0;
  --mr-rail-active: #4f7fe7;
  --mr-rail-verified: #2bb7a8;
  --mr-rail-waiting: #b88700;
  --mr-rail-failed: #d14b4b;

  /* Verdict */
  --mr-verdict-ready-bg: #ddf5f1;
  --mr-verdict-ready-fg: #176d64;
  --mr-verdict-review-bg: #f4e8bf;
  --mr-verdict-review-fg: #674c00;
  --mr-verdict-failed-bg: #f5dada;
  --mr-verdict-failed-fg: #8f2f2f;

  /* Ledger */
  --mr-ledger-cost: #7c6be8;
  --mr-ledger-cache: #2bb7a8;
  --mr-ledger-token: #4f7fe7;
}
```

Then add `[data-theme='dark']` overrides once. Website and app should consume the same semantic names.

### Color Discipline

Current issue: blue, teal, amber, purple, pink, cyan, status colors, and graphite all compete.

Target rule:

- Blue: primary action and active rail.
- Teal: verified / safe / merged.
- Amber: waiting for human decision or review.
- Red: failed / blocked / unsafe.
- Purple: cost/ledger only, if kept at all.
- Cyan/pink: remove from primary surfaces unless there is a named semantic.

Do not use color as decoration on cards. Use it only for state and evidence.

### Typography Discipline

Current issue: public site uses Space Grotesk, Console uses Inter/system, IDE uses system plus JetBrains Mono, and old comments say JetBrains Mono was once the "sole brand face."

Target:

- Marketing headline: Space Grotesk.
- Marketing body: Inter/system.
- Product UI: Inter/system.
- Code/log/token/IDs only: JetBrains Mono.

Hard rule:

JetBrains Mono should not carry regular UI labels. It makes the product feel more technical but less premium, and it reduces scan quality in dense app surfaces.

### Component Primitives To Add

Add these as reusable components before redesigning every screen:

1. `RunRail`
   - Horizontal for website hero and result overview.
   - Vertical for mobile and side panels.
   - Stages: Spec, Plan, Execute, Review, Validate, PR.

2. `VerdictCard`
   - Ready / Needs review / Failed.
   - Score.
   - Evidence summary.
   - Primary action.

3. `EvidencePill`
   - Build, Tests, Review, Criticals, Files, Cost.
   - Must show source kind: deterministic, LLM reviewer, formula, hybrid.

4. `CostLedgerMini`
   - Estimate, actual, cap, provider, cache tokens.

5. `PrOutcome`
   - PR URL, branch, CI state, review comments, merge readiness.

### Affected Files

High-priority:

- `frontend/src/design-system/primitives.css`
- `frontend/src/tokens.css`
- `frontend/src/console/console.css`
- `frontend/src/ide/ide.css`
- `website/index.html`

New files:

- `frontend/src/design-system/merge-rail-tokens.css`
- `frontend/src/design-system/RunRail.tsx`
- `frontend/src/design-system/VerdictCard.tsx`
- `frontend/src/design-system/EvidencePill.tsx`
- `frontend/src/design-system/CostLedgerMini.tsx`

### Acceptance Criteria

- Website and app use the same status/verdict/rail colors.
- A screenshot of website hero and app run result clearly look like the same product.
- No generic purple/pink/cyan decoration on primary surfaces.
- JetBrains Mono appears only in code/log/token/ID contexts.
- The word "rail" is not only in comments; it appears as an actual visual primitive.

## Direction 2: Website

### Current Problem

The homepage first viewport is:

- nav
- huge headline
- paragraph
- CTA row
- abstract stats
- product screenshot starting at the bottom edge

This is backwards for the category. Developer-infra buyers want proof immediately. Supabase shows platform modules. Linear and Cursor show product workflow. Neon shows a technical world. ExecuteSpec asks the visitor to read claims first.

### Website Positioning

Replace:

> AI that ships pull requests your team actually merges.

With a more ownable category:

> The verified PR pipeline for AI coding work.

Supporting copy:

> Write the spec. Approve the plan. Watch agents execute. Merge only when build, tests, review, and token ledger agree.

This is stronger because it claims the operating model, not just the output.

### Hero Layout

#### Desktop

Target first viewport:

```text
Nav

[Left]
The verified PR pipeline
for AI coding work.

Write the spec...
[Run a sandbox PR] [See how verification works]

[Right / Under]
RunRail:
Spec -> Plan -> Execute -> Review -> Validate -> PR

VerdictCard:
READY TO MERGE
Score 94
Build green · Tests 38/38 · Review clean · Cost $0.42
Open PR: github.com/acme/web/pull/248
```

The product artifact must be above the fold. It can be mocked, but it must look like a real ExecuteSpec run.

#### Mobile

Target first viewport:

```text
ExecuteSpec        Start

The verified PR pipeline
for AI coding work.

[Run a sandbox PR]

READY TO MERGE
Score 94
Spec -> Plan -> Tests -> PR
$0.42 actual / $2 cap
```

Mobile should not show four abstract stats. It should show one compact verdict object.

### Homepage Structure

Replace the current section order:

1. Hero
2. Demo
3. Token transparency
4. How it works
5. Workspace
6. Library
7. Pricing
8. Compare
9. FAQ

With:

1. Hero: verified PR pipeline.
2. Product proof: one run, six stages, visible evidence.
3. Why the verdict is trusted: build, tests, reviewer, criticals, token ledger.
4. Work modes: sandbox, connected repo, multi-repo project, local CLI.
5. Pricing.
6. FAQ.

Library/migrations are useful, but they are not the primary story. Move them under product proof or a secondary page. Right now they dilute the core positioning.

### Nav

Current nav is too busy:

- How it works
- Token ledger
- Workspace
- Library
- Pricing
- Compare
- FAQ

Target nav:

- Product
- Docs
- Pricing
- Sign in
- Run sandbox PR

Reason: the first decision should be "understand product" or "try it." Not seven internal anchors.

### Visual Style

Current public page:

- Clean light background.
- Large black headline.
- Blue highlighted words.
- Cards and stat rows.

Target:

- Keep light background if desired, but introduce the rail object as the main visual identity.
- Use dark product artifact inside light page to connect to the app.
- Use fewer rounded generic cards.
- Use thin rails, checkpoint nodes, verdict chips, and code/PR artifacts.
- Remove emoji icons from serious product sections.

### Website Components

1. `HeroRunArtifact`
   - Shows run rail, verdict card, token ledger, PR link.
   - Replaces generic screenshot/video placement in first viewport.

2. `VerificationStack`
   - 5 evidence cards:
     - Build
     - Tests
     - Reviewer
     - Criticals
     - Cost ledger
   - Each card should explain deterministic vs LLM vs formula evidence.

3. `RunStory`
   - Step-by-step: spec -> plan -> execute -> verify -> PR.
   - Use actual UI fragments, not abstract icons.

4. `Comparison`
   - Keep, but sharpen:
     - Cursor/Copilot: editing assistance.
     - Devin: autonomy.
     - ExecuteSpec: reviewed, verified, cost-visible merge pipeline.
   - Avoid sounding like SEO spam.

### Copy Changes

Replace generic:

- "Start free"
- "Try the sandbox"
- "Token transparency"
- "A real IDE, in the browser"

With product-specific:

- "Run a sandbox PR"
- "See a verified run"
- "Inspect the token ledger"
- "Review the merge evidence"

### Motion

Use motion only to explain pipeline progress:

- Rail segment lights up.
- Stage moves from pending to active to verified.
- Cost counter increments.
- Verdict appears after evidence completes.

Do not add floating gradients, bokeh, particles, or decorative animation.

### Website Acceptance Criteria

- At 1440x1100, a product artifact is visible without scrolling.
- At 390x844, a verdict/pipeline artifact is visible without scrolling.
- A first-time visitor can explain the pipeline after five seconds.
- The hero does not rely on the phrase "AI that ships PRs" to be understood.
- The public page and app share the same VerdictCard visual language.

## Direction 3: Product UI

### Current Problem

The app has the right data but the wrong ceremony.

Existing `IdeRunResult.tsx` already has:

- `verifiedScore`
- verdict label via `verdictFor`
- PR URL
- build breakers
- tests
- review failures
- issues
- files generated
- pipeline result
- cost
- provider
- credits
- validation ledger

But the overview renders these as scattered badges, cards, tabs, and key-value rows. That makes the most important product promise feel like an admin report.

### Product UI North Star

Every run screen should answer four questions immediately:

1. Where is this run in the pipeline?
2. Is it safe to merge?
3. What evidence supports that?
4. What did it cost?

### App Architecture Direction

Stop making the app feel like a dashboard with routes.

Make it feel like a workbench with a run lifecycle:

```text
Start -> Plan -> Live Work -> Result -> PR / Iterate
```

Route names can stay, but the visible UI should be lifecycle-first.

### Start / Compose Screen

Current screen:

- "Start work"
- large textarea
- run setup panel
- review panel
- context selector

Problem:

It reads as a form. For a product asking permission to modify code, the first screen should feel like a contract.

Target:

```text
Create verified PR

[Spec composer]
Describe the change...

[Run contract]
Output: Pull request with verdict
Approval: Plan gate before execution
Validation: Build + tests + reviewer + criticals
Cost cap: $2
Provider: Anthropic

[Expected rail]
Spec -> Plan -> Execute -> Review -> Validate -> PR

[Create plan]
```

Implementation:

- Rename visible label from `Start work` to `Create verified PR`.
- Keep textarea prominent.
- Convert right panel from "Run setup / Review" into "Run contract."
- Show exact things the user is agreeing to.
- Add a compact rail preview.

### Console Home

Current home:

- "What needs the next decision?"
- metrics for awaiting decisions, live work, projects.
- attention queue and setup.

This is decent information architecture, but visually it is still generic.

Target:

The home page should be a merge-control room:

1. Needs approval
2. Running now
3. Ready to merge
4. Failed / blocked

Each row should carry:

- stage
- verdict or pending stage
- score if available
- cost
- next action

Add a compact `RunRailMini` to each important row. This will create product identity without making the page decorative.

### Live Run Screen

The live screen should have the strongest rail behavior:

- Current stage highlighted.
- Tool events grouped under stage.
- Cost ledger visible but compact.
- Human intervention points clear.

Suggested layout:

```text
[RunRail horizontal]
Spec | Plan | Execute | Review | Validate | PR

[Left] Agent stream grouped by current task
[Right] Run contract + live cost + current validation state
```

Do not show a raw event stream as the main product. The raw stream is evidence; the main product is progress toward merge.

### Run Result Screen

This is the signature screen.

Target top section:

```text
READY TO MERGE       Score 94
Build green · Tests 38/38 · Review clean · 0 criticals

PR opened
github.com/acme/web/pull/248

Actual cost $0.42 / cap $2.00
Anthropic · cache saved 31%

[Open PR] [Download report] [Iterate]
```

Then:

```text
RunRail
Spec ✓ -> Plan ✓ -> Execute ✓ -> Review ✓ -> Validate ✓ -> PR ✓
```

Then:

```text
Evidence ledger
Build       Deterministic     green
Tests       Deterministic     38/38
Reviewer    LLM reviewer      clean
Criticals   Formula           0
Cost        Provider ledger    $0.42
```

Tabs can remain below this for detailed code/log/test exploration. The current tabs should not be the first thing a user sees.

### Project Screens

Current project screens are dark cards with sparse data. They should show project health as accumulated merge evidence.

Add:

- Last 10 runs by verdict.
- Average verified score.
- Failed stage distribution.
- Cost trend.
- PR merge state.
- Architecture freshness.

Project page should answer:

> Is this project becoming safer to change?

Not just:

> Here are project metadata cards.

### Sidebar / Navigation

Current nav:

- Start
- Work
- Projects
- Team
- Setup
- Recent Work
- Operator/Admin/Benchmarks

Target lifecycle grouping:

- Create
- Approvals
- Runs
- Projects
- Reports
- Team
- Settings

For admin/operator routes, keep them lower and visually separated. They should not define the product's primary identity.

### App Components To Build

#### `RunRail`

Props:

```ts
type RunRailStage = 'spec' | 'plan' | 'execute' | 'review' | 'validate' | 'pr';
type RunRailState = 'pending' | 'active' | 'verified' | 'failed' | 'skipped';

type RunRailProps = {
  stages: Array<{
    id: RunRailStage;
    label: string;
    state: RunRailState;
    detail?: string;
  }>;
  orientation?: 'horizontal' | 'vertical';
  compact?: boolean;
};
```

#### `VerdictCard`

Props:

```ts
type VerdictCardProps = {
  verdict: 'ready' | 'review' | 'failed' | 'running';
  score?: number;
  statusReason?: string;
  prUrl?: string;
  evidence: Array<{label: string; value: string; kind: 'ok' | 'warn' | 'bad'}>;
  cost?: {actual?: number; cap?: number; provider?: string};
};
```

#### `EvidenceLedger`

This already exists conceptually in `report.verificationLedger`; the UI should make it first-class.

Props:

```ts
type EvidenceLedgerProps = {
  entries: Array<{
    claim: string;
    source: 'deterministic' | 'llm_reviewer' | 'llm_assessment' | 'hybrid' | 'formula';
    result: string;
    severity?: 'ok' | 'warn' | 'bad';
  }>;
};
```

### App Acceptance Criteria

- The first visible element on result page is a verdict object, not a title plus tabs.
- A non-technical stakeholder can tell if a PR is merge-ready without opening tabs.
- A technical reviewer can inspect evidence with one click.
- Cost is always visible beside verdict, not buried in the Cost tab.
- Run lifecycle is visible on compose, live, and result screens.

## Direction 4: Trust Layer

### Current Problem

ExecuteSpec claims deterministic scoring and token transparency, but the design treats those as content blocks, not trust infrastructure.

Trust is the actual product.

### Trust Objects

Create a repeated visual grammar for:

- Claim
- Evidence source
- Result
- Confidence / severity
- Action

Example:

```text
Tests
Deterministic
38/38 passed
No action needed
```

For LLM-based checks:

```text
Reviewer
LLM reviewer + wiring pass
2 minor issues fixed
Open details
```

This prevents overclaiming. It also visually explains what is deterministic and what is model-assessed.

### Website Trust Layer

Add a section:

> What "verified" means

Cards:

- Build result: deterministic
- Tests: deterministic
- Reviewer: LLM reviewer
- Critical issues: policy/formula
- Cost ledger: provider usage
- PR state: GitHub/SCM

This should replace some of the current generic feature storytelling.

### App Trust Layer

Put evidence source labels directly in UI:

- `DETERMINISTIC`
- `LLM REVIEWER`
- `HYBRID`
- `FORMULA`
- `PROVIDER LEDGER`

Use small uppercase machine labels, but do not make all surrounding UI monospace.

### Acceptance Criteria

- Every score/verdict display links to evidence.
- Deterministic checks are visually distinguished from LLM judgment.
- Cost and token claims show source.
- The website does not imply the score is magic.

## Direction 5: Brand Assets And Product Imagery

### Current Problem

Competitors have strong first-glance artifacts:

- Supabase: product taxonomy cards and green Postgres identity.
- Vercel: black triangle.
- Linear: premium app workflow.
- Neon: dark data-grid world.
- Cursor: product screenshot realism.

ExecuteSpec has a logo and screenshots, but no iconic product artifact.

### Needed Artifact

Create the `Verified Run Card`.

It should be usable in:

- Homepage hero.
- OG image.
- Press kit.
- App run result.
- Investor deck.
- Product Hunt launch.

Composition:

```text
READY TO MERGE
Score 94

Spec        OAuth callback hardening
Plan        Approved by Navneet
Execute     4/4 tasks complete
Review      Clean
Validate    38/38 tests passed
PR          github.com/acme/web/pull/248

Cost        $0.42 / $2.00 cap
Provider    Anthropic
```

This is ExecuteSpec's equivalent of Vercel's triangle or Linear's issue panel. It is not a logo, but it is a reusable product-signature object.

### Imagery Rules

- Use real product surfaces, not abstract screenshots.
- Use one clear run story repeatedly.
- Avoid generic dashboards.
- Avoid decorative gradients.
- Do not use emoji icons in primary marketing.
- Show PR and evidence as concrete artifacts.

## Direction 6: Implementation Sequence

### Phase 1: Design System Foundation

Files:

- `frontend/src/design-system/merge-rail-tokens.css`
- `frontend/src/design-system/RunRail.tsx`
- `frontend/src/design-system/VerdictCard.tsx`
- `frontend/src/design-system/EvidenceLedger.tsx`
- `frontend/src/design-system/CostLedgerMini.tsx`

Work:

- Add shared tokens.
- Add components in isolation.
- Add focused unit tests for verdict/state mapping.
- Do not redesign pages yet.

### Phase 2: App Result Screen

Files:

- `frontend/src/ide/IdeRunResult.tsx`
- `frontend/src/ide/runResultUtils.ts`
- `frontend/src/ide/ide.css`

Work:

- Replace current overview top area with `VerdictCard`.
- Add `RunRail` below verdict.
- Pull cost out of Cost tab into overview.
- Pull evidence summary out of Validation tab into overview.
- Keep tabs for detail.

Reason:

The app result screen is the product proof. Fixing it first gives the website a real artifact to show.

### Phase 3: Compose / Start Screen

Files:

- `frontend/src/ide/IdeSurfaces.tsx`
- `frontend/src/ide/ide.css`

Work:

- Reframe as `Create verified PR`.
- Add run contract preview.
- Add expected rail.
- Make context/provider/cap feel like contract terms, not settings clutter.

### Phase 4: Website Hero

Files:

- `website/index.html`
- possibly `website/media/*` after recapturing assets.

Work:

- Replace hero stats with `Verified Run Card`.
- Put product artifact above fold.
- Rewrite positioning.
- Simplify nav.
- Add "What verified means" trust section.

### Phase 5: Console Home

Files:

- `frontend/src/console/ConsoleHome.tsx`
- `frontend/src/console/console.css`

Work:

- Reframe home around lifecycle buckets.
- Add mini rail to run rows.
- Add ready-to-merge / needs-review grouping.

### Phase 6: Press Kit And Proof

Files:

- `website/press-kit/current/*`
- `website/images/og-hero.png`
- `website/media/hero.webm`

Work:

- Recapture screenshots after app changes.
- Generate new OG hero around Verified Run Card.
- Replace product video if it still shows old generic surfaces.

## Direction 7: What Not To Do

Do not:

- Copy Supabase green.
- Copy Vercel minimalism.
- Copy Linear black-premium aesthetic wholesale.
- Add gradient blobs.
- Add more feature cards.
- Add more homepage sections.
- Make the app more spacious at the cost of operator density.
- Make everything monospace.
- Treat token transparency as a billing feature only.
- Treat verified score as just a number.

ExecuteSpec's advantage is not beauty. It is evidence-backed autonomy. The design has to make that advantage visible.

## Concrete Before / After

### Website Hero

Before:

> Big copy, CTA, abstract stats, product starts below fold.

After:

> Big category claim, CTA, verified run card, rail, evidence, PR URL above fold.

### Run Result

Before:

> Title, badge, score, scattered signal cards, tabs.

After:

> Verdict object, rail, evidence summary, cost ledger, PR action, then tabs.

### Start Screen

Before:

> Form.

After:

> Run contract.

### Console Home

Before:

> Dashboard.

After:

> Merge-control room.

## Final Standard

When this work is done, five screenshots should be enough to sell the product:

1. Homepage hero with Verified Run Card.
2. Compose screen with Run Contract.
3. Live run with RunRail and agent stream.
4. Run result with VerdictCard and EvidenceLedger.
5. Project page with merge-health history.

If those five screenshots do not look like one product, the design system is still not fixed.
