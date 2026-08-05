# ExecuteSpec Enterprise — Figma Prototype Accessibility Audit

Status: **CP-076 whole-file Figma audit passed; browser/runtime accessibility remains an implementation gate**  
Date: **2026-07-22**

## Audited scope

The final scripted audit covered every accepted Enterprise root on page `5:4`:

- 110 screens: 48 desktop, 30 tablet and 32 mobile.
- 4,035 visible text nodes.
- 327 canonical `Action/Button` instances.
- 127 icon-only prototype targets.
- 231 collapsed `Navigation/Row` instances.
- 81 `Navigation/PaneToggle` instances.
- 20 named prototype starts.
- 13 permission-denied, wrong-view, expired or otherwise redaction-critical roots.

Focused screenshots were also reviewed for the repaired source-unavailable screen and both desktop recovery states that previously had no actionable return path.

## Final automated assertions

| Assertion | Result |
|---|---|
| Accepted viewport sizes are exactly 1440×960, 834×960 and 390×844 | Pass |
| Visible text below 9px | 0 |
| Text clipped by a visible ancestor | 0 |
| Text or interactive control outside its accepted viewport | 0 |
| Computed WCAG 2.2 AA text contrast | 4,035/4,035 pass |
| Canonical actions | 327 total; 316 wired; 11 explicitly inert |
| Accidental unwired actions | 0 |
| Missing destinations / self-navigation / malformed URLs | 0 / 0 / 0 |
| Icon-only semantic names and 44px targets | 127/127 pass |
| Collapsed navigation hidden labels | 231/231 non-empty and non-numeric |
| Pane-toggle semantic names and target size | 81/81 pass |
| Duplicate accepted screen names | 0 |
| Generic or non-accepted flow starts | 0 |
| Accepted-screen dead ends | 0 |
| Redaction scan | 13/13 pass |

## Repairs made during CP-076

1. Replaced an oversized 1320px `Source/Row` compressed into a 664px degraded-state workspace with a compact canonical Source Identity composition.
2. Repaired the source-unavailable banner and 31 other clipped instance-property strings with shorter equivalent copy; authority, evidence, recovery and state meaning were retained.
3. Raised all inherited 8px metadata labels to 10px across seven canonical data-dense component families. Component dimensions remained unchanged.
4. Restored the external reconciliation content and `Timeline/Event` instance to their canonical 1320px composition width.
5. Rebound expired/disconnected component text and one mobile status glyph to accessible semantic text/icon variables; the computed contrast failures fell from seven to zero.
6. Added a primary recovery action to Continuity Expired and primary/secondary recovery actions to Link Authorization Denied. Both screenshots were visually reviewed.
7. Replaced two generic flow names, retargeted two legacy starts and added explicit zero-information and model-route recovery starts.

## Focus and input contracts

All accepted roots carry shared implementation metadata for logical focus order, entry focus, status announcements and reduced motion.

- Desktop: skip link → top bar → left navigation → primary workspace/actions → Inspect toggle/content → Search context.
- Tablet: skip link → top bar → semantic rail → primary workspace/actions → Inspect trigger. While the Inspect drawer is open, background content is inert and focus is trapped.
- Mobile: skip link → top bar → primary content/actions → bottom navigation. Pushed routes follow document order.
- Tablet Inspect `251:10683` and mobile navigation overlay `263:11434` specify first-focus, Tab/Shift+Tab cycling, Escape/close/back dismissal and focus restoration to the invoker.
- Recovery screens specify heading → explanation → recovery actions → privacy note.
- No destructive or high-impact action receives entry autofocus.
- Progress/status uses polite announcement; assertive announcement is reserved for an immediate blocking failure.

## What Figma cannot prove

The design audit is complete, but runtime accessibility is not. Coding agents must still prove:

- actual DOM order, keyboard focus visibility, trap and restoration in the browser;
- screen-reader name/role/value/state, live-region dedupe and accessibility-tree redaction;
- 200% browser zoom/reflow and 30% localisation expansion;
- reduced-motion execution, mobile safe areas and virtual-keyboard behavior;
- real authorization, source ACL, connector and persistence semantics.

The required Playwright/component/security tests are defined in `21-CODING-AGENT-IMPLEMENTATION-HANDOFF.md` and `10-ANALYTICS-AND-ACCEPTANCE-TESTS.md`.
