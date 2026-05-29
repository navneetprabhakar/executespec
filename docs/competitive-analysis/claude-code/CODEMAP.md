# Codemap — Claude Code (deobfuscated)

> Directory-by-directory navigation map. ~1,900 TS/TSX files. Several files are giant
> deobfuscated bundles (`main.tsx` 800 KB, `screens/REPL.tsx` 895 KB) — treat those as
> "the whole feature lives here," not hand-authored modules. Counts are TS/TSX file counts.

## Mental model

```
                       ┌─────────────────────────────────────────────┐
   entrypoints/ ──────►│  main.tsx  (commander arg parse + dispatch)  │
   bootstrap/          └───────────────┬───────────────┬─────────────┘
                                       │ interactive   │ -p / non-TTY
                                       ▼               ▼
                            replLauncher → screens/    cli/print.ts (headless)
                            REPL.tsx (Ink UI)                 │
                                       └───────┬───────────────┘
                                               ▼
                                        QueryEngine.ts  ── wraps ──►  query.ts  (the agent loop)
                                               │                         │ while(true): callModel → tools → compact
                       ┌───────────────────────┼─────────────────────────┼───────────────────────┐
                       ▼                        ▼                         ▼                       ▼
                  constants/prompts.ts     Tool.ts + tools/        services/* (api, mcp,     context.ts + memdir/
                  (system prompt)          (the tool set)          compact, lsp, memory)     (context + memory)
```

## Root-level files (the brain + glue)

| File | Size | What it is |
|------|------|-----------|
| `query.ts` | 68 KB | **The agent loop.** `query()`→`queryLoop()` async generator: `while(true)` stream model → run tools → compact → until terminal. Token budget, interruptions, hooks woven in. |
| `QueryEngine.ts` | 46 KB | **Production wrapper** around `query`. Assembles system prompt + memory, accumulates usage/cost, file-history snapshots, permission gating, MCP/plugins/skills, session persistence, thinking. |
| `Tool.ts` | 29 KB | **Tool base contract.** `validateInput → checkPermissions → call → mapToolResultToToolResultBlockParam`; flags `isConcurrencySafe/isReadOnly/isDestructive/maxResultSizeChars/shouldDefer`; `toAutoClassifierInput` security projection. |
| `Task.ts` | 3 KB | **Task model.** Types `local_bash | local_agent | remote_agent | in_process_teammate | local_workflow | monitor_mcp | dream`; disk-streamed output; central `AppState`. |
| `commands.ts` | 25 KB | Slash-command registry (`Command` = `prompt`/`local`/`local-jsx`); merges builtin + skills + plugins + MCP. |
| `context.ts` | 6 KB | Per-conversation **context assembly** (memoized): `getSystemContext` (git status) + `getUserContext` (CLAUDE.md + date). |
| `cost-tracker.ts` | 10 KB | Per-model cache-aware cost/usage accounting; persist + restore per session. |
| `history.ts` | 14 KB | `~/.claude/history.jsonl` prompt history (append-only, lock-guarded, paste-store for big pastes). |
| `main.tsx` | **800 KB** | CLI entry: commander parser, full flag/subcommand surface, interactive-vs-print decision. |
| `interactiveHelpers.tsx` | 57 KB | REPL helper logic. |
| `dialogLaunchers.tsx` / `replLauncher.tsx` | 23 / 4 KB | Launch modal dialogs / mount the REPL. |
| `ink.ts` | 4 KB | Entry to the custom Ink renderer. |

## Directories

### Core agent + tools
| Dir | Files | Purpose |
|-----|-------|---------|
| `tools/` | 184 | **Every tool.** Subdirs per tool: `BashTool`, `FileEditTool`, `FileWriteTool`, `FileReadTool`, `GrepTool`, `GlobTool`, `AgentTool` (sub-agents + `runAgent.ts`), `TodoWriteTool`, `SkillTool`, `ToolSearchTool`, `WebFetch/WebSearch`, `NotebookEditTool`, `LSPTool`, MCP tools, `SleepTool`, `SyntheticOutputTool`, `PowerShellTool`, cron/schedule/team tools. |
| `query/` | 4 | Loop support: `transitions.ts` (Terminal/Continue), `tokenBudget.ts`, `config.ts`, `deps.ts`. |
| `tasks/` | 12 | Task implementations (bash/agent/teammate/workflow/remote/monitor/dream) + lifecycle. |
| `coordinator/` | 1 | `coordinatorMode.ts` — multi-agent coordinator (feature-flagged). |
| `assistant/` | 1 | Long-lived "assistant"/KAIROS persona glue. |

### Prompts + context + memory
| Dir | Files | Purpose |
|-----|-------|---------|
| `constants/` | 21 | **`prompts.ts`** (system prompt assembly + the coding-discipline prose), `systemPromptSections.ts` (memoized section registry + cache boundary), `xml.ts` (tags), model ids, limits. |
| `context/` | 9 | Context-window analysis (`/context` grid), context helpers. |
| `memdir/` | 8 | **Auto-managed typed memory** (`memoryTypes.ts` = user/feedback/project/reference taxonomy; `MEMORY.md` index; `findRelevantMemories.ts` LLM relevance; `paths.ts` security). |
| `state/` | 6 | `AppState` (central reducer state) + selectors. |
| `migrations/` | 11 | Config/settings/session-format migrations across versions. |

### CLI + terminal UI
| Dir | Files | Purpose |
|-----|-------|---------|
| `components/` | 389 | **All Ink UI.** `Messages`/`VirtualMessageList` (transcript), `PromptInput/` (input + typeahead), `Settings/Config.tsx`, `Stats.tsx`, `Spinner.tsx`, `mcp/`, `FullscreenLayout`, scroll/keybinding handlers. |
| `ink/` | 96 | **Custom Ink fork** — react-dom-like reconciler over Yoga layout → ANSI output (not npm `ink`). Keypress/mouse/kitty-protocol parsing, `ScrollBox`, `AlternateScreen`. |
| `screens/` | 3 | `REPL.tsx` (895 KB — the whole interactive app), `ResumeConversation.tsx`. |
| `cli/` | 19 | `print.ts` (headless runner — same `query` loop, text/json/stream-json output), `structuredIO.ts` (NDJSON). |
| `entrypoints/` | 8 | `cli.tsx` (lazy fast-path bootstrap), `sdk/` (Agent SDK types + transport — `agentSdkTypes.ts`, `coreTypes.ts` w/ HOOK_EVENTS). |
| `bootstrap/` | 1 | `state.js` — session id, global counters, token-budget state, cached CLAUDE.md. |
| `keybindings/` | 14 | Config-driven keybindings: named contexts/actions, chords, reserved keys, user overrides. |
| `vim/` | 5 | Vim-mode state machine (operators/motions/text-objects/transitions). |
| `hooks/` | 104 | Ink/React UI hooks (`useTypeahead`, `useDiffInIDE`, `useIdeSelection`, `useCanUseTool` = permission gate, `useIDEIntegration`). NB: *UI* hooks — the user-configurable *lifecycle* hooks live in `utils/hooks/` + `schemas/hooks.ts`. |
| `outputStyles/` | 1 | Output-style presets. |
| `voice/` | 1 | Voice/STT entry. |
| `moreright/` | 1 | Misc UI. |

### Integrations + extensibility
| Dir | Files | Purpose |
|-----|-------|---------|
| `bridge/` | 31 | **Web/mobile → local-CLI bridge** (the *inverse* of remote): local CLI registers a session with the backend so claude.ai web/mobile can drive it. `replBridge.ts`, `HybridTransport`/`SSETransport`. |
| `remote/` | 4 | **Remote-brain** controller: CLI is a thin client of a cloud "CCR" session (`RemoteSessionManager`, `SessionsWebSocket`); `viewerOnly` follower mode. |
| `server/` | 3 | **Direct-connect** WS session manager (`DirectConnectSessionManager`, stream-json wire). |
| `upstreamproxy/` | 2 | Container-side MITM CONNECT→WS credential-injection proxy for sandboxed agents. |
| `plugins/` | 2 | Plugin loader + builtin plugins (manifest `plugin.json`; git marketplaces; `name@marketplace` ids). |
| `skills/` | 20 | **Agent Skills** (`loadSkillsDir.ts`; `SKILL.md` + frontmatter; `bundled/` skills). |
| `native-ts/` | 4 | Native/platform bindings. |

### Services (the engine room — `services/`, 130 files)
| Subdir | Purpose |
|--------|---------|
| `api/` | Anthropic SDK client (`claude.ts` streaming/non-streaming, prompt-caching control, effort/budget, retry, usage, errors). |
| `mcp/` | Full MCP stack: `client.ts`, `config.ts`, `types.ts`, `SdkControlTransport.ts` (out-of-process tool routing = our RemoteToolDispatcher), OAuth, elicitation, registry. |
| `compact/` | Context management: `autoCompact.ts` (token-buffer thresholds), `microCompact` (drop stale tool results), `snipCompact`, `reactiveCompact`, `compact.ts`. |
| `tools/` | `StreamingToolExecutor`, `toolOrchestration`, `toolHooks` (hook gating), `toolExecution`. |
| `lsp/` | Language Server Protocol client + diagnostics registry. |
| `extractMemories/`, `SessionMemory/`, `autoDream/` | Background memory-curation sub-agents (write durable memories, per-session notes, nightly consolidation). |
| `analytics/` | Telemetry + Datadog + **GrowthBook** feature flags (`growthbook.ts`). |
| `oauth/` | OAuth login listener + crypto. |
| `plugins/`, `teamMemorySync/`, `settingsSync/`, `remoteManagedSettings/`, `policyLimits/` | Plugin ops + enterprise policy push + shared-memory sync (with secret-scanner). |
| (others) | `tokenEstimation`, `toolUseSummary`, `diagnosticTracking`, `notifier`, `voice*`, `vcr` (record/replay), `claudeAiLimits`. |

### Supporting
| Dir | Files | Purpose |
|-----|-------|---------|
| `utils/` | 564 | Everything else: `Shell.ts` (bash exec + cwd persistence), `fileHistory.ts` (undo snapshots), `fileStateCache.ts` (read-before-write ledger), `claudemd.ts` (CLAUDE.md loader + @import), `hooks/` (**user-configurable lifecycle hooks**), `messages*.ts`, `sessionStorage.ts`, `model/`, `permissions/`, `bash/bashParser.ts`, `processUserInput/`. |
| `commands/` | 189 | One dir per slash command (`memory/`, `resume/`, `plugin/`, `mcp/`, `config/`, `commit/`, `review/`, …). |
| `types/` | 11 | `message.ts`, `hooks.ts`, `plugin.ts`, `ids.ts`, `textInputTypes.ts`. |
| `schemas/` | 1 | `hooks.ts` — the user-hook config Zod schema (4 hook kinds). |
| `buddy/` | 6 | "Buddy"/companion feature. |
| `keybindings/`, `migrations/` | (above) | |

## Where to look for X

| You want… | Go to |
|-----------|-------|
| The agent loop | `query.ts`, `QueryEngine.ts` |
| The system prompt / coding instructions | `constants/prompts.ts` (`getSystemPrompt`, `getSimpleDoingTasksSection`, `getActionsSection`) |
| How a tool works / the tool contract | `Tool.ts` + `tools/<Name>Tool/` |
| File editing semantics | `tools/FileEditTool/{FileEditTool.ts,utils.ts}` |
| Bash exec + sandbox | `tools/BashTool/`, `utils/Shell.ts` |
| Sub-agents | `tools/AgentTool/{AgentTool.tsx,runAgent.ts}` |
| Memory (CLAUDE.md) | `utils/claudemd.ts`, `context.ts` |
| Memory (auto/typed) | `memdir/` |
| Compaction | `services/compact/` |
| User-configurable hooks | `schemas/hooks.ts`, `utils/hooks/`, `services/tools/toolHooks.ts` |
| IDE/VS Code bridge | `utils/ide.ts`, `hooks/useIDEIntegration.tsx`, `hooks/useDiffInIDE.ts`, `services/diagnosticTracking.ts` |
| MCP | `services/mcp/` |
| Skills | `skills/`, `tools/SkillTool/` |
| CLI entry / headless | `main.tsx`, `entrypoints/cli.tsx`, `cli/print.ts` |
| Interactive UI | `screens/REPL.tsx`, `components/`, `ink/` |
| Cost | `cost-tracker.ts` |
| Remote/proxy execution | `remote/`, `server/`, `bridge/`, `upstreamproxy/`, `services/mcp/SdkControlTransport.ts` |
