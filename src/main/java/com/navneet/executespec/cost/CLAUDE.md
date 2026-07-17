# Cost and LLM usage attribution

## Canonical contracts

- `RunCostTracker` is the live in-memory accumulator. Token totals and call counts are `long`; use
  `RunCostTracker.saturatedInt(...)` only at a documented legacy INTEGER entity/DTO boundary.
- `run_step_usage` is the canonical physical-provider-attempt ledger. V243 created success-call
  rows; V283 adds durable execution-context, tenant, feature, actual key-source, status/error,
  attempt, latency, and safe provider-usage metadata fields. Failed retries and failover targets
  must each write their own row.
- `provider_usage_raw_json` is a historical column name, not permission to store provider payloads.
  `RunStepUsageRecorder` may store only allow-listed numeric usage/cost metadata. Never store
  prompts, completions, tool arguments/results, request bodies, headers, credentials, or arbitrary
  provider JSON.
- Durable context attribution is exact and tenant-scoped: callers provide both
  `LlmRequest.llmExecutionContextId` and `llmExecutionScope`. Never infer a V282 context by
  `source_id`, `correlation_id`, or a synthetic run id. Transitional fallback may resolve only a
  real `pipeline_run.run_id` or `agent_session.id` primary key.
- `agent_usage_ledger` is a compatibility roll-up for agent turns, not the canonical provider-call
  ledger. Enterprise audit and FinOps queries read `run_step_usage`.
- A failed attempt-ledger write does not replace the provider result after spend occurred, but it
  must emit WARN logging and `executespec.v2.llm.usage.persistence{result="failed"}` for alerting.

## Enterprise audit surface

`GET /api/admin/analytics/llm-usage-audit?orgId=&from=YYYY-MM-DD&to=YYYY-MM-DD&timezone=IANA&page=0&size=100`
is own-org ORG_ADMIN or SUPER_ADMIN only. BILLING_ADMIN is deliberately excluded from physical
attempt details; the aggregate billing/cost-breakdown endpoints remain available to that role.
Dates are inclusive tenant-calendar dates converted to a half-open instant interval. The range is
capped at 366 days, page size at 500 and combined roll-ups at 500 rows. Multi-query JSON assembly
runs in a read-only PostgreSQL REPEATABLE READ transaction so totals, pagination and reconciliation
refer to one database snapshot.

The JSON report includes a paginated attempt list and a combined user x provider x model x agent
role x feature x project x run/session x key-source x status roll-up. Run and agent-session ids are
resolved only by exact tenant-scoped primary-key joins; no source-id guessing is allowed.

`GET /api/admin/analytics/llm-usage-audit.csv` accepts the same org/date/timezone filters and emits
at most 10,000 detail rows. It includes a UTF-8 BOM, RFC-4180 quoting, spreadsheet-formula
neutralization and explicit `X-Export-*` limit/truncation headers.

Failed attempts without numeric provider-reported/upstream/accounted cost metadata expose
`knownCostUsd=null`, `costStatus=UNKNOWN_PROVIDER_NOT_REPORTED`, and increment
`unknownCostAttempts`. Aggregate spend is named `knownCostUsdLowerBound`; never reinterpret a
provider-unreported failure as a proven zero-cost call.

Reconciliation uses `max($0.000001, 0.5% of canonical known cost)` and exact tokens when both sides
carry token facts. `pipeline_run.actual_cost_usd` is cost-only, so that comparison does not claim
token reconciliation. Managed-AI matching uses exact run/provider/model/role/step groups and marks
missing groups as incomplete/not-managed rather than silently discarding them. Provider-invoice
reconciliation remains `NOT_AVAILABLE_NO_PROVIDER_INVOICE_OR_REQUEST_IDENTIFIER`: V283 has no
provider invoice/export ingestion or durable upstream request/invoice id.

`RunStepUsageRecorder` emits `executespec.v2.llm.usage.persistence{result="failed"}` when the
canonical write fails. Prometheus loads `observability/alerts/executespec-enterprise-alerts.yml` and
raises a labeled critical rule on any such failure. Alertmanager/pager delivery must still be
qualified per environment. This is visibility, not recovery: durable outbox/replay and ledger
retention/partitioning remain release work.
