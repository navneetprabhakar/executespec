# UX Assessment — IDE Chrome (`frontend/src/ide/`)

> Critical, honest review of the VS Code-style **IDE** chrome (`uiMode === 'ide'`).
> Code-read assessment (not a running-browser pass). Date: 2026-05-22.
> Scope: every IDE surface + every shared panel it mounts.
> All paths absolute-relative to repo root `frontend/src/`.

The IDE chrome is genuinely impressive in **breadth** — nearly every Signal surface has a real IDE-native counterpart wired to live APIs (10 project sub-tabs, 9 run-result tabs, 6-tab admin, full GDPR/DSAR account, deployments+GCP, integrations). The depth of API coverage is the strong part. The weak part is **craft**: the iconography is placeholder-grade unicode glyphs, the "IDE" framing is skin-deep (it's a tabbed web app wearing VS Code colors, not an editor), several flagship interactions silently degrade (diff toggle, theme sync of embedded panels), and `window.confirm`/`window.prompt` are still load-bearing across destructive actions despite a `Modal`/`ConfirmModal` primitive existing. It reads as a fast, comprehensive rebuild that has not yet had a design pass.

---

## The 10 biggest UX problems in the IDE (prioritized)

1. **Iconography is unicode-glyph placeholder, everywhere.** The activity bar (`⊞ ＋ ◎ ▤ ▥ ◍`), Explorer list markers (`✓`/`•` runs, `▤` projects, `✦` admin, `＋ ◎`), every action button (`▷ ⤴ ☁ ⬇ ⌁ ↻ ⑂ ⊟ ⊘ ◷ ◉`), and the code-viewer file/folder icons are all typographic symbols or a single hand-drawn tinted-SVG document shape. There is no real icon set. This is the single largest "looks unfinished" signal and the founder flagged it first. (Defect #1 — detailed below.)

2. **Code viewer is a read-only `<table>`, and its Diff toggle is dead on the files that need it.** `IdeRunResult.openFile` only builds a diff (all-`+` lines) when a file's `status === 'ADDED'`; for **MODIFIED** files it throws away the `base` the backend already returns (`FileDiffPayload.base`) and shows content-only — so the Content/Diff toggle never appears on edited files, which is exactly where a diff matters. Plus a hand-rolled regex tokenizer (one keyword list for all languages), no folding/minimap/search-in-file. (Defect #2.)

3. **No discoverable "create project" from the workspace shell — only buried on the Projects page.** `IdeProjects.tsx` *does* have a working `＋ New project` inline-create (added in `d20d2c78`), so the founder complaint is now partly addressed — but the **Explorer PROJECTS section** (`IdeWorkspace`) has no `+` affordance, the **activity-bar Projects icon** just lists, the **⌘K palette** has no "New project" command, and **Welcome** has no create CTA. A user living in the sidebar (the whole point of an IDE chrome) cannot make a project without first navigating to `/projects`. (Defect #3.)

4. **Embedded Signal-token panels desync from the IDE theme toggle.** The IDE theme is driven by `<html data-theme>` (set by `useUiStore.setTheme`); but `CostProgressBar`, the **"Build my spec" `SpecChat` modal**, and any `Primitives.Button` rendered inside the IDE use `--signal-*` tokens, which follow a *different* attribute `<html data-signal-theme>` that the IDE toggle never updates. Flip the IDE to light and these surfaces stay dark (or whatever the last Signal session left). The cost bar on the flagship Live/Result pages is the most visible victim.

5. **`window.confirm` / `window.prompt` are still the destructive-action UX across the IDE**, despite `Modal`/`ConfirmModal`/`PromptModal` existing and being the documented standard for Signal. Cancel run, delete run, deploy, stop preview, unlink repo, delete project, erase-my-data (GDPR), every Admin tier/role/suspend/grant, top-up CRUD, drift-dismiss rationale — all native browser dialogs. Inconsistent, unstyled, un-brandable, and the `prompt()` ones can't validate well.

6. **The "IDE" is a tabbed dashboard, not an editor.** Run-result, projects, and admin all render as a horizontal tab strip (`ide-rtabs`) over a scrolling pane — the same IA as the Signal app, reskinned. There are no editor tabs that persist across navigation, no split panes outside the two file-browse views, no breadcrumb (the `.ide-breadcrumb` CSS exists but is never rendered), and the status bar is decorative (`⎇ main` is hardcoded — it does not reflect the run's actual branch). Users expecting VS Code muscle memory get a website.

7. **First-load empty/cold-start experience is thin and the "Welcome" surface under-sells.** New users (0 runs, 0 projects) see "No runs yet — start with a new spec" and "No projects yet" with no onboarding, no sample-spec prominence (samples are buried inside Compose, not Welcome), and no "create your first project" path. The whole left Explorer collapses to two static links + "No runs yet". The product's activation funnel (signup→project→spec) has no UI scaffolding here.

8. **Inconsistent run-detail depth between the two run-detail surfaces.** `IdeRunResult` (full page) uses the correct ≥85/70 verdict thresholds; the embedded `RunDetailPanel` inside `IdeProjectRuns` uses a *stale* `≥90` "Shippable" threshold — two different verdicts for the same run depending on where you look. The project Runs split-pane also can't deep-link a selected run (selection is local state, lost on refresh/back).

9. **Live-run console is functional but visually flat and lossy.** `IdeLive` caps the log at the last 100 SSE entries with no "load earlier", renders every event as a one-line `TYPE · time · message` with truncation, no grouping by task, no tool-call expansion, no copy. Tasks show `● ◉ ○` dots. For the surface a user stares at for minutes while burning credits, it's the least-polished flagship.

10. **Pervasive small-polish + a11y debt.** Buttons are `<button className='ide-btn'>` text strings (no aria-labels on icon-only ones like the `←` back, `✕` close, palette `⌘K`); the activity bar items are `title`-only tooltips; the file-tree chevrons/folder icons aren't keyboard-operable as a tree (no `role=tree`, arrow-key nav); the `ide-fileicon` CSS class is dead (component uses `ide-ficon`); `highlight.js/styles/github-dark-dimmed.css` is imported globally in `main.tsx` but the IDE viewer never uses highlight.js (bundle dead-weight); and many panels swallow fetch errors into bare empty states ("No X") that are indistinguishable from a real 403/500.

---

## Founder-flagged defects — verified in code

### Defect #1 — Icons / logos are wrong (activity bar, Explorer, code-viewer file/folder icons)

**Severity: HIGH (top cosmetic-credibility issue).**

**What's there (verified):**

- **Activity bar** (`IdeWorkspace.tsx` L18-23, L111-124): `ACTIVITY` array uses literal unicode — Workspace `⊞`, New spec `＋`, All runs `◎`, Projects `▤`, plus sidebar-toggle `▥` and Account `◍`. Rendered as text inside `.ide-act` (`font-size:19px`).
- **Explorer tree** (`IdeWorkspace.tsx` L146-197): group items use `＋`/`◎` glyphs; recent-run markers are `statusKind(r.status) === 'ok' ? '✓' : '•'` (so *every* non-complete run — failed, running, partial — collapses to the same `•` dot, losing status); projects use `▤`; admin uses `✦`. All wrapped in `.ide-tree-ico` (a 15px text span).
- **Code-viewer file icon** (`IdeKit.tsx` `FileIcon`, L262-315): a **single** hand-drawn document-shape `<svg>` whose only variation is fill/stroke `color` from a 23-entry `EXT_ICON` map, plus a 2-char text label that is **rendered nowhere** — `iconMeta(...).l` (`'ts'`, `'{}'`, `'<>'`, `'$'`) is computed but `FileIcon` only consumes `.c` (the color). So a `.ts` and a `.py` file differ *only by tint*, with the same generic page glyph. Unknown extensions get a gray dot color.
- **Folder icon** (`IdeKit.tsx` `FolderIcon`, L317-330): one tinted folder `<svg>`, open/closed differ only by `fillOpacity`.
- **Tab + group glyphs** elsewhere: `▤` project markers in `IdeProjects`/`IdeProjectRepos`/`IdeProjectTabs`/Welcome, `⌁`/`◷`/`◉` etc. across surfaces.

**Root cause:** A deliberate dependency-free choice — the comment on `FileIcon` says *"Tinted document SVG (colour = language) — reads as a real file icon, no dep."* It does **not** read as a real file icon; it reads as a tinted sheet of paper. There is no icon system at all (no `lucide-react`, no Codicons, no Seti/material-icon-theme glyph set), so every chrome affordance fell back to whatever unicode symbol was closest.

**Fix recommendation:**

1. **Activity bar + Explorer + action buttons:** adopt one real icon set. Two clean options:
   - **Codicons** (`@vscode/codicons`, the actual VS Code glyph font) — most on-brand for an "IDE" chrome; gives `files`, `git-branch`, `play`, `cloud-upload`, `account`, `extensions`, etc. for free and themes via `currentColor`.
   - **`lucide-react`** (already common in React apps, tree-shakeable, ~1KB/icon) if a font-icon dependency is unwanted.
   Replace the `ACTIVITY` `icon: string` field with an icon component, and the `.ide-tree-ico` glyphs with `<File/>`, `<FolderTree/>`, `<ShieldCheck/>` (admin), and a **status-accurate** run icon (`<CheckCircle/>` ok / `<XCircle/>` failed / `<Loader/>` running / `<AlertTriangle/>` partial) instead of the 2-state `✓`/`•`.
2. **Code-viewer file/folder icons:** ship a real per-language glyph set. The lightest path is **`material-icon-theme`'s SVG set** or **`seti-ui`** mapped by extension (both are what VS Code-style file trees actually use). Failing a dependency, at minimum *render the existing 2-char label* (`iconMeta().l`) on top of the tinted shape — that one change (use `m.l` inside `FileIcon`) instantly makes `ts`/`py`/`json` visually distinct, and the CSS hook `.ide-fileicon` (currently dead) was clearly built for exactly this.
3. **Logos:** `BrandLogos.tsx` already has real multicolour SVGs for GitHub/Slack/Jira/etc. — the IDE `Integrations` panel renders **plain text names** instead of importing them. Wire `BrandLogos` into `IdeIntegrations` rows.

---

### Defect #2 — Code viewer (`IdeKit.IdeCodeViewer` + `IdeFileTree`)

**Severity: MEDIUM-HIGH (it's billed as "the flagship").**

| Aspect | Status | Notes |
| --- | --- | --- |
| Line numbers | ✅ works | `<td className='ide-code-gutter'>` per row, right-aligned, non-selectable. Fine. |
| Syntax highlighting | ⚠️ partial | Hand-rolled `tokenizeLine` (`IdeKit.tsx` L36-76): ONE shared keyword set spanning JS+Java+Python+Go+Rust, naive string/comment scan. No per-language grammar, no JSX/TSX awareness, no multi-line strings/comments (a `/* */` block or triple-quoted Python string mis-highlights line-by-line). The `#`-comment heuristic (`!line.slice(0,i).includes('"')`) is fragile. `highlight.js` CSS is imported in `main.tsx` but **never used** here — dead weight. |
| Diff toggle | ❌ broken for the common case | Toggle only renders when `hasDiff`. `IdeRunResult.openFile` builds `diff` **only for `status==='ADDED'`** (every line a `+`); MODIFIED files get `diff:null` despite the backend returning `base`+`head` in `FileDiffPayload`. So edited files — the most useful diffs — never get a Diff view. The toggle is effectively "added-files-only." |
| Copy | ✅ works | `navigator.clipboard.writeText`, 1.2s "Copied" flip, silent catch on blocked clipboard. Good. |
| Wrap toggle | ✅ works | Flips `white-space` to `pre-wrap`. Fine. |
| File tree | ⚠️ partial | `IdeFileTree` builds a real nested tree from flat paths, sorts dirs-first, collapse/expand per node. But: not a real ARIA tree (no `role=tree`/`treeitem`, no arrow-key nav, no type-ahead), folder/file icons are the placeholder SVGs (Defect #1), no context menu, no "reveal active file", no multi-select. |
| Editor tabs | ⚠️ partial | `IdeRunResult` Code tab has open-file tabs with close `×` — good — but they're local to that one tab render, don't persist when you switch to Overview and back is fine, but there's no "pin", no overflow handling beyond `overflow-x:auto`, no dirty indicator (read-only anyway), no split. |
| Read-only framing | ⚠️ | Nothing tells the user the viewer is read-only; combined with editor-tab chrome it implies editability that isn't there. |

**Root cause:** Same dependency-free philosophy as #1 — Monaco/CodeMirror were deliberately dropped ("saves ~600KB", per the Signal `CodeView` note) in favor of a regex tokenizer + `<table>`. That's a defensible bundle decision, but the diff-toggle gap is a plain **bug** (the data is there, the branch just doesn't handle MODIFIED), and the missing per-language tokens/folding/search are real quality gaps for a surface positioned as the centerpiece.

**Fix recommendation:**
- **Immediate (bug):** in `openFile`, when `status !== 'ADDED'` and `base` differs from `head`, compute a real line diff (LCS or a tiny diff lib) from `base`→`head` and populate `diff`, so MODIFIED files get the toggle. This is the highest-value, lowest-cost fix in the whole viewer.
- **Highlighting:** either actually use the already-imported `highlight.js` (`hljs.highlightAuto`/by-extension) — it's paid for in the bundle — or keep the tokenizer but at least fix multi-line comment/string state. Per-language keyword sets keyed by extension is a small, cheap upgrade.
- **Tree a11y:** add `role=tree`/`treeitem`, `aria-expanded`, and arrow-key navigation; it's a contained component.
- Consider CodeMirror 6 (≈ Monaco features at a fraction of the size) if the viewer is truly the flagship.

---

### Defect #3 — No "create project" option

**Severity: MEDIUM (partly fixed; discoverability still broken).**

**What's actually there (verified):**

- `IdeProjects.tsx` **does** have a discoverable, working create flow: header `＋ New project` button (L124-146) → inline name input → `create()` (L40-49) calls `api.createProject({name})` and navigates to `/projects/:id`. Added in commit `d20d2c78`. **So the blanket claim "no create project option" is now stale for the `/projects` page.**
- **However**, the affordance exists *only* on `/projects`. Every other natural entry point is missing it:
  - **Explorer PROJECTS group** (`IdeWorkspace.tsx` L173-188) — lists projects as links, **no `+`/create row**. This is the IDE-shell-native place a user would expect it (like VS Code's "New Folder").
  - **Activity bar Projects icon** (`▤`) just routes to the list.
  - **⌘K command palette** (`IdeWorkspace.tsx` `commands`, L67-79) — has Workspace/New spec/All runs/Projects/theme/signal + per-run "Open run", but **no "New project" command**. A power user's first instinct (`⌘K → new project`) dead-ends.
  - **Welcome** (`IdeSurfaces.IdeWelcome`) — Projects section shows cards or "No projects yet" with **no create CTA**.
- Minor: the inline-create has thin UX — `catch { setCreating(false) }` swallows the error with no message (a duplicate-name or 403 just silently closes the input), no loading state on "Create", and Enter submits but there's no disabled-empty guard beyond `if(!name)return`.

**Root cause:** The create flow was added to the list page only; the shell/palette/welcome were never wired to it. The founder likely tested from the Explorer or palette (the IDE-native paths) and correctly found no way to create.

**Fix recommendation:**
1. Add a **`+ New project`** action to the Explorer PROJECTS group header (`IdeWorkspace`), opening the same inline-create or a `PromptModal`.
2. Add a **`New project`** entry to the ⌘K `commands` list.
3. Add a **create CTA** on Welcome's empty Projects state ("Create your first project").
4. Replace the silent `catch` in `IdeProjects.create()` with a visible error (use the existing `Banner`/`Notice` pattern) and a loading state.

---

## Surface-by-surface assessment

### Workspace chrome — `IdeWorkspace.tsx`

| Feature | Status | Notes |
| --- | --- | --- |
| Activity bar | ⚠️ partial | Works + active state, but placeholder glyphs (Defect #1), `title`-only labels (no visible labels, no aria beyond title on the toggle button). |
| Explorer (recent runs + projects + admin) | ⚠️ partial | Live data (8 runs, all projects, system/admin for SUPER_ADMIN). But: run markers lose status (`✓`/`•` only); **no create-project / create-anything affordance**; "Open" group is just two static links; no search/filter; loads silently (`.catch(()=>{})` — an API failure shows "No runs yet" indistinguishable from genuinely empty). |
| Explorer resize | ✅ works | `ResizeHandle` drag, clamped 180–520px, persisted to `es:ideSideW`. Solid. |
| Sidebar collapse | ✅ works | `▥` toggle hides the aside. |
| Status bar | ⚠️ partial | `⎇ main` is **hardcoded** (never reflects actual run/project branch), tier badge is live, theme + �leftrightarrow Signal-UI toggles work. "IDE · Beta" label. Decorative more than informative. |
| ⌘K command palette | ⚠️ partial | Works: hotkey, fuzzy `includes` filter, arrow/enter/escape, mouse hover-select, "No matches". But: substring-only (no fuzzy ranking), commands are static (no "new project", no project navigation, no actions like deploy/cancel), per-run entries only from the 8 prefetched runs. |
| Mobile (drawer + bottom tabs + top bar) | ✅ works | Distinct layout under 860px: top bar with ☰/⌘K, slide-in drawer + backdrop, bottom tab bar. `drawerOpen` resets on route change. Genuinely separate mobile IA — good. Bottom tabs use the same placeholder glyphs. |
| Theme toggle | ⚠️ partial | Cycles dark→light→system; applies to `data-theme` (IDE + tokens.css) but **not** `data-signal-theme` → embedded Signal-token panels desync (see Problem #4). |
| Signal-UI switch | ✅ works | `toggleUiMode` flips back to Signal cleanly. |

### Welcome — `IdeSurfaces.IdeWelcome`

| Feature | Status | Notes |
| --- | --- | --- |
| LiveHero (in-flight run card) | ✅ works | Finds first live run, hydrates full detail, pulsing dot, phase badge, tasks/elapsed/$ , Plan + Watch-live links. Nice touch. |
| Run cap line | ✅ works | `currentRunCount / monthlyRunLimit` (∞ for unlimited), lazy-fetches permissions. |
| Recent runs grid | ✅ works | 12 runs, loader → empty → grid; routes live→`/live`, else→`/result`. |
| Projects grid | ✅ works | Filters archived, loader/empty/grid. |
| Empty/onboarding | ❌ thin | 0-state is just "No runs yet — start with a new spec" + "No projects yet." No first-run guidance, no sample prominence, no create-project CTA. Weak activation surface (Problem #7). |
| Provider label | ✅ works | Lowercased provider chip. |

### Compose (new spec) — `IdeSurfaces.IdeCompose`

| Feature | Status | Notes |
| --- | --- | --- |
| Spec textarea | ✅ works | 12-row, JetBrains-mono, good placeholder. |
| Build-my-spec (SpecChat) | ⚠️ partial | Functional multi-turn chat + "Use as spec", BUT renders in a Signal `Modal` with `--signal-*` tokens that desync from the IDE theme (Problem #4) — modal can look out-of-chrome (dark modal in light IDE). |
| Project picker | ✅ works | Sandbox + project chips with `✓`, contextual sub-line. |
| Repo scoping | ✅ works | Loads `getProjectRepos`, all-selected default, toggle chips, uses canonical `repoDisplayName`/`repoIdFor` (SHA-vs-name bug avoided). |
| Spending cap | ✅ works | Per-tier max (FREE $3 / PRO-TEAM $25 / ENT ∞), number input clamped. |
| Estimate chip | ✅ works | Debounced p25/p75 from project history (project-only), shows tasks + cost range + basis. |
| Samples row | ✅ works | `listOnboardingSamples` → chips, `pickOnboardingSample` applies spec + preferred provider, graceful fallback to title. (But buried below the fold, not on Welcome.) |
| Provider / Mode / TDD / Figma | ✅ works | Selects + checkbox + URL input; OpenRouter gated to ENTERPRISE. |
| At-cap state | ✅ works | Yellow card with Top-up + Upgrade links, submit disabled. |
| Submit | ✅ works | Builds body conditionally, routes to `/plan/:id`. Inline error on failure. |

### Plan gate — `IdePlan.tsx`

| Feature | Status | Notes |
| --- | --- | --- |
| Poll + auto-route | ✅ works | 2s poll; routes to `/live` (executing) or `/result` (terminal). |
| Plan summary + KVs | ✅ works | Strategy, tasks, credits, estimate range, hard cap. |
| Task list | ✅ works | Numbered titles. (No per-task detail/expansion — flat list only.) |
| Revise plan | ✅ works | Feedback textarea → `editRunPlan`, poll picks up new AWAITING_APPROVAL. Good — this is a real capability. |
| Approve / Cancel | ✅ works | Approve charges credits (label shows count); Cancel → `cancelRun` → home. |
| Pre-approval (not-yet-AWAITING) | ✅ works | Shows "Planning — classifying + outlining…" badge. |
| Error states | ✅ works | Inline red error on approve/cancel/revise. |
| Polish | ⚠️ | `runId` shown raw (no short form like `R-xxxxxx`). Cancel uses inline button (no confirm) — minor; cancel here is low-stakes. |

### Live run — `IdeSurfaces.IdeLive`

| Feature | Status | Notes |
| --- | --- | --- |
| SSE stream | ✅ works | `useSse` + `useSseStore`; connection-state label (live/connecting/reconnecting/polling). |
| Status poll safety net | ✅ works | 8s poll catches terminal even if SSE drops; auto-navigates to `/result` on complete. |
| Cost progress bar | ⚠️ partial | `CostProgressBar` renders only if `hardCap>0` — but uses Signal tokens → theme-desync (Problem #4). |
| Cancel | ⚠️ partial | Works (`cancelRun`) but via `window.confirm` (Problem #5). |
| Task list | ✅ works | `● ◉ ○` dots + status badge. Placeholder glyphs again. |
| Live log | ⚠️ partial | Last-100 cap, no "load earlier", one-line entries, color by type (tool/llm/warn), auto-scroll. No grouping, no tool-call expand, no copy, flat (Problem #9). |
| Warming-up state | ✅ works | "Waiting for the first tool call…" loader. |

### Run result — `IdeRunResult.tsx` (9 tabs)

| Tab / feature | Status | Notes |
| --- | --- | --- |
| Tab strip (Overview/Code/Tasks/Timeline/Validation/Cost/PR/Tests/Chat) | ✅ works | Badges for Code(file count)/Tasks/Validation(issues). Lazy-loads Tests/Timeline/Chat on first open. |
| Overview — verdict + score | ⚠️ partial | Correct ≥85/70 thresholds (matches Signal). Plain-English not as rich as Signal's "honesty pass" sentence — just badge + number + `statusReason`. |
| Overview — Signals/Run KV cards | ✅ works | Build/Tests/Reviewer/Issues/Files/Pipeline + Status/Provider/Worker/Cost/Credits. |
| "What now" actions | ⚠️ partial | Pause/Resume (gated), Push/Open PR, Preview, Deploy, Report PDF, Share, Try {provider}×3, Fork, Archive/Unarchive, Delete. Comprehensive! But Deploy/Delete via `window.confirm` (Problem #5); the button row is a long unprioritized flex-wrap of ~12 identical-weight ghost buttons (visual overload, no grouping). |
| Preview lifecycle | ✅ works | Start → poll status+logs → iframe embed (sandboxed) → Rebuild/Stop + TTL + error surface + logs `<pre>`. Genuinely good. Stop via `window.confirm`. |
| Code tab | ⚠️ partial | File tree + editor tabs + viewer — see Defect #2 (diff broken for modified, placeholder icons). Empty state OK. |
| Tasks | ✅ works | Status badge, duration, retry on FAILED/PARTIAL/BLOCKED, failure reason, provider/model/tokens. |
| Timeline | ✅ works | Gantt-ish bars normalized to run span, color by status, duration labels. Loader/empty. Nice. |
| Validation | ✅ works | Pipeline/files/breakers/failures/issues KVs + issue rows (severity badge, file, stage, description, fix) + PDF download. Solid. |
| Cost | ✅ works | Actual/cap/credits/estimate/tokens + per-phase (classifier/outline/expansion). |
| PR | ✅ works | PR URL + open + `PrCommentInbox` (ide variant, clean), or push CTA. |
| Tests | ✅ works | Pass/fail/error counts + duration, test plan w/ compile status, root-cause groups w/ Markdown fix + "Apply fix as new run", per-test rows (cap 60) w/ mark-invalid. Very complete. Mark-invalid via `window.confirm`. |
| Chat (run-scoped + iterate) | ✅ works | Optimistic send, Markdown both roles, "Iterate as new run" routes to gate/live. Good. |
| Loading | ✅ works | Full-pane loader. |
| Run-not-found / 404 | ⚠️ | `getRun().catch(()=>{})` then renders with `run=null` → headers show "Loading…"/"Run"; no explicit "run not found / no access" state. A bad/forbidden id looks like a half-loaded page. |

### Projects list — `IdeProjects.tsx`

| Feature | Status | Notes |
| --- | --- | --- |
| Health cards | ✅ works | Per-project dashboard fetch: status (Live/Healthy/New/Archived), repos/runs/PRs, avg score, spend. |
| Create project | ⚠️ partial | Works inline (see Defect #3) but silent-fail catch + no loading + only here (not in shell/palette). |
| Mine/Archived filter | ✅ works | Chip toggle. |
| Tech-debt metric | ❌ stub | Card always shows Tech debt `'—'` (hardcoded, never fetched). Dead metric. |
| Loader / empty | ✅ works | Per state. |

### Project workspace + tabs — `IdeProject.tsx`, `IdeProjectTabs.tsx`, `IdeProjectMore.tsx` (10 sub-tabs)

| Sub-tab | Status | Notes |
| --- | --- | --- |
| Tab strip (`ProjectTabs`) | ✅ works | Compose/Pulse/Repos/Runs/Code/Chat/Architecture/Members/Activity/Settings, active-state by path. Re-rendered at top of each sub-page (slightly redundant but consistent). |
| **Compose** (`IdeProject`) | ✅ works | Header + back, spec composer + SpecChat (theme-desync caveat), repo scoping, provider/mode, recent runs. `project?.aiProvider` defaulting reads stale `project` (set in same effect) — minor: provider may not pre-fill on first paint. |
| **Pulse** (`IdeProjectPulse`) | ✅ works | Metrics (avg score/run time/spend/merged), workspace health rows, open PRs, recent runs w/ "View all". Handles both `mergedPrs30d`/`prsMerged30d` key drift. Solid. |
| **Repos** (`IdeProjectRepos`) | ✅ works | Link-repo panel (`listRepos` → link), per-repo cards (drift/webhook/PRs/lang/LOC/HEAD/arch), sync/install-webhook/unlink. Empty state points to Integrations. Unlink via `window.confirm`. Good depth. |
| **Runs** (`IdeProjectRuns`) | ⚠️ partial | Runs/PRs sub-tabs, filter chips, split-pane list + `RunDetailPanel`, PR cards w/ `PrReviewPanel` (ide) + autofix. BUT `RunDetailPanel` uses **stale ≥90 "Shippable"** threshold (inconsistent w/ rest of app, Problem #8); selected-run is local state (no deep-link/back-restore); "Autofix review" only *fetches* comments and says "autofix will be dispatched" — it does **not** actually dispatch (misleading copy). |
| **Code** (`IdeProjectCode`, 6 sub-tabs) | ✅ works | Browse (tree+viewer), Tech debt (category autofix dispatch — real), **Code review** (`CodeReviewPanel` ide), Health, Standards (TEAM+, org-gated), Docs (gen/regen/download). Browse uses placeholder icons + tokenizer (Defect #1/#2). Strong coverage overall. |
| **Chat** (`IdeProjectChat`) | ✅ works | Gated-to-TEAM+ detection (403 → upgrade card, not broken shell — good), memory digest banner, Markdown bubbles, compile-to-spec, compact, clear (confirm). Uses raw `fetch` for send (bypasses api client — inconsistent). |
| **Architecture** (`IdeProjectArchitecture`, 5 sub-tabs) | ✅ works | Current (Markdown, staleness badge, regen), Versions (compare diff picker + per-repo file list), Invariants (pass/fail), ADRs (status), Contracts (per-repo detectors/gaps/drift, rescan/check/refresh/dismiss). `Promise.allSettled` resilient. Drift-dismiss rationale via `window.prompt`. Very deep. |
| **Members** (`IdeProjectMembers`) | ✅ works | Team list (EXT badge, role, remove), invite (gated by `external_sharing_enabled` → off-state card), share-links (create/copy/revoke). RBAC-aware. Confirms via `window.confirm`. |
| **Activity** (`IdeProjectActivity`) | ⚠️ partial | Feed rows (type/summary/actor). No pagination/load-more, no `sinceDays` control, no icons/grouping, no poll-refresh (Signal refreshes every 30s). Flat. |
| **Settings** (`IdeProjectSettings`, 3 sub-tabs) | ✅ works | Settings (name/desc/provider/branch/naming/owner — raw `fetch` PATCH, RBAC-gated edit), Documents/RAG (upload/list/delete w/ status), Memory (digest + compact), Metadata, Danger zone (archive/delete via confirm). Comprehensive. |

### All-runs list — `IdeMore.IdeRuns`

| Feature | Status | Notes |
| --- | --- | --- |
| Filter strip + counts | ✅ works | All/Live/Shipped/Needs/Failed, bucketed once. Correct ≥85 threshold. |
| List rows | ✅ works | Status badge, title, R-id + project + relTime, provider, score (band color) + cost. |
| Pagination | ✅ works | "Load more" with hasNext, loading state. |
| Empty / no-match / error | ✅ works | Distinct states incl. "Show all" reset and an explicit error card. Good. |

### Account — `IdeMore.IdeAccount`

| Feature | Status | Notes |
| --- | --- | --- |
| Identity + tier/usage badges | ✅ works | Name/email/tier/usage/SUPER_ADMIN. |
| Preferences (Interface + Theme) | ✅ works | Signal/IDE segmented + Light/Dark/System. The canonical place to switch chromes. |
| Manage links | ✅ works | Billing/Plans/API-keys/Integrations/Deployments + Org-settings (role-gated). These route to **Signal-fallback** pages (billing/plans/api-keys/org are not IDE-native) — acceptable per the documented fallback set, but the chrome visibly changes when you click them. |
| Plan limits | ✅ works | 7 rows from permissions. |
| Notifications | ✅ works | Per-event/channel toggle switches (`updateNotificationPreference`), loader/empty. Real custom toggle (not placeholder). |
| GDPR export / DSAR / erasure | ✅ works | All three (ZIP / JSON / DELETE) via raw `fetch` w/ bearer + blob download; erasure has strong `window.confirm` warning. Functionally complete; the trio is via `fetch` not the api client (inconsistent, but no helper exists). |
| Sign out | ✅ works | logout → `/login`. |

### Deployments + GCP — `IdeMore.IdeDeployments`

| Feature | Status | Notes |
| --- | --- | --- |
| GCP credential panel | ✅ works | `GcpCredentialPanel` ide variant — connect/validate/disconnect, write-only JSON, never re-displays secret. Clean ide-token styling. |
| Deployment list | ✅ works | Status/version/strategy/run-link/serviceUrl/created/TTL + rollback + delete (both `window.confirm`). |
| Empty / loader | ✅ works | Per state. |

### Integrations — `IdeSettings.IdeIntegrations`

| Feature | Status | Notes |
| --- | --- | --- |
| Provider rows | ⚠️ partial | GitHub/Slack/Jira/Confluence/Bitbucket w/ Connected/Not-connected + Install/Connect actions for GH+Slack. **Uses plain text names — does NOT use `BrandLogos`** (which has real SVGs for all of these). Jira/Confluence/Bitbucket have no connect action (read-only status). Missing Stripe/Razorpay rows that `BrandLogos` supports. |
| Loader | ✅ works | |

### Admin — `IdeSettings.IdeAdmin` (6 tabs)

| Tab | Status | Notes |
| --- | --- | --- |
| Overview | ✅ works | 6-stage funnel (canonical `windowed`), tiers (subs + state badge), feature flags (read-only list — **can't toggle** here; Signal admin can). |
| Users | ⚠️ partial | List + inline tier/role/status `MiniSelect` + grant-runs. Grant uses `window.prompt` (Problem #5). No search/pagination (full list rendered — fine at small scale, won't at 1000s). |
| Orgs | ⚠️ partial | Same shape as Users + run-limit override + grant. `window.prompt` for limit/grant. No search. |
| Analytics | ✅ works | Usage/cost-by-model/billing/members, org-scoped, `allSettled`. No-org empty state. |
| Ops | ✅ works | Halt-all / verify-audit-chain / reconcile / usage-report. All confirm-gated. Reconcile via raw `fetch`. |
| Top-ups | ⚠️ partial | List + create/enable/disable/delete — all CRUD via `window.prompt`/`window.confirm`. Functional but the create flow (3 sequential prompts) is poor UX. |
| Toast feedback | ✅ works | Shared ok/bad banner. |

---

## Shared panels (mounted inside the IDE)

| Panel | Status in IDE | Notes |
| --- | --- | --- |
| `PrReviewPanel` (variant=ide) | ✅ works | Fully ide-tokened branch (`ide-card`/`ide-btn`/`--ide-*`). Submit/poll/verdict/comments/autofix + gated state. Clean. |
| `CodeReviewPanel` (variant=ide) | ✅ works | Same — dedicated ide branch. |
| `PrCommentInbox` (variant=ide) | ✅ works | ide-tokened; Fix/Dismiss/View; returns null when no pending. Clean. |
| `GcpCredentialPanel` (variant=ide) | ✅ works | ide-tokened branch throughout. |
| `Markdown` | ✅ works | `.signal-md*` classes are explicitly remapped to `--ide-*` in `ide.css` (L809-874). Renders native in both chromes. Good. |
| `CostProgressBar` | ⚠️ partial | **No variant** — always Signal tokens (`--signal-mint/coral/y/card-elev`). Tokens resolve (because `<html>` carries `.signal-app` globally) but follow `data-signal-theme`, not the IDE's `data-theme` → **theme desync** (Problem #4). |
| `SpecChat` | ⚠️ partial | Renders in Signal `Modal` + `Primitives.Button`, all Signal-tokened/`data-signal-theme`. Functional but visually off-chrome when IDE theme ≠ signal theme. |

---

## Cross-cutting findings

- **Theming architecture is fragile by design.** `main.tsx` puts `.signal-app` on `<html>` globally so Signal tokens are always defined — but it ties them to `data-signal-theme`, a *separate* axis from the IDE's `data-theme`. Any Signal-tokened component used inside the IDE (CostProgressBar, SpecChat/Modal, Primitives.Button) will not follow the IDE theme toggle. **Fix:** either (a) have `useUiStore.setTheme` mirror to `data-signal-theme`, or (b) give CostProgressBar/SpecChat ide variants, or (c) alias `--signal-*` → `--ide-*` inside `.ide-root` for the handful of tokens these components use. Option (a) is the smallest, most robust fix.
- **`window.confirm`/`window.prompt` debt.** ~20 destructive/inputs across the IDE use native dialogs while `Modal`/`ConfirmModal`/`PromptModal` exist and are the Signal standard. Inconsistent and unstyled.
- **Dead code / bundle weight.** `.ide-fileicon` CSS rule is unused (component uses `.ide-ficon`); `highlight.js/styles/github-dark-dimmed.css` imported in `main.tsx` but the IDE viewer never uses highlight.js; `iconMeta().l` (the 2-char file label) computed but never rendered.
- **Silent error swallowing.** Many fetches `.catch(()=>set([]))` so a 403/500 is indistinguishable from genuinely-empty — Explorer runs, projects, several panels. Hard to debug, and users can't tell "no access" from "nothing here."
- **Status-bar branch is hardcoded** `⎇ main` — never reflects the run/project target branch.
- **Inconsistent verdict thresholds** — `IdeRunResult` uses ≥85, `IdeProjectRuns.RunDetailPanel` uses ≥90. One run, two verdicts.
- **a11y gaps** — icon-only buttons without aria-labels (back `←`, close `✕`, palette/status `⌘K`), file tree not a real ARIA tree, activity bar relies on `title`.
- **Misleading copy** — `IdeProjectRuns.autofix` says "autofix will be dispatched" but only fetches comments; it never dispatches a fix.

---

## What's genuinely good (so the bar is clear)

- API breadth is excellent: nearly every backend capability has a live IDE wiring (run management, preview lifecycle, test root-cause + apply-fix, architecture diff/invariants/ADRs/contracts, standards, RAG docs, project memory, GDPR/DSAR/erasure, full admin).
- Loaders + empty states are present on almost every fetch (the rebuild was disciplined about that).
- The mobile layout is a real, distinct IA (drawer + bottom tabs), not a squashed desktop.
- Preview iframe lifecycle, Timeline gantt, Tests tab, and the ide-variant PR/Code-review panels are well-built.
- Resizable Explorer + persisted width, ⌘K palette, editor-tab close — the IDE *primitives* are there; they just need real icons and a few bug fixes to feel finished.
