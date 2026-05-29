# Feature catalog — Claude Code (deobfuscated)

> The full capability surface of a mature agentic-coding CLI. Each section flags **➜ ExecuteSpec
> relevance** (our pipeline) and/or **➜ Client relevance** (our CLI / VS Code extension). Sourced
> from line-level survey of this repo.

---

## 1. Agentic core

| Feature | Detail | Relevance |
|---|---|---|
| **Single streaming loop** | `query.ts` async generator: `while(true)` stream model → run tools → compact → terminal. No classifier/planner/reviewer split. | ➜ ours is a fixed pipeline; theirs is adaptive |
| **Streaming tool execution** | `StreamingToolExecutor` + `runTools`; concurrency-safe tools run in parallel, unsafe serialize. | ➜ we run tools serially per worker turn |
| **Per-turn token budget + "token target" mode** | `query/tokenBudget.ts`; user can say "spend 2M tokens" and the loop auto-continues to a floor. | ➜ |
| **Extended thinking** | `ThinkingConfig`, default-on heuristics, thinking budget. | ➜ |
| **Interruptions** | New user message mid-run → `UserInterruptionMessage`; per-tool `interruptBehavior()` (`cancel`/`block`). | ➜ Client |
| **Sub-agents** | `AgentTool` spawns isolated agent: own prompt + tool pool + context; final message collapses to one `tool_result`. Fresh vs **fork** (inherits parent prompt+history → cache reuse). Optional git-worktree isolation. Async/background w/ task-notification. | ➜ strong |
| **Plan mode** | Read-only until model submits a plan + user approves (`EnterPlanMode`/`ExitPlanMode`). | ➜ our plan gate analog |
| **TodoWrite** | Live self-maintained checklist; "verify" nudge at ≥3 closed. | ➜ |
| **Multi-agent teams** | `in_process_teammate` tasks, `SendMessage`/`TeamCreate`/`TeamDelete`, `monitor_mcp`, `dream` (background) tasks. | — |

## 2. Tools (the action surface)

**Contract (`Tool.ts`):** `validateInput → checkPermissions(allow/ask/deny) → call → mapToolResultToToolResultBlockParam`. Flags: `isConcurrencySafe`, `isReadOnly`, `isDestructive`, `interruptBehavior`, `maxResultSizeChars` (over budget → persist-to-disk + preview), `shouldDefer` (lazy schema load via ToolSearch), `toAutoClassifierInput` (security projection for auto-approval).

| Tool | Purpose | Notable |
|---|---|---|
| **Bash** | shell | fresh process/call + **persistent cwd**; bubblewrap sandbox; AST security analysis + read-only allowlists + classifier; 30 KB output budget → disk; `interpretCommandResult` (grep exit 1 = "no matches") |
| **FileRead** | read file/image/pdf/notebook | blocks `/dev`,`/proc`; token cap; records into read-ledger |
| **FileEdit (Edit)** | exact string replace | **read-before-edit + staleness + uniqueness checks**; quote/whitespace normalization |
| **FileWrite (Write)** | whole-file write | read-before-overwrite; secret guard |
| **MultiEdit** | sequential edit array | rejects `old_string` ⊂ prior `new_string` |
| **Grep / Glob** | ripgrep / filename search | read-only, concurrency-safe, paginated (`head_limit`/`offset`) |
| **Agent (Task)** | sub-agent | §1 |
| **TodoWrite** | checklist | §1 |
| **Skill** | invoke a Skill | inline or forked sub-agent |
| **ToolSearch** | fetch deferred tool schemas | enables lazy tool loading |
| **WebFetch / WebSearch** | URL / web | deferred; domain filters |
| **AskUserQuestion** | multiple-choice prompt | 1–4 questions, multiselect |
| **NotebookEdit** | `.ipynb` cell edit | Edit redirects `.ipynb` here |
| **LSP** | code intel (defs, diagnostics) | read-only |
| **MCP tools** | external server tools | `mcp__<server>__<tool>` namespacing |
| **Task{Create,Update,Get,List,Stop,Output}** | persistent task graph | deferred, concurrency-safe |
| **Cron / Schedule / RemoteTrigger / Sleep / SyntheticOutput / Config / PowerShell / REPL** | scheduling, structured output, Windows shell, config | — |

➜ **ExecuteSpec**: our tool set is `readFile/writeFile/editFile/listFiles/searchFiles/shell/gitStatus/gitDiff/buildProject/runTests/completeTask`. The gaps worth closing: read-before-write enforcement, output budgeting→disk, per-tool permission classification, LSP diagnostics.

## 3. Context & memory

| Feature | Detail | Relevance |
|---|---|---|
| **Context assembly (memoized)** | git status (branch/main/status/log, 2 KB cap, "snapshot not live") + CLAUDE.md + date; cached per conversation. | ➜ |
| **CLAUDE.md instruction files** | 4 scopes: enterprise `/etc/claude-code` > user `~/.claude` > project (`CLAUDE.md`, `.claude/rules/*.md`) > local (`CLAUDE.local.md`). `@import` directive (depth 5), 40 KB/file cap, OVERRIDE-default header. | ➜ matches our CLAUDE.md model |
| **Auto-managed typed memory (`memdir/`)** | File-per-memory, **4-type taxonomy** (user/feedback/project/reference), explicit "don't save what's derivable from code/git" rule, `MEMORY.md` index (only index auto-loaded), LLM relevance-selection (Sonnet picks ≤5 files). Path-security hardened. | ➜ **strong** — sharpen our `ProjectMemoryProvider` |
| **Background memory curation** | `extractMemories` (forked sub-agent at loop end), `SessionMemory` (per-conversation notes), `autoDream` (nightly consolidation). | ➜ |
| **Compaction (multi-strategy)** | `autoCompact` (compact at ctx−13k, warn at −20k), `microCompact` (drop *stale tool results*), `snipCompact`, `reactiveCompact`, `contextCollapse`, microcompact boundaries. | ➜ we have none — worker just caps iters |
| **Context visualizer** | `/context` renders a colored grid of context-window usage. | ➜ Client |
| **Tool-result summarization** | `generateToolUseSummary` collapses verbose tool calls to save context. | ➜ |

## 4. Sessions, history, cost

| Feature | Detail | Relevance |
|---|---|---|
| **Transcripts** | append-only JSONL `~/.claude/projects/<cwd-hash>/<sessionId>.jsonl`; sub-agents get `agent-<id>.jsonl`. | ➜ Client |
| **Prompt history** | `~/.claude/history.jsonl` (0600, lock-guarded, paste-store for >1 KB pastes); ctrl+r search. | Client |
| **Resume / continue / fork** | `-c`/`--continue` (most recent), `-r`/`--resume [id]` (picker), `--fork-session`; `/rewind`, `/branch`, `/compact`, `/clear`. | ➜ Client |
| **Cost tracking** | per-model cache-aware (`cacheReadInputTokens`/`cacheCreationInputTokens`), nested/advisor usage rolls up, GrowthBook-tagged counters, **persisted + restored on resume**. `/cost` breakdown. | ➜ matches our `RunCostTracker`; we lack persist/restore on resume |

## 5. User-configurable hooks (a differentiator we lack)

| Feature | Detail | Relevance |
|---|---|---|
| **27 lifecycle events** | `PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `UserPromptSubmit`, `SessionStart/End`, `Stop`, `SubagentStart/Stop`, `PreCompact/PostCompact`, `PermissionRequest/Denied`, `TaskCreated/Completed`, `WorktreeCreate/Remove`, `FileChanged`, `CwdChanged`, … | ➜ **strong** |
| **4 hook kinds** | `command` (shell, with `async`/`asyncRewake`), `prompt` (LLM eval), `agent` (sub-agent verifier — *our ReviewerAgent-as-a-gate*), `http` (POST to URL). | ➜ |
| **Gate + mutate tool calls** | `PreToolUse` returns `permissionDecision: allow/deny/ask` + **`updatedInput`** (rewrite args). `PostToolUse` injects `additionalContext`. `UserPromptSubmit`/`SessionStart` inject context. | ➜ deterministic policy enforcement |
| **`if:` pre-filter + matchers** | permission-rule syntax (`Bash(git *)`) filters before spawning; name/regex matchers. Trust-gated. | ➜ |

## 6. Extensibility

| Feature | Detail | Relevance |
|---|---|---|
| **Skills** | `skill-name/SKILL.md` + YAML frontmatter (`description`/`when_to_use`/`allowed-tools`/`context:fork`/`model`/`effort`). Advertised in a ~1%-budget system-reminder; body loads lazily on `Skill` invoke. Inline vs forked-sub-agent. Safe-property auto-approval. From user/project/managed/bundled/plugin/MCP. | ➜ + Client |
| **Plugins** | dir + `plugin.json`; contributes commands/agents/skills/hooks/output-styles/MCP servers/LSP/settings/channels. `userConfig` typed prompts (sensitive→keychain). Git **marketplaces** (`.claude-plugin/marketplace.json`), `name@marketplace` ids, `.mcpb`/`.dxt` bundles, per-scope install state. | Client + ➜ connector store |
| **MCP** | scopes enterprise>local>user>project(+dynamic); transports stdio/sse/http/ws/sdk; `mcp__server__tool` namespacing; prompts→`/mcp__server__prompt`; resources→synthetic List/Read tools; two-layer approval (server trust dialog + per-tool ask) + enterprise allow/deny policy. | ➜ + Client (matches our MCP registry) |
| **Output styles** | configurable response styling; `keepCodingInstructions` toggle. | — |
| **Statusline / theme / vim / keybindings** | user-configurable. | Client |

## 7. IDE / editor integration

| Feature | Detail | Relevance |
|---|---|---|
| **Bridge = MCP** | The editor is an MCP server. Discovery via **lockfile** `~/.claude/ide/<port>.lock` (`{workspaceFolders, pid, transport, authToken}`); cwd-match + pid-ancestry + TCP-probe. Transport WS or SSE. | ➜ **Client (VS Code)** |
| **Interactive diff apply** | CLI calls `openDiff` and **blocks** until the user saves (re-diffs edited buffer) / closes / rejects (`FILE_SAVED`/`TAB_CLOSED`/`DIFF_REJECTED`). | ➜ Client — better than approve/reject modal |
| **Diagnostics baseline-diff** | snapshot `getDiagnostics` before edit; surface only **new** errors after → model self-corrects on compile errors. | ➜ + Client |
| **Selection / @-mention** | IDE → CLI notifications `selection_changed`, `at_mentioned` (file+lines). | Client |
| **Auto-install extension** | `code --install-extension anthropic.claude-code`. | Client |
| Model-exposed IDE tools | only `mcp__ide__executeCode` + `mcp__ide__getDiagnostics`; rest are internal RPC. | Client |

## 8. Remote / proxy execution (local-hands / remote-brain)

| Mode | Detail | Relevance |
|---|---|---|
| **Remote brain (`remote/`)** | CLI is a thin controller of a cloud "CCR" session; receives SDK messages + permission `control_request`, sends input via HTTP POST. `viewerOnly` follower. | ➜ **strong** (our local-hands/remote-brain) |
| **Direct connect (`server/`)** | WS to a server URL, stream-json wire, Bearer auth. | ➜ |
| **Web/mobile bridge (`bridge/`)** | inverse: local CLI registers a session so claude.ai web/mobile drives it; code stays local. | ➜ |
| **Out-of-process tools = MCP control transport** | `SdkControlTransport`: wrap tool call as `control_request{server_name, request_id}` over stdout/stdin, correlate by id. | ➜ **= our RemoteToolDispatcher** |
| **Upstream proxy** | container-side MITM CONNECT→WS credential injection for sandboxed agents. | — |

## 9. CLI / terminal UX

| Feature | Detail | Relevance |
|---|---|---|
| **Lazy fast-path bootstrap** | `--version`/`--help`/subcommands load with minimal imports; dead-code-eliminated by build-time `feature()` flags; startup profiler. | ➜ Client |
| **Auto-headless on non-TTY** | `!stdout.isTTY` → print mode without `-p`. | ➜ Client |
| **Output formats** | `text` / `json` / **`stream-json` (NDJSON)** — same loop, three serializers; pipeable to CI/jq; also the SDK transport. | ➜ Client |
| **Rich REPL (custom Ink)** | `FullscreenLayout` (scrollable transcript + pinned input + modal slot), two-level virtualization, live streaming markdown + tool calls, spinner with token counter + thinking timer. | Client |
| **Unified typeahead** | one engine, fans out by sigil `/` (commands) `@` (files) `#` (channels) + path completion + shell-history ghost text. | Client |
| **Config keybindings + vim** | named contexts/actions, chords, reserved keys, user overrides; full vim state machine. | Client |
| **Voice input** | STT streaming (`/voice`). | — |

## 10. Slash commands (the mature feature surface)

- **Session:** `/clear` `/compact` `/branch` `/rewind` `/resume` `/rename` `/tag` `/export` `/copy` `/summary` `/session` `/desktop` `/exit`
- **Context/memory:** `/context` `/memory` `/files` `/add-dir` `/thinkback`
- **Config/setup:** `/config` `/doctor` `/init` `/permissions` `/hooks` `/theme` `/color` `/vim` `/keybindings` `/statusline` `/effort` `/fast` `/terminal-setup` `/sandbox` `/privacy-settings` `/env`
- **Git/PR:** `/commit` `/commit-push-pr` `/diff` `/review` `/pr-comments` `/install-github-app` `/autofix-pr` `/security-review` `/bughunter`
- **MCP/plugins/skills:** `/mcp` `/plugin` `/reload-plugins` `/skills` `/agents` `/install-slack-app`
- **Model/cost/usage:** `/model` `/advisor` `/cost` `/usage` `/stats` `/extra-usage` `/upgrade` `/insights`
- **Auth:** `/login` `/logout`
- **Plan/tasks:** `/plan` `/tasks` `/ultraplan` `/passes`
- **Debug/internal:** `/heapdump` `/ant-trace` `/perf-issue` `/debug-tool-call` `/break-cache` `/version` `/feedback` `/release-notes` `/bridge` `/ide` `/chrome` `/teleport` `/remote-env`

➜ **For our CLI/VS Code roadmap**, the must-haves a mature coding agent ships that we don't yet: `/context` (usage viz), `/compact` + `/rewind` + `/branch`, `/review` + `/security-review` + `/pr-comments`, `/hooks`, `/skills`, `/init` (generate CLAUDE.md), `/doctor`.

---

See `CODING-TASKS.md` for the deep dive on the loop + the prioritized borrow list, and `CODEMAP.md`
to locate any of the above in the tree.
