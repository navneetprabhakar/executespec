# Cost and LLM usage attribution

## Canonical contracts

- Managed-credit debit reconciliation compares canonical `key_source=PLATFORM` attempts only with
  `managed_ai_usage_event.source_type=PLATFORM`. Zero-charge BYOK analytics events are a separate
  cohort and must not appear as false `managed_only` discrepancies.

- `RunCostTracker` is the live in-memory accumulator. Token totals and call counts are `long`; use
  `RunCostTracker.saturatedInt(...)` only at a documented legacy INTEGER entity/DTO boundary.
- `run_step_usage` is the canonical physical-provider-attempt ledger. V243 created success-call
  rows; V283 adds durable execution-context, tenant, feature, actual key-source, status/error,
  attempt, latency, and safe provider-usage metadata fields. V293 adds immutable write-ahead
  intents, correlation/model-tier dimensions, logical/physical/parent attempt causality and exact
  managed-credit linkage. Failed retries, failover targets and provider-internal HTTP attempts must
  each write their own intent and terminal row.
- `EffectiveRouteProjection` is the only sanctioned way to answer "which provider/model actually
  served this run". It reads successful `run_step_usage` attempts and returns `LEDGER` (exactly one
  route), `MIXED` (several — `provider`/`model` stay null by design) or `UNATTRIBUTED` (no
  successful attempt). It never falls back to the requested route. A caller that collapses `MIXED`
  to a single provider reintroduces exactly the untruth this projection exists to remove. Consumed
  by `BenchmarkTrackingService` (V304 attempt attribution) and `BenchmarkGenerationService`
  (`GenerateResult.effectiveProvider`/`effectiveModel`/`routeAttribution`/`routeDiverged`).
- `RunEffectiveRouteService` is the single read seam over that projection: `forRun(runId)`,
  `forTask(runId, taskId)`, planner-phase `forPlanner(runId)` (outline + expansion + coherence;
  classifier excluded), reviewer-pass-only
  `forReviewer(runId)`, and `latestSuccessfulForTask(runId, taskId)` for the physical route that
  produced a continuation decision's terminal observation. Consumed by `RunTokenAttributor`,
  continuation policy, and `api/RunMapper`. Do not re-derive an effective route anywhere else.
- **Which layer carries which route (C1, 2026-07-19).** REQUESTED / tier-resolved:
  `TaskOutcome.dispatchedProvider`/`dispatchedModel`, `agent_task.provider`/`model`,
  `pipeline_run.requested_provider`/`requested_model`/`worker_model`, `RunDetail.requestedProvider`/
  `requestedModel`, and `model_task_metric.provider`/`model_name`. EFFECTIVE / physically served:
  `run_step_usage.provider`/`model` (canonical), `agent_task.effective_provider`/`effective_model`/
  `effective_route_attribution` (V305), `benchmark_task_result.provider`/`model`/`route_attribution`
  (V304), `GenerateResult.effective*`, and the derived `RunDetail.effectiveProvider`/
  `effectiveModel`/`effectiveRouteAttribution`/`routeDiverged`. `model_task_metric` is the one
  remaining consumer still keyed on the requested route — see `router/CLAUDE.md`.
- `RunTokenAttributor` writes the V305 effective columns from the ledger and falls back to the
  requested route ONLY when the task has no successful attempt, in which case the attribution stays
  `UNATTRIBUTED` so an unproven fallback is never mistaken for ledger proof.
- Planner and reviewer phase attribution also uses the canonical ledger projection. A single
  physical route is persisted as the actual phase provider/model; a multi-route phase leaves those
  singular fields null rather than reporting the originally requested route as though it served
  every call. Legacy/no-ledger runs retain the requested-route fallback.
- Terminal run publication reconciles `pipeline_run.total_input_tokens`, `total_output_tokens`, and
  `actual_cost` upward from successful canonical `run_step_usage` rows. This is a raise-only floor:
  it repairs physical attempts persisted before an interrupted executor reached its normal
  in-memory attribution boundary, without replacing larger phase/task totals or double-counting
  replayed reconciliation.
- The run cost read model also raises terminal totals to the successful `run_step_usage` ledger.
  This covers post-terminal consumers such as Screens and Ask, whose calls occur after the pipeline
  snapshot was frozen. Null legacy cache/reasoning fields mean “not projected”, not numeric zero;
  aggregation must tolerate them and return the ledger totals rather than failing or under-reporting.
- Planning phase costs in that read model are also raise-only against known successful
  `planner.classify*`, `planner.outline*`, `planner.expand*`, and `planner.coherence*` step rows.
  A fail-closed hierarchical plan can terminate before its legacy phase snapshot is saved; the
  durable attempt ledger must still show where the planning spend occurred instead of reporting
  expansion/coherence as zero.
- `PlannerPhaseSplit` / `PlannerPhaseAttribution` own how pre-executor spend is booked (RR-1561).
  `RunOrchestrator` used to hand `attributePhase(PLANNER, …)` the WHOLE-RUN snapshot, so the smart
  classifier — which runs on every run, before the planner — was always booked to
  `planner_*_tokens`. The planner is now a delta from the snapshot taken when the classifier
  returns. The pre-planner remainder is NOT discarded: `attributePhase` also moves the run total by
  whatever it is handed, so the remainder is added back through `addTokens` and
  `plannerIn + prePlannerIn` still equals the whole-run input count.
- `RunCostEvidenceService.toCall` exposes `modelTier`, `attemptKind`, `physicalAttemptId`,
  `parentAttemptId` and `logicalCallId` alongside provider/model, so a consumer of
  `/api/runs/{id}/cost` can distinguish a same-provider retry from a cross-provider failover instead
  of inferring it.
- **Per-attempt `stepCalls[]` is a privileged surface (RR-1552).** It exposes which physical provider
  served each call, so it is emitted only to ORG_ADMIN/SUPER_ADMIN. For every other caller the array
  is withheld and the response carries `stepCallsIncluded=false`,
  `stepCallsRestrictedReason=ORG_ADMIN_OR_SUPER_ADMIN_REQUIRED` and `stepCallsTotal`. Those three
  fields exist specifically so a client can render "withheld" rather than "no calls" — omitting them
  and returning an empty array is the defect this replaced. Any new consumer MUST branch on
  `stepCallsIncluded` before interpreting an empty `stepCalls[]`.
- `provider_usage_raw_json` is a historical column name, not permission to store provider payloads.
  `RunStepUsageRecorder` may store only allow-listed numeric usage/cost metadata. Never store
  prompts, completions, tool arguments/results, request bodies, headers, credentials, or arbitrary
  provider JSON.
- Durable context attribution is exact and tenant-scoped: callers provide both
  `LlmRequest.llmExecutionContextId` and `llmExecutionScope`. Never infer a V282 context by
  `source_id`, `correlation_id`, or a synthetic run id. Transitional fallback may resolve only a
  real `pipeline_run.run_id` or `agent_session.id` primary key.
- `agent_usage_ledger` is a compatibility roll-up for agent turns, not the canonical provider-call
  ledger. Enterprise audit and FinOps queries start from `llm_usage_attempt_intent` and left-join
  the terminal fact so process loss remains visible.
- Intent persistence fails closed before provider transport. V298 stages the exact bounded,
  secret-free terminal fact in `llm_usage_terminal_outbox` in an independent transaction before
  canonical materialization. SHA-256 identity, a unique physical-attempt key, fenced leases and
  `SKIP LOCKED` claims let any JVM replay it exactly once without retrying a successful provider
  response. `LlmUsageRecoverySpool` is only the last resort when PostgreSQL cannot accept even the
  outbox stage; that directory must be shared/persistent for cross-node recovery during a total
  database outage, while the immutable intent remains the durable missing-terminal signal.
  Recovery artifacts use owner-only directory/file modes on POSIX hosts, malformed local payloads
  move to a `.dead` artifact, and the database outbox dead-letters after the bounded
  `executespec.usage-terminal-outbox.max-attempts` ceiling (default 8). Monitor health,
  last-success, local oldest-age and dead gauges distinguish fresh zeroes from a failed integrity
  sweep. Recovery logs expose only attempt id, opaque artifact basename and stable exception class;
  absolute spool paths, exception messages and stacks are forbidden and covered by captured canary
  tests.

## Enterprise audit surface

`GET /api/admin/analytics/llm-usage-audit?orgId=&from=YYYY-MM-DD&to=YYYY-MM-DD&timezone=IANA&page=0&size=100`
is own-org ORG_ADMIN or SUPER_ADMIN only. BILLING_ADMIN is deliberately excluded from physical
attempt details; the aggregate billing/cost-breakdown endpoints remain available to that role.
Dates are inclusive tenant-calendar dates converted to a half-open instant interval. The range is
capped at 366 days, page size at 500 and combined roll-ups at 500 rows. Multi-query JSON assembly
runs in Spring's read-only default transaction isolation. The previous per-request REPEATABLE READ
override exhausted the local PostgreSQL transaction contract on authenticated JSON/CSV audit reads;
the database/environment default now owns snapshot semantics (Phase-6 F14, fixed 2026-07-18).

The schema-v4 JSON report includes a paginated intent-first attempt list and a combined user x human
org/global role x provider x model x agent role x feature x project x run/session x key-source x
status roll-up. V301 snapshots the most-scoped project/org/global human role on the immutable
attempt intent before provider dispatch, so historical exports do not change after membership edits. Legacy attempts are
backfilled once at upgrade time. **V337 (RR-430)** adds
`llm_usage_attempt_intent.role_provenance` (nullable) recording HOW that role was obtained —
`CAPTURED_AT_DISPATCH` for every intent `RunStepUsageRecorder` writes going forward, and NULL legacy
rows read as `UNKNOWN` (surfaced as `user_role_provenance` in the report/CSV) so a
migration-reconstructed role is never presented with the authority of a true dispatch-time snapshot.
Pinned by `RunStepUsageRecorderTest`. Run and agent-session ids are
resolved only by exact tenant-scoped primary-key joins; no source-id guessing is allowed. A missing
terminal is returned as `MISSING_TERMINAL`/`PENDING_TERMINAL`, never silently omitted.

`GET /api/admin/analytics/llm-usage-audit.csv` accepts the same org/date/timezone filters and emits
at most 10,000 detail rows. It includes a UTF-8 BOM, RFC-4180 quoting, spreadsheet-formula
neutralization and explicit `X-Export-*` limit/truncation headers.

Failed attempts without numeric provider-reported/upstream/accounted cost metadata expose
`knownCostUsd=null`, `costStatus=UNKNOWN_PROVIDER_NOT_REPORTED`, and increment
`unknownCostAttempts`. Aggregate spend is named `knownCostUsdLowerBound`; never reinterpret a
provider-unreported failure as a proven zero-cost call.

The same rule applies to a successful response whose post-success price projection fails.
`LlmRouter` persists `costUsd=-1` as an explicit unknown sentinel; repository aggregates exclude
negative cost from `knownCostUsdLowerBound`, the cost API exposes `knownCostUsd=null`, and a durable
execution context with a dollar ceiling fails closed before authorizing another provider call.
Only unknown-cost SUCCESS rows close that dollar gate: failed transport attempts remain eligible
for the router's bounded retry. The run cost API exposes `physicalAttempts`, `successfulCalls`, and
`failedAttempts` separately because the backward-compatible `calls` field counts successful
responses, not all billable physical dispatch attempts.

Reconciliation uses `max($0.000001, 0.5% of canonical known cost)` and exact tokens when both sides
carry token facts. `pipeline_run.actual_cost_usd` is cost-only, so that comparison does not claim
token reconciliation. The managed-AI cohort is anchored to canonical physical attempts in the
requested window and joins each exact V293 `usage_event_id` projection regardless of a straddling
projection timestamp. Genuine managed-event orphans created inside the window remain
`comparisonOnly`; a counterpart outside the window is not falsely reported missing. Missing
projections remain incomplete/not-managed rather than being silently discarded.
Provider-invoice reconciliation remains `NOT_AVAILABLE_NO_PROVIDER_INVOICE_OR_REQUEST_IDENTIFIER`: V293 has no
provider invoice/export ingestion or durable upstream request/invoice id.

`RunStepUsageRecorder` emits `executespec.v2.llm.usage.persistence{result="failed"}` when the
canonical write fails. V298 also exposes pending/dead recovery gauges and critical alerts; the
database outbox scheduler can claim an expired lease and recover the exact fact on another JVM.
Prometheus loads `observability/alerts/executespec-enterprise-alerts.yml`. Alertmanager/pager
delivery, deployed multi-JVM process-kill/DB-denial qualification, a shared fallback directory for
total PostgreSQL outage, and ledger retention/partitioning remain release work.

## Historical cost estimation is tenant-scoped (RR-1551)

`CostHistoryService.estimateFromHistory` sets `pipeline_run.credits_charged` and tightens
`pipeline_run.hard_cap_usd` through `integration/RunCostEstimatePersister`, so the estimate is
derived exclusively from the submitting run's OWN org history. A validated voluntary
`user_cost_cap_usd` is already copied into `hard_cap_usd` on the initial run row before any
classifier or planner call; the estimate may only preserve or tighten that ceiling. This keeps the
customer cap enforceable and visible through `RunDetail.userCostCapUsd` even when planning fails
before an estimate exists. Both `RunCostHistoryRepository` queries
(`findSimilarCosts`, `findSimilarCostsLoose`) take a required `orgId` predicate; the loose fallback
relaxes shape only, never the tenant boundary. A run with no `orgId` skips history entirely and
takes the model-aware Stage-1 formula — a global pool is never a fallback. Before this fix the
P25/P75 band was computed across every organisation's observed `actual_cost_usd`, which both
mispriced runs across tenants and made foreign orgs' cost distribution reconstructable by varying
`planStrategy`/`provider`. Deferred: `idx_run_cost_history_similarity` still leads on
`plan_strategy`, so the org-scoped query does not have a leading-column index — a future migration
should add `(org_id, plan_strategy, provider, repo_count, task_count, completed_at DESC)`. This is
a performance concern only; correctness does not depend on it.
