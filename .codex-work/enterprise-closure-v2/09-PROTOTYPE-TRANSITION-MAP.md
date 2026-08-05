# ExecuteSpec Enterprise — Prototype Transition Map

Status: **Normative interaction graph; live accepted destinations exported in `22-FIGMA-PROTOTYPE-GRAPH.json` at CP-076**

## Global shell transitions

| From | Control/action | To | State preserved |
|---|---|---|---|
| Any Enterprise desktop screen | collapse left pane | same screen, left collapsed | tenant, Objective/tool, centre state, Inspect state |
| Left collapsed | left expand icon | prior expanded state | all |
| Any desktop/compact screen with Inspect | collapse/close Inspect | same screen, Inspect closed | selected Inspect object and query draft |
| Inspect closed | Inspect icon or evidence/context link | same screen, Inspect open on selected object | current primary content and pending decision |
| Any destination | `Prototype directory` utility | P00 | no product data; test-harness return only |
| Home/Objectives | Intelligence semantic nav | EN-30 | tenant/team and optional current Objective scope |
| Home/Objectives | Services semantic nav | EN-40 | tenant/team and entity reference |
| Any Enterprise screen | Settings semantic nav | EN-50 | safe return location |
| Enterprise view menu | Switch to Studio | EN-00 confirmation → Studio | only explicitly permitted reference; no Enterprise content |
| Studio view menu | Switch to Enterprise | EN-00 confirmation → EN-10 | re-authorised tenant; no Studio private storage by default |

## Objective lifecycle

| From | Action | To |
|---|---|---|
| EN-10 Home | New/link Objective | EN-11 |
| EN-10 ready-work row | Open | EN-12 when scope ambiguous; otherwise EN-13 |
| EN-11 direct intent/source select | Continue | EN-12 |
| EN-12 confirm scope | Continue | EN-14 |
| EN-12 ambiguous/denied | Clarify/request access | EN-12 recovery state |
| EN-14 context accepted | Plan work | EN-17; if decision needed, EN-15 |
| EN-14 conflict | Resolve authority | EN-16 draft → EN-14 resolved |
| EN-15 produce artifact | Review decision | EN-16 |
| EN-16 approve | Continue to plan/document | EN-17 or EN-25 |
| EN-17 approve and start | EN-19 running |
| EN-17 reject | EN-17 rejected → EN-13 needs decision |
| EN-17 change scope | EN-12 with plan draft preserved and invalidated |
| EN-17 expired/source changed | Re-review | EN-17 invalidated with changed fields |
| EN-19 pause/cancel | EN-19 paused/cancelled |
| EN-19 resume/retry | EN-19 running/recovery |
| EN-19 superseded | Create/open successor | EN-13 successor; prior remains archived evidence |
| EN-19 execution complete | Verify | EN-20 |
| EN-20 passed/partial/failed | Inspect evidence | EN-22 corresponding state |
| EN-22 required proof satisfied | Deliver | EN-23 |
| EN-22 missing proof | Run missing verification | EN-18 typed action → EN-20 |
| EN-23 successful reconciled write-back | Objective | EN-13 ready/complete depending delivery boundary |
| EN-23 partial/unknown | Reconcile | EN-62 → EN-23 reconciled/failed |

## Verification transitions

| From | Action | To |
|---|---|---|
| EN-20 inventory | Review impact | EN-20 impact state |
| EN-20 impact | Review plan | EN-20 plan state |
| EN-20 plan | Remove required item | exception request in EN-18; never silent removal |
| EN-20 plan | Run verification | EN-18 action → EN-20 environment check/running |
| EN-20 environment blocked | Fix/rebind/retry | EN-20 environment check |
| EN-20 missing coverage | Generate test proposal | EN-21 |
| EN-21 regression baseline-fail/candidate-pass | Adopt | EN-18 approval → EN-20 rerun |
| EN-21 passes both | Inspect limitation | EN-21 inconclusive; regenerate/reject available |
| EN-20 flaky suspected | capped retry | EN-20 attempt history; green retry retains original failure |
| EN-20 newer SHA | Supersede | current run cancelled/superseded → new EN-20 bound to new SHA |

## Intelligence and context

| From | Action | To |
|---|---|---|
| EN-30 | Submit scoped query | EN-31 loading → result state |
| EN-31 citation | Inspect | source detail in Inspect/mobile route; return to same answer |
| EN-31 conflict | Compare sources | conflict detail → EN-31 |
| EN-31 partial/stale/denied | Change scope/refresh/request access | EN-30 scope or EN-31 refreshed state |
| EN-31 bounded action | Propose | EN-18 typed action; no direct mutation |
| EN-31 create Objective | Review new Objective binding | EN-11/EN-12 |
| EN-31 save/share | P1 path | P1-labelled saved/share confirmation; recipient goes through EN-60 |

## Documents and decisions

| From | Action | To |
|---|---|---|
| EN-24 | Draft update | EN-25 |
| EN-25 approve publish | EN-18 authority card → EN-23 write-back |
| EN-25 stale target | Rebase | EN-25 refreshed diff; approval invalidated |
| EN-25 write-back uncertain | Reconcile | EN-62 |
| EN-16 publish decision | EN-25/EN-23 | linked Objective/decision preserved |

## Services, incidents and collaboration

| From | Action | To |
|---|---|---|
| EN-40 service row | Open | EN-41 |
| EN-41 live unavailable | Use last observed | EN-41 stale/unknown; no `Healthy` claim |
| EN-41 incident | Investigate | EN-42 P1/Conditional |
| EN-42 recommend remediation | Create Objective/escalate | EN-11/EN-26; no production execution |
| EN-26 send request | recipient deep link | EN-60 → authorised response or denied/expired |
| EN-26 recipient accepts/declines/redirects | response | EN-26 result + EN-13 timeline event |

## Settings and data lifecycle

| From | Action | To |
|---|---|---|
| EN-50 group | Open | EN-51–59 |
| EN-53 Connect source | EN-04/EN-54 validation |
| EN-54 expand scope/rotate credentials | review changes | EN-18 high-impact → EN-54 validating |
| EN-54 disconnect | review effect | EN-59 destructive review |
| EN-59 export | EN-18 high-impact → export status |
| EN-59 start purge | EN-18 destructive confirm → EN-59 running |
| EN-59 partial/failed purge | retry incomplete stores | EN-59 running/recovery |
| EN-59 verified purge | EN-54 disconnected with audit link |
| EN-55 unavailable route | select allowed route/request policy change | EN-18 → EN-55 saved or blocked |
| EN-51/55/56/58 dirty state | Leave | save/discard/stay dialog; return only after decision |

## Deep-link and device continuity

| From | Action | To |
|---|---|---|
| authorised external link | Open | EN-60 check → exact Object/action |
| denied/expired/wrong-view link | request access/sign in/switch view | neutral EN-60/EN-00; no inherited context |
| mobile desktop-required step | Continue on desktop | EN-61 token created |
| desktop opens token | re-auth/re-authorise/revalidate versions | exact EN-D/C family and state |
| token expired/revoked/source changed | Reissue/re-review | EN-61 recovery or current-screen invalidated state |
| destination completes | Mark handoff complete | origin device EN-61 completed |

## Mandatory choice behavior

- Decision and plan options are selectable at the card level, not only through a bottom CTA.
- Selecting an option updates visible selected state and enables the appropriate primary action.
- Approve, reject and change-scope are independently playable.
- Back/close returns to the immediately prior state with drafts and selection intact unless security requires clearing it; that clearing is explained.
- Destructive confirmation cannot share the same destination as cancel/normal continue.

## Interaction-graph verification

The final Figma audit must export every reaction as `(source node, trigger, action, destination)` and prove:

1. all destinations resolve;
2. no non-utility self-loops;
3. no state frame is unreachable from a realistic entry;
4. no destination is a dead end;
5. every pane state exits;
6. every visible control is either wired or visibly non-interactive/maturity-disabled;
7. the 14 closure scenarios traverse only valid transitions above.
