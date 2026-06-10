# Status And Health Monitoring

> Recreated 2026-06-10 from `HealthController`, Actuator config, and current operations docs.

## Health Endpoints

| Endpoint | Purpose | Behavior |
| --- | --- | --- |
| `GET /api/health` | Cheap liveness and dashboard status | Always 200 if JVM is alive; DB/Redis/metrics are cached for 30s |
| `GET /api/health/ready` | Readiness / traffic routing | Inline DB check; returns 503 when DB is unavailable |
| `GET /actuator/health` | Spring Boot composite health | Standard actuator health |
| `GET /actuator/prometheus` | Metrics scrape | Micrometer + custom metrics |

`/api/health` returns fields such as `status`, `uptimeMs`, `activeRuns`, `queuedRuns`, `totalRuns`, `completedRuns`, `failedRuns`, `db`, `redis`, and `timestamp`.

## Metrics

Prometheus metrics are exposed through Actuator. Key custom prefixes include:

- `executespec.v2.run.*`
- `executespec.v2.planner.*`
- `executespec.v2.task.*`
- `executespec.v2.worker.*`
- `executespec.v2.llm.*`
- `executespec.v2.reviewer.*`
- `executespec.v2.context.*`

Operational SLOs are documented in `docs/operations/slos.md`.

## Customer-Facing Status

There is no fully separate, independently hosted public status-page product in this repo. The implemented health/status surfaces are application endpoints and operator dashboards.

If a public status page is introduced, it should consume stable synthetic probes rather than raw internal health JSON. Do not expose run counts or internal queue state publicly without a privacy review.

## Incident Checks

1. Check `/api/health`.
2. Check `/api/health/ready`.
3. Check `/actuator/health`.
4. Check `/actuator/prometheus` scrape availability.
5. Check Cloud SQL and Redis.
6. Separate provider outage from app outage for LLM failures.
7. Preserve trace ids, run ids, and webhook ids.

## Code Pointers

- `src/main/java/com/navneet/executespec/api/controller/HealthController.java`
- `src/main/resources/application.yml` (`management.endpoints.web.exposure`)
- `docs/operations/slos.md`
- `observability/docker-compose.yml`
