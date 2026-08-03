# ExecuteSpec

Spec-driven AI coding platform. A customer submits a spec in natural language, the V2 pipeline plans, codes across one or more repos, builds + tests, and opens a pull request with a deterministic verified score. A human approves at the plan gate; everything after is automatic. Backend is a single Spring Boot 3.5 JAR (Java 21) serving the React 18 / Vite 5 frontend out of `static/`. Postgres + Redis are the only required infra; observability and billing are pluggable. The codebase is structured so the same Worker tool surface drives both the build-from-scratch path (`runMode=BUILD`) and the patch-mode path (`runMode=PATCH`) that powers the SWE-bench / ProgramBench adapters and four customer-facing autofix surfaces.

ExecuteSpec has capabilities across code generation, pull request creation, CI status, CI autofix,
deployment, and deployment tracking. This is not yet one enforced end-to-end release gate: the
unattended CI-autofix path remains feature-flagged, and deployment does not currently require proof
of merge plus green CI. Runtime operations and incident response are a separate future PilotSRE
boundary: the intended handoff is the existing A2A contract plus deployment evidence, with
PagerDuty, New Relic, and similar integrations still roadmap items rather than shipped ExecuteSpec
capabilities.

The Web Agent stores complete chat snapshots and exposes paginated conversation history, but a chat
snapshot is not a restorable filesystem image. Projects remain the canonical durable repository
context: the agent can select an existing project or hand off to the normal Projects creation flow
and return with that project selected. Closing a Web Agent workspace archives its conversation
after active work is clear; it does not delete the linked project.
Repository linking uses the caller's stored SCM credential. If that credential cannot see a
private GitHub or Bitbucket repository, Web and scoped CLI clients receive the same safe,
provider-specific instruction to grant repository access or reconnect the integration; the failure
does not create a partial repository or project link.
Within a selected project, exact repository search is paired with a read-only bounded source-range
tool: the agent can expand a `file:line` match into up to 200 lines of method/signature context from
the linked repository's revision-pinned clone. Reads are project-authorized, repository-relative,
symlink-contained, and exclude Git metadata and credential-like paths/content.
Plan Review's “Start revised run” uses the canonical Start composer and restores both the revised
request and its project/repository scope; it no longer falls through the project landing redirect.
Exact HTTP paths in a submitted request retain their namespace through planning: `/route` and
`/api/route` are distinct producer contracts, and an added or removed `/api` prefix is rejected
before approval or code-generation spend. Explicit method-rejection acceptance such as
`POST /route must return 405` is preserved as a negative contract: the forbidden method must remain
absent while another supported method proves the path exists, so a 404 cannot masquerade as the
requested behavior. Web Agent streaming also treats a parsed terminal `done` as authoritative when
a proxy resets the connection immediately afterward.

The BUILD planner preserves exact behavioral clauses, keeps focused maintained tests with the
production task they verify, and bounds task breadth to avoid one runaway worker context. Workers
reject disposable root-level smoke scripts as test substitutes and stop repeated same-path rewrite
loops that do not obtain a passing test/build. Downloadable source archives omit interpreter and
test-runner caches such as `__pycache__`, `.pytest_cache`, and compiled Python bytecode.
Stale project architecture refresh runs durably in the background instead of blocking the planner.
For monorepos, build and test verification discovers nested manifest roots but selects only modules
with actual Git working-tree changes; ambiguous unscoped modules are reported unavailable rather
than building unrelated applications. A provider output cap before the first write gets one bounded
same-route mutation recovery, whose output still has to pass the ordinary build, review, and
deterministic validation gates.

Terminal run results use the validation report's reviewer verdict and product-file count as the
customer-facing truth. Bootstrap/workspace metadata remains visible in Explorer but is not labeled
generated product code. “Run again” preserves the persisted provider/model route; changing provider
is a separate, explicit paid action that clears an exact model pin.

Terminal server-owned runs can be continued through
`POST /api/runs/{sourceRunId}/iterate` with `{ "userRequest": "..." }`. The endpoint creates and
starts a new BUILD run, records `sourceRunId`, inherits the source route/project/repository and
execution/publication policies, and initializes from a fail-closed Git-tracked workspace snapshot.
Callers may provide `Idempotency-Key`; the route requires write access to the source run.

The protected Web surfaces send caller-owned idempotency keys for create, fork, and iterate
mutations. A key is retained in the current browser tab only while the outcome is ambiguous, so a
retry or same-tab reload replays the same server claim instead of creating duplicate work; a
successful, definitively rejected, changed, or deliberately repeated action receives a fresh key.
The tab ledger stores only a SHA-256 digest of the canonical request plus the idempotency key and
timestamp; spec/request text is never written to sessionStorage.
Project Start Work also exposes the existing exact per-run model override to Enterprise users; the
selection applies to that run only and does not alter the project or organization matrix. Its
catalog comes from the authenticated run-submitter model endpoint, so Enterprise developers do not
need ORG_ADMIN access merely to select an allowed exact route.

Active run detail overlays persisted cost with the higher JVM-owned live cost snapshot; terminal
runs always expose stored durable cost. Effective route evidence keeps `MIXED` for multiple physical
models, but retains the common provider when every tier stayed on that provider, so a provider-only
request is not falsely shown as cross-provider divergence. Exact model pins remain strict.
Terminal result pages render the server-owned outcome verbatim with exactly one primary next action;
logs, evidence, and files remain secondary diagnostics. User cancellation is presented as a neutral
stop, while platform failures retain a support code without exposing internal exception detail.

Run-event publication uses bounded per-run lock striping to order Redis replay append, local
delivery, Redis publication, subscription installation, and completion; unconstrained Spring
listeners run afterward. Remote Redis callbacks are sequence-reordered in a bounded buffer, and a
typed terminal event closes peer streams only after it is delivered. A bounded terminal marker
makes later subscribers replay and close. Each connected Web/CLI subscriber has one ordered
replay/live lane, bounded FIFO, and virtual-thread writer, so a
disconnected or non-reading socket cannot stall the run orchestrator, Redis listener, event
producer, or another subscriber. Heartbeats detect quiet half-open sockets; a 30-second blocked-send
watchdog retires them, while stuck writer/close work remains bounded and admission-accounted.
Buffer exhaustion retires only that client and relies on `Last-Event-ID` replay; terminal stream
completion is queued after already accepted frames so final status/cost/completion events are not
truncated.

Standalone remote `repoMode=EXISTING` runs clone `repoUrl` into the run workspace before the first
model call and fail closed unless the checkout contains Git metadata plus a deterministic source
inventory. Project-linked runs retain their multi-repository admission path, source-run iterations
retain their required parent snapshot copy, and local-hands runs retain the client working-tree
handshake.

Plan-gate decisions are durable and restart-safe. V348 routes APPROVE, REJECT, and pre-approval
CANCEL through one atomic `PlanGateDecisionService` transaction, persists byte-stable canonical
decision evidence plus a request fingerprint, and updates gate/run/outbox state together. The
receipt cross-binds provider, execution mode, separate fixed-six-decimal `estimateHighUsd` and
nullable `hardCapUsd`, expected/confirmed credits, payer, continuation, contract report, and the
exact persisted typed acknowledgement. A complete pre-V348 acknowledgement tuple is retained
byte-for-byte: `BREAKING` validity is trim/case-insensitive, while receipt/report equality remains
exact. An omitted
legacy client plan fingerprint remains absent with a null match result; a supplied fingerprint must
match, and ENFORCED RequirementIR approval requires one. An
approved continuation follows `WAITING → READY → PROCESSING → COMPLETED` under a fenced lease;
COMPLETED means the dispatcher atomically handed `APPROVAL_RESUMING` to normal durable `WORKING`
recovery, not that the run finished. The dispatcher validates the leased database tuple before
workspace restore and revalidates it at the atomic handoff. Approval execution starts only after
that commit, and startup recovery uses the outbox rather than directly resubmitting
`APPROVAL_RESUMING`. Versioned gate/outbox evidence and referenced/acknowledged reports reject
direct child deletion while parent-run deletion retains its normal cascade; the new report→run FK
is `NOT VALID` only so historical orphan rows do not block deployment while every new write is
checked. Recovery tasks are registered before executor submission to avoid stale active-run
handles when an inline/fast executor finishes immediately. The
`requirement.ir.gate.mode` flag remains `LEGACY`.

V349 corrects external-collaborator approval without weakening tenant isolation: the run and
project remain in the host organization, while the immutable gate receipt records the submitting
user's payer/credential organization. REST submit, confirmed Web Agent cloud runs, forks, and the
unified Execution View now share `CustomerRunSubmissionService`, so project write authority,
explicit linked-repository selection, provider/key/spend preflight, and durable idempotency are
applied before dispatch. Pending RequirementIR V1 gates are decoded and approved through a frozen
V1 wire codec and semantic validator rather than the rolling current-schema implementation.

Committed backend checkpoint `13ead3f40` carries that V350 separation through execution:
`pipeline_run.org_id` and CUSTOMER `llm_execution_context.org_id` remain the host data/governance
tenant, while `pipeline_run.billing_org_id` and `llm_execution_context.credential_org_id` retain
the submitting actor's payer/provider-policy organization for key-policy revalidation and managed-
AI debit. The migration is forward-only and installs separate PostgreSQL rejection triggers for
`billing_org_id` and `credential_org_id`; application admission and dispatch guards independently
enforce the same immutable binding before spend. The V350 migration/immutability and related named
PostgreSQL gates pass independently, and clean Java 21 verification passes 5,738/5,738 with zero
failures, errors, or skips. The same locally verified source shape preserves the
source run's host tenant while assigning the current actor as payer on BUILD iterations, and makes
server-internal finding PATCH submission resolve its active host project and active actor before
dedupe or dispatch.

The current deployment candidate advances the migration source head to V372. V354 adds
rollout-gated operation-fenced provider-attempt admission; its default remains
`EXECUTESPEC_LLM_OPERATION_ADMISSION_MODE=LEGACY`, so this deployment does not activate the new
admission path. V355 adds bounded oldest-first recovery for stale streamed agent turns and never
replays provider transport. V356 preserves complete Web Agent transcripts with integrity metadata
and adds tenant-safe archive/close plus paginated conversation history. V357 persists the exact
per-task routing tier, category, and source before provider dispatch. V358 makes generated project
documents durable asynchronous jobs with exact-route admission, fenced publication, reloadable
status, and bounded terminal-job retention. V359 gives each immutable GitHub check-run/head-SHA
failure a unique database claim before CI autofix submission, binds the canonical PATCH run in the
next fenced transaction, and permits a later delivery to retry after a fenced failed submission or
an expired claim with no discoverable run. V360 persists a run-bound canonical Spring HTTP surface
at brownfield admission and blocks route removal after workers, corrections, validation, and
immediately before publication unless an exact durable `BREAKING` gate receipt authorizes it. V361
binds every new validation report and ledger entry to a canonical Git-tree workspace generation.
V362 persists deterministic workflow/config evidence and the exact build/test commands, working
directories, exit codes, and bounded outputs used for validation. V363 requires explicit
confirmation before retrying a generated-document job whose provider outcome is uncertain.
The forward migration widens the durable status column before admitting the longer
`NEEDS_CONFIRMATION` state, so PostgreSQL recovery writes cannot fail after provider execution.
V364 claims each exact continuation proposal with one durable approval id before queueing; an
in-flight duplicate conflicts, while queued/completed duplicates replay that receipt and cannot
queue or spend twice. Because task-boundary, post-review, and post-validation approvals all execute
a code-mutating worker, every server-side approved continuation now uses the same reversible
checkpoint, stale-evidence invalidation, exact approved-route fencing, read-only final review,
deterministic validation, and accept-or-rollback lifecycle.
Exact HTTP requirements and the admitted brownfield surface are checked before the read-only
reviewer spends tokens, then checked again inside the final-validation chokepoint used by normal,
crash-recovery, retry-validation, and re-review paths.
V365 timestamps every continuation-approval transition. A stale pre-queue claim is safely released;
a stale queued correction without a fresh run heartbeat fails closed, invalidates publication
evidence, and requires a fresh run instead of guessing whether mutation or provider spend occurred.
Approved corrections now execute against a reversible Git-tree checkpoint, invalidate earlier
evidence before mutation, reject no-op/testless output, fence reviewer and validation evidence to
one generation, and roll back both workspace and durable validation state when any deterministic
gate regresses. Their durable resolution records before/after generations, changed paths and a diff
digest, plus a bounded unified patch with truncation/redaction metadata and a typed before/after
validation delta. V366 persists the exact deterministic causal gate for each newly generated zero
headline; historical and nonzero rows remain null, and readers never reverse-infer a cause. The
correction delta covers build, tests, workflow/config, protected contracts, reviewer and score.
SCM publication and permanent deployment reject stale/unbound evidence, unverified tests, failed
builds, and approved-route mismatches before any external side effect. The read-only
`GET /api/runs/{id}/publication-eligibility` contract previews those same deterministic gates
without rewriting comparison evidence; the run Evidence tab renders route, build, test,
workflow/config, protected-contract, reviewer result, physical reviewer route, correction
generations/diff/invalidation, freshness, and publication facts independently rather than treating
the internal score as publication proof.
Validation-report responses expose their lifecycle status; a stale/pending/failed report suppresses
its previously computed pipeline result, and the Evidence UI presents regeneration state instead of
relabeling old validation as current. Public report links reject those non-complete rows instead of
publishing earlier scores or test claims. Known non-HTTP reports omit endpoint coverage from both score
numerator and denominator rather than receiving a penalty or synthetic midpoint credit. V367
persists that runtime-surface applicability; V368 disables legacy Apply-to-Run until it uses the
correction lifecycle; V369 persists crash-recoverable correction authority; V370 aligns correction
digest column types with Hibernate validation; and V371 persists skipped tests separately. A fresh
exit-zero suite is publication-green only when at least one test passed and
`passed + skipped == total`; all-skipped, malformed, failed, errored, non-zero-exit, or unverified
suites remain blocked.
Historical plan estimates are tenant- and route-scoped: V372 fingerprints the successful physical
provider/model set, corrected persisted task count, and canonical LOW/MEDIUM/HIGH distribution, so
an exact model selection no longer loses compatible history or silently borrows costs from another
model, failover route, or materially cheaper task mix. Project-generated
setup/explanation documents exclude recursive model-generated architecture prose, reserve exact-SHA
configuration/Compose/Spring-handler facts ahead of bulk codemap inventory, and retry or fail closed
on unsupported schema-ownership, Compose-startup, or response-representation claims.
SCM push failures expose a stable failure code, the exact blocking gate (for example `GIT_PUSH` or
`PR_CREATION`), and remediation. Correction evidence shows the complete durable task/action history
instead of only the last transaction.
The internal headline reserves zero for an explicit deterministic gate. LLM assessments may cap a
positive score no lower than 1; a zero report carries a `score-zero-cause` ledger row naming the
exact deterministic artifact, while historical unattributed zeros say that their cause is
unavailable instead of inferring one.
RequirementIR also remains `LEGACY`.

## Versions

| Component         | Version             | Source                                                                 |
| ----------------- | ------------------- | ---------------------------------------------------------------------- |
| Project           | `2.0.0`             | [`VERSION`](VERSION), [`Version`](Version), [`pom.xml`](pom.xml) `<version>` |
| Spring Boot       | `3.5.14`            | [`pom.xml`](pom.xml) `<parent>` (bumped from `3.3.0` by Dependabot #132, 2026-05-16) |
| Spring AI         | `1.1.6`             | [`pom.xml`](pom.xml) `<spring-ai.version>` (BOM import)                |
| Flyway head       | `V372`              | V372 route-fingerprints historical cost cohorts; next V373               |
| Frontend          | `2.0.0`             | [`frontend/package.json`](frontend/package.json) `"version"`           |

`VERSION` and `Version` are kept identical by hand; the project's own conventions reference `Version` as the source of truth. The frontend ships under the same release stamp as the backend.

### Strict sandbox toolchain (dark)

`docker/sandbox-toolchain/Dockerfile` builds the representative Java/Maven, Node/Prettier,
Python/Black, Go, and Rust toolchain from a digest-pinned Debian base. Run
`docker/sandbox-toolchain/validate.sh` for the local no-network, non-root, read-only-root build
matrix. A deployment must push the validated image and configure the resulting immutable
`repository@sha256:...` reference. This does not enable sandbox execution:
`sandbox.lifecycle.enabled`, `sandbox.runtime.dispatch.enabled`,
`sandbox.runtime.container-strict-v1.enabled`, and
`sandbox.runtime.toolchain-readiness.enabled` remain independently default-off.
The bind-mounted run workspace must also be owned and writable by the configured non-root
`executespec.sandbox.runtime-uid:runtime-gid` (defaults `65532:65532`). Provisioning verifies the
existing tree and refuses it without changing permissions; the deployed service/workspace
ownership must be aligned explicitly before rollout.
When strict sandbox command routing is enabled, `backgroundJob` rejects new starts rather than
launching a host process outside the container boundary; status/log/kill remain available so a
runtime-policy transition cannot strand a process that was already running.
The local validation script also exercises non-root identity, Docker-socket/host-path denial, zero
capabilities, cgroup ceilings with swap disabled, no-exec `/tmp`, metadata-network denial, and the advertised compiler
toolchains. This is not deployment readiness: a cold Maven build currently cannot resolve project
dependencies under `--network none`, so a digest-pinned dependency cache or governed artifact
egress is required before the four runtime flags can be enabled.

## Stack at a glance

| Layer               | Tech                                                                                | Source of truth                                                                 |
| ------------------- | ----------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| JVM / language      | Java 21                                                                             | [`pom.xml`](pom.xml) `<java.version>`                                            |
| Framework           | Spring Boot 3.5.14 (Web, Data JPA, Security, OAuth2 client, Mail, Thymeleaf, Actuator, AOP, Validation, Retry, Data Redis) | [`pom.xml`](pom.xml) deps 1, 5, 14–20, 30, 36–39, 60 |
| LLM SDK             | Spring AI 1.1.6 starters for Anthropic, OpenAI, Google GenAI                        | [`pom.xml`](pom.xml) deps 2–4                                                    |
| LLM auto-config     | Provider auto-configurations disabled in favor of manual `AgentModelConfig` beans   | [`src/main/java/com/navneet/executespec/ExecutespecApplication.java`](src/main/java/com/navneet/executespec/ExecutespecApplication.java) |
| Database            | PostgreSQL (Cloud SQL in prod), Flyway migrations head V372                         | [`pom.xml`](pom.xml) deps 6–8, `src/main/resources/db/migration/`                 |
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
| CI                  | GitHub Actions: `lint.yml` on PRs to `dev`/`main`; `deploy.yml` on push to `dev`/`benchmark` plus manual protected `prod` dispatch. Deploy reads `/opt/app/RUNNING_SHA` back from the target host and fails on any target-SHA mismatch before health verification. | [`.github/workflows/lint.yml`](.github/workflows/lint.yml), [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) |
| Cloud               | GCP us-east4 — single GCE VM serves prod + dev; Cloud Run for on-demand previews; Workload Identity Federation for deploy auth | [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) |

Python previews resolve bounded nested application packages: a FastAPI app declared in
`app/main.py` is launched as `app.main:app` for both VM/Docker and Cloud Run preview commands.

### Current model catalog

The 2026-07-24 provider-catalog refresh adds account-visible Claude Fable 5, GPT-5.6
Sol/Terra/Luna, Gemini 3.6 Flash, Gemini 3.5 Flash-Lite, Gemini 3.1 Flash-Lite, and the latest
curated OpenRouter agentic routes (Kimi K3, Qwen 3.7 Plus/Max, Grok 4.5). Native DeepSeek V4
Pro/Flash remains current. The Fireworks allow-list now contains only the four serverless coding
models its live account catalog actually returns: DeepSeek V4 Pro, Kimi K2.6, GLM 5.1, and GLM
5.2; persisted removed IDs resolve to a live replacement.

New frontier routes are selectable but not tier defaults until they pass ExecuteSpec's
output-quality evaluation. Models with stepped long-context pricing use the conservative upper
rate so managed-AI usage cannot be undercharged. Existing tier defaults remain benchmark-based: Sonnet 5, GPT-5.4,
Gemini 3.5 Flash/3.5 Flash-Lite, native DeepSeek V4, and the surviving Fireworks routes.

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
│   └── ... (78 package roots total under com.navneet.executespec)
├── src/main/resources/
│   ├── application.yml           Single source of truth for model matrix, provider routes, pricing, failover
│   ├── db/migration/             Flyway V*.sql (head V372; next V373; 358 source files; immutable post-apply)
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

The `local` and `dev` profiles converge release-validation gates at startup: goal-decision
enforcement, verify-before-complete, auto-verification, Ask, and screen rendering are enabled.
Remote-sandbox lifecycle, dispatch, strict runtime, toolchain readiness, and live-preview network
access remain disabled and fail closed. The initializer updates only flags already created by
migrations; a missing required row is logged without inventing configuration or failing startup.

Run submit advanced options include continuation controls: "Confirm model upgrades" maps to `requireConfirmationBeforeExpensiveContinuation`, while "Require benchmark evidence" maps to `requireBenchmarkPriorForAutomaticContinuation`. Web Compose and Project Start send both choices explicitly, so an unchecked control cannot silently inherit a stricter server fallback. Exact model pins still forbid automatic route changes; Enterprise recovery candidates remain limited to effective organization model-matrix cells. An ordered Enterprise recovery ladder is not yet implemented.

`Apply to Run` is temporarily fail-closed. V368 force-disables the legacy direct-mutation path until it uses the same checkpointed correction, evidence invalidation, deterministic validation, read-only review, rollback, and publication-admission lifecycle as approved continuation corrections.

Operator benchmark smokes are disabled by default. For a local/dev SWE-bench run, set `SWEBENCH_ENABLED=true`, `SWEBENCH_OUTPUT_BASE` to a writable directory, and `SWEBENCH_INSTANCES_FILE` to the task JSON; the base configuration binds these controls without requiring the benchmark profile. Keep `SWEBENCH_DEFAULT_PROVIDER=deepseek` for the approved DeepSeek-only smoke lane. The benchmark generation request accepts `absoluteDeadlineEpochMs` and `shutdownReserveMs`; the server tightens that deadline to `SWEBENCH_TASK_WALL_TIMEOUT_MINUTES`, keeps at least 120 seconds for cleanup/result publication, and rejects an unusable deadline before container or provider work.

Live provider fixtures are also fail-closed. Reserve an exact provider/model/run scope with
`scripts/provider-budget-ledger.mjs`, then boot with the Maven live-test profile and that reservation:

```bash
mvn -Plive-llm-test \
  -Dexecutespec.live-llm.reservation-id="$RESERVATION_ID" \
  spring-boot:run -Dspring-boot.run.profiles=local
```

The Spring Boot plugin propagates the profile's safety properties into the forked application JVM.
Without both the profile and a matching reservation, `LlmRouter` rejects provider dispatch. Reconcile
or conservatively close the reservation after the test; never reuse it for a different run.

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

Operators can set `execution.continuation.low-complexity-worker-start-tier=LIGHTWEIGHT` to start LOW worker tasks on the cheaper tier while preserving automatic step-up; the default remains `WORKHORSE`. Web Compose, web/CLI/VS Code agent confirmation, and legacy spec-chat `submit_run` can send `lowComplexityWorkerStartTier=LIGHTWEIGHT` without setting `modelName`, so it is not a route pin. Run-detail continuation policy evidence shows both the configured and effective LOW start tier, so invalid values that fell back to `WORKHORSE` stay visible.

### Project Chat paid actions

Project Chat's model-selected tool surface is read-only. Web and CLI/OpenTUI offer three explicit
human actions—regenerate architecture, generate an EXPLANATION/SETUP document, and run a linked
repository Code Review. They are buttons/commands, not proposals generated by the chat model.

Each action first issues a V292 confirmation scoped to the initiating actor, host org, project,
action, exact provider/model, canonical target, and a hard action budget. Only a SHA-256 token
digest is stored; the bearer is returned once, kept in client process memory, expires after five
minutes by default, and is consumed under a PostgreSQL row lock before any side effect. Execution
uses the action-specific route returned by `GET /api/projects/{projectId}/chat/info`, including
Enterprise org matrix overrides. It cannot adapt or fail over, including across the asynchronous
Code Review executor; an unavailable or drifting route returns 503. Usage is attributed to the
initiating actor while credential resolution remains bound to the host project subject.

Hard limits apply to the configured token and provider-call counts: architecture **1,000,000 tokens
/ 64 calls**, generated docs **200,000 / 2**, and code review **300,000 / 2**. The corresponding
**$10 / $3 / $5** ceilings are reconciled from successful provider usage; the final admitted call
can cross its dollar ceiling before a later call is denied. A consumed grant is never resurrected
after failure. Issue, deny, consume, expiry, replay, completion, and failure decisions enter the
hash-chained `activity_audit`; V292 rejects audit-row UPDATE/DELETE and serializes cross-JVM chain
appends with a PostgreSQL transaction advisory lock.

Durable Web document jobs enforce the same generated-doc token/call limits and post-call dollar
reconciliation per confirmed attempt, and use the short interactive retry envelope. If provider
dispatch began but delivery remains uncertain and nothing was published, the job requires explicit
confirmation before replay and shows a stable safe failure code; it does not silently create
another billable attempt.

### Enterprise LLM usage audit

Managed FREE accounts expose and persist only the configured native DeepSeek Flash route. A
premium provider/model submitted by an older client is normalized before the run record, plan
gate, or UI summary is created; dispatch still reauthorizes the same lock. BYOK, TEAM, and
ENTERPRISE routing remains governed by the customer's key-source and model-matrix policy.

V293 records an immutable write-ahead intent before each known physical provider operation and an
append-only terminal fact afterward. Retries, failovers, Gemini cache operations, Anthropic forced
tool fallbacks, token/cache/reasoning usage, actual key source, actor, role, feature, project and run
causality remain separately attributable. A missing terminal is surfaced as `MISSING_TERMINAL`
rather than disappearing, and managed-AI credits link to the exact usage event instead of a fuzzy
provider/model/time match.

ORG_ADMIN users can inspect their own organization in Org settings; SUPER_ADMIN users can select a
tenant in Admin analytics. JSON and formula-safe CSV exports use an inclusive organization-calendar
date range plus an explicit IANA timezone. The same guarded API is available at
`GET /api/admin/analytics/llm-usage-audit` and `/llm-usage-audit.csv`. Provider invoice comparison
remains explicitly unavailable until a real upstream invoice/request ingestion contract exists.

### Governed public validation reports

New validation-report shares use the project-share lifecycle: SHA-256 bearer storage, seven-day
default expiry, revocation, access audit, and project/run scope validation. V294 preserves
historical `/reports/shared/{token}` URLs for a bounded migration grace period while clearing their
plaintext bearers. A standalone run must be attached to a project before it can mint a public
report link.

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

For provider-free protected-Web functional coverage, run
`scripts/run-web-provider-free-functional.sh`. It launches disposable PostgreSQL and Redis
containers, starts the backend with the isolated `web-qa` profile, creates a one-run QA identity,
and drives the real React UI through login, workspace creation, run tabs, failure recovery, plan
gate rendering, and fixture-only lifecycle transitions. The profile disables live LLM dispatch and
does not activate the local/dev feature-flag initializer; the harness also compares a digest of all
feature-flag values and audit metadata before and after the browser run. Browser session creation
uses the real provider-free backend contract and its exact session is included in fixture cleanup.
The fixture assigns the disposable user an unusable DeepSeek readiness marker solely so real project
create/delete policy can be exercised. Live dispatch remains disabled. Browser-only cleanup removes
the marker; a combined browser/CLI pass retains it between those phases, then destroys the database.
Frontend, browser, backend, and optional CLI child processes receive purpose-specific allowlisted
environments; the harness never reads a credential file and destroys its disposable services on
exit. This proves deterministic browser contracts, not provider-backed Ask answers, screen
generation, sandbox isolation, or generated-code quality.

Set `WEB_QA_CLIENT_ROOT` to an absolute standalone client-checkout path to run the CLI behavioral
suite against that same disposable backend and identity after the browser checks. The client harness
mints and revokes its own PAT in guaranteed cleanup, removes its temporary config/workspaces, and
removes the disposable email/password plus unrelated credentials from all child processes. Live-web
and update checks are disabled for that optional pass. The variable is optional; no client checkout
path is hardcoded.

Validation evidence is fail-closed: code-changing tasks require an admitted baseline-to-final product
diff attributable to that task, and persisted executable verification distinguishes `PASSED`,
`BUILD_ONLY`, `FAILED`, and `UNAVAILABLE`. The Web evidence ledger exposes those deterministic facts;
the headline score is a summary and must not be treated as independent proof.
Post-run Ask follows the same rule: configured build success is not test success, missing concrete
test counts remain an explicit verification gap, and provider tool-protocol markup is suppressed
rather than displayed to customers. A post-run Ask response stopped by `length` / `max_tokens`
returns `complete=false` with `incompleteReason=PROVIDER_OUTPUT_LIMIT`; the web client retains the
partial text but renders an explicit incomplete/retry notice instead of treating it as a complete
answer. Web/CLI agent answers have bounded 8,192-token direct and
4,096-token final-synthesis ceilings and visibly warn when a provider stops for output length.
System-map refresh is a durable asynchronous job: the write-gated POST returns 202, identical
compatible requests reuse one stable job id, and the web console resumes/polls persisted state
without holding a provider-duration HTTP request open. Admission performs only database/config
work. PREPARING snapshots every linked repository at an exact revision using that link's credential
subject, binds separate exact PLANNING repo-map and project-synthesis contexts, and
never persists SCM credentials. Paid execution is not automatically replayed after an expired
lease: uncertain work becomes `NEEDS_CONFIRMATION`. Generated artifacts remain invisible until one
transactional publisher verifies the database lease/fence, unchanged source revisions, and staged
digest, then saves every canonical artifact and marks the job SUCCEEDED atomically. Cancellation,
deadlines, and abnormal provider finish reasons fail closed; truncated provider prose is never
published. Other classified provider failures may use an explicit WARNING-quality deterministic
fallback rather than being mislabeled as provider success. Async refresh deliberately
suppresses customer-repository codemap PR publication until that external side effect has its own
idempotency/fencing contract.
`NEEDS_CONFIRMATION` blocks ordinary retries for web, CLI, and API callers; only the explicit
confirm-retry action by the original requester can requeue the same durable identity, and every
confirmation appends an immutable actor/time receipt. The latest-job read restores terminal truth
after reload. Per-repository regenerate intent also enters this project-wide boundary so it cannot
race a whole-project refresh.
Compare History renders the
actual authorized document-content diff, Timeline omits invalid timestamps instead of fabricating
epoch durations, and the provider-key screen exposes OpenRouter's organization-scoped status and
route to org settings.

Generated EXPLANATION/SETUP documents use provider-neutral external prompts and a fair 60k source
budget across architecture plus every linked repository. Each repository section must come from the
codemap at the exact SHA captured by that architecture version; source drift fails closed. Every
abnormal provider finish reason is rejected. Required structure, balanced code fences, secrets, and
tool-protocol leakage are checked before save, while reads/downloads re-scan retained legacy
documents. EXPLANATION output also fails closed on concrete symbol-to-symbol call edges and
inactive/unused/stub/dead-code claims, plus directional Mermaid edges, that declaration-only
codemaps cannot prove. Relationship euphemisms such as works/interacts/collaborates/depends/uses and
symbol-local hinted/placeholder/prototype/candidate labels are covered by the same narrow gate.
Unsupported prose is deterministically reduced to declaration-only symbol notes before validation;
any prose sentence containing two or more distinct known codemap symbols is conservatively treated
as an unproven relationship regardless of verb, while single-symbol inventory remains unless it
claims reliance, a hinted mechanism, or incomplete integration. Directional Mermaid/sequence
blocks are replaced by a non-directional evidence-limit note. The provider retry is reserved for
remaining structural failures, and cached output is revalidated under the same rule before reuse.
Provider transport runs without a database
transaction; only the exact-source recheck,
shared project publication lock, cache recheck, version allocation, save, and pruning use a short
transaction.

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

The REST API contract is maintained in the central docs repository at
[`backend/reference/openapi.yaml`](../executespec-docs/backend/reference/openapi.yaml).

## Worker safety ceilings

Workers stop authorizing provider/tool turns after configured live task ceilings are observed. The
per-solve dollar cap is task-scoped. `worker.best-effort-max-task-cost-usd` and
`worker.best-effort-max-task-total-tokens` optionally stop task spend and input+output tokens in the
current JVM; both default to `-1` (disabled), and Terminal-Bench is exempt from these defaults.
Before a worker provider call, ExecuteSpec also reserves a conservative estimate of the complete
next call against the run and task cost caps. Admission runs after managed routing and on failover,
so it prices the effective provider/model rather than an earlier requested route. The estimate
assumes uncached input with headroom and the requested maximum output; it reduces material one-call
overshoot but is not an exact invoice guarantee because provider tokenization and cache results
arrive after the response. Missing model pricing falls back to observed-spend enforcement rather
than being treated as free. These are
safety stops, not durable authorization boundaries: restart recovery does not yet hydrate
`RunCostTracker` from `run_step_usage`. A successful build/test/shell check counts as progress
once after a workspace mutation, but repeated verification against unchanged files cannot keep a
worker alive indefinitely.

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

Native DeepSeek thinking/tool calls use a 300-second read window by default (`DEEPSEEK_READ_TIMEOUT_SECONDS`);
OpenAI, OpenRouter, and Fireworks retain the shared 120-second OpenAI-wire default.

**Optional**

| Variable                                                                                   | Default / notes                                                       |
| ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD`  | `localhost` / `5432` / `executespec` / `postgres` / `postgres`        |
| `REDIS_HOST`                                                                               | `localhost`                                                           |
| `CRYPTO_LOCAL_KEY` / `CRYPTO_GCP_SECRET_RESOURCE`                                          | Shared dev/prod/benchmark require one. Local key is Base64 AES-256; GCP resource must end in immutable `/versions/<number>` (never `latest`). |
| `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` / `STRIPE_WEBHOOK_SECRET`                   | Required when `BILLING_DEFAULT_GATEWAY=stripe`                        |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`                                    | Enables Google OAuth2 login                                           |
| `GITHUB_OAUTH_CLIENT_ID` / `GITHUB_OAUTH_CLIENT_SECRET`                                    | Enables GitHub OAuth2 login                                           |
| `SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` / `SLACK_SIGNING_SECRET`                         | Enables Slack integration                                             |
| `DEPLOYMENT_RECOVERY_SAFE_ENABLED`                                                         | Explicit prod attestation required to expose deployment creation; defaults to `false` |
| `EXECUTESPEC_MIN_CLI_VERSION`                                                              | Oldest supported CLI/VS Code protocol version advertised on API responses; defaults to `1.0.0` |

Release safety: production and benchmark profiles do not deliver outbound integration lifecycle
updates because the current adapters are non-idempotent, best-effort append operations. Deployment
creation is also hidden and returns 503 unless durable external-resource recovery has been
explicitly attested with `DEPLOYMENT_RECOVERY_SAFE_ENABLED=true`; existing deployment read,
rollback, and destroy operations remain available for cleanup.
| `APP_SURFACE_RETURN_URL`                                                                   | Browser OAuth callback URL (e.g. `http://localhost:5173/auth/callback`) |
| `DASHBOARD_URL`                                                                            | Dashboard URL used in Slack and notification links                    |

Additional environment variables consumed at runtime (cookies, CORS, mail, preview engine, OTel) are set per-environment by [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml) (`env-patch.sh`) rather than committed to the template.
