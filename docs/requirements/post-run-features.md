# Post-Run Features — Requirements

## Overview

Post-run interactive features: conversational AI chat about generated code, run iteration, run management (pause/edit/restart/retry), and automated post-merge agents.

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
- Model: `claude-haiku-4-5-20251001` (UTILITY_CHAIN) for cost efficiency
- Max output: 2048 tokens
- Endpoints:
  - `POST /api/runs/{id}/conversation` — send message, get reply
  - `GET /api/runs/{id}/conversation` — get history
- HTTP 429 when message limit reached
- Frontend: RunConversation.tsx with chat UI, message counter, typing indicator

### 2. Run Iteration (Chat → New Run)

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
  - Status → PAUSED
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
- After all tasks complete: full review → auto-merge → report cycle
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
  - Follows full review → auto-merge → report cycle
- Rework: `POST /api/runs/{id}/rework`
  - Re-runs flagged tasks with reviewer feedback injected
  - Follows full review → auto-merge → report cycle

### 7. Post-Merge Agents

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
- Triggers when failure rate > 20% and pattern occurs ≥ 2 times
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
| `api/controller/RunController.java` | Run lifecycle endpoints |
| `harness/PipelineHarness.java` | Pause, edit, restart, retry, iterate |
| `context/ContextAgent.java` | Post-merge documentation updates |
| `optimiser/PromptOptimiserAgent.java` | Self-improving prompts |
| `apitests/ApiTestSuiteAgent.java` | OpenAPI spec generation |
| `harness/event/PostMergeOrchestrator.java` | Post-merge event handler |
| `frontend/src/components/runs/RunConversation.tsx` | Chat UI |

## Database Migrations

| Version | Description |
|---------|-------------|
| V9 | Conversation history + iteration run linkage |
