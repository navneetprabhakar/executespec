# How opencode executes a coding task

> Analysis of the open-source **opencode** (anomalyco/opencode), a TypeScript/Bun monorepo. Purpose:
> understand its design + mine it for our code-gen engine, alongside the Claude Code analysis. File
> paths cite this repo. (opencode is open-source — summary/analysis.)

> **Important layering note:** `packages/core/src/` is the newer **v2 event-sourced schema layer**
> (pure Effect-`Schema` data + an immer reducer over a `session.next.*` event union, dual-written
> behind `flags.experimentalEventSystem`). The **live runtime** is in **`packages/opencode/src/`**
> (`session/`, `tool/`, `agent/`). Everything below is the runtime.

---

## 0. TL;DR

opencode is a **single streaming agentic loop** built on the **Vercel AI SDK** (`streamText`) — much
closer to Claude Code than to our classifier→planner→worker→reviewer pipeline. **No planner, no
reviewer.** One model, one tool-loop, driven to completion. Its quality comes from four places we can
borrow:

1. **Rich tool feedback** — a fuzzy edit-cascade + LSP diagnostics appended to the tool result.
2. **A doom-loop detector** (3 identical tool calls → intervene).
3. **Compaction as a first-class loop task** (summarize + continue, never just die at the cap).
4. **A permission ruleset** as the single chokepoint, where **"an agent" = a permission profile +
   prompt + model over one shared toolset** (so "plan mode" = deny-all-edits, trivially).

---

## 1. The agent loop — two nested loops over the AI SDK

- **Inner turn** (`session/processor.ts` → `session/llm.ts`): one `llm.stream(...)` = one
  `streamText` call. **The AI SDK runs the intra-turn tool loop itself** (executes each tool's
  `execute` and feeds results back), emitting a `fullStream`. `LLMAISDK.toLLMEvents` normalizes it
  into an Effect `Stream<LLMEvent>`; `processor.handleEvent` switches on `text-*`, `reasoning-*`,
  `tool-input-*`, `tool-call`, `tool-result`, `step-*` and persists each as a message **Part**
  (streaming deltas on a bus). `process` returns `"compact" | "stop" | "continue"`.
- **Outer step loop** (`session/prompt.ts` `runLoop`, ~L1244): `while(true)` that each step
  re-derives the latest messages, **re-resolves tools**, **re-assembles system + env + instructions**,
  and calls `process`. Continue-vs-stop is by finish reason: keep looping while tool calls are
  unanswered (`hasToolCalls`; explicit workaround for providers that send `stop` with pending tools),
  break on a non-`tool-calls` finish with no orphan tools. `step >= maxSteps` (`agent.steps ??
  Infinity`) injects a `MAX_STEPS` message. **`DOOM_LOOP_THRESHOLD = 3`** (processor.ts ~L425): 3
  identical consecutive tool calls → force a permission prompt.

So the AI SDK owns the *tool* loop; opencode's outer loop exists for **compaction, sub-tasks,
reminders, and per-step prompt re-assembly**. (An experimental hand-rolled `LLMNativeRuntime` exists
behind `flags.experimentalNativeLlm`, but AI SDK is default.)

> **Contrast:** Claude Code hand-rolls its `while(true)` over a custom provider abstraction; opencode
> delegates the inner loop to the AI SDK. Ours is a fixed multi-agent pipeline. Both of them are
> single-loop "one strong model + tools."

## 2. Tools (`tool/registry.ts`, `tool/tool.ts`)

**Built-ins:** `shell` (bash), `read`, `glob`, `grep`, `edit`, `write`, `apply_patch`, `task`
(spawn sub-agent), `todowrite`, `webfetch`, `websearch`, `skill`, `question`, `invalid` (a repair
sink for malformed calls), + flag-gated `lsp`, `plan_enter`/`plan_exit`, `repo_clone`/`repo_overview`.
Notably **`apply_patch` (OpenAI envelope) vs `edit`+`write` are mutually exclusive per model** —
GPT-5-class get `apply_patch`, everyone else gets `edit`+`write` (registry.ts ~L322).

**Contract** (`tool/tool.ts`): `Tool.define(id, init)` → `{description, parameters: Schema,
execute(args, ctx) → {title, metadata, output, attachments?}}`. `Tool.wrap` decodes args against the
schema (failure → model-facing `InvalidArgumentsError` = "rewrite the input") and **auto-truncates
output** (spills overflow to a file). Descriptions live in sibling `.txt` files.

**Edit tool** (`tool/edit.ts`) — **string-replace with a fuzzy cascade** (ported from cline +
gemini-cli): `SimpleReplacer` → `LineTrimmedReplacer` (ignores whitespace) → `BlockAnchorReplacer`
(matches first/last line of a ≥3-line block, scored by **Levenshtein** similarity). It does **NOT**
enforce read-before-write; it reads fresh, takes a **per-file semaphore lock**, asks `edit`
permission with a computed diff, runs the configured **formatter**, and **appends LSP diagnostics to
the tool output** (*"LSP errors detected, please fix"*). Preserves BOM + CRLF. `oldString===""` =
create/overwrite.

**Bash tool** (`tool/shell.ts`) — **NOT sandboxed** (host `ChildProcessSpawner`). Safety = **static
analysis via tree-sitter** (bash + PowerShell grammars): parses the command into sub-commands +
filesystem-touching paths and asks `bash`/`external_directory` permission **per extracted pattern**.
Default **2-min timeout** (race timeout vs abort → `kill` + `forceKillAfter 3s`). Output truncated
(~30 KB metadata cap), streamed.

## 3. Prompt / context assembly (`session/prompt.ts`, `session/system.ts`, `session/instruction.ts`)

Re-assembled **each step**: `system = [...environment, ...instructions, ...skills]`.
- **Base prompt is per-MODEL** (`system.ts` `provider()`): `anthropic.txt` / `gpt.txt` / `gemini.txt`
  / `codex.txt` / `beast.txt` / `kimi.txt` / `default.txt` chosen by model id. (We use one base prompt
  for all providers — opencode tunes per model family.)
- **Environment:** cwd, worktree root, is-git, platform, date.
- **Instructions** (`instruction.ts`): walks up from cwd for the first `AGENTS.md`/`CLAUDE.md`/
  `CONTEXT.md`, + global `~/.config/opencode/AGENTS.md` + `~/.claude/CLAUDE.md` + config globs +
  **remote URLs** (5 s fetch). **Lazy directory-local rules:** when `read` loads a file, it attaches
  any **nearby AGENTS.md in that subtree** (once per message).
- **Reminders** (`reminders.ts`): `<system-reminder>` blocks; a mid-loop new user message is wrapped
  "the user sent the following message… continue with your tasks."
- **Caching:** no explicit `cache_control` here — delegated to a provider transform middleware
  (`ProviderTransform.message`); the structural win is the stable env/instructions prefix +
  deterministic tool re-resolution.

## 4. Session model

**SQLite via Drizzle** (`session/session.sql.ts`: `SessionTable`, `PartTable`). A message = a list of
**parts** (text / reasoning / tool / step-start / step-finish / patch / file). Resume is implicit
(state IS the DB; `prompt.ts` `ensureRunning` resumes the loop). **Fork** clones messages+parts up to
a message id. **Sub-sessions** carry `parentID` (spawned by the `task` tool). **Compaction**
(`session/compaction.ts`): on token `isOverflow` (`overflow.ts`), enqueue a `compaction` task → a
hidden no-tools `compaction` agent summarizes history → writes a summary part + `tail_start_id`;
`filterCompactedEffect` then feeds the model summary + tail instead of full history.

## 5. Agents / modes (`agent/agent.ts`)

An "agent" = `{mode: primary|subagent|all, permission ruleset, model?, prompt?, temperature?, steps?,
hidden?}`. Built-ins:
- **build** (default primary): `permission "*": allow` — full toolset.
- **plan** (primary): build minus `edit: "*": deny` (except `.opencode/plans/*.md`) + `plan_exit`.
  **`plan_exit` → asks "switch to build and implement?" → injects a synthetic user message switching
  `agent: build`** — plan→build is an *in-session agent switch*, not a new run.
- **general** (subagent): full perms minus `todowrite` — the parallel worker spawned by `task`.
- **explore / scout** (subagents): read-only.
- **compaction / title / summary** (hidden, no tools): internal LLM utilities.

So an agent changes **prompt + model + permission-gated toolset + step cap + temperature** — all via
config/permissions, **not separate code paths**. Users define custom agents in config.

## 6. Borrow for our harness (prioritized)

| # | Borrow | Into | Why |
|---|--------|------|-----|
| 1 | **Fuzzy edit cascade (Simple→LineTrimmed→BlockAnchor/Levenshtein) + LSP diagnostics in the tool result** | `EditFileTool` | Whitespace/indent drift stops failing edits; diagnostics give an in-loop fix signal cheaper than our reviewer's fix-build pass |
| 2 | **tree-sitter command analysis for shell permissioning** (sub-commands + touched paths) | `ShellSandboxPolicy` / abuse scanner | More precise than regex/denylist; surfaces *which paths* a command writes |
| 3 | **Compaction as a loop task** (overflow → summarize → `tail_start_id` + tail) | `WorkerAgent` (= our #7) | Long LARGE/LEGACY tasks survive context limits instead of `ITERATION_LIMIT_EXCEEDED` |
| 4 | **Doom-loop detector** (3 identical tool calls → intervene) | `WorkerAgent` progress signal | Targeted catch for "retries the same failing edit"; complements our `itersSinceProgress` |
| 5 | **Permission ruleset (`allow/deny/ask`, last-match-wins, "always" persistence) as the single side-effect chokepoint + agent = a permission profile** | tool authority + plan gate | Express plan-gate / BYOK / guardrail-level as *data*; "plan mode" = deny-all-edits trivially |
| 6 | **Per-model system prompts** (`anthropic.txt`/`gpt.txt`/…) | worker/planner prompt assembly | We use one prompt for 3 providers; per-family tuning is a cheap quality lever |
| 7 | **Lazy directory-local rules** (attach nearby AGENTS.md when a file is read) | context assembly | On-demand local conventions without bloating the base prompt |

See `_ANALYSIS/README.md` (architecture overview), `FEATURES.md` (capability surface incl. providers
+ clients), `CODEMAP.md` (navigation), and the cross-tool `../sourcecode/_ANALYSIS/` Claude Code docs
+ the 3-way comparison.
