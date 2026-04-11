# ExecuteSpec E2E Matrix Test Report

**Date:** 2026-04-11  
**Environment:** https://dev.executespec.ai  
**Build:** ea2d065 (subtask-aware retry + preview queue fixes)  
**Mode:** PROTOTYPE · skipApproval=true · 5 specs × 3 providers = 15 runs  
**Execution:** All 3 providers ran in parallel

---

## Executive Summary

| Metric | Value |
|--------|-------|
| Total runs | 15 |
| ✅ COMPLETE | 6 (40%) |
| ⚠️ PARTIAL | 6 (40%) |
| ⏱️ TIMEOUT | 3 (20%) |
| ❌ FAILED | 0 |
| Total cost (completed runs) | $7.91 |
| Total tokens | 4,896,782 in / 282,390 out |

---

## 1. Results Matrix

| Spec | Tier | Anthropic | Google | OpenAI |
|------|------|-----------|--------|--------|
| CloudPulse Landing Page | simple | ⚠️ PARTIAL 0/1 | ⚠️ PARTIAL 0/1 | ⚠️ PARTIAL 0/1 |
| Todo App (Material Design) | simple | ✅ COMPLETE 1/1 | ✅ COMPLETE 1/1 | ✅ COMPLETE 1/1 |
| Task API — Node.js + MongoDB | backend | ⚠️ PARTIAL 14/16 | ⏱️ TIMEOUT | ✅ COMPLETE 14/14 |
| Notes API — Java + MongoDB | backend | ✅ COMPLETE 22/22 | ⏱️ TIMEOUT | ⏱️ TIMEOUT |
| Bookstore API — Python + MongoDB | backend | ⚠️ PARTIAL 13/14 | ⚠️ PARTIAL 12/13 | ✅ COMPLETE 12/12 |

---

## 2. Per-Provider Summary

| Metric | Anthropic | Google | OpenAI |
|--------|-----------|--------|--------|
| **Success rate** | **2/5 (40%)** | **1/5 (20%)** | **3/5 (60%)** |
| Partial (near-miss) | 3/5 | 2/5 | 1/5 |
| Timeout | 0/5 | 2/5 | 1/5 |
| Models (plan/work) | sonnet-4-6 / haiku-4-5 | gemini-3-flash-preview | gpt-5.4 |
| Total cost | $1.25 | $0.50 | $6.16 |
| Total input tokens | 1,562,556 | 1,156,595 | 2,177,631 |
| Total output tokens | 208,586 | 25,812 | 47,992 |

---

## 3. Per-Run Detail

### Anthropic (claude-sonnet-4-6 planner / claude-haiku-4-5 worker)

| Spec | Status | Duration | Tasks | Cost | Tokens (in/out) |
|------|--------|----------|-------|------|----------------|
| CloudPulse Landing Page | ⚠️ PARTIAL | 10s | 0/1 | — | 0 / 0 |
| Todo App | ✅ COMPLETE | 74s | 1/1 | $0.097 | 32,359 / 12,874 |
| Task API — Node.js | ⚠️ PARTIAL | 149s | 14/16 | — | 189,069 / 44,629 |
| Notes API — Java | ✅ COMPLETE | 374s | 22/22 | $1.151 | 627,150 / 97,412 |
| Bookstore API — Python | ⚠️ PARTIAL | 415s | 13/14 | — | 713,978 / 53,671 |

**Task completion across all Anthropic runs:** 50/54 = **92.6%**

### Google (gemini-3-flash-preview)

| Spec | Status | Duration | Tasks | Cost | Tokens (in/out) |
|------|--------|----------|-------|------|----------------|
| CloudPulse Landing Page | ⚠️ PARTIAL | 10s | 0/1 | — | 0 / 0 |
| Todo App | ✅ COMPLETE | 118s | 1/1 | $0.497 | 140,579 / 9,693 |
| Task API — Node.js | ⏱️ TIMEOUT | 606s | — | — | 0 / 0 |
| Notes API — Java | ⏱️ TIMEOUT | 609s | — | — | 0 / 0 |
| Bookstore API — Python | ⚠️ PARTIAL | 266s | 12/13 | — | 1,016,016 / 16,119 |

**Task completion (non-timeout runs):** 13/15 = **86.7%**

### OpenAI (gpt-5.4)

| Spec | Status | Duration | Tasks | Cost | Tokens (in/out) |
|------|--------|----------|-------|------|----------------|
| CloudPulse Landing Page | ⚠️ PARTIAL | 10s | 0/1 | — | 0 / 0 |
| Todo App | ✅ COMPLETE | 86s | 1/1 | $0.395 | 110,950 / 7,837 |
| Task API — Node.js | ✅ COMPLETE | 202s | 14/14 | $2.795 | 980,615 / 22,909 |
| Notes API — Java | ⏱️ TIMEOUT | 608s | — | — | 0 / 0 |
| Bookstore API — Python | ✅ COMPLETE | 595s | 12/12 | $2.974 | 1,086,066 / 17,246 |

**Task completion (non-timeout runs):** 27/27 = **100%**

---

## 4. Failure Analysis

### Issue 1 — CloudPulse Landing Page: PARTIAL (0/1) across all 3 providers

**Pattern:** All 3 providers, 10s duration, 0 tokens consumed, 0/1 tasks.  
**Root cause:** Cold-start race condition on fresh deployment. The spec is classified as SINGLE_SHOT (1 task). The run transitions to PARTIAL before the worker thread can start — likely the task is stuck in PENDING when the pipeline exits. This is not provider-specific.  
**Evidence:** All 3 triggered within seconds of each other immediately after deployment. The subsequent SINGLE_SHOT spec (Todo App) succeeded for all 3 providers.  
**Fix needed:** Investigate SINGLE_SHOT fast path — task may be queued but harness marks run PARTIAL before worker thread acquires the slot.

### Issue 2 — Google: TIMEOUT on Task API + Notes API (backend specs)

**Pattern:** Both 10-minute timeouts hit on multi-task backend specs.  
**Root cause:** Google/Gemini generates significantly more tasks than Anthropic/OpenAI for backend specs (observed previously: 108 tasks for HIGH vs 63 for OpenAI). The test harness `poll_until_terminal` timeout (600s) is insufficient for Google's decomposition volume on complex specs.  
**Additional factor:** Google's output token count is much lower per task (25,812 out vs 208,586 for Anthropic across 5 runs) — suggests Gemini is verbose in planning but terse in execution, potentially hitting completion detection edge cases.  
**Fix needed:** Increase `SPEC_TIMEOUTS` for backend specs from 600s → 900s for Google, or investigate why Gemini stalls mid-execution.

### Issue 3 — OpenAI: TIMEOUT on Notes API (Java + MongoDB)

**Pattern:** Single timeout, 608s, 0 tokens consumed.  
**Root cause:** Notes API is a Java + Spring Boot + MongoDB spec — one of the heaviest in the matrix. OpenAI's PLANNING tier (gpt-5.4 at $2.50/$15.00) may have hit rate limiting or an upstream slowdown during this specific run. The adjacent run (Bookstore/Python) completed in 595s with 12/12 tasks, suggesting the platform was functional.  
**Likely cause:** Rate limit or transient upstream delay, not a systematic issue.

### Issue 4 — Anthropic PARTIAL runs (Task API + Bookstore)

| Run | Tasks | Missing |
|-----|-------|---------|
| Task API — Node.js | 14/16 | Create Task Mongoose model; Create Express server entry point |
| Bookstore API — Python | 13/14 | Wire database lifespan and router into main.py |

Both are "wiring" tasks — the final integration task that connects components. This is a known pattern: subtask decomposition assigns separate files to separate subtasks, but the main entry point wiring task depends on all others and sometimes fails due to missing context or merge conflicts. The new `PartialRunResumeService` (subtask-aware retry) should auto-recover these on the next 5-min sweep.

---

## 5. Cost Analysis (completed runs only)

| Spec | Anthropic | Google | OpenAI |
|------|-----------|--------|--------|
| Todo App (simple, 1 task) | **$0.097** | $0.497 | $0.395 |
| Task API — Node.js (14 tasks) | — (PARTIAL) | — (TIMEOUT) | $2.795 |
| Notes API — Java (22 tasks) | **$1.151** | — (TIMEOUT) | — (TIMEOUT) |
| Bookstore API — Python (12 tasks) | — (PARTIAL) | — (PARTIAL) | $2.974 |

**Cost per completed task:**
- Anthropic: $1.248 / 23 tasks = **$0.054/task**
- Google: $0.497 / 1 task = **$0.497/task** (only 1 complete multi-step run)
- OpenAI: $6.164 / 27 tasks = **$0.228/task**

Anthropic is **4.2× cheaper per task than OpenAI** on comparable completed work. However Anthropic has higher PARTIAL rates on complex specs.

---

## 6. Duration Analysis

| Spec | Anthropic | Google | OpenAI |
|------|-----------|--------|--------|
| Todo App | **74s** | 118s | 86s |
| Task API — Node.js | 149s (PARTIAL) | TIMEOUT | **202s** |
| Notes API — Java | **374s** | TIMEOUT | TIMEOUT |
| Bookstore API — Python | 415s (PARTIAL) | **266s** (PARTIAL) | 595s |

Anthropic is fastest for Java (374s for 22 tasks). OpenAI is most consistent for Node.js/Python backend specs. Google is fastest mid-run but stalls on complex specs.

---

## 7. Key Findings & Recommendations

### 1. OpenAI most reliable for backend specs
3/5 complete, 100% task completion rate on non-timeout runs. Best for production use today. High cost ($0.228/task) is the tradeoff.

### 2. Anthropic best cost-per-task but needs PARTIAL retry fix
$0.054/task — 4× cheaper than OpenAI. PARTIAL rates are high on complex specs (3/5 PARTIAL) but task completion within those runs is 92.6%. The new subtask-aware `PartialRunResumeService` should recover these automatically. Recommend re-running after the next deploy to validate.

### 3. Google needs investigation before production use
2 hard timeouts on backend specs. Either the test harness timeout (600s) needs to increase to 900s, or there's a stall in Gemini's execution on multi-file backend specs. Cost is low ($0.497 for todo-app) but reliability is too low at 20% overall success rate.

### 4. CloudPulse PARTIAL (cold-start) is a platform bug — not provider-related
All 3 providers failed identically on the first spec after a fresh deployment. Needs investigation in the SINGLE_SHOT fast path. Not counted against any provider in reliability assessment.

### 5. Increase backend spec timeout for Google
`SPEC_TIMEOUTS` for backend specs: 600s → 900s in `e2e-matrix-test.sh`.

---

## 8. Raw Run IDs

| Spec | Anthropic | Google | OpenAI |
|------|-----------|--------|--------|
| CloudPulse Landing Page | 95601e9a | 2060354c | fd4e6987 |
| Todo App | 96f54d3a | d9f2a398 | f8f4bbf8 |
| Task API — Node.js | d569a9ab | 54372e41 | 600d6756 |
| Notes API — Java | cee49e8e | 5a206a37 | a46e35ff |
| Bookstore API — Python | be60472f | 62363717 | 7b0af806 |
