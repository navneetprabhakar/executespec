# ExecuteSpec Studio - User Journeys and Figma Flows

Date: 2026-07-31  
Checkpoint: `STUDIO-CP-018`  
Status: Designer handoff authority for journey intent, state transitions, and Figma navigation  
Canonical Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio>

## How to use this document

Each canonical journey below defines the user's goal, preconditions, trigger, happy path, decisions, recovery, success evidence, responsive behavior, and analytics. Screen links open the exact Figma node. The 20 named Figma flow starts are listed after the journeys. P3-P5 screens are reference-only; P8/P9 screens are implementation design authority.

## Journey index

- [J01 - Zero-information idea to working preview](#j01-zero-information-idea-to-working-preview)
- [J02 - Existing project to visual or conversational change](#j02-existing-project-to-visual-or-conversational-change)
- [J03 - Add data, sign-in, files, functions, or an integration](#j03-add-data-sign-in-files-functions-or-an-integration)
- [J04 - Failed check to bounded repair and fresh evidence](#j04-failed-check-to-bounded-repair-and-fresh-evidence)
- [J05 - Preview to release, health evidence, and rollback](#j05-preview-to-release-health-evidence-and-rollback)
- [J06 - Return later and understand current state](#j06-return-later-and-understand-current-state)
- [J07 - Reach a limit without project or task loss](#j07-reach-a-limit-without-project-or-task-loss)
- [J08 - Upgrade or downgrade with durable continuity](#j08-upgrade-or-downgrade-with-durable-continuity)
- [J09 - Recover source, authority, publish, connectivity, or state disagreement](#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- [J10 - Export or delete source, data, documents, evidence, project, or account](#j10-export-or-delete-source-data-documents-evidence-project-or-account)
- [J11 - Create or switch an isolated client workspace](#j11-create-or-switch-an-isolated-client-workspace)
- [J12 - Import GitHub, Bitbucket, local, or existing Studio project](#j12-import-github-bitbucket-local-or-existing-studio-project)
- [J13 - Understand repository and connected context](#j13-understand-repository-and-connected-context)
- [J14 - Requirement to estimate, scope baseline, and plan](#j14-requirement-to-estimate-scope-baseline-and-plan)
- [J15 - Local or cloud implementation with route and budget control](#j15-local-or-cloud-implementation-with-route-and-budget-control)
- [J16 - Generate and validate integration-aware tests, then run CI or on demand](#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- [J17 - PR or preview to client review and decision](#j17-pr-or-preview-to-client-review-and-decision)
- [J18 - Deploy, hand off, and establish maintenance boundary](#j18-deploy-hand-off-and-establish-maintenance-boundary)
- [J19 - Promote sanitized reusable knowledge without client leakage](#j19-promote-sanitized-reusable-knowledge-without-client-leakage)
- [J20 - Graduate to Team or Enterprise with preserved history](#j20-graduate-to-team-or-enterprise-with-preserved-history)

## J01 - Zero-information idea to working preview

**Persona:** Casual builder - all tiers  
**Goal:** Turn an undeveloped idea into an honest, inspectable preview without requiring technical setup.  
**Preconditions:** Signed-in or guest-ready Studio; no project data is required.  
**Trigger:** The user describes an idea, selects a template/reference, or starts blank.

### Journey sequence

[R01 - Home / Projects and tasks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7666) -> [R14 - Brief / Editable assumptions](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8477) -> [R02 - Builder / Preview selection](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7727) -> [R04 - Output / Preview](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7898)

### Decision and authority

Ask one focused clarification only when the answer changes the product outcome, cost, exposure, or feasibility. Assumptions remain editable until approved.

### Failure and recovery

If context is insufficient, preserve the draft Brief and offer one clarification. If generation fails, retain the Task and expose retry, modify, or safe stop.

### Successful completion

A live or explicitly mocked Preview opens with a durable Project, Task, Brief, and Version.

### Evidence the UX must expose

Brief acceptance, preview runtime state, source freshness, changed objects, and verification status.

### Responsive behavior

Desktop uses three panes; tablet keeps Task visible while Output opens as a sheet; mobile keeps Task primary and switches to a dedicated Output surface.

### Analytics contract

- `studio_project_started`
- `studio_brief_accepted`
- `studio_first_preview_ready`

**Figma authority start:** [Open J01 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7666)

## J02 - Existing project to visual or conversational change

**Persona:** Casual builder and professional individual  
**Goal:** Make a bounded change, inspect before/after impact, and create a reversible Version.  
**Preconditions:** A Project and current Version exist; the user has write authority.  
**Trigger:** The user selects an object in Preview or describes a change in the Task composer.

### Journey sequence

[R01 - Home / Projects and tasks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7666) -> [R02 - Builder / Preview selection](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7727) -> [R03 - Change review / Before after](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7814) -> [R05 - Output / Changes](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7973) -> [D19 - Versions](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-580) -> [D20 - Compare](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-675) -> [D21 - Restore](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-770)

### Decision and authority

Material, destructive, public, or billable changes require a Decision; ordinary reversible edits do not.

### Failure and recovery

On conflict, show before/after, affected objects, latest source, and choices to rebase, discard, or create a new Task.

### Successful completion

A new Version is created with synchronized Preview, Changes, evidence, and restore point.

### Evidence the UX must expose

Selected object, requested intent, before/after diff, affected objects, checkpoint identifier, and fresh Checks.

### Responsive behavior

Tablet uses synchronized split compare; mobile uses a swipe compare with stable screen-reader order and explicit exit.

### Analytics contract

- `studio_change_requested`
- `studio_change_reviewed`
- `studio_version_created`
- `studio_version_restored`

**Figma authority start:** [Open J02 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-365)

## J03 - Add data, sign-in, files, functions, or an integration

**Persona:** Casual builder with progressive professional depth  
**Goal:** Add a managed Resource safely and verify its permissions and behavior.  
**Preconditions:** A Project exists; the requested Resource is supported by the current tier or an upgrade choice is available.  
**Trigger:** The user asks for data/auth/storage/function/integration capability or opens Data.

### Journey sequence

[R06 - Output / Data](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8046) -> [D11 - Authentication](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-363) -> [D12 - Storage and Functions](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-447) -> [D13 - API and Secret](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-529) -> [R10 - Trust / Pending](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8336) -> [R12 - Trust / Confirmed](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8488) -> [R07 - Output / Checks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8118)

### Decision and authority

Credentials, scopes, external writes, public exposure, and material cost require a bounded Decision with expiry and recovery.

### Failure and recovery

Unavailable sources offer reconnect or a safe managed alternative; secret values are write-only and never echoed; partial creation is reconciled before retry.

### Successful completion

The Resource is durable, permission-scoped, visible in Data, linked to a Version, and covered by fresh Checks.

### Evidence the UX must expose

Schema/access policy, connection identity, secret reference not value, remote effect receipt, and verification evidence.

### Responsive behavior

Tablet/mobile show resource summaries and decision sheets; dense schema editing may hand off to desktop without losing Task state.

### Analytics contract

- `studio_resource_requested`
- `studio_resource_decision_opened`
- `studio_resource_verified`

**Figma authority start:** [Open J03 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-289)

## J04 - Failed check to bounded repair and fresh evidence

**Persona:** All Studio users  
**Goal:** Understand a failure, choose a bounded response, and obtain fresh evidence without hiding uncertainty.  
**Preconditions:** At least one Check is failed, partial, stale, inconclusive, or unmeasured.  
**Trigger:** A Check completes with a non-passing state or the user opens Checks.

### Journey sequence

[R07 - Output / Checks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8118) -> [R09 - Output / Evidence](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8263) -> [D18 - Check Failure and Repair](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=18-767) -> [R10 - Trust / Pending](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8336) -> [R12 - Trust / Confirmed](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8488) -> [R07 - Output / Checks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8118)

### Decision and authority

Autofix requires explicit scope, affected objects, cost if material, and rollback; retry alone does not imply a code change.

### Failure and recovery

Offer retry, bounded repair, revert, quarantine/flaky handling, reconnect runner, or acknowledge unmeasured status. Never relabel stale evidence as passing.

### Successful completion

The Check ledger records a fresh outcome and the user can inspect exact assertions, captures/logs, and changed objects.

### Evidence the UX must expose

Check source, freshness, pass/fail counts, assertion, affected behavior, repair diff, and receipt.

### Responsive behavior

Mobile opens a failure sheet and returns to the same assertion; tablet keeps Task context while evidence opens beside it.

### Analytics contract

- `studio_check_failed`
- `studio_repair_decision`
- `studio_check_rerun`
- `studio_check_recovered`

**Figma authority start:** [Open J04 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=18-767)

## J05 - Preview to release, health evidence, and rollback

**Persona:** All tiers with destination depth by entitlement  
**Goal:** Publish an immutable Release, verify health, and recover through a known rollback anchor.  
**Preconditions:** A Preview exists and required Checks meet the destination policy.  
**Trigger:** The user requests publish/deploy or opens Ship.

### Journey sequence

[R08 - Output / Release](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8190) -> [R10 - Trust / Pending](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8336) -> [R12 - Trust / Confirmed](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8488) -> [R20 - Professional / Deployment](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9224) -> [D24 - Published](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=19-1072) -> [D25 - Health Degraded](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-712) -> [D26 - Rollback Complete](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-873)

### Decision and authority

Confirm target, exposure, immutable manifest, cost, authority, freshness, and rollback before an external effect.

### Failure and recovery

Failed or partial deployment preserves the previous healthy Release; show reconciliation and rollback options with remote/durable state.

### Successful completion

The Release is healthy or safely rolled back, with immutable evidence and a visible next action.

### Evidence the UX must expose

Manifest, destination, revision, health window, Check ledger, receipt, and rollback anchor.

### Responsive behavior

Publish and rollback remain complete on tablet/mobile; mobile uses a full-width decision sheet and status timeline.

### Analytics contract

- `studio_publish_reviewed`
- `studio_release_created`
- `studio_release_health_changed`
- `studio_release_rolled_back`

**Figma authority start:** [Open J05 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-3)

## J06 - Return later and understand current state

**Persona:** Returning user - all tiers  
**Goal:** Resume work without reconstructing context from chat history.  
**Preconditions:** A prior Project or Task exists in any durable state.  
**Trigger:** The user returns to Home or reopens a Project.

### Journey sequence

[R01 - Home / Projects and tasks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7666) -> [R23 - Operation / Running](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9350) -> [R24 - Operation / Reconnecting](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9455) -> [R25 - Operation / Partial result](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9561) -> [R26 - Operation / Reconciled](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9667) -> [R27 - Operation / Failed](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9788) -> [R28 - Operation / Cancelled](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9894)

### Decision and authority

If remote and durable state disagree, block new consequential work until reconciliation is explicit.

### Failure and recovery

Show Running, Reconnecting, Partial, Reconciled, Failed, Cancelled, Paused, or Needs input with checkpoint and next action.

### Successful completion

The user knows what is happening, what changed, what is blocked, and the safest next action within one screen.

### Evidence the UX must expose

Task state, semantic phase, checkpoint, remote effect, freshness, last evidence, and recovery route.

### Responsive behavior

Drawers and surface switches preserve selected Project/Task, scroll, focus, and composer draft.

### Analytics contract

- `studio_task_resumed`
- `studio_operation_state_viewed`
- `studio_reconciliation_completed`

**Figma authority start:** [Open J06 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9350)

## J07 - Reach a limit without project or task loss

**Persona:** Free or Pro user  
**Goal:** Explain the exact gated outcome and preserve all durable work.  
**Preconditions:** The user reaches a capacity, frequency, destination, retention, or advanced-control limit.  
**Trigger:** A requested action crosses the current entitlement.

### Journey sequence

[D34 - Free Limit](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1126) -> [R29 - Tier / Free object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9999) -> [R30 - Tier / Pro object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10154) -> [R31 - Tier / Max object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10309) -> [D35 - Upgrade and Resume](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1234)

### Decision and authority

Upgrade, top-up, wait, reduce scope, export, or continue read-only must be shown in the context of the requested outcome.

### Failure and recovery

If payment or entitlement refresh fails, remain on the same Task with unchanged Project, Versions, Checks, and Release history.

### Successful completion

The user chooses a valid route and the same Task resumes or remains safely readable/exportable.

### Evidence the UX must expose

Limit type, current usage, requested outcome, preserved objects, price/consequence, and entitlement receipt.

### Responsive behavior

Use a sheet/dialog without replacing the work surface; return focus to the invoking action.

### Analytics contract

- `studio_limit_reached`
- `studio_upgrade_intent`
- `studio_same_task_resumed`

**Figma authority start:** [Open J07 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1126)

## J08 - Upgrade or downgrade with durable continuity

**Persona:** All paid Studio users  
**Goal:** Change plan without forking the product, losing history, or confusing BYOK with Max.  
**Preconditions:** A Studio account and at least one Project exist.  
**Trigger:** The user deliberately changes plan or accepts a contextual upgrade.

### Journey sequence

[D33 - Professional Graduation](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1019) -> [D35 - Upgrade and Resume](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1234) -> [D36 - Billing and Downgrade](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1335) -> [R29 - Tier / Free object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9999) -> [R30 - Tier / Pro object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10154) -> [R31 - Tier / Max object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10309) -> [R33 - Account / AI and usage](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10344)

### Decision and authority

Show feature/capacity consequences at object level; BYOK remains an AI-provider route, not a tier.

### Failure and recovery

Downgrade never blocks export/deletion/recovery. Ineligible active capabilities become clearly limited or read-only with a remediation path.

### Successful completion

Plan and entitlement update while Projects, Tasks, Versions, Checks, Releases, and history retain identity.

### Evidence the UX must expose

Old/new tier, effective date, usage/retention impact, active objects, billing receipt, and recovery/support path.

### Responsive behavior

Account plan flows work on mobile; dense comparison may open a full-screen table with accessible headings.

### Analytics contract

- `studio_plan_change_started`
- `studio_plan_change_confirmed`
- `studio_entitlement_refreshed`

**Figma authority start:** [Open J08 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1126)

## J09 - Recover source, authority, publish, connectivity, or state disagreement

**Persona:** All users; professional depth when source/client boundaries apply  
**Goal:** Return to a trustworthy state without duplicating remote effects or losing local work.  
**Preconditions:** A Task is offline, stale, disconnected, expired, permission-blocked, partially published, or inconsistent with durable state.  
**Trigger:** A recovery condition is detected or the user returns during one.

### Journey sequence

[D39 - Offline and Stale Recovery](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=25-1165) -> [D54 - Context Freshness and Conflict](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1950) -> [D14 - Integration Failure](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-614) -> [R11 - Trust / Expired](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8414) -> [R24 - Operation / Reconnecting](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9455) -> [R25 - Operation / Partial result](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9561) -> [R26 - Operation / Reconciled](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9667) -> [R27 - Operation / Failed](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9788)

### Decision and authority

Refresh authority only for the same scoped action; never replay a remote effect solely because the UI timed out.

### Failure and recovery

Offer reconnect, re-authenticate, refresh context, reconcile, retry from checkpoint, restore, rollback, export, or safe stop according to cause.

### Successful completion

Remote and durable state agree, or the product stops in an honest state with preserved evidence and a specific next action.

### Evidence the UX must expose

Checkpoint, idempotency/effect identifier, source SHA/freshness, authority expiry, remote receipt, reconciliation result.

### Responsive behavior

Offline banners persist across Task/Output switching; composer drafts and selection survive reconnect and rotation.

### Analytics contract

- `studio_recovery_detected`
- `studio_recovery_action_selected`
- `studio_state_reconciled`

**Figma authority start:** [Open J09 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=25-1165)

## J10 - Export or delete source, data, documents, evidence, project, or account

**Persona:** All Studio users  
**Goal:** Exercise ownership safely with complete export and destructive-action clarity.  
**Preconditions:** The user owns or is authorized for the target objects.  
**Trigger:** The user requests export, Project deletion, client offboarding, or account deletion.

### Journey sequence

[D28 - Remix and Export](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=20-1138) -> [D37 - Privacy and Ownership](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=24-1098) -> [D38 - Delete Project Decision](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=24-1233) -> [R13 - Account / Six groups](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8561) -> [R37 - Account / Privacy and ownership](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10907)

### Decision and authority

Deletion requires target, effect, retained/deleted objects, dependency impact, re-authentication where needed, and cancellation before commitment.

### Failure and recovery

Export failure is resumable. Deletion failure reports exactly what was removed and what remains. Client boundaries are checked before action.

### Successful completion

Export is downloadable and verified, or deletion completes with a durable receipt and recovery/retention statement.

### Evidence the UX must expose

Manifest of included/excluded objects, hashes where appropriate, deletion receipt, retention window, and access revocation.

### Responsive behavior

Mobile supports review/confirm/status; large exports download asynchronously with notification and Account history.

### Analytics contract

- `studio_export_requested`
- `studio_export_ready`
- `studio_destructive_decision`
- `studio_deletion_completed`

**Figma authority start:** [Open J10 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=24-1233)

## J11 - Create or switch an isolated client workspace

**Persona:** Professional individual - Max  
**Goal:** Maintain absolute client separation while making the active boundary obvious.  
**Preconditions:** Max entitlement and professional mode; user has client-workspace authority.  
**Trigger:** The user creates, switches, reviews, or offboards a ClientWorkspace.

### Journey sequence

[D41 - Professional Mode Introduction](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1239) -> [D44 - Client Workspaces Overview](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1496) -> [D45 - Create Client Workspace](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1583) -> [D46 - Switch Client Boundary](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1668) -> [D47 - Offboard Client Workspace](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1753) -> [R21 - Professional / Client review](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9356) -> [R22 - Professional / Handoff](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9472)

### Decision and authority

Switching with unsaved/local work, active credentials, or pending external actions requires explicit consequence and safe handling.

### Failure and recovery

Boundary mismatch blocks the action and identifies conflicting client, source, credential, memory, or usage objects.

### Successful completion

The intended ClientWorkspace is active; projects, sources, secrets, memory, usage, and review links remain isolated.

### Evidence the UX must expose

Persistent client identity, isolation policy, active objects, pending effects, access list, and offboarding evidence.

### Responsive behavior

Tablet/mobile always display the active client boundary and require deliberate switching through a drawer/sheet.

### Analytics contract

- `studio_client_workspace_created`
- `studio_client_boundary_switched`
- `studio_boundary_blocked`

**Figma authority start:** [Open J11 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1239)

## J12 - Import GitHub, Bitbucket, local, or existing Studio project

**Persona:** Professional individual - Max  
**Goal:** Bind source with least privilege and preserve exact repository/branch/working-tree identity.  
**Preconditions:** Professional mode; provider/local permission route is available.  
**Trigger:** The user chooses an import source.

### Journey sequence

[D42 - Import Existing Repository](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1324) -> [D48 - Connect GitHub](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1840) -> [D49 - Connect Bitbucket Cloud](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1519) -> [D50 - Bind Local Workspace](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1604) -> [D51 - Branch and Dirty-Tree Safety](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1689) -> [R17 - Professional / Repository context](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8865) -> [R34 - Account / Sources and local](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10485)

### Decision and authority

Choose repository, scopes, branch/PR policy, local permissions, dirty-tree handling, and client boundary before mutation.

### Failure and recovery

Denied/unavailable source offers reconnect; dirty state offers checkpoint/stash/new branch/cancel; partial import exposes ready and failed objects separately.

### Successful completion

Source is bound at an exact identity and Repository context is ready for inspection.

### Evidence the UX must expose

Provider/account, scopes, repo, branch, commit, dirty state, local path permission, webhook/CI status, and import receipt.

### Responsive behavior

Mobile/tablet allow status, decisions, and reconnect; dense source binding may hand off to desktop/CLI with a continuation token.

### Analytics contract

- `studio_source_import_started`
- `studio_source_authorized`
- `studio_source_bound`

**Figma authority start:** [Open J12 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-614)

## J13 - Understand repository and connected context

**Persona:** Professional individual - Max  
**Goal:** Build an evidence-linked Brief from code, documents, issues, designs, and prior decisions.  
**Preconditions:** One or more sources are connected and access is current.  
**Trigger:** The user opens Repository context or asks a project-scoped question.

### Journey sequence

[D52 - Repository Map](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1776) -> [D53 - Project Brief and Connected Context](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1863) -> [D54 - Context Freshness and Conflict](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1950) -> [R14 - Brief / Editable assumptions](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8477) -> [R15 - Brief / Citation conflict](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8611) -> [R17 - Professional / Repository context](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8865)

### Decision and authority

Conflicting citations prevent approval; low-confidence or stale evidence is labeled and refreshable.

### Failure and recovery

Missing source yields partial answers with known gaps. Refresh pins a new source identity and preserves prior cited conclusions for comparison.

### Successful completion

The user can inspect repository map, citations, freshness, assumptions, affected objects, and unresolved questions.

### Evidence the UX must expose

Exact source identity, citation type, confidence/freshness, conflicting evidence, repository boundaries, and Brief impact.

### Responsive behavior

Tablet/mobile show summaries and citations; complex maps open read-only with desktop handoff.

### Analytics contract

- `studio_context_generated`
- `studio_context_conflict_detected`
- `studio_brief_citation_opened`

**Figma authority start:** [Open J13 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1950)

## J14 - Requirement to estimate, scope baseline, and plan

**Persona:** Professional individual - Max  
**Goal:** Convert a requirement into a bounded, priced, testable delivery commitment.  
**Preconditions:** Client/source/context are selected; requirement is available.  
**Trigger:** The user submits a requirement, issue, document, or design.

### Journey sequence

[D55 - Requirement Intake](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-2037) -> [D56 - Clarifications and Assumptions](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-2122) -> [D57 - Estimate Range and Risk](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-1799) -> [D58 - Scope Baseline and Change Request](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-1886) -> [R14 - Brief / Editable assumptions](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8477) -> [R15 - Brief / Citation conflict](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8611) -> [R18 - Professional / Scope baseline](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8995) -> [R16 - Plan / Approved impact](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8730)

### Decision and authority

Clarifications, assumptions, exclusions, estimate range, risks, dependencies, acceptance intent, and object impact remain editable before approval.

### Failure and recovery

Scope divergence creates a ChangeRequest; rejected/expired/invalidated approval preserves the draft and explains the next valid action.

### Successful completion

An accepted ScopeBaseline and approved Plan are attached to the Task with an immutable receipt.

### Evidence the UX must expose

Requirement citations, estimate range, exclusions, dependencies, risks, test plan, object impact, budget, authority, and receipt.

### Responsive behavior

Responsive views preserve edit/conflict/approved states; mobile focuses one decision or section at a time.

### Analytics contract

- `studio_scope_proposed`
- `studio_scope_accepted`
- `studio_plan_approved`

**Figma authority start:** [Open J14 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8477)

## J15 - Local or cloud implementation with route and budget control

**Persona:** Professional individual - Max  
**Goal:** Execute the approved Plan within branch, provider, authority, time, and budget boundaries.  
**Preconditions:** Approved Plan and exact source identity; route and credentials are usable.  
**Trigger:** The user starts implementation or approves a bounded continuation.

### Journey sequence

[R16 - Plan / Approved impact](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-8730) -> [D60 - Authority and Control Level](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2056) -> [D61 - Provider BYOK and Limits](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2141) -> [D62 - Execution Route and Plan Block](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2226) -> [D63 - Implementation Working](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2313) -> [D64 - Diff Inspection](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2400) -> [R23 - Operation / Running](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-9350)

### Decision and authority

Local/cloud route, provider/model/BYOK, branch, budget, cost cap, and external effects are explicit; expired authority cannot be reused.

### Failure and recovery

Pause preserves checkpoint. Provider/route failure offers same-scope retry, approved alternative, or safe stop. Partial results stay inspectable.

### Successful completion

Changes are checkpointed and ready for review with attributable cost and exact source identity.

### Evidence the UX must expose

Plan receipt, branch/SHA, provider route, budget/cost, changed files/objects, checkpoints, and operation state.

### Responsive behavior

Mobile/tablet supervise, decide, pause, and inspect summaries; dense editing honestly hands off to desktop/CLI/IDE.

### Analytics contract

- `studio_implementation_started`
- `studio_implementation_paused`
- `studio_implementation_completed`

**Figma authority start:** [Open J15 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2226)

## J16 - Generate and validate integration-aware tests, then run CI or on demand

**Persona:** Professional individual - Max  
**Goal:** Produce trustworthy tests, validate the suite itself, execute in the right environment, and retain evidence.  
**Preconditions:** Implementation changes and test topology are available.  
**Trigger:** The user enters Verify, requests test generation, or a policy auto-triggers validation.

### Journey sequence

[D67 - Test Topology and Impact](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2251) -> [D68 - Generated Test Suite](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2338) -> [D69 - Test Suite Validation](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2423) -> [D70 - Integration Environment and CI Trigger](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2510) -> [D71 - CI Failure and Evidence](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2595) -> [R19 - Professional / CI validation](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9117) -> [R07 - Output / Checks](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8118) -> [R09 - Output / Evidence](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-8263)

### Decision and authority

Generated tests are not trusted until suite validation passes; CI/environment/secret scopes and billable triggers require clear authority.

### Failure and recovery

Invalid suites return to bounded generation repair; failed CI links exact assertion/log/source; flaky behavior offers retry/quarantine with explicit confidence impact.

### Successful completion

A fresh evidence ledger records suite validity, environment, CI/on-demand result, and affected acceptance intent.

### Evidence the UX must expose

Test topology, generated/changed tests, suite-validation result, environment/config identity, CI run, assertions/logs/captures, and freshness.

### Responsive behavior

Tablet/mobile show topology/result/evidence and allow rerun/repair decisions; editing test code may hand off.

### Analytics contract

- `studio_test_suite_generated`
- `studio_test_suite_validated`
- `studio_ci_triggered`
- `studio_ci_completed`

**Figma authority start:** [Open J16 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-778)

## J17 - PR or preview to client review and decision

**Persona:** Professional individual and scoped client reviewer  
**Goal:** Share only decision-relevant delivery evidence and capture acceptance or bounded change requests.  
**Preconditions:** Verified PR/Preview and client-review package exist.  
**Trigger:** The professional invites a reviewer or shares a scoped review link.

### Journey sequence

[D72 - Preview and Environment Targets](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2682) -> [D76 - Client Review Package](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2618) -> [D77 - Decision-Only Client View](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2703) -> [R21 - Professional / Client review](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9356) -> [D78 - Client Changes Requested](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2788) -> [D79 - Client Accepted and Handoff](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2875)

### Decision and authority

Reviewer scope, expiry, redaction, visible artifacts, and allowed decision are fixed before sharing.

### Failure and recovery

Expired/revoked access denies safely. Change requests anchor to artifact/version and return to the same Scope/Task without leaking internal context.

### Successful completion

The client accepts or submits a traceable ChangeRequest; the professional retains an immutable decision record.

### Evidence the UX must expose

Package manifest, redaction policy, reviewer identity/expiry, anchored comments, decision, and resulting Task/Scope link.

### Responsive behavior

Decision-only view is mobile-complete; professional workbench remains separate from client view.

### Analytics contract

- `studio_client_review_shared`
- `studio_client_change_requested`
- `studio_client_accepted`

**Figma authority start:** [Open J17 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1239)

## J18 - Deploy, hand off, and establish maintenance boundary

**Persona:** Professional individual - Max  
**Goal:** Move verified work to the agreed environment and transfer ownership without ambiguous access or obligations.  
**Preconditions:** Accepted delivery package, release authority, and target environment.  
**Trigger:** The professional initiates deploy/handoff/offboarding.

### Journey sequence

[D73 - Deploy Review](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2359) -> [D74 - Deploying and Post-Deploy Verification](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2444) -> [R20 - Professional / Deployment](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9224) -> [R21 - Professional / Client review](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9356) -> [R22 - Professional / Handoff](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9472) -> [D80 - Project Documents](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2962)

### Decision and authority

Confirm immutable manifest, target, canary/health window, rollback, ownership, credentials, access revocation, and maintenance terms.

### Failure and recovery

Deployment failure retains previous Release. Handoff cannot complete with unresolved credential ownership or active unauthorized access.

### Successful completion

Healthy Release and complete Handoff package with accepted ownership and explicit maintenance boundary.

### Evidence the UX must expose

Release manifest/health, rollback anchor, handoff artifacts, owner mapping, credential transfer policy, revocation evidence, and maintenance terms.

### Responsive behavior

Tablet/mobile provide review, decision, health, rollback, and handoff status; large artifact inspection may hand off.

### Analytics contract

- `studio_deployment_started`
- `studio_handoff_reviewed`
- `studio_handoff_completed`

**Figma authority start:** [Open J18 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2531)

## J19 - Promote sanitized reusable knowledge without client leakage

**Persona:** Professional individual - Max  
**Goal:** Reuse patterns/checklists while proving client code, secrets, identity, and proprietary context are excluded.  
**Preconditions:** Completed client work and eligible reusable insight.  
**Trigger:** The user proposes a template, checklist, pattern, or memory promotion.

### Journey sequence

[D81 - Templates and Checklist Library](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2639) -> [D82 - Sanitized Memory Promotion](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=38-2726) -> [R22 - Professional / Handoff](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=122-9472) -> [R37 - Account / Privacy and ownership](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10907)

### Decision and authority

Promotion requires source client, included abstraction, redaction proof, destination personal library, and rollback/delete path.

### Failure and recovery

Any suspected client-specific content blocks promotion and identifies the evidence requiring removal or review.

### Successful completion

A sanitized reusable object is created with provenance and no client-bound data.

### Evidence the UX must expose

Source/destination boundaries, redaction findings, included/excluded content, reviewer/authority, and promotion receipt.

### Responsive behavior

Responsive surfaces support decision/review; dense redaction inspection may require desktop.

### Analytics contract

- `studio_memory_promotion_proposed`
- `studio_memory_promotion_blocked`
- `studio_memory_promoted`

**Figma authority start:** [Open J19 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3091)

## J20 - Graduate to Team or Enterprise with preserved history

**Persona:** Professional individual outgrowing Studio  
**Goal:** Move durable objects and evidence to a collaborative/governed product without rebuilding.  
**Preconditions:** Studio Project has Team/Enterprise intent and eligible ownership.  
**Trigger:** The user selects graduation from Professional Mode or Account.

### Journey sequence

[D33 - Professional Graduation](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1019) -> [D92 - Graduate to Team](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3178) -> [D93 - Graduate to Enterprise](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3263) -> [R31 - Tier / Max object depth](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=124-10309) -> [R37 - Account / Privacy and ownership](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=126-10907)

### Decision and authority

Show identity, object, policy, billing, client-boundary, provider, and access mapping; conflicts require explicit resolution.

### Failure and recovery

Unsupported policy or identity conflict pauses migration while Studio remains usable/readable; no destructive cutover without verification.

### Successful completion

Target workspace contains mapped Projects, Tasks, Versions, Checks, Releases, Handoffs, and history with a migration receipt.

### Evidence the UX must expose

Source/target identities, object counts, policy conflicts, access mapping, migration checks, and rollback/support window.

### Responsive behavior

Mobile supports review/status; complex mapping opens desktop without losing the migration Task.

### Analytics contract

- `studio_graduation_started`
- `studio_graduation_conflict`
- `studio_graduation_completed`

**Figma authority start:** [Open J20 in Figma](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3263)

## Named Figma prototype flows

These are the 20 current flow starts in the file. A flow may demonstrate several canonical journeys; journey links are the product interpretation authority.

### F01 - Studio - Core build, publish, health, and rollback

- Figma start: [open node `13:3`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-3)
- Journeys: [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F02 - Studio - Visual edit, Version, compare, and restore

- Figma start: [open node `13:365`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=13-365)
- Journeys: [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F03 - Studio - Data, API, secret, and checks

- Figma start: [open node `15:289`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-289)
- Journeys: [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F04 - Studio - Limit, upgrade, and same-Task resume

- Figma start: [open node `23:1126`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=23-1126)
- Journeys: [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F05 - Studio - Professional client delivery and handoff

- Figma start: [open node `32:1239`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=32-1239)
- Journeys: [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F06 - Recovery - Offline workspace

- Figma start: [open node `25:1165`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=25-1165)
- Journeys: [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F07 - Recovery - Stale or conflicting context

- Figma start: [open node `34:1950`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=34-1950)
- Journeys: [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F08 - Recovery - Source or integration unavailable

- Figma start: [open node `15:614`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-614)
- Journeys: [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F09 - Recovery - Failed checks

- Figma start: [open node `18:767`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=18-767)
- Journeys: [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F10 - Recovery - Invalid generated test suite

- Figma start: [open node `15:778`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=15-778)
- Journeys: [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F11 - Recovery - Failed CI

- Figma start: [open node `36:2595`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=36-2595)
- Journeys: [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F12 - Recovery - Deployment failure and rollback

- Figma start: [open node `37:2531`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=37-2531)
- Journeys: [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F13 - Recovery - Permission denied

- Figma start: [open node `39:2919`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-2919)
- Journeys: [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F14 - Recovery - Expired or insufficient authority

- Figma start: [open node `35:2226`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=35-2226)
- Journeys: [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F15 - Recovery - Cancel personal project deletion

- Figma start: [open node `24:1233`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=24-1233)
- Journeys: [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F16 - Recovery - Cancel professional destructive action

- Figma start: [open node `39:3006`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3006)
- Journeys: [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F17 - Recovery - Client boundary mismatch

- Figma start: [open node `39:3091`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=39-3091)
- Journeys: [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F18 - Studio P0/P1 authority - Desktop

- Figma start: [open node `108:7666`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=108-7666)
- Journeys: [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change), [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F19 - Studio P0/P1 authority - Tablet

- Figma start: [open node `109:8168`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8168)
- Journeys: [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change), [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

### F20 - Studio P0/P1 authority - Mobile

- Figma start: [open node `109:8341`](https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio?node-id=109-8341)
- Journeys: [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change), [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration), [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement), [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context), [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand), [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)
- Interaction rule: no modal or sheet may dead-end; dismiss/back returns to the invoking Task state with selection, focus, composer draft, and durable checkpoint preserved.

## Global transition rules

1. Project and Task identity persist across drawers, sheets, Output tabs, decisions, reconnects, and viewport changes.
2. Consequential actions use the same target/effect/evidence/authority/expiry/recovery grammar.
3. External-operation timeouts do not imply failure or authorize replay; reconcile remote and durable state first.
4. Back, close, cancel, and escape return to the invoking composition with deterministic focus return.
5. Reference-only P3-P5 frames may guide content/state intent but must not override P8/P9 navigation, builder, Output, trust, operation, tier, Account, or responsive grammar.
6. Runtime keyboard, screen-reader, reduced-motion, zoom/reflow, localization, authorization, redaction, and durable reconciliation remain implementation acceptance gates.
