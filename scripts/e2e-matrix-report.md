# ExecuteSpec E2E Matrix Test Report

**Date:** 2026-04-10 13:10 IST
**Environment:** https://dev.executespec.ai
**Execution Mode:** PROTOTYPE (all providers run in parallel)
**Total Runs:** 15 (5 specs x 3 providers)

---

## Executive Summary

| Metric | Value |
|--------|-------|
| Total runs | 15 |
| Completed | 6 (40%) |
| Failed | 5 |
| Timeout | 4 |
| Success rate | **40% — CRITICAL** |
| Total wall-clock time | 26m 49s (parallel) |
| Total LLM cost | $4.07 |
| Total tokens | 2.85M in / 294K out |
| Validation reports generated | **0/15 (0%)** |

### Verdict: NOT READY FOR LAUNCH

Two systemic issues cause 60% of runs to fail:
1. **RequestTriageService misclassification** — all PROTOTYPE runs go through single-shot path
2. **Single-shot path cannot handle multi-file projects** — complex specs fail or timeout

---

## Critical Root Cause: Triage Misclassification

**Every single run** was routed to the single-shot fast path (`SingleShotExecutor`) instead of the full pipeline (PlannerAgent -> TaskExecutor). Evidence:

- All task IDs contain `_single_shot` suffix
- All run titles start with `Single-shot:`
- Planner/compiler/reviewer tokens = 0 across all providers (planner was never invoked)
- Successful Anthropic runs produced 12-17 tasks via single-shot decomposition (subtasks), while Google/OpenAI produced only 1 task

**Why this happens:** The triage prompt (`prompts/triage.txt`) classifies backend-only APIs as `SINGLE_SHOT` because the `MULTI_TASK` criteria requires "BOTH a backend AND a frontend." A Node.js+MongoDB API with no frontend UI passes triage as single-shot.

**Why Google/OpenAI fail more:** The `LIGHTWEIGHT` tier models (gemini-2.5-flash, gpt-5.4-mini) used by the single-shot worker produce less robust multi-file output than claude-haiku-4-5. When triage forces these complex specs through a single worker call, weaker models fail entirely.

**Fix required in `prompts/triage.txt`:** Add rule: "Backend APIs with multiple routes, models, or middleware are MULTI_TASK regardless of frontend."

**Fix required in `RequestTriageService.java`:** Add regex pattern for backend-only indicators: `mongodb|mongoose|fastapi|spring.boot|express.*routes|middleware.*auth`.

---

## 1. Per-Run Results Matrix

| # | Spec | Provider | Status | Duration | Cost | Tokens (in/out) | Tasks | Notes |
|---|------|----------|--------|----------|------|----------------|-------|-------|
| 1 | CloudPulse Landing Page | ANTHROPIC | COMPLETE | 96s | $0.14 | 56K/18K | 1/1 | Single-shot, no report |
| 1 | CloudPulse Landing Page | GOOGLE | FAILED | 43s | — | 0/0 | 0/1 | Single-shot worker failed |
| 1 | CloudPulse Landing Page | OPENAI | FAILED | 42s | — | 0/0 | 0/1 | Single-shot worker failed |
| 2 | Todo App (Material Design) | ANTHROPIC | COMPLETE | 85s | $0.17 | 109K/12K | 1/1 | Single-shot, no report |
| 2 | Todo App (Material Design) | GOOGLE | FAILED | 42s | — | 0/0 | 0/1 | Single-shot worker failed |
| 2 | Todo App (Material Design) | OPENAI | FAILED | 53s | — | 0/0 | 0/1 | Single-shot worker failed |
| 3 | Task API — Node.js + MongoDB | ANTHROPIC | COMPLETE | 396s | $1.62 | 928K/131K | 17/17 | Full decomposition via single-shot |
| 3 | Task API — Node.js + MongoDB | GOOGLE | COMPLETE | 43s | $0.10 | 107K/4K | 1/1 | Shallow single-file output |
| 3 | Task API — Node.js + MongoDB | OPENAI | COMPLETE | 43s | $0.14 | 159K/4K | 1/1 | Shallow single-file output |
| 4 | Notes API — Java + MongoDB | ANTHROPIC | FAILED | 160s | — | 99K/27K | 0/1 | Single-shot too complex for Java |
| 4 | Notes API — Java + MongoDB | GOOGLE | TIMEOUT | 611s | — | 0/0 | 0/0 | Stuck in single-shot worker |
| 4 | Notes API — Java + MongoDB | OPENAI | TIMEOUT | 608s | — | 0/0 | 0/0 | Stuck in single-shot worker |
| 5 | Bookstore API — Python + MongoDB | ANTHROPIC | COMPLETE | 363s | $1.90 | 1.4M/98K | 12/12 | Full decomposition via single-shot |
| 5 | Bookstore API — Python + MongoDB | GOOGLE | TIMEOUT | 608s | — | 0/0 | 0/0 | Stuck in single-shot worker |
| 5 | Bookstore API — Python + MongoDB | OPENAI | TIMEOUT | 608s | — | 0/0 | 0/0 | Stuck in single-shot worker |

---

## 2. Per-Provider Comparison

| Metric | Anthropic | Google | OpenAI |
|--------|-----------|--------|--------|
| Success Rate | **4/5 (80%)** | 1/5 (20%) | 1/5 (20%) |
| Avg Duration (completed) | 235s | 43s | 43s |
| Total Cost | $3.83 | $0.10 | $0.14 |
| Total Input Tokens | 2.58M | 107K | 159K |
| Total Output Tokens | 285K | 4K | 4K |
| Avg Tasks/Run (completed) | 7.75 | 1.0 | 1.0 |
| Validation Reports | 0/5 | 0/5 | 0/5 |
| Compilation Pass | 0/5 | 0/5 | 0/5 |

### Why Anthropic Outperforms

Anthropic (claude-haiku-4-5) generates **multi-file decomposition** even within the single-shot path — it produces 12-17 subtasks with proper file structure. Google (gemini-2.5-flash) and OpenAI (gpt-5.4-mini) produce **single-file output** in single-shot mode, which fails for multi-file projects.

This is a model capability gap in the WORKHORSE tier, not a platform issue per se — but the platform shouldn't rely on model-specific behavior. The proper fix is routing to the full pipeline.

### Cost Efficiency

Google and OpenAI appear cheap ($0.10-$0.14) but they only produced 1 task with shallow output. Anthropic's $1.62-$1.90 per backend spec reflects actual multi-file generation (17 tasks for Node.js, 12 for Python).

---

## 3. Per-Spec Analysis

### Spec 1: CloudPulse Landing Page (Simple, 1 file)

| Provider | Status | Duration | Cost | Quality |
|----------|--------|----------|------|---------|
| ANTHROPIC | COMPLETE | 96s | $0.14 | Single HTML file generated |
| GOOGLE | FAILED | 43s | — | Worker failed immediately |
| OPENAI | FAILED | 42s | — | Worker failed immediately |

**Analysis:** A single HTML file should be trivially achievable. The Google/OpenAI failures here are likely concurrency-related — 3 single-shot workers launched simultaneously. Anthropic's success may be due to faster model initialization.

### Spec 2: Todo App — Material Design (Simple, 1 file)

| Provider | Status | Duration | Cost | Quality |
|----------|--------|----------|------|---------|
| ANTHROPIC | COMPLETE | 85s | $0.17 | Single HTML file with localStorage |
| GOOGLE | FAILED | 42s | — | Worker failed |
| OPENAI | FAILED | 53s | — | Worker failed |

**Analysis:** Same pattern as Spec 1. Simple frontend specs should pass but Google/OpenAI fail under concurrent load.

### Spec 3: Task API — Node.js + Express + MongoDB (Backend, multi-file)

| Provider | Status | Duration | Cost | Tasks | Quality |
|----------|--------|----------|------|-------|---------|
| ANTHROPIC | COMPLETE | 396s | $1.62 | 17/17 | Full project with auth, routes, models, middleware |
| GOOGLE | COMPLETE | 43s | $0.10 | 1/1 | Single-file dump (not a proper project) |
| OPENAI | COMPLETE | 43s | $0.14 | 1/1 | Single-file dump (not a proper project) |

**Analysis:** All 3 marked COMPLETE, but quality differs vastly. Anthropic generated a proper 17-file project structure. Google/OpenAI generated 1 file in 43s — likely a single `index.js` with everything crammed in. The 1-task completion is misleading quality-wise.

### Spec 4: Notes API — Java + Spring Boot + MongoDB (Backend, multi-file)

| Provider | Status | Duration | Failure |
|----------|--------|----------|---------|
| ANTHROPIC | FAILED | 160s | Single-shot couldn't handle pom.xml + multi-package Java |
| GOOGLE | TIMEOUT | 611s | Stuck in worker loop |
| OPENAI | TIMEOUT | 608s | Stuck in worker loop |

**Analysis:** Failed on ALL 3 providers. Java projects require coordinated file generation (pom.xml, package structure, multiple classes). Single-shot path is fundamentally unable to handle this.

### Spec 5: Bookstore API — Python + FastAPI + MongoDB (Backend, multi-file)

| Provider | Status | Duration | Cost | Tasks |
|----------|--------|----------|------|-------|
| ANTHROPIC | COMPLETE | 363s | $1.90 | 12/12 |
| GOOGLE | TIMEOUT | 608s | — | 0/0 |
| OPENAI | TIMEOUT | 608s | — | 0/0 |

**Analysis:** Anthropic decomposed into 12 subtasks and completed. Google/OpenAI timed out trying to generate the entire FastAPI project in a single worker call.

---

## 4. Failure Category Analysis

| Category | Count | Runs | Root Cause |
|----------|-------|------|-----------|
| Single-shot worker failed | 5 | Specs 1,2 (Google/OpenAI), Spec 4 (Anthropic) | Model too weak for single-shot on concurrent load / complex specs |
| Timeout (stuck in worker) | 4 | Specs 4,5 (Google/OpenAI) | Single-shot worker loop exhausting 600s timeout on complex multi-file specs |
| Total non-complete | **9/15** | — | — |

### Cross-Cutting Issues

- **Notes API (Java):** Failed ALL 3 providers — triage routes it to single-shot, which cannot generate a multi-package Maven project
- **No validation reports generated (0/15):** Single-shot path likely doesn't trigger background validation
- **No compilation checks (0/15):** Same — validation pipeline bypassed entirely
- **Token counts 0/0 on failures:** Metrics not captured when single-shot fails immediately (tokens consumed but not persisted)

---

## 5. Token & Cost Breakdown by Agent Role

| Provider | Planner | Worker | Compiler | Reviewer | Total |
|----------|---------|--------|----------|----------|-------|
| ANTHROPIC | 0 | 2.58M in / 285K out | 0 | 0 | $3.83 |
| GOOGLE | 0 | 107K in / 4K out | 0 | 0 | $0.10 |
| OPENAI | 0 | 159K in / 4K out | 0 | 0 | $0.14 |

**Note:** Planner, compiler, and reviewer tokens are all 0 because single-shot path bypasses these agents entirely. All token consumption is from the worker.

---

## 6. Models Used

| Provider | Planner (not invoked) | Worker | Reviewer (not invoked) |
|----------|---------|--------|----------|
| ANTHROPIC | claude-sonnet-4-6 | claude-haiku-4-5-20251001 | claude-sonnet-4-6 |
| GOOGLE | gemini-3-flash-preview | gemini-2.5-flash | gemini-3-flash-preview |
| OPENAI | gpt-5.4 | gpt-5.4-mini | gpt-5.4 |

---

## 7. Immediate Action Items (Pre-Launch Blockers)

### P0 — Must Fix Before Launch

1. **Fix triage prompt** (`prompts/triage.txt`): Add backend-only API as MULTI_TASK criteria
   - Any spec mentioning MongoDB, PostgreSQL, Express routes, middleware, auth + CRUD = MULTI_TASK
   - Current prompt only considers backend+frontend combos

2. **Add backend keyword regex** (`RequestTriageService.java`): Extend `FULL_STACK_PATTERN` to catch backend-only multi-file specs
   - Patterns: `mongodb|mongoose|fastapi.*motor|spring.boot.*mongodb|express.*mongoose|crud.*api`

3. **Fix validation pipeline for single-shot** — 0/15 reports generated. Either:
   - Ensure single-shot triggers background validation (ReviewerAgentV2 + TestGen)
   - Or route more specs to full pipeline (preferred)

4. **Fix token persistence on failure** — failed runs show 0/0 tokens even though LLM calls were made. Metrics lost.

### P1 — Should Fix Before Launch

5. **Investigate concurrent worker failures** — Google/OpenAI simple specs (landing page, todo) failed under 3-concurrent-run load
6. **Add failureReason to all FAILED runs** — some runs have null failureReason
7. **Per-provider run pool** — see `docs/design-provider-run-pool.md` for isolation design

---

## 8. Raw Run IDs

| Spec | ANTHROPIC | GOOGLE | OPENAI |
|------|-----------|--------|--------|
| Landing Page | 31c4ea1c-744b-4732-935f-e6d2afdc4307 | 78ed0184-bdb0-4455-bde7-a45bf8cede69 | a16bf8e9-99d1-4e3f-a551-73de98e29b48 |
| Todo App | b48291f3-7c9d-4ee6-a113-303b2a7ef410 | 800d7d1d-b35d-462b-bcc7-c0bf4ef66cce | d3f75855-1511-4d08-af8f-c08a6bf8afa5 |
| Task API (Node) | 531ce76c-8a71-4cd0-8ba8-e3523249a35b | 77eb173b-94c2-4a50-bcd9-4ec5f8675ed9 | 219876d1-22a1-4665-a5d2-b0f068742ea8 |
| Notes API (Java) | 5ec447bc-83df-4c20-98d4-a11f410d1ffc | e0174d7f-3a86-4e66-bb68-b3dc2acea205 | c816941e-d4a5-47f7-96a4-4dad3147fa96 |
| Bookstore (Python) | 241026ca-bbad-446a-a5f5-53c828ab6479 | 9af3a22e-dcfa-42c6-8f7c-e7b366e30160 | 46355683-19e7-4b03-828e-8ace2a6fa9c4 |
