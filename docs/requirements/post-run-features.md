# Post-Run Features — Requirements

> Last updated: 2026-04-03

## Overview

Post-run interactive features: conversational AI chat about generated code, run iteration, run management (pause/edit/restart/retry), automated post-merge agents, run archival, GitHub PR creation, and background validation.

---

## Features

### 1. Post-Run Conversation

**Purpose:** After a run completes, users can ask questions about the generated code.

**Requirements:**
- ConversationAgent with workspace context injection:
  - File tree of workspace
  - README.md (up to 4k chars)
  - CODEBASE_CONTEXT.md (up to 3k chars)
  - Plan JSON (up to 2k chars)
  - Review JSON (up to 1k chars)
- Up to 10 user messages per run
- Persistent conversation history in DB (chatHistoryJson on PipelineRun)
- Model: LIGHTWEIGHT tier (claude-haiku-4-5-20251001 / gemini-3-flash-preview / gpt-5.4-mini) for cost efficiency
- Max output: 2048 tokens
- Endpoints:
  - `POST /api/runs/{id}/conversation` — send message, get reply
  - `GET /api/runs/{id}/conversation` — get history
- HTTP 429 when message limit reached
- Frontend: RunConversation.tsx with chat UI, message counter, typing indicator

### 2. Run Iteration (Chat -> New Run)

**Purpose:** Take insights from conversation and create a new run that iterates on the code.

**Requirements:**
- "Implement as New Run" button in conversation panel
- User provides change description (or uses last user message as default)
- Endpoint: `POST /api/runs/{id}/iterate` with `{request: string}`
- Creates new run with:
  - Workspace copied from source run
  - `sourceRunId` linkage for traceability
  - User's change description as the new goal
- Refresh sidebar run list and navigate to new run
- Success toast notification

### 3. Run Pause & Edit

**Purpose:** Pause an active run, edit the goal, and restart.

**Requirements:**
- Pause: `POST /api/runs/{id}/pause`
  - Signals open gates as rejected
  - Cancels all working tasks
  - Status -> PAUSED
- Edit & Restart: `POST /api/runs/{id}/edit-and-restart`
  - Creates a fresh run from paused run with edited goal
  - Carries forward credentials (GitHub PAT, API keys, org, project)
  - Original run remains in PAUSED state for reference

### 4. Task Retry

**Purpose:** Retry individual failed tasks without restarting the entire pipeline.

**Requirements:**
- Endpoint: `POST /api/runs/{id}/tasks/{taskId}/retry`
- Accept task states: PARTIAL, BLOCKED, FAILED, PENDING, LOCKED
- Reset task to PENDING, re-run worker
- Auto-pick up all other incomplete sibling tasks
- After all tasks complete: full review -> auto-merge -> report cycle
- Completed event triggers PostMergeOrchestrator

### 5. Re-review (Legacy: Merge Gate Reopen)

**Purpose:** Re-run reviewer on a PARTIAL/COMPLETE run.

**Requirements:**
- Endpoint: `POST /api/runs/{id}/reopen-merge` (legacy name)
- Re-runs ReviewerAgentV2 on current code
- Auto-merges if review passes (no manual gate)

### 6. Implement Suggestions / Rework

**Purpose:** Act on reviewer feedback without manual intervention.

**Requirements:**
- Implement Suggestions: `POST /api/runs/{id}/implement-suggestions`
  - Re-runs affected workers for MEDIUM/LOW reviewer issues
  - Follows full review -> auto-merge -> report cycle
- Rework: `POST /api/runs/{id}/rework`
  - Re-runs flagged tasks with reviewer feedback injected
  - Follows full review -> auto-merge -> report cycle

### 7. Apply Suggestion (Chat Copilot)

**Purpose:** Apply a single review/chat suggestion as an incremental change on the same run.

**Requirements:**
- Endpoint: `POST /api/runs/{id}/apply-suggestion`
- Creates new tasks scoped to the suggestion
- Runs worker -> compile -> merge cycle
- Triggers validation report generation after merge
- Copilot graph splitting: task graph tab groups nodes by source prefix (`_cop{N}_`, `_rev{N}_`), rendering separate collapsible graphs per copilot/review run
- Per-copilot token breakdown: overview tab shows input/output tokens per copilot run under Live Cost section

### 8. Background Validation

**Purpose:** Run quality checks in the background after the run reaches COMPLETE, without blocking the user.

**Requirements:**
- Run marked COMPLETE immediately after compile + merge
- Auto-preview starts (5-min TTL) for both PROTOTYPE and PRODUCTION modes
- Background validation runs in a virtual thread (`bg-validation-{runId}`):
  1. ReviewerAgentV2 — inspect code, auto-fix issues
  2. TestGenOrchestrator — generate tests
  3. TestPreviewCoordinator — reuse existing preview for test execution
  4. ValidationReportGenerator — produce final report
- User can chat, submit changes, and use preview while validation runs
- SSE cleanup (`broadcaster.complete()`, `runKeyStore.remove()`, `runBudgetTracker.remove()`) deferred to background thread's `finally` block
- `backgroundValidationRuns` ConcurrentHashSet tracks active background validations
- Known limitation: no retry/recovery if background thread fails silently (fire-and-forget)

### 9. GitHub PR Creation

**Purpose:** Automatically push code to GitHub and open a pull request for existing-repo runs.

**Requirements:**
- Triggered automatically by `PipelineHarness.pushToGitHubIfConfigured()` after merge
- Creates branch `executespec/{runId-short}` (not pushed to main)
- Pushes workspace to the branch via `GitTool.pushToBranch()`
- Opens PR automatically via `GitHubRepoService.createPullRequest()`
  - Base branch: detected via `GitHubRepoService.getDefaultBranch()`
- PR URL stored in `PipelineRun.githubPrUrl` (V52 migration)
- Surfaced in `RunDetail` API response and frontend overview tab
- Requires `GITHUB_PAT` or per-run GitHub token

### 10. Run Archive

**Purpose:** Allow users to archive terminal runs they no longer need in their active list.

**Requirements:**
- Endpoint: `POST /api/runs/{id}/archive`
- Only terminal runs can be archived: COMPLETE, FAILED, CANCELLED, PARTIAL
- Irreversible — archived runs are permanently hidden from default run list
- Run list endpoint accepts `?archived=false` (default) or `?archived=true` to filter
- SSE event: `RUN_ARCHIVED`
- DB: `archived_at TIMESTAMPTZ` column on `pipeline_run` (V53 migration)
- Partial index: `idx_pipeline_run_archived ON pipeline_run (archived_at) WHERE archived_at IS NOT NULL`

### 11. Post-Merge Agents

**Purpose:** Automated improvements after successful merge.

**Requirements:**
- All non-fatal, run in virtual threads
- Triggered by MergeCompleted event

**ContextAgent:**
- Updates CODEBASE_CONTEXT.md and decisions.md based on git diff
- Reads existing docs, scans changes, calls LLM to update
- Commits to main
- Run interval: every completed run

**PromptOptimiserAgent:**
- Analyzes failure patterns across recent runs (window: 20 runs)
- Triggers when failure rate > 20% and pattern occurs >= 2 times
- Makes MINIMUM surgical edits to system prompts
- Run interval: every 10 completed runs

**ApiTestSuiteAgent:**
- Generates OpenAPI 3.0 YAML for backend-only runs
- Skips if frontend files (.html, .jsx, .tsx) detected
- Skips if no controller files found
- Writes to `api-docs/openapi.yaml`
- Sets `run.hasApiSpec = true`

---

## Key Files

| File | Purpose |
|------|---------|
| `agent/ConversationAgent.java` | Post-run chat with workspace context |
| `api/controller/RunConversationController.java` | Chat REST endpoints |
| `api/controller/RunController.java` | Run lifecycle endpoints (archive, retry, etc.) |
| `harness/PipelineHarness.java` | Pause, edit, restart, retry, iterate, background validation |
| `context/ContextAgent.java` | Post-merge documentation updates |
| `optimiser/PromptOptimiserAgent.java` | Self-improving prompts |
| `apitests/ApiTestSuiteAgent.java` | OpenAPI spec generation |
| `harness/event/PostMergeOrchestrator.java` | Post-merge event handler |
| `integration/github/GitHubRepoService.java` | Branch, PR creation, default branch lookup |
| `tool/GitTool.java` | Workspace git ops, `pushToBranch()` |
| `report/ValidationReportService.java` | Validation report lifecycle |
| `report/ValidationReportGenerator.java` | Report generation (background) |
| `testgen/TestGenOrchestrator.java` | Test generation orchestrator |
| `testgen/TestPreviewCoordinator.java` | Preview reuse for test execution |
| `testgen/TestResultController.java` | Apply-test-fixes endpoint |
| `frontend/src/components/runs/RunConversation.tsx` | Chat UI |
| `frontend/src/components/runs/ValidationReportPanel.tsx` | Report display |

## Database Migrations

| Version | Description |
|---------|-------------|
| V9 | Conversation history + iteration run linkage |
| V52 | `github_pr_url VARCHAR(512)` on `pipeline_run` |
| V53 | `archived_at TIMESTAMPTZ` on `pipeline_run` + partial index |
