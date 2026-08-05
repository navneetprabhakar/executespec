# ExecuteSpec Enterprise — Responsive and Cross-Device Rules

Status: **Normative and represented in the validated CP-076 desktop/tablet/mobile pack**

## Breakpoints

| Mode | Width | Navigation | Centre | Inspect | Primary use |
|---|---:|---|---|---|---|
| Desktop | ≥1280 | expanded or collapsed left pane | fluid, max readable content width | docked/collapsible; task-dependent default | creation, comparison, administration |
| Compact desktop | 1024–1279 | collapsed by default; expandable overlay/docked when space allows | retains priority over Inspect | closed by default or ≤36% width | full work with reduced simultaneous context |
| Tablet | 768–1023 | icon rail or modal navigation drawer | full-width primary object | modal drawer with scrim and focus trap | full journey, sequential inspection |
| Mobile | 320–767 | top bar + bottom/overflow destinations; no permanent side panes | one primary screen at a time | separate pushed screen/sheet | decisions, status, evidence, recovery, secure handoff |

## Invariants

1. Objective title/status/next decision remain visible before secondary context.
2. The centre is never narrower than the information needed for the current primary action. Inspect yields first.
3. Both desktop panes preserve their collapse preference per user/device, except a critical decision may temporarily open Inspect with explicit focus.
4. Tablet Inspect is a true modal drawer: scrim, background inert, focus trapped, Escape/close/back supported, focus restored to invoker.
5. Mobile never compresses the desktop three-pane shell. It uses dedicated screens with explicit back/return semantics.
6. Every mobile approval offers approve, reject, change scope and inspect evidence. If an action cannot safely execute on mobile, the user can preserve/send a desktop handoff.
7. No breakpoint widens permission or reuses stale approval.

## Pane sizing

- Desktop expanded left pane: 240–288px. Collapsed: 56px.
- Desktop Inspect: 360–440px; may reach 480px only for evidence comparison and only when centre remains ≥640px.
- Compact desktop left: 56px default. Inspect: overlay or ≤360px.
- Tablet Inspect drawer: min(560px, 86vw), right anchored.
- Mobile sheets: ≤92vh with a full-screen route for evidence ledger/diffs.

## Responsive journey responsibility

| Journey | Desktop/compact | Tablet | Mobile |
|---|---|---|---|
| Home/Needs you | full lists/filters | full list with filter sheet | prioritised cards, search/filter screen |
| Objective Context | simultaneous centre + Inspect | centre + modal Inspect | context summary → source/conflict detail screens |
| Plan decision | full comparison | full sequential review | scope/risk/evidence summary; approve/reject/change scope; full detail drill-down |
| Working/recovery | activity + Inspect evidence | full status and controls | status, pause/cancel where authorised, blocked cause, retry/handoff |
| Evidence/Delivery | ledger + diff/write-back | full ledger sequential detail | summary cards → full ledger; write-back recovery and share state |
| Intelligence | full scope builder/history/answer | full, filters in sheet | query, explicit scope, answer/citations; saved/org-wide depth labelled P1 |
| Services/Incident | portfolio + detail/live panels | portfolio/detail routes | service status, unknown/incident review, recommendation/escalation only |
| Settings | full six-group workspace | full sequential groups | status/critical approval/recovery; broad policy editing uses secure handoff |
| Onboarding | full guided setup | full guided setup | complete brief/proposal decisions; complex connector OAuth may hand off |

## Cross-device continuity contract

Transfer only these explicitly scoped fields: product view, tenant, Objective ID, current stage, selected evidence/source ID, pending decision/action ID, draft identifier, filters, source versions and approval expiry.

### Handoff delivery

- `Continue on desktop` creates a one-time, short-lived, user-bound continuation token.
- Delivery options: signed-in device list and copyable secure link. Email/Slack delivery is optional and must not contain confidential content.
- Desktop re-authenticates, re-authorises every object, reloads current source versions and invalidates stale approval before restoring the decision.
- Mobile shows handoff status: created, opened, expired, revoked, source changed, or completed; user can revoke or issue a new token.

## Touch, keyboard and accessibility

- All tablet/mobile targets ≥44×44px.
- Logical DOM/focus order follows visual order after reflow.
- Drawer/sheet opening is announced; live state changes use polite status announcements; failures requiring action are assertive without repeated spam.
- 200% zoom uses the next narrower layout rather than horizontal page scrolling, except intentional code/diff regions with labelled horizontal scrolling.
- Reduced motion removes non-essential transitions; state change remains perceptible through text/icon/position.
- Localisation validation uses 30% string expansion and long repo/model/provider names.

## Responsive exit gate

For every P0 user-facing row in the traceability matrix, the screen manifest must name a desktop, tablet and mobile responsibility plus entry, primary action, secondary action, denial, partial/failure, recovery and return behavior. Static outcome-only samples do not satisfy the gate.
