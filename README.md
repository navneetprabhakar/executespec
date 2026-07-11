# ExecuteSpec

Spec-driven AI coding platform. A customer submits a spec in natural language, the V2 pipeline plans, codes across one or more repos, builds + tests, and opens a pull request with a deterministic verified score. A human approves at the plan gate; everything after is automatic. Backend is a single Spring Boot 3.5 JAR (Java 21) serving the React 18 / Vite 5 frontend out of `static/`. Postgres + Redis are the only required infra; observability and billing are pluggable. The codebase is structured so the same Worker tool surface drives both the build-from-scratch path (`runMode=BUILD`) and the patch-mode path (`runMode=PATCH`) that powers the SWE-bench / ProgramBench adapters and four customer-facing autofix surfaces.

## Versions

| Component         | Version             | Source                                                                 |
| ----------------- | ------------------- | ---------------------------------------------------------------------- |
| Project           | `2.0.0`             | [`VERSION`](VERSION), [`Version`](Version), [`pom.xml`](pom.xml) `<version>` |
| Spring Boot       | `3.5.14`            | [`pom.xml`](pom.xml) `<parent>` (bumped from `3.3.0` by Dependabot #132, 2026-05-16) |
| Spring AI         | `1.1.6`             | [`pom.xml`](pom.xml) `<spring-ai.version>` (BOM import)                |
| Flyway head       | `V258`              | `src/main/resources/db/migration/V258_*.sql`                           |
| Frontend          | `2.0.0`             | [`frontend/package.json`](frontend/package.json) `"version"`           |

`VERSION` and `Version` are kept identical by hand; the project's own conventions reference `Version` as the source of truth. The frontend ships under the same release stamp as the backend.

## Stack at a glance

| Layer               | Tech                                                                                | Source of truth                                                                 |
| ------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| JVM / language      | Java 21                                                                             | [`pom.xml`](pom.xml) `<java.version>`                                            |
| Framework           | Spring Boot 3.5.14 (Web, Data JPA, Security, OAuth2 client, Mail, Thymeleaf, Actuator, AOP, Validation, Retry, Data Redis) | [`pom.xml`](pom.xml) deps 1, 5, 14–20, 30, 36–39, 60 |
| LLM SDK             | Spring AI 1.1.6 starters for Anthropic, OpenAI, Google GenAI                        | [`pom.xml`](pom.xml) deps 2–4                                                    |
| LLM auto-config     | Provider auto-configurations disabled in favor of manual `AgentModelConfig` beans   | [`src/main/java/com/navneet/executespec/ExecutespecApplication.java`](src/main/java/com/navneet/executespec/ExecutespecApplication.java) |
| Database            | PostgreSQL (Cloud SQL in prod), Flyway migrations head V258                         | [`pom.xml`](pom.xml) deps 6–8, `src/main/resources/db/migration/`                 |
| Cache / lock        | Redis (Lettuce), ShedLock 7.7.0                                                     | [`pom.xml`](pom.xml) deps 30–32                                                  |
| Auth                | JJWT 0.13.0 + Spring Security OAuth2 client (Google, GitHub)                        | [`pom.xml`](pom.xml) deps 14–18                                                  |
| Billing             | Stripe 32.1.0, Razorpay (HMAC), invoice PDFs via OpenPDF 2.0.3 + flying-saucer 9.4.0 | [`pom.xml`](pom.xml) deps 21–22, 56; HMAC via commons-codec 1.22.0 (dep 35)     |
| GCS / Cloud Run     | google-cloud-{storage, run, build, artifact-registry, resourcemanager, secretmanager}; xDS and Conscrypt transitive clients are excluded from the fat JAR | [`pom.xml`](pom.xml) deps 23–29                                                |
| Code intelligence   | JGit 6.9.0, JavaParser 3.28.1 (no symbol-solver), Tika 3.3.0 with document/code/text/PDF/Microsoft parsers retained and unsupported Apple/audio/CAD/crypto/font/image/mail/news/OCR/package/webarchive/XMP parsers excluded | [`pom.xml`](pom.xml) deps 9–11, 59                                              |
| Observability       | Micrometer + OTel bridge + Prometheus registry; structured JSON via logstash encoder; local Grafana / Prometheus / Tempo stack | [`pom.xml`](pom.xml) deps 40–48, [`observability/docker-compose.yml`](observability/docker-compose.yml) |
| Build               | Maven; Spotless 3.4.0 (google-java-format 1.22.0), JaCoCo 0.8.14, Surefire (excludes `e2e,integration,chaos`) | [`pom.xml`](pom.xml) `<build><plugins>` |
| Frontend            | React 18.3, React Router 6.22, Zustand 4.5, TypeScript 5.3, Tailwind CSS 4.3, Vite 5.1 | [`frontend/package.json`](frontend/package.json), [`frontend/vite.config.ts`](frontend/vite.config.ts), [`frontend/tailwind.config.ts`](frontend/tailwind.config.ts) |
| Charts / UI         | Monaco editor 4.7, d3 7.9, recharts 2.12, framer-motion 11, lucide-react, cmdk, Radix Tabs, react-markdown + remark-gfm | [`frontend/package.json`](frontend/package.json) |
| Frontend telemetry  | OpenTelemetry web SDK 2.7 + auto-instrumentations 0.63 + OTLP HTTP exporter; PostHog | [`frontend/package.json`](frontend/package.json), [`frontend/src/main.tsx`](frontend/src/main.tsx) |
| Typography          | JetBrains Mono (variable) — `font-family: 'JetBrains Mono', ui-monospace, ...` for both `sans` and `mono` aliases | [`frontend/tailwind.config.ts`](frontend/tailwind.config.ts), [`frontend/src/index.css`](frontend/src/index.css) |
| CI                  | GitHub Actions: `lint.yml` on PRs to `dev`/`main`; `deploy.yml` on push to `dev` and `benchmark` | [`.github/workflows/lint.yml`](.github/workflows/lint.yml), [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) |
| Cloud               | GCP us-east4 — single GCE VM serves prod + dev; Cloud Run for on-demand previews; Workload Identity Federation for deploy auth | [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) |

## Repository layout

```
.
├── pom.xml                       Maven build descriptor (parent SB 3.5.14, Spring AI 1.1.6 BOM)
├── VERSION / Version             Single-line version stamps (2.0.0)
├── lombok.config                 Stop-bubbling + Spring @Qualifier propagation for constructor injection
├── .env.template                 Copy-to-.env env var template (LLM keys + integrations)
├── src/main/java/com/navneet/executespec/
│   ├── ExecutespecApplication.java  Spring Boot entry; excludes provider auto-configs
│   ├── agent/                    OutlinePlanner, WorkerAgent, LlmRouter, SmartClassifier, ...
│   ├── harness/                  SequentialExecutor (per-task tier-routed Worker + smoke build)
│   ├── reviewer/                 ReviewerAgent (general + wiring passes)
│   ├── tool/                     11 tools (readFile, writeFile, editFile, shell, gitDiff, ...) + ToolRegistry
│   ├── integration/              RunOrchestrator + scm/ScmPushService
│   ├── codeintel/ memory/        Project workbench: codemaps, architecture pipeline, RAG, ledger
│   ├── prreview/ codereview/ finding/  PR Review + Code Review agents + four-surface autofix chokepoint
│   ├── billing/ auth/ ratelimit/ Tier + payment + JWT + scoped SSE token paths
│   ├── swebench/ programbench/   Cleanroom container adapters for the patch-mode pipeline
│   ├── agentsession/             Durable CLI/VS Code agent-session protocol (V240, /api/agent/*)
│   └── ... (76 package roots total under com.navneet.executespec)
├── src/main/resources/
│   ├── application.yml           Single source of truth for model matrix, provider routes, pricing, failover
│   ├── db/migration/             Flyway V*.sql (head V258, immutable post-apply)
│   ├── prompts/                  External LLM prompts (assembled by PromptAssembler)
│   └── static/                   Vite build output served by Spring Boot
├── src/test/java/                Unit + ArchUnit guard tests (e.g. AccessGuardCoverageTest)
├── frontend/
│   ├── package.json              React 18, Vite 5, Tailwind 4, TS 5.3
│   ├── vite.config.ts            `@` → `./src`, dev port 5173, proxies /api → http://localhost:8080
│   ├── tailwind.config.ts        Var-driven theme (CSS custom properties), class-based dark mode
│   ├── public/                   logo-{192,512}.png, manifest.webmanifest, sw.js (PWA shell cache)
│   └── src/
│       ├── App.tsx               Top-level routes + LegacyRedirect for retired /v3, /v4, /signal paths
│       ├── main.tsx              React entry, OTel init, service-worker registration (prod only)
│       ├── tokens.css            Graphite + Signal design tokens (:root dark default, [data-theme='light'] overrides)
│       ├── index.css             Global stylesheet (Tailwind v4, mobile/iOS Safari fixes, component utilities)
│       ├── console/              Protected web control-plane shell (ConsoleShell + ConsoleHome + ConsoleDecisions)
│       ├── ide/                  Route-level protected surfaces for runs/projects/org/billing/benchmark
│       ├── signal/               Public pages (auth/marketing/legal/shared) + AdminPage + shared components reused by the IDE
│       ├── design-system/        Color primitives + low-level tokens (loaded before tokens.css)
│       └── hooks/ stores/ lib/ types/  Cross-cutting React + Zustand + API client + telemetry
├── observability/                Local Prometheus + Grafana + Tempo (docker compose up -d)
├── .github/workflows/            CI: lint.yml + deploy.yml (dev + benchmark push trigger)
└── docs/                         All project docs (see "Where to go next")
```

Note on the frontend tree: the legacy `/v3/`, `/v4/`, `/components/`, and `/pages/` directories under `frontend/src/` were retired in commit `5f5e4aaa`. The protected web chrome is now the **Console** control plane (`console/`); `App.tsx` mounts `ConsoleShell` for every protected route. The old `ide/IdeWorkspace` shell and `localStorage es:console` opt-in path are gone, while `ide/` remains the route-level implementation layer for runs, projects, org settings, billing, benchmark tracking, and related surfaces. The retired Signal chrome's pages were ported then deleted in the 2026-05-23 cleanup, leaving `signal/` with the public pages (auth/marketing/legal/shared), `AdminPage`, and shared components reused by protected surfaces. `App.tsx` keeps a `LegacyRedirect` to absorb bookmarks of the old paths.

## Run it locally

**Prerequisites**

- Java 21 (Temurin recommended). On macOS, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` is the project convention.
- Maven (or use the wrapper if present).
- Node.js LTS (matches the `lts/*` pin in CI).
- Docker (for local Postgres, Redis, and the observability stack).
- PostgreSQL 14+ and Redis reachable on the defaults (`localhost:5432` / `localhost:6379`) — Flyway will create the schema on first boot.
- At least one LLM API key. The backend boots without any keys (provider auto-configs are excluded and `AgentModelConfig` degrades gracefully) but any run that needs that provider will fail.

**Backend**

```bash
cp .env.template .env                      # fill in keys
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS only
mvn spring-boot:run                        # serves on http://localhost:8080
```

**Frontend (dev mode with HMR)**

```bash
cd frontend
npm ci
npm run dev                                # http://localhost:5173
```

The Vite dev server proxies `/api` to `http://localhost:8080` (overridable via `VITE_BACKEND_URL` to run multiple worktrees side-by-side, per the inline note in [`frontend/vite.config.ts`](frontend/vite.config.ts)).

Run submit advanced options include continuation controls: "Confirm model upgrades" maps to `requireConfirmationBeforeExpensiveContinuation`, while "Require benchmark evidence" maps to `requireBenchmarkPriorForAutomaticContinuation`. Benchmark evidence is not required by default, so the quality-ranked selected candidate can run automatically within the normal continuation ceilings; checking the benchmark guard sends an explicit per-run `true` override that approval-gates unbenchmarked model/provider upgrades.

Operators can set `execution.continuation.low-complexity-worker-start-tier=LIGHTWEIGHT` to start LOW worker tasks on the cheaper tier while preserving automatic step-up; the default remains `WORKHORSE`. Web Compose, web/CLI/VS Code agent confirmation, and legacy spec-chat `submit_run` can send `lowComplexityWorkerStartTier=LIGHTWEIGHT` without setting `modelName`, so it is not a route pin. Run-detail continuation policy evidence shows both the configured and effective LOW start tier, so invalid values that fell back to `WORKHORSE` stay visible.

**Local observability (optional)**

```bash
cd observability
docker compose up -d
# Grafana http://localhost:3001  (admin/admin)
# Prometheus http://localhost:9090
# Tempo API http://localhost:3200
```

Prometheus scrapes the Spring Boot app at `host.docker.internal:8080/actuator/prometheus` every 15s; Grafana auto-provisions both datasources and two dashboards (JVM + HTTP, and ExecuteSpec product/pipeline metrics).

## Build / test / lint

**Backend**

```bash
mvn compile -q                  # quick compile
mvn verify -q                   # full gate: spotless:check + JaCoCo report + Surefire (unit tests only)
mvn spotless:apply              # auto-format to google-java-format 1.22.0
mvn -Pintegration verify        # opt-in integration tests (e2e, integration, chaos groups)
```

`mvn verify` runs Spotless in `check` mode (the same gate CI enforces in `lint.yml`) and binds JaCoCo so a coverage report lands under `target/site/jacoco/`. Surefire excludes the `e2e`, `integration`, and `chaos` JUnit groups by default; pass `-Pintegration` (or the equivalent profile your CI uses) to run them.

**Frontend**

```bash
cd frontend
npm run build                   # tsc strict compile + vite build → ../src/main/resources/static
npm run format                  # prettier --write
npm run format:check            # prettier --check (CI gate)
npm run lint                    # eslint (--max-warnings 9999; severity gated via eslint config)
npm run lint:fix                # eslint --fix
```

`npm run build` writes the production bundle into `src/main/resources/static` with `emptyOutDir: true`, so it wipes and rebuilds the Spring Boot static folder. Run `mvn package` afterward to embed the bundle in the JAR. The fat JAR intentionally prunes unused heavyweight transitive dependencies from Spring AI Anthropic, Tika, and Google client libraries; keep `DocumentExtractorTest` green when changing parser exclusions.

## Where to go next

The long-form documentation now lives in the central docs repo — see [`DOCS.md`](DOCS.md):

→ **https://github.com/navneetprabhakar/executespec-docs/tree/main/backend** — architecture · product · operations · security · design · plans · ADRs · reason-logs · business · competitive analysis · reference.

The REST API spec remains in this repo at [`docs/openapi.yaml`](docs/openapi.yaml).

## Required environment variables

Copy [`.env.template`](.env.template) to `.env` and fill in values. Names below match the template verbatim.

**Required**

| Variable               | Purpose                              |
| ---------------------- | ------------------------------------ |
| `ANTHROPIC_API_KEY`    | Anthropic provider (Claude)          |
| `OPENAI_API_KEY`       | OpenAI provider (GPT)                |
| `GOOGLE_API_KEY`       | Google GenAI provider (Gemini; retained for operator regression while buyer-facing Gemini is temporarily darked) |
| `GITHUB_OWNER`         | Default GitHub owner / org           |
| `RAZORPAY_KEY_ID`      | Razorpay test or live key            |
| `RAZORPAY_KEY_SECRET`  | Razorpay key secret                  |
| `RAZORPAY_WEBHOOK_SECRET` | HMAC for webhook verification     |
| `RAZORPAY_WEBHOOK_URL` | Webhook callback URL                 |

The application boots without LLM keys (provider auto-configs are excluded; manual beans degrade gracefully), but any pipeline that needs a missing provider will fail. The template foregrounds Razorpay because the dev default gateway is Razorpay; prod default is Stripe (toggle via `BILLING_DEFAULT_GATEWAY=stripe`).

The Spring AI provider settings resolve the variables above (and the optional `OPENROUTER_API_KEY`,
`DEEPSEEK_API_KEY`, and `FIREWORKS_API_KEY`) at startup; do not edit provider keys into the tracked
YAML configuration.

**Optional**

| Variable                                                                                   | Default / notes                                                       |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD`  | `localhost` / `5432` / `executespec` / `postgres` / `postgres`        |
| `REDIS_HOST`                                                                               | `localhost`                                                           |
| `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` / `STRIPE_WEBHOOK_SECRET`                   | Required when `BILLING_DEFAULT_GATEWAY=stripe`                        |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`                                    | Enables Google OAuth2 login                                           |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET`                                    | Enables GitHub OAuth2 login                                           |
| `SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` / `SLACK_SIGNING_SECRET`                         | Enables Slack integration                                             |
| `APP_SURFACE_RETURN_URL`                                                                   | Browser OAuth callback URL (e.g. `http://localhost:5173/auth/callback`) |
| `DASHBOARD_URL`                                                                            | Dashboard URL used in Slack and notification links                    |

Additional environment variables consumed at runtime (cookies, CORS, mail, preview engine, OTel) are set per-environment by [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) (`env-patch.sh`) rather than committed to the template.
