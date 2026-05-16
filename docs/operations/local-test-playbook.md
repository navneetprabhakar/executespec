# Local Test Playbook

How to bring up ExecuteSpec on a developer workstation, run the test suite, and verify a change before pushing. Code-truth only — every command below is reproducible from a clean checkout.

If a step here disagrees with what `mvn`/`npm` actually does, the code wins; fix this file.

## Prerequisites

| Tool       | Version                  | Why                                                                       |
| ---------- | ------------------------ | ------------------------------------------------------------------------- |
| Java       | 21 (Temurin)             | `pom.xml` `<java.version>21</java.version>` + Spring Boot 3.5 baseline    |
| Maven      | 3.9+                     | Build + Spotless + JaCoCo                                                  |
| Node.js    | LTS                      | `frontend/package.json` requires `vite ^5.1.0` (Node 18+); CI uses `lts/*` |
| Docker     | 24+ with Compose plugin   | Local Postgres/Redis, observability stack, cleanroom containers, previews  |
| PostgreSQL | 16                       | `application.yml` JDBC URL targets PG; matches Cloud SQL                  |
| Redis      | 7                        | `application.yml` `spring.data.redis.*`                                   |
| `psql`     | from PostgreSQL          | Used by `scripts/dev-cleanup.sh` and ad-hoc DB pokes                      |
| `jq`       | latest                   | Used by `scripts/v2-eval.sh`, `scripts/e2e-test.sh`                       |
| `curl`     | latest                   | Health probes + scripts                                                    |
| `gsutil` / `gcloud` | optional         | Only needed if interacting with the deploy bucket / Secret Manager        |

### macOS-specific JAVA_HOME

The project's standing rule (from MEMORY): macOS dev sets `JAVA_HOME` to the Homebrew-installed OpenJDK 21 before every Maven command.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

If `mvn -v` ever shows `Runtime: ... 17` or `... 23`, you forgot to export this. Re-export and retry. The CLAUDE.md MUST-follow patterns list this constraint explicitly.

## Bring up Postgres and Redis

There is no integrated dev `docker-compose.yml` for app dependencies. Two options:

### Option A — single-command Docker

```bash
docker run -d --name executespec-pg \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_DB=executespec \
  -p 5432:5432 \
  postgres:16

docker run -d --name executespec-redis \
  -p 6379:6379 \
  redis:7-alpine
```

Stop / start with `docker stop executespec-pg executespec-redis` and `docker start ...`. Drop the data with `docker rm -f executespec-pg && docker volume prune`.

### Option B — Homebrew (macOS)

```bash
brew install postgresql@16 redis
brew services start postgresql@16
brew services start redis

# Create the DB if this is a fresh install
createdb executespec
```

The defaults in `src/main/resources/application.yml` already match these settings (`localhost:5432`, db `executespec`, user `postgres`, password `postgres`; Redis at `localhost:6379`).

Verify both are up:

```bash
psql -h localhost -U postgres -d executespec -c 'select 1;'
redis-cli ping
```

## Boot the backend

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# One-time: compile + run unit tests + check formatting
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q

# Then run the app
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local
```

`local` profile is REQUIRED. The base `application.yml` has the OTel tracer pointed at `http://localhost:4318/v1/traces`; when no Tempo is running, the JVM retries every 5–30s indefinitely and (worst case) eats off-heap memory. The `local` profile in `application.yml` (line 851+) and `application-local.yml` both disable tracing + OTLP metrics. See the comment block at the bottom of `application.yml` for the full story.

On first boot Flyway applies every migration up to **V199** (latest). Subsequent boots only apply pending migrations. The Hibernate setting `ddl-auto: validate` means schema mismatches fail boot.

### Health probes

```bash
# Custom HealthController — liveness, cached 30s
curl http://localhost:8080/api/health

# Custom HealthController — readiness, inline DB check
curl http://localhost:8080/api/health/ready

# Spring Actuator (used by CI deploy gate)
curl http://localhost:8080/actuator/health
```

A successful `/api/health` returns `status: UP` and includes `db: UP`, `redis: UP`, plus run counters (active / queued / total / completed / failed). If either DB or Redis is `DOWN`, the status flips to `DEGRADED` — the JVM is alive but a downstream is unreachable.

`/api/health/ready` returns HTTP 503 when DB is unreachable (`HealthController.ready()` runs an inline `Connection.isValid(2)` against the datasource).

### Local-only LLM API keys

`application-local.yml` ships with placeholder Anthropic / OpenAI / Gemini keys. If you want to actually run a pipeline locally:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
export OPENAI_API_KEY=sk-...
export GOOGLE_API_KEY=AIza...
```

These take precedence over the local-profile defaults (`spring.ai.{anthropic,openai,google}.api-key`).

## Boot the frontend

```bash
npm --prefix frontend install   # first time only
npm --prefix frontend run dev
```

Vite dev server on **5173** (`frontend/vite.config.ts`). Proxies `/api/**` → `http://localhost:8080` so the SPA can hit the local backend without CORS pain.

Override the port and/or the proxy target:

```bash
VITE_PORT=5174 npm --prefix frontend run dev
VITE_BACKEND_URL=http://localhost:8081 npm --prefix frontend run dev
```

Open `http://localhost:5173/` in a browser. Use the local creds from `~/Desktop/local_creds.txt` (per MEMORY rule `feedback_local_creds_file.md`); do not register a fresh user unless you specifically want to test the registration flow.

## Run the test suite

```bash
# All unit tests (excludes JUnit groups: e2e, integration, chaos)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
```

`pom.xml` configures `maven-surefire-plugin` with `<excludedGroups>e2e,integration,chaos</excludedGroups>`. The full count is roughly 1295 tests at the time of this writing (cross-referenced in `pom.xml` comments around line 14). CI runs the same default via `mvn verify -q`.

The suite includes ArchUnit guards:

- `AccessGuardCoverageTest` — pins every controller method touching tenant-scoped paths to call a tenant access guard.
- `ApiContractTest` — pins every URL in `frontend/src/lib/api.ts` to a registered backend `@*Mapping`.

A failing ArchUnit test typically means a controller-level guard was missed or a frontend URL doesn't resolve.

### Run a single test class

```bash
mvn test -Dtest=AccessGuardCoverageTest
mvn test -Dtest=ProjectMemoryProviderTest#buildPlannerContext_emptyProject_emitsZeroSnapshot
```

### Run with the spotless gate

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q
```

`verify` triggers the `spotless:check` execution bound to that phase in `pom.xml`. Any drift from `googleJavaFormat` fails the build with a non-zero exit. CI runs exactly this.

### Apply formatting before commit

```bash
# Reformat Java source in place
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn com.diffplug.spotless:spotless-maven-plugin:apply

# Verify the result is clean
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn com.diffplug.spotless:spotless-maven-plugin:check
```

Spotless version is pinned in `pom.xml` (currently 3.4.0) and uses `googleJavaFormat 1.22.0` in `GOOGLE` style with `reflowLongStrings: true`, `removeUnusedImports`, and `importOrder: java,javax,jakarta,com,org,`.

### Run an integration / e2e test on purpose

```bash
# JUnit groups are tag-based; pass them explicitly
mvn test -Dgroups=integration
mvn test -Dgroups=e2e
mvn test -Dgroups=chaos
```

These usually need Docker + a live DB + real API keys. They are not part of CI's `mvn verify` default.

### JaCoCo coverage report

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test
open target/site/jacoco/index.html
```

DTOs and the application entrypoint are excluded from the headline number (`pom.xml` `jacoco-maven-plugin` configuration).

## Frontend lint, format, build

```bash
# Lint (eslint)
npm --prefix frontend run lint

# Auto-fix lint errors
npm --prefix frontend run lint:fix

# Prettier check (CI gate)
npm --prefix frontend run format:check

# Prettier apply
npm --prefix frontend run format

# Production build (outputs to ../src/main/resources/static/)
npm --prefix frontend run build
```

`lint` runs eslint with `--max-warnings 9999` — warnings exist in the codebase today but are tolerated; new errors will fail CI.

`format:check` is what CI runs first in `.github/workflows/lint.yml`. If it fails, run `format`, commit the result, push again.

`build` runs `tsc && vite build`. The TypeScript compile is part of the build step, so type errors fail the build.

## Run a real pipeline locally

After the backend is up and you've set the LLM API keys:

```bash
# 1. Authenticate (uses local creds from ~/Desktop/local_creds.txt per MEMORY rule)
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "<email>" --arg p "<password>" '{email:$e,password:$p}')" \
  | jq -r '.accessToken')

# 2. Submit a 1-credit spec in PROTOTYPE mode (skips plan gate)
curl -s -X POST http://localhost:8080/api/runs \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"requirement":"Build me a hello-world endpoint at /hello returning text/plain", "executionMode":"PROTOTYPE", "aiProvider":"ANTHROPIC"}'

# 3. Poll status
RUN_ID=<from previous response>
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/runs/$RUN_ID | jq '.status, .verifiedScore'
```

See `scripts/e2e-test.sh` for a fuller harness; `scripts/v2-eval.sh` for the 3 × 3 evaluation matrix.

### V2 eval matrix

`scripts/v2-eval.sh` submits one spec per `{low, medium, high}` complexity band × `{anthropic, openai, gemini}` provider in PROTOTYPE mode and writes a markdown + JSON report.

```bash
# Run the full 9-way matrix
EMAIL=<admin email> PASSWORD=<admin password> ./scripts/v2-eval.sh

# Subset
./scripts/v2-eval.sh --providers anthropic --bands low,medium
./scripts/v2-eval.sh --concurrency 3 --timeout 900
```

Output lands in `scripts/v2-eval-results/v2-eval-<timestamp>.{md,json}` plus per-run raw payloads. Requires the backend at `BASE_URL` (default `http://localhost:8080`) and login creds (`EMAIL` / `PASSWORD` env vars). PROTOTYPE bypasses the plan approval gate so the runs flow through unattended.

## Local observability stack

```bash
cd observability
docker compose up -d
```

| Service     | URL                              | Notes                                             |
| ----------- | -------------------------------- | ------------------------------------------------- |
| Grafana     | http://localhost:3001            | Login: `admin` / `admin` (anonymous Viewer also enabled) |
| Prometheus  | http://localhost:9090            | Scrapes `host.docker.internal:8080/actuator/prometheus` every 15s |
| Tempo       | http://localhost:3200            | OTLP gRPC on 4317, HTTP on 4318                  |

Dashboards: `ExecuteSpec — Overview` and `JVM / HTTP Overview`, provisioned from `observability/grafana/dashboards/`.

To actually ship traces from the local JVM, you need to compose the `local` profile with one that re-enables OTLP. The simplest path:

```bash
# Re-enable OTLP export by NOT using the local profile (or compose another profile)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
```

Boot without any profile and the base `application.yml` defaults take over — OTLP tracing is enabled and points at `localhost:4318`. Metrics export to OTLP is disabled at the base level (`management.otlp.metrics.export.enabled: false`); Prometheus scrape via `/actuator/prometheus` is the active path.

Stop the stack:

```bash
cd observability
docker compose down
```

## Common debugging recipes

### Inspect the dependency tree

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn dependency:tree
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn dependency:tree -Dincludes=org.springframework.ai
```

### Stream the backend log

When booting via `mvn spring-boot:run`, logs print to the terminal directly. When running the packaged JAR:

```bash
# Boot the packaged JAR
java -jar target/executespec-1.0.0-M1.jar --spring.profiles.active=local > /tmp/executespec.log 2>&1 &
tail -f /tmp/executespec.log
```

Structured JSON logging (via `logstash-logback-encoder` from `pom.xml`) is wired so traceId / spanId propagate. Greppable by `traceId`.

### Common 500 root causes

| Symptom in log                                    | Likely cause                                                                            |
| ------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `FlywayMigrateException` / `out of order`         | Migrations applied in an unexpected order. The `dev` profile enables `out-of-order: true`; `local` does not. Either switch profiles or apply migrations manually with `flyway repair` against a dev DB |
| `Schema-validation: missing column ...`           | Hibernate `ddl-auto: validate` caught a drift between entity and DB. A migration is missing or has not run                                                                                            |
| `Failed to initialize HikariDataSource`           | Postgres not running on `localhost:5432`, or the password is wrong                                                                                                                                       |
| `RedisCommandTimeoutException`                    | Redis not running, or `REDIS_HOST`/`REDIS_PORT` set to a stale value                                                                                                                                     |
| `OAuth2AuthenticationException: redirect_uri ...` | `application.yml` redirect URIs don't match the OAuth client config in Google / GitHub Console — check `spring.security.oauth2.client.registration.*.redirect-uri`                                       |
| `JwtException` / `IllegalStateException: JWT secret`  | `app.jwt.secret` shorter than 256 bits in prod. `JwtSecretValidator` enforces this under `@Profile("prod")`                                                                                          |
| `ApiContractTest` fails                            | A URL in `frontend/src/lib/api.ts` doesn't match a registered backend mapping. Re-run `mvn test -Dtest=ApiContractTest` for the offending URL                                                            |
| `AccessGuardCoverageTest` fails                    | A new controller method touches `/runs/{`, `/projects/{`, `/executions/{`, `/launches/{`, or `/repos/{` without calling a tenant access guard. Either wire the guard or add to `KNOWN_LEGACY_DRIFT`     |

### Reset the local DB

`scripts/dev-cleanup.sh` is parameterised on DB host / user / port; against a local `localhost:5432` it wipes run data and resets usage counters while preserving users, orgs, projects, and billing config.

```bash
DB_HOST=localhost DB_PORT=5432 DB_NAME=executespec DB_USER=postgres ./scripts/dev-cleanup.sh
```

For a harder reset (drop projects too):

```bash
./scripts/dev-wipe-runs-and-projects.sh
```

The bare nuclear option:

```bash
docker rm -f executespec-pg && docker volume prune -f
# then re-create per Option A above
```

### Provider connectivity smoke

```bash
./scripts/test-providers.sh
```

Pings each LLM provider with a trivial completion to verify keys + reachability.

### Resilience harness

```bash
./scripts/test-resilience.sh
```

Drives the retry / fallback / circuit-breaker paths.

## Pre-push checklist

Run these in order, fix any output, then push. From MEMORY rule `feedback_test_locally_first.md`: **before every push to `dev`, run the change locally; CI is not authoritative**.

```bash
# 1. Format Java
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn com.diffplug.spotless:spotless-maven-plugin:apply

# 2. Format frontend
npm --prefix frontend run format

# 3. Full build (verifies spotless + runs unit tests + builds JAR)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q

# 4. Frontend lint + build
npm --prefix frontend run lint
npm --prefix frontend run build

# 5. Smoke-boot the packaged JAR
java -jar target/executespec-1.0.0-M1.jar --spring.profiles.active=local
# (Ctrl-C once you see "Started ExecutespecApplication")

# 6. Hit the health endpoint
curl -fsS http://localhost:8080/api/health | jq '.status, .db, .redis'
```

Only push when all six steps are green.

## Optional: GitHub PR review locally

If you're adding a feature touching the autofix surfaces or the PR Review pipeline, you can stand up a temporary repo to exercise the path:

```bash
# Create a throwaway repo under your GH account
gh repo create executespec-smoke-$RANDOM --public --add-readme
```

Set `GITHUB_PAT` (with `repo` scope) before booting the backend so the run can clone + push.

## Optional: Caddy reverse proxy

If you want to mirror the production routing pattern locally for HTTPS testing, `infra/scripts/startup.sh` (phase 11) shows the Caddy JSON config used in prod. Adapting it for local needs a self-signed cert and `caddy run --config caddy.json`.

In the common case, just hit `http://localhost:8080` directly.
