# ExecuteSpec Enterprise — Figma Screen Repair Backlog

Status: **Read-only repair plan; no mutations applied**  
Baseline: 46 top-level frames, 224 reactions  
File: `aq1I0rCCUpfRKe7gyyO41S`

## Repair policy

1. Do not duplicate all 46 frames. Correct shared components first and migrate instances.
2. A separate frame is justified only when scope, authority, evidence, recovery, breakpoint responsibility or prototype state changes.
3. Preserve the existing node until its replacement is visually and interactively verified.
4. State, redaction and action-severity blockers precede typography and visual polish.
5. Desktop repair does not prove tablet/mobile closure.

## Batch R1 — State truth, redaction and consequential actions

| Node | Current screen | Required repair | Components/state proof | Exit evidence |
|---|---|---|---|---|
| `40:103` | E01Z Zero information | Replace inherited shell with neutral org-creation/greenfield state; remove objectives, people, sources, graph and freshness | Empty/State, Permission/Notice, redacted Shell | no forbidden identifiers in visible or accessibility text |
| `40:438` | E02D Permission denied/no result | Split denied from no-result; neutralise Inspect and graph | Answer/Panel denied/no-result, Permission/Notice | denied variant exposes requested scope only; no-result proves search occurred |
| `50:291` | X01A Choose view | Remove populated Enterprise shell; present neutral Studio/Enterprise choice | ViewChoice, neutral Shell | no tenant/project/objective context before selection |
| `50:626` | X02B Wrong view/expired/denied | Replace inherited shell; split wrong-view, expired and denied recovery states | Permission/Notice, Continuity/Card | no repository/Jira/Slack/member/graph disclosure |
| `42:175` | E05F Failed/inconclusive/unmeasured | Replace “Evidence complete” with compound outcome and missing proof | Status/Compound, Evidence/Card, Failure/Panel | process/outcome/evidence completeness agree |
| `42:399` | E06P Partial/stale/conflicting | Replace “Grounded answer”; expose omissions, conflicts and freshness | Answer/Panel partial/conflicting, Conflict/Card | only complete/partial answer types may claim grounding |
| `42:510` | E06D Denied/unable | Remove “Grounded answer”; split permission and availability causes | Answer/Panel denied/unable, Permission/Notice | no hidden citation/source identifiers |
| `44:359` | E09H Connector drift | Replace Healthy with drift/stale/degraded tuple | Connector/Card, Source/Row, Status/Compound | connection, permission, availability and freshness agree |
| `44:471` | E09P Disconnect/purge/export | Remove Healthy; create destructive zone and review confirmation | Action/CriticalButton, Action/Card, Connector/Card | target, retained/deleted data, legal hold and recovery limits visible |
| `41:139` | E03X Rejected/expired/invalidated | Replace Plan ready; preserve superseded decision history | Status/Compound, Action/Card blocked, Timeline/Event | invalid approval cannot execute; valid rescope/reapprove path exists |
| `27:714` | E08 Incident | Replace generic green Rollback with P2/Conditional recommendation or approval request | Status/Maturity, Action/Card, Action/CriticalButton | no executable P0/P1 production rollback |
| `44:583` | E09M Route unavailable/limit | Treat Apply route as high-impact and policy/availability-aware | Model/Identity, Action/Card, Action/CriticalButton | exact route, cost, policy, recovery and reconciliation shown |
| `51:327` | X02C Reconciliation | Treat uncertain external write as high-impact; preserve idempotency/correlation | Action/Card, Failure/Panel, Timeline/Event | retry cannot duplicate write and result remains reconcilable |

## Batch R2 — Shared shell, navigation, query and hierarchy

Apply to every expanded desktop frame unless a neutral/redacted shell is required.

- Replace all glyph-prefixed footer rows with `Navigation/Row` instances using fixed 24px icon slots and fixed label anchors.
- Use `Navigation/PaneToggle` for both rails and wire reversible collapse/expand transitions.
- Convert centre input to `Composer/Objective`; convert Inspect input to visually distinct, read-only `Search/Context` with explicit scope.
- Make New Objective secondary outside Home/creation. The current consequential decision owns the strongest accent.
- Use 40px preferred desktop navigation/action height; 44px in touch variants.
- Migrate primary instructions and decisions to 15px+, navigation/supporting content to 13px+.

| Node(s) | Shared repair responsibility |
|---|---|
| `9:2`, `27:126`, `27:224`, `27:322`, `27:420` | lifecycle shell, fixed navigation, dual-composer separation, action hierarchy |
| `27:518`, `27:616`, `27:812`, `31:94` | shell repair plus purpose-built surface replacement in R3 |
| `40:215`, `40:327`, `41:251`, `41:363`, `41:475`, `42:287`, `43:211`, `43:323`, `43:435`, `43:548`, `44:247`, `50:403`, `50:515`, `51:439` | state-specific shell migration using shared navigation/query/action components |

## Batch R3 — Purpose-built global tools

| Node | Replacement primary object | Required composition | Primary action | Secondary paths |
|---|---|---|---|---|
| `27:518` E06 Intelligence | scoped query/session | scope builder, recent/saved/history, filters, answer provenance, conflict comparison | Run read-only query | save query P1, create Objective, handoff |
| `42:399` E06P | partial/conflicting answer | omissions, source versions, conflicts, freshness, retry scope | Clarify/change scope | create Objective, handoff |
| `42:510` E06D | denied/unavailable query | requested scope, safe cause, authorised recovery | Request access/change scope/retry | open source settings if authorised |
| `27:616` E07 Services | service portfolio | health/owner/env/readiness/incident/change filters, portfolio list/map, evidence freshness | Inspect service | create investigation Objective |
| `43:211` E07U | unknown/degraded portfolio | unavailable source and last valid observation separated | Retry/inspect source | handoff/escalate |
| `43:323` E07I | selected active incident | affected service/env, owner, live vs historical evidence | Open incident | recommend remediation Objective |
| `27:812` E09 Settings | six-group admin workspace | group nav, role effects, dirty state, review changes, save/discard, audit preview | Review changes | discard, request approval |
| `31:94` E09B | model policy matrix | provider logo/name, full model label, policy route, availability, cost boundary | Review policy change | validate route, audit history |
| `44:247`, `44:359`, `44:471`, `44:583` | connector/model lifecycle variants | validation, capability, permission, freshness, drift, disconnect/export/purge, route outage | state-specific reviewed action | retry, request access, export, cancel |

## Batch R4 — Core lifecycle and PRD workflow completion

| Node/family | Keep | Add or change |
|---|---|---|
| `27:126` E01 | Needs-you prioritisation | greenfield organisation/workspace path; source-based Objective creation; today-from-Jira with scope binding |
| `9:2` E02 | context decision | ambiguous-scope builder; citation authority/conflict; option selection reactions; structured brainstorming entry |
| `27:224`, `41:139`, `41:251` E03 | plan scope/risk/cost/expiry | reject, change scope, invalidation, exact authority, approval version, decision record |
| `27:322`, `41:363`, `41:475` E04 | work/paused/recovery | actor/ownership, progress evidence, cancel/resume/retry, supersession and preserved history |
| `27:420`, `42:175`, `42:287` E05 | evidence/delivery | complete VER-001–015 plan/run/classification, unmeasured ledger, export/share permissions, write-back correlation/reconciliation |
| E02/E05 document family | source/citation concepts | understand, cited update diff, publication approval, drift, supersession and write-back |
| Objective timeline | existing lifecycle thread | decisions, verification, delivery, external results, learning and Work Graph lineage |
| Context Operations | existing source metadata | retrieval health, permission drift, deletion impact, golden-query quality and cold-start state |

## Batch R5 — Prototype graph repair

### Existing dead ends

| Node | Current reactions | Required reachable transitions |
|---|---:|---|
| `39:103` P00 directory | 45 | retain all destinations; add severity/maturity legend, filter/search and prototype status without becoming product navigation |
| `14:23` left collapsed | 0 | expand left, collapse right, focus, return P00 |
| `15:44` right collapsed | 0 | expand right, collapse left, focus, return P00 |
| `15:176` focus | 0 | expand left, expand right, return previous state, return P00 |
| `45:283` compact desktop | 0 | navigation, left toggle, Inspect open/close, primary/secondary action, return P00 |
| `45:423` tablet drawer | 0 | close by button/scrim/back, change Inspect destination, preserve focus/state, return P00 |

Every desktop destination also receives a visible prototype-only Return to directory control or global testing shortcut. Decision options, reject/change-scope, pause/resume/cancel/retry/recover and all drawer/modal close paths receive real reactions.

## Batch R6 — Responsive frame delta

The existing E10 set is illustrative. The minimum operational pack below reuses responsive components and state variants; it does not become permanent product navigation.

### Tablet responsibility frames

| Frame | Responsibility |
|---|---|
| `T01 Home` | Needs you, greenfield and source entry |
| `T02 Context` | scope, citations, conflict and option selection |
| `T03 Plan` | review, approve, reject and change scope |
| `T04 Working` | progress, pause, block, cancel and recovery |
| `T05 Evidence` | summary, ledger, failure/inconclusive and delivery reconciliation |
| `T06 Intelligence` | scoped query and partial/denied result |
| `T07 Services` | portfolio, selected service and incident entry |
| `T08 Incident` | evidence, escalation and P2/Conditional recommendation |
| `T09 Settings` | six groups, reviewed change and critical lifecycle action |
| `T10 Recovery` | denied/stale/expired/degraded state |
| `T11 Inspect drawer` | open/close, scrim, trap, selected destination and restored focus |

### Mobile responsibility frames

| Frame | Responsibility |
|---|---|
| `M00 Navigation` | Home/Objectives/global tools/notifications/account without exposing unauthorised scope |
| `M01 Home` | Needs you, greenfield and Jira-ready entry |
| `M02 Objective` | actual Objective thread, stage and composer—not a Home summary |
| `M03 Plan` | evidence review, approve, reject and change scope |
| `M04 Working` | progress, pause/cancel, blocked cause and recovery |
| `M05 Evidence` | summary plus ledger drill-down and partial write-back |
| `M06 Intelligence` | scoped query plus complete/partial/denied/conflicting result states |
| `M07 Incident` | incident review, escalation and create-remediation-Objective path |
| `M08 Critical settings` | reviewed connector/model/access change plus failure recovery |
| `M09 Deep link` | authorised, wrong-view, expired and denied states |
| `M10 Desktop handoff` | create, deliver, open, expire, revoke and recover continuity token |

Existing `45:528`, `45:532`, `45:630` and `45:634` are inputs to M01/M03/M05/M10 respectively, not accepted as complete mobile proof.

## Batch R7 — Per-frame validation

Each repaired/new frame must pass:

1. canonical state tuple agrees across headline, rows, actions and accessibility annotations;
2. no forbidden inherited context in neutral/denied/expired/wrong-view states;
3. one strongest action, severity-correct and maturity-labelled;
4. 40px desktop or 44px touch targets and visible keyboard focus;
5. primary body ≥15px and navigation/supporting copy ≥13px;
6. official identity mark + canonical name; model also includes provider + full label;
7. entry, primary, secondary, denial/failure, recovery, return and continuity transitions;
8. no invalid destination, self-loop masquerading as progress or dead end;
9. 200% zoom/reflow and 30% string expansion annotation;
10. P0/P1/P2/Conditional and Current/Partial/Planned maturity remain honest.

## Repair order

`R1 blockers → Phase 1 foundations → atomic components → R2 shell migration → R3 dedicated tools → R4 lifecycle/PRD workflows → R5 graph → R6 responsive pack → R7 validation`.

No screen mutation should start before Phase 1 foundations are approved and validated.
