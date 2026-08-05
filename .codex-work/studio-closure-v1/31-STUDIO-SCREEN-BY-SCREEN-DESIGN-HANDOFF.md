# ExecuteSpec Studio - Screen-by-Screen Designer Handoff

Date: 2026-07-31  
Checkpoint: `STUDIO-CP-018`  
Audience: UX/UI designer, interaction designer, content designer, accessibility reviewer, and product manager  
Canonical Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio>

## Authority rule

The file contains 200 actual screen frames. The 57 P8/P9 repair screens are implementation design authority. The 143 P3-P5 screens are retained as reference-only history. Do not implement their older dossier/composer patterns when they conflict with P8/P9. The 18 `Authority / Reference only` banners are canvas annotations, not screens, and are excluded from the count.

## Global composition contract

- Desktop: left Projects/Tasks, center Task/decisions/composer, right inspectable Output; both side panes collapse independently to meaningful icons.
- Tablet: one narrow persistent rail; Project/Task and Output use drawers/sheets while preserving the Task.
- Mobile: one primary Task or Output surface at a time with a 68 px bottom navigation and 44x44 px minimum targets.
- Output always means one of Preview, Changes, Data, Checks, Release, or Evidence. It is inspectable, not a summary card.
- Consequential actions expose target, effect, evidence/freshness, cost when material, authority/expiry, and recovery/rollback.
- Status uses text plus visual treatment; do not rely on color, hover, animation, or transient toast alone.

## Screen index

- Implementation design authority: 57 screens.
- Reference only: 143 screens.
- Total: 200 screens.

## Part A - P8/P9 implementation design authority

### R01 - Home / Projects and tasks

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7666) (`108:7666`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Resume the right work immediately.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Workspace identity; Project list with tier/privacy/task count; Task list with status and last activity; Build/Data/Checks/Ship lenses; Account entry; running/blocked/completed summary.
- **Primary interaction:** Open or resume a Task, start/import a Project, switch lens, or open Account.
- **State and recovery:** Empty Home offers idea/template/import starts; returning Home surfaces stale, blocked, and running Tasks without relying on chat history.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R02 - Builder / Preview selection

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7727) (`108:7727`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Build while inspecting the live product.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Persistent Projects/Tasks; active Task and semantic phase; composer; live Preview; selected object hierarchy; direct controls; interaction trace; source and evidence freshness.
- **Primary interaction:** Select an object, edit a safe property, describe a change, open another Output mode, pause, or review a proposed Change.
- **State and recovery:** Selection and composer draft survive Output switching, pane collapse, reconnect, and viewport transition; mocked runtime is labeled.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R03 - Change review / Before after

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7814) (`108:7814`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Review a proposed change before commitment.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Synchronized before/after Preview; changed objects and files; requested intent; acceptance impact; Check impact; source freshness; checkpoint and rollback.
- **Primary interaction:** Approve/apply, reject, modify request, inspect cited evidence, or return to Preview.
- **State and recovery:** Conflict, stale source, failed Check, and superseded proposal block blind approval and provide rebase/retry/revert routes.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R04 - Output / Preview

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7898) (`108:7898`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect the running Preview.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Runtime status; exact revision; selected object; object details; interaction trace; source link/freshness; viewport controls; explicit mocked/partial state.
- **Primary interaction:** Interact with Preview, select an object, enter full preview, inspect details, or switch Output mode.
- **State and recovery:** Runtime unavailable, loading, crashed, stale, mocked, and healthy are distinct; every non-healthy state has retry/rebuild/open-evidence guidance.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R05 - Output / Changes

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7973) (`108:7973`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Understand what changed.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Product-language summary; changed objects/files; before/after; citations; Check impact; source identity; Version/checkpoint; unresolved conflicts.
- **Primary interaction:** Open a changed object/file, compare, accept/reject, restore, or request another bounded change.
- **State and recovery:** Binary/large/dense diffs use summaries and desktop handoff; stale or conflicting diffs cannot be approved as current.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R06 - Output / Data

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8046) (`108:8046`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect Data and access behavior.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Schema/resources; records or sample shape; access policy; auth/storage/function/integration status; environment; secret references; source and Check freshness.
- **Primary interaction:** Open a Resource, edit supported configuration, connect/verify, inspect access, or switch to relevant Checks.
- **State and recovery:** Empty, migration pending, permission denied, partial sync, stale schema, and secret unavailable states remain explicit.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R07 - Output / Checks

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8118) (`108:8118`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect verification status and failures.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Pass/fail/partial counts; exact failing assertion; affected behavior; evidence source; logs/capture links; freshness; generated-suite validity; repair/retry options.
- **Primary interaction:** Open evidence, rerun, request bounded repair, revert, quarantine flaky Check, or acknowledge unmeasured status.
- **State and recovery:** Failed, flaky, inconclusive, unavailable runner, invalid suite, stale, and passing states never collapse into one score.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R08 - Output / Release

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8190) (`108:8190`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect a Release and rollback anchor.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Destination; immutable manifest/revision; exposure; current health; required Checks; deployment receipt; previous healthy revision; rollback availability.
- **Primary interaction:** Open release evidence, monitor health, initiate bounded rollback, or create a new release decision.
- **State and recovery:** Deploying, partial, failed, degraded, healthy, superseded, and rolled-back states reconcile remote and durable identity.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R09 - Output / Evidence

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8263) (`108:8263`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Audit claims and their proof.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Claim ledger; deterministic/LLM/hybrid source label; cited artifact; freshness; current source identity; confidence/unknown status; receipt links.
- **Primary interaction:** Open the underlying assertion, capture, log, diff, receipt, or source; filter by object/outcome.
- **State and recovery:** Missing, stale, conflicting, or model-derived evidence is labeled and never presented as deterministic proof.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R10 - Trust / Pending

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8336) (`108:8336`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Decide on a bounded consequential action.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Pending status; target; effect; unchanged scope; evidence/freshness; material cost; authority and expiry; recovery/rollback; safe alternative.
- **Primary interaction:** Confirm or cancel; open evidence before deciding.
- **State and recovery:** No effect occurs while pending; repeated confirm is idempotent; expired or changed context invalidates the Decision.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R11 - Trust / Expired

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8414) (`108:8414`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Recover from expired authority.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Original target/effect; expiry reason/time; changed context; preserved Task/checkpoint; refresh requirements; safe cancel/alternative.
- **Primary interaction:** Refresh the same scoped authority, modify the request, or cancel.
- **State and recovery:** The expired receipt cannot be reused; refreshed authority must bind the current target, source, budget, and environment.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R12 - Trust / Confirmed

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8488) (`108:8488`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Verify what an approved action actually did.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Confirmed state; immutable receipt; actor/time; exact target/effect; remote result; durable checkpoint; evidence; rollback/recovery.
- **Primary interaction:** Open receipt/evidence, continue to result, or initiate the bounded recovery action.
- **State and recovery:** Partial remote effect opens reconciliation; a confirmed UI state without durable receipt is treated as Needs attention.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R13 - Account / Six groups

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8561) (`108:8561`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Navigate Account by six stable jobs.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Profile/preferences; AI/usage; sources/local; data/secrets; publish/domains; privacy/ownership, each with summary, status, and next action.
- **Primary interaction:** Open a group without exposing an internal capability catalogue.
- **State and recovery:** Unavailable or tier-gated settings explain the requested outcome and preserve export, deletion, privacy, and recovery access.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RT01 - Tablet / Project and task drawer

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8168) (`109:8168`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Choose Project and Task on tablet.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Drawer lists Projects/Tasks with status while the active Task remains behind it; selection and scroll persist.
- **Primary interaction:** Open a Task or dismiss the drawer.
- **State and recovery:** Rotation, dismissal, and reconnect restore the same selection and focus.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RT02 - Tablet / Output side sheet

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8205) (`109:8205`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect Output beside the tablet Task.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Side sheet exposes Preview and object detail without replacing Task context.
- **Primary interaction:** Inspect/select, switch Output mode, or close to Task.
- **State and recovery:** Sheet size and focus trap adapt to portrait/landscape; close returns to invoking control.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RT03 - Tablet / Full preview

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8233) (`109:8233`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Use full tablet Preview.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Runtime fills the canvas with explicit exit, selected object, and interaction-trace continuity.
- **Primary interaction:** Interact/select and exit back to the same Task/Output state.
- **State and recovery:** System gestures, rotation, and runtime failure do not strand the user.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RT04 - Tablet / Compare

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8256) (`109:8256`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Compare versions on tablet.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Synchronized before/after panes with shared selection, focus, and scroll.
- **Primary interaction:** Move divider, select an object, inspect evidence, restore, or exit.
- **State and recovery:** Portrait may stack/switch panes but preserves semantic order and comparison labels.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RT05 - Tablet / Decision sheet

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8278) (`109:8278`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Resolve a tablet Decision.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Sheet contains target/effect/authority/freshness/cost/recovery and full-width Cancel/Confirm actions.
- **Primary interaction:** Open evidence, cancel, or confirm once.
- **State and recovery:** Focus trap/return, expiry, context change, offline, and duplicate-submit behavior are explicit.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RT06 - Tablet / Orientation and keyboard

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8309) (`109:8309`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Preserve tablet state through orientation and keyboard.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Portrait/landscape examples show selected object, composer, safe area, and focus preservation.
- **Primary interaction:** Rotate or invoke keyboard without losing Task context.
- **State and recovery:** Keyboard never covers composer/primary action; orientation does not reset selection or scroll.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM01 - Mobile / Build task

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8341) (`109:8341`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Work from the mobile Task surface.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Task title/state/semantic phase, decisive card/output summary, composer, and bottom navigation.
- **Primary interaction:** Describe a small change, open Output/drawer, or resolve the active Decision.
- **State and recovery:** Composer stays above safe area; background/reconnect preserves draft and checkpoint.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM02 - Mobile / Project and task drawer

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8358) (`109:8358`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Switch Project or Task on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Full-height drawer with Project and Task status over the current Task.
- **Primary interaction:** Open selection or close by explicit control/gesture.
- **State and recovery:** Focus returns correctly; drawer never destroys Task scroll/composer draft.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM03 - Mobile / Output switcher

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8395) (`109:8395`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Switch inspectable Output modes on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Dedicated Output surface with Preview/Changes/Data/Checks/Release/Evidence modes and selected-object continuity.
- **Primary interaction:** Choose mode, inspect, open full view/sheet, or return to Task.
- **State and recovery:** Mode switch announces state and preserves the same Project/Task.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM04 - Mobile / Checks sheet

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8429) (`109:8429`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect a failed Check on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Failure sheet shows assertion, expected/observed behavior, evidence link, freshness, and bounded actions.
- **Primary interaction:** Open evidence, rerun/repair, or close to the same assertion.
- **State and recovery:** Partial/flaky/unavailable states remain labeled; no toast-only failure.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM05 - Mobile / Full preview

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8452) (`109:8452`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Use full mobile Preview.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Runtime-first surface with explicit exit and selection continuity.
- **Primary interaction:** Interact/select and exit.
- **State and recovery:** Browser chrome/safe areas and runtime crash/loading states remain usable.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM06 - Mobile / Compare

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8475) (`109:8475`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Compare versions on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Swipe/divider or before/after toggle with synchronized selection and stable screen-reader order.
- **Primary interaction:** Move comparison, inspect evidence, restore, or exit.
- **State and recovery:** Do not rely on simultaneous tiny panes; labels and semantic order stay clear.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM07 - Mobile / Account

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8497) (`109:8497`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Navigate six Account jobs on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Six direct group rows with status/summary, not an overloaded capability catalogue.
- **Primary interaction:** Open a group or return to prior Task.
- **State and recovery:** Deep settings use full screens and preserve unsaved-change warnings and deterministic back behavior.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### RM08 - Mobile / Decision sheet

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8529) (`109:8529`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Resolve a mobile Decision.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Full-width sheet with complete consequence grammar and thumb-reachable actions.
- **Primary interaction:** Open evidence, cancel, or confirm once.
- **State and recovery:** Expiry/context change/offline/duplicate submit keep the effect safe and visible.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R14 - Brief / Editable assumptions

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8477) (`122:8477`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Edit and approve the delivery Brief.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Outcome; users; constraints; assumptions; acceptance intent; citations/freshness; affected objects; unresolved questions; edit history.
- **Primary interaction:** Edit fields, open citations, resolve questions, save draft, or submit for approval.
- **State and recovery:** For casual tiers reuse this interaction grammar with lighter content; the visible Max badge in this frame is not a requirement for J01.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R15 - Brief / Citation conflict

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8611) (`122:8611`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Resolve contradictory source evidence.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Conflicting claims side by side; exact citations; source identity/freshness; affected Brief fields and downstream objects; resolution rationale.
- **Primary interaction:** Choose current evidence, refresh a source, amend the Brief, or pause/cancel approval.
- **State and recovery:** Approval remains blocked while material conflict is unresolved; low-confidence evidence cannot silently win.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R16 - Plan / Approved impact

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8730) (`122:8730`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Inspect the approved Plan and its frozen impact.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Approved steps/dependencies; tests; affected objects/repos; route; budget/time cap; authority scope; source identity; immutable approval receipt.
- **Primary interaction:** Start implementation, open a step/object, or request a new Plan/ChangeRequest.
- **State and recovery:** Any material source/scope/provider/budget/environment change invalidates the old authority instead of mutating it in place.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R17 - Professional / Repository context

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8865) (`122:8865`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Understand exact repository context.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Provider; repo/branch/commit; freshness; dirty state; repository map; cross-repo boundaries; citations; current Task impact; client identity.
- **Primary interaction:** Open map/source/citation, refresh context, change pinned source through a Decision, or continue to scope.
- **State and recovery:** Partial, stale, conflicting, permission-denied, and multi-repo mismatch states identify known/unknown context.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R18 - Professional / Scope baseline

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8995) (`122:8995`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Control the accepted professional boundary.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Included/excluded work; assumptions; acceptance criteria; estimate range; risks/dependencies; budget; baseline receipt; divergence and ChangeRequest economics.
- **Primary interaction:** Accept/edit baseline, create/approve/reject ChangeRequest, or inspect affected Plan objects.
- **State and recovery:** Work outside baseline cannot be hidden as progress; unresolved divergence blocks handoff/acceptance until explicitly handled.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R19 - Professional / CI validation

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9117) (`122:9117`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Diagnose CI and choose a bounded response.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Pipeline stages; environment/commit; exact failed stage/assertion; logs/artifacts; retry history; suite validity; affected acceptance intent; cost/authority.
- **Primary interaction:** Open evidence, rerun, request bounded repair, quarantine flaky behavior, or stop.
- **State and recovery:** Queued/running/passed/failed/cancelled/inconclusive and provider-unavailable states retain the exact CI identity.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R20 - Professional / Deployment

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9224) (`122:9224`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Control a staging or production deployment.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Target/environment; immutable manifest; canary/rollout timeline; health signals; required Checks; current/previous revision; rollback anchor; authority receipt.
- **Primary interaction:** Confirm deploy, monitor/reconcile, open evidence, promote, or rollback.
- **State and recovery:** Failed/partial/unknown deployment never replaces the prior healthy release; remote and durable state reconcile before retry.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R21 - Professional / Client review

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9356) (`122:9356`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Prepare a client-safe decision package.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Package artifacts; redaction summary; visible/hidden scope; reviewer identity and expiry; anchored comments; acceptance/change-request actions; revocation.
- **Primary interaction:** Share/invite, preview client view, respond to anchored comment, revoke, or record decision.
- **State and recovery:** Expired/revoked/denied links fail closed; internal source, cost, secrets, prompts, and unrelated client context stay hidden.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R22 - Professional / Handoff

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9472) (`122:9472`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Transfer delivery ownership cleanly.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Artifact manifest; source/release/evidence/docs; ownership mapping; credential handling; access revocation; unresolved items; maintenance/support terms; receipt.
- **Primary interaction:** Review/complete handoff, download bundle, transfer/revoke access, or return unresolved items to a Task.
- **State and recovery:** Handoff cannot complete while ownership, credential, acceptance, or rollback responsibilities are ambiguous.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R23 - Operation / Running

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9350) (`124:9350`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Monitor an external operation in progress.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Operation identity; semantic phase; durable checkpoint; remote target; elapsed/last update; cancel semantics; background continuation; expected next evidence.
- **Primary interaction:** Leave safely, inspect activity, pause/cancel if supported, or continue other work.
- **State and recovery:** Closing the screen does not stop work; reconnect restores the same operation rather than starting another.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R24 - Operation / Reconnecting

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9455) (`124:9455`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Stay oriented while an operation reconnects.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Last durable checkpoint; last remote acknowledgement; connectivity/provider state; preserved partial output; retry/reconcile timer; safe user options.
- **Primary interaction:** Wait, retry connection, work offline where safe, inspect checkpoint, or cancel.
- **State and recovery:** No duplicate remote action is issued on UI timeout; prolonged interruption transitions to Partial or Failed with evidence.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R25 - Operation / Partial result

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9561) (`124:9561`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Use safe partial output without implying completion.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Ready versus failed objects; per-object state; checkpoint; missing evidence; isolated failures; downstream limitations; reconcile/retry options.
- **Primary interaction:** Inspect/export ready objects, retry failed subset, reconcile, or stop with partial receipt.
- **State and recovery:** Partial result cannot unlock publish/handoff claims that require the missing objects or fresh verification.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R26 - Operation / Reconciled

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9667) (`124:9667`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Confirm remote and durable state now agree.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Pinned remote identity; durable object identity; reconciliation comparison; repaired differences; final checkpoint; evidence freshness; continuation action.
- **Primary interaction:** Open reconciliation evidence or continue the original Task.
- **State and recovery:** Any remaining mismatch keeps the operation in Needs attention; reconciliation does not erase prior partial/failure history.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R27 - Operation / Failed

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9788) (`124:9788`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Understand a safe hard failure.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Cause; stage; target; last checkpoint; confirmed remote effects; unchanged objects; logs/evidence; retry eligibility; alternate route/support.
- **Primary interaction:** Retry same scope, choose approved alternative, inspect/export partial work, or stop.
- **State and recovery:** Failure copy distinguishes provider, permission, validation, policy, budget, user, and unknown causes.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R28 - Operation / Cancelled

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9894) (`124:9894`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Confirm user cancellation and cleanup.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Cancelled actor/time; completed/checkpointed work; temporary resources/authority revoked; confirmed remote effects; retained objects; restart path.
- **Primary interaction:** Open retained output, resume as a new bounded action, or dismiss.
- **State and recovery:** Cancellation does not claim rollback of already-confirmed effects; cleanup failure becomes a visible recovery Task.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R29 - Tier / Free object depth

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9999) (`124:9999`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Understand Free object depth.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Project/Task/Preview; current Checks/evidence; safe publish destination; limits and retention; export/delete/recovery; gated intent explanation.
- **Primary interaction:** Continue within limits, export, or request an outcome that opens contextual comparison.
- **State and recovery:** No permanent locked-nav clutter; safety, honest failure, export, deletion, and recovery remain complete.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R30 - Tier / Pro object depth

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10154) (`124:10154`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Understand Pro object depth.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Free objects plus repository ownership, custom domain/production depth, recurring verification, expanded resources/history, provider route, and usage.
- **Primary interaction:** Operate the personal product, configure production depth, or compare Max at a client-delivery intent.
- **State and recovery:** Plan changes preserve all object identities; BYOK remains under AI/usage rather than appearing as a tier.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R31 - Tier / Max object depth

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10309) (`124:10309`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Understand Max professional depth.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Pro objects plus ClientWorkspace, scope, CI authority, evidence ledger, client review, handoff, project/client attribution, and graduation.
- **Primary interaction:** Enter a professional workflow, create/switch client boundary, or start Team/Enterprise graduation.
- **State and recovery:** Max never implies organisation-wide Enterprise governance; client isolation and professional evidence remain explicit.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R32 - Account / Identity and preferences

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10203) (`126:10203`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Manage identity and personal preferences.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Profile identity; theme; keyboard; notifications; locale/timezone; plan continuity; security/re-auth state; save receipt.
- **Primary interaction:** Edit and save reversible preferences or start a security-sensitive change through re-authentication.
- **State and recovery:** Unsaved changes, validation, session expiry, and cross-device preference conflict have explicit resolution.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R33 - Account / AI and usage

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10344) (`126:10344`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Control AI route and usage.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Effective provider/model names; managed versus own-key source; default route; usage/limit; cost attribution; receipts; fallback/blocked state; key health.
- **Primary interaction:** Choose allowed default, add/rotate/revoke key, inspect usage/receipt, or change a cap.
- **State and recovery:** Provider logos are omitted for now, but names/routes remain; BYOK secret is write-only and failed validation does not replace a working key.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R34 - Account / Sources and local

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10485) (`126:10485`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Manage source connections and local permissions.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** GitHub/Bitbucket identities; scopes; connected repos; webhook/CI status; local folders and granted permissions; last use; revoke/reconnect.
- **Primary interaction:** Connect/re-authorize/revoke, review scope, bind/unbind local folder, or open affected Projects.
- **State and recovery:** Revocation explains active Task impact; local permission is least privilege and never implies unrestricted machine access.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R35 - Account / Data and secrets

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10626) (`126:10626`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Manage storage, retention, and secrets.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Storage/package usage; environments; retention/export/delete rules; secret names/scopes/rotation status without values; affected Resources.
- **Primary interaction:** Change policy, rotate/revoke secret, export/delete data, or inspect dependent objects through a Decision.
- **State and recovery:** Secret values never render after entry; destructive policy changes require re-auth, dependency impact, and recovery statement.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R36 - Account / Publish and domains

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10766) (`126:10766`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Manage publish defaults and domains.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Default destination/environment; domains/DNS/TLS; exposure; current Release/health; rollback policy; share defaults; certificate/verification state.
- **Primary interaction:** Add/verify/remove domain, change publish default, inspect Release, or initiate rollback.
- **State and recovery:** DNS/TLS pending, conflict, expired, failed verification, and partial cutover preserve previous healthy routing.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### R37 - Account / Privacy and ownership

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10907) (`126:10907`)
- **Viewport:** Desktop 1440x1024
- **Authority:** implementation design authority
- **User job:** Exercise privacy and ownership rights.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Privacy/retention; source/data/docs/evidence export; Project/account deletion; client data boundaries; active access/integrations; re-auth and receipts.
- **Primary interaction:** Request/export/download, change retention, revoke access, delete Project/account, or cancel.
- **State and recovery:** Exports are resumable and manifest-driven; deletion reports exactly removed/retained objects and never crosses client boundaries.
- **Responsive/design note:** Desktop composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### PT07 - Tablet / Brief conflict

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=129-10814) (`129:10814`)
- **Viewport:** Tablet 1024x1366
- **Authority:** implementation design authority
- **User job:** Resolve a Brief citation conflict on tablet.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Conflict, citations, affected Brief fields, and resolution actions fit a tablet detail sheet/workbench.
- **Primary interaction:** Refresh citation, choose evidence with rationale, edit Brief, or pause.
- **State and recovery:** Approval stays blocked; sheet preserves Task and source context.
- **Responsive/design note:** Tablet composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### PT08 - Tablet / Operation reconnecting

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=129-10872) (`129:10872`)
- **Viewport:** Tablet 1024x1366
- **Authority:** implementation design authority
- **User job:** Monitor reconnecting work on tablet.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Operation identity, checkpoint, remote acknowledgement, preserved output, and reconcile options remain beside Task context.
- **Primary interaction:** Wait/retry/reconcile/cancel or leave safely.
- **State and recovery:** No duplicate remote action; rotation preserves operation identity.
- **Responsive/design note:** Tablet composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### PT09 - Tablet / Account AI usage

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=129-10940) (`129:10940`)
- **Viewport:** Tablet 1024x1366
- **Authority:** implementation design authority
- **User job:** Manage AI route and usage on tablet.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Provider/model names, key-source state, usage/cap, receipts, and allowed actions use a tablet Account workbench.
- **Primary interaction:** Choose route, inspect usage, or add/rotate/revoke key.
- **State and recovery:** Write-only key handling and provider failure remain explicit; logos stay omitted.
- **Responsive/design note:** Tablet composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### PM09 - Mobile / Brief conflict

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=129-11004) (`129:11004`)
- **Viewport:** Mobile 390x844
- **Authority:** implementation design authority
- **User job:** Resolve a Brief conflict on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** One conflicting field/citation at a time with progress, affected outcome, and clear resolution actions.
- **Primary interaction:** Open citation, refresh, choose/edit, or pause.
- **State and recovery:** No approval until all material conflicts are resolved; Task state persists.
- **Responsive/design note:** Mobile composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### PM10 - Mobile / Operation reconnecting

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=129-11048) (`129:11048`)
- **Viewport:** Mobile 390x844
- **Authority:** implementation design authority
- **User job:** Monitor reconnecting work on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Single-surface operation card shows checkpoint, last remote effect, connection state, and safe actions.
- **Primary interaction:** Wait/retry/reconcile/cancel or navigate away safely.
- **State and recovery:** Returning reattaches to the same operation and never duplicates it.
- **Responsive/design note:** Mobile composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

### PM11 - Mobile / Account groups

- **Figma:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=129-11093) (`129:11093`)
- **Viewport:** Mobile 390x844
- **Authority:** implementation design authority
- **User job:** Navigate detailed Account groups on mobile.
- **Entry and continuity:** Entered from the preceding Task/Output action or a persistent navigation control; restore the exact Project, Task, selection, focus, and checkpoint.
- **Required content:** Six groups expose concise status and drill-down; privacy/export/delete remain reachable regardless of tier.
- **Primary interaction:** Open a group, resolve a setting Decision, or return to Task.
- **State and recovery:** Deep settings preserve re-auth, receipts, errors, and unsaved-change behavior.
- **Responsive/design note:** Mobile composition must preserve semantic reading order, focus return, and the current Task; use the matching responsive authority screen rather than scaling desktop.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)
- **Acceptance:** the user can identify the active object, current state, evidence/freshness, available action, consequence, and recovery without reading agent logs or inferring from color.

## Part B - P3/P4/P5 reference-only screen catalogue

These screens remain useful for state intent, vocabulary, and historical coverage. For each one, preserve the named job and evidence needs, but recompose it through P8/P9 components and navigation. The current authority is always the linked P8/P9 grammar, not the legacy shell.

### Desktop references D01-D93

#### D01 - Home

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-3) (`13:3`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Orient and resume. Project and Task identity, status, recency, blocking reason, and the next useful action.
- **Interaction intent to retain:** Open/resume a Task, start a Project, or enter Account without reconstructing context.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D02 - New Project

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-67) (`13:67`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D03 - Brief Ready

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-131) (`13:131`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Define intent. Outcome, user, constraints, assumptions, acceptance intent, citations, freshness, and affected objects.
- **Interaction intent to retain:** Edit, resolve a conflict, or approve the bounded Brief.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D04 - Building

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-215) (`13:215`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D05 - Plan Decision

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-282) (`13:282`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D06 - Preview Ready

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-365) (`13:365`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Inspect the working product. Live/mock status, selected object, interaction trace, source freshness, Output mode, and task linkage.
- **Interaction intent to retain:** Select, inspect, edit, compare, or return to the same Task.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D07 - Visual Select

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-440) (`13:440`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Inspect the working product. Live/mock status, selected object, interaction trace, source freshness, Output mode, and task linkage.
- **Interaction intent to retain:** Select, inspect, edit, compare, or return to the same Task.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### D08 - Contextual Change

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-507) (`13:507`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### D09 - Iteration Conflict

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-207) (`15:207`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### D10 - Data Overview

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-289) (`15:289`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D11 - Authentication

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-363) (`15:363`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D12 - Storage and Functions

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-447) (`15:447`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D13 - API and Secret

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-529) (`15:529`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D14 - Integration Failure

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-614) (`15:614`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D15 - Checks Overview

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-696) (`15:696`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D16 - Generated Suite Validation

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-778) (`15:778`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D17 - On-Demand Check

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=17-523) (`17:523`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D18 - Check Failure and Repair

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=18-767) (`18:767`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### D19 - Versions

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-580) (`19:580`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### D20 - Compare

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-675) (`19:675`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### D21 - Restore

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-770) (`19:770`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### D22 - Publish Review

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-873) (`19:873`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### D23 - Deploying

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-980) (`19:980`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### D24 - Published

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-1072) (`19:1072`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### D25 - Health Degraded

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-712) (`20:712`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### D26 - Rollback Complete

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-873) (`20:873`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### D27 - Share Preview

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-1046) (`20:1046`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Inspect the working product. Live/mock status, selected object, interaction trace, source freshness, Output mode, and task linkage.
- **Interaction intent to retain:** Select, inspect, edit, compare, or return to the same Task.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D28 - Remix and Export

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-1138) (`20:1138`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D29 - Revoke Share

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-1239) (`20:1239`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D30 - Verified Progress

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-1340) (`20:1340`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D31 - Quests and Opt-out

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-1435) (`20:1435`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### D32 - Advanced View

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=21-934) (`21:934`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

#### D33 - Professional Graduation

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1019) (`23:1019`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

#### D34 - Free Limit

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1126) (`23:1126`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)

#### D35 - Upgrade and Resume

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1234) (`23:1234`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)

#### D36 - Billing and Downgrade

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1335) (`23:1335`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)

#### D37 - Privacy and Ownership

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=24-1098) (`24:1098`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D38 - Delete Project Decision

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=24-1233) (`24:1233`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D39 - Offline and Stale Recovery

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=25-1165) (`25:1165`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D40 - Collapsed Panes and Safety

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=26-1197) (`26:1197`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### D41 - Professional Mode Introduction

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1239) (`32:1239`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D42 - Import Existing Repository

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1324) (`32:1324`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D43 - Guided Capability Check

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1409) (`32:1409`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D44 - Client Workspaces Overview

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1496) (`32:1496`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D45 - Create Client Workspace

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1583) (`32:1583`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D46 - Switch Client Boundary

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1668) (`32:1668`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D47 - Offboard Client Workspace

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1753) (`32:1753`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D48 - Connect GitHub

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1840) (`32:1840`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### D49 - Connect Bitbucket Cloud

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1519) (`34:1519`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### D50 - Bind Local Workspace

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1604) (`34:1604`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### D51 - Branch and Dirty-Tree Safety

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1689) (`34:1689`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### D52 - Repository Map

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1776) (`34:1776`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)

#### D53 - Project Brief and Connected Context

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1863) (`34:1863`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Define intent. Outcome, user, constraints, assumptions, acceptance intent, citations, freshness, and affected objects.
- **Interaction intent to retain:** Edit, resolve a conflict, or approve the bounded Brief.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)

#### D54 - Context Freshness and Conflict

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1950) (`34:1950`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)

#### D55 - Requirement Intake

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-2037) (`34:2037`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D56 - Clarifications and Assumptions

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-2122) (`34:2122`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Define intent. Outcome, user, constraints, assumptions, acceptance intent, citations, freshness, and affected objects.
- **Interaction intent to retain:** Edit, resolve a conflict, or approve the bounded Brief.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D57 - Estimate Range and Risk

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-1799) (`35:1799`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D58 - Scope Baseline and Change Request

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-1886) (`35:1886`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D59 - Plan Review

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-1971) (`35:1971`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D60 - Authority and Control Level

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2056) (`35:2056`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D61 - Provider BYOK and Limits

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2141) (`35:2141`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D62 - Execution Route and Plan Block

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2226) (`35:2226`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### D63 - Implementation Working

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2313) (`35:2313`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Track durable work. Semantic phase, checkpoint, remote effect, progress evidence, partial objects, failure cause, and recovery action.
- **Interaction intent to retain:** Pause, resume, reconnect, reconcile, retry, inspect partial output, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

#### D64 - Diff Inspection

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2400) (`35:2400`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

#### D65 - Automated Review Findings

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2079) (`36:2079`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

#### D66 - Bounded Autofix and Commit

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2166) (`36:2166`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

#### D67 - Test Topology and Impact

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2251) (`36:2251`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### D68 - Generated Test Suite

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2338) (`36:2338`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### D69 - Test Suite Validation

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2423) (`36:2423`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### D70 - Integration Environment and CI Trigger

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2510) (`36:2510`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### D71 - CI Failure and Evidence

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2595) (`36:2595`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### D72 - Preview and Environment Targets

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2682) (`36:2682`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Inspect the working product. Live/mock status, selected object, interaction trace, source freshness, Output mode, and task linkage.
- **Interaction intent to retain:** Select, inspect, edit, compare, or return to the same Task.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D73 - Deploy Review

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2359) (`37:2359`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D74 - Deploying and Post-Deploy Verification

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2444) (`37:2444`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D75 - Deploy Failure and Rollback

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2531) (`37:2531`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D76 - Client Review Package

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2618) (`37:2618`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D77 - Decision-Only Client View

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2703) (`37:2703`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D78 - Client Changes Requested

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2788) (`37:2788`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D79 - Client Accepted and Handoff

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2875) (`37:2875`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### D80 - Project Documents

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2962) (`37:2962`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)

#### D81 - Templates and Checklist Library

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2639) (`38:2639`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)

#### D82 - Sanitized Memory Promotion

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2726) (`38:2726`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)

#### D83 - Pre-Action Cost Estimate

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2811) (`38:2811`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision)

#### D84 - Live Cost Attribution

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2896) (`38:2896`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision)

#### D85 - Cost Cap and BYOK Failure

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2983) (`38:2983`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision)

#### D86 - Scoped Reviewer Invite

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-3070) (`38:3070`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision)

#### D87 - Comments Notifications and Revoke

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-3155) (`38:3155`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision)

#### D88 - Client Privacy and Retention

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-3242) (`38:3242`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision)

#### D89 - Credential and Local-Data Boundaries

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-2919) (`39:2919`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D90 - Destructive Professional Action

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3006) (`39:3006`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D91 - Boundary Mismatch and Offboarding Evidence

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3091) (`39:3091`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### D92 - Graduate to Team

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3178) (`39:3178`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

#### D93 - Graduate to Enterprise

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3263) (`39:3263`)
- **Viewport:** Desktop 1440x1024; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

### Tablet references T01-T20

#### T01 - Home Brief and Start

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=44-3094) (`44:3094`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Orient and resume. Project and Task identity, status, recency, blocking reason, and the next useful action.
- **Interaction intent to retain:** Open/resume a Task, start a Project, or enter Account without reconstructing context.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview)

#### T02 - Working Task

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3133) (`46:3133`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Track durable work. Semantic phase, checkpoint, remote effect, progress evidence, partial objects, failure cause, and recovery action.
- **Interaction intent to retain:** Pause, resume, reconnect, reconcile, retry, inspect partial output, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview)

#### T03 - Output and Visual Selection

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3207) (`46:3207`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Inspect the working product. Live/mock status, selected object, interaction trace, source freshness, Output mode, and task linkage.
- **Interaction intent to retain:** Select, inspect, edit, compare, or return to the same Task.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### T04 - Data API and Secret

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3279) (`46:3279`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration)

#### T05 - Checks and Evidence

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3381) (`46:3381`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### T06 - Versions Compare and Restore

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3485) (`46:3485`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### T07 - Publish Health and Rollback

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3571) (`46:3571`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### T08 - Professional Mode Entry

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=46-3673) (`46:3673`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### T09 - Client Boundary Switch

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3428) (`47:3428`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### T10 - Source and Dirty Local State

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3506) (`47:3506`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### T11 - Context Freshness

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3595) (`47:3595`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)

#### T12 - Scope Baseline and Change Request

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3696) (`47:3696`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### T13 - Plan Authority Route and Limits

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3774) (`47:3774`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### T14 - Implementation Diff Review and Autofix

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3861) (`47:3861`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

#### T15 - Test Suite and CI Failure

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-3950) (`47:3950`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### T16 - Delivery Verification and Rollback

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=47-4051) (`47:4051`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### T17 - Client Review Approval and Handoff

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=48-3764) (`48:3764`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### T18 - Documents Templates and Memory

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=48-3856) (`48:3856`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)

#### T19 - Cost Attribution and Cap

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=48-3955) (`48:3955`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### T20 - Safety Isolation and Deletion

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=48-4044) (`48:4044`)
- **Viewport:** Tablet 1024x1366; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

### Mobile references M01-M30

#### M01 - Home Projects and Start

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=49-3932) (`49:3932`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Orient and resume. Project and Task identity, status, recency, blocking reason, and the next useful action.
- **Interaction intent to retain:** Open/resume a Task, start a Project, or enter Account without reconstructing context.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview)

#### M02 - Active Working Task

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-3969) (`51:3969`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Track durable work. Semantic phase, checkpoint, remote effect, progress evidence, partial objects, failure cause, and recovery action.
- **Interaction intent to retain:** Pause, resume, reconnect, reconcile, retry, inspect partial output, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview)

#### M03 - Full Screen Output and Visual Edit

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4043) (`51:4043`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Inspect the working product. Live/mock status, selected object, interaction trace, source freshness, Output mode, and task linkage.
- **Interaction intent to retain:** Select, inspect, edit, compare, or return to the same Task.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### M04 - Data Resources and Secret

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4117) (`51:4117`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### M05 - Checks and Evidence

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4217) (`51:4217`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Verify the outcome. Exact assertion/finding, source and freshness, pass/fail/partial status, affected behavior, logs/captures, and repair scope.
- **Interaction intent to retain:** Inspect evidence, rerun, repair, revert, quarantine, or acknowledge unmeasured state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)

#### M06 - Versions Compare and Restore

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4317) (`51:4317`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

#### M07 - Publish Deploy Health and Rollback

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4397) (`51:4397`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)

#### M08 - Share and Revoke

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4497) (`51:4497`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### M09 - Verified Progress and Opt Out

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4578) (`51:4578`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### M10 - Advanced and Professional Entry

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=51-4668) (`51:4668`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

#### M11 - Limit Upgrade and Resume

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4307) (`52:4307`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)

#### M12 - Privacy Ownership and Delete

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4381) (`52:4381`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

#### M13 - Professional Entry and Import

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4455) (`52:4455`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

#### M14 - Client Boundary

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4530) (`52:4530`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### M15 - Source Local Hands and Dirty State

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4596) (`52:4596`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### M16 - Context Freshness

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4671) (`52:4671`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)

#### M17 - Scope and Change Request

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4768) (`52:4768`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### M18 - Plan Authority Provider and Limits

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4834) (`52:4834`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan)

#### M19 - Implementation Diff Review and Autofix

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4909) (`52:4909`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Review reversible change. Before/after, changed objects/files, evidence, checkpoint, conflict state, and restore consequence.
- **Interaction intent to retain:** Approve/apply, reject, restore, or open cited evidence.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

#### M20 - Test Suite Integration and CI

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=52-4990) (`52:4990`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Manage a Resource. Resource type, environment, schema/config, access/scopes, secret reference, status, freshness, and Checks.
- **Interaction intent to retain:** Create/connect, authorize, verify, reconnect, or remove with a recovery path.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

#### M21 - Delivery Verification and Rollback

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=58-4661) (`58:4661`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Ship and recover. Target, manifest/revision, exposure, health window, evidence freshness, remote effect, and rollback anchor.
- **Interaction intent to retain:** Review, confirm, monitor, reconcile, or rollback.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### M22 - Client Review and Handoff

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=58-4734) (`58:4734`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Share a bounded decision surface. Visible package, redactions, reviewer scope/expiry, anchored comments, decision state, and revocation.
- **Interaction intent to retain:** Invite/share, accept, request change, revoke, or complete handoff.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

#### M23 - Docs Templates and Memory

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=58-4813) (`58:4813`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)

#### M24 - Cost Attribution and Cap

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=58-4869) (`58:4869`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Bound professional work. Included/excluded scope, estimate range, risks, object impact, route, authority, budget/cost, expiry, and receipt.
- **Interaction intent to retain:** Edit, approve, reject, request change, choose route, or stop without mutation.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### M25 - Collaboration and Notifications

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=58-4934) (`58:4934`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state)

#### M26 - Safety Isolation and Delete

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=59-4841) (`59:4841`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Protect client isolation. Active client identity, projects/sources/credentials/memory/usage boundary, pending effects, and access.
- **Interaction intent to retain:** Create, switch, block mismatch, revoke, export, or offboard.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)

#### M27 - Graduation to Team or Enterprise

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=59-4921) (`59:4921`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Support verified growth and reuse. Evidence-backed milestone, opt-in status, reusable object boundaries, sanitization, and target mapping.
- **Interaction intent to retain:** Continue, opt out, promote sanitized knowledge, export, or graduate.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

#### M28 - Offline and Stale Recovery

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=59-4984) (`59:4984`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Resolve the named product state. Current object identity, status, consequence, evidence, authority, and recovery.
- **Interaction intent to retain:** Take the next bounded action or return without losing Task state.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)

#### M29 - Source Unavailable and Reconnect

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=59-5059) (`59:5059`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Bind and understand source. Provider, repository, branch, commit, dirty state, scopes, freshness, citations, and cross-repo impact.
- **Interaction intent to retain:** Connect/import, refresh, inspect, re-authorize, or cancel safely.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)

#### M30 - Account Settings and Privacy

- **Figma reference:** [open exact screen](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=60-4977) (`60:4977`)
- **Viewport:** Mobile 390x844; **authority:** reference only.
- **State intent to retain:** Manage account and entitlement. Identity, tier/usage, providers, connections, retention, export/delete consequence, receipts, and recovery.
- **Interaction intent to retain:** Change settings/plan, export, re-authenticate, confirm destructive action, or cancel.
- **Recomposition instruction:** use P8/P9 Project/Task navigation, inspectable Output, trust/operation grammar, tier depth, Account grouping, and responsive surface behavior. Do not copy the older universal dossier/composer treatment.
- **Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)

## Designer delivery checklist

1. Treat the 57 P8/P9 frames as component and composition authority.
2. Resolve the seven remaining P2 polish items before visual freeze: metadata scale/contrast, dark-surface hierarchy, replacement of old Kitchen Planner examples, stale-action copy, casual-language cleanup, old empty acreage, and contextual gamification.
3. Document component variants for empty/loading/working/partial/stale/failed/recovered/expired/confirmed states.
4. Verify focus order/return, accessible names, live regions, reduced motion, 200% zoom/reflow, localization expansion, and 44x44 touch targets in the interactive prototype and implementation.
5. Preserve provider names without logos for now, per the explicit product decision; do not remove the provider routes.
6. Do not modify application source until the user separately authorizes coding after UX acceptance.
