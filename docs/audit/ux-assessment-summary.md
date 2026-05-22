# UX Assessment + Missing Features + Conversion — Executive Summary (2026-05-22)

> Consolidates three companion docs (read them for the full feature-by-feature tables):
> - [`ux-assessment-ide.md`](ux-assessment-ide.md) — IDE chrome, 139 feature rows + 10 top problems.
> - [`ux-assessment-signal.md`](ux-assessment-signal.md) — Signal chrome, full surface coverage + 10 top problems.
> - [`conversion-analysis.md`](conversion-analysis.md) — per-persona funnel, friction, levers.
>
> Verified against code (file:line in the detail docs) + browser sweeps as admin + persona creds.

## Verdict

Both chromes are **functionally broad and unusually honest** — after the 2026-05-22 rebuild, nearly every backend capability is wired in both, loaders/empty/error tri-states are the norm, and the run-loop + project-workbench depth is real. **The weaknesses are consistency, last-mile polish, and conversion plumbing — not missing features.** The IDE specifically has a "reskinned dashboard, not an editor" feel driven mostly by **placeholder iconography**.

## The three founder-flagged issues — confirmed

| # | Issue | Truth (verified) | Fix |
| --- | --- | --- | --- |
| 1 | **IDE logos wrong** | Activity bar (`⊞ ＋ ◎ ▤`), Explorer markers (`✓ · ▤ ✦`), ~12 action buttons, and project rows all use **unicode glyphs**, not a real icon set. | Ship a proper SVG icon set (activity bar + explorer + per-language file icons). |
| 2 | **Code-view logos + viewer** | `IdeKit` FileIcon/FolderIcon are one hand-drawn tinted page-shape (no per-language). **Diff toggle is dead for MODIFIED files** — `IdeRunResult.openFile` only builds a diff when `status==='ADDED'`, discarding the `base` the backend returns for edits. | Real file-type icons + wire the diff for MODIFIED files (use `FileDiffPayload.base`+`head`). |
| 3 | **No create-project** | **Partly stale** — `＋ New project` *works* on `/projects` (verified). But it's **undiscoverable**: the Explorer PROJECTS section, activity bar, Welcome, and the Compose project-picker have no create affordance (picker only *selects*). | Add `＋` to the Explorer PROJECTS header + a "New project" command in ⌘K + a Welcome CTA. |

## Top cross-chrome UX problems (prioritized)

1. **IDE iconography is placeholder everywhere** (issue #1) — the single biggest reason the IDE reads as unfinished.
2. **Verdict thresholds disagree** — canonical ≥85 (Shippable), but `ProjectRunsPage` inline detail + the **public Shared report** page use ≥90. The same run reads differently in adjacent panes and on the link customers share. No shared `VerdictPill` in `signal/` — every page re-derives it.
3. **Public `/pricing` CTAs are inert** — "Start free / Start trial / Talk to sales" have **no onClick**. Every visitor converting from the marketing pricing page hits a dead end. (Conversion-critical.)
4. **FREE quota copy is a lie at the upgrade moment** — enforced as a **lifetime** cap (`TierEnforcementService` "never reset") but UI/migration/at-cap card all say "15 runs/**month**."
5. **Gate dead-ends with no upgrade path** — deploy / PR-review / code-review gates 403/429 into a toast with no `/plans` CTA; the one FREE→paid nudge ("See PRO") points at a **hidden, unbuyable** plan.
6. **IDE theme desync** — `CostProgressBar` + "Build my spec" `SpecChat` use `--signal-*` tokens tied to `data-signal-theme`, which the IDE theme toggle never updates → they stay dark in IDE light mode.
7. **Stale Glossary** — defines "verified score" as the *old* multiplicative formula; scoring has been additive-weighted since 2026-05-19. The most-surfaced tooltip teaches the opposite of reality.
8. **Dead-end rows** (chevron, no action) — Account "Active sessions", Billing invoices + "Update", Architecture version/ADR/invariant rows, Trust "Documents", ProjectPulse Share/More, ProjectMembers "+".
9. **`window.confirm/alert/prompt` + raw `fetch()` escapes** — ~20 destructive/input actions still use native dialogs; real customer paths (project chat/invite/share-links, account GDPR/DSAR, billing) bypass `api.ts`.
10. **Plan-gate anxiety** — the highest-traffic monetization screen shows "charge N credits" with no "what's a credit (~$5)", no balance, no "refunded on failure", and a misleading `credits=3/cap=2.0` fallback.

## Detailed missing-features list (post-build state)

Almost nothing is *fully* missing now; the residual splits three ways.

**A. Intentionally deferred (product decisions — not built on purpose):**
- V3 multi-solution **execution lane** (`/api/executions`) — feature-flagged OFF (`executionViewEnabled=false`; `App.tsx` redirects `/executions/*` away). V2 is the only active path.
- **Accounting / GST / invoicing** admin UI (`/api/accounting/*`, admin invoices) — SUPER_ADMIN finance, niche.
- **Slack per-project channel wiring** (channel mapping/verbosity/test/logs).

**B. Built but flag-OFF / unsurfaced (low effort to light up):**
- **Anonymous-trial loop** — full backend (`AnonymousRunService`, abuse-screened, signup carry-over) behind `v3.anonymous-trial.real-dispatch` (default OFF) with **no frontend caller**. ~80% done; the single biggest top-of-funnel lever.

**C. Genuinely missing / broken (defects, all in scope to fix):**
- Real **icon set** (activity bar / explorer / per-language file icons) — issue #1/#2.
- **Code-viewer diff for MODIFIED files** — issue #2.
- **Create-project discoverability** from IDE-native paths — issue #3.
- **Public pricing CTAs** wired (#3 in top problems).
- **In-context upgrade modals** at every gate, targeting the correct buyable tier.
- **Usage meter** (runs used / cap) on home + compose, with correct month-vs-lifetime copy.
- **Shared `VerdictPill`** to kill the ≥85/≥90 drift.
- **Glossary** refreshed to additive scoring.
- Tech-debt stat on project cards (renders "—").
- Doc-indexing poll (Settings→Documents shows EMBEDDING forever, never polls).
- Project tab live counts; notification-bell real count; org "1 seats" bug; theme-desync panels.

## Conversion — top 5 cross-persona levers (from `conversion-analysis.md`)

1. **Ship the anonymous-trial loop** — worst drop-off is the auth wall before any run; the backend is ready.
2. **Fix FREE quota truth + add a usage meter** — the lie lands exactly at the upgrade decision.
3. **Replace dead-end gate errors with in-context upgrade modals** targeting the correct (buyable) tier.
4. **Add a proportionate single-seat paid step** — today FREE→TEAM is a $78/mo min jump (PRO hidden); biggest ladder discontinuity.
5. **De-anxietise the plan gate** — explain credits (~$5), show balance + "refunded on failure".

**Worst funnel stage:** top-of-funnel (pre-SIGNUP → SPEC_SUBMITTED) — auth wall despite a ready anonymous backend; close second is the FREE→paid boundary.

## Suggested fix order (highest ROI first)

1. **IDE icon set** (#1/#2) — biggest perceived-quality win, self-contained.
2. **Public pricing CTAs + gate upgrade modals + FREE quota copy/meter** — direct conversion impact, small diffs.
3. **Create-project discoverability** (#3) + code-viewer MODIFIED diff (#2).
4. **Consistency sweep** — shared VerdictPill (≥85), Glossary refresh, theme-desync panels, dead-end rows, notification badge.
5. **Anonymous-trial loop** (flag flip + thin UI) — biggest funnel lever, larger effort.
