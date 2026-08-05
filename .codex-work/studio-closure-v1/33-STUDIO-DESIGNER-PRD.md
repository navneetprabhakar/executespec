# ExecuteSpec Studio - Designer PRD

Version: 1.1 designer handoff  
Date: 2026-07-31  
Checkpoint: `STUDIO-CP-018`  
Status: UX/product design authority; application coding is not authorized by this document  
Canonical Figma: <https://www.figma.com/design/LfEwxST9vagBMNJdVWn23L/ExecuteSpec-Studio>

## 1. Product promise

Turn an idea or requirement into a working, verified, publishable product with simplicity by default and professional control when the work requires it.

Studio is one product for two jobs: casual creation and professional individual delivery. Professional depth must preserve every durable Project, Task, Version, Check, Release, and history object.

## 2. Personas and tiers

- Casual builder: works in outcome/screen language and needs a preview-first managed path.
- Professional individual: works with client boundaries, repositories, CI, evidence, deployment, and handoff.
- Free ($0): make something real.
- Pro ($20/month): make it yours.
- Max ($100/month): deliver professionally.
- BYOK is an AI-provider/billing route, not a fourth Studio tier. Team and Enterprise are graduation destinations.

## 3. Durable information architecture

- Home: start/continue/import/open Project and Task.
- Project: Build, Data, Checks, and Ship lenses around one durable Task and inspectable Output.
- Account: six stable groups for identity/preferences, AI/usage, sources/local, data/secrets, publish/domains, and privacy/ownership.
- Project, Task, Brief, Version, Change, Check, Decision, Resource, Release, ClientWorkspace, ScopeBaseline, ChangeRequest, Handoff, and Progress are canonical objects.

## 4. Agent and interaction grammar

Ready, Working, Needs input, Complete, Needs attention, and Paused are user states. Non-trivial work may show Understand -> Build -> Verify -> Publish, but never invented percentages, tool chatter, hidden reasoning, or fake completeness.

All consequential actions must state target, effect, evidence/freshness, material cost, authority/expiry, and recovery/rollback. Messages guide work; durable state lives in product objects.

## 5. Journey-linked requirements

- [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview) - Turn an undeveloped idea into an honest, inspectable preview without requiring technical setup.
- [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change) - Make a bounded change, inspect before/after impact, and create a reversible Version.
- [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration) - Add a managed Resource safely and verify its permissions and behavior.
- [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence) - Understand a failure, choose a bounded response, and obtain fresh evidence without hiding uncertainty.
- [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback) - Publish an immutable Release, verify health, and recover through a known rollback anchor.
- [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state) - Resume work without reconstructing context from chat history.
- [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss) - Explain the exact gated outcome and preserve all durable work.
- [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity) - Change plan without forking the product, losing history, or confusing BYOK with Max.
- [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement) - Return to a trustworthy state without duplicating remote effects or losing local work.
- [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account) - Exercise ownership safely with complete export and destructive-action clarity.
- [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace) - Maintain absolute client separation while making the active boundary obvious.
- [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project) - Bind source with least privilege and preserve exact repository/branch/working-tree identity.
- [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context) - Build an evidence-linked Brief from code, documents, issues, designs, and prior decisions.
- [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan) - Convert a requirement into a bounded, priced, testable delivery commitment.
- [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control) - Execute the approved Plan within branch, provider, authority, time, and budget boundaries.
- [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand) - Produce trustworthy tests, validate the suite itself, execute in the right environment, and retain evidence.
- [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision) - Share only decision-relevant delivery evidence and capture acceptance or bounded change requests.
- [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary) - Move verified work to the agreed environment and transfer ownership without ambiguous access or obligations.
- [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage) - Reuse patterns/checklists while proving client code, secrets, identity, and proprietary context are excluded.
- [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history) - Move durable objects and evidence to a collaborative/governed product without rebuilding.

Every feature and screen described below must link to at least one journey. The full step, decision, recovery, evidence, responsive, and analytics contracts live in the linked journey document.

## 6. Functional requirements by journey

### Creation and editing

Idea/template/reference intake, one useful clarification, editable Brief, preview-first generation, direct selection, conversational change, before/after review, Version creation, compare, and restore.

**Journeys:** [J01 - Zero-information idea to working preview](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j01-zero-information-idea-to-working-preview), [J02 - Existing project to visual or conversational change](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j02-existing-project-to-visual-or-conversational-change)

### Resources and integrations

Managed data/auth/files/functions/integrations with least privilege, write-only secrets, explicit remote effects, and fresh verification.

**Journeys:** [J03 - Add data, sign-in, files, functions, or an integration](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j03-add-data-sign-in-files-functions-or-an-integration)

### Checks and evidence

Generated test plan/suite, suite validation, unit/contract/integration/E2E depth, CI and on-demand execution, cited failures, bounded repair, and freshness.

**Journeys:** [J04 - Failed check to bounded repair and fresh evidence](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j04-failed-check-to-bounded-repair-and-fresh-evidence), [J16 - Generate and validate integration-aware tests, then run CI or on demand](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j16-generate-and-validate-integration-aware-tests-then-run-ci-or-on-demand)

### Publish and release

Preview/staging/production targets, immutable manifest, consequence Decision, health window, reconciliation, rollback, client handoff, and maintenance boundary.

**Journeys:** [J05 - Preview to release, health evidence, and rollback](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j05-preview-to-release-health-evidence-and-rollback), [J18 - Deploy, hand off, and establish maintenance boundary](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j18-deploy-hand-off-and-establish-maintenance-boundary)

### Continuity and recovery

Return/resume state, offline/reconnect, stale/conflicting source, expired authority, partial remote effects, deterministic reconciliation, and safe terminal states.

**Journeys:** [J06 - Return later and understand current state](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j06-return-later-and-understand-current-state), [J09 - Recover source, authority, publish, connectivity, or state disagreement](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j09-recover-source-authority-publish-connectivity-or-state-disagreement)

### Commercial continuity

Contextual limit/upgrade, deliberate plan change, object-level tier depth, same-Task resume, downgrade continuity, receipts, export, and deletion safety.

**Journeys:** [J07 - Reach a limit without project or task loss](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j07-reach-a-limit-without-project-or-task-loss), [J08 - Upgrade or downgrade with durable continuity](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j08-upgrade-or-downgrade-with-durable-continuity)

### Ownership and privacy

Source/data/docs/evidence export, Project/account deletion, client offboarding, retention, access revocation, and sanitized reusable knowledge.

**Journeys:** [J10 - Export or delete source, data, documents, evidence, project, or account](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j10-export-or-delete-source-data-documents-evidence-project-or-account), [J19 - Promote sanitized reusable knowledge without client leakage](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j19-promote-sanitized-reusable-knowledge-without-client-leakage)

### Professional source and client boundary

Isolated ClientWorkspace, GitHub/Bitbucket/local import, dirty-tree safety, repository map, citations, freshness, and conflict resolution.

**Journeys:** [J11 - Create or switch an isolated client workspace](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j11-create-or-switch-an-isolated-client-workspace), [J12 - Import GitHub, Bitbucket, local, or existing Studio project](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j12-import-github-bitbucket-local-or-existing-studio-project), [J13 - Understand repository and connected context](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j13-understand-repository-and-connected-context)

### Professional scope and execution

Requirement intake, estimate range, ScopeBaseline, ChangeRequest, Plan/authority, provider/BYOK/budget controls, checkpointed implementation, diff, pause, and recovery.

**Journeys:** [J14 - Requirement to estimate, scope baseline, and plan](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j14-requirement-to-estimate-scope-baseline-and-plan), [J15 - Local or cloud implementation with route and budget control](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j15-local-or-cloud-implementation-with-route-and-budget-control)

### Client review and graduation

Redacted decision-only review, anchored comments/change requests, acceptance record, and Team/Enterprise migration with preserved history.

**Journeys:** [J17 - PR or preview to client review and decision](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j17-pr-or-preview-to-client-review-and-decision), [J20 - Graduate to Team or Enterprise with preserved history](32-STUDIO-USER-JOURNEYS-AND-FIGMA-FLOWS.md#j20-graduate-to-team-or-enterprise-with-preserved-history)

## 7. Screen authority

The exact 200-screen catalogue is defined in [31-STUDIO-SCREEN-BY-SCREEN-DESIGN-HANDOFF.md](31-STUDIO-SCREEN-BY-SCREEN-DESIGN-HANDOFF.md). P8/P9 contains 57 implementation-authority screens; P3-P5 contains 143 reference-only screens. Eighteen reference-authority banners are annotations, not screens.

### Canonical P8/P9 families

- Builder and Output: R01-R09.
- Trust and Account grouping: R10-R13.
- Brief, Plan, and professional objects: R14-R22.
- External operation and tier depth: R23-R31.
- Account workflows: R32-R37.
- Responsive authority: RT01-RT06, RM01-RM08, PT07-PT09, PM09-PM11.

## 8. Responsive contract

Desktop exposes three panes with independently collapsible/resizable sides. Tablet preserves one rail and uses drawers/sheets. Mobile shows one primary Task or Output surface. All supported layouts preserve decisions, selection, checkpoint, composer draft, focus return, evidence, publish/rollback, and recovery. Complex source editing may hand off honestly without losing Task state.

## 9. Accessibility and content

Target WCAG 2.2 AA; keyboard-complete navigation; visible focus; deterministic focus return; 44x44 touch targets; status not color-only; reduced motion; live regions for progress/results; 200% zoom/reflow; localization expansion; plain product language by default; no secret values, hidden reasoning, internal tier identifiers, or fake completion.

## 10. Analytics and acceptance

The north-star candidate is the share of activated users who reach a retained, verified, user-valued outcome within 30 days without support intervention or unsafe recovery. Journey analytics are specified in the linked journey chapters. Release requires playable happy/recovery paths, exact requirement traceability, responsive coverage, zero P0/P1 design blockers, and runtime accessibility/authorization/redaction/reconciliation acceptance.

## 11. Remaining design work

Seven P2 refinements remain before visual freeze: metadata scale/contrast, dark-surface hierarchy, replacement of old Kitchen Planner examples, stale-action copy, casual-language cleanup, old empty acreage, and contextual gamification. Provider/model routes remain represented by names; provider logos are intentionally omitted until the external issue is resolved.

## 12. Scope boundary

This PRD and its linked files authorize UX/product design refinement only. They do not authorize application source, migration, configuration, CI, generated-client, package-manifest, or repository-architecture changes.
