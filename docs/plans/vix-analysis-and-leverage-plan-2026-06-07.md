# ExecuteSpec Harness — vix Analysis, State Audit & Execution Plan (SINGLE SOURCE OF TRUTH)

> Date: 2026-06-07. Author: deep-read of the repo at a shallow clone of `https://github.com/get-vix/vix`
> (commit as of clone). Every claim below was verified against source, not the project's docs.
> File references like `session.go:1677` are paths inside `internal/daemon/` of the vix repo unless noted.
> ExecuteSpec file references are clickable links relative to this repo.
>
> **📌 This is the single source of truth.** It consolidates three docs (2026-06-07):
> 1. this vix code-verified analysis + ExecuteSpec leverage/state audit (§0–§8b),
> 2. the independent external audit `vix-exhaustive-line-audit-2026-06-07.md` — its P0–P10 are
>    **fully absorbed** here (reconciled in §8b, scheduled in §8 W0–W5); that file is **superseded**,
> 3. the standalone `worker-context-compaction-execution-plan-2026-06-06.md` — **inlined verbatim** as
>    **§W2.1** below; that file is **superseded**.
> Use only this doc going forward; the other two carry a SUPERSEDED banner pointing here.
>
> **Migration numbering corrected (2026-06-07):** the earlier drafts assumed `V231 = worker.compaction.enabled`,
> but **V231 shipped as `worker.critic-pass.enabled`** (RL-2026-06-006, the common critic/deliverable/verify
> harness stages — see §7b). Head is now **V231**. The plan's flags are therefore renumbered: **V232** =
> `worker.compaction.enabled` (§W2.1), **V233** = `worker.edit-diagnostics.enabled` (W1.1), **V234** = the A2
> cache flag (W2.2), **V235/V236** = code-intel flags (W3). Confirm with `ls V*.sql` before creating.

---

## 0. TL;DR

vix is a **Go terminal coding agent** (a Claude Code / Crush / OpenCode competitor) built as a
**client + daemon** pair: a thin Bubble Tea TUI (`vix`) talks NDJSON over a Unix socket to a
long-lived daemon (`vixd`) that owns the LLM loop, tools, sandboxing, LSP/tree-sitter, MCP, and an
embedded web "mission control" dashboard.

**The single most important finding:** vix's reputation for being "good" is **not** a caching trick
and **not** a clever harness. Its caching is literally one line (`anthropic.go:126`, the SDK's
top-level auto-breakpoint). What actually makes it good is **disciplined, boring engineering of the
things that bust LLM coding agents**:

1. **Prefix-stability discipline** so the prompt cache actually hits (frozen system prompt, stable
   tool order, append-only history, volatile content last).
2. **A read-before-edit hard gate** + **exact-unique-match edits** that kill hallucinated edits.
3. **Separation-of-powers review** (reviewer agent with write tools *physically removed*, biased to
   reject).
4. **An anti-over-engineering system prompt** that is unusually specific.
5. **Token-disciplined tool output** (deterministic truncation + spill-to-file + UI-only diffs).

ExecuteSpec is already **ahead of vix** on persistence, multi-tenancy, budgets/caps, scoring, native
Gemini, and explicit Gemini caching. So leverage is **selective**, not wholesale. The highest-ROI
borrows are in **§7** (ranked). The biggest single opportunity is one vix *itself does not do*:
**auto-inject compiler/test diagnostics into the edit tool result** — for a Java/Spring codebase that
is a deterministic, high-signal feedback loop we can beat vix on.

---

## 1. What vix is — architecture

- **Two binaries.** `cmd/vix/main.go` (client TUI + headless) and `cmd/vixd/main.go` (daemon).
- **Transport.** One Unix socket (`/tmp/vixd.sock`), **NDJSON** framing, 32 MB max message
  (`protocol/messages.go:11`). Two protocols multiplexed on the first line (`server.go:182`): a
  persistent **session** (`{"type":"session.start"}`) or a one-shot **RPC** (`{"action":...}` /
  `{"command":"tool.read_file",...}`). Tools are thus both the agent's internal mechanism *and* a
  public RPC surface — which is what makes "remote brain / local hands" possible.
- **Daemon lifecycle.** The client pings the socket; if nobody answers it spawns its own private
  daemon, preferring the `vixd` binary sitting next to it so client/daemon never version-skew
  (`main.go:334`). A private daemon gets `--no-mission-control` and is killed on client exit; a shared
  daemon persists and serves many clients.
- **State lives in the daemon, in RAM.** `Server.sessions map[string]*Session` (`server.go:46`). Each
  TUI connection = one `Session` with its own agent loop. The TUI is disposable: it can reconnect, and
  it can **fork a session at a turn index** (`ForkSessionID`/`ForkTurnIdx`). **There is no
  conversation persistence** — a daemon restart drops all history (the only SQLite DB is tool-access
  telemetry, `access_stats.go`).
- **Web UI.** `webserver.go` serves a Vite/React SPA embedded via `//go:embed web/dist` ("Vixd
  Mission Control") on `127.0.0.1:1337` — a read-only live view of all sessions over a WebSocket, plus
  ElevenLabs voice-agent hooks. Not a second chat client.
- **Provider abstraction.** `llm.Client` interface (`llm/client.go:14`), **streaming-only**,
  provider-neutral types. Three wire adapters chosen from a data-driven `providers.json`:
  `messages`→Anthropic (`anthropic.go`), `responses`→OpenAI (`openai.go`),
  `chat_completions`→OpenAI-compatible incl. OpenRouter/MiniMax (`chat_completions.go`). **No Gemini
  adapter** (only reachable via OpenRouter's chat-completions path → no explicit caching).

---

## 2. The harness (agent loop, workflows, subagents)

### 2.1 The loop
The interactive loop is `Session.handleInput` → the inner `for {}` at `session.go:1677`:

```
for {
  maybeAutoCompact()                 // context-window guard (see §5)
  system := buildSystemPrompt()      // REBUILT EVERY TURN
  msg := streamWithRetry(system,...) // LLM call w/ retry + stall watchdog
  append assistant msg
  if StopEndTurn { ...todo-nudge gate... break }
  if "tool_use" { results := dispatchToolCalls(msg); append user(results); continue }
}
```

- **No max-iteration cap, no wall-clock, no dollar budget** on the main loop. It runs until the model
  emits `end_turn`, the user cancels, or an error. (Subagents/workflow agents *do* cap at
  `max_turns`.) This is riskier than ExecuteSpec's per-tier caps + `itersSinceProgress` force-terminate
  — **do not copy this openness for an autonomous product surface.**
- **Stop/stuck detection** is minimal: only a **TODO-completion nudge** (up to 3× refuse to end the
  turn while TODOs are pending, `session.go:1714`) and prompt-level "don't brute-force" guidance. No
  stagnation/progress detector. ExecuteSpec is ahead here.
- **API retry** (`streamWithRetry`, `session.go:1420`): up to 10 attempts, exp backoff + jitter,
  provider-aware retryability (`errors.go:classifyError`, handles typed *and* raw-JSON stream errors),
  and — notably — **`CloseIdleHTTPConnections()` before each retry** (`session.go:1515`) so a wedged
  half-open connection can't silently swallow every retry.
- **Thinking-stall handling** (`session.go:1534`/`1576`): a stalled thinking block becomes a
  user-role nudge feeding the model its own partial summary; a **sticky `sawAnyStall` flag** forces
  the *final* retry to run with extended thinking **disabled** so the model just answers.

### 2.2 Tool orchestration (`dispatchToolCalls`, `session.go:2025`)
One unified dispatcher shared by main agent, subagents, and workflow steps, parameterized by a
`dispatchOptions` callback struct. Three stages: parse/classify → execute → collect-in-order.

- **Read-only-batch parallelism** — the key line (`session.go:2123`):
  ```go
  canParallelize := !hasInteractive && !hasWrite && len(tasks) > 1
  ```
  Batches of pure reads/greps/globs run concurrently; **any** write or interactive tool forces the
  whole batch sequential. Confirmations from a parallel batch are deferred to a sequential post-pass.
- **Always-pair tool_results, even on cancel** (`session.go:2135`): every `tool_use` gets a matching
  `tool_result` (synthetic `{Cancelled, IsError:true}` if skipped). Eliminates the dangling-tool_use
  400 error class and keeps cancelled turns resumable.
- **Timeouts** (`session.go:1271`): every tool pinned to a floor (120s); only `bash`/`glob_files` may
  raise it (clamped to 600s). The real caps are plumbed into the tool JSON schemas so the model reads
  them. A timed-out tool returns an error result; it does not abort the agent.

### 2.3 Workflows + the plan gate (`workflow.go`, `config/defaults/settings.json:163`)
A declarative JSON step graph (`agent` / `tool` / `bash` step types) with `next_steps`, conditional
`execute_if` bash predicates, `$(step.id.key)` variable passing, and **goroutine fan-out when >1
branch passes**. The shipped `"Plan"` workflow is the plan gate:

```
explore(agent) → plan(fork_from explore) → review(tool: ask_question_to_user)
   ├ Accept → execute(agent)
   ├ Modify → refine(fork_from plan) → review   [loop]
   └ Reject → stop
```

Phase handoff uses two mechanisms together: **`fork_from`** (`AgentRunner.Clone`, `workflow.go:795`)
clones the prior phase's *full conversation* so the planner inherits exploration without re-doing it;
and a **prompt-level "set aside the previous phase" banner** at the top of each phase prompt. Per-step
model routing is encoded in agent frontmatter (explore=Sonnet, plan=Opus) — a deliberate cost/quality
split.

### 2.4 Subagents (`subagent.go`) — vix's Task tool
`spawn_agent{prompt, agent_type, background}`. Context-shielded (subagent gets only the prompt + its
own system prompt, **not** the parent's history); only its final text returns. **Background mode**
(`SpawnBackground`, `subagent.go:414`) runs in a session-scoped goroutine, returns a `task_<N>` id
immediately, and the model later polls via `task_output` (blocks ≤30s). Bulk-cancellable. There are
**three** agent abstractions: ephemeral subagents (`RunSubagent`), persistent workflow agents
(`AgentRunner`, keeps history across `Send()`), and the main session agent.

### 2.5 `tool_orchestrator` (`tool_orchestrator.go`, flag-gated, default off)
The cleverest harness idea: the model writes a **Python script** that calls `read_file`, `grep`,
`edit_file`, `lsp_query`, etc. as Python functions; vix runs it in a `python3` subprocess that calls
back into vix's tools over a **stdin/stdout JSON-RPC loop**. Collapses an N-turn tool dance into one
turn for mechanical batch work (rename across N files, gather-and-digest). Hardened with process-group
SIGKILL-on-cancel + OOM-score bias + 5-min cap. **Caveat:** inner calls bypass the deny-list /
read-gate / confirmation (`tool_orchestrator.go:256`) — if we adopt this, route inner calls back
through the gates.

---

## 3. Coding capabilities (the tools)

### 3.1 The catalog
`read_file`, `read_minified_file`, `write_file`, `write_minified_file`, `edit_file`,
`edit_minified_file`, `delete_file`, `bash` (+ background mode), `grep`, `glob_files`, `lsp_query`,
`tool_orchestrator`, `spawn_agent`, `task_output`, `ask_question_to_user`, `todo_write/read`,
`web_fetch`, `web_search`, `skill`, `mcp__<server>__<tool>`. A read-only subset
(`tool_schemas.go:471`) is used for plan-mode exploration.

### 3.2 File editing — why it's reliable
- **Exact unique string-replace, no fuzzy matching** (`editFileImpl`, `tools.go:520`):
  `old_string` must match **exactly once**, else a hard error states the count
  (`"old_string found %d times (must be unique)"`). This forces the model to include disambiguating
  context. There is deliberately no Levenshtein/whitespace-tolerant fallback (fuzzy matching hides
  model mistakes and produces wrong edits).
- **Read-before-edit hard gate** (`session_read_gate.go:45`): `edit_file` is rejected unless the file
  was read this session (per-session `readFiles` set, marked on read *and* on successful edit/write so
  you can edit→edit without re-reading). `write_file` is intentionally exempt (wholesale replace).
- **Mode preservation** on overwrite/edit (`tools.go:493`) — doesn't silently demote a `0755` script.
- **Diffs are UI-only** (`session.go:418`, `Detail` field) — the model gets only the terse
  `"Edited X (replaced 1 occurrence)."`, never the diff, so edits don't eat context.

### 3.3 Search & context
`grep`/`glob_files` have pluggable backends (ripgrep/`fd` or built-ins), always return
`path:line:col`, are deny-list-filtered on output, and the schemas actively steer the model off shell
`grep`/`find` ("There is no need to use bash grep — this tool already provides line numbers",
`tool_schemas.go:236`). `bash` carries required `reason_to_use_instead_of_*` justification fields.

### 3.4 tree-sitter (`treesitter.go`, 888 lines) — used ONLY for minification
Not symbol extraction (that's LSP). It strips comments+whitespace to a token-dense one-line form for
cheaper read/edit context, then a real formatter (`gofmt`/`prettier`) re-expands on write. The hard
parts: per-language auto-semicolon/whitespace annotators, **idempotency** (so re-minifying is stable,
else `edit_minified_file` matching breaks), and a **re-parse validation gate** (`revalidateMinified`,
`treesitter.go:230`) that rejects any minified output that introduced a syntax error — so it never
writes broken code.

### 3.5 LSP (`brain/lsp/`, `lsp_tool.go`)
A from-scratch JSON-RPC LSP client pool (lazy start, 30s failure cooldown, crash auto-restart,
per-URI async diagnostics). `lsp_query` exposes definitions/references/hover/symbols/diagnostics/
implementations as compact `path:line:col`. LSP also powers the project symbol index.
**The critical gap:** diagnostics are **not** auto-injected after an edit — the model only sees
compiler/type errors if it *chooses* to call `lsp_query operation=diagnostics`. No post-edit
"here's what you broke" loop. **This is the gap we can beat.**

### 3.6 Output discipline
Layered deterministic caps: `read_file` 256 KB (line-boundary truncation + "use offset/limit" hint),
`bash`/`grep`/`glob`/orchestrator 20 KB, lsp locations 200. Full bash output spills to
`vix-bash-history.log` (debuggable without context bloat). **No LLM-summarization of tool output** —
the `tool_orchestrator` is the intended "gather a lot, return a digest" escape hatch.

---

## 4. Caching & the LLM layer — the real story

**Verified first-hand.** The premise "its caching is so good" is, on the evidence, **false** — or
more precisely, vix gets the *discipline* right and leaves the *technique* on the table.

### 4.1 What vix actually does
- **Anthropic: a single top-level breakpoint.** `params.CacheControl = NewCacheControlEphemeralParam()`
  on `MessageNewParams` (`anthropic.go:126`). That's the SDK's **automatic placement** of one
  `ephemeral` breakpoint on the last cacheable block (render order tools→system→messages). The
  per-system-block `CacheControl` plumbing exists (`anthropic.go:329`) but **no caller ever sets it** —
  it's dead. So: **one breakpoint, placement delegated to the SDK, no rolling multi-breakpoint, no
  20-block-lookback mitigation.**
- **OpenAI / chat-completions: implicit only.** No `cache_control` emitted; it only *reads*
  `cached_tokens` back. The code comment claiming OpenRouter forwards `cache_control` to Anthropic is
  **false** — `buildChatCompletionMessages` drops it entirely.
- **Usage tracking** captures all four Anthropic fields (`anthropic.go:421`).

### 4.2 What actually drives its cache hit-rate — prefix stability
This is the transferable lesson and vix does it well, mostly by architecture:
1. **Frozen system prompt** — `buildSystemPrompt` interpolates only the session-stable working dir; no
   `time.Now()`, no request IDs, no random ordering.
2. **Stateless append-only history with verbatim round-trip** (`Message.ToParam()`, including thinking
   blocks + signatures) so turn N+1's byte prefix is exactly turn N's request + response + results.
3. **Stable tool ordering** (slice order, never re-sorted per request) and **deterministic
   header/body key sorting** in the chat adapter.
4. **Volatile content goes last** — new input and tool results always appended after the cached prefix.
5. **Plugin/system prefix prepended as block 0** (constant) anchors the cache front.
6. **Honest identity** — vix keeps its own stable prompt and refuses Claude-Code impersonation, which
   is incidentally cache-friendly.

### 4.3 Where vix is weak (and ExecuteSpec can do better)
- Only 1 Anthropic breakpoint → exposed to the **20-content-block lookback limit**: a tool-heavy turn
  emitting >20 `tool_use`/`tool_result` blocks between breakpoints can silently miss the cache.
- **System prompt rebuilt every turn** including a "frequently accessed files" block that can churn →
  shifts the system segment of the prefix and can cost the tools+system cache tier.
- **Compaction is cache-pessimal**: it rewrites `messages[0]` with a summary, invalidating the entire
  prefix cache on the very next turn (§5.4).
- **No Gemini** explicit caching at all.
- **Tool-schema JSON key order** is `map[string]any` → Go's random iteration can change request bytes
  (partly saved by SDK re-marshal; don't rely on it).
- **Cost bug** (verified): `CalculateCost` (`cost.go:85`) subtracts `cache_read+cache_creation` from
  `input`, but callers pass Anthropic's `input_tokens` which is *already* uncached-only (vix's own UI
  adds them at `model.go:1889`). → under-counts Anthropic cost, clamps to 0 on cache-heavy turns.
  `CostUSD`/OpenRouter server-reported cost is declared but never populated (always 0).

---

## 5. Session / context / compaction

- **Full history, no sliding window** — same baseline as ExecuteSpec's V2 worker. The whole
  `s.messages` slice is sent every turn.
- **Auto-compaction ON by default** (`maybeAutoCompact`, `session.go:1825`), triggered at **top of
  every turn** when `lastInputTokens >= 0.8 * contextWindow(model)`.
- **The best idea here:** the trigger uses **observed, cache-inclusive** prompt size —
  `lastInputTokens = InputTokens + CacheReadTokens + CacheCreationTokens` (`session.go:1707`) — not an
  estimate. Unknown context window ⇒ compaction disabled (fail-safe).
- **Turn-boundary snapshots** (`turnSnapshots`) make compaction **never split a tool_use/tool_result
  pair** (`resolveCompactionKeep`, `session.go:1785`), and back fork + rewind for free. Keep policy:
  explicit `/compact N`, or `keep_last_n_turns`, or ratio (default keep 25%).
- **The summarizer** (`session.go:1919`) is a one-shot, tool-free call with a tuned retention prompt
  (goals → decisions+rationale → exact file paths → tool outputs/errors → open TODOs; "keep
  identifiers/paths/commands verbatim"). Appends a synthetic trailing **user** turn because the dropped
  prefix ends on an assistant message and some APIs reject a trailing assistant prefill.
- **§5.4 cache interaction (the gotcha):** summarize-and-replace writes the summary at `messages[0]`,
  which busts the whole prefix cache next turn. The fix vix *doesn't* do: keep system+tools frozen and
  mutate only content *after* the last cache breakpoint, or place a breakpoint right after the summary.

---

## 6. Security / sandbox / prompts / skills (condensed)

- **Two-layer file safety.** `bash` runs in an OS sandbox enforced at **syscall time**
  (Landlock on Linux via a self-re-exec helper with ABI-adaptive masks; seatbelt on macOS; bwrap
  fallback). The **bash path pre-flight was deliberately deleted** (`session.go:1043`) — "parsing a
  shell string to predict paths is unreliable; Landlock enforces the real boundary." The in-process
  **file tools** keep a symlink-resolved path allowlist (because they run inside `vixd`, which isn't
  sandboxed). A single `platform_policy.go` table feeds both the sandbox profile and the approval
  heuristic.
- **Deny-list** (`deny_list.go`) is operator-configured, **absolute/unoverridable**, runs first,
  matches paths by ancestor + symlink resolution and bash by hand-rolled token scanning (only tokens
  with `/` are paths; only `://` are URLs). Even **scrubs grep/glob output** lines pointing into denied
  paths. **Network is unrestricted in all sandbox modes** — the URL deny-list is a content filter, not
  an enforcement boundary.
- **Approval system**: deny-list → read-before-edit gate → confirmation prompts (write tools,
  outside-cwd dir access with persist-able grants). Principle baked into the prompt: "approving once is
  not blanket approval."
- **The system prompt** (`config/defaults/agents/general.md`) is a goldmine — see §7 item C5. Its
  anti-over-engineering cluster is unusually specific.
- **Reviewer separation of powers**: `agents/reviewer.md` has write/edit/delete **removed from its
  toolset** + must run the code + **asymmetric verdict bias** ("if ambiguous default to NEEDS_FIX — a
  false DONE ships broken; a false NEEDS_FIX costs one retry") + machine-parseable trailing JSON
  verdict that gates the loop; retry **forks the implementer** so its context is preserved.
- **Skills**: Claude-Code-style 3-level progressive disclosure (name+description in system prompt →
  body via `skill` tool → bundled files). Maps onto ExecuteSpec's prompt-fragment system.
- **MCP**: from-scratch JSON-RPC client (stdio + HTTP), tools-only (no resources), per-server
  allowlist + `require_confirmation`, deny-list on server URLs, strips internal params before
  forwarding.

---

## 7. Leverage plan for ExecuteSpec — ranked

> Legend: **Impact** (coding quality / cost / latency / robustness) · **Effort** · **Where**.
> ExecuteSpec is already ahead of vix on persistence, multi-tenancy, budgets/caps, scoring, native
> Gemini, and the V225 explicit-Gemini-cache flag — so these are selective borrows.

### Tier A — Caching (your explicit ask)

**A1. Prefix-stability audit of the worker request.** *Impact: cost (high). Effort: low–med.*
The real lesson from vix. Audit [`engine/WorkerAgent.java`](src/main/java/com/navneet/executespec/engine/WorkerAgent.java)
+ [`agent/LlmRouter.java`](src/main/java/com/navneet/executespec/agent/LlmRouter.java) for anything
that perturbs the prompt **prefix** turn-to-turn: timestamps/UUIDs/run-ids in the system prompt,
non-deterministic tool-schema key order, re-sorted/conditionally-included tools, codemap or
"frequently-accessed-files" content injected *before* the cache breakpoint. Rule: **freeze
system+tools as a byte-stable prefix; put all volatile context (codemap, plan, dynamic state) AFTER
the last cache breakpoint; append new turns at the end only.** CLAUDE.md already records this hazard
for Gemini implicit cache (per-turn system-prompt drift → 0% hits) — vix proves the same discipline
lifts Anthropic too. Verify with `cache_read_input_tokens > 0` across turns via
[`cost/RunCostTracker.java`](src/main/java/com/navneet/executespec/cost/RunCostTracker.java).

**A2. Multi-breakpoint rolling Anthropic cache (do better than vix).** *Impact: cost (high). Effort: med.*
vix uses 1 breakpoint. Place **two+**: one **stable** breakpoint after the last tool/system block
(caches tools+system as a frozen unit), and a **rolling** breakpoint on the last block of the latest
turn. In tool-heavy worker turns, add an **intermediate breakpoint roughly every ~15 content blocks**
to defeat the **20-block lookback limit** (budget: 4 max — 1 stable + up to 3 rolling). ExecuteSpec
already uses Spring AI Anthropic explicit `cache_control` blocks, so this is breakpoint *placement*,
not new plumbing.

**A3. Canonicalize tool-schema serialization.** *Impact: cost (med). Effort: low.*
Emit every tool's JSON schema with a **fixed key order** (Jackson `@JsonPropertyOrder` /
`SORT_PROPERTIES_ALPHABETICALLY` / `LinkedHashMap`). vix's `map[string]any` random order is a latent
position-0 cache-buster; don't inherit it.

**A4. Gemini: keep native + explicit cache; never route through an OpenAI-compat shim.** *Impact: cost. Effort: n/a (validation).*
vix has no Gemini caching precisely because it would go through OpenRouter chat-completions. ExecuteSpec's
`GeminiNativeToolClient` + V225 `gemini.explicit-cache.enabled` is the right design — this is a
confirmation, plus the reminder from V225's own note: when referencing `cachedContents`, **omit** the
cached system/tools from the request body (Gemini 400s if both are set).

**A5. Compaction that doesn't bust the cache.** *Impact: cost. Effort: med.* See B-tier B3 — this is
the compaction/cache interaction; listed there to keep it with the compaction work.

### Tier B — Coding quality (highest ROI overall)

**B1. ⭐ Auto-inject build/test diagnostics into the edit result — beat vix's biggest gap.**
*Impact: coding quality (very high). Effort: med.*
vix surfaces diagnostics only if the model *asks*. We have
[`tool/BuildProjectTool`](src/main/java/com/navneet/executespec/tool/) and `runTests` already. Add a
post-edit hook (or wrap `editFile`/`writeFile`) that runs a **fast, scoped** compile/type-check on the
touched module and **appends the first N compiler errors (`file:line: message`) to the tool result**.
For Java/Spring, `javac`/Spring wiring errors are deterministic and cheap — this closes the
edit→"here's what you broke" loop that vix leaves open and directly attacks the *wrong-half-fix* class
that dominates the SWE-bench residuals (per RL-2026-05-061). Gate behind a flag; measure on the
round-2 eval.

**B2. Read-before-edit gate.** *Impact: coding quality (high). Effort: low.*
Track a per-run **read-set** in the worker; reject `editFile` against a file not read this run with an
instructive error ("read the file first"). Mark files read on successful read *and* successful
edit/write (so edit→edit doesn't force a re-read). Exempt `writeFile`. Kills the entire
hallucinated-edit class for ~30 lines of code in [`tool/ToolRegistry.java`](src/main/java/com/navneet/executespec/tool/ToolRegistry.java)
/ the worker dispatch. **Verify first** whether ExecuteSpec's `editFile` already does this.

**B3. Exact-unique-match on `editFile` with an actionable count error.** *Impact: coding quality. Effort: low.*
Ensure `editFile` requires a **unique** `old_string` and, on failure, returns the **match count**
("found N times — add surrounding context to disambiguate" / "not found"). The count is the signal the
model needs. Avoid fuzzy matching. (CLAUDE.md's RL-2026-05-062 head-first truncation already serves the
"model can match what it saw" half — this is the enforcement half.) **Verify current behavior first.**

**B4. Schema-validate tool input before the handler runs.** *Impact: robustness. Effort: low.*
Validate each tool call's JSON against its declared schema in the dispatcher; reject "missing required
field X" instead of letting a missing field become a silent `null`/empty that writes garbage. Keep
advisory fields (e.g. a `reason`) non-blocking.

**B5. Steer the worker off shell `grep`/`cat`/`sed`.** *Impact: coding quality / cost. Effort: low.*
Mirror vix's schema steering in the `searchFiles`/`readFile`/`editFile` tool descriptions ("no need to
shell out — this tool gives line numbers") and add a required justification field on `shell` when it
would run `grep`/`find`/`sed`/`cat`. Improves reviewable diffs and cuts wasted turns.

**B6. (Optional, higher effort) tree-sitter minified read/edit substrate.** *Impact: cost on big files. Effort: high.*
Only if context-token cost on large Java files is a measured bottleneck. `google-java-format` (already
in your spotless gate) is the "expand" step; the hard parts are idempotency + a re-parse validation
gate. Do B1–B5 first.

### Tier C — Harness

**C1. Read-only-batch tool parallelism.** *Impact: latency (high on exploration turns). Effort: med.*
ExecuteSpec's worker dispatches tools sequentially (`dispatchOne`). Adopt vix's predicate:
`canParallelize = !hasWrite && !hasInteractive && batch>1` → run `readFile`/`searchFiles`/`listFiles`
batches concurrently; serialize anything that writes; defer confirmations to a post-pass. Big
wall-clock win on the read-heavy early phase of a task.

**C2. Always-pair tool_results, even on cancel/error.** *Impact: robustness. Effort: low.*
Synthesize a `{cancelled/error}` result for every un-run `tool_use` so a cancelled/aborted worker turn
stays API-valid and resumable (matches the Anthropic pairing invariant). Pairs well with your
Redis `v2:agent-ctx` snapshots.

**C3. Reviewer separation-of-powers + asymmetric verdict.** *Impact: coding quality. Effort: low–med.*
[`reviewer/ReviewerAgent.java`](src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java):
(a) **strip ALL mutation tools** from the reviewer at the `ToolRegistry.bindForWorker` level (you
already filter `gitCommit` — extend to write/edit/delete); (b) force the verdict bias ("ambiguous →
NEEDS_FIX"); (c) require it to *run* the build/tests and cite commands; (d) on the fix-build third
pass, **fork the implementer's context** rather than starting cold. Maps cleanly onto your existing
reviewer + fix-build third pass.

**C4. Compaction driven by observed cache-inclusive tokens + turn-boundary safety.** *Impact: cost / correctness. Effort: med.*
For [`WorkerHistoryCompactor`](src/main/java/com/navneet/executespec/) (flag
`worker.history-compaction.enabled`): trigger on **real** prompt size
`input + cache_read + cache_creation` (you already record `cache_read` in `RunCostTracker`) vs a
fraction of the model window — not an estimate. Snapshot the message list at each **turn boundary** and
only ever cut there, so you never orphan a `tool_result`. Steal vix's summarizer retention prompt
(exact paths verbatim). **And** (the A5/B3 cross-link) place the summary + a cache breakpoint so only
content *after* the last breakpoint changes — don't rewrite the prefix like vix does.

**C5. Adopt the anti-over-engineering prompt cluster.** *Impact: coding quality. Effort: low.*
Port the specific prohibitions from `agents/general.md` into the worker base prompt
(`SequentialExecutor.SYSTEM_PROMPT_BASE`): "minimum complexity for the task; no defensive code for
impossible cases; validate only at boundaries; don't add comments/docstrings to untouched code; delete
unused code outright (no `_`-rename shims); three similar lines beat a premature abstraction." These
are unusually effective at curbing LLM gold-plating and complement your existing tests-mandatory +
symptom-anchored directives.

**C6. (Optional) `fork_from` phase handoff.** *Impact: cost / quality. Effort: med.*
Planner→worker handoff could fork the planner's *conversation* (its exploration reasoning) into the
worker instead of re-serializing just the plan text — preserving expensive context. Back it with your
Redis context snapshots.

**C7. (Optional, evaluate) `tool_orchestrator` Python-IPC batch tool.** *Impact: cost / latency on mechanical multi-file edits. Effort: high.*
A sandboxed scripting step that lets the worker chain `searchFiles → readFile(ranges) → edit → digest`
in one turn. Only worth it if multi-file mechanical edits are a measured cost driver. If built, route
inner calls through your read-gate + sandbox (vix doesn't — don't copy that).

### Explicitly NOT worth copying
- vix's **unbounded main loop** (no wall-clock / dollar / stagnation cap) — ExecuteSpec's per-tier
  caps + `itersSinceProgress` are safer for an autonomous surface.
- vix's **no-persistence / RAM-only** model — your Redis `ContextStore` + DB-authoritative design is
  strictly more robust.
- vix's **prefix-busting compaction** (rewrites `messages[0]`) — see C4.
- vix's **network-unrestricted sandbox** — your cleanroom `--network none` containers are stronger.
- vix's **cost double-subtraction** bug (`cost.go:85`) — your `RunCostTracker` already does cache-aware
  pricing correctly.

---

## 7b. AUDIT — current ExecuteSpec state (code-verified 2026-06-07)

After §7 was drafted from the vix side, the ExecuteSpec worker/tool/reviewer/router code was read
directly to score each borrow. **Headline: ExecuteSpec already implements most of these, and is ahead
of vix on prefix-stability, Gemini caching, deterministic schemas, and bounded-loop safety.** The
genuine high-value gap is **B1** (diagnostics-in-edit). Verdicts:

> **UPDATE 2026-06-07 (RL-2026-06-006) — common critic/deliverable/verify stages shipped (V231).** Since
> this audit, three completeTask-time stages landed in the **shared** `engine/WorkerAgent` as
> flag/data-activated, workspace-agnostic capabilities (NOT a Terminal-Bench fork): **#1 critic pass**
> (`criticPass`, one fresh-context no-tool acceptance review over the deliverable — git diff for local
> runs, `/app` for remote TB; PATCH opts in behind `worker.critic-pass.enabled` **V231** default OFF; TB
> via `ES_TB_CRITIC`), **#2 deliverable gate** (`requiredDeliverablePaths`, deterministic existence
> check), **#3 verify gate** (`terminalVerifyGate`). Validated on Terminal-Bench (DeepSeek): a 13-task
> near-miss slice went **4/13 → 8/13 (pass@2)**, 0 regressions. **Plan impact:** this **partially
> advances C3/P5** (a read-only fresh-context review role now exists — though it does NOT yet strip the
> worker's write tools, so the C3a "separation of powers" item is still open) and seeds the
> verification-discipline theme. It also **consumed V231**, forcing the renumber in the header.

Verdicts:

| Item | Verdict | Current state (file:line) |
|---|---|---|
| **A1** prefix stability | ✅ **PRESENT (ahead of vix)** | System prompt built once, placed at history[0], never rebuilt (`WorkerAgent.java:258`, `SequentialExecutor.java:77-183`); tools stable each turn (`:511`); dynamic context folded into the index-0 prefix; mid-run nudges appended as `UserMessage` (not System) to avoid busting Gemini cache; full append-only history. vix *rebuilds* its system prompt every turn — ExecuteSpec doesn't. Remaining refinement: static/dynamic boundary *across tasks* (engine Phase 6). |
| **A2** multi-breakpoint Anthropic cache | 🟡 **PARTIAL** | Centralized in `LlmRouter.callAnthropic` (`agent/LlmRouter.java:436-444`) but delegates to Spring AI `AnthropicCacheStrategy.CONVERSATION_HISTORY` — one auto-placed breakpoint scheme, **no rolling/multi-breakpoint, no 20-block-lookback mitigation**. Going further needs a richer Spring AI strategy or dropping to raw `AnthropicApi` (the `AnthropicForcedToolClient` pattern). |
| **A3** deterministic tool-schema serialization | ✅ **PRESENT** | Schemas are static text-block literals (`tool/Tool.java:35`); all 3 clients serialize via order-preserving Jackson `ObjectNode`/`LinkedHashMap`; `ToolRegistry.bind*` preserves injected list order (no sort/HashMap in dispatch path). Better than vix's random Go-map order. |
| **A4** Gemini explicit caching | ✅ **PRESENT (vix has none)** | `GeminiNativeToolClient.java:142,379-383,616-670` — mints `cachedContents`, references by name, **omits** system/tools/toolConfig from the body, SHA-256 key, 45m<60m TTL, double fail-safe inline fallback. Complete and correct. |
| **B1** diagnostics-in-edit-result | ❌ **MISSING (the standout gap; vix lacks it too)** | `EditFileTool.java:202` / `WriteFileTool.java:119` return only a write summary; no compile runs on edit. Build feedback is deferred to the per-task `buildProject` smoke / reviewer. `MavenDependencyValidator` exists but is wired only inside `BuildProjectTool`. Seams exist (`EditFileTool` already has `ExecutionContext`; bench docker-exec reusable). |
| **B2** read-before-edit gate | ✅ **PRESENT (ahead of vix)** | `EditFileTool.java:141-159` + `ReadFileStateStore` — rejects edits to unread files, **SHA-256 staleness** (catches files rewritten under the worker), writeFile exempt by design, fail-open. vix uses a simpler read-set. |
| **B3** exact unique-match edit | ✅ **PRESENT** | `StringEditMatcher.java:82-88` reports the match **count** on multi-match; `:140-146` every fuzzy tier still requires uniqueness. Note: has a whitespace/indent-tolerant fuzzy fallback (fires only on zero exact matches) — a deliberate trade vix doesn't make; well-guarded (unique-only, no partial apply). |
| **B4** schema validation before handler | 🟡 **PARTIAL** | Strict schemas declared + provider-side enforcement + per-tool manual null-checks; **no centralized validator**. Self-contained to add at the dispatch chokepoint (`ToolRegistry.java:175-185` / `WorkerAgent.java:1416-1424`). |
| **B5** steer off shell grep/cat/sed | ❌ **MISSING / inverted** | Worker prompt *recommends* `grep -rn` (`SequentialExecutor.java:106`); `cat`/`grep`/`sed` allowlisted (`ShellSandboxPolicy.java:236-254`). **Real hole found:** `sed -i` via `shell` bypasses the B2 read-before-edit gate entirely. Prompt + policy change. |
| **C1** intra-turn read-only tool parallelism | ❌ **MISSING** | Sequential dispatch (`WorkerAgent.java:646-843`). Structurally favorable (already collects all envelopes into one list) but metrics/SSE/gate-state mutations are interleaved in the loop body. `TaskWaveScheduler.java` (whole-task parallelism) exists but is **dead code — never called**. |
| **C2** always-pair tool_results | 🟡 **PARTIAL** | Safe on iteration/wall-clock/budget/cancel (checked top-of-loop) and tool-throw (always returns envelope). One dangling edge: `completeTask` emitted *before* a sibling tool call in the same batch leaves siblings unpaired (`WorkerAgent.java:776-796`) — low impact (terminal history, never re-sent), ~5-line fix. |
| **C3a** reviewer write-tools stripped | ❌ **MISSING** | Reviewer is a thin composition over the **writable** `WorkerAgent` on all passes; only `gitCommit` denied (`ToolRegistry.java:65`). The whole reviewer is "fix-in-place on every pass" by design — vix's read-only-review + separate-fix-phase is a redesign, not a flag. The `ToolRegistry` deny-set primitive to build on exists. |
| **C3b** asymmetric "default NEEDS_FIX" | ⚠️ **DELIBERATELY OPPOSITE** | ExecuteSpec biases to *not* fix when unsure ("Over-fixing creates more bugs than it solves", `reviewer-general.md:59`). This is a defensible philosophy difference, **not** a defect — don't blindly flip it. Strength worth noting: the reviewer **runs the build as ground truth** (`ReviewerAgent.java:659`), which is vix's "gather first-hand evidence" point. |
| **C3c** fix-build forks worker context | ❌ **MISSING** | Fix-build pass starts cold with only the build error (`ReviewerAgent.java:433,460`), fresh `ExecutionContext`. Must re-derive intent from workspace + error each time. |
| **C4** cache-safe compaction | 🟡 **PARTIAL** | `WorkerHistoryCompactor` has the good parts — **observed-token trigger** (`WorkerAgent.java:538`, real `promptTokens`), turn-boundary cut, tool-pair-safe — but **rewrites position-1** (`WorkerHistoryCompactor.java:229-232`) → cache-busting. Default OFF. Fix = make the rewrite append-only (keep messages 0..1 verbatim, insert summary after the head). `ToolOutputCompactor` (shrinks new outputs) is already cache-safe and complementary. Both default OFF. |
| **C5** anti-over-engineering prompt | 🟡 **PARTIAL** | Has "FIX minimally" (bug-fix path, `SequentialExecutor.java:117`), tests-mandatory (`:144`), symptom-anchored verify (`:113-125`). **Missing** the 6 broader directives — and crucially they don't apply to greenfield/feature tasks. PATCH-mode `patch-agent.md:63-73` has some; the BUILD-mode base prompt does not. |
| **caps / bounded loop** | ✅ **PRESENT (ahead of vix)** | iteration (`WorkerAgent.java:366`) / wall-clock (`:394`) / dollar (`:429`) / stagnation `itersSinceProgress` (`:862-973`) / cooperative cancel (`:372`). vix is unbounded. |
| **cache-token accounting** | ✅ **PRESENT** | `RunCostTracker.java:129-130` records `cache_read` (all 3 providers) + `cache_creation` (Anthropic) per-run + per-task — so an even-more-precise observed-token compaction trigger is feasible with no new plumbing. |

### The genuine to-do list (everything else is already done or a deliberate choice)
1. **B1 — diagnostics-in-edit-result** — the one big, differentiating gap. ❌
2. **C5 — generalize the anti-over-engineering directives** to greenfield (6 concrete lines below). 🟡
3. **B4 — centralize schema validation** at the dispatch chokepoint. 🟡 (self-contained)
4. **C2 — fix the completeTask-early dangling tool_result** (~5 lines). 🟡
5. **C4 — make `WorkerHistoryCompactor`'s rewrite append-only** (cache-safe) before flipping it on. 🟡
6. **C1 — read-only-batch parallelism** (latency; medium effort, edge-coupled). ❌
7. **A2 — multi-breakpoint Anthropic cache** (capability-limited by Spring AI; bigger lift). 🟡
8. **B5 — steer off shell read/search + close the `sed -i` gate bypass.** ❌
9. **C3 — reviewer separation-of-powers** — a redesign; evaluate against the deliberate "don't over-fix" bias. ❌

### Bugs/holes found while auditing (incidental)
- **`sed -i` via `shell` bypasses the read-before-edit gate** (`ShellSandboxPolicy.java:236-254` allowlists `sed`) — a gate-evasion path. Tighten B5 to close it.
- **`completeTask`-before-sibling dangling tool_result** (`WorkerAgent.java:776-796`) — harmless today (terminal), but malforms the persisted snapshot; trivial fix.
- **`TaskWaveScheduler` is dead code** — whole-task parallelism designed but never wired (`SequentialExecutor` runs a plain sequential task loop). Relevant if parallelism is ever pursued.
- **`TokenBudgetMonitor`/`Policy` is partly orphaned** — its `COMPACT` verdict has no consumer in the V2 worker loop (the live lever is `WorkerHistoryCompactor`'s own threshold). Dead-ish intent.

### Caching verdict (the original ask), specifically
You are in **good shape**: A1/A3/A4 present, cache-token accounting present, prefix discipline ahead of
vix. The only real caching work is **A2** (multi-breakpoint, limited by Spring AI's auto-strategy) and
**C4**'s cache-busting rewrite (default OFF, easy append-only fix). "Improve caching" ≈ two refinements,
not a rebuild — vix has nothing to teach here that you haven't already done better.

---

## 8b. Reconciliation with the external research audit

Compared against `/Users/navneetprabhakar/Documents/research/vix-exhaustive-line-audit-2026-06-07.md`
(same vix code, also read from source, dated 2026-06-07). The two are **complementary**: this doc is a
*code-verified ExecuteSpec gap audit* (what's already built); that one is a *strategic
observability/contract thesis* (what to build next). They reach the **same bottom line** — vix's edge is
execution *discipline*, not a trick; the leap for ExecuteSpec is making execution cache-aware,
code-intel-aware, and observable. That independent convergence is worth trusting.

### Where they corroborate this doc
Stable prefix (their "why vix feels strong" #2 = my A1), deterministic-first compaction (their P1 = my
C4/B-tier), read-before-edit + schema validation (their P7 = my B2/B4), provider registry (their P8),
"don't copy the TUI / Python orchestrator-first / blind LLM compaction / minified-write-first" (their
"what not to copy" = my "NOT worth copying"). No conflicts.

### Corrections — items the external audit lists as TO-DO that are ALREADY DONE (don't redo)
The external audit is more aspirational and didn't read ExecuteSpec's code as deeply; several of its
asks already exist:
- **P7 read-before-edit + file-version check → ALREADY PRESENT.** `ReadFileStateStore` +
  `EditFileTool.java:141-159`, with **SHA-256 staleness** (stronger than vix's). Only the *centralized
  schema validation* half (my B4) is a real gap.
- **Stable prefix (P1 premise) → ALREADY PRESENT, ahead of vix** (system prompt built once, not rebuilt).
- **P8 provider registry → mostly DONE.** YAML `model-matrix` is already the single source of truth for
  model id / context window / **cache-aware pricing** / tier map. Net-new is only `providerUsageRaw`
  storage + usage-extraction rules.
- **P0 cache telemetry → ~half DONE.** `RunCostTracker.java:129-130` already records `cache_read`
  (3 providers) + `cache_creation` (Anthropic), accumulated per-run **and per-task**. The gap is
  per-**role/step** attribution + `reasoningTokens` + `providerUsageRaw` + **surfacing** in SSE / API
  DTOs / report JSON.

### Net-new in the external audit worth ABSORBING (not in this doc originally)
1. **The shared run/session event contract (their P0.5 + P9 + P10) — the biggest absorb.** Unify web +
   CLI + VS Code + benchmark + Terminal-Bench on ONE rich, typed event stream rather than per-frontend
   wrappers. ExecuteSpec is *already* building `clients/core` + CLI + VS Code (RL-2026-05-043/052) and a
   Terminal-Bench adapter — so the absorb is **to enrich the event contract NOW** (typed
   tool-call/tool-result/step events carrying cache+reasoning+reason+stepId+role) **while the clients are
   still forming**, before they harden around a thinner shape. Highest strategic leverage.
2. **Execution telemetry as a first-class contract (P0 + P4).** Promote planner/worker/retry/reviewer/
   verifier/terminal-bench/scorer to typed steps each carrying `{stepId, role, status, provider/model,
   input/output/cache-read/cache-write/reasoning tokens, toolCalls, artifactPaths, failureKind,
   retryOf}`. This is the *measurement layer my caching tier assumed but didn't elevate* — and it's the
   prerequisite to know whether A1/A2 cache changes actually help vs. just move spend.
3. **Background command jobs (P6)** — `{jobId, pid/pgid, log, poll, tail, kill, rc}`. Directly relevant
   to your active Terminal-Bench work (servers/watchers/long builds) and not in my original list.
4. **Re-rank code-intelligence UP (their P2 minified-outline reads + P3 LSP tools).** I had these low
   (B6/optional); for the *benchmark + large customer-repo* use case their "localize without reading
   whole repos" framing is right. Keep my B1 first (cheaper), then add `readCodeOutline` (Java/TS/Py/Go)
   and Java-LSP tools behind a flag.

### What THIS doc adds that the external audit missed
- **B1 auto-inject build/compiler diagnostics into the edit result** — my standout. The external audit
  only has *manual* LSP `diagnostics` as a tool the model must call (their P3, = vix's model). The
  auto-inject version is stronger and cheaper, and **neither vix nor the external audit proposes it.**
- **Code-verified current-state** (the §7b PRESENT/PARTIAL/MISSING table) — keeps you from rebuilding
  the ~8 things already done.
- **Concrete bugs/holes**: `sed -i` read-gate bypass, completeTask dangling tool_result,
  `TaskWaveScheduler` dead code, `TokenBudgetMonitor` orphaned `COMPACT` verdict.
- **The honest caching critique**: vix's *technique* is weak (1 breakpoint, 20-block exposure, cost
  double-subtract bug); the external audit praises vix's cache *visibility* (true) but not its
  under-optimized placement. Both true — adopt vix's visibility (their P0), beat vix's technique (my A2).
- **The reviewer "don't-over-fix" nuance** — the external audit's P5 ("reviewer: no write by default")
  ignores that ExecuteSpec's *opposite* bias is a deliberate, defensible choice.

### Merged unified backlog (this doc §8 + absorbed external items)
- **Tier 0 — Contract & measurement (absorb P0/P0.5/P4):** enrich the `clients/core` run-event contract
  with typed tool/step events carrying cache+reasoning+reason+stepId+role; surface in SSE + API DTO +
  report JSON; add per-step usage rollup. Do FIRST — it's the measurement layer everything else is judged
  by, and it must land before the CLI/VS Code clients harden.
- **Tier 1 — Coding quality (cheap, high ROI):** B1 (diagnostics-in-edit) ▸ C5 (greenfield anti-bloat
  directives) ▸ B4 (centralized schema validation = their P7 remainder) ▸ C2 (dangling-tool_result fix).
- **Tier 2 — Cache technique:** A2 (multi-breakpoint) ▸ C4 (append-only compaction) — *after* Tier 0
  telemetry proves the need.
- **Tier 3 — Code intelligence (absorb P2/P3):** `readCodeOutline` (Java/TS/Py/Go) ▸ Java-LSP tools,
  flag-gated. Benchmark + large-repo win.
- **Tier 4 — Harness depth:** C1 (read-only parallel dispatch) ▸ P6 (background command jobs, for
  Terminal-Bench) ▸ C3/P5 (role-isolated reviewer/verifier — weigh vs the don't-over-fix bias).
- **Tier 5 — Clients (their P9/P10):** finish CLI + VS Code as thin clients over the Tier-0 contract.
- **Drop:** B5 stays (close the `sed -i` hole); read-gate / prefix / pricing-registry already done.

---

## 8. Execution plan

This is the authoritative, actionable plan. It unifies three inputs: this doc's vix-leverage items
(§7 rationale A1–C7, §7b code-verified audit), the external research audit (§8b reconciliation, P0–P10),
and the standalone **worker-context-compaction execution plan**
([Appendix A](#appendix-a--worker-context-compaction-execution-plan-inlined)),
which becomes workstream **W2.1** (it remains the authoritative spec for that workstream). Those sections
are the "why"; this is the "how." Already-done items (B2/B3 edit gate+unique-match, A1 prefix stability,
A3 schemas, A4 Gemini cache, caps, cache-token recording) are **out of scope** — do not rebuild them.

> **⚠️ Provider-agnostic mandate (applies to EVERY workstream below).** Every technique borrowed from vix
> must work across **all** providers ExecuteSpec runs — **Anthropic, OpenAI, Gemini, DeepSeek/OpenRouter**
> — not just Anthropic. Almost all of these borrows live at the tool / dispatch / prompt / telemetry
> layers and are provider-neutral by construction. Where a provider-specific API is unavoidable (e.g.
> Anthropic `cache_control`), it is only a **tactic serving a provider-neutral goal**, and an equivalent
> tactic MUST be specified for each other provider (see **W2.2**). Two hard rules:
> 1. **Shared, not per-provider.** Prompt directives (C5), validation (B4), diagnostics (B1), compaction
>    (W2.1), telemetry (W0), parallel dispatch (C1) all live in the **shared** worker/tool layer — never in
>    a single-provider prompt fragment (`provider-anthropic.md` / `-openai.md` / `-deepseek.md`).
> 2. **Eval on all four before any flag-flip.** "Works on Anthropic" is NOT sufficient evidence — DeepSeek
>    and Gemini are schema-sensitive (RL-2026-05-061 reverted a tool that stalled native DeepSeek). Every
>    new tool / behavioral change is validated across Anthropic + OpenAI + Gemini + DeepSeek.

**Conventions (per repo rules):** every feature is flag-gated via `FeatureFlagService.isEnabled(...)`
seeded by a new Flyway `Vxxx` — **migration allocation (avoid collisions):** **V232** =
`worker.edit-diagnostics.enabled` (W1.1/B1, **SHIPPED 2026-06-07**, RL-2026-06-009 — took V232, not the
originally-reserved V233, because the founder-gated `worker.compaction.enabled` it was reserved against did
not land first, and Flyway forbids back-filling a lower version after a higher one applies). Remaining flags
(compaction §W2.1, the A2 cache flag W2.2, code-intel W3) allocate to the **next free `Vxxx`** when
implemented — head is now **V232**, confirm with `ls V*.sql` before creating per repo rule; ship CLAUDE.md +
per-package CLAUDE.md + `docs/reason-log/2026-06.md` updates in the same commit; test locally
(`JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q verify`) before any push; **no push without explicit
approval**; one branch / one PR for the session, not per slice.

Legend — Effort: S (<½ day) · M (1–3 days) · L (>3 days). Impact: coding-quality / cost / latency /
observability / strategic.

---

### W0 — Execution contract & cache/step telemetry  ▸ GATING, do first
*Absorbs external P0 + P0.5 + P4. Impact: observability + strategic + cost-measurement. Effort: M–L.*

**Goal:** every agent action becomes typed, observable state carrying cache/reasoning/usage, on ONE
event contract shared by SSE → web, CLI, VS Code, benchmark, and Terminal-Bench. This is the
measurement layer every later workstream is judged by, and it must land **before** the CLI/VS Code
clients harden around a thinner event shape.

**Steps**
1. Extend per-call usage capture in [`cost/RunCostTracker.java`](src/main/java/com/navneet/executespec/cost/RunCostTracker.java)
   — already records `cache_read`/`cache_creation` (line 129-130); add `reasoningTokens` +
   `providerUsageRaw` (the raw provider usage map, for audit/benchmark round-trip).
2. Add **role/step attribution** to each cost record: `{agentRole, stepId, taskId, retryOf}` where role ∈
   {planner, worker, worker-retry, reviewer, verifier, terminalbench, scorer}. Rollups become
   per-role/step, not just per-task.
3. Enrich the run-event contract (you already have `RunEvent.toolCall`, RL-2026-05-052, + SSE +
   `/api/runs/{id}/event-log` replay): typed `tool_call{toolId,name,args,reason,timeoutSec}`,
   `tool_result{toolId,output(preview),isError,artifactPath}`, and `step{stepId,role,status,provider,
   model,input/output/cacheRead/cacheWrite/reasoning,toolCalls,failureKind,retryOf,startedAt,endedAt}`.
4. Surface in: SSE stream, `/api/runs/{id}` DTO (per-step cost breakdown), report JSON, and the
   `clients/core` typed event definitions (single source the CLI + VS Code + bench consume).
5. Persist step-level usage (new `run_step_usage` table or extend the run cost columns) — its own Flyway
   migration (a telemetry table; **distinct from the flag allocations above — not V232**, which is the
   compaction flag).

**Provider-uniform (the mandate, made concrete):** the usage schema is identical for all four providers,
populated per each provider's reality — `cacheRead` for **all** (uniform metadata key, already so per §7b);
`cacheWrite` where the provider reports it (Anthropic today); `reasoningTokens` where reported (OpenAI
o-series / Gemini thinking / DeepSeek reasoner). No provider is left without telemetry, and cache-hit-rate
is computable **per provider**.
**Files:** `cost/RunCostTracker.java`, the `RunEvent`/SSE emitter (`integration/RunOrchestrator` +
`harness/event/`), `report/ValidationReportService` (rollup), `clients/core` event types, the telemetry
migration.
**Flag:** additive — no flag needed (or `telemetry.step-usage.enabled` if you want a kill switch).
**Tests:** per-provider usage extraction incl. cache+reasoning; per-step rollup sums to run total; SSE
event shape pinned; event-log replay carries the new fields.
**Done when:** a single run yields a per-step timeline with cache read/write tokens visible, and
cache-hit-rate is computable per role from one API/SSE payload.

---

### W1 — Coding-quality wins  ▸ high ROI, mostly independent of W0
*My Tier 1. Impact: coding-quality. Effort: B1 = M; C5/B4/C2 = S each.*

**W1.1 — B1: auto-inject build/compiler diagnostics into the edit result  ⭐ the standout**
After a successful `editFile`/`writeFile` on a compilable source file, run a **fast scoped** check and
append the first N diagnostics (`file:line: message`) to the tool-result envelope the model sees.
- First cut: Java-only — single-file/`module` `javac` (or reuse the `BuildProjectTool` docker-exec
  routing + `MavenDependencyValidator`) for syntax/type errors. `EditFileTool` already holds
  `ExecutionContext` (workspaceRoot/runId/taskId).
- Bench-aware: respect `ProgramBench`/`Swebench` container routing; skip when no build resolver or
  non-Java; bound latency (timeout) + diagnostic count.
- Files: [`tool/EditFileTool.java`](src/main/java/com/navneet/executespec/tool/EditFileTool.java),
  [`tool/WriteFileTool.java`](src/main/java/com/navneet/executespec/tool/WriteFileTool.java), a small
  `EditDiagnosticsService` (or `BuildProjectTool` internals), V233 flag `worker.edit-diagnostics.enabled`
  (default OFF).
- Tests: edit introducing a type error → diagnostics appended; clean edit → none; non-Java/no-resolver →
  skipped (envelope unchanged).
- **Risk gate:** flag-gated; prove on the round-2 SWE-bench eval (the owed no-regression gate from
  RL-2026-05-061) before considering default-on. This is the one place you can decisively beat vix —
  vix and the external audit only have *manual* diagnostics.

**W1.2 — C5: generalize the anti-over-engineering directives to greenfield**
Add the 6 missing lines (listed in §7b "C5") to the **shared** `SequentialExecutor.SYSTEM_PROMPT_BASE`
(NOT a `provider-*.md` fragment) so they apply to feature/scaffold tasks on **every** provider, not just
the bug-fix path. Files:
[`harness/SequentialExecutor.java`](src/main/java/com/navneet/executespec/harness/SequentialExecutor.java).
No flag (prompt change) — but **measure 0 regression** on each provider's passing set before/after.

**W1.3 — B4: centralized tool-arg schema validation**
Validate tool-call args against `Tool.inputSchema()` at the dispatch chokepoint
([`tool/ToolRegistry.java`](src/main/java/com/navneet/executespec/tool/ToolRegistry.java) `~175-185` /
[`engine/WorkerAgent.java`](src/main/java/com/navneet/executespec/engine/WorkerAgent.java) `~1416-1424`)
before `tool.execute`; missing/mistyped required field → `INVALID_ARGS` naming the field. Use a JSON
Schema validator (e.g. networknt). Keep advisory fields (`reason`) non-blocking. Tests: missing required
field → clear error; missing advisory → allowed.

**W1.4 — C2: fix the completeTask-early dangling tool_result**
In `WorkerAgent.java:776-796`, before the completeTask early-return, emit synthetic placeholder
`ToolResponse`s ("superseded by completeTask") for any tool_calls after the completeTask index so the
persisted history is well-formed. ~5 lines. Test pins a `[completeTask, readFile]` batch → fully paired.

**W1.5 — B5: steer off shell read/search + close the `sed -i` gate bypass**
Prompt: prefer `searchFiles`/`readFile`/`editFile` over `grep`/`cat`/`sed` in `SYSTEM_PROMPT_BASE` +
`patch-agent.md`. Policy: block in-place mutation (`sed -i`, `perl -i`, redirection into tracked source)
in [`tool/ShellSandboxPolicy.java`](src/main/java/com/navneet/executespec/tool/ShellSandboxPolicy.java)
so mutation must go through `editFile` (and its read-gate). *(Chip `task_4d6c4b9c` already tracks the
`sed -i` half.)* Tests: `sed -i` on a source file rejected; read-only `sed 's/x/y/' f` allowed.

---

### W2 — Cache & context compaction  ▸ the dominant token lever; approval-gated, its own multi-phase track
*Owns external **P1** (deterministic-first compaction) + my **C4** + the deterministic-reducer idea +
**A2**. Impact: cost (high). Effort: L (multi-phase). **Status: the compaction sub-plan is
founder-pending — see its §12 confirmation gate + §10 open decisions; do not implement until approved.***

**W2.1 — Worker context compaction.**
The full spec is **[Appendix A](#appendix-a--worker-context-compaction-execution-plan-inlined)** below
(inlined verbatim from the now-**superseded** standalone `worker-context-compaction-execution-plan-2026-06-06.md`,
with its `V231` renumbered to **V232**). It encodes external-audit **P1** ("deterministic-first; LLM
compaction only as fallback") and is grounded in the exact worker seams. In brief:
- **One master flag** `worker.compaction.enabled` (**V232**, default OFF, operator rollback), with legacy
  `worker.tool-compactor.enabled` (V216) + `worker.history-compaction.enabled` (V222) demoted to hidden
  sub-gates. `WorkerCompactionPolicy` centralizes every decision (no I/O).
- **Deterministic-first** at emit-time (`WorkerAgent.maybeCompact`): a `ToolOutputReducerRouter` with
  `ShellLogReducer` / `SearchResultReducer` / `ListFilesReducer` / `DiffReducer` — each preserves gate
  markers (`\nexit=0\n`), skips `runTests` + the latest result + fresh reads, shrink-gated, fail-open. The
  LLM `ToolOutputCompactor` is demoted to an opt-in fallback (it's expensive while LIGHTWEIGHT=flagship, §11).
- **Spill-and-retrieve (vix-parallel):** a reduced output becomes a terse marker; the full original goes to
  `CompactContextStore` (Redis, 24h, run/task-scoped), retrievable on demand via a new read-only
  `RetrieveContextTool` (bound only when the flag is ON and the run is non-remote). This is the Redis+hash
  version of vix's "spill to a file the model can re-read" — and it's cache-safe (it shrinks what's appended).
- **Dominant levers:** history fold (rewire `WorkerHistoryCompactor`) + a net-new **read-lifecycle** pass
  (stale-read replacement; deterministic; ships last).
- **7 phases (0–6)** under the master flag default OFF, with a Phase-0 baseline and a Phase-6
  eval/acceptance bar before any flip-to-ON (a separate follow-up migration).

**Two grafts from this doc into the sub-plan:**
1. **C4 (cache-safe history fold).** The sub-plan's §6.1 still folds the task into a summary at position 1
   — the cache-busting rewrite my C4 flagged. **Make it append-only:** keep `messages[0]`+`[1]` verbatim
   and insert the summary *after* the preserved head, so the Anthropic prefix cache + Gemini
   `cachedContents` key survive a fold. Fold this into the sub-plan's **Phase 5**.
2. **W0 telemetry satisfies the sub-plan's "per-strategy metrics present" acceptance criterion** — emit the
   reducer/compactor strategy + bytes-saved through the W0 step-usage events instead of bespoke counters.
   So **W0 should land before the sub-plan's Phase 5–6** (and W0's Phase-0 baseline numbers feed the
   sub-plan's non-inferiority margin).

**W2.2 — Maximize cache hit-rate per provider (provider-agnostic GOAL; tactics differ by API).**
Compaction *shrinks* the prompt; this workstream raises the cache **hit-rate** on whatever prompt remains —
for **every** provider, not just Anthropic. Gate the work on W0's per-provider `cache_read` deltas: do the
provider whose telemetry shows the worst hit-rate first.
- **Shared, provider-neutral levers (apply to all four):** keep the prefix byte-stable (A1, done), keep it
  stable *through* compaction (the W2.1 C4 graft — benefits Anthropic explicit, OpenAI implicit, Gemini,
  and DeepSeek caches alike), and ensure the cacheable prefix exceeds **each** provider's minimum cacheable
  size.
- **Anthropic tactic:** multi-`cache_control` breakpoints (≥2: frozen tools+system + rolling near tail;
  mind the 4-breakpoint cap + 20-block lookback) — likely via raw `AnthropicApi` (the
  `AnthropicForcedToolClient` pattern) since the Spring AI `CONVERSATION_HISTORY` auto-strategy can't
  express it. Flag **V234**, in [`agent/LlmRouter.java`](src/main/java/com/navneet/executespec/agent/LlmRouter.java) `callAnthropic`.
- **OpenAI tactic:** implicit prefix caching — *no breakpoints to place*; the lever is purely prefix
  stability + clearing the ~1024-token minimum. Confirm the Responses-API path preserves the stable prefix;
  verify `cached_tokens` climbs via W0.
- **Gemini tactic:** explicit `cachedContents` is already in place (A4/V225); ensure the prefix clears the
  per-model floor and apply the same prefix discipline; verify `cachedContentTokenCount` via W0.
- **DeepSeek/OpenRouter tactic:** DeepSeek's automatic context caching benefits from the same stable
  prefix; no breakpoint API to manage — verify `cache_read` via W0.
- **Done when:** each provider's `cache_read` hit-rate improves on a fixed transcript (per W0), with no
  provider regressed.

---

### W3 — Code intelligence  ▸ benchmark + large-repo win, flag-gated
*Absorbs external P2 + P3 (re-ranked up from my B6). Impact: cost + coding-quality. Effort: outline = M; LSP = L.*

**W3.1 — `readCodeOutline` (minified read-only)** — new worker tool returning imports / classes /
functions / signatures / line-spans / omitted-body markers. Java + TS/JS + Python + Go. Read-only only
(defer minified *write*). Java via JavaParser/JDT; others via tree-sitter. New `tool/ReadCodeOutlineTool`
+ registration + schema; tests against real fixtures.
**W3.2 — LSP tools (Java first)** — `workspaceSymbols`/`goToDefinition`/`findReferences`/
`documentSymbols`/`diagnostics` backed by a `jdtls` process pool (lazy start, failure cooldown,
auto-restart). Flag-gated. Bigger lift; strongest on benchmark localization + large customer repos.

---

### W4 — Harness depth
*My C1 + external P6 + C3/P5. Impact: latency + Terminal-Bench + diagnosis. Effort: C1 = M; P6 = M; C3 = M–L.*

**W4.1 — C1: read-only-batch tool parallelism** — in the `WorkerAgent` dispatch loop (`646-843`):
classify calls, run the read-only set (`readFile`/`searchFiles`/`listFiles`/`gitStatus`/`gitDiff`)
concurrently on **virtual threads** (you already use Loom), serialize writes/`completeTask`/
`buildProject`/`shell`, and replay `metrics`/SSE/gate-state in deterministic order. Tests: parallel
reads preserve result order; writes serialized.
**W4.2 — P6: background command jobs** — `shell` background mode returning
`{jobId,pid/pgid,log,poll,tail,kill,rc}` for Terminal-Bench servers/long builds; remote-dispatcher
parity. Files: `tool/ShellTool.java` + a `JobRegistry`.
**W4.3 — C3/P5: role-isolated subagents (optional, weigh the trade)** — add `bindForReviewer(ctx)` to
`ToolRegistry` (deny-set primitive already exists at `ToolRegistry.java:65`) to strip write/edit/delete
on read-only review passes, and a `verifier` role (run+inspect, no edits). **Weigh against the deliberate
"don't over-fix" bias** (§7b C3b) — this is a redesign (read-only review + separate fix phase), possibly
not worth it; revisit only if reviewer/worker behavior-blending shows up in failure analysis.

---

### W5 — Clients over the W0 contract (CLI + VS Code)
*External P0.5 + P9 + P10. Impact: strategic + consistency. Effort: M (both partly in flight). Gated by W0.*

**Principle (external P0.5 = vix's headless harness):** web, **CLI**, VS Code, the benchmark harness, and
Terminal-Bench all consume the **same** W0 event stream — one run timeline, one per-provider cache/cost
rollup, one tool-event shape — so behavior never drifts between surfaces. Do **not** wrap ad-hoc HTTP per
client; thin clients over the shared contract only. No client becomes a second execution engine or bypasses
`ToolRegistry` / worker policy / deny-lists. This is *why W0 is urgent*: the CLI is being built **now**, and
if it hardens around a thin event shape you pay to retrofit it later.

**W5.1 — CLI (`@executespec/cli`, `executespec`/`es`).**
*Current state (per CLAUDE.md, RL-2026-05-043/052):* already landed + building locally — auth + run
submit/watch/list/view/approve/reject/cancel/report + experimental `run local` (local-hands), over
`clients/core` (typed REST/SSE client, PAT token store, SSE consumer). So this is **enrich + extend**, not
greenfield.
- **Thin client of the W0 contract:** render the W0 typed events (step timeline, tool calls/results,
  per-step usage) instead of scraping logs. The *same* `clients/core` consumer feeds CLI + VS Code +
  benchmark collectors — one parser, no drift.
- **Command surface (extend what exists):** `run` (`--provider/--model/--prompt/--repo`), `status`,
  `stream <id> --format text|json|ndjson`, `cancel`, `resume`, `approve`, `answer`, `artifacts`,
  `logs [--task T3] [--tool buildProject]`, **`cache <id>`** (per-provider cache read/write savings —
  only possible because W0 telemetry is provider-uniform), and `tb run …` for Terminal-Bench.
- **Output modes (vix `headless.go` parallel):** human text (interactive) · **NDJSON/JSON** (CI + benchmark
  collectors) · compact table (status). Auto-approve / auto-answer are **explicit** flags; a usage/cache/
  cost summary is emitted at stream end.
- **Run modes:** remote/cloud submission · **local-hands** (`run local` — brain in cloud, tools on the
  user's machine via `RemoteToolDispatcher`; the vix one-shot-tool-RPC parallel) · attach to a running run
  · replay a saved event log.
- **Provider-agnostic (the mandate):** `--provider`/`--model` selects any of the four; `cache`/`logs`/
  `stream`/`status` behave identically for every provider — no Anthropic-only path. `executespec cache`
  surfacing per-provider savings is the most visible payoff of W0's provider-uniform telemetry.
- **Terminal-Bench unification:** `tb run` emits the **same** W0 stream → benchmark result artifacts become
  a *filter over the run timeline*, not a separate log scrape. This closes the external audit's "don't
  scrape worker logs" point and makes per-provider benchmark comparisons clean and apples-to-apples.

**Robustness contract (lessons from vix's `cmd/vix/main.go` + `internal/headless/headless.go`, code-verified).**
vix's robustness centers on a *local daemon* (auto-spawn, same-build pin, reconnect); ExecuteSpec's backend
is the *cloud*, so those map to PAT auth + SSE reconnect/resume + server-side cancel (and, for `run local`,
a local tool runtime). The transferable rules:
- **stdout = result, stderr = progress/diagnostics.** vix prints tool/step progress + errors to **stderr**
  and only the final result to **stdout** (`headless.go:160-200,212`), so `executespec run … | jq` just
  works. *This is the #1 robust-CLI rule — verify the current CLI honors it.*
- **Exit codes are a contract.** 0 = success; non-zero on CLI error (vix `os.Exit(1)`); **130 on SIGINT**
  (`headless.go:80`, the 128+SIGINT convention). Map the **run outcome** to the code too — FAILED /
  ITERATION_LIMIT / BUDGET_EXCEEDED → non-zero (+ `isError:true` in the JSON result) so CI can branch.
  Document the table.
- **Signals cancel the *server* run, not just the local process.** vix's SIGINT/SIGTERM handler sends
  `cancel`+`close` to the daemon before exiting (`headless.go:74-82`). ExecuteSpec analog: SIGINT →
  `DELETE /api/runs/{id}` → exit 130, so Ctrl-C never orphans a **billed** cloud run.
- **SSE reconnect + resume + attach** (cloud analog of vix's `attemptReconnect`): on a network blip,
  reconnect with a fresh scoped SSE token and **resume from the last event id via event-log replay**
  (`/api/runs/{id}/event-log`, 24h Redis). `executespec attach/stream <run-id>` re-attaches to a *running*
  run. Long cloud runs make this mandatory; `clients/core` already has "SSE consumer with scoped-token
  reconnect" to build on.
- **Fail fast, up front.** Validate flags → clear stderr + exit 1 (vix `main.go:130-143`); **pre-flight the
  PAT** before submitting (vix pre-flights the provider key, `main.go:169-182`) with an actionable message
  (`run \`executespec auth login\` or set EXECUTESPEC_TOKEN`).
- **stdin prompt** — `--prompt -` reads the spec from stdin (vix `main.go:146-158`) for piping/CI.
- **Structured final-result envelope** (json/stream-json) — `{type,result,runId,isError,numTurns,
  durationMs,usage{input,output,cacheRead,cacheWrite,reasoning}(per provider),steps[]}` (vix
  `headless.go:33-66`), emitted at stream end — the W0 contract terminating in one parseable object.
- **`--version`** stamped at build (vix `main.go:78-81`); **crash capture** (vix recovers panics →
  telemetry → re-raise, `main.go:221-227`); **telemetry opt-out**.
- **Idempotent submit** (cloud extra, beyond vix): retry the submit POST with backoff + an idempotency key
  so a flaky network can't double-submit (double-charge) a run.

*Sequencing note:* the **robustness layer** (stdout/stderr split, exit-code contract, signal→server-cancel,
SSE reconnect/resume, auth pre-flight, stdin) is largely **client-side and independent of W0** — it can
start **now** against the existing event stream. Only the **observability surfaces** (per-step cache/cost
panel, step timeline) need W0's enriched contract.

**W5.1 AUDIT — current `clients/cli` + `clients/core` vs. the contract (code-verified 2026-06-07).**
Headline: the **core plumbing is strong** (SSE reconnect/resume/token-refresh is *ahead* of vix — verified
end-to-end against `SseBroadcaster`); the gaps are concentrated in the **CLI behavior layer** + **two
money/automation-safety holes**. So this is "harden the behavior layer," not "build a CLI."

| # | Contract item | Verdict | Evidence / gap |
|---|---|---|---|
| 1 | stdout=result / stderr=progress | ❌ **MISSING (inverted)** | progress + streamed events + per-event JSON all go to stdout (`cli/run.ts:317,323,329,337,354`; `cli/ui.ts:19,27`) → `run … \| jq` is polluted. **#1 fix, mechanical.** |
| 2 | exit codes a contract | 🟡 PARTIAL | 0/1/2 honored (`cli/index.ts:115`); but `PARTIAL` status (absorbs iter-limit/budget/tests-failed) → **exit 0** (`cli/run.ts:160` maps only FAILED/CANCELLED); no 130 on SIGINT |
| 3 | SIGINT cancels SERVER run | ❌ **MISSING (wiring)** | no SIGINT handler on the watch path → Ctrl-C orphans a running run. Core primitive **exists** (`core/api.ts:59` `cancel`→`DELETE /runs/{id}`) + wired to the explicit `run cancel` verb — just not to Ctrl-C |
| 4 | SSE reconnect + resume + attach | ✅ **PRESENT (ahead)**; attach 🟡 | reconnect + resume via `Last-Event-ID` + per-reconnect scoped-token refresh (`core/sse.ts:31,34,39,53`), **verified server-side** (`SseBroadcaster` emits `id`, replays from `sse:replay:{runId}`). Gap: no `eventLog(runId)` in `api.ts` → attach to an already-running run only gets forward events, not from-start backfill (endpoint exists: `/runs/{id}/event-log`) |
| 5 | output formats | 🟡 PARTIAL | only `--json` boolean (`cli/args.ts:11`); no `--format text\|json\|stream-json`; `--json` is pretty-printed multi-line (not line-delimited NDJSON) and interleaves progress + final |
| 6 | structured final-result envelope | ❌ MISSING | `--json` dumps the raw `RunDetail` (`cli/run.ts:159,354`); no `{result,isError,numTurns,durationMs,usage{…},steps[]}` |
| 7 | fail-fast + PAT pre-flight | 🟡 PARTIAL | positional usage-checks exit 2; but `run submit` never checks the token before submitting (`cli/run.ts:89`) → raw 401; enum flags (`--provider/--mode`) cast unvalidated (`cli/run.ts:105-109`). REPL + `doctor` DO gate on token — pattern exists, not on submit |
| 8 | stdin prompt | ❌ MISSING | spec is positional-only (`cli/run.ts:90`); no `run submit -` / stdin read |
| 9 | http retry/backoff · timeout · idempotency · errors | 🟡 PARTIAL | retry/backoff ✅ (`core/http.ts:82-122`, honors `Retry-After`, transient-only) + typed errors ✅ (`core/errors.ts`); **request timeout ❌** (unary `request()` has no default `AbortController` deadline — a half-open socket hangs forever); **idempotency key ❌** on `POST /runs` (`core/api.ts:43`) |
| 10 | `--version` + crash capture + telemetry | 🟡 PARTIAL | `--version` ✅ (`cli/index.ts:54`, hardcoded `0.1.0`, no `-v`); top-level `.catch` ✅ but no `uncaughtException`/`unhandledRejection`; no telemetry (n/a) |
| 11 | typed event/usage contract | 🟡 PARTIAL | run-event `payload` is a pipe-delimited **string** (`core/types.ts:200`), not a typed union (the *chat* stream IS well-typed); `RunCost` has **no cache/reasoning tokens, no per-provider** breakdown (← W0 enriches this) |

**Prioritized punch-list (ROI ÷ effort):**
- **P0 — pipeline & money safety (cheap, do first):**
  1. **stdout/stderr split** — route progress/events through `errln`; only the final result on stdout. (#1)
  2. **SIGINT → `cancel(runId)` then exit 130** in `watchRun` — the cancel primitive already exists; just wire it. (#3)
  3. **Idempotency key on `submitRun`** + server dedupe — `http.ts` retries 5xx, so a post-create proxy 5xx can **duplicate the run** (and double-charge a plan-gate-*bypassing* PROTOTYPE run; PRODUCTION charges at approval so its blast radius is duplicate AWAITING_APPROVAL runs, not a double-charge — but still fix it). (#9)
  4. **PAT pre-flight on `run submit`** — token presence (+ ideally `me()` validity) before submit; actionable message. (#7)
- **P1 — automation correctness (cheap):** 5. map `PARTIAL`+`statusReason` → non-zero exit (#2) · 6. default per-request timeout in `http.ts` (#9) · 7. real `--format text|json|stream-json` + compact NDJSON (#5) · 8. `run submit -` stdin (#8).
- **P2 — completeness:** 9. structured final-result envelope (ties to W0, per-provider usage incl. cache) (#6) · 10. expose `eventLog(runId)` for true from-start attach (#4) · 11. version from `package.json` + `uncaughtException`/`unhandledRejection` handlers (#10) · 12. enum-flag validation (#7).
- **Already solid — don't touch:** SSE reconnect/resume/token-refresh (#4), HTTP retry/backoff + error taxonomy (#9), local-runtime **file-arg** path safety (`core/tools/pathSafety.ts`).
- **Security follow-up (separate from robustness):** the `run local` tool runtime executes **arbitrary shell by default** (`core/tools/runtime.ts:364` `spawn(cmd,{shell:true})`; `allowCommand` defaults to allow; the permission-prompt capability is still `planned`). Path safety guards file-tool *args*, not shell command *contents*. `run local` is currently gated off in the CLI — gate it behind the permission layer before it ships.

**Implementation status — P0 + P1 + P2 landed (2026-06-07, uncommitted on `docs/handoff-cleanup-reprioritize`; RL-2026-06-005).** Both workspaces compile; **15 core + 11 cli tests green** (7 new). Docs refreshed (`clients/cli` CODEMAP + ARCHITECTURE + reason-log).
- ✅ **#1 stdout/stderr split** — all progress/streamed-event/confirmation output moved to `errln` (`cli/run.ts` `renderEvent` + submit/handleGate/decide/cancel/retry); only the final result + `--json` machine stream stay on stdout.
- ✅ **#3 SIGINT→server-cancel** — `watchRun` installs SIGINT/SIGTERM → `cancel(runId)` → `process.exit(130)`; listeners removed in `finally`.
- ✅ **#2 exit-code contract** — new exported `exitForStatus(status, statusReason)` (0 / 1 / 3) wired into `watchRun`; pinned by `cli/test/exit-codes.test.mjs`.
- ✅ **#9a idempotency key** — `core/api.ts submitRun` sends a `crypto.randomUUID()` `Idempotency-Key`; `core/http.ts` gained an `opts.headers` carried verbatim across retries (so it's stable per logical submit); pinned by `core/test/submit-idempotency.test.mjs`. **Server-side dedupe on the header is the owed backend follow-up.**
- ✅ **#7 PAT pre-flight** — `run submit` checks `client.tokens.get()` before submitting → actionable message instead of a raw 401.
- ✅ **#10 crash capture** — `cli/index.ts` adds `uncaughtException`/`unhandledRejection` handlers (clean message + non-zero exit).
- ✅ **#9b default per-request HTTP timeout** (`core/http.ts`, 60s, `AbortSignal.any`+`timeout`); **#5 `--format text|json|stream-json`** (NDJSON, timestamped); **#8 `run submit -` stdin**; **#6 structured result envelope** (`resultEnvelope`); **#4 `run attach` + `eventLog(runId)`** backfill; **enum-flag validation**; **`--version` from `package.json`**.
- ✅ **Server-side `Idempotency-Key` dedupe LANDED** — `run/RunIdempotencyService` (Redis, 24h, user-scoped, fail-open) + `RunController.submit` reads the header and returns the original run on a repeat, **before** the quota/usage increment so a retry never double-charges. Unit-tested.
- ✅ **Local-exec permission gate LANDED** (RL-2026-06-006) — `ToolRuntime` is now fail-closed: `shell`/`buildProject`/`runTests`/`gitCommit` are denied without an approval policy; added `createInteractivePermissions` (per-command y/N/a + session memory) + `allowAllPermissions` (trusted opt-in). Final CLI prompt wiring lands with Phase-3 `run local` activation.
- ⏳ **Remaining:** **W0-fed per-step cache/usage** in the result envelope (lands with W0).

**W5.2 — VS Code extension** (external P10) — rich client over the same contract: run-timeline view, task
tree, tool-event inspector, diff view, background-job panel, **per-provider** cache/cost panel, artifact
browser; respect VS Code workspace-trust + secret storage; never bypass backend tool policy.
- ✅ **Robustness parity landed (RL-2026-06-007):** inherits the core hardening (idempotency, HTTP
  timeout, fail-closed permission gate) + PAT pre-flight + SSE reconnect/resume; added **Cancel Run**
  (`api.cancel`, inline Runs-view action + palette, confirm modal) + a **cancellable watch**
  (`withProgress`). Typecheck + esbuild bundle build clean locally.
- ⏳ The richer surfaces above (timeline / inspector / diff / cache panel) are the **Shape B** feature
  track (ADR-0001), gated on W0's enriched event contract — not a robustness gap.

---

### Sequencing & milestones

| Milestone | Workstreams | Rationale |
|---|---|---|
| **M1** (now) | **W0** (contract+telemetry) ‖ **W1.2/W1.3/W1.4/W1.5** (cheap quality cluster, parallel) | W0 gates measurement; the cheap cluster is independent and low-risk |
| **M2** | **W1.1 (B1)** + measure on round-2 SWE-bench | the standout differentiator; W0 makes its effect measurable |
| **M3 (track A)** | **W2.1 compaction** sub-plan Phases 0–6 *(approval-gated)* ▸ then **W2.2 A2** (conditional) | the dominant token lever; deterministic-first; its Phase-0 baseline + Phase-6 eval consume W0 telemetry → starts once W0 lands, then runs as its own multi-phase track in parallel with M4/M5 |
| **M4 (track B)** | **W3** (outline → LSP) | code-intel; flag-gated, independent of compaction |
| **M5 (track B)** | **W4** (C1 → P6 → C3?) | harness depth; P6 unblocks Terminal-Bench long tasks |
| **M6** | **W5** | clients consume the now-stable W0 contract |

**Critical path:** **W0 is the single gate** — W2 (compaction baseline/acceptance + A2 measurement) and
W5 (clients) both depend on it. **W2.1 is additionally gated on founder confirmation** (the sub-plan's §12)
and is its own multi-phase track that can run in parallel with the code-intel/harness tracks (M4/M5). W1,
W3, and W4.1/W4.2 are independent and interleave; B1 (W1.1) doesn't strictly need W0 but wants it to measure.

### Definition of done (per workstream)
W0: per-step cache/usage visible in one SSE/API/report payload + pinned tests. W1: each sub-item
flag-gated (where behavioral) + tests + 0-regression on eval (B1, C5). W2: cache_read hit-rate improves
on a fixed transcript with no correctness regression. W3: outline/LSP tools return correct results on
fixtures behind a flag. W4: parallel dispatch deterministic + background jobs controllable. W5: CLI/VS
Code render the same timeline + cache rollup as web from the shared contract.

### Risks / no-regression gates
- **Provider-agnostic gate (the mandate):** every new tool / behavioral change is eval'd across
  **Anthropic + OpenAI + Gemini + DeepSeek** before any default-on flip — not Anthropic-only. New tools
  (`RetrieveContextTool`, `readCodeOutline`, LSP tools, background-job tool) get a per-provider schema-
  sensitivity check (RL-2026-05-061: a tool in native-DeepSeek's schema caused reasoning-only stalls).
- B1 and A2 are **flag-gated**; C5 is a prompt change measured for **0 regression on each provider's
  passing set**. Prod defaults stay OFF until the round-2 eval clears.
- **W2.1 compaction follows its sub-plan's own acceptance bar** (§8 of that doc): OFF byte-compatible · ON
  reduces worker input tokens ≥25% on MEDIUM/HIGH debug-heavy evals · verifiedScore within the Phase-0
  non-inferiority band · no fresh/latest read compacted · no run failed by compaction. Flip to default-ON
  only via a **separate follow-up migration** after Phase 6 — and it is **founder-approval-gated** before
  any code is written.
- A2 only if telemetry proves single-breakpoint misses (don't pre-optimize).
- C3 reviewer change is reversible and weighed against a deliberate design bias — treat as optional.
- Every change carries doc-refresh + a Flyway flag-seed migration + local `mvn verify` before push.

---

## Appendix A — Worker context compaction execution plan (inlined)

> Inlined verbatim from the now-**superseded** standalone `worker-context-compaction-execution-plan-2026-06-06.md` (2026-06-06), with `V231`→`V232` renumbered (V231 shipped as `worker.critic-pass.enabled`). **Status: founder-approval-gated** — do not implement until approved (see §A.12 below). This is §W2.1 above's authoritative spec.

### 0. What changed from the proposal (decisions locked in)

| # | Proposal said | This plan does | Why (verified) |
|---|---------------|----------------|----------------|
| D1 | `worker.compaction.enabled = true` (default ON) | **Seed default OFF** (V232). Flip to ON only via a follow-up migration after Phase 6 eval clears the acceptance bar. | Every sibling worker flag (V216/217/219/220/221/222) is seeded `false` pending eval; default-ON at introduction would turn on un-validated, flagship-priced LLM compaction before the safe reducers exist. |
| D2 | §6.5 "use the existing read/edit state" | **Read-lifecycle is a net-new history-correlation pass** (explicit algorithm in §6). Shipped LAST (Phase 4), stale-only, superseded OFF. | `ReadFileStateStore` keeps only the *latest* SHA per file ([ReadFileStateStore.java:57-65,97-100](../../src/main/java/com/navneet/executespec/tool/ReadFileStateStore.java#L57)) — no per-message timeline, no "superseded" notion. The edit gate is disk-vs-ledger ([EditFileTool.java:141-159](../../src/main/java/com/navneet/executespec/tool/EditFileTool.java#L141)), independent of history. |
| D3 | `retrieveContext` × remote-tool mode unaddressed | **Do not bind `retrieveContext` on remote runs.** | The worker's remote branch returns *before* `maybeCompact` ([WorkerAgent.java:1393-1395](../../src/main/java/com/navneet/executespec/engine/WorkerAgent.java#L1393)) — so no compact-store entries exist on remote runs; a bound retrieval tool would be wrongly proxied to the Harbor client and always miss. |
| D4 | `retrieveContext` × read-before-write gate unspecified | Retrieved content is **explicitly not an edit anchor**; the tool output carries a "re-read before editing" note. | Edit gate validates disk-vs-ledger, so a retrieved (possibly stale) blob can't satisfy `editFile`; STALE forces a re-read regardless ([EditFileTool.java:151-159](../../src/main/java/com/navneet/executespec/tool/EditFileTool.java#L151)). |
| D5 | New `Tool` bean silently widens the default tool list | `bindForWorker` **filters** `retrieveContext` when the master flag is OFF or the run is remote; pinned by an OFF-mode byte-compat test. | `@Component implements Tool` is auto-collected into `allTools` and bound on every run ([ToolRegistry.java:96-104](../../src/main/java/com/navneet/executespec/tool/ToolRegistry.java#L96)). |
| D6 | "Build/test log reducer" is the lead reducer (§6.1) | **Reprioritized.** `runTests` is *excluded* (gate-critical + already structured-small); `buildProject` reduces only its `output:` body and preserves the header verbatim; the high-value/safe single-call targets are `shell` / `searchFiles` / `listFiles` / old `gitDiff`. History compaction + read-lifecycle are the dominant token levers. | Every tool output except `readFile` (60k) is already budget-capped: `shell`/`buildProject` at 6k via `ToolOutput.budget` ([ToolOutput.java:28](../../src/main/java/com/navneet/executespec/tool/ToolOutput.java#L28)), `listFiles` at 500 entries, `searchFiles` paged 50/200, `runTests` a small JSON envelope. The completeTask gates inspect `buildProject`/`runTests` envelopes by exact substring (§3). |
| D7 | LLM `ToolOutputCompactor` is the tool-output path | **Demote it to an opt-in fallback** behind its existing sub-flag (default OFF). Default-ON behavior is deterministic-only (zero added LLM tokens). | LIGHTWEIGHT tier resolves to flagship `claude-sonnet-4-6` / `gpt-5.4` in this checkout ([application.yml:86,94](../../src/main/resources/application.yml#L86) via [ModelResolver.java:60-70](../../src/main/java/com/navneet/executespec/router/ModelResolver.java#L60)) — LLM compaction is not cheap here. (Known temporary tier state, §11.) |

---

### 1. Verified integration map (the seams this plan touches)

All line numbers are against the current worktree (head **V231**, so the new flag is **V232**).

| Seam | File:line | Role in this plan |
|------|-----------|-------------------|
| Worker loop, full history kept | [`engine/WorkerAgent.java`](../../src/main/java/com/navneet/executespec/engine/WorkerAgent.java) | History list: `[0]=SystemMessage`, `[1]=UserMessage(task)`, then `Assistant(toolCalls)`→`ToolResponse` pairs. |
| Tool-output compaction hook | `WorkerAgent.java:1413` (`maybeCompact` at :1435) | Wraps `ToolResult.Ok.content()` before `okEnvelope`. **The single insertion point for the reducer router.** |
| History-compaction hook | `WorkerAgent.java:489-493` | Top-of-loop, before building the request, gated by `lastInputTokens`. **The insertion point for read-lifecycle + history fold.** |
| Latest-result protection | `WorkerAgent.java:643-646` | `isImmediatelyPriorToolCall = (callIdx == lastIdx)` — already passed into `maybeCompact`. |
| **Build gate marker** | `WorkerAgent.java:801-806` | `envelope.contains("\\nexit=0\\n")` → `lastBuildPassIter`. **Reducer MUST preserve `\nexit=0\n`.** |
| **Test gate marker** | `WorkerAgent.java:788-795` | needs `\"failed\":[]` + `\"errored\":[]` + `\"exit_code\":0`. **`runTests` excluded from reducers.** |
| Remote-mode early return | `WorkerAgent.java:1393-1395` | Returns before `maybeCompact`; reducers naturally skipped on TB runs. |
| Worker tool binding | [`tool/ToolRegistry.java:96-104`](../../src/main/java/com/navneet/executespec/tool/ToolRegistry.java#L96) (`bindForWorker`) + `:114` (`isRemoteRun`) | Gate `retrieveContext` binding here. |
| Redis store template | [`context/ContextStore.java`](../../src/main/java/com/navneet/executespec/context/ContextStore.java) (`StringRedisTemplate` optional, `keyFor`, `deleteAll` at :150) | `CompactContextStore` is modeled on this 1:1. |
| Run-end Redis cleanup | [`harness/SequentialExecutor.java:374-375`](../../src/main/java/com/navneet/executespec/harness/SequentialExecutor.java#L374) (`contextStore.deleteAll(runId)`) | Add `compactContextStore.deleteAll(runId)` beside it. |
| Flag service | [`config/featureflag/FeatureFlagService.java:44`](../../src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java#L44) (`isEnabled`) | In-memory cached → cheap per-iteration. |
| Edit gate (read-before-write) | [`tool/EditFileTool.java:141-159`](../../src/main/java/com/navneet/executespec/tool/EditFileTool.java#L141) | Disk-vs-ledger; read-lifecycle must never compact a *fresh* read. |
| readFile caps + truncation | [`tool/ReadFileTool.java:52-55`](../../src/main/java/com/navneet/executespec/tool/ReadFileTool.java#L52) | 60k chars / 2000 lines, head-first; the only unbounded single output. |

`ExecutionContext` is `(runId, taskId, workspaceRoot)` — **no provider** ([ExecutionContext.java:18](../../src/main/java/com/navneet/executespec/tool/ExecutionContext.java#L18)). So `retrieveContext.execute(args, ctx)` has full run/task scope; the *policy* threads `provider` from `WorkerTaskRequest.provider()` exactly as the existing compactors do.

---

### 2. Target architecture (one master flag, deterministic-first, reversible)

```
                       worker.compaction.enabled  (V232, default OFF — operator rollback switch)
                                     │
        ┌────────────────────────────┼───────────────────────────────┐
        │                            │                                │
  emit-time (per tool output)   pre-call (per iteration)        tool binding
  WorkerAgent.maybeCompact      WorkerAgent top-of-loop         ToolRegistry.bindForWorker
        │                            │                                │
  ToolOutputReducerRouter      ReadLifecycleCompactor (Phase 4)   bind retrieveContext
   ├─ deterministic reducer       + WorkerHistoryCompactor        iff flag ON && !remote
   ├─ shrink gate                  (run AFTER reducers — already
   ├─ CompactContextStore.put       satisfied: reducers ran at
   └─ marker output                 emit-time)
        │
   (opt-in LLM fallback behind worker.tool-compactor.enabled, default OFF)

           retrieveContext(hash[,query,maxChars])  ──reads──▶  CompactContextStore (Redis, 24h)
```

**Staging sub-gates** (temporary, default OFF, folded into the master after their phase clears eval —
not permanent config surface): `worker.tool-compactor.enabled` (LLM fallback, existing V216),
`worker.history-compaction.enabled` (history fold, existing V222). This resolves the proposal's §14
open decision: keep the two legacy flags as hidden sub-gates for one release, then make them no-ops.
**No new sub-flag migrations** — V232 is the only new migration.

---

### 3. Hard invariants (every reducer/compactor MUST hold these; each gets a pinning test)

1. **Master-OFF byte-compatibility.** Flag OFF ⇒ worker tool list unchanged, `maybeCompact` returns
   raw, history compactor returns the same list reference, no Redis writes. (Pinned: §7 OFF tests.)
2. **Gate-marker preservation.** A reduced `buildProject` envelope still contains `\nexit=0\n` when the
   build passed; `runTests` is never reduced. A reducer that can't guarantee this returns raw.
3. **Fresh-read protection.** A `readFile` result that is the latest read of its file with no later
   edit is **never** compacted (it is the model's exact `editFile` anchor).
4. **Latest-result protection.** The most-recent tool result in a batch (`isImmediatelyPriorToolCall`)
   is never reduced (already enforced at the hook).
5. **Skip set.** `writeFile`, `editFile`, `gitCommit`, `completeTask`, `gitStatus`, `runTests` are
   never reduced (short/actionable/gate-critical). `gitDiff`: only old/non-latest.
6. **Shrink gate.** Emit the marker only if it is strictly smaller than the raw by chars **and** by a
   char-based token estimate; store the original only after it passes. Otherwise return raw.
7. **Fail-open.** Any reducer/store/retrieval error ⇒ raw output (or a typed retrieval miss). No
   compaction path can fail a run. (Mirrors `ContextStore` + `ReadFileStateStore` fail-open policy.)
8. **Remote carve-out.** No reducer runs and `retrieveContext` is not bound on remote (Terminal-Bench)
   runs.
9. **Cache discipline.** No per-turn system-prompt rewrite; `retrieveContext` schema is byte-stable
   while bound; deterministic reducers produce identical output for identical input (no stochastic
   cache-busting).

---

### 4. New components (skeletons)

New package: `tool/compact/` (the two existing compactors stay in `tool/`; auto-discovery is
package-agnostic). All token counts use a shared char-based estimator (`chars / 4`, no LLM call).

#### 4.1 `WorkerCompactionPolicy` — all decisions in one place (no I/O)

```java
@Service @RequiredArgsConstructor
public class WorkerCompactionPolicy {
  public static final String FLAG_MASTER = "worker.compaction.enabled";          // V232, default OFF
  static final String FLAG_LLM_FALLBACK  = ToolOutputCompactor.FLAG_ENABLED;      // sub-gate, OFF
  static final String FLAG_HISTORY       = WorkerHistoryCompactor.FLAG_ENABLED;   // sub-gate, OFF
  static final int    MIN_REDUCE_BYTES   = 2048;  // below this, not worth a marker+store round-trip

  private final FeatureFlagService flags;

  public boolean enabled() { return flags.isEnabled(FLAG_MASTER); }

  /** retrieveContext is bound iff master ON and the run is not remote (D3/D5). */
  public boolean shouldBindRetrieveTool(boolean isRemoteRun) { return enabled() && !isRemoteRun; }

  /** Gate for deterministic tool-output reduction at emit time. */
  public boolean shouldReduceToolOutput(String toolName, String raw, boolean isLatest) {
    if (!enabled() || isLatest || raw == null || raw.length() < MIN_REDUCE_BYTES) return false;
    return !NEVER_REDUCE.contains(toolName);   // see invariant #5
  }

  /** LLM fallback only when master ON AND the legacy sub-gate is explicitly ON. */
  public boolean shouldRunLlmFallback() { return enabled() && flags.isEnabled(FLAG_LLM_FALLBACK); }

  /** History fold only when master ON AND the legacy sub-gate is explicitly ON. */
  public boolean shouldRunHistoryCompaction() { return enabled() && flags.isEnabled(FLAG_HISTORY); }

  /** Read-lifecycle ships in Phase 4; constant gate until eval clears it. */
  public boolean shouldRunReadLifecycle() { return enabled() && READ_LIFECYCLE_ON; }

  static final Set<String> NEVER_REDUCE =
      Set.of("writeFile","editFile","gitCommit","completeTask","gitStatus","runTests");
  static final boolean READ_LIFECYCLE_ON = false; // flip in Phase 4 after eval
}
```

#### 4.2 `CompactContextStore` — Redis original-content store (modeled 1:1 on `ContextStore`)

```java
@Component @Slf4j @RequiredArgsConstructor
public class CompactContextStore {
  public static final String KEY_PREFIX = "v2:compact:";          // v2:compact:{runId}:{taskId}:{hash}
  public static final Duration TTL = Duration.ofHours(24);
  @Autowired(required = false) private StringRedisTemplate redis;  // optional → tests don't crash
  private final ObjectMapper objectMapper;

  /** @return 24-lowercase-hex hash, or empty on any failure (fail-open). */
  public Optional<String> put(ExecutionContext ctx, String toolName, String strategy,
                              String original, String compressed) {
    if (redis == null || original == null) return Optional.empty();
    try {
      String hash = sha256hex24(original);
      ObjectNode v = objectMapper.createObjectNode();
      v.put("original", original); v.put("compressed", compressed);
      v.put("toolName", toolName); v.put("strategy", strategy);
      v.put("originalBytes", original.length()); v.put("compressedBytes", compressed.length());
      redis.opsForValue().set(key(ctx, hash), v.toString(), TTL);
      return Optional.of(hash);
    } catch (Exception e) { log.warn("CompactContextStore put failed run={}: {}", ctx.runId(), e.getMessage()); return Optional.empty(); }
  }

  public Optional<CompactEntry> get(ExecutionContext ctx, String hash) { /* scoped to runId/taskId; empty on miss/down */ }
  public long deleteAll(String runId) { /* redis.keys(KEY_PREFIX+runId+":*") + delete — mirrors ContextStore.deleteAll */ }
  private static String key(ExecutionContext c, String h){ return KEY_PREFIX+c.runId()+":"+c.taskId()+":"+h; }
}
```
Wire `compactContextStore.deleteAll(runId)` beside `contextStore.deleteAll(runId)` at
`SequentialExecutor.java:374-375` (always-on hygiene, like V221's `ContextStore.deleteAll`).

#### 4.3 `RetrieveContextTool implements Tool` (read-only, scoped)

```java
@Component @RequiredArgsConstructor
public class RetrieveContextTool implements Tool {
  static final int DEFAULT_MAX_CHARS = 8000, HARD_CAP = 16000;
  private final CompactContextStore store;

  public String name() { return "retrieveContext"; }
  public String description() {
    return "Retrieve the full original content of a previously compacted tool output by its hash "
      + "(from a [compressed … retrieveContext hash=…] marker). Optional `query` searches within it. "
      + "NOTE: retrieved content is a historical snapshot — if you intend to editFile, readFile the "
      + "file again first so your edit matches current disk contents.";  // D4
  }
  public String inputSchema() { /* hash:^[0-9a-f]{24}$ (required), query:string, maxChars:int — additionalProperties:false */ }

  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String hash = args.path("hash").asText("");
    if (!hash.matches("[0-9a-f]{24}")) return ToolResult.err(INVALID_ARGS, "hash must be 24 lowercase hex chars");
    var entry = store.get(ctx, hash);                                   // scoped to THIS run/task only
    if (entry.isEmpty()) return ToolResult.ok("(retrieval miss: no stored content for hash " + hash + " in this run/task)");
    String original = entry.get().original();
    int cap = Math.min(args.path("maxChars").asInt(DEFAULT_MAX_CHARS), HARD_CAP);
    String q = args.path("query").asText(null);
    return ToolResult.ok(q == null || q.isBlank() ? headPreview(original, cap) : bestLineMatches(original, q, cap));
  }
}
```
Never shells out, never reads disk, never crosses run/task scope (invariant #7, security row in §9). A
miss is a typed `Ok` string, not an error — the worker treats it as "detail unavailable," never a stall.

#### 4.4 Reducer SPI + router

```java
public interface ToolOutputReducer {
  boolean handles(String toolName);
  /** @return reduced content (a body the router will append the marker to), or empty to pass raw. */
  Optional<String> reduce(String toolName, String rawOutput);
}

@Service @RequiredArgsConstructor
public class ToolOutputReducerRouter {
  private final WorkerCompactionPolicy policy;
  private final CompactContextStore store;
  private final List<ToolOutputReducer> reducers;   // auto-collected; first match wins

  /** Called from WorkerAgent.maybeCompact. Returns the content to envelope (reduced+marker, or raw). */
  public String maybeReduce(String toolName, String raw, boolean isLatest, ExecutionContext ctx) {
    if (!policy.shouldReduceToolOutput(toolName, raw, isLatest)) return raw;
    var reducer = reducers.stream().filter(r -> r.handles(toolName)).findFirst().orElse(null);
    if (reducer == null) return raw;
    String reduced = reducer.reduce(toolName, raw).orElse(null);
    if (reduced == null || !passesShrinkGate(raw, reduced)) return raw;   // invariant #6
    String hash = store.put(ctx, toolName, reducer.getClass().getSimpleName(), raw, reduced).orElse(null);
    if (hash == null) return raw;                                         // fail-open: store down → raw
    return reduced + "\n[compressed " + shape(raw) + "→" + shape(reduced)
         + "; retrieveContext hash=" + hash + "]";                        // stable, terse marker
  }
}
```

`WorkerAgent.maybeCompact` becomes (one new nullable field `reducerRouter`, last in ctor — null-guarded
exactly like the existing compactors):
```java
private String maybeCompact(String toolName, String content, boolean isLatest, WorkerTaskRequest req) {
  if (reducerRouter != null) {                                  // deterministic first (default-ON path)
    String reduced = reducerRouter.maybeReduce(toolName, content, isLatest, req.context());
    if (!reduced.equals(content)) return reduced;
  }
  if (toolOutputCompactor != null && policy.shouldRunLlmFallback()) {   // opt-in LLM fallback only
    return toolOutputCompactor.compactIfNeeded(toolName, content, req.userPrompt(), isLatest,
             req.context(), req.provider()).map(Compacted::summary).orElse(content);
  }
  return content;
}
```

---

### 5. The reducers (priority-ordered by value × safety)

> Reminder (D6): all single outputs except `readFile` are already budget-capped, so each reducer's
> per-call win is modest; the value **compounds** across 60–180 iterations. Token wins ranked:
> **history compaction (§6) > read-lifecycle (§6) > shell/search/list/diff reducers (this section)**.

#### 5.1 `ShellLogReducer` — `shell` + `buildProject` body (HIGH value, SAFE if header-preserved)
- **`buildProject`**: split on the literal `"\noutput:\n"`. **Keep the `command=…\nsource=…\nexit=<n>\noutput:\n` header verbatim** (this is what preserves the `\nexit=0\n` gate marker — invariant #2), reduce only the body.
- **`shell`**: reduce the whole body (no gate dependency).
- Body keep-rules: first + last error/failure block, stack traces (≤3, ≤20 lines each), compiler
  diagnostics with file:line, a deduped warning sample (≤5), the persisted-full-log `readFile` path that
  `ToolOutput.budget` already appended. Drop: download/install progress, repeated passing lines, dup warnings.
- Defaults: `maxErrors=10, errorContextLines=3, maxStackTraces=3, stackTraceMaxLines=20, maxWarnings=5,
  maxBodyLines=120, minBodyLines=50`.
- **`runTests` is NOT handled here** (excluded; gate-critical + already small structured JSON).

#### 5.2 `SearchResultReducer` — `searchFiles` (MEDIUM value, SAFE)
- Output is `"<total> match(es); showing a–b\n<file:line:content>…"` — keep the count/pagination header,
  then first/last match per file, file groups by relevance, `file:line:content` intact enough to `readFile`.
- Defaults: `maxFiles=15, maxMatchesPerFile=5, maxTotalMatches=40`. (Single-call is already paged 50/200,
  so the real win is replacing OLD large search outputs left in history.)

#### 5.3 `ListFilesReducer` — `listFiles` (LOW-MEDIUM value, SAFE)
- Keep root dirs, source/test/config files, task-relevant neighbors, per-dir omitted counts. Drop
  vendor/build/generated subtrees and deep repeated detail. (`listFiles` is already capped at 500 entries.)

#### 5.4 `DiffReducer` — old/non-latest `gitDiff` only (LOW value, SAFE)
- Keep file headers, all `+`/`-` lines, ≤2 context lines, first/last/high-change hunks, dropped-file
  summary. **Never the latest `gitDiff`** (immediate verification signal — already protected by `isLatest`).

---

### 6. The dominant levers: history compaction + read-lifecycle

#### 6.1 History compaction (rewire, mostly built)
`WorkerHistoryCompactor` already triggers at 60k input tokens, keeps recent-8, cuts on an
`AssistantMessage` boundary, folds the task into the summary, fail-safe ([WorkerHistoryCompactor.java](../../src/main/java/com/navneet/executespec/tool/WorkerHistoryCompactor.java)). Changes:
- Gate via `policy.shouldRunHistoryCompaction()` (master AND the V222 sub-gate) instead of the bare flag.
- **Ordering is already correct**: per-output reducers run at *emit-time* (`dispatchOne`), so by the time
  history compaction runs at the next iteration's top, the tool outputs it summarizes are already reduced
  markers. No code reordering needed — the proposal's §7 concern is structurally satisfied by where the
  hooks sit.
- Prompt tweak: instruct the summarizer to **preserve any `retrieveContext hash=…` markers** it sees so
  retrieval still works after a fold (best-effort; markers point at 24h-TTL Redis).

#### 6.2 Read-lifecycle compactor (Phase 4 — net-new, the algorithm the proposal lacked)
Runs as a separate pre-call history pass beside history compaction. **Deterministic, no LLM, no
`ReadFileStateStore` dependency** (that store only has the latest SHA; this needs the message timeline):

```
compactStaleReads(history, ctx):
  if !policy.shouldRunReadLifecycle(): return history
  recentCutoff = history.size() - KEEP_RECENT (8)        # never touch the live working set
  for each ToolResponseMessage tr at index i < recentCutoff:
     for each response r in tr where r.name == "readFile":
        path = pathOf(precedingAssistant.toolCalls matching r.id)   # parse the readFile call's `path`
        if path == null: continue
        # STALE := a later writeFile/editFile to the SAME path exists after index i
        if existsLaterEditTo(history, i, path):
            hash = compactStore.put(ctx, "readFile", "read-lifecycle-stale", r.responseData, marker)
            replace r.responseData with "[stale read of {path} — file edited since; retrieveContext hash={hash}]"
        # SUPERSEDED (later readFile covering a wider range) — OFF in v1 (READ_LIFECYCLE_SUPERSEDED=false)
  return history (new list iff anything changed; else same reference)
```
- **Fresh reads are structurally untouched** (no later edit ⇒ skipped) — invariant #3.
- Edit-correctness is unaffected: the edit gate is disk-vs-ledger; a stale read was already useless as an
  anchor (the file changed), so replacing it removes a *wrong* anchor, it doesn't remove a usable one.
- Superseded-read replacement stays OFF until stale-read eval proves safe (proposal §6.5 default).

---

### 7. Test matrix (mirror `ToolOutputCompactorTest` / `WorkerHistoryCompactorTest` — Mockito, `@ExtendWith(MockitoExtension.class)`)

**OFF-mode byte-compat (the rollback guarantee — invariant #1):**
- `WorkerCompactionPolicyTest`: master OFF ⇒ every `should*` returns false.
- `BindForWorkerCompactionOffTest`: flag OFF ⇒ `bindForWorker(ctx)` tool-name set is identical to HEAD; flag ON + remote ⇒ no `retrieveContext`.
- `MaybeCompactOffTest` (WorkerAgent): flag OFF ⇒ `maybeCompact` returns the exact input string.

**Invariant pins:**
- `BuildLogReducerTest`: a passing build envelope still contains `\nexit=0\n` after reduction; header preserved byte-for-byte; body shrunk. A failing build keeps `exit=1` + first/last error.
- `RunTestsNeverReducedTest`: `runTests` content passes through unchanged (gate markers intact).
- `LatestResultNeverReducedTest`: `isLatest=true` ⇒ raw.
- `ShrinkGateTest`: reducer output ≥ raw ⇒ raw returned, nothing stored.
- `FailOpenTest`: store/`Redis` down ⇒ raw returned; no throw.

**Component units:** `CompactContextStoreTest` (put/get/deleteAll, scope isolation across run/task, 24-hex hash), `RetrieveContextToolTest` (hash validation, miss→Ok string, query path, maxChars cap, cross-run isolation), `SearchResultReducerTest`, `ListFilesReducerTest`, `DiffReducerTest`, `ReadLifecycleCompactorTest` (fresh untouched / stale replaced / pairing preserved).

**Integration/eval:** Java build-failure loop · pytest-failure loop (assert the gate still fires through a reduced `buildProject`) · broad-search task · large-read+edit task (assert `editFile` still matches after unrelated reductions) · stale-read-after-edit task · SWE-bench subset with verifiedScore comparison ON vs OFF.

---

### 8. Phase plan (all under master flag default OFF; eval gate before the flip)

| Phase | Deliverable | Exit criteria |
|-------|-------------|---------------|
| **0 — baseline** | Capture token/cost/latency on simple+MEDIUM+HIGH+build-failure+large-read runs (flag OFF). Run the OFF baseline ≥3× to measure the verifiedScore noise band → this sets the Phase-6 non-inferiority margin (§10.1). Save raw tool outputs as reducer fixtures. No code. | Baseline numbers + OFF-variance band recorded in reason-log; fixtures committed. |
| **1 — flag + policy** | V232 `worker.compaction.enabled` (default OFF). `WorkerCompactionPolicy`. Rewire `ToolOutputCompactor` + `WorkerHistoryCompactor` to consult the policy (master + sub-gate). | All OFF-mode byte-compat tests green; `mvn verify` green; production byte-identical (flag OFF). |
| **2 — store + retrieval** | `CompactContextStore` + `deleteAll` wired at `SequentialExecutor:374`. `RetrieveContextTool`. `bindForWorker` gate (D5) + remote exclusion (D3). | Store/tool unit tests green; bind tests green; retrieval scoped + hash-validated. |
| **3 — deterministic reducers** | SPI + router + `ShellLogReducer`/`SearchResultReducer`/`ListFilesReducer`/`DiffReducer`. `maybeCompact` routes reducer-first. LLM compactor demoted to opt-in fallback. | Invariant pins green; reducer units green; with master ON in a test profile, gates still fire. |
| **4 — read-lifecycle** | `ReadLifecycleCompactor` (stale-only; superseded OFF). Hook beside history compaction. | Edit-correctness + read-before-write tests green; fresh-read-never-compacted pinned. |
| **5 — history cleanup** | Confirm reducers-before-summarization (already structural); marker-preservation prompt tweak; before/after prompt-token metric. | History fold runs after reducers; metrics emit. |
| **6 — eval + flip** | Run the eval set (§7) master ON vs OFF. | Acceptance bar (§ below) met → **separate follow-up migration flips the V232 seed to ON**; only then recalibrate `RunCostTracker` bands. |

**Definition of done per phase** (documentation hygiene, mandatory before each commit): root `CLAUDE.md`
(in-flight section), `engine/CLAUDE.md` + a new `tool/compact/CLAUDE.md`, `docs/CODEMAP.md` (new package +
V232), `docs/document_map.md` (this doc), `docs/reason-log/2026-06.md` (status + a new RL id), and the
V232 cross-link from `tool/compact/CLAUDE.md`.

**Acceptance bar (Phase 6, before flipping default ON):** OFF byte-compatible · ON reduces worker input
tokens ≥25% on MEDIUM/HIGH debug-heavy evals · ON does not lower verifiedScore beyond a pre-declared
non-inferiority margin on a pre-declared N (resolve in §10) · retrieval miss rate ≈0 for same-run hashes ·
no fresh read compacted · no latest result compacted · no run failed by compaction · per-strategy metrics
present.

---

### 9. Risks (updated for the verified findings)

| Risk | Mitigation |
|------|------------|
| Reduced `buildProject`/`runTests` envelope breaks the completeTask gate | `runTests` excluded; `buildProject` header (incl. `\nexit=0\n`) preserved verbatim; pinned by `BuildLogReducerTest` + `RunTestsNeverReducedTest`. |
| Compacting a fresh read breaks `editFile` matching | Fresh reads never compacted; read-lifecycle is stale-only and ships last behind its own gate. |
| `retrieveContext` proxied to the Harbor client on a TB run | Not bound on remote runs (compaction is off there anyway). |
| Worker edits against a retrieved (stale) snapshot | Tool output says "re-read before editing"; edit gate independently forces a re-read on STALE. |
| New `Tool` bean changes the default tool list | `bindForWorker` filters it when OFF/remote; OFF-mode tool-list test. |
| LLM fallback costs more than it saves (LIGHTWEIGHT=flagship) | Default-ON path is deterministic-only; LLM fallback stays opt-in behind the V216 sub-gate. |
| Default-ON surprises production | Seeded OFF; flip only via follow-up migration after Phase 6; one master rollback flag. |
| Security leak via retrieval | Scope by run/task, 24-hex hash validation, output cap, redacted logs (no original content logged). |

---

### 10. Open decisions (carry into approval)

1. **Eval N + non-inferiority margin** for "does not lower verifiedScore" — **method agreed
   (2026-06-06): paired flag-ON-vs-OFF experiments on the same eval set.** Still to pick: the eval set,
   N, and the margin. The margin is **derived empirically from the OFF-run variance measured in Phase 0**
   (run the OFF baseline ≥3× to get the run-to-run noise band; the ON arm must stay within it), since
   small-N benchmark noise on this repo is real (see reason-log re ~3-reliable-on-re-run). Proposal:
   SWE-bench Lite subset, N≥20.
2. Retrieval tool name `retrieveContext` (proposed).
3. Whether superseded-read replacement is ever enabled (only after stale proves safe).
4. Whether the two legacy sub-flags become no-ops at the Phase-6 flip or survive one more release.

---

### 11. Appendix — known temporary tier state (context, not an action item)

In this checkout the **LIGHTWEIGHT tier resolves to flagship models** for Anthropic/OpenAI
(`lightweight: claude-sonnet-4-6`, `lightweight: gpt-5.4` — [application.yml:86,94](../../src/main/resources/application.yml#L86), consumed by [ModelResolver.java:60-70](../../src/main/java/com/navneet/executespec/router/ModelResolver.java#L60)), so it currently differs from the CLAUDE.md tier table (Haiku / gpt-5.4-mini / Flash), which is the intended end-state.

**Confirmed by founder (2026-06-06): this is a deliberate temporary state and will be reverted later** —
not a bug, no investigation needed. Two consequences for this plan, both already handled:
- The default-ON path is **deterministic-only**, so it is unaffected by which model LIGHTWEIGHT points at.
- The optional LLM fallback (behind the V216 sub-gate, default OFF) only becomes cost-attractive once the
  tier mapping is reverted to Haiku/mini/Flash — which is exactly why it stays opt-in until then (D7).

### 12. Confirmation gate

This is a plan. Implementation waits for explicit confirmation.
