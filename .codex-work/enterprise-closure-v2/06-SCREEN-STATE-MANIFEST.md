# ExecuteSpec Enterprise — Screen and State Manifest

Status: **Normative screen architecture; accepted Figma roots and flow evidence recorded in documents 19, 21 and 22**

## Naming

`EN-{breakpoint}-{family}{state}`

- Breakpoint: `D` desktop, `C` compact desktop, `T` tablet, `M` mobile.
- State: `A` normal/ready, `E` empty, `P` partial/stale/conflict, `D` denied, `F` failure, `B` blocked, `X` expired/invalidated/superseded, `R` recovery/reconciled.
- Example: `EN-M-17X` is mobile Plan review with invalidated approval.

Maturity badge is part of screen metadata and, for mixed surfaces, visible beside the affected control: `Current`, `Partial`, `Planned P0`, `P1`, `P2`, `Conditional`.

## Permanent product surfaces

There are only four persistent Enterprise destinations plus the Objective/Home context:

1. Home/Objectives in the left rail;
2. Intelligence;
3. Services;
4. Enterprise Settings.

Evidence, Verification, documents, decisions, actions, incidents and connector detail are contextual routes/panels inside those surfaces—not additional permanent navigation items.

## Core screen families

| Family | Purpose and PRD responsibility | Entry | Primary action | Secondary action | Denial/partial/failure | Recovery | Return | Breakpoint responsibility |
|---|---|---|---|---|---|---|---|---|
| EN-00 Neutral view choice | Studio/Enterprise isolation; X01/X02 trust | auth callback, expired/wrong-view link | Open authorised view | sign in/switch account | neutral withheld state; no product shell | request/reissue link | safe signed-in landing | D/C/T/M full |
| EN-01 Workspace setup | ONB-001, GOV-009 | Enterprise first entry | Create workspace | review policy/region | validation/permission failure | correct fields/retry | EN-10 | D/C/T full; M full form or secure resume |
| EN-02 Greenfield brief | ONB-002–003, INT-007 | new empty workspace | Save brief | connect a source | zero-info, incomplete, policy blocked | clarify/resume draft | EN-10/EN-03 | D/C/T/M full |
| EN-03 Bootstrap proposals | ONB-004–005, GRAPH-005 | saved greenfield brief | approve selected proposal | edit/reject/request alternative | conflicting/later source, approval expired | re-review changed proposal | EN-13 | D/C/T/M full decisions |
| EN-04 Guided connection | ONB-006 | onboarding checklist, Settings | Connect and validate | review requested permissions | OAuth denied, missing permission, timeout | retry/change account/narrow scope | EN-53/EN-10 | D/C/T full; M OAuth/status/recovery |
| EN-10 Home · Needs you | OBJ-003, S1 summary | Enterprise landing | open next required decision | create/link Objective, filter work | no ready work, source unavailable, permission-limited | connect/refresh/change scope | selected Objective EN-13 | D/C/T/M full |
| EN-11 Create/link Objective | OBJ-001 | New Objective, Jira/Confluence/Figma/SCM/CLI | create/link | save draft/cancel | duplicate, source denied, unsupported source state | resolve duplicate/request access/manual intent | EN-13 or EN-10 | D/C/T/M full |
| EN-12 Scope binding | OBJ-004, CTX-001 | source-linked Objective | confirm scope | change source/project/repo | ambiguous, missing permission, stale version | clarify/re-authorise/refresh | EN-14/EN-13 | D/C/T/M full |
| EN-13 Objective summary/thread | OBJ-002/007/008, FLOW-001 | Home, deep link, any Objective action | next lifecycle decision | ask/clarify/handoff/cancel | blocked, partial, cancelled, superseded | retry/create successor/hand off | Home or prior deep-link origin | D/C/T/M full |
| EN-14 Context review | CTX-001–008 | Objective Context, Inspect | accept/resolve context | add/refresh/compare source | partial, stale, conflicting, denied, unavailable, not-searched/no-result | refresh, change scope, request access, record authority decision | EN-13/EN-17 | D/C simultaneous; T drawer; M routes |
| EN-15 Structured brainstorm | DEC-001 | Objective decision need | produce review artifact | invite/ask for evidence/defer | insufficient evidence, denied source, session conflict | resume with scoped evidence | EN-16/EN-13 | D/C/T/M full; participant list collapses |
| EN-16 Decision record | DEC-002, DOC-003 | brainstorm, Objective Decisions | approve/publish decision | reject/change/supersede | approval denied/expired, write-back partial | re-approve/reconcile | EN-13/EN-25 | D/C/T/M full decision |
| EN-17 Plan review | PLAN-001–004 | Objective ready-to-plan, approval deep link | approve and start | reject/change scope/save draft | oversized warning, expired/invalidated/changed source | re-plan/re-authorise | EN-19 or EN-13 | D/C/T/M full approve/reject/change-scope |
| EN-18 Typed action detail | ACT-001–002 | any proposed consequential action | approve/confirm when allowed | change bounds/reject | permission/policy/precondition/stale block | re-check authority/re-scope | invoking surface | D/C/T/M full decision |
| EN-19 Working/recovery | ACT-003–005, OBJ-008 | approved plan/action | inspect current work | pause/cancel/handoff | blocked, failed, partial, superseded | resume/retry/re-plan/create successor | EN-13/EN-20 | D/C/T/M full control/recovery |
| EN-20 Verification workspace | VER-001–005,009–015 | Objective Verify, PR/CI, Service | run/continue required verification | edit plan, inspect topology/env, request exception | environment blocked, flaky, inconclusive, failed, partial, unmeasured, superseded | retry selected, fix env/test, rebind SHA, handoff | EN-22/EN-13 | D/C/T full; M plan/status/decision/detail routes |
| EN-21 Generated-test proof | VER-006–009 | missing coverage, failed regression | adopt proposal after review | edit/reject/quarantine | passes both, wrong baseline, test defect, secret/data issue | regenerate/rebind/review | EN-20 | D/C/T full; M evidence/decision, desktop edit handoff |
| EN-22 Evidence ledger | EVD-001/002/005/006, VER-013 | Objective Evidence, PR/CI, Service | inspect required proof | export/share/run missing verification | failed, stale, partial, inconclusive, unmeasured, permission-limited | rerun/request access/refresh | EN-13/EN-23 | D/C/T/M full summary and ledger drill-down |
| EN-23 Delivery/write-back | EVD-003–004, OBJ-007 | evidence ready | publish/open PR/write back | preview/share | pending, partial, failed, external change, revoked permission | retry/reconcile/change target | EN-13/EN-22 | D/C/T/M full status/recovery |
| EN-24 Document understanding | DOC-001 | document/objective/service query | inspect cited answer | create update/decision | stale/conflicting/denied/unable | refresh/change scope/request access | EN-13/EN-25 | D/C/T/M full read |
| EN-25 Cited document diff | DOC-002–003 | understand/update request | approve publication | edit/reject/change target | version conflict, authority denied, partial write-back | rebase/re-authorise/reconcile | EN-24/EN-13 | D/C/T full; M review/approve, desktop editing handoff |
| EN-26 Collaboration/handoff | OBJ-006, COL-001/002 | Objective/Intelligence | send explicit request | change recipient/context, cancel | recipient denied, Slack unavailable, link expired | redirect/resend/use Console | EN-13 | D/C/T/M full |
| EN-30 Intelligence query | INT-001/002/007 | semantic rail item, global Ask | run scoped query | recent/saved (P1), filters | no evidence, scope denied, source unavailable | narrow/expand authorised scope, bootstrap | EN-31/prior object | D/C/T/M full; org/saved labelled P1 |
| EN-31 Intelligence answer | INT-003–006 | completed query, entity Ask | inspect sources or create bounded action | compare conflict, feedback, save/share P1 | partial, stale, conflicting, denied, unable, timeout | refresh/change scope/request access | EN-30/invoking Objective | D/C/T/M full |
| EN-40 Services portfolio | OPS-001 | semantic rail item, entity link | open service | filter owner/health/env/readiness/incident | empty, partial catalogue, permission-limited | connect catalogue/change scope | EN-41/EN-10 | D/C/T/M full portfolio |
| EN-41 Service detail/live state | OPS-001–003 | portfolio, Objective link, Intelligence | inspect service/current evidence | open work/runbook/verification | live unavailable, stale, unknown, permission-limited | refresh live source/use history/handoff | EN-40/EN-42 | D/C/T/M full; no production mutation |
| EN-42 Incident investigation | OPS-003/004/005 | service incident, conditional external link | create/link remediation Objective or escalate | inspect evidence/draft learning | action blocked, source unavailable, write-back partial | handoff/reconcile/refresh | EN-41/EN-13 | D/C/T/M review/escalation; P1 Conditional, P2 mutation absent |
| EN-50 Settings home | six-group IA | semantic Settings item | open group | search settings | role-limited/partial loading | request role/retry | previous product context | D/C/T full; M status/critical paths |
| EN-51 Organisation/people/identity | GOV-001/002 | Settings group | save reviewed identity/role change | invite/export | permission denied, SSO validation failed | fix config/rollback change | EN-50 | D/C/T full; M critical approval/recovery |
| EN-52 Knowledge/Context Ops | CTX/GOV-005/007, GRAPH-006 | Settings group | inspect/recover context pipeline | reindex/quarantine/query quality P1 | stale, drift, failure, deletion impact | retry/rebuild/narrow scope | EN-50/EN-54 | D/C/T full; M status/handoff |
| EN-53 Integrations inventory | GOV-004, CONN-001–010 | Settings group | connect/open connector | filter health/capability | not connected, unhealthy, partial | validate/retry | EN-54/EN-50 | D/C/T full; M status/critical recovery |
| EN-54 Connector detail | ONB-006, CONN-001–010 | inventory, onboarding | connect/validate/save reviewed scope | rotate/narrow/disconnect | denied, rate limit, webhook/cursor drift, API drift, failed write | retry/reconcile/rotate | EN-53 | D/C/T full; M OAuth/status/recovery |
| EN-55 Governance and AI | GOV-008/009/010, MEM-005 | Settings group | review/apply allowed policy | inspect provider/data/security artifacts | route unavailable, quota/usage limit, permission denied | choose allowed route/request change/retry | EN-50 | D/C/T full; M critical route approval/recovery |
| EN-56 Environments/operations/CI | VER-004/011/015, CONN-008–010 | Settings group | validate runner/env/trigger policy | inspect callbacks/quotas | unavailable, SHA mismatch, callback failure | retry/reconcile/rebind | EN-20/EN-50 | D/C/T full; M status/recovery |
| EN-57 Billing and audit | GOV-003 | Settings group | export audit / manage approved billing action | filter/search | role denied, export failed | retry/request role | EN-50 | D/C/T full; M status/export recovery |
| EN-58 Personalisation/memory | MEM-001–005 | Settings group | save explicit preference | inspect/edit/disable/delete P1 inferred memory | policy conflict, private/team scope conflict | accept policy/correct/delete | EN-50 | D/C/T full; M preferences and critical privacy control |
| EN-59 Data lifecycle | GOV-006, GRAPH-006 | connector/Settings destructive zone | disconnect/export/purge after review | cancel before irreversible boundary | hold, partial purge, verification failed | retry remaining stores/escalate | EN-54/EN-50 | D/C/T full; M approval/status/recovery |
| EN-60 External deep link | cross-channel continuity | Jira/Confluence/Figma/Slack/PR/CI | open exact authorised object/action | change account/view | expired, wrong view, denied, source changed | re-authorise/reissue/return safe | originating app or EN-10 | D/C/T/M full |
| EN-61 Secure device handoff | responsive continuity | mobile/tablet desktop-required action | create/open continuation | revoke/copy link | expired, revoked, source version changed | issue new token/re-review | original device/surface | T/M origin + D/C destination |
| EN-62 Reconciliation detail | EVD-004 | delivery/connector failure | reconcile exact external effect | retry/cancel/mark externally resolved with evidence | uncertain result, duplicate, revoked access | re-read authoritative source/replay outbox/handoff | invoking Objective/Settings | D/C/T/M full status/recovery |

## Requirement-to-family binding

| PRD IDs | Required families |
|---|---|
| ONB-001–006 | EN-01–04, EN-10 |
| OBJ-001–008 | EN-10–14, EN-17, EN-19, EN-23, EN-26 |
| CTX-001–008 | EN-12–14, EN-30–31, EN-52 |
| DEC-001–002; PLAN-001–004 | EN-15–18 |
| ACT-001–006 | EN-18–19, EN-41–42 |
| VER-001–015 | EN-20–22, EN-54, EN-56 |
| EVD-001–006 | EN-22–23, EN-62 |
| INT-001–008 | EN-30–31, EN-52 |
| GRAPH-001–006 | EN-03, EN-14, EN-31, EN-41, EN-52, EN-59 |
| DOC-001–005 | EN-16, EN-24–25, EN-42 |
| COL-001–003; FLOW-001–002 | EN-13, EN-26, EN-31; P1 Flow within Intelligence |
| OPS-001–005 | EN-40–42 |
| CONN-001–010 | EN-04, EN-53–56, EN-59 |
| MEM-001–005 | EN-55, EN-58 |
| GOV-001–010 | EN-00, EN-51–59, EN-62 |

## Representative prototype state set

The playable prototype must instantiate at least these state variants, with desktop/tablet/mobile nodes where required by the breakpoint column:

1. zero-information setup and proposal approval;
2. source-linked Objective and ambiguous scope;
3. context complete, partial/stale/conflicting, denied, unavailable, no-result and not-searched;
4. plan normal, rejected, change-scope, expired and invalidated;
5. action running, paused, blocked, failed, recovered, cancelled and superseded;
6. verification planning, environment blocked, executing, flaky, inconclusive, failed, passed, partial and unmeasured;
7. generated regression proof pass and passing-on-both inconclusive;
8. delivery/write-back pending, partial, failed, retry, reconciled and externally changed;
9. Intelligence complete, partial, denied, conflicting, stale, unable and greenfield unknown;
10. service live, stale, unavailable and active incident read-only;
11. connector first connection, healthy, permission drift, disconnected and purge lifecycle;
12. model route unavailable and usage limit;
13. authorised, denied, expired and wrong-view deep links;
14. secure mobile-to-desktop handoff created, expired, source-changed and completed.

## Manifest gate

No P0 user-facing requirement is allowed into design-to-code unless at least one family above owns its primary behavior and all required state/breakpoint responsibilities are present. A Figma frame count is not evidence of coverage; the named transitions and state contracts are.
