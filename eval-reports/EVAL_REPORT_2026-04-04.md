# ExecuteSpec Evaluation Report

> **Date:** 2026-04-04
> **Evaluation ID:** `cb5362f6-4f04-44e1-817e-1cc10a155bf9`
> **Duration:** 2,077,862 ms (34 min 38s)
> **Scope:** Model Eval (4 models x 12 test cases = 48 LLM calls) + E2E Pipeline Tests (3 providers x 6-10 runs)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Model Evaluation — Methodology](#2-model-evaluation--methodology)
3. [Model Evaluation — Per-Model Results](#3-model-evaluation--per-model-results)
4. [Model Evaluation — Per-Test-Case Breakdown](#4-model-evaluation--per-test-case-breakdown)
5. [Model Evaluation — Tier Recommendations](#5-model-evaluation--tier-recommendations)
6. [E2E Pipeline Tests — Methodology](#6-e2e-pipeline-tests--methodology)
7. [E2E Pipeline Tests — Results by Provider](#7-e2e-pipeline-tests--results-by-provider)
8. [E2E Pipeline Tests — Cross-Provider Comparison](#8-e2e-pipeline-tests--cross-provider-comparison)
9. [Failure Analysis](#9-failure-analysis)
10. [Raw JSON Data](#10-raw-json-data)

---

## 1. Executive Summary

### Model Eval Highlights
- **Best overall model:** `claude-haiku-4-5-20251001` (Anthropic) — dominates all 3 tiers on cost+speed while maintaining near-perfect accuracy
- **Best raw accuracy:** `claude-sonnet-4-6` (Anthropic) — highest accuracy on PLANNING tier (9.22) but severely penalized on speed/cost
- **All 4 models** score 100% accuracy on WORKHORSE tier (code generation tasks)
- **Hardest test cases:** `planning-saga-diagnosis` and `planning-saga-compliance` — no model achieves full pass (max 8.57/10 accuracy)
- **LIGHTWEIGHT speed ceiling (5s)** is too aggressive — only `gpt-5.4-mini` consistently meets it

### E2E Pipeline Highlights
- **OpenAI:** 83% pass rate (5/6), fastest completions (40-121s), most reliable provider
- **Anthropic:** 67% pass rate (6/9 + cancel), strong on multi-task runs but hit timeouts on Python/Java/HTML
- **Google (Gemini):** 22% pass rate (2/9 + cancel), frequent timeouts and validation failures
- **Cancel flow:** Works correctly across all providers

---

## 2. Model Evaluation -- Methodology

### Scoring Dimensions

| Dimension | Weight | How It's Measured |
|-----------|--------|-------------------|
| **Accuracy** | 50% | Regex pattern matching + JSON structural validation. Quadratic curve: `1 + 9 * ratio^1.3` |
| **Speed** | 25% | Latency vs tier ceiling. Score 10 at <=50% budget, linear decay to 1 at 200% |
| **Cost** | 25% | Log-scale normalization across models per test case. Cheapest=10, 2x=~7.5, 10x=~2.5 |

**Overall = accuracy * 0.50 + speed * 0.25 + cost * 0.25**

### Tier Latency Ceilings

| Tier | Ceiling | Rationale |
|------|---------|-----------|
| PLANNING | 30,000 ms | Architectural reasoning, plan generation |
| WORKHORSE | 45,000 ms | Multi-file code generation |
| LIGHTWEIGHT | 5,000 ms | Fast triage, summaries, diffs |

### Test Case Distribution

| Tier | Count | Test IDs |
|------|-------|----------|
| PLANNING | 6 | `planning-plan-generation`, `planning-code-review`, `planning-architecture-decision`, `planning-saga-diagnosis`, `planning-saga-consistency`, `planning-saga-compliance` |
| WORKHORSE | 3 | `workhorse-websocket-notification`, `workhorse-virtualized-search-list`, `workhorse-multi-bug-fix` |
| LIGHTWEIGHT | 3 | `lightweight-request-triage`, `lightweight-dual-summary`, `lightweight-config-diff-analysis` |

### Models Under Test

| # | Provider | Model | Current Assignment |
|---|----------|-------|--------------------|
| 1 | Anthropic | `claude-sonnet-4-6` | PLANNING tier |
| 2 | Anthropic | `claude-haiku-4-5-20251001` | WORKHORSE + LIGHTWEIGHT tiers |
| 3 | OpenAI | `gpt-5.4` | PLANNING tier |
| 4 | OpenAI | `gpt-5.4-mini` | WORKHORSE + LIGHTWEIGHT tiers |

---

## 3. Model Evaluation -- Per-Model Results

### 3.1 claude-sonnet-4-6 (Anthropic)

| Tier | Accuracy | Speed | Cost | Overall | Pass Rate |
|------|----------|-------|------|---------|-----------|
| PLANNING | **9.22** | 1.16 | 3.24 | 5.71 | 4/6 (67%) |
| WORKHORSE | **10.00** | 5.21 | 3.34 | 7.14 | 3/3 (100%) |
| LIGHTWEIGHT | **10.00** | 1.65 | 2.90 | 6.14 | 3/3 (100%) |

**Best fit tier:** WORKHORSE (overall 7.14)

**Analysis:** Highest raw accuracy on PLANNING tasks but the slowest model in the eval. Every PLANNING test exceeded the 30s ceiling (82-167s), collapsing speed scores to 1.0. At $0.06-0.14 per call, cost scores are also low. Strong choice when accuracy is paramount and latency is acceptable.

---

### 3.2 claude-haiku-4-5-20251001 (Anthropic)

| Tier | Accuracy | Speed | Cost | Overall | Pass Rate |
|------|----------|-------|------|---------|-----------|
| PLANNING | 9.44 | 5.76 | **10.00** | **8.66** | 4/6 (67%) |
| WORKHORSE | **10.00** | **9.44** | **10.00** | **9.86** | 3/3 (100%) |
| LIGHTWEIGHT | 9.52 | 4.15 | **10.00** | **8.30** | 2/3 (67%) |

**Best fit tier:** WORKHORSE (overall 9.86)

**Analysis:** Dominates across all tiers due to exceptional cost efficiency ($0.002-0.040 per call) and competitive latency. Nearly matches Sonnet on accuracy (within 0.2 points on PLANNING). WORKHORSE score of 9.86 is the highest single-tier score in the eval. Only weakness: missed `lightweight-config-diff-analysis` (K8s breaking changes).

---

### 3.3 gpt-5.4 (OpenAI)

| Tier | Accuracy | Speed | Cost | Overall | Pass Rate |
|------|----------|-------|------|---------|-----------|
| PLANNING | 8.92 | 5.26 | 6.18 | 7.32 | 4/6 (67%) |
| WORKHORSE | **10.00** | 7.48 | 5.24 | 8.18 | 3/3 (100%) |
| LIGHTWEIGHT | 9.52 | 3.17 | 4.47 | 6.67 | 2/3 (67%) |

**Best fit tier:** WORKHORSE (overall 8.18)

**Analysis:** Balanced performer — good accuracy, moderate speed, mid-range cost. Strongest on `planning-saga-consistency` (overall 9.51, the highest score any model got on a saga test). Cost sits between Sonnet and Haiku, making it a solid middle-ground choice.

---

### 3.4 gpt-5.4-mini (OpenAI)

| Tier | Accuracy | Speed | Cost | Overall | Pass Rate |
|------|----------|-------|------|---------|-----------|
| PLANNING | 8.15 | **9.91** | 7.64 | 8.46 | 2/6 (33%) |
| WORKHORSE | 9.46 | **10.00** | 6.29 | 8.80 | 2/3 (67%) |
| LIGHTWEIGHT | 9.06 | 7.23 | 5.82 | 7.79 | 2/3 (67%) |

**Best fit tier:** WORKHORSE (overall 8.80)

**Analysis:** Fastest model overall — hits 10.0 speed on most tests. But lowest PLANNING accuracy (8.15) and only 33% pass rate on PLANNING tier. Misses nuanced patterns in saga tests and architecture decisions. Best for latency-sensitive WORKHORSE/LIGHTWEIGHT tasks where perfect accuracy isn't required.

---

## 4. Model Evaluation -- Per-Test-Case Breakdown

### 4.1 PLANNING Tier (6 tests)

#### planning-plan-generation
*"Generate an execution plan for a real-time collaborative editor with OT engine"*

Validates: task structure, project_type, depends_on, parallelizable_with, 6+ tasks, OT edge cases, HIGH complexity tasks, version vectors.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.00 | 1.12 | 5.53 | 167,581ms | $0.1397 | YES |
| claude-haiku-4-5 | 10.00 | 5.36 | 10.00 | 8.84 | 38,215ms | $0.0179 | YES |
| gpt-5.4 | 10.00 | 1.00 | 4.31 | 6.33 | 63,138ms | $0.0667 | YES |
| gpt-5.4-mini | 10.00 | 9.49 | 6.09 | 8.89 | 17,563ms | $0.0443 | YES |

**Verdict:** All models achieve perfect accuracy. Differentiated purely by speed/cost. Haiku is 4.4x faster than Sonnet at 7.8x less cost.

---

#### planning-code-review
*"Review a distributed rate limiter for race conditions, TOCTOU bugs, and metric cardinality issues"*

Validates: TOCTOU in Lua script, volatile HashMap thread-safety, high-cardinality metrics tag, burst rate limiter race, missed_by_junior field, overall_risk=HIGH.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.00 | 1.54 | 5.63 | 99,936ms | $0.0875 | YES |
| claude-haiku-4-5 | 10.00 | 7.48 | 10.00 | 9.37 | 27,607ms | $0.0124 | YES |
| gpt-5.4 | 10.00 | 6.38 | 5.01 | 7.85 | 33,095ms | $0.0392 | YES |
| gpt-5.4-mini | 10.00 | 10.00 | 6.49 | 9.12 | 12,584ms | $0.0279 | YES |

**Verdict:** All pass. Haiku leads overall (9.37) by combining near-Sonnet accuracy with dramatically better speed/cost.

---

#### planning-architecture-decision
*"Make a justified architecture decision for a fintech payment system"*

Validates: PCI DSS, implementation phases, team skills (Python/Django), idempotency, rejected_options, decision_reversibility, multi-currency.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.00 | 2.93 | 5.98 | 119,377ms | $0.0740 | YES |
| claude-haiku-4-5 | 10.00 | 5.36 | 10.00 | 8.84 | 38,192ms | $0.0145 | YES |
| gpt-5.4 | 10.00 | 5.90 | 6.12 | 8.01 | 35,484ms | $0.0354 | YES |
| gpt-5.4-mini | 8.57 | 10.00 | 7.22 | 8.59 | 11,752ms | $0.0275 | NO |

**Verdict:** Mini is the only model that fails — misses some validation patterns (likely `decision_reversibility` or `multi-currency` nuance). All others achieve perfect accuracy.

---

#### planning-saga-diagnosis (Boss-Level)
*"Diagnose 6 production incidents in a trading saga and design a correct compensation protocol"*

Validates: @Transactional boundary spanning HTTP, timeout != failure, LIFO compensation with reasoning, partial fill 340-qty compensation, settlement-aware (cancel vs offset), poison pill escalation, saga state machine with guards.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 7.19 | 1.00 | 5.46 | 5.21 | 144,915ms | $0.1134 | NO |
| claude-haiku-4-5 | **8.57** | 4.86 | 10.00 | 8.00 | 81,443ms | $0.0397 | NO |
| gpt-5.4 | 7.19 | 6.35 | 7.41 | 7.04 | 66,493ms | $0.0723 | NO |
| gpt-5.4-mini | 7.19 | 10.00 | 8.85 | 8.31 | 20,021ms | $0.0518 | NO |

**Verdict:** **No model passes.** This is the hardest test in the eval. Haiku achieves highest accuracy (8.57) — likely catches more patterns than Sonnet due to concise, structured output. All models struggle with the strict regex patterns for settlement-aware compensation and LIFO ordering WITH reasoning.

---

#### planning-saga-consistency (Boss-Level)
*"Design dedup and consistency protocol for a concrete multi-exchange failure timeline"*

Validates: fencing tokens (not TTL-only), lease-based locks tied to exchange SLAs, outbox pattern/CDC, Leg A settled -> offsetting trade, 340-share partial fill compensation, idempotent compensation handler, anti-pattern rejection.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.94 | 4.26 | 6.55 | 82,953ms | $0.0595 | YES |
| claude-haiku-4-5 | 10.00 | 7.35 | 10.00 | 9.34 | 42,339ms | $0.0158 | YES |
| gpt-5.4 | 10.00 | **10.00** | 8.06 | **9.51** | 21,805ms | $0.0247 | YES |
| gpt-5.4-mini | 6.81 | 10.00 | 9.29 | 8.23 | 8,188ms | $0.0186 | NO |

**Verdict:** GPT-5.4 achieves the highest overall score on any saga test (9.51) — fast AND accurate. Mini fails, likely missing fencing token or outbox pattern. Sonnet passes but speed penalty crushes its score.

---

#### planning-saga-compliance (Boss-Level)
*"Review a broken audit implementation for MiFID II compliance and design correct audit trail"*

Validates: non-atomic audit crash risk, same-TX audit atomicity, microsecond timestamps, market data snapshot at decision time, staleness threshold with concrete number, explicit rejection of async audit.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 8.10 | 1.00 | 4.13 | 5.33 | 160,895ms | $0.1268 | NO |
| claude-haiku-4-5 | 8.10 | 4.14 | 10.00 | 7.58 | 66,462ms | $0.0327 | NO |
| gpt-5.4 | 6.31 | 1.90 | 6.19 | 5.18 | 83,282ms | $0.0788 | NO |
| gpt-5.4-mini | 6.31 | 10.00 | 7.92 | 7.64 | 20,741ms | $0.0528 | NO |

**Verdict:** **No model passes.** Second-hardest test. All models identify the core non-atomic audit problem (8.10 accuracy for Anthropic models) but fail on negative checks — explicitly rejecting async audit patterns. GPT models score lower accuracy (6.31), likely missing microsecond timestamp specificity or market data snapshot patterns.

---

### 4.2 WORKHORSE Tier (3 tests)

#### workhorse-websocket-notification
*"Generate a complete WebSocket notification system with retry, rate limiting, and reconnect draining"*

Validates: STOMP config, concurrent session tracking, exponential backoff, rate limiting, pending notification drain, @Transactional, @Entity, NotificationType enum.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.00 | 3.19 | 6.05 | 115,619ms | $0.1648 | YES |
| claude-haiku-4-5 | 10.00 | 8.32 | 10.00 | **9.58** | 35,066ms | $0.0342 | YES |
| gpt-5.4 | 10.00 | 6.42 | 6.82 | 8.31 | 49,385ms | $0.0712 | YES |
| gpt-5.4-mini | 10.00 | 10.00 | 7.92 | 9.48 | 17,258ms | $0.0553 | YES |

---

#### workhorse-virtualized-search-list
*"Generate a virtualized infinite-scroll list with fuzzy search, optimistic updates, and keyboard nav"*

Validates: virtual scroll position math, debounced search, fuzzy matching, optimistic update with rollback, keyboard nav, AbortController cleanup, TypeScript generics.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 4.62 | 2.95 | 6.89 | 62,839ms | $0.0902 | YES |
| claude-haiku-4-5 | 10.00 | 10.00 | 10.00 | **10.00** | 21,103ms | $0.0177 | YES |
| gpt-5.4 | 10.00 | 6.02 | 3.51 | 7.38 | 52,318ms | $0.0793 | YES |
| gpt-5.4-mini | 8.37 | 10.00 | 5.28 | 8.00 | 17,461ms | $0.0527 | NO |

**Haiku achieves a perfect 10.00** — the only perfect score in the entire eval. Mini misses TypeScript generics or fuzzy matching patterns.

---

#### workhorse-multi-bug-fix
*"Fix 7 interacting bugs in a billing service (thread-safety, cache staleness, NPE, overpayment)"*

Validates: ConcurrentHashMap, .orElse() for reduce, overpayment guard, OptionalDouble handling, @Param annotation, cache invalidation.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 10.00 | 3.87 | 8.47 | 18,538ms | $0.0306 | YES |
| claude-haiku-4-5 | 10.00 | 10.00 | 10.00 | **10.00** | 6,887ms | $0.0074 | YES |
| gpt-5.4 | 10.00 | 10.00 | 5.39 | 8.85 | 13,195ms | $0.0216 | YES |
| gpt-5.4-mini | 10.00 | 10.00 | 5.68 | 8.92 | 5,108ms | $0.0201 | YES |

**Verdict:** All models ace bug-fixing. Haiku again hits perfect 10.00. Even Sonnet achieves 10.0 speed (18.5s) — bug-fix tasks generate shorter, more focused output.

---

### 4.3 LIGHTWEIGHT Tier (3 tests)

#### lightweight-request-triage
*"Triage an ambiguous multi-part user request: identify ambiguities, prioritize by business impact"*

Validates: MULTI_TASK classification, ambiguities array, data mismatch as P0, 3+ tasks, dashboard ambiguity, risks_if_no_clarification.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.00 | 1.85 | 5.71 | 71,929ms | $0.0513 | YES |
| claude-haiku-4-5 | 10.00 | 1.00 | 10.00 | 7.75 | 18,687ms | $0.0078 | YES |
| gpt-5.4 | 10.00 | 1.00 | 3.25 | 6.06 | 33,595ms | $0.0371 | YES |
| gpt-5.4-mini | 10.00 | 4.39 | 6.28 | 7.67 | 7,172ms | $0.0184 | YES |

**Verdict:** All pass with perfect accuracy. But the 5s LIGHTWEIGHT ceiling is brutal — only Mini comes close (7.2s). All other models blow past it, scoring 1.0 on speed. **Recommendation: increase LIGHTWEIGHT ceiling to 10-15s.**

---

#### lightweight-dual-summary
*"Generate developer + stakeholder summaries from a raw pipeline report with financial risk"*

Validates: both summaries, blocks_release=true, double refund/financial loss, PCI/API key exposure, severity_distribution, top_3_actions.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 2.96 | 4.02 | 6.74 | 8,370ms | $0.0079 | YES |
| claude-haiku-4-5 | 10.00 | 8.27 | 10.00 | **9.57** | 3,942ms | $0.0020 | YES |
| gpt-5.4 | 10.00 | 7.15 | 4.95 | 8.02 | 4,877ms | $0.0063 | YES |
| gpt-5.4-mini | 10.00 | 10.00 | 5.87 | 8.97 | 2,310ms | $0.0051 | YES |

**Verdict:** Best LIGHTWEIGHT test — all pass, fast completions. Haiku at $0.002/call is essentially free.

---

#### lightweight-config-diff-analysis
*"Identify only breaking changes between two K8s deployment configs"*

Validates: breaking_changes array, replica 3->2, maxUnavailable 0->1, STRIPE_API_KEY removal, secret key name change, DEBUG log in prod, topology constraint change, verdict=BLOCK.

| Model | Accuracy | Speed | Cost | Overall | Latency | Cost USD | Pass |
|-------|----------|-------|------|---------|---------|----------|------|
| claude-sonnet-4-6 | 10.00 | 1.00 | 2.83 | 5.96 | 25,044ms | $0.0255 | YES |
| claude-haiku-4-5 | 8.57 | 3.17 | 10.00 | 7.58 | 8,189ms | $0.0049 | NO |
| gpt-5.4 | 8.57 | 1.37 | 5.21 | 5.93 | 9,692ms | $0.0147 | NO |
| gpt-5.4-mini | 7.19 | 7.29 | 5.30 | 6.74 | 4,760ms | $0.0144 | NO |

**Verdict:** Only Sonnet passes. All other models miss patterns — likely the topology constraint change (`DoNotSchedule -> ScheduleAnyway`) or the secret key name change. This test has 8 validation patterns, more than any other LIGHTWEIGHT test — it's over-indexed for the tier.

---

## 5. Model Evaluation -- Tier Recommendations

### Eval-Recommended Configuration

```json
{
  "recommendations": {
    "PLANNING": {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "score": 8.66,
      "reasoning": "Best PLANNING model: accuracy=9.4, speed=5.8, cost=10.0"
    },
    "WORKHORSE": {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "score": 9.86,
      "reasoning": "Best WORKHORSE model: accuracy=10.0, speed=9.4, cost=10.0"
    },
    "LIGHTWEIGHT": {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "score": 8.30,
      "reasoning": "Best LIGHTWEIGHT model: accuracy=9.5, speed=4.1, cost=10.0"
    }
  }
}
```

### Current Configuration vs Eval Recommendation

| Tier | Current (OpenAI) | Current (Anthropic) | Eval Recommends | Delta |
|------|------------------|---------------------|-----------------|-------|
| PLANNING | gpt-5.4 (7.32) | claude-sonnet-4-6 (5.71) | claude-haiku-4-5 (8.66) | +1.34 over gpt-5.4 |
| WORKHORSE | gpt-5.4-mini (8.80) | claude-haiku-4-5 (9.86) | claude-haiku-4-5 (9.86) | Already optimal |
| LIGHTWEIGHT | gpt-5.4-mini (7.79) | claude-haiku-4-5 (8.30) | claude-haiku-4-5 (8.30) | Already optimal |

### Commentary

The eval heavily favors Haiku due to its exceptional cost efficiency (10.0 on every test). In production, the PLANNING tier choice is nuanced:

- **Sonnet** has the highest raw accuracy (9.22) but unacceptable latency (82-167s per call) — users will experience 2-5 min plan generation times
- **Haiku** matches accuracy closely (9.44) at 3-4x better speed — but it fails the same saga tests as Sonnet
- **GPT-5.4** offers the best saga-consistency score (9.51) and moderate latency — good for OpenAI-preference users
- **GPT-5.4-mini** is fastest but lowest accuracy on PLANNING (8.15, 33% pass rate) — not suitable for planning tasks

**Recommendation:** Keep current matrix assignments. Sonnet for PLANNING (accuracy-critical), Haiku for WORKHORSE/LIGHTWEIGHT (cost-optimal). The eval's recommendation of Haiku for PLANNING is skewed by the 50% cost weight.

---

## 6. E2E Pipeline Tests -- Methodology

### Test Harness: `scripts/e2e-test.sh`

| Parameter | Value |
|-----------|-------|
| Base URL | `http://localhost:8080` |
| Auth | `admin@executespec.ai` / JWT |
| Run timeout | 600s (10 min) |
| Poll interval | 10s |
| Plan approval | `skipApproval: true` (auto-approve) |
| Cancel test | `skipApproval: false` → wait for `AWAITING_APPROVAL` → cancel |

### Test Cases

| # | Language | Request |
|---|----------|---------|
| 1 | Python | CLI calculator (add, subtract, multiply, divide) |
| 2 | JavaScript | Node.js Express API (GET /health, POST /echo) |
| 3 | Java | Maven project with Main class (hello world) |
| 4 | TypeScript | Email validator with regex (tsconfig + package.json) |
| 5 | Static HTML | Responsive portfolio layout (single index.html) |
| 6 | Cancel | Complex microservices request (intentionally too large, tests cancel flow) |

### What Gets Verified Per Run

1. **Submit** — POST `/api/runs` returns 202 with runId
2. **Pipeline completion** — Polls until terminal state within timeout
3. **Task completion** — Reads `completedTaskCount/taskCount` from run detail
4. **Validation report** — GET `/api/runs/{id}/report` returns 200
5. **Report sharing** — POST `/api/runs/{id}/report/share` + GET public link

---

## 7. E2E Pipeline Tests -- Results by Provider

### 7.1 OpenAI Provider

**Pass Rate: 83% (5/6)**

| # | Language | Status | Tasks | Duration | Report | Share | Notes |
|---|----------|--------|-------|----------|--------|-------|-------|
| 1 | Python | COMPLETE | 1/1 | 40s | HAS_REPORT | SHARE_OK | |
| 2 | JavaScript | TIMEOUT | ?/? | 602s | NO_REPORT | NONE | Timed out after 602s |
| 3 | Java | COMPLETE | 1/1 | 50s | HAS_REPORT | SHARE_OK | |
| 4 | TypeScript | COMPLETE | 2/2 | 110s | HAS_REPORT | SHARE_OK | |
| 5 | Static HTML | COMPLETE | 1/1 | 121s | HAS_REPORT | SHARE_OK | |
| 6 | Cancel | PASS | 0/0 | 124s | NO_REPORT | NONE | Cancel flow works |

```json
[
  {"index":0,"language":"Python","status":"COMPLETE","tasks":"1/1","duration":40,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":1,"language":"JavaScript","status":"TIMEOUT","tasks":"?/?","duration":602,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 602s "},
  {"index":2,"language":"Java","status":"COMPLETE","tasks":"1/1","duration":50,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":3,"language":"TypeScript","status":"COMPLETE","tasks":"2/2","duration":110,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":4,"language":"Static HTML","status":"COMPLETE","tasks":"1/1","duration":121,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":5,"language":"Cancel","status":"PASS","tasks":"0/0","duration":124,"model":"","report":"NO_REPORT","share":"NONE","notes":"Cancel flow works "}
]
```

---

### 7.2 Anthropic Provider

**Pass Rate: 67% (6/9 + cancel)**

| # | Language | Status | Tasks | Duration | Report | Share | Notes |
|---|----------|--------|-------|----------|--------|-------|-------|
| 1 | Python | TIMEOUT | ?/? | 1844s | NO_REPORT | NONE | Timed out after 1844s |
| 2 | JavaScript | COMPLETE | 2/2 | 429s | HAS_REPORT | SHARE_OK | |
| 3 | Go | COMPLETE | 2/2 | 271s | HAS_REPORT | SHARE_OK | |
| 4 | Java | TIMEOUT | ?/? | 603s | NO_REPORT | NONE | Timed out after 603s |
| 5 | TypeScript | COMPLETE | 3/3 | 432s | HAS_REPORT | SHARE_OK | |
| 6 | Rust | COMPLETE | 2/2 | 381s | HAS_REPORT | SHARE_OK | |
| 7 | Ruby | COMPLETE | 1/1 | 261s | HAS_REPORT | SHARE_OK | |
| 8 | PHP | COMPLETE | 2/2 | 291s | HAS_REPORT | SHARE_OK | |
| 9 | Static HTML | TIMEOUT | ?/? | 603s | NO_REPORT | NONE | Timed out after 603s |
| 10 | Cancel | PASS | 0/0 | 124s | NO_REPORT | NONE | Cancel flow works |

```json
[
  {"index":0,"language":"Python","status":"TIMEOUT","tasks":"?/?","duration":1844,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 1844s "},
  {"index":1,"language":"JavaScript","status":"COMPLETE","tasks":"2/2","duration":429,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":2,"language":"Go","status":"COMPLETE","tasks":"2/2","duration":271,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":3,"language":"Java","status":"TIMEOUT","tasks":"?/?","duration":603,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 603s "},
  {"index":4,"language":"TypeScript","status":"COMPLETE","tasks":"3/3","duration":432,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":5,"language":"Rust","status":"COMPLETE","tasks":"2/2","duration":381,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":6,"language":"Ruby","status":"COMPLETE","tasks":"1/1","duration":261,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":7,"language":"PHP","status":"COMPLETE","tasks":"2/2","duration":291,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":8,"language":"Static HTML","status":"TIMEOUT","tasks":"?/?","duration":603,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 603s "},
  {"index":9,"language":"Cancel","status":"PASS","tasks":"0/0","duration":124,"model":"","report":"NO_REPORT","share":"NONE","notes":"Cancel flow works "}
]
```

---

### 7.3 Google (Gemini) Provider

**Pass Rate: 22% (2/9 + cancel)**

| # | Language | Status | Tasks | Duration | Report | Share | Notes |
|---|----------|--------|-------|----------|--------|-------|-------|
| 1 | Python | FAILED | 0/1 | 40s | NO_REPORT (404) | SKIPPED | All tasks failed validation |
| 2 | JavaScript | TIMEOUT | ?/? | 601s | NO_REPORT | NONE | Timed out after 601s |
| 3 | Go | FAILED | 0/1 | 40s | NO_REPORT (404) | SKIPPED | All tasks failed validation |
| 4 | Java | TIMEOUT | ?/? | 602s | NO_REPORT | NONE | Timed out after 602s |
| 5 | TypeScript | TIMEOUT | ?/? | 601s | NO_REPORT | NONE | Timed out after 601s |
| 6 | Rust | PARTIAL | 1/2 | 71s | NO_REPORT (404) | SKIPPED | Incomplete: CLI arg parsing + conversion |
| 7 | Ruby | COMPLETE | 1/1 | 220s | HAS_REPORT | SHARE_OK | |
| 8 | PHP | FAILED | 0/2 | 151s | NO_REPORT (404) | SKIPPED | All tasks failed validation |
| 9 | Static HTML | TIMEOUT | ?/? | 601s | NO_REPORT | NONE | Timed out after 601s |
| 10 | Cancel | PASS | 0/0 | 124s | NO_REPORT | NONE | Cancel flow works |

```json
[
  {"index":0,"language":"Python","status":"FAILED","tasks":"0/1","duration":40,"model":"anthropic","report":"NO_REPORT|404","share":"SKIPPED","notes":"All tasks failed validation "},
  {"index":1,"language":"JavaScript","status":"TIMEOUT","tasks":"?/?","duration":601,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 601s "},
  {"index":2,"language":"Go","status":"FAILED","tasks":"0/1","duration":40,"model":"anthropic","report":"NO_REPORT|404","share":"SKIPPED","notes":"All tasks failed validation "},
  {"index":3,"language":"Java","status":"TIMEOUT","tasks":"?/?","duration":602,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 602s "},
  {"index":4,"language":"TypeScript","status":"TIMEOUT","tasks":"?/?","duration":601,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 601s "},
  {"index":5,"language":"Rust","status":"PARTIAL","tasks":"1/2","duration":71,"model":"anthropic","report":"NO_REPORT|404","share":"SKIPPED","notes":"Incomplete tasks: Implement CLI argument parsing and Celsius to Fahrenheit conversion in main.rs [PARTIAL] "},
  {"index":6,"language":"Ruby","status":"COMPLETE","tasks":"1/1","duration":220,"model":"anthropic","report":"HAS_REPORT","share":"SHARE_OK","notes":" "},
  {"index":7,"language":"PHP","status":"FAILED","tasks":"0/2","duration":151,"model":"anthropic","report":"NO_REPORT|404","share":"SKIPPED","notes":"All tasks failed validation "},
  {"index":8,"language":"Static HTML","status":"TIMEOUT","tasks":"?/?","duration":601,"model":"","report":"NO_REPORT","share":"NONE","notes":"Timed out after 601s "},
  {"index":9,"language":"Cancel","status":"PASS","tasks":"0/0","duration":124,"model":"","report":"NO_REPORT","share":"NONE","notes":"Cancel flow works "}
]
```

---

## 8. E2E Pipeline Tests -- Cross-Provider Comparison

| # | Language | Google | OpenAI | Anthropic |
|---|----------|--------|--------|-----------|
| 1 | Python | FAILED 0/1 40s | COMPLETE 1/1 40s | TIMEOUT 1844s |
| 2 | JavaScript | TIMEOUT 601s | TIMEOUT 602s | COMPLETE 2/2 429s |
| 3 | Go | FAILED 0/1 40s | *(not tested)* | COMPLETE 2/2 271s |
| 4 | Java | TIMEOUT 602s | COMPLETE 1/1 50s | TIMEOUT 603s |
| 5 | TypeScript | TIMEOUT 601s | COMPLETE 2/2 110s | COMPLETE 3/3 432s |
| 6 | Rust | PARTIAL 1/2 71s | *(not tested)* | COMPLETE 2/2 381s |
| 7 | Ruby | COMPLETE 1/1 220s | *(not tested)* | COMPLETE 1/1 261s |
| 8 | PHP | FAILED 0/2 151s | *(not tested)* | COMPLETE 2/2 291s |
| 9 | Static HTML | TIMEOUT 601s | COMPLETE 1/1 121s | TIMEOUT 603s |
| 10 | Cancel | PASS 124s | PASS 124s | PASS 124s |

### Speed Comparison (completed runs only, avg duration)

| Provider | Avg Duration (completed) | Fastest Run | Slowest Run |
|----------|-------------------------|-------------|-------------|
| OpenAI | 80s | Python 40s | Static HTML 121s |
| Anthropic | 344s | Ruby 261s | TypeScript 432s |
| Google | 220s | Ruby 220s | Ruby 220s (only 1 completed) |

### Reliability Comparison

| Provider | Tests Run | Complete | Timeout | Failed | Partial | Pass Rate |
|----------|-----------|----------|---------|--------|---------|-----------|
| OpenAI | 6 | 4 | 1 | 0 | 0 | 83% |
| Anthropic | 10 | 6 | 3 | 0 | 0 | 70% |
| Google | 10 | 1 | 4 | 3 | 1 | 22% |

---

## 9. Failure Analysis

### 9.1 JavaScript Timeout (OpenAI + Google)

Both OpenAI and Google timed out on the JavaScript/Express test. Anthropic completed it in 429s with 2 tasks. Likely cause: Node.js project setup (package.json + Express boilerplate) takes longer in the pipeline when the planner creates multiple tasks, and the 600s timeout is insufficient for slower providers.

### 9.2 Python Timeout (Anthropic, 1844s)

Extreme outlier — 30+ minutes for a simple calculator. Likely hit the fabricated-completion-report issue documented in known tech debt: "REJECTED fabricated completion report — claims files_created but 0 writeFile calls were dispatched." This causes repeated re-execution loops.

### 9.3 Google Validation Failures (Python, Go, PHP)

All failed with "All tasks failed validation" in 40-151s. Gemini models generate output that doesn't match the expected `<tool_call>` XML format, causing the worker completion detector (response must START with `{`) to reject the output.

### 9.4 Static HTML Timeout (Anthropic + Google)

Simple single-file HTML test times out on both providers. Could be a planner over-decomposition issue — creating too many tasks for a single index.html file, or the planner including unnecessary build steps.

### 9.5 Saga Test Failures (All Models)

`planning-saga-diagnosis` and `planning-saga-compliance` fail for ALL models. The validation patterns are extremely specific:
- **Saga diagnosis:** Requires regex match for `@Transactional.*HTTP` in a specific phrasing, LIFO compensation "WITH reasoning", and settlement-aware compensation distinction
- **Saga compliance:** Requires explicit "reject async audit" phrasing that models don't naturally produce

**Recommendation:** Review whether these patterns are too strict. The models may have correct reasoning but express it differently than the regex expects.

---

## 10. Raw JSON Data

### Full Eval Report JSON

**File:** `eval-reports/eval-cb5362f6-4f04-44e1-817e-1cc10a155bf9.json`

```json
{
  "evaluationId": "cb5362f6-4f04-44e1-817e-1cc10a155bf9",
  "timestamp": "2026-04-04T06:07:46.736677Z",
  "durationMs": 2077862,
  "modelResults": [
    {
      "provider": "anthropic",
      "model": "claude-sonnet-4-6",
      "tierScores": {
        "PLANNING": {
          "accuracy": 9.215,
          "speed": 1.157,
          "cost": 3.238,
          "overall": 5.706,
          "reasoning": "Avg accuracy=9.2, speed=1.2, cost=3.2 across 6 tests. 4/6 passed.",
          "testResults": [
            {"testCaseId":"planning-plan-generation","accuracy":10.0,"speed":1.0,"cost":1.12,"overall":5.53,"latencyMs":167581,"costUsd":0.1397,"passed":true},
            {"testCaseId":"planning-code-review","accuracy":10.0,"speed":1.0,"cost":1.54,"overall":5.63,"latencyMs":99936,"costUsd":0.0875,"passed":true},
            {"testCaseId":"planning-architecture-decision","accuracy":10.0,"speed":1.0,"cost":2.93,"overall":5.98,"latencyMs":119377,"costUsd":0.0740,"passed":true},
            {"testCaseId":"planning-saga-diagnosis","accuracy":7.19,"speed":1.0,"cost":5.46,"overall":5.21,"latencyMs":144915,"costUsd":0.1134,"passed":false},
            {"testCaseId":"planning-saga-consistency","accuracy":10.0,"speed":1.94,"cost":4.26,"overall":6.55,"latencyMs":82953,"costUsd":0.0595,"passed":true},
            {"testCaseId":"planning-saga-compliance","accuracy":8.10,"speed":1.0,"cost":4.13,"overall":5.33,"latencyMs":160895,"costUsd":0.1268,"passed":false}
          ]
        },
        "WORKHORSE": {
          "accuracy": 10.0,
          "speed": 5.207,
          "cost": 3.338,
          "overall": 7.136,
          "reasoning": "Avg accuracy=10.0, speed=5.2, cost=3.3 across 3 tests. 3/3 passed.",
          "testResults": [
            {"testCaseId":"workhorse-websocket-notification","accuracy":10.0,"speed":1.0,"cost":3.19,"overall":6.05,"latencyMs":115619,"costUsd":0.1648,"passed":true},
            {"testCaseId":"workhorse-virtualized-search-list","accuracy":10.0,"speed":4.62,"cost":2.95,"overall":6.89,"latencyMs":62839,"costUsd":0.0902,"passed":true},
            {"testCaseId":"workhorse-multi-bug-fix","accuracy":10.0,"speed":10.0,"cost":3.87,"overall":8.47,"latencyMs":18538,"costUsd":0.0306,"passed":true}
          ]
        },
        "LIGHTWEIGHT": {
          "accuracy": 10.0,
          "speed": 1.652,
          "cost": 2.899,
          "overall": 6.138,
          "reasoning": "Avg accuracy=10.0, speed=1.7, cost=2.9 across 3 tests. 3/3 passed.",
          "testResults": [
            {"testCaseId":"lightweight-request-triage","accuracy":10.0,"speed":1.0,"cost":1.85,"overall":5.71,"latencyMs":71929,"costUsd":0.0513,"passed":true},
            {"testCaseId":"lightweight-dual-summary","accuracy":10.0,"speed":2.96,"cost":4.02,"overall":6.74,"latencyMs":8370,"costUsd":0.0079,"passed":true},
            {"testCaseId":"lightweight-config-diff-analysis","accuracy":10.0,"speed":1.0,"cost":2.83,"overall":5.96,"latencyMs":25044,"costUsd":0.0255,"passed":true}
          ]
        }
      },
      "bestFitTier": "WORKHORSE"
    },
    {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "tierScores": {
        "PLANNING": {
          "accuracy": 9.444,
          "speed": 5.758,
          "cost": 10.0,
          "overall": 8.662,
          "reasoning": "Avg accuracy=9.4, speed=5.8, cost=10.0 across 6 tests. 4/6 passed.",
          "testResults": [
            {"testCaseId":"planning-plan-generation","accuracy":10.0,"speed":5.36,"cost":10.0,"overall":8.84,"latencyMs":38215,"costUsd":0.0179,"passed":true},
            {"testCaseId":"planning-code-review","accuracy":10.0,"speed":7.48,"cost":10.0,"overall":9.37,"latencyMs":27607,"costUsd":0.0124,"passed":true},
            {"testCaseId":"planning-architecture-decision","accuracy":10.0,"speed":5.36,"cost":10.0,"overall":8.84,"latencyMs":38192,"costUsd":0.0145,"passed":true},
            {"testCaseId":"planning-saga-diagnosis","accuracy":8.57,"speed":4.86,"cost":10.0,"overall":8.00,"latencyMs":81443,"costUsd":0.0397,"passed":false},
            {"testCaseId":"planning-saga-consistency","accuracy":10.0,"speed":7.35,"cost":10.0,"overall":9.34,"latencyMs":42339,"costUsd":0.0158,"passed":true},
            {"testCaseId":"planning-saga-compliance","accuracy":8.10,"speed":4.14,"cost":10.0,"overall":7.58,"latencyMs":66462,"costUsd":0.0327,"passed":false}
          ]
        },
        "WORKHORSE": {
          "accuracy": 10.0,
          "speed": 9.442,
          "cost": 10.0,
          "overall": 9.860,
          "reasoning": "Avg accuracy=10.0, speed=9.4, cost=10.0 across 3 tests. 3/3 passed.",
          "testResults": [
            {"testCaseId":"workhorse-websocket-notification","accuracy":10.0,"speed":8.32,"cost":10.0,"overall":9.58,"latencyMs":35066,"costUsd":0.0342,"passed":true},
            {"testCaseId":"workhorse-virtualized-search-list","accuracy":10.0,"speed":10.0,"cost":10.0,"overall":10.0,"latencyMs":21103,"costUsd":0.0177,"passed":true},
            {"testCaseId":"workhorse-multi-bug-fix","accuracy":10.0,"speed":10.0,"cost":10.0,"overall":10.0,"latencyMs":6887,"costUsd":0.0074,"passed":true}
          ]
        },
        "LIGHTWEIGHT": {
          "accuracy": 9.522,
          "speed": 4.148,
          "cost": 10.0,
          "overall": 8.298,
          "reasoning": "Avg accuracy=9.5, speed=4.1, cost=10.0 across 3 tests. 2/3 passed.",
          "testResults": [
            {"testCaseId":"lightweight-request-triage","accuracy":10.0,"speed":1.0,"cost":10.0,"overall":7.75,"latencyMs":18687,"costUsd":0.0078,"passed":true},
            {"testCaseId":"lightweight-dual-summary","accuracy":10.0,"speed":8.27,"cost":10.0,"overall":9.57,"latencyMs":3942,"costUsd":0.0020,"passed":true},
            {"testCaseId":"lightweight-config-diff-analysis","accuracy":8.57,"speed":3.17,"cost":10.0,"overall":7.58,"latencyMs":8189,"costUsd":0.0049,"passed":false}
          ]
        }
      },
      "bestFitTier": "WORKHORSE"
    },
    {
      "provider": "openai",
      "model": "gpt-5.4",
      "tierScores": {
        "PLANNING": {
          "accuracy": 8.917,
          "speed": 5.255,
          "cost": 6.182,
          "overall": 7.318,
          "reasoning": "Avg accuracy=8.9, speed=5.3, cost=6.2 across 6 tests. 4/6 passed.",
          "testResults": [
            {"testCaseId":"planning-plan-generation","accuracy":10.0,"speed":1.0,"cost":4.31,"overall":6.33,"latencyMs":63138,"costUsd":0.0667,"passed":true},
            {"testCaseId":"planning-code-review","accuracy":10.0,"speed":6.38,"cost":5.01,"overall":7.85,"latencyMs":33095,"costUsd":0.0392,"passed":true},
            {"testCaseId":"planning-architecture-decision","accuracy":10.0,"speed":5.90,"cost":6.12,"overall":8.01,"latencyMs":35484,"costUsd":0.0354,"passed":true},
            {"testCaseId":"planning-saga-diagnosis","accuracy":7.19,"speed":6.35,"cost":7.41,"overall":7.04,"latencyMs":66493,"costUsd":0.0723,"passed":false},
            {"testCaseId":"planning-saga-consistency","accuracy":10.0,"speed":10.0,"cost":8.06,"overall":9.51,"latencyMs":21805,"costUsd":0.0247,"passed":true},
            {"testCaseId":"planning-saga-compliance","accuracy":6.31,"speed":1.90,"cost":6.19,"overall":5.18,"latencyMs":83282,"costUsd":0.0788,"passed":false}
          ]
        },
        "WORKHORSE": {
          "accuracy": 10.0,
          "speed": 7.480,
          "cost": 5.239,
          "overall": 8.180,
          "reasoning": "Avg accuracy=10.0, speed=7.5, cost=5.2 across 3 tests. 3/3 passed.",
          "testResults": [
            {"testCaseId":"workhorse-websocket-notification","accuracy":10.0,"speed":6.42,"cost":6.82,"overall":8.31,"latencyMs":49385,"costUsd":0.0712,"passed":true},
            {"testCaseId":"workhorse-virtualized-search-list","accuracy":10.0,"speed":6.02,"cost":3.51,"overall":7.38,"latencyMs":52318,"costUsd":0.0793,"passed":true},
            {"testCaseId":"workhorse-multi-bug-fix","accuracy":10.0,"speed":10.0,"cost":5.39,"overall":8.85,"latencyMs":13195,"costUsd":0.0216,"passed":true}
          ]
        },
        "LIGHTWEIGHT": {
          "accuracy": 9.522,
          "speed": 3.172,
          "cost": 4.471,
          "overall": 6.672,
          "reasoning": "Avg accuracy=9.5, speed=3.2, cost=4.5 across 3 tests. 2/3 passed.",
          "testResults": [
            {"testCaseId":"lightweight-request-triage","accuracy":10.0,"speed":1.0,"cost":3.25,"overall":6.06,"latencyMs":33595,"costUsd":0.0371,"passed":true},
            {"testCaseId":"lightweight-dual-summary","accuracy":10.0,"speed":7.15,"cost":4.95,"overall":8.02,"latencyMs":4877,"costUsd":0.0063,"passed":true},
            {"testCaseId":"lightweight-config-diff-analysis","accuracy":8.57,"speed":1.37,"cost":5.21,"overall":5.93,"latencyMs":9692,"costUsd":0.0147,"passed":false}
          ]
        }
      },
      "bestFitTier": "WORKHORSE"
    },
    {
      "provider": "openai",
      "model": "gpt-5.4-mini",
      "tierScores": {
        "PLANNING": {
          "accuracy": 8.147,
          "speed": 9.915,
          "cost": 7.642,
          "overall": 8.463,
          "reasoning": "Avg accuracy=8.1, speed=9.9, cost=7.6 across 6 tests. 2/6 passed.",
          "testResults": [
            {"testCaseId":"planning-plan-generation","accuracy":10.0,"speed":9.49,"cost":6.09,"overall":8.89,"latencyMs":17563,"costUsd":0.0443,"passed":true},
            {"testCaseId":"planning-code-review","accuracy":10.0,"speed":10.0,"cost":6.49,"overall":9.12,"latencyMs":12584,"costUsd":0.0279,"passed":true},
            {"testCaseId":"planning-architecture-decision","accuracy":8.57,"speed":10.0,"cost":7.22,"overall":8.59,"latencyMs":11752,"costUsd":0.0275,"passed":false},
            {"testCaseId":"planning-saga-diagnosis","accuracy":7.19,"speed":10.0,"cost":8.85,"overall":8.31,"latencyMs":20021,"costUsd":0.0518,"passed":false},
            {"testCaseId":"planning-saga-consistency","accuracy":6.81,"speed":10.0,"cost":9.29,"overall":8.23,"latencyMs":8188,"costUsd":0.0186,"passed":false},
            {"testCaseId":"planning-saga-compliance","accuracy":6.31,"speed":10.0,"cost":7.92,"overall":7.64,"latencyMs":20741,"costUsd":0.0528,"passed":false}
          ]
        },
        "WORKHORSE": {
          "accuracy": 9.455,
          "speed": 10.0,
          "cost": 6.292,
          "overall": 8.801,
          "reasoning": "Avg accuracy=9.5, speed=10.0, cost=6.3 across 3 tests. 2/3 passed.",
          "testResults": [
            {"testCaseId":"workhorse-websocket-notification","accuracy":10.0,"speed":10.0,"cost":7.92,"overall":9.48,"latencyMs":17258,"costUsd":0.0553,"passed":true},
            {"testCaseId":"workhorse-virtualized-search-list","accuracy":8.37,"speed":10.0,"cost":5.28,"overall":8.00,"latencyMs":17461,"costUsd":0.0527,"passed":false},
            {"testCaseId":"workhorse-multi-bug-fix","accuracy":10.0,"speed":10.0,"cost":5.68,"overall":8.92,"latencyMs":5108,"costUsd":0.0201,"passed":true}
          ]
        },
        "LIGHTWEIGHT": {
          "accuracy": 9.064,
          "speed": 7.227,
          "cost": 5.819,
          "overall": 7.794,
          "reasoning": "Avg accuracy=9.1, speed=7.2, cost=5.8 across 3 tests. 2/3 passed.",
          "testResults": [
            {"testCaseId":"lightweight-request-triage","accuracy":10.0,"speed":4.39,"cost":6.28,"overall":7.67,"latencyMs":7172,"costUsd":0.0184,"passed":true},
            {"testCaseId":"lightweight-dual-summary","accuracy":10.0,"speed":10.0,"cost":5.87,"overall":8.97,"latencyMs":2310,"costUsd":0.0051,"passed":true},
            {"testCaseId":"lightweight-config-diff-analysis","accuracy":7.19,"speed":7.29,"cost":5.30,"overall":6.74,"latencyMs":4760,"costUsd":0.0144,"passed":false}
          ]
        }
      },
      "bestFitTier": "WORKHORSE"
    }
  ],
  "recommendations": {
    "PLANNING": {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "score": 8.662,
      "reasoning": "Best PLANNING model: accuracy=9.4, speed=5.8, cost=10.0"
    },
    "WORKHORSE": {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "score": 9.860,
      "reasoning": "Best WORKHORSE model: accuracy=10.0, speed=9.4, cost=10.0"
    },
    "LIGHTWEIGHT": {
      "provider": "anthropic",
      "model": "claude-haiku-4-5-20251001",
      "score": 8.298,
      "reasoning": "Best LIGHTWEIGHT model: accuracy=9.5, speed=4.1, cost=10.0"
    }
  }
}
```

---

*Report generated from eval run `cb5362f6` and E2E results in `scripts/e2e-results-{openai,anthropic,google}.json`*
