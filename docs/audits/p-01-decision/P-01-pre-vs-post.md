# P-01 Decision Doc — Two Project Surfaces, Pick One

> **Date:** 2026-05-04
> **Audit ref:** `docs/audits/enterprise-ux-2026-05-03.md` (P-01 [P0])
> **Status:** ✅ **Resolved — Option C (cross-link) shipped 2026-05-04**
> **Stakeholders:** navneet.prabhakar007@gmail.com

> **Decision:** the user picked Option C — keep both surfaces, but make the
> bridge between them obvious. Implementation: V3ProjectHome gains a
> "Dashboard →" button in its header strip; ProjectDashboardPage gains a
> "Quick spec" button (Sparkles icon) in the action cluster that links
> back to V3ProjectHome. Either surface is one click from the other.
> Sections below are kept for posterity; the table at the bottom now
> includes Option C with the actually-shipped costs.

## TL;DR

Today, after login, the URL `/projects/{id}` renders **V3ProjectHome** (a Slack-like
chat thread with a Composer pinned to the bottom — 291 LOC, V3Shell chrome, reads
`useV3ProjectStore`). The URL `/projects/{id}/dashboard` renders **ProjectDashboardPage**
(the 4-tab Pulse · Runs · Code · Chat dashboard — 772 LOC, AppLayout chrome, reads
`useProjectStore`).

The two surfaces have different chrome, different IA, different data stores, and
overlapping but mismatched terminology. Enterprise users can't say "what page does
X live on?" because the answer is "depends which URL you happened to land on first."

The audit's recommendation: **pick one, retire the other**. Two paths.

---

## The two options

### Option A — Redirect `/projects/:slug` → `/projects/:slug/dashboard` (cheapest)

Single-line change. Drops V3ProjectHome's role at the URL level. The component code
stays in the repo for now (next sweep removes it). After login, `V3HomeRedirect`
already routes to `/projects/{firstId}/dashboard` for new users — the only people
who see V3ProjectHome today are returning users who bookmarked `/projects/{id}`.

**Files touched:**
- `frontend/src/App.tsx:261` — change `<Route path='/projects/:slug' element={<V3ProjectHome />} />` to a `<Navigate to=':slug/dashboard' replace />` redirect.

**Risk:** Near-zero. Existing `/projects/{id}/dashboard` URLs unaffected.

**Migration impact:**
- Bookmarks pointing at `/projects/{id}` — they get auto-redirected to `/dashboard`.
- The chat-thread state in `useV3ProjectStore` becomes orphaned. Either delete the
  store + its IndexedDB persistence in the same PR, or keep it for one release as
  a roll-back hatch.

**Lines deleted (rough):**
- 0 (just changes a route definition).

**Lines deletable in a follow-up sweep:**
- `V3ProjectHome.tsx` (291 LOC)
- `useV3ProjectStore` (≈ 200 LOC across store + persistence)
- 8 references via `grep useV3ProjectStore` — touchpoints in V3 chat composer, follow-up dispatcher, run-card mini.

### Option B — Fold V3ProjectHome's chat composer into PulseTab as the hero affordance

V3ProjectHome's value prop was the always-visible Composer pinned to the bottom of
the project page — a one-click "submit a follow-up run" entry point that's faster
than the dashboard's Chat tab. Option B preserves that ergonomic by promoting the
Composer into PulseTab's hero row, then deletes V3ProjectHome.

**Files touched:**
- `frontend/src/components/project/PulseTab.tsx` — add a `<ProjectChatComposer />` block above LastRunCard.
- `frontend/src/components/project/ProjectChatComposer.tsx` (NEW) — extract from
  V3ProjectHome's Composer. Uses the same `submitRun` API, but the message
  thread lives in the existing dashboard Chat tab (via the pre-shipped `ProjectChatService`).
- `frontend/src/App.tsx:261` — drop the `<Route path='/projects/:slug' element={<V3ProjectHome />} />` line; add the redirect from Option A.

**Risk:** Medium. Requires testing that:
- The composer's submit flow lands on `/projects/{id}/dashboard?tab=runs&sub=detail` (not the V3 chat thread).
- Message history pre-existing in `useV3ProjectStore` doesn't disappear silently.
  (Likely deletable — none of it is server-authoritative.)
- The PulseTab grid layout absorbs a new hero row without breaking the existing 12-col.

**Lines added/deleted:**
- New `ProjectChatComposer.tsx`: ≈ 80 LOC.
- PulseTab.tsx: +20 LOC (hero row insertion).
- V3ProjectHome.tsx: -291 LOC.
- `useV3ProjectStore`: -200 LOC.
- Net: ≈ -390 LOC.

---

## Pre — current state

### Pre #1: `/projects/{id}` (= V3ProjectHome)

Layout (ASCII):

```
┌─ V3Shell top bar (V3 chrome — different from AppLayout) ────────────────┐
│ ExecuteSpec | Project picker ▾ | … V3 nav items …  | UserMenu           │
└──────────────────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────────────────┐
│  [Run #abc1 → SHIPPABLE · 9m12s · $0.42]                       2h ago    │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ Spec: "Add /api/contacts endpoint with email validation…"         │  │
│  │ Result: PR #123 opened on github.com/foo/bar                       │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│  [Run #def4 → NEEDS REVIEW · 12m · $0.81]                      yesterday │
│  ┌────────────────────────────────────────────────────────────────────┐  │
│  │ Spec: "Add login rate limiting with sliding window…"              │  │
│  │ Result: PR #119 opened — 1 reviewer issue: missing test           │  │
│  └────────────────────────────────────────────────────────────────────┘  │
│                                                                          │
│                                                          Chat with project →│
│                                                                          │
│ ┌──────────────────────────────────────────────────────────────────────┐ │
│ │ [Composer — pinned bottom]                                           │ │
│ │ Type a follow-up spec…                                       [Send]  │ │
│ └──────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

**What's NOT here:**
- No Pulse summary. No score ring. No Verdict pill on the run cards.
- No Repos panel. No Architecture link. No Code tab.
- No team panel. No activity feed (the chat thread is the only "activity").

### Pre #2: `/projects/{id}/dashboard` (= ProjectDashboardPage)

Layout (ASCII):

```
┌─ AppLayout top bar (canonical chrome) ──────────────────────────────────┐
│ ExecuteSpec / Projects / <project name>           [Search ⌘K] [Bell] [▾]│
└──────────────────────────────────────────────────────────────────────────┘
┌─ Project header ───────────────────────────────────────────────────────┐
│ <project name>          [Repos · 3 ▸ +]  [Launch ▸]  [More ▾]            │
└──────────────────────────────────────────────────────────────────────────┘
┌─ Tab strip ────────────────────────────────────────────────────────────┐
│ [Pulse]  [Repos]  [Runs]  [Code]  [Chat]                                 │
└──────────────────────────────────────────────────────────────────────────┘
┌─ PulseTab (default) ────────────────────────────────────────────────────┐
│ ┌──── Last run hero ────────┐    ┌── Score ring ──┐                       │
│ │  Run #abc1                │    │   ◯ 92         │                       │
│ │  SHIPPABLE · 9m12s · $0.42│    │   verified     │                       │
│ │  PR #123 → github.com…   │    │     /100       │                       │
│ └──────────────────────────┘    └────────────────┘                       │
│                                                                          │
│  [$0.42 spent · 38k tokens]                                              │
│                                                                          │
│ ┌── Health ──┐  ┌── Knowledge ──┐  ┌── Activity ──┐                      │
│ │ 3 repos    │  │ Explanation ✓ │  │ • PR #123 mrg│                      │
│ │ codemaps✓ │  │ Setup ✗       │  │ • Run #abc1  │                      │
│ │ 1 open PR  │  │ View in More→│  │ • Repo linked│                      │
│ └────────────┘  └───────────────┘  └──────────────┘                      │
│                                                                          │
│ Recent pull requests: …                                                  │
└──────────────────────────────────────────────────────────────────────────┘
```

**What IS here:**
- Verdict-led scoring (Plan 1 #3 from the product-split prep PR).
- Pulse · Runs · Code · Chat IA (v2.6.4 ux overhaul).
- Knowledge tile (P-10 from PR #62).
- Per-project spend bar, score ring, health rollup, activity feed preview.
- Architecture / Repos / Tech debt / Standards / Contracts in Code tab.
- Memory / Generated Docs / Team / full Activity in More drawer.

---

## Post — Option A (redirect-only)

`/projects/{id}` redirects to `/projects/{id}/dashboard`. A user landing on the
old V3ProjectHome URL gets the canonical dashboard immediately. Same UI as Pre #2.
V3ProjectHome.tsx stays in the repo for one release as a rollback hatch, then
deletes in the next cleanup pass.

**Visual diff vs Pre #1:** total replacement. The Slack-like chat thread is gone.

**Visual diff vs Pre #2:** zero. The dashboard is unchanged.

---

## Post — Option B (fold composer into PulseTab)

PulseTab gains a Composer row above the LastRunCard hero — same Composer that lived
in V3ProjectHome, same submit semantics. The ASCII layout for the new PulseTab:

```
┌─ Project header ───────────────────────────────────────────────────────┐
│ <project name>          [Repos · 3 ▸ +]  [More ▾]                        │
└──────────────────────────────────────────────────────────────────────────┘
┌─ Tab strip ────────────────────────────────────────────────────────────┐
│ [Pulse]  [Repos]  [Runs]  [Code]  [Chat]                                 │
└──────────────────────────────────────────────────────────────────────────┘
┌─ PulseTab ──────────────────────────────────────────────────────────────┐
│ ┌──────────────────────────────────────────────────────────────────────┐ │
│ │ ✦ What do you want to build, <name>?                                  │ │
│ │ ┌──────────────────────────────────────────────────────────────────┐  │ │
│ │ │ Type a spec…                                              [Send] │  │ │
│ │ └──────────────────────────────────────────────────────────────────┘  │ │
│ │ Quick mode (skip review) · 1 credit                                  │ │
│ └──────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│ ┌──── Last run hero ────────┐    ┌── Score ring ──┐                       │
│ │  Run #abc1 (clickable)    │    │   ◯ 92         │                       │
│ │  SHIPPABLE · 9m12s · $0.42│    │   verified     │                       │
│ └──────────────────────────┘    └────────────────┘                       │
│                                                                          │
│ … rest of PulseTab as Pre #2                                             │
└──────────────────────────────────────────────────────────────────────────┘
```

The "Launch" button becomes redundant on the project header — the composer is the
hero affordance — so we either drop it or keep it as a power-user shortcut.

**Visual diff vs Pre #1:** the chat-thread layout is gone, but the Composer
ergonomic is preserved at the top of PulseTab.

**Visual diff vs Pre #2:** PulseTab gains a hero Composer row (~120px tall on
md+, scrollable on mobile).

---

## Decision matrix

| Criterion | Option A — redirect | Option B — fold composer | Option C — cross-link (✅ shipped) |
|---|---|---|---|
| Implementation cost | ~1 hour | ~6-8 hours | ~30 minutes |
| Visual consistency win | High (one surface, one chrome) | Highest | Lowest — both surfaces remain |
| Risk of regression | Near-zero | Low-medium | Near-zero |
| Lines deletable | -290 V3ProjectHome (next sweep) | -390 net | 0 (additive only) |
| User friction loss | Returning users on `/projects/{id}` get redirected — no friction | Same + new users see Composer prominently | Two surfaces stay live, but each is one click from the other |
| Marketing-front-end impact | None | None | None |
| Deep-link breakage | None | None | None — both URLs continue to resolve |
| Reversibility | Hard (V3ProjectHome deleted in next sweep) | Hard | Trivial — flip a render gate to revisit later |

### Why Option C is the right call right now

Options A and B both bet on retiring V3ProjectHome. Option C buys time:
the cross-link closes the discoverability gap *today* without committing
to which surface wins. If usage data later shows V3ProjectHome is barely
visited, fall back to Option A (cheap). If users use V3ProjectHome more
than the dashboard, the data drives Option B. Either way, Option C's
work isn't wasted — the cross-link buttons stay useful as long as both
surfaces exist.

---

## What shipped (Option C)

**Files changed:**
- `frontend/src/v3/pages/V3ProjectHome.tsx` — added a primary "Dashboard →"
  button in the project-name header strip, immediately to the left of the
  existing "Chat with project →" button. Both buttons share the same
  visual treatment so the cluster reads as a coherent navigation set.
- `frontend/src/pages/project/ProjectDashboardPage.tsx` — added a "Quick
  spec" button (Sparkles icon, label hidden on <sm) in the project-header
  action cluster between "Repos · N", "+ New Run", and "More". Routes to
  `/projects/{id}` (V3ProjectHome). The label uses "Quick spec" rather
  than "Chat" or "V3" to describe the *what* (one-line spec composer)
  rather than the *which surface*.

**Bridge UX:**
- A user landing on either surface can reach the other in one click.
- Neither surface is hidden, redirected, or feature-gated.
- The forward direction (V3ProjectHome → Dashboard) is brand-coloured,
  emphasising the canonical 4-tab dashboard. The reverse direction
  (Dashboard → V3ProjectHome) uses neutral chrome since it's secondary
  flow.

**Future work (optional — track separately):**
- Telemetry to compare /projects/{id} vs /projects/{id}/dashboard usage.
  Once the data is in, decide whether to fall back to Option A
  (retire V3ProjectHome) or Option B (fold its composer into PulseTab).
- The cross-link cost is sunk; either future move is additive on top of
  it, not a re-implementation.
