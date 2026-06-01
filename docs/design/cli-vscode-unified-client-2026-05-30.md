# `spec` Unified Client — Technical Design (CLI + VS Code)

> **Date:** 2026-05-30 · **Status:** Proposed · **Owner:** RL-2026-05-052 (client track)
> **Decision record:** [`docs/adr/ADR-0001-unified-spec-client.md`](../adr/ADR-0001-unified-spec-client.md)
> **Execution plan:** [`docs/plans/cli-vscode-unified-client-execution-2026-05-30.md`](../plans/cli-vscode-unified-client-execution-2026-05-30.md)
> **Amends:** [`cli-plugin-spec-shape-b-2026-05-28.md`](cli-plugin-spec-shape-b-2026-05-28.md)

This document specifies *what* the unified `spec` client is and *how* it is built. The organizing constraint, from ADR-0001, is **feature parity: every capability is reachable from both the CLI and the VS Code extension, backed by one shared core.**

---

## 1. Goals / non-goals

**Goals**
- One product, two renderers (CLI + VS Code), strict feature parity.
- Chat-first interactive UX *and* deterministic scriptable verbs, on the same core.
- Local-hands/remote-brain execution: code stays local, nothing published by default.
- Multi-provider cost routing (Anthropic / OpenAI / Gemini / OpenRouter / DeepSeek) surfaced to the user.
- Deterministic, audited plan-gate governance.
- Honest economics: measure the stateless-chat cost before scaling.

**Non-goals (V1)**
- Cursor-style inline completions / gutter actions / file watchers (deferred to a later "editor-mode" effort).
- A local agent loop (the brain stays server-side — non-negotiable moat).
- OpenAPI-generated SDK (interim is the hand-maintained typed core; future ADR).
- Web-IDE chat ↔ CLI chat sync (separate, non-synced conversations by design).
- New billing/pricing surfaces (the clients consume existing run-credit + cost APIs unchanged).

## 2. Architecture: one core, two renderers

```
                         ┌──────────────────────────────────────────────┐
   CLI renderer          │           clients/core  (the product)         │
   ┌──────────────┐      │                                               │
   │ repl + verbs │◄────►│  capabilities (one impl each, surface-agnostic):│
   │ ANSI render  │      │   • auth (PAT)          • chat session         │
   └──────────────┘      │   • runs (submit/watch/ │   (SSE consumer +    │
                         │     list/view/approve/  │    conversation mgr) │
   VS Code renderer      │     reject/cancel/report)│  • local state       │
   ┌──────────────┐      │   • project link/unlink │  • render model      │
   │ sidebar chat │◄────►│   • ToolRuntime (local  │    (surface-agnostic) │
   │ tree + modals│      │     hands)              │  • capability registry│
   │ webview      │      │   • provider/model      │    (parity key)       │
   └──────────────┘      └──────────────────────────────────────────────┘
                                          │ HTTPS + SSE (PAT bearer)
                                          ▼
                          ExecuteSpec backend (unchanged brain)
                          REST + /api/spec/chat + tool-proxy
```

**Rules of the seam:**
- `core` contains **all** logic; it imports neither `node:readline`/ANSI nor `vscode`. It is pure TypeScript + Node stdlib (and `fetch`/`ReadableStream` web globals).
- Each renderer depends on `core` and on its host (`node:tty`/ANSI for CLI; `vscode` API for the extension). Renderers contain only presentation + host wiring.
- A **capability registry** in `core` is the single source of truth for the feature list. Each capability has a stable id, a typed input/output, and metadata (interactive? scriptable? requires-workspace? mutating?). Both renderers enumerate the registry to build their surfaces; the parity check (§9) diffs the two enumerations.

This is the existing structure (`clients/core` already holds `api.ts`/`sse.ts`/`auth.ts`/`tools/runtime.ts`/`session.ts`) extended with `chat.ts`, `localState.ts`, `link.ts`, `render.ts`, and `capabilities.ts`.

## 3. The two interaction layers

### 3.1 Conversational (default, interactive)
- CLI: `spec` with no args on a TTY drops into a chat REPL (`readline`-based).
- VS Code: a chat sidebar webview (`chatPanel`), same conversation model.
- An LLM interprets intent and may call **action tools** (`submit_run`, `approve_plan`, …). Tokens stream; tool calls render inline; results render inline.
- Conversation history is local-only (§7). The server is sent the relevant history each turn (§6.4).

### 3.2 Deterministic (retained, first-class, for scripts/CI/non-TTY)
- CLI: `spec <group> <verb> [args] --flags`, `--json` on every read. Non-TTY or piped input → deterministic mode automatically (no REPL).
- VS Code: command-palette commands + tree context menus + buttons map 1:1 to the same `core` calls.
- These exist because automation must not depend on an LLM interpreting intent, and governance actions must be deterministic.

### 3.3 How they relate
Both layers call the identical `core` capability functions. The chat layer's action tools are *thin adapters* that call the same functions the verbs call. There is no second implementation of "submit a run."

## 4. Feature Matrix (the parity contract)

Every row ships to **both** surfaces. "—" is never allowed in a shipped row; a row with a "—" is incomplete.

| # | Capability | CLI affordance | VS Code affordance | Mutating? | Needs workspace? |
| --- | --- | --- | --- | --- | --- |
| F1 | **Auth: sign in (PAT)** | `spec auth login [--token]` | `ExecuteSpec: Sign In` (SecretStorage) | y | n |
| F2 | **Auth: status / whoami** | `spec auth status [--json]` | status bar item + `…: Account` | n | n |
| F3 | **Auth: sign out** | `spec auth logout` | `…: Sign Out` | y | n |
| F4 | **Mint PAT (deep link)** | `spec auth token --new` → opens web token page | `…: Create API Token` → opens web | n | n |
| F5 | **Chat (agentic)** | `spec` (TTY) → REPL | sidebar chat webview | y* | optional |
| F6 | **Submit run** | `spec run submit "<spec>" [flags]` | `…: Submit Spec…` (palette + tree `+`) | y | optional |
| F7 | **Watch run (live)** | `spec run watch <id>` | tree click → OutputChannel/webview stream | n | n |
| F8 | **List runs** | `spec run list [--status --scope --json]` | Runs TreeView | n | n |
| F9 | **View run detail** | `spec run view <id> [--json]` | tree item → detail webview | n | n |
| F10 | **Approve plan gate** | `spec run approve <id> [--credits]` + REPL `[y/N]` | modal/button + tree action | y | n |
| F11 | **Reject plan gate** | `spec run reject <id> [--feedback]` + REPL `[y/N]` | modal/button + tree action | y | n |
| F12 | **Cancel run** | `spec run cancel <id>` | tree action / command | y | n |
| F13 | **Run report (score)** | `spec run report <id> [--json]` | report webview | n | n |
| F14 | **Cost snapshot** | `spec run cost <id> [--json]` | shown in detail webview + status | n | n |
| F15 | **Local-hands run** | `spec run submit … --local` / REPL "(local)" | toggle "Run locally" + per-project default | y | **y** |
| F16 | **Tool-call permission prompts (local)** | `[allow/deny]` per side-effecting tool | per-tool quick-pick / "always allow" | y | y |
| F17 | **Link directory ↔ project** | `spec link [--project <id>] [--create]` | `…: Link Folder to Project` | y | y |
| F18 | **Unlink** | `spec unlink` | `…: Unlink Folder` | y | y |
| F19 | **Select provider/model** | `--provider/--model` + REPL `/provider` | settings + chat `/provider` quick-pick | n | n |
| F20 | **Open PR / artifacts** | prints PR URL | "Open PR" notification + detail link | n | n |
| F21 | **Local session resume** | `spec` resumes last session per project | sidebar restores last conversation | n | optional |
| F22 | **Config (host/defaults)** | `spec config get/set` + env | Settings (`executespec.*`) | y | n |
| F23 | **Tail run logs** | `spec run logs <id>` | OutputChannel | n | n |
| F24 | **Retry failed task** | `spec run retry <id> <taskId>` | tree action on failed task | y | n |
| F25 | **Diagnostics / doctor** | `spec doctor` (host reachable, token valid, docker present) | `…: Run Diagnostics` | n | n |

\* F5 is mutating only when the model calls a mutating action tool, each of which is independently guarded and (for governance) human-confirmed.

**Surface-specific presentation is allowed; surface-specific *capability* is not.** Example: F13 renders as ANSI text in the CLI and as a webview in VS Code — same capability, different render. What is forbidden is a capability existing in one surface only.

## 5. What should and should NOT be provided

### 5.1 Should provide (in scope)
- Everything in the Feature Matrix (F1–F25), on both surfaces.
- PAT-only auth; env (`EXECUTESPEC_TOKEN`) and file/SecretStorage token sources.
- Both REMOTE and LOCAL execution, explicit + remembered per project.
- Multi-provider selection incl. DeepSeek/OpenRouter cost routing.
- Deterministic + audited plan-gate path on both surfaces.
- Local conversation persistence + resume.
- Robust SSE reconnect, bounded retries/backoff, graceful offline behavior.
- `--json` machine output for every read verb (scripting contract).
- A `doctor`/diagnostics capability.

### 5.2 Should NOT provide (explicit exclusions)
- **No client-side agent loop / prompts / planner / scoring.** The brain stays server-side. (Moat.)
- **No durable server-side storage of chat content.** Only the `spec_chat_audit` row (no message bodies).
- **No auto-publish/push by default.** Push/PR is an explicit opt-in action, never implicit, especially in LOCAL mode.
- **No silent project auto-creation.** Linking is explicit + confirmed (revises Shape B #4).
- **No LLM-only governance.** The model may surface a gate but cannot be the *only* way to approve/reject.
- **No unguarded side-effecting tools in LOCAL mode.** `shell`/`buildProject`/`runTests`/`gitCommit` pass through a permission gate (`ToolPermissions.allowCommand`).
- **No writes outside the workspace root.** `safeResolve` rejects path escapes; absolute/`..` paths are `FORBIDDEN`.
- **No cookie/refresh-token auth in clients.** PAT is the whole credential.
- **No secret material in logs / audit / telemetry.** PAT never logged; IP stored only as a SHA-256 hash.
- **No inline editor completions / gutter actions (V1).** Deferred.
- **No cross-org or admin operations** beyond what the caller's role already allows on the web.
- **No bundling of the `vscode` module into core**, and no `node:`-only API in `core` paths the extension host can't run.

## 6. Backend contracts

### 6.1 Auth — Personal Access Tokens (landed, V215)
`Authorization: Bearer es_pat_…`. `JwtAuthenticationFilter` routes the `es_pat_` prefix to `PersonalAccessTokenService.resolveUserId` and binds the **same `AppUserDetails`** a JWT would (identical roles + tenant). `POST/GET/DELETE /api/auth/me/tokens`. No change required; the clients already model `CreatePatRequest`/`PatDto`/`CreatePatResponse`.

### 6.2 Run lifecycle (existing REST)
`POST /api/runs`, `GET /api/runs[/{id}]`, `POST /api/runs/{id}/approve`, `DELETE /api/runs/{id}`, `GET /api/runs/{id}/{events,sse-token,cost,report,event-log}`, `POST /api/runs/{id}/tasks/{taskId}/retry`. Consumed by `core/api.ts` (already implemented). SSE uses a 5-min scoped token minted per connection; `core/sse.ts` reconnects with `Last-Event-ID` (already implemented).

### 6.3 Chat — `POST /api/spec/chat` (Phase-1 skeleton landed; loop pending)
PAT-authenticated SSE endpoint. Request: `{sessionId, projectId?, localContext?, conversation[], providerOverride?}`. Response SSE: `event: token {delta}`, `event: tool_call {tool,args,id}`, `event: tool_result {id, result}`, `event: done {usage, cost_usd, turn_count}`. Server is stateless for content; persists only `spec_chat_audit` (`session_id, project_id, user_id, org_id, turn_count, timestamps, model_used, ip_hash` — no bodies).

**Phase-1 step 3 (the real loop) design:**
1. Translate incoming `conversation` → Spring AI `Message` list.
2. Build per-turn `ProjectChatActionTools.ToolContext` + bind action tools (and read-only `ProjectChatTools`) via `LlmRequest.toolsOverride(...)`.
3. Stream model tokens → `event: token`.
4. On model tool-call → `event: tool_call`, dispatch via the bound `ToolCallback`, append result to history, `event: tool_result`, continue.
5. Terminal → `event: done` with usage + cost from `RunCostTracker`.
6. Route through `LlmRouter` at the LIGHTWEIGHT tier (the chat router is an intent layer, not a coder).

### 6.4 Statelessness vs. prompt cache (the open economic risk)
Shape B locks "server stateless; client re-sends full history each turn." This maximizes privacy but risks **cold prompt cache every turn**, which the competitive analysis names as the #1 cost lever for incumbents. Design response:
- **Default:** stateless; client compacts (keep last N turns verbatim, summarize older) to bound payload.
- **Measured gate (Phase 2):** measure $/turn and cache-hit rate on a representative multi-turn session against the cost-pitch envelope.
- **Pre-approved fallback if the gate fails:** a **prompt-prefix cache** keyed by `(user, session)` holding only the *cache-eligible prefix* (system + tool schemas + stable early turns), encrypted, short TTL (≤ chat idle window), **never** durable, **never** message-content storage beyond the cache TTL, and surfaced in the audit as "prefix-cache used." This preserves "we don't store your conversations" while restoring cache hits.

### 6.5 Local-hands tool-proxy (landed dormant)
`ToolRegistry.ContextBoundToolCallback.call()` branches to `RemoteToolDispatcher.dispatch(...)` iff `isRemote(runId)`. `RemoteToolDispatcher` emits a `TOOL_CALL` SSE event and blocks (120s) for the client to `POST /api/runs/{id}/tool-results`. The client `LocalExecutionSession` consumes `TOOL_CALL`, runs the tool via `ToolRuntime`, and posts the byte-compatible envelope back. Activation wiring (Phase 3): `register(runId)` at LOCAL run start, `unregister(runId)` at every terminal; skip server clone + skip auto-push for LOCAL runs.

### 6.6 Tool contract parity
The client `ToolRuntime` must match each server `tool/*` `inputSchema()` and the `ToolResult` envelope. Truncation limits mirror the server (readFile 8k / shell 6k / diff 5k). This is implemented in `core/tools/runtime.ts`; a contract test (§9) diffs client arg-shapes against the server tool schemas.

## 7. Local state layout

Identical semantics on both surfaces; different physical homes.

**CLI:** `~/.executespec/` (override `EXECUTESPEC_CONFIG_DIR`)
```
token                       # PAT, 0600 (existing FileTokenStore)
state.json                  # default provider, exec-mode prefs, recent-runs cache
sessions/<session>.json     # per-session metadata
chats/<projectId>-<session>.jsonl   # one line per turn (local-only history)
logs/<runId>.log            # local run logs
```
**VS Code:** PAT in `SecretStorage` (existing `SecretTokenStore`); session/chat/state in `ExtensionContext.globalState`/`workspaceState` and `globalStorageUri` (jsonl), mirroring the CLI layout key-for-key so a `core/localState.ts` abstraction serves both via a `StateBackend` interface (`FileBackend` for CLI, `VsCodeBackend` for the extension).

**Per linked directory:** `<root>/.executespec/project.json` = `{projectId, linkedAt, defaultProvider, defaultLocality}`. Written only by an explicit link action (F17).

## 8. Security model

- **Credential:** PAT only; 0600 file / encrypted SecretStorage; never logged; redacted in any diagnostic dump.
- **Tenant guards:** every mutating action re-checks access server-side. **Fix required:** `ProjectChatActionTools.approve_plan/reject_plan/cancel_run` currently call `requireRunAccess` (read); they must call `requireRunWriteAccess` (write) — these are write/governance actions reached via PAT. `RemoteToolController` already uses the write guard correctly.
- **Coverage pin:** add `/api/spec/` to `AccessGuardCoverageTest` (currently absent), so the chat surface is held to the same guard-coverage bar as the rest of the API.
- **Local file sandbox:** `safeResolve` confines all file tools to the workspace root; `..`/absolute escapes → `FORBIDDEN`.
- **Side-effect gate:** `shell`/`build`/`test`/`commit` require `ToolPermissions.allowCommand` approval; default-deny is offered as a config (`autoApprove=false`).
- **No egress in LOCAL mode:** the run's workspace is the local tree; the server never receives file contents except as tool *results* the user's machine chose to return. The test plan verifies zero source upload.
- **Audit:** `spec_chat_audit` records metadata only (no bodies); IP is SHA-256 hashed.
- **Controller hygiene:** `SpecChatController` should return proper `4xx` (`ResponseStatusException`) for missing auth / bad request rather than raw `IllegalState/IllegalArgumentException` (current skeleton throws the latter).

## 9. Parity + contract enforcement

- **Capability registry** (`core/capabilities.ts`): the canonical feature list with ids + metadata.
- **Parity check** (test + lint): asserts the CLI command table and the VS Code `contributes` both cover exactly the registry's interactive/scriptable capability ids. A capability present in one and not the other fails CI.
- **Tool-contract test:** diffs `core/tools/runtime.ts` arg shapes + envelope against the server `tool/*` schemas.
- **`--json` snapshot tests:** every read verb's JSON output is snapshotted so the scripting contract can't silently change.
- **SSE parser unit tests:** event framing, `Last-Event-ID` resume, terminal detection, malformed-frame tolerance.

## 10. Known defects in the landed code (to fix as part of this work)

1. **Write actions guarded as reads.** `ProjectChatActionTools` approve/reject/cancel use `requireRunAccess`; must be `requireRunWriteAccess`. (§8)
2. **Locality passed into the mode slot.** `ProjectChatActionTools.submit_run` passes `"REMOTE"` as `submitRun(...)`'s `executionMode` argument, which expects `PRODUCTION|PROTOTYPE`. Locality must move to `PipelineRun.executionLocality` (Phase 3); `executionMode` must carry a valid value.
3. **Three incompatible "local" notions.** Client `RunRequest.localExecution` (`types.ts`) + gen-1 `run local` send a field the server record lacks; Shape B locks `PipelineRun.executionLocality`. Converge on `executionLocality`; delete the dead client field + retarget `run local`.
4. **Controller error mapping.** `SpecChatController` throws raw exceptions for validation; map to 400/401.
5. **`/api/spec` not guard-pinned.** Add to `AccessGuardCoverageTest`.
6. **TS never compiled.** No `clients/` lockfile, not in CI; the whole TS surface is reasoned-not-compiled. First task is `npm install && npm run typecheck` + a CI job.

## 11. Open questions for the operator

1. Re-confirm the three revised Shape B decisions (ADR §6): explicit-link, explicit-locality, stateless-with-cache-fallback.
2. Cost envelope for the Phase-2 chat-cost gate — what $/turn is "acceptable" before the prefix-cache fallback is engaged?
3. VS Code chat UX: webview (richer, more build) vs. the native Chat API (`vscode.ChatParticipant`, less control). Recommendation: webview for render parity with the CLI.
4. Should `--local` ever be allowed without Docker present? (Affects `buildProject`/`runTests` detection.) Recommendation: allow file/shell tools; warn that build/test may fail without the toolchain.
