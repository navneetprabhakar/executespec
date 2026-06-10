# Benchmark Server Creation and Deployment Plan

Date: 2026-06-10

Source of truth: `review/checkpoints/be-bench-eval.md`, `review/checkpoints/tb-sweep-plan.md`, `review/checkpoints/infra-build.md`, `review/08-models-strategy.md`, `review/09-model-matrix-consistency-plan.md`, `review/10-aggregator-architecture-openrouter-fireworks.md`, and model evidence in `review/checkpoints/models-eval-evidence.md`. This is a creation/deployment plan, not an implementation record. Verify exact file names and line numbers against source before editing.

## Objective

Create a dedicated benchmark server/environment that can run Terminal-Bench, SWE-bench, ProgramBench, and tier-eval work without contaminating dev/prod, while producing trustworthy run, cost, model, artifact, and leaderboard data.

The benchmark server must not be a cosmetic clone of dev. The review shows benchmark correctness depends on these properties:

- cleanroom setup failures abort the run before LLM spend;
- capture paths are asynchronous in reality, not only by annotation;
- diff capture is consistent across `/run` and `/generate`;
- cost accounting is non-zero and model-attributed correctly;
- remote-tool sessions cannot leak;
- CI/deploy can reject broken context/config before the server is updated;
- benchmark dashboard/tracking ingest is idempotent enough for verifier retries;
- Chinese-model/provider/route matrix work is postponed until after the benchmark server is reliable.

## Target Shape

### Environment

- Spring profile: `benchmark`.
- Public surface: operator-only benchmark environment, not customer-facing production.
- Authentication: SUPER_ADMIN-only access for all benchmark launch and tracking endpoints.
- Primary backend: the normal Spring Boot monolith, running with benchmark profile and benchmark infrastructure.
- Frontend: same web app, with benchmark dashboard visible only to authorized operator/admin users.
- Worker execution: local Docker/Harbor-capable benchmark host or benchmark VM with enough disk/CPU for concurrent cleanroom containers.
- Results storage: benchmark tracking tables plus GCS bucket/result archive for raw artifacts.

### Endpoint Surface

Keep these surfaces explicit:

- `POST /api/v2/test/terminal-bench/start`: Terminal-Bench remote-brain/local-hands launcher.
- `POST /api/v2/test/benchmark/start` and ingest endpoints: pure benchmark tracking sink.
- `POST /api/admin/benchmark/run`: SWE-bench BUILD/codegen path.
- `POST /api/admin/programbench/run`: ProgramBench PATCH path.
- `POST /api/admin/eval/v2/run`: tier-eval through production router/model resolver.

Decision: keep legacy eval v1 and dead SWE-bench service out of the benchmark-server definition unless deliberately revived after cleanup.

## Phase 0 - Do Not Deploy Yet: Close Benchmark Blockers

The review identifies benchmark bugs that would make a fresh benchmark server produce misleading numbers. These must be fixed before the benchmark server becomes authoritative.

### 0.1 Real Async Capture

Problem: capture loops are annotated `@Async` but self-invoked, so endpoints that return 202 actually block the HTTP request thread until terminal or timeout.

Fix:

- Extract capture loops into proxied components, or call them through a self-injected proxied bean.
- Apply to:
  - SWE-bench BUILD capture;
  - ProgramBench capture;
  - any still-live generation/capture path.
- Update `AsyncAllowlistTest` so it verifies safe lazy collection and does not canonize dead async behavior.

Acceptance:

- Launch endpoint returns within a small fixed budget after the run id is created.
- Capture job continues after HTTP response.
- One test proves self-invocation is gone by observing async collaborator dispatch.

### 0.2 Cleanroom Setup Failure Must Abort

Problem: setup failure writes `FAILED/BENCH_SETUP_FAILED`, but the orchestrator later overwrites it and spends tokens against a bad workspace.

Fix:

- Add a run-pipeline start guard: if the row is already `FAILED` with a benchmark setup reason, return without classifier/planner/worker.
- Prefer a typed setup-failure marker over a string-only convention.
- Emit a terminal event and cost snapshot with zero or setup-only cost.

Acceptance:

- Failed cleanroom setup never enters `WORKING`.
- No LLM calls occur after setup failure.
- Dashboard marks the benchmark run failed with setup reason.

### 0.3 Diff Capture Consistency

Problem: SWE-bench `/run` and `/generate` have diverged. `/run` has source-pollution stripping and stronger blind specs; `/generate` does not.

Fix:

- Centralize diff-strip excludes in one helper.
- Centralize blind-spec generation in one helper.
- Decide whether `/generate` remains supported. If retained, it must use the same diff-strip and blind-spec rules as `/run`.

Acceptance:

- Unit test pins the exclude list.
- Same instance through both capture paths produces comparable patch filtering.

### 0.4 Safe Process Output Drain

Problem: shell helper copies wait for process completion before reading stdout; large output can deadlock and silently emit empty patches.

Fix:

- Replace `waitFor`-before-read helpers with concurrent output drain or redirect-to-file.
- Apply to benchmark, generation, SWE-bench, and ProgramBench copies.
- Return failure metadata instead of empty patch when diff capture fails.

Acceptance:

- Test with >64KB output completes.
- A failed diff capture is visible as a failed artifact capture, not an empty model patch.

### 0.5 Tracking Ingest Idempotency

Problem: task result upsert is check-then-insert; concurrent verifier retry can 500. Event sequence uses `count(*)`, so concurrent events can duplicate sequence values.

Fix:

- Add database-backed upsert for `(run_id, task_id, attempt)`.
- Add unique `(run_id, seq)` or replace manual seq with monotonic DB sequence/order column.
- Define behavior for corrected reward re-publish: first-write-wins, last-write-wins, or explicit correction event.

Acceptance:

- Duplicate `TASK_SCORED` is idempotent.
- Concurrent events have deterministic replay order.
- Unknown event types persist and broadcast without breaking state.

### 0.6 Terminal-Bench Remote Session Cleanup

Problem: `finalizeTerminal` can throw before `remoteToolDispatcher.unregister`, leaking session state.

Fix:

- Put unregister and workspace cleanup in nested `finally` blocks that always execute.
- Make duplicate `RemoteToolDispatcher.register` fail or cancel old pending calls explicitly.
- Cap remote-tool timeout for TB runs so `wallClock + inFlightToolTimeout < Harbor timeout`.

Acceptance:

- Terminal finalization exception still unregisters remote session.
- Remote session cannot be replaced silently.
- TB worst-case tool wait cannot exceed Harbor wall-clock budget.

## Phase 1 - Benchmark Server Infrastructure

### 1.1 Choose Infrastructure Root

The review identifies two infrastructure shapes:

- main Terraform with `environments/benchmark.tfvars`;
- standalone `infra/benchmark/` root with local state for throwaway SWE/TB VM and results bucket.

Decision:

- Use the main Terraform environment for long-lived benchmark server infrastructure.
- Use `infra/benchmark/` only for ephemeral worker/experiment infrastructure after its state is moved off local state or consciously documented as disposable.

Required changes:

- Remote Terraform state for benchmark server.
- Separate benchmark project/service account or least-privileged benchmark service account.
- Explicit benchmark DB/Redis/storage resources.
- Dedicated results bucket with lifecycle policy.
- Network rules limited to load balancer/health checks/admin access.

Acceptance:

- `terraform plan -var-file=environments/benchmark.tfvars` is reproducible.
- State is remote and locked.
- Benchmark server resources are tagged/labeled as benchmark.

### 1.2 Compute Shape

Minimum server roles:

- app VM or app runtime for Spring Boot + static frontend;
- Docker-capable benchmark worker host for cleanroom containers and Harbor jobs;
- optional separate results sync/bridge process host.

Sizing principle:

- Start with conservative concurrency: two Harbor jobs in parallel only when providers differ.
- Keep OpenRouter-backed jobs serialized due shared gateway latency.
- Ensure disk is sized for Docker images, cleanroom workspaces, and raw logs.

Acceptance:

- Docker is available and version-pinned enough for Harbor and cleanroom runs.
- Worker host has cleanup jobs for stopped containers, workspaces, and job artifacts.
- Disk alerting is in place before full sweeps.

### 1.3 Secrets

Required secrets:

- backend JWT secret;
- database password;
- Redis credentials if applicable;
- OpenAI/Anthropic/Gemini keys as needed;
- DeepSeek key for baseline;
- OpenRouter key only after cost/pricing truth is fixed;
- Fireworks key only in final model/provider phase;
- any Chinese-model gateway key is used only after route pricing and route health are explicit;
- benchmark SUPER_ADMIN credential or PAT;
- Telegram/notification token if long sweep updates are enabled.

Rules:

- No literal secrets in tracked profile files.
- No plaintext GCS API-key fallback for benchmark.
- Store secrets in Secret Manager or equivalent.
- Benchmark bootstrap users should not be normal-login SUPER_ADMIN users with empty password hash.

Acceptance:

- Missing required secret fails boot clearly.
- No benchmark deploy depends on local `application-local.yml`.
- Benchmark PAT can be rotated without rebuilding the app.

## Phase 2 - CI and Deploy Pipeline

### 2.1 Re-enable Gates

The review says all app workflows are disabled. Before deploying benchmark:

- re-enable backend/frontend test workflow;
- re-enable lint workflow;
- re-enable deploy workflow for `benchmark` branch/environment;
- remove or ignore orphaned deleted clients workflow.

### 2.2 Add Required Context Smoke

Problem: default `mvn test` excludes the only Spring context smoke.

Fix:

- Add a fast benchmark-safe context smoke that loads Spring context with test containers or mocked infra.
- Keep heavy integration tests tagged out, but do not tag out the only context boot check.

Acceptance:

- CI catches missing beans, bad `@Value`, and profile config failures before deploy.

### 2.3 Deployment Safety

Fix deploy workflow:

- Add `concurrency:` for benchmark deploys.
- Deploy artifact by immutable SHA.
- Health-check the new version.
- Roll back symlink/systemd unit to previous SHA on failed health check.
- Capture logs and `RUNNING_SHA` on failure.

Acceptance:

- Two pushes cannot race one benchmark deploy target.
- Failed health check does not leave benchmark server on broken JAR.

### 2.4 System Hardening

The review flags root/systemd hardening and broad service account permissions.

Fix:

- Run app as non-root service user.
- Enable `NoNewPrivileges`.
- Apply conservative `ProtectSystem`/`ProtectHome` where compatible.
- Narrow service account permissions away from project-wide compute admin and service-account-user.
- Keep Docker permissions scoped to worker host, not generic app server, if split.

Acceptance:

- App JVM compromise does not imply project-wide compute admin.
- Generated-code execution has an OS-level blast-radius boundary beyond Java checks.

## Phase 3 - Benchmark Runtime Configuration

### 3.1 Profiles and Flags

Benchmark profile must explicitly set:

- datasource and Redis from env/secret;
- `benchmark` profile active;
- terminal-bench/tracking profile-enabled controllers;
- benchmark/tracking endpoints SUPER_ADMIN-only;
- `swebench.enabled` and `programbench.enabled` values chosen intentionally;
- agent concurrency matched to worker host capacity;
- remote tool timeout below TB wall-clock buffer;
- observability and health endpoints configured.

Avoid:

- inheriting weak literal JWT fallback;
- inheriting local default paths;
- depending on absolute Mac paths from Java defaults;
- `flyway.out-of-order` masking migration-order drift unless intentionally documented.

### 3.2 Dataset and Artifact Paths

Fix before launch:

- Remove absolute personal-machine defaults for SWE-bench instance file.
- Configure dataset paths per benchmark server.
- Configure ProgramBench task dir explicitly.
- Configure results bucket/artifact prefix.
- Ensure cleanup policies for:
  - Docker images/containers;
  - `/tmp/es-tb-jobs`;
  - run workspaces;
  - GCS artifacts.

Acceptance:

- A fresh benchmark server can run without `/Users/...` paths.
- Dataset absence fails launch clearly, not after spending LLM tokens.

### 3.3 Tracking Dashboard

Tracking layer is the source for dashboard display, not execution.

Required behavior:

- `RUN_STARTED` creates run record;
- `TASK_SCORED` updates task result and recomputes resolved/cost;
- `RUN_DONE/RUN_FAILED` finalizes;
- `SUBMISSION_*` updates status and leaderboard URL;
- unknown event types are preserved for audit.

Acceptance:

- Dashboard can replay from stored events.
- SSE stream validates run exists.
- Operator can correlate Harbor job name, provider, model, run id, and artifact location.

## Phase 4 - Launch Runbook

### 4.1 Preflight

Before any benchmark run:

- confirm backend SHA and frontend SHA;
- confirm active Spring profile includes `benchmark`;
- confirm DB migration version;
- confirm Redis reachable;
- confirm Docker/Harbor reachable on worker host;
- confirm benchmark PAT/SUPER_ADMIN auth;
- confirm model provider key exists for selected provider;
- confirm pricing row exists for selected model;
- confirm run cost tracker records non-zero cost for a smoke call;
- confirm tracking ingest accepts a synthetic run and task score;
- confirm GCS/results bucket write works;
- confirm cleanup job dry run.

### 4.2 Smoke Sequence

Run in this order:

1. Health check: `/actuator/health`.
2. Auth check: protected benchmark endpoint returns 401 unauthenticated.
3. Tracking check: create synthetic benchmark run and ingest one synthetic task score.
4. Terminal-Bench one-task smoke with low-cost baseline model.
5. SWE-bench one-instance smoke, blind path only.
6. ProgramBench one-task smoke.
7. Tier-eval one provider/tier smoke.

Promotion gate:

- Do not run full first-20 or full89 until all smoke artifacts are visible in dashboard and raw result storage.

### 4.3 First Real Campaign

Start with Terminal-Bench first-20:

- baseline: `deepseek / deepseek-v4-flash-native`;
- concurrency: one job initially;
- run bridge alongside Harbor job;
- record resolved count, cost, wall-clock, timeout count, and artifact links.

Then:

- repeat baseline once to establish variance;
- add one non-Chinese comparison to keep a sanity anchor;
- add OpenRouter-routed Chinese models only after OpenRouter cost truth is verified;
- add Fireworks-routed Chinese models only after final model/provider phase;
- do not treat route latency as model quality. A slow route that times out is a route failure until the same model is tested through a faster route.

### 4.4 Operational Monitoring

Minimum observability:

- app health;
- DB connection pool;
- Redis availability;
- run outcome counts;
- cost per run;
- remote tool timeout/error counts;
- benchmark ingest failures;
- container setup failures;
- disk usage;
- Docker daemon health;
- SSE replay errors;
- deploy SHA.

Alert thresholds:

- cleanroom setup failures > 0 in smoke;
- any benchmark model cost recorded as 0 when tokens > 0;
- Harbor timeout;
- remote tool timeout;
- disk usage above safe threshold;
- tracker ingest 5xx;
- app restart loop.

## Phase 5 - Security and Access

Required:

- SUPER_ADMIN-only benchmark launch.
- Benchmark tracking ingest protected by PAT or server-to-server auth.
- Remote tool result endpoint remains run-write-guarded.
- No public benchmark endpoints without auth.
- Operator user/PAT has minimum needed scope.
- Benchmark bootstrap users cannot be used for ordinary login unless explicitly intended.
- Dataset paths and artifacts do not include secrets.
- Tracking payload rendering escapes untrusted JSON.

Acceptance:

- Unauthenticated benchmark launch returns 401/403.
- Non-admin authenticated user cannot launch or ingest.
- Stored payload cannot execute in dashboard.

## Phase 6 - Cleanup and Deletion Before Broad Use

Delete or freeze:

- dead `SwebenchTaskService` unless intentionally revived;
- dead planner scaffold eval code;
- stale eval v1 from official server runbook unless retained as legacy/manual;
- hardcoded stale model matrices in test controllers;
- tracked benchmark result artifacts and committed `node_modules`;
- stale scripts that measure `accuracyScore` instead of verified score.

Acceptance:

- Benchmark server runbook points to one canonical path per benchmark type.
- No operator script defaults to dev URL or inline password.
- Scripts use PAT/env inputs, not default credentials.

## Phase 7 - Chinese Model and Route Work Last

This phase stays last, matching the review-driven execution plan.

The updated model context is not "add more providers." The review docs frame the Chinese-model lane as the cheap-model strategy:

- DeepSeek is the first native cheap anchor.
- Qwen, Kimi, GLM, and Mimo are model identities, not product-facing providers.
- OpenRouter and Fireworks are routes/transports for those models.
- Mimo is the canonical route-latency warning: capability can be acceptable while the route makes it unusable for agentic loops.
- The benchmark server must measure quality, cost, throughput, and timeout behavior together, or the Chinese-model story becomes misleading.

Do not add Fireworks/OpenRouter/matrix changes to the benchmark server until:

- benchmark server cost truth is fixed;
- model-matrix rows have pricing for every route/model pair being swept;
- route dispatch is tested;
- BYOK/key plumbing is explicit;
- failover cannot route a model to unrelated platform keys or silently change model class;
- Fireworks slugs are verified by smoke;
- OpenRouter calls prefer throughput, not cheapest slow backend, when the benchmark is latency-sensitive;
- native DeepSeek reasoning/tool-loop behavior is either supported or the thinking lane is explicitly excluded;
- `FailoverOrderEnumAlignmentTest` and provider enum tests are updated;
- dashboard shows model/vendor/route/cost consistently.

Then run the model sweep:

- DeepSeek native baseline first: `deepseek-v4-flash-native`, repeated to establish variance.
- Qwen/Kimi/GLM/Mimo lanes next, but only as registered models with priced routes.
- Gemini/OpenAI/Anthropic cheap lanes as non-Chinese sanity anchors, not the center of this campaign.
- OpenRouter lanes serialized due gateway latency and shared gateway behavior.
- Fireworks lanes only after key, slug verification, pricing rows, and smoke.
- Thinking/reasoning models only after the OpenAI-compatible reasoning-content echo problem is fixed, or they must be marked excluded.
- Flagships only after explicit cost approval.

Publish:

- resolved count;
- cost per resolved;
- wall-clock;
- generation throughput / p90 call latency;
- timeout/error count;
- route transport;
- exact model id;
- model class;
- artifact links.

## Deployment Checklist

Before first deploy:

- [ ] Benchmark blockers in Phase 0 fixed and tested.
- [ ] CI workflows re-enabled for benchmark.
- [ ] Context smoke included in default gate.
- [ ] Deploy workflow has concurrency and rollback.
- [ ] Benchmark Terraform uses remote state.
- [ ] Secrets are in Secret Manager/equivalent.
- [ ] Benchmark profile has no literal weak fallback secrets.
- [ ] Dataset paths configured without personal-machine defaults.
- [ ] Docker/Harbor worker host provisioned.
- [ ] Results bucket provisioned with lifecycle policy.
- [ ] Dashboard tracking ingest smoke passes.
- [ ] One-task TB smoke passes.
- [ ] One-instance SWE-bench smoke passes.
- [ ] One-task ProgramBench smoke passes.
- [ ] Cost is non-zero for token-using smoke calls.
- [ ] Runbook records rollback and cleanup steps.

## Go/No-Go Criteria

Go:

- health check green;
- auth gates correct;
- smoke benchmarks produce artifacts;
- cost and model attribution correct;
- no cleanroom setup false-complete;
- dashboard and raw artifact store agree;
- deploy rollback tested.

No-go:

- any model route records `$0` with tokens;
- cleanroom setup failure proceeds to worker;
- capture endpoint blocks request thread;
- diff capture can silently emit empty patch;
- remote session leak observed;
- benchmark server still depends on local absolute paths;
- deploy can leave broken JAR live.
