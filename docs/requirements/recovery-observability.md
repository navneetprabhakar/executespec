# Recovery & Observability — Requirements

> Last updated: 2026-04-03

## Overview

Automatic detection and recovery of stuck tasks, real-time SSE event streaming, context window management with proactive compaction, cost estimation, rate limiting, and custom metrics (30+ Micrometer meters).

---

## Features

### 1. Stuck Run Monitor

**Requirements:**
- Scheduled task runs every 2 minutes (`@Scheduled`)
- Detect tasks with status=WORKING/LOCKED idle beyond threshold
- Warning at 8 minutes (log only)
- Recovery action at 15 minutes (aligns with maxTaskDurationMinutes)
- Orphaned run threshold: 8 minutes
- Emit SSE events: RECOVERY_ATTEMPTED, RUN_RESTARTED
- Record all actions in `run_recovery` table

### 2. Stuck Run Evaluator

**Requirements:**
- Classify stuck tasks by analyzing recent event log (last 20 events)
- Classifications:
  - `LLM_TIMEOUT` — outbound LLM call with no response
  - `LLM_LOOP` — same tool called >= 3 times (loop threshold)
  - `FILE_LOCK_BLOCK` — blocked waiting for file lock
  - `BUILD_FAILURE_STALL` — build failures without progress
  - `UNKNOWN` — cannot determine cause

### 3. Recovery Actions

**Requirements:**
- LLM_TIMEOUT, FILE_LOCK_BLOCK -> retry task (up to 1 retry)
- LLM_LOOP, BUILD_FAILURE_STALL, UNKNOWN -> fresh restart
- Auto-restart for transient failures (rate limits, 503, timeout):
  - Transient keywords: "rate", "429", "overloaded", "503", "timeout", "502", "all providers", "rate limit", "service unavailable"
  - Backoff: 90 seconds between restarts
  - Max auto-restarts: 2
- Auto-resume on startup: disabled (`auto-resume-on-startup: false`) — manual resume via UI to avoid surprise restarts
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
- LlmRouter skips unhealthy providers in fallback chain; ModelResolver also filters by health during model selection
- Fail-open on Redis errors (never block LLM calls due to Redis unavailability)
- Metrics: `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed`

### 6. Server-Sent Events (SSE)

**Requirements:**
- Real-time event streaming: `GET /api/runs/{id}/events`
- SseBroadcaster: in-memory, no-timeout emitters
- LB cookie affinity ensures clients hit same instance as pipeline thread
- Event type format: SCREAMING_SNAKE_CASE (e.g., `RUN_STATUS`, not `runStatus`)
- Event types:
  - Pipeline: RUN_STATUS, TASK_STATUS, LOG, GATE_OPENED, GATE_RESOLVED
  - Pipeline phases: COMPILING, MERGING, AWAITING_MERGE
  - Cost: COST_UPDATE, COMPLETE
  - Recovery: RECOVERY_ATTEMPTED, RUN_RESTARTED
  - Deployment: DEPLOY_STARTED, DEPLOY_BUILDING, DEPLOY_PUSHING, DEPLOY_LIVE, DEPLOY_FAILED
  - Preview: PREVIEW_STARTED, PREVIEW_READY, PREVIEW_FAILED, PREVIEW_STOPPED
  - Validation: VALIDATION_STARTED, VALIDATION_PASSED, VALIDATION_FAILED, MODEL_ESCALATED
  - Review: REVIEW_STARTED, REVIEW_ISSUE_FOUND, REVIEW_AUTO_FIX_STARTED, REVIEW_AUTO_FIX_RESULT, REVIEW_COMPLETE
  - Archive: RUN_ARCHIVED
- RunEvent record: `(type, runId, taskId, payload, timestamp)` with factory methods
- Auto-prune dead emitters on send failure
- Frontend SSE refetch triggers include COMPILING, MERGING, AWAITING_MERGE

### 7. Token Budget & Compaction

**Requirements:**
- Monitor context window usage per task against model limits
- Anthropic: 200k tokens, OpenAI: 128k tokens
- Four status levels:
  - OK (< 25%): continue normally
  - WARN (>= 25%): log warning
  - COMPACT (>= 35%): automatic message compaction
  - HARD_STOP (>= 55%): emergency compact or force report
- HIGH complexity tasks get +5% extra headroom
- Compaction: summarize middle messages via LLM, keep first 2 + last 18 (sliding window)
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

### 9. Rate Limiting

**Requirements:**
- Redis-backed API rate limiting via `RateLimitFilter`
- Registered after JwtAuthenticationFilter in security chain
- Per-endpoint-group policies: AUTH, RUNS, WEBHOOK
- Tier-aware: different limits per user tier (FREE, PRO, ENTERPRISE)
- Rate limit identifier: authenticated user ID or client IP for anonymous
- Standard rate limit headers on all responses
- Skips health checks and static resources
- Configuration: `executespec.rate-limit.*` properties with master enable/disable switch

### 10. Custom Metrics (Micrometer)

**Requirements:**
- 30+ custom Micrometer meters registered in `CustomMetricsConfig`
- All metrics prefixed with `executespec.*`

**Run metrics:**
- `executespec.run.total` (counter) — total runs by status
- `executespec.run.duration` (timer) — end-to-end run duration
- `executespec.run.active` (gauge) — currently active runs
- `executespec.run.queued` (gauge) — queued runs

**Task metrics:**
- `executespec.task.total` (counter) — tasks by status
- `executespec.task.duration` (timer) — per-task duration

**LLM metrics:**
- `executespec.llm.calls` (counter) — LLM calls by provider/model
- `executespec.llm.tokens.input` (counter) — input tokens
- `executespec.llm.tokens.output` (counter) — output tokens
- `executespec.llm.errors` (counter) — LLM errors by type (rate_limit, server_error, etc.)
- `executespec.llm.fallback` (counter) — provider fallback events
- `executespec.llm.duration` (timer) — per-call latency
- `executespec.llm.retry_count` (counter) — LLM retries

**Tool metrics:**
- `executespec.tool.calls` (counter) — tool invocations

**Validation metrics:**
- `executespec.validation.total` (counter) — validation runs

**Gate metrics:**
- `executespec.gate.wait_time` (timer) — plan gate wait duration

**Handover metrics:**
- `executespec.handover.duration` (timer) — agent handover latency

**Monitor metrics:**
- `executespec.monitor.stuck_detected` (counter) — stuck run detections

**Preview metrics:**
- `executespec.preview.starts` (counter) — preview starts
- `executespec.preview.duration` (timer) — total preview lifecycle
- `executespec.preview.build_duration` (timer) — preview build time
- `executespec.preview.deploy_duration` (timer) — preview deploy time
- `executespec.preview.failures` (counter) — preview failures
- `executespec.preview.active` (gauge) — active previews

**Deploy metrics:**
- `executespec.deploy.total` (counter) — deployment starts
- `executespec.deploy.duration` (timer) — total deploy lifecycle
- `executespec.deploy.build_duration` (timer) — deploy build time
- `executespec.deploy.health_check_duration` (timer) — health check latency
- `executespec.deploy.failures` (counter) — deploy failures
- `executespec.deploy.active` (gauge) — active deploys

**Circuit breaker metrics:**
- `executespec.provider.circuit_opened` (counter) — circuit breaker opens
- `executespec.provider.circuit_closed` (counter) — circuit breaker closes

**Observability stack:**
- Local: Micrometer + OTel -> Prometheus + Tempo + Grafana (docker-compose in `observability/`)
- GCP: Cloud Trace (traces) + Cloud Monitoring (metrics) via `@Profile("gcp")` exporters
- Structured logging with traceId/spanId via Micrometer context propagation (MDC auto-populated)
- Dashboards: ExecuteSpec Overview + JVM/HTTP (Grafana)

---

## Configuration

```yaml
agent:
  recovery:
    enabled: true
    warn-threshold-minutes: 8
    act-threshold-minutes: 15
    max-task-retries: 1
    max-auto-restarts: 2
    auto-restart-backoff-seconds: 90
    orphaned-run-threshold-minutes: 8
    auto-resume-on-startup: false
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
| `router/ProviderHealthTracker.java` | Redis-backed circuit breaker |
| `sse/RunEvent.java` | Event record + factories |
| `sse/SseBroadcaster.java` | SSE emitter management |
| `agent/TokenBudgetMonitor.java` | Context window monitoring |
| `agent/CostEstimator.java` | Cost estimation |
| `config/CustomMetricsConfig.java` | 30+ custom Micrometer meters |
| `config/ObservabilityConfig.java` | OTel + trace configuration |
| `ratelimit/RateLimitFilter.java` | Redis-backed API rate limiting |
| `ratelimit/RateLimitPolicy.java` | Per-group, per-tier rate policies |
| `ratelimit/RateLimitProperties.java` | Rate limit configuration properties |
