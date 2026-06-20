# oss-agent — feature & capability surface

> Breadth analysis of oss-agent (anomalyco/oss-agent), complementing `CODING-TASKS.md` (the agent
> loop). Covers providers, permissions, cost, client/server, CLI, plugins, enterprise. Each tagged
> ➜ relevance to our harness / clients. Open-source; analysis/summary with cited paths.

> **Two stacks:** production runtime in `packages/oss-agent/src/`; a newer Effect rewrite in
> `packages/core/src/` (the `*V2` modules); a standalone schema-first LLM engine in `packages/llm/`.

## 1. Provider abstraction & model catalog ➜ strong (our multi-provider)

- **Production** (`packages/oss-agent/src/provider/provider.ts`): leans on the **Vercel AI SDK**. A
  `CUSTOM_LOADERS` table maps each provider to `getModel(sdk, id, opts) → LanguageModelV3`. Adding a
  provider = a models.dev entry naming its npm package (+ a custom loader only for quirks: Bedrock
  SigV4, Vertex ADC, Azure deployment URLs).
- **Plugin V2** (`packages/core/src/plugin/provider/*.ts`): each provider is a tiny
  `PluginV2.define({id, effect})` with hooks `aisdk.sdk` (lazy `import("@ai-sdk/x")` + `createX`),
  `aisdk.language` (responses vs languageModel), `catalog.transform` (mutate catalog: headers,
  disable bad aliases). Anthropic's whole plugin is ~25 lines. **`DynamicProviderPlugin`**
  npm-installs *any* provider package at runtime → ~30 providers, no 30 code paths.
- **Catalog = models.dev** (`models-dev.ts`): fetches `https://models.dev/api.json`, disk-cached
  (5-min freshness, hourly refresh, cross-process `Flock`), typed (cost, limits, modalities,
  `tool_call`, release date). Overridable (`OSS_AGENT_MODELS_URL`), offline snapshot
  (`OSS_AGENT_MODELS_DEV`), disable (`OSS_AGENT_DISABLE_MODELS_FETCH`).
- **Auth** (`plugin/{env,account}.ts`): layered — `EnvPlugin` marks a provider `enabled:{via:"env"}`
  if its env key is set → `AccountPlugin` overlays `account.json` (chmod 0600) credentials
  (`{type:"api",key}` or `{type:"oauth",access,refresh,expires}`). Precedence: account > env.
  `Provider.Info.enabled` is a tagged union (`false | {via:"env"|"account"|"custom"}`).

➜ **Borrow:** models.dev catalog (kill our YAML staleness); `catalog.transform`/`DynamicProvider`
hook shape (add providers without `LlmRouter` edits).

## 2. Model / agent config ➜ medium

- `config/config.ts`: top-level `model` (`"provider/model"`), **`small_model`** (cheap tasks), 
  `default_agent`, `enabled/disabled_providers`, per-provider overrides (apiKey/baseURL/models).
- **Per-agent** (`config/agent.ts`, markdown+frontmatter): `model`, `temperature`, `top_p`, `steps`
  (iter cap), `permission` ruleset, `options`. Deprecated `tools:{name:bool}` auto-collapses into
  `permission`.
- **`small_model` = our lightweight tier**: if unset, `getSmallModel()` picks from a hardcoded cheap
  priority list (`claude-haiku-4-5`, `gemini-3-flash`, `gpt-5-nano`…) or scores by cost+recency
  (`SMALL_MODEL_RE = /\b(nano|flash|lite|mini|haiku|small|fast)\b/`). Routing = **per-agent model pin +
  a "small" fallback**, not a fixed tier matrix.

## 3. Permission model ➜ strong

- `permission.ts`: `Rule = {permission, pattern, action: ask|allow|deny}`; **`evaluate()` = findLast
  over merged rulesets with wildcard match on permission + pattern → last-match-wins, default `ask`.**
  Rulesets stack: agent config → session-`approved` (DB-persisted).
- `fromConfig()` turns `{bash:{"git push":"ask","*":"allow"}, edit:"deny"}` into rules.
- **Shell** (`tool/shell.ts`): tree-sitter-parses the command → asks per sub-command with the literal
  command + an `always` glob from **`BashArity.prefix()`** (`git checkout main` → `git checkout *`);
  out-of-workspace paths raise a separate `external_directory` permission.
- **Flow** (`permission/index.ts ask`): deny→hard error; allow→pass; else publish `permission.asked`
  on the bus + block on a `Deferred` until `reply` (`once|always|reject`). `always` persists a rule +
  **auto-resolves other pending asks**. Reject feedback → `CorrectedError` fed to the model.
- **Yolo:** `--dangerously-skip-permissions` auto-replies `once`; non-interactive injects deny rules.

➜ **Borrow:** the declarative ruleset (express plan-gate/BYOK/guardrail as data) + tree-sitter+arity
shell gating (vs our regex `ShellSandboxPolicy`).

## 4. Cost / usage ➜ audit our tracker against this

`Session.getUsage()` normalizes AI SDK usage into **5 buckets** (input/output/reasoning/cache.read/
cache.write), **cache-aware with provider-specific metadata fallbacks**
(`anthropic.cacheCreationInputTokens`, `vertex.*`, `bedrock.usage.*`), subtracts cache tokens from
normalized input to avoid double-count, uses `Decimal` × per-million rate with **context-tier pricing**
(>200k premium). Caching defaults ON: `"auto"` places 3 cache breakpoints (last tool def, last system,
latest user msg).

➜ **Borrow:** the provider-specific cache-token metadata fallbacks (a real correctness gap in our
`RunCostTracker`) + context-tier pricing.

## 5. Client / server architecture ➜ DECISIVE for our engine extraction

- **`oss-agent serve`** (`cli/cmd/serve.ts` → `server/server.ts`): headless HTTP server on Effect
  **HttpApi + OpenAPI**, `127.0.0.1:4096`, **mDNS discovery**. Protocol = **HTTP + SSE** (`GET /event`)
  **+ WebSocket** (PTY / remote-workspace proxy).
- **SDK auto-generated from OpenAPI** (`sdk/js/src/gen/`): clients never drift.
  `createOssAgentClient()`; `createOssAgentServer()` spawns `oss-agent serve` as a subprocess.
- **Dual-mode, one SDK:** `oss-agent run` passes a custom `fetch` calling `Server.Default().app.fetch`
  directly (in-process, no socket); `--attach http://host:4096` uses real HTTP. Client is identical.
- **Multi-tenancy:** `x-oss-agent-directory` header / `?directory=` → one server, many project dirs.
- **Auth:** HTTP Basic (`OSS_AGENT_SERVER_PASSWORD`) or base64 `?auth_token=` query param (for SSE/WS
  where headers are awkward).

➜ **Borrow (high):** generate our SDK from the Spring OpenAPI; one client in-process-or-remote via
swappable transport; `?auth_token=` for our scoped-SSE / `es_pat_`.

## 6. CLI / headless ➜ our CLI

`oss-agent run [msg]` (`cli/cmd/run.ts`): non-interactive default, `--interactive [--attach]`.
`--format json` = **NDJSON events** (`text`/`tool_use`/`step_*`/`reasoning`/`error`) for CI;
`--format default` pretty-prints; non-TTY auto-plain. Subscribes to SSE, loops until
`session.status==="idle"`. `--continue`/`--session`/`--fork`/`--command`/`--agent`/`--model`/`--share`/
`--dangerously-skip-permissions`. Other commands: `serve`, `acp`, `mcp`, `github`, `export`/`import`,
`stats`, `agent`, `models`, `session`.

## 7. VS Code & editor integration ➜ contrast with our native plan

`sdks/vscode/` is **one ~137-line file** — does NOT render UI/diffs/approvals; it opens an integrated
terminal running `oss-agent` (the TUI), polls `GET /app`, and `POST /tui/append-prompt` to inject text
(incl. `@relativePath#L10-20` from the selection). **The TUI is the UI.** Rich editor integration is
delegated to **ACP** (`cli/cmd/acp.ts`, Agent Client Protocol over ndjson — for Zed/JetBrains).

➜ Our native webview (TreeView/plan-gate/diffs) is a deliberately richer bet; consider ACP for
multi-editor parity. Claude Code's **blocking interactive diff** is the better apply-UX to borrow.

## 8. Plugin system ➜ strong (broader than PreToolUse)

A plugin = `(input) => Promise<Hooks>` (`packages/plugin/src/index.ts`); `input` hands the SDK
`client`, project/dir, `$` (Bun shell), `experimental_workspace.register`. Declared in config
(`plugin: ["npm-pkg" | ["./local.ts", opts]]`). **Hooks:** add **tools** (`tool:{name:{args,execute}}`),
**providers/auth** (`provider`, `auth`), and lifecycle — **`permission.ask`** (PreToolUse-equivalent:
mutate ask/deny/allow), **`tool.execute.before/after`**, **`tool.definition`** (rewrite desc/params),
`chat.message`/`chat.params`/`chat.headers`, `command.execute.before`, `shell.env`, experimental
`chat.messages.transform`/`system.transform`/`session.compacting`. Hooks run sequentially; all plugins
also receive every bus event.

➜ **Borrow:** the typed hook-bus (our planned #9) — add tools/providers, mutate LLM params,
intercept permissions, all as deterministic hooks.

## 9. Enterprise ➜ our enterprise/self-host story

- `packages/enterprise/` (SolidStart): **session sharing** (`core/share.ts` — create/sync/get share
  snapshots; backs `oss-agent run --share`).
- `packages/console/` (SST/AWS): hosted teams/accounts control plane; `packages/identity/` = auth.
- **`control-plane/` workspace adapters**: `WorkspaceAdapter` resolves a workspace to a `Target =
  {local, directory} | {remote, url, headers}`; `workspace-routing` middleware **transparently
  proxies** HTTP/WS to the remote target (with a sync "fence"). = **local-hands/remote-brain
  generalized + pluggable** (`experimental_workspace.register`).
- Surrounding: `packages/app` (SolidJS web), `packages/web` (Astro docs), `packages/desktop`
  (Electron), `packages/slack` (bot), `github/` (GitHub Action).

➜ **Borrow:** workspace-adapter abstraction (`local|remote{url}` + proxy) as the shape for our
local-hands/remote-brain split.

## 10. Net borrow list (see COMPARISON.md §9 for the consolidated plan)

models.dev catalog · catalog.transform/DynamicProvider · `evaluate()` permission ruleset ·
tree-sitter+arity shell gating · cache-token metadata fallbacks · OpenAPI-generated SDK ·
in-process-or-remote swappable transport · workspace adapters · `--format json` NDJSON · plugin
hook-bus · per-model prompts · fuzzy edit cascade + LSP-diagnostics-in-result · compaction-as-a-task ·
doom-loop detector.
