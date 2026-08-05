# ExecuteSpec Studio — Responsive Rules

## Breakpoints

- Phone: 320–767
- Tablet: 768–1023
- Desktop: 1024–1535
- Wide: 1536+

## Shell behavior

| Region | Desktop | Tablet | Mobile |
|---|---|---|---|
| Left history/project pane | 248 px expanded, 48 px icon rail collapsed | modal drawer or 48 px rail | off-canvas drawer |
| Center task | minimum 560 px and always dominant | full remaining width | single surface |
| Right Output | 360–440 px expanded, 48 px icon rail collapsed | bottom/side sheet | full-screen destination pushed from Task |
| Composer | pinned inside center | pinned | pinned above safe area/keyboard |
| Primary action | visible label | label where space permits | label, minimum 44 px target |

Rules:
1. Collapsed panes show official library icons, never numeric placeholders.
2. No critical decision exists only in hover, tooltip, or right pane.
3. Mobile preserves Task, current status, primary decision, evidence summary, publish/rollback, and recovery.
4. Tables become labelled cards; diffs become file list → single-file diff.
5. Side-by-side version compare becomes segmented before/after view.
6. Output preview defaults to fit-width on tablet and opens full-screen on mobile.
7. Offline/stale/source-unavailable/limit states preserve the user request and resume affordance.

