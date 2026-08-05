# ExecuteSpec Studio — Phase 6 Playable Prototype Graph

Date: 2026-07-23  
Status: **Accepted**  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>

## Verification result

- Named flow starts: **17**
- Interactive reaction nodes: **72**
- Navigation edges: **72**
- Distinct screen transitions: **71**
- Broken or missing destinations: **0**
- Required core/recovery route assertions: **19/19 passed**
- Generic or unnamed flow starts: **0**

Controls were wired at the actual actionable text, card, button, panel, or composer node wherever the existing composition exposed one. Root-frame transitions are limited to intentional time-based deployment progress.

## Named flow starts

| Start | Node | Purpose |
|---|---:|---|
| Studio — Core build, publish, health, and rollback | `13:3` | casual creation through verified recovery |
| Studio — Visual edit, Version, compare, and restore | `13:365` | direct manipulation and safe version recovery |
| Studio — Data, API, secret, and checks | `15:289` | managed resource and secret path |
| Studio — Limit, upgrade, and same-Task resume | `23:1126` | entitlement continuity |
| Studio — Professional client delivery and handoff | `32:1239` | full professional delivery path |
| Recovery — Offline workspace | `25:1165` | reconnect without Task loss |
| Recovery — Stale or conflicting context | `34:1950` | resolve cited-source disagreement |
| Recovery — Source or integration unavailable | `15:614` | reconnect or safely remain blocked |
| Recovery — Failed checks | `18:767` | bounded repair and fresh evidence |
| Recovery — Invalid generated test suite | `15:778` | suite repair before evidence can be cited |
| Recovery — Failed CI | `36:2595` | classified repair Task |
| Recovery — Deployment failure and rollback | `37:2531` | evidence-preserving rollback |
| Recovery — Permission denied | `39:2919` | request bounded authority |
| Recovery — Expired or insufficient authority | `35:2226` | renew the exact grant |
| Recovery — Cancel personal project deletion | `24:1233` | safe cancellation |
| Recovery — Cancel professional destructive action | `39:3006` | client-scoped cancellation |
| Recovery — Client boundary mismatch | `39:3091` | remove mismatched artifact and retry |

## Verified transition paths

1. Core: `D01 → D02 → D03 → D04 → D05 → D06 → D15 → D22 → D23 → D24 → D25 → D26`.
2. Visual: `D06 → D07 → D08 → D19 → D20 → D21 → D19`.
3. Data: `D06 → D10 → D13 → D15`.
4. Data failure: `D13 → D14 → D13`.
5. Limit: `D05 → D34 → D35 → D04`; the original Task resumes.
6. Professional: `D41 → D44 → D48 → D52 → D55 → D59 → D63 → D67 → D68 → D69 → D70 → D71 → D72 → D73 → D74 → D76 → D77 → D79 → D80 → D81`.
7. Client feedback: `D77 → D78 → D63`.
8. Offline: `D39 → D10`.
9. Stale context: `D54 → D52`.
10. Source unavailable: `D14 → D13`.
11. Failed checks: `D18 → D17 → D15`.
12. Invalid suite: `D16 → D17`.
13. Failed CI: `D71 → D72`.
14. Deployment failure: `D75 → D73`.
15. Permission denied: `D89 → D60 → D61 → D59`.
16. Expired authority: `D62 → D60`.
17. Personal deletion cancellation: `D38 → D37`.
18. Professional deletion cancellation: `D90 → D88`.
19. Client boundary mismatch: `D91 → D47 → D91`.

## Interaction decisions

- Preview exposes both visual editing and downstream verification without turning the whole frame into an ambiguous click target.
- Automatic CI and on-demand CI converge on the same durable CI evidence state.
- Deployment progress can advance automatically, while pause deliberately exercises the degraded-health/rollback path.
- Client review exposes approve and request-changes as different durable branches.
- Upgrade returns to the saved working Task rather than starting a new Project or conversation.
- Cancellation returns to the pre-destructive state; it never simulates a completed mutation.

## Responsive prototype contract

The canonical click-through graph is desktop because it exercises the complete interaction model. Tablet and mobile retain the same durable objects, actions, decisions, and recovery states in responsive compositions. Implementation must apply the same transition contract to responsive routes; it must not create a second state machine.

