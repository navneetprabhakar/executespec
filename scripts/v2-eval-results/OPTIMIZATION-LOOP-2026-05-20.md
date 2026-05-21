# Scoring Optimization Loop — 2026-05-20

Goal: consistent **90+ verifiedScore** on MEDIUM + HIGH complexity pipeline runs,
across Anthropic / OpenAI / Gemini. Budget: $20/provider. **Achieved.**

## Final scoreboard (verifiedScore)

| Provider  | Medium      | High        |
| --------- | ----------- | ----------- |
| Anthropic | 100, 91     | 94, 94      |
| OpenAI    | 91, 96      | 91, 91      |
| Gemini    | 91          | 94          |

Every run ≥ 90. Lowest observed: **91**.

## Spend (this loop, from run_cost_log delta since 2026-05-20T03:00:53Z)

| Provider  | Spent  | Cap  |
| --------- | ------ | ---- |
| Anthropic | $12.72 | $20  |
| OpenAI    | $5.09  | $20  |
| Gemini    | $5.70  | $20  |

## Root causes found + fixed (each validated against real run scores)

1. **Dead feature-flag cache** (`fix(flags)` 7c35248a) — V202 seeded
   `reviewer.third-pass.enabled` with `scope='GLOBAL'`, but `FeatureFlag.Scope`
   only had PLATFORM/ORG. `repository.findAll()` threw on that row on every
   reload, emptying the entire flag cache → every flag read its default (off).
   So `scoring.additive-v2` was `true` in the DB but read `false`, and the
   pipeline silently ran the legacy MULTIPLICATIVE formula. **This masked the
   whole additive-scoring rebuild.** Medium 72 → 85.

2. **Vague coverage rubric** (`fix(scoring)` 6d64620b) — `testCoverageLevel`
   had no band definitions, so the LLM judge defaulted to PARTIAL (damped 40)
   even when every spec endpoint had a test. Added precise bands (GOOD = every
   required target has a happy-path test). High 88 → 94.

3. **No worker test mandate** (`feat(worker)` 70ad2acb) — the worker base
   prompt relied entirely on planner test-tasks; Gemini's Flash worker skipped
   them (coverage NONE). Added a "TESTS ARE PART OF THE TASK" directive.
   Gemini medium 79 → 91.

4. **Eval validator bug** (`fix(eval)` ebfe6efe) — the WORKHORSE fix-bug
   validator whitespace-stripped file content then checked for "def is_even(n)"
   WITH spaces, under-scoring every correct fix by 20. Also: the WORKHORSE eval
   was loading the legacy XML `worker.txt` instead of the production
   `SYSTEM_PROMPT_BASE`.

5. **Tier-eval false alarm** (in `feat(eval)` 44426b3a) — first run reported
   Anthropic "never calls submitOutline" (0/100). That was a harness bug:
   `grade()` read only `resp.getResult()` (first generation = Anthropic's empty
   thinking block) instead of `resp.getResults()` (all). Production's
   OutlinePlannerAgent already iterates getResults(), so it was never affected.

## Scoring shape that makes 90+ reachable (additive)

`verifiedScore = weighted-avg over measured slots:`
CODE_DELIVERED(30) + BUILD(25) + TESTS(20) + REVIEWER(20) + CRITICALS(5).

Realistic floor now: reviewer FIXED_ISSUES (85) + coverage GOOD (70, LLM-cap) =
**91**. Sub-90 only on a genuine test gap (PARTIAL→85 / NONE→79), which the
worker test directive prevents.

## Known tradeoff (NOT a scoring problem)

The worker test directive made HIGH runs slower (more per-task work). The
11-task Spring Boot HIGH spec on Gemini Flash ran ~75 min and timed out the
eval *script* (1200–1800s) — but completed server-side and scored 94. This is
a **latency** concern, separate from the 90+ goal. Future work: parallel task
execution or a smarter per-task budget for large plans.

## Scoring-integrity note

Coverage = UNKNOWN drops the TESTS slot (additive "don't penalize unmeasured"),
which can let a run score 100. The rubric now says "no test files = NONE" (not
UNKNOWN), so the judge should only emit UNKNOWN when it genuinely can't read the
tests. If UNKNOWN→inflation recurs in production telemetry, treat UNKNOWN as a
damped value rather than dropping it.
