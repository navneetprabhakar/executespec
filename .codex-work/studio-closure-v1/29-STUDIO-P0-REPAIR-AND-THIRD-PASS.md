# ExecuteSpec Studio — P0 Repair and Strict Third Pass

Date: 2026-07-24  
Checkpoint: `STUDIO-CP-016`  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>  
Scope: Studio product/UX, Figma, traceability, and checkpoint documents only  
Repository implementation: **not authorised and not modified by this repair pass**

## 1. Verdict

The six design blockers recorded in document 27 now have explicit repair evidence. The new evidence
does not rely on capability names, summary counts, or a repeated generic decision shell.

- P0 design blockers: **0 open**.
- P1 product/interaction debt: **6 open**.
- P2 visual/content refinements: **7 open**.
- Runtime accessibility, authorization, redaction, state-machine parity, and deterministic
  `AT-<requirement-id>` execution remain implementation gates, not claims made by Figma.
- Studio is materially closer to an implementation-ready product definition, but application coding
  remains blocked until the user explicitly accepts the UX and authorises a named implementation
  scope.

## 2. Additive repair surfaces

### Component repair section

- `94:7680` — `P8 / P0 Repair Components`.
- `96:132` — `WorkspaceNavigation`, 4 variants.
- `98:110` — `LensSwitcher`, 4 variants.
- `99:152` — `OutputSwitcher`, 6 variants.
- `101:420` — `ArtifactInspector`, 30 variants.
- `102:284` — `TrustAction`, 15 variants.
- `104:396` — `ResponsiveSurface`, 14 variants.

The repair adds 6 component sets and 73 variants. Existing accepted components and screens were
retained. New screens use instances from these sets rather than detached lookalikes.

### Desktop repair sections

- `94:7681` — builder and Output repair evidence.
- `94:7682` — navigation, trust, and Account repair evidence.

| Screen | Node | Purpose |
|---|---:|---|
| R01 / Home / Projects and tasks | `108:7666` | Projects, durable Tasks, statuses, lenses, and Account entry |
| R02 / Builder / Preview selection | `108:7727` | working runtime Preview, selected object, direct controls, interaction trace |
| R03 / Change review / Before after | `108:7814` | synchronized before/after comparison and evidence links |
| R04 / Output / Preview | `108:7898` | runtime and selection inspector |
| R05 / Output / Changes | `108:7973` | changed objects, selected diff, before/after, evidence |
| R06 / Output / Data | `108:8046` | schema, records, access rules, and function evidence |
| R07 / Output / Checks | `108:8118` | assertions, failure trace, capture, and coverage |
| R08 / Output / Release | `108:8190` | target, manifest, timeline, and recovery anchor |
| R09 / Output / Evidence | `108:8263` | claim ledger, source, freshness, authority, and download |
| R10 / Trust / Pending | `108:8336` | field-complete bounded decision before action |
| R11 / Trust / Expired | `108:8414` | invalidated decision and explicit no-action state |
| R12 / Trust / Confirmed | `108:8488` | immutable receipt and recovery anchor |
| R13 / Account / Six groups | `108:8561` | stable six-group Account information architecture |

### Responsive repair sections

- `94:7683` — six tablet states: `109:8168`, `109:8205`, `109:8233`, `109:8256`,
  `109:8278`, `109:8309`.
- `94:7684` — eight mobile states: `109:8341`, `109:8358`, `109:8395`, `109:8429`,
  `109:8452`, `109:8475`, `109:8497`, `109:8529`.

The responsive set demonstrates Project/Task drawers, Output side/bottom sheets, Task-versus-Output
switching, full Preview, Compare, bounded decisions, Account, keyboard resizing, and orientation
change. State notes explicitly preserve project, task, selection, focus, authority, freshness, and
recovery context.

## 3. P0 closure matrix

| Finding | Trigger | Inspectable object | Decision/outcome | Failure/recovery | Evidence | Result |
|---|---|---|---|---|---|---|
| P0-01 builder absent | open running Task or select Preview object | realistic checkout Preview, selected CTA, properties, runtime trace | direct edit remains scoped to selected object and produces a reviewable Change | comparison retains selection and links to checks | R02 `108:7727`, R03 `108:7814`, Preview inspector `101:74` | closed at design level |
| P0-02 Output generic | choose Preview/Changes/Data/Checks/Release/Evidence | six purpose-built artifact anatomies | open inspector, compare, download, or follow exact evidence | each mode has Empty, Loading, Error, and Stale variants | R04–R09 plus `99:152` and `101:420` | closed at design level |
| P0-03 navigation absent | open Home/Project/Task or switch lens | Projects, running/blocked/completed/archived Tasks, Build/Data/Checks/Ship, Account | resume durable Task without reconstructing context | collapsed navigation retains official icon affordances; blocked Task remains explicit | R01, R13, `96:132`, `98:110`, T01, M02, M07 | closed at design level |
| P0-04 trust incomplete | start billable/external/privilege/destructive/irreversible action | target, effect, unchanged scope, authority, freshness, cost, expiry, invalidators, recovery | pending confirmation or immutable confirmed receipt | expired state performs no action and requires refreshed evidence | R10–R12 and `102:284` | closed at design level |
| P0-05 responsive transitions absent | open drawer, sheet, Preview, Compare, decision, Account, keyboard/orientation state | one primary responsive surface with explicit overlay/sheet contract | next state preserves task/selection/focus/authority | explicit exit, dismiss, cancel, refresh, and return paths | T01–T06, M01–M08, `104:396` | closed at design level |
| P0-06 closure evidence invalid | launch one of three named prototype flows | exact screen node, component instance, target, reaction, and destination | 27 deterministic next transitions across desktop/tablet/mobile | destinations cycle to a valid start; no broken route | three flow starts; 27 reaction nodes; this document and document 02 overlay | closed for P0 design handoff |

## 4. Component state coverage

`ArtifactInspector` provides 30 variants:

- Kind: Preview, Changes, Data, Checks, Release, Evidence.
- State: Default, Empty, Loading, Error, Stale.

`TrustAction` provides 15 variants:

- Kind: Billable, External, Privilege, Destructive, Irreversible.
- State: Pending, Expired, Confirmed.

`ResponsiveSurface` provides 14 variants:

- Tablet: Project drawer, Output side sheet, full Preview, Compare, decision sheet,
  orientation/keyboard.
- Mobile: Task, Project drawer, Output switcher, Checks sheet, full Preview, Compare, Account,
  decision sheet.

## 5. Prototype and structural validation

The final Figma verifier reported:

- 27 repair screens: 13 desktop, 6 tablet, 8 mobile;
- 58 component instances and 808 text nodes across the repair screens;
- 0 missing fonts;
- 27 prototype controls, 27 valid reactions, and 0 broken destinations;
- 3 new CP-016 flow starts: desktop `108:7666`, tablet `109:8168`, mobile `109:8341`;
  the 17 accepted earlier starts were restored and retained, so the file now has 20 named starts;
- 0 direct children outside their screen bounds;
- prototype controls no smaller than 44 px high;
- 31 mobile product controls inspected after transform, minimum 88 × 44 px, 0 failures.

The mobile touch-target repair changed the real Output tabs, Project rows, and action controls in
`ResponsiveSurface`; it was not limited to the presentation-only next control.

## 6. Remaining P1 debt

1. Brief and plan review still need editable assumptions, acceptance intents, citations, and
   object-level impact.
2. Repository, scope, CI, deployment, client review, and handoff need deeper professional working
   views beyond the new core Output anatomy.
3. Some older catalogue screens still use a repeated decision-card template and competing universal
   composer; the P0 repair screens establish the replacement direction but do not rewrite all 143
   earlier compositions.
4. Long-running external operations still need broader reconnecting, partial-result, and
   reconciliation examples outside the six core Output modes.
5. Tier depth still needs stronger object-level differentiation; copy, badges, and density are not
   sufficient Professional/Max value on their own.
6. Account now has the correct six-group entry surface, but each group still needs its full detailed
   workflow before implementation handoff is final.

## 7. Remaining P2 and implementation gates

The seven document-27 refinements remain useful: metadata scale/contrast, dark-surface hierarchy,
replacement of old Kitchen Planner examples, stale action copy, casual-language cleanup, old empty
acreage, and contextual gamification.

Figma does not establish runtime keyboard order, accessible names/roles/live regions, focus traps,
reduced motion, 200% zoom/reflow, localization expansion, production authorization, redaction,
durable-state reconciliation, or responsive state-machine parity. Those remain deterministic
implementation acceptance work after explicit coding authorisation.

## 8. Scope boundary

This checkpoint changed Figma and UX/checkpoint documentation only. It did not modify ExecuteSpec
application source, migrations, configuration, CI, generated clients, package manifests, or
repository architecture. Existing unrelated worktree changes remain untouched.
