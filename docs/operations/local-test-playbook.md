# Local Test Playbook

Step-by-step recipe to bring ExecuteSpec up on a developer machine and run the
test suites. Everything here is copy-paste runnable from a fresh checkout of the
repo on macOS or Linux. Where macOS-specific paths appear (`/opt/homebrew/...`),
the Linux equivalent is called out.

The single most common boot failure on macOS is a wrong `JAVA_HOME` — the parent
POM (`pom.xml`) pins `java.version=21`, and `mvn` will pick up whatever Java is
on `$PATH` first. Always set `JAVA_HOME` explicitly before any Maven invocation.

---

## 1. Prerequisites

### Required

| Tool | Version | Where it's used |
| --- | --- | --- |
| JDK 21 | exactly 21 (Temurin or OpenJDK) | Spring Boot 3.5, `pom.xml` `<java.version>21</java.version>` |
| Maven | 3.9+ (project ships no `mvnw`) | All backend build/test/run |
| Node.js | LTS (>= 20) | Vite dev server, ESLint, Prettier |
| npm | bundled with Node 20+ | Frontend tooling (`npm --prefix frontend run ...`) |
| Docker | 24+ | Postgres + Redis sidecars, observability stack, preview containers |
| PostgreSQL client | 16+ (`psql`, `pg_dump`) | Dev-wipe helper scripts under `scripts/` |
| `curl` + `jq` | any recent | Eval scripts under `scripts/` |

### Optional

- `redis-cli` for inspecting Redis state during runs (SSE replay log, pause
  flags, BYOK cache).
- `gcloud` only if you need to test the deploy-engine path
  (`com.navneet.executespec.deployment.engine.UserCloudRunDeployer`) against a
  real GCP project. Local dev never needs it.
- `gsutil` only if you intend to exercise the GCS workspace archive path
  (`agent.deployment.workspace.gcs-enabled=true`, off by default in
  `application.yml`).

### macOS `JAVA_HOME`

Install JDK 21 via Homebrew and export `JAVA_HOME` in every shell that will run
Maven:

```bash
brew install openjdk@21
# One-shot
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
# Persistent (zsh)
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@21' >> ~/.zshrc
```

On Linux pick whatever your distro names JDK 21 (`/usr/lib/jvm/temurin-21-jdk`
on Debian, `/usr/lib/jvm/java-21-openjdk` on Fedora).

---

## 2. Postgres + Redis (Docker)

`application.yml` defaults to:

- `spring.datasource.url=jdbc:postgresql://localhost:5432/executespec`,
  user `postgres`, password `postgres`.
- `spring.data.redis.host=localhost`, `port=6379`, `timeout=2000ms`, Lettuce
  pool `max-active=50`, `min-idle=5`.

Spin up both with one pair of docker run commands so they survive restarts:

```bash
docker run -d --name executespec-pg \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=executespec \
  -p 5432:5432 \
  -v executespec_pg:/var/lib/postgresql/data \
  postgres:16-alpine

docker run -d --name executespec-redis \
  -p 6379:6379 \
  redis:7-alpine
```

Health check both:

```bash
docker exec executespec-pg pg_isready -U postgres        # expect: accepting connections
docker exec executespec-redis redis-cli ping             # expect: PONG
```

Flyway will run on backend boot. Migration head is `V199`; locations are
`classpath:db/migration` per `application.yml`
(`spring.flyway.locations=classpath:db/migration`). The base profile carries
`baseline-on-migrate=true`, `baseline-version=0`, and
`ignore-migration-patterns="*:missing"` to absorb the orphan `V119`
preview-engine row.

If you need a clean reset partway through testing:

```bash
scripts/dev-wipe-runs-and-projects.sh     # backs up + truncates run/project tables
```

That helper SQL preserves identity + billing tables — it is intentionally not a
DROP DATABASE.

---

## 3. Environment variables

Copy the template and fill in the two required keys:

```bash
cp .env.template .env
# Required for the V2 pipeline to dispatch any run:
#   ANTHROPIC_API_KEY=...
#   OPENAI_API_KEY=...
#   GOOGLE_API_KEY=...     (only if testing the Gemini provider)
#   GITHUB_PAT=...         (only if testing the GitHub push path)
```

The default Spring profile when developing should be `local`. That profile
(declared inside `application.yml`) silences the OTLP exporter so the JVM
doesn't spam reconnect attempts when Tempo isn't running:

```yaml
# application.yml — local profile
spring.config.activate.on-profile: local
management.tracing.enabled: false
management.otlp.metrics.export.enabled: false
```

There is also a committed `application-local.yml`. **Important:** that file
contains hardcoded API key fallbacks from earlier development. Do not commit
new keys there — rely on `.env` / shell env vars. The keys in the committed
file should be considered burned.

Source the env before launching:

```bash
set -a; source .env; set +a
```

---

## 4. Backend boot

### Compile only

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
```

### Boot the app

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The JVM binds to `localhost:8080`. First boot will run all Flyway migrations
through V199, which can take ~30-60s on a cold DB.

### Liveness probe

`HealthController`
(`src/main/java/com/navneet/executespec/api/controller/HealthController.java`)
exposes a cheap, cached liveness endpoint:

```bash
curl -s http://localhost:8080/api/health | jq .
```

Expected JSON shape includes `status: UP` or `DEGRADED`, `uptime`, cached
`db.up` / `redis.up`, and a cached metrics block (refreshed in background every
30s — `CACHE_INTERVAL_MS=30_000` in `HealthController`). The probe never opens a
DB connection on the request thread, so it cannot exhaust the Hikari pool even
if called many times per second.

### Readiness probe

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/api/health/ready
# 200 if DB pings back, 503 if DB is down
```

Readiness runs an inline DB check; that's the right signal for a load balancer.

### Spring Boot Actuator

`application.yml` exposes a small allowlist:

```yaml
management.endpoints.web.exposure.include: health,info,metrics,prometheus
```

So you also get:

- `GET /actuator/health` — Spring's standard composite health (DB, Redis, disk
  space, etc.).
- `GET /actuator/metrics` — list of registered metric names.
- `GET /actuator/metrics/{name}` — single metric snapshot.
- `GET /actuator/prometheus` — Prometheus scrape endpoint (Micrometer + Spring
  AI + V2 custom metrics under `executespec.v2.*`).
- `GET /actuator/info` — empty unless you populate `info.*` in config.

Other actuator endpoints (`env`, `beans`, `loggers`, `threaddump`, `heapdump`)
are intentionally **not** exposed.

### Common boot failures

| Symptom | Root cause | Fix |
| --- | --- | --- |
| `UnsupportedClassVersionError: class file version 65.0` | `mvn` picked up Java 17 | `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` |
| `Flyway: Validate failed: Migration checksum mismatch` | Edited an already-applied `V*.sql` | Add a new `V_next__patch.sql` instead. Never modify applied migrations |
| `org.hibernate.tool.schema.spi.SchemaManagementException` on boot | DB schema drifted from Flyway head | Drop the DB and let Flyway re-baseline, or run `scripts/dev-wipe-runs-and-projects.sh` |
| `Connection refused (localhost:5432)` | Postgres container not running | `docker start executespec-pg` |
| `Connection refused (localhost:6379)` | Redis container not running | `docker start executespec-redis` |
| `OTLP exporter failed` log every 60s | OTel exporter pointed at unreachable endpoint | Confirm you booted with `--spring-boot.run.profiles=local` so the local-profile override fires |

---

## 5. Frontend boot

Frontend lives under `frontend/` as a standalone npm project, ESM-only,
Vite-driven.

### Install once

```bash
npm --prefix frontend ci
```

(`ci` rather than `install` to honour the committed lockfile.)

### Dev server

`frontend/vite.config.ts` reads:

```ts
server.port    = Number(process.env.VITE_PORT ?? 5173)
server.proxy['/api'] = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080'
```

So the default dev URL is `http://localhost:5173`, and all `/api/*` calls are
proxied to `localhost:8080` (matching the backend's default port).

```bash
npm --prefix frontend run dev
# Vite v5 -> ready at http://localhost:5173
```

If you're running concurrent worktrees on the same machine, override:

```bash
VITE_PORT=5174 VITE_BACKEND_URL=http://localhost:8081 npm --prefix frontend run dev
```

### Production build

```bash
npm --prefix frontend run build
```

This compiles TS then runs `vite build` with output **emptied and rewritten
into `src/main/resources/static/`** — i.e. it directly populates the Spring Boot
JAR's static folder. Don't run this if you want to keep your in-memory dev
backend in sync with a Vite dev server; instead run `npm run dev`.

### Lint / format

```bash
npm --prefix frontend run lint           # eslint, --max-warnings 9999
npm --prefix frontend run format:check   # prettier --check
npm --prefix frontend run format         # prettier --write
```

---

## 6. Backend tests

The Surefire profile in `pom.xml` declares
`<excludedGroups>e2e,integration,chaos</excludedGroups>`, so the default
`mvn test` only runs unit + ArchUnit + minimal slice tests.

### Full unit suite

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test -q
```

### One test class

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn -q -Dtest=RunOrchestratorTest test
```

### One method

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn -q -Dtest=RunOrchestratorTest#shouldClaimPlanGateAtomically test
```

### Full verify (formats + JaCoCo report)

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q
```

`verify` invokes:

- The Spotless `check` execution (`spotless-check`, bound to `verify`) using
  `google-java-format` 1.22.0 with the import order
  `java, javax, jakarta, com, org, (others)`.
- JaCoCo's `report` execution (HTML at `target/site/jacoco/index.html`).

### Spotless fix

If `mvn verify` fails on a Spotless gate, run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn spotless:apply
```

…then re-run verify.

### Integration / e2e profiles

The default `pom.xml` excludes the JUnit tags `e2e`, `integration`, `chaos`. To
run them you must un-exclude or use a profile that overrides `excludedGroups`.
None of the integration profile machinery is declared in this POM; see the
shell scripts in `scripts/` for harness-driven end-to-end coverage instead.

---

## 7. Eval and smoke-test scripts

All shell harnesses live under `scripts/` and read shared env (`BASE_URL`
defaults to `http://localhost:8080`, `EMAIL`/`PASSWORD` to your local admin).
Hot paths to know about:

### V2 eval matrix (3 bands x 3 providers)

```bash
EMAIL=admin@executespec.ai PASSWORD=REDACTED_FOR_PUBLIC_RELEASE \
  scripts/v2-eval.sh --providers anthropic,openai,gemini --bands low,medium,high
```

Output lands in `scripts/v2-eval-results/v2-eval-<ts>.{json,md}` plus a
`-raw/` directory with each `{run,report,result}.json`. Used to verify
regression on the model matrix or fallback-chain config (see
`application.yml model-matrix.fallback-chains`).

### Strict eval (S3/S4/S5 specs, captures `verifiedScore`)

```bash
scripts/v2-eval-strict.sh --providers anthropic,openai,gemini
```

Uses the `s3-medium.txt`, `s4-large.txt`, `s5-integration.txt` specs under
`scripts/v2-eval-specs/`. Captures both `accuracyScore` (legacy LLM-judged) and
`verifiedScore` (the deterministic multiplicative score), plus
`reviewerVerdict`, `finalBuildPassed`, `testsPassed/Total`, `statusReason`.

### Multi-provider regression

```bash
scripts/test-providers.sh           # single-shot HTML + Express multi-task
scripts/exhaustive-eval.sh          # 16 specs x 3 providers (large, slow)
scripts/benchmark-run.sh            # 9-run production-readiness benchmark
```

### Crash-resilience (StartupRunResumer)

```bash
scripts/test-resilience.sh
# boots a JVM via mvn spring-boot:run, submits a run, SIGKILLs mid-flight,
# restarts the JVM, asserts the run reaches terminal post-restart.
```

Outputs go to `/tmp/executespec-resilience/`.

### Preview + code-editor probe

```bash
scripts/e2e-preview-test.sh
# 3 providers x 2 spec sizes; verifies workspace files, /preview/status,
# and a HTTP-200 response from the preview URL.
```

### Dev cleanup helpers

```bash
scripts/dev-wipe-runs-and-projects.sh     # pg_dump + truncate run tables, wipe workspace, FLUSHDB
scripts/dev-cleanup.sh                    # interactive DELETE-based cleanup (preserves users/billing)
```

The two cleanup paths cover slightly different table sets — `dev-cleanup.sh`
omits the V131-V139 enterprise-readiness tables that `dev-wipe-...sql`
truncates. Use whichever matches the table set you actually need to reset.

---

## 8. Observability stack (Prometheus + Tempo + Grafana)

The `observability/docker-compose.yml` brings up a 3-container local stack:

```bash
cd observability
docker compose up -d
docker compose ps   # confirm all three are Up
```

Ports (from `observability/docker-compose.yml`):

| Service | Host port | Container port | URL |
| --- | --- | --- | --- |
| Grafana | **3001** | 3000 | http://localhost:3001 (admin/admin) |
| Prometheus | 9090 | 9090 | http://localhost:9090 |
| Tempo (OTLP gRPC) | 4317 | 4317 | n/a |
| Tempo (OTLP HTTP) | 4318 | 4318 | matches `management.otlp.tracing.endpoint` in `application.yml` |
| Tempo (query API) | 3200 | 3200 | http://localhost:3200 |

Prometheus scrapes Spring Boot at `host.docker.internal:8080/actuator/prometheus`
every 15s (`observability/prometheus.yml`):

```yaml
scrape_configs:
  - job_name: executespec
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

Grafana auto-loads two dashboards from `observability/grafana/dashboards/`:

- `jvm-http-overview.json` — JVM heap/non-heap, GC, threads, HikariCP, HTTP
  request rate + latency (p50/p95/p99), Lettuce/Redis command latency.
- `executespec-overview.json` — Active/queued runs, run duration percentiles,
  LLM calls + latency + tokens by provider/model, tool calls, agent handover
  duration, gate wait, preview/deploy lifecycle, stuck-run detector.

Note that the Grafana dashboard JSON queries `executespec_run_total_total` and
similar names (the Micrometer Counter double-`_total` shape). The
`Metrics.java` facade registers them as `executespec.v2.run.outcome` etc., so
some dashboard panels may show empty if the metric never fires — query
`/actuator/prometheus | grep executespec_v2_` to confirm the metric name being
exported.

To turn off the stack:

```bash
docker compose down            # stop + remove containers
docker compose down -v         # also wipe volumes (Tempo trace history)
```

To send traces from the local backend into the stack, drop the local-profile
override:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn spring-boot:run \
      -Dspring-boot.run.profiles=default \
      -Dmanagement.otlp.tracing.endpoint=http://localhost:4318/v1/traces \
      -Dmanagement.tracing.enabled=true \
      -Dmanagement.tracing.sampling.probability=1.0
```

---

## 9. Common 500s and their root causes

Pulled from the global exception advisors
(`src/main/java/com/navneet/executespec/api/advice/`) and the validation /
optimistic-lock / billing handlers:

| HTTP shape | Source | Likely cause when seen locally |
| --- | --- | --- |
| 500 with `errorId` UUID | `GlobalExceptionAdvice.onAny(Throwable)` | Unhandled `RuntimeException`; full stack is in the server log keyed by `errorId`. **Always** grep for the UUID before assuming flake. |
| 503 on `/api/health/ready` | `HealthController#ready` failed inline DB check | Postgres container down or `maxLifetime` exhausted the pool |
| 409 with `entity, id` | `OptimisticLockAdvice.onOptimisticLock` | Two concurrent writers to the same `ExecutionRun` (`@Version`) — reload + retry |
| 400 with `fields` map | `ValidationAdvice.onValidation` | `RunRequest` JSR-380 violation (e.g. `aiProvider` not in `ANTHROPIC|GEMINI|GOOGLE|OPENAI`, `userCostCap` out of `[0.10, 1000.00]`, `userRequest` empty or >50_000 chars) |
| 402 PAYMENT_REQUIRED | `ValidationAdvice.onBilling` | Project monthly spend cap exceeded (`GovernanceGuard.assertProjectSpendCap`) or insufficient lifetime credits |
| 405 with supported-methods list | `ValidationAdvice.onMethodNotAllowed` | Wrong verb on a known path (e.g. `POST /api/runs/{id}` instead of `GET`) |
| 410 Gone on `/clarify`, `/merge`, `/retry`, `/iterate`, `/edit-and-restart`, `/rework`, `/implement-suggestions`, `/reopen-merge`, `/edit-plan`, `/api/feasibility` | Various controllers explicitly throw 410 | All V1 endpoints; V2 doesn't have these. Update the client. |
| 429 on `/api/**` | `RateLimitFilter` Redis-backed bucket | Per-tier req/min cap hit. FREE READ=300/min, WRITE=60/min, RUNS=5/min. Wait `retryAfterSeconds` or temporarily flip `executespec.rate-limit.enabled=false` in `application.yml`. |
| `OutOfMemoryError: Java heap space` | Mid-run agent context blow-out | Raise `-Xmx` on the spring-boot-run JVM; check `agent.token-budget.max-tokens` (default 64000) and `agent.compaction.compact-pct` (default 0.60) in `application.yml` |
| `Connection is not available, request timed out after 10000ms` | Hikari pool exhausted (`maximum-pool-size=30`) | Usually a long-running blocking call on a Tomcat thread; the apply-suggestion endpoint already routes through a dedicated `applyExecutor`. Look for non-async code in `RunController` |
| Frontend `GET /api/...` returns HTML | Vite proxy not running or wrong `VITE_BACKEND_URL` | Restart `npm run dev`, confirm proxy in `frontend/vite.config.ts` |

---

## 10. Reference: ports and URLs cheat-sheet

| Service | URL / port | Source |
| --- | --- | --- |
| Backend HTTP | http://localhost:8080 | Spring Boot default; `application.yml` doesn't override |
| Backend health (cheap) | http://localhost:8080/api/health | `HealthController` |
| Backend readiness | http://localhost:8080/api/health/ready | `HealthController` |
| Actuator health (composite) | http://localhost:8080/actuator/health | `application.yml` exposure |
| Actuator metrics | http://localhost:8080/actuator/metrics | `application.yml` exposure |
| Prometheus scrape | http://localhost:8080/actuator/prometheus | `application.yml` exposure |
| Vite dev | http://localhost:5173 | `frontend/vite.config.ts` default |
| Postgres | localhost:5432 | `application.yml` `spring.datasource.url` |
| Redis | localhost:6379 | `application.yml` `spring.data.redis` |
| Grafana | http://localhost:3001 (admin/admin) | `observability/docker-compose.yml` |
| Prometheus UI | http://localhost:9090 | `observability/docker-compose.yml` |
| Tempo OTLP HTTP | http://localhost:4318 | `observability/docker-compose.yml` |
| Tempo query API | http://localhost:3200 | `observability/docker-compose.yml` |
