# 11 — UX review: honest assessment + direction

> Method: the app was walked LIVE (Vite dev + local backend, ENTERPRISE test account) at desktop,
> 800px and 375×812 widths, dark + light themes, IDE chrome + opt-in Console chrome. Raw notes with
> every defect: checkpoints/ux-live-notes.md. Code-side root causes: checkpoints/fe-ide.md +
> fe-signal-console.md (addendum below updates when those land). You said you're not convinced the
> UX is good — the honest verdict: **your instinct is right, but the problem is the frame, not the
> craft.** The screens are competently built; the IDE metaphor and the internals leaking through
> are what hold it back.

## 1. The strategic call: the IDE chrome is the wrong frame for the web app

What the web app actually does for a customer: submit a spec → approve a plan → watch a run →
read a verdict → open the PR → manage projects/billing. That is **mission control**, not editing.
The VS Code cosplay (activity bar, EXPLORER, status bar, editor tabs) adds furniture that:

- costs jargon ("Workspace", "Explorer", a status bar showing `⌥ ExecuteSpec [ENTERPRISE] … IDE · Beta`),
- costs pixels (double chrome on mobile: blue status bar + bottom tab bar ≈ 120px),
- invites comparison with the customer's real IDE (which you cannot win — and don't need to:
  your actual in-editor story is the VS Code **extension**),
- and buries your differentiators (score, gate, cost) inside generic IDE furniture.

Meanwhile your own opt-in Console chrome (`es:console=1`) already demonstrates the right frame:
hero CTA, "what needs attention", relative timestamps, inline empty-state actions. **Recommendation:
make Console the default web chrome, demote the IDE chrome, keep IDE-ness for the code viewer
inside run results (where an editor metaphor genuinely fits) and for the VS Code extension.**
This is also the positioning-coherent choice (doc 07 §3).

## 2. Verified defects to fix regardless of frame (all live-reproduced)

1. **`/api-keys` deep-load = permanent blank screen** — no route match, no 404 fallback, root
   renders nothing. Add a catch-all route + restore the page mapping.
2. **Run-detail Code tab at mobile/tablet width has no file selector** — the other changed files
   are unreachable (a11y-tree confirmed). Violates your own mobile-first rule on the flagship surface.
3. **Billing page says $0.00 / 0 runs while the runs list shows 2 runs / $0.08** — same account,
   two money surfaces disagreeing (backend cause traced in checkpoints/be-billing.md).
4. **Status-bar tier badge contrast** — ENTERPRISE badge olive-on-blue, borderline unreadable in
   dark, worse in light; status bar also overflows horizontally at 375px.
5. **Notification spam** — fresh test account shows 29 unread (badge "9+"). Auto-read on view,
   digest repeated events, and cap toast-worthy event types.
6. **Raw enums + insider strings in customer UI**: `CLEAN_COMPLETION` on the result header;
   "Default (org / system matrix)" in compose; "Openrouter"/"Openai" mis-capitalized provider rows;
   raw model ids (`deepseek-v4-flash-native`) in the F3 grid; "BYOK bypasses the cost cap" on the
   LOGIN page; "TEAM-tier orgs share a seat pool (min 2)" vs the 1-seat reality.
7. **Provider-first UX** (the thing doc 10 removes): PROVIDER as a top-level compose field,
   "Try anthropic"/"Try gemini" action buttons, provider ids on run rows — while the verified
   score (your headline metric) is absent from the runs LIST entirely.
8. **Date/number inconsistency**: IDE shows `07/06/2026, 13:37:49` (locale-ambiguous + seconds);
   Console shows `3d ago`. Pick relative-with-tooltip everywhere.
9. **"Press ⌘K to jump anywhere"** shown on touch devices.
10. **Diff rendering**: added lines are full-bleed saturated green — a new file paints the whole
    viewport green. Use conventional subtle tint + per-file +/- counts.

## 3. Surface-by-surface verdicts (live walk)

- **Login**: strong layout + real value-prop copy. Fix: two different brand marks on one screen
  (white E vs amber E), coral links read as errors, BYOK bullet is insider-speak. No visible
  error/spinner state on a failed submit.
- **Home (IdeWelcome)**: ~65% empty canvas for a user with history; no score trend, spend, or
  attention queue. ConsoleHome already solves this — adopt it.
- **Compose**: good bones (textarea-first, samples, sandbox default, spending cap). Overloaded
  with equal-weight PROVIDER/MODEL/MODE/CAP columns — collapse to "Model (smart default) +
  Advanced". "Build my spec" deserves primary visual weight; it's your best onboarding asset.
- **Run result**: the best surface — verdict-first hero (Shippable 94/100), honest signals grid.
  Fix the 10-item flat action row (primary: Push/PR; overflow the rest; Delete nowhere near
  primary), tab overload (10 tabs; merge Tests into Validation, Logs into Timeline for non-debug
  users), `Code 7` vs `Files 3` count mismatch.
- **Runs list**: filters are right; rows show provider+cost but no score — invert: score chip +
  model label, provider gone.
- **Org settings / F3 grid**: functional but it renders the broken model taxonomy directly
  (doc 09); after the aggregator reframe this becomes tier×model with display labels.
- **Mobile**: genuinely responsive overall (stacking, bottom tabs, drawer) — beyond the file-tree
  and status-bar defects above.
- **Light theme**: tokens mostly adapt; fix the badge-on-blue and audit `--ide-*` bridge values
  used on colored surfaces.

## 4. Theme & brand direction (you asked "including on theme")

- **All-mono is a real identity — keep it for data, not for everything.** JetBrains Mono for
  code, ids, numbers, chips; a humanist sans (e.g. Inter/Geist) for body copy, empty states,
  marketing paragraphs. Paragraph-length mono is measurably harder to read and the app has a lot
  of explanatory copy.
- **Pick ONE accent story.** Today: Signal Blue (buttons/status bar) + coral (links, the word
  "builder.", login accents) + amber (brand mark variant) + the 4-band score palette. Blue =
  action, score palette = state, ONE brand accent for identity moments — kill coral-as-link
  (it collides with the red end of your own score scale).
- **The score deserves a visual system.** It's your product's signature — give the 4-band ring/
  chip a consistent, prominent slot on every run surface (list rows included) so "the number"
  becomes the thing people remember.
- **Status bar**: if the IDE chrome stays anywhere, the status bar should carry live, glanceable
  state (plan gates awaiting, active runs, monthly spend) — not "IDE · Beta" + theme toggle.
- Dark-first is correct for the audience; light theme is a checkbox, keep it working but don't
  design for it first.

## 5. Priority order

1. Defects #1-#5 (trust + reachability — small fixes, immediate).
2. Jargon/enum sweep #6 + provider-first removal #7 (rides the doc-10 Phase D UI flip).
3. Console-as-default decision + runs-list score chip + result-page action hierarchy.
4. Typography split + accent consolidation (one focused pass, mostly CSS tokens).
5. The deeper tab-consolidation on run result after observing real customer navigation.
