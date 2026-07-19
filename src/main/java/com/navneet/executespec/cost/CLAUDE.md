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
- `RunEffectiveRouteService` is the single read seam over that projection: `forRun(runId)` and
  `forTask(runId, taskId)`. Consumed by `RunTokenAttributor` (per-task V305 columns) and
  `api/RunMapper` (derived `RunDetail` fields). Do not re-derive an effective route anywhere else.
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
- `RunCostEvidenceService.toCall` exposes `modelTier`, `attemptKind`, `physicalAttemptId`,
  `parentAttemptId` and `logicalCallId` alongside provider/model, so a consumer of
  `/api/runs/{id}/cost` can distinguish a same-provider retry from a cross-provider failover instead
  of inferring it.
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
backfilled once at upgrade time. Run and agent-session ids are
resolved only by exact tenant-scoped primary-key joins; no source-id guessing is allowed. A missing
terminal is returned as `MISSING_TERMINAL`/`PENDING_TERMINAL`, never silently omitted.

`GET /api/admin/analytics/llm-usage-audit.csv` accepts the same org/date/timezone filters and emits
at most 10,000 detail rows. It includes a UTF-8 BOM, RFC-4180 quoting, spreadsheet-formula
neutralization and explicit `X-Export-*` limit/truncation headers.

Failed attempts without numeric provider-reported/upstream/accounted cost metadata expose
`knownCostUsd=null`, `costStatus=UNKNOWN_PROVIDER_NOT_REPORTED`, and increment
`unknownCostAttempts`. Aggregate spend is named `knownCostUsdLowerBound`; never reinterpret a
provider-unreported failure as a proven zero-cost call.

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
