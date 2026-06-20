# Harness & code-gen: ExecuteSpec vs Claude Code vs oss-agent

> Design + approach comparison of three agentic-coding systems, written to guide (1) our harness /
> code-gen engine and (2) our CLI / VS Code clients. Sources: our own codebase; the deobfuscated
> Claude Code dump (`docs/competitive-analysis/claude-code/`); the open-source oss-agent
> (`docs/competitive-analysis/oss-agent/`). 2026-05-27.

---

## 0. The one-paragraph verdict

**Claude Code and oss-agent are both "one strong model in a single streaming tool-loop."** ExecuteSpec
is the outlier: a **staged multi-agent pipeline** (classifier → planner → worker → reviewer). On raw
SWE-bench resolve-rate the single-loop designs currently beat us *on the same model* (Claude Code
4/5 vs our 2/5 single-provider on the gate-5) — the gap is **harness extraction efficiency**, not
model. Their quality comes from **tool feedback richness, prompt-cache discipline, per-model prompts,
and a clean engine boundary** — exactly the levers we just started adopting. Meanwhile, **oss-agent
independently validates the founder's "core engine as a server + thin clients" plan** — it's their
literal architecture. Our differentiation is the **product layer** (plan gate, multi-repo PRs,
governance, scoring, project memory) + **multi-provider routing** (our complementarity ceiling was
19/30 vs 16/13 single-provider — a lever neither competitor has).

---

## 1. Architecture & control flow

| | **ExecuteSpec** | **Claude Code** | **oss-agent** |
|---|---|---|---|
| Shape | **Multi-agent pipeline** | Single streaming loop | Single streaming loop |
| Stages | classifier → planner → (worker × tasks) → lint → reviewer → scoring | one `query` loop | one loop (build/plan/general agents) |
| Loop owner | our `WorkerAgent` per task | hand-rolled `query.ts` `while(true)` over a custom provider client | **Vercel AI SDK** `streamText` (inner) + a thin `runLoop` (outer) |
| Planning | explicit `OutlinePlannerAgent` | none — model plans inline (+ optional plan mode / TodoWrite) | none — `plan` agent is just deny-all-edits + a plan-file; `plan_exit` switches to `build` in-session |
| Review | explicit `ReviewerAgent` (3 passes) | none — verify is in the prompt + the loop | none — diagnostics fed back per-edit; sub-agents for research |
| Stop condition | per-task `completeTask` / caps | no tool calls → terminal | finish-reason has no unanswered tool calls |

**Takeaway:** the staged pipeline is our biggest architectural difference and the source of both our
moat (governance/scoring happen *because* of the stages) and our gap (more orchestration ≠ better
code-gen; the single loop is leaner + the model decides its own approach). Our `benchmark` mode
already "stops at the worker" to emulate a single loop for scoring — which is exactly why the
**engine extraction** (isolating the worker-loop as a core engine) is the right move.

## 2. The agentic loop in detail

- **ExecuteSpec:** `WorkerAgent` — a hand-rolled multi-turn loop via `LlmRouter` (native tool calling
  across 3 providers). One progress counter (`itersSinceProgress`) → nudge → force-terminate. Per-tier
  iter caps. No context compaction (sliding-window is a TODO).
- **Claude Code:** `query.ts` async generator, `while(true)`: stream `callModel` → run tools
  (streaming, concurrency-aware) → compact → continue. Multi-strategy compaction
  (auto/micro/snip/reactive). Per-turn token budget. Hooks woven in.
- **oss-agent:** the **AI SDK owns the inner tool loop**; oss-agent's outer `runLoop` re-assembles the
  prompt + re-resolves tools each step and handles compaction/sub-tasks/reminders. **Doom-loop
  detector** (3 identical tool calls → intervene). Compaction = a loop task (summarize + `tail_start_id`).

**Borrow:** compaction-as-a-loop-task (both competitors have it; we have none) + oss-agent's
doom-loop detector (cheap, targeted) → our `WorkerAgent`.

## 3. Tools & the edit/exec contract

| | **ExecuteSpec** | **Claude Code** | **oss-agent** |
|---|---|---|---|
| Edit | exact string-replace, unique-match | exact string-replace + **read-before-write + staleness** | **fuzzy cascade** (Simple→LineTrimmed→BlockAnchor/Levenshtein) + per-file lock + formatter + **LSP diagnostics in result** |
| Read-before-write | **just added (#1)** ✅ | yes (`readFileState`) | no (relies on fuzzy match) |
| Bash safety | `ShellSandboxPolicy` (regex/allowlist) + cleanroom container | bubblewrap sandbox + AST analysis + allowlists + classifier | **NOT sandboxed** (host) + **tree-sitter** static analysis + per-command permission |
| Output budget | **just added (#6): persist-to-disk + preview** ✅ | `maxResultSizeChars` → persist + preview | auto-truncate → spill-to-file |
| Sub-agents | none (sequential tasks) | `AgentTool` (fresh/fork, worktree isolation) | `task` tool (sub-sessions) + explore/scout |

**Borrow:** oss-agent's **fuzzy edit cascade + LSP-diagnostics-in-the-tool-result** (cheaper in-loop
fix signal than our reviewer's fix-build pass); both competitors' **read-before-write** (we just
added it) and **output-budget-to-disk** (we just added it). Claude Code's **classifier-based
auto-approval** for the permission gate.

## 4. Permissions / safety

- **ExecuteSpec:** 8-layer defense-in-depth + 3 guardrail levels + cleanroom containers; tool
  authority hard-coded per stage (`ToolRegistry.bindForWorker` strips gitCommit).
- **Claude Code:** per-tool `checkPermissions` (allow/ask/deny) + permission modes
  (default/acceptEdits/plan/bypass) + auto-classifier + **user-configurable hooks** (27 events, incl.
  PreToolUse deny/rewrite + an `agent` verifier hook).
- **oss-agent:** a **wildcard permission ruleset** (`evaluate()` last-match-wins, ask/allow/deny,
  session-persisted "always") as the **single side-effect chokepoint**; **"an agent" = a permission
  profile** (plan mode = deny-all-edits). Tree-sitter + `BashArity` for shell ("git push *").

**Borrow:** the **declarative ruleset** (both competitors) — express plan-gate / BYOK / guardrail
levels as *data* instead of hard-coded per-stage filters; **agent = permission-profile** makes plan
mode trivial. Claude Code's **hooks framework** (= our planned #9; ReviewerAgent-as-a-gate generalized).

## 5. Context, memory, prompt strategy

| | **ExecuteSpec** | **Claude Code** | **oss-agent** |
|---|---|---|---|
| Project memory | `{{PROJECT_MEMORY}}` + RAG + codemaps + `project_memory_snapshot` | CLAUDE.md (4 scopes, @import) + **auto-typed `memdir`** (4-type taxonomy, LLM relevance) | AGENTS.md/CLAUDE.md walk-up + **lazy directory-local rules on read** + remote URLs |
| System prompt | **one base prompt for all 3 providers** | static-then-`DYNAMIC_BOUNDARY` (cache-stable) | **per-model prompts** (anthropic.txt/gpt.txt/gemini.txt…) |
| Prompt cache | documented **0% Gemini** hit | explicit boundary discipline + memoized sections | provider-transform middleware + stable prefix |
| Compaction | none (TODO) | multi-strategy | summarize-loop-task |

**Borrow:** **per-model prompts** (oss-agent) + **cache boundary discipline** (Claude Code) → our #5;
Claude Code's **typed auto-memory** (`memdir`) to sharpen our `ProjectMemoryProvider`.

## 6. Multi-provider (our strongest comparative position)

- **ExecuteSpec:** `LlmRouter` across Anthropic/OpenAI/Gemini/OpenRouter; YAML `model-matrix` (tiers
  planning/workhorse/lightweight) + F3 per-org overrides + per-run pin. **Per-task model routing is a
  real lever** (eval union 19/30 > 16/13 single-provider).
- **Claude Code:** Anthropic-only (first-party).
- **oss-agent:** ~30 providers via **models.dev catalog** + plugin `catalog.transform` + runtime
  npm-install (`DynamicProviderPlugin`); `small_model` = a lightweight-tier analog; cache-aware
  5-bucket cost with provider-specific metadata fallbacks + >200k tier pricing.

**Takeaway:** multi-provider is where WE lead Claude Code and match oss-agent's breadth.
**Borrow from oss-agent:** the **models.dev catalog** (kill our hand-maintained YAML staleness) +
**catalog.transform/DynamicProvider** hook shape (add providers without router edits) + their
**cache-token accounting** (provider-specific metadata fallbacks — a real correctness gap in ours).

## 7. Clients & the "engine as a service" question (decisive for the extraction)

| | **ExecuteSpec** | **Claude Code** | **oss-agent** |
|---|---|---|---|
| Core delivery | Spring monolith (engine entangled in `RunOrchestrator`) | the CLI *is* the engine; SDK exposes it | **`oss-agent serve` = headless HTTP server; everything is a client** |
| Client↔core | REST + SSE (bespoke) | in-proc + Agent SDK | **OpenAPI-generated SDK**; same SDK in-proc (swappable `fetch`) OR remote (`--attach`) |
| CLI | thin REST/SSE | rich Ink REPL + `-p`/stream-json | `oss-agent run` + `--format json` (NDJSON) |
| VS Code | native (TreeView/webview/PAT) — *in progress* | native ext over MCP/lockfile + blocking diff | **ultra-thin (137 LOC)**: hosts the TUI in a terminal; rich integration via ACP |
| Remote exec | local-hands/remote-brain (planned, `RemoteToolDispatcher`) | remote-brain (`remote/`) + MCP control transport | **workspace adapters** (`Target = local|remote{url}` + transparent proxy) |

**This is the most important section for the founder's plan.** oss-agent is living proof that
**"core engine as a server + thin clients" works**: a headless `serve`, an **OpenAPI-generated SDK**
so clients never drift, a **dual-mode client** (same code in-process or remote via a swappable
`fetch`), and **workspace adapters** that transparently proxy to a remote/containerized brain
(= our local-hands/remote-brain, generalized + pluggable).

**Borrow (high priority for the extraction):**
1. **Generate our client SDK from the Spring OpenAPI** (we already have `/api/runs` + `/events`) —
   our CLI + VS Code both hand-roll `clients/core`; codegen eliminates contract drift.
2. **One client, in-process OR remote, via swappable transport** — the CLI embeds the engine for
   solo/offline, points at `dev.executespec.ai` for hosted, zero branching.
3. **Workspace-adapter abstraction** (`local | remote{url,headers}` + proxy) as the clean shape for
   our local-hands/remote-brain split.
4. **`--format json` NDJSON + `?auth_token=` query-param SSE auth** for our CLI `run watch` + scoped
   SSE tokens / `es_pat_`.

> Caveat: oss-agent's VS Code ext is *intentionally minimal* (TUI-in-a-terminal); our native webview
> bet (TreeView, plan-gate modal, diffs) is richer. Claude Code's **blocking interactive diff**
> (`openDiff` → user edits the patch → re-diff) is the better apply-UX to borrow for ours.

## 8. What's unique to each (don't lose sight of our moat)

- **Claude Code:** first-party Anthropic models; hooks framework; the most polished single-loop +
  tool contract.
- **oss-agent:** clean monorepo; engine-as-a-service; ~30 providers via models.dev; plugin hook-bus;
  ACP for editors.
- **ExecuteSpec (our moat — none of which the extraction exposes):** the **plan gate**, **multi-repo
  PR creation + cross-link**, **governance** (standards/contracts/ADR/invariants), **verified
  additive scoring**, **project memory + RAG**, the **4 autofix surfaces**, **billing/tiers**, and
  **multi-provider per-task routing**. These are all legitimately *upstream/downstream* of code-gen.

## 9. Net adoption plan (harness + code-gen)

**Already done this session (resolve-rate levers):** #1 read-before-write, #2 prompt discipline,
#3 verify-nudge, #4 paged grep, #6 output budget, + the black-pollution fix.

**Into the engine extraction:**
- #5 cache discipline (Claude Code boundary + oss-agent per-model prompts)
- #7 compaction-as-a-loop-task (both)
- #8 fork-sub-agents (Claude Code) + oss-agent's `task`/explore
- #9 permission ruleset + hooks (oss-agent ruleset + Claude Code hooks) — agent = permission-profile
- doom-loop detector (oss-agent)
- fuzzy edit cascade + LSP-diagnostics-in-result (oss-agent)

**Architecture (the founder's plan, validated by oss-agent):** extract code-gen as a provider-agnostic
**core engine** (`solve(spec, workspace, tools, model) → diff + verification + transcript + cost`)
behind an **OpenAPI contract**, with the pipeline (classifier/planner/reviewer/scoring/governance) as
upstream/downstream layers, and **generated thin clients** (CLI/VS Code) that run the engine
in-process or against a remote server.

**Provider edge (extend our lead):** adopt the **models.dev catalog** + cache-token accounting from
oss-agent; keep our **per-task multi-provider routing** as a differentiator neither competitor has.
