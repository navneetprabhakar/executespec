# P-01 Decision Doc — Two Project Surfaces, Pick One

> **Date:** 2026-05-04
> **Audit ref:** `docs/audits/enterprise-ux-2026-05-03.md` (P-01 [P0])
> **Status:** Awaiting your call between Option A vs Option B
> **Stakeholders:** navneet.prabhakar007@gmail.com

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

| Criterion | Option A — redirect | Option B — fold composer |
|---|---|---|
| Implementation cost | ~1 hour | ~6-8 hours |
| Visual consistency win | High (one surface, one chrome) | Highest (one surface, one chrome, primary action up-top) |
| Risk of regression | Near-zero | Low-medium (composer integration testing) |
| Lines deletable | -290 V3ProjectHome (next sweep) | -390 net |
| User friction loss | Returning users on `/projects/{id}` get redirected — no friction | Same + new users see the Composer prominently |
| Marketing-front-end impact | None (V3 marketing routes untouched) | None |
| Deep-link breakage | None (redirect is permanent) | None |

---

## Recommendation

**Option A first**, ship it inside a week. It buys you the surface consolidation
without any layout decisions to make. Then schedule Option B as a follow-up
sprint when there's a designer cycle to do the Composer placement properly.
Splitting the work this way means:

- Returning enterprise users get one consistent project surface tomorrow.
- The Composer-on-PulseTab decision can absorb a real design review (font sizes,
  empty-state copy, mobile layout) without blocking the consolidation.

The audit doc explicitly calls this out: "the cheapest" path. Fastest customer-
visible improvement, smallest scope of regression.

---

## What I need from you

1. **Pick A or B** (or "neither — leave the split").
2. **Email yourself screenshots if you want them.** The dev environment runs at
   https://dev.executespec.ai. You can:
   - Open https://dev.executespec.ai/projects/{id} → screenshot the V3ProjectHome
     page (= Pre #1).
   - Open https://dev.executespec.ai/projects/{id}/dashboard → screenshot the
     PulseTab (= Pre #2 = Post-Option-A).
   - For Post-Option-B you'd need a build with the change applied; happy to
     spin one up on a branch and share a preview URL once you've picked an
     option.

> I don't have native email capability in this Claude session, so I can't auto-send
> the screenshots to your inbox. Two ways forward:
>
> - **Easy:** Take the screenshots yourself with the URLs above and the ASCII
>   wireframes here as context.
> - **I drive:** Grant me computer-use access to your browser (request_access on
>   Chrome) and I'll take the two pre-state screenshots, save them to disk,
>   then trigger Mail.app to compose an email pre-attached for you to send.
>   You'd still hit Send manually — no auto-send of credentials.
>
> Either way, this doc is the substantive comparison; the screenshots are
> confirmation rather than primary evidence.
