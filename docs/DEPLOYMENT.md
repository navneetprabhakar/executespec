# ExecuteSpec — Deployment Guide

> **Target audience:** engineers running ExecuteSpec locally or deploying to Google Cloud Platform.
> **Last updated:** 2026-05-12 (1.0.0-M1 · migration head V186).
>
> For long-form ops runbooks (cookie-domain switch, Stripe price IDs, SSO/SAML wiring, DPA flow, payment-history reference, status-page operator), see the dedicated docs under `docs/`.

---

## 1. Local development

### Prerequisites

- Java 21. On macOS: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.
- PostgreSQL 15+ on `localhost:5432`.
- Redis 7+ on `localhost:6379`.
- Node.js 18+ + npm (for frontend build).
- Maven 3.8+ (`mvn -version`).
- API keys: `ANTHROPIC_API_KEY` (required — default provider since 1.0.0-M1), `OPENAI_API_KEY` (required — embeddings + openai-targeted runs).

### Database setup

```bash
# Create the database
psql -U postgres -c "CREATE DATABASE executespec;"
```

Flyway migrations run automatically on startup (current head: **V186**). For the full per-migration index, see [`CODEMAP.md`](CODEMAP.md) §Migrations. Operational notes:

- `application.yml` sets `spring.flyway.out-of-order=true` + `ignore-migration-patterns: *:missing` so boot tolerates the V119 orphan in `flyway_schema_history` (left over from the preview-engine repo split).
- Migration filenames are immutable post-apply (Flyway tracks by exact filename).

### Migration range summary

| Range | Theme |
|-------|-------|
| V1–V44 | Core schema, auth, runs, tasks, billing baseline, integrations baseline. |
| V45–V73 | Stripe gateway, OAuth2 login, feature flags, Bitbucket, RLS, event log + heartbeat, side-effect outbox, data retention, prompt audit. |
| V74–V90 | Guardrail level seed, project shell + repo registry, validation-report scores, codebase intelligence (codemaps + arch), assessment + figma_url, run code map, project memory, execution view, multi-run solutions. |
| V91–V102 | pgvector + project documents (V95), auto-push (V96-V97), backfill provider (V99), run_repo_snapshot (V100), project_repo.webhook_installed (V101), project_spec_template (V102). |
| V103–V115 | execution_run.branch (V103), continue_pr (V105), validation_report.verified_score + scoring.v2-verified flag (V106), status_reason (V107), planner requirement graph (V109), @Version optimistic lock (V112), project_generated_doc (V113-V114), repo_architecture_doc (V115). |
| V116–V120 | Memory polish, plan-version tooling, LlmProvider GOOGLE → GEMINI rename (V120, rewrites legacy rows). |
| V121–V129 | Persona-roadmap waves — tier lifecycle (V121-V124), activation funnel + sandbox (V125-V126), SSO + GitHub-app schema (V127), provider routing log (V128), startup-program (V129). |
| V130 | Hierarchical planner schema (`plan_strategy`, `plan_outline_json`, per-phase cost columns). |
| V131–V139 | Enterprise readiness — `.executespec/` folder status + activity feed + contracts + standards + ADRs + invariants + notification prefs. |
| V140–V146 | Tier-config alignment, ladder consolidations, annual billing plans. |
| V147–V164 | v2.7 Foundation — audit-chain, governance, retention, multi-org seam. |
| V165–V168 | Drop MAX tier (rename to TEAM @ $39, V165), align FREE billing plan (V166), revert FREE to 5 (V167), pause/resume columns (V168). |
| V169–V178 | PR record `project_id`/`kind` (V169), CI auto-fix attempts (V170), R09 reconcile (V171), PR + Code review agents (V172), widen review columns (V173), arch-doc cost columns (V174), external sharing (V175), Solo tier (V176/V177, retired by V179/V185), user cost cap (V178). |
| V179–V186 | Drop product split (V179), lint autofix flag (V180), PR comment inbox (V181), `run_mode` (V182), `triggered_by_finding` (V183), widen `last_llm_failure` to TEXT (V184), retire Solo branding (V185), PR CI status columns (V186). |

### Redis setup

```bash
docker run -d -p 6379:6379 redis:7-alpine
# or
brew install redis && brew services start redis
```

ExecuteSpec uses Redis for: feature flag cache, Shedlock (scheduled jobs), rate-limit windows, gate coordination, `ContextStore` worker snapshots (24h TTL), `ProviderHealthTracker` circuit breaker, `ProgramBenchContainerStore` runId→containerId map, `StalenessService` cache. Default in-memory config is fine for dev.

### Environment variables

```bash
# Required
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANTHROPIC_API_KEY=sk-ant-...    # default provider since 1.0.0-M1
export OPENAI_API_KEY=sk-...           # required for embeddings + openai-targeted runs

# Optional — LLM
export GOOGLE_API_KEY=AIza...

# Optional — Postgres + Redis (defaults: localhost/postgres/postgres)
export POSTGRES_USER=postgres
export POSTGRES_PASSWORD=postgres
export REDIS_HOST=localhost

# Optional — Integrations
export GITHUB_PAT=ghp_...
export GITHUB_OWNER=your-github-username

# Optional — OAuth2 login
export GOOGLE_OAUTH_CLIENT_ID=...
export GOOGLE_OAUTH_CLIENT_SECRET=...
export GITHUB_OAUTH_CLIENT_ID=...
export GITHUB_OAUTH_CLIENT_SECRET=...

# Optional — Billing
export RAZORPAY_KEY_ID=...
export RAZORPAY_KEY_SECRET=...
export STRIPE_SECRET_KEY=...
export STRIPE_WEBHOOK_SECRET=...
export STRIPE_PUBLISHABLE_KEY=...
export STRIPE_TEAM_MONTHLY_PRICE_ID=price_...
export STRIPE_ENTERPRISE_PRICE_ID=price_...
export BILLING_DEFAULT_GATEWAY=stripe   # razorpay | stripe (prod default: stripe; dev: razorpay)
export BILLING_STRIPE_ENABLED=true

# Optional — Slack
export SLACK_CLIENT_ID=...
export SLACK_CLIENT_SECRET=...
export SLACK_SIGNING_SECRET=...

# Optional — Frontend URL (used in email links)
export DASHBOARD_URL=http://localhost:8080

# Optional — Analytics
export POSTHOG_ENABLED=false           # flip on prod when project exists
export POSTHOG_API_KEY=
export POSTHOG_HOST=https://us.i.posthog.com

# Optional — Observability
export OTEL_SAMPLING_PROBABILITY=1.0
export OTEL_METRICS_ENABLED=false
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces

# Optional — Admin
export ADMIN_NOTIFICATION_EMAIL=admin@executespec.ai
export ADMIN_REMINDER_TOKEN=...        # shared secret for /api/internal/reminder/send

# Optional — Telegram bridge (operator-only)
# Per-user secrets file at ~/.executespec/telegram.json with {bot_token, chat_id}
```

For ops env vars specific to a feature, see:
- [`docs/stripe-price-ids-ops.md`](stripe-price-ids-ops.md) — full Stripe env-var checklist.
- [`docs/cookie-domain-switch.md`](cookie-domain-switch.md) — `AUTH_COOKIE_DOMAIN` cutover plan.

### Run locally

```bash
# Option 1: Supervisor (recommended — auto-restart + health monitoring)
./supervisor.sh start

# Option 2: Manual
npm --prefix frontend install
npm --prefix frontend run build
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -DskipTests

# Option 3: Vite dev server proxy (faster frontend iteration)
# Terminal 1: backend
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run
# Terminal 2: frontend (proxies /api → :8080)
npm --prefix frontend run dev
```

Open `http://localhost:8080`. Default admin: `admin@executespec.ai` / `Admin@123` (forced password change on first login).

### Build gates

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q      # spotless + tests
npm --prefix frontend run build
npm --prefix frontend run format:check                    # prettier
npm --prefix frontend run lint                            # eslint
```

CI runs `mvn spotless:check` + `npm run format:check` + `npm run lint` on every PR to `dev`/`main` (`.github/workflows/lint.yml`, ~2 min). The deploy workflow runs `mvn verify` + frontend build + GCE deploy + health check on push to `dev`/`main`.

### Local observability stack

```bash
cd observability && docker compose up -d
```

- Grafana: http://localhost:3001 (admin/admin)
- Prometheus: http://localhost:9090
- Tempo: http://localhost:3200

Dashboards: ExecuteSpec Overview + JVM/HTTP.

### Feature flag administration

```bash
# List all feature flags (requires SUPER_ADMIN role)
curl http://localhost:8080/api/feature-flags -H "Authorization: Bearer $JWT"

# Update a feature flag
curl -X POST http://localhost:8080/api/feature-flags/scoring.v2-verified \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"enabled":true}'
```

Notable flags (default OFF in prod unless noted): `scoring.v2-verified`, `validation.v3-strict`, `assessment.llm.enabled`, `billing.lifecycle.sunsetting.enabled`, `v2.pause.enabled`, `ci.autofix.enabled`, `provider.smart-routing.enabled`, `security.guardrail_level` (LOW / MEDIUM / HIGH).

---

## 2. GCP deployment

### Overview

Single GCE VM in `us-east4` runs the Spring Boot app + serves prod + dev. Cloud Run hosts on-demand previews. Cloud SQL Postgres + Memorystore Redis are managed. Cloudflare WAF in front of `*.executespec.ai`. Artifact Registry holds Docker images.

```
Cloudflare ──► GCE VM (Spring Boot :8080) ──► Cloud SQL (Postgres HA)
                    │                  ├──► Memorystore (Redis 7)
                    │                  ├──► GCS (workspace archive + JAR releases)
                    │                  ├──► Secret Manager (env vars / API keys)
                    │                  └──► docker compose exec ──► local containers
                    │                                                (test exec, programbench)
                    └──► Cloud Run on-demand previews
                              │
                              ├──► Artifact Registry (per-run images)
                              └──► Cloud Logging + Monitoring
```

### Prerequisites checklist

- GCP project with billing enabled (`gcloud projects create executespec-prod`).
- IAM: Owner role on the project for setup; once configured, a dedicated service account (see Step 1).
- gcloud CLI authenticated (`gcloud auth login`).
- Terraform 1.5+ (config under `infra/`).
- `gh` CLI authenticated for GitHub Actions secrets.

### Step-by-step

1. **Project setup** — service account `executespec-deployer@<project>.iam.gserviceaccount.com` with roles: Cloud SQL Client, Storage Object Admin, Secret Manager Secret Accessor, Service Account User, Cloud Run Admin, Artifact Registry Writer, Compute Instance Admin, IAP-Secured Tunnel User.
2. **Network + VPC** — VPC + subnet + private services access for Cloud SQL + Memorystore. NAT gateway for outbound traffic.
3. **Cloud SQL (Postgres 15)** — HA primary + read replica. Private IP only. `executespec` database created on first migration apply.
4. **GCS buckets** — `executespec-workspaces` (run workspace archive, nearline storage class), `executespec-releases` (JAR + frontend bundle), `executespec-uploads` (user document uploads for RAG).
5. **Memorystore Redis** — Standard HA tier, 1 GB memory, private VPC peering.
6. **Secret Manager** — store API keys + OAuth secrets + Stripe + Razorpay credentials.
7. **GCE VM** — `n2-standard-4` (4 vCPU / 16 GB RAM), Ubuntu 22.04, Docker + JDK 21 installed via startup script. Systemd unit pulls latest JAR from GCS on boot.
8. **Cloud Run** — `executespec-preview` service. Per-request scaling; max instance count 50.
9. **Artifact Registry** — `executespec-preview-images` repo (Docker).
10. **Cloudflare** — DNS + WAF rules + caching + Origin Certificate for `*.executespec.ai`.
11. **GitHub Actions secrets** — `GCP_SA_KEY`, `GCE_VM_HOST`, `GCE_VM_USER`, ENV-var secrets per `application-prod.yml`.

Full Terraform under `infra/`. Reach out to platform team for the per-step gcloud commands if Terraform drifts.

### Deploy workflow (`.github/workflows/deploy.yml`)

Triggered on push to `dev` or `main`. Steps:

1. Checkout + JDK 21 + Node 18 setup.
2. `mvn verify` (spotless + tests).
3. Frontend `format:check` + `lint` + `build`.
4. Build JAR (`mvn package -DskipTests`).
5. Upload JAR to `gs://executespec-releases/<sha>.jar`.
6. SSH via IAP to GCE VM → pull new JAR → systemd restart → wait for health.
7. Curl `https://(dev.)executespec.ai/api/health` until `UP|DEGRADED`.

### Lint workflow (`.github/workflows/lint.yml`)

Triggered on every PR to `dev` or `main`. Parallel jobs:

- **Java** — `mvn -B spotless:check`.
- **Frontend** — `npm run format:check` + `npm run lint`.

Failure messages point at the local fix command (`mvn spotless:apply` / `npm run format` + `npm run lint -- --fix`).

### Health check

```bash
curl https://dev.executespec.ai/api/health
# expects 200 with body containing "UP" or "DEGRADED"
```

`/api/health` checks Postgres + Redis + LLM provider reachability. DEGRADED tolerates one provider outage; UP requires all green.

### Rollback

```bash
# SSH to VM
gcloud compute ssh executespec-prod --tunnel-through-iap --zone us-east4-c

# Switch to previous JAR
ls /opt/app/releases/
sudo ln -sf /opt/app/releases/<previous-sha>.jar /opt/app/current.jar
sudo systemctl restart executespec
```

Flyway migrations are forward-only. A rollback after a destructive migration requires a follow-up `V_{n+1}` migration.

### Operational dashboards

- **Cloud Trace** + **Cloud Monitoring** exporters via `@Profile("gcp")` (configured in `config/ObservabilityConfig.java`).
- Prometheus scrape at `https://(dev.)executespec.ai/actuator/prometheus` (auth required).
- Logs: Cloud Logging — search by `traceId` for end-to-end trace pull.
- Custom V2 metrics: `executespec.v2.{run,planner,task,worker,llm,reviewer,context}.*`.

---

## 3. Backups

- **Postgres:** Cloud SQL automated backups (daily, 7-day retention). PITR enabled.
- **Workspaces:** archived to `gs://executespec-workspaces` on run terminal (30-day Nearline retention).
- **Secret Manager:** versioned automatically.

Restore drill cadence: quarterly. Runbook lives in `infra/runbooks/postgres-restore.md`.

---

## 4. Operations references

- Cookie-domain switch (Phase 2 surface split): [`cookie-domain-switch.md`](cookie-domain-switch.md).
- Stripe price-ID checklist: [`stripe-price-ids-ops.md`](stripe-price-ids-ops.md).
- Payment history (Razorpay + Stripe `payment_audit`): [`payment-history.md`](payment-history.md).
- Better Stack status-page runbook: [`status-page.md`](status-page.md).
- SSO/SAML wiring + lockout-prevention rationale: [`sso-saml-wiring.md`](sso-saml-wiring.md).
- DPA request → drafting → signature → activation: [`dpa-flow.md`](dpa-flow.md).
- SLO definitions + alert thresholds: [`operations/slos.md`](operations/slos.md).
- Pending plans (distributed processing, VS Code extension, Vite split): [`plans/pending.md`](plans/pending.md).
