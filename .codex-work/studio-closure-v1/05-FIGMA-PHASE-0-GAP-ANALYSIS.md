# ExecuteSpec Studio — Figma Phase 0 Gap Analysis

Date: 2026-07-23  
Status: **Read-only source audit complete; Figma structure initialized**  
File: `LfEwxST9vagBMNJdVWn23L`  
URL: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L>

## 1. Starting state

A separate Studio design file was created to preserve the explicit product-view boundary from Enterprise.

Pages initialized:

| Page | Node ID | Current content |
|---|---|---|
| Cover | `0:1` | empty |
| Foundations | `1:2` | empty |
| Components | `1:3` | empty |
| Studio Screens | `1:4` | empty |

No visual screen, component, token, interaction, or accessibility claim is yet accepted in this file.

## 2. Source strength

Studio has more product definition than the empty Figma file suggests:

- 81 stable casual-builder requirements;
- 92 stable professional-individual requirements;
- two coding-agent delivery guides;
- an exhaustive feature catalog;
- accepted product-line logic for Free, Pro, and Max;
- a Codex-inspired agent shell direction;
- a single-user Build/Data/Checks/Ship model;
- grouped Settings IA;
- explicit mobile intent and professional progressive disclosure.

The problem is not missing ideation. The problem is reconciliation and visual proof.

## 3. Critical gaps

### G01 — no master Studio authority

Two approved persona PRDs and several proposed UX documents can conflict without one resolution layer. `01-STUDIO-MASTER-PRD.md` is the closure draft; it cannot become authoritative until conflicts and tier semantics close.

### G02 — tier naming does not match live entitlement architecture

Design documents use Free/Pro/Max. The current platform model uses Free/Pro/hidden Pro+/BYOK/Team/Enterprise. Max behavior, BYOK placement, upgrades, downgrades, and existing-user migration are not closed.

### G03 — no current-state parity audit

Existing/planned/partial/missing is not reconciled for all 173 requirements. Designing an idealized Studio without this audit risks showing non-existent features as current and hiding already-built capabilities.

### G04 — shell and lens models need one composition rule

Older material describes Build/Data/Checks/Ship as the Project workspace. Later material describes a Codex-style left Task list, center agent Task, and right Output. They are compatible only if:

- Task is the center work object;
- Preview/Changes/Checks/Data/Release are Output tabs/lenses;
- Build/Data/Checks/Ship remains product language and route/deep-link structure;
- neither creates a competing second navigation system.

This is the current master-PRD interpretation and must be validated in Figma.

### G05 — proposed UX decisions are not all explicitly locked

Several documents still say `proposed`, `exploratory`, or `not yet locked`. The closure register inherits only decisions explicitly accepted in conversation. Remaining choices require product disposition, not accidental implementation.

### G06 — responsive experience is descriptive, not designed

Mobile and tablet requirements exist, but no accepted Studio responsive frames exist. Mobile must be task-capable for review, Decisions, Checks, publish, rollback, and recovery without pretending to match dense desktop editing.

### G07 — failure, recovery, and continuity are incomplete visually

Source unavailable, expired authorization, offline/reconnect, UI/durable-state mismatch, partial publish, stale approval, client mismatch, cost cap, hard limit, and destructive recovery require first-class frames.

### G08 — professional client safety can disappear under progressive disclosure

Client identity, source identity, credential boundary, memory policy, cost attribution, and offboarding cannot be hidden so aggressively that a professional user acts in the wrong client context.

### G09 — gamification risks becoming decoration

The product requirement is evidence-backed progress. Figma must prove verified triggers, opt-out, reduced motion, subtle professional mode, and no spend/activity rewards.

### G10 — capability coverage can overload Settings

All capabilities must be reachable, but the 20 internal domains cannot become user navigation. The six-group Settings model and contextual Output/action placement must be validated against all 173 requirements.

### G11 — no Figma component system

Studio currently lacks its own canonical shell, panes, Task states, Output tabs, Decisions, checks, resources, release, entitlement, client boundary, Settings, progress, and continuity components.

### G12 — no prototype graph or action audit

There are no prototype starts, wired actions, focus contracts, dead-end checks, or accessibility metadata.

### G13 — comments cannot be ingested by current Figma MCP

The installed Figma connector has no comments endpoint, and the Plugin API exposes no comment-thread property. Review comments must be inspected through the signed-in Figma UI or retrieved through separately authenticated Figma REST comment access. This affects review operations, not product design scope.

### G14 — third-party identity assets are deferred

Provider/model/source names remain required. Official logos are intentionally deferred following the product decision. Studio must not introduce fake substitute marks or letter-tile approximations.

## 4. Adversarial product risks

| Risk | Likely failure | Closure response |
|---|---|---|
| Studio becomes Enterprise-lite | dense dashboards and internal terminology | enforce object, density, and navigation contracts |
| Studio becomes chat-only | application state buried in transcript | durable Project/Task/Output objects and inspectable right pane |
| Studio becomes canvas-only | autonomous work and Decisions become vague | semantic Task states and Mission phases |
| tier forks | Free/Pro/Max diverge into different products | one shell and upgrade continuity tests |
| Max overload | every professional control stays visible | contextual disclosure plus persistent client boundary only |
| feature hiding | capabilities disappear to preserve simplicity | exhaustive placement/Settings traceability |
| fake progress | percentage theatre and activity rewards | server/durable-evidence-only progress contract |
| unsafe publish | consequence, health, or rollback hidden | dedicated Decision/Release/failure/recovery states |
| client leakage | wrong client/source/memory context | persistent boundary, isolation states, destructive/offboarding proofs |
| mobile theatre | desktop squeezed into mobile | one-surface mobile task model and honest handoff |

## 5. Phase sequence

### Phase 0 — reconcile

- lock master authority and tier semantics;
- expand 173 traceability rows;
- complete current-state parity;
- consolidate logical states into accepted composition set;
- resolve remaining product questions.

### Phase 1 — foundations

- build Cover and documented foundations using the accepted ExecuteSpec palette;
- define semantic variables, typography, spacing, radii, elevation, icons, focus, motion, and breakpoints;
- document Studio vs Enterprise density differences.

### Phase 2 — components

- build canonical component/variant families;
- bind semantic variables;
- validate content expansion, focus, disabled, loading, permission, failure, tier, and breakpoint variants.

### Phase 3 — desktop core

- Free creation loop;
- Pro operation loop;
- shared Task/Output shell;
- Data, Checks, Ship;
- failure/recovery;
- upgrade continuity.

### Phase 4 — Max/professional

- client/repository/context/scope/plan;
- integration verification/CI;
- PR/review/deploy/handoff;
- cost/provider/BYOK;
- isolation/offboarding/graduation.

### Phase 5 — tablet/mobile

- create accepted responsive frames for the manifest minimum;
- verify parity where tasks remain supported;
- document honest handoffs for dense source work.

### Phase 6 — prototype and adversarial closure

- wire named starts and transitions;
- audit all actions, destinations, dead ends, focus, redaction, contrast, clipping, responsive behavior, and PRD parity;
- issue coding-agent handoff and prototype graph only when P0 failures are zero.

## 6. Mutation boundary

Phase 0 permits:

- Figma file/page structure;
- closure documents;
- read-only source/Figma inspection.

Full visual mutation should begin only from the reconciled master authority, accepted composition manifest, and current-state parity audit. This prevents polishing screens that later have to be structurally replaced.

