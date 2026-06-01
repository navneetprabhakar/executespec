# `@executespec/cli` — Codemap

> Per-file index of the ExecuteSpec CLI. A **thin terminal client**: it parses args, calls the
> shared `@executespec/core` REST/SSE client, and renders the results. **No agent loop, prompts,
> model routing, or scoring live here** — those stay server-side (the *local hands / remote brain*
> model). Companion: [`ARCHITECTURE.md`](ARCHITECTURE.md).

## File index

| File | LOC | Role |
| --- | --- | --- |
| `package.json` | 27 | `@executespec/cli@0.1.0`. Bins **`executespec`** + **`es`** → `dist/index.js`. Single runtime dep `@executespec/core` (workspace). Node ≥ 18, ESM (`"type":"module"`). `build`=`tsc`, `typecheck`=`tsc --noEmit`. |
| `tsconfig.json` | 21 | NodeNext ESM, target ES2022, `strict` + `noUncheckedIndexedAccess`. Emits `dist/` from `src/`, sourcemaps on. |
| `src/index.ts` | 76 | **Entry point** (carries the `#!/usr/bin/env node` shebang). Parses `argv`, handles `--version`/`--help`, builds the core client (`createClient({host})` — host from `--host` or `$EXECUTESPEC_HOST`), and dispatches the first positional to the `auth` or `run` command group. Top-level rejection → stderr + `process.exit(1)`. |
| `src/args.ts` | 65 | Zero-dependency argv parser. Splits positionals vs `--flag` / `--flag=value`; `--` ends flag parsing (passthrough). `BOOLEAN_FLAGS` (`watch`/`yes`/`json`/`tdd`/`local`/`help`/`version`/`skip-approval`) never consume the next token, so `--yes "spec"` keeps the positional. Accessors `flagStr` / `flagBool` / `flagNum`. |
| `src/ui.ts` | 51 | Terminal I/O. ANSI color helpers (`bold`/`dim`/`red`/`green`/`yellow`/`cyan`, gated on `stdout.isTTY` + `NO_COLOR`); `out` / `errln` / `printJson`; `prompt` / `confirm` over `node:readline/promises`; `errorMessage` (unwraps `ApiError` to `msg (HTTP nnn)`). |
| `src/commands/auth.ts` | 90 | `auth login` — **gcloud-style browser sign-in** by default (`core.loopbackLogin`); `--token` pastes a PAT, `--no-browser` prompts for a paste. Stores via the core `TokenStore` + verifies `/auth/me` (clears on failure). `auth status [--json]`, `auth logout`. |
| `src/commands/run.ts` | 385 | The run surface: `submit` / `watch` / `list` / `view` / `approve` / `reject` / `cancel` / `report` + experimental **`local`**. Owns the watch loop (consumes `streamRunEvents`, renders each event, prompts `[y/N]` on `GATE_OPENED`, breaks on terminal), the `RunRequest` assembly from flags, the final run-detail print, and the local-hands driver (`LocalExecutionSession` + `ToolRuntime` with a per-command permission prompt). |

## Module graph

```
index.ts ─┬─ args.ts            (parse argv)
          ├─ ui.ts              (render/prompt)
          ├─ commands/auth.ts ─┐
          └─ commands/run.ts ──┴─→ @executespec/core
                                     (ExecuteSpecApi · streamRunEvents ·
                                      TokenStore · ToolRuntime · LocalExecutionSession)
```
Only runtime dependency is `@executespec/core`. No other npm runtime deps (dev: `typescript`, `@types/node`).

## Command → core call map

| CLI command | core call(s) |
| --- | --- |
| `auth login/status` | `loopbackLogin` (browser) · `tokens.set/get` · `api.me()` |
| `run submit` | `api.submitRun(RunRequest)` (+ `streamRunEvents` if `--watch`) |
| `run watch` | `streamRunEvents` (+ `api.approve` on gate) |
| `run list` / `view` / `report` | `api.listRuns` / `getRun` / `getReport` |
| `run approve` / `reject` | `api.approve(runId, {approved})` |
| `run cancel` | `api.cancel(runId)` |
| `run local` | `api.submitRun({localExecution:true})` + `LocalExecutionSession.run()` (drives `ToolRuntime` on the local tree) |

## What is NOT here (by design)

- No agent loop, prompts, model routing, or scoring — server-side (moat).
- No direct LLM-provider calls; no GitHub clone/push (the server does that for REMOTE runs).
- The **chat REPL** (Shape B) is not built yet — today's surface is the deterministic verbs above. See `ARCHITECTURE.md` §Roadmap and the decision record `ADR-0001-unified-spec-client`.

## Build & run

```bash
npm install                 # from the workspace root (clients/)
npm run build -w @executespec/core && npm run build -w @executespec/cli
node cli/dist/index.js --help
# or: npm link -w @executespec/cli  → `executespec` / `es` on PATH
```
