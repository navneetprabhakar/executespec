# ExecuteSpec Deployment

Production + development environments are GCP-hosted in region `us-east4`. A single GCE VM serves each environment for the long-running Spring Boot JAR; Cloud Run is used for on-demand preview containers. CI/CD is GitHub Actions; auth to GCP is Workload Identity Federation (no JSON keys).

This document is code-truth: every claim cross-links the source file. If you change the deploy pipeline, update this file in the same commit.

## Topology

| Concern              | Where                                                                                                     |
| -------------------- | --------------------------------------------------------------------------------------------------------- |
| Backend JVM (prod)   | GCE VM `executespec-prod-vm` in `us-east4-a` — see `infra/environments/prod.tfvars`                       |
| Backend JVM (dev)    | GCE VM `executespec-dev-vm` in `us-east4-b` — see `infra/environments/dev.tfvars`                         |
| Domain (prod)        | `https://app.executespec.ai` (Cloudflare proxied) — set in `.github/workflows/deploy.yml`                 |
| Domain (dev)         | `https://dev.executespec.ai` (Cloudflare proxied) — set in `.github/workflows/deploy.yml`                 |
| Database             | Cloud SQL PostgreSQL — `infra/main.tf` module `database`                                                  |
| Cache                | Memorystore Redis — `infra/main.tf` module `cache`                                                        |
| Object store         | GCS — `infra/main.tf` module `storage`                                                                    |
| Preview containers   | Cloud Run (per-run, TTL configured in `application.yml preview.preview-ttl-minutes`)                      |
| Preview reverse-prox | Caddy on the GCE VM with Cloudflare Origin Certificate — `infra/scripts/startup.sh` phase 11              |
| Secrets at runtime   | GCP Secret Manager (primary) + GCS `config/api-keys` fallback — `infra/scripts/startup.sh` phase 6c       |
| Frontend bundle      | Compiled into the JAR at `src/main/resources/static/` (Vite `outDir`) — `frontend/vite.config.ts`         |
| Observability        | Local: Prometheus + Tempo + Grafana (`observability/docker-compose.yml`). Prod: GCP Cloud Trace + Monitoring (Spring profile `gcp` in `application.yml`) |

The prod surface is gated: `.github/workflows/deploy.yml` lines 7-22 only trigger on `push: branches: [dev]` and a `workflow_dispatch` whose `target_env` is hard-coded to a single option (`dev`). Production credentials are not yet provisioned; the workflow header comments explain the gate.

## CI/CD pipeline (`.github/workflows/deploy.yml`)

Single workflow `Build & Deploy`, single job `build-and-deploy`. Runs on `ubuntu-latest`. Permissions: `contents: read`, `id-token: write` (the second is required for Workload Identity Federation).

### Trigger

| Event                | Effect                                                                              |
| -------------------- | ----------------------------------------------------------------------------------- |
| `push` to `dev`      | Deploys to dev. `DEPLOY_ENV=dev`, `TARGET_SHA=github.sha`                           |
| `workflow_dispatch`  | Manual run, only `target_env=dev` option exposed; optional `target_sha` for rollback |

### Job steps (in order)

1. **Checkout** (`actions/checkout@v6`) at `TARGET_SHA` with `fetch-depth: 0` so commit messages are available for the Slack notification step.
2. **Set up Java 21** (`actions/setup-java@v5`, Temurin distribution, Maven cache).
3. **Set up Node.js** (`actions/setup-node@v6`, LTS, npm cache keyed on `frontend/package-lock.json`).
4. **Install frontend dependencies** — `cd frontend && npm ci`.
5. **Frontend format check** — `cd frontend && npm run format:check` (prettier).
6. **Frontend lint** — `cd frontend && npm run lint` (eslint, `--max-warnings 9999`).
7. **Build frontend** — `cd frontend && npm run build`. Output goes to `src/main/resources/static/` (see `frontend/vite.config.ts` `build.outDir`).
8. **Build JAR + run unit tests** — `mvn verify -q`. Surefire excludes groups `e2e,integration,chaos` (see `pom.xml` plugin config). The `verify` phase runs the spotless `check` execution wired in `pom.xml` so any drift from `google-java-format` fails CI here.
9. **Authenticate to GCP** (`google-github-actions/auth@v3`) — uses `secrets.GCP_WORKLOAD_IDENTITY_PROVIDER` + `secrets.GCP_SERVICE_ACCOUNT`. No JSON key.
10. **Set up gcloud CLI** (`google-github-actions/setup-gcloud@v3`).
11. **Debug — print resolved config** — echoes `GCP_PROJECT`, `GCE_ZONE`, `GCP_REGION`, `DEPLOY_ENV`; lists compute instances filtered by `name~executespec`.
12. **Set deploy bucket** — derives `DEPLOY_BUCKET=gs://executespec-${DEPLOY_ENV}-deploy` and exports to `$GITHUB_ENV`.
13. **Upload JAR to GCS** — picks `target/*.jar`, uploads to `gs://${DEPLOY_BUCKET}/releases/app-${TARGET_SHA}.jar`. Writes the SHA to `gs://${DEPLOY_BUCKET}/CURRENT_SHA`. Releases are keyed by commit SHA and never overwritten, so rollback = update `CURRENT_SHA` to a prior value.
14. **Set instance name** — `executespec-prod-vm` if `DEPLOY_ENV=prod` else `executespec-dev-vm`.
15. **Ensure validator toolchains on GCE** — SSHes over IAP and idempotently installs Node LTS + Python 3 + `mypy ruff pytest`. Needed because the code validator (`validator/CodeValidatorImpl.java`) shells out to `npx`/`tsc`/`eslint`/`mypy`/`ruff`/`pytest` on the VM.
16. **Set public URL** — `https://app.executespec.ai` for prod, `https://dev.executespec.ai` for dev. Exported as `APP_PUBLIC_URL`.
17. **Sync design system files** (`continue-on-error: true`) — `gsutil rsync` of `gs://${DEPLOY_BUCKET}/designs/design-md` into `/opt/executespec/designs/design-md` on the VM.
18. **Write env patch script** — generates `/tmp/env-patch.sh`. Upserts settings into `/opt/app/executespec.env`: `CORS_ALLOWED_ORIGINS`, `DASHBOARD_URL`, `APP_BASE_URL`, `MAIL_TRANSPORT=smtp`, `MAIL_FROM`, the preview-engine handoff vars (`PREVIEW_ENGINE_URL`, `PREVIEW_ENGINE_REGISTRY_URL`, `PREVIEW_ENGINE_ROUTE_VIA`, `PREVIEW_MODE`). Then pulls `gs://${DEPLOY_BUCKET}/config/api-keys` and `gs://${DEPLOY_BUCKET}/config/smtp-credentials` (if present) and upserts each `KEY=VALUE` line.
19. **Deploy JAR to GCE** — SCPs the patch script to the VM, SSHes in, downloads the new JAR via `sudo gsutil cp` into `/opt/app/releases/`, runs the env patch, symlinks `/opt/app/app.jar` to the new JAR, writes the SHA into `/opt/app/RUNNING_SHA`, then `sudo systemctl restart executespec`.
20. **Wait for app startup** — local `sleep 30`.
21. **Health check** — SSHes in and curls `http://localhost:8080/actuator/health` up to 8 times with 10s spacing. The wrapper deletes `/tmp/health.json` before every probe to avoid the buffer-reuse false-positive that caused the V113 silent boot crash (header comments on the step document the bug). Success = HTTP 200 AND body matches `"status":"(UP|DEGRADED)"`.
22. **Dump app logs on failure** — `if: failure()` — pulls `systemctl status executespec`, last 150 lines of `journalctl -u executespec`, and the env file with values redacted.
23. **Prune old Docker images** (`if: success()`, `continue-on-error: true`) — `docker image prune -a --force --filter 'until=168h'` + `docker builder prune --force --filter 'until=168h'`. Runs on the VM.
24. **Prune old JAR releases** — `gsutil ls` everything under `releases/`, keep the current SHA + the most recent 10. The current JAR is explicitly excluded from the prune list to guarantee it survives.
25. **Deployment summary** — echoes target SHA, instance, bucket path, environment.
26. **Notify Slack (success)** / **Notify Slack (failure)** — webhook payload built with `jq`, posted to `secrets.SLACK_WEBHOOK_URL`. Skipped if the secret is unset.

### Secrets referenced

Every `${{ secrets.X }}` reference in `.github/workflows/deploy.yml`:

| Secret                            | Used at line(s) | Purpose                                                        |
| --------------------------------- | --------------- | -------------------------------------------------------------- |
| `GCP_PROJECT`                     | 39              | Target GCP project for `gcloud` + `gsutil`                    |
| `GCP_REGION`                      | 40              | Region (`us-east4`) for `gcloud` defaults                      |
| `GCE_ZONE`                        | 41              | Zone for the target GCE VM                                     |
| `GCP_WORKLOAD_IDENTITY_PROVIDER`  | 106             | WIF provider for keyless auth                                  |
| `GCP_SERVICE_ACCOUNT`             | 107             | Service account impersonated via WIF                           |
| `SLACK_WEBHOOK_URL`               | 430, 458        | Incoming-webhook URL for the deploy-channel notifications      |

Application secrets (LLM keys, GitHub PAT, Razorpay, Stripe, SMTP, OAuth) are NOT injected by the workflow; they are loaded at runtime by the JVM from GCP Secret Manager (primary) or from `gs://${DEPLOY_BUCKET}/config/api-keys` (fallback). See `infra/scripts/startup.sh` phase 6c and `infra/scripts/seed-secrets.sh`.

### Workload Identity Federation

The workflow uses `permissions.id-token: write` to mint an OIDC token, then `google-github-actions/auth@v3` exchanges it for a short-lived GCP access token via the configured WIF provider. The job assumes the service account via impersonation; no service-account key file is stored in GitHub.

### Lint workflow (`.github/workflows/lint.yml`)

Separate workflow that runs on `pull_request: branches: [dev, main]` and `workflow_dispatch`. Three jobs:

| Job              | Steps                                                                                |
| ---------------- | ------------------------------------------------------------------------------------ |
| `java-lint`      | `mvn -B spotless:check` (fails on any drift from Google Java Format)                |
| `frontend-lint`  | `npm ci` → `npm run format:check` (prettier) → `npm run lint` (eslint)              |
| `frontend-audit` | `npm audit --audit-level=high` — warn-only (`continue-on-error: true`)              |

`frontend-audit` is intentionally warn-only at present; the comment in the workflow file (lines 84-106) explains why and how to flip it to enforcing.

## Build artifacts

### Backend JAR

- `mvn verify -q` produces a fat Spring Boot JAR at `target/executespec-*.jar` (artifact ID `executespec`, version `1.0.0-M1` from `pom.xml`).
- The Spring Boot Maven plugin builds the executable JAR. Lombok is excluded from the packaged artifact.
- JaCoCo is wired (`pom.xml` plugin `jacoco-maven-plugin`); `prepare-agent` runs at `test` phase, `report` at `test`. Excludes: DTO classes + the application entrypoint. Reports land in `target/site/jacoco/`.
- Surefire excludes JUnit groups `e2e,integration,chaos`; only unit tests run during default `mvn verify`. CI runs the same default.

### Frontend bundle

- `npm --prefix frontend run build` runs `tsc && vite build` (`frontend/package.json`).
- Vite `build.outDir` is `../src/main/resources/static` (`frontend/vite.config.ts`), so the bundle is dropped directly into the Spring Boot static path and gets packaged into the JAR.
- `emptyOutDir: true` is set; the static dir is wiped each build.

### Combined deliverable

A single JAR contains the compiled backend + the prebuilt frontend bundle. The deploy step uploads exactly one artifact to GCS. The GCE VM serves both the API (`/api/**`) and the static frontend (everything else) from the same JVM process.

## GCE VM bootstrap (`infra/scripts/startup.sh`)

The startup script is attached to the GCE instance and runs on first boot AND every reboot. It is idempotent. Eleven phases:

| Phase | Action                                                                                                       |
| ----- | ------------------------------------------------------------------------------------------------------------ |
| 1     | Install OpenJDK 21 + Maven                                                                                    |
| 2     | Install Docker CE + `docker-compose-plugin`; enable + start `docker.service`                                |
| 3     | Install Node.js LTS via NodeSource                                                                            |
| 4     | Install Python 3, pip, and `mypy ruff pytest` globally (used by the code validator on the host)              |
| 5     | Ensure `git`, `gsutil`, `gcloud` are present                                                                  |
| 5b    | `gcloud auth configure-docker` for the Artifact Registry host so the VM can push preview images              |
| 6     | Read GCE metadata attributes → write `/opt/app/executespec.env` (mode 600). DB / Redis / bucket / region come from metadata; SMTP creds + LLM API keys come from `gs://${DEPLOY_BUCKET}/config/*` files (fallback path) |
| 6c    | LLM API keys fallback: pulls `gs://${DEPLOY_BUCKET}/config/api-keys` and exports `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GOOGLE_API_KEY`, `GITHUB_PAT`. The primary path is GCP Secret Manager via `SecretManagerConfigLoader` at JVM startup |
| 6d    | Derive `APP_PUBLIC_URL` from `ES_ENV` metadata; set `CORS_ALLOWED_ORIGINS` (`https://app.executespec.ai,https://dev.executespec.ai,http://localhost:5173`); set `SPRING_PROFILES_ACTIVE=${ES_ENV},gcp`; set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`; set `JAVA_OPTS="-Xms4g -Xmx12g -XX:+UseG1GC"` |
| 7     | Write `/etc/systemd/system/executespec.service` (Type=simple, EnvironmentFile, Restart=always, RestartSec=30, StartLimitBurst=5, LimitNOFILE=65535)                                              |
| 8     | Read `gs://${DEPLOY_BUCKET}/CURRENT_SHA`, download the matching JAR, symlink `/opt/app/app.jar` to it. Falls back to "most recent JAR by lex sort" if the CURRENT_SHA JAR has been pruned |
| 8b    | `gsutil rsync` design system files into `/opt/executespec/designs/design-md`                                |
| 9     | `systemctl restart executespec` (only if a JAR exists)                                                       |
| 10    | Install `/etc/cron.daily/docker-cleanup` (24h-idle Docker prune)                                             |
| 11    | Install Caddy reverse proxy with a pre-shipped `/etc/caddy/config.json` (terminates TLS using a Cloudflare Origin Certificate at `/etc/caddy/origin.pem` + `/etc/caddy/origin-key.pem`). Routes `dev.executespec.ai` → `localhost:8080`. Preview routes are added dynamically via the Caddy admin API at `localhost:2019` |

The systemd unit runs `java $JAVA_OPTS -jar /opt/app/app.jar` as root with `EnvironmentFile=/opt/app/executespec.env`. Logs go to journald.

## Spring profiles

Profile-specific YAML files live in `src/main/resources/`:

| File                      | Activated by                              | Notes                                                                                      |
| ------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------------ |
| `application.yml`         | Base; always loaded                       | Hardcoded local defaults (Postgres at `localhost:5432`, Redis at `localhost:6379`). Contains the model matrix, agent config, observability config |
| `application-local.yml`   | `--spring.profiles.active=local`           | Local dev — silences OTel exporter (no collector), provides convenience defaults for credentials. NOT for production use |
| `application-dev.yml`     | `--spring.profiles.active=dev`             | Dev VM. Resolves DB/Redis/secrets from env vars. Excludes `OtlpAutoConfiguration` since the dev VM has no collector |
| `application-prod.yml`    | `--spring.profiles.active=prod`            | Prod VM. Stricter `agent.concurrency.max-runs=20`. `server.forward-headers-strategy=framework` for LB TLS termination. Requires `PREVIEW_ENGINE_REGISTRY_URL` + `PREVIEW_ENGINE_API_KEY` (no defaults) |
| `application.yml` (`gcp` section, line 880+) | Composed with `dev`/`prod` via `SPRING_PROFILES_ACTIVE=${ES_ENV},gcp` | Activates virtual threads, disables local OTLP exporters in favour of GCP Cloud Trace + Cloud Monitoring (`exporter-trace`, `exporter-metrics` from `pom.xml`) |

`@Profile` annotations in the codebase:

| Class                                    | Profile  | What it does                                                          |
| ---------------------------------------- | -------- | --------------------------------------------------------------------- |
| `config/GcpOtelExporterConfig.java`      | `gcp`    | Wires GCP Cloud Trace + Cloud Monitoring exporters                     |
| `auth/security/JwtSecretValidator.java`  | `prod`   | Fails fast at boot if `app.jwt.secret` is still the default value     |

## Database setup

Flyway is enabled (`application.yml` `spring.flyway`). Migrations live in `src/main/resources/db/migration/V*.sql`. Current head: **V199** (`V199__sync_org_tier_with_subscription_tier.sql`). Migrations run on application startup before Hibernate validates the schema (`spring.jpa.hibernate.ddl-auto: validate`).

`pom.xml` pulls in `flyway-core` + `flyway-database-postgresql`. The latter is required for Spring Boot 3.5 + Flyway 11.x against PostgreSQL.

Two boot-time behaviours documented in `application.yml`:

- `baseline-on-migrate: true` and `baseline-version: 0` — safe on existing databases.
- `ignore-migration-patterns: "*:missing"` — tolerates the V119 orphan left when `preview-engine` was extracted to its own repo. The comment block (lines 81-116) explains the workaround.

The dev profile additionally sets `spring.flyway.out-of-order: true` (`application-dev.yml`) so out-of-order V68 from the v2 branch can apply on a dev DB that already saw later versions.

Hibernate is configured for `DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` and `open-in-view: false`. JDBC connections are released to Hikari at every transaction commit.

## Cleanroom containers (SWE-bench + ProgramBench)

Bench-mode runs use Docker for isolation:

| Adapter                                                | Launch command                                                                                                                                                |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `swebench/SwebenchTaskService.java` (line 142)         | `docker run -d --rm --network none --name <runId> -v <workspaceHost>:/testbed -w /testbed <image> sleep`                                                       |
| `programbench/ProgramBenchTaskService.java` (line 167) | `docker run -d --rm --network none --name <runId> -v <workspaceHost>:/workspace -w /workspace <image> sleep`                                                  |

After the container starts, the adapter registers the container ID with `ProgramBenchContainerStore`. Subsequent `ShellTool` and `BuildProjectTool` invocations consult the store and dispatch via `docker exec` instead of running directly on the host. `--network none` blocks all egress for the duration of the run. `--rm` ensures the container is removed on stop.

Cleanroom invocations only fire when the run is bench-mode (SWE-bench or ProgramBench adapters); standard customer runs use the host shell directly with the `ShellSandboxPolicy` allowlist (see `agent/shell/`).

## Preview container lifecycle

`application.yml` `preview.*` and `application-prod.yml` `preview.*`. Three possible modes (`PREVIEW_MODE` env var):

| Mode        | Where it runs                                                                                                            |
| ----------- | ------------------------------------------------------------------------------------------------------------------------ |
| `docker`    | Docker Compose on the main GCE VM (default in dev). Caddy on port 443 routes `preview-<id>.executespec.ai` → container  |
| `cloud-run` | Cloud Run service per preview. Managed HTTPS                                                                              |
| `vm`        | Out-of-band preview-gateway VM (`preview-gw` in `us-east4-b`). Configured in `application.yml` `preview.vm.*`            |

Prod composes `mode=docker` with `engine.route-via=true`: the main JVM builds the preview image, pushes it to `us-east4-docker.pkg.dev/executespecv1/preview-images`, and hands off to the external `preview-engine` service at `https://preview-engine.executespec.ai`. The engine then pulls the image and owns the container's lifetime. `PREVIEW_ENGINE_API_KEY` is the shared secret. Disable by setting `PREVIEW_ENGINE_ROUTE_VIA=false`.

Default `preview-ttl-minutes: 30` (`application.yml`). The scheduled job `PreviewGarbageCollector` (`deployment/preview/vm/PreviewGarbageCollector.java`, `@Scheduled(fixedRate = 600_000)`) reaps expired previews every 10 minutes.

## Manual deploy escape hatch

There is no manual `gcloud` deploy script in `scripts/`. Manual deploys are done via the `workflow_dispatch` trigger on `.github/workflows/deploy.yml` with an explicit `target_sha`.

Rollback is performed by running `workflow_dispatch` with `target_sha` set to a prior commit. The previous JAR will already be in GCS at `gs://${DEPLOY_BUCKET}/releases/app-<sha>.jar` (last 10 retained by the prune step), so the deploy step downloads it directly and the systemd symlink flips to point at it.

Operational scripts under `scripts/` are for testing and local cleanup, not deploy:

| Script                       | Purpose                                                          |
| ---------------------------- | ---------------------------------------------------------------- |
| `dev-cleanup.sh`             | Wipes run data + resets usage on a dev VM (preserves users/orgs) |
| `dev-wipe-runs-and-projects.sh` | More aggressive wipe (project tables included)                |
| `e2e-test.sh`                | E2E provider matrix run via the API                              |
| `e2e-matrix-test.sh`         | Multi-spec matrix variant                                        |
| `e2e-preview-test.sh`        | Preview-engine smoke against deployed environment                |
| `v2-eval.sh`                 | 3 × 3 (provider × complexity band) evaluation harness            |
| `v2-eval-strict.sh`          | Stricter variant of `v2-eval.sh`                                 |
| `test-providers.sh`          | Per-provider connectivity smoke                                  |
| `test-resilience.sh`         | Chaos / resilience harness                                        |

## Local development

Backend:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile is required to silence the OTel exporter loop when no Tempo collector is running (see the comment block at the bottom of `application.yml`, lines 851-877).

Backend listens on **8080**. Health probe: `curl http://localhost:8080/api/health` (custom `HealthController`, not actuator) or `curl http://localhost:8080/actuator/health` (Spring actuator).

Frontend:

```bash
npm --prefix frontend run dev
```

Vite dev server listens on **5173** (overridable via `VITE_PORT`) and proxies `/api` → `http://localhost:8080` (overridable via `VITE_BACKEND_URL`). See `frontend/vite.config.ts`.

Postgres and Redis are assumed at localhost on default ports. To run them via Docker, the simplest path is `cd observability && docker compose up -d` (which only starts the observability stack), then `docker run -d --name pg -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:16` and `docker run -d --name redis -p 6379:6379 redis:7-alpine` separately. There is no integrated dev docker-compose for the app dependencies yet — see `docs/operations/local-test-playbook.md`.

## Environment variables

Variables read by the application via `@Value("${...}")` or by `application.yml` substitution. Required vs optional is determined by the presence of a default (`${VAR:default}`) or the criticality of the surface.

### Required at runtime (prod / dev VM)

| Variable                        | Read by                                                                  | Purpose                                          |
| ------------------------------- | ------------------------------------------------------------------------ | ------------------------------------------------ |
| `ANTHROPIC_API_KEY`             | `application*.yml` → `spring.ai.anthropic.api-key`                       | Claude API. Without it the LLM router falls over |
| `OPENAI_API_KEY`                | `application*.yml` → `spring.ai.openai.api-key`                          | OpenAI + embeddings                              |
| `GOOGLE_API_KEY`                | `application*.yml` → `spring.ai.google.genai.api-key`                    | Gemini                                            |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `application-{dev,prod}.yml` → `spring.datasource.*` | Cloud SQL connection                              |
| `REDIS_HOST` / `REDIS_PORT`     | `application-{dev,prod}.yml` → `spring.data.redis.*`                     | Memorystore connection                            |
| `JWT_SECRET`                    | `app.jwt.secret` (validated by `JwtSecretValidator` under `@Profile("prod")`) | JWT signing — must be ≥256 bits in prod    |

### Required for specific surfaces

| Variable                                                                  | Surface that needs it                                                           |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `GITHUB_PAT` / `GITHUB_OWNER`                                             | Multi-repo runs, PR Review autofix, code review                                  |
| `SMTP_USERNAME` / `SMTP_PASSWORD` (and `SMTP_HOST`/`SMTP_PORT` if overriding) | Outbound transactional + admin email                                          |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` / `RAZORPAY_WEBHOOK_SECRET`     | INR billing path                                                                 |
| `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` / `STRIPE_WEBHOOK_SECRET` / `STRIPE_PRO_PRICE_ID` / `STRIPE_ENTERPRISE_PRICE_ID` / `STRIPE_TEAM_PRICE_ID` / `STRIPE_TEAM_PRICE_ID_INR` | USD billing (default in prod) |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`                   | OAuth2 login via Google                                                          |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET`                   | OAuth2 login via GitHub                                                          |
| `SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` / `SLACK_SIGNING_SECRET`        | Slack integration                                                                |
| `PREVIEW_ENGINE_REGISTRY_URL` / `PREVIEW_ENGINE_API_KEY`                  | Required in prod when `engine.route-via=true` (no defaults in `application-prod.yml`) |
| `JIRA_WEBHOOK_SECRET`                                                     | Jira integration webhook HMAC verification                                        |
| `POSTHOG_API_KEY` / `POSTHOG_HOST`                                        | Product analytics (no-op when blank — `application.yml` `analytics.posthog`)    |
| `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_SAMPLING_PROBABILITY`               | Custom OTel collector / sampling override                                         |
| `GCP_PROJECT` / `GCP_REGION` / `AR_REPOSITORY` / `AR_HOST`                | Cloud Run preview deploys + Artifact Registry pushes (`agent.deployment.*`)     |
| `GCS_WORKSPACE_BUCKET` / `DEPLOY_BUCKET`                                  | GCS workspace archival + deploy artefacts                                         |
| `CRYPTO_LOCAL_KEY`                                                        | Local-dev override of GCP-Secret-Manager-backed crypto key (`executespec.crypto.local-key`) |
| `ADMIN_NOTIFICATION_EMAIL`                                                | Override the destination address for admin alerts                                 |
| `ADMIN_REMINDER_TOKEN`                                                    | Enables `POST /api/internal/reminder/send` (blank disables endpoint)              |
| `CONTACT_JIRA_BASE_URL` / `CONTACT_JIRA_EMAIL` / `CONTACT_JIRA_API_TOKEN` / `CONTACT_JIRA_PROJECT_KEY` | Contact-form ingestion into Jira                                                  |
| `AGENT_SECURITY_DEPS_*`                                                   | Override dependency-install guardrails (see `application.yml` `agent.security.deps`) |
| `EXECUTESPEC_REPO_MAX_SIZE_KB`                                            | Repo-size clone-time gate (default 250000 KB)                                    |
| `PREVIEW_GATEWAY_IP` / `PREVIEW_GW_INTERNAL_IP` / `PREVIEW_SSH_KEY_PATH` / `PREVIEW_SSH_USER` | Preview VM mode (preview-gw)                                                      |
| `VITE_PORT` / `VITE_BACKEND_URL`                                          | Frontend dev-server port + proxy target overrides (`frontend/vite.config.ts`)    |

`.env.template` at the repo root tracks the most-commonly-needed subset for local bootstrapping; it is not exhaustive.

## Smoke after deploy

After every deploy the workflow runs the actuator health probe documented above. For deeper post-deploy verification:

```bash
# 1. Liveness (cached state; always 200 unless JVM is gone)
curl https://dev.executespec.ai/api/health

# 2. Readiness (inline DB check; 503 if Postgres unreachable)
curl https://dev.executespec.ai/api/health/ready

# 3. Spring Actuator (used by CI)
curl https://dev.executespec.ai/actuator/health
```

The exposed actuator endpoints are configured in `application.yml`:

```
management.endpoints.web.exposure.include: health,info,metrics,prometheus
management.endpoints.web.base-path: /actuator
```

Only `health`, `info`, `metrics`, and `prometheus` are reachable externally. The deploy step gates success on `/actuator/health` returning HTTP 200 + body containing `"status":"UP"` or `"status":"DEGRADED"`. DEGRADED is treated as success because the cached DB/Redis state may briefly lag after a restart; the readiness probe catches actual outages.

## Observability stack

Prod ships traces to GCP Cloud Trace and metrics to Cloud Monitoring (`exporter-trace`, `exporter-metrics` in `pom.xml`; activated by the `gcp` profile). Local + dev use Prometheus + Tempo + Grafana via `observability/docker-compose.yml`:

| Service     | Image                       | Host port | Container port |
| ----------- | --------------------------- | --------- | -------------- |
| Prometheus  | `prom/prometheus:latest`    | 9090      | 9090           |
| Tempo       | `grafana/tempo:2.6.1`       | 4317 (OTLP gRPC), 4318 (OTLP HTTP), 3200 (Tempo API) | 4317/4318/3200 |
| Grafana     | `grafana/grafana:latest`    | 3001      | 3000           |

Prometheus scrapes `host.docker.internal:8080/actuator/prometheus` every 15s (`observability/prometheus.yml`). Tempo stores traces locally under `/var/tempo/traces` (`observability/tempo/tempo.yml`). Grafana provisions both as datasources and ships two dashboards: `executespec-overview.json` and `jvm-http-overview.json`.

Custom metrics are emitted under two namespaces:

- `executespec.v2.*` — V2 pipeline metrics (`observability/Metrics.java`). Counters/timers/distribution-summaries for `run.outcome`, `run.latency`, `planner.latency`, `planner.tasks`, `task.status`, `task.latency`, `task.smoketest`, `worker.iterations`, `worker.outcome`, `worker.toolcall`, `llm.latency`, `llm.key_source`, `classifier.verifier.engaged`, `reviewer.verdict`, `context.save`, `context.save.bytes`.
- `executespec.*` — Cross-cutting metrics (`config/CustomMetricsConfig.java` + ad-hoc `Counter.builder(...)` sites). Gauges for `run.active`, `run.queued`, `preview.active`, `deploy.active`; counters for LLM tokens / errors / fallbacks; deploy lifecycle timers; SCM cleanup; SSE emitters; Redis/gate fallbacks.

See `docs/operations/slos.md` for the SLO-relevant subset.

## Scheduled jobs (run automatically on the JVM)

Jobs annotated `@Scheduled`. Distributed locking via ShedLock + Redis (`pom.xml`: `shedlock-spring`, `shedlock-provider-redis-spring`). 29 jobs total at the time of writing; the table below lists the operationally-meaningful ones.

| Job                                                | Schedule          | Lock name                  | Effect                                                         |
| -------------------------------------------------- | ----------------- | -------------------------- | -------------------------------------------------------------- |
| `deployment/WorkspaceArchiveJob.java`              | `0 0 * * * *` (hourly) | `WorkspaceArchiveJob`     | Zips + uploads completed-run workspaces older than 24h to GCS |
| `compliance/WorkspaceDiskCleanupJob.java`          | `0 30 3 * * *` (03:30 daily) | `workspaceDiskCleanup` | Reaps orphaned per-run workspace directories                  |
| `compliance/StaleScmCleanupJob.java`               | `0 45 3 * * *` (03:45 daily) | `staleScmCleanup`     | Closes/deletes stale SCM branches                              |
| `compliance/DataRetentionService.java`             | `0 0 3 * * *` (03:00 daily) | n/a                    | Hard-delete pass per retention policy                          |
| `activity/ActivityFeedRetentionJob.java`           | `0 0 3 * * *` (03:00 daily) | `ActivityFeedRetentionJob` | Drops activity rows past retention                           |
| `activity/ActivityFeedPartitionJob.java`           | `0 30 2 * * *` (02:30 daily) | `ActivityFeedPartitionJob` | Creates next-N-month partitions on `activity_feed`         |
| `auth/service/MonthlyRunCounterResetJob.java`      | `0 0 0 1 * *` (1st of month, Asia/Kolkata) | `monthlyRunCounterReset` | Resets per-user monthly run counters         |
| `auth/service/WeeklyAdminSummaryJob.java`          | `0 0 9 * * MON` (Mon 09:00 Asia/Kolkata) | `weeklyAdminSummary` | Emails weekly admin summary                       |
| `onboarding/SandboxCleanupJob.java`                | `0 5 * * * *` (xx:05 hourly) | `sandboxCleanup`     | GCs abandoned sandbox projects                                  |
| `integration/IntegrationEventLogCleanupJob.java`   | `0 0 3 * * *` (03:00 daily) | n/a                    | Trims integration event log                                     |
| `integration/slack/SlackNotificationLogCleanupJob.java` | `0 0 4 * * *` (04:00 daily) | n/a               | Trims Slack notification log                                    |
| `deployment/DeploymentCleanupJob.java`             | `${agent.deployment.cleanup-cron}` (default `0 3 * * * *`, hourly :03) | n/a | Reaps Cloud Run preview services past TTL                |
| `deployment/preview/PreviewService.java`           | `fixedRate = 60_000` + `fixedRate = 1_800_000` | n/a            | Preview heartbeat (1 min) + cleanup (30 min)                   |
| `deployment/preview/vm/PreviewGarbageCollector.java` | `fixedRate = 600_000` (10 min) | n/a                   | VM-mode preview reaper                                          |
| `deployment/preview/engine/BuildCachePruneJob.java` | cron-scheduled    | n/a                        | Prunes preview-engine local build cache                         |
| `deployment/WorkspaceGcsUploadListener.java`       | `fixedRate = 300_000` (5 min) | `GcsUploadFallback`   | Retries failed GCS workspace uploads                           |
| `recovery/StartupRunResumer.java`                  | `ApplicationReadyEvent` (boot-time, once) | n/a            | Scans non-terminal V2 runs, marks each FAILED with a clear reason + emits SSE so the UI shows them (Phase 1 fail-cleanly behaviour) |
| `saga/SideEffectExecutor.java`                     | `fixedRate = 30_000` (30s) | n/a                  | Dispatches deferred side effects (retries on failure)           |
| `batch/BatchPollService.java`                      | `fixedDelay = 30_000`, lock `v2-batch-poll` | n/a            | Polls in-flight provider batch jobs                              |
| `config/featureflag/FeatureFlagService.java`       | `fixedRate = 60_000` (60s) | n/a                  | Reloads feature flag rows                                        |
| `project/service/StackRefreshJob.java`             | `0 0 3 * * *` (03:00 daily) | `StackRefreshJob`    | Per-project stack refresh                                        |
| `execution/service/AdminReconciliationService.java` | `fixedDelay = 15 * 60_000`, `initialDelay = 5 * 60_000` | `executionRunReconcile` | Marks orphaned non-terminal `ExecutionRun` rows FAILED |
| `billing/scheduler/RenewalReminderScheduler.java`   | `0 15 9 * * *` (09:15 daily) | n/a                  | Sends renewal reminder emails                                    |
| `billing/scheduler/PeriodEndDowngradeScheduler.java` | `0 */30 * * * *` (every 30 min) | n/a                | Applies period-end downgrades                                    |
| `billing/scheduler/DunningScheduler.java`            | `0 0 9 * * *` (09:00 daily) | n/a                  | Sends dunning notices on failed payments                          |

## Cost-management knobs at runtime

These are not deploy concerns but live in `application.yml` and affect per-deploy cost behaviour:

- `agent.concurrency.max-runs` — `50` in base config, **`20` in `application-prod.yml`**
- `agent.concurrency.max-task-duration-minutes` — `25`
- `agent.concurrency.safety-iteration-cap` — `500`
- `agent.cost.credit-multiplier-usd` — `5.0` ($5 of LLM compute per run credit)
- `agent.cost.budget-margin` — `1.5` (silent hard cap = credits × multiplier × margin)
- `preview.preview-ttl-minutes` — `30`
- `preview.concurrency.{free-max, pro-max, enterprise-max}` — `1`, `3`, `10`
- `app.tiers.{free,pro,enterprise}.{monthly-run-limit, concurrent-run-limit}` — see `application.yml`

## Terraform infrastructure

`infra/` uses a workspace-per-environment layout (`dev`, `prod`). Common modules:

- `network/` — VPC, subnet, Cloud NAT, IAP firewall rule
- `compute/` — GCE instance with the startup script attached
- `database/` — Cloud SQL PostgreSQL (HA toggled by `db_ha_enabled`)
- `cache/` — Memorystore Redis (`BASIC` for dev, `STANDARD_HA` for prod)
- `storage/` — GCS deploy bucket + workspace bucket
- `loadbalancer/` — Optional HTTPS LB (`enable_lb`)
- `secrets/` — Secret Manager bindings (consumed by `SecretManagerConfigLoader` at JVM startup)

Apply:

```bash
cd infra
terraform workspace select dev
terraform apply -var-file=environments/dev.tfvars
```

Estimated monthly cost is documented at the bottom of each `.tfvars` file (`infra/environments/dev.tfvars`: ~$229/mo, `infra/environments/prod.tfvars`: ~$373/mo).

A one-time secret-seed pass migrates plain-text GCS keys to Secret Manager via `infra/scripts/seed-secrets.sh`; the GCS file remains as a fallback for boot-time bootstrap (see `infra/scripts/startup.sh` phase 6c).

## Rollback

1. Identify the prior good SHA from `gs://${DEPLOY_BUCKET}/releases/`.
2. Trigger `workflow_dispatch` on `.github/workflows/deploy.yml` with `target_env=dev`, `target_sha=<prior_sha>`.
3. The workflow re-uploads the SHA-keyed JAR (idempotent), updates `CURRENT_SHA`, runs the env-patch + JAR-symlink dance on the VM, and restarts the service.

Production rollback is currently blocked at the workflow-trigger level (no `prod` option in the `target_env` choice). The header comment in `deploy.yml` (lines 1-6) documents this gate. Until prod GCP credentials are provisioned, manual production rollback would require SSHing onto the VM and running:

```bash
sudo gsutil cp gs://executespec-prod-deploy/releases/app-<prior_sha>.jar /opt/app/releases/
sudo ln -sf /opt/app/releases/app-<prior_sha>.jar /opt/app/app.jar
echo '<prior_sha>' | sudo tee /opt/app/RUNNING_SHA
sudo systemctl restart executespec
```

This is the same set of commands the workflow runs in the `Deploy JAR to GCE` step.

## Known operational gaps

- Production deploy path is wired but `target_env=prod` is gated out of the `workflow_dispatch` choice list (see header comment in `deploy.yml`).
- `frontend-audit` job is warn-only (`continue-on-error: true`); high-severity vite/esbuild advisories surface in CI but don't block.
- Health check uses HTTP not HTTPS via `localhost:8080` — fine because it runs over the IAP-tunnelled SSH session inside the VM.
- Single GCE VM per environment means there is no HA story for the JVM. Cloud SQL HA is on in prod (`db_ha_enabled = true`); Memorystore is `STANDARD_HA` in prod; the JVM itself is not redundant.
- The `recovery/StartupRunResumer.java` Phase 1 behaviour fails non-terminal runs cleanly on boot — it does not yet replay them. Phase 2 (replay via `ContextStore.load` + `WorkerAgent.resumeFromSnapshot()`) is tracked in the class header.
