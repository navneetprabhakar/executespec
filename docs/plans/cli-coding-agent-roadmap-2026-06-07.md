# CLI coding-agent experience — roadmap (2026-06-07)

> Goal (user): take the unified `spec` CLI from "looks like Gemini/Claude-Code" to "behaves like
> one." Constraint: **provider-agnostic** (no Anthropic-only code) and **remote-brain preserved** —
> the planner/worker/reviewer/scoring moat stays server-side; the CLI is hands + rendering.
> Tracking: RL-2026-06-014. Companion docs: `clients/cli/{ARCHITECTURE,CODEMAP}.md`.

## Architectural frame

The other coding CLIs (Gemini CLI, Claude Code, Codex, vix) are **local-brain**: the agent loop runs
on the user's machine and edits local files. ExecuteSpec is **remote-brain** by design. We deliver the
same *feel* via **local-hands / remote-brain**: the brain stays in the cloud; tool execution (file
edits, shell, tests) is proxied to the user's machine. The right peer is `gh`/`vercel`/`fly` plus a
local execution layer — not a reimplementation of Claude Code.

## Status

| Phase | What | Status |
|---|---|---|
| **P1** | Auto-login on first run (gh-style) | ✅ shipped (RL-2026-06-014), CLI 14/14 |
| **P2** | Inline run streaming in the chat TUI (plan/tasks/files/score + inline gate) | ✅ shipped (RL-2026-06-014) |
| **—** | OAuth device-login fix (Google/GitHub onboarding) + welcome screen | ✅ shipped (RL-2026-06-014) |
| **P3** | Local-hands activation — the agent edits YOUR working tree | ⏸ **held for review** (touches core pipeline) |
| **P4** | Rich rendering — markdown + syntax-highlighted diffs, `@file` mentions | ⏳ deferred |

## P3 — Local-hands activation (the keystone)

**What it unlocks:** the agent reads/writes/shell/tests against the user's actual working tree, so
`executespec run local "..."` (or a chat turn) edits local code in the terminal — the core Claude-Code
experience — while planning/scoring/review stay server-side.

**Why it's credible, not greenfield.** The remote-tool loop already works in production-adjacent code:
the Terminal-Bench adapter runs ExecuteSpec's `engine/WorkerAgent` with local hands today
(`ToolRegistry.isRemoteRun`/`dispatchRemote` → `RemoteToolDispatcher`; the worker emits a `TOOL_CALL`,
the client runs it, POSTs the envelope to `/api/runs/{id}/tool-results`). The client side is built and
shipped: `clients/core` `ToolRuntime` (fail-closed: shell/build/test/gitCommit need an approval
policy) + `LocalExecutionSession` + the CLI `run local` driver. **The only missing piece is wiring the
same loop into the customer `run local` path in `RunOrchestrator`.**

**Why it's held for review (not done autonomously):** it edits `integration/RunOrchestrator` — core
pipeline code — and there is in-flight worker WIP nearby (`tool/*`, `engine/WorkerAgent`). A blind
change risks the default pipeline + merge conflicts. This needs a supervised, flag-gated landing.

**Implementation outline (all behind a `LOCAL` execution mode; default path byte-identical):**
1. **Submit:** `RunRequest.localExecution=true` → `RunOrchestrator` registers a remote session for the
   runId (a `RemoteToolDispatcher` session keyed by runId) instead of the normal flow.
2. **Skip clone:** in LOCAL mode, do not clone the repo into the server workspace — the working tree
   is the client's. The worker's file/shell/test tools route through `dispatchRemote` (already the TB
   path) instead of the host workspace.
3. **Drive tools over SSE:** worker emits `TOOL_CALL`; the client `LocalExecutionSession` runs it via
   `ToolRuntime` (permission-gated — pass `createInteractivePermissions(ask)` so shell/edits prompt
   y/N/a) and POSTs the envelope back. This loop already exists; LOCAL mode just turns it on for a
   customer run.
4. **Skip auto-push:** at run end, do **not** `ScmPushService.push` — nothing is published; the diff
   lives in the user's tree. Surface the diff/summary instead.
5. **Lifecycle:** unregister the session on terminal/cancel/disconnect; a dropped client must fail the
   run cleanly (no orphaned server loop).
6. **Flag:** gate the whole LOCAL path on a feature flag (default OFF) so production is byte-identical
   until validated; add a `V###` seed migration.

**Verification:** a `RunOrchestrator` LOCAL-mode integration test (mock client session: TOOL_CALL →
envelope → continue → terminal, no clone, no push) + an end-to-end `run local` smoke against the local
backend (greenfield spec → local file written → no PR).

**Open questions for the user (P3):**
- Land P3 on this branch, or a fresh `feature/cli-local-hands` (it's a bigger, riskier change than
  P1/P2 and brushes the worker WIP)?
- Default the interactive permission prompt to per-command y/N/a (recommended) — confirm.
- LOCAL runs: still charge credits (planning happens server-side) or free (no cloud execution)?

## P4 — Rich rendering (deferred)

- Markdown in the transcript (headers/bold/lists/inline code/code blocks) — hand-rolled markdown→Ink
  to stay zero-dep (like the gradient), or adopt a small renderer. Assistant replies + run summaries.
- Syntax-highlighted **diffs** for `FILE_WRITTEN`/local edits (reuse the web `CodeView` tokenizer
  approach, ported to ANSI).
- `@file` mentions: read a local file into the spec/chat context (client-side; the brain stays remote).
- Token-level streaming is a **server** change (the chat router is per-round today) — polish, last.

## Invariant

Feature parity with the VS Code client: every capability that ships to the CLI ships to the extension
(`clients/vscode`), per `ADR-0001`.
