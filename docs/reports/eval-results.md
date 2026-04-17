# Eval results — strict scenarios v2 × providers

> Live data from 2026-04-16 against the local build. Submitted via
> `POST /api/runs` with `executionMode=PROTOTYPE`, `skipApproval=true`,
> `autoMerge=true`. All runs share the strict spec text from
> `docs/reports/eval-scenarios-v2.md`.

## Summary table

| Spec | Provider | Model in use | Status | Wall time | In tokens | Out tokens | Verified | Accuracy | Reviewer | Build | Strict tests | Notes |
|---|---|---|---:|---:|---:|---:|---:|---:|---|:---:|---:|---|
| **S1 (TINY fib)** | Anthropic | `claude-haiku-4-5-20251001` | COMPLETE | 94 s | 113 470 | 7 468 | — | — | (via API) | ✓ | **7/7** | clean iterative impl with full docstring; matches spec exactly |
| **S1 (TINY fib)** | OpenAI | `gpt-5.4-mini` | COMPLETE | 69 s | 83 144 | 2 925 | **90** | 82 | CLEAN | ✓ | **7/7** | terse, correct, type-hinted, double quotes |
| **S1 (TINY fib)** | Gemini | `gemini-3-flash-preview` | (stuck REVIEWING) | 5+ min | 151 324 | 2 882 | — | — | — | ✓ | **7/7** | tasks ALL complete on disk + tests pass; reviewer hung |
| **S2 (SMALL shorten)** | Anthropic | `claude-haiku-4-5-20251001` | WORKING (T3 failed, T4 in flight) | 4+ min | 4 206 | 2 568 | — | — | — | — | n/a | T3 (test suite) failed without reason; harness retried as T4 |
| **S2 (SMALL shorten)** | OpenAI | `gpt-5.4-mini` | COMPLETE | 160 s | 126 313 | 6 318 | (pending) | (pending) | (pending) | ✓ | **5/5** but only 5 of 12 strict cases written | server.js works; misses 4xx + 415 + ID-format coverage I asked for |
| **S2 (SMALL shorten)** | Gemini | `gemini-3-flash-preview` | **FAILED** at planner | 80 s | 3 414 | 0 | — | — | — | — | n/a | `MALFORMED_FUNCTION_CALL` — `salvagePlanFromText` did not save it |

## Cost (estimated from token counts)

Estimates use these list prices (lowercase = per million tokens, in/out): Haiku 4.5 ≈ $1/$5, gpt-5.4-mini ≈ $0.15/$0.60, Gemini 3 Flash Preview ≈ $0.30/$2.50. Real billed cost is whatever the local cost-log materialises post-terminate.

| Spec | Provider | $ in | $ out | $ total |
|---|---|---:|---:|---:|
| S1 | Anthropic | 0.113 | 0.037 | **~$0.15** |
| S1 | OpenAI | 0.012 | 0.002 | **~$0.014** |
| S1 | Gemini | 0.045 | 0.007 | **~$0.052** |
| S2 | Anthropic (so far) | ~0.004 | ~0.013 | **~$0.017** |
| S2 | OpenAI | 0.019 | 0.004 | **~$0.023** |
| S2 | Gemini (failed early) | ~0.001 | 0 | **~$0.001** |
| | | | **Subtotal** | **~$0.26** |

Cumulative LLM spend for the eval matrix: **well under $1**. The $25 cap is
nowhere in play.

## What worked

### OpenAI `gpt-5.4-mini` (S1 + S2)
- **Cleanest happy path.** Built S1 in 69 s, S2 in 160 s. Both directly
  satisfied the spec. Test suites generated were minimal but passed.
- **Cheapest by an order of magnitude.** S1 at ~1.4 ¢, S2 at ~2.3 ¢.
- **Correct type hints** in S1 (`def fib(n: int) -> int:`) — strict-eval point
  most providers miss without nudging.
- **Verified score 90** — the new V106 scoring picked up the build pass +
  CLEAN reviewer + base40 + dimension scores ~82 across the board.

### Anthropic `claude-haiku-4-5` (S1)
- **Most thoroughly documented output.** Every function has Args/Returns/Raises.
- **Tests pass** identically (7/7).
- **Slower (94 s vs 69 s) and 36 % more input tokens** than OpenAI — pays
  the price of a longer system prompt + more verbose chain-of-thought.
- **Cost ~10× OpenAI** for an equivalent artefact.

### Gemini `gemini-3-flash-preview` (S1 only)
- **Code is correct** — 7/7 tests pass on disk.
- **`def fib(n)` is missing the `int` type annotation**, violating the strict
  contract clause "exact signature `fib(n: int) -> int`". Auto-graders that
  parse signatures would fail this; pytest didn't catch it.
- **Reviewer phase hung** (>5 min REVIEWING with all worker tasks COMPLETE).
  This is a real availability issue: the run is "done" from a code POV but
  cannot terminate. The user sees a never-finishing run.

## What broke

### Bug A — Gemini planner `MALFORMED_FUNCTION_CALL` recurrence (S2)
- Run `aad8fe51-fc65-4c66-90d3-cb4130d7af0c`, status FAILED at 80 s with:
  > `planner: IllegalStateException: Planner LLM did not call submitPlan. Finish reason: MALFORMED_FUNCTION_CALL; text preview: [Gemini terminated with finishReason=MALFORMED_FUNCTION_CALL; detail: Malformed function call: call:default_api:submitPlan{buildCommand: npm install ,planSummary: Build a Node.js Express 4 URL shorten...`
- This is the same family of failure that v2.5.9 added
  `PlannerAgentV2.salvagePlanFromText` to mitigate. The salvage path **did not
  trigger** here — the response wasn't a text blob, it was a malformed
  function-call envelope, which the regex doesn't cover.
- **Suggested fix:** extend `salvagePlanFromText` to also accept the
  `Malformed function call:` prefix and parse the JSON-ish body that follows.
  Even better, introduce a `PlannerOutputRepairAgent` (low-tier) that takes a
  text blob and re-emits a valid `submitPlan` call — already feasible with
  the existing tier infra.

### Bug B — Anthropic chat reaching an Opus-4.7-class model that rejects `temperature`
- `POST /api/projects/{id}/chat` against any Anthropic-default project (`Hostel
  Management`, `UX Test Project`, `5-Repo Evaluation`) returns:
  > `Sorry — chat failed: 400 - {"type":"error","error":{"type":"invalid_request_error","message":"`temperature` is deprecated for this model.","request_id":"req_011..."}}`
- The chat path uses `AgentTier.PLANNING` ([memory/ProjectChatService.java:144](src/main/java/com/navneet/executespec/memory/ProjectChatService.java:144)).
  `application.yml` maps `anthropic.planning → claude-sonnet-4-6` — a model that
  fully supports `temperature`.
- The live `/api/models/available` returns `claude-opus-4-7` for Anthropic
  PLANNING despite the YAML saying `sonnet-4-6` — the JVM has stale runtime
  state from before the matrix landed at its current values.
- **Impact:** every project that defaults to Anthropic has a non-functional
  chat tab — silently. The OPENAI-defaulted project chat returns sensible,
  RAG-grounded responses citing actual repo paths.
- **Fix:** restart the backend so the YAML model matrix takes effect; verify
  `/api/models/available` returns `claude-sonnet-4-6` for Anthropic PLANNING.
  **Do NOT remove the `.temperature(...)` calls** — `temperature` is required
  for our flows and is supported by `sonnet-4-6` and `haiku-4-5`. The Opus 4.7
  migration is *not* on the roadmap right now.

### Bug C — Reviewer can hang the whole run (S1 Gemini)
- Run `00657e25-50d2-4c6d-8599-e61756b0ea6e` — 3/3 worker tasks COMPLETE,
  artefacts on disk pass tests, but the run sits in `REVIEWING` indefinitely.
- ReviewerAgentV3 uses `gemini-3-flash-preview` for both general + wiring
  passes; with no per-pass timeout in the harness, a stuck pass blocks the
  whole termination path.
- **Impact:** UI shows a never-terminating run; the user has no way to read
  the verified score (V106 columns are written at terminate). Cost continues
  to accrue while the LLM sits in a tool loop.
- **Fix:** add a wall-clock timeout to each reviewer pass (e.g. 90 s general,
  60 s wiring). Treat timeout as `REVIEW_FAILED` and let
  `resolveTerminalStatus` derive the run status from the build + worker
  outcomes (the v2.5.10 logic already covers this fall-through).

### Bug D — Worker T3 silent FAILED (S2 Anthropic)
- Run `a1084272-e2a3-4c8b-8b9c-e129384f4ba8`, T3 (test suite generation)
  status `FAILED`, `failureReason: ""`. Empty string, not null, not a
  message. Harness retried as T4.
- **Fix:** ensure `failureReason` is populated on every FAILED transition,
  even if it's `"unknown — check logs at trace_id X"`.

### Bug E — Five zombie executions on a 24-hour-old DB
- `POST /api/admin/executions/reconcile` returned:
  > `{ "reconciled": 5, "reconciledIds": [...], "staleMinutes": 30 }`
- Five `ExecutionRun` rows had been stuck in `ARCH_REVIEW` / `EXECUTING` for
  12-24 hours after the developer closed the laptop. Per CLAUDE.md this is
  what the AdminReconciliationService is for — but it's a manual endpoint.
- **Fix:** add a `@Scheduled` job that runs reconcile every 15 minutes with
  a configurable `staleMinutes` (default 60). Otherwise every dev session
  starts with a flock of stuck runs that block new executions per project.

## What about the strict rubric?

Even on the runs that "PASSED" in the harness sense, the strict-rubric scoring
exposes gaps:

| | S1 / OpenAI | S1 / Anthropic | S1 / Gemini | S2 / OpenAI |
|---|:---:|:---:|:---:|:---:|
| Functional correctness (out of 30) | 30 | 30 | 28 (missing type hint) | 27 (5 of expected 12 cases) |
| Contract conformance (out of 20) | 20 | 20 | 16 (signature) | 16 (status code coverage) |
| Security hygiene (out of 15) | 13 | 13 | 13 | 13 |
| Performance budget (out of 10) | n/a (deferred for S1) | n/a | n/a | 6 (no perf test ran) |
| Observability (out of 10) | 6 | 6 | 6 | 9 (X-Trace-Id present!) |
| DX & repo hygiene (out of 10) | 9 | 10 | 8 (extra `test_edge_cases.py` outside `tests/`) | 9 |
| Test quality (out of 5) | 4 | 4 | 4 | 2 (only 5 cases) |
| **Strict total** | **82** | **83** | **75** | **82** |

Note the harness gives Gemini the same "tests passed" tick as OpenAI/Anthropic
because pytest doesn't enforce signature shape — but the strict score cuts
Gemini 7 points for the missing type annotation. This is exactly the gap the
strict-eval plan is meant to surface.

## Model recommendations

The product currently exposes per-tier:
- **PLANNING:** `claude-sonnet-4-6`, `gemini-3-flash-preview`, `gpt-5.4`
  (Anthropic Opus 4.7 deliberately excluded — temperature deprecation, deferred)
- **WORKHORSE:** `claude-haiku-4-5-20251001`, `gemini-3-flash-preview`, `gpt-5.4`
- **LIGHTWEIGHT:** same as WORKHORSE

**Eval-driven recommendations:**

1. **Default OpenAI's WORKHORSE / LIGHTWEIGHT to `gpt-5.4-mini`.** It's what
   the live API actually returned for our LIGHTWEIGHT runs (`plannerModel:
   "gpt-5.4-mini"` in the response), and it was 10× cheaper than Anthropic
   Haiku for an equivalent or better artefact. The published matrix in
   CLAUDE.md says `gpt-5.4` for all OpenAI tiers — that's stale.

2. **Add a Gemini PLANNING model that isn't Flash.** Flash Preview's tool-call
   formatting is unreliable (Bug A). Wire `gemini-3-pro-preview` (or `gemini-3-pro`
   when GA) for the planner tier on the GOOGLE provider, keep Flash for
   workhorse.

3. **Keep Anthropic PLANNING on `claude-sonnet-4-6`.** Opus 4.7 is explicitly
   off the menu — it deprecated the `temperature` param our flows rely on,
   and the migration is not on this roadmap. The current sonnet-4-6 default
   is correct; do not let the live `/api/models/available` drift back to
   `opus-4-7` after a JVM restart.

4. **Add a lightweight + premium per provider.** The strict-eval design called
   for two cells per provider; today OpenAI and Gemini collapse to one. The
   matrix is more useful if it can show "cheap-but-OK" vs "premium-and-worth-it"
   *within* a provider rather than only across providers.

5. **Stop calling the Gemini model `Gemini 3 Flash Preview`** in the UI.
   "Preview" implies "do not run in prod" — the tool-call malformation
   evidence (Bug A) supports that. Mark it Beta in the model selector and
   consider holding new project defaults to Anthropic/OpenAI until it's GA.

## Closing budget

Cumulative LLM spend across the 6 eval runs: **~$0.26**. The $25 cap is
intact with $24.74 remaining. Plenty of headroom for the project-shell
launch and any retries. The main cost leak is the *hung* Gemini reviewer,
which is consuming nothing now but would have continued chewing tokens if
the planner pass were stuck rather than a worker pass.
