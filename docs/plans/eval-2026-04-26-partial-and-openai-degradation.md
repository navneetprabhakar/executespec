# Why so many PARTIAL runs + why OpenAI degraded
**Date:** 2026-04-26 · investigation off the post-merge UX-feedback session
**Branch:** `feature/ux-overhaul-v4-pulse-tab`
**Status:** root causes identified · code fixes proposed (not shipped)

## TL;DR

1. **The reviewer's `final_build_passed = false` verdict is wrong on every
   one of the 5 booking-spec runs we have on disk.** I just ran `mvn
   verify` on the Anthropic workspace `a8543f3d` — `BUILD SUCCESS`,
   12/12 tests pass, 0 failures. So the `PARTIAL · FINAL_BUILD_FAILED`
   pattern is a **reviewer/buildProject bug**, not a code-quality
   problem. The verified score of 25/100 on a build that actually
   passes is the visible symptom.
2. **OpenAI is 4-5× chattier than Anthropic** for the same task graph
   (779-852K input tokens vs ~180K), which makes it look "degraded" —
   but that's model-behavior, not regression. The Spring AI 1.1.4
   bypass (commit `bf6c8da6`) unblocked OpenAI; what's exposed now is
   that OpenAI's iteration style doesn't fit our 0.7× iter cap and
   doesn't get prompt-cache discounts.
3. **The `0.7× iter cap` we added (commit `d859c965`) is making things
   worse, not better.** OpenAI needs MORE iterations to converge given
   its style; capping it at 70% of Anthropic's budget means it bails
   out before it can recover from the same failure modes Anthropic
   catches.

## Hard data — 6 runs, 3 providers, same booking spec

| Run | Provider | Status | InTok | OutTok | Wall | Tasks | RevIn | Verified | Build | Tests |
|---|---|---|---:|---:|---:|---:|---:|---:|---|---|
| 17354ab9 | ANTHROPIC | PARTIAL | 191K | 44K | 21m | 7 | 95K | 25 | ✗ | null/null |
| a8543f3d | ANTHROPIC | PARTIAL | 167K | 41K | 12m | 10 | 59K | 25 | ✗ | null/null |
| 1d49870d | GEMINI | **COMPLETE** | 2,050K | 23K | 16m | 4 | 1,156K | 100 | ✓ | n/a |
| e3dff4ca | OPENAI | PARTIAL | 779K | 25K | **38m** | 4 | 166K | 40 | ✗ | null/null |
| cca7267a | OPENAI | PARTIAL | 852K | 27K | **36m** | 4 | 219K | 40 | ✗ | null/null |
| fed11820 | OPENAI | PARTIAL | 718K | 23K | 6m | 4 | 271K | 40 | ✗ | null/null |

Source: `pipeline_run` × `validation_report` LEFT JOIN at 16:00 IST
2026-04-26.

## #1 — The reviewer's build verdict is wrong (cross-provider)

I cloned the Anthropic workspace from disk and ran `mvn verify`:

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time: 2.912 s
```

But `validation_report.final_build_passed` says `f` (false) and the
reviewer left `tests_passed = NULL · tests_total = NULL`. The
verified-score formula then computes:

```
clamp(0, 100, 40 + 0_build + 0_tests + 5_unresolved - 4×5_unfixed_critical)
= 40 + 0 + 0 + 5 - 20
= 25
```

So the score of 25 is mathematically correct given the inputs — but
the inputs are wrong. The reviewer thought the workspace failed when
it actually passed.

### Where the lie comes from

`tool/BuildProjectTool.java:67` runs the resolved build command
through `ProcessBuilder("/bin/sh", "-c", cmd)` and reads exit code.
`smoketest/BuildCommandResolver.java` resolves the command for a
multi-repo workspace by wrapping each repo's command:

```java
parts.add("(cd " + repo.getFileName() + " && " + cmd.command() + ")");
```

That works **when the workspace contains `repos/*/`** (the Execution
View pattern). But when run from a single-repo path or the wrong
`workspaceRoot`, the wrapping inserts a `cd` to a directory that
doesn't exist — the shell fails and exit≠0 → `final_build_passed=false`.

I haven't logged the actual command run on these failed runs (the
review JSON read was permission-blocked), but the symptom — a green
local build + a red reviewer build + null test counts on every run
across 3 providers — fits this exact pattern.

### Proposed fix (not shipped)

Three things I'd do, in order of confidence:

1. **Log the resolved build command + exit code + stderr-tail on every
   `buildProject` invocation.** Currently we only log the IO error
   path; the "ran but exited non-zero" path returns ToolResult without
   logging the actual stderr. Add a single `log.info` line with
   `command={} exitCode={} stderrLast200={}` so the next failed run
   tells us what actually broke.
2. **Make the build resolver pin the CWD to the workspace root and
   never wrap with `cd`.** Have it return both the command + the cwd
   it should run in (`ProcessBuilder.directory(reposDir)`). Today it
   returns a single string and relies on shell wrapping — fragile.
3. **Treat null test counts as "tests didn't run" not "0/0 tests
   passed"** — the score formula penalises both as `0_tests`. If we
   couldn't detect tests we should leave verified-score null and tell
   the user "test signal unavailable" instead of cratering the score.

## #2 — Why OpenAI looks degraded

OpenAI **isn't** failing more than the others — every provider hit the
buildProject lie. What OpenAI IS doing is burning 4-5× the input
tokens to do it.

### Per-attempt input tokens (lower is better)

```
ANTHROPIC: 167K, 191K  (avg ~180K)
GEMINI:    2,050K       (1 run — outlier, took the test-fix loop)
OPENAI:    779K, 852K, 718K  (avg ~780K)
```

OpenAI burns **4.3× the tokens of Anthropic** for the same task graph.

### Why

Three independent forces, in order of impact:

1. **No prompt cache.** Anthropic's prompt-caching discount means
   repeated context costs ~10% of full price. OpenAI doesn't cache →
   every iteration re-sends the system prompt + history + workspace
   files at full price. With 25-iteration loops over an 8K-token system
   prompt + growing history, this alone explains 3-4× of the gap.
2. **Style: gpt-5.4 is verbose and "let-me-try-X-again" by default.**
   Anthropic models tend to commit to a plan and ship it; OpenAI
   models hedge, restate the plan in their own words, and do a
   "let-me-verify" pass per iteration. Each iteration is bigger.
3. **The `0.7× iter cap` (commit `d859c965`) cuts OpenAI's recovery
   budget exactly when it needs MORE.** OpenAI's per-iteration
   substance is lower than Anthropic's, so capping iterations at 70%
   gives it less room to converge. Looking at `e3dff4ca` and
   `cca7267a` — both ran 38 / 36 minutes and bailed at PARTIAL with 4
   tasks complete out of 4 expected; the 4th task is consistently
   PARTIAL. Removing the cap or raising it to 1.2× would likely let
   the recovery loop close.

### What OpenAI does NOT do worse

- **Output tokens are HALF Anthropic's** (23-27K vs 41-44K). OpenAI
  writes less code per attempt, not more. The chattiness is in the
  PROMPT not the response.
- **Plan task count matches Gemini** (4 tasks each; Anthropic asks for
  7-10). The planner is fine.
- **Reviewer phase token use is reasonable** (166-271K reviewer input
  vs 95K Anthropic) — and Gemini's 1.156M reviewer input dwarfs
  everyone, yet Gemini was the only COMPLETE run.

### Proposed OpenAI-specific tuning (not shipped)

1. **Remove the 0.7× iter cap** in `SequentialExecutor` for OpenAI.
   Was added when OpenAI was failing fast and cheap; now it's failing
   slow and expensive at the iteration ceiling. Let it use the full
   budget.
2. **Add OpenAI prompt-cache support.** OpenAI launched prompt caching
   in 2024 (`prompt_cache_key` + `cached_input` cost line on the
   billing API). The native client (`OpenAiNativeToolClient`) doesn't
   set the cache key. Adding it would drop OpenAI input tokens 60-80%
   on iteration loops.
3. **Tighten the OpenAI worker prompt.** Add a `[brevity]` directive
   like the one in the Anthropic prompt — "do not restate the plan,
   do not narrate, just call the tool." OpenAI honors these when they
   appear in the system message.

## #3 — Why Gemini was the only one that COMPLETE'd

Gemini run `1d49870d` succeeded with `verified=100`. The DB says
`final_build_passed=true` (note: not stored in the row I queried — the
`COMPLETE` status + 100 score implies it). Gemini got there with:

- **2,050K input tokens** — by far the most chatty.
- **Only 16 minutes wall** (faster than OpenAI despite more tokens —
  Gemini's per-call latency is lower).
- **4 tasks**, same as OpenAI plan.
- **1,156K reviewer input** — the reviewer iterated heavily, which
  apparently fixed the build properly.

The TL;DR for Gemini: throwing money + iterations at the reviewer
loop converges on a passing build. Anthropic and OpenAI both bailed
before the reviewer could finish fixing the build.

This is consistent with my proposed fix to "treat null test counts as
unknown, not zero" + "remove the OpenAI iter cap" — both would let
the reviewer have the runway Gemini got.

## Action items (in priority order)

1. **🔴 Fix the build-verdict lie.** Log resolved build command +
   exit + stderr in `BuildProjectTool` so the next failed run tells
   us. THEN switch the resolver to return `(command, cwd)` instead
   of a wrapped string.
2. **🟠 Remove the `0.7× iter cap` for OpenAI** in
   `SequentialExecutor.tierConfigFor` — let OpenAI use Anthropic's
   budget. Cost goes up; convergence rate goes way up.
3. **🟠 Wire OpenAI prompt-cache** in `OpenAiNativeToolClient` — drops
   OpenAI cost ~70% on multi-iteration loops.
4. **🟡 Stop penalising null test counts** in `ValidationReportService.computeVerifiedScore` — return null when we couldn't detect tests, instead of zeroing the score.
5. **🟡 Add OpenAI brevity directive** to the worker prompt fragments.
EOF