# ExecuteSpec

ExecuteSpec is an experimental, spec-driven AI coding platform. It turns a natural-language
request into a plan, executes coding tasks against one or more repositories, and records build,
test, review, usage, and publication evidence.

The backend is Java 21 / Spring Boot 3.5. The web application is React 18 / TypeScript / Vite
and is packaged into the same JAR. PostgreSQL with pgvector and Redis provide persistent state
and coordination.

## What is here

- BUILD: classification, planning, optional human plan approval, task execution, review, and validation.
- PATCH: a worker loop for targeted changes and benchmark adapters.
- Project and repository context, document retrieval, chat, and generated architecture documents.
- Provider routing and usage accounting; caller-owned SCM credentials for repository access.
- Durable plan decisions, workspace generations, recovery, and publication checks.
- Optional billing, deployment, preview, and observability integrations.

This is a research/development codebase. Available runtime surfaces and feature flags constrain
what can execute. A successful build, passing repository tests, LLM review, and the reported
verified score are different kinds of evidence; none alone proves every requested behavior.
Unattended operation and generated-code execution require deliberate sandbox and spend policies.

## Local development

Install JDK 21, Maven, Node.js, npm, and Docker. Set `JAVA_HOME` to JDK 21; on Homebrew macOS:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

Copy `.env.template` to a local `.env`, replace placeholders, and export the values into the
process environment using your preferred environment loader. Spring Boot does not automatically
read a root `.env` file. At minimum, configure a strong `JWT_SECRET`, your selected provider keys,
and the database credentials. Shared environments also require configured credential encryption.

Start disposable local PostgreSQL/pgvector and Redis:

```bash
export POSTGRES_PASSWORD="$(openssl rand -hex 24)"
docker compose up -d
export SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD"
export JWT_SECRET="$(openssl rand -hex 48)"
```

The default database is `executespec`, user `postgres`, at `localhost:5432`; Redis is at
`localhost:6379`. Docker volumes persist database state. Keep the same database password when
restarting an existing volume; changing the environment does not rotate an initialized database.

Build the web app before starting the backend:

```bash
npm --prefix frontend ci
npm --prefix frontend run build
mvn spring-boot:run
```

Open `http://localhost:8080`. For frontend development, run `npm --prefix frontend run dev` in
another terminal and use `http://localhost:5173`; Vite proxies `/api` to port 8080.
OAuth, billing, mail, previews, deployment, and live provider tests require additional credentials
and setup. The cloud profiles and `infra/` are deployment examples, not a turnkey local setup.
Applied Flyway migrations must remain immutable.

## Verification

```bash
mvn verify
npm --prefix frontend test
npm --prefix frontend run build
npm --prefix frontend run format:check
npm --prefix frontend run lint
python3 scripts/check-public-release.py
```

The default Maven suite excludes tests tagged `e2e`, `integration`, or `chaos`. The integration
profile requires Docker and its declared external dependencies:

```bash
mvn -Pintegration test
```

Provider-backed tests require a separate explicit live-provider opt-in and spend authorization;
they are not enabled by the commands above. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Live pipeline qualification

The authenticated pipeline E2E is not part of normal `mvn verify`: Surefire excludes the `e2e`
tag unless `-Pintegration` is explicit. Each live invocation runs exactly one lifecycle scenario
(`happy`, `reject`, or `cancel`) on the non-frontier DeepSeek Flash route with a hard `$0.10`
per-run cap. Reserve the UUID before starting the test so the provider guard can match the first
planner dispatch:

```bash
export E2E_SCENARIO=happy
export E2E_RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
export RESERVATION_ID="$(
  node scripts/provider-budget-ledger.mjs reserve \
    --provider deepseek \
    --model deepseek-v4-flash-native \
    --run "$E2E_RUN_ID" \
    --safe-max-usd 0.10
)"

JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn -Pintegration,live-llm-test \
    -Dtest=PipelineE2ETest \
    -Dexecutespec.live-llm.reservation-id="$RESERVATION_ID" \
    test
```

Use a fresh UUID and reservation for every scenario. Afterward, reconcile the reservation with the
run's recorded cost or use `close-unknown` to charge the full reserved `$0.10` conservatively.
Missing scenario, UUID, live-test profile, or reservation skips the provider-backed scenario; the
router independently remains fail-closed.

## Code map

| Path | Responsibility |
| --- | --- |
| `src/main/java/com/navneet/executespec/` | Backend packages and package-local contributor notes |
| `src/main/resources/prompts/` | Agent prompts and fragments |
| `src/main/resources/db/migration/` | Forward-only Flyway migrations |
| `frontend/src/console/` | Protected application shell |
| `frontend/src/ide/` | Run/project and other route implementations |
| `frontend/src/signal/` | Public pages and shared UI primitives |
| `frontend/public/` | Canonical static public assets; Vite copies these into its build |
| `scripts/` | Verification, evaluation, and operator tools |
| `docker/` | Generated-code sandbox toolchain |
| `infra/` | Optional GCP deployment examples and benchmark infrastructure |
| `observability/` | Optional local metrics/tracing stack |

Generated web output under `src/main/resources/static/`, local workspaces, captures, and runtime
reports are ignored. Their source inputs and reproducible tools belong in Git.

[Documentation](DOCS.md) · [Contributor instructions](AGENTS.md) · [Security](SECURITY.md)

## License

Copyright 2026 Navneet Prabhakar. Licensed under [Apache License 2.0](LICENSE).
Third-party dependencies retain their respective licenses.
