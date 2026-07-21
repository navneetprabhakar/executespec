# ExecuteSpec Enterprise — Action Authority, Effect, and Recovery Contract

Status: **Normative P0/P1 action boundary**

## Authority levels

| Level | Capability | P0 examples | Required gate | Recovery |
|---:|---|---|---|---|
| 0 | allow-listed public/metadata read | provider catalogue, non-sensitive status | tenant/product policy | retry or hide unavailable source |
| 1 | authorised read/search | repo/doc/work-item context, live read-only service state | current user/source permission before retrieval | refresh, request access, change scope |
| 2 | analysis/proposal | plan, answer, test proposal, remediation recommendation | cited authorised context; no mutation | edit/reject/supersede |
| 3 | reversible content/work mutation | Jira comment/transition, Confluence reviewed update, notification, collaboration request | exact scoped confirmation or approved policy; delegated identity | idempotent retry, reconcile, compensating update where supported |
| 4 | repository/delivery mutation | branch/commit/PR/check, bounded CI trigger, approved preview route | approved Objective/plan, repo policy, budget, exact SHA/env, expected evidence | cancel/supersede, close/update PR, stop/clean preview, reconcile |
| 5 | production/operational mutation | rollback/remediation | **P2/Conditional only**: allow-list, short-lived identity, approval, preconditions, live verification, audit, rollback | typed rollback/forward recovery and incident escalation |

Slack entry can never raise the mapped user above their Console authority. UI approval is not authority; Services revalidates immediately before dispatch.

## Canonical action envelope

```yaml
schemaVersion: 1
actionId: stable-id
tenantId: required
objectiveId: required
actor:
  id: required
  type: USER | SERVICE | WORKLOAD
  initiatingUserId: required-for-consequential-action
operation: versioned-type
maturity: CURRENT | PARTIAL | PLANNED_P0 | P1 | P2 | CONDITIONAL
target:
  type: required
  id: required
  version: required-when-versioned
scope:
  projectIds: []
  serviceIds: []
  repositoryIds: []
  environmentIds: []
requestedAuthority: 0..5
policyVersion: required
permissionRefs: []
routeOrTool: required
budget:
  cost: optional
  time: optional
  resources: optional
preconditions: []
expectedEffect: required
expectedEvidence: []
recovery:
  cancellationBoundary: required
  retryPolicy: required
  reconciliation: required-for-external-effect
  rollbackOrCompensation: required-or-explicitly-unavailable
approval:
  required: boolean
  approver: optional
  approvalHash: optional
  expiresAt: optional
idempotencyKey: required-for-retryable-or-external-effect
correlationId: required
```

## Material invalidators

Any change to actor/effective identity, tenant, target, target version/SHA, scope, route/model/tool, permission, policy version, budget, environment, data recipe, expected effect or recovery invalidates the approval. The UI changes to `Approval invalidated`, disables execution, identifies the changed fields and routes to review.

## P0/P1 action matrix

| Action | Level | P0/P1 maturity | UI class | Authority/effect | Recovery requirement |
|---|---:|---|---|---|---|
| Search Objective context | 1 | P0 | normal | read current permitted scope | partial/denied/timeout with no leaked cache |
| Ask Objective-scoped Intelligence | 1–2 | P0 | normal | read + cited answer/proposal | abstain/partial/change scope |
| Organisation-wide query | 1–2 | P1 | normal with P1 badge | explicit org scope and per-viewer re-authorisation | narrow scope/abstain |
| Approve bootstrap proposal | 3 | P0 | approval | converts generated proposal to declared context | supersede; preserve history |
| Approve plan/start bounded execution | 4 | P0 | approval | exact repos/workspace/tools/budget/evidence | cancel/supersede/resume where supported |
| Trigger on-demand verification | 4 | P0 | approval or normal when pre-approved | exact SHA, tests, env, data, cost | cancel, retry selected, reconcile CI |
| Adopt generated test | 4 | P0 | approval | reviewable change to selected repo/branch | reject/quarantine/revert through SCM |
| Create/update GitHub/Bitbucket PR | 4 | P0 | high-impact | exact branch/PR and initiating user | idempotent update/close/reconcile |
| Jira comment/status/evidence | 3 | P0 | high-impact | exact issue/version and attributed write-back | retry/reconcile/compensating comment where valid |
| Confluence document publication | 3 | P0 | high-impact | reviewed diff against exact page version | block stale version; rebase/reconcile |
| Figma context read/link | 1 | P0 | normal | selected nodes/assets/version only | refresh/re-authorise; no mutation |
| Slack notification/deep link | 3 | P0 | normal/high-impact by audience | mapped target and minimal permitted content | delivery status/retry; Console fallback |
| Slack query/approval/collaboration | 1–3 | P1 | P1-labelled | delegated mapped user; bounded actions | Console confirmation/re-authorisation |
| Start/stop supported preview | 4 | P0 | high-impact | exact artifact/env/TTL/cleanup | stop/cleanup/reconcile evidence |
| Change model route | 3–4 | P0 admin/run policy | high-impact | approved provider/model/data/usage policy | revert route; preserve audit/cost |
| Grant/expand connector scope | 3 | P0 admin | high-impact | named capabilities, permissions, data and audience | narrow/revoke/reindex/purge derived data |
| Disconnect connector | 3 | P0 admin | destructive | stops sync/action and declares retained/deleted data | reconnect as new event; no automatic content restoration |
| Export governed data/evidence | 3 | P0 | high-impact | exact classes/scope/recipient/expiry | revoke link where supported; audit export |
| Purge derived data | 3 | P0 admin | destructive | exact source and downstream stores; hold precedence | retry incomplete stores; verification report; no false complete |
| Incident investigation | 1–2 | P1/Conditional | normal | read-only live/history evidence and proposal | refresh/handoff/create remediation Objective |
| Production rollback/remediation | 5 | P2/Conditional | destructive | absent from P0/P1 executable controls | typed rollback/forward recovery and live verification |

## UI behavior by lifecycle

| Lifecycle | Required presentation |
|---|---|
| Proposed | full typed action card; no side effect |
| Authority checked | permission/policy result and time; denied reason is safe/redacted |
| Awaiting approval | exact approval summary, expiry and invalidators; approve/reject/change scope |
| Queued/running | current phase, cancellation boundary, external correlation, cost/time where relevant |
| Verifying | expected evidence and authoritative system; separate process/outcome state |
| Succeeded | exact external result/evidence; next lifecycle boundary |
| Partial/failed | preserved completed effects, uncertainty, valid retry/reconciliation/compensation |
| Cancelled/superseded | side effects stopped where possible; stale publication prohibited; history preserved |

## Failure and uncertain external effect

When an external call times out after dispatch, the product must not offer a blind duplicate retry. It enters `Outcome unknown`, reads the authoritative target using the correlation/idempotency key, then either marks reconciled, retries safely, requests user resolution, or dead-letters for admin recovery. The screen names the exact uncertainty without exposing inaccessible content.

## Acceptance gate

Every visible consequential control in Figma must reference one row in this action matrix, display its maturity/severity, and transition through a state with defined authority, effect and recovery. A generic green CTA or an unwired button fails the gate.
