# Local verification — CLI/VS Code client work

> **Why this doc exists:** this work was built in the Claude Code **web sandbox**, whose network
> policy returns **403** for Maven Central, the Spring repos, **and** the npm registry (no warm
> cache). So **nothing here was compiled, typechecked, or tested in-session** — it's reasoned
> against the real code, not machine-verified. Run the steps below on a local checkout of branch
> `claude/modest-sagan-6Tge7` and fix anything that surfaces.

---

## 1. Backend — Personal Access Tokens (commit `f8c57bf`)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS; use your JDK21 home elsewhere
mvn -q -Dtest='PersonalAccessTokenServiceTest,RemoteToolDispatcherTest,ToolRegistryWorkerBindTest' test
mvn -q verify                                         # full build incl. spotless format gate
```

- **If spotless fails:** the new files follow google-java-format, but run `mvn spotless:apply` if the gate complains, then re-`verify`.
- **Flyway:** `V215__personal_access_token.sql` applies on boot against Postgres. Confirm the head is V215 and the `personal_access_token` table exists.
- **Manual smoke** (server up at `$H`, e.g. `http://localhost:8080`; get a `$JWT` via `POST /api/auth/login`):
  ```bash
  # create — returns the es_pat_ plaintext ONCE
  curl -sXPOST $H/api/auth/me/tokens -H "Authorization: Bearer $JWT" \
       -H 'Content-Type: application/json' -d '{"name":"laptop"}'
  # use the PAT as a bearer — should resolve to your user
  curl -s $H/api/auth/me -H "Authorization: Bearer es_pat_..."
  # list (secret-free) / revoke
  curl -s $H/api/auth/me/tokens -H "Authorization: Bearer $JWT"
  curl -sXDELETE $H/api/auth/me/tokens/<id> -H "Authorization: Bearer $JWT"   # 204; PAT then 401s
  ```
- **Reasoned-not-compiled — confirm:**
  - `commons-codec` `DigestUtils` is on the compile classpath (pom L271, no scope ⇒ compile). ✓ by reading.
  - `/api/auth/me/tokens` is auth-gated by the existing `/api/auth/me/**` matcher (`SecurityConfig` L144) — a no-token request must 401/403.
  - The `JwtAuthenticationFilter` PAT branch leaves the JWT path unchanged — existing auth tests must still pass.

---

## 2. Clients — `clients/` (npm workspaces: `core` + `cli`)

```bash
cd clients
npm install            # installs both workspaces (the registry was 403-blocked in-session)
npm run build          # builds @executespec/core THEN @executespec/cli (order matters)
npm run typecheck      # or typecheck only (also builds core first, for its .d.ts)
```

- `core` has zero runtime deps; `cli` depends on `core` (built first — the root scripts enforce the order). Node ≥ 18 (you have 22).
- TS was **never typechecked** in-session ⇒ expect to fix minor nits. The `cli` shebang (`#!/usr/bin/env node`) is preserved by tsc into `dist/index.js`.
- Watch item: the web globals (`fetch`/`Response`/`ReadableStream`/`TextDecoder`/`AbortSignal`/`URLSearchParams`) come from `@types/node` (Node 18+). If tsc can't find `ReadableStream`, confirm `@types/node` resolved and the `tsconfig` `types`/`lib` are applied.
- Try the CLI (after build + a PAT):
  ```bash
  export EXECUTESPEC_HOST=http://localhost:8080
  node clients/cli/dist/index.js auth login --token es_pat_...
  node clients/cli/dist/index.js run submit "add a /health endpoint" --repo https://github.com/me/app --watch
  node clients/cli/dist/index.js run list
  ```
  (Or `npm i -g clients/cli` / `npm link` to get the `executespec` + `es` binaries.) The `run local` command is wired but **experimental** — it needs the §3 activation wiring on the server before tool calls actually arrive.

---

## 3. Backend tool-proxy — built but DORMANT (zero impact until activated)

The **REST + SSE** half of the core works against the **existing** API today (auth, submit run, list, view, stream events, approve plan gate, report). The server half of local-hands now exists and is wired, but it is **inert by default** — the dispatch branch fires only once `RemoteToolDispatcher.register(runId)` is called, and nothing calls it yet, so the default server-executed pipeline is byte-for-byte unchanged. **Landed:**

- `sse/RunEvent.toolCall(...)` — the `TOOL_CALL` event factory.
- `tool/remote/RemoteToolDispatcher` — `register`/`unregister`/`isRemote`/`dispatch` (blocks for the client result, 120s) / `complete`.
- `tool/remote/RemoteToolController` — `POST /api/runs/{id}/tool-results` (write-guarded via `RunAccessGuard.requireRunWriteAccess`).
- `tool/ToolRegistry` — gated branch in `ContextBoundToolCallback.call()` (null- and `isRemote`-guarded; no-op for every server-run).

**Remaining activation wiring** (the last connective step — do this in a compile-capable env, additive + flag-gated):

1. Mark a run as local. **NOTE:** `RunRequest` is a Java record constructed *positionally* (e.g. `RunController` fork), so don't just append a field blind — either add it at the end **and** fix every positional `new RunRequest(...)` caller, or (cleaner) carry the flag on `PipelineRun` / the run-mode rather than the request DTO.
2. In `RunOrchestrator`: for a local run, call `remoteToolDispatcher.register(runId)` at run start and `unregister(runId)` at every terminal path (success / failure / cancel).
3. In local mode, **skip the server-side repo clone** (the workspace *is* the client's local tree, reached only via tool calls) and **skip `ScmPushService` auto-push** (no PR/publish by default).
4. The client subscribes to the run's SSE stream (it does, via `LocalExecutionSession`) to receive `TOOL_CALL` frames and POST results back.

Until 1–3 land, a local run can't be initiated, the dispatcher is never registered, and the branch never fires — hence zero impact on existing behavior. `LocalExecutionSession.run()` will connect but see no `TOOL_CALL` events.

---

## 4. Tool-contract parity to confirm when wiring the proxy

The client `ToolRuntime` must match each server `tool/*` `inputSchema()` and the `ToolResult` envelope exactly, or local-run output quality drifts from server-run. I mirrored these arg shapes — **diff them against the server tools** (`tool/ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListFilesTool`, `SearchFilesTool`, `ShellTool`, `BuildProjectTool`, `GitStatusTool`, `GitDiffTool`, `RunTestsTool`) and adjust if they differ:

| tool | args I assumed |
| --- | --- |
| `readFile` | `{ path }` |
| `writeFile` | `{ path, content }` |
| `editFile` | `{ path, oldString, newString, replaceAll? }` |
| `listFiles` | `{ path? }` |
| `searchFiles` | `{ query \| pattern \| regex, path? }` |
| `shell` | `{ command }` |
| `buildProject` | `{ command? }` (else auto-detect) |
| `runTests` | `{ command? }` (else auto-detect) |
| `gitStatus` | `{}` |
| `gitDiff` | `{ path? }` |
| `gitCommit` | `{ message }` |

Truncation limits mirrored from the server token-cost controls: readFile 8k / shell 6k / diff 5k chars.
