# Status page

How service health is monitored, surfaced internally, and exposed to customers. Code-truth only — features that don't exist are flagged as gaps.

## Internal health monitoring

### `/actuator/health` (Spring Boot Actuator)

`application.yml` exposes the standard actuator health endpoint:

```
management.endpoints.web.exposure.include: health,info,metrics,prometheus
management.endpoints.web.base-path: /actuator
management.endpoint.health.show-details: when_authorized
```

Reachable at `https://app.executespec.ai/actuator/health` (prod) and `https://dev.executespec.ai/actuator/health` (dev). The deploy gate in `.github/workflows/deploy.yml` (step `Health check`, lines 330-352) is the primary internal consumer:

- Probes up to 8 times with 10s spacing.
- Success = HTTP 200 AND response body contains `"status":"(UP|DEGRADED)"`.
- Deletes the temp file before every probe to avoid the buffer-reuse false-positive that masked the V113 boot crash (header comment at lines 314-329).

The actuator path aggregates Spring's auto-discovered health indicators: DB (Hikari), Redis (Lettuce), disk space, etc. The verbose body is only returned to authenticated callers (`when_authorized`).

### `/api/health` and `/api/health/ready` (custom)

`api/controller/HealthController.java` exposes two non-actuator endpoints. These are independent of the actuator and exist primarily for the dashboard header status badge + GCE managed instance group health checks.

| Endpoint                  | Purpose                                                                                              |
| ------------------------- | ---------------------------------------------------------------------------------------------------- |
| `GET /api/health`         | Liveness. Always 200 if the JVM is up. DB + Redis statuses are cached and refreshed every 30s        |
| `GET /api/health/ready`   | Readiness. Inline DB check via `Connection.isValid(2)`. Returns 503 if DB unreachable                 |

The cached liveness path is deliberately cheap to prevent connection-pool exhaustion when a load balancer is probing aggressively. The MIG auto-healer is expected to use `/api/health`; the LB backend service should use `/api/health/ready` for traffic-routing decisions.

Response body (when called externally):

```json
{
  "status": "UP",
  "uptimeMs": 1234567,
  "uptime": "20m 34s",
  "activeRuns": 3,
  "queuedRuns": 0,
  "totalRuns": 124,
  "completedRuns": 110,
  "failedRuns": 8,
  "db": "UP",
  "redis": "UP",
  "timestamp": "2026-05-16T18:30:00Z"
}
```

`status` flips to `DEGRADED` when either DB or Redis is `DOWN` (per `HealthController.health()` lines 69-89).

### HealthIndicator beans

There are **no `HealthIndicator` bean implementations** in the application code. Verified by grepping `src/main/java/` for `implements HealthIndicator`. Spring's auto-configured `DataSourceHealthIndicator`, `RedisHealthIndicator`, `DiskSpaceHealthIndicator`, etc. cover the indicator layer via the `spring-boot-starter-actuator` dependency in `pom.xml`.

### Prometheus + Grafana (internal)

The local + dev observability stack (`observability/docker-compose.yml`):

| Service     | Port      | Role                                                                              |
| ----------- | --------- | --------------------------------------------------------------------------------- |
| Prometheus  | 9090      | Scrapes `host.docker.internal:8080/actuator/prometheus` every 15s                |
| Tempo       | 4317/4318/3200 | OTLP gRPC + HTTP receivers + Tempo API                                       |
| Grafana     | 3001 (host) | Dashboards `executespec-overview.json` + `jvm-http-overview.json`              |

The overview dashboard (`observability/grafana/dashboards/executespec-overview.json`) is the operator's single pane. Top row has stat panels for live `Active Runs`, `Queued Runs`, `Active Previews`, `Active Deploys` with threshold colours (green / yellow / red). Subsequent panels cover run duration p50/p95/p99, LLM call rate + latency + token usage + errors, task duration, agent handover, gate wait times, tool calls, preview build/deploy duration, deploy failures, and the stuck-runs counter.

Prod ships the same data to GCP Cloud Monitoring + Cloud Trace via the OpenTelemetry exporters wired by `GcpOtelExporterConfig.java` under `@Profile("gcp")` (`pom.xml` dependencies: `exporter-trace`, `exporter-metrics`).

Custom metric catalog: see `docs/operations/slos.md`.

## External / customer-visible status

### What exists today

There is **no public status page** wired into the codebase. Search results:

- `grep -rln "status.executespec\|statuspage\|StatusPage" src/` → no matches.
- `.env.template` does not contain a status-page integration variable.
- No customer-facing controller or route exposes a public health summary at `/api/public/status` or similar.

The `/api/health` and `/actuator/health` endpoints are reachable externally on the deployed VM (the Spring Security configuration whitelists them — see `auth/security/SecurityConfig.java`), so a customer could in principle hit them, but they are not advertised, are not styled for end users, and do not return a service-aggregate health summary suitable for a "status page" experience.

### Gap

Building a public status page would require, at minimum:

1. A public read-only endpoint (e.g. `GET /api/status/public`) that returns a non-sensitive summary: component up/down, current incident, last-N-day uptime.
2. A non-Spring-Security-protected route in `SecurityConfig`.
3. A dedicated frontend route under the unauthenticated surface (e.g. `/status`).
4. An incident table — there is no `incident` entity in the schema today (verified by listing `state/entity/*.java`).
5. Optional: integration with a hosted status-page provider (Statuspage.io, Better Stack, Instatus). No SDK or webhook for any of these is wired in `pom.xml`.

### How customers currently see system status

| Surface                                | What's shown                                                                                                |
| -------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Dashboard header                       | "Server" status badge derived from `/api/health` (UP / DEGRADED) — frontend code only; not a status page    |
| Run detail pages                       | Per-run live status via SSE (`/api/runs/{id}/events`); cost progress bar; verified score                    |
| Email notifications                    | Run completion + quota warning + gate-opened events (`notification/RunCompletionEmailListener.java`)        |
| In-app notifications                   | `/api/notifications` + WebSocket toasts                                                                      |

Nothing in those surfaces communicates *platform*-wide outages — they communicate per-run health only.

## Incident escalation

### Slack notifications

The deploy workflow (`.github/workflows/deploy.yml` lines 426-478) posts a success-or-failure summary card to the incoming-webhook URL in `secrets.SLACK_WEBHOOK_URL`. The payload includes branch, commit, actor, and a link to the workflow run. This is the only automatic Slack notification in the deploy path.

There is no Slack alerting from the JVM itself (no `SlackAlerter` bean, no Slack webhook configured for runtime errors). Cloud Monitoring + Cloud Trace alerts can be wired in GCP console but are not codified in Terraform.

### PagerDuty / Opsgenie

There is **no on-call paging integration** for service health. `integration/pagerduty/PagerDutyAdapter.java` exists, but it is a *customer-facing inbound* adapter (PagerDuty incidents → trigger an ExecuteSpec pipeline run), not an outbound alerter for ExecuteSpec's own health. `application.yml integrations.pagerduty.enabled: false` confirms it's the inbound integration.

The `.env.template` does not contain `PAGERDUTY_*` or `OPSGENIE_*` keys for runtime use.

### Email

Admin-class events use SMTP via `auth/service/MailService.java`. Destinations:

- New-signup alerts: `${ADMIN_NOTIFICATION_EMAIL:admin@executespec.ai}` (`application.yml app.mail.admin-address`).
- Weekly admin summary: `WeeklyAdminSummaryJob` (Mondays 09:00 Asia/Kolkata, `@Scheduled(cron = "0 0 9 * * MON")`).
- Dunning + renewal reminders: `billing/scheduler/{Dunning,RenewalReminder}Scheduler.java`.

These cover product / billing events, not platform incidents.

### Telegram operator bridge

Documented in the project CLAUDE.md but not surfaced in `.env.template`: an operator-only Telegram chat (`chat_id=6475810501` + bot `8489535450`) receives heartbeat + status-change pings every 600s and accepts `getUpdates` polled every 120s. This is an out-of-band side channel for matrix tracking, not a customer-facing health surface. It is not currently wired as a runtime alerting channel for service-down events.

## Maintenance windows

### Feature flags for paused submissions

Searched the codebase for pause-style flags (`grep -rln "submissions.paused\|isEnabled.*paused" src/main/java/`). Found:

- `api/controller/RunController.java` consumes `FeatureFlagService.isEnabled(...)` but the specific key is application-defined per call site; there is no central `submissions.paused` constant.
- `FeatureFlagService` (`config/featureflag/FeatureFlagService.java`) reloads flag rows every 60s (`@Scheduled(fixedRate = 60_000)`). Backed by a DB table; admin-modifiable via `FeatureFlagController` at `POST /api/feature-flags/{key}` (SUPER_ADMIN only).

A maintenance-mode flag can be added by inserting a row keyed on a name the run-submit code path consults. The infrastructure is there; the flag itself isn't named yet.

### Documented maintenance levers

| Lever                                             | Where                                                                            |
| ------------------------------------------------- | -------------------------------------------------------------------------------- |
| Cap concurrent runs                               | `application.yml agent.concurrency.max-runs` (50 base, 20 prod). Restart needed |
| Tighten per-provider concurrency                  | `application.yml agent.provider-pools.providers.*.concurrency`                  |
| Disable a tier                                    | `PUT /api/admin/tiers/{tier}` → `SUNSETTING` (admin-only)                       |
| Pause v2 runs in-flight                           | `POST /api/runs/{id}/pause` (gated by `v2.pause.enabled`, default OFF)          |
| Cancel running runs                               | `DELETE /api/runs/{id}` (`RunOrchestrator.cancel(runId)`)                        |
| Disable previews                                  | Set `preview.enabled: false` in `application.yml`                                |
| Disable LLM provider                              | Set `spring.ai.{anthropic,openai,google}.api-key: ""`. Failover chain takes over |

### Deploy-time pauses

The deploy workflow does not currently flip a maintenance flag before / after deploy. The systemd unit `executespec.service` (installed by `infra/scripts/startup.sh`) is restarted via `sudo systemctl restart executespec` (`deploy.yml` line 305). Restart downtime is bounded by `RestartSec=30`; in practice the health check loop in the workflow (lines 330-352) waits up to 80 seconds for the new JVM to report UP.

A more careful pause-then-deploy flow would need:

1. A `maintenance.enabled` flag in `feature_flag` consulted by `RunController.submit` to return HTTP 503.
2. A pre-deploy step in `deploy.yml` to flip the flag via `POST /api/feature-flags/maintenance.enabled`.
3. A post-deploy step to flip it back after the health check passes.

None of those exist today. The deploy workflow restarts the service without a planned-maintenance signal to in-flight users.

## Operator runbook (current state)

| Symptom                                              | First check                                                              | Action                                                                       |
| ---------------------------------------------------- | ------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| Deploy health check fails                            | `gcloud compute ssh ... 'sudo journalctl -u executespec -n 200'`         | Workflow's `Dump app logs on failure` step prints this automatically         |
| Customer reports "site is down"                      | `curl https://dev.executespec.ai/api/health`                              | If DB/Redis DOWN, check Cloud SQL + Memorystore status in GCP console        |
| Slack channel quiet but customers complain           | Verify `SLACK_WEBHOOK_URL` is set in repo settings (deploy.yml line 430) | Failure path skips if blank; this is fail-open                                |
| Stuck-run count rising                               | Grafana `Stuck Runs Detected` panel (`executespec_monitor_stuck_detected_total`) | Inspect `recovery/StartupRunResumer` logs; cancel runs via admin            |
| LLM call latency rising                              | Grafana `LLM Call Latency` panel                                          | Provider failover should activate via `model-matrix.failover-order`           |
| Provider pool saturated                              | Grafana `executespec_run_queued` gauge ≥ 5                                | Bump `agent.provider-pools.providers.<PROVIDER>.concurrency` + redeploy      |
| Preview builds failing                               | Grafana `Preview & Deploy Failures` panel                                | Check `executespec.preview.failures` tags; verify Artifact Registry auth on VM |
| Workspace disk filling                               | `df -h` on the VM; `executespec.workspace.disk.reaped` counter           | `WorkspaceDiskCleanupJob` runs daily at 03:30 — wait or run manual cleanup   |

## Known gaps

- **No public status page.** No customer-visible endpoint summarising platform health.
- **No incident entity.** No DB schema for tracking incidents (start / end / severity / affected components).
- **No automatic incident broadcast.** Slack webhook is deploy-only; no runtime SEV alerting.
- **No on-call rotation in code.** PagerDuty / Opsgenie integrations are not wired for outbound alerting.
- **No maintenance-mode flag.** Flag infrastructure exists but no canonical key consulted across the run-submit / billing / public-read paths.
- **External health endpoints not styled for end users.** `/api/health` returns a JSON blob suitable for monitoring but not for human readers.

Each of these is an opportunity for a future PR; none is in flight today.
