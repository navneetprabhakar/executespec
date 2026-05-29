# Codemap — opencode (anomalyco/opencode)

> Monorepo navigation map. TypeScript/Bun. Two stacks: the live runtime in `packages/opencode/`, and
> a newer Effect rewrite in `packages/core/` (the `*V2` modules, dual-written behind
> `flags.experimentalEventSystem`). When in doubt, the runtime is in `packages/opencode/src/`.

## Packages (top level)

| Package | What it is |
|---------|-----------|
| **`packages/opencode/`** | **The live runtime** — agent loop, tools, providers, config, permissions, CLI, server. The heart. |
| `packages/core/` | Effect/Schema v2 rewrite (event-sourced session layer, provider/permission V2, agent registry). |
| `packages/llm/` | Standalone schema-first LLM engine — 4-axis `Route` (Protocol/Endpoint/Auth/Framing); native tool-call normalization (alternative to the AI SDK). |
| `packages/plugin/` | Plugin SDK — the `Hooks` interface + `ToolDefinition`/`AuthHook` types. |
| `packages/sdk/js/` | **Auto-generated** TypeScript SDK (off the server's OpenAPI spec) + `createOpencodeClient`/`createOpencodeServer`. |
| `packages/enterprise/` | SolidStart app — session **sharing** (`core/share.ts`). |
| `packages/console/` | SST/AWS hosted control plane (teams/accounts); `packages/identity/` = auth. |
| `packages/app` · `web` · `desktop` · `ui` | SolidJS web app · Astro docs · Electron desktop · shared components. |
| `packages/slack` · `github/` | Slack bot · GitHub Action. |
| `sdks/vscode/` | VS Code extension (~137 LOC — hosts the TUI in a terminal). |

## `packages/opencode/src/` — the runtime (where to look)

| You want… | Go to |
|-----------|-------|
| The agent loop | `session/prompt.ts` (`runLoop`), `session/processor.ts` (turn), `session/llm.ts` (`streamText`) |
| Tools | `tool/registry.ts` (registry), `tool/tool.ts` (contract), `tool/{edit,write,apply_patch,shell,read,grep,glob,task,plan}.ts` |
| Edit semantics (fuzzy cascade) | `tool/edit.ts` (Simple→LineTrimmed→BlockAnchor/Levenshtein) |
| Shell safety (tree-sitter) | `tool/shell.ts` + `permission/arity.ts` (`BashArity`) |
| Prompt / context assembly | `session/system.ts` (per-model base + env), `session/instruction.ts` (AGENTS.md walk-up + lazy local rules), `session/reminders.ts` |
| Per-model prompts | `session/system.ts` `provider()` → `anthropic.txt`/`gpt.txt`/`gemini.txt`/… |
| Session / persistence | `session/session.ts` (+ `.sql.ts`, Drizzle/SQLite), parts model |
| Compaction | `session/compaction.ts` + `session/overflow.ts` |
| Agents / modes | `agent/agent.ts` (build/plan/general/explore/scout/compaction/title/summary) |
| Permissions | `permission/index.ts` (`ask`/`evaluate`), `permission/arity.ts`, `permission/schema.ts` |
| Providers | `provider/provider.ts` (CUSTOM_LOADERS, `getSmallModel`), `provider/auth.ts` |
| Model catalog | `models-dev.ts` (models.dev fetch+cache); `packages/core/src/plugin/provider/*` (V2 provider plugins) |
| Config | `config/config.ts`, `config/agent.ts`, `config/permission.ts` |
| Cost / usage | `session/session.ts` `getUsage()` (5-bucket, cache-aware) |
| CLI | `cli/cmd/run.ts` (headless `run`, `--format json`), `cli/cmd/serve.ts`, `cli/cmd/acp.ts` |
| Server | `server/server.ts` (Effect HttpApi), `server/routes/`, `server/mdns.ts`, `server/auth.ts` |
| Remote / workspace adapters | `control-plane/types.ts` (`Target`), `server/middleware/workspace-routing.ts` |
| Plugins | `plugin/index.ts` (`Plugin.layer`), `plugin/loader.ts`, `packages/plugin/src/index.ts` (Hooks) |

## `packages/core/src/` — v2 schema layer

`session.ts`/`session-event.ts`/`session-message.ts`/`session-message-updater.ts` (event union +
immer reducer), `agent.ts`/`permission.ts`/`provider.ts`/`model.ts`/`catalog.ts`/`models-dev.ts`,
`plugin/{boot,env,account,models-dev}.ts` + `plugin/provider/*.ts` (the per-provider plugins).
