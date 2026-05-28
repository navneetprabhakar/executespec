# `spec` CLI + VS Code Plugin — Shape B (Local Hands, Remote Brain)

> **Date:** 2026-05-28 · **Status:** Design locked, Phase 1 starting · **Owner:** RL-2026-05-052
> **Lock signoff:** founder, 2026-05-28 (5/5 decisions confirmed)

## Vision

A **single chat interface** for ExecuteSpec — same UX in CLI + VS Code sidebar — that lets enterprise/indie users run the full ExecuteSpec pipeline against their **local working tree** without uploading code to a server. Cost-undercut via DeepSeek/Qwen routing; privacy via local-hands-remote-brain architecture.

## What we ship

| Surface | What |
|---|---|
| **`spec` CLI** | One binary; running it drops into a chat REPL. No subcommand maze — the LLM intent layer translates user intent into tool calls. |
| **VS Code plugin** | Sidebar webview = the same chat (mirrors the CLI). Plan-gate approvals + run details render inline in the chat. |

## The 5 locked decisions

1. **Binary name:** `spec` (full alias `executespec` for unambiguous scripts; drop the old `es` alias)
2. **Chat persistence:** **LOCAL-ONLY.** Conversation history + session state on user's machine. Server is stateless for chat.
3. **Default `submit_run` mode in linked project:** **LOCAL** (Shape B local hands)
4. **CWD not in a linked project:** **B — Auto-create project** from CWD's basename (with safeguards against system dirs like `/tmp`, `~/`, `/`)
5. **VS Code distribution:** Sideload VSIX initially, Marketplace at Phase 6 polish

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  User's machine                                                  │
│  ┌───────────────┐    ┌───────────────┐   ~/.executespec/        │
│  │ spec CLI      │    │ VS Code plug  │   ├── auth.json (PAT)    │
│  └──┬────────────┘    └──┬────────────┘   ├── state.json         │
│     │  chat REPL         │  sidebar       └── chats/<pid>.jsonl  │
│     └──────────┬─────────┘                                       │
│                │ HTTPS + SSE (PAT auth)                          │
│                │                                                  │
│  Local tree ◄──┤  ToolRuntime: readFile/writeFile/shell/etc.     │
│  (cwd)         │  (clients/core/src/tools/runtime.ts)            │
└────────────────┼─────────────────────────────────────────────────┘
                 │
┌────────────────▼─────────────────────────────────────────────────┐
│  ExecuteSpec backend                                              │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Chat session (stateless) — receives full history per turn   │ │
│  │ + Action tools: submit_run / approve_plan / cancel / switch │ │
│  │ + Read tools: existing ProjectChatTools (13 read-only)      │ │
│  └────────────────────────────┬────────────────────────────────┘ │
│                               │                                    │
│  ┌────────────────────────────▼────────────────────────────────┐ │
│  │ Worker / Planner / Reviewer (existing V2 pipeline)          │ │
│  │ Tool dispatch via RemoteToolDispatcher (LOCAL mode)         │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
                  LLM providers (Anthropic / OpenAI / Gemini /
                                 OpenRouter for DeepSeek + Qwen)
```

## API contracts

### Chat endpoint

`POST /api/spec/chat` (PAT-authenticated)

Request:
```json
{
  "session_id": "uuid (client-generated, persisted locally)",
  "project_id": "uuid (linked project, or null pre-link)",
  "local_context": {
    "cwd": "/path/to/project",
    "git": {"branch": "main", "dirty": ["src/foo.py"]},
    "platform": "darwin"
  },
  "conversation": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "...", "tool_calls": [...]},
    {"role": "tool", "tool_call_id": "...", "content": "..."},
    {"role": "user", "content": "current turn..."}
  ],
  "provider_override": "openrouter:deepseek-v4-pro"  // optional
}
```

Response: SSE stream of events
```
event: token   data: {"delta": "I'll "}
event: token   data: {"delta": "submit "}
event: tool_call  data: {"tool":"submit_run","args":{...},"id":"tc_1"}
event: tool_call  data: {"tool":"approve_plan","args":{...},"id":"tc_2"}
event: done    data: {"usage":{...}, "cost_usd":0.012}
```

### Action tools (new — added to ChatService tool registry)

| Tool | Args | Auth gate |
|---|---|---|
| `submit_run` | `{spec, mode=LOCAL\|REMOTE, provider?, model?}` | run-create on user's org |
| `approve_plan` | `{runId, creditsConfirmed?}` | run-write on the run |
| `reject_plan` | `{runId, feedback?}` | run-write |
| `cancel_run` | `{runId}` | run-write |
| `switch_provider` | `{provider, model?}` — session-scoped, persisted in local state | none (client-side) |
| `list_recent_runs` | `{limit?, status?}` | run-read |
| `get_run_report` | `{runId}` | run-read |
| `link_directory` | (client-side only — modifies `.executespec/project.json`) | none |

### Tool-result POST (Shape B, already exists, dormant)

`POST /api/runs/{id}/tool-results` — `RemoteToolController`. Activated when run is LOCAL.

## Local state layout

`~/.executespec/`
```
auth.json                    # PAT token (chmod 600), refresh token
state.json                   # current session, default provider, recent runs cache
sessions/                    # per-session metadata
  <session_uuid>.json
chats/                       # per-project conversation history
  <project_id>-<session_uuid>.jsonl  # one JSON line per turn (user / assistant / tool)
logs/                        # local logs from active runs
  <run_id>.log
```

Each linked project directory:
```
<project_root>/
└── .executespec/
    └── project.json         # { "projectId": "...", "linkedAt": "...", "defaultProvider": "..." }
```

## Auto-link behavior (decision #4 = B)

When `spec` starts and CWD has no `.executespec/project.json`:

1. **System dir guard:** if CWD is `/`, `~/`, `/tmp`, `/var`, system root, or a sub-2-level path under any of these → fall back to "ask" prompt (don't auto-create)
2. **Otherwise:** auto-create a new project named after `basename(CWD)` with the linked GitHub remote (if `.git` exists) detected from `git remote get-url origin`. Save `.executespec/project.json` and continue.
3. **Surface** in the first chat turn: "Linked to new project `payments-service` (auto-created from CWD). Want to rename or change the linked repo? Just say so."

## Stateless server contract (decision #2 implication)

- Server's `/api/spec/chat` endpoint does **NOT persist** the conversation
- Every turn, client sends the FULL conversation history
- Server only persists: a chat-session **audit record** with `{session_id, project_id, turn_count, timestamps, cost, model_used}` — no message content
- This means the WEB IDE's project chat (existing, server-persisted) and the CLI/Plugin chat are **separate, non-synced** conversations. Same backend service, different storage paths.

## File layout in `clients/`

```
clients/
├── core/                    # shared TS lib (already exists)
│   ├── src/
│   │   ├── chat.ts          # NEW: chat client (SSE consumer, conversation manager)
│   │   ├── localState.ts    # NEW: ~/.executespec/ read/write
│   │   ├── autoLink.ts      # NEW: project auto-create logic
│   │   ├── api.ts           # existing — extend with /api/spec/chat
│   │   ├── tools/runtime.ts # existing — ToolRuntime for Shape B
│   │   └── ...
├── cli/                     # the CLI
│   ├── src/
│   │   ├── index.ts         # NEW: `spec` entry (drops into chat REPL)
│   │   ├── repl.ts          # NEW: interactive chat REPL (readline-based)
│   │   ├── render.ts        # NEW: ANSI rendering of tool calls + assistant turns
│   │   ├── commands/auth.ts # existing
│   │   └── commands/run.ts  # KEEP only the experimental `spec run local` non-interactive mode
└── vscode/                  # extension
    ├── src/
    │   ├── extension.ts     # existing — wire chat panel
    │   ├── chatPanel.ts     # NEW: webview-based chat sidebar
    │   ├── chatPanel.html   # NEW: the chat UI (mirrors CLI render)
    │   ├── client.ts        # existing
    │   ├── runsTree.ts      # existing — keep as supplementary view
    │   └── ...
```

## Phase plan (revised)

| Phase | Span | Output |
|---|---|---|
| **1. Backend action tools** + stateless chat endpoint | 2-3 days | `POST /api/spec/chat` lives; action tools execute against authenticated user |
| **2. CLI chat REPL** + local state + auto-link | 2-3 days | `spec` works end-to-end (REMOTE-mode runs only) |
| **3. Shape B activation wiring** (PipelineRun.executionLocality + RunOrchestrator skip-clone + skip-push + dispatcher register/unregister) | 1-2 days | LOCAL mode flips on; tool calls route to CLI's local tree |
| **4. VS Code chat sidebar webview** (mirrors CLI render) | 2-3 days | Plugin = sidebar chat |
| **5. Privacy hardening** (BYOK-only LOCAL + audit-only-no-content server logging) | 1-2 days | "Your code → your provider only" pitch defensible |
| **6. Polish + marketplace publish** | 2-3 days | Ship-ready |
| **7. Cursor-mode (later)** — inline gutter actions, completions, watcher | 2 weeks | Separate effort |

Total V1: **~10-15 days**.

## Risks + mitigations

| Risk | Mitigation |
|---|---|
| Large conversation context payloads (no server-side state) | Client-side compaction: keep last N turns verbatim, summarize older |
| Server-stateless chat can't easily replay | OK — chat audit record has cost/timing; users have full history locally |
| Action tool authorization (a user shouldn't accidentally cancel another's run) | Reuse existing tenant access guards on each action tool — `assertCanWrite` on the run |
| Auto-link surprises user in `/tmp/foo` | System-dir guard list + first-time confirmation message in chat |
| Local file permissions on `~/.executespec/auth.json` (PAT) | chmod 600 on write; same as `.secrets.env` pattern used elsewhere |
| Web Project Chat and CLI chat diverge | Document the difference clearly in `spec` docs + plugin README. Future: optional sync via export/import. |

## Sequencing within Phase 1

1. **`docs/design/` for this doc** (done — you're reading it)
2. Define `SpecChatController` skeleton + `POST /api/spec/chat` endpoint signature
3. Add new action tools to a fresh `ProjectChatActionTools` (separate from read-only `ProjectChatTools` so they can be auth-gated independently)
4. Extend `ChatService` to accept a "stateless mode" flag — when true, doesn't persist messages
5. Wire SSE token streaming for the new endpoint
6. Tests for each action tool (auth gates + happy path)
7. Audit log table `spec_chat_audit` (V217 migration) — schema: `id, session_id, project_id, user_id, started_at, ended_at, turn_count, cost_usd, model_used, ip_hash` (no content fields)
8. Commit + push (after bench completes, to not disrupt)

This doc lands first; coding begins next.
