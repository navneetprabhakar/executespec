# How Claude Code executes a coding task

> Reverse-engineered from the deobfuscated source in this repo. Purpose: understand the
> competitor's coding-agent design and extract what we can adopt into (a) the ExecuteSpec
> pipeline and (b) our CLI / VS Code clients. File paths cite this repo.

---

## 0. TL;DR — the shape of it

Claude Code is **one strong model in a tight, streaming tool loop** — *not* a multi-stage
pipeline. There is no classifier, no separate planner agent, no reviewer agent. The model itself
decides the approach each turn: explore → localize → reproduce → edit → verify → stop. All of the
"intelligence" we split across `SmartClassifierAgent` / `OutlinePlannerAgent` / `WorkerAgent` /
`ReviewerAgent` lives instead in **one system prompt + one loop + a strong tool contract**.

The sophistication is NOT in orchestration; it is in four things we under-invest in:

1. **A tool contract with teeth** — read-before-write enforcement, staleness checks, per-tool
   output budgets, permission classification (`Tool.ts`).
2. **A coding system prompt that bakes in discipline** — minimal-diff, verify-before-done,
   read-before-modify, faithful reporting (`constants/prompts.ts`).
3. **Context engineering for the prompt cache** — static/dynamic boundary, memoized context,
   multi-strategy compaction (`constants/prompts.ts`, `context.ts`, `services/compact/*`).
4. **A permission / blast-radius model** that makes autonomy safe (`Tool.checkPermissions`).

---

## 1. The agentic loop (`query.ts` → `QueryEngine.ts`)

### 1.1 `query()` / `queryLoop()` — the engine (`query.ts`)

The core is an **async generator** (`export async function* query` → `queryLoop`, `query.ts:219/241`)
that **yields a stream of SDK messages** as it runs. The structure is a single `while (true)` loop
(`query.ts:307`):

```
while (true):
  yield { type: 'stream_request_start' }
  for await (message of deps.callModel({...})):     # stream model output
      yield message                                  # assistant text / thinking / tool_use blocks
  if assistant produced tool_use blocks:
      runTools(...) via StreamingToolExecutor         # execute tools, streaming results back
      yield each tool_result + attachment message
      # check interruptions, token budget, compaction → continue
  else:
      # no tool calls → terminal; break
```

Key properties (each is a lever we lack):

- **Streaming end-to-end.** Model output streams token-by-token; tool execution streams via
  `StreamingToolExecutor` (`services/tools/StreamingToolExecutor.js`) and `runTools`
  (`services/tools/toolOrchestration.js`). Concurrency-safe tools run in **parallel**; unsafe ones
  serialize (the `isConcurrencySafe` flag on each tool — see `Tool.ts`).
- **Per-turn token budget.** `createBudgetTracker` / `checkTokenBudget` (`query/tokenBudget.js`) +
  `getCurrentTurnTokenBudget` (`bootstrap/state.js`). There's even a "token target" mode where the
  user can say "spend 2M tokens" and the loop *auto-continues* itself to hit a floor
  (`constants/prompts.ts:548`).
- **Interruptions** are first-class: a new user message mid-tool-run creates a
  `createUserInterruptionMessage` (`query.ts:1047`); each tool declares `interruptBehavior()`
  (`'cancel' | 'block'`) so e.g. a long Bash can be cancelled but a Write is allowed to finish.
- **State-machine transitions** (`query/transitions.js`: `Terminal | Continue`) decide loop exit vs
  continue — cleaner than our `Outcome` enum sprawl.

### 1.2 `QueryEngine` — what wraps the loop (`QueryEngine.ts`)

`QueryEngine` is the production entry that prepares everything the loop needs, per turn:

- **System prompt assembly** — `fetchSystemPromptParts` → `getSystemPrompt` (see §3).
- **Memory** — `loadMemoryPrompt()` from `memdir/` (CLAUDE.md + memory files).
- **Usage / cost** — `accumulateUsage`, `getTotalCost`, `getModelUsage` (`cost-tracker.js`).
- **File-history snapshots** — `fileHistoryMakeSnapshot` (`utils/fileHistory.js`): a content
  snapshot before edits, enabling **undo / rewind** of file changes.
- **File-state cache** — `FileStateCache` (`utils/fileStateCache.js`): the read-before-write ledger.
- **Permission gating** — `CanUseToolFn` (`hooks/useCanUseTool.js`) threaded into every tool call.
- **Compaction** — `autoCompact`, `snipCompact`, `reactiveCompact`, `contextCollapse`,
  `microcompact` (all under `services/compact/*`).
- **Thinking** — `ThinkingConfig` / `shouldEnableThinkingByDefault` (extended thinking budget).
- **MCP, plugins, skills, session persistence (`recordTranscript`), coordinator mode.**

> **Contrast with us:** our `RunOrchestrator` is a *fixed* sequence (classifier→planner→
> executor→lint→reviewer). Claude Code's `QueryEngine` is a *single recursive loop* with all of
> these as cross-cutting services the loop consults. Neither is strictly better — ours gives a plan
> gate + governance; theirs gives adaptivity + a warm cache + far less ceremony per coding task.

---

## 2. The tool system for coding (`Tool.ts` + `tools/`)

> Full inventory in `FEATURES.md`. Here: the coding-critical tools + the contract that makes them
> reliable.

### 2.1 The `Tool` contract (`Tool.ts`)

Every tool is an object with a **4-stage lifecycle** the loop drives:

1. `validateInput(input, ctx)` → `{result:true}` or `{result:false, message, errorCode}` — the
   message is fed back to the **model** so it can self-correct.
2. `checkPermissions(input, ctx)` → `allow | ask | deny` (+ optional `updatedInput`). Runs only
   after validation passes.
3. `call(args, ctx, canUseTool, parentMessage, onProgress?)` → result (+ optional `newMessages`,
   `contextModifier`).
4. `mapToolResultToToolResultBlockParam(output, id)` — serialize to the **model-facing** result
   block (distinct from the React UI renderer).

Declarative flags the loop reads: `isConcurrencySafe`, `isReadOnly`, `isDestructive`,
`interruptBehavior()`, `maxResultSizeChars` (over budget → persist to disk + give the model a
preview + path), `shouldDefer` (ToolSearch lazy-loading), and **`toAutoClassifierInput(input)`** — a
compact security projection of the call fed to a fast classifier for **auto-approval**.

### 2.2 The coding tools (depth)

**Edit (`tools/FileEditTool`)** — exact **string replacement**, not diff-apply. Its validation
gauntlet is the part to copy:

- **Read-before-edit**: refuses to edit a file the model hasn't `Read` this session
  (`readFileState.get(path)` must exist) → error *"File has not been read yet."*
- **Staleness check**: if the file's `mtime` changed since the recorded read, it refuses (the file
  changed under it — e.g. a formatter or the user edited it).
- **Uniqueness check**: `old_string` must occur exactly once unless `replace_all` → else *"Found N
  matches… provide more context."*
- **Quote/whitespace normalization**: curly↔straight quotes, trailing-whitespace handling
  (markdown-aware).

**Write (`tools/FileWriteTool`)** — whole-file; read-before-overwrite (new files exempt), staleness
check, secret-in-team-memory guard.

**Bash (`tools/BashTool`)** — **fresh process per call, persistent CWD** (it appends
`pwd -P >| <tmpfile>` and re-reads it after exit; `utils/Shell.ts`). Sandboxed by default
(bubblewrap on Linux via `SandboxManager`), 30-min default timeout with tree-kill, output
tail-truncated at 30 KB then persisted to disk with a preview. "Banned commands" aren't a denylist —
they're **AST security analysis** (`bashSecurity.ts` rejects `$(...)`, backticks, `<(...)`) + per-binary
**read-only allowlists** (`readOnlyValidation.ts`: which `git`/`gh`/`docker` subcommands are safe →
auto-approved + concurrency-safe) + a classifier. `interpretCommandResult` knows `grep`/`rg` exit 1
= "no matches", not failure.

**Grep (`tools/GrepTool`)** — ripgrep wrapper, `head_limit`/`offset` pagination, excludes VCS dirs.
**Glob** — filename patterns. Both read-only + concurrency-safe.

**Agent / Task (`tools/AgentTool`)** — spawn a **sub-agent** (see §6).

**TodoWrite** — a session checklist the model maintains; a "verify your work" nudge fires when ≥3
items close.

**EnterPlanMode / ExitPlanMode** — the plan-gate workflow (model proposes a plan, user approves —
our plan gate's analog, but model-driven rather than pipeline-driven).

---

## 3. The coding system prompt (`constants/prompts.ts`)

This is the **single highest-value artifact** in the repo for improving our worker. `getSystemPrompt`
(`constants/prompts.ts:444`) assembles ordered sections. Two things matter: the **cache architecture**
and the **prose**.

### 3.1 Cache architecture (steal this)

Sections are emitted **static-first, then a `SYSTEM_PROMPT_DYNAMIC_BOUNDARY` marker, then dynamic
sections** (`prompts.ts:560-574`). The static prefix (intro, "doing tasks", actions, tools, tone,
output-efficiency) is byte-stable across turns → **prompt-cache hit**. Dynamic content (memory, env,
MCP instructions, token budget) goes *after* the boundary. The code is littered with cache-cost
accounting, e.g. a budget section was moved out of `DANGEROUS_uncachedSystemPromptSection` because
toggling it *"was busting ~20K tokens per budget flip."*

> **Directly relevant to us:** our Gemini cache-hit was 0% because the worker's per-turn system
> prompt + tool schemas drift (documented in our CLAUDE.md). Claude Code's discipline — *stable
> prefix, all volatility after a boundary marker* — is the fix. We should restructure our worker /
> planner prompt assembly the same way.

### 3.2 The prose (the coding discipline — adapt into our worker prompt)

Verbatim highlights from `getSimpleDoingTasksSection` + `getActionsSection`:

- **Minimal-diff / no gold-plating:** *"Don't add features, refactor code, or make 'improvements'
  beyond what was asked. A bug fix doesn't need surrounding code cleaned up… Three similar lines of
  code is better than a premature abstraction."* (This is exactly the discipline our `black` lint
  pass *violated* by reformatting 200-490 files — see the harness bug we just fixed.)
- **Verify before done:** *"Before reporting a task complete, verify it actually works: run the
  test, execute the script, check the output… If you can't verify… say so explicitly rather than
  claiming success."* — i.e. reproduce-first/verify is **baked into the prompt**, not bolted on.
- **Read before modifying:** *"do not propose changes to code you haven't read… read it first.
  Understand existing code before suggesting modifications."* — backed structurally by the
  read-before-edit tool gate (§2.2).
- **Faithful reporting (anti-false-claims):** *"if tests fail, say so with the relevant output…
  never claim 'all tests pass' when output shows failures… never characterize incomplete or broken
  work as done."* (And the converse: don't hedge confirmed results.)
- **Diagnose before retry:** *"If an approach fails, diagnose why before switching tactics — read
  the error, check your assumptions, try a focused fix. Don't retry the identical action blindly."*
- **Prefer editing over creating files.** Don't create files unless necessary.
- **Blast-radius confirmation** (`getActionsSection`): freely do local reversible actions (edit,
  test); **confirm before** destructive/irreversible/shared-state actions (rm -rf, force-push, PRs,
  messages, dropping tables). *"A user approving an action once does NOT mean they approve it in all
  contexts."* This is the rationale behind the permission model (§5).

The prompt also has **numeric length anchors** (*"keep text between tool calls to ≤25 words"*) — an
~1.2% output-token reduction vs qualitative "be concise."

> **Action for us:** our `SequentialExecutor.SYSTEM_PROMPT_BASE` just gained a bug-fix discipline
> section (localize→reproduce→minimal→verify). Claude Code's prose is more complete + battle-tested:
> add (a) the faithful-reporting clause, (b) the diagnose-before-retry clause, (c) the
> blast-radius/confirm clause for the customer-facing autofix surfaces, and (d) restructure for the
> static/dynamic cache boundary.

---

## 4. Context + memory assembly (`context.ts`, `memdir/`)

Per-conversation context is built once and **memoized** (cache-stable):

- `getSystemContext` (`context.ts:116`): **git status at start** — branch, main branch, user,
  `status --short`, last 5 commits, truncated to 2 KB with a note *"this status is a snapshot…
  will not update during the conversation"* (so the model doesn't trust it as live).
- `getUserContext` (`context.ts:155`): **CLAUDE.md / memory files**, auto-discovered by walking
  the cwd up + explicit `--add-dir` dirs, plus the current date.
- `memdir/memdir.js::loadMemoryPrompt` injects the memory prompt; memory has **scopes** (project /
  user / enterprise — see `services/commands/memory` survey).

> **Contrast:** we already inject `{{PROJECT_MEMORY}}` + codemap + RAG into the planner. The
> patterns worth adopting: (1) **git-status-at-start as a first-class context block** for the
> worker on existing-repo runs (we have the workspace; we don't summarize its git state for the
> model); (2) the **snapshot-not-live framing** so the model re-checks via tool when it matters.

---

## 5. Permissions, sandbox & safety (`Tool.checkPermissions`, `hooks/useCanUseTool.js`)

Autonomy is made safe by a **per-tool-call permission gate**, not a global mode:

- Each tool's `checkPermissions` returns `allow | ask | deny`. `ask` surfaces a confirmation in the
  REPL (`toolUseConfirmQueue`).
- **Permission modes**: `default`, `acceptEdits` (auto-allow file edits), `plan` (read-only until a
  plan is approved), `bypassPermissions` ("yolo"). Sub-agents run under `acceptEdits` by default.
- **Auto-approval classifier**: `toAutoClassifierInput` → a fast model judges whether a Bash command
  / edit is safe, so the gate only interrupts for genuinely risky calls.
- **Sandbox**: Bash runs under bubblewrap with an injected filesystem/network allowlist; the model
  is *told* the boundary and to use `$TMPDIR`.
- **Hooks** (`utils/hooks/*`, user-configurable): PreToolUse / PostToolUse / Stop hooks can block or
  modify a tool call — an extensibility layer for org policy.

> **Relevant to our autofix surfaces + VS Code client:** we gate at the *run* level (plan approval,
> tier caps). A *per-tool* permission result (`allow/ask/deny`) with an auto-classifier is the
> right model for the local-hands client (the user's machine should `ask` before a destructive
> local command) and for unattended CI autofix (deny shared-state actions by policy).

---

## 6. Sub-agents, parallelism, plan-mode, todos

- **Sub-agents (`tools/AgentTool` + `runAgent.ts`)**: the model can spawn a sub-agent with its own
  fresh system prompt + own tool pool + own context. The sub-agent runs the **same `query` loop**;
  only its **final message collapses into the single `tool_result`** the parent sees — keeping the
  parent context clean. Two modes: **fresh** (zero history, briefed by a prompt) and **fork**
  (inherits the parent's exact rendered prompt + history → reuses the prompt cache; deliberately
  refuses to change `model` on a fork to keep the cache warm). Optional **git-worktree isolation**.
  Async/background sub-agents return a task id and notify the parent later via a `<task-notification>`
  user message (exactly the mechanism driving *this* analysis).
- **Plan mode**: read-only exploration until the model submits a plan and the user approves — a
  model-driven analog of our plan gate.
- **TodoWrite**: a live checklist the model maintains across a long task — lightweight self-planning
  without a separate planner agent.

> **Relevant to us:** (1) the **fork-for-cache-reuse** sub-agent is a direct answer to our
> Gemini/worker cache misses; (2) the **collapse-subagent-to-one-result** pattern is how to do
> multi-step work without polluting the main context — we could use it for our per-task workers;
> (3) **worktree isolation** is exactly what our `Agent` tool's `isolation:"worktree"` does — they
> converge here.

---

## 7. What we adopt — prioritized

| # | Borrow | Into | Why | Effort |
|---|--------|------|-----|--------|
| 1 | **Read-before-write + staleness gate** on `editFile`/`writeFile` | ExecuteSpec `tool/` | Kills stale-edit/clobber bugs; gives the model self-correcting errors | M |
| 2 | **Verify-before-done + faithful-reporting + diagnose-before-retry** prompt clauses | Worker `SYSTEM_PROMPT_BASE` | Directly targets our 9 "wrong-fix" misses; battle-tested prose | S |
| 3 | **Static/dynamic system-prompt boundary** (cache discipline) | Worker + planner prompt assembly | Fixes our 0% Gemini cache hit; cuts cost on all 3 providers | M |
| 4 | **Per-tool output budget → persist-to-disk + preview** (vs flat 6k/5k truncation) | `shell`/`runTests`/`buildProject` tools | Model never loses access to full output; pulls on demand | M |
| 5 | **NDJSON `stream-json` headless transport** (one loop, three serializers) | CLI + VS Code client | Pipeable to CI/jq; clean machine surface for the extension | M |
| 6 | **Per-tool permission `allow/ask/deny` + auto-classifier** | local-hands client + autofix surfaces | Makes local + unattended execution safe without a global yolo flag | L |
| 7 | **Fork-for-cache-reuse sub-agent** + collapse-to-one-result | `SequentialExecutor` | Warm cache across tasks; clean parent context | L |
| 8 | **git-status-at-start context block** (snapshot-framed) | Worker context (existing-repo runs) | Cheap orientation; we have the workspace already | S |

See `_ANALYSIS/README.md` for the architecture overview and `FEATURES.md` for the full capability
surface this doc draws on.
