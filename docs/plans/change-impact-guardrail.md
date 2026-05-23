# Change-impact guardrail — impact at the decision moment

> Created 2026-05-22. Status: **proposed**. Owner: founder.
>
> Goal: at the plan-approval gate, tell the human **what shared code a run is about
> to touch and who depends on it** — in plain language, grounded in the contract
> dependency graph we already store. v1 is **warn-only with an acknowledged
> override** (no hard blocking). It turns approval from a blind "looks fine" into
> triage: safe changes flow, changes that ripple into other teams get a human's
> attention before tokens are spent.

## Why now — intellectual control at 10x

This traces directly to the "Software Engineering at the Tipping Point" talk (Google I/O, 2026). Its central worry: as AI generates 10x more code, humans lose the ability to *reason about* the system, and the two scarcest resources become **review** ("code review is becoming the bottleneck… people cut corners to avoid being blockers") and **human attention** ("the most precious resource we have").

We audited ExecuteSpec against the talk and found we already cover a lot — cost visibility, automated review, real test execution, isolation, architecture generation, drift detection. The biggest gap was **reasoning over the system we've built**: the project chat can *read* the architecture doc but has no way to answer "what breaks if I change this?" All nine chat tools are read-state or run-pipeline; there is no *analyze* tier.

The key realization: we don't need new intelligence to close this. We already persist a **dependency graph** — `ContractRegistry` (nodes) + `ContractConsumer` (edges) — plus a per-format `BreakingChangeService`. We just never surface it at the moment a human is deciding whether to let a change proceed. This plan exposes it where it has the most leverage: the plan gate.

## The core idea — triage, not a viewer

The moment impact can change what the human *does* at the gate (even just "stop and acknowledge"), this stops being a blast-radius viewer and becomes **automatic triage of which changes deserve attention**. That is the direct answer to the talk's two loudest worries. Instead of a human scanning 10x more changes hoping to catch the dangerous one, the system lets the low-risk ones flow and pulls a human's eye to the ones that ripple into other teams.

## Personas

Inferred from the tier ladder + the talk; should be validated against who actually approves runs today. The same engine serves all four at different altitudes.

| Persona | Altitude | What they ask | What they consume | Role in v1 |
| --- | --- | --- | --- | --- |
| **IC engineer / "doer"** | Per-change | "Am I about to break another team?" | The Impact block at the gate | Primary daily **user** |
| **Tech lead / "approver"** | Per-decision | "Is this run safe to approve?" | Same Impact block, at the approval point | Daily user |
| **Junior-with-agents** | Constant, no intuition | "I don't even know what depends on what" | The Impact block as a **judgment prosthetic** | The emotional center — the guardrail manufactures the intuition they haven't earned |
| **EM / architect / "steward"** | Periodic, portfolio | "Where are we fragile? Who must sign off on what?" | The policy config + the fragility list | The **buyer** — sets the line, sees the forest |

**Buyer vs user split (explicit):** the steward writes the enterprise check; the IC/junior gets the daily value. v1 must satisfy both — a daily guardrail for the IC and a configurable lever + portfolio view for the steward.

## Scope — v1 (Option A)

**In scope:**

1. **Plan-gate coordination warning.** When a PRODUCTION run's plan will touch a contract that has ≥1 declared consumer, the gate shows an Impact block: which contract, which consumer repos/teams, fan-out count, and an honest coverage note.
2. **Acknowledged override.** The warning is never a dead-end. The approver can always proceed; when policy requires it, proceeding is gated behind an explicit "Proceed anyway" with an optional reason, recorded for audit.
3. **Steward policy.** An org-level dial: per change-class and per-contract-tag severity (`OFF` / `WARN` / `WARN_REQUIRE_ACK`). No hard `BLOCK` in v1.
4. **Per-contract tags.** The steward can tag contracts (e.g. `public-api`, `internal`) to drive tag-specific severity.
5. **Read-only fragility list.** A project's top-5 most-depended-on contracts (ranked by declared consumers), so the steward sees where risk concentrates.
6. **Honest coverage.** Every Impact block states it counts *declared* consumers only and that unpinned consumers are invisible — with a link to pin one.

**Explicitly NOT in v1** (see [Future work](#future-work)):

- **Hard blocking / breaking-change detection.** "Breaking" cannot be known at plan time (no diff exists yet), so the hard block is deferred to moment 2 (below).
- **Downstream notification** of the affected team.
- **Guardrail coverage for the four unattended autofix surfaces** (PR-review, tech-debt, CI-failure, code-review).
- **Capacity / "what-if-traffic-10x" simulation** — needs runtime telemetry we don't collect.
- **Exploratory what-if in project chat** — the conversational `analyze` tool tier.

## Design rationale — the guardrail is two moments

Designing the gate copy surfaced the load-bearing constraint: **whether a change is breaking can only be known once the code exists.** At plan time we know *which files/contracts will be touched*, not *how*. So the guardrail naturally splits, and the split maps onto the confidence ladder:

| Moment | When | What's knowable | Guardrail | Status |
| --- | --- | --- | --- | --- |
| **1 — plan gate** | Pre-code, at `AWAITING_APPROVAL` | *What* will be touched + *who* depends | Coordination warning (+ acknowledged override) | **v1** |
| **2 — PR / pre-push** | Post-code, diff exists | Whether the change is *breaking* (`BreakingChangeService`) | Hard block + sign-off | **deferred** |

Moment 1 is cheap, immediate, and lets the approver bail *before* tokens are burned generating code that would later be blocked — a direct hit on the talk's token-waste worry. Moment 2 needs the real diff and a post-code checkpoint; it becomes the obvious next slice once the warning has earned trust.

## Enforcement model — enforce in proportion to certainty

The governing principle (and the answer to the talk's "people route around bottlenecks" caution): **only spend enforcement strength on what we are sure of.** v1 deliberately tops out at "require acknowledgment" because, with no diff, we are never certain enough to block.

| Confidence | Signal | v1 behavior |
| --- | --- | --- |
| Medium | Plan touches a consumed contract | `WARN` — show the Impact block; Approve works as normal |
| Medium (steward-elevated) | Same, but policy/tag says it matters | `WARN_REQUIRE_ACK` — Approve gated behind acknowledged override + optional reason |
| Low | Plan touches code with no known consumers | No block (optionally an informational line) |
| High | Breaking change to a declared consumer | **Deferred to moment 2** (`BLOCK` reserved in the schema, not exercised in v1) |

## Functional requirements

- **FR-1 — Change-impact compute.** A new `ChangeImpactService` computes, at plan emission, the set of impacted contracts: intersect the plan's declared target paths with `ContractRegistry.detectedPath` (per repo), then resolve `ContractConsumer` rows (`contract_id` + `declared_in_project_id = this project`) to consumer repos. Output is a snapshot: `[{contract, type, producerRepo, consumers[], fanOut, tags[]}]` + a coverage descriptor. Plan-time matching is path-based and **approximate by design** — it answers "what could this touch," refined to certainty only in moment 2.
- **FR-2 — Gate payload + Impact block.** The computed snapshot is attached to the run's gate state and returned on the run-detail / gate read so the frontend renders the Impact block above the Approve action. Applies to PRODUCTION runs only (PROTOTYPE bypasses the gate and is isolated by design — out of scope).
- **FR-3 — Coverage honesty.** The snapshot always carries a coverage note ("N declared consumers; unpinned consumers won't show") and a pin affordance. The system never implies completeness.
- **FR-4 — Acknowledged override.** `POST /api/runs/{id}/approve` accepts `impactAcknowledged: bool` and `overrideReason: string?`. When effective severity is `WARN_REQUIRE_ACK`, approval is rejected unless `impactAcknowledged=true`. The acknowledgment + reason + the snapshot it referred to are persisted (FR-7).
- **FR-5 — Steward policy.** Org-scoped `ChangeImpactPolicy`: a default severity (`OFF`/`WARN`/`WARN_REQUIRE_ACK`) plus per-tag overrides. Read by `ChangeImpactService` when grading a snapshot. Edited via `GET`/`PUT /api/org/me/change-impact-policy` (ORG_ADMIN / ENTERPRISE, mirroring the `/api/org/me/model-matrix` pattern). Lenient on read.
- **FR-6 — Contract tags.** `ContractRegistry` gains a `tags` field; an endpoint lets the steward tag/untag a contract. Tags drive FR-5 per-tag severity.
- **FR-7 — Override audit.** Every acknowledged override writes an audit row (snapshot id, runId, userId, reason, timestamp) queryable by the steward — the trail that justifies the enterprise lever.
- **FR-8 — Fragility list.** `GET /api/projects/{id}/change-impact/fragility` returns the top-N contracts ranked by declared-consumer count. Read-only in v1.
- **FR-9 — Feature flag + tier gate.** Behind `change-impact.enabled` (`FeatureFlagService`, default OFF). Gated to tiers that have the contracts/consumer graph (lean ENTERPRISE; TEAM is an open question).

## UX / output design — what the human actually reads

The guiding principle for "intuitive for a normal person": **speak in their nouns, not the graph's.** Severity is a human consequence, not a metric. Be honest about blind spots.

### IC / approver — the gate Impact block

```
Impact:  ⚠ Needs coordination
This plan modifies the orders API (orders.openapi.yaml).
3 services depend on it:
   • mobile app          team-mobile
   • billing service     team-payments
   • analytics pipeline  team-data

Whether the change is breaking is checked automatically once the
code is written — before the PR merges.   (coming soon)

Coverage: 3 declared consumers. Repos that consume this without
being pinned won't show here.   [Pin a consumer]

[ Approve ]            ← when severity = WARN
[ Proceed anyway ▾ ]   ← when severity = WARN_REQUIRE_ACK (reason optional, logged)
[ Cancel ]
```

### Steward — the policy surface

```
Change-impact policy — org: acme

When a plan affects a contract with declared consumers:
   Default            →  [ Warn & log ▾ ]      (Off / Warn / Warn + require ack)

Tag overrides:
   public-api  →  Warn + require acknowledgment
   internal    →  Warn & log
   [+ add tag rule]

Where risk concentrates  →  your 5 most-depended-on contracts   (read-only)
```

## Data & system touchpoints

**Reused (no change):**

| Component | Role here |
| --- | --- |
| `contracts/ContractRegistry` + `ContractConsumer` | The dependency graph — nodes + edges |
| `contracts/breaking/BreakingChangeService` | Moment-2 breaking detection (deferred) |
| `integration/RunOrchestrator` + `GateStore.awaitPlanGate` | Where the plan gate pauses; where the snapshot attaches |
| `agent/OutlinePlannerAgent` (the emitted Plan) | Source of declared target paths |
| `config/featureflag/FeatureFlagService` | `change-impact.enabled` |
| Audit-event infrastructure (cf. `ORG_EXTERNAL_SHARING_CHANGED`) | Override trail |

**New:**

| Component | Kind | Notes |
| --- | --- | --- |
| `ChangeImpactService` | Service | The compute (FR-1) + policy grading (FR-5) |
| `ChangeImpactAssessment` | Entity + table | Per-run plan-time snapshot; referenced by the override |
| `ChangeImpactPolicy` | Entity + table | Org-scoped default + per-tag overrides |
| `change_impact_override` | Audit row | FR-7 |
| `ContractRegistry.tags` | Column | FR-6 |
| `ChangeImpactController` | REST | policy GET/PUT, fragility GET, contract-tag mutate |

**Proposed migrations** (head is currently **V205**, so the next free numbers): `V206` contract tags, `V207` `change_impact_policy`, `V208` `change_impact_assessment` + override. *Confirm head with `ls V*.sql` at implementation time — do not reuse an applied number.*

**API additions:**

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/api/runs/{id}/approve` | extend body: `impactAcknowledged`, `overrideReason` |
| GET | `/api/runs/{id}` (or gate read) | include the impact snapshot |
| GET / PUT | `/api/org/me/change-impact-policy` | steward policy (ENTERPRISE / ORG_ADMIN) |
| GET | `/api/projects/{id}/change-impact/fragility` | top-N most-depended contracts |
| PUT | `/api/projects/{id}/contracts/{contractId}/tags` | contract tags |

## Non-goals

- Not a hard-blocking gate in v1. Not a CI gate.
- Not a capacity/scaling simulator.
- Not a replacement for code review or the reviewer agent — it's a *targeting* signal for human attention.
- Not dependent on perfect consumer declarations — it degrades honestly, it doesn't fail.

## Open questions / risks

1. **Tier line** — ENTERPRISE-only (matches the contracts surface + the buyer), or include TEAM? Leaning ENTERPRISE.
2. **Plan-path fidelity** — how reliably does the emitted Plan declare the files it will touch? If target paths are vague, plan-time matching over/under-counts. Needs a spike against real plans.
3. **Edge sparsity** — the warning is only as useful as declared consumers. Without auto-detection, sparse pinning makes it quiet. Honest-coverage mitigates trust risk but not usefulness; the fragility list + pin nudge are the growth loop.
4. **Old-content source for moment 2** — confirm `ContractVersion` stores the bytes `BreakingChangeService` needs to diff against.
5. **Override fatigue** — if `WARN_REQUIRE_ACK` is the default, approvers may rubber-stamp. Keep the default at `WARN`; let stewards elevate per-tag.

## Future work (fast-follows, in likely order)

1. **Moment 2 — breaking-change hard block.** Post-code checkpoint at the PR / pre-push: run `BreakingChangeService` on the real diff; `BLOCK` + sign-off or override-with-reason for breaking changes to declared consumers. This is what the reserved `BLOCK` severity unlocks.
2. **Downstream notification.** Tell the *affected* consumer's owning team a change is coming (needs ownership mapping — CODEOWNERS / project membership). The 10x coordination primitive.
3. **Autofix-surface coverage.** Extend the chokepoint to the four unattended PATCH surfaces, where a silent agent change is most dangerous.
4. **Exploratory what-if in chat.** The `analyze` tool tier — `query_blast_radius` / `classify_proposed_change` wired into `ProjectChatService` for hypotheticals.
5. **Capacity what-if (narrative).** Qualitative scaling reasoning off the codemap, explicitly labeled as estimate, not simulation.

## Success criteria (v1 acceptance)

- For any flagged-on PRODUCTION run whose plan touches a contract with ≥1 declared consumer, the gate renders an accurate Impact block (contract, consumers as repo/team names, fan-out, coverage note).
- Steward can set an org default severity + per-tag overrides; the gate honors them (`OFF` hides, `WARN` shows, `WARN_REQUIRE_ACK` requires an acknowledged override).
- `approve` captures `impactAcknowledged` + optional reason; overrides are persisted and queryable by the steward.
- Fragility endpoint returns the top-5 most-depended contracts for a project.
- No Impact block ever implies complete coverage; the unpinned-consumer caveat is always present.
- Nothing hard-blocks in v1.
- Everything sits behind `change-impact.enabled`.

## Decision log

The forks settled during the 2026-05-22 brainstorm that produced this plan:

1. **Ambient-at-gate over chat-first** — enrich the existing approval gate, not a separate "ask the oracle" mode (highest leverage; hits the bottleneck the talk names).
2. **Honest coverage that grows** — never fake completeness; report blind spots; nudge pinning. Trust over magic.
3. **Both personas** — steward is the buyer (policy + forest view), junior/IC is the user base (daily guardrail).
4. **Proactive with guardrails** — the system surfaces impact at the decision moment rather than waiting to be asked.
5. **Option A first** — warn-only with acknowledged override; the breaking-change hard block (moment 2) is the immediate fast-follow, because "breaking" can't be known until the code exists.
