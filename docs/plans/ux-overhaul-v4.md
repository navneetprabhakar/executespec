# UX Overhaul v4 — Project workbench IA cleanup
**Branch:** `feature/ux-overhaul-v4-pulse-tab`
**Source:** UX & Wireframe Report 2026-04-26 (`/tmp/eval-reports/wireframe-report.md`)
**Status:** SHIPPED on the branch — frontend builds clean, ready for review.

## TL;DR

Project dashboard's first-paint affordance count dropped from **25 → ~10**.
Six top-level tabs (Overview / Work / Knowledge / Activity / Settings /
Chat) collapsed into **four** (Pulse / Runs / Code / Chat) plus a "More"
drawer for the long tail. The previous Overview pane (7 stacked panels +
6 KPI tiles all "no data yet") was replaced by a focused PulseTab
(last-run hero + verified-score ring + health rollup + activity
preview).

Two personas in the source eval (Senior PM owning SEM + Lead Engineer)
both bounced because nothing on screen said "do THIS first." The new
default state of every project, both empty and post-first-run, has a
single primary CTA + one obvious "what shipped most recently" surface.

## Why this work, why now

The **2026-04-26 project eval session** ran live admin walkthroughs as
both personas. Findings (full report under `/tmp/eval-reports/`):

1. **No primary action on first paint.** Lead Engineer asked "where's
   `git clone`?"; PM asked "where do I see my team's runs?". The page
   answered neither.
2. **Tab fan-out was visually flat.** Overview / Work / Knowledge /
   Activity all read as interchangeable. PMs scanned right-to-left and
   gave up.
3. **6 KPI tiles all empty** = noise. Empty-state empathy is good but 6
   tiles of nothing is *more* visual cost than 0 tiles.
4. **Three "generate" buttons in the same pane.** Project summary +
   Explanation doc + Setup doc all live on Overview, none labelled by
   what they produce.
5. **Tech debt + workspace health + standards** were stitched across 3
   tabs — engineers couldn't see code-health in one place.

## What shipped

### Top-level IA — 6 tabs → 4 + drawer

| Old (v2.6.0) | New (v2.6.4) |
|---|---|
| Overview (7 stacked panels) | **Pulse** (4 cards + recent-PRs preview) |
| Work → Launches / PRs / Tasks | **Runs** → Launches / Pull requests / Run detail |
| Knowledge → Architecture / Docs / Memory | **Code** → Architecture / Repos / Health / Tech debt / Standards / Contracts |
| Activity (full feed) | **More ▾** drawer panel |
| Settings → Repos / Team | **Code** (Repos) + **More ▾** (Team / Settings) |
| Chat | **Chat** (unchanged) |

Backward-compat: old `?tab=<x>` deep links still work — `legacyTabToNew`
in `ProjectDashboardPage.tsx` maps every legacy key to its new home,
plus the `?drawer=<panel>` query param can deep-link directly into the
drawer.

### New components (5 + 1 rewrite)

| File | LOC | Purpose |
|---|---|---|
| `frontend/src/components/project/PulseTab.tsx` | ~280 | Last-run hero + verified-score ring + health & debt summary + activity preview + recent PRs |
| `frontend/src/components/project/CodeTab.tsx` | ~80 | 6-chip sub-tab strip wrapping every code-health surface |
| `frontend/src/components/project/MoreDrawer.tsx` | ~210 | Right-edge slide-over with 6 panels + ESC + body-scroll lock + deep-link via query param |
| `frontend/src/components/project/RunStamp.tsx` | ~190 | Copy / Share / Embed for a 1-line run summary — wired into ValidationReportPanel |
| `frontend/src/components/project/EmptyProjectLanding.tsx` | ~170 | Single-textbox empty state + sample-spec gallery + share-link continue |
| `frontend/src/pages/project/ProjectDashboardPage.tsx` | 638 → 480 | New 4-tab strip + Runs sub-tabs + compact summary bar + More button + drawer wiring |

### Touched-but-unchanged-behaviorally

- `frontend/src/components/runs/ValidationReportPanel.tsx` — added
  optional `stampMeta` prop; renders `RunStamp` at the bottom of the
  expanded panel when meta is present and status is COMPLETE/PARTIAL.
- `frontend/src/components/runs/RunDetail.tsx` — passes stampMeta
  through `MobileCollapsibleReports`.
- `frontend/src/components/project/InlineRunDetail.tsx` — passes
  stampMeta to ValidationReportPanel.
- `frontend/CLAUDE.md` — refreshed for v2.6.4.

## What was NOT shipped (deferred follow-ups)

These appear in the wireframe report's prioritised list but would
balloon this branch's diff. Tracked as separate slices:

1. **Persona tests of the wireframe.** UX report §9 #6 — 5-user
   Lookback.io / Figma + interview round before any further wireframe
   changes. PgM call.
2. **Inline ADR / Contracts / Invariants UI.** Today's E2/E3 surfaces
   are API-only on `feature/enterprise-readiness-eval`. A separate
   slice will surface them under the Code tab as inline panels (the
   placeholder is already there — sub-tab key `contracts` resolves to
   `ContractCoveragePanel` per linked repo).
3. **Session-resume nudge.** UX report §6.3 #4 — "You started a run 2h
   ago, status PLANNING. Resume?" on the empty state. Needs a backend
   helper to surface non-terminal runs older than N minutes belonging
   to the current user; deferred until V2 startup-replay lands per
   `docs/plans/harness-v2-remaining-work.md` Gap 2.
4. **One-screen "spec mode" toggle.** UX report §6.3 #5. Net-new
   surface; deferred.
5. **PRO visibility decision.** UX report §6.2 — strategy call between
   re-LISTING PRO or removing the website card. Out of frontend scope.
6. **TEAM tier ($49/seat) vs new TEAM** — already deleted on
   `feature/enterprise-readiness-eval` via V141 (renamed MAX → TEAM,
   dropped per-seat). Frontend reflects post-rename. ✅ shipped on dev.

## How to verify locally

```bash
# Install + run the frontend dev server
cd frontend && npm install && npm run dev

# In another shell, start backend (local profile, port 8080)
cd .. && JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run \
    -Dspring.profiles.active=local

# Hit the browser:
# http://localhost:5173/projects/<id>/dashboard
# - Confirm 4 top tabs (Pulse / Runs / Code / Chat)
# - Confirm "More" button in top-right opens the drawer
# - On a project with no repos + no runs: confirm EmptyProjectLanding renders
# - On a project with at least one COMPLETE run: open the run detail tab,
#   expand ValidationReportPanel, scroll to bottom — RunStamp should render
#   with Copy / Share / Embed buttons.
# - Verify ?tab=overview redirects to Pulse, ?tab=memory opens the drawer
```

## Build evidence

```
> npm --prefix frontend run build
> tsc && vite build

vite v5.4.21 building for production...
✓ 4048 modules transformed.
✓ built in 3.48s
```

No TypeScript errors. No lint warnings beyond the existing chunk-size
warning (pre-existing — not introduced by this branch).

## Out-of-scope items mentioned in the report (kept for next slice)

- §8 #5 Spring AI 1.1.4 `extra_body` bug — already fixed via V1
  router OpenAI bypass on `feature/enterprise-readiness-eval` (now
  merged to dev as commit `bf6c8da6`).
- §8 #6 Gemini 25-min stall — already fixed via 60s read timeout +
  429 Retry-After honoring on `feature/enterprise-readiness-eval`
  (commit `d859c965`).
- §8 #1-#4 — all backend / DB items, already shipped on dev.
