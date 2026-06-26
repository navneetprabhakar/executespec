# Deployment

How the ExecuteSpec Spring Boot application is built, shipped, and run. Covers CI/CD,
target environments, environment variables, Spring profiles, Flyway, cleanroom
containers, preview deploys, and manual escape hatches.

> Engineering-honest notes: prod paths exist in the deploy workflow but are gated
> behind a dev-only trigger; some Terraform modules (`infra/modules/secrets`) are
> declared but not wired into the root composition; committed secrets exist in
> `application-local.yml` and need rotation. These are called out inline where they
> matter.

---

## 1. Target environment

Single-region GCP deployment in `us-east4`.

```
                 Cloudflare DNS + TLS termination
                       (*.executespec.ai)
                              |
                              v
              +-------------------------------+
              |  GCE VM (executespec-{env}-vm)|
              |  Ubuntu 22.04, e2-standard-4  |
              |                               |
              |  systemd: executespec.service |
              |   `- java -jar /opt/app/app.jar
              |                               |
              |  Caddy :443 reverse proxy     |
              |   `- Cloudflare Origin Cert   |
              |   `- localhost:8080           |
              |                               |
              |  Docker daemon (previews)     |
              |  Python/Node/Maven toolchains |
              +-------------------------------+
                              |
       +----------------------+----------------------+
       v                      v                      v
  Cloud SQL              Memorystore             GCS buckets
  Postgres 15            Redis 7.0           executespec-*-deploy
  (private IP)           (private SA)        executespec-workspaces
                                              executespec-tfstate

       v on-demand for previews and customer deploys
  Cloud Run (preview-engine handoff, customer Cloud Run Direct deploys)
  Artifact Registry: us-east4-docker.pkg.dev/executespecv1/executespec-{env}-docker
```

The same Spring Boot JAR serves both `dev.executespec.ai` and `app.executespec.ai`.
Environments differ only by Spring profile (`dev` vs `prod`), the GCS deploy bucket
(`executespec-dev-deploy` vs `executespec-prod-deploy`), the GCE instance name,
and the OAuth redirect URIs.

### Infrastructure provisioning (Terraform)

The `infra/` Terraform stack provisions one full environment from
`infra/main.tf`. Composition order (root module dependencies):

1. **Network** (`infra/modules/network/main.tf`) — VPC `executespec-{env}-vpc`,
   subnet `10.0.0.0/24`, Cloud Router + NAT for outbound egress, private services
   range, five firewall rules (LB health, LB->backend on `:8080`, IAP SSH from
   `35.235.240.0/20`, internal `10.0.0.0/24` ICMP/TCP/UDP, preview ports
   `10000-10100`).
2. **Database** (`infra/modules/database/main.tf`) — Cloud SQL `executespec-{env}-pg`
   on `POSTGRES_15`, private IP only, `PD_SSD`, automated backups 03:00 UTC with
   point-in-time recovery when HA, max-connections 100. Generates a 32-char random
   password and stores it in Secret Manager (`executespec-{env}-db-password`).
3. **Cache** (`infra/modules/cache/main.tf`) — Memorystore Redis 7.0,
   `allkeys-lru` eviction, weekly maintenance Sunday 04:00 UTC, default 1 GB,
   `BASIC` tier.
4. **Storage** (`infra/modules/storage/main.tf`) — three GCS buckets and one
   Artifact Registry repo:
   - `executespec-{env}-deploy` — versioned, keeps last 20 object versions
   - `executespec-{env}-workspaces` — 30-day lifecycle delete
   - Docker repo `executespec-{env}-docker` — keeps 10 most recent versions
5. **Compute** (`infra/modules/compute/main.tf`) — standalone GCE VM on Ubuntu
   22.04 LTS, network tag `executespec-server`, no external IP (outbound via NAT),
   `e2-standard-4` default machine type, 50 GB `pd-balanced` boot disk.
   Wrapped in an unmanaged instance group for LB backend wiring.
6. **Compute Service Account** (`infra/modules/compute/sa/main.tf`) — 9 IAM
   roles: `cloudsql.client`, `secretmanager.secretAccessor`,
   `logging.logWriter`, `monitoring.metricWriter`, `cloudtrace.agent`,
   `artifactregistry.writer`, `compute.instanceAdmin.v1`,
   `iap.tunnelResourceAccessor`, `iam.serviceAccountUser`. Plus
   `storage.objectAdmin` on the deploy and workspace buckets.
7. **Load Balancer** (`infra/modules/loadbalancer/main.tf`, optional via
   `var.enable_lb`) — global HTTPS LB with managed SSL cert, backend service with
   `session_affinity=CLIENT_IP` (for SSE), HTTP health check on
   `/api/health/ready` (15s interval, 10s timeout), HTTP->HTTPS redirect with
   `MOVED_PERMANENTLY_DEFAULT`.

State lives in GCS at `gs://executespec-tfstate/infra/{dev,prod}/`
(`infra/backend.tf`). Terraform >= 1.5, providers
`hashicorp/google ~> 5.0` and `hashicorp/google-beta ~> 5.0`
(`infra/versions.tf`).

Engineering-honest: `infra/modules/secrets/main.tf` declares 14 Secret Manager
slots (`anthropic-api-key`, `openai-api-key`, `google-api-key`, `github-pat`,
`jwt-secret`, the four Razorpay secrets, two SMTP creds, two Slack secrets,
two Atlassian tokens) but the module is **not wired into the root composition**.
Operators bootstrap these manually using `infra/scripts/seed-secrets.sh` (see
section 11).

### GCE VM startup

`infra/scripts/startup.sh` (503 lines, idempotent) runs on first boot and every
subsequent reboot via instance metadata. It installs:

- Java 21 (`openjdk-21-jdk-headless`), Maven
- Docker CE + compose-plugin (enabled at boot)
- Node.js LTS via NodeSource
- Python 3 + pip + mypy + ruff + pytest (validator fallbacks)
- gcloud SDK and `gcloud auth configure-docker` for Artifact Registry
- Caddy (writes `/etc/caddy/config.json`, admin API on `localhost:2019`,
  listens `:443`, Cloudflare Origin Cert at `/etc/caddy/origin.pem`)

Reads 11 `es-*` metadata keys (environment, db-host/name/user/password,
redis-host/port, deploy-bucket, workspace-bucket, ar-region, ar-repo, ar-host)
plus pulls `gs://<deploy-bucket>/config/api-keys` and
`gs://<deploy-bucket>/config/smtp-credentials` as a fallback to Secret Manager.

Writes `/opt/app/executespec.env` with: POSTGRES_\*, REDIS_\*,
GCS_WORKSPACE_BUCKET, DEPLOY_BUCKET, MAIL_\*, API keys, GCP_PROJECT,
AR_REPOSITORY, CONTACT_JIRA_\*, `SPRING_PROFILES_ACTIVE=<env>,gcp`,
CORS_ALLOWED_ORIGINS, DASHBOARD_URL, APP_BASE_URL,
`JAVA_OPTS=-Xms4g -Xmx12g -XX:+UseG1GC`, `PREVIEW_MODE=vm`,
`PREVIEW_GATEWAY_IP=34.186.68.141`, `PREVIEW_GW_INTERNAL_IP=10.150.0.2`,
`PREVIEW_SSH_KEY_PATH=/opt/app/preview-ssh-key`,
`PREVIEW_SSH_USER=admin_executespec_ai`.

The systemd unit `/etc/systemd/system/executespec.service` is `Type=simple`,
`ExecStart=java $JAVA_OPTS -jar /opt/app/app.jar`, `Restart=always`,
`RestartSec=30`, `LimitNOFILE=65535`.

Engineering-honest: `PREVIEW_GATEWAY_IP=34.186.68.141` and
`PREVIEW_GW_INTERNAL_IP=10.150.0.2` are hardcoded in the startup script
(`infra/scripts/startup.sh` phase 6d) and are NOT exposed via Terraform
variables. The preview-gateway VM is provisioned out-of-band.

---

## 2. CI/CD pipeline

Two workflows under `.github/workflows/`:

| File         | Purpose                                | Trigger                                            |
| ------------ | -------------------------------------- | -------------------------------------------------- |
| `deploy.yml` | Build, push JAR to GCS, deploy on VM   | `push` to `dev`, manual `workflow_dispatch` (dev) |
| `lint.yml`   | Java spotless + Prettier + ESLint gate | `pull_request` to `dev` or `main`                  |

### `.github/workflows/deploy.yml` -- Build & Deploy

Workflow name `Build & Deploy`. Single job
`build-and-deploy` runs on `ubuntu-latest`.

Triggers:

- `push` on branches: `[dev]`
- `workflow_dispatch` with inputs:
  - `target_env`: choice, required, default `dev`, options `[dev]` (prod
    option **not currently exposed**)
  - `target_sha`: optional, blank = latest

Top-level env: `JAVA_VERSION=21`, `DEPLOY_BUCKET_PREFIX=executespec`.

Job env:

- `DEPLOY_ENV` -- `prod` if branch/input is `main`, else `dev`
- `TARGET_SHA` -- `inputs.target_sha || github.sha`
- `GCP_PROJECT`, `GCP_REGION`, `GCE_ZONE` from secrets
- `environment:` -- `production` if `main`, else `development`

Permissions: `contents: read`, `id-token: write` (required for Workload
Identity Federation).

Steps (in order):

1. **Checkout** -- `actions/checkout@v6`, `ref: ${{ env.TARGET_SHA }}`,
   `fetch-depth: 0` (needed for Slack commit message rendering).
2. **Set up Java 21** -- `actions/setup-java@v5`, `distribution: temurin`,
   `cache: maven`.
3. **Set up Node.js** -- `actions/setup-node@v6`, `node-version: 'lts/*'`,
   `cache: 'npm'`, `cache-dependency-path: frontend/package-lock.json`.
4. **Install frontend deps** -- `cd frontend && npm ci`.
5. **Frontend format check** -- `cd frontend && npm run format:check`.
6. **Frontend lint** -- `cd frontend && npm run lint`.
7. **Build frontend** -- `cd frontend && npm run build`.
8. **Build JAR** -- `mvn verify -q`. Binds `spotless-maven-plugin` at the
   `verify` phase; surefire excludes `e2e,integration,chaos` groups.
9. **Authenticate to GCP** -- `google-github-actions/auth@v3` with
   `workload_identity_provider` and `service_account` from secrets (no
   service-account JSON keys checked in).
10. **Set up gcloud CLI** -- `google-github-actions/setup-gcloud@v3`.
11. **Debug -- print resolved config** -- emits `gcloud config get-value project`
    and `gcloud compute instances list --filter="name~executespec"`.
12. **Set deploy bucket** -- exports
    `DEPLOY_BUCKET=gs://executespec-{env}-deploy`.
13. **Upload JAR to GCS** -- `gsutil cp` to
    `${DEPLOY_BUCKET}/releases/app-${TARGET_SHA}.jar` and writes the SHA to
    `${DEPLOY_BUCKET}/CURRENT_SHA`. JARs are permanent -- never overwritten --
    giving a full rollback history.
14. **Set instance name** -- `executespec-prod-vm` or `executespec-dev-vm`.
15. **Ensure validator toolchains on GCE** -- SSH via `--tunnel-through-iap`;
    installs Node LTS, Python3 + pip + venv if missing, then
    `pip3 install --break-system-packages mypy ruff pytest`.
16. **Set public URL** -- `https://app.executespec.ai` (prod) or
    `https://dev.executespec.ai` (dev).
17. **Sync design system files** -- `continue-on-error: true`; SSHes to VM and
    rsyncs `gs://...-deploy/designs/design-md` to
    `/opt/executespec/designs/design-md`.
18. **Write env patch script** -- generates `/tmp/env-patch.sh` (a heredoc bash
    script that upserts keys into `/opt/app/executespec.env`). Keys it sets:
    - `CORS_ALLOWED_ORIGINS=https://app.executespec.ai,https://dev.executespec.ai,http://localhost:5173`
    - `DASHBOARD_URL=$1`, `APP_BASE_URL=$1`
    - `MAIL_TRANSPORT=smtp`, `MAIL_FROM=admin@executespec.ai`
    - `PREVIEW_ENGINE_URL=https://preview-engine.executespec.ai`
    - `PREVIEW_ENGINE_REGISTRY_URL=us-east4-docker.pkg.dev/executespecv1/preview-images`
    - `PREVIEW_ENGINE_ROUTE_VIA=true`, `PREVIEW_MODE=docker`
    - Loads `gs://${DEPLOY_BUCKET}/config/api-keys` and
      `gs://${DEPLOY_BUCKET}/config/smtp-credentials` line-by-line via `upsert`.
19. **Deploy JAR to GCE** -- `gcloud compute scp` env-patch.sh to VM,
    then SSH and:
    - `gsutil cp` JAR from GCS to `/opt/app/releases/`
    - Run `/tmp/env-patch.sh` with public URL + bucket name
    - `ln -sf` JAR to `/opt/app/app.jar`
    - Write SHA to `/opt/app/RUNNING_SHA`
    - `systemctl restart executespec`
20. **Wait for app startup** -- `sleep 30`.
21. **Health check** -- 8-attempt loop with 10s sleep. SSH and
    `curl http://localhost:8080/actuator/health`. The workflow removes
    `/tmp/health.json` before each curl to avoid buffer-reuse false-positives. Gate on `http=200`
    substring AND JSON body containing `"status":"(UP|DEGRADED)"`. (A 2026-04-17
    V113 boot-crash previously sailed through CI as success because of the
    buffer-reuse bug.)
22. **Dump app logs (on failure)** -- `if: failure()`; SSHes to dump
    `systemctl status executespec`, last 150 lines of `journalctl -u executespec`,
    and a redacted env file.
23. **Prune old Docker images** -- `if: success()`,
    `continue-on-error: true`; SSH and run
    `docker image prune -a --force --filter 'until=168h'` plus
    `docker builder prune --force --filter 'until=168h'`.
24. **Prune old JAR releases** -- keeps current + last 10; deletes older via
    `gsutil rm`.
25. **Deployment summary** -- echoes deployed SHA, JAR path, branch,
    environment.
26. **Notify Slack (success)** -- uses `secrets.SLACK_WEBHOOK_URL`; builds
    Block-Kit payload with `jq`.
27. **Notify Slack (failure)** -- same Slack pattern with `:x:` message.

Workload Identity Federation: no static service-account JSON keys. The job uses
`google-github-actions/auth@v3` with `workload_identity_provider` +
`service_account` secrets and the `id-token: write` permission on the
workflow.

Cross-workflow consistency: both `deploy.yml` and `lint.yml` pin
`actions/checkout@v6`, `actions/setup-java@v5`, `actions/setup-node@v6`, and
Java 21.

### `.github/workflows/lint.yml` -- Lint

146 lines; three parallel jobs gating PRs.

Triggers:

- `pull_request` on `[dev, main]`
- `workflow_dispatch`

Jobs:

| Job              | Tool                                  | Behavior                                                                                       |
| ---------------- | ------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `java-lint`      | `mvn -B spotless:check`                | Google Java Format gate. On failure: `::error::` hint to run `mvn spotless:apply`.            |
| `frontend-lint`  | `npm run format:check && npm run lint` | Prettier + ESLint. On failure: hint to run `npm --prefix frontend run format` + `lint:fix`. |
| `frontend-audit` | `npm audit --audit-level=high`        | `continue-on-error: true`. Emits `::warning::` or `::notice::npm audit clean`.                |

The `frontend-audit` job is **warn-only by design** pending clearance of known
high-severity advisories in dev-only Vite 5.x transitives (referenced as
`docs/security/audit-policy.md`, currently TBD).

---

## 3. Secrets

Every GitHub Actions secret referenced by `deploy.yml`. Values never appear in
this doc.

| Secret                          | Used for                                                                |
| ------------------------------- | ----------------------------------------------------------------------- |
| `GCP_PROJECT`                   | GCP project ID for both Cloud Build / SCP / SSH targets                 |
| `GCP_REGION`                    | `us-east4` (used for AR host and gcloud config)                         |
| `GCE_ZONE`                      | Zone of the executespec-{env}-vm                                        |
| `WORKLOAD_IDENTITY_PROVIDER`    | WIF provider full path (`projects/.../providers/...`)                   |
| `SERVICE_ACCOUNT`               | Email of the GitHub-side service account WIF impersonates                |
| `SLACK_WEBHOOK_URL`             | Slack incoming-webhook for `deploy.yml` success/failure notifications   |

(GCS-resident secrets -- API keys, SMTP credentials, OAuth client secrets -- are
NOT referenced as GitHub Actions secrets; they are pulled from
`gs://{deploy-bucket}/config/api-keys` and `gs://{deploy-bucket}/config/smtp-credentials`
during the env-patch step on the VM.)

Engineering-honest:
- **`application-local.yml` commits real Anthropic, OpenAI, and Google API
  keys, plus Razorpay test creds, plus Google + GitHub OAuth client secrets,
  plus an admin Gmail app password.** The file header says "NEVER commit real
  secrets here in production repos" but the file itself does. These keys need
  rotation and the file should source from env-only.
- **`application-dev.yml` commits OAuth client-id / client-secret defaults and
  the same SMTP app password as fallbacks.** Intentional for dev VM
  convenience but defeats the env-var pattern.

---

## 4. Build artifacts

### Backend: Maven JAR

- Coordinates: `com.navneet:executespec:1.0.0-M1` (`pom.xml` line 12-15).
- Output: `target/executespec-1.0.0-M1.jar`.
- Parent: `org.springframework.boot:spring-boot-starter-parent:3.5.14`.
- Java 21 (`<java.version>21`).
- Spring AI 1.1.6 BOM (`org.springframework.ai:spring-ai-bom`).
- Static frontend bundled under `src/main/resources/static/` and served from
  the JAR.
- Build plugins:
  - `spring-boot-maven-plugin` -- excludes `org.projectlombok:lombok` from the
    repackaged JAR.
  - `maven-surefire-plugin` -- `<excludedGroups>e2e,integration,chaos</excludedGroups>`.
  - `spotless-maven-plugin:3.4.0` -- bound to `verify` phase; Google Java
    Format `1.22.0`, style `GOOGLE`, `reflowLongStrings=true`,
    `removeUnusedImports`, importOrder `java,javax,jakarta,com,org,`,
    `formatAnnotations`.
  - `jacoco-maven-plugin:0.8.14` -- `prepare-agent` + `report` (phase `test`),
    excludes `com/navneet/executespec/api/dto/**`, `**/dto/**`, and
    `ExecutespecApplication.class`.
- Spring repositories:
  - `spring-milestones` -> `https://repo.spring.io/milestone` (snapshots
    disabled)
  - `spring-snapshots` -> `https://repo.spring.io/snapshot` (releases disabled)

Mockito is pinned to `5.17.0` and ByteBuddy to `1.16.1` to override the SB
3.5 defaults for Java 25 class-file compatibility (comment is forward-looking;
project currently runs on Java 21).

### Frontend: Vite bundle

- Built via `cd frontend && npm ci && npm run build`.
- Vite production output is copied to `src/main/resources/static/` so the
  Spring Boot JAR serves it directly. No separate frontend deploy step.
- Format/lint gates: `npm run format:check` (Prettier) and `npm run lint`
  (ESLint) -- both enforced in `deploy.yml` and `lint.yml`.

### Versioning

`VERSION` and `Version` at the repo root both contain `1.0.0-M1`. Two
case-distinct files with identical content -- risk of drift on future bumps.
The project's `Version` file is the source of truth referenced in onboarding
docs.

---

## 5. Local development

### Backend

```bash
# macOS dev requires this -- toolchains use OpenJDK 21 from Homebrew
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Compile only
mvn compile -q

# Full verify (spotless + tests)
mvn verify -q

# Run with the local profile
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` Spring profile is the base `application.yml` with the local
profile doc activated. It uses:

- Postgres on `localhost:5432`, db `executespec`, user/password `postgres/postgres`
- Redis on `localhost:6379`
- Tracing disabled (`management.tracing.enabled=false`)
- CORS origin `http://localhost:5173`
- JWT secret = baked-in dev default
- Razorpay-first billing (`billing.default-gateway=razorpay`)

`application-local.yml` is loaded after `application.yml` when
`SPRING_PROFILES_ACTIVE=local` is set. It currently commits real API keys (see
section 3 engineering-honest note).

### Frontend

```bash
npm --prefix frontend ci
npm --prefix frontend run dev          # Vite dev server on :5173
npm --prefix frontend run format:check
npm --prefix frontend run lint
```

### Local observability stack

```bash
cd observability && docker compose up -d
# Grafana http://localhost:3001 (admin/admin)
# Prometheus :9090, Tempo :3200
```

### Environment template

Copy `.env.template` to `.env` (gitignored) and fill in the required keys:

```bash
cp .env.template .env
```

Required (uncommented in template): `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
`GOOGLE_API_KEY`, `GITHUB_PAT`, `GITHUB_OWNER`.

Optional sections (all commented out in template): Postgres connection,
Redis host, Stripe keys, OAuth2 client IDs/secrets, Slack credentials.

Razorpay is uncommented with placeholder values; comment notes
`# Test keys: rzp_test_xxx / secret  |  Live keys: rzp_live_xxx / secret`.

Engineering-honest: `.env.template` foregrounds Razorpay while the prod
default gateway is Stripe -- the template hasn't been updated for the prod
default switch.

---

## 6. Environment variables

Pulled from `.env.template`, `application*.yml`, and the env-patch step in
`deploy.yml`. Variables marked **required** must be set to start the JVM;
optionals carry sensible defaults.

### LLM providers

| Variable            | Required        | Notes                                             |
| ------------------- | --------------- | ------------------------------------------------- |
| `ANTHROPIC_API_KEY` | Yes for system-key routes | Bound to `spring.ai.anthropic.api-key` |
| `OPENAI_API_KEY`    | Yes for system-key routes | Bound to `spring.ai.openai.api-key` |
| `GOOGLE_API_KEY`    | Optional | Gemini; bound to `spring.ai.google.genai.api-key` |
| `DEEPSEEK_API_KEY`  | Optional | Native DeepSeek route |
| `OPENROUTER_API_KEY` | Optional | OpenRouter aggregator route |
| `FIREWORKS_API_KEY` | Optional | Fireworks aggregator route |

### Integrations

| Variable                                                                                                    | Used for                                                |
| ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| User/org SCM credentials                                                                                    | GitHub/Bitbucket clone, push, PR creation. There is no system GitHub PAT. |
| `SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET`                                            | Slack OAuth + webhook verification                      |
| `DASHBOARD_URL`                                                                                             | Link target embedded in Slack messages + emails         |

### OAuth2 (web login)

| Variable                       | Used for                                                                |
| ------------------------------ | ----------------------------------------------------------------------- |
| `GOOGLE_OAUTH_CLIENT_ID`       | `spring.security.oauth2.client.registration.google.client-id`           |
| `GOOGLE_OAUTH_CLIENT_SECRET`   | `...google.client-secret`                                               |
| `GITHUB_OAUTH_CLIENT_ID`       | `spring.security.oauth2.client.registration.github.client-id`           |
| `GITHUB_OAUTH_CLIENT_SECRET`   | `...github.client-secret`                                               |

Redirect URIs in prod default to
`https://executespec.ai/api/auth/oauth2/callback/{registrationId}`; dev uses
`https://dev.executespec.ai/...` (`application-prod.yml`, `application-dev.yml`).

### Billing

| Variable                            | Used for                                                                            |
| ----------------------------------- | ----------------------------------------------------------------------------------- |
| `RAZORPAY_KEY_ID`                   | Razorpay API key (INR payments)                                                    |
| `RAZORPAY_KEY_SECRET`               | Razorpay secret                                                                     |
| `RAZORPAY_WEBHOOK_SECRET`           | HMAC-SHA256 for `/api/webhooks/razorpay`                                            |
| `RAZORPAY_WEBHOOK_URL`              | Full webhook URL Razorpay posts back to                                              |
| `STRIPE_SECRET_KEY`                 | Stripe API key (USD + multi-currency)                                                |
| `STRIPE_WEBHOOK_SECRET`             | `Stripe-Signature` verification for `/api/webhooks/stripe`                          |
| `STRIPE_PUBLISHABLE_KEY`            | Frontend Checkout init                                                              |
| `STRIPE_TEAM_PRICE_ID`              | Stripe price ID for Team per-seat                                                   |
| `STRIPE_TEAM_PRICE_ID_INR`          | Stripe price ID for Team INR override                                               |
| `STRIPE_ENTERPRISE_PRICE_ID`        | Stripe price ID for Enterprise                                                      |
| `BILLING_DEFAULT_GATEWAY`           | `stripe` (prod default) / `razorpay` (dev default)                                  |
| `BILLING_STRIPE_ENABLED`            | Set `true` in prod to enable Stripe handler                                          |

### Infrastructure

| Variable          | Default               | Notes                                                       |
| ----------------- | --------------------- | ----------------------------------------------------------- |
| `POSTGRES_HOST`   | `localhost`           | `spring.datasource.url` interpolation                       |
| `POSTGRES_PORT`   | `5432`                |                                                             |
| `POSTGRES_DB`     | `executespec`         |                                                             |
| `POSTGRES_USER`   | `postgres`            |                                                             |
| `POSTGRES_PASSWORD` | `postgres`           |                                                             |
| `REDIS_HOST`      | `localhost`           |                                                             |
| `REDIS_PORT`      | `6379`                |                                                             |
| `GCS_WORKSPACE_BUCKET` | `executespec-workspaces` | `agent.deployment.workspace.gcs-bucket`               |
| `DEPLOY_BUCKET`   | env-driven            | `executespec-{env}-deploy`                                  |
| `AR_REPOSITORY`   | env-driven            | `executespec-{env}-docker`                                  |
| `GCP_PROJECT`     | `executespecv1`       | `agent.deployment.gcp-project`                              |

### Observability

| Variable                          | Default | Notes                                                              |
| --------------------------------- | ------- | ------------------------------------------------------------------ |
| `OTEL_SAMPLING_PROBABILITY`       | `1.0`   | `management.tracing.sampling.probability`                          |
| `OTEL_METRICS_ENABLED`            | `false` | `management.otlp.metrics.export.enabled` (prod overrides to true) |
| `OTEL_EXPORTER_OTLP_ENDPOINT`     | `http://localhost:4318/v1/traces` | OTLP HTTP endpoint                       |

### Preview engine

| Variable                          | Used for                                                                            |
| --------------------------------- | ----------------------------------------------------------------------------------- |
| `PREVIEW_MODE`                    | `docker` (local + GCE), `vm` (worker pool), `cloud-run` (managed)                  |
| `PREVIEW_ENGINE_URL`              | `preview.engine.base-url`; `https://preview-engine.executespec.ai` in prod         |
| `PREVIEW_ENGINE_REGISTRY_URL`     | Artifact Registry host for preview images                                          |
| `PREVIEW_ENGINE_API_KEY`          | `X-Preview-Api-Key` header set by `PreviewEngineClient`                            |
| `PREVIEW_ENGINE_ROUTE_VIA`        | `true` to enable handoff; otherwise `PreviewService` runs local Docker Compose      |
| `PREVIEW_GATEWAY_IP`              | Public IP of the preview gateway VM (hardcoded `34.186.68.141` in startup.sh)      |
| `PREVIEW_GW_INTERNAL_IP`          | Internal IP `10.150.0.2`                                                            |
| `PREVIEW_SSH_KEY_PATH`            | `/opt/app/preview-ssh-key`                                                          |
| `PREVIEW_SSH_USER`                | `admin_executespec_ai`                                                              |

### Security

| Variable                                              | Used for                                                                                     |
| ----------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `JWT_SECRET`                                          | `app.jwt.secret` -- HS256 signing key for access + refresh tokens                            |
| `AGENT_DEPLOYMENT_CREDENTIAL_ENCRYPTION_KEY`          | Base64 32-byte AES-256-GCM key used by `CredentialEncryptionService` for stored GCP creds   |

Engineering-honest: `CredentialEncryptionService` (line 91 of source) silently
generates an **ephemeral** AES key with only a WARN log if
`agent.deployment.credential-encryption-key` is unset. In production this
would encrypt with a per-restart key that breaks all subsequent decrypts --
there is no startup fail-fast guard.

### Mail

| Variable        | Default             | Notes                                                       |
| --------------- | ------------------- | ----------------------------------------------------------- |
| `MAIL_TRANSPORT` | `smtp`             | `app.mail.transport`                                        |
| `MAIL_FROM`     | `admin@executespec.ai` |                                                          |
| `SMTP_HOST`     | `smtp.gmail.com`    |                                                             |
| `SMTP_PORT`     | `587`               |                                                             |
| `SMTP_USERNAME` | env-driven          | Pulled from `gs://{deploy-bucket}/config/smtp-credentials` |
| `SMTP_PASSWORD` | env-driven          | Same                                                       |

### CORS + URLs

| Variable                | Default                                                                | Notes                                                        |
| ----------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------ |
| `CORS_ALLOWED_ORIGINS`  | `http://localhost:5173` (local) -> `https://app.executespec.ai,https://dev.executespec.ai,http://localhost:5173` (prod) | Comma-separated origins set in env-patch                     |
| `APP_BASE_URL`          | env-driven                                                              | `app.mail.base-url` -- derives links in transactional email  |
| `DASHBOARD_URL`         | env-driven                                                              | Public URL embedded in Slack notifications + reset-password emails |

---

## 7. Spring profiles

Four profiles defined across `src/main/resources/application*.yml`.

### `local` (default)

Active when no profile is set. Embedded in `application.yml` as a
profile-document section. Overrides:

- `management.tracing.enabled=false`
- `management.otlp.metrics.export.enabled=false`

Uses base `application.yml` defaults: localhost Postgres + Redis, base
`spring.ai.anthropic/openai.api-key` blank (must be supplied via env or
`application-local.yml`), Razorpay-first billing,
`agent.recovery.auto-resume-on-startup=false`.

### `local` (file override)

`src/main/resources/application-local.yml` (76 lines). Loaded when
`SPRING_PROFILES_ACTIVE=local`. Adds developer-machine overrides:

- Postgres user/password = `postgres/postgres`
- `spring.flyway.out-of-order=true`
- Local design system path `/Users/navneetprabhakar/Documents/git/uxdesigns/design-md`
- CORS origins `http://localhost:5173,http://localhost:5174`
- Tracing disabled

Engineering-honest: this file commits **real Anthropic, OpenAI, and Google API
keys** + Razorpay test creds + Google OAuth secret + GitHub OAuth secret +
Gmail app password. See section 3.

### `dev`

`src/main/resources/application-dev.yml` (183 lines). Used by the GCE
`executespec-dev-vm` at `dev.executespec.ai`. Differences from prod:

- `dev.executespec.ai` domain everywhere
- `spring.flyway.out-of-order=true` (V2 branch safety net -- V68 added after
  V69-V73 already applied on some envs)
- `spring.autoconfigure.exclude=OtlpAutoConfiguration` (no collector on dev VM)
- Hardcoded fallback Google + GitHub OAuth client IDs/secrets (env-overridable)
- `agent.deployment.artifact-registry.repository=executespec-dev-docker`
- `agent.recovery.auto-resume-on-startup=true`,
  `startup-resume-delay-seconds=30`
- `preview.preview-ttl-minutes=15`
- `app.cors.allowed-origins` includes `dev.executespec.ai`, the `app.dev` and
  `build.dev` subdomains, plus marketing origins `executespec.ai` +
  `www.executespec.ai`
- `management.tracing.enabled=false`, `sampling.probability=0`
- `management.metrics.tags.environment=dev`
- `billing.default-gateway=razorpay` (dev default)

### `prod`

`src/main/resources/application-prod.yml` (198 lines). Used by
`executespec-prod-vm` at `app.executespec.ai` (currently dormant -- see
section 2 deploy gate). Differences from base:

- All credentials env-driven, no committed fallbacks
- `spring.security.oauth2.client.registration.*.redirect-uri` points to
  `https://executespec.ai/...`
- `server.forward-headers-strategy=framework` (Spring's
  `ForwardedHeaderFilter` for LB termination)
- `agent.concurrency.max-runs=20` (base value 50 is labeled "temporarily
  increased for load testing")
- `agent.deployment.artifact-registry.repository=executespec-docker` (no `-dev`)
- `preview.engine.base-url=https://preview-engine.executespec.ai`,
  `route-via=true`
- `app.cors.allowed-origins=https://executespec.ai,https://www.executespec.ai,https://app.executespec.ai,https://build.executespec.ai`
- `billing.default-gateway=stripe`, `billing.stripe.enabled=true`
- `management.tracing.sampling.probability=0.1`
- `management.otlp.metrics.export.enabled=true`
- `management.metrics.tags.environment=prod`

### `gcp`

Embedded profile document inside `application.yml`. Activated alongside `dev`
or `prod` (`SPRING_PROFILES_ACTIVE=<env>,gcp`). Overrides:

- `spring.threads.virtual.enabled=true`
- `management.otlp.tracing.enabled=false`
- `management.otlp.metrics.export.enabled=false`
- `management.tracing.sampling.probability=1.0`
- `management.zipkin.tracing.enabled=false`

Used to disable the OTLP exporters when running on GCE so Cloud Trace +
Cloud Monitoring exporters take over via the `com.google.cloud.opentelemetry`
deps (`pom.xml`).

---

## 8. Flyway migrations

| Setting               | Value                                |
| --------------------- | ------------------------------------ |
| Head migration        | **V248**                              |
| Locations             | `classpath:db/migration`             |
| Baseline              | `baseline-on-migrate=true`, `baseline-version=0` |
| Ignore patterns       | `*:missing` (tolerates applied-but-missing rows; see below) |
| Out-of-order          | `false` in base, `true` in `dev` + `local` profiles |

`spring.jpa.hibernate.ddl-auto=validate` -- Flyway is the **only** source of
schema changes. Hibernate fails fast at boot if the schema drifts from
entity definitions.

Migrations run automatically on app startup via the Spring Boot Flyway
auto-configuration. Files live at
`src/main/resources/db/migration/V{n}__description.sql`.

Engineering-honest: `ignore-migration-patterns: "*:missing"` is broad --
tolerates **any** applied-but-missing migration. Documented exit strategy
in inline comment is to repair the orphan V119 row from dev/prod DBs (V119
was split into the preview-engine repo). Until that's done, the
missing-migration safety net is disabled.

---

## 9. Cleanroom containers

Used by SWE-bench and ProgramBench bench-mode runs for full
sandbox-isolation. Every shell + build invocation is dispatched via
`docker exec` into a per-run container.

### Container spec

```
docker run --rm --network none -v <hostDir>:/workspace ...
```

- `--rm` -- container is removed at exit
- `--network none` -- no outbound network access (no internet, no cloud
  metadata, no DNS)
- `-v <hostDir>:/workspace` -- workspace dir is bind-mounted at `/workspace`
  so host-side tools and container-side tools see the same files

The mount path is fixed at `/workspace` so `ShellTool` and
`BuildProjectTool` paths line up regardless of which side they're invoked
from. When `ProgramBenchContainerStore` records a containerId for a run,
those tools dispatch via `docker exec` instead of host shell.

### ProgramBench config

`scripts/programbench/programbench.yaml` is the mini-swe-agent config for
ProgramBench tasks. Notable values:

```yaml
environment:
  cwd: /workspace
  timeout: 120
  interpreter: ["bash", "-c"]
  run_args: ["--rm", "--network", "none"]
  environment_class: docker
agent:
  step_limit: 500
  cost_limit: 25.0
model:
  model_name: "anthropic/claude-sonnet-4-6"
  observation_template:    # 10000-char cap with head 5000 + tail 5000 elision
```

The runner is `scripts/programbench/programbench_runner.py` (358 lines, Typer
CLI). Discovers tasks under the installed `programbench` package's
`data/tasks/<id>/task.yaml`, renders Jinja templates, spawns the docker
container, then captures `/workspace` via `docker cp` and tars to
`submission.tar.gz`.

### Submission shape

Unlike SWE-bench (which submits a git diff), ProgramBench submits a tarball
of `/workspace` after the agent writes `compile.sh` + source files producing
`/workspace/executable`. The agent terminates with:

```
echo COMPLETE_TASK_AND_SUBMIT_FINAL_OUTPUT && echo programbench-done
```

### Setup

See `scripts/programbench/README.md`. Requires `uv` + a `.venv` at
`/opt/programbench-bench`, plus the three LLM keys
(`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`).

CLI: `--instance` (single task), `--language`, `--difficulty`, `--workers`,
`--slice 0:5`, `--filter '^abishek.*'`, `--output`, `--model`. Score command:
`programbench eval ./out`.

Cost guidance per the README: `cmatrix` easy = $5; `ffmpeg`/`chroma` large
Rust/Go = push `cost_limit` to $15-30 + `step_limit` 800+.

---

## 10. Preview deploys

Cloud Run on-demand previews with a 5-minute TTL (legacy default) or 30-minute
TTL (`preview.preview-ttl-minutes` in `application.yml`; dev profile shortens
to 15 min). Four modes selected by `preview.mode`:

| Mode         | Backend                                                            | Use                                              |
| ------------ | ------------------------------------------------------------------ | ------------------------------------------------ |
| `docker`     | Local Docker Compose via `ComposeExecutor`                          | Default on dev VM; runs on the same host        |
| `cloud-run`  | `CloudRunClient` + `gcloud run deploy`                              | Managed previews on Cloud Run                   |
| `vm`         | SSH/SCP to preview-gateway VM via `PreviewDeploymentService`        | Worker pool, multi-VM ready                     |
| `engine`     | Handoff to preview-engine via `PreviewEngineHandoff`                | Enabled when `preview.engine.route-via=true`    |

The orchestrator entry point is `PreviewService.start(runId, user, envVars)`
in `deployment/preview/PreviewService.java` (1817 lines). It:

1. Looks up tier-based concurrency limit (FREE=1, PRO=3, ENTERPRISE=10 via
   `GlobalPreviewLimiter` + `PreviewProperties.ConcurrencyConfig`).
2. Increments a Redis-backed counter at `preview:count:{userId}` (1h TTL).
   Rolls back if the count crosses the limit. Fail-open on Redis errors.
3. Dispatches to one of `buildAndStart` (Docker), `buildAndStartCloudRun`,
   `buildAndStartVm`, or `buildAndStartViaEngine`.

### Docker Compose mode

Per-run workspace gets a generated `Dockerfile` from `DevDockerfileGenerator`
(3538 lines covering 10 `PreviewProjectType`s x 2 environments x 3 modes:
WEB / CLI / MCP). Auto-detects monorepos, full-stack layouts, patches
`vite.config.*` to set `allowedHosts:true`, normalizes ports.

`ComposeFileGenerator.generate(...)` writes a `docker-compose.yml` with:

- Auto-injected DB sidecars (Postgres / MySQL / Mongo / Redis) when the app
  declares them
- `tmpfs` mounts for ephemeral data
- App container hardening: `no-new-privileges`, `cap_drop:ALL`,
  metadata-endpoint blocking via `extra_hosts`
- DB containers exempt from hardening (official images need chown)

Port assignment via `PreviewPortPool` (`ServerSocket(0)` ephemeral port or
scan of `preview.port-range-start..end` = 10000..10100).

### Cloud Run mode

`CloudRunClient` shells out to `gcloud run deploy` with
`--allow-unauthenticated`, IAM patch (`allUsers` -> `roles/run.invoker`),
env-vars YAML file. Three retries with backoff `[2s, 5s, 10s]` for transient
errors (429/5xx/UNAVAILABLE/INTERNAL).

Health check: 3 attempts x 15s timeout, status `<500` counts as healthy.

### VM mode

`PreviewDeploymentService` SCPs the project to the target VM (selected by
lowest active preview count), SSHs to run
`/opt/preview-engine/bin/deploy-preview.sh`, parses the `PREVIEW_JSON:` line,
and writes a Caddy config on the gateway if the target is a worker.
Persisted to the `preview_registry` table.

`PreviewGarbageCollector` runs every 10 minutes
(`@Scheduled(fixedRate=600_000)`), finds `status='active' AND expires_at < now`,
and calls `previewService.teardown` on each.

### Preview-engine handoff

`PreviewEngineHandoff.start(...)` builds + tags + pushes a content-addressed
image (SHA-256 over (path, contents) per audit I #58 -- was previously
hashing (path, size, mtime) which caused cache-poisoning collisions). Then
POSTs `/from-image` to the preview-engine REST surface. Engine pulls, runs,
health-probes, and registers. DELETE `/api/previews/{id}` unregisters.

`BuildCachePruneJob` keeps the main app's local BuildKit layer cache bounded:
every 6 hours runs `docker builder prune --keep-storage 10g` and prunes
preview-engine-labelled local images older than 24h.

### Workspace garbage collection

`WorkspaceArchiveJob` runs hourly (`@Scheduled(cron="0 0 * * * *")`), archives
terminal-state run workspaces older than `archiveAfterHours` (default 24) to
GCS via `WorkspaceExportService.exportWorkspace` +
`GcsWorkspaceStore.uploadZip`, then deletes the local copy.

`WorkspaceGcsUploadListener` listens for `PipelineEvent.RunCompleted` /
`RunFailed` events and uploads the workspace zip to GCS for VM-restart
resilience; also runs a 5-min fallback sweep via
`@Scheduled(fixedRate=300_000)` with ShedLock.

Workspaces archive into
`gs://executespec-workspaces/{env}/workspace/{runId}.zip` with a legacy
`workspaces/{runId}.zip` fallback. The bucket has a 30-day lifecycle delete
rule (`infra/modules/storage/main.tf`).

---

## 11. Manual deploy escape hatches

Scripts under `scripts/` that operators can run directly when CI is broken
or for one-off operations.

### Bootstrapping secrets

`infra/scripts/seed-secrets.sh` (116 lines, one-time migration script).
Reads the plain-text `config/api-keys` file from GCS and seeds matching
Secret Manager secrets (prefixed `executespec-*`). Maps 13 env var names ->
secret suffixes: `ANTHROPIC_API_KEY` -> `anthropic-api-key`, etc. Creates
secret if missing with `automatic` replication and `app=executespec` label,
then adds a version with the value. Loops through `config/smtp-credentials`
afterward.

Usage:

```bash
./infra/scripts/seed-secrets.sh <GCP_PROJECT> <DEPLOY_BUCKET>
```

### Local crash-resilience test

`scripts/test-resilience.sh` (309 lines). Boots Spring Boot via
`mvn spring-boot:run`, submits a multi-task V2 run, polls until WORKING,
SIGKILLs (or SIGTERM via `SIGNAL=TERM`) the JVM mid-flight, restarts the
JVM, asserts the run reaches terminal status. Validates
`StartupRunResumer` + `RunOrchestrator.resumeAfterCrash`.

```bash
scripts/test-resilience.sh
# Exits 0 on success; 1-6 for various failure modes
# Logs: /tmp/executespec-resilience/{app.log, app-after-restart.log, ...}
```

### Database / workspace wipe (dev only)

`scripts/dev-wipe-runs-and-projects.sh` (60 lines) takes a `pg_dump -Fc` of
the local DB into `~/executespec-backups/`, runs the companion `.sql` to
TRUNCATE CASCADE all run/project tables, then clears the local `workspace/`
directory and Redis keys (`project:*`, `sse:*`, `run:*`).

`scripts/dev-cleanup.sh` (196 lines) is the VM-targeted equivalent that uses
`DELETE FROM` (not TRUNCATE) inside a transaction, resets monthly-run
counters, restores `run_topup.remaining = quantity`, and removes GCS
archives. Requires interactive `yes` confirmation. Preserves
users/orgs/projects/billing/deployment-creds/slack-integrations.

> Engineering-honest: the two scripts overlap but cover slightly different
> tables -- `dev-cleanup.sh` omits the V131-V139 enterprise-readiness tables
> that `dev-wipe-runs-and-projects.sql` truncates. Operational drift risk.

### Eval / benchmark drivers

These submit runs against a running backend (local or
`https://dev.executespec.ai`) and emit JSON + markdown reports:

| Script                          | Purpose                                                                                                  |
| ------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `scripts/v2-eval.sh`            | 3 bands x 3 providers (anthropic/openai/gemini) = 9 runs. Outputs `scripts/v2-eval-results/`              |
| `scripts/v2-eval-strict.sh`     | S3/S4/S5 strict specs x 3 providers. Captures `verifiedScore` + `reviewerVerdict` + `finalBuildPassed`   |
| `scripts/exhaustive-eval.sh`    | 16 specs x 3 providers in parallel. Critic report with failure-pattern detection                          |
| `scripts/benchmark-run.sh`      | 9-run Go/No-Go production-readiness benchmark (Anthropic + OpenAI only, Google skipped). 5-dim scoring  |
| `scripts/e2e-test.sh`           | Per-provider 7-language matrix with retry/share-link/preview/GitHub push tests                            |
| `scripts/e2e-matrix-test.sh`    | 5 specs x 3 providers, sequential or parallel. Emits Devil's-Advocate critical report                    |
| `scripts/e2e-preview-test.sh`   | 3 providers x 2 spec sizes with preview poll. Verdict per test: PASS / WARN / FAIL                       |
| `scripts/test-providers.sh`     | Two specs x 2 providers. 4-dimension per-provider box                                                    |
| `scripts/project-view-e2e.sh`   | Project workbench E2E across 5 phases (12 repos, memory compaction at 20+30 marks, chat)                 |
| `scripts/hostel-e2e.sh`         | 10-step iterative E2E for the "HostelMgmt" project, $25 budget ceiling                                   |

Most default `BASE_URL` to `http://localhost:8080`; `e2e-matrix-test.sh`
defaults to `https://dev.executespec.ai` -- easy to misfire on shared dev.

### Per-slice status messengers

`scripts/slice-email.py` (356 lines) renders mobile-friendly inline-CSS HTML
email and sends to `admin@executespec.ai` via SMTP+STARTTLS. Reads SMTP
credentials from `application-local.yml` (no PyYAML -- has a tiny inline
YAML reader).

`scripts/slice-telegram.py` (214 lines) renders compact HTML
(Telegram cap 4096 chars; `<b>` / `<code>` only) and POSTs to
`https://api.telegram.org/bot<token>/sendMessage`. Loads creds with priority:
env vars `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` -> `~/.executespec/telegram.json`
-> `application-local.yml`.

### Trust-pack PDF render

`scripts/trust-pack/render.mjs` (250 lines) generates the Trust Pack PDF for
the V3 trust page. Renders a hardcoded HTML mirror of `V3Trust.tsx` via
Playwright headless Chromium to a printable Letter-sized PDF dropped at
`frontend/public/trust-pack.pdf`. Run via `node scripts/trust-pack/render.mjs`.

### Build & test commands

```bash
# Backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q

# Frontend
npm --prefix frontend run build
npm --prefix frontend run format:check
npm --prefix frontend run lint

# Run locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
```

---

## Appendix: key file references

| Concern                        | Path                                                                                                                       |
| ------------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| CI workflow                    | `.github/workflows/deploy.yml`                                                                                             |
| Lint workflow                  | `.github/workflows/lint.yml`                                                                                               |
| Maven build                    | `pom.xml`                                                                                                                  |
| Env template                   | `.env.template`                                                                                                            |
| Base Spring config             | `src/main/resources/application.yml`                                                                                       |
| Dev profile                    | `src/main/resources/application-dev.yml`                                                                                   |
| Prod profile                   | `src/main/resources/application-prod.yml`                                                                                  |
| Local profile                  | `src/main/resources/application-local.yml`                                                                                 |
| Flyway migrations              | `src/main/resources/db/migration/V*.sql` (head V249)                                                                       |
| Terraform root                 | `infra/main.tf`, `infra/variables.tf`, `infra/outputs.tf`, `infra/backend.tf`                                              |
| Terraform modules              | `infra/modules/{network,database,cache,storage,compute,compute/sa,loadbalancer,secrets}/`                                   |
| GCE startup script             | `infra/scripts/startup.sh`                                                                                                 |
| Secret bootstrap               | `infra/scripts/seed-secrets.sh`                                                                                            |
| Cloud Run deploy client        | `src/main/java/com/navneet/executespec/deployment/CloudRunClient.java`                                                     |
| Cloud Run deploy orchestrator  | `src/main/java/com/navneet/executespec/deployment/DeploymentService.java`                                                  |
| Customer Cloud Run path        | `src/main/java/com/navneet/executespec/deployment/engine/DeployEngineOrchestrator.java`                                   |
| Cloud Build user-side images   | `src/main/java/com/navneet/executespec/deployment/engine/UserImageBuilder.java`                                            |
| Preview lifecycle              | `src/main/java/com/navneet/executespec/deployment/preview/PreviewService.java`                                             |
| VM preview deployer            | `src/main/java/com/navneet/executespec/deployment/preview/vm/PreviewDeploymentService.java`                                |
| Preview-engine handoff         | `src/main/java/com/navneet/executespec/deployment/preview/engine/PreviewEngineHandoff.java`                                |
| Dev Dockerfile generator       | `src/main/java/com/navneet/executespec/deployment/preview/DevDockerfileGenerator.java`                                     |
| Workspace archive job          | `src/main/java/com/navneet/executespec/deployment/WorkspaceArchiveJob.java`                                                |
| GCS upload listener            | `src/main/java/com/navneet/executespec/deployment/WorkspaceGcsUploadListener.java`                                         |
| GCS workspace store            | `src/main/java/com/navneet/executespec/deployment/GcsWorkspaceStore.java`                                                  |
| Workspace restore              | `src/main/java/com/navneet/executespec/deployment/WorkspaceRestoreService.java`                                            |
| ProgramBench runner            | `scripts/programbench/programbench_runner.py`                                                                              |
| ProgramBench config            | `scripts/programbench/programbench.yaml`                                                                                   |
| Run orchestrator               | `src/main/java/com/navneet/executespec/integration/RunOrchestrator.java`                                                   |
| Multi-repo push                | `src/main/java/com/navneet/executespec/integration/scm/ScmPushService.java`                                                |
