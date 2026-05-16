# Service Level Objectives

This file is code-truth: every SLO listed below is backed by a metric registered in the code and visible on the Grafana overview dashboard. SLOs that don't have a code-backed metric are intentionally omitted rather than aspirational-but-unmeasurable.

The metric namespace is `executespec.*` for the cross-cutting cohort (`config/CustomMetricsConfig.java`) and `executespec.v2.*` for V2 pipeline-specific metrics (`observability/Metrics.java`).

## Where the data comes from

| Layer                          | Source                                                                                    |
| ------------------------------ | ----------------------------------------------------------------------------------------- |
| Custom application metrics     | `Counter.builder` / `Timer.builder` / `Gauge.builder` / `DistributionSummary.builder` sites |
| HTTP server timing             | Spring Boot auto-instrumented `http.server.request.duration`                              |
| LLM call timing                | Spring AI auto-instrumented `spring.ai.chat.client.duration`                              |
| JVM, HikariCP, Redis, threads  | Spring Boot Actuator + Micrometer auto-config                                              |
| Custom histogram buckets       | `application.yml management.metrics.distribution.percentiles-histogram` (both above are explicitly enabled) |

Exposure: `management.endpoints.web.exposure.include: health,info,metrics,prometheus` (`application.yml`). Only those four actuator endpoints are reachable; everything else is closed. Base path `/actuator`.

Local stack: Prometheus + Tempo + Grafana via `observability/docker-compose.yml`. Prometheus scrapes every 15s. Two dashboards ship: `executespec-overview.json` and `jvm-http-overview.json`.

Prod stack: GCP Cloud Trace + Cloud Monitoring via OpenTelemetry exporters (`exporter-trace` and `exporter-metrics` from `pom.xml`; activated by Spring profile `gcp`).

## Published SLOs

Each SLO below is backed by a code-registered metric. If you change the metric name, update this table in the same commit.

### SLO 1 — Pipeline run reaches a terminal state in 15 minutes

**Target.** ≥95% of pipeline runs end in a terminal `Run.Status` (`COMPLETED`, `PARTIAL`, `FAILED`, `CANCELLED`) within 15 minutes of submission, measured weekly.

**Metric.** `executespec.v2.run.latency` (timer, tagged `overall`). Registered in `observability/Metrics.java` via `recordRunLatency(String overall, Duration elapsed)`. Histogram buckets are enabled via the global `management.metrics.distribution.percentiles-histogram` setting + Micrometer's per-Timer histogram support.

**Counter.** `executespec.v2.run.outcome` (tagged `overall`) — denominator for the SLO. Counts terminal-state transitions.

**Code anchors.**

- `agent.concurrency.max-task-duration-minutes: 25` (`application.yml`) — single-task ceiling. Multi-task runs can exceed 15 min and still complete; the SLO is a percentile, not a hard cap.
- `agent.concurrency.safety-iteration-cap: 500` — absolute backstop in the worker loop.
- `RunOrchestrator.cancel(runId)` is invoked by `AdminReconciliationService` (`@Scheduled(fixedDelay = 15 * 60_000)`) for runs left non-terminal past the reconciliation window. Failing in that path counts toward the outcome counter with `overall=FAILED`.

**Prometheus query.**

```promql
histogram_quantile(0.95,
  rate(executespec_v2_run_latency_seconds_bucket[7d])
)
```

Target ≤ 900 seconds.

**Surfaced on.** `executespec-overview.json` panel `Run Duration (p50 / p95 / p99)` (legacy panel keyed off `executespec_run_duration_seconds_bucket`; for V2 runs query `executespec_v2_run_latency_seconds_bucket`).

### SLO 2 — Plan-gate wait time

**Target.** ≥95% of plan-gate waits complete (approval or timeout) within 30 minutes, measured weekly.

**Metric.** `executespec.gate.wait_time` (timer, tagged `gate`). Defined in `config/CustomMetricsConfig.java` as `GATE_WAIT_TIME`. Surfaced on the overview dashboard at panel `Gate Wait Times`.

**Prometheus query.**

```promql
histogram_quantile(0.95,
  rate(executespec_gate_wait_time_seconds_bucket[7d])
)
```

Target ≤ 1800 seconds.

**Code anchor.** Gate lifecycle is managed by `harness/GateStore.java`. Gate-loss fallback (`approve()` → if gate missing → auto-fail with message) is documented in the CLAUDE.md MUST-follow patterns.

### SLO 3 — Verified-score correctness on BUILD-mode runs

**Target.** No code-backed metric currently exists for `verifiedScore` correctness as an SLO. The `verifiedScore` value is persisted per run (`ValidationReportService`), surfaced in the UI, and pinned by `ReviewerVerdict` (counter `executespec.v2.reviewer.verdict`) — but there is no Prometheus-friendly accuracy gauge.

**Gap.** Auditing whether the multiplicative score model produces the right outcome per run requires offline analysis against the `validation_report.issues_json` table; it cannot be evaluated from metrics alone.

**Available proxy.** `executespec.v2.reviewer.verdict` (counter, tagged `verdict`) tracks CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED distribution. A regression in the CLEAN ratio is an early signal.

### SLO 4 — LLM call latency

**Target.** ≥95% of LLM calls return within their tier-appropriate ceiling, measured weekly. PLANNING tier: ≤180s. WORKHORSE tier: ≤120s. LIGHTWEIGHT tier: ≤45s.

**Metric.** `executespec.v2.llm.latency` (timer, tagged `provider`, `model`, `outcome`). `observability/Metrics.java` records this per dispatch. The histogram bucket is enabled via `management.metrics.distribution.percentiles-histogram.spring.ai.chat.client.duration: true` (`application.yml`), AND via the custom timer above which is independently histogram-bucketed.

**Code anchors.**

- Per-tier model resolution: `application.yml model-matrix.agent-tier-map`.
- Provider failover: `application.yml model-matrix.failover-order`.
- Retry backoff: `spring.ai.retry` — max 3 attempts, 2s → 4s → 8s backoff capped at 10s (`application.yml`).
- Companion counter: `executespec.llm.errors` (legacy) / `executespec.v2.llm.latency{outcome="ERROR"}` (V2).

**Prometheus query (PLANNING tier).**

```promql
histogram_quantile(0.95,
  rate(executespec_v2_llm_latency_seconds_bucket{model="claude-sonnet-4-6"}[7d])
)
```

Target ≤ 180 seconds.

### SLO 5 — Webhook ingestion latency

**Target.** ≥99% of webhook deliveries are persisted and a 2xx response is returned within 60 seconds, measured weekly.

**Metric (auto-instrumented).** `http.server.request.duration` (timer, Spring Boot Actuator default; histogram explicitly enabled in `application.yml` `management.metrics.distribution.percentiles-histogram.http.server.request.duration: true`, range 1ms to 30s).

**Prometheus query (GitHub webhook).**

```promql
histogram_quantile(0.99,
  rate(http_server_request_duration_seconds_bucket{uri="/webhooks/github",method="POST"}[7d])
)
```

Target ≤ 60 seconds.

**Code anchors.**

- HMAC SHA-256 verification of the GitHub webhook payload is done before any DB work; see `repo/` package.
- Razorpay HMAC + Stripe `Stripe-Signature` verification happens at the controller boundary.

### SLO 6 — Hot-path 5xx error rate

**Target.** ≥99.5% of API requests return < 500 status (i.e. 5xx rate < 0.5%), measured weekly.

**Metric.** `http.server.request.duration` count split by `status` tag (auto-instrumented). The metric is a histogram; counting any status ≥ 500 over the total request count gives the error rate.

**Prometheus query.**

```promql
1 -
(
  sum(rate(http_server_request_duration_seconds_count{status=~"5.."}[7d]))
  /
  sum(rate(http_server_request_duration_seconds_count[7d]))
)
```

Target ≥ 0.995.

## Operational metrics that don't drive an SLO (but are watched)

These metrics exist in the codebase and on the dashboards. They are early indicators rather than SLO denominators.

### Run lifecycle gauges

Defined in `config/CustomMetricsConfig.java`. Backed by `AtomicInteger` updated from service code.

| Metric                           | Type   | Updated by                                       | Why it matters                                         |
| -------------------------------- | ------ | ------------------------------------------------ | ------------------------------------------------------ |
| `executespec.run.active`         | gauge  | Pipeline submit / terminal hooks                  | Live count of in-flight runs                            |
| `executespec.run.queued`         | gauge  | Per-provider pool queue                          | Queue depth — sustained > 0 means provider saturation   |
| `executespec.preview.active`     | gauge  | Preview lifecycle                                 | Live preview sessions                                   |
| `executespec.deploy.active`      | gauge  | Deploy lifecycle                                  | In-progress Cloud Run deploys                           |

Dashboard: top-row stat panels on `executespec-overview.json` with thresholds (green / yellow / red).

### V2 pipeline counters

| Metric                                       | Type                  | Source                                                                              |
| -------------------------------------------- | --------------------- | ----------------------------------------------------------------------------------- |
| `executespec.v2.run.outcome`                 | counter               | `observability/Metrics.java#recordRunOutcome`                                       |
| `executespec.v2.task.status`                 | counter               | `observability/Metrics.java#recordTaskStatus`                                       |
| `executespec.v2.task.smoketest`              | counter               | `observability/Metrics.java#recordSmokeTest`                                        |
| `executespec.v2.worker.outcome`              | counter               | `observability/Metrics.java#recordWorkerOutcome`                                    |
| `executespec.v2.worker.iterations`           | distribution summary  | `observability/Metrics.java#recordWorkerIterations`                                 |
| `executespec.v2.worker.toolcall`             | counter               | `observability/Metrics.java#recordToolCall`                                         |
| `executespec.v2.reviewer.verdict`            | counter               | `observability/Metrics.java#recordReviewerVerdict`                                  |
| `executespec.v2.context.save`                | counter               | `observability/Metrics.java#recordContextSave`                                      |
| `executespec.v2.context.save.bytes`          | distribution summary  | `observability/Metrics.java#recordContextSave`                                      |
| `executespec.v2.classifier.verifier.engaged` | counter               | `observability/Metrics.java#recordClassifierVerifier`                               |
| `executespec.v2.llm.key_source`              | counter               | `observability/Metrics.java#recordKeySource` (V165 BYOK split: USER / ORG / PLATFORM) |
| `executespec.v2.planner.latency`             | timer                 | `observability/Metrics.java#recordPlannerLatency`                                   |
| `executespec.v2.planner.tasks`               | distribution summary  | `observability/Metrics.java#recordPlannerTaskCount`                                 |

### Legacy / shared counters

Registered in `config/CustomMetricsConfig.java` as name constants; recorded ad-hoc by various services.

| Metric                                       | What it tracks                                                                              |
| -------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `executespec.llm.calls`                      | Total LLM calls (legacy path)                                                                |
| `executespec.llm.tokens.input` / `.output`   | Input + output tokens                                                                          |
| `executespec.llm.tokens.input.dist` / `.dist` | Distribution summaries on tokens per call                                                     |
| `executespec.llm.errors`                     | LLM errors                                                                                     |
| `executespec.llm.fallback`                   | Provider fallbacks (legacy `FallbackLlmRouter`)                                                |
| `executespec.llm.duration`                   | Legacy LLM duration (V2 uses `executespec.v2.llm.latency`)                                     |
| `executespec.llm.retry_count`                | Retry counter                                                                                  |
| `executespec.deploy.total` / `.duration` / `.build_duration` / `.health_check_duration` / `.failures` | Deploy lifecycle (`deployment/DeploymentService.java`)                |
| `executespec.preview.starts` / `.duration` / `.build_duration` / `.deploy_duration` / `.failures` | Preview lifecycle                                                          |
| `executespec.tool.calls`                     | Tool dispatch (legacy)                                                                          |
| `executespec.validation.total`               | `validator/CodeValidatorImpl.java`                                                              |
| `executespec.gate.wait_time`                 | Plan-gate wait                                                                                  |
| `executespec.handover.duration`              | Agent-to-agent handover                                                                          |
| `executespec.monitor.stuck_detected`         | Stuck-run detector                                                                              |
| `executespec.provider.pool.active` / `.queued` / `.capacity` | Per-provider concurrency pool                                                      |
| `executespec.redis.connection_failures`      | Redis connectivity                                                                              |
| `executespec.gate.db_fallback`               | DB fallback when Redis-backed gate path fails                                                    |
| `executespec.redis.failopen`                 | Fail-open count when Redis is down                                                              |
| `executespec.scm.cleanup.skipped` / `.closed` / `.deleted` | `compliance/StaleScmCleanupJob.java`                                              |
| `executespec.workspace.disk.reaped`          | `compliance/WorkspaceDiskCleanupJob.java`                                                       |
| `executespec.sse.emitters.active` / `.rejected` | `sse/SseBroadcaster.java`                                                                    |
| `executespec.side_effect.completed` / `.failed` / `.retried` | `saga/SideEffectExecutor.java`                                                  |
| `executespec.async.uncaught.exception`       | `config/AsyncObservabilityConfig.java` — uncaught exceptions on async dispatches                |
| `executespec.security.guardrail.blocked`     | `security/guardrail/GuardrailLevelResolver.java`                                                |
| `executespec.prompt.optimisation.applied` / `.rolledback` | `optimiser/PromptAuditService.java`, `config/PromptVersioningService.java`        |
| `executespec.stack.refresh.changed` / `.unchanged` / `.failed` | `project/service/StackRefreshJob.java`                                       |

## Actuator endpoint summary

`application.yml management.endpoints.web.exposure.include` exposes exactly four:

| Path                         | Purpose                                                       |
| ---------------------------- | ------------------------------------------------------------- |
| `GET /actuator/health`       | Health check (used by the deploy gate)                        |
| `GET /actuator/info`         | Build / version info                                          |
| `GET /actuator/metrics`      | Per-meter metric introspection (`/actuator/metrics/{name}`)   |
| `GET /actuator/prometheus`   | Scrape endpoint for Prometheus (text/plain exposition)         |

`management.endpoint.health.show-details: when_authorized` — health detail is hidden from unauthenticated callers.

The custom `HealthController` at `/api/health` and `/api/health/ready` is separate from the actuator. It exists for the dashboard header status badge + MIG/LB health checks; it does NOT use the `HealthIndicator` SPI. There are no Spring `HealthIndicator` bean implementations in the codebase (verified by grepping the source tree for `implements HealthIndicator`).

## Error budget

There is no automated error-budget tracking in the codebase. Targets are documented here; consumption is computed manually via Prometheus queries against the metrics above.

A future error-budget pipeline would need:

1. A per-SLO target row (probably in a YAML file under `observability/`).
2. A scheduled job to compute consumption and publish a gauge.
3. A Grafana panel and an alert on > 80% consumption.

None of those exist today; flagging as a gap rather than an SLO.

## SLO change procedure

1. Add or modify a metric in `observability/Metrics.java` or `config/CustomMetricsConfig.java`.
2. Add the Prometheus query to this file.
3. Add or update a panel on `observability/grafana/dashboards/executespec-overview.json` (or `jvm-http-overview.json` for JVM-internal metrics).
4. Commit all four touch points in the same change.

## Tracing

`management.tracing.sampling.probability: 1.0` in the base `application.yml` and on the `gcp` profile. Prod sets `OTEL_SAMPLING_PROBABILITY` to `0.1` by default (`application-prod.yml`). Annotation-driven `@Observed` is enabled via `management.observations.annotations.enabled: true`.

OTLP traces:

- Local (no profile or default profile): emitted to `http://localhost:4318/v1/traces`. Tempo (in `observability/docker-compose.yml`) accepts on the same port.
- `local` profile: tracing disabled (no collector running).
- `dev` profile: `OtlpAutoConfiguration` excluded entirely (no dev-VM collector).
- `gcp` profile: shipped to GCP Cloud Trace via `GcpOtelExporterConfig`.

MDC propagation: `micrometer-tracing-bridge-otel` populates traceId / spanId into the SLF4J context. Logback JSON encoder is configured via `logstash-logback-encoder` (from `pom.xml`).
