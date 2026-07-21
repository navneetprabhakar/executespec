# ExecuteSpec Enterprise — Analytics and UX Acceptance Tests

Status: **Normative event and closure-test contract**

## Analytics envelope

Every product event uses a versioned envelope:

```yaml
schemaVersion: 1
eventId: opaque-unique-id
eventType: namespace.verb
occurredAt: timestamp
tenantId: required
workspaceId: optional
projectId: optional
objectiveId: optional
actorId: opaque
actorType: USER | SERVICE | WORKLOAD
surface: ENTERPRISE_WEB | CLI | IDE | JIRA | SLACK | CI | OTHER
breakpoint: DESKTOP | COMPACT | TABLET | MOBILE | NON_VISUAL
correlationId: required-for-workflow
causationId: optional
sourceType: optional
maturity: CURRENT | PARTIAL | PLANNED_P0 | P1 | P2 | CONDITIONAL
result: optional-enum
reasonCode: optional-safe-enum
durationMs: optional
payload: no confidential source text, titles, prompts, secrets, hidden identities or model chain-of-thought
```

Events cannot be used to rank individual developers. Queries/answers store safe IDs, scope/mode/count/latency/cost/evaluation fields—not confidential text by default.

## Event catalogue

| Namespace | Required events | PRD ownership |
|---|---|---|
| `workspace` | `created`, `zero_info_viewed`, `brief_saved`, `region_viewed` | ONB-001–005, GOV-009 |
| `bootstrap` | `proposed`, `approved`, `rejected`, `superseded` | ONB-004, GRAPH-005 |
| `source` | `connection_requested`, `validated`, `failed`, `connected`, `health_changed`, `permission_drift_detected`, `disconnected`, `purge_started`, `purge_partial`, `purge_verified` | ONB-006, CONN-001–010, GOV-004–006 |
| `objective` | `created`, `linked`, `scope_bound`, `clarification_requested`, `stage_changed`, `blocked`, `cancelled`, `superseded`, `completed`, `summary_viewed`, `timeline_viewed` | OBJ-001–008, FLOW-001 |
| `context` | `bundle_created`, `compared`, `conflict_detected`, `conflict_resolved`, `permission_denied`, `untrusted_omitted`, `degraded`, `freshness_changed` | CTX-001–008 |
| `decision` | `session_started`, `proposed`, `approved`, `rejected`, `superseded`, `artifact_published` | DEC-001–002, DOC-003 |
| `plan` | `proposed`, `viewed`, `slice_recommended`, `override_recorded`, `approved`, `rejected`, `change_requested`, `invalidated` | PLAN-001–004 |
| `action` | `proposed`, `authority_checked`, `denied`, `approved`, `started`, `paused`, `cancelled`, `failed`, `partial`, `recovered`, `succeeded`, `production_blocked`, `boundary_violation` | ACT-001–006 |
| `verification` | `inventory_discovered`, `impact_mapped`, `plan_proposed`, `environment_checked`, `triggered`, `executed`, `classified`, `blocked`, `exception_requested`, `evidence_published`, `authority_reconciled` | VER-001–015 |
| `generated_test` | `proposed`, `validated`, `adopted`, `rejected`, `quarantined`, `regression_proved`, `behavior_proved` | VER-006–009 |
| `evidence` | `viewed`, `classification_viewed`, `missing_viewed`, `exported`, `shared`, `summary_viewed` | EVD-001/002/005/006 |
| `delivery` | `attempted`, `succeeded`, `failed`, `retried`, `reconciled`, `externally_changed` | EVD-003/004 |
| `query` | `asked`, `answered`, `partial`, `failed`, `greenfield_unknown`, `retrieval_mode_used`, `saved`, `shared`, `refreshed`, `feedback`, `evaluation_run` | INT-001–008, GOV-007 |
| `graph` | `query_executed`, `entity_viewed`, `edge_inspected`, `inference_corrected`, `cold_start_created`, `lineage_viewed`, `lineage_purged` | GRAPH-001–006 |
| `document` | `understood`, `update_proposed`, `publication_attempted`, `published`, `drift_detected`, `drift_resolved` | DOC-001–004 |
| `learning` | `proposed`, `approved`, `rejected` | DOC-005 |
| `collaboration` | `suggested`, `requested`, `sent`, `accepted`, `declined`, `redirected`, `expired`, `external_action_requested` | OBJ-006, INT-004/005, COL-001–003 |
| `flow` | `constraint_viewed`, `improvement_objective_created` | FLOW-002 |
| `service` | `viewed`, `live_state_requested`, `live_state_unavailable`, `incident_opened` | OPS-001–004 |
| `operations` | `mutation_blocked`, `recommendation_created`, `escalated` | OPS-003–005 |
| `preference` / `memory` | `updated`, `overridden`, `inferred`, `confirmed`, `scope_changed`, `edited`, `disabled`, `deleted` | MEM-001–005 |
| `identity` / `authorization` | `sso_configured`, `sso_failed`, `denied` | GOV-001/002 |
| `audit` / `security` | `searched`, `exported`, `artifact_viewed`, `artifact_exported` | GOV-003/010 |
| `model` | `policy_changed`, `route_blocked`, `route_selected` | GOV-008 |
| `continuity` | `token_created`, `opened`, `expired`, `revoked`, `source_changed`, `completed` | responsive continuity |

## Acceptance-test layers

Every `AT-<PRD-ID>` proof in the traceability matrix is implemented at the lowest deterministic layer that can prove it and, for user-visible P0 behavior, also has a rendered interaction test.

| Layer | Responsibility |
|---|---|
| Domain/unit | lifecycle, precedence, epistemic and evidence/action rules |
| API/contract | versioned schema, typed errors, additive compatibility, generated consumers |
| Persistence/migration | clean install, upgrade, lineage, idempotency, owner uniqueness |
| Permission/security | tenant/project/source/recipient isolation, prompt/untrusted-content authority |
| Connector sandbox | OAuth/app state, scope, pagination, webhook, retry, rate limit, reconciliation, deletion, API drift |
| Integration/system | DB/queue/outbox, exact SHA/env/data binding, cancellation/supersession/recovery |
| UI component | variants, keyboard, focus, screen reader, contrast, target size, zoom, localisation |
| Playwright/E2E | desktop/tablet/mobile journey, denial/partial/failure/recovery/return |
| Moderated prototype | comprehension and decision safety for the 14 closure scenarios |

## Universal UX assertions

Applied to every P0 user-facing `AT-*`:

1. Entry and requested scope are apparent.
2. One primary action is clear; secondary/recovery actions remain available.
3. Permission, freshness, evidence, authority and maturity claims agree.
4. Loading, empty, partial, failure, denial and recovery use state-specific content.
5. Desktop, compact, tablet and mobile responsibilities follow the responsive rules.
6. Keyboard focus is visible and ordered; screen-reader name/role/value/state is correct.
7. Targets pass the size floor; contrast passes WCAG 2.2 AA; 200% zoom and 30% expansion do not lose content/action.
8. Reduced motion preserves state comprehension.
9. Every visible prototype control is wired or honestly disabled/non-interactive.
10. Analytics event fires once with safe identifiers and correct result/reason; retry does not double-count business outcome.

## Fourteen closure scenarios

### SC-01 Zero-information startup

Path: EN-00 → EN-01 → EN-02 → EN-03 → EN-10/13.

Pass: no inherited sources/people/objectives; absence is explicit; generated proposals are labelled and reviewable; approval creates DECLARED context; rejection/supersession persists; desktop/tablet/mobile complete.

### SC-02 Jira-ready work entry

Path: Jira deep link → EN-60 → EN-11/12 → EN-14 → EN-13.

Pass: actor/tenant/Jira object/project/service/repos bind; readiness is explained; denial/expired/wrong-view states are redacted; no auto-execution.

### SC-03 Cross-source conflict resolution

Path: EN-14 conflict → EN-16 authority decision → EN-14 resolved.

Pass: source/version/freshness/authority shown; semantic similarity does not choose truth; resolution is durable and supersedable.

### SC-04 Plan approve, reject, expiry and invalidation

Path: EN-17 normal/rejected/change-scope/expired/invalidated → EN-19 or EN-12.

Pass: all choices are independently playable; exact authority shown; material change disables stale approval; mobile includes reject and change scope.

### SC-05 Working, pause, block, recovery and supersession

Path: EN-19 running → paused/blocked/failed → recovered or successor.

Pass: completed work/evidence preserved; cancellation/supersession prevents stale publish; valid retry/handoff only; panes remain reversible.

### SC-06 Evidence pass, fail, inconclusive and partial write-back

Path: EN-20/21/22 → EN-23 → EN-62.

Pass: process completion differs from proof; unmeasured visible; passes-both regression test is inconclusive; write-back uncertainty reconciles before retry.

### SC-07 Intelligence partial, denied and conflicting

Path: EN-30 → EN-31 variants.

Pass: scope/modes/citations/omissions/freshness render; denied has no leaked shell content; `Grounded answer` is absent for denied/unable; proposed action routes to EN-18.

### SC-08 Service unknown state and active incident

Path: EN-40 → EN-41 unknown/live → EN-42.

Pass: history is not current health; observation time shown; incident is P1/Conditional; P0/P1 offers investigation/recommendation/escalation, not executable rollback.

### SC-09 Connector first connection, drift, disconnect, export and purge

Path: EN-53 → EN-04/54 → EN-59.

Pass: official logo+name, capabilities/permissions/identity/freshness shown; drift is not Healthy; destructive actions have exact effect; partial purge cannot say complete.

### SC-10 Model route unavailable and usage limit

Path: EN-55 route selection → blocked/high-impact review → recovery.

Pass: provider logo/name and full model label; health separate from identity; blocked reason and allowed alternatives; route change is not generic green.

### SC-11 Studio/Enterprise switch

Path: Enterprise → EN-00 → Studio and reverse.

Pass: neutral choice, no opposite-view leakage, separate storage/context, explicit permitted reference only, target re-authorised.

### SC-12 Authorised and denied external deep links

Path: external source → EN-60 authorised/denied/expired/wrong-view.

Pass: exact object/action restored only after auth; denial withholds titles/source/member/graph content; return route safe.

### SC-13 Tablet Inspect workflow

Path: EN-T-13/14 → Inspect drawer → citation/evidence/action → close.

Pass: scrim/background inert/focus trap/Escape/back/close; focus restored; no clipped primary content; selected state preserved.

### SC-14 Mobile approval, recovery and desktop handoff

Path: EN-M-17 → approve/reject/change-scope/evidence → blocked/recovery or EN-M-61 → desktop.

Pass: all decision paths; 44px targets; secure one-time token; desktop re-authorises/revalidates versions; expiry/revocation/source-change recovery; origin shows completion.

## Adversarial release gates

- Zero unauthorised facts in E01Z-equivalent, E02D-equivalent, X01A-equivalent and X02B-equivalent states.
- Zero contradictory success/failure labels.
- Zero destructive/high-impact actions using the normal-primary treatment.
- Zero dead-end destination frames or unwired live-looking controls.
- Zero primary instructions/navigation/status labels below the typography floor.
- Intelligence, Services and Settings use their purpose-built information architecture.
- Provider/integration identity passes official-asset/licensing/accessible-name review.
- Automated contrast/target/font/reaction audit and human screenshot review both pass; one cannot substitute for the other.

The final closure record must attach test commands/results, exported reaction graph, screenshots, accessibility findings, unresolved/unmeasured conditions and owner decisions. Passing narrow component checks cannot prove the full product pack.
