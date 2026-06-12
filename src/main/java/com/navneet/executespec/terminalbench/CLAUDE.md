# `terminalbench/` — Terminal-Bench remote-worker run (operator-only)

> Added 2026-06-05 (RL-2026-06, terminal-bench integration). Runs ExecuteSpec's OWN worker on
> Terminal-Bench 2.0 via the "remote brain / local hands" model — the proof that the local-hands
> story works end-to-end (docs/pending.md §J3).

## What this is

A standalone path that runs the V2 `WorkerAgent` in **REMOTE-tool mode** as a real persisted run,
so an external client (the Terminal-Bench **Harbor adapter**, `~/tbench/es_harbor_agent.py`) can
drive it: the ExecuteSpec server runs the worker loop (the *brain*); every worker tool call is
emitted as a `TOOL_CALL` SSE event; the adapter executes it **inside the Harbor task container**
(the *hands*) via `environment.exec` / file ops, and POSTs the result envelope back to
`POST /api/runs/{id}/tool-results`. The worker is provider-agnostic, but the standing evaluation
policy is **DeepSeek-first only** until the harness quality is materially better. Anthropic / OpenAI /
Gemini runs are deferred until DeepSeek results stop exposing obvious prompt/tool/context gaps.

A Terminal-Bench task is a single terminal goal, not a multi-task code spec, so this path runs the
worker **directly** — NO classifier / planner / reviewer / scoring / PR push. It reuses the built
remote-tool protocol + the run SSE / scoped-token / cost / access infra; it does **NOT** touch
`RunOrchestrator` or the product pipeline.

## Files

| File | Purpose |
|------|---------|
| `TerminalBenchController.java` | `POST /api/v2/test/terminal-bench/start` `{instruction, provider, model, maxIterations?}` → `{runId, sseToken}`. `@Profile({"local","dev","benchmark"})` + under `/api/v2/test/**` which `SecurityConfig` locks to SUPER_ADMIN → operator-only. |
| `TerminalBenchRunService.java` | Persists a `PipelineRun` (ownership stamped, `harnessVersion="v2"` so `/cost` reads the V2 tracker, `runMode=PATCH`, `sourceType="TERMINAL_BENCH"`) → `RemoteToolDispatcher.register(runId)` → launches the worker on a context-propagated virtual thread with a remote `ExecutionContext(runId)` → on terminal: persists status + finalizes cost + emits the terminal `COMPLETE` SSE event + **`unregister(runId)` in `finally`** (always, so a crash can't leak a session). Holds the terminal-task `TB_SYSTEM_PROMPT`. Sets the worker's harness-stage flags from env: `criticPass(ES_TB_CRITIC)` + `requiredDeliverablePaths(extractDeliverablePaths(instruction))` + `terminalVerifyGate(ES_TB_VERIFY_GATE)`. **`extractDeliverablePaths` (RL-2026-06-016)** now runs TWO matchers: the absolute `/app/…\.ext` `DELIVERABLE_PATH` + a `RELATIVE_DELIVERABLE` that catches a bare `name.ext` introduced by an output verb (write/create/produce/…) in the same clause (`[^./]` gap can't cross a `/` into an absolute path nor a `.` into the next sentence; letter-led extension avoids `e.g.`/`i.e.`), prepended `/app/`. Fixes the write-compressor miss where "Write me **data.comp**" left the un-prefixed OUTPUT unextracted (the gate saw only the inputs → passed). Note: a server-run *test* gate (VBC) is moot for TB — `tests/` is verifier-side, NOT in the `environment/` container during the agent's run. |

## Harness stages are COMMON, not a TB fork (RL-2026-06)

The critic/reviewer pass (#1) + deliverable gate (#2) + verify gate (#3) are **NOT** Terminal-Bench code — they live in the shared `engine/WorkerAgent` as flag/data-activated, workspace-agnostic capabilities (see `engine/CLAUDE.md`). TB is just one **consumer** (it sets the flags via `ES_TB_CRITIC` / `ES_TB_DELIVERABLE_GATE` / `ES_TB_VERIFY_GATE`); `PatchAgent` is another (critic behind `worker.critic-pass.enabled`, V231, default OFF). So the "no reviewer" framing above is now "no *pipeline* reviewer (`ReviewerAgent`)" — TB gets the lightweight common critic instead, reading the `/app` files (remote) the way PATCH reads its `git diff` (local). Empirically the #2+#1 combo lifted a 13-task near-miss slice 4/13 → 8/13 (pass@2), 0 regressions.

## The keystone mechanism (and the fix that made it work)

`register(runId)` before the worker runs flips `RemoteToolDispatcher.isRemote(runId)` true. The V2
worker dispatches tools **manually** (`tool.execute()` at `WorkerAgent.dispatchOne`), NOT through
Spring AI's `ContextBoundToolCallback` — so the remote branch had to be added at the manual-dispatch
site, not only in `ToolRegistry.ContextBoundToolCallback.call` (which only covers Spring-AI internal
execution). The fix (RL-2026-06):

- `ToolRegistry.isRemoteRun(ctx)` + `ToolRegistry.dispatchRemote(ctx, name, argsJson)` — pass-throughs
  to the dispatcher (keeps the dispatcher dependency in `ToolRegistry`, so `WorkerAgent`'s
  `@RequiredArgsConstructor` is unchanged → no test call-site breakage).
- `WorkerAgent.dispatchOne` — when `toolRegistry.isRemoteRun(req.context())`, return
  `toolRegistry.dispatchRemote(...)` verbatim (identical `{ok,content}` envelope) instead of
  `tool.execute()`. Compaction is skipped in remote mode (compactor flag is OFF by default).
- `RemoteToolDispatcher.TOOL_TIMEOUT_MS` → constructor-injected `@Value(executespec.remote-tool.timeout-ms)`,
  default raised 120s → **600s** (long TB build/test commands run in the client's container).

## The client (Harbor adapter)

`~/tbench/es_harbor_agent.py` (`ExecuteSpecAgent(BaseAgent)`, slated to move into the repo / its own
client folder). `harbor run -d terminal-bench/terminal-bench-2 -i <task> --agent-import-path
es_harbor_agent:ExecuteSpecAgent -m deepseek/deepseek-v4-flash`. The adapter runs on the HOST, so the
server only needs to be reachable at `localhost` (no in-container networking). Tool execution maps the
12 worker tools onto `environment.exec` (shell/build/test/git, cat for readFile, grep for searchFiles,
base64-via-exec for write/edit). Config via env: `ES_PROVIDER`, `ES_MODEL`, `ES_BASE`, `ES_MAX_ITERS`,
`ES_PAT` or `ES_ADMIN_EMAIL`/`ES_ADMIN_PW`. Populates Harbor's `AgentContext` cost/tokens from
`GET /api/runs/{id}/cost`.

## Verified

- Pump test (`~/tbench/tb_pump_test.py`, local-fs stand-in): 5 remote tool calls, file created in the
  client workspace, `COMPLETE`. Keystone proven with DeepSeek (RL-2026-06).
- Real Harbor smoke (`terminal-bench/terminal-bench-2`, DeepSeek, 1 task): worker drove **19** tool
  calls executed in the container → `COMPLETE`. Integration validated end-to-end.
- DeepSeek first-20 baseline before terminal-mode tuning (`~/tbench/es-tb-base20.yaml`, job
  `es-deepseek-base20-v2`): **7/20 resolved (35%) for about $0.09**. Solved: `fix-git`,
  `git-leak-recovery`, `log-summary-date-ranges`, `nginx-request-logging`,
  `openssl-selfsigned-cert`, `prove-plus-comm`, `sqlite-db-truncate`. Full result directory:
  `/tmp/es-tb-jobs/es-deepseek-base20/` from the baseline run; re-runs should write a new job name
  or archive the old directory first.
- Current DeepSeek run policy: Terminal-Bench requests below the service floor are normalized to
  **120 worker iterations**. Terminal mode treats `shell` as progress, sends an artifact nudge near
  iteration 16, sends a deadline nudge near iteration 32, and runs a restricted terminal finalizer at
  the force threshold (currently iteration 64) even if shell calls keep resetting the progress
  counter. Wall-clock-exceeded terminal runs also get one restricted finalizer attempt before they
  fail. The server-side TB worker wall clock is **900s**, intentionally below Harbor's 1350s agent
  timeout with buffer for one slow in-flight tool/LLM turn plus the terminal finalizer, so long runs
  fail cleanly through ExecuteSpec instead of surfacing as Harbor `AgentTimeoutError` + orphaned
  backend work. After the normal one-shot text-only nudge, a second text-only terminal response also
  runs the forced terminal finalizer so the model must emit `completeTask` or write/edit the exact
  `/app` artifact instead of failing the ExecuteSpec run while the artifact may already be correct.
- Latest comparable first-20 run (`/tmp/es-tb-jobs/es-deepseek-base20-iter100-finalizer-20260606-095500`):
  **12/20 resolved (60%)**, **1 Harbor `AgentTimeoutError`**, **$0.400126**, **47m52s**. The
  100-floor + finalizer run fixed `openssl-selfsigned-cert` and `sanitize-git-repo` versus the
  previous 80-floor run, but regressed `largest-eigenval` and `query-optimize`; both misses were
  verifier/performance-shaped rather than raw-iteration misses. Follow-up prompt policy now tells
  Terminal-Bench workers to inspect `/tests` early, treat tests as the scoring contract, sweep named
  error patterns recursively, implement detection-first no-op handling for clean transform inputs,
  benchmark against golden/reference thresholds, and stop changing a candidate once a
  verifier-shaped check passes. **RL-2026-06-013** added two more (ported from `SYSTEM_PROMPT_BASE`):
  build the MINIMUM (no gold-plating a passing candidate — a correct deliverable + an unrequested
  bonus script/dep that errors scores zero; maps to the openssl-selfsigned-cert regression), and
  symptom-anchored fix-verification for FIX/REPAIR tasks (reproduce the exact symptom, confirm your
  change moved THAT symptom, "if your edit didn't change the output you're in the wrong place," sweep
  all occurrences; maps to sanitize-git-repo). Measured on base20 DeepSeek, one lever.

## DeepSeek first-20 runbook

```bash
# Backend, only if down; always use the local Spring profile.
cd ~/Documents/antigravity/executespec
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run -Dspring-boot.run.fork=false \
  -Dspring-boot.run.profiles=local > /tmp/es-tb3.log 2>&1 &
curl -s localhost:8080/actuator/health

# Harbor adapter.
cd ~/tbench
. ~/swebench-bench/.espec-admin.env
export ES_ADMIN_EMAIL="$ESPEC_ADMIN_EMAIL" ES_ADMIN_PW="$ESPEC_ADMIN_PW"
export ES_PROVIDER=deepseek
export ES_MODEL=deepseek-v4-flash-native
export ES_MAX_ITERS=120
./.venv/bin/harbor run -c es-tb-base20.yaml -y
```

Keep first follow-up tests **DeepSeek-only** unless the founder explicitly requests the provider
matrix again. Use the first-20 slice in `es-tb-base20.yaml` as the comparable baseline; provider
YAMLs for Anthropic/OpenAI/Gemini exist in `~/tbench` but are intentionally out of scope for the
current pass.

## Iteration policy

- Test Terminal-Bench 2.0 / 2.1 with DeepSeek first. Other providers are comparison data only after
  the ExecuteSpec harness has improved enough that DeepSeek is no longer failing on obvious harness
  defects.
- Use small comparable slices first (`es-tb-base20.yaml` before wider sets). Keep job names unique or
  archive the prior `/tmp/es-tb-jobs/<job_name>` directory before rerunning.
- After every run, inspect failures task-by-task and classify them as prompt, tool protocol,
  context/retrieval, timeout/iteration budget, environment mismatch, or model capability. Change one
  lever at a time, document it, and rerun the same slice before expanding.
- Move to Anthropic/OpenAI/Gemini only after DeepSeek quality is stable enough that provider
  comparison would answer a model-quality question instead of masking harness debt.

## Invariants

- Operator-only: `@Profile({"local","dev","benchmark"})` + SUPER_ADMIN via the `/api/v2/test/**` matcher. Not a
  customer surface.
- `unregister` MUST run on every terminal/exception path (it's in `finally`) — else a dangling session
  leaks + `isRemote` stays true for that runId.
- No `RunOrchestrator` / pipeline coupling — this is a direct `WorkerAgent.run` invocation.
