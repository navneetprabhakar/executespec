# Status and Health Monitoring

How ExecuteSpec exposes liveness, readiness, and runtime health — both for our
own load balancers / probes and for the operator. Customer-facing status
beyond a self-hosted readiness URL does **not** exist as of today; that gap
is called out in section 5.

---

## 1. Application health endpoints

Two distinct probe surfaces ship with the backend:

### 1.1 Custom cheap probe — `/api/health`

`HealthController`
(`src/main/java/com/navneet/executespec/api/controller/HealthController.java`)
defines a deliberately lightweight pair of endpoints under `/api/health`:

```
GET  /api/health        liveness, always 200 if JVM is up; cached metrics block
GET  /api/health/ready  readiness, inline DB ping; 200 on success, 503 on DB down
```

Implementation details:

- The class holds an `AtomicReference<MetricsData> cachedMetrics`,
  `AtomicBoolean cachedDbUp`, `AtomicBoolean cachedRedisUp`, and an
  `AtomicReference<Instant> lastCacheRefresh`. Every call to `/api/health`
  checks `lastCacheRefresh` and refreshes the snapshot **only if older than
  `CACHE_INTERVAL_MS = 30_000`**. The refresh runs on a background thread
  (a virtual thread, per the `@Async` annotation), so the request thread
  never opens a Hikari connection.
- This is the design's defence against probe-induced pool exhaustion. Kubernetes
  livenessProbes that hit the endpoint every 5s used to drain the 30-slot
  Hikari pool when an upstream blip held connections open; switching to the
  cached liveness fixed it.
- `/api/health/ready` deliberately bypasses the cache. It opens a fresh
  connection from the pool, runs `SELECT 1`, releases it, and reports 200 or
  503. That's the correct signal for "should the load balancer route traffic
  here right now."
- Uptime is computed from `START_TIME = Instant.now()`, set at class-load time.

Response body shape (`/api/health`):

```json
{
  "status": "UP" | "DEGRADED",
  "uptime": "1d 4h 23m",
  "version": "1.0.0-M1",
  "metrics": {
    "totalRuns": 12345,
    "activeRuns": 7,
    "queuedRuns": 0,
    "completedRuns": 12001,
    "failedRuns": 337
  },
  "db": { "up": true },
  "redis": { "up": true }
}
```

`status=DEGRADED` if either `db.up` or `redis.up` flipped to false in the most
recent cache refresh. Note that "DEGRADED" still returns HTTP 200 — only
`/api/health/ready` returns 503.

### 1.2 Standard Spring Boot Actuator — `/actuator/health`

`application.yml` exposes the actuator allowlist:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

So `GET /actuator/health` returns Spring Boot's composite health, built from
the auto-discovered `HealthIndicator` beans. Out-of-the-box that includes:

- `db` — `DataSourceHealthIndicator` (validates the configured Hikari pool).
- `redis` — `RedisHealthIndicator` (Lettuce `PING`).
- `mail` — `MailHealthIndicator` (SMTP CONNECT to `smtp.gmail.com:587`).
- `diskSpace` — `DiskSpaceHealthIndicator` (default threshold 10 MB).
- `livenessState` / `readinessState` — Spring's standard probes.

No custom `HealthIndicator` beans are registered in the codebase as of the
research notes. Anything new — for example a custom indicator wrapping
`ProviderHealthTracker` (the circuit breaker for LLM providers) — would need
to be added under the `health` package or alongside `observability/`.

Operationally:

- `/api/health` is the right choice for high-frequency liveness checks
  (load balancer health, K8s liveness probe).
- `/actuator/health` is the right choice for composite "is everything bolted
  on" diagnostic checks (deploy gates, alerting).
- `/api/health/ready` is the right choice for readiness probes that should
  pull a node out of rotation when the DB is unreachable.

### 1.3 Other actuator endpoints

The full set exposed by `application.yml`:

| Endpoint | Purpose |
| --- | --- |
| `GET /actuator/health` | Composite health (DB, Redis, Mail, diskSpace, liveness, readiness) |
| `GET /actuator/info` | Service info bundle. Empty unless `info.*` properties are set. |
| `GET /actuator/metrics` | List of registered metric names |
| `GET /actuator/metrics/{name}` | Single metric snapshot |
| `GET /actuator/prometheus` | Prometheus scrape target (Micrometer + Spring AI + custom `executespec.v2.*`) |

Everything else (`env`, `beans`, `loggers`, `threaddump`, `heapdump`, etc.) is
intentionally **off**. That keeps the attack surface narrow: those endpoints
can leak credentials (`env`), CPU-intensive heap snapshots (`heapdump`), or
reveal the bean graph in a way useful for OWASP-style enumeration.

---

## 2. Operational signals beyond `/health`

### 2.1 Maintenance and feature flags

There is no application-wide `submissions.paused` flag. The closest knobs are:

- `agent.recovery.enabled` (default `true`) — turns the
  `StartupRunResumer` salvage path off without redeploying.
- `agent.recovery.auto-resume-on-startup` (default `false`; `true` on `dev`)
  — controls whether interrupted runs auto-resume after a restart.
- `agent.recovery.max-auto-restarts` (default 2) — caps loop-back recoveries.
- `v2.pause.enabled` feature flag (default `false`, gated through
  `FeatureFlagService`) — gates the `POST /api/runs/{id}/pause` /
  `POST /api/runs/{id}/resume` endpoints. 503 if the flag is off.
- `ci.autofix.enabled` feature flag (default `false`) — gates the
  unattended CI-failure autofix webhook (`CiFailureAutoFixService`).
- `security.guardrail_level` (`LOW` / `MEDIUM` / `HIGH`) — flips between three
  pre-defined guardrail profiles (`security/ShellSandboxPolicy`,
  `security/CodeAbuseScanner`, `security/InputSanitizer`).
- `executespec.rate-limit.enabled` (default `true`) — kill-switch for the
  Redis-backed `RateLimitFilter` (`ratelimit/RateLimitFilter.java`). On at
  rest; flip to `false` only for emergency loosening.
- `analytics.posthog.enabled` (default `false`) — gate for PostHog product
  analytics dispatch.
- `agent.api-test-suite.enabled` (default `true`) — gates the post-merge
  OpenAPI generator (`apitests/ApiTestSuiteAgent`).

There is also no "freeze submissions" toggle by default. The simplest way to
hard-stop new runs is to flip `agent.concurrency.max-runs` to `0` (the
orchestrator queues them but never dispatches) and announce the change via the
operator Telegram bridge (chat `6475810501`, bot `8489535450`). That keeps
existing runs alive without burning credits while new ones queue.

### 2.2 Quotas and tier limits

`application.yml` ships defaults that act as soft circuit breakers:

```yaml
app.tiers:
  free:        { monthly-run-limit: 5,   concurrent-run-limit: 1 }
  pro:         { monthly-run-limit: 100, concurrent-run-limit: 3 }
  enterprise:  { monthly-run-limit: -1,  concurrent-run-limit: 10 }   # -1 = unlimited
```

`agent.concurrency.max-runs: 50` in base config and `20` in production
(`application-prod.yml`). When that ceiling is hit, the orchestrator returns
202 with a `queuePosition` field rather than 503 — runs are durable in
Postgres, not lost.

### 2.3 Operator Telegram bridge

There is an out-of-band monitoring channel the operator uses:

- `chat_id=6475810501`, bot `8489535450`.
- Push: heartbeat every 600s plus an event on every status-count change.
- Pull: `getUpdates` polled every 120s, output appended to `/tmp/tg-inbox.log`.

Not a customer-facing integration; it lives alongside the deployment as a
side-channel for "is the matrix still moving" awareness.

---

## 3. Self-monitoring (Prometheus + Grafana)

The local observability stack (`observability/docker-compose.yml`) and the GCP
profile both scrape `/actuator/prometheus` for the same `executespec.v2.*`
metrics. Dashboards live under `observability/grafana/dashboards/`:

- `executespec-overview.json` — the operator dashboard. Eighteen panels:
  active/queued runs, run duration percentiles, LLM call rate + latency +
  tokens by provider/model, LLM errors and fallbacks, task duration, agent
  handover duration, gate wait, tool calls, preview build + deploy duration,
  preview/deploy failures, **stuck-runs detected**.
- `jvm-http-overview.json` — JVM-level dashboard: heap/non-heap, GC pause,
  thread counts, HikariCP pool, Lettuce/Redis command latency, HTTP request
  rate + latency.

The stuck-run panel is the most operationally useful: it tracks
`executespec_monitor_stuck_detected_total`. A non-zero value means a run has
exceeded the watchdog thresholds (`agent.recovery.warn-threshold-minutes`,
`act-threshold-minutes`) without progress. There's no automatic alert wired —
operator owns the response.

Local Grafana credentials are committed in the compose file (`admin/admin`).
Acceptable for the local-only stack; do not expose this compose file to a
non-local host without rotating credentials.

---

## 4. Alerting integrations (present, partial)

The following hooks exist in code but are not wired end-to-end as alert
destinations:

| Integration | Source | State |
| --- | --- | --- |
| Slack incident channels | `agent.integrations.slack.*` (controller `api/controller/SlackIntegrationController.java`) | OAuth flow + project/channel mapping ships, used for **run notifications** not infrastructure alerts. Per-org config; no global "ops alerts" channel wiring. |
| Email (Gmail SMTP) | `app.mail.smtp.host=smtp.gmail.com:587` | Used for transactional mail (run-complete, password reset, invoices). No automatic alerting hook. |
| PagerDuty | `agent.integrations.pagerduty.enabled: false` | Config slot exists; never enabled. Trigger types in `application.yml`: `["incident.trigger"]`. |
| JIRA | `agent.integrations.jira.enabled: false` | Inbound webhook receiver lives in `api/controller/IntegrationController.java`. Outbound ticketing not wired. |

The Grafana dashboards have alerting capable but **no Slack / PagerDuty
destination is configured** in the JSON. Adding one is config-only.

---

## 5. Customer-facing status page

**There is no customer-facing status page today.** The trust-pack PDF
(`scripts/trust-pack/render.mjs` → `frontend/public/trust-pack.pdf`) documents
the data-flow and certifications posture but does not include a real-time
status URL.

Specifically:

- No `status.executespec.ai` subdomain.
- No public Statuspage / Better Stack / Statusgator integration.
- No public endpoint that returns "all systems operational" / "degraded".
- The closest customer-visible signal is the `/api/health` endpoint, which
  is JWT-protected at the security filter chain and not designed for public
  consumption.

If a status surface were added, the sane shape would be a static page hosted
behind Cloudflare (where `*.executespec.ai` already terminates) backed by a
small JSON polled from `/api/health` (cheap path, already cached) plus the
Prometheus `executespec_v2_run_outcome_total` and stuck-runs counters via a
read-only token.

---

## 6. Incident escalation channels

The current channels, ordered by criticality:

1. **Operator Telegram bridge** (chat `6475810501`) — first-touch for any
   operator awareness. Heartbeats + status-count changes auto-fire.
2. **Prometheus + Grafana** (`observability/`) — the operator dashboard is
   the primary debugging surface. Stuck-run, preview/deploy failures, and
   LLM error rates are the panels to check first.
3. **Application log stream** — structured JSON via Logback
   (`logstash-logback-encoder:8.0` in `pom.xml`). Every error path stamps a
   correlation id (`errorId` on 500 responses, `runId`/`taskId` on V2 spans).
4. **Email to `admin@executespec.ai`** — `app.contact.fallback-email`.
   `app.contact.jira.{base-url, email, api-token, project-key=SUP,
   issue-type=Task}` exists in config but is gated off; flipping it on routes
   inbound support email into a JIRA `SUP-*` ticket via the inbound integration
   controller.
5. **Deploy rollback path** — `POST /api/deployments/{id}/rollback` re-routes
   Cloud Run traffic to the previous revision; `engine-rollback` does the
   same via the deploy-engine handoff (`DeployEngineOrchestrator`).
6. **Workspace recovery** — `agent.recovery.auto-resume-on-startup` (true on
   dev) plus `StartupRunResumer` (`recovery/StartupRunResumer.java`) salvages
   any RESUMABLE_STATUSES run on JVM start. If the workspace dir is missing,
   the run is marked PARTIAL with `status_reason=REVIEWER_INTERRUPTED_CODE_PRESERVED`
   so the operator can see exactly what was rescued vs lost.

There is no on-call rotation tool wired in. The operator absorbs all four
channels; this is one of the larger gaps for a publishable status posture.
