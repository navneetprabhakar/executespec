# ExecuteSpec Enterprise - Designer PRD

Version: 1.0 formal external UX-designer handoff; plain-English companion is document 26  
Date: 2026-07-31  
Checkpoint: `CP-077`  
Status: Enterprise product/UX authority; runtime implementation verification remains  
Canonical Figma: <https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise>

> Reader note: this file preserves the concise formal product contract. For non-technical explanations, real-world examples, and a plain-English explanation of every accepted screen, use `26-ENTERPRISE-PLAIN-ENGLISH-DESIGNER-PRD.md` or the revised `ExecuteSpec-Enterprise-Designer-PRD.pdf`.

## 1. Product definition

ExecuteSpec Enterprise is an Objective-centred software-delivery and organisational-context workspace. It helps teams understand intent and systems, plan and execute bounded work, verify outcomes, coordinate decisions, operate services read-first, and retain queryable evidence and history.

It is not only a coding agent. It is a software-work system spanning engineering, product, documentation, delivery, context, collaboration, integrations, and governed operations.

## 2. Users and responsibilities

Primary users include developers, tech leads, engineering/product managers, architects, SRE/operations, security/governance owners, administrators, executives/reviewers, SMEs, and invited participants. Each sees only permitted organisation/team/Objective/source context and actions.

## 3. Navigation and durable objects

The shell is Organisation / Team -> Objective -> Inspect. Permanent semantic entries are Objectives, Intelligence, Services, and Settings. Home/Needs you is the landing state; Evidence is contextual first and aggregate by link/filter. Verification is embedded rather than a seventh global application.

Durable concepts include Objective, ContextBundle, source/citation, governing-authority Decision, Plan, typed Action, attempt/checkpoint, Verification Plan/Run, Evidence ledger, delivery/write-back effect, Service/Incident, Connector, policy, audit event, collaboration request, and continuity token.

## 4. Knowledge and cross-team context

The queryable context system covers code (repositories, branches, commits, blame/ownership, architecture), documents (Confluence, Notion, Google/Word documents, Git pages, README/Markdown, Figma), discussion (permission-scoped Slack/Teams), Jira issues/comments, decisions/ADRs, CI/PR/releases/evidence, service/environment/incident state, and people/teams/SMEs/planned work. Greenfield organisations with zero data remain first-class and bootstrap progressively.

## 5. Integration scope

The design accommodates GitHub, Bitbucket, Jira, Confluence, Slack, Figma, CI/CD, cloud providers and CLIs, documents/storage suites, PagerDuty, ServiceNow, identity/SSO, model providers, and audit/billing systems. Capability, permission, availability, freshness, retention, read/write depth, maturity, and recovery are represented separately. Provider logos remain governed by the current identity decision.

## 6. Journey-linked product requirements

### SC-01 - Zero-information Enterprise setup

Create a truthful Enterprise starting point without inheriting tenant, people, source, Objective, graph, or memory data.

**Journey:** [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)

### SC-02 - Jira-ready work to scoped Objective

Open authorised ready work and bind it to the correct Organisation, Team, Objective, project, repository, and source versions.

**Journey:** [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)

### SC-03 - Cross-source context and governing-authority resolution

Understand code, documents, issues, decisions, discussions, delivery, operations, and people context while preserving source authority and contradictions.

**Journey:** [EJ03 - Cross-source context and governing-authority resolution](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej03-cross-source-context-and-governing-authority-resolution)

### SC-04 - Plan review, approval, rejection, expiry, and invalidation

Review a versioned Plan and grant only bounded authority for the exact consequence.

**Journey:** [EJ04 - Plan review, approval, rejection, expiry, and invalidation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej04-plan-review-approval-rejection-expiry-and-invalidation)

### SC-05 - Objective work, pause, block, recovery, and supersession

Track bounded work honestly and preserve durable recovery across interruption or replacement.

**Journey:** [EJ05 - Objective work, pause, block, recovery, and supersession](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej05-objective-work-pause-block-recovery-and-supersession)

### SC-06 - Verification, evidence, delivery, and external-write reconciliation

Separate process completion from proof, run required verification, and reconcile every external delivery effect.

**Journey:** [EJ06 - Verification, evidence, delivery, and external-write reconciliation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej06-verification-evidence-delivery-and-external-write-reconciliation)

### SC-07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence

Ask a read-only question over explicit Objective/team/organisation scope and understand exactly what is known, missing, denied, or conflicting.

**Journey:** [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)

### SC-08 - Service state and incident investigation

Understand current service/environment state and investigate incidents without implying unsafe production authority.

**Journey:** [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)

### SC-09 - Connector permission, health, disconnect, export, and purge lifecycle

Connect and operate Jira, Confluence, GitHub, Bitbucket, Slack, Figma, CI, document, incident, and cloud integrations with least privilege and explicit lifecycle effects.

**Journey:** [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)

### SC-10 - AI model policy and route recovery

Understand exact provider/model routes, data/budget policy, health, usage limits, and explicit alternatives without silent failover.

**Journey:** [EJ10 - AI model policy and route recovery](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej10-ai-model-policy-and-route-recovery)

### SC-11 - Neutral Studio/Enterprise view choice and secure switching

Choose the correct product without leaking tenant or personal context across product boundaries.

**Journey:** [EJ11 - Neutral Studio/Enterprise view choice and secure switching](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej11-neutral-studio-enterprise-view-choice-and-secure-switching)

### SC-12 - Authorised and failed external deep links

Open the exact authorised Object/action while preventing wrong-view, expired, denied, or changed-source disclosure.

**Journey:** [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)

### SC-13 - Tablet Inspect and read-only context retrieval

Open contextual Inspect without losing the primary Objective and without turning search into mutation.

**Journey:** [EJ13 - Tablet Inspect and read-only context retrieval](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej13-tablet-inspect-and-read-only-context-retrieval)

### SC-14 - Mobile approval, recovery, and secure desktop handoff

Complete supported decisions/recovery on mobile and securely continue desktop-required work without losing or exposing context.

**Journey:** [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)

## 7. Trust and state model

Every major object independently communicates process, outcome, permission, availability, freshness, evidence completeness, and epistemic status. Process completion never implies proof. Unknown and unmeasured remain visible. Consequential actions use Normal, Approval, High impact, Destructive, or Blocked treatment and expose exact authority/effect/recovery.

## 8. Responsive contract

Desktop supports expanded/collapsed left rail and optional Inspect. Tablet uses a 56px semantic rail and 560px Inspect modal/drawer. Mobile shows one primary surface, a semantic bottom navigation/overlay, pushed Inspect routes, complete approval/recovery, and secure handoff for desktop-required work. All touch controls are at least 44x44px.

## 9. Accessibility and content

Target WCAG 2.2 AA. Product font is JetBrains Mono. Verify keyboard order/focus, accessible name/role/value/state, traps/dismissal/focus return, live regions, reduced motion, 200% reflow, 30% localization expansion, non-colour state communication, and redaction from visible and accessibility trees.

## 10. Analytics and acceptance

The 14 journey chapters define analytics events and completion evidence. Design closure covers 110 accepted screens, 327 canonical actions (316 wired and 11 explicitly inert), 20 named starts, 4,035 visible text nodes, and zero final Figma audit failures. Runtime release still requires deterministic `AT-<PRD-ID>` results, production authorization/redaction, connector certification, persistence/idempotency, browser accessibility, reflow/localization, and screen-reader evidence.

## 11. Scope boundary

This package is an external UX-designer handoff and does not claim the Enterprise software is implemented, integrated, certified, or production-ready. Any engineering phase remains separately authorised and must follow the existing 109-ID traceability and coding-agent handoff.
