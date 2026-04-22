# Screenshot Capture Guide — ExecuteSpec

_For P0-22 (PH gallery) + P0-24 (swap mock tour panels for real assets)._

Recommended workflow: macOS `Cmd+Shift+4` region capture into HiDPI PNGs at 1440×900 viewport. Drop the files into `website/press-kit/` with the exact names below — the tour-panel swap job will `<img src="">` them from there.

## Pre-flight

1. Backend running: `localhost:8080` (Spring Boot)
2. Frontend running: `localhost:5173` (Vite)
3. Logged in as a user with data: `admin@executespec.ai` (ENTERPRISE tier, has 96 runs + 10 projects including completed-run projects)
4. Browser window sized **1440 × 900** (approx MacBook 14" scaled)
5. Dev tools closed
6. Dark mode (the app defaults to dark; toggle via header if needed)

## The 6 shots

### `ss-01-dashboard.png` — Workspace dashboard

**URL**: `http://localhost:5173/dashboard`

**What to show**: "Ship velocity, at a glance." headline, metric tiles (Total Runs / Active / Total Tokens / Complete), Token Usage Over Time card with Runs-by-Status donut, Model Comparison bar chart, Recent Activity sidebar.

**Capture region**: full right pane starting just below the top breadcrumb, all the way to the bottom of the Model Comparison chart. Exclude the left sidebar for a cleaner shot.

---

### `ss-02-run-detail.png` — Run detail with verified score + pipeline

**URL**: `http://localhost:5173/` → click the first completed run in the list (should be `c5dab59a` "Build Expo Habit Tracker" or similar)

**What to show**: Right pane — Run title, "X/100 verified · Est. Y" chip, pipeline stages row (Planning ✓ → Approval ✓ → Tasks ✓ → Compilation ✓ → Review ✓ → Merge ✓ → Preview), collapsed Validation Report summary row (`Build ✓ · Reviewer … · Verified X · Est. Y`), and the tab strip (OVERVIEW / PLAN / TASKS / TIMELINE / LIVE LOG / CODE / PREVIEW).

**Capture region**: right pane only. Exclude the run-list on the left to keep focus on the detail view.

---

### `ss-03-project-overview.png` — Project overview with generated docs

**URL**: `http://localhost:5173/projects/0151dd82-e09c-43a2-842a-0b72185cd4b3/dashboard` (Hostel Management — 4 completed runs, avg score 68)

**What to show**: Header "Hostel Management · ACTIVE · ANTHROPIC · Launch", metric tiles (Active/Completed/Failed/Avg Score/Tokens/Cost), Overview tab content on the right: Project settings → Project summary paragraph → **Generated Docs** block with Explanation v2 + Setup v1 rows each showing View / .md / Regenerate buttons.

**Capture region**: full page (don't exclude the RUNS left column — it shows per-run PARTIAL/READY status chips + scores, adds depth). Might need **GoFullPage** browser extension to get the entire tall page, or just capture the top 900px which has most of the value.

---

### `ss-04-architecture-viewer.png` — Architecture doc with version selector

**URL**: Same project (`0151dd82...`) → click **Knowledge** tab → then **Architecture** sub-tab

**What to show**: Sub-tabs (Architecture / Docs / Memory), the version selector `v7 · 18/04/2026`, Diff vs v6 button, Regenerate button, then the actual architecture markdown content — Overview section + Repository Layer with HOSTEL-BE-V3 (Backend) and HOSTEL-FE-V3 (Frontend) subsections rendered as readable prose.

**Capture region**: right pane from the sub-tab strip down through ~2-3 subsections of content. Aim for showing enough prose that it's clearly an LLM-generated architecture doc, not a file tree.

---

### `ss-05-validation-report.png` — Verified score + 6 dimensions

**URL**: `http://localhost:5173/` → click the same `c5dab59a` run → in the right pane, click the collapsed **"> Validation Report"** header to expand it

**What to show**: Expanded Validation Report with three metric tiles (LLM ESTIMATE · BUG RISKS · REVIEW), the **Quality Dimensions** panel with horizontal bars for Code quality / Test coverage / Architecture / Security / Performance (+ Design if present), Validation Summary paragraph, and the Issues Detected list with CRITICAL/MINOR severity chips + file names.

**Capture region**: right pane, tightly framed on the expanded Validation Report.

---

### `ss-06-mobile-qr-preview.png` — RN + Expo preview with QR

**URL**: `http://localhost:5173/` → click `c5dab59a` (mobile run) → click **Preview** tab

**Prerequisite**: The preview must be **started** (currently shows "Preview not started · 0 of 5 previews used"). Click the "Start preview" button if present, wait ~30-60s for the Expo bundler + QR to render. Alternatively, use a different RN run that already has an active preview session.

**What to show**: Left side — `React Native · Expo SDK 55` eyebrow, "Scan to preview on your device" heading, the QR code SVG, Android / iOS / Cross-platform chips, and the Expo Go instruction text. Right side — an iPhone-style device frame with the running app (habit cards like "💧 Drink water", "🏃 Morning run").

**Capture region**: full Preview panel content on the right pane.

## Bonus shot (optional for the gallery, useful for the tour)

### `ss-extra-score-trend.png` — 30-day score trend chart

**URL**: Same Hostel Management project dashboard, scroll down past Generated Docs

**What to show**: "Score trend" card with the recharts line chart — 4 lines (overall · quality · security · architecture) plotted over 15/04 → 17/04 with values 0-100.

**Capture region**: just the chart card.

## How the tour-panel swap will work (P0-24)

Current: `website/index.html` lines ~755-820 contain 5 `<div class="tour-mock">` blocks with styled HTML mocks.

After you drop the 6 PNGs into `website/press-kit/`, tell me **"screenshots landed"** and I'll:

1. Copy the 5 relevant ones into `website/images/tour-NN-name.png` (separate location — the tour needs asset paths relative to the deployed site root)
2. Replace each `<div class="tour-mock">...</div>` with `<img src="images/tour-NN-name.png" alt="..." class="tour-mock-img">`
3. Add minimal CSS so the `<img>` fits the browser-chrome frame (preserves the rounded corners + dark window-chrome effect)
4. Redeploy via `cd website && npx wrangler deploy` — one command, ~10 seconds

Mapping tour panel → screenshot:
| Tour panel | Best screenshot |
|---|---|
| 1. Plan Gate | `ss-02-run-detail.png` (or a new plan-approval shot if you have one) |
| 2. Live Execution | `ss-02-run-detail.png` during a WORKING status (alternative shot) |
| 3. Verified Score | `ss-05-validation-report.png` |
| 4. Project Dashboard | `ss-03-project-overview.png` |
| 5. Mobile Preview | `ss-06-mobile-qr-preview.png` |

If the PH gallery + tour want slightly different crops, capture twice at different zoom levels — a `-tight` crop for the tour panels (e.g. `tour-01-plan-gate.png`) and a `-wide` crop for the PH gallery (e.g. `ss-01-dashboard.png`).

## When you're done

Tell me one of:
- **"all 6 captured"** → I'll wire the tour swap + update P0-22/24 in the checklist
- **"captured 4/5 but skipping mobile"** → I'll wire what's there + leave Shot 6 as deferred
- **"need different file locations"** → adjust paths per your preference
