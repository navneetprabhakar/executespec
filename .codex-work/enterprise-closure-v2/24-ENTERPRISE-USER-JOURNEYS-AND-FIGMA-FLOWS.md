# ExecuteSpec Enterprise - User Journeys and Figma Flows

Date: 2026-07-31  
Checkpoint: `CP-077`  
Status: Designer handoff authority for Enterprise journeys and interaction flows  
Canonical Figma: <https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise>

## Authority

The 14 journeys are the accepted PRD closure scenarios. They map to 110 accepted Figma roots (48 desktop, 30 tablet, 32 mobile) and 20 named flow starts. Older non-`EN-D/T/M-*` frames remain canvas history and are not implementation authority.

## Journey index

- [EJ01 / SC-01 - Zero-information Enterprise setup](#ej01-zero-information-enterprise-setup)
- [EJ02 / SC-02 - Jira-ready work to scoped Objective](#ej02-jira-ready-work-to-scoped-objective)
- [EJ03 / SC-03 - Cross-source context and governing-authority resolution](#ej03-cross-source-context-and-governing-authority-resolution)
- [EJ04 / SC-04 - Plan review, approval, rejection, expiry, and invalidation](#ej04-plan-review-approval-rejection-expiry-and-invalidation)
- [EJ05 / SC-05 - Objective work, pause, block, recovery, and supersession](#ej05-objective-work-pause-block-recovery-and-supersession)
- [EJ06 / SC-06 - Verification, evidence, delivery, and external-write reconciliation](#ej06-verification-evidence-delivery-and-external-write-reconciliation)
- [EJ07 / SC-07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- [EJ08 / SC-08 - Service state and incident investigation](#ej08-service-state-and-incident-investigation)
- [EJ09 / SC-09 - Connector permission, health, disconnect, export, and purge lifecycle](#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- [EJ10 / SC-10 - AI model policy and route recovery](#ej10-ai-model-policy-and-route-recovery)
- [EJ11 / SC-11 - Neutral Studio/Enterprise view choice and secure switching](#ej11-neutral-studio-enterprise-view-choice-and-secure-switching)
- [EJ12 / SC-12 - Authorised and failed external deep links](#ej12-authorised-and-failed-external-deep-links)
- [EJ13 / SC-13 - Tablet Inspect and read-only context retrieval](#ej13-tablet-inspect-and-read-only-context-retrieval)
- [EJ14 / SC-14 - Mobile approval, recovery, and secure desktop handoff](#ej14-mobile-approval-recovery-and-secure-desktop-handoff)

## EJ01 - Zero-information Enterprise setup

**Closure scenario:** `SC-01`  
**Actors:** First organisation administrator or delegated setup owner  
**Goal:** Create a truthful Enterprise starting point without inheriting tenant, people, source, Objective, graph, or memory data.  
**Preconditions:** Authenticated user has Enterprise access but the tenant contains no approved context.  
**Trigger:** The user chooses Enterprise or lands in a zero-information tenant.

### Journey sequence

[EN-D-00A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345) -> [EN-D-01Z](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=168-3261) -> [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485) -> [EN-D-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-16241) -> [EN-T-02Z](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-16123) -> [EN-M-02Z](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-19043)

**Narrative sequence:** Neutral product choice -> empty Home -> scoped Ask/setup Brief -> review proposal -> create first Objective/connect source.

### Authority and consequence

Bootstrap remains proposal-only until scope, identity, source permissions, and effect are reviewed. Studio context is not transferred automatically.

### Failure and recovery

OAuth denial, empty connector result, unsupported source, permission limitation, and abandoned setup preserve a safe empty tenant and resumable draft.

### Successful completion

A reviewed first Objective or connection exists with explicit organisation/team/source scope and an audit receipt.

### Required evidence

Setup proposal, actor/effective identity, tenant, requested permissions, source authority, affected objects, audit event, and next required decision.

### Responsive behavior

Desktop, tablet, and mobile have native zero-information screens; no responsive layout may display fabricated example tenant data as real.

### Analytics

- `enterprise_zero_info_viewed`
- `enterprise_setup_proposed`
- `enterprise_setup_approved`

**Figma journey entry:** [open EJ01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345)

## EJ02 - Jira-ready work to scoped Objective

**Closure scenario:** `SC-02`  
**Actors:** Developer, engineering manager, product manager, or Objective owner  
**Goal:** Open authorised ready work and bind it to the correct Organisation, Team, Objective, project, repository, and source versions.  
**Preconditions:** Jira/Confluence/SCM identity is connected and the viewer has required read authority.  
**Trigger:** The user opens Home work, an authorised Jira deep link, or creates/links an Objective.

### Journey sequence

[EN-D-60A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-17349) -> [EN-D-10H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203) -> [EN-D-11U](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5159) -> [EN-D-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485) -> [EN-T-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9440) -> [EN-M-01](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688)

**Narrative sequence:** Authorise link -> Home/Needs you -> choose work -> inspect scope -> link/create Objective -> continue to context.

### Authority and consequence

Source systems remain authoritative. Creating/linking an Objective is a reviewed proposal; duplicate or ambiguous binding cannot auto-resolve.

### Failure and recovery

Wrong view, expired/denied link, unavailable Jira, stale source, duplicate Objective, and missing permission route to explicit recovery without disclosing protected identifiers.

### Successful completion

A durable Objective is bound to exact source objects and opens with current status, owner, scope, and next lifecycle decision.

### Required evidence

Link/auth receipt, source object/version, organisation/team/project/repo scope, actor, Objective identity, availability/freshness, and audit event.

### Responsive behavior

Home and deep-link entry are complete across desktop/tablet/mobile; dense linking may defer editing but never scope/authority review.

### Analytics

- `enterprise_ready_work_opened`
- `enterprise_objective_linked`
- `enterprise_source_unavailable`

**Figma journey entry:** [open EJ02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-17349)

## EJ03 - Cross-source context and governing-authority resolution

**Closure scenario:** `SC-03`  
**Actors:** Objective owner, SME, product/engineering lead, or authorised reviewer  
**Goal:** Understand code, documents, issues, decisions, discussions, delivery, operations, and people context while preserving source authority and contradictions.  
**Preconditions:** Objective scope and at least one permitted source are available.  
**Trigger:** Context retrieval finds partial, stale, conflicting, denied, unavailable, or no-result evidence.

### Journey sequence

[EN-D-20C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470) -> [EN-D-21P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5449) -> [EN-D-14D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=172-487) -> [EN-D-14E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=174-562) -> [EN-D-20R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-18472) -> [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706) -> [EN-M-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11804)

**Narrative sequence:** Inspect scope/citations -> compare conflict -> identify governing source -> record versioned authority decision -> refresh context -> continue.

### Authority and consequence

Similarity, recency, or model confidence never chooses organisational truth. Human authority decisions are versioned, auditable, and supersedable.

### Failure and recovery

Permission denial redacts protected content; unavailable and no-result remain distinct; stale/partial context lists omissions; source changes invalidate dependent decisions.

### Successful completion

Context is accepted for planning with citations, omissions, freshness, governing-source decision, and unresolved uncertainty visible.

### Required evidence

ContextBundle version, citations, epistemic class, permission, availability, freshness, omissions/conflicts, governing-source decision, and supersession history.

### Responsive behavior

Desktop can show context and Inspect simultaneously; tablet uses a modal/drawer; mobile uses dedicated routes and pushed evidence inspection.

### Analytics

- `enterprise_context_loaded`
- `enterprise_context_conflict`
- `enterprise_source_authority_resolved`

**Figma journey entry:** [open EJ03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3470)

## EJ04 - Plan review, approval, rejection, expiry, and invalidation

**Closure scenario:** `SC-04`  
**Actors:** Objective owner, approver, security/operations authority, or budget owner  
**Goal:** Review a versioned Plan and grant only bounded authority for the exact consequence.  
**Preconditions:** Objective context and scope are current enough to plan.  
**Trigger:** A Plan is ready, changed, rejected, expired, invalidated, or requires approval.

### Journey sequence

[EN-D-23A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737) -> [EN-D-23B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-5745) -> [EN-D-23X](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=192-1440) -> [EN-T-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10003) -> [EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937) -> [EN-T-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-15559) -> [EN-M-12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-16695)

**Narrative sequence:** Review tasks/tests/dependencies -> inspect effect/cost/environment/evidence/recovery -> approve, reject, or change scope -> start or return.

### Authority and consequence

Approval binds plan/version, actor, target, environment, source SHA, budget, expiry, preconditions, evidence, cancellation, and recovery. Production remediation stays P2/Conditional.

### Failure and recovery

Source/scope/provider/budget/environment changes invalidate prior approval; expiry cannot be bypassed; rejection and scope change preserve the draft and rationale.

### Successful completion

An immutable approval/rejection receipt exists, and approved work begins only within its exact bounds.

### Required evidence

Plan version, scope, actor/approver, cost/time/resource caps, source versions, environment, maturity, expected effect, proof requirements, recovery, and receipt.

### Responsive behavior

Approve/reject/change-scope remain independently accessible on tablet/mobile with 44px targets and no hidden bottom-only choice.

### Analytics

- `enterprise_plan_reviewed`
- `enterprise_plan_decided`
- `enterprise_approval_invalidated`

**Figma journey entry:** [open EJ04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3737)

## EJ05 - Objective work, pause, block, recovery, and supersession

**Closure scenario:** `SC-05`  
**Actors:** Developer, Objective owner, or authorised operator  
**Goal:** Track bounded work honestly and preserve durable recovery across interruption or replacement.  
**Preconditions:** A current Plan/action has valid authority.  
**Trigger:** Work starts, pauses, blocks, fails partially, recovers, cancels, or is superseded.

### Journey sequence

[EN-D-24W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4004) -> [EN-D-24B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6027) -> [EN-D-24R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6335) -> [EN-T-04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10185) -> [EN-T-04B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=290-19925) -> [EN-M-04](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12063) -> [EN-M-04B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12180)

**Narrative sequence:** Start -> show semantic phase/checkpoint -> pause/cancel or encounter block -> inspect cause -> request access/retry/re-plan -> resume or create successor.

### Authority and consequence

Only actions within the approved bound execute. Pause/cancel preserve the checkpoint; superseded work remains queryable evidence and cannot silently become current.

### Failure and recovery

Environment/provider/source failures separate process from outcome; partial work is inspectable; recovery identifies exact checkpoint and replacement SHA/action.

### Successful completion

Work finishes or stops in a safe explicit state, with current and superseded attempts correctly related.

### Required evidence

Attempt/checkpoint identity, route/provider, source SHA, budget usage, actions/effects, outcome, blockers, recovery action, and supersession link.

### Responsive behavior

Responsive surfaces support status, evidence, pause/cancel/request-access/retry; dense implementation moves to desktop/CLI/IDE without losing Objective state.

### Analytics

- `enterprise_work_started`
- `enterprise_work_blocked`
- `enterprise_work_recovered`
- `enterprise_work_superseded`

**Figma journey entry:** [open EJ05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4004)

## EJ06 - Verification, evidence, delivery, and external-write reconciliation

**Closure scenario:** `SC-06`  
**Actors:** Developer, reviewer, Objective owner, CI owner, or delivery approver  
**Goal:** Separate process completion from proof, run required verification, and reconcile every external delivery effect.  
**Preconditions:** Work produced a candidate change or a required proof is missing.  
**Trigger:** Verification runs or delivery/write-back is requested.

### Journey sequence

[EN-D-25E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271) -> [EN-D-25F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=179-674) -> [EN-D-25W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=235-6639) -> [EN-D-62R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=202-1834) -> [EN-D-62C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-20749) -> [EN-T-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10379) -> [EN-M-05](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12297) -> [EN-M-05P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12433)

**Narrative sequence:** Review verification plan/impact -> run/inspect outcomes -> open evidence ledger -> review delivery -> execute write-back -> reconcile partial/unknown result.

### Authority and consequence

Required tests cannot be silently removed. Generated tests are proposals until baseline/candidate proof is valid. External retries use authoritative read/idempotency evidence, never blind duplicate writes.

### Failure and recovery

Environment blocked, flaky, inconclusive, failed, partial, unmeasured, callback failure, revoked permission, and unknown remote result each have distinct recovery.

### Successful completion

Evidence satisfies the delivery boundary and remote/durable state agree, or the Objective remains visibly partial/blocked with preserved payload and next action.

### Required evidence

Test topology/plan/run, source SHA, environment, assertions/artifacts, epistemic class, freshness, delivery target, correlation/idempotency keys, remote receipt, and reconciliation read.

### Responsive behavior

All surfaces support evidence summary and recovery; desktop/tablet provide dense plan/run detail while mobile supports decisions/status and handoff.

### Analytics

- `enterprise_verification_started`
- `enterprise_evidence_viewed`
- `enterprise_writeback_partial`
- `enterprise_writeback_reconciled`

**Figma journey entry:** [open EJ06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-4271)

## EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence

**Closure scenario:** `SC-07`  
**Actors:** Any authorised Enterprise user  
**Goal:** Ask a read-only question over explicit Objective/team/organisation scope and understand exactly what is known, missing, denied, or conflicting.  
**Preconditions:** At least one context source or a truthful greenfield state exists.  
**Trigger:** The user opens Intelligence or global scoped Ask.

### Journey sequence

[EN-D-30Q](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=207-1960) -> [EN-D-26P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=181-806) -> [EN-D-26D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=183-973) -> [EN-D-26U](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=184-1026) -> [EN-T-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-12803) -> [EN-T-06D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=273-13993) -> [EN-M-06](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510) -> [EN-M-06D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13640)

**Narrative sequence:** Choose scope/permissions -> submit query -> loading -> answer/partial/conflict/denied/unavailable/no-result -> inspect citations or propose bounded follow-up.

### Authority and consequence

Search is read-only. Objective and Inspect query histories are separate. Organisation-wide/cross-team Intelligence is P1 and explicitly labelled.

### Failure and recovery

Denied answers expose no protected claim; unavailable is not denial; partial lists omissions; conflict offers compare/authority resolution; greenfield unknown does not fabricate history.

### Successful completion

The user receives a cited answer or an honest non-answer with valid next action and no scope leakage.

### Required evidence

Query/scope, permissions, source set, retrieval receipt, citations, omissions/conflicts, freshness, epistemic status, answer state, and proposed follow-up.

### Responsive behavior

Desktop may keep Inspect open; tablet/mobile have dedicated scoped-query and denied/recovery screens with preserved query draft.

### Analytics

- `enterprise_intelligence_queried`
- `enterprise_answer_state`
- `enterprise_citation_opened`

**Figma journey entry:** [open EJ07](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=207-1960)

## EJ08 - Service state and incident investigation

**Closure scenario:** `SC-08`  
**Actors:** Service owner, SRE, incident commander, engineering manager, or investigator  
**Goal:** Understand current service/environment state and investigate incidents without implying unsafe production authority.  
**Preconditions:** Service catalogue or incident link is available.  
**Trigger:** The user opens Services, a service entity, or active incident.

### Journey sequence

[EN-D-40P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=209-2309) -> [EN-D-41U](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-1356) -> [EN-D-42I](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-1843) -> [EN-D-42B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-2257) -> [EN-D-42V](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-2671) -> [EN-T-07](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13019) -> [EN-T-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13183) -> [EN-M-07](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13749)

**Narrative sequence:** Portfolio -> service detail/live evidence -> incident investigation -> proposal/escalation/remediation Objective -> verification -> write-back result.

### Authority and consequence

P0/P1 is read-first. Production rollback/remediation is P2/Conditional; current screens may recommend, request approval, escalate, or create an Objective, not execute generic rollback.

### Failure and recovery

Live unavailable uses last observed history with current state unknown; blocked action shows missing preconditions; verified remediation can still have failed ServiceNow/PagerDuty write-back.

### Successful completion

Investigation produces a bounded Objective/escalation or verified evidence, with incident record write-back reconciled separately.

### Required evidence

Service/environment/owner, observation time, live/history state, incident severity/maturity, evidence/omissions, authority/preconditions, verification, and external workflow receipt.

### Responsive behavior

Portfolio/detail/investigation are complete on tablet/mobile for review/escalation; no mobile production mutation is implied.

### Analytics

- `enterprise_service_opened`
- `enterprise_incident_investigated`
- `enterprise_remediation_proposed`

**Figma journey entry:** [open EJ08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=209-2309)

## EJ09 - Connector permission, health, disconnect, export, and purge lifecycle

**Closure scenario:** `SC-09`  
**Actors:** Organisation admin, integration admin, security owner, or data owner  
**Goal:** Connect and operate Jira, Confluence, GitHub, Bitbucket, Slack, Figma, CI, document, incident, and cloud integrations with least privilege and explicit lifecycle effects.  
**Preconditions:** Viewer has the role required to review or request connector changes.  
**Trigger:** A connector is added, unhealthy/drifted, re-authorised, disconnected, exported, or purged.

### Journey sequence

[EN-D-53C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-3226) -> [EN-D-39H](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=187-1120) -> [EN-D-39P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=189-1278) -> [EN-D-59Q](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-19605) -> [EN-T-09](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13339) -> [EN-T-10](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=273-14146) -> [EN-M-08](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13878) -> [EN-M-08F](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13990)

**Narrative sequence:** Review capabilities/scopes -> OAuth/validate -> monitor connection/permission/availability/freshness/capability -> repair drift or disconnect -> export -> confirm purge -> verify stores.

### Authority and consequence

Requested permissions, capability, identity, retention, legal hold, affected Objects, irreversible boundary, and recovery are reviewed. Logos may be absent but canonical provider names remain.

### Failure and recovery

OAuth denied, missing scope, rate limit, webhook/cursor/API drift, failed write, partial purge, legal hold, and revoked permission have connector-specific recovery.

### Successful completion

Connector is validated/healthy, safely disconnected with export, or verified purged with complete audit evidence.

### Required evidence

Provider/account, scopes/capabilities, tokens as write-only references, health dimensions, cursor/webhook state, retained/deleted stores, legal hold, receipts, and verification.

### Responsive behavior

Tablet/mobile support critical review/OAuth/status/recovery; dense inventory/policy editing remains desktop-first with secure continuation.

### Analytics

- `enterprise_connector_reviewed`
- `enterprise_connector_authorized`
- `enterprise_connector_drifted`
- `enterprise_purge_verified`

**Figma journey entry:** [open EJ09](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=239-3226)

## EJ10 - AI model policy and route recovery

**Closure scenario:** `SC-10`  
**Actors:** AI platform admin, governance owner, budget owner, or authorised Objective approver  
**Goal:** Understand exact provider/model routes, data/budget policy, health, usage limits, and explicit alternatives without silent failover.  
**Preconditions:** At least one model route exists or a route failure/limit is detected.  
**Trigger:** The user reviews AI policy or an Objective call is blocked by route/usage policy.

### Journey sequence

[EN-D-55P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=220-2861) -> [EN-D-55M](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=200-1698) -> [EN-M-55R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19297) -> [EN-M-55M](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19483)

**Narrative sequence:** Inspect matrix -> select allowed route/candidate -> review provider/model/data/budget/failure boundaries -> approve policy/route change -> validate -> resume or remain blocked.

### Authority and consequence

Route changes are high impact. Exact provider and model remain visible; DeepSeek/OpenRouter/Fireworks stay named without logos. No hidden substitution or spend.

### Failure and recovery

Unavailable provider, health degradation, quota/usage limit, policy denial, missing key, paused-call uncertainty, and no eligible route each show separate recovery.

### Successful completion

An allowed validated route is active with audit receipt, or the action remains explicitly blocked with approved alternatives.

### Required evidence

Provider/model, key source, health, policy/maturity, data boundary, budget/usage, candidate checks, approver, validation, paused-call reconciliation, and receipt.

### Responsive behavior

Desktop owns full matrix; mobile provides route review/blocked recovery with complete authority and cost context.

### Analytics

- `enterprise_model_policy_viewed`
- `enterprise_route_blocked`
- `enterprise_route_changed`

**Figma journey entry:** [open EJ10](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=220-2861)

## EJ11 - Neutral Studio/Enterprise view choice and secure switching

**Closure scenario:** `SC-11`  
**Actors:** User with access to one or both products  
**Goal:** Choose the correct product without leaking tenant or personal context across product boundaries.  
**Preconditions:** User reaches a product entry/switch point.  
**Trigger:** The user chooses Enterprise/Studio or switches view.

### Journey sequence

[EN-D-00A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345) -> [EN-T-00A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19673) -> [EN-M-00A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19907)

**Narrative sequence:** Neutral choice -> authenticate/authorise target -> open matching product URL and breakpoint -> resolve permitted explicit references only.

### Authority and consequence

Studio and Enterprise are separate views/routes/storage/context boundaries. Target authorisation occurs before any protected reference is restored.

### Failure and recovery

Wrong account/view, denied target, expired session, or unsupported reference returns to neutral choice with no inherited protected identifiers.

### Successful completion

The correct product opens with authorised context; no automatic cross-product transfer occurs.

### Required evidence

Chosen product, actor/session, target authority, permitted reference mapping, denied/cleared context, and audit event.

### Responsive behavior

Desktop/tablet/mobile use native neutral choice frames; Studio opens its separate URL.

### Analytics

- `enterprise_view_choice_opened`
- `enterprise_view_switched`
- `enterprise_cross_view_denied`

**Figma journey entry:** [open EJ11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345)

## EJ12 - Authorised and failed external deep links

**Closure scenario:** `SC-12`  
**Actors:** Enterprise user entering from Jira, Confluence, Figma, Slack, PR, CI, email, or incident system  
**Goal:** Open the exact authorised Object/action while preventing wrong-view, expired, denied, or changed-source disclosure.  
**Preconditions:** External link contains no protected payload and references a server-side scoped token/object.  
**Trigger:** The user opens the link.

### Journey sequence

[EN-D-60A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-17349) -> [EN-D-60W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=175-611) -> [EN-D-60E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=176-3425) -> [EN-D-60D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=177-663) -> [EN-T-09A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-14299) -> [EN-T-09W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-15102) -> [EN-T-09E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-15899) -> [EN-T-09D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-16704) -> [EN-M-09A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-17515) -> [EN-M-09W](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-18354) -> [EN-M-09E](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-19185) -> [EN-M-09D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-20025)

**Narrative sequence:** Open -> authenticate -> choose correct view/account -> evaluate permission and source version -> open exact Object/action or recovery.

### Authority and consequence

Only authorised state resolves protected identifiers. Sign-in/view switch alone is not authorisation. Links are scoped, expiring, revocable, and source-version aware.

### Failure and recovery

Wrong view, expired, denied, revoked, source changed, and unavailable target remain mutually distinct with request-access/reissue/return-safe routes.

### Successful completion

Authorised users reach the exact Object/action; others receive a redacted recovery state and audit trail.

### Required evidence

Token/link ID, origin, intended object/action class, expiry, actor/account/view, permission decision, source version, denial/recovery, and open receipt.

### Responsive behavior

Tablet/mobile have complete authorised/wrong/expired/denied matrices; desktop includes authorised and recovery states.

### Analytics

- `enterprise_external_link_opened`
- `enterprise_external_link_authorized`
- `enterprise_external_link_recovered`

**Figma journey entry:** [open EJ12](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-17349)

## EJ13 - Tablet Inspect and read-only context retrieval

**Closure scenario:** `SC-13`  
**Actors:** Tablet user reviewing an Objective, evidence, decision, or source  
**Goal:** Open contextual Inspect without losing the primary Objective and without turning search into mutation.  
**Preconditions:** A primary tablet surface and inspectable object exist.  
**Trigger:** The user taps an evidence/context/decision link or Inspect control.

### Journey sequence

[EN-T-11](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683) -> [EN-T-05A](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413) -> [EN-T-02](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-9706)

**Narrative sequence:** Primary surface -> open 560px modal Inspect -> focus trap/read-only Search context -> inspect/copy/open source -> close/Escape/back -> deterministic focus return.

### Authority and consequence

Background is inert while the modal is open. Search context is permission-scoped and read-only; Objective composer state/history stays separate.

### Failure and recovery

Unavailable/denied/no-result search remains inside Inspect with redaction; orientation/keyboard changes preserve selected object and query draft.

### Successful completion

Inspect closes to the exact invoking control with Objective, selection, draft, and pending decision preserved.

### Required evidence

Invoking object, inspect type, query/scope, permission, result state, source/citation, focus origin/return, and dismissal action.

### Responsive behavior

Normative tablet modal is x=274, y=64, 560x896 with 44px controls and responsive zoom/localisation behavior.

### Analytics

- `enterprise_inspect_opened`
- `enterprise_context_searched`
- `enterprise_inspect_closed`

**Figma journey entry:** [open EJ13](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=251-10683)

## EJ14 - Mobile approval, recovery, and secure desktop handoff

**Closure scenario:** `SC-14`  
**Actors:** Mobile user supervising an Objective or completing a review  
**Goal:** Complete supported decisions/recovery on mobile and securely continue desktop-required work without losing or exposing context.  
**Preconditions:** Mobile Objective/decision exists; some action may require desktop/CLI/IDE.  
**Trigger:** The user approves/rejects/changes scope, handles a block/write-back failure, or selects Continue on desktop.

### Journey sequence

[EN-M-03](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937) -> [EN-M-04B](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12180) -> [EN-M-05P](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-12433) -> [EN-M-10C](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400) -> [EN-M-10O](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-20866) -> [EN-M-10S](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12504) -> [EN-M-10X](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12613) -> [EN-M-10R](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-21844) -> [EN-M-10D](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12715)

**Narrative sequence:** Review decision -> act or recover -> create one-time handoff -> desktop reauth/reauthorise/source check -> opened -> complete, expire, revoke, or reissue after source change.

### Authority and consequence

The token is one-time, same-user, scoped, expiring, revocable, contains no protected payload, and restores only after destination permission and source-version checks.

### Failure and recovery

Blocked work, partial write-back, wrong user, expired/revoked token, source changed, denied destination, and abandoned handoff preserve Objective and valid recovery.

### Successful completion

The mobile action completes or an authorised desktop continuation restores the exact safe state; token is consumed/invalidated with receipt.

### Required evidence

Decision/receipt, Objective/evidence scope, token ID/expiry, origin/destination, actor, permissions, source versions, open/complete/revoke events, and return path.

### Responsive behavior

Mobile uses dedicated one-primary-surface states and 44px controls; desktop/tablet destination reauthorisation remains mandatory.

### Analytics

- `enterprise_mobile_decision`
- `enterprise_handoff_created`
- `enterprise_handoff_opened`
- `enterprise_handoff_completed`

**Figma journey entry:** [open EJ14](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11937)

## Twenty named Figma flow starts

### F01 - Enterprise Desktop - Core Objective Flow

- Start: [open `227:3203`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=227-3203)
- Screen: `EN-D-10H - Home needs you - R1`
- Journeys: [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F02 - Enterprise Desktop - View Choice and Recovery

- Start: [open `166:345`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=166-345)
- Screen: `EN-D-00A - Neutral view choice - R1`
- Journeys: [EJ11 - Neutral Studio/Enterprise view choice and secure switching](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej11-neutral-studio-enterprise-view-choice-and-secure-switching)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F03 - Enterprise Mobile - Core Objective Flow

- Start: [open `263:11688`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=263-11688)
- Screen: `EN-M-01 - Home needs you - R1`
- Journeys: [EJ02 - Jira-ready work to scoped Objective](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej02-jira-ready-work-to-scoped-objective)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F04 - Enterprise Mobile - Secure Continuity

- Start: [open `264:12400`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=264-12400)
- Screen: `EN-M-10C - Secure handoff created - R1`
- Journeys: [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F05 - Enterprise Tablet - Intelligence and Scope Recovery

- Start: [open `270:12803`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-12803)
- Screen: `EN-T-06 - Intelligence scoped partial - R1`
- Journeys: [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F06 - Enterprise Tablet - Connector Authorization Recovery

- Start: [open `270:13339`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13339)
- Screen: `EN-T-09 - Critical Settings connector review - R1`
- Journeys: [EJ09 - Connector permission, health, disconnect, export, and purge lifecycle](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej09-connector-permission-health-disconnect-export-and-purge-lifecycle)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F07 - Enterprise Tablet - Operations and Governance

- Start: [open `270:13019`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=270-13019)
- Screen: `EN-T-07 - Services portfolio - R1`
- Journeys: [EJ08 - Service state and incident investigation](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej08-service-state-and-incident-investigation)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F08 - Enterprise Mobile - Intelligence, Incident and Settings

- Start: [open `271:13510`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=271-13510)
- Screen: `EN-M-06 - Intelligence scoped partial - R1`
- Journeys: [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F09 - Enterprise Tablet - External Link Authorized

- Start: [open `282:14299`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-14299)
- Screen: `EN-T-09A - Deep link authorized - R1`
- Journeys: [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F10 - Enterprise Tablet - Secure Continuity

- Start: [open `283:14489`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=283-14489)
- Screen: `EN-T-10C - Continuity token created - R1`
- Journeys: [EJ14 - Mobile approval, recovery, and secure desktop handoff](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej14-mobile-approval-recovery-and-secure-desktop-handoff)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F11 - Enterprise Tablet - External Link Recovery

- Start: [open `282:15102`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-15102)
- Screen: `EN-T-09W - Deep link wrong - R1`
- Journeys: [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F12 - Enterprise Mobile - External Link Authorized

- Start: [open `282:17515`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-17515)
- Screen: `EN-M-09A - Deep link authorized - R1`
- Journeys: [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F13 - Enterprise Mobile - External Link Recovery

- Start: [open `282:18354`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=282-18354)
- Screen: `EN-M-09W - Deep link wrong - R1`
- Journeys: [EJ12 - Authorised and failed external deep links](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej12-authorised-and-failed-external-deep-links)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F14 - Enterprise Desktop - Scoped Ask

- Start: [open `289:15485`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-15485)
- Screen: `EN-D-05A - Scoped Ask - R1`
- Journeys: [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F15 - Enterprise Tablet - Scoped Ask

- Start: [open `289:16413`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-16413)
- Screen: `EN-T-05A - Scoped Ask - R1`
- Journeys: [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F16 - Enterprise Mobile - Scoped Ask

- Start: [open `289:17353`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=289-17353)
- Screen: `EN-M-05A - Scoped Ask - R1`
- Journeys: [EJ07 - Scoped Intelligence answer with partial, denied, conflicting, or unavailable evidence](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej07-scoped-intelligence-answer-with-partial-denied-conflicting-or-unavailable-evidence)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F17 - Enterprise Desktop - Zero-info Setup

- Start: [open `292:16241`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=292-16241)
- Screen: `EN-D-02 - Zero-info setup brief - R1`
- Journeys: [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F18 - Enterprise Tablet - Zero-info Setup

- Start: [open `301:16123`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-16123)
- Screen: `EN-T-02Z - Zero-info setup brief - R1`
- Journeys: [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F19 - Enterprise Mobile - Zero-info Setup

- Start: [open `301:19043`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=301-19043)
- Screen: `EN-M-02Z - Zero-info setup brief - R1`
- Journeys: [EJ01 - Zero-information Enterprise setup](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej01-zero-information-enterprise-setup)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

### F20 - Enterprise Mobile - Model Route Recovery

- Start: [open `302:19297`](https://www.figma.com/design/aq1I0rCCUpfRKe7gyyO41S/ExecuteSpec-Enterprise?node-id=302-19297)
- Screen: `EN-M-55R - Model route review - R1`
- Journeys: [EJ10 - AI model policy and route recovery](24-ENTERPRISE-USER-JOURNEYS-AND-FIGMA-FLOWS.md#ej10-ai-model-policy-and-route-recovery)
- Rule: no dead end; back/close/cancel restores Organisation, Team, Objective, Inspect selection, drafts, filters, source versions, and pending authority unless security requires explained clearing.

## Cross-journey rules

1. Source systems remain authoritative; model similarity/recency never chooses organisational truth.
2. Process, outcome, permission, availability, freshness, evidence completeness, and epistemic status remain independent.
3. Objective composer proposes work; Search context is read-only.
4. Every consequential action shows actor, target/version, scope, route/permissions, bounds, preconditions, effect, proof, approval/expiry, cancellation, retry, reconciliation, and recovery.
5. P0/P1 incident operations are read-first; production rollback/remediation is P2/Conditional.
6. Deep links and handoffs contain no protected payload and re-check identity, permission, view, expiry, and source version.
7. Studio and Enterprise remain separate products and URLs.
