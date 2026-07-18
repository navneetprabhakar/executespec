# Cost and LLM usage attribution

## Canonical contracts

- `RunCostTracker` is the live in-memory accumulator. Token totals and call counts are `long`; use
  `RunCostTracker.saturatedInt(...)` only at a documented legacy INTEGER entity/DTO boundary.
- `run_step_usage` is the canonical physical-provider-attempt ledger. V243 created success-call
  rows; V283 adds durable execution-context, tenant, feature, actual key-source, status/error,
  attempt, latency, and safe provider-usage metadata fields. V293 adds immutable write-ahead
  intents, correlation/model-tier dimensions, logical/physical/parent attempt causality and exact
  managed-credit linkage. Failed retries, failover targets and provider-internal HTTP attempts must
  each write their own intent and terminal row.
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

## Enterprise audit surface

`GET /api/admin/analytics/llm-usage-audit?orgId=&from=YYYY-MM-DD&to=YYYY-MM-DD&timezone=IANA&page=0&size=100`
is own-org ORG_ADMIN or SUPER_ADMIN only. BILLING_ADMIN is deliberately excluded from physical
attempt details; the aggregate billing/cost-breakdown endpoints remain available to that role.
Dates are inclusive tenant-calendar dates converted to a half-open instant interval. The range is
capped at 366 days, page size at 500 and combined roll-ups at 500 rows. Multi-query JSON assembly
runs in Spring's read-only default transaction isolation. The previous per-request REPEATABLE READ
override exhausted the local PostgreSQL transaction contract on authenticated JSON/CSV audit reads;
the database/environment default now owns snapshot semantics (Phase-6 F14, fixed 2026-07-18).

The JSON report includes a paginated intent-first attempt list and a combined user x provider x model x agent
role x feature x project x run/session x key-source x status roll-up. Run and agent-session ids are
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
token reconciliation. Managed-AI matching uses the exact V293 `usage_event_id` foreign key and
marks missing projections as incomplete/not-managed rather than silently discarding them.
Provider-invoice reconciliation remains `NOT_AVAILABLE_NO_PROVIDER_INVOICE_OR_REQUEST_IDENTIFIER`: V293 has no
provider invoice/export ingestion or durable upstream request/invoice id.

`RunStepUsageRecorder` emits `executespec.v2.llm.usage.persistence{result="failed"}` when the
canonical write fails. V298 also exposes pending/dead recovery gauges and critical alerts; the
database outbox scheduler can claim an expired lease and recover the exact fact on another JVM.
Prometheus loads `observability/alerts/executespec-enterprise-alerts.yml`. Alertmanager/pager
delivery, deployed multi-JVM process-kill/DB-denial qualification, a shared fallback directory for
total PostgreSQL outage, and ledger retention/partitioning remain release work.
