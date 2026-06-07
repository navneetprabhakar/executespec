# `@executespec/cli` — Architecture

> The ExecuteSpec command-line client. Companion: [`CODEMAP.md`](CODEMAP.md). Design of record:
> `ADR-0001-unified-spec-client` + `cli-vscode-unified-client-2026-05-30` (main repo `docs/`).

## 1. Purpose & principles

A **thin terminal client** for the ExecuteSpec pipeline. The defining constraint is **local hands /
remote brain**:

- The **brain stays on the server** — classifier → planner → worker → reviewer, all prompts, model
  routing, and scoring. The CLI never embeds any of it.
- The CLI is **hands + rendering**: parse intent, call the REST/SSE API, render results, and (in
  local mode) execute tool calls against the user's working tree.
- **Nothing is published by default.** Push/PR happens server-side for REMOTE runs; local-mode
  publishes nothing unless the user opts in.

Everything reusable lives in **`@executespec/core`** (the typed REST/SSE client, PAT token store,
local `ToolRuntime`). The CLI is one renderer over that core; the VS Code extension is the other.

## 2. Components

```
┌── @executespec/cli ────────────────────────────────────────┐
│  index.ts      entry: parse argv → dispatch group           │
│  args.ts       argv parser (positionals + flags)            │
│  ui.ts         ANSI render + prompt/confirm + error fmt     │
│  commands/                                                  │
│    auth.ts     login / status / logout (PAT)                │
│    run.ts      submit/watch/list/view/approve/reject/       │
│                cancel/report + local-hands `run local`      │
└───────────────┬─────────────────────────────────────────────┘
                │ imports
┌───────────────▼── @executespec/core ───────────────────────┐
│  ExecuteSpecApi (REST) · streamRunEvents (SSE) ·            │
│  TokenStore (PAT) · ToolRuntime + LocalExecutionSession     │
└───────────────┬─────────────────────────────────────────────┘
                │ HTTPS + SSE (PAT bearer)
        ExecuteSpec backend (unchanged pipeline)
```

## 3. Configuration & auth

- **Host:** `--host <url>` → `$EXECUTESPEC_HOST` → default `https://executespec.ai`. Point at
  `http://localhost:8080` for local dev.
- **Sign-in: gcloud-style browser flow (default).** `auth login` (no `--token`) calls
  `core.loopbackLogin()` — starts a one-shot `127.0.0.1` server, opens the browser to
  `${host}/cli-login?port=&state=`; the web page mints a PAT and redirects back to the loopback with
  `?token=&state=` (state-validated, loopback-only). Fallbacks: `--token <pat>` pastes one;
  `--no-browser` prompts for a paste (headless/SSH). The resulting `es_pat_…` is stored via the core
  `FileTokenStore` (`~/.config/executespec/token`, `0600`; `$EXECUTESPEC_TOKEN` overrides), sent as
  `Authorization: Bearer es_pat_…`, and resolves to the same identity (roles + tenant) a JWT would.
  No cookies, no refresh dance.

## 4. Data flow (how it talks to the backend)

The CLI uses **REST for actions** and **SSE for live streams** — no WebSocket. REST calls (core
`http.ts`) retry transient 5xx/429 with backoff, carry a **default per-request timeout** (so a
half-open socket can't hang a scripted invocation), and `submitRun` sends a stable **`Idempotency-Key`**
reused across retries so a retried submit can't duplicate a run (server-side dedupe on the header is a
pending backend follow-up).

**Deterministic command** (`run list`, `view`, `approve`, …):
```
CLI ──REST (Bearer PAT)──▶ backend ──JSON──▶ CLI render / --json
```

**Submit + watch a REMOTE run** (code cloned + executed server-side):
```
CLI ─POST /api/runs──────────▶ backend (clones repo, runs pipeline)
CLI ◀── 202 {runId} ──────────
CLI =GET /runs/{id}/events (SSE, scoped token)═▶
CLI ◀≈≈ RUN_STATUS / TASK_STATUS / FILE_WRITTEN / COST_UPDATE ≈≈
CLI ◀≈≈ GATE_OPENED ≈≈  →  [human: y/N]  ─POST /runs/{id}/approve──▶
CLI ◀≈≈ COMPLETE ≈≈      ─GET /runs/{id}/report──▶  score + PR URL
```
The watch loop (`run.ts`) consumes `streamRunEvents` (fresh 5-min scoped SSE token per connection,
reconnect with `Last-Event-ID`), renders **progress to stderr and the final result to stdout**, ends
on a terminal event, and **traps SIGINT/SIGTERM to cancel the server run (`DELETE /runs/{id}`) before
exiting 130** so Ctrl-C never orphans a billed run. `run attach <id>` backfills the persisted event
log (`GET /runs/{id}/event-log`) so you see the run from the start, then follows live.

**Output & exit-code contract (for scripting / CI):**
- `--format text | json | stream-json` (`--json` = `json`). **text** → human, progress on **stderr**,
  result on **stdout** (so `… | jq` is clean); **json** → one structured `{type:"result",…}` envelope
  on stdout; **stream-json** → NDJSON of every event (timestamped) followed by the result envelope.
- **Exit codes:** `0` success / clean PARTIAL · `1` FAILED/CANCELLED · `3` degraded PARTIAL
  (build/tests/reviewer failed) · `2` usage error · `130` SIGINT.
- `run submit -` reads the spec from stdin; the **PAT is pre-flighted** and enum flags
  (`--provider`/`--mode`/`--repo-mode`) validated **before** submit, so failures are actionable, not an
  opaque server 4xx.

**Local-hands run** (`run local`, **experimental**) — code stays on the machine:
```
CLI ─POST /api/runs {localExecution:true}─▶ backend (registers a remote session)
CLI =GET /runs/{id}/events (SSE)═▶
CLI ◀≈≈ TOOL_CALL {readFile/writeFile/shell/…} ≈≈   server agent wants a tool
    ToolRuntime runs it on the local tree (permission-gated)
CLI ─POST /runs/{id}/tool-results─▶  server agent continues
CLI ◀≈≈ COMPLETE ≈≈
```
The local `ToolRuntime` is **fail-closed**: `shell` / `buildProject` / `runTests` / `gitCommit` are
**denied** unless an explicit permission policy approves them — `createInteractivePermissions(ask)`
for a per-command y/N/a prompt (with session memory), or `allowAllPermissions()` for trusted /
non-interactive contexts. Path-confined file tools stay open (`safeResolve` keeps them inside the
workspace). So local-hands can never run an arbitrary command silently.

The server-side activation wiring for this path is **not yet shipped**, so `run local` is inert
today (the `localExecution` flag is dropped by older backends). It is wired client-side and ready
for when the server local-mode lands — activation must pass an interactive permission policy.

## 5. Governance

The plan gate is a **deterministic human checkpoint**: while watching, `GATE_OPENED` triggers a
`[y/N]` prompt (or `--yes` auto-approves; `--json`/non-interactive prints the approve command). The
model never silently approves — approval is always a keypress or an explicit `run approve`.

## 6. What is NOT here

- No agent loop / prompts / model routing / scoring (server-side moat).
- No direct LLM-provider calls; no clone/push (server does it for REMOTE runs).
- No durable server-side chat state; no secret material logged (PAT redacted).

## 7. Build, run, distribute

```bash
npm install                                            # workspace root (clients/)
npm run build -w @executespec/core                     # core first (cli depends on it)
npm run build -w @executespec/cli                      # → cli/dist/index.js (shebang preserved)
npm link -w @executespec/cli                           # `executespec` + `es` on PATH
# later: npm publish (own repo) — core is the one shared dependency to publish/vendor alongside.
```

## 8. Roadmap (Shape B)

Per `ADR-0001`, the CLI evolves into a **chat-first** client: `spec` with no args drops into a chat
REPL where an LLM interprets intent and calls *action tools* (`submit_run`/`approve_plan`/…), while
the deterministic verbs above are **retained** for scripting/CI. The chat surface adds
`core/chat.ts` (SSE over `POST /api/spec/chat`) + `repl.ts` + `render.ts`; the backend endpoint
(`SpecChatController`) exists in skeleton form. Feature parity with the VS Code client is the
invariant: every capability ships to both.
