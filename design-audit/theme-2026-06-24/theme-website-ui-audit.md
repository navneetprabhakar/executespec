# ExecuteSpec Theme / Website / UI Audit

Date: 2026-06-24

Evidence folder: `design-audit/theme-2026-06-24/screenshots/`

Captured references:
- Supabase: `01-supabase-home-{desktop,mobile}.png`
- Vercel: `02-vercel-home-{desktop,mobile}.png`
- Linear: `03-linear-home-{desktop,mobile}.png`
- Neon: `04-neon-home-{desktop,mobile}.png`
- Cursor: `05-cursor-home-{desktop,mobile}.png`
- ExecuteSpec website: `06-executespec-home-local-{desktop,mobile}.png`
- ExecuteSpec app: copied from `website/press-kit/current/*.png`

## Executive Verdict

The user feedback is directionally correct. ExecuteSpec is not broken, but it is visually underpowered versus the category bar.

The current website is clean but generic. The app is functional but visually reads like an internal admin console. The theme has too many borrowed references: light SaaS homepage, dark IDE shell, graphite control plane, Merge Rail branding, monospace accents, and several semantic color systems. Competitors win because they make one unmistakable visual bet and repeat it everywhere.

ExecuteSpec's strongest product idea is not "AI that ships PRs." That is copy. The product idea is a verified merge pipeline: spec -> plan gate -> agent work -> deterministic verdict -> PR. The theme should make that pipeline visible as the brand.

## Competitor Pattern

| Site | What They Own Visually | What They Show Immediately | Why It Feels Strong |
| --- | --- | --- | --- |
| Supabase | White developer-platform clarity with green as a disciplined product accent. | Sharp headline, trust logos, product modules. | The page says "platform" before it says "marketing." The product taxonomy appears above the fold. |
| Vercel | Severe minimalism and iconic black triangle. | One huge symbol, few words, clear CTA. | It can afford emptiness because the symbol is strong and consistent. ExecuteSpec cannot copy this yet. |
| Linear | Premium dark product world. | Simulated app workflow in the hero. | The UI is the brand. It shows how humans and agents work together without over-explaining. |
| Neon | High-energy dark data-grid aesthetic. | Dark technical environment plus product cards. | The motion/data texture maps directly to databases, scale, and agents. |
| Cursor | Warm product screenshot realism. | Actual product panes and coding-agent tasks. | It sells the experience, not a feature list. The page feels like a product you can already use. |
| ExecuteSpec | Light SaaS page with blue/teal accents and dark app shell. | Big headline, paragraph, CTAs, stats; product screenshot begins below the fold. | Competent, but not ownable. The theme does not make the verified PR pipeline tangible fast enough. |

## Pass 1: Basic Market Fit Critique

Health: acceptable foundation, weak category presence.

Findings:
- The homepage says what ExecuteSpec does, but it does not make the product feel inevitable.
- The first viewport is mostly copy and abstract metrics. The product screenshot starts at the fold, so the visitor has to trust the claim before seeing the proof.
- The app screenshots show real density and useful operator workflows, but they do not have a visually memorable "ExecuteSpec surface."
- The nav is overstuffed for a first visit: How it works, Token ledger, Workspace, Library, Pricing, Compare, FAQ. Competitors simplify the first choice.

Fix:
- Treat the hero as a product proof area, not a copy area.
- Bring a live pipeline artifact into the first viewport: plan gate, task stream, score, PR verdict, token ledger.
- Cut top nav to Product, Docs, Pricing, Sign in, Start free. Move the rest into sections.

## Pass 2: Visual Hierarchy Critique

Health: clean but timid.

Findings:
- The headline scale is strong, but the page below it is too quiet. Stats like "0-100", "5-20 min", "Tokens", "BYOK" are internal product metadata, not first-viewport proof.
- The primary CTA is black-on-light. It is usable, but it has no relationship to the Merge Rail brand or verified pipeline concept.
- The screenshot frame is decorative instead of narrative. It should show a run moving from "Spec" to "Ready PR", not a generic app card.
- On mobile, the page compresses into text, pills, and small stats. Supabase, Vercel, Neon, and Cursor preserve a strong signature object on mobile. ExecuteSpec loses identity.

Fix:
- Replace the stats row with a compact "run verdict strip": `PLAN APPROVED -> 4 tasks executed -> tests passed -> score 94 -> PR opened`.
- Make the hero visual split/fuse with the headline: left copy, right/behind a real pipeline board, or full-width pipeline rail under the headline but still visible above the fold.
- Give the CTA a product-specific label: "Run a sandbox PR" beats "Start free."
- Create a mobile-first hero object: one vertical run card with status rail, score, token spend, and PR verdict.

## Pass 3: Theme-System Critique

Health: fragmented.

Evidence from code:
- Website uses `Space Grotesk` for the public page and JetBrains Mono for accents.
- Console shell uses Inter/system.
- IDE shell uses system font with JetBrains Mono in many local surfaces.
- Website tokens are embedded in `website/index.html`; app tokens live in `frontend/src/tokens.css`, `frontend/src/console/console.css`, and `frontend/src/ide/ide.css`.

Findings:
- There is no single theme. There are at least three: public light SaaS, console light/dark control plane, and dark IDE.
- The brand accent is blue/teal, but the actual app largely reads as generic dark graphite with blue buttons.
- The Merge Rail idea exists in naming and logo assets, but the interface does not express rails, checkpoints, merges, verdicts, or movement.
- The product surfaces use tiny radii and dense borders in many places, while the marketing page uses large rounded cards. The mismatch makes the website feel like a separate product.
- The mono accent is overused as small labels but underused as an identity system. It currently reads as "developer-ish," not "verified pipeline."

Fix:
- Define one brand model:
  - Base: graphite/ink.
  - Primary accent: verdict blue.
  - Secondary accent: merge teal.
  - Warning: plan-gate amber.
  - Error: failed-run red.
  - Purple should be removed from primary marketing unless it has a product role.
- Create a shared token file for website + app, then compile or import it in both. Stop duplicating token values inside `website/index.html`.
- Make "rail" a visual primitive: left border/status rail, pipeline connector, run-step timeline, score badge, PR verdict chip.
- Standardize typography:
  - Marketing: Space Grotesk for headlines, Inter/system for body, JetBrains Mono only for machine labels.
  - Product: Inter/system for UI, JetBrains Mono only for logs, tokens, IDs, diffs.
  - Do not use JetBrains Mono as a broad brand face; it makes the product feel smaller and noisier.

## Pass 4: Product UI Critique

Health: useful, but not premium or ownable.

Findings:
- The app has dense operator information, which is good for ExecuteSpec. The problem is that everything has equal emotional weight.
- The start screen is a form. It should feel like a command center for creating a verified PR.
- The dark app shell is too close to default VS Code/admin tooling. Competitors show product-specific components; ExecuteSpec shows panels.
- The sidebar and top bar are serviceable, but they do not carry brand. No rail system, no vivid run state, no "merge readiness" pulse.
- The run result page should be the signature screen. Right now it looks like a report table. The score/verdict/PR state should be a hero object.
- The architecture/project screens are valuable but visually underplayed. They need product-shaped information hierarchy, not just dark cards.

Fix:
- Redesign the app around three signature components:
  1. `RunRail`: plan, execute, review, validate, PR as a vertical or horizontal rail.
  2. `VerdictCard`: Ready / Needs review / Failed with score, evidence count, build/test state.
  3. `CostLedger`: compact token/cost ledger with provider, cache, reasoning, cap.
- Start screen should lead with a large "Spec composer" and a live preview of the resulting plan/verdict, not just setup fields.
- Replace generic cards with workbench modules:
  - Spec
  - Plan gate
  - Agent stream
  - Evidence
  - Pull request
- Use color only for state. Blue for primary action, teal for verified/merged, amber for waiting/approval, red for failed. Remove decorative color from neutral cards.

## Pass 5: No-Excuses Critique

Health: below the bar for a developer-infra product asking users to trust autonomous code changes.

Hard findings:
- The current brand does not yet earn trust. It says "deterministic verdict" but visually behaves like a generic AI SaaS page.
- The site leads with claims instead of proof. Supabase shows platform primitives; Linear shows the workflow; Cursor shows the product; Neon shows a technical world. ExecuteSpec shows a headline and then a screenshot frame.
- "AI that ships pull requests" is not enough. Every AI coding tool now says some version of that. The defensible brand is "verified merge pipeline."
- The product UI has too little ceremony around the thing that matters: whether the AI-created PR is safe to merge.
- The public page and app feel like two different companies. That weakens perceived maturity.

Non-negotiable improvement direction:
- Stop designing ExecuteSpec as a generic SaaS dashboard.
- Make the pipeline the visual language.
- Make the verdict the hero.
- Make the token/cost ledger a trust artifact, not a stats block.
- Make the product screenshot visible above the fold on desktop and mobile.
- Collapse theme drift into one design system before adding more pages.

## Specific Recommendations

### Theme

1. Rename the visual system from "Graphite + Signal" to "Merge Rail" and make it concrete.
2. Add core primitives:
   - `rail.pending`
   - `rail.active`
   - `rail.verified`
   - `rail.failed`
   - `verdict.ready`
   - `verdict.review`
   - `verdict.failed`
   - `ledger.cost`
   - `ledger.cache`
3. Use one neutral ladder:
   - Graphite 0-4 for dark app.
   - Ink 0-4 for light marketing.
   - No extra warm gray, slate, or purple unless tied to a semantic.
4. Typography:
   - Space Grotesk: marketing headlines only.
   - Inter/system: all readable body and UI.
   - JetBrains Mono: logs, code, run IDs, token numbers.
5. Create one shared source of truth:
   - `frontend/src/design-system/merge-rail-tokens.css`
   - Website imports or mirrors generated values from it.

### Website

1. Replace first viewport layout.
   - Current: headline + paragraph + CTA + stats + screenshot below fold.
   - Target: headline + short proof copy + CTA + live product pipeline visible immediately.
2. Rewrite hero copy:
   - Current: "AI that ships pull requests your team actually merges."
   - Stronger direction: "The verified PR pipeline for AI coding work."
   - Supporting copy: "Write the spec. Approve the plan. Watch agents execute. Merge only when build, tests, review, and token ledger agree."
3. Replace stats row with proof rail:
   - `Spec accepted`
   - `Plan approved`
   - `4/4 tasks complete`
   - `Tests passed`
   - `Score 94`
   - `PR opened`
4. Make one hero artifact:
   - A real run result, not a generic "Start work" screenshot.
   - Include score, PR URL, failed/passed checks, token cost.
5. Reduce feature sections:
   - Keep: How it works, Product proof, Pricing, FAQ.
   - Move Library/Migrations below or into product proof.
6. Remove generic emoji icons from serious sections. They cheapen the product next to Supabase/Linear/Neon.
7. Add a trust band above pricing:
   - "What the verdict is based on"
   - Build, tests, reviewer, criticals, files changed, token ledger.
8. Mobile:
   - First viewport must show a compact run verdict card.
   - Current mobile first screen is too copy-heavy.

### App UI

1. Reframe `Start work` as `Create verified PR`.
2. Replace the right setup column with a live "run contract" preview:
   - Provider
   - Approval gate
   - Max cost
   - Expected validations
   - Output: PR with verdict
3. Add a persistent run rail in run detail:
   - Spec
   - Plan
   - Execution
   - Review
   - Validation
   - PR
4. Make `RunResult` the signature screen:
   - Large verdict card.
   - Evidence ledger.
   - PR action.
   - Cost ledger.
   - Issues/failures below.
5. Console home should not look like a dashboard summary first. It should answer:
   - What needs approval?
   - What is running?
   - What shipped?
   - What failed and why?
6. Sidebar:
   - Group around work lifecycle, not generic nouns.
   - Start, Approvals, Runs, Projects, Reports, Settings.
7. Use color more selectively:
   - Current blue buttons and random status colors blur together.
   - State colors should only appear where state is meaningful.
8. Bring the marketing product object into the app. The same run rail/verdict card should appear publicly and privately.

## Priority Plan

### Week 1: Theme Correction

- Create Merge Rail token map.
- Standardize type roles.
- Remove decorative purple from primary marketing.
- Define run rail and verdict color semantics.
- Make app and website share the same status/verdict primitives.

### Week 2: Homepage Rebuild

- Rebuild hero around a visible product artifact.
- Replace stats with pipeline proof.
- Move product video/screenshot above fold.
- Simplify nav.
- Replace emojis with product-native icons/rails.

### Week 3: App Signature Screens

- Redesign Start screen.
- Redesign Run Result screen.
- Add RunRail and VerdictCard.
- Update Console Home hierarchy around approvals/running/shipped/failed.

### Week 4: Trust And Conversion

- Add "What verified means" section.
- Add visible token ledger proof.
- Add one high-quality animated run story.
- Update press-kit screenshots after UI changes.

## Final Bar

The correct target is not "make it prettier." The correct target is:

> A visitor should understand within five seconds that ExecuteSpec is a verified merge pipeline, not another AI coding chat box.

And inside the app:

> A user should always know what stage a run is in, what evidence supports the verdict, what it cost, and what action they can safely take next.

Until those two statements are visually true, the theme is still too weak.
