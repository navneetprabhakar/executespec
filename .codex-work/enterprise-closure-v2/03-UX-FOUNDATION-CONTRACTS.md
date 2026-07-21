# ExecuteSpec Enterprise — Shared UX Foundation Contracts

Status: **Normative for the Enterprise closure redesign**

## 1. State is a tuple, not a badge

Every major object renders these independent fields. A single status string must never stand in for all of them.

| Field | Canonical values | User-facing responsibility |
|---|---|---|
| Process | `NOT_STARTED`, `READY`, `RUNNING`, `PAUSED`, `BLOCKED`, `CANCELLED`, `SUPERSEDED`, `FINISHED` | What the workflow is doing now |
| Outcome | `NOT_ASSESSED`, `PASSED`, `FAILED`, `PARTIAL`, `INCONCLUSIVE`, `UNMEASURED` | Whether the intended proof/outcome succeeded |
| Permission | `ALLOWED`, `LIMITED`, `DENIED`, `REVOKED`, `UNKNOWN` | What the current viewer may see or do |
| Availability | `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, `TIMEOUT`, `UNKNOWN` | Whether the source/tool/environment can respond |
| Freshness | `LIVE`, `FRESH`, `STALE`, `EXPIRED`, `UNKNOWN` | When the claim was observed and whether it is current enough |
| Evidence completeness | `COMPLETE`, `PARTIAL`, `MISSING_REQUIRED`, `UNMEASURED` | Which declared proof exists or is absent |
| Epistemic status | `AUTHORITATIVE`, `OBSERVED`, `DECLARED`, `GENERATED_PROPOSAL`, `DERIVED`, `INFERRED`, `UNKNOWN` | Why the product believes a fact and what it cannot authorise |

### Canonical display rules

1. Lead with the user-relevant compound truth: `Verification finished · 2 failed · 1 unmeasured`, not `Evidence complete`.
2. Process completion never implies outcome success.
3. A query is `Answered`, `Partial answer`, `Permission-limited`, `Conflicting evidence`, or `Unable to answer`; only complete/partial answers may be described as grounded.
4. A connector cannot be `Healthy` while disconnected, drifted, denied, or scheduled for deletion.
5. An invalidated plan is `Approval invalidated`, never `Plan ready`.
6. Every state uses text + semantic icon + optional colour. Colour is never the sole signal.
7. Unknown and unmeasured remain visible and cannot be converted to warning-free success by aggregation.

## 2. Semantic state presentation

| Semantic class | Examples | Colour role | Default permitted actions |
|---|---|---|---|
| Neutral/info | ready, declared, observed | information/foreground | inspect, continue, clarify |
| Active | running, verifying, syncing | active/info | inspect, pause/cancel if authorised |
| Attention | stale, partial, inconclusive, needs input | warning | inspect cause, refresh, clarify, hand off |
| Blocked | denied, unavailable, policy blocked | blocked/danger-neutral | request access, change scope, retry when valid |
| Failure | failed, environment failed, write-back failed | danger | inspect evidence, retry/recover, hand off |
| Success | passed, reconciled, verified complete | success | inspect proof, continue to next lifecycle boundary |
| Terminal neutral | cancelled, superseded, archived | muted | view history, create successor |

## 3. Action severity and authority

| Class | Use | Visual treatment | Confirmation |
|---|---|---|---|
| `NORMAL_PRIMARY` | current reversible low-risk next step | strongest accent for the current screen only | inline when effect is already clear |
| `APPROVAL` | authorise exact plan/action | approval accent distinct from normal create/save | decision card shows actor, target, scope, version, policy, evidence, cost, environment |
| `HIGH_IMPACT` | route change, access grant, external write, reconciliation | warning border/icon; never generic green | review step plus exact effect and recovery |
| `DESTRUCTIVE` | disconnect, purge, irreversible deletion | danger styling and separate action zone | typed/explicit confirmation, target, retained/deleted data, legal hold, recovery limitations |
| `BLOCKED` | unavailable, unauthorised, stale approval, policy conflict | disabled/blocked treatment with reason | cannot execute; show valid recovery path |

Production rollback/remediation is `P2/Conditional`. P0/P1 screens may render a `Recommendation`, `Request approval`, `Escalate`, or `Create remediation objective`; they must not render an executable rollback control.

## 4. Consequential action card contract

Every consequential action shows before execution:

- action type and maturity label;
- initiating actor and effective identity;
- exact target and version/SHA;
- tenant, project, service, repository and environment scope;
- route/tool and source permissions;
- budget/time/resource bounds;
- preconditions and stale/invalidating inputs;
- expected external effect;
- required evidence and authoritative system;
- cancellation, retry, reconciliation and recovery behavior;
- approval status, approver and expiry.

## 5. Navigation component contract

- Expanded row: Auto Layout, fixed 24px semantic SVG icon slot, 12px icon-label gap, fixed label anchor, minimum 40px desktop height and 44px touch height.
- Collapsed row: 44×44 minimum hit area, centred semantic SVG, accessible name, tooltip, optional unread/warning/state dot outside the glyph.
- States: default, hover, keyboard focus, active, unread, warning, disabled.
- Use one component/variant family in expanded/collapsed rails. Unicode glyph prefixes are prohibited.
- Pane collapse controls are reachable by mouse, touch and keyboard, and work in both directions.

## 6. Composer and query contract

The product uses two deliberately different controls:

1. **Objective composer** — centre pane. May clarify the Objective or propose typed work. It never mutates directly; consequential work becomes an action/decision card.
2. **Search context** — Inspect pane. Read-only. Label is always `Search context`, never `Ask` or an open-ended imperative. It shows current scope and retrieval permissions before submit.

Keyboard order: shell navigation → primary content → Objective composer → Inspect toggle → Inspect content → Search context. `Cmd/Ctrl+K` opens global Ask; `Cmd/Ctrl+Shift+F` focuses Search context when Inspect is open. Scope/history are stored separately and never bleed between the two controls.

## 7. Typography and target floor

Product font remains JetBrains Mono.

| Role | Size | Line height | Notes |
|---|---:|---:|---|
| Page heading | 24–28px | 1.2 | desktop; responsive clamp allowed |
| Section heading | 18–20px | 1.3 | no all-caps paragraph substitute |
| Primary body/instruction | 15–16px | 1.5 | decisions, warnings, explanations |
| Navigation/supporting body | 13–14px | 1.45 | never below 13px |
| Metadata/caption | 11–12px | 1.4 | exceptional timestamps/IDs only; never sole carrier of state/action |

- Desktop pointer targets: minimum 32px, 40px preferred for navigation/actions.
- Tablet/mobile/touch targets: minimum 44×44px.
- Support 200% zoom/reflow and 30% string expansion without loss of content/action.
- Fixed-height text rows must be removed or revalidated after the type ramp is applied.

## 8. Prototype interaction contract

Every visible control must have one of:

- a valid prototype reaction;
- a clearly annotated `not interactive in prototype` treatment that does not resemble a live control; or
- a maturity-disabled state with reason.

Required reversible transitions: left collapse/expand, right collapse/expand, Inspect selection/close, decision option select/change, approve/reject/change scope, pause/resume/cancel/retry/recover, modal/drawer close, back, secure cross-view switch, and return to P00.

No destination may be a dead end. State preservation must include Objective, selected evidence, pending decision, draft text, filters, authority expiry and source versions.
