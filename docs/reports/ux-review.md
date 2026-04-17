# UX Consistency Review — Run views vs Project views

> Scope: visual/typographic consistency between the two primary surfaces of the
> ExecuteSpec frontend. Captured 2026-04-16 on `feature/validation-v3-pillar1-requirement-graph`
> against the live local build (admin@executespec.ai, ENTERPRISE tier, desktop 1440×900).
> Method: live preview snapshots + DOM inspect + source-code grep of
> `frontend/src/components/{runs,project,shared}/*` and `frontend/src/pages/**`.

## TL;DR

There are **two visual dialects** in the same product. The Run surface speaks
in `font-mono uppercase tracking-wider` for navigation and section headers; the
Project surface speaks in sentence-case sans-serif. Cards, status pills, border
radii, and even the typographic scale (Tailwind preset vs. arbitrary `text-[Npx]`)
diverge. There is no shared `Card` / `SectionHeader` / `Tab` primitive enforcing
the design system across the two surfaces.

## Verified ground truth (live inspect)

| Element | Where | Computed style |
|---|---|---|
| `<body>` | global | `font-family: "Geist Sans"`, `font-size: 16px`, `color: rgb(237,237,237)`, `bg: rgb(0,0,0)` |
| `<header>` | shared | `font-family: Geist Sans`, height `h-14`, `border-b border-[var(--border-default)]` |
| New Run button | RunList | `font-family: Geist Sans`, `font-size: 14px`, `font-weight: 500`, `letter-spacing: normal` |

The font *family* is uniform (Geist Sans body, Geist Mono in code/IDs, both via
`tailwind.config.js` font definitions). The inconsistencies are in **size,
casing, tracking, weight, radius, and spacing tokens** — not in the typeface.

## Side-by-side dialect comparison

| Surface | Header tabs | Section headers | Filter chips |
|---|---|---|---|
| **Runs list** ([components/runs/RunList.tsx](frontend/src/components/runs/RunList.tsx), [RunDetailTabs.tsx:148](frontend/src/components/runs/RunDetailTabs.tsx:148)) | `MY RUNS / ORG RUNS / ALL RUNS` — `font-mono uppercase tracking-wider` | `OVERVIEW / PLAN / TASKS / GRAPH / LIVE LOG / CODE / PREVIEW / CHAT` — `font-mono uppercase` | `ALL / PLANNING / WORKING / AWAITING APPROVAL / COMPLETE / PAUSED / FAILED / ARCHIVED` — `font-mono uppercase` |
| **Project list** ([pages/project/OrgProjectsPage.tsx](frontend/src/pages/project/OrgProjectsPage.tsx)) | `Projects` (h1, sans) — no tabs | `Hostel Management / UX Test Project / 5-Repo Evaluation` — sans, title-case | n/a |
| **Project dashboard** ([pages/project/ProjectDashboardPage.tsx:241-245](frontend/src/pages/project/ProjectDashboardPage.tsx:241)) | `Overview / Launches / PRs / Tasks / Repos / Architecture / Docs / Memory / Chat / Team` — sans, sentence-case, `text-xs font-medium` | `Project settings / Project summary / Workspace health / Recent submissions` — sentence-case sans | n/a |
| **Run detail** ([RunDetailTabs.tsx:148](frontend/src/components/runs/RunDetailTabs.tsx:148)) | `OVERVIEW / PLAN / TASKS / GRAPH / LIVE LOG / CODE / PREVIEW / CHAT` — `font-mono uppercase` | `RUN STATS / CREDITS / QUEUED / PLANNING / WORKING / REVIEWING / CODE GENERATED` — uppercase | property labels `Time taken / Started / Created by / Total tokens` — sentence-case |

A user clicking "Open" on a project dashboard goes from `MY RUNS` (shouting,
mono) to `Overview / Launches / PRs` (whispering, sans) **inside the same
session, on the same product, with the same admin account**. There is no
in-product signal that this is intentional.

## Concrete mismatches with file:line citations

### 1. Tab strip dialect collision — most visible
- [components/runs/RunDetailTabs.tsx:148](frontend/src/components/runs/RunDetailTabs.tsx:148) — `font-mono font-medium uppercase tracking-wider text-[10px] lg:text-xs`
- [pages/project/ProjectDashboardPage.tsx:241](frontend/src/pages/project/ProjectDashboardPage.tsx:241) — `font-medium text-xs` (no mono, no uppercase, no tracking)

**Impact:** the tab as a navigation primitive looks like two different controls.
A QA engineer would file this as "the tabs on Projects look broken vs Runs."

### 2. Card border-radius divergence
- [components/runs/RunCard.tsx:38](frontend/src/components/runs/RunCard.tsx:38), [RunCard.tsx:71](frontend/src/components/runs/RunCard.tsx:71) — `rounded-md` (6px)
- [components/project/RepoCard.tsx:55](frontend/src/components/project/RepoCard.tsx:55), [ProjectDashboardPage.tsx:321](frontend/src/pages/project/ProjectDashboardPage.tsx:321), [ProjectDashboardPage.tsx:374](frontend/src/pages/project/ProjectDashboardPage.tsx:374) — `rounded-sm` (2px)

The Geist token system defines `--radius-sm: 2px` as the default. Run cards
break the system; Project panels follow it.

### 3. Arbitrary pixel sizes outside the Tailwind scale
The Project surface routinely reaches into bracket-pixel notation that
sidesteps the design system:
- [ProjectDashboardPage.tsx:163](frontend/src/pages/project/ProjectDashboardPage.tsx:163) — `text-[14px]`
- [ProjectDashboardPage.tsx:189](frontend/src/pages/project/ProjectDashboardPage.tsx:189) — `text-[14px] font-medium`
- [ProjectTaskList.tsx:56](frontend/src/components/project/ProjectTaskList.tsx:56) / [:70](frontend/src/components/project/ProjectTaskList.tsx:70) / [:82](frontend/src/components/project/ProjectTaskList.tsx:82) — `text-[11px]`, `text-[9px]`
- [components/project/RepoCard.tsx:61](frontend/src/components/project/RepoCard.tsx:61) and ~7 sibling lines — `text-[11px]`

Compare to Run cards which (mostly) stay on the standard scale:
- [components/runs/RunCard.tsx:48](frontend/src/components/runs/RunCard.tsx:48) — `text-sm font-medium`
- [components/runs/RunCard.tsx:51](frontend/src/components/runs/RunCard.tsx:51) — `text-xs`

Arbitrary pixel values short-circuit the responsive scale (`lg:text-sm` etc.)
and mean a future scale bump won't carry through Project.

### 4. `StatusBadge` is a Run-only component
- [components/shared/StatusBadge.tsx](frontend/src/components/shared/StatusBadge.tsx) — used on every Run detail header (it owns the `statusReason` tooltip from v2.5.10).
- [components/project/ProjectTaskList.tsx:82](frontend/src/components/project/ProjectTaskList.tsx:82) — re-implements status as inline `<span class="font-mono ...">`, no shared component, no tooltip.
- [pages/project/ProjectDashboardPage.tsx:456](frontend/src/pages/project/ProjectDashboardPage.tsx:456) — `text-[9px] font-mono tabular-nums` for status — also bypasses the badge.

**Consequence:** `statusReason` (the v2.5.10 honest-PARTIAL feature) is invisible
on every Project surface. The same FAILED/PARTIAL run shows different
treatment depending on which page you're looking at.

### 5. Backend statuses with no frontend mapping
The Execution View (project Launches) flows through `SETUP → ANALYSING → ARCH_REVIEW → EXECUTING → COMPLETE` per [execution/service/ExecutionOrchestrator.java](src/main/java/com/navneet/executespec/execution/service/ExecutionOrchestrator.java). The frontend status tables don't cover all of them:
- [frontend/src/lib/constants.ts](frontend/src/lib/constants.ts) `STATUS_COLORS` — has WORKING, PLANNING, REVIEWING, AWAITING_APPROVAL, COMPLETE, FAILED, etc.
- **Missing:** `ANALYSING`, `ARCH_REVIEW`, `SETUP` — fall through to `.replace(/_/g, ' ')` with no semantic color.
- [components/shared/StatusBadge.tsx:14-26](frontend/src/components/shared/StatusBadge.tsx:14) `DISPLAY_NAMES` — same gap.

A user staring at a project launch in `ANALYSING` sees an uncoloured "Analysing"
chip while every other status pops with colour.

### 6. Ghost batch states still mapped
`STATUS_COLORS` and `DISPLAY_NAMES` still contain `QUEUED_BATCH`, `IN_PROGRESS_BATCH`,
`BATCH_COMPLETE` — but CLAUDE.md says "V2 backend never emits them." Dead enum
mappings clutter the badge logic.

### 7. Empty states are inconsistent in voice
- [components/runs/RunList.tsx](frontend/src/components/runs/RunList.tsx) — uses a dedicated empty-state with icon + helper text.
- [components/project/ProjectLaunchesList.tsx:27](frontend/src/components/project/ProjectLaunchesList.tsx:27) — inline `"No launches yet. Click 'Launch' to start a cross-repo run."`
- [components/project/ProjectMemoryPanel.tsx:62](frontend/src/components/project/ProjectMemoryPanel.tsx:62) — `"No project memory yet. Memory is generated automatically after each completed run."`
- [components/project/ProjectTaskList.tsx:50](frontend/src/components/project/ProjectTaskList.tsx:50) — `<div className="text-xs text-muted">No runs yet.</div>`
- [components/project/ProjectRepoManager.tsx:85](frontend/src/components/project/ProjectRepoManager.tsx:85) — `"No repositories linked yet. Click 'Add repository' to get started."`

Each empty state was written by hand with no shared component. Punctuation,
imperatives ("Click X"), and helper text density vary between them.

### 8. Run view has *internal* casing inconsistency too
On the Runs list:
- Tabs `MY RUNS / ORG RUNS / ALL RUNS` — UPPERCASE mono.
- Filter chips `ALL / PLANNING / WORKING / AWAITING APPROVAL / COMPLETE` — UPPERCASE mono.
- Project chips `All Runs / Unassigned / Hostel Management / UX Test Project` — sentence-case sans.

Three tab strips on the same screen, three different visual treatments. The
UPPERCASE mono is "system metadata"; sentence-case is "user content" — a
defensible rule, but not surfaced as one anywhere.

### 9. Inline action buttons add `font-medium` only on Project
- [components/project/RepoCard.tsx:93](frontend/src/components/project/RepoCard.tsx:93) — `text-[11px] font-medium px-2 py-0.5 rounded-sm`
- [components/runs/RunCard.tsx](frontend/src/components/runs/RunCard.tsx) — same shape, no `font-medium` on the same actions.

### 10. KPI strip on Project Dashboard *does* match the Run dialect
([ProjectDashboardPage.tsx](frontend/src/pages/project/ProjectDashboardPage.tsx))
The top metric strip (`ACTIVE / COMPLETED / FAILED / AVG SCORE / TOKENS / COST`)
*is* UPPERCASE mono, and the giant numeric values use `tabular-nums`. So the
inconsistency isn't 100/0 — there's a self-aware nod to the Run dialect at the
top of the dashboard, then it drops the convention everywhere below.

## Suggested fixes (concrete)

### Now (low risk, no rewrite)

1. **Add the missing status enums** to `constants.ts` STATUS_COLORS and
   `StatusBadge.DISPLAY_NAMES`: `ANALYSING`, `ARCH_REVIEW`, `SETUP`. Map them
   to the same purple/blue family as PLANNING/WORKING.
2. **Strip the dead batch enums** (`*_BATCH`) — V2 doesn't emit them.
3. **Use `StatusBadge` on Project surfaces** — replace the inline status
   spans in `ProjectTaskList.tsx:82` and `ProjectDashboardPage.tsx:456` with
   `<StatusBadge status={...} statusReason={...} />`. Single change closes
   the v2.5.10 tooltip gap on Project pages too.
4. **Pick one empty-state component** and use it everywhere
   (`shared/EmptyState.tsx` if it exists, otherwise lift the one from RunList).

### Next (1-2 PRs)

5. **Codify the dialect rule** in a `<SectionHeader />` and `<TabStrip />`
   primitive. Two variants: `dialect="system"` (UPPERCASE mono, for tabs and
   metadata-style sections) and `dialect="content"` (sentence-case sans, for
   user-named entities). Both surfaces consume the same primitive; new tabs
   inherit the correct dialect by default.
6. **Replace every `text-[Npx]` in `frontend/src/components/project/*` and
   `frontend/src/pages/project/**`** with the closest Tailwind preset
   (`text-xs / text-sm / text-base`). If no preset fits, add it to
   `tailwind.config.js` once instead of inlining 8 sites.
7. **Standardise card radius** on `rounded-sm` (the design token says 2px).
   Update `RunCard.tsx:38,71` to drop `rounded-md`. Visual diff is a 4px
   delta — small but uniform.

### Later (1 sprint)

8. **ESLint rule** banning bracket-pixel sizes for `text-*` and `p-*` outside
   `tailwind.config.js`. Catches regressions on day one.
9. **Visual-regression CI** — Playwright + screenshot diff against checked-in
   baselines for `/`, `/runs/{id}`, `/org/projects`, `/projects/{id}`. The
   inconsistencies above would have shown up on the first PR that introduced
   them.

## What is genuinely consistent

- **Geist Sans / Geist Mono pair** — both surfaces consume from the same
  font stack (`tailwind.config.js`). Casing/sizing varies, family doesn't.
- **Color tokens** — both surfaces resolve `var(--text-primary)`,
  `var(--border-default)`, `var(--geist-bg)` from `tokens.css`. No surface
  hard-codes a hex outside the accent palette.
- **Header chrome** — the top bar (`ExecuteSpec / Runs / VIBE / PRO / PROJECT`,
  search, notifications, avatar) is identical on every page.
- **Sidebar nav** — same icon stack on every page.

The fix is therefore not a re-skin; it's adopting the design tokens that
already exist on both sides of the divide.
