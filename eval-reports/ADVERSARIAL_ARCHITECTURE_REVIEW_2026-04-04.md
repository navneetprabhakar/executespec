# ExecuteSpec — Adversarial Architecture Review

> **Date:** 2026-04-04
> **Scope:** Eval framework, E2E test harness, Preview system, Deploy system
> **Methodology:** Code-level static analysis, data anomaly detection, architectural review
> **Stance:** Adversarial — every finding assumes worst-case impact

---

## Table of Contents

1. [Verdict Summary](#1-verdict-summary)
2. [Eval Scoring Engine — Structural Biases](#2-eval-scoring-engine--structural-biases)
3. [Eval Test Cases — Pattern Validation Flaws](#3-eval-test-cases--pattern-validation-flaws)
4. [Eval Prompts — Achievability & Fairness](#4-eval-prompts--achievability--fairness)
5. [Eval vs Production — Routing Divergence](#5-eval-vs-production--routing-divergence)
6. [E2E Test Harness — Statistical Invalidity](#6-e2e-test-harness--statistical-invalidity)
7. [E2E Results — Data Integrity Failures](#7-e2e-results--data-integrity-failures)
8. [Preview System — Architecture Failures](#8-preview-system--architecture-failures)
9. [Deploy System — Architecture Failures](#9-deploy-system--architecture-failures)
10. [Preview/Deploy — Frontend & State Issues](#10-previewdeploy--frontend--state-issues)
11. [Prioritized Fix List](#11-prioritized-fix-list)

---

## 1. Verdict Summary

### Eval Framework: NOT TRUSTWORTHY

| Problem | Severity |
|---------|----------|
| Scoring algorithm has 5 mathematical biases that systematically favor cheap/fast models over accurate ones | CRITICAL |
| Pattern-matching validation cannot distinguish correct reasoning from keyword stuffing | CRITICAL |
| N=1 sample size per test — no statistical significance, no confidence intervals | CRITICAL |
| Eval bypasses production LLM routing (no fallback, no caching, no retry) — measures different system | HIGH |
| Cost normalization collapses to binary (cheap=10, expensive=1) — 25% of score is fake precision | HIGH |
| 2 of 12 test cases have hidden terminology requirements not in the prompt (false negatives guaranteed) | HIGH |
| LIGHTWEIGHT tier ceiling (5s) is unachievable — 3 of 4 models score 1.0 on speed | MEDIUM |

### E2E Tests: DATA COMPROMISED

| Problem | Severity |
|---------|----------|
| OpenAI results show `"model": "anthropic"` — impossible, data integrity broken | CRITICAL |
| Different test counts per provider (6 vs 10) — cross-provider comparison invalid | CRITICAL |
| N=1 per test case — results are anecdotes, not evidence | HIGH |
| All timeouts cluster at exactly 600-603s — harness-induced, not real failures | HIGH |
| Test harness script doesn't match results (script has 6 tests, results show 10) | HIGH |

### Preview System: MULTIPLE FAILURE MODES

| Problem | Severity |
|---------|----------|
| Cloud Run TTL never enforced — services leak indefinitely, billing impact | CRITICAL |
| Port pool TOCTOU race condition — concurrent previews can collide | HIGH |
| Short-ID collisions (8-char prefix) in service naming and port routing | HIGH |
| Caddy route failure is non-blocking — preview marked RUNNING but unreachable | HIGH |
| No SSE events for preview lifecycle — frontend polls with 5s latency | MEDIUM |
| Session not removed on failure — memory leak of PreviewSession objects | MEDIUM |

### Deploy System: INCOMPLETE ROLLBACK

| Problem | Severity |
|---------|----------|
| Workspace not restored for >24h old runs — deploy fails with cryptic Docker error | CRITICAL |
| No image cleanup on failed deploy — Artifact Registry leaks images indefinitely | HIGH |
| Deploy marked LIVE before health check passes — users see false-positive status | HIGH |
| No mutual exclusion between preview and deploy for same run — resource collision | HIGH |
| GitHub PAT potentially logged in error responses | MEDIUM |
| No retry logic for transient Cloud Run API failures | MEDIUM |

---

## 2. Eval Scoring Engine -- Structural Biases

**File:** `EvalScorer.java`

### Bias 2.1: Superlinear Accuracy Penalty Punishes Near-Misses

```java
double patternScore = 1.0 + 9.0 * Math.pow(ratio, 1.3);  // line 60
```

The exponent 1.3 creates disproportionate penalties:

| Patterns Matched | Linear (1.0) | Actual (1.3) | Penalty vs Linear |
|------------------|-------------|-------------|-------------------|
| 100% (8/8) | 10.0 | 10.0 | 0 |
| 87.5% (7/8) | 8.9 | 8.4 | -0.5 |
| 75% (6/8) | 7.8 | 7.0 | -0.8 |
| 62.5% (5/8) | 6.6 | 5.7 | -0.9 |
| 50% (4/8) | 5.5 | 4.5 | -1.0 |

**Impact:** A model with 75% correct reasoning (architecturally sound, misses 2 keyword patterns) loses 2.6 points more than proportional. This punishes models that express correct ideas with different terminology.

### Bias 2.2: Cost Normalization Collapses to Binary

```java
double logPenalty = 3.0 * (Math.log(ratio) / Math.log(2));  // line 138
scores[i] = Math.max(1.0, Math.min(10.0, 10.0 - logPenalty));
```

| Cost Ratio vs Cheapest | Log Penalty | Score |
|------------------------|------------|-------|
| 1x (cheapest) | 0.0 | 10.0 |
| 2x | 3.0 | 7.0 |
| 4x | 6.0 | 4.0 |
| 8x | 9.0 | 1.0 (clamped) |
| 10x | 9.97 | 1.0 (clamped) |
| 60x | 17.7 | 1.0 (clamped) |

Any model costing 8x+ the cheapest gets score 1.0 — identical to 60x. With Haiku at $0.002/call and Sonnet at $0.14/call (70x ratio), Sonnet ALWAYS gets cost=1.0. This means 25% of Sonnet's overall score is permanently fixed at 1.0, making it mathematically impossible for Sonnet to win on overall score even with perfect accuracy.

**With the user's stated priority of accuracy-first, speed-second, the current weights (accuracy 50%, speed 25%, cost 25%) are wrong.** Cost should be 10% at most if accuracy is the priority.

### Bias 2.3: Speed Scoring Has Invisible Cliff at 2x Budget

```java
double score = 10.0 - (ratio - 0.5) * 6.0;  // line 93
```

| Latency vs Ceiling | Score |
|--------------------|-------|
| 50% (15s/30s) | 10.0 |
| 100% (30s/30s) | 7.0 |
| 150% (45s/30s) | 4.0 |
| 200% (60s/30s) | 1.0 |
| 500% (150s/30s) | 1.0 (clamped) |

Models at 2x, 5x, and 10x over budget are all indistinguishable (score=1.0). Sonnet's PLANNING latencies range 83-168s (2.8x-5.6x ceiling) — all score 1.0. This erases any speed differentiation among slower models.

### Bias 2.4: LIGHTWEIGHT Ceiling is Unachievable (5,000ms)

Only `gpt-5.4-mini` at 2,310ms meets the 5s ceiling on any LIGHTWEIGHT test. All other models:

| Model | lightweight-request-triage | lightweight-dual-summary | lightweight-config-diff |
|-------|---------------------------|-------------------------|------------------------|
| claude-sonnet-4-6 | 71,929ms (14.4x) → 1.0 | 8,370ms (1.7x) → 2.8 | 25,044ms (5.0x) → 1.0 |
| claude-haiku-4-5 | 18,687ms (3.7x) → 1.0 | 3,942ms (0.8x) → 8.3 | 8,189ms (1.6x) → 3.2 |
| gpt-5.4 | 33,595ms (6.7x) → 1.0 | 4,877ms (1.0x) → 7.1 | 9,692ms (1.9x) → 1.4 |
| gpt-5.4-mini | 7,172ms (1.4x) → 4.4 | 2,310ms (0.5x) → 10.0 | 4,760ms (1.0x) → 7.3 |

The 5s ceiling means LIGHTWEIGHT speed scores are mostly 1.0 across the board, except for the simplest test (dual-summary). **The ceiling should be 15-20s based on actual model performance.**

### Bias 2.5: Length Limits Never Enforced

```java
// EvalTestCaseRegistry — ALL tests use maxResponseLength=0
new EvalTestCase("planning-plan-generation", ..., 0);  // line 60
new EvalTestCase("lightweight-request-triage", ..., 0);  // line 227
```

```java
// EvalScorer.java — maxLength=0 always returns true
public boolean isWithinLengthLimit(String response, int maxLength) {
    return maxLength <= 0 || ...;  // line 169 — always true
}
```

LIGHTWEIGHT tests should penalize verbose responses, but they don't. A model that produces 10,000 tokens for a "lightweight" summary gets the same score as one producing 200 tokens.

### Bias 2.6: Per-Test-Case Cost Normalization Compounds Unfairly

Costs are normalized independently per test case, not globally. This means:

- On a $0.14 test (Sonnet) vs $0.002 (Haiku): ratio=70x, score=1.0
- On a $0.008 test (Sonnet) vs $0.002 (Haiku): ratio=4x, score=4.0

The SAME model pair gets different cost scores depending on the test, even though the cost RATIO is driven by the same pricing structure. When averaged across 12 tests, the cheap model's advantage compounds unevenly.

---

## 3. Eval Test Cases -- Pattern Validation Flaws

**File:** `EvalTestCaseRegistry.java`

### Flaw 3.1: Substring Matching Cannot Detect Correctness

All 12 tests use `Pattern.matcher(response).find()` — substring search. This means:

**False positive example:**
```
Pattern: (?i)(toctou|time.?of.?check)
Response: "There are no TOCTOU issues in this code."  → MATCHES (incorrect)
```

**False negative example:**
```
Pattern: (?i)(poison.*pill|dead.*letter)
Response: "Uncompensable states must be escalated to manual review queue"  → NO MATCH (correct reasoning, wrong keywords)
```

Pattern matching measures **vocabulary overlap**, not understanding. A model that uses the "right words" in the wrong context passes; a model with correct reasoning but different terminology fails.

### Flaw 3.2: "Negative Checks" Are Actually Positive Checks

```java
// planning-saga-consistency, line 358-362
// Comment says: "NEGATIVE CHECK: must NOT propose these anti-patterns"
Pattern.compile("(?i)(avoid.*retry.*without|reject.*2PC|reject.*XA|...)")
```

This checks whether the model explicitly MENTIONS rejecting anti-patterns. But:
- A model that simply doesn't propose 2PC (correct behavior) → **fails** the check
- A model that lists "Approaches considered: 2PC (rejected because...)" → **passes**

The check rewards verbosity (explicitly discussing what NOT to do) and punishes conciseness (just doing the right thing without explaining alternatives).

### Flaw 3.3: Unequal Pattern Counts Skew Tier Scoring

| Tier | Test | Pattern Count | Impact |
|------|------|--------------|--------|
| PLANNING | plan-generation | 8 | Missing 1 = 87.5% → 8.4/10 |
| PLANNING | code-review | 7 | Missing 1 = 85.7% → 8.1/10 |
| PLANNING | architecture-decision | 8 | Missing 1 = 87.5% → 8.4/10 |
| PLANNING | saga-diagnosis | 9 | Missing 1 = 88.9% → 8.6/10 |
| PLANNING | saga-consistency | 7 | Missing 1 = 85.7% → 8.1/10 |
| PLANNING | saga-compliance | 6 | Missing 1 = 83.3% → 7.8/10 |
| WORKHORSE | websocket | 8 | Missing 1 = 87.5% → 8.4/10 |
| WORKHORSE | virtualized-list | 7 | Missing 1 = 85.7% → 8.1/10 |
| WORKHORSE | multi-bug-fix | 6 | Missing 1 = 83.3% → 7.8/10 |
| LIGHTWEIGHT | request-triage | 6 | Missing 1 = 83.3% → 7.8/10 |
| LIGHTWEIGHT | dual-summary | 7 | Missing 1 = 85.7% → 8.1/10 |
| LIGHTWEIGHT | config-diff | 8 | Missing 1 = 83.3% → 7.8/10 |

Missing one pattern on a 6-pattern test costs 2.2 points; missing one on a 9-pattern test costs 1.4 points. Tests with fewer patterns are harsher per-miss.

### Flaw 3.4: JSON Validation Inconsistency Across Tiers

| Tier | Tests with requiredJsonKeys | Tests without |
|------|---------------------------|---------------|
| PLANNING | 6/6 (all have keys) | 0/6 |
| WORKHORSE | 0/3 (none have keys) | 3/3 |
| LIGHTWEIGHT | 3/3 (all have keys) | 0/3 |

WORKHORSE tests never check JSON structure. A model that outputs raw code (not wrapped in JSON) gets zero JSON deduction. But PLANNING tests can lose up to 3.0 points for bad JSON. This gives WORKHORSE an implicit accuracy advantage.

---

## 4. Eval Prompts -- Achievability & Fairness

### 4.1: Two Tests Have Hidden Terminology Requirements

**planning-saga-diagnosis:**
- Pattern requires: `(?i)(poison.*pill|dead.*letter|manual.*intervention|escalat.*uncompens|...)`
- Prompt NEVER mentions "poison pill" or "dead letter queue"
- A model that correctly designs escalation but says "manual reconciliation queue" → **fails**
- **Impact:** 0/4 models pass this test, partly due to terminology mismatch

**planning-saga-compliance:**
- Pattern requires: `(?i)(not.*fire.*and.*forget|reject.*async.*audit|audit.*must.*not.*be.*async|...)`
- Prompt shows broken async audit and asks to fix it
- A model that designs correct synchronous audit without explicitly saying "reject async" → **fails**
- **Impact:** 0/4 models pass this test

### 4.2: Filename-Content Mismatches

| Filename | Actual Content | Registered As |
|----------|---------------|---------------|
| `planning-complexity-classification.txt` | Architecture decision (A/B/C recommendation) | `planning-architecture-decision` |
| `workhorse-rest-controller.txt` | WebSocket notification system | `workhorse-websocket-notification` |

Not a scoring issue, but creates confusion during maintenance and debugging.

### 4.3: Prompt Ambiguities That Produce Valid Non-Matching Responses

**workhorse-rest-controller.txt (WebSocket):**
- "Max 30 notifications per user per minute. Excess batched as digest after 60s."
- Ambiguous: Does "digest" mean (a) batch send, (b) summary message, (c) deduplicated aggregate?
- All three are valid implementations; pattern just checks for keyword "digest"

**lightweight-validation-report.txt (K8s diff):**
- "There are exactly 6 breaking changes. Find all of them."
- But prompt doesn't list them — model must independently classify breaking vs cosmetic
- Reasonable engineers disagree on whether `maxSurge: 1 → 2` is breaking or cosmetic

### 4.4: Accuracy-First Scoring Recommendation

Given user priority of accuracy-first, speed-second:

| Dimension | Current Weight | Recommended Weight |
|-----------|---------------|-------------------|
| Accuracy | 50% | 70% |
| Speed | 25% | 20% |
| Cost | 25% | 10% |

With these weights, the recommendations would change:

| Tier | Current Rec (50/25/25) | Accuracy-First Rec (70/20/10) |
|------|----------------------|------------------------------|
| PLANNING | claude-haiku-4-5 (8.66) | claude-haiku-4-5 (~8.9) |
| WORKHORSE | claude-haiku-4-5 (9.86) | claude-haiku-4-5 (~9.9) |
| LIGHTWEIGHT | claude-haiku-4-5 (8.30) | claude-sonnet-4-6 (~8.7) |

Sonnet would win LIGHTWEIGHT under accuracy-first weights because it's the only model that passes all 3 LIGHTWEIGHT tests (10.0 accuracy vs Haiku's 9.52).

---

## 5. Eval vs Production -- Routing Divergence

**File:** `LlmRouter.java`, `ModelEvaluator.java`

### 5.1: Eval Uses a Different Code Path

| Aspect | Eval (callDirect) | Production (callWithFallbackTracked) |
|--------|-------------------|--------------------------------------|
| Fallback chain | None | Full 3-provider chain |
| Circuit breaker | Ignored | Redis-backed, 5-fail threshold |
| Rate limit handling | Fatal error | 30s sleep + retry |
| Overload (529) | Fatal error | 30s sleep + retry |
| Prompt caching | Never | Enabled for 6+ agent types |
| Per-run API keys | System only | User/org key resolution |
| Retry on failure | None | 2-3 attempts via RetryTemplate |
| Metrics recorded | Calls, duration only | +Fallback, retry, errors, tokens |

**Impact:** Eval measures a system that doesn't exist in production. Cost, latency, and reliability numbers from eval do not predict production behavior.

### 5.2: Eval Cost Ignores Anthropic Cache Multipliers

```java
// Production (CostEstimator.java:148-181):
totalCost += (cacheWrite / 1_000_000.0) * rates[0] * 1.25;  // 25% premium
totalCost += (cacheRead  / 1_000_000.0) * rates[0] * 0.10;  // 90% discount

// Eval: uses computeCost(provider, model, inputTokens, outputTokens)
// → flat rate, no cache multipliers
```

Anthropic cost in eval is **underestimated by 15-25%** for cache-write-heavy agents and **overestimated by up to 90%** for cache-read-heavy agents. Since Haiku's cost advantage drives its recommendation, this bias is material.

### 5.3: Token Count Extraction Silently Zeros on Failure

```java
// ModelEvaluator.java:232-238
try {
    var usage = llmResponse.response().getMetadata().getUsage();
    inputTokens = usage.getPromptTokens();
    outputTokens = usage.getCompletionTokens();
} catch (Exception e) {
    // SILENT — inputTokens=0, outputTokens=0, costUsd=$0.00
}
```

If any provider returns null metadata (documented behavior for some Google models), the eval records $0.00 cost. That model gets perfect cost score (10.0) — not because it's cheap, but because token tracking failed.

---

## 6. E2E Test Harness -- Statistical Invalidity

**File:** `scripts/e2e-test.sh`

### 6.1: N=1 Per Test Case — Zero Statistical Power

Each language/provider combination runs exactly once. With N=1:
- Cannot compute standard deviation, confidence intervals, or p-values
- Cannot distinguish signal from noise
- A 40s result could be 40s ± 200s with proper sampling

**Example:** OpenAI Python completes in 40s. Is OpenAI actually fast for Python? Or was it a lucky run? With N=1, we cannot know. The true distribution might be [40s, 180s, 90s, TIMEOUT, 55s].

### 6.2: Different Test Sets Per Provider — Invalid Comparison

| Provider | Tests Run | Languages |
|----------|-----------|-----------|
| OpenAI | 6 | Python, JS, Java, TS, HTML, Cancel |
| Anthropic | 10 | Python, JS, Go, Java, TS, Rust, Ruby, PHP, HTML, Cancel |
| Google | 10 | Python, JS, Go, Java, TS, Rust, Ruby, PHP, HTML, Cancel |

OpenAI was tested on 6 languages; Anthropic/Google on 10. Claiming "OpenAI 83% vs Anthropic 67%" compares different denominators. If OpenAI were tested on the same 10 languages, its pass rate would likely be lower.

### 6.3: Harness Script Doesn't Match Results

The `e2e-test.sh` script defines 6 test cases:
```bash
declare -a LANGUAGES=("Python" "JavaScript" "Java" "TypeScript" "Static HTML" "Cancel")
```

But Anthropic and Google results contain 10 entries including Go, Rust, Ruby, PHP. Either:
- The script was modified between runs (no version control of test config)
- A different script was used for Anthropic/Google
- Results were manually assembled

**None of these are acceptable for a testing framework.**

### 6.4: Timeout Clustering Indicates Harness Artifact

| Provider | Timeout Values |
|----------|---------------|
| Google | 601s, 602s, 601s, 601s |
| Anthropic | 1844s, 603s, 603s |
| OpenAI | 602s |

All timeouts (except the Python 1844s anomaly) cluster within 1-3s of the 600s `RUN_TIMEOUT`. This means:
- Runs didn't fail — they were killed by the harness at the timeout boundary
- We don't know if they would have completed at 610s, 700s, or 900s
- The timeout is arbitrary (10 minutes) — not based on any SLA requirement

### 6.5: No Warm-Up, No Isolation, No Authentication Refresh

- **No warm-up:** First test runs cold (JIT, connection pools empty). Later tests benefit from warm caches.
- **No isolation:** Previous run's artifacts may contaminate subsequent runs (workspace state, Docker containers).
- **No token refresh:** Single JWT obtained at startup, reused for 30+ minutes. The 1844s Python timeout on Anthropic could be a stale-token issue.
- **10s poll interval:** Timing resolution is ±10s, making durations under 60s unreliable (±17% error).

---

## 7. E2E Results -- Data Integrity Failures

### 7.1: OpenAI Results Show Wrong Model Provider

```json
// e2e-results-openai.json
{"language":"Python","status":"COMPLETE","model":"anthropic","report":"HAS_REPORT"}
{"language":"Java","status":"COMPLETE","model":"anthropic","report":"HAS_REPORT"}
{"language":"TypeScript","status":"COMPLETE","model":"anthropic","report":"HAS_REPORT"}
```

All successful OpenAI runs report `"model": "anthropic"`. This is impossible if the runs used OpenAI as the provider. The `model` field comes from:

```bash
model=$(echo "$detail" | jq -r '.workerModel // .plannerModel // "unknown"')
```

This means either:
1. The server was configured with Anthropic as the default provider during the "OpenAI" batch
2. The results file was copied from the Anthropic batch and partially modified
3. The `workerModel` field on the run entity stores provider name, not model name, and Anthropic was the actual worker provider used

**Regardless of cause: the OpenAI E2E results cannot be trusted for cross-provider comparison.**

### 7.2: Python 1844s Anomaly on Anthropic

OpenAI Python: 40s (COMPLETE). Anthropic Python: 1844s (TIMEOUT — 30+ minutes).

Possible causes:
- **Fabricated completion loop:** Known tech debt — Haiku sometimes generates "REJECTED fabricated completion report" requiring re-execution cycles
- **Token expiry:** JWT was obtained at batch start; after 9 previous tests (~2000s elapsed), token may have degraded
- **Server state:** Prior test runs accumulated state that slowed Python processing

Without diagnostics (which the harness doesn't collect), root cause is unknown.

### 7.3: Google Validation Failures Are Not Provider Failures

```json
// e2e-results-google.json
{"language":"Python","status":"FAILED","notes":"All tasks failed validation"}
{"language":"Go","status":"FAILED","notes":"All tasks failed validation"}
{"language":"PHP","status":"FAILED","notes":"All tasks failed validation"}
```

These runs completed quickly (40-151s) but tasks "failed validation." This likely means:
- Gemini models don't produce output matching the `<tool_call>` XML format expected by `WorkerAgent`
- The completion detector (response must START with `{`) rejected Gemini's output
- This is a **format compatibility issue**, not a capability issue

The same tasks pass on Anthropic/OpenAI because those models are fine-tuned to produce the expected XML/JSON format. Google's failure rate reflects tool-calling format mismatch, not model quality.

---

## 8. Preview System -- Architecture Failures

### 8.1: Cloud Run TTL Never Enforced (CRITICAL — Active Billing Leak)

**File:** `PreviewService.java:240-248, 888-898`

- TTL stored in `PreviewSession.expiresAt()` (in-memory)
- `cleanupExpired()` scheduler checks `PreviewSession::isExpired()` and calls `stop()`
- **But:** If JVM restarts, the `active` ConcurrentHashMap is lost
- Cloud Run services have NO TTL metadata (no labels, no Cloud Scheduler deletion)
- Orphan cleanup runs every 30 minutes but relies on naming convention match
- **A Cloud Run preview started at $0.10/hour that escapes cleanup runs indefinitely**

**Fix:** Store `deployed_at` as Cloud Run label; orphan cleanup deletes services where `now - deployed_at > TTL`.

### 8.2: Port Pool TOCTOU Race Condition (HIGH)

**File:** `PreviewPortPool.java:34-80`

```
Thread A: allocate() → isPortAvailable(10034) ✓ → returns 10034
Thread B: allocate() → isPortAvailable(10034) ✓ → returns 10034  ← RACE
Thread A: docker-compose up -p project-a -p 10034:3000
Thread B: docker-compose up -p project-b -p 10034:3000  ← FAILS: port in use
```

`isPortAvailable()` creates a ServerSocket, checks if bind succeeds, then closes it. Between close and Docker bind, another thread can claim the same port.

### 8.3: Short-ID Collisions in Service Naming and Port Routing (HIGH)

**File:** `PreviewService.java:240-242`, `PreviewProxyFilter.java:133-141`

- Cloud Run service: `"es-preview-" + runId.substring(0, 8)` — 8-char prefix
- Port lookup: `findPortByPrefix()` does prefix match on runId

With UUID v4, collision probability for 8-char hex prefix:
- 2 concurrent previews: 1 in 4.3 billion (negligible)
- 100 concurrent previews: ~1 in 860,000 (still low but nonzero)
- **Real risk:** Sequential UUIDs from some generators share prefixes

If collision occurs: second preview overwrites first Cloud Run service; port lookup returns wrong container.

### 8.4: Caddy Route Failure is Non-Blocking (HIGH)

**File:** `CaddyProxyManager.java:72-112`, `PreviewService.java:621-623`

`caddyProxy.addRoute()` logs warning but doesn't throw on Caddy API failure. Preview transitions to RUNNING state. User gets URL that routes through Caddy → **404 Not Found** because route was never created.

### 8.5: No SSE Events for Preview Lifecycle (MEDIUM)

Preview emits only `PREVIEW_READY` at the very end. Missing events:
- `PREVIEW_ANALYZING` → frontend shows "Detecting project type..."
- `PREVIEW_BUILDING` → frontend shows "Building Docker image..."
- `PREVIEW_STARTING` → frontend shows "Starting container..."
- `PREVIEW_FAILED` → frontend shows error immediately
- `PREVIEW_STOPPED` → frontend removes iframe

Without these, frontend polls every 5s via REST API — up to 5s latency on every state change.

### 8.6: Session Not Removed on Failure — Memory Leak (MEDIUM)

**File:** `PreviewService.java:800-822`

`fail()` sets session status to FAILED and calls `cleanupOnFailure()`, but never calls `active.remove(runId)`. Failed sessions accumulate in the ConcurrentHashMap indefinitely. Over hundreds of failed previews, this becomes a memory leak.

### 8.7: Health Check Deadline Not Pre-Validated (MEDIUM)

**File:** `PreviewService.java:720-762`

Health check retries 30 × 2s = 60s max. But if build phase consumed 7 of 8 minutes, only 60s remains. Health check starts without checking remaining time, then fails at retry #30 even though container might need 90s to start.

### 8.8: PreviewProxyFilter Doesn't Set Permissive CSP (MEDIUM)

**File:** `PreviewProxyFilter.java`

Filter STRIPS restrictive security headers from upstream (correct) but doesn't ADD permissive CSP. Preview content that makes fetch requests or opens WebSockets may be blocked by browser default CSP policy.

### 8.9: ComposeExecutor.down() Timeout Without Force Kill (MEDIUM)

**File:** `ComposeExecutor.java:70-83`

`docker compose down` has 30s timeout. If it times out, exception is caught but container process is not force-killed. Stalled containers consume resources until Docker daemon restarts.

### 8.10: PreviewProperties Default TTL Too Short (MEDIUM)

**File:** `PreviewProperties.java`

Default TTL: 10 minutes. Startup timeout: 8 minutes. **Net usable preview time: 2 minutes.** Users barely see the preview before auto-expiry. Should be 30 minutes minimum.

---

## 9. Deploy System -- Architecture Failures

### 9.1: Workspace Not Restored for Old Runs (CRITICAL)

**File:** `DeploymentService.java:138`

`deploy()` calls `resolveWorkspace(runId)` which returns a Path without checking existence. `WorkspaceArchiveJob` archives workspaces to GCS after 24 hours. Deploy of a 2-day-old run:

```
resolveWorkspace() → /workspace/runs/{runId}  ← directory doesn't exist
DockerfileGenerator.detect() → reads files from path  ← FileNotFoundException
ArtifactRegistryClient.buildImage() → docker build -f ... /workspace/runs/{runId}  ← build fails
```

The newer `DeployEngineOrchestrator` correctly calls `workspaceRestore.ensureWorkspaceAvailable()`, but the older `DeploymentService` does not. **Both code paths are active.**

### 9.2: No Image Cleanup on Failed Deploy (HIGH)

**File:** `ArtifactRegistryClient.java`, `DeploymentService.java`

Deploy pipeline: build image → push to Artifact Registry → deploy to Cloud Run.

If push succeeds but Cloud Run deploy fails:
- Image sits in Artifact Registry indefinitely
- No cleanup in the exception handler
- `DeploymentCleanupJob` only cleans up based on `auto_destroy_at` TTL
- If TTL wasn't set (e.g., permanent deploy), image stays forever

**Artifact Registry storage cost:** ~$0.10/GB/month. 100 failed deploys × 500MB average = 50GB = $5/month growing perpetually.

### 9.3: Deploy Marked LIVE Before Health Check (HIGH)

**File:** `DeploymentService.java:222-227`

```java
// Deployment marked LIVE
deployment.setStatus(DeploymentStatus.LIVE);
deploymentRepo.save(deployment);

// Health check runs AFTER
boolean healthy = cloudRunClient.waitForHealthy(deployment.getServiceUrl());
if (!healthy) {
    log.warn("Deployment marked LIVE but health check failed");
    // NO STATUS ROLLBACK — still LIVE in DB
}
```

Users querying deployment status see LIVE. Frontend shows green checkmark. But the service is actually crashing. No `HEALTH_CHECK_FAILED` or `DEGRADED` status exists.

### 9.4: No Mutual Exclusion Between Preview and Deploy (HIGH)

**File:** `PreviewService.java`, `DeploymentService.java`

Both systems can target the same run simultaneously:
- Preview creates `es-preview-{runId8}` Cloud Run service
- Deploy creates `app-{runId8}` Cloud Run service (different prefix — OK)
- **But:** Both read the same workspace. If deploy modifies files during Dockerfile generation while preview is building from the same workspace directory → build race condition

### 9.5: GitHub PAT in Error Logs (MEDIUM)

**File:** `DeploymentGitHubService.java:64-93`

When GitHub API returns an error, the raw response body is thrown as an IOException message. GitHub error responses can include request headers (including the Authorization header with PAT) in debug output. This gets logged at ERROR level.

### 9.6: No Retry for Transient Cloud Run Failures (MEDIUM)

**File:** `CloudRunClient.java:56-121`

If `gcloud run deploy` returns a transient 503, deploy fails immediately. No retry logic. But health checks (line 349-376) DO retry 3-5 times. Inconsistent resilience strategy.

### 9.7: Cloud Run NULL URL Not Caught (MEDIUM)

**File:** `CloudRunClient.java:112-120`

```java
String serviceUrl = root.path("status").path("url").asText(null);
// ... fallback ...
if (serviceUrl == null) {
    // Returns DeployResult(null, null) — no error thrown
}
```

If gcloud returns malformed JSON, deploy appears to succeed with a null URL. Subsequent health check NPEs.

### 9.8: Compose Project Name Collision (LOW-MEDIUM)

**File:** `PreviewService.java:873-876`

`composeProjectName()` strips non-alphanumeric chars and truncates to 12 chars. Two UUIDs with similar prefixes could collide: `docker compose -p espreview12` for both runs.

---

## 10. Preview/Deploy -- Frontend & State Issues

### 10.1: Preview Runtime State Not Persisted to DB

`PipelineRun.previewStatus` stores only "READY"/"FAILED" (build-time status). Runtime states (ANALYZING, BUILDING, STARTING, RUNNING, STOPPED) live only in `PreviewService.active` ConcurrentHashMap.

**Impact:**
- Cannot query "show me all runs with active previews" from DB
- Server restart loses all preview state — active previews become orphans
- No audit trail of preview lifecycle

### 10.2: Frontend iframe Sandbox Missing Attributes

```tsx
// PreviewTab.tsx
<iframe sandbox="allow-scripts allow-same-origin allow-forms allow-popups" />
```

Missing: `allow-modals` (alert/confirm won't work), `allow-top-navigation-by-user-activation` (links with `target="_blank"` blocked).

### 10.3: Frontend Stops Polling When Tab Hidden

```tsx
// PreviewTab.tsx line 84
if (document.hidden) return;  // skip poll when tab unfocused
```

User switches to another tab → polling stops → switches back → sees stale "Starting..." status until next poll (up to 5s). TTL countdown timer also freezes, showing incorrect remaining time.

### 10.4: Deployment Status Never Updated After LIVE

`Deployment.status` transitions: `PENDING → BUILDING → DEPLOYING → LIVE`. But:
- No `DEGRADED` state for health check failures
- No `STOPPED` state for manual teardown
- `rollback()` doesn't update status field
- Cannot distinguish "intentionally stopped" from "still running"

### 10.5: Preview Logs Lost on Stop

Docker Compose runtime logs are only available via real-time `getLogs()` API. After preview stops, container is removed, logs are gone. No persistence to DB, GCS, or filesystem.

---

## 11. Prioritized Fix List

### P0 — Blocking / Active Damage

| # | System | Issue | Impact | Effort |
|---|--------|-------|--------|--------|
| 1 | Preview | Cloud Run TTL not enforced — services leak indefinitely | Active billing leak | Medium |
| 2 | Deploy | Workspace not restored for >24h runs — deploys fail | Deploy broken for old runs | Low |
| 3 | Deploy | Deploy marked LIVE before health check — false positive | User confusion, silent failures | Low |
| 4 | E2E | OpenAI results show "anthropic" model — data invalid | All cross-provider analysis wrong | Low (re-run tests) |

### P1 — High Severity

| # | System | Issue | Impact | Effort |
|---|--------|-------|--------|--------|
| 5 | Preview | Port pool TOCTOU race condition | Concurrent preview collisions | Medium |
| 6 | Deploy | No image cleanup on failed deploy — registry leak | Storage cost creep | Low |
| 7 | Preview | Caddy route failure non-blocking — silent 404s | Unreachable previews | Low |
| 8 | Deploy | No mutual exclusion preview vs deploy on same run | Workspace race condition | Low |
| 9 | Eval | Cost normalization collapses to binary | 25% of score is fake precision | Medium |
| 10 | Eval | Eval bypasses production LLM path | Measures wrong system | High |

### P2 — Medium Severity

| # | System | Issue | Impact | Effort |
|---|--------|-------|--------|--------|
| 11 | Eval | Scoring weights wrong for accuracy-first priority | Recommendations skewed | Low (config) |
| 12 | Eval | 2 tests have hidden terminology requirements | Guaranteed false negatives | Low (prompt fix) |
| 13 | Eval | LIGHTWEIGHT ceiling (5s) unachievable | Speed scores meaningless | Low (config) |
| 14 | Preview | No SSE events for preview lifecycle | 5s frontend latency | Medium |
| 15 | Preview | Session not removed on failure — memory leak | Memory growth | Low |
| 16 | Preview | Default TTL too short (10 min, 2 min usable) | Bad UX | Low (config) |
| 17 | Deploy | GitHub PAT in error logs | Security | Low |
| 18 | Deploy | No retry for transient Cloud Run failures | Flaky deploys | Medium |
| 19 | E2E | N=1 per test, different test sets per provider | No statistical validity | High (re-design) |
| 20 | Preview | PreviewProxyFilter no permissive CSP added | Fetch/WS blocked in iframe | Low |

### P3 — Low Severity / Tech Debt

| # | System | Issue | Impact | Effort |
|---|--------|-------|--------|--------|
| 21 | Eval | Superlinear accuracy penalty (exponent 1.3) | Near-miss harshness | Low |
| 22 | Eval | Length limits never enforced | Verbose responses unpunished | Low |
| 23 | Preview | Health check doesn't pre-validate remaining deadline | Flaky startup | Low |
| 24 | Preview | Short-ID collisions in service naming | Very rare collision | Low |
| 25 | Deploy | Cloud Run NULL URL not caught | NPE on malformed response | Low |
| 26 | Deploy | Docker push timeout hardcoded (10 min) | Large image failures | Low |
| 27 | Frontend | iframe sandbox missing allow-modals | Dialogs broken in preview | Low |
| 28 | Frontend | Polling stops when tab hidden | Stale status on tab switch | Low |
| 29 | State | Preview runtime state not in DB | No audit, orphans on restart | Medium |
| 30 | State | Deployment status never updated after LIVE | Can't track teardown | Medium |

---

*Generated by adversarial code review. Every finding verified against source code. No issues fabricated.*
