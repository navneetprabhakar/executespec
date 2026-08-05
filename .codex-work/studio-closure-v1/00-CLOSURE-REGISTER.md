# ExecuteSpec Studio — Product and UX Closure Register

Date opened: 2026-07-23  
Status: **P0/P1 design blockers closed at CP-017; 7 P2 refinements and runtime gates remain; coding not authorised**  
Product surface: `studio.executespec.ai`  
Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>  
Figma file key: `LfEwxST9vagBMNJdVWn23L`

## 1. Closure objective

Produce one implementation-ready Studio product and UX package for individual users while retaining two progressively disclosed jobs:

- casual builder / vibe coder;
- professional individual / freelancer / solo founder.

Studio remains one product, one project model, one route tree, and one interaction grammar. Professional depth appears inside the same shell; it does not create another application.

## 2. Product boundary

| Surface | Primary user | Canonical URL | Product object | Default mental model |
|---|---|---|---|---|
| Studio | Individual users | `studio.executespec.ai` | Project + Task + Output | make, verify, publish, recover |
| Enterprise Console | Organisations and teams | separate Enterprise URL | Objective + context + action + evidence | coordinate governed software work |

Shared backend contracts do not justify a shared UX shell. Studio may reuse identity, billing, agent execution, model routing, verification, preview/deployment, versioning, connectors, evidence, and analytics without exposing Enterprise information architecture.

## 3. Existing authority inventory

### Persona product authorities

- `other-solutions/casual-vibe-coder/CASUAL-VIBE-CODER-IMPLEMENTATION-PRD.md` — 81 `STU-*` requirements.
- `other-solutions/professional-individual/PROFESSIONAL-INDIVIDUAL-IMPLEMENTATION-PRD.md` — 92 `PRO-*` requirements.
- Their coding-agent delivery guides remain implementation companions.

### UX and packaging authorities

- `STUDIO-APPLE-LOGIC-TIERED-UX.md`
- `STUDIO-AGENT-FOCUSED-UX-VARIATIONS.md`
- `STUDIO-CODEX-STYLE-AGENT-UX.md`
- `STUDIO-SINGLE-USER-UX-WIREFRAMES.md`
- `STUDIO-EXHAUSTIVE-FEATURE-CATALOG.md`
- `STUDIO-FEATURE-PLACEMENT-AND-SETTINGS-IA.md`
- `STUDIO-GROUPED-SETTINGS-IA.md`

### Shared authorities

- `shared/PRODUCT-PORTFOLIO.md`
- `shared/GLOSSARY-AND-OBJECT-MODEL.md`
- `shared/CURRENT-STATE-PARITY-AND-SHARED-OBJECT-AUDIT.md`
- Enterprise PRD only where Studio explicitly reuses safety, authority, evidence, SCM, CI, or delivery contracts.

## 4. Closure document set

| Document | Purpose | State |
|---|---|---|
| `00-CLOSURE-REGISTER.md` | authority, scope, gates, and progress | complete |
| `01-STUDIO-MASTER-PRD.md` | one Studio authority spanning Free, Pro, Max and both individual personas | final implementation authority |
| `02-PRD-TRACEABILITY-MATRIX.md` | 173 requirement IDs to journeys, screens, states, actions, analytics, and tests | 173/173 audited with exact node evidence |
| `03-UX-FOUNDATION-CONTRACTS.md` | shell, objects, language, density, entitlement, and safety contracts | final baseline |
| `04-SCREEN-STATE-MANIFEST.md` | exhaustive desktop/tablet/mobile screen and state inventory | complete |
| `05-FIGMA-PHASE-0-GAP-ANALYSIS.md` | source-to-Figma gap assessment and mutation boundary | complete for start state |
| `06-SESSION-HANDOFF-AND-CHECKPOINT.md` | resumable checkpoint | complete at STUDIO-CP-018 |
| `07-CURRENT-STATE-PARITY-AUDIT.md` | existing/partial/planned/unsupported capability audit | complete |
| `08-STUDIO-DECISIONS-AND-CONFLICTS.md` | resolved product, tier, and UX decisions | complete |
| `09-AUTHORITY-EFFECT-RECOVERY-MATRIX.md` | consequential action authority and recovery contracts | complete |
| `10-RESPONSIVE-RULES.md` | desktop, tablet, and mobile behavior contract | complete |
| `11-COMPONENT-AND-VARIANT-INVENTORY.md` | required component and state variants | complete |
| `12-PROTOTYPE-TRANSITION-MAP.md` | required navigation and recovery graph | complete and verified |
| `13-ANALYTICS-AND-ACCEPTANCE-TESTS.md` | redacted events and deterministic acceptance obligations | complete |
| `14-FIGMA-PHASE-2-COMPONENT-LIBRARY.md` | canonical component inventory and validation evidence | complete |
| `15-FIGMA-PHASE-3-DESKTOP-PROGRESS.md` | accepted Free/Pro desktop compositions and evidence | complete; D01–D40 accepted |
| `16-PRODUCT-DISCOVERY-AUDIT-CANONICAL.md` | durable product-discovery audit and checkpoint history | complete through STUDIO-CP-012 |
| `17-FIGMA-PHASE-4-PROFESSIONAL-DESKTOP.md` | accepted Max/professional desktop compositions and evidence | complete; D41–D93 accepted |
| `18-FIGMA-PHASE-5-RESPONSIVE.md` | accepted tablet/mobile compositions and responsive evidence | complete; T01–T20 and M01–M30 accepted |
| `19-FIGMA-PHASE-6-PROTOTYPE-GRAPH.md` | named starts, interactions, and verified route assertions | complete |
| `20-STUDIO-ADVERSARIAL-UX-AUDIT.md` | fresh whole-product visual, structural, responsive, and prototype audit | complete; zero unresolved P0/P1 |
| `21-STUDIO-CODING-AGENT-IMPLEMENTATION-HANDOFF.md` | repository, component, API, state, test, analytics, and rollout contracts | complete |
| `22-STUDIO-FINAL-COMPLETION-PROOF.md` | requirement-by-requirement closure evidence | complete |
| `23-STUDIO-VISUAL-SHARPNESS-PASS.md` | Enterprise-calibrated visual critique, responsive corrections, and final audit | complete |
| `24-IMPLEMENTATION-PHASE-I0-KICKOFF.md` | future implementation planning inventory; no coding authority | planning only; not started |
| `25-IMPLEMENTATION-PHASE-I0-CONTRACT-SLICE.md` | audit of the mistakenly started implementation slice | rolled back; historical only |
| `26-STUDIO-PRD-ADVERSARIAL-DESIGN-REVIEW.md` | first fresh visual review of all Studio surfaces against the 173-requirement PRD | superseded by stricter document 27 |
| `27-STUDIO-STRICT-PRD-AUDIT-SECOND-PASS.md` | strict job-completion and inspectability audit against the PRD | diagnostic baseline; P0/P1 superseded by documents 29 and 30 |
| `28-FIGMA-STRICT-AUDIT-BOARD.md` | Figma review board plotting all 18 strict-pass captures and health notes | complete at STUDIO-CP-015 |
| `29-STUDIO-P0-REPAIR-AND-THIRD-PASS.md` | P0 repair evidence, strict third-pass verdict, and remaining debt | complete at STUDIO-CP-016 |
| `30-STUDIO-P1-CLOSURE-AND-FOURTH-PASS.md` | six-P1 repair evidence, responsive coverage, prototype extension, and strict fourth-pass verdict | complete at STUDIO-CP-017 |
| `31-STUDIO-SCREEN-BY-SCREEN-DESIGN-HANDOFF.md` | all 200 Figma screens with authority, intent, content, actions, state/recovery, responsive, acceptance, and journey links | complete at STUDIO-CP-018 |
| `32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md` | 20 detailed canonical journeys plus all 20 named Figma flow starts | complete at STUDIO-CP-018 |
| `33-STUDIO-DESIGNER-PRD.md` | concise designer-facing PRD with linked journey requirements and UX scope boundary | complete at STUDIO-CP-018 |
| `ExecuteSpec-Studio-Designer-PRD.pdf` | 46-page linked designer PRD containing journey chapters and all 200 screen records | visually and structurally verified at STUDIO-CP-018 |
| `dsb-state-studio-closure-v1-2026-07-23.json` | machine-readable closure state | refreshed for CP-018 |
| `evidence/` | 93 durable PNGs, including five fresh P9 whole-section renders | complete for current audit |

## 5. Closed decisions inherited from prior work

1. Studio is a separate view and product surface from Enterprise.
2. Studio has only three durable application surfaces: Home, Project, and Account.
3. Project work uses four lenses: Build, Data, Checks, and Ship.
4. The accepted shell is Codex-inspired: history/context left, agent task center, inspectable output right.
5. Both side panes collapse and restore using icons, not numbers.
6. The center remains dominant; side panes are contextual and must not crowd the canvas.
7. Free, Pro, and Max share the same interaction grammar; tiers change capability envelopes, not navigation architecture.
8. Professional Mode is progressive disclosure in Studio, not a separate freelancer shell.
9. Settings uses six job-based groups, not the internal 20-domain feature catalog.
10. Gamification follows verified outcomes and useful learning; it never rewards spend, token use, or meaningless activity.
11. Mobile supports review, decisions, checks, small changes, publish, rollback, and recovery; it does not pretend to provide full desktop source editing.
12. Provider/model names remain supported, but third-party logos are deferred and are not a Studio closure blocker.

## 6. Open closure gates

| Gate | Exit condition | Status |
|---|---|---|
| S0 — authority | master PRD reconciles both persona PRDs and old UX documents | closed |
| S1 — tier semantics | Free/Pro/Max customer packaging maps explicitly to live platform entitlements without ambiguous `PRO+`/`BYOK` behavior | closed |
| S2 — parity | every P0 capability is marked existing, partial, planned, or unsupported against current code | closed |
| S3 — journeys | casual and professional journeys share objects and upgrade/graduation continuity | closed |
| S4 — traceability | all 173 stable requirement IDs have an exact screen/state/action/test mapping | P0 and P1 repair obligations have exact CP-016/CP-017 overlays; runtime acceptance remains open |
| S5 — responsive | desktop, tablet, and mobile rules/states are explicit and represented | closed for P0/P1 design scope; CP-017 adds responsive Brief, operation, and Account depth |
| S6 — trust | authority, secrets, publish, payment, client isolation, rollback, and failure states are exhaustive | closed |
| S7 — Figma foundations | palette, type, spacing, icon, status, focus, and motion foundations exist | closed |
| S8 — Figma components | canonical components and variants cover all states without detached lookalikes | closed |
| S9 — Figma surfaces | accepted screen/state set is complete and playable | closed for P0/P1 design scope through P9; 7 P2 refinements remain |
| S10 — adversarial audit | layout, accessibility, entitlement, redaction, action wiring, dead-end, and PRD parity failures are zero | 0 P0, 0 P1, 7 P2; runtime gates remain |
| S11 — handoff | coding-agent implementation document and prototype graph are complete | external UX-designer package and linked PDF added at CP-018; implementation still requires explicit approval |
| S12 — visual sharpness | Studio reaches the accepted Enterprise hierarchy benchmark without copying Enterprise IA | visually closed; does not establish product completeness |

## 7. Completion rule

Studio UX is not closed because a few attractive desktop frames exist. It is closed only when:

- every approved requirement is either visibly represented or deliberately non-visual;
- Free, Pro, and Max states preserve one coherent journey;
- casual-to-professional graduation retains Project, Task, Version, Check, Release, data, and history;
- responsive and failure/recovery states are first-class;
- the Figma prototype, master PRD, traceability matrix, and coding-agent handoff agree exactly;
- the consolidated adversarial audit reports zero unresolved P0 failures.

Document 27 remains the strict diagnostic baseline. CP-016 closed its six P0 design blockers and
CP-017 closed the six remaining P1 product/interaction gaps. Seven P2 refinements remain before a
zero-defect visual freeze. Runtime keyboard/screen-reader behavior, 200% zoom/localisation,
production authorization/redaction, durable reconciliation, responsive state-machine parity, and
deterministic `AT-<requirement-id>` execution remain later implementation acceptance gates.

## 8. CP-018 external UX-designer handoff

- Figma was read directly to reconcile 27 Studio sections, 200 screen frames, and 20 named flow
  starts. The 18 `Authority / Reference only` banners are annotations and are not counted as
  screens.
- The handoff explicitly separates 57 P8/P9 implementation-authority screens from 143 P3-P5
  reference-only screens, preventing the designer from implementing obsolete dossier/composer
  patterns.
- Twenty canonical journeys now contain persona, goal, preconditions, trigger, exact screen
  sequence, decision/authority, failure/recovery, success, evidence, responsive, analytics, and
  direct Figma links.
- The designer PRD PDF contains every journey chapter and every screen record. All journey mentions
  are internal PDF links, and every screen/flow reference links to the exact Figma node.
- PDF validation: 46 A4 pages, 369 external Figma links, 457 internal journey links, and zero
  malformed link annotations. All 46 rendered pages were visually reviewed through contact sheets,
  with representative full-size page checks.
- Canonical package totals: 129 files, 34 numbered documents, 93 evidence PNGs, and the linked PDF.
- UX-only boundary remains in force. No application source, migration, configuration, CI,
  generated-client, package-manifest, or repository-architecture change is authorised.
