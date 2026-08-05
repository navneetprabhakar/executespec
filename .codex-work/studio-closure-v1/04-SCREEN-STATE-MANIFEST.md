# ExecuteSpec Studio — Screen and State Manifest

Version: 0.1 logical inventory  
Date: 2026-07-23  
Status: **Initial exhaustive inventory; consolidation and requirement mapping pending**  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>

## 1. Representation rule

Each logical state below must have one of these dispositions:

- **Frame:** distinct accepted Figma frame because layout, consequence, or navigation materially changes.
- **Variant:** canonical component/state within a parent frame, inspectable and prototype-reachable.
- **Non-visual:** behavior or invariant proven through annotation/test; never used to hide a real UX state.
- **Deferred:** P1/P2 with explicit rationale and no P0 dependency.

Interactive, authority, failure, recovery, entitlement, redaction, and responsive states cannot be closed as prose-only.

## 2. Shared and casual Studio states

| ID | State | Tier | Expected representation |
|---|---|---|---|
| S01 | Home — zero information / first visit | All | Frame |
| S02 | Home — returning user / continue work | All | Frame |
| S03 | Home — template, import, duplicate choices | All | Frame |
| S04 | Brief — one focused clarification | All | Frame |
| S05 | Task — Ready / interpreted outcome | All | Frame |
| S06 | Task — Working / Understand | All | Variant/Frame |
| S07 | Task — Working / Build | All | Variant/Frame |
| S08 | Task — Working / Verify | All | Variant/Frame |
| S09 | Build — default live Preview | All | Frame |
| S10 | Build — selected element context | All | Frame |
| S11 | Build — direct property/content edit | All | Frame |
| S12 | Build — conversational change with live Output | All | Frame |
| S13 | Changes — product summary and disclosure | All | Frame |
| S14 | Version — checkpoint created | All | Variant/Frame |
| S15 | Versions — history timeline | All | Frame |
| S16 | Versions — compare two checkpoints | Pro/Max | Frame |
| S17 | Versions — restore/reconcile decision | All | Frame |
| S18 | Data — resource overview | All | Frame |
| S19 | Data — schema/table editor | All | Frame |
| S20 | Data — app users and sign-in | All | Frame |
| S21 | Data — records and access rules | All | Frame |
| S22 | Data — storage and media | All | Frame |
| S23 | Data — functions and automation | Pro/Max | Frame |
| S24 | Data — integration connection | Pro/Max | Frame |
| S25 | Data — secret/permission boundary | All | Frame |
| S26 | Checks — overview / readiness | All | Frame |
| S27 | Checks — generating tests | All | Variant/Frame |
| S28 | Checks — running tests | All | Variant/Frame |
| S29 | Checks — passed / fresh evidence | All | Frame |
| S30 | Checks — failed / affected behavior | All | Frame |
| S31 | Checks — bounded repair decision/result | All | Frame |
| S32 | Checks — flaky, partial, inconclusive, unmeasured | All | Frame |
| S33 | Checks — runner/source unavailable | All | Frame |
| S34 | Ship — destination overview | All | Frame |
| S35 | Ship — Preview configuration | All | Frame |
| S36 | Ship — publish consequence Decision | All | Frame |
| S37 | Ship — publishing/deploying | All | Variant/Frame |
| S38 | Ship — healthy Release | All | Frame |
| S39 | Ship — failed/partial publish | All | Frame |
| S40 | Ship — degraded health / needs attention | Pro/Max | Frame |
| S41 | Ship — Release history | All | Frame |
| S42 | Ship — rollback and post-rollback evidence | All | Frame |
| S43 | Share — create scoped link | All | Frame |
| S44 | Share — active, expired, revoked, denied links | All | Frame |
| S45 | Share — remix/licence decision | Free/Pro | Frame |
| S46 | Ownership — source/data export | All | Frame |
| S47 | Progress — verified milestone | All opt-in | Variant/Frame |
| S48 | Progress — skill/quest state | All opt-in | Frame |
| S49 | Progress — celebration/learning preferences | All | Frame |
| S50 | Graduation — enable Professional Mode | Pro→Max | Frame |
| S51 | Graduation — source ownership/export readiness | All | Frame |
| S52 | Graduation — data/docs/evidence package | All | Frame |
| S53 | Account — plan and usage overview | All | Frame |
| S54 | Entitlement — contextual upgrade at intent | Free/Pro | Frame |
| S55 | Entitlement — deliberate plan comparison | All | Frame |
| S56 | Entitlement — hard limit / safe read-only | Free/Pro | Frame |
| S57 | Entitlement — downgrade consequences | Pro/Max | Frame |
| S58 | Account — privacy, retention, export, delete | All | Frame |
| S59 | Account — providers/connections/permissions | Pro/Max | Frame |
| S60 | Safety — destructive Project action | All | Frame |
| S61 | Continuity — offline/reconnect/resume | All | Frame |
| S62 | Continuity — authorization expired | All | Frame |
| S63 | Continuity — source unavailable/reconnect | All | Frame |
| S64 | Continuity — UI and durable Task mismatch | All | Frame |

## 3. Professional and Max states

| ID | State | Expected representation |
|---|---|---|
| P01 | Professional Mode — value and boundary introduction | Frame |
| P02 | Professional onboarding — import existing repository | Frame |
| P03 | Professional onboarding — local-hands handoff | Frame |
| P04 | Professional onboarding — guided capability check | Frame |
| P05 | Professional onboarding — existing Studio graduation summary | Frame |
| P06 | Client workspaces — overview | Frame |
| P07 | Client workspace — create and define isolation | Frame |
| P08 | Client boundary — persistent identity indicator | Variant/Frame |
| P09 | Client workspace — safe switching | Frame |
| P10 | Client workspace — offboarding | Frame |
| P11 | Client boundary — leakage-prevention block | Frame |
| P12 | Source — connect GitHub | Frame |
| P13 | Source — connect Bitbucket Cloud | Frame |
| P14 | Source — select repository and permissions | Frame |
| P15 | Source — bind local workspace | Frame |
| P16 | Source — branch/PR policy | Frame |
| P17 | Source — dirty working tree safety | Frame |
| P18 | Source — multi-repository Project | Frame |
| P19 | Source — denied, unavailable, reconnect | Frame |
| P20 | Context — repository map | Frame |
| P21 | Context — Project Brief | Frame |
| P22 | Context — connected documents/design/issues | Frame |
| P23 | Context — project-scoped Intelligence answer | Frame |
| P24 | Context — partial/stale/conflicting/unavailable | Frame |
| P25 | Scope — requirement intake | Frame |
| P26 | Scope — clarification | Frame |
| P27 | Scope — estimate range/risk/cost | Frame |
| P28 | Scope — proposed baseline | Frame |
| P29 | Scope — accepted baseline | Frame |
| P30 | Scope — divergence detected | Frame |
| P31 | Scope — change request accept/reject | Frame |
| P32 | Plan — review tasks, dependencies, tests | Frame |
| P33 | Plan — approval with bounded authority | Frame |
| P34 | Plan — local/cloud execution options | Frame |
| P35 | Plan — provider/model/BYOK selection | Frame |
| P36 | Plan — budget/time cap | Frame |
| P37 | Plan — rejected/expired/invalidated | Frame |
| P38 | Plan — route/provider unavailable or policy-blocked | Frame |
| P39 | Execute — implementation working | Frame |
| P40 | Execute — changes/diff inspection | Frame |
| P41 | Review — cited findings | Frame |
| P42 | Review — bounded autofix authority/result | Frame |
| P43 | Execute — paused/resume | Frame |
| P44 | Execute — partial/recovered/superseded | Frame |
| P45 | Verify — generated test plan/suite | Frame |
| P46 | Verify — suite validation | Frame |
| P47 | Verify — integration environment/configuration | Frame |
| P48 | Verify — CI automatic trigger | Frame |
| P49 | Verify — CI result/failure | Frame |
| P50 | Verify — on-demand trigger | Frame |
| P51 | Verify — flaky/retry/quarantine decision | Frame |
| P52 | Verify — cited evidence ledger | Frame |
| P53 | Deliver — Preview/staging/production targets | Frame |
| P54 | Deliver — deploy consequence Decision | Frame |
| P55 | Deliver — deploying | Variant/Frame |
| P56 | Deliver — Release and health | Frame |
| P57 | Deliver — failure/reconciliation | Frame |
| P58 | Deliver — rollback | Frame |
| P59 | PR — created and checks state | Frame |
| P60 | Client review — share package | Frame |
| P61 | Client review — reduced decision-only view | Frame |
| P62 | Client review — changes requested | Frame |
| P63 | Client review — accepted | Frame |
| P64 | Handoff — delivery package | Frame |
| P65 | Docs — generate explanation/setup | Frame |
| P66 | Docs — architecture/operations/source context | Frame |
| P67 | Reuse — template/checklist/pattern library | Frame |
| P68 | Memory — sanitized promotion Decision | Frame |
| P69 | Docs/reuse — export | Frame |
| P70 | Cost — pre-action estimate | Frame |
| P71 | Cost — live attributable usage | Frame |
| P72 | Cost — Project/client/provider attribution | Frame |
| P73 | Cost — cap reached / continuation Decision | Frame |
| P74 | Cost — BYOK error, fallback, or blocked route | Frame |
| P75 | Collaboration — invite scoped reviewer | Frame |
| P76 | Collaboration — comments and notifications | Frame |
| P77 | Collaboration — decision-only participation | Frame |
| P78 | Collaboration — revoke access | Frame |
| P79 | Safety — client privacy and retention | Frame |
| P80 | Safety — credentials and secret boundaries | Frame |
| P81 | Safety — retention/deletion policy | Frame |
| P82 | Safety — destructive professional action | Frame |
| P83 | Safety — client/source boundary mismatch | Frame |
| P84 | Safety — offboarding evidence | Frame |
| P85 | Safety — client export/deletion complete | Frame |
| P86 | Graduation — Team/Enterprise intent | Frame |
| P87 | Graduation — identity/object mapping | Frame |
| P88 | Graduation — policy/conflict resolution | Frame |
| P89 | Graduation — completed with preserved history | Frame |

## 4. Responsive representation priorities

All P0 logical states require responsive rules. Separate accepted frames are mandatory where layout or task capability materially changes.

### Tablet frame minimum

- S01/S02 Home
- S04 Brief
- S05–S08 Task states
- S09 Build Preview
- S10 selected element
- S15 history
- S18 resource overview
- S25 secret/permission
- S26/S30 Checks overview/failure
- S36 publish Decision
- S38/S42 Release/rollback
- S54/S56 entitlement intent/limit
- S61–S64 continuity
- P09 client switch
- P17 dirty workspace
- P28 scope
- P33 approval
- P40 changes
- P49 CI failure
- P61 client decision-only review
- P83 boundary mismatch

### Mobile frame minimum

- S01/S02 Home
- S04 clarification
- S05 Ready
- S07 Working
- S08 Needs input/Verify
- S09 Task-first Build
- S09 Output-first Preview
- S13 Changes summary
- S15 history
- S18 resources
- S25 secret/permission Decision
- S26/S30/S31 Checks pass-fail-repair
- S34 Ship
- S36 publish Decision
- S37 deploying
- S38 healthy
- S39 failed
- S42 rollback
- S43 share
- S54 upgrade intent
- S56 hard limit
- S60 destructive action
- S61 offline/resume
- S62 authorization expired
- S63 source unavailable
- S64 durable-state mismatch
- P08 client boundary
- P09 client switch
- P31 change request
- P33 approval
- P43 pause/resume
- P49 CI failure
- P61 client reviewer
- P73 cost cap Decision
- P83 client mismatch

Mobile does not need dense source editing, full multi-file diff, or complex schema manipulation parity. It must provide an honest desktop/CLI/IDE handoff without losing the Task or Decision.

## 5. Consolidation targets

The logical inventory currently contains 153 states. That is not automatically 153 desktop frames.

Before Figma production:

1. Collapse states that are canonical component variants within the same composition.
2. Keep distinct frames for authority, failure, recovery, entitlement, client boundary, and responsive task changes.
3. Record every collapse in the traceability matrix so coverage is not lost.
4. Set the accepted frame count only after the row-level requirement audit.

No arbitrary screen-count target is imposed. Completeness and comprehensibility matter more than matching the Enterprise frame count.

