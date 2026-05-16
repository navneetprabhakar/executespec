# ExecuteSpec

Spec-driven AI coding platform. Customer submits a spec in natural language; the V2 pipeline plans, codes across one or more repos, builds + tests, and opens a pull request with a deterministic verified score. The human approves at the plan gate; everything after is automatic.

Version: `1.0.0-M1` (see [`VERSION`](VERSION) and [`pom.xml`](pom.xml)). Flyway migration head: **V199** ([`src/main/resources/db/migration/V199__sync_org_tier_with_subscription_tier.sql`](src/main/resources/db/migration/V199__sync_org_tier_with_subscription_tier.sql)).

---

## Stack at a glance

| Layer | Tech | Source of truth |
|---|---|---|
| Backend | Java 21, Spring Boot 3.5.14, Spring AI 1.1.6 | [`pom.xml`](pom.xml) |
| Build tool | Maven (single JAR; spotless + jacoco bound to `verify`) | [`pom.xml`](pom.xml) `<build>` |
| Frontend | React 18.3, TypeScript 5.3, Vite 5.1, Tailwind 4.3 | [`frontend/package.json`](frontend/package.json) |
| State | PostgreSQL (JPA + Flyway), Redis (Lettuce + ShedLock) | [`pom.xml`](pom.xml), [`src/main/resources/application.yml`](src/main/resources/application.yml) |
| LLM providers | Anthropic, OpenAI, Gemini (native tool calling end-to-end) | `application.yml model-matrix` |
| Auth | Spring Security + JJWT 0.13 + OAuth2 (Google, GitHub) | [`src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`](src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java) |
| Billing | Stripe + Razorpay (routed by currency) | [`pom.xml`](pom.xml), `billing.currency-gateway-map` in `application.yml` |
| Observability | Micrometer + OTel; Prometheus, Tempo, Grafana locally; Cloud Trace + Cloud Monitoring on GCP | [`observability/docker-compose.yml`](observability/docker-compose.yml) |
| Cloud | GCP us-east4 — Cloud SQL (Postgres), GCS, Cloud Run (on-demand previews), Cloud Build, Artifact Registry, Secret Manager | `pom.xml` `google-cloud-*` deps |

---

## Repository layout

```
.
├── pom.xml                          Maven build + dep manifest
├── VERSION                          Plain version file (matches pom.xml <version>)
├── lombok.config                    Stops Lombok bubbling; copies @Qualifier
├── .env.template                    Sample env vars; copy to .env locally
├── README.md, CLAUDE.md, CHANGELOG.md   Repo entry-point docs
├── src/main/java/com/navneet/executespec/   72 top-level packages (see docs/CODEMAP.md)
├── src/main/resources/
│   ├── application.yml              Base config (local defaults)
│   ├── application-dev.yml          dev profile (GCE VM)
│   ├── application-prod.yml         prod profile (GCE VM)
│   ├── db/migration/V*.sql          Flyway migrations, head V199 (199 files)
│   ├── prompts/                     LLM prompts (.md, .txt), grouped by agent
│   └── static/                      Built frontend bundle (populated by vite build)
├── src/test/java/                   Unit + integration + chaos + e2e + arch tests
├── frontend/
│   ├── package.json
│   ├── vite.config.ts               Dev server proxies /api → :8080
│   ├── tailwind.config.ts
│   ├── public/                      PWA manifest + sw.js + trust-pack asset
│   └── src/                         App.tsx routes; signal/, components/, v3/, v4/, pages/
├── docs/                            See docs/document_map.md for the full index
├── observability/                   Local Grafana + Prometheus + Tempo docker-compose
├── scripts/                         v2-eval matrix, e2e probes, dev wipe, benchmark
├── evaluation-specs/                Sample spec files used by eval scripts
├── infra/                           Terraform modules + environments for GCP
├── website/                         Marketing site (separate static bundle)
└── workspace/                       Per-run work area (gitignored at runtime)
```

---

## Run it locally

Prereqs: Java 21, Maven 3.9+, Node LTS, Docker (for local Postgres + Redis + the observability stack), and at least one LLM API key.

```bash
# 1. Database + Redis (local docker, or use system installs)
#    Postgres expected at localhost:5432, db `executespec`, user/pass `postgres/postgres`
#    Redis expected at localhost:6379
#    (Defaults in src/main/resources/application.yml; override via env or application-local.yml)

# 2. Environment
cp .env.template .env
#  Required: ANTHROPIC_API_KEY (or OPENAI_API_KEY / GOOGLE_API_KEY)
#  Optional: GITHUB_PAT, OAuth2 client IDs, Razorpay / Stripe keys, REDIS_HOST

# 3. Backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS dev requires this
mvn spring-boot:run
#  Listens on :8080. Health: GET /api/health (HealthController, no actuator dependency).
#  Flyway runs on boot; V199 head is applied automatically.

# 4. Frontend (separate terminal)
cd frontend
npm install
npm run dev
#  Vite serves on :5173; proxies /api → :8080 (see vite.config.ts)
#  Override:  VITE_PORT=5174 VITE_BACKEND_URL=http://localhost:8081 npm run dev
```

Optional — local observability stack:

```bash
cd observability && docker compose up -d
# Grafana http://localhost:3001 (admin/admin)
# Prometheus :9090, Tempo OTLP :4317/:4318, Tempo API :3200
```

---

## Build, test, lint

```bash
# Backend
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

mvn compile -q                 # fast type-check only
mvn verify -q                  # full build; runs unit tests + spotless:check + jacoco
mvn spotless:apply             # auto-format Java to google-java-format
mvn -B spotless:check          # CI gate (lint.yml runs this on every PR)

# Surefire excludes groups e2e, integration, chaos by default (see pom.xml).
# Run the heavyweight suites manually:
mvn verify -Pintegration

# Frontend
npm --prefix frontend run build         # tsc + vite build → src/main/resources/static
npm --prefix frontend run format        # prettier --write
npm --prefix frontend run format:check  # CI gate
npm --prefix frontend run lint          # eslint, --max-warnings 9999
```

CI lives in [`.github/workflows/`](.github/workflows): `lint.yml` runs spotless + prettier + eslint + `npm audit` (warn-only) on every PR against `dev`/`main`; `deploy.yml` runs on push to `dev` and deploys to the dev GCE VM.

---

## Where to go next

| Need | File |
|---|---|
| Architecture, request flow, run lifecycle | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| Mechanical index (packages, controllers, agents, tools, migrations) | [`docs/CODEMAP.md`](docs/CODEMAP.md) |
| Index of every doc in this repo | [`docs/document_map.md`](docs/document_map.md) |
| Auth, roles, tier gates | [`docs/RBAC.md`](docs/RBAC.md) |
| Deployment | [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) |
| Local-test playbook | [`docs/operations/local-test-playbook.md`](docs/operations/local-test-playbook.md) |
| SLOs | [`docs/operations/slos.md`](docs/operations/slos.md) |
| API surface | [`docs/openapi.yaml`](docs/openapi.yaml), [`docs/JAVADOC.md`](docs/JAVADOC.md) |
| Frontend → backend wiring | [`docs/frontend-api-usage.md`](docs/frontend-api-usage.md) |
| Design tokens + code style | [`docs/design/ui-tokens.md`](docs/design/ui-tokens.md), [`docs/design/code-style.md`](docs/design/code-style.md) |
| Per-package code-adjacent context | `src/main/java/com/navneet/executespec/<pkg>/CLAUDE.md` (9 packages) |

---

## Required environment variables

The minimum to boot is one LLM key (Anthropic is the default provider since `1.0.0-M1`; Spring Boot will start without keys but every LLM-touching endpoint will fail).

```bash
ANTHROPIC_API_KEY=
OPENAI_API_KEY=
GOOGLE_API_KEY=            # Gemini

GITHUB_PAT=                # for PR creation + check-runs polling
GITHUB_OWNER=

# Optional
GOOGLE_OAUTH_CLIENT_ID= / GOOGLE_OAUTH_CLIENT_SECRET=
GITHUB_OAUTH_CLIENT_ID= / GITHUB_OAUTH_CLIENT_SECRET=
RAZORPAY_KEY_ID= / RAZORPAY_KEY_SECRET= / RAZORPAY_WEBHOOK_SECRET=
STRIPE_SECRET_KEY= / STRIPE_PUBLISHABLE_KEY= / STRIPE_WEBHOOK_SECRET=
SLACK_CLIENT_ID= / SLACK_CLIENT_SECRET= / SLACK_SIGNING_SECRET=
REDIS_HOST=                # default localhost
POSTHOG_API_KEY= / POSTHOG_HOST=
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

See [`.env.template`](.env.template) for the canonical list.

---

## License

Internal. All rights reserved.
