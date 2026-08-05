# ExecuteSpec Enterprise — 109-ID PRD Traceability Matrix

Status: **Checkpoint 076 requirement classification complete; accepted Figma nodes/flows are bound through the screen manifest, closure audit and exported graph; production test results remain**  
Source: `ENTERPRISE-IMPLEMENTATION-PRD.md`  
Unique stable IDs: **109**

## Controlled vocabulary

### State bundles

- **S0 First use:** loading, empty/zero-information, denied, unavailable, retry/recovery.
- **S1 Objective:** draft, needs context, ready, blocked, cancelled, superseded, complete-with-open-boundary.
- **S2 Context:** ready, partial, stale, conflicting, denied, unavailable, not searched, no result, unknown.
- **S3 Decision/plan:** draft, awaiting decision, approved, rejected, changed, expired, invalidated, superseded.
- **S4 Action:** proposed, authority-checked, approval required, queued, running, paused, blocked, failed, partial, cancelled, superseded, recovered.
- **S5 Verification:** discover, impact, plan, environment blocked, executing, flaky suspected, inconclusive, failed, passed, partial, unmeasured, cancelled, superseded.
- **S6 Delivery:** pending, executing, partial, failed, retrying, reconciled, externally changed, revoked.
- **S7 Intelligence:** complete, partial, permission-limited, conflicting, stale, unable, timeout, greenfield unknown.
- **S8 Connector/data:** not connected, validating, healthy, stale, permission drift, denied, unavailable, failed, disconnected, quarantined, purge scheduled/running/partial/failed/verified.
- **S9 Administration:** loading, clean, dirty, validation error, review changes, approval, saving, saved, failed, permission denied, recovery.

### Breakpoint responsibility

- **B1:** full desktop, compact desktop, tablet, and mobile journey.
- **B2:** full desktop/compact/tablet; mobile summary, decision, recovery, and explicit desktop handoff for broad editing/comparison.
- **B3:** full desktop/compact/tablet administration; mobile notification, critical approval/recovery, status, and secure handoff.
- **BN:** primarily non-visual/backend contract; status, denial, audit, and recovery must still surface at the relevant breakpoint.

### Current implementation evidence anchors

- **I0 — NET_NEW:** no canonical Objective, ContextBundle, Epistemic, Decision, Verification, Service, Work Graph, or Enterprise Memory product model was found.
- **I1 — REUSABLE_WITH_CHANGE:** org/project/auth/RBAC foundations in `auth/`, including `OrgController`, `ProjectController`, tenant guards, invitations, and `SsoConfigController`.
- **I2 — REUSABLE_WITH_CHANGE:** plan gates, run lifecycle, agent action events, cancellation/recovery in `RunLifecycleService`, `GateStore`, `RunOrchestrator`, and `agentsession/`.
- **I3 — REUSABLE_WITH_CHANGE:** bounded workspace/tool execution, multi-repository tracking/push, run recovery, and source snapshots in `engine/`, `tool/`, `integration/scm/`, `state/`, and `recovery/`.
- **I4 — PARTIAL:** test planning/generation/execution in `testgen/`, `apitests/`, `WorkspaceTestRunner`, `WorkspaceRunTestsTool`, and validation kickoff. This is not the complete VER lifecycle.
- **I5 — REUSABLE_WITH_CHANGE:** validation report, verification evidence, export/share, scoring, and preview evidence in `report/`, `sharing/`, `deployment/preview/verify/`, V270/V273/V294.
- **I6 — PARTIAL:** project RAG, documents, architecture, contracts, exact search, project memory/chat in `memory/`, `codeintel/`, `contracts/`, and document ingestion packages. No enterprise ContextBundle/graph authority model.
- **I7 — UNVERIFIED_LEGACY/PARTIAL:** Jira, Confluence, Slack, PagerDuty, and ServiceNow adapters exist under `integration/`; capability, identity, retention, reconciliation, and live contract certification are not proven by this UX audit.
- **I8 — REUSABLE_WITH_CHANGE:** GitHub and Bitbucket SCM services/adapters and canonical SCM clients in `repo/` and `integration/bitbucket/`.
- **I9 — REUSABLE_WITH_CHANGE:** activity audit/feed, DSAR/data export, public/project sharing, outbox/reconciliation migrations and services.
- **I10 — REUSABLE_WITH_CHANGE:** one preview/deployment path in `deployment/`; production mutation must be excluded from P0/P1 UX.
- **I11 — REUSABLE_WITH_CHANGE:** provider/model registry, org overrides, usage/AI-credit policy and UI support in `router/`, `billing/`, and current Settings surfaces.
- **I12 — NET_NEW CONNECTOR:** no first-class Figma connector/domain adapter was found in current server packages.

`AT-<ID>` identifies the requirement acceptance contract. Detailed cases live in the acceptance-test artifact; the matrix records the primary proof obligation.

## 7.1 Onboarding and greenfield

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| ONB-001 · Create organisation/workspace | P0 launch | Workspace setup | invite, Enterprise entry | S0,S9 | B2 | Hybrid | PARTIAL I1 | `workspace.created` | AT-ONB-001: tenant, owner, region and versioned defaults persist before async work; no connector/execution silently enabled |
| ONB-002 · Zero-information disclosure | P0 launch | Greenfield home | Intelligence unknown, create Objective | S0,S7 | B1 | Visual | NET_NEW I0 | `workspace.zero_info_viewed` | AT-ONB-002: empty workspace contains no retrieved/history claims or inherited confidential shell data |
| ONB-003 · Greenfield brief | P0 launch | Greenfield brief | first Objective | S0,S1 | B1 | Visual+data | NET_NEW I0 | `workspace.brief_saved` | AT-ONB-003: captures intent, users, outcome, people/team, constraints, risk and first Objective |
| ONB-004 · Bootstrap proposals | P0 launch | Proposal review | brainstorming, first Objective | S0,S3 | B1 | Hybrid | NET_NEW I0 | `bootstrap.proposed/approved/rejected` | AT-ONB-004: every proposal has provenance/state; approval creates DECLARED context; rejection/supersession is durable |
| ONB-005 · Progressive connection | P0 launch | Context timeline | Settings Integrations | S2,S8 | B2 | Hybrid | NET_NEW over I6/I7/I8 | `source.connected_conflict_detected` | AT-ONB-005: later observed sources enrich history and expose conflict without erasing approved greenfield facts |
| ONB-006 · Guided enterprise connection | P0 launch | Settings · Integrations | onboarding checklist | S8,S9 | B3 | Hybrid | PARTIAL I7/I8/I12 | `source.connection_*` | AT-ONB-006: capability, identity, permission, validation, health, recovery and deletion behavior are explicit |

## 7.2 Objectives and work

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| OBJ-001 · Create/link objective | P0 launch | Objective create/link | Jira, Confluence, Figma, SCM, CLI/IDE | S0,S1,S2 | B1 | Hybrid | NET_NEW I0; run creation reusable I2 | `objective.created/linked` | AT-OBJ-001: direct and supported-source entry bind one canonical Objective and preserve source reference |
| OBJ-002 · Objective summary | P0 launch | Objective overview/thread | Home, deep link | S1,S4,S5,S6 | B1 | Visual+API | NET_NEW I0 | `objective.summary_viewed` | AT-OBJ-002: outcome, owner, scope, blockers, dependencies, decisions, actions, evidence and next step agree |
| OBJ-003 · Today’s ready work | P0 launch | Home · Needs you | Jira/Slack deep link | S0,S1,S2 | B1 | Hybrid | PARTIAL current run/project lists | `ready_work.viewed/selected` | AT-OBJ-003: only authorised work is ranked with explainable readiness; assignment never auto-executes |
| OBJ-004 · Scope binding | P0 launch | Objective scope review | source entry, plan gate | S1,S2,S3 | B1 | Hybrid | PARTIAL I2/I3 | `objective.scope_bound/clarification_requested` | AT-OBJ-004: org, actor, project, service, repos, sources, environments and delivery boundary bind; ambiguity blocks planning |
| OBJ-005 · Dependency/overlap | P1 | Intelligence/Inspect relationships | Objective summary, Services | S2,S7 | B2 | Hybrid | DEFERRED; fragments I6 | `objective.overlap_detected` | AT-OBJ-005: authorised cited overlap/dependency is distinct from inference and supports resolution action |
| OBJ-006 · Handoff/collaboration | P0 launch | Objective collaboration card | Intelligence, Slack notification | S1,S4 | B1 | Hybrid | PARTIAL invitations/notifications I1/I7 | `collaboration.requested/accepted/declined` | AT-OBJ-006: explicit recipient can accept, decline or redirect; no silent assignment |
| OBJ-007 · Lifecycle integrity | P0 launch | Objective header/timeline | Evidence, delivery | S1,S4,S5,S6 | B1 | Hybrid | PARTIAL run lifecycle I2/I5 | `objective.stage_changed` | AT-OBJ-007: Complete is impossible while required evidence/delivery/write-back/outcome remains open |
| OBJ-008 · Supersession/cancellation | P0 launch | Objective recovery | Working, CI/PR | S1,S4,S5,S6 | B1 | Hybrid | REUSABLE_WITH_CHANGE I2/I3 | `objective.cancelled/superseded` | AT-OBJ-008: obsolete work cannot publish; state/evidence/audit remain accessible |

## 7.3 Context and retrieval

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| CTX-001 · Versioned context bundle | P0 launch | Objective Context + Inspect | plan gate, Intelligence trace | S2 | B1 | Hybrid | NET_NEW contract; retrieval reusable I6 | `context.bundle_created/compared` | AT-CTX-001: selected sources, versions, times, scopes, conflicts, omissions, modes and epistemic labels are versioned |
| CTX-002 · Permission filtering | P0 invariant | Denial/redaction layer | every retrieval/answer | S2,S7,S8 | BN | Backend with visible denial | PARTIAL tenant guards; source-ACL model net-new I1/I6 | `context.permission_denied` | AT-CTX-002: inaccessible content never enters candidates, prompt, graph expansion, cache, answer, trace or inherited shell |
| CTX-003 · Precedence | P0 invariant | Inspect · policy trace | Settings policy, action gate | S2,S3,S9 | BN | Backend with explanation | NET_NEW canonical precedence; fragments I11 | `policy.precedence_applied` | AT-CTX-003: security/org policy outranks approval, project convention, explicit preference and inference |
| CTX-004 · Conflict handling | P0 launch | Context conflicts | plan, Intelligence, docs | S2,S7 | B1 | Hybrid | PARTIAL source context I6/I7 | `context.conflict_detected/resolved` | AT-CTX-004: authority and freshness are shown; similarity never silently chooses truth |
| CTX-005 · Untrusted content | P0 invariant | Inspect · source safety | admin quarantine, action trace | S2,S8 | BN | Backend with visible omission | PARTIAL sanitisation/tool policy I2/I3 | `context.untrusted_omitted` | AT-CTX-005: repository/document/comment/tool content cannot modify system policy or action authority |
| CTX-006 · Retrieval modes | P0 scoped; P1 graph/org | Intelligence trace | Context Inspect | S2,S7 | B2 | Hybrid | PARTIAL exact/semantic/live fragments I6 | `query.retrieval_mode_used` | AT-CTX-006: resolver uses necessary modes and exposes them; unavailable modes degrade honestly |
| CTX-007 · Freshness/current state | P0 launch | Context freshness + Services live state | Intelligence, Evidence | S2,S7,S8 | B1 | Hybrid | PARTIAL staleness services I6/I7/I10 | `source.freshness_changed` | AT-CTX-007: runtime claims require authorised live observation time; history cannot masquerade as now |
| CTX-008 · Graceful degradation | P0 launch | Partial/unknown state | every context/answer surface | S2,S7,S8 | B1 | Hybrid | PARTIAL fail-soft retrieval I6 | `context.degraded` | AT-CTX-008: semantic/graph/live failure returns partial or unknown with omissions and valid recovery |

## 7.4 Decisions and planning

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| DEC-001 · Structured clarification/brainstorming | P0 launch | Objective · Brainstorm | Intelligence, document flow | S2,S3 | B1 | Hybrid | NET_NEW artifact model; chat reusable I2/I6 | `decision.session_started` | AT-DEC-001: assumptions, alternatives, risks, constraints, unknowns and recommendation create reviewable artifacts, not transcript-only output |
| DEC-002 · Decision record | P0 launch | Decision record | Objective timeline, document publish | S3,S6 | B1 | Hybrid | NET_NEW I0 | `decision.proposed/approved/rejected/superseded` | AT-DEC-002: rationale, alternatives, participants, approvers, evidence, affected objects, state and supersession persist |
| PLAN-001 · Reviewable plan | P0 launch | Objective · Plan | CLI/IDE, approval deep link | S3 | B1 | Hybrid | REUSABLE_WITH_CHANGE I2 | `plan.proposed/viewed` | AT-PLAN-001: plan shows valuable tasks, systems, risk, verification, delivery, recovery and estimates |
| PLAN-002 · Plan gate | P0 launch | Approval decision | mobile approval, CLI/IDE | S3 | B1 | Hybrid | REUSABLE_WITH_CHANGE I2 | `plan.approved/rejected/change_requested` | AT-PLAN-002: execution pauses; exact actor/target/scope/policy/evidence/environment/cost are reviewable and server revalidated |
| PLAN-003 · Small-batch guidance | P0 launch | Plan risk guidance | Objective clarification | S3 | B1 | Hybrid | PARTIAL classifier/planner I2 | `plan.slice_recommended/overridden` | AT-PLAN-003: oversized work proposes smaller deliverable slices; override requires rationale |
| PLAN-004 · Approval invalidation | P0 launch | Invalidated approval state | Working, CI/PR | S3,S4 | B1 | Hybrid | PARTIAL gate/version I2 | `plan.invalidated` | AT-PLAN-004: actor, target, scope, route, budget, source version or environment change invalidates stale approval |

## 7.5 Actions and execution

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| ACT-001 · Typed action proposal | P0 launch | Action detail card | Intelligence, Verification, Settings | S4 | B1 | Hybrid | PARTIAL agent action events I2 | `action.proposed` | AT-ACT-001: actor, target, operation, authority, route/tool, scope, budget, preconditions, result, evidence and recovery are present |
| ACT-002 · Just-in-time authority | P0 invariant | Authority check result | every consequential action | S4,S8,S9 | BN | Backend with visible result | PARTIAL run guards/confirmations I1/I2 | `action.authority_checked/denied` | AT-ACT-002: tenant, identity, role, source permission, policy, scope, freshness and confirmation revalidate immediately before execution |
| ACT-003 · Bounded workspace | P0 invariant | Working · boundaries Inspect | CLI/IDE local-hands | S4 | B2 | Backend with operator evidence | REUSABLE_WITH_CHANGE I3 | `action.boundary_violation` | AT-ACT-003: repo/path/command/network/secret/resource/time constraints fail closed and remain auditable |
| ACT-004 · Multi-repository change | P0 launch | Working/Changes | plan, Evidence, PRs | S4,S6 | B2 | Hybrid | REUSABLE_WITH_CHANGE I3 | `change.repo_started/completed` | AT-ACT-004: each repo keeps exact SHA, diff, tests, PR and evidence under one Objective |
| ACT-005 · Durable/recoverable execution | P0 launch | Working/recovery | CLI/IDE, activity | S4 | B1 | Hybrid | REUSABLE_WITH_CHANGE I2/I3 | `action.paused/resumed/retried/recovered` | AT-ACT-005: failure is explainable; safe cancel/resume/reconcile uses persisted state and artifacts |
| ACT-006 · No P0 production mutation | P0 boundary | Incident/read-only Services | action denial/handoff | S4 | B1 | Policy+visual | CURRENT UI CONFLICT; backend power must be bounded I10 | `action.production_blocked` | AT-ACT-006: P0/P1 can investigate/recommend/escalate/handoff but cannot run production rollback/remediation |

## 7.6 Integration and System Verification

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| VER-001 · Test inventory/topology | P0 launch | Objective · Verify inventory | Service verification | S5 | B2 | Hybrid | PARTIAL I4/I6 | `verification.inventory_discovered` | AT-VER-001: tests/suites/frameworks/boundaries/dependencies/environments/data/owner/history/flake evidence are inspectable |
| VER-002 · Change impact | P0 launch | Verify · Impact | Plan, contracts | S5 | B2 | Hybrid | PARTIAL contracts/codemaps I4/I6 | `verification.impact_mapped` | AT-VER-002: deterministic mappings lead; diff/config/schema/infra/contract changes map to affected boundaries and candidate tests |
| VER-003 · Verification plan | P0 launch | Verify · Plan | approval card, PR/CI | S3,S5 | B1 | Hybrid | NET_NEW canonical plan over I4 | `verification.plan_proposed/changed` | AT-VER-003: required/recommended/excluded/missing items include reason, env, data, cost/time, order and approval |
| VER-004 · Environment binding | P0 launch | Verify · Environment | Settings CI/runner, Service | S5 | B2 | Hybrid | PARTIAL preview/workspace I3/I10 | `verification.environment_checked` | AT-VER-004: exact SHA/version, environment, dependency mode, identity, config/flags, data, cleanup and readiness bind |
| VER-005 · Existing test execution | P0 launch | Verify · Active/results | PR/CI, CLI/IDE | S5 | B1 | Hybrid | REUSABLE_WITH_CHANGE I4 | `verification.executed` | AT-VER-005: impacted build/unit/component/contract/integration/acceptance checks run in approved environment |
| VER-006 · Test generation | P0 launch | Verify · Gap/test proposal | Evidence, CLI/IDE | S5 | B2 | Hybrid | PARTIAL I4 | `generated_test.proposed` | AT-VER-006: generation starts only from an explicit requirement/contract/regression/incident/critical-path gap |
| VER-007 · Regression proof | P0 launch | Generated test proof | Evidence ledger | S5 | B2 | Hybrid | NET_NEW proof contract over I4 | `generated_test.regression_proved` | AT-VER-007: test fails baseline for expected reason and passes candidate, otherwise inconclusive |
| VER-008 · New behavior/refactor proof | P0 launch | Generated test proof | plan, Evidence | S5 | B2 | Hybrid | NET_NEW proof contract | `generated_test.behavior_proved` | AT-VER-008: acceptance/contract or differential/invariant/compatibility basis is explicit |
| VER-009 · Test validation | P0 launch | Verify · Test quality | Service suite health | S5 | B2 | Hybrid | PARTIAL runner/report I4/I5 | `generated_test.validated/adopted/rejected` | AT-VER-009: assertion, realism, determinism, isolation, cleanup, baseline, flake, portability, cost, owner and data hygiene are evaluated |
| VER-010 · Failure classification | P0 launch | Verify · Result classification | CI/PR, Evidence | S5 | B1 | Hybrid | PARTIAL current statuses I4/I5 | `verification.classified` | AT-VER-010: regression, baseline, environment, test defect, flake, dependency, permission/config and inconclusive remain distinct |
| VER-011 · CI trigger | P0 launch | PR/CI status + Objective activity | Settings trigger policy | S5 | B2 | Hybrid | PARTIAL GitHub webhook/autofix I4/I8 | `verification.ci_triggered` | AT-VER-011: immutable event/SHA, idempotency, supersession, budget/retry/cancel and reconciliation hold |
| VER-012 · On-demand trigger | P0 launch | Typed verification action | Objective, Evidence, Service, CLI/IDE | S4,S5 | B1 | Hybrid | PARTIAL manual run-tests endpoint I4 | `verification.on_demand_requested` | AT-VER-012: authorised scoped request exposes commit, tests, reason, env, identity, data, cost, effects, approval and evidence |
| VER-013 · Evidence | P0 launch | Verification evidence ledger | PR/CI, Objective Evidence | S5,S6 | B1 | Hybrid | PARTIAL I5 | `verification.evidence_published` | AT-VER-013: SHAs, runner, environment, modes, config/data, attempts, duration, results, artifacts, exclusions and classification persist |
| VER-014 · Blocking policy | P0 launch | Verify · Blocking findings | plan gate, delivery | S3,S5,S6 | B1 | Hybrid | PARTIAL build/test caps I2/I5 | `verification.blocked/exception_requested` | AT-VER-014: required build/contracts/critical integration/security/acceptance checks block completion absent authorised exception |
| VER-015 · CI authority | P0 invariant | PR/CI authority label | Settings policy, Evidence | S5,S6 | BN | Backend with visible authority | PARTIAL I8 | `verification.authority_reconciled` | AT-VER-015: customer CI/branch policy is authoritative; ExecuteSpec cannot create hidden merge/deploy truth |

## 7.7 Evidence and delivery

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| EVD-001 · Evidence ledger | P0 launch | Objective Evidence/Inspect | aggregate Evidence, Service | S5,S6 | B1 | Hybrid | REUSABLE_WITH_CHANGE I5 | `evidence.viewed` | AT-EVD-001: build/unit/component/contract/integration/workflow/security/standards/architecture/preview/delivery/production stay separate; unmeasured explicit |
| EVD-002 · Evidence classification | P0 launch | Evidence item/detail | approval card, export | S5,S6 | B1 | Hybrid | REUSABLE_WITH_CHANGE I5 | `evidence.classification_viewed` | AT-EVD-002: deterministic, live, LLM, hybrid, formula and user-declared labels are never conflated |
| EVD-003 · Delivery/write-back | P0 launch | Deliver/write-back | SCM PR, Jira, Confluence, CI | S6 | B1 | Hybrid | PARTIAL I7/I8/I9 | `delivery.attempted/succeeded/failed` | AT-EVD-003: idempotent authorised PR/status/evidence publication retains initiating actor and exact target/version |
| EVD-004 · External reconciliation | P0 launch | Delivery recovery | Settings failures, Objective activity | S6 | B1 | Hybrid | REUSABLE_WITH_CHANGE/PARTIAL I9 | `delivery.retried/reconciled` | AT-EVD-004: correlation, retry/backoff, dead letter/replay and external confirmation are durable and user-visible |
| EVD-005 · Evidence export/share | P0 launch | Evidence share/export | Objective/share link, Admin export | S6,S9 | B2 | Hybrid | REUSABLE_WITH_CHANGE I5/I9 | `evidence.exported/shared` | AT-EVD-005: export/share re-authorises recipient and cannot widen source permission or leak inaccessible facts |
| EVD-006 · Verification summary honesty | P0 invariant | Objective/Evidence summary | Home, PR/CI | S5,S6 | B1 | Hybrid | PARTIAL I5; current Figma conflict | `evidence.summary_viewed` | AT-EVD-006: aggregate status never hides failed, omitted, stale, simulated or unmeasured proof |

## 7.8 Engineering Intelligence

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| INT-001 · Objective-scoped query | P0 launch | Intelligence query/answer | Objective Ask, Inspect search | S7 | B1 | Hybrid | PARTIAL project/run chat I6 | `query.asked` | AT-INT-001: linked work/code/docs/design/decisions/owners/dependencies/changes/evidence are queryable within authorised Objective scope |
| INT-002 · Universal authorised query | P1 | Intelligence scope builder | global Ask | S7 | B2 | Hybrid | DEFERRED P1; partial I6 | `query.org_scope_selected` | AT-INT-002: explicit authorised org/team/project scope; no P0 implication or cross-scope leakage |
| INT-003 · Answer contract | P0 launch | Intelligence answer | Objective/Inspect | S7 | B1 | Hybrid | NET_NEW contract over I6 | `query.answered/partial/failed` | AT-INT-003: answer/inability, scope, citations, times, epistemic labels, conflicts, omissions, modes and permitted actions render |
| INT-004 · SME/reviewer suggestion | P1 | Intelligence people result | Objective collaboration | S7 | B2 | Hybrid | DEFERRED P1 | `collaboration.suggested` | AT-INT-004: declared ownership plus cited relevant evidence; no opaque score or productivity signal |
| INT-005 · Cross-team coordination | P1 | Intelligence overlap result | Objective, Services | S7 | B2 | Hybrid | DEFERRED P1 | `coordination.overlap_viewed` | AT-INT-005: authorised active/planned work, dependency, overlap, impact and teams are cited and actionable |
| INT-006 · Saved/shared query | P1 | Intelligence history/saved | share/deep link | S7 | B2 | Hybrid | DEFERRED P1 | `query.saved/shared/refreshed` | AT-INT-006: query scope/time/refresh persist and every recipient independently re-authorises facts |
| INT-007 · Greenfield behavior | P0 launch | Intelligence zero-information | onboarding, Objective | S0,S7 | B1 | Hybrid | NET_NEW I0 | `query.greenfield_unknown` | AT-INT-007: absence is stated; minimum questions/proposals are labelled; unknown history/ownership/runtime is not invented |
| INT-008 · Query evaluation | P1 | Settings · Intelligence quality | Analytics harness | S9 | B3 | Backend/admin | PARTIAL benchmark/eval infrastructure, domain suite net-new | `query.evaluation_run` | AT-INT-008: golden queries cover permission, entailment, source, freshness, conflict, completeness, abstention, authority, latency and cost |

## 7.9 Software Work Graph

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| GRAPH-001 · Logical temporal graph | P1 | Intelligence relationships | Inspect, Context Ops | S2,S7 | B2 | Backend+visual trace | DEFERRED P1; code/contract fragments I6 | `graph.query_executed` | AT-GRAPH-001: one logical layered graph works without requiring a dedicated graph database |
| GRAPH-002 · Initial spine | P0/P1 split | Inspect relationships | Objective, Services | S2,S7 | B2 | Hybrid | NET_NEW canonical graph; fragments I6 | `graph.entity_viewed` | AT-GRAPH-002: P0 cold-start/objective spine and declared linked entities exist; broader domain depth annotated P1 |
| GRAPH-003 · Edge contract | P1 | Relationship detail | Context Ops lineage | S2,S7,S8 | BN | Backend with visual trace | DEFERRED P1 | `graph.edge_inspected` | AT-GRAPH-003: tenant, version, source, epistemic/confidence, observed/valid time and visibility policy persist |
| GRAPH-004 · Inference limits | P1 invariant | Relationship/inference label | approval denial, Context Ops | S2,S7 | BN | Backend with warning | DEFERRED P1 | `graph.inference_corrected` | AT-GRAPH-004: inference cannot grant access, ownership, approval or action authority |
| GRAPH-005 · Cold-start graph | P0 launch | Greenfield timeline/Inspect | onboarding proposals | S0,S2 | B1 | Hybrid | NET_NEW I0 | `graph.cold_start_created` | AT-GRAPH-005: organisation→person/team→objective→project→decision plus approved proposals accumulates without fabricated history |
| GRAPH-006 · Deletion/lineage | P1 | Settings · lineage/purge | relationship detail | S8,S9 | B3 | Backend/admin | DEFERRED P1; purge primitives I9 | `graph.lineage_viewed/purged` | AT-GRAPH-006: every node/edge traces to source and participates in retention, disconnect, hold and downstream purge |

## 7.10 Documentation and learning

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| DOC-001 · Understand document | P0 launch | Document understanding | Objective Context, Intelligence | S2,S7 | B1 | Hybrid | PARTIAL ingestion/RAG I6 | `document.understood` | AT-DOC-001: section citations, version/freshness, authority, conflicts and linked objectives/decisions render |
| DOC-002 · Create/update document | P0 launch | Cited document diff | Objective decision, Confluence | S3,S6 | B1 | Hybrid | PARTIAL generated docs; governed update net-new I6/I7 | `document.update_proposed/published` | AT-DOC-002: cited reviewable diff targets declared source, checks conflict/authority, publishes and reconciles |
| DOC-003 · Decision artifact | P0 launch | Decision publication | Objective timeline, Confluence | S3,S6 | B1 | Hybrid | NET_NEW I0/I7 | `decision.artifact_published` | AT-DOC-003: approved record links objective, service, repos, design, plan and evidence |
| DOC-004 · Drift/supersession | P1 | Document drift | Context conflicts, Services | S2,S3 | B2 | Hybrid | DEFERRED P1; contract drift reusable I6 | `document.drift_detected/resolved` | AT-DOC-004: stale/conflicting doc/design/decision relationships support explicit resolution and supersession |
| DOC-005 · Learning-to-standard | P1 | Learning proposal | incident, review, retrospective | S3,S6 | B2 | Hybrid | DEFERRED P1; standards package reusable | `learning.proposed/approved` | AT-DOC-005: separately approved learning updates test/runbook/standard/template/check/training; never auto-policy mutation |

## 7.11 Collaboration and flow

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| COL-001 · Bounded collaboration | P0 launch | Objective handoff/review | Intelligence, deep link | S1,S4 | B1 | Hybrid | PARTIAL invitations/notifications I1/I7 | `collaboration.requested/responded` | AT-COL-001: enough context to accept/decline/redirect; recipient permission checked; no silent assignment |
| COL-002 · Slack P0 | P0 launch | Notification/deep link | Settings mapping | S6,S8 | B1 | Integration+visual | PARTIAL/UNVERIFIED I7 | `slack.notification_sent/opened` | AT-COL-002: concise status and authorised deep link only; no query/action or blanket ingestion |
| COL-003 · Slack/Teams actions | P1 | External bounded action | Console confirmation | S4,S7 | B2 | Integration+visual | DEFERRED P1 | `collaboration.external_action_requested` | AT-COL-003: mapped user re-authorised; action cannot exceed Console authority; no blanket ingestion |
| FLOW-001 · Objective timeline | P0/P1 split | Objective activity/timeline | Home, Flow | S1,S4,S5,S6 | B1 | Hybrid | REUSABLE_WITH_CHANGE activity feed I9 | `objective.timeline_viewed` | AT-FLOW-001: stage, active, waiting, blocked, handoff and rework events are durable and ordered |
| FLOW-002 · System bottlenecks | P1 | Intelligence · Flow | Services/team scope | S7 | B2 | Analytics+visual | DEFERRED P1 | `flow.constraint_viewed` | AT-FLOW-002: system queues/waits/flakes/builds/dependencies/batch/rework aggregate without ranking people |

## 7.12 Services and operations

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| OPS-001 · Service workspace | P0 launch | Services portfolio/detail | Objective entity link | S0,S1,S2 | B1 | Hybrid | NET_NEW Service model; project/architecture reusable I6 | `service.viewed` | AT-OPS-001: ownership, repos, dependencies, APIs, envs, deployments, runbooks, standards, work and evidence are coherent |
| OPS-002 · Live state | P0 launch | Service live-state panel | Intelligence, Incident | S2,S7,S8 | B1 | Hybrid | PARTIAL deployment/preview I10 | `service.live_state_requested` | AT-OPS-002: authorised live source and observation time required; unavailable becomes unknown |
| OPS-003 · P0 operational boundary | P0 boundary | Read-only incident/service | action denial/handoff | S4,S7 | B1 | Policy+visual | CURRENT FIGMA CONFLICT | `operations.mutation_blocked` | AT-OPS-003: P0 operational tools are read-only; broad cloud CLI/shell/production action unavailable |
| OPS-004 · Incident workflow | P1/Conditional | Incident investigation | PagerDuty/ServiceNow deep link | S1,S2,S4,S6 | B2 | Hybrid | UNVERIFIED legacy I7; deferred maturity | `incident.objective_linked` | AT-OPS-004: when enabled, intake/investigation/remediation proposal/learning/write-back are annotated Conditional; no automatic production action |
| OPS-005 · Governed production action | P2 | Future typed action | incident recommendation | S4 | B2 | Hybrid | DEFERRED P2; excluded from P0/P1 | `production_action.*` | AT-OPS-005: allow-listed short-lived approved verified auditable reversible action only under explicit contract |

## 7.13 Integrations

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| CONN-001 · Capability contract | P0 invariant | Settings connector detail | onboarding | S8,S9 | BN | Backend/admin | NET_NEW canonical contract; adapters partial | `source.capability_viewed` | AT-CONN-001: read/subscribe/trigger/act/publish/identity/permission/freshness/deletion/audit-reconcile declared separately |
| CONN-002 · GitHub | P0 launch | GitHub connector detail | Objective source, PR/CI | S8,S6 | B2 | Hybrid | REUSABLE_WITH_CHANGE I8 | `source.github_*` | AT-CONN-002: declared repo/branch/commit/PR/review/check/CODEOWNERS/webhook/app/write-back contract passes connector tests |
| CONN-003 · Bitbucket Cloud | P0 launch | Bitbucket connector detail | Objective source, PR | S8,S6 | B2 | Hybrid | REUSABLE_WITH_CHANGE I8 | `source.bitbucket_*` | AT-CONN-003: repo/branch/commit/PR/review/webhook/workspace identity/write-back map to SCM contract with differences explicit |
| CONN-004 · Jira Cloud | P0 launch | Jira connector detail | Objective source, write-back | S8,S6 | B2 | Hybrid | UNVERIFIED_LEGACY I7 | `source.jira_*` | AT-CONN-004: issues/epics/AC/deps/comments/assignment/webhooks/readiness and approved write-back preserve actor/source permission |
| CONN-005 · Confluence Cloud | P0 launch | Confluence connector detail | Context/doc update | S8,S6 | B2 | Hybrid | UNVERIFIED_LEGACY I7 | `source.confluence_*` | AT-CONN-005: page/section/version/comment/labels/link/source-of-truth/citation/drift/reviewed publication contract passes |
| CONN-006 · Figma | P0 launch | Figma connector detail | Objective source, Context | S8,S2 | B2 | Hybrid | NET_NEW I12 | `source.figma_*` | AT-CONN-006: selected hierarchy/variants/variables/assets/annotations/comments/version/mapping are permission-bound; no design mutation |
| CONN-007 · Slack | P0/P1 split | Slack connector detail | notification/deep link; P1 query | S8,S7 | B3 | Hybrid | PARTIAL/UNVERIFIED I7 | `source.slack_*` | AT-CONN-007: P0 notify/deep-link only; P1 query/actions visibly gated; only curated decisions persist |
| CONN-008 · GitHub Actions/Checks | P0 launch | CI connector/status | Objective Verify, PR | S5,S8 | B2 | Hybrid | PARTIAL I4/I8 | `source.github_checks_*` | AT-CONN-008: first native trigger/status binds SHA/event and reconciles customer-authoritative check result |
| CONN-009 · Provider-neutral CI API/CLI | P0 launch | Settings CI API/CLI | CLI/IDE, Bitbucket Pipelines | S5,S8 | B2 | Hybrid | PARTIAL run/test APIs I2/I4 | `verification.external_ci_*` | AT-CONN-009: approved CI can trigger/poll/publish bounded exact-version results without a false native-adapter claim |
| CONN-010 · Initial preview/deployment route | P0 launch | Objective Deliver/Preview | Service, Evidence | S4,S5,S6 | B2 | Hybrid | REUSABLE_WITH_CHANGE I10 | `preview.started/stopped/verified` | AT-CONN-010: one route exposes environment, artifact/version, health, TTL/cleanup and evidence; no multi-cloud promise |

## 7.14 Personalisation and memory

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| MEM-001 · Explicit preferences | P0 launch | Settings · Personalisation | composer/settings shortcut | S9 | B3 | Hybrid | PARTIAL account preferences; enterprise categories net-new I11 | `preference.updated` | AT-MEM-001: user controls explanation, planning, review, notification, tool and workflow defaults with effects explained |
| MEM-002 · Inferred preference | P1 | Settings · Personal memory | response influence detail | S9 | B3 | Hybrid | DEFERRED P1 | `memory.inferred/confirmed` | AT-MEM-002: evidence, confidence, scope, confirmation, expiry, sensitivity and correction are inspectable |
| MEM-003 · Scope | P0/P1 split | Memory scope control | Objective/project settings | S9 | B3 | Hybrid | PARTIAL project memory I6; personal/team model net-new | `memory.scope_changed` | AT-MEM-003: private, team, project and org context separate; private cannot become shared without explicit action |
| MEM-004 · Control | P1 | Personal memory list/detail | privacy/export | S9 | B3 | Hybrid | DEFERRED P1 | `memory.edited/disabled/deleted` | AT-MEM-004: view/edit/disable/delete/correct works with audit and derived-data effect |
| MEM-005 · Precedence | P0 invariant | Preference conflict explanation | action gate, Settings policy | S3,S9 | BN | Backend with explanation | NET_NEW canonical precedence over I11 | `preference.overridden` | AT-MEM-005: org/security/project policy and explicit action instruction override personal/inferred preference |

## 7.15 Governance and Context Operations

| ID · requirement | Priority/maturity | Primary surface | Alternate entry points | States | Breakpoint | Classification | Current status | Analytics | Acceptance proof |
|---|---|---|---|---|---|---|---|---|---|
| GOV-001 · Tenant/RBAC | P0 invariant | Permission/role effects | every surface | S2,S4,S8,S9 | BN | Backend with visible denial | REUSABLE_WITH_CHANGE I1 | `authorization.denied` | AT-GOV-001: every read/write/action enforces org/project/source scope and denies by default |
| GOV-002 · Enterprise identity | P0 SSO; P1 SCIM | Settings · Identity | sign-in/onboarding | S8,S9 | B3 | Hybrid | PARTIAL SSO config I1; protocol proof incomplete | `identity.sso_configured/failed` | AT-GOV-002: OIDC/SAML works for launch; SCIM marked P1 unless contract pulls it forward |
| GOV-003 · Immutable audit | P0 launch | Settings · Audit/export | Objective activity, action detail | S4,S6,S9 | B3 | Hybrid | REUSABLE_WITH_CHANGE I9 | `audit.searched/exported` | AT-GOV-003: identity/source/retrieval/action/policy/approval/result/correlation/evidence are tamper-evident and exportable |
| GOV-004 · Source inventory/health | P0 launch | Settings · Integrations | onboarding | S8,S9 | B3 | Hybrid | PARTIAL adapters/inventory I7/I8 | `source.health_viewed` | AT-GOV-004: owner, scope, credentials, permission, webhook/cursor, health, freshness, failures and validation render |
| GOV-005 · Permission drift | P0/P1 split | Context Ops · permission drift | connector detail, query denial | S2,S7,S8 | B3 | Hybrid | NET_NEW canonical drift; guards reusable I1 | `source.permission_drift_detected` | AT-GOV-005: ACL/identity failure prevents stale permission from widening answer/action; impact and recovery explicit |
| GOV-006 · Retention/deletion | P0 launch | Settings · Data lifecycle | disconnect, export, purge | S8,S9 | B3 | Hybrid | PARTIAL DSAR/export I9 | `data.disconnect/purge_*` | AT-GOV-006: retention, disconnect, quarantine, hold where contracted, export and verified purge cover all derived stores |
| GOV-007 · Intelligence quality | P1 | Context Ops · query quality | Intelligence trace | S7,S9 | B3 | Hybrid | DEFERRED P1; eval harness reusable | `query.feedback/evaluation_*` | AT-GOV-007: trace, diagnostics, feedback, golden queries, stale-answer and inferred-edge review work without leaking content |
| GOV-008 · Model/data policy | P0 launch | Settings · Governance and AI | model selector/action gate | S8,S9 | B3 | Hybrid | REUSABLE_WITH_CHANGE I11 | `model.policy_changed/route_blocked` | AT-GOV-008: only approved routes/retention/training policy/keys-gateway are available and provider identity is explicit |
| GOV-009 · Data residency | P0 launch | Settings · Region/data | workspace setup, security review | S0,S9 | B3 | Hybrid | PARTIAL deployment config; product contract net-new | `workspace.region_viewed` | AT-GOV-009: exactly one supported launch region is declared; unavailable deployment/residency options are not implied |
| GOV-010 · Security review artifacts | P0 launch | Settings · Security & compliance | procurement export | S9 | B3 | Hybrid | PARTIAL documentation/audit; product home net-new | `security.artifact_viewed/exported` | AT-GOV-010: data flow, subprocessors, isolation, incident response, vulnerability management and pentest posture are current and accessible |

## Coverage summary

- **109/109** stable requirement IDs are classified.
- Every requirement has priority/maturity, primary surface, alternate entry points, required state bundle, breakpoint responsibility, visuality, current implementation status/evidence anchor, analytics family, and acceptance proof.
- `NET_NEW`, `PARTIAL`, `REUSABLE_WITH_CHANGE`, `UNVERIFIED_LEGACY`, `DEFERRED P1`, and `DEFERRED P2` are intentionally distinct. A file or adapter name is not treated as production proof.
- Accepted Figma nodes are bound through `06-SCREEN-STATE-MANIFEST.md`, `19-CROSS-SURFACE-CLOSURE-AUDIT.md`, `21-CODING-AGENT-IMPLEMENTATION-HANDOFF.md` and `22-FIGMA-PROTOTYPE-GRAPH.json`. Production `AT-<ID>` commands/results remain to be attached during implementation.
