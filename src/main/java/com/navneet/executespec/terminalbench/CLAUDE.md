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
`POST /api/runs/{id}/tool-results`. The worker is provider-agnostic — **DeepSeek-first** (cheap),
then Anthropic / OpenAI / Gemini.

A Terminal-Bench task is a single terminal goal, not a multi-task code spec, so this path runs the
worker **directly** — NO classifier / planner / reviewer / scoring / PR push. It reuses the built
remote-tool protocol + the run SSE / scoped-token / cost / access infra; it does **NOT** touch
`RunOrchestrator` or the product pipeline.

## Files

| File | Purpose |
|------|---------|
| `TerminalBenchController.java` | `POST /api/v2/test/terminal-bench/start` `{instruction, provider, model, maxIterations?}` → `{runId, sseToken}`. `@Profile({"local","dev"})` + under `/api/v2/test/**` which `SecurityConfig` locks to SUPER_ADMIN → operator-only. |
| `TerminalBenchRunService.java` | Persists a `PipelineRun` (ownership stamped, `harnessVersion="v2"` so `/cost` reads the V2 tracker, `runMode=PATCH`, `sourceType="TERMINAL_BENCH"`) → `RemoteToolDispatcher.register(runId)` → launches the worker on a context-propagated virtual thread with a remote `ExecutionContext(runId)` → on terminal: persists status + finalizes cost + emits the terminal `COMPLETE` SSE event + **`unregister(runId)` in `finally`** (always, so a crash can't leak a session). Holds the terminal-task `TB_SYSTEM_PROMPT`. |

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

## Invariants

- Operator-only: `@Profile({"local","dev"})` + SUPER_ADMIN via the `/api/v2/test/**` matcher. Not a
  customer surface.
- `unregister` MUST run on every terminal/exception path (it's in `finally`) — else a dangling session
  leaks + `isRemote` stays true for that runId.
- No `RunOrchestrator` / pipeline coupling — this is a direct `WorkerAgent.run` invocation.
