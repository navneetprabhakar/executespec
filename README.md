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

See the [BUILD execution contract](docs/architecture/build-path-contract.md) for acceptance,
recovery, publication boundaries and the current bounded provider verification checkpoint.

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
(`happy`, `reject`, `cancel`, or `build`) on an explicitly pinned provider route with a default `$0.10`
per-run cap. `E2E_COST_CAP_USD` can raise that cap up to `$0.50` for a full reviewer pass;
reserve the same maximum in the ledger before dispatch. Reserve the UUID before starting the test
so the provider guard can match the first planner dispatch. The `build` scenario adds a generated Python library and independent held-out
behavior checks inside a disposable strict sandbox.

The default route is `DEEPSEEK/deepseek-flash-native` (current V4.1 Flash). Set `E2E_PROVIDER`
(case insensitive) and `E2E_MODEL` to one of these bounded current routes:

| Provider | Primary fixture model | Secondary fixture model |
| --- | --- | --- |
| Anthropic | `claude-haiku-5-5` | `claude-sonnet-5-5` |
| OpenAI | `gpt-6-luna` | `gpt-6.1-sol` |
| Gemini | `gemini-3.8-flash` | `gemini-4-argon` |
| DeepSeek | `deepseek-flash-native` | `deepseek-v4-pro-native` |
| OpenRouter | `deepseek-v4.1-flash-1017` | `gemini-3.8-flash-or` |
| Fireworks | `deepseek-v4p1-flash-1017-fw` | `glm-5p3-flash-fw` |

The model catalog was refreshed against official documentation and authenticated discovery on
2026-10-18. GPT-6 uses the Responses API with model-bound encrypted reasoning replay; other
OpenAI-wire providers retain Chat Completions. New model entries are BYOK-only pending broader
qualification; the canonical native Flash alias retains the existing managed/free eligibility. Gemini introductory pricing needs review before December 2026 (Argon) and
January 2027 (Flash). Historical IDs remain readable for persisted runs.

Other fixture models are rejected before startup. Each reservation must match the exact
provider/model/run tuple, and only that provider's credential is needed. This qualifies the selected
route, not every model offered by the provider. If the Anthropic key requires workspace routing,
set `ANTHROPIC_WORKSPACE_ID`; its header is attached only to the matching configured platform key.


Before reserving provider spend, validate the local toolchain with
`docker/sandbox-toolchain/validate.sh <image-tag>`, push it to a registry, and set
`EXECUTESPEC_E2E_SANDBOX_IMAGE` to its exact `repository@sha256:...` reference. Set
`EXECUTESPEC_E2E_RUNTIME_UID` and `EXECUTESPEC_E2E_RUNTIME_GID` to the non-root host workspace owner's
numeric ids. The fixture uses isolated PostgreSQL and Redis containers and enables runtime flags
only inside that disposable database. Automatic preview/deployment is disabled.

A key-free startup/runtime preflight is available with `E2E_RUNTIME_PREFLIGHT=true` and
`mvn -Pintegration -Dtest=PipelineE2ETest -Dgroups=e2e test`. Provider cases deliberately skip
without their separate scenario, key, and reservation. The preflight does not authorize LLM calls.

```bash
export E2E_SCENARIO=happy
export E2E_PROVIDER=DEEPSEEK
export E2E_MODEL=deepseek-flash-native
export E2E_COST_CAP_USD=0.10
export E2E_RUN_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
export RESERVATION_ID="$(
  node scripts/provider-budget-ledger.mjs reserve \
    --provider deepseek \
    --model deepseek-flash-native \
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
Live fixtures retain the ignored generated workspace and write a minimal status/cost snapshot under
`target/live-e2e-evidence/` for diagnosis; ordinary key-free fixtures clean up their workspace.
Missing scenario, UUID, live-test profile, or reservation skips the provider-backed scenario; the
router independently remains fail-closed. The private snapshot includes report findings, task
results and exact verification receipts; bounded transport errors redact configured credentials.
Provider-free checks preserve diagnostic workspaces from earlier invocations.

Verification sessions retain ownership during slow model/read phases. Their dedicated heartbeat
uses `executespec.sandbox.verification-heartbeat-interval-ms` (default `10000`, maximum `30000`).
The Fireworks defaults now use DeepSeek V4.1 Flash; obsolete saved explicit model pins require
reselection. Historical run results remain unchanged.

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

## BUILD verification boundaries

BUILD tasks are accepted from deterministic build/test evidence on an exact workspace generation.
New shared-kernel plans bootstrap once and can continue from a healthy baseline. Corrections retain
the accepted task frontier and re-verify its required artifacts and tests. Unsupported shared-kernel
runtime plans are rejected before approval. Python manifest-only bootstraps can use the declared
project type and exact manifest path without repeating the language in task prose. The deterministic bootstrap regression fixtures cover
Python and non-HTTP Node JavaScript; full provider/runtime qualification remains separate.

Local-hands BUILD submission is unavailable until client replies carry generation-bound acceptance
and checkpoint receipts. Client capability discovery reports that restriction even when the local
transport flag is enabled. Use remote BUILD execution for this path.

Current runs persist their original design and continuation settings for recovery. A run lacking
that immutable policy cannot resume through the new recovery path; start a fresh run rather than
silently changing confirmation requirements. Historical artifacts remain readable.

The local regression suites use deterministic provider substitutes and real build/test commands;
PostgreSQL tests also exercise forward migrations and durable authority. Live provider end-to-end
qualification requires an explicit API key and the reserved budget procedure above. No deployment
or shared rollout flag is enabled by a local test.

Provider qualification also enforces explicit plan-task counts, maintained bootstrap configuration
tests, artifact rebinding after plan repair, and PostgreSQL-compatible approval/resume money
rounding. First-task retries require proven rollback to the initial workspace; publication still
requires an accepted generation. Bounded Claude 5.5 classifier/accuracy calls use low-effort visible
output settings while normal generation retains provider-default reasoning. Repository evidence
preserves UTF-8 with legacy-encoding fallback, and read-only reviewers receive fresh server-run
test evidence for the exact reviewed generation without receiving mutation tools. Explicitly
requested text artifacts are included in assessment evidence, and quiet pytest results retain
aggregate execution counts without fabricating per-test collection errors.
The live library fixture checks fourteen independent ASCII and Unicode behavior cases, including
lowercasing before ASCII filtering; passing only the generated candidate's own tests is insufficient.

## License

Copyright 2026 Navneet Prabhakar. Licensed under [Apache License 2.0](LICENSE).
Third-party dependencies retain their respective licenses.
