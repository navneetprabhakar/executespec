# ExecuteSpec Studio — P1 Closure and Strict Fourth Pass

Date: 2026-07-24  
Checkpoint: `STUDIO-CP-017`  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>  
Scope: Studio product/UX, Figma, traceability, evidence, and checkpoint documents only  
Repository implementation: **not authorised and not modified**

## 1. Verdict

The six P1 product/interaction gaps recorded at CP-016 now have direct, inspectable design evidence.
The strict fourth pass found:

- P0 design blockers: **0 open**;
- P1 product/interaction blockers: **0 open**;
- P2 visual/content refinements: **7 open**;
- runtime accessibility, authorization, redaction, durable reconciliation, responsive state-machine
  parity, and deterministic `AT-<requirement-id>` execution remain implementation gates.

This is design-level closure only. It is not permission to modify application source, migrations,
configuration, CI, generated clients, package manifests, or repository architecture.

## 2. Added component authority

Component section: `115:74` — `P9 / P1 Closure Components`.

| Component set | Node | Variants | Material axes |
|---|---:|---:|---|
| `BriefPlanReview` | `115:402` | 6 | Brief/Plan × Editable/Conflict/Approved |
| `ProfessionalObjectView` | `116:337` | 6 | Repository, Scope, CI, Deployment, ClientReview, Handoff |
| `OperationMonitor` | `117:308` | 6 | Running, Reconnecting, Partial, Reconciled, Failed, Cancelled |
| `TierObjectDepth` | `118:293` | 3 | Free, Pro, Max |
| `AccountWorkflow` | `119:416` | 6 | Account, AIUsage, SourcesLocal, DataSecrets, PublishDomains, PrivacyOwnership |
| `P1ResponsiveDetail` | `128:262` | 6 | Tablet/Mobile × Brief/Operation/Account |

The six sets add 33 variants. Structural validation found 0 missing fonts and 0 unbound solid
fills. The mobile responsive actions are 44 px high.

## 3. Added desktop screen authority

### Brief, Plan, and professional workbenches

Section `122:8476` — `P9.A / P1 Closure / Brief Plan Professional`.

| Screen | Node | Inspectable job |
|---|---:|---|
| R14 / Brief / Editable assumptions | `122:8477` | edit outcome, assumption, acceptance intent, citations, and object impact |
| R15 / Brief / Citation conflict | `122:8611` | block approval until conflicting cited evidence is resolved |
| R16 / Plan / Approved impact | `122:8730` | freeze bounded steps, verification, object impact, and approval receipt |
| R17 / Professional / Repository context | `122:8865` | pinned provider/repository/branch/commit, repository map, freshness, and affected objects |
| R18 / Professional / Scope baseline | `122:8995` | included/excluded scope, accepted baseline, budget, estimate, and change request |
| R19 / Professional / CI validation | `122:9117` | pipeline stages, failing assertion, logs, and bounded repair/rerun route |
| R20 / Professional / Deployment | `122:9224` | target, immutable manifest, canary timeline, health, and rollback anchor |
| R21 / Professional / Client review | `122:9356` | bounded package visibility, reviewer expiry, anchored comment, and decision |
| R22 / Professional / Handoff | `122:9472` | delivery bundle, ownership, credential handling, access revocation, and maintenance boundary |

### External operations and tier depth

Section `124:9349` — `P9.B / P1 Closure / Operations and Tiers`.

- R23–R28: `124:9350`, `124:9455`, `124:9561`, `124:9667`, `124:9788`,
  `124:9894`.
- R29–R31: `124:9999`, `124:10154`, `124:10309`.

The operation sequence distinguishes transient interruption, inspectable partial output, successful
reconciliation, hard failure, and user cancellation. Every state identifies the durable checkpoint,
remote effect, next action, and recovery path. Tier depth changes real Source, Check, Release,
Evidence, ClientWorkspace, CI, and Handoff capabilities while retaining the same Project and Task.

### Account workflows

Section `126:10202` — `P9.C / P1 Closure / Account Workflows`.

- R32–R37: `126:10203`, `126:10344`, `126:10485`, `126:10626`, `126:10766`,
  `126:10907`.

The six detailed jobs cover identity/preferences, AI/provider route/usage/receipts, GitHub and
Bitbucket plus local permissions, storage/retention/write-only secrets, publish/domain defaults and
rollback, and privacy/ownership/export/deletion. Consequential settings retain the same Decision,
receipt, re-authentication, and recovery grammar used in the work surface.

## 4. Responsive authority

- Tablet section `129:10813`: T07 `129:10814`, T08 `129:10872`, T09 `129:10940`.
- Mobile section `129:11003`: M09 `129:11004`, M10 `129:11048`, M11 `129:11093`.

Tablet retains one persistent rail and opens Output as a side sheet. Mobile shows one primary
surface, uses the existing 68 px bottom navigation, and exposes 44 px next and primary-action
targets. Brief conflict, reconnecting operation, and Account state preserve Task, selection,
authority, checkpoint, decision, and recovery context across surface changes.

## 5. Older catalogue authority correction

All 18 P3–P5 sections were retained but marked visibly and structurally as `reference-only`.
Each now contains an `Authority / Reference only` banner directing implementation to P8/P9.
No accepted historical screen was deleted or renamed.

This closes the conflict between the repeated older DecisionCard/universal-composer examples and
the current builder, inspectable Output, professional object, operation, tier, and Account designs.

## 6. P1 closure matrix

| CP-016 P1 finding | Exact evidence | Result |
|---|---|---|
| editable Brief/Plan assumptions, acceptance intent, citations, and object impact | `115:402`; R14–R16 `122:8477`, `122:8611`, `122:8730`; tablet/mobile `129:10814`, `129:11004` | closed at design level |
| deeper repository, scope, CI, deployment, client-review, and handoff workbenches | `116:337`; R17–R22 `122:8865`–`122:9472` | closed at design level |
| older catalogue competes with current implementation authority | 18 reference-only section flags and banners `127:10813`–`127:10864`; P8/P9 remain implementation authority | closed |
| reconnecting, partial-result, and reconciliation examples for external work | `117:308`; R23–R28; T08 `129:10872`; M10 `129:11048` | closed at design level |
| object-level Free/Pro/Max differentiation | `118:293`; R29–R31 `124:9999`, `124:10154`, `124:10309` | closed at design level |
| six complete Account workflows | `119:416`; R32–R37; T09 `129:10940`; M11 `129:11093` | closed at design level |

## 7. Prototype and structural validation

The P1 pass added 30 screens: 24 desktop, 3 tablet, and 3 mobile.

- 126 component instances and 1,801 text nodes across the 30 screens;
- 0 missing fonts;
- 0 direct children outside screen bounds;
- 30 visible next controls with valid destinations;
- 0 broken destinations;
- whole Studio page: 20 named flow starts, 129 reaction nodes, 129 distinct edges, 0 broken
  destinations;
- the existing desktop, tablet, and mobile P8 starts now continue through the P9 flows and return
  to their valid starts;
- all tested tablet/mobile next and primary actions meet the 44 px minimum.

## 8. Fresh visual evidence

- `evidence/studio-p1-cp017-p9a-brief-professional.png`
- `evidence/studio-p1-cp017-p9b-operations-tiers.png`
- `evidence/studio-p1-cp017-p9c-account.png`
- `evidence/studio-p1-cp017-p9d-tablet.png`
- `evidence/studio-p1-cp017-p9e-mobile.png`

All five section renders were captured after the final mutations and visually inspected. The dark
palette, three-pane desktop hierarchy, tablet rail, mobile single-surface grammar, spacing, and
component scaling are consistent with the accepted P8 direction.

## 9. Remaining P2 and implementation gates

The seven CP-016 refinements remain: metadata scale/contrast, dark-surface hierarchy, replacement
of old Kitchen Planner examples, stale-action copy, casual-language cleanup, old empty acreage, and
contextual gamification. They do not block the repaired P1 jobs, but they should be resolved before
visual freeze if the user wants a zero-P2 design package.

Figma still does not prove runtime keyboard order, accessible names/roles/live regions, focus traps
and return, reduced motion, 200% zoom/reflow, localization expansion, authorization, redaction,
durable-state reconciliation, responsive state-machine parity, or deterministic
`AT-<requirement-id>` execution.

## 10. Scope boundary

This checkpoint changed Figma and UX/checkpoint evidence only. ExecuteSpec application source,
migrations, configuration, CI, generated clients, package manifests, and repository architecture
were not modified.
