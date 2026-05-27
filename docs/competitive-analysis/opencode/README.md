# opencode (anomalyco/opencode) — analysis

> Analysis of the open-source **opencode** agentic coding tool, written to mine its design for our
> harness/code-gen engine + our CLI/VS Code clients. Companion to the Claude Code analysis
> (`../claude-code/`) and the 3-way `../COMPARISON.md`.

## What it is

A TypeScript/Bun **monorepo** (`packages/*`) implementing an agentic coding tool as a **headless core
engine + many thin clients**. The agent is a **single streaming loop over the Vercel AI SDK**
(`streamText`) — no planner, no reviewer; quality comes from rich tool feedback, per-model prompts,
sub-agents, and a permission ruleset. Supports **~30 LLM providers** via the models.dev catalog.

## The defining architecture (most relevant to us)

**Core engine as an HTTP server; every client is an HTTP client.** `opencode serve` runs an Effect
HttpApi server (OpenAPI spec); the TUI, VS Code, web, desktop, and the auto-generated SDK all talk to
it over HTTP + SSE + WebSocket. The *same* SDK runs in-process (swappable `fetch`) or against a remote
server (`--attach`). **This is exactly the "core engine + thin clients" architecture we're planning —
opencode is working proof it holds up.**

## The doc set

| Doc | Read it for |
|-----|-------------|
| **README.md** (this) | What opencode is + the headline architecture |
| **CODING-TASKS.md** | ⭐ The agent loop end-to-end (two nested loops over the AI SDK), tools, edit cascade, permissions, agents/modes, compaction + borrow list |
| **FEATURES.md** | Providers/catalog (models.dev), permission ruleset, cost, client/server, CLI, plugins, enterprise + borrow list |
| **CODEMAP.md** | Monorepo package map + where-to-find-X |

## Top takeaways for ExecuteSpec

**Harness / code-gen:** fuzzy edit cascade + LSP-diagnostics-in-the-tool-result; doom-loop detector;
compaction-as-a-loop-task; permission ruleset (agent = a permission profile); per-model prompts;
lazy directory-local rules.

**Multi-provider (extend our lead):** models.dev catalog (vs our hand-maintained YAML);
`catalog.transform` + `DynamicProviderPlugin` (add providers without router edits); cache-token
metadata fallbacks (a correctness gap in our `RunCostTracker`); `small_model` = our lightweight tier.

**Clients / engine-as-service (validates the extraction):** generate the client SDK from our Spring
OpenAPI; one client in-process-or-remote via swappable transport; workspace adapters
(`local | remote{url}` + transparent proxy) = our local-hands/remote-brain generalized; `--format
json` NDJSON + `?auth_token=` SSE auth; the plugin hook-bus.

See `../COMPARISON.md` for the consolidated 3-way verdict + the prioritized adoption plan.

## Caveat

opencode's VS Code extension is intentionally *minimal* (hosts the TUI in a terminal); our native
webview bet is richer. For multi-editor reach, opencode uses **ACP** (Agent Client Protocol) rather
than per-editor UI — worth a look. Source: open-source (MIT) — use as a design reference.
