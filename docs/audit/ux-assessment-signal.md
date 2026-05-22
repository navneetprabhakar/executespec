# Signal chrome — critical UX assessment

> Production chrome (`uiMode === 'signal'`). Source: `frontend/src/signal/` (pages/** + components/**), mounted via `signal/components/Shell.tsx`.
> Method: every surface read end-to-end. "Works / partial / broken" is judged from the code (data fetch, empty/loading/error states, dead-ends, gating, a11y, mobile). No runtime/browser pass — behavioural calls are inferred from the code paths.
> Date: 2026-05-22. Companion to `ux-assessment-ide.md`.

Overall: Signal is **functionally broad and mostly honest** — almost every backend capability is wired, loading/empty/error states are the norm not the exception, destructive actions use `ConfirmModal`, and the 2026-05-19 "honesty pass" (plain-English verdict, "not measured" instead of fake zeros, real run-cap surfacing) genuinely improved trust. The problems are **consistency and last-mile polish**, not missing features: a stale scoring definition that contradicts the product's own honesty work, three different "Shippable" score thresholds, a cluster of `showChev`/icon rows that look clickable but dead-end, several `window.confirm`/`window.alert`/`fetch()` escapes from the design system, and a per-project composer that silently drops the cost-cap control the global composer has.

---

## The 10 biggest UX problems in Signal (prioritized)

1. **Stale "verified score" glossary definition directly contradicts the product.** `Glossary.tsx` still says the score is combined **"multiplicatively, so any single failure caps the score."** The scorer has been **additive-weighted** since 2026-05-19 (`scoring.additive-v2`, default ON), and the entire ResultPage "honesty pass" exists precisely to stop the old multiplicative score from lying. The single most-surfaced jargon tooltip teaches users the opposite of how scoring now works.

2. **Three inconsistent "Shippable" thresholds across surfaces.** The canonical band is **≥85 = Shippable** (HomePage, AllRunsPage, ResultPage, ProjectRunsPage list, ProjectComposePage). But `ProjectRunsPage.InlineRunDetailPane` (the split-pane detail) uses **≥90**, and `MiscPages.SharedPage` (the public report customers send to stakeholders) uses **≥90**. The same 87-score run reads "Shippable" in the list and "Needs review" in the detail pane beside it — and "Needs review" on the public link.

3. **Dead-end rows that look interactive.** Many `Row`s render a chevron (`showChev`) or sit in a clickable-looking card but have no `to`/`onClick`: AccountPage "Active sessions"; BillingPage invoice rows + "Update" payment-method button; ProjectArchitecturePage version rows + ADR/invariant rows (no detail view); TrustPage's 5 "Documents" rows (SOC2/DPA/sub-processors/pen-test/privacy). Users click and nothing happens.

4. **Per-project composer silently drops the cost-cap control.** The global `/compose` page exposes a "Spending limit" input and a FREE-tier "you've used all N runs" warning card. `ProjectComposePage` (the default landing for every project, where most real runs start) has **neither** — no cap input, no at-cap warning. The most-used submission path is the one missing the spend guard.

5. **`window.confirm` / `window.alert` escapes break the modal contract.** CLAUDE.md claims every `window.confirm`/`prompt` was replaced by `ConfirmModal`/`PromptModal`. Three live exceptions remain: ResultPage "Stop preview" and "Deploy to Cloud Run" use `window.confirm`; ResultPage "Mark test invalid" uses `window.confirm`; AccountPage GDPR export error uses `window.alert`. These are unstyled, not focus-trapped, and inconsistent with the rest of the app.

6. **Direct `fetch()` bypasses the single API client on real customer paths.** ProjectChatPage (send message), ProjectMembersPage (invite + share-links), ProjectSettingsPage (save settings), AccountPage (GDPR/DSAR/erasure), BillingPage (payment-history), PlansPage (subscribe), TopupPage (purchase) all hand-roll `fetch` + bearer instead of `api.ts`. This violates the documented "single API client" rule, dodges `ApiContractTest`, and re-implements auth-header + error handling inconsistently (some surface `(${status})`, some don't).

7. **Non-functional header icon buttons.** ProjectPulsePage Share + More IconButtons have no `onClick`. ProjectMembersPage "+" IconButton has no `onClick`. These are top-bar affordances that do nothing — a classic "looks done, isn't wired" tell.

8. **Notification bell shows a permanent unread dot.** HomePage's bell `IconButton` is hardcoded with the `badge` prop, so it always shows the red unread indicator regardless of actual unread count (`getUnreadCount` is fetched on `/more` but never feeds the bell). The one persistent attention signal on the home screen is meaningless.

9. **"Try another provider" / provider chips offered to FREE without gating context.** ComposePage + ProjectComposePage show Anthropic/OpenAI/Gemini chips to everyone, and ResultPage's "Try another provider" fires a full re-run on the chosen provider. FREE is "1-credit specs only" with a $3 cap and a 15-run/month limit — re-running on three providers to A/B them silently burns three of those runs with no "this costs a run" warning at the point of click.

10. **Doc-indexing and a few async statuses never converge without a manual refresh.** ProjectSettingsPage Documents tab shows UPLOADED/EXTRACTING/EMBEDDING/READY status but never polls — a doc sits on "EMBEDDING" until you reload. (Activity, project memory, and project chat memory *do* poll every 30s, so the gap is jarring by contrast.) Minor but it makes RAG indexing feel stuck.

---

## Cross-cutting observations

- **4-band score palette / VerdictPill consistency:** the helper functions (`scoreKind`, dot mapping) are copy-pasted into ~8 files and *mostly* agree on ≥85/≥70 bands and mint/amber/coral dots — but the two ≥90 stragglers (#2) and the stale glossary (#1) break the story. There is no shared `VerdictPill` component in `signal/` (the one referenced in CLAUDE.md lives in the deleted V2 `components/runs/`); each page re-derives the verdict string inline, which is why they drift.
- **Mobile-first:** the responsive scaffolding is real — `Shell` ships a desktop sidebar + a separate mobile drawer + bottom tab bar, `signal.css` has `@media` breakpoints at 600/768/919/1024/1280, split-panes collapse to stacked on narrow, modals slide up on mobile. The claim holds at the structural level. Caveat: pages lean heavily on inline `style={{}}` with fixed pixel paddings rather than tokens/utility classes, so fine-grained mobile tuning is page-by-page and hard to audit without a device pass.
- **Legacy `/signal/*` links:** `Chrome.tsx` `TabBar` (broken `/signal/home` etc. + a fictional `/signal/projects/checkout-api/chat`) is still rendered by HomePage/AllRunsPage/ProjectsPage/MorePage but **hidden by CSS** (`.signal-shell .signal-tabbar:not(.mobile-tabs){display:none}`), so it's dead code, not a visible break. The TopBar **avatar** does link to `/signal/account` (visible + clickable) — it works only because `LegacyRedirect` rewrites `/signal/*`, costing an extra redirect hop.
- **Loading/empty/error discipline is strong.** Nearly every fetch distinguishes `null` (loading → Skeleton/LoadingState) from `[]` (empty → friendly card with a next-action link), and errors render a coral banner. This is the best part of the chrome.
- **Accessibility:** `Modal` has focus-trap + ESC + `role=dialog` + `aria-modal`. `CostProgressBar` has `role=progressbar`. Toggles use `aria-pressed`/`role=switch`. Icon buttons carry `aria-label`. Gaps: many decorative SVGs lack `aria-hidden`; some clickable `<div>`s (run rows in ProjectRunsPage, top-up pack cards) are not keyboard-focusable/operable; `Chip` is a `<button>` used for non-button toggles which is fine but role-noisy.

---

## Surface-by-surface

### Auth & onboarding

| Feature | Status | Notes |
| --- | --- | --- |
| Login (email + OAuth) | ✅ works | Google/GitHub buttons hit `/api/auth/oauth2/authorize/{provider}` directly; OAuth-error bounce decoded into friendly copy (`oauth_no_email`, `oauth_email_conflict`); busy state; `autoComplete` set. |
| Register | ✅ works | Derives first/last name from email local-part (clever — no extra fields); 12-char min enforced; Terms/Privacy links. |
| Verify email | ✅ works | Handles both signup-splash and magic-link token; success/error/idle states distinct; redirects to login on verify. |
| Forgot password | ✅ works | Sends reset link, success + error banners, 1h-validity copy. |
| Reset password | ✅ works | Reads `?token`; explicit "no token in URL" error; password-match + length validation client-side. |
| OAuth callback | ✅ works | Strips refresh_token from localStorage (security note in code); preserves `?spec=` stash across the bounce; spinner + error fallback → /login. |
| Welcome / sandbox picker | ⚠️ partial | 2-step preset picker + GitHub-app linking is solid. "Use sandbox for now" / "Skip" both go to `/compose`; the picked preset is stashed in sessionStorage and consumed by the composer — works, but if `getGitHubAppInstallUrl` fails it silently navigates to `/compose` (the picked spec survives, but the user gets no "install failed" message). |

### Run loop

| Feature | Status | Notes |
| --- | --- | --- |
| Home / Today | ⚠️ partial | Live-run hero, quick-grid (with monthly run-cap surfaced — good honesty fix), recent runs, projects. Distinguishes loading (`null`) from empty. **Bell badge always lit** (#8). Quick-card `<a href>` hard-navigates (full reload) instead of SPA `<Link>`. |
| Compose (global) | ✅ works | Provider chips, project picker, repo multi-select, cost-cap input, debounced p25/p75 estimate (project-only), at-cap warning, sandbox-handoff prefill, SpecChat. The most complete composition surface. OpenRouter chip gated to ENTERPRISE. |
| SpecChat ("Build my spec") | ✅ works | Multi-turn (sends full history), "Use as spec" finalizes + extracts `REQUIREMENT SUMMARY`, Markdown rendering both sides, graceful fallback if finalize call fails. |
| Plan gate (approve/revise/cancel) | ✅ works | Polls until AWAITING_APPROVAL, auto-redirects on EXECUTING/COMPLETED, no-runId fallback, credits + cap shown, **plan revision** textarea (`editRunPlan`) re-plans in place, classifier glossary. Solid. |
| Live (SSE + cost + cancel) | ✅ works | `useSse` with connecting/reconnecting/polling status chip, auto-scroll log, CostProgressBar vs hard cap, per-task lines, phase-label honesty ("Reviewing changes" not "Running"), `ConfirmModal` cancel, 8s poll safety net. Strong. "Raise cap" hero action just links to `/billing` (no inline cap raise). |
| Result — Overview | ✅ works | Plain-English verdict sentence from measured signals; "not measured" collapsed into one "Skipped" row (honesty pass); copyable prompt; preview/deploy/PDF/share/download rows. |
| Result — Tasks | ✅ works | Per-task retry on FAILED/PARTIAL with failure-reason tail. |
| Result — Timeline | ✅ works | Gantt-style bars normalized to run span; loading + empty states. |
| Result — Code | ✅ works | FileTree + CodeView with Content/Diff toggle, lazy file + diff fetch, download workspace. |
| Result — Cost | ✅ works | Total/cap/credits/tokens + per-phase (classifier/outline/expansion/coherence) breakdown. |
| Result — PR + PrCommentInbox | ✅ works | PR card → GitHub; `PrCommentInbox` lists PENDING comments with Fix/Dismiss/View (uses inbox row id correctly). "Push to GitHub" when no PR. |
| Result — Validation | ✅ works | Build/tests/coverage/reviewer/security/arch KvList + issues list + PDF/retry. |
| Result — Tests | ✅ works | Pass-rate, test plan, root causes (with "Apply fix as new run"), suggestions, all-tests list, "Mark invalid" — but mark-invalid uses **`window.confirm`** (#5). |
| Result — Chat / iterate | ✅ works | Run-scoped chat, optimistic insert, "Iterate as new run" client-composes a fresh spec (the `/iterate` endpoint is 410, handled). |
| Result — run management | ⚠️ partial | Fork/archive/unarchive/soft-delete in header (delete via ConfirmModal — good). But **Stop preview + Deploy permanent use `window.confirm`** (#5); "Try another provider" burns a run with no cost warning (#9). |
| Result — preview lifecycle | ✅ works | Start/rebuild/stop, polled status + logs + sandboxed iframe + TTL countdown + failure message. Genuinely complete. |
| All Runs | ✅ works | Filter tabs (all/live/shipped/needs/failed/archived) with counts, score band coloring, load-more pagination, error banner. Counts computed only over loaded page (filter badge can undercount before "load more"). |

### Projects

| Feature | Status | Notes |
| --- | --- | --- |
| Projects list | ✅ works | Mine/Shared/Archived tabs, per-project dashboard stats fetched in a second pass, recency-weighted avg score (nice), gradient avatars, create via `PromptModal`. Repo count is **approximated from distinct PR repos** (no repo-count field), so projects with 0 PRs show "0 repos" even when repos are linked. Tech-debt stat hardcoded to `—` (debtCount=0). |
| Project workbench tabs | ✅ works | `ProjectTabs` strip with clamped counts (the `Members · -1` regression is fixed); 10 tabs incl. Architecture promoted to top-level. |
| Compose (project, default tab) | ⚠️ partial | Composer + repo scoping + estimate + SpecChat + recent runs. **No cost-cap input, no at-cap warning** (#4). Share/More IconButtons *do* link here (to members/settings) unlike Pulse's. |
| Pulse | ⚠️ partial | Live hero, metric quick-cards, workspace-health rollup, open PRs, recent runs, good loading skeletons. **Share + More IconButtons have no onClick** (#7). |
| Repos | ✅ works | Link/unlink (ConfirmModal), install webhook, sync drift, per-repo health KvList, available-repos panel with "connect GitHub" empty state. Thorough. |
| Runs — Runs split-pane | ⚠️ partial | List + inline detail pane (v3 parity). **InlineRunDetailPane uses ≥90 Shippable threshold** (#2). Run rows are clickable `<div>`s (not keyboard-operable). |
| Runs — PRs + PrReviewPanel | ⚠️ partial | PR cards with CI/verdict/comment chips, refresh snapshot, "Review with AI" (full `PrReviewPanel` with poll + autofix). But the separate **"Autofix review" button only fetches comments and shows a count** — it does not dispatch the autofix (misleading label vs the real autofix inside PrReviewPanel). |
| Code — Browse | ✅ works | Live GitHub-proxied file tree + content, per-repo + per-file cache, repo picker, excellent error copy ("PAT missing/expired"), empty-repo handling, "Open in GitHub". Best sub-surface in the workbench. |
| Code — Tech debt + autofix | ✅ works | Category cards with severity pills + per-category "Autofix N" dispatch. |
| Code — Code review (CodeReviewPanel) | ✅ works | Per-repo picker → shared `CodeReviewPanel`. |
| Code — Health | ✅ works | Per-repo coverage/build/lint/webhook grid. |
| Code — Standards | ⚠️ partial | Violations list + "PROJECT_ADMIN can request overrides per finding" copy — but **no override button is rendered** on a violation (the backend override/exception endpoints exist; the UI promises an action it doesn't surface). |
| Code — Docs | ✅ works | Generate/Regen/Download EXPLANATION + SETUP. |
| Chat | ✅ works | Project-scoped chat (memory digest banner, compile-to-spec, compact, clear via ConfirmModal), TEAM+ gating with a clear upgrade card, memory poll every 30s, bottom-pinned composer. Uses direct `fetch` (#6). |
| Architecture — Current | ✅ works | Markdown doc (8k truncation) + staleness pill + regenerate. |
| Architecture — Versions + diff | ⚠️ partial | Version→version compare with changed-file LCS list (good). But the **version Rows have `showChev` and no detail target** (#3) — clicking a version does nothing. |
| Architecture — Invariants | ⚠️ partial | Pass/fail per invariant with violation counts. No detail/drill-in. |
| Architecture — ADRs | ⚠️ partial | Status-coloured ADR rows. No detail view — can't read an ADR body. |
| Architecture — Contracts | ✅ works | Per-repo detector coverage, rescan, per-repo + project-wide drift check, known-gaps. |
| Members — team | ⚠️ partial | Member list with EXT badge, remove (ConfirmModal). **No per-member project-role change** (org members page has it; this one only removes). TopBar "+" IconButton dead (#7). |
| Members — invite | ✅ works | V198-gated (external_sharing flag + org-admin) with a clear "external sharing is off → Org settings" card when blocked. Uses direct `fetch` (#6). |
| Members — share links | ✅ works | Create/copy/revoke public snapshot links. Direct `fetch` (#6). |
| Activity | ✅ works | Filter tabs, 30s poll (so a just-finished run appears), CSV export, type-coloured dots. |
| Settings — settings | ⚠️ partial | Name/desc/provider/branch/owner with RBAC disable, danger zone (archive/delete via ConfirmModal). Saves via direct `fetch` (#6). |
| Settings — documents (RAG) | ⚠️ partial | Upload + status pills + delete (ConfirmModal). **No indexing poll** — status sits stale until reload (#10). |
| Settings — memory | ✅ works | Snapshot KvList + compact + planner-digest text. |

### Account, billing, org

| Feature | Status | Notes |
| --- | --- | --- |
| Account profile | ✅ works | Avatar/tier/usage chips, quick links, plan-limits KvList, theme + IDE-interface toggle. |
| Account — notifications prefs | ✅ works | Per-event/channel toggle switches with `aria-pressed`, optimistic refetch. |
| Account — GDPR export | ⚠️ partial | Works, but error path uses **`window.alert`** (#5) and direct `fetch` (#6). |
| Account — DSAR + right-to-erasure | ✅ works | DSAR JSON download; erasure behind a typed-`DELETE` `PromptModal`. Both direct `fetch` (#6). |
| Account — "Active sessions" | ❌ broken/missing | Row with chevron, **no action** — dead-end (#3). No session-management surface exists. |
| Account — "Change password" | ⚠️ partial | Links to `/reset-password` with no token → that page correctly shows "no reset token" error. Should instead trigger `forgot-password` / send-link flow; landing on an error screen is a poor affordance. |
| Account — "View mode" | ⚠️ partial | Displays project/pro but is **read-only** (no toggle control). |
| Billing | ⚠️ partial | Plan summary, cycle usage bar, invoices, payment method. **"Update" payment-method button + invoice rows are dead** (#3). Payment-history via direct `fetch` (#6). |
| Plans | ⚠️ partial | 3-tier cards, monthly/yearly toggle, RECOMMENDED/CURRENT badges, PRO grandfather note. FREE card shows "Start free" enabled when you're on a paid tier; `subscribe('FREE')` is a no-op (dead button — should be "Downgrade" or disabled). Subscribe via direct `fetch` (#6). |
| Top-up credits | ✅ works | Pack picker (admin endpoint with static fallback for non-admins), buy → checkout. Direct `fetch` (#6). Reachable via `/topup` + Account, **not in sidebar**. |
| Integrations | ✅ works | GitHub/Slack/Jira/Confluence/Bitbucket with real brand logos, connect/disconnect (ConfirmModal), per-provider credential `PromptModal`s, connected-count. Clean. |
| API keys (BYOK) | ✅ works | Per-provider validate+save, tier-gated (tier-based not permission-based, with a code note explaining the RJ-01 quirk), encrypted-at-rest copy. |
| Org members | ✅ works | Seats card + invite (role select), per-member inline role change + remove (ConfirmModal), pending invites with resend. The strongest management page. |
| Org settings | ⚠️ partial | Profile, seats, SSO (provider/metadata/domain/enforce + clear), AI-keys (write-only, blank=keep), **F3 model-matrix** (per-tier × provider override grid, ENTERPRISE-editable), external-sharing toggle, member suspend/reinstate, lazy audit log, compliance. Very complete. Bug: TopBar subtitle reads `org.seats` (doesn't exist; it's `seatCount`) → always renders "1 seats". |

### Cross-cutting & discovery

| Feature | Status | Notes |
| --- | --- | --- |
| Notifications | ✅ works | Filter tabs, unread count, mark-one/all-read, deep-link routing by type (falls through to /home so clicks never dead-end). |
| Search | ✅ works | Client-side filter over loaded runs + projects, ⌘K-styled, quick-actions + recent when empty, Enter → first hit. (Client-only — caps at 50 runs / all projects in memory; no server search.) |
| More | ⚠️ partial | Full link directory. Renders the hidden legacy `<TabBar>` (dead). Not in sidebar; reachable via `/more` and the mobile "More" tab actually points to `/account`, so `/more` is semi-orphaned. |
| Insights | ✅ works | Metric quick-cards + provider comparison + top-user, 30/90/YTD tabs, loaded-vs-zero distinction (BUG-011 fix). |
| Library (list + detail) | ✅ works | Facet category tabs, locked-tier pills, detail with variant picker + repo-picker instantiate + favourite. Live API. |
| Migrations (list + detail) | ✅ works | Dry-run + apply modals, live API. |
| Templates (spec) | ⚠️ partial | Project-scoped CRUD, Use/Delete. But Compose links here as "Saved templates"; a no-project user lands on "Templates are project-scoped" — the entry point oversells. |
| Audit log | ✅ works | Tier+role-aware 403 messaging (FREE→upsell, non-admin→ask admin, admin→enable in Org settings), filter tabs, CSV export. Exemplary error handling. |
| Deployments + GCP | ✅ works | Deployment list with rollback/destroy (ConfirmModal), `GcpCredentialPanel`. |
| Admin (6 tabs) | ✅ works | Overview (funnel/tiers/flags), Users (inline tier/role/status + grant runs), Orgs (tier/status/limit/grant), Analytics, Ops (halt-all/reconcile/verify-chain/usage-report with confirms), Top-ups CRUD. Comprehensive; reconcile uses direct `fetch` (#6). SUPER_ADMIN-gated + redirect. |

### Public / marketing / shared

| Feature | Status | Notes |
| --- | --- | --- |
| Welcome (marketing) | ✅ works | (= onboarding sandbox picker; reachable unauthenticated.) |
| Public pricing | ⚠️ partial | Tier cards. **Monthly/Yearly TabsStrip has no onClick** — the toggle is decorative; the page can't actually switch cycle. |
| Trust | ⚠️ partial | Compliance badges + encryption KvList + "what we never do". **All 5 "Documents" rows are dead** (showChev, no action) (#3) — a credibility page where every download CTA is inert. |
| Terms / Privacy / DPA | ✅ works | Copy-only legal shells, clean, public, back-to-app link. |
| Shared report (public) | ⚠️ partial | Score card + spec + PR for a share token; expired/revoked + loading states. **Uses ≥90 Shippable threshold** (#2) — the public-facing artefact disagrees with the owner's own dashboard. |
| Empty project | ✅ works | 3-path getting-started (link repo / sandbox / library). |
| Accept invite | ⚠️ partial | Token-gated accept with missing-token guard. **"Decline invite" just navigates home** — no server-side decline, so the invite stays pending. |

---

## What's genuinely good (so it isn't lost in a fix pass)

- The **honesty pass** (ResultPage plain-English verdict, "Skipped/not measured" rows, real monthly-run-cap on Home/Compose, loaded-vs-zero distinction on Insights, tier-aware audit 403s) is high-quality product judgement.
- **Loading/empty/error tri-state** is applied consistently and is the chrome's biggest strength.
- **Org members, Org settings, Admin, Code→Browse, Repos, Integrations, preview lifecycle** are all complete, well-gated, and use the design system correctly.
- Destructive actions are `ConfirmModal`-gated almost everywhere (the 3 `window.confirm` holdouts are the exceptions, not the rule).

## Suggested fix order

1. One-line content fix + the three threshold constants (#1, #2) — highest trust-impact, lowest effort. Extract a shared `verdictFor(score)` + `SCORE_BANDS` so they can't drift again.
2. Wire or remove the dead rows/buttons (#3, #7) — they read as "unfinished."
3. Add cost-cap + at-cap warning to `ProjectComposePage` (#4).
4. Replace the 3 `window.confirm` + 1 `window.alert` with the existing modals (#5).
5. Fix the bell badge to read `getUnreadCount` (#8) and add a "this uses a run" confirm on FREE provider re-runs (#9).
6. Migrate the direct-`fetch` callers into `api.ts` (#6) — larger, lower user-visible impact, but it's the root cause of the inconsistent error handling.
