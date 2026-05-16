# Service Level Objectives

Every SLO listed here is wired to a metric registered in code. Metrics that
exist but are not yet attached to an SLO are listed in section 4 as
"unobjectived inventory" so they can be promoted when targets are agreed.

Scrape path: `GET /actuator/prometheus`, scraped every 15s by
`observability/prometheus.yml`. Spring Boot exposes the four-endpoint allowlist
`health, info, metrics, prometheus` (see `application.yml`):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  tracing:
    sampling:
      probability: 1.0
  observations:
    annotations:
      enabled: true
  metrics:
    distribution:
      percentiles-histogram:
        http.server.request.duration: true
        spring.ai.chat.client.duration: true
    tags:
      application: executespec
      environment: local      # prod overrides to "prod", dev to "dev"
```

The `percentiles-histogram` flags above are what let Prometheus compute the
`p50/p95/p99` latency panels in
`observability/grafana/dashboards/jvm-http-overview.json` and
`executespec-overview.json` — without them, the Spring Boot Counter/Timer
wouldn't emit a `_bucket` series for `histogram_quantile()`.

---

## 1. SLO inventory

| # | SLO statement | Target | Backing metric(s) | Window |
| - | --- | --- | --- | --- |
| 1 | A submitted run reaches a terminal status (`COMPLETE`, `PARTIAL`, `FAILED`, `CANCELLED`) within 15 minutes for at least 95% of runs. | >= 95% | `executespec.v2.run.latency{overall}` (Timer, histogram) | rolling 7 days |
| 2 | The reported `verifiedScore` matches the underlying signals — multiplicative `baseAccuracy x buildOK x testRatio x reviewerMultiplier - criticalPenalty` is computed and persisted; no NULL `verifiedScore` rows for terminal runs. | >= 90% non-null | `executespec.v2.run.outcome{overall=COMPLETE|PARTIAL}` x DB query `SELECT count(*) FROM pipeline_run WHERE status IN (...) AND verified_score IS NULL` | rolling 30 days |
| 3 | Cost estimate accuracy: 80% of completed runs land within 30% of the planner-estimated buffered cost (`actualCostUsd` vs `costEstimateHigh`). | >= 80% | `executespec.v2.planner.latency{classification,provider,model}` for the estimate side; DB column `pipeline_run.actual_cost_usd` for the actual. Composed at report time, no live metric. | rolling 30 days |
| 4 | Webhook ingestion (`POST /webhooks/github`, `POST /api/webhooks/{stripe,razorpay}`) completes server-side processing within 60s. | >= 99% | `http.server.requests{uri=~"/webhooks/.*"}` Timer (Spring AutoConfig) | rolling 7 days |
| 5 | Hot-path 5xx rate on customer-facing API (`/api/runs/**`, `/api/projects/**`, `/api/auth/**`) stays below 0.5%. | < 0.5% | `http.server.requests{status=~"5..", uri=~"/api/(runs|projects|auth).*"}` / total | rolling 1 day |

These five are the published gates. Sections 2-4 expand on each, then list the
metrics that exist in code but aren't yet attached to a target.

---

## 2. SLO details

### SLO 1 — Run terminal latency

The `RunOrchestrator` records run start/end on the `Metrics` facade
(`src/main/java/com/navneet/executespec/observability/Metrics.java`):

```java
void recordRunLatency(String overall, Duration elapsed);  // executespec.v2.run.latency{overall=...}
void recordRunOutcome(String overall);                    // executespec.v2.run.outcome{overall=...}
```

With `management.metrics.distribution.percentiles-histogram` enabled for the
HTTP path and the Micrometer Timer DSL, we get a `_bucket` series suitable for
`histogram_quantile(0.95, sum by (le) (rate(executespec_v2_run_latency_seconds_bucket[5m])))`.

Caveat: the Grafana panel "Run Duration (p50/p95/p99)" in
`executespec-overview.json` actually queries
`executespec_run_duration_seconds_bucket`, not `executespec_v2_run_latency_*`.
The dashboard pre-dates the V2 rename. Either the panel needs a query update
or the V2 Timer needs a no-namespace alias before this SLO can be charted from
the existing dashboard.

### SLO 2 — Verified-score correctness

The score is computed in `scoring/` and persisted on the `validation_report`
table. `Metrics.recordReviewerVerdict(verdict)` fires per-run so we can rate
the verdict distribution, but there is no Timer measuring the score's
correctness directly — correctness has to be measured against the spec via the
strict eval harness (`scripts/v2-eval-strict.sh`) and the resulting JSON,
which carries both `accuracyScore` (legacy LLM-judged) and `verifiedScore`
(the multiplicative product).

Operational signal: a count of terminal runs with `verified_score IS NULL`
indicates a scoring pipeline regression. There is no Prometheus query for this
today — it's a DB-only signal.

### SLO 3 — Cost estimation accuracy

The planner-side estimate is recorded as Timer + token-count metrics:

```java
recordPlannerLatency(classification, provider, model, elapsed)
recordPlannerTaskCount(classification, tasks)
```

The actual cost lives on `pipeline_run.actual_cost_usd`, populated by
`RunCostTracker` (`cost/RunCostTracker.java`). The estimate band lives on
`cost_estimate_low` / `cost_estimate_high`. The 30%-of-estimate-high
comparison is done at report-rendering time, not at scrape time — so this SLO
is observed via offline rollups (the V2 eval scripts) rather than continuous
Prometheus alerting.

### SLO 4 — Webhook latency

Spring Boot's autoconfigured `http.server.requests` Timer covers every HTTP
endpoint (because the Actuator + Micrometer autoconfig wires it in). With the
`uri` label intact, we can extract:

```
histogram_quantile(0.99,
  sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri=~"/webhooks/.*"}[5m]))
)
```

The actual handlers (`GithubWebhookController`, `StripeWebhookController`,
`RazorpayWebhookController`) all sign-verify in-line and persist to
`webhook_event_log`. None of them spin off long async work on the request
thread — the latency budget is fundamentally bounded by signature verification
+ a single DB insert.

### SLO 5 — Hot-path 5xx rate

`http.server.requests` carries the `status` label. The `ValidationAdvice`
(`src/main/java/com/navneet/executespec/api/advice/ValidationAdvice.java`) and
`GlobalExceptionAdvice` translate the common 4xx/5xx classes to canonical
envelopes, so a real 500 only fires when an unhandled `Throwable` reaches
`GlobalExceptionAdvice.onAny(Throwable)` and stamps an `errorId` UUID. That
gives us a clean way to scope this SLO to "unhandled" 5xx — count 500s with
the `error.handler` tag set to `GlobalExceptionAdvice` (the
`management.observations.annotations.enabled: true` flag picks up the
`@Observed` annotation on the handler).

---

## 3. Custom metric inventory

All V2 pipeline metrics are registered via the facade
`observability/Metrics.java`. The namespace constant is `NS = "executespec.v2."`,
so each method below maps to a Micrometer metric name with that prefix and the
tags listed.

| Java method | Prometheus name | Tags | Type |
| --- | --- | --- | --- |
| `recordRunOutcome(overall)` | `executespec.v2.run.outcome` | `overall` (COMPLETE / PARTIAL / FAILED / CANCELLED) | Counter |
| `recordRunLatency(overall, elapsed)` | `executespec.v2.run.latency` | `overall` | Timer (histogram) |
| `recordPlannerLatency(classification, provider, model, elapsed)` | `executespec.v2.planner.latency` | `classification, provider, model` | Timer |
| `recordPlannerTaskCount(classification, tasks)` | `executespec.v2.planner.tasks` | `classification` | DistributionSummary |
| `recordTaskStatus(status)` | `executespec.v2.task.status` | `status` | Counter |
| `recordTaskLatency(status, elapsed)` | `executespec.v2.task.latency` | `status` | Timer |
| `recordSmokeTest(result)` | `executespec.v2.task.smoketest` | `result` (PASSED / FAILED / TIMEOUT / SKIPPED) | Counter |
| `recordWorkerIterations(outcome, provider, model, iterations)` | `executespec.v2.worker.iterations` | `outcome, provider, model` | DistributionSummary |
| `recordWorkerOutcome(outcome, provider, model)` | `executespec.v2.worker.outcome` | `outcome, provider, model` | Counter |
| `recordToolCall(tool)` | `executespec.v2.worker.toolcall` | `tool` (readFile / writeFile / editFile / shell / buildProject / runTests / completeTask / ...) | Counter |
| `recordLlmLatency(provider, model, outcome, elapsed)` | `executespec.v2.llm.latency` | `provider, model, outcome` | Timer |
| `recordKeySource(provider, source)` | `executespec.v2.llm.key_source` | `provider, source` (SYSTEM / OWN_KEYS / HYBRID) | Counter |
| `recordClassifierVerifier(result)` | `executespec.v2.classifier.verifier.engaged` | `result` | Counter |
| `recordReviewerVerdict(verdict)` | `executespec.v2.reviewer.verdict` | `verdict` (CLEAN / FIXED_ISSUES / NEEDS_WORK / FAILED) | Counter |
| `recordContextSave(result, bytes)` | `executespec.v2.context.save` + `executespec.v2.context.save.bytes` | `result` | Counter + DistributionSummary |

Other custom metric emitters not on the `Metrics` facade:

| Source | Metric | Use |
| --- | --- | --- |
| `compliance/WorkspaceDiskCleanupJob.java` | `executespec.workspace.disk.reaped{category}` | Counter — orphan disk sweep |
| `compliance/StaleScmCleanupJob.java` | `executespec.scm.cleanup.{closed|deleted|skipped}{kind}` | Counter — stale PR/branch sweep |
| `compliance/DataRetentionService.java` | `executespec.data.retention.deleted_runs{trigger}` | Counter — GDPR + scheduled retention sweeps |
| `compliance/DataRetentionService.java` | `executespec.data.retention.deleted_projects{trigger}` | Counter |
| `ratelimit/RateLimitFilter.java` | `executespec.redis.failopen` | Counter — Redis dropped, fail-open path engaged |
| `sse/SseBroadcaster.java` | `executespec.sse.emitters.active` | Gauge — currently-attached SSE emitters |

Standard auto-instrumented metrics worth knowing (all under
`spring.boot.actuator` / Micrometer defaults):

| Metric | Source |
| --- | --- |
| `http.server.requests` | Spring MVC autoconfig (Timer w/ histogram, tags `method, status, uri, exception, outcome`) |
| `jvm.memory.used`, `jvm.memory.max` | JVM binder |
| `jvm.gc.pause` | JVM binder |
| `jvm.threads.{live,daemon,peak}` | JVM binder |
| `hikaricp.connections.{active,idle,pending}`, `hikaricp.connections` | HikariCP binder |
| `lettuce.command.completion` | Lettuce binder for Redis command latency |
| `spring.ai.chat.client.duration` | Spring AI autoconfig (per-provider chat call duration, histogram-enabled in `application.yml`) |

The Grafana JVM panel queries `jvm_memory_used_bytes{area="heap"}`,
`hikaricp_connections_*`, `lettuce_command_completion_seconds_*` — all
standard names produced by the autoconfig binders, no custom wiring required.

---

## 4. Error-budget gaps (honest list)

These are SLO-shaped statements we could publish but for which the metric or
the alerting integration isn't fully wired. Listed so they don't go pretending
they're enforced.

| Gap | What we'd need | Why it's not done |
| --- | --- | --- |
| Verified-score correctness alerting | Counter `executespec.scoring.score_null_terminal_total` incremented from `scoring/` when `verifiedScore` lands NULL on a terminal run | The scoring path doesn't increment a metric; correctness is checked offline via `scripts/v2-eval-strict.sh` |
| Cost-estimate band hit rate | Histogram `executespec.cost.estimate_error_pct` = `(actual - estimate_high) / estimate_high` | Calculated only on report render; not pushed into Micrometer |
| Stuck-run alerting | Counter `executespec.monitor.stuck_detected` exists; the Grafana panel "Stuck Runs Detected" is wired to `executespec_monitor_stuck_detected_total`. Alert rule not configured. | Prometheus has no `alerting.rules` block; Grafana alerting is set up but no destinations configured (Slack / PagerDuty integrations are present in code but require per-env credentials). |
| Provider failover rate | Counter `executespec.v2.llm.fallback` would let us track how often the YAML `failover-order` kicks in | `LlmRouter` does not increment a fallback-event counter today — the failover happens silently |
| Preview / deploy failure rate | `executespec_preview_failures_total`, `executespec_deploy_failures_total` panels exist in the Grafana dashboard; the metric name produced by code is `executespec.preview.failures` (and `executespec.deploy.failures`) but I could not find the Micrometer registration sites in the bucket research, only the dashboard query | Worth a separate audit of `deployment/preview/*` and `deployment/engine/*` for the Counter registration |
| Container/Cloud Run health-check budget | `UserCloudRunDeployer.healthCheck` retries 5x with `{5,10,15,20,30}s` delays | The result is captured per-deployment in the `Deployment` entity but isn't surfaced as a Counter (`deploy.health_check_outcome`) for fleet-level alerting |
| Rate-limit fail-open severity | `executespec.redis.failopen` increments but no SLO target | We treat it as a leading indicator: Redis being down for >60s is a P1 ops event, but the alert threshold isn't set |

---

## 5. Where to put alert rules

Prometheus alert rules live under `observability/prometheus.yml`, which today
only carries `scrape_configs`. To attach an alert to SLO 1 (run terminal
latency p95 > 15min), the file would gain:

```yaml
rule_files:
  - rules.d/*.yml

# observability/rules.d/run-latency.yml
groups:
  - name: run-latency
    rules:
      - alert: V2RunP95LatencyHigh
        expr: histogram_quantile(0.95,
                sum by (le) (rate(executespec_v2_run_latency_seconds_bucket{overall=~"COMPLETE|PARTIAL"}[10m]))
              ) > 900
        for: 10m
        labels:
          severity: page
        annotations:
          summary: V2 run latency p95 above 15min for 10min
```

(No rule files are checked in today — alerting is owned by ops infrastructure
not represented in this repo.)
