# fe-ide — raw findings

Scope: `frontend/src/ide/` (20 files, 17,380 LOC) + `App.tsx` (241) + `main.tsx` (55), branch `benchmark`, working tree as-is. Every file read in full; dead-code claims grep-verified over `frontend/src`. Conclusions from code only (no docs consulted).

---

## 1. Inventory — per file

| File | LOC | Components (exported / total incl. helpers) | What it renders |
|---|---|---|---|
| `IdeWorkspace.tsx` | 452 | 1 / 1 | The chrome: mobile top bar, activity bar, Explorer sidebar (recent runs + projects + admin/benchmark links), `<Outlet/>` editor area, status bar (tier badge, plan-gate chip, theme, "Try Console"), mobile bottom tabs, ⌘K palette wiring. Polls runs+unread every 12s + on nav. |
| `IdeSurfaces.tsx` | 972 | 3 / 3 | `IdeWelcome` (home: live-run hero + recent runs + projects), `IdeCompose` (spec composer: project/repo scoping, provider+model pin, mode, cost cap, TDD, Figma, estimate), `IdeLive` (SSE console + 8s status/cost poll + tasks + cancel). |
| `IdeRunResult.tsx` | 1,752 | 1 / 2 (`Kv` + default) | Flagship run detail: 10 tabs (Overview / Code / Tasks / Timeline / Logs / Validation / Cost / PR / Tests / Chat), file tree + LCS diff viewer, preview lifecycle, run management (fork/archive/delete/pause/resume/push/deploy/share), run chat + iterate. **One ~1,600-line component.** |
| `IdeProjectMore.tsx` | 2,454 | 6 / ~12 | `IdeProjectChat`, `IdeProjectCode` (6 sub-tabs incl. split-pane browse), `IdeProjectArchitecture` (5 sub-tabs), `IdeProjectMembers`, `IdeProjectActivity`, `IdeProjectSettings` (3 sub-tabs) + `RTabs`/`Banner`/`MetaLabel`/`ArchDocView`/`Field` helpers. |
| `IdeProjectTabs.tsx` | 1,264 | 4 / 7 | `ProjectTabs` strip (fetches 4 endpoints for badges), `IdeProjectPulse`, `IdeProjectRuns` (runs/PRs split-pane + `RunDetailPanel`), `IdeProjectRepos` (link/sync/regen/webhook/unlink). |
| `IdeDiscovery.tsx` | 1,745 | 6 / ~17 | `IdeInsights`, `IdeLibrary`(+`IdeLibraryDetail`+`LibraryInstantiateModal`), `IdeMigrations`(+detail+dry-run/apply modals), `IdeSpecTemplates`, shared `useProjectRepoPicker` hook + `Pill`/`Tag`/`NoteCard`/`KvRows` helpers. |
| `IdeSettings.tsx` | 1,385 | 2 / 13 | `IdeIntegrations` (GitHub/Slack/Jira/Confluence/Bitbucket token modals), `IdeAdmin` (6 sections: overview funnel, users, orgs, analytics, ops + feature flags, top-ups). |
| `IdeOrg.tsx` | 1,342 | 3 / 6 | `IdeApiKeys` (/api-keys BYOK), `IdeOrgMembers` (invites/roles/remove), `IdeOrgSettings` (profile, seats, SSO, org BYOK keys, F3 model matrix, external sharing, suspend, audit, compliance). |
| `IdeMore.tsx` | 967 | 3 / 4 | `IdeRuns` (paginated list + 5 filter buckets), `IdeAccount` (theme, contact form, GDPR export/DSAR/erasure, notif prefs, plan limits), `IdeDeployments`. |
| `IdeSystem.tsx` | 886 | 5 / 7 | `IdeNotifications`, `IdeSearch` (client-side over 50 runs + projects), `IdeAudit` (+CSV export), `IdeEmptyProject`, `IdeMoreMenu`. |
| `IdeBilling.tsx` | 742 | 2 / 5 | `IdeBilling` (subscription summary, runs-used, invoices), `IdePlans` (FREE/TEAM/ENTERPRISE cards + gateway checkout). |
| `IdeIcons.tsx` | 738 | 67 exports / ~21 distinct | Monochrome UI icons + inlined Material Icon Theme file/folder glyphs (`dangerouslySetInnerHTML` of a vendored literal), `fileIconFor` ext/name maps, `StatusDot`. |
| `IdeBenchmark.tsx` | 455 | 1 / 11 | Operator-only `/benchmark` tracking dashboard (SUPER_ADMIN): run rows, detail KV grid, task table, event log. Polls list 5s / detail 3s. |
| `IdeKit.tsx` | 389 | 10 / 9 | `statusKind`, `IdeBadge`, regex tokenizer + `IdeCodeViewer`, `IdeCommandPalette` + `usePaletteHotkey`, `FileIcon`/`FolderIcon`, `IdeFileTree`, `ResizeHandle`, `IdeLoader`, a `useNavigate` re-export. |
| `IdePlan.tsx` | 207 | 1 / 1 | Plan gate: 2s poll, auto-route to live/result, credits/cap card, `RunTimeline`, revise + approve/cancel. |
| `IdeProject.tsx` | 191 | 1 / 1 | Project home: composer + repo scoping + recent runs. |
| `IdeProjects.tsx` | 189 | 1 / 1 | Project health cards (N+1 dashboard + tech-debt fetch per project). |
| `PlanGateIndicator.tsx` | 92 | 1 / 1 | "N awaiting approval" chip + popover; 15s poll of `getRuns({status:'AWAITING_APPROVAL'})`. |
| `RunTimeline.tsx` | 44 | 1 / 1 | Status-driven vertical step timeline. |
| `ide.css` | 1,114 | — | Dark+/Light+ token set, `--signal-*` bridge, chrome layout, code viewer, palette, mobile breakpoint, timeline, gate chip. |
| `App.tsx` | 241 | — | Route table: public, protected (IDE or opt-in Console shell), legacy redirects, catch-all `* → /home`. |
| `main.tsx` | 55 | — | Bootstrap: OTel init, prod SW registration, pre-paint theme, `highlight.js` CSS import (dead — see §3). |

Inline `style={{…}}` objects across ide/*.tsx: **959** (IdeProjectMore 175, IdeRunResult 117, IdeDiscovery 94, IdeOrg 89, IdeProjectTabs 82, IdeSettings 73, …) — the `.ide-*` class system covers chrome only; nearly all page layout is untyped inline CSS.

---

## 2. Findings

### Real bugs first

- **[BUG] `/api-keys` deep-load blank screen — root cause is the Vite dev proxy, not the route table.**
  The route exists (`App.tsx:147` → `IdeApiKeys`) and a catch-all exists (`App.tsx:238`). `IdeApiKeys` (IdeOrg.tsx:80-204) has no unconditional crash, and auth bootstraps fine (`useAuthStore.ts:31` sets `isAuthenticated` synchronously from localStorage). The break is `vite.config.ts:21`: the proxy context `'/api'` is matched by **prefix** (`url.startsWith(context)` in Vite's `doesProxyContextMatchUrl`), so a dev-server deep-load of `/api-keys` is proxied to the Spring backend instead of being served Vite's SPA index. The backend then serves the **stale built** `static/index.html` (SpaFallbackController treats first segment `api-keys` as SPA-eligible), whose hashed `/assets/index-*.js` URLs don't exist on the Vite dev server → scripts 404 → permanently blank page. Client-side navigation to /api-keys works; only deep-load/refresh breaks, exactly matching the live repro. Same exposure for any future route starting with `/api…`.
  Fix: change the proxy key to `'^/api/'` (regex) or `'/api/'`.
  Prod path verified safe at both layers: `SpaFallbackController.java:39` excludes only exact first-segments `{api, actuator, webhooks}`, and `public/sw.js:58` checks `startsWith('/api/')` (with slash). Residual unverified: any CDN/Cloudflare rule on `/api*`.

- **[BUG] Mobile Code tab has NO file selector (live-confirmed, now root-caused).**
  `IdeRunResult.tsx:913` renders the file tree inside `.ide-split-files` and `IdeRunResult.tsx:930` renders open-file tabs as `.ide-tabs`. `ide.css:846-848` hides `.ide-tabs` and `ide.css:849-852` hides `.ide-split-files`/`.ide-split-info` at ≤860px, and **no alternative picker is rendered** — on mobile the user is stuck on whichever file `openFile(f[0])` auto-opened (IdeRunResult.tsx:213). The run-info panel (`.ide-split-info`) is likewise unreachable on mobile wherever used. **Second instance:** project Code → Browse uses the same `.ide-split-files` (IdeProjectMore.tsx:616) → no file picker on mobile there either. Fix needs a <860px fallback (e.g. a `<select>` of files or a collapsible drawer).

- **[RISK] No ErrorBoundary anywhere in the app** (grep: only a comment in `lib/monacoSetup.ts:36`). Any render-time throw in any IDE surface = permanent white screen with no recovery UI. Given the amount of `as unknown as` casting against loosely-typed payloads (e.g. IdeProjects.tsx:64-78, IdeProjectTabs.tsx:9-45), this is the standing amplifier for every "blank screen" class of bug.

- **[BUG] "Autofix review" button doesn't dispatch an autofix.** `IdeProjectTabs.tsx:620-640` — `autofix()` only calls `api.fetchPrReviewComments(...)` and then prints *"PR review comments found — autofix will be dispatched."* No dispatch call follows. Customer-facing dead-end that claims success.

- **[BUG] Project default provider never applied.** `IdeProject.tsx:47` — `if (project?.aiProvider) setProvider(...)` runs inside the `[projectId]` effect *before* `getProject` resolves (reads the stale `project` from the previous render, `null` on mount). The composer always defaults to ANTHROPIC regardless of the project's configured provider. (`IdeProjectSettings` does it correctly in the `.then` — IdeProjectMore.tsx:2042.)

- **[BUG] Terminal run can show "In progress".** `IdeRunResult.tsx:22` — `verdictFor` only special-cases `s === 'COMPLETE'` when score is null; backend uses `COMPLETED` (cf. terminal list at IdeRunResult.tsx:448), so a COMPLETED run with no validation report yet renders an "In progress" badge on the terminal result page.

- **[BUG] Undefined CSS token.** `IdeRunResult.tsx:1187` uses `background: 'var(--ide-bg)'` — `--ide-bg` is defined nowhere (grep over src: 0 definitions). The Timeline Gantt track is invisible; bars float without a rail.

- **[BUG] `REVIEWING` runs fall through the redirect net.** `IdeRunResult.tsx:233-241` redirects active runs to `/live` but the list omits `REVIEWING` (and `PAUSED` is intentional). `IdePlan.tsx:8` and `IdeSurfaces.tsx:43-52` both treat REVIEWING as live. A REVIEWING run opened at `/result/:id` renders the empty terminal chrome — the exact founder-bug #3 class this redirect was added to fix, resurfacing for one status.

- **[BUG] Status-bucket sets disagree across four files.** `IdeMore.tsx:18-26` (`LIVE_STATUSES` incl. PROVIDER_SWITCHING + PAUSED), `IdeSurfaces.tsx:43-52` (`NON_TERMINAL` incl. REVIEWING), `IdeProjectTabs.tsx:39` (`LIVE` without REVIEWING/PAUSED/PROVIDER_SWITCHING), `IdeSystem.tsx:264-270` (without REVIEWING/PAUSED). Consequences: a REVIEWING run is "live" on /runs but not in project tabs or search (routes to /result, which doesn't redirect it — see above); a PAUSED run routes to /live from /runs but to /result from project runs. Also `IdeMore.tsx:101` counts shipped only for `COMPLETED` while `statusKind` (IdeKit.tsx:7) and `IdePlan.tsx:9` also accept `COMPLETE` — a `COMPLETE` run lands in **no** filter bucket on /runs.

- **[BUG] Seats display reads a non-existent field.** `IdeOrg.tsx:806` derives `seatCount` from `(org as {seats?: number}).seats ?? 1` while the rest of the file uses `org.seatCount` (IdeOrg.tsx:625, 869) — subtitle likely always "1 seats". Same phantom-field read in `IdeOrgMembers` (`IdeOrg.tsx:241`, falls back to `members.length` so it accidentally works). Copy bug alongside: "TEAM-tier orgs share a seat pool (min 2)" (IdeOrg.tsx:850) contradicts the input's `min={1}` (IdeOrg.tsx:859-861).

- **[BUG] Audit CSV export has no escaping.** `IdeSystem.tsx:564-586` — values containing `,`/`"`/newlines corrupt rows; values starting `=`/`+`/`@` are an Excel formula-injection vector (actor emails/targets are user-influenced).

- **[BUG-RISK] SPA navigation via synthetic popstate.** `IdeSystem.tsx:195-200` — notification click does `history.pushState` + `dispatchEvent(new PopStateEvent('popstate'))` instead of `useNavigate()`. Fragile against router internals (works today, undefined contract), and the row is a `div onClick` (not keyboard reachable, see A11Y). Backend-provided absolute `link` goes to `window.location.href` unvalidated (see §7).

- **[BUG-RISK] Legacy redirect loop for unmatched `/org/*` and bare `/p`.** `App.tsx:230` routes `/org/*` to `LegacyRedirect`, but `LegacyRedirect` (App.tsx:65-97) only rewrites `/org/projects`; any other path (`/org`, `/org/billing`) falls through unchanged → `<Navigate>` to itself → render/navigate loop (each replaceState re-fires). `/org/settings` itself is safe (static route outranks the splat). Same for a bare `/p` (the `/p/` regexes need a trailing segment).

- **[BUG, cosmetic] `#` treated as a comment in all languages.** Tokenizer `IdeKit.tsx:54` colors everything after `#` as a comment unless a `"` appeared earlier in the line — CSS hex colors, Java/TS strings with `#`, URLs with fragments render half-comment-green in the code viewer.

- **[BUG, conditional] "NaN tok" possible in Tasks tab.** `IdeRunResult.tsx:1021` — `num(t.inputTokens + t.outputTokens)`; `TaskSummary` types them non-optional (core.ts:225-226) but pending tasks can serialize null → `NaN.toLocaleString()` = "NaN".

### Polling / state patterns

Inventory (grep-verified): 6 `setInterval` loops + 3 chained-`setTimeout` loops + 1 debounce. **All have cleanup.** Hidden-tab handling is inconsistent:

| Poller | Interval | `document.hidden` skip |
|---|---|---|
| IdeWorkspace runs+unread (IdeWorkspace.tsx:100-107) | 12s | ✅ |
| PlanGateIndicator (PlanGateIndicator.tsx:32) | 15s | ✅ |
| IdeBenchmark list / detail (IdeBenchmark.tsx:386/399) | 5s / 3s | ✅ |
| IdeProjectPulse runs (IdeProjectTabs.tsx:190) | 12s while live | ✅ |
| **IdePlan run poll (IdePlan.tsx:37)** | **2s** | ❌ |
| **IdeLive status+cost (IdeSurfaces.tsx:712)** | 8s (12s on error) | ❌ |
| **IdeProjectChat memory (IdeProjectMore.tsx:140)** | 30s forever while mounted | ❌ |
| **IdeRunResult preview poll (IdeRunResult.tsx:301-303)** | 3s/5s until terminal | ❌ |

- **[PERF] PlanGateIndicator is mounted twice and double-polls.** `IdeWorkspace.tsx:143` (mobile bar) + `IdeWorkspace.tsx:392` (status bar) — CSS hides one per breakpoint (`ide.css:797-801`) but both instances run their own 15s `getRuns({status:'AWAITING_APPROVAL', size:50})`. 2× identical requests forever, for every user.
- **[PERF] ProjectTabs costs 4 API calls per project-tab navigation** (`IdeProjectTabs.tsx:74-79`: dashboard + repos + members + tech-debt just for badge counts), re-fetched on every sub-route mount (the strip is remounted per page — IdeProjectPulse:223, IdeProjectRuns:684, IdeProjectRepos:1029, every IdeProjectMore surface). Crossing the 10 tabs = ~40 calls + each page's own fetches.
- **[PERF] IdeProjects N+1**: dashboard + tech-debt fetch per project card (IdeProjects.tsx:32-41), no cancellation guard (others use `cancelled` flags).
- **[PERF] IdeProjectArchitecture `refresh()` fires 7 endpoints + 1 contract-coverage call per repo** on mount (IdeProjectMore.tsx:1031-1061).
- **[INCONSISTENT] IdeWelcome's live-run hero never refreshes** (one-shot fetch, IdeSurfaces.tsx:89-118) while the sidebar 1 px away updates every 12s.
- **[SMELL] IdeLive's poll effect depends on `fileTick`** (IdeSurfaces.tsx:725) — every SSE file-write event tears down and restarts the poll loop (an immediate extra `getRun`+`getLiveCost` per write). Works, but it's a hidden fan-out under heavy write activity.

### Routing / shell

- **[SMELL] `localStorage.getItem('es:console')` read inline in route elements** (App.tsx:112, 211) — evaluated on every render of `<App/>`; switching requires a hard reload (the "Try Console" button does reload — IdeWorkspace.tsx:415). Acceptable, but worth knowing it's not reactive.
- **[INCONSISTENT] Composer handoff uses three different navigation mechanisms** for the same `signal:pending-spec` pattern: `navigate('/compose')` (IdeDiscovery.tsx:745), `window.location.href = '/compose'` (IdeDiscovery.tsx:1587 — full reload), `window.location.href = '/projects/{id}'` (IdeProjectMore.tsx:192 — full reload). The reloads throw away the SPA session for no reason.
- **[INCONSISTENT] `/topup` link on the at-cap card** (IdeSurfaces.tsx:420) — `App.tsx:145` redirects `/topup → /billing`, so "Top up credits →" lands on the billing dashboard, not a top-up flow.

### Component size / duplication

- **[SMELL] God component: `IdeRunResult` default export ≈1,600 lines** (IdeRunResult.tsx:156-1752) holding 10 tabs, the diff engine, preview lifecycle, chat, and run management in one closure with ~20 `useState`. `IdeOrgSettings` ≈775 lines (IdeOrg.tsx:567-1342), `IdeProjectArchitecture` ≈612, `IdeProjectCode` ≈474, `IdeCompose` ≈411 are the next worst.
- **[DUPLICATION] `relTime` ×3**: `signal/lib/runUtils.ts:76` (canonical), `IdeBenchmark.tsx:35-46`, `IdeProjectTabs.tsx:25-37` (different rounding: `Math.round` vs floor).
- **[DUPLICATION + INCONSISTENT] `fmtWhen` ×3 with different output**: `IdeProject.tsx:10-14` → `toLocaleDateString()` (date only) vs `IdeSurfaces.tsx:13-17` and `IdeMore.tsx:11-15` → `toLocaleString()` (date+time). Same helper name, different formats on adjacent surfaces.
- **[DUPLICATION] `usd()` ×2** (IdePlan.tsx:7, IdeRunResult.tsx:33); `scoreOf`/`costOf` ×2 (IdeMore.tsx:30-34, IdeProjectTabs.tsx:41-45); run-filter bucket logic ×2 (IdeMore.tsx:91-121 vs IdeProjectTabs.tsx:642-672); PR card markup ×2 (IdeProjectTabs.tsx:268-324 vs 806-936).
- **[DUPLICATION] Identical helper components re-declared per file**: `Banner` (IdeOrg.tsx:21-36 ≡ IdeProjectMore.tsx:47-62), `InfoCard` (IdeOrg.tsx:40-56 ≡ IdeBilling.tsx:25-41), `Field` (IdeOrg.tsx:59-77 ≈ IdeProjectMore.tsx:2436-2454), `ErrCard` (IdeSystem.tsx:30-36 ≡ IdeBenchmark.tsx:60-66), `rowStyle` (IdeSystem.tsx:17-27 ≈ IdeSettings.tsx:358-366), and the hand-rolled toggle-switch JSX ×3 (IdeMore.tsx:668-698, IdeSettings.tsx:929-971 `IdeSwitch`, IdeOrg.tsx:1132-1179).

### Date / number formatting consistency

- Relative (`relTime`): sidebar gate popover, /runs rows, project runs, benchmark. Absolute date+time (`toLocaleString`): IdeWelcome run cards (IdeSurfaces.tsx:201), notifications (IdeSystem.tsx:187), audit (IdeSystem.tsx:671), org audit (IdeOrg.tsx:1292), arch versions (IdeProjectMore.tsx:1374), deployments (IdeMore.tsx:909). Date-only: project recent runs (IdeProject.tsx:13), invoices (IdeBilling.tsx:365), member last-seen (IdeOrg.tsx:441), ADRs (IdeProjectMore.tsx:1444). The same run rendered as a card on /home shows `6/10/2026, 2:31:08 PM` while the sidebar shows `2h ago`. No shared formatter, no consistent locale strategy.
- Cost: `$x.toFixed(2)` everywhere except IdeBenchmark's 4-decimal `fmtCost` (IdeBenchmark.tsx:26-29, operator-appropriate) and IdeBilling's `fmtMoney` that only knows USD/INR symbols (IdeBilling.tsx:440-445).

### Raw backend enums shown unmapped

- `statusReason` raw: `IdeSurfaces.tsx:822` and `IdeRunResult.tsx:516` render `CLEAN_WITH_INTERMEDIATE_WARNINGS` / `TESTS_FAILED:2_OF_5` verbatim as muted text (no mapping layer exists in ide/).
- Run `status` raw in badges: IdeProject.tsx:182, IdeSurfaces.tsx:199, IdeMore.tsx:194, IdeSystem.tsx:450, IdePlan.tsx:96, IdeProjectTabs.tsx:364/758, IdeRunResult.tsx:560 (`AWAITING_APPROVAL`, `PROVIDER_SWITCHING` shown as-is). Only `IdeLive`/hero map to human labels via `phaseLabel` (IdeSurfaces.tsx:57-70) — one surface humanizes, eight don't.
- Task status raw (`WORKING`, `LOCKED`, `BLOCKED`): IdeSurfaces.tsx:895, IdeRunResult.tsx:989, IdeProjectTabs.tsx:483.
- Others raw: deployment `strategy` (IdeMore.tsx:883), preview status (IdeRunResult.tsx:762), org roles `BILLING_ADMIN`/`ORG_ADMIN` as option labels (IdeOrg.tsx:354-356, 456-458) and role badge (IdeOrg.tsx:475-477), audit `eventType` (IdeOrg.tsx:1289, IdeSystem.tsx:659), notification pref `eventType` is at least `_`-stripped+lowercased (IdeMore.tsx:662).

### Mobile

- Code-tab file-tree bug — see top.
- **[INCONSISTENT] IdeProjectRuns split-pane has no mobile handling**: inline `gridTemplateColumns: 'minmax(280px, 1fr) minmax(0, 1.2fr)'` (IdeProjectTabs.tsx:724-731) — at 375px this forces 280px+ for the list and squeezes/overflows the detail pane. No media query (inline styles can't have one).
- Two different mobile breakpoints: 860px for the chrome (ide.css:802) vs 720px for the run-action footer (ide.css:608) — minor, but means a 780px viewport gets mobile CTA treatment in a desktop chrome.

### Accessibility

- **[A11Y] Modal is documented "focus-trapped" but isn't** (signal/components/Modal.tsx:4-8): focus moves into the dialog on open (Modal.tsx:34) but Tab freely escapes to the background, and focus is not restored to the trigger on close. ESC + backdrop work.
- **[A11Y] Command palette** (IdeKit.tsx:177-244): no `role='dialog'`/`aria-modal`, results are `div`s (not options/buttons), no `role='listbox'`/`aria-activedescendant`; Escape only handled while the input has focus; backdrop is a plain div.
- **[A11Y] Notification rows are `div onClick`** (IdeSystem.tsx:189-202) — no `role='button'`, no `tabIndex`, unreachable by keyboard.
- **[A11Y] ResizeHandle** (IdeKit.tsx:353-376): `role='separator'` but mouse-only (no keyboard resize, no `aria-valuenow`); also no touch events → sidebar/file-tree resize impossible on touch devices.
- **[A11Y] Tab strips** (`.ide-rtabs` buttons everywhere, e.g. IdeRunResult.tsx:487-495): no `role='tablist'/'tab'`, no `aria-selected`; state is color-only.
- Positives: icon-only controls consistently carry `aria-label`/`title` (IdeWorkspace.tsx:138-246), SVGs are `aria-hidden` by default (IdeIcons.tsx:62-65), log emoji are `aria-hidden` (IdeRunResult.tsx:1088), toggles use `aria-pressed`/`role='switch'` (IdeMore.tsx:670, IdeOrg.tsx:1134), gate popover uses `role='menu'` (PlanGateIndicator.tsx:69) though without arrow-key support.

### Misc

- **[SMELL] 8 raw `fetch()` calls bypass the shared API client** inside ide/: IdeBilling.tsx:85, 545; IdeMore.tsx:334, 361, 388; IdeProjectMore.tsx:160, 2067; IdeSettings.tsx:1131. They hand-build `Authorization` from the same `access_token` key core.ts uses (verified core.ts:18) so they work, but they skip `ApiError`/status-message parsing and produce raw "(403)" strings inconsistent with everything else.
- **[SMELL] Version string hardcoded** "ExecuteSpec · v1.0.0-M1" (IdeSystem.tsx:882).
- **[SMELL] Pricing/plan copy hardcoded in the frontend** (`FEATURES` map, "$3 hard cost cap", "15 runs / month" — IdeBilling.tsx:397-429; tier cap map IdeSurfaces.tsx:248) — will silently drift from the backend YAML matrix.
- **[SMELL] Hardcoded compliance claims** "SOC 2 · Type II ready", "GDPR · Compliant" (IdeOrg.tsx:1308-1315) — legal assertions baked into UI.
- **[SMELL] Suspend/Reinstate buttons both always render** with no indication of the member's current suspended state (IdeOrg.tsx:1225-1243).
- **[SMELL] Chat "Compact" clears the visible thread entirely** (IdeProjectMore.tsx:198-206 `setMsgs([])`) although compaction server-side retains a summary + recent messages — UI misrepresents the operation.
- **[INCONSISTENT] Project chat + run chat textareas use `className='ide-input'`** (IdeProjectMore.tsx:372, IdeRunResult.tsx:1704) instead of `ide-textarea`.

---

## 3. Dead code & junk — grep-verified

- **`highlight.js`**: CSS theme imported in `main.tsx:10` ("Syntax highlighting theme for the IDE file viewer") but `hljs` is used **nowhere** (grep: only that import) — the viewer uses IdeKit's regex tokenizer with `.tok-*` classes. Dead CSS import + dead dependency (`package.json` "highlight.js": ^11.11.1).
- **Monaco remnants**: `lib/monacoSetup.ts`, `lib/monacoGeistTheme.ts`, `lib/monacoErrorPatterns.ts` have **zero importers** (grep) → 3 dead files + dead dep `@monaco-editor/react`.
- **Dead dependencies (no import anywhere in src/)**: `d3` (+`@types/d3`), `recharts` (only a CSS *comment* mentions it — index.css:147), `framer-motion`, `cmdk`, `lucide-react`, `@radix-ui/react-tabs`, `qrcode.react`. With highlight.js + monaco that's ~9 removable deps.
- **Dead exports in `IdeIcons.tsx`**: `SwapIcon` (:232), `SettingsIcon` (:149), `SearchIcon` (:159), `fileIcon()` (:702) — no usage outside the defining file.
- **Dead re-export**: `export {useNavigate}` (IdeKit.tsx:389) — never imported from IdeKit.
- **Dead CSS classes in `ide.css`**: `.ide-fileicon` (:709-718), `.ide-breadcrumb` (:297-307), `.ide-tree-sub` (:244-249) — no TSX references.
- **Dead/overridden declaration**: `.ide-palette input` declares `border: none` then `border: 1px solid …` four lines later (ide.css:366 + 374).
- **Undefined token used once**: `var(--ide-bg)` (IdeRunResult.tsx:1187) — see §2.
- Route param naming junk: `/runs/:runShort` (App.tsx:122) — the only link to it passes a full run id (IdeBenchmark.tsx:243); "short" resolution doesn't exist client-side.

---

## 4. Provider / model / tier leakage into UI — exhaustive (ide/ + routing scope)

**Raw model IDs shown to customers:**
- `IdeSurfaces.tsx:845-848` — IdeLive KV row "Worker model" renders `run.workerModel` verbatim (e.g. `claude-sonnet-4-6`). Every user, every live run.
- `IdeRunResult.tsx:563-568` — Overview KV "Worker" renders `run.workerModel` verbatim. Every user, every terminal run. (Note: per backend behavior this field is additionally misleading — worker model is complexity-routed per task.)
- `IdeRunResult.tsx:1020` — Tasks tab per-task `{t.model || t.assignedModel}` raw model id + `(t.provider).toLowerCase()`.
- `IdeCompose` model pin (ENTERPRISE): raw model ids as option labels — `IdeSurfaces.tsx:536-541`.
- F3 model-matrix grid (org settings, visible read-only to all org admins): `Default · {def}` + option list of raw model ids — `IdeOrg.tsx:1081-1087`.
- Admin-only: cost-by-model raw ids (`IdeSettings.tsx:868-874`); operator-only: benchmark `r.model` (`IdeBenchmark.tsx:175, 291`).

**Provider IDs shown to customers:**
- `IdeMore.tsx:213-217` — /runs rows: `r.aiProvider.toLowerCase()` ("anthropic"/"openai"/"gemini"/"openrouter").
- `IdeSurfaces.tsx:200` — IdeWelcome run cards: same.
- `IdeSurfaces.tsx:842` — IdeLive KV "Provider": same.
- `IdeRunResult.tsx:561` — Overview KV "Provider": same.
- `IdeRunResult.tsx:702-712` — "↻ Try anthropic / Try openai / Try gemini" buttons (lowercase provider ids as button copy; hardcoded 3-provider list ignores openrouter/deepseek runs).
- `IdeDiscovery.tsx:445-458` — Insights "Cost by provider" rows: raw `p.provider` keys.
- Provider **selectors** (deliberate but raw vendor names as labels): `IdeSurfaces.tsx:518-521` (Compose, incl. `OPENROUTER` for ENTERPRISE), `IdeProject.tsx:147-149`, `IdeProjectMore.tsx:2177-2179` (project settings).
- BYOK key rows naming vendors (expected for BYOK, listed for completeness): `IdeOrg.tsx:198-200` (personal), `IdeOrg.tsx:798-803` (org incl. OpenRouter for ENTERPRISE), `IdeBilling.tsx:239` upsell copy "your Anthropic / OpenAI / Gemini keys".
- Operator/benchmark: `IdeBenchmark.tsx:176, 319` raw `r.provider`.

**Internal tier names (Planning/Workhorse/Lightweight) in customer-facing copy:**
- `IdeOrg.tsx:1026-1038` — the F3 matrix renders `matrix.tiers` keys (planning / workhorse / lightweight, capitalized only by CSS `textTransform`) as section headings, with provider column labels `{p}` (IdeOrg.tsx:1060) — visible to every org admin (read-only below ENTERPRISE).
- `IdeSurfaces.tsx:536` — "Default (org / system matrix)" references the matrix concept in compose.
- (Run pipeline tiers don't otherwise leak in ide/; classifier strategy `planStrategy` leaks raw at `IdePlan.tsx:111` — `SINGLE_SHOT`/`LEGACY` shown verbatim in the plan-gate "Strategy" row.)

**Aggregator-invisibility note:** the only place "OpenRouter" is shown to a non-admin is the ENTERPRISE compose dropdown (IdeSurfaces.tsx:521); org BYOK (IdeOrg.tsx:802) is admin-only. Everything else that needs a provider/model display funnels through the raw strings above — there is **no display-name mapping layer in the frontend at all** (no use of the YAML `display-label` on these surfaces), so an aggregator-invisible redesign needs one chokepoint helper plus the ~16 call sites listed here.

---

## 5. Styling / theme

- **Token system**: `.ide-root` defines a coherent VS Code Dark+ set with a `[data-theme='light']` override (ide.css:7-74) and bridges `--signal-*` onto `--ide-*` (ide.css:86-120) so reused Signal components theme correctly — solid design. But **959 inline style objects** in ide/*.tsx do layout/typography ad hoc; the class system covers maybe 20% of actual styling. Recurrent hand-typed literals (`fontSize: 11, fontWeight: 700, letterSpacing: '0.14em', textTransform: 'uppercase'`) appear 30+ times where one `.ide-label` class should exist.
- **[BUG] Status-bar badge contrast (the live-reported ENTERPRISE-badge issue), root cause:** `.ide-statusbar` background is hardcoded `#007acc` in **both** themes (ide.css:16 and :57) while `IdeBadge` paints transparent-background colored text (`.ide-badge.ok` → `--ide-green`, ide.css:547-549). Computed contrast of the tier badge (IdeWorkspace.tsx:390): dark theme `#4ec9b0` on `#007acc` ≈ **2.2:1**; light theme `#098658` on `#007acc` ≈ **1.02:1 — virtually invisible**. The PlanGateIndicator chip on the same bar: `--ide-yellow` dark `#d7ba7d` ≈ 2.4:1, light `#b58900` ≈ **1.4:1** (its translucent tint background barely helps). Anything `IdeBadge`-based placed on the status bar fails WCAG; badges were designed for `--ide-bg-sidebar` surfaces. Fix: statusbar-scoped badge variant (solid chip background or `--ide-fg-statusbar` text), or theme `--ide-bg-statusbar`.
- **Hardcoded colors bypassing tokens**: `.tok-num: #b5cea8` (ide.css:469) — pale green untthemed → near-invisible numbers in the **light** code viewer; `#fff` inline for unread badges/toggle knobs (IdeWorkspace.tsx:163/219, IdeMore.tsx:694, IdeOrg.tsx:1174, IdeSettings.tsx:965); chat user-bubble `color: '#fff'` on `--ide-blue` (IdeRunResult.tsx:1680 — light theme blue `#0451a5`, OK); ProtectedRoute spinner `bg-[#0f1117]` + `border-purple-500` (ProtectedRoute.tsx:41-42) — a legacy palette flash before the IDE chrome loads; preview iframe `background: '#fff'` (IdeRunResult.tsx:868, defensible).
- **Specificity hack (documented, working):** `.ide-root .ide-btn` (ide.css:559-577) deliberately out-specifies the `.signal-app button` global reset (signal.css:330). It works, but every new filled control inside the IDE must remember the same trick — the underlying `.signal-app button { background: transparent }` reset is still live underneath the whole IDE.
- `--ide-bg-statusbar` identical in light/dark (intentional VS Code mimicry) is the root of the contrast bug above; `--ide-yellow` light `#b58900` on white cards ≈ 3.0:1 — marginal for the many 11px badge texts.
- Two mobile breakpoints (860 vs 720) noted in §2.

---

## 6. Test coverage

- `package.json` wires vitest (`"test": "vitest run"`, vitest + jsdom in devDeps).
- Test files in the whole frontend: **1** — `src/design-system/contrast.test.ts` (token contrast pins; ironically the status-bar-badge combination it should catch isn't covered since the statusbar/badge pair isn't in its matrix).
- **Zero tests for anything in `frontend/src/ide/`, `App.tsx`, or `main.tsx`.** No render tests, no route-table test (a one-liner route test would have caught the /api-keys class only partially — the bug is in vite config — but would pin `/api-keys` existing), no tokenizer/diff (`lineDiff` IdeRunResult.tsx:48-79 is a pure function begging for a unit test), no `statusKind`/bucket-logic tests (which would have surfaced the COMPLETE/COMPLETED divergence).
- CI (per package.json scripts) runs prettier + warn-only eslint (`--max-warnings 9999`); `npm test` is not part of any gate visible here.

## 7. Security notes (parked)

- 8 raw `fetch()` calls with bearer token from localStorage (§2 misc) — consistent with the app's existing token storage model (XSS ⇒ token theft is a pre-existing platform property, not new here).
- Preview iframe `sandbox='allow-scripts allow-same-origin allow-forms allow-popups'` (IdeRunResult.tsx:863) — `allow-scripts`+`allow-same-origin` together neutralize most of the sandbox; safe only while preview URLs are guaranteed cross-origin (Cloud Run). A local/docker preview served same-origin would have full DOM access to the app.
- Notification deep-link: backend-provided `link` starting with `http` is followed via `window.location.href` with no allowlist (IdeSystem.tsx:195-197) — open-redirect / phishing vector if notification links are ever attacker-influenced (e.g. via webhook-derived notifications).
- Audit CSV export: no quoting/escaping → CSV/Excel formula injection (`=HYPERLINK(...)` in an actor email) (IdeSystem.tsx:564-586).
- Hardcoded compliance claims (SOC 2 / GDPR) in `IdeOrg.tsx:1308-1315` — legal exposure if untrue.
- `IdeIcons` uses `dangerouslySetInnerHTML` for the Material glyphs (IdeIcons.tsx:516) — input is a build-time constant literal, not user data; fine, but the pattern invites copy-paste misuse.
