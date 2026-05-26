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
mvn -q -Dtest=PersonalAccessTokenServiceTest test     # the new unit test
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

## 2. Client core — `clients/core`

```bash
cd clients/core
npm install
npm run typecheck     # tsc --noEmit
npm run build         # emits dist/
```

- Registry was blocked in-session ⇒ `node_modules` is absent and TS was **never typechecked**. Expect to fix minor type nits. Zero runtime deps; Node ≥ 18 (you have 22).
- Watch item: the web globals (`fetch`/`Response`/`ReadableStream`/`TextDecoder`/`AbortSignal`/`URLSearchParams`) are provided by `@types/node` (Node 18+). If tsc can't find `ReadableStream`, confirm `@types/node` resolved and `tsconfig` `types`/`lib` are applied.

---

## 3. Not-yet-built — these paths WILL fail until the backend tool-proxy lands

The **REST + SSE** half of the core works against the **existing** API today (auth, submit run, list, view, stream events, approve plan gate, report). But the **local-execution loop** (`LocalExecutionSession` + `ToolRuntime`) needs server surface that does **not exist yet**:

- `localExecution` run mode (additive flag on `RunRequest`).
- A `TOOL_CALL` SSE event whose `payload` is a JSON `ToolCallFrame`.
- `POST /api/runs/{id}/tool-results` to receive a `ToolResultFrame`.
- Server-side: `RemoteToolDispatcher` + the **gated** branch in `ToolRegistry.ContextBoundToolCallback.call()` (no-op unless a `LOCAL` session is bound) + LOCAL-mode disabling the server clone + `ScmPushService` auto-push.

Until those land, `LocalExecutionSession.run()` connects but receives no `TOOL_CALL` events and `postToolResult` 404s. This is the next build slice (additive, flag-gated, zero impact on the default pipeline).

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
