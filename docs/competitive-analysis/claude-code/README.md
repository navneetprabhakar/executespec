# Claude Code (reverse-engineered) — analysis

> **What this is:** an analysis of the code under `/Users/navneetprabhakar/Documents/antigravity/sourcecode`.
> That tree is a **deobfuscated / beautified dump of Anthropic's "Claude Code" agentic-coding CLI**
> — the real product's internals split into ~1,900 named TypeScript/TSX files (several are giant
> bundles, e.g. `main.tsx` ~800 KB, `screens/REPL.tsx` ~895 KB). It is **not** a from-scratch clone:
> imports reference `@anthropic-ai/sdk`, `bun:bundle` `feature()` flags, an internal Agent SDK
> (`entrypoints/sdk/`), GrowthBook, and Anthropic-internal markers (`USER_TYPE === 'ant'`,
> `@[MODEL LAUNCH]` comments). Treat it as a reference implementation to **learn from**, not to copy
> wholesale (it's Anthropic's proprietary code — see "Usage / licensing" below).
>
> **Why we're studying it:** to decide what to adopt into (1) the **ExecuteSpec pipeline** and
> (2) our **CLI + VS Code clients**, so we can meet/beat Claude Code on coding tasks.

## The four docs

| Doc | Read it for |
|-----|-------------|
| **[README.md](README.md)** (this) | What the codebase is, the architecture in one diagram, top takeaways. |
| **[CODING-TASKS.md](CODING-TASKS.md)** | **The centerpiece.** How it executes a coding task end-to-end (loop, tools, system-prompt discipline, verification) + a prioritized "what we adopt" table. Start here for "improve our system." |
| **[FEATURES.md](FEATURES.md)** | The full capability catalog (tools, commands, hooks, memory, compaction, MCP, skills, plugins, IDE bridge, remote modes, CLI UX), each tagged with ExecuteSpec/Client relevance. |
| **[CODEMAP.md](CODEMAP.md)** | Directory-by-directory navigation map + "where to look for X." |

## Architecture in one diagram

```
              entrypoints/cli.tsx ──► main.tsx (commander parse)
                                          │
                  interactive ◄───────────┴──────────► -p / non-TTY (headless)
                  screens/REPL.tsx (Ink UI)            cli/print.ts (text|json|stream-json)
                          └───────────────┬────────────────────┘
                                          ▼
                                   QueryEngine.ts
                 (system prompt + memory + cost + file-history + permissions + MCP/plugins/skills)
                                          │
                                          ▼
                                      query.ts  ── THE LOOP ──
                          while(true): stream callModel → run tools → compact → until terminal
                                          │
            ┌─────────────┬──────────────┼───────────────┬────────────────┐
            ▼             ▼               ▼               ▼                ▼
        Tool.ts +     constants/      services/api    services/compact   context.ts + memdir/
        tools/        prompts.ts      (Anthropic SDK) (auto/micro/snip)   (git + CLAUDE.md + typed memory)
      (Bash, Edit,   (the coding     services/mcp    services/lsp
       Agent, Grep)   discipline)     (bridge, tools) (diagnostics)
```

**The defining design choice:** Claude Code is **one strong model in a tight streaming tool loop**,
not a multi-agent pipeline. Everything we split across classifier→planner→worker→reviewer, it folds
into *one loop + one disciplined system prompt + a strong tool contract*. Its depth is in **tool
reliability, prompt-cache engineering, context/memory management, and a permission model** — not
orchestration.

## Top 10 takeaways (what's worth our time)

**For the ExecuteSpec pipeline (improve our coding system):**

1. **Read-before-write + staleness gate** on file edits (`Tool.ts` / `FileEditTool`). Refuse to edit
   an unread or drifted file; return self-correcting errors. Kills the stale-edit/clobber class.
2. **The coding-discipline system prompt** (`constants/prompts.ts`): minimal-diff, verify-before-done,
   read-before-modify, faithful-reporting, diagnose-before-retry, blast-radius-confirm. We just added
   a bug-fix discipline section; this is the more complete, battle-tested version to adopt.
3. **Prompt-cache discipline**: static sections → `SYSTEM_PROMPT_DYNAMIC_BOUNDARY` → dynamic
   sections, memoized. Directly fixes our documented 0% Gemini cache-hit problem.
4. **Per-tool output budget → persist-to-disk + preview** instead of flat 6k/5k truncation; the model
   pulls full output on demand.
5. **Auto-managed typed memory** (`memdir/`): 4-type taxonomy + "don't store what's derivable" rule +
   `MEMORY.md` index + LLM relevance-selection. Sharpens our `ProjectMemoryProvider`.
6. **Context compaction** (`services/compact/`): micro-compact stale tool results so long worker loops
   survive context pressure (we only have iter caps today).
7. **User-configurable lifecycle hooks** (27 events, 4 kinds incl. an `agent` verifier and
   `PreToolUse` that can deny/rewrite a tool call): deterministic policy enforcement for
   TEAM/ENTERPRISE — our ReviewerAgent-as-a-gate, generalized.

**For our CLI + VS Code clients:**

8. **NDJSON `stream-json` transport** + auto-headless on non-TTY: one agent loop, three serializers;
   pipeable to CI/jq; the same transport Claude Code's own VS Code extension uses
   (`CLAUDE_CODE_ENTRYPOINT=claude-vscode`).
9. **IDE bridge as an MCP server** with **lockfile discovery** (`~/.claude/ide/<port>.lock`),
   **blocking interactive diff** (`openDiff` → user edits the patch in-editor → re-diff), and
   **baseline-diffed diagnostics** (surface only new errors after an edit). Far better than our
   approve/reject modal + bespoke SSE.
10. **Out-of-process tools via MCP control transport** (`SdkControlTransport` = our
    `RemoteToolDispatcher`): standardize our local-hands/remote-brain proxy on MCP `control_request`
    framing instead of a bespoke protocol — and get tools/resources/prompts/notifications for free.

Full prioritized borrow table with effort estimates is in **CODING-TASKS.md §7**.

## How this analysis was produced

Line-level read of the agent brain (`query.ts`, `QueryEngine.ts`, `Tool.ts`, `Task.ts`,
`context.ts`, `constants/prompts.ts`) + four parallel deep surveys (tools/, CLI+UI, IDE-bridge/MCP/
skills/plugins, services/commands/memory). Giant bundle files were sampled for behavior, not read
line-by-line in full. Every claim cites a file path; verify against source before building on it.

## Usage / licensing caveat

This is Anthropic's proprietary Claude Code source (deobfuscated). Use it as a **design reference**
to inform our own independent implementation — do not copy code verbatim into our product. The value
here is the *patterns and decisions*, which are what the four docs capture.
