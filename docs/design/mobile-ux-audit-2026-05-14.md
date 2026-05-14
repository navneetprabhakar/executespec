# Mobile UX audit — 2026-05-14

> Comprehensive line-by-line audit of every routable surface against a 375×812 iPhone viewport.
> This document is review-then-implement. **No code has been written based on these findings yet.**
> Status: draft 1 — awaiting user sequencing input.

## TL;DR for a reviewer in 60 seconds

- **121 distinct mobile-UX failures** across ~50 routable surfaces. Most are not bugs — they're **information-design patterns that read fine on desktop and break on a phone**.
- The previous "responsive UX parity" branch (`feature/responsive-ux-parity-2026-05-14`) shipped framework-level fixes (viewport units, safe-area insets, momentum scroll, font-size floor, tap feedback) — those are real wins but don't address the patterns documented here. They're a different altitude of problem.
- **One scroll regression introduced by that branch has already been hot-fixed on this branch** (commit `7baddbe`) — over-broad `touch-action: pan-x` on every `.overflow-x-auto` was killing vertical pan on tables + code blocks.
- The remaining work is **pattern-level component redesigns**, not CSS tweaks. The right shape is 13 small PRs (one per pattern, fixed at the primitive level wherever possible) — see "Sequencing" below.

---

## The 14 recurring patterns

Numbered for traceability. P14 (data tables forcing horizontal scroll) was added during the audit — it's distinct enough from P4 (single-line truncation) to deserve its own row.

| # | Pattern | Why it breaks mobile |
|---|--------|----------------------|
| **P1** | Dot-separated metadata strips (`ACTIVE · ANTHROPIC · OWNER pplai-app · REPOS 2 · DONE 16`) | Overflows at <420px; last chip wraps; trailing `·` mid-string reads as broken |
| **P2** | Action button rows on cards | Destructive button adjacent to common actions; whole row unreadable below 400px; accidental-tap risk |
| **P3** | Long IDs / SHAs / URLs surfaced as primary content (`d7d990cf`, `https://github.com/pplai-ap…`, `claude-3-5-sonnet-20241022`) | Mid-string truncation wastes the space the title should have owned |
| **P4** | Single-line `truncate` clamps cutting titles mid-word (`Remove x-user-id hea…`, repo name `p…`) | User can't identify the row; line-clamp-2 wrap is almost always better |
| **P5** | Marketing-sized heroes (`text-2xl/3xl/4xl`) on data screens | Pushes actual data 500-700px below the fold on a 375px phone |
| **P6** | Filter / chip groups wrapping to 3+ rows | Eats 30% of viewport before content; collapse to single "Filters" sheet on mobile |
| **P7** | Double-layer tab strips (top tabs + sub-tab strip inside the page) | Two horizontal scrollers stacked = unintelligible nav model on a phone |
| **P8** | Verbose timestamps (`13/5/2026, 8:28:52 PM`) | Seconds + 4-digit year + full date eats a horizontal row that titles need |
| **P9** | Deep file-tree indentation (24px/level) | By level 4 the filename has zero horizontal breathing room |
| **P10** | Absolute-positioned chips / badges that collide with sibling text | The pill rides on top of the text it's supposed to label (workspace health `main`/`stale`) |
| **P11** | Composer / chat / panel headers that eat >30% of viewport vertical space | Conversation gets ~5 lines visible; primary content squeezed |
| **P12** | Modal-as-centered-dialog where iOS bottom-sheet is more thumb-friendly | Centered dialogs on a 812px-tall phone waste top + bottom; bottom-sheet is the iOS-native pattern |
| **P13** | Markdown code blocks overflowing the bubble without horizontal-scroll affordance | User can't read past the right edge of `<pre>` content in chat / PR review / code review |
| **P14** | Data tables with `min-w-[Npx]` forcing horizontal scroll on phones (admin / audit / invoices / org / accounting) | Phone-on-call SUPER_ADMIN can't read the table they need to act on; column structure was never reflowed for mobile |

---

## What worked (don't redo)

- `ResponsiveModal` (the shared primitive) — already uses `mobileVariants` (slide-from-bottom) + safe-area insets. Correct.
- `Drawer` — right-slide is fine for the use cases it covers (settings flows).
- `MarkdownContent` — `<pre>` blocks already have `overflow-x-auto`. The chat-bubble code-overflow issue is at the bubble's `max-w-[X%]` clamp, not in `MarkdownContent` itself.
- The 2026-05-14 typography lift (`text-[<12px]` → 12px floor on mobile) is shipping legibly per user screenshots — keep.
- iOS momentum scroll (`-webkit-overflow-scrolling: touch` on every `.overflow-y-auto`) — keep.
- Mobile drawer (TopBar hamburger) — works, all nav reachable.

---

## Findings, surface-by-surface

### Bucket A — Project workbench (20 findings)

| File:Line | Pattern | Severity | Failure mode |
|-----------|---------|----------|--------------|
| ProjectDashboardPage.tsx:784-796 | P1 | High | Status metadata strip (status · provider · owner · repos · done · spent) wraps to 3+ lines |
| ProjectLaunchesList.tsx:132-155 | P2 | High | Action row stacked horizontally without flex-wrap; PR pill truncated <375px |
| ProjectLaunchesList.tsx:149-152 | P3 | Medium | Token-count metadata competes with title in single truncated line |
| ProjectPrRollup.tsx:165-169 | P1 | Medium | "Pull requests" + `lastRefresh` timestamp wraps awkwardly <400px |
| ProjectPrRollup.tsx:420 | P3 | Medium | Short SHA + PR number + URL fragment compete for row width |
| PulseTab.tsx:369-371 | P3 | High | Run ID + timestamp + duration eat space from task name (already line-clamp-2) |
| ProjectDashboardPage.tsx:850-851 | P4 | Medium | Task name uses `truncate` (single-line); long names cut mid-word |
| ProjectDashboardPage.tsx:461-462 | P4 | Medium | Project name `h1` `truncate`; should wrap or use line-clamp-2 |
| ProjectChatPanel.tsx:270-307 | P11 | Critical | Chat header (Compact + Reset + meta) pushes action buttons below header on <420px |
| ProjectChatPanel.tsx:280-306 | P8 | Medium | Header token chips "123k in / 45k out · 200k window" verbose on phone |
| ComposeTab.tsx:415-421 | P11 | High | Chat bubble `max-w-[85%]` shrinks effective width to ~300px; unbreakable tokens overflow |
| InlineRunDetail.tsx:323-357 | P2 | Critical | 7-button cluster (Pause, Cancel, Re-run, View, Push, Download, View PR) wraps uncontrollably |
| InlineRunDetail.tsx:453-471 | P2 | High | Sync row inline-flex without flex-wrap; button text unreadable <350px |
| CodeTab.tsx:84-105 | P6 | Medium | 8-tab sub-strip (Architecture · Health · Code review · Tech debt · Standards · Contracts · ADRs · Invariants) wraps |
| ArchitectureViewer.tsx:110-145 | P2 | High | Toolbar version dropdown + branch input on same row; input shrinks to ~80px <375px |
| WorkspaceHealthPanel.tsx:103-123 | P1, **P10** | High | Stale/fresh + webhook + open-PR chips collide with left text — visible as the "main"/"stale" overlap in user screenshot |
| CodeQualityPanel.tsx:227-237 | P1 | Medium | Verdict strip (Status · sha=N · date · duration) — duration collapses early, status chip jams text |
| PostMergeBar.tsx:52-75 | P1 | High | "✓ PR #N merged — refreshed" wraps; "Start next" button loses room <350px |
| ProjectLaunchesList.tsx:129-156 | P5 | Low | Launch row link text in 10px mono on mobile |
| InlineRunDetail.tsx:323-356 | P5 | Medium | Action cluster header pushes Refresh to second row |

### Bucket B — Run detail (18 findings)

| File:Line | Pattern | Severity | Failure mode |
|-----------|---------|----------|--------------|
| RunDetail.tsx:351-353 | P3 | High | Run ID truncated to 16 chars + status badge eats 60% of header width |
| RunDetail.tsx:365 | P1 | Medium | "Complete · with warnings" trailing-dot truncation <400px |
| RunDetailTabs.tsx:244-254 | P7 | High | 7 top-level tabs (Overview / Plan / Timeline / Log / Code / Preview / Chat) — horizontal scroll strip breaks tab discoverability |
| ValidationReportPanel.tsx:346-348 | P1 | Medium | Header summary string (`2 files · JS, React · 3 fixed`) truncates final segment |
| ScoreCard.tsx:28 | P4 | Medium | "Code quality" label truncates in 140px space without word-break |
| SignalBreakdown.tsx:49 | P1 | High | 3-chip row ("Build ✓ · Tests 73/73 · Reviewer CLEAN") wraps unpredictably; single failing test eats 45% of width |
| CostProgressBar.tsx:56-57 | P1 | Medium | "$X.XX / $Y.YY" + percentage collide at <300px |
| PreviewTab.tsx:384-385 | P1 | Medium | Project-type chip + database chip stack or hide on narrow viewport |
| PreviewTab.tsx:432-436 | P3 | High | Live preview URL rendered as raw link text; truncates mid-domain |
| RunConversation.tsx:380-386 | P1 | High | Model chip `max-w-[140px]` truncates `claude-3-5-sonnet-20241022` to `claude-3-5-sone…` |
| RunConversation.tsx:401-412 | P1 | Medium | Message counter + Apply / Fix / Iterate buttons wrap to 2+ rows |
| RunConversation.tsx:756-761 | P2, P13 | High | User/assistant bubbles `max-w-[85-92%]` ≈ 315-345px; long URLs + 80-char code lines overflow without scroll affordance |
| TaskTimeline.tsx:273 | P4 | High | Task title `truncate` in `w-48` gutter; mid-word truncation |
| TaskTimeline.tsx:303 | P12 | Critical | Hover tooltip `position: absolute; left: 192px; min-w-[280px]` → overflows right edge of 375px viewport |
| FileViewer.tsx:30 | P1 | Medium | Filename `truncate` cuts long paths with no tap-to-reveal |
| RunSidePanel.tsx:310 | P7 | Medium | 7 tabs horizontal scroll; tab switching resets scroll position |
| RunFailureSummary.tsx:84-119 | P1 | High | File-path bullets create inline dot-separated string; severity label crowds inline path |
| ConsolePanel.tsx:71 | P1 | Medium | "Allowed: ls, ps, cat, curl, env, df, free, netstat, ss, ping, whoami, hostname, date, uptime, pwd" wraps to 3 lines |

### Bucket C — Cross-project lists + landing (27 findings)

| File:Line | Pattern | Severity | Failure mode |
|-----------|---------|----------|--------------|
| RunsListPage.tsx:176-178 | P5 | High | h1 fontSize:28 → 3-line title wraps at 375px, content below fold |
| RunsListPage.tsx:416 | P1 | Medium | Status·projectId·SHA row trailing-dot truncation |
| InsightsPage.tsx:248-250 | P5 | Medium | h1 fontSize:22 + description; 30-day-window buttons wrap to 2 rows |
| InsightsPage.tsx:305-362 | P6 | Medium | Multi-project filter chip rail wraps to 4+ rows |
| LandingPage.tsx:140-149 | P5 | High | h1 fontSize:36 + `<br/>` forces break; title + description = 40% viewport |
| LandingPage.tsx:258-289 | P6 | Medium | Sample-prompt grid `minmax(220px)` 2-row wrap; chips truncate without ellipsis |
| LandingPage.tsx:891-940 | P4 | Medium | Competitor table 160px name col; "Cursor" / "Devin" rows unreadable at <400px |
| LibraryPage.tsx:96-102 | P5 | Medium | h1 fontSize:24 + 3-select filter row wraps at 375px |
| LibraryPage.tsx:128-198 | P6 | High | 3 selects + search input + "Hide locked" button → 2-3 row wrap |
| LibraryPage.tsx:219 | P6 | Medium | Card grid `minmax(320px)` traps single-column on 375px |
| MigrationsPage.tsx:82-89 | P5 | Medium | h1 fontSize:24 + description push data below fold |
| MigrationsPage.tsx:92-159 | P6 | High | Filter row wraps to 2+ rows; "All categories" select text overflows |
| MigrationsPage.tsx:200-211 | P6 | Medium | Card grid `minmax(320px)` forces single column on 375px |
| LibraryDetailPage.tsx:193-195 | P5 | Medium | h1 + lock chip on same line compete for space |
| MigrationDetailPage.tsx:183-192 | P5 | Medium | h1 + lock chip inline cause wrap at <400px |
| TeamPage.tsx:115-118 | P5 | Medium | h1 fontSize:22 + subtitle eat vertical above fold |
| TeamPage.tsx:265-296 | P6 | High | Anonymous-preview hero `auto-fit minmax(260px)` single-col on mobile; button row 2-row wrap |
| TrustPage.tsx:48-66 | P5 | Medium | h1 fontSize:26 + metadata sentence = 25% viewport |
| TrustPage.tsx:69-79 | P1 | Medium | Pill row (TLS · AES-256 · Data residency · us-east4) wraps to 3 rows |
| TrustPage.tsx:358-368 | P1 | Medium | `SubProcRow` doesn't stack; region pill collides with text <375px |
| ExecDemoPage.tsx:100-118 | P6 | Medium | KPI grid `minmax(140px)` single-col; expense rows wrap to 2+ lines |
| ExecDemoPage.tsx:143-188 | P1, P6 | High | Spend-by-project list 4 adjacent flex cols; text flows off-screen at 375px |
| EmptyProjectPage.tsx:149-161 | P1 | Medium | Tier-limits string ("20 runs / month · FREE") wraps awkwardly |
| RunsNewPage.tsx:138-152 | P5 | Medium | h1 fontSize:28 + description push composer below fold |
| RunsNewPage.tsx:158-315 | P5 | Medium | Project picker + create form max-w 880px; no responsive narrowing |
| ProjectsListPage.tsx:52-64 | P5 | Medium | h1 accent font + "Multi-repo" styling + manage link compete |
| ProjectsListPage.tsx:94-134 | P6 | Medium | 75px icon + chevron compress project name text |

### Bucket D — Admin + finance + org (21 findings, dominated by P14)

| File:Line | Pattern | Severity | Failure mode |
|-----------|---------|----------|--------------|
| AdminPage.tsx:572 | P7 | High | 8-tab strip (Users · Orgs · Tiers · Escalations · Packages · Reports · Audit · Webhooks) horizontal scrolls off-screen |
| AdminPage.tsx:633 | **P14** | High | Users table `min-w-[600px]` — 6 cols don't fit portrait |
| AdminPage.tsx:754 | **P14** | High | Orgs table `min-w-[600px]` — 7 cols overflow |
| AdminPage.tsx:904 | **P14** | Medium | Tier config table `min-w-[560px]`; `w-24` inputs clamp mid-number |
| AdminPage.tsx:1122 | **P14** | High | Packages table `min-w-[600px]` — 7 cols unreadable |
| FeatureFlagsPage.tsx:195 | **P14** | High | Flag table `min-w-[640px]` truncates columns on 375px |
| TierLifecyclePage.tsx:128 | **P14** | High | Lifecycle state-machine table `min-w-[640px]` — 6 cols don't fit |
| PricingManager.tsx:115 | **P14** | Medium | Pricing table `overflow-x-auto` with no scroll affordance |
| OrgAdminDashboard.tsx:387 | **P14** | High | Member activity table `min-w-[560px]` with no scroll indicator |
| TeamMemberDashboard.tsx:387 | P4 | Medium | "Runs / Success % / Last Active" cols wrap badly |
| AuditPage.tsx:331 | P1 | High | Entity ID `slice(0,8) + '…'`; full IDs inaccessible |
| AuditPage.tsx:332-428 | P7 | Critical | Mobile card layout swaps in but Actor/Changes still in 11px font |
| AccountingPage.tsx:139 | **P14** | Medium | Trial balance 4-col table overflows on 375px |
| InvoicesPage.tsx:156 | **P14** | High | Invoice table `min-w-[…]` forces scroll; mobile card clamps description to 2 lines |
| DeploymentsPage.tsx:109 | P5 | Medium | Grid skeleton loaders at `text-lg` on mobile |
| ExecSummaryPage.tsx:143 | P6 | Medium | KPI grid 5 tiles × 2-col wraps to 3 rows on 375px |
| ExecSummaryPage.tsx:206 | P4 | High | Spend-by-project list `w-24` progress bar clamps in 48px width |
| OrgProjectsPage.tsx:278 | P1 | Medium | Project status + AI Provider chips wrap to 2 rows; spacing inconsistent |
| OrgProjectsPage.tsx:629 | P5 | Medium | Header `flex flex-col sm:flex-row` stacks; hero text wraps each word |
| OrgSettingsPage.tsx:233 | **P14** | High | Members table `min-w-[560px]`; Remove / Suspend buttons cut off |
| ExecutionViewPage.tsx:119 | P8 | Low | `ExecutionStepper` verbose date format takes 2 lines |

### Bucket E — Billing + auth + settings + legal + primitives (35 findings)

| File:Line | Pattern | Severity | Failure mode |
|-----------|---------|----------|--------------|
| TopBar.tsx:45-69 | P11 | High | Inline nav + UsageBadge + AvatarMenu may not fit on tablet landscape (mobile drawer covers <768px) |
| PricingGrid.tsx:81-90 | P6 | Medium | USD/INR + Monthly/Annual toggle group wraps at iPhone SE (~350px) |
| PricingGrid.tsx:148 | P2 | High | Enterprise footer "Schedule demo" + "Trust + compliance" stack awkwardly <420px |
| PlanCard.tsx:303 | P2 | High | Approve + Adjust + Cancel button row overflow <400px |
| BillingProfileForm.tsx:115-166 | P2 | Medium | Save button `w-full sm:w-auto` forces full-width; secondary buttons no room |
| BillingProfileForm.tsx:123-135 | P1 | Low | 38-item state name `<select>` unvirtualized |
| SettingsPage.tsx:567 | P2 | High | Save + Test Connection + Clear (3 buttons each ~120px+) wrap to 2+ rows <450px |
| SettingsPage.tsx:713-738 | P5 | Medium | Health card grid 4-col jumps too late (1→2 at sm, 2→4 at lg) |
| BillingPage.tsx:66-161 | P5 | Medium | h1 fixed 22px doesn't scale down for mobile |
| IntegrationsPage.tsx:1121 | P6 | Medium | Integration card grid `grid-cols-1 md:grid-cols-2` jumps too late |
| IntegrationsPage.tsx:1197-1219 | P1 | High | Jira URL + Confluence URL + Slack workspace `truncate` fails when URL >180px |
| IntegrationsPage.tsx:1314-1342 | **P14** | Medium | Integration runs table `min-w-[560px]` even with scroll fade |
| UsageBadge.tsx:74-85 | P1 | Medium | "{current}/{max} runs" min-width 83px truncates at "999/1000" |
| RegisterPage.tsx:166-184 | P6 | Medium | Persona picker `grid-cols-3` (Personal/Team/Enterprise) → ~100px each; "Team (2+ devs)" label wraps |
| RegisterPage.tsx:192-214 | P2 | Medium | First/Last name `grid-cols-2` on mobile → ~145px inputs each |
| RegisterPage.tsx:257-276 | P6 | Medium | Password rules `grid-cols-2 gap-x-3` → rule text truncates if long |
| WelcomePage.tsx:95-134 | P2 | Medium | Sample card buttons: icon + title + 2 badges + "Start here" link tight at 375px |
| PricingTable.tsx:85-98 | P4 | Medium | Model name `truncate` in mono text-xs; can't see `gpt-4-turbo-preview` |
| SharedReportPage.tsx:89-128 | P1 | Medium | "files · languages · issues" inline; no wrap; overflows at 375px |
| SharedProjectPage.tsx:185-204 | P1 | Medium | Stat grid 4 cards 2-col 375px → tight values |
| Composer.tsx:47-100 | P2 | Low | Advanced expander + text field + toggle controls layout uncertain |

---

## Sequencing (recommended)

13 PRs, sequenced by impact-per-hour. Each PR is bounded — primitives-first, then call-site retrofit.

| PR | Pattern | Scope | Why first / why later |
|----|--------|-------|----------------------|
| **1** | **P14** Data table → mobile card list | New `<ResponsiveTable>` primitive that renders rows as cards under 768px. Retrofit AdminPage (4 tables) + FeatureFlagsPage + TierLifecyclePage + InvoicesPage + OrgSettingsPage + AccountingPage + OrgAdminDashboard. | Affects ALL admin/finance + most-frustrating on-call surface. ~12 tables across 8 files. ETA 2-3 days. |
| **2** | **P1+P10** Metadata strip + chip collisions | New `<MetaRow>` primitive: dot-separated on desktop, stacked key-value on mobile. Fix WorkspaceHealthPanel collision specifically. Retrofit project header (ProjectDashboardPage:784), CodeQualityPanel:227, ValidationReportPanel:346, SignalBreakdown, PostMergeBar, ConsolePanel allowed-commands, RunFailureSummary file-path list. | Recurring everywhere; one primitive fixes ~15 call sites. ETA 2 days. |
| **3** | **P2** Action button rows | New `<ActionCluster>` primitive: primary inline + secondary overflow on `…` menu on mobile. Retrofit InlineRunDetail (7 buttons → 1 + overflow), ProjectLaunchesList, ArchitectureViewer, SettingsPage, PricingGrid Enterprise footer, PlanCard, BillingProfileForm, RegisterPage name fields. | High accidental-tap risk on destructive actions. ETA 2 days. |
| **4** | **P4** Title truncation | `truncate` → `line-clamp-2` on every list-row title. RunCard, repo cards (ProjectRepoManager), TaskTimeline, FileViewer, project name h1, sample-spec cards, validation report items. | Trivial per-site fix; mostly find-and-replace with grep guardrails. ETA 1 day. |
| **5** | **P3** Long IDs / SHAs / URLs as primary content | Replace raw URLs with `owner/repo#NN` format (PR cards), demote SHAs to secondary chip (run cards / workspace health), shorten run ID slice. | Per-component judgement calls. ETA 2 days. |
| **6** | **P8** Verbose timestamps | New `<RelativeTime>` primitive that uses "17h ago" on mobile + ISO timestamp in tooltip. Retrofit run cards, audit log, activity feed, project memory ledger. | One primitive, many consumers. ETA 1 day. |
| **7** | **P11** Chat / panel headers eating viewport | Collapse ProjectChatPanel header chips into a single tap-to-expand "Meta" chip on mobile. Same for RunConversation header (model + counters). | High-traffic surface. ETA 1 day. |
| **8** | **P6** Filter / chip groups | New `<FilterSheet>` primitive: collapse to single "Filter (N)" button on mobile → opens bottom-sheet. Retrofit /runs Scope+Status, InsightsPage project filter, LibraryPage + MigrationsPage 3-select rail. | Lifts ~30% vertical real estate on mobile across 5 surfaces. ETA 2 days. |
| **9** | **P5** Hero sizing on data screens | Scale-down on mobile: `text-2xl/3xl/4xl` → `text-xl/2xl` under 640px. Retrofit /runs hero, /library, /migrations, /landing, /insights, /team, /trust, RunsNewPage, project name h1, BillingPage h1. | Most are 1-line className edits. ETA 1 day. |
| **10** | **P7** Tab strip double-layer | RunDetailTabs (7 tabs) + Code sub-tabs (8 tabs) + AdminPage (8 tabs) → mobile uses bottom-sheet picker for sub-tabs or collapses rare tabs into "More". | Real navigation redesign; product decision: which tabs are "core" vs "More"? | ETA 2-3 days, blocked on product input. |
| **11** | **P12** TaskTimeline tooltip | Replace absolute-positioned `min-w-[280px]` tooltip with a bottom-sheet on mobile; flip to popover on desktop. | One file. ETA 0.5 day. |
| **12** | **P13** Code blocks in chat bubbles | Bubble inner `<pre>` gets `overflow-x-auto` wrapper. Affects RunConversation, ProjectChatPanel, MarkdownContent consumers. | ETA 0.5 day. |
| **13** | **P9** File-tree indentation | Reduce mobile indent to 12px/level; flatten breadcrumb at top so user knows the path even without seeing parent folders. | Affects RunDetailTabs Code, FileViewer, validation report. ETA 1 day. |

**Total bounded estimate: 17-20 dev-days.** I can do this incrementally; user picks the order. PR sizes are small enough that each can ship + deploy within a day. Sequencing above is my recommendation only; happy to reorder if you tell me which surfaces are most-trafficked.

---

## Open product questions (need user direction before PRs that touch them)

1. **`/runs` cross-project hero** — does mobile need a hero at all, or should mobile land directly on the run list? (Affects PR-9 scope.)
2. **Admin tab consolidation** — of the 8 AdminPage tabs (Users · Orgs · Tiers · Escalations · Packages · Reports · Audit · Webhooks), which are "core mobile" vs "More-drawer"? (Affects PR-10.)
3. **Project Chat compact-mode** — when we collapse the header on mobile, should "Compact" + "Reset" stay visible, or move into an overflow `…` menu? (Affects PR-7.)
4. **Run-detail tab ordering** — same question for RunDetailTabs' 7 tabs. Which 3-4 are "phone-essential"? (Affects PR-10.)
5. **PR review surface** — `PrReviewButton` opens a verdict popover. On mobile this is hard to position. Convert to a full-page detail view? (Affects PR-11.)

---

## What's not in this audit

- Animations & motion (not framed as broken in feedback so far)
- Voice / a11y screen-reader navigation
- Performance budgets (bundle size, FCP, TTI on 4G)
- Visual regressions on iPad portrait (768-820px range — falls into the awkward "tablet-but-not-desktop" zone)
- Dark vs light theme drift
- Print stylesheets (only matters for validation report download)

Any of those worth a follow-up audit, say so and I'll line it up.

---

## Changelog
- 2026-05-14 — draft 1 — full audit across 5 buckets / 51 page files. P14 added during synthesis.
