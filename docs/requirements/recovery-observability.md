# Recovery & Observability — Requirements

## Overview

Automatic detection and recovery of stuck tasks, real-time SSE event streaming, and context window management with proactive compaction.

---

## Features

### 1. Stuck Run Monitor

**Requirements:**
- Scheduled task runs every 2 minutes (`@Scheduled`)
- Detect tasks with status=WORKING/LOCKED idle beyond threshold
- Warning at 15 minutes (log only)
- Recovery action at 30 minutes
- Emit SSE events: RECOVERY_ATTEMPTED, RUN_RESTARTED
- Record all actions in `run_recovery` table

### 2. Stuck Run Evaluator

**Requirements:**
- Classify stuck tasks by analyzing recent event log (last 20 events)
- Classifications:
  - `LLM_TIMEOUT` — outbound LLM call with no response
  - `LLM_LOOP` — same tool called ≥ 3 times (loop threshold)
  - `FILE_LOCK_BLOCK` — blocked waiting for file lock
  - `BUILD_FAILURE_STALL` — build failures without progress
  - `UNKNOWN` — cannot determine cause

### 3. Recovery Actions

**Requirements:**
- LLM_TIMEOUT, FILE_LOCK_BLOCK → retry task (up to 1 retry)
- LLM_LOOP, BUILD_FAILURE_STALL, UNKNOWN → fresh restart
- Auto-restart for transient failures (rate limits, 503, timeout):
  - Transient keywords: "rate", "429", "overloaded", "503", "timeout"
  - Backoff: 60 seconds between restarts
  - Max auto-restarts: 3
- Escalation ladder:
  1. Upgrade to capable model (same provider)
  2. Switch to alternate provider entirely
  3. Fresh run restart

### 4. LLM Call Guard

**Requirements:**
- Wraps LLM calls with error classification and retry for scout agents
- Retry up to 3 times for transient failures
- Rate limit (429): sleep then retry
- Server error (500/503): exponential backoff
- Budget exhausted: escalate provider immediately
- Auth failure (401): throw immediately (needs human action)

### 5. Provider Health Tracking (Redis-backed Circuit Breaker)

**Requirements:**
- Track consecutive failure counts per provider via Redis (shared across threads/instances)
- 5 consecutive failures = mark provider unhealthy (circuit OPEN)
- 60-second auto-recovery via Redis TTL expiry
- LlmRouter skips unhealthy providers in fallback chain; ModelRouter also filters by health during model selection
- Fail-open on Redis errors (never block LLM calls due to Redis unavailability)
- Metrics: `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed`

### 6. Server-Sent Events (SSE)

**Requirements:**
- Real-time event streaming: `GET /api/runs/{id}/events`
- SseBroadcaster: in-memory, no-timeout emitters
- LB cookie affinity ensures clients hit same instance as pipeline thread
- Event types:
  - Pipeline: RUN_STATUS, TASK_STATUS, LOG, GATE_OPENED, GATE_RESOLVED
  - Cost: COST_UPDATE, COMPLETE
  - Recovery: RECOVERY_ATTEMPTED, RUN_RESTARTED
  - Deployment: DEPLOY_STARTED, DEPLOY_BUILDING, DEPLOY_PUSHING, DEPLOY_LIVE, DEPLOY_FAILED
  - Validation: VALIDATION_STARTED, VALIDATION_PASSED, VALIDATION_FAILED, MODEL_ESCALATED
  - Review: REVIEW_STARTED, REVIEW_ISSUE_FOUND, REVIEW_AUTO_FIX_STARTED, REVIEW_AUTO_FIX_RESULT, REVIEW_COMPLETE
- RunEvent record: `(type, runId, taskId, payload, timestamp)` with factory methods
- Auto-prune dead emitters on send failure

### 7. Token Budget & Compaction

**Requirements:**
- Monitor context window usage per task against model limits
- Anthropic: 200k tokens, OpenAI: 128k tokens
- Four status levels:
  - OK (< 25%): continue normally
  - WARN (≥ 25%): log warning
  - COMPACT (≥ 35%): automatic message compaction
  - HARD_STOP (≥ 55%): emergency compact or force report
- HIGH complexity tasks get +5% extra headroom
- Compaction: summarize middle messages via LLM, keep first 2 + last 18
- Emergency compaction: try compaction before hard-stop, only stop if recently compacted
- Output token limits per complexity: LOW=2048, MEDIUM=4096, HIGH=8192, ceiling=14400

### 8. Cost Estimation

**Requirements:**
- Pre-run estimation:
  - Low estimate: 1 iteration per worker
  - High estimate: 3 iterations (accounts for retries)
  - Add planner + reviewer fixed costs
  - Display to user before plan approval
- Post-run actual cost (cache-aware):
  - Normal input: 1.00x rate
  - Cache-write: 1.25x rate
  - Cache-read: 0.10x rate (90% discount)
  - Output: 1.00x rate
- DB-backed pricing (`model_pricing` table), editable via REST
- Endpoints: `GET /api/pricing`, `PUT /api/pricing/{provider}/{model}`
- Feasibility endpoint: `POST /api/feasibility` (estimate without creating run)

---

## Configuration

```yaml
agent:
  recovery:
    enabled: true
    warn-threshold-minutes: 15
    act-threshold-minutes: 30
    max-task-retries: 1
    max-auto-restarts: 3
    auto-restart-backoff-seconds: 60
  token-budget:
    max-tokens: 16000
    hard-ceiling-pct: 0.90
  compaction:
    warn-pct: 0.25
    compact-pct: 0.35
    hard-stop-pct: 0.55
  concurrency:
    max-iterations: 80
```

## Key Files

| File | Purpose |
|------|---------|
| `recovery/StuckRunMonitor.java` | Scheduled stuck detection |
| `recovery/StuckRunEvaluator.java` | Stuck classification |
| `recovery/RunRecoveryService.java` | Recovery orchestration |
| `recovery/LlmCallGuard.java` | Retry-wrapped LLM calls |
| `recovery/EscalationService.java` | Model/provider escalation |
| `router/ProviderHealthTracker.java` | Provider health tracking |
| `sse/RunEvent.java` | Event record + factories |
| `sse/SseBroadcaster.java` | SSE emitter management |
| `agent/TokenBudgetMonitor.java` | Context window monitoring |
| `agent/CostEstimator.java` | Cost estimation |
