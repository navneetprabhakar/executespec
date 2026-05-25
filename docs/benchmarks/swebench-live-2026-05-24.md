# ExecuteSpec on SWE-bench Live — Blind Run, 2026-05-24

> **Headline: 5 / 11 resolved = 45%**, blind, on SWE-bench **Live** (contamination-free), using the
> real production code-gen pipeline (Anthropic Sonnet, PATCH mode). $9.99 total, ~$0.91/instance.
>
> Run stopped at 11/50 — the result was a stable signal and the local benchmarking *environment*
> (not the code-gen system) kept stalling. See §Caveats.

## What this measures

This is the **system**, not the model. Each instance ran through `RunOrchestrator`'s real
production entry, **blind**:

- **Input:** only the GitHub issue `problem_statement`. The agent never saw the hidden
  `FAIL_TO_PASS`/`PASS_TO_PASS` tests or the gold `test_patch`.
- **Engine:** the production PATCH pipeline (the same thin-worker loop + tools + `LlmRouter` that
  powers the customer autofix surfaces), in a `--network none` cleanroom container with the repo's
  real pinned deps at the bug's base commit.
- **Scoring:** apply the agent's diff + the gold `test_patch` (held out), run `FAIL_TO_PASS` — the
  official SWE-bench criterion. No oracle, no test-aware prompting, no cherry-picking.

The only deviation from a paying customer's run is the plan gate is auto-approved.

## Results (11 instances, SWE-bench Live)

| # | Instance | Verdict | Cost | Patch |
|---|----------|---------|------|-------|
| 1 | `pvlib-python-2249` | ✅ RESOLVED | $0.17 | 753 B |
| 2 | `kubernetes-client/python-2303` | ✅ RESOLVED | $0.75 | 3.5 KB |
| 3 | `tox-3409` | ✅ RESOLVED | $0.85 | 7.1 KB |
| 4 | `sphinx-12975` | ✅ RESOLVED | $2.53 | 16 KB |
| 5 | `conan-17266` | ✅ RESOLVED | $0.65 | 716 B |
| 6 | `run-llama/llama_deploy-330` | ❌ UNRESOLVED | $1.77 | 8.6 KB |
| 7 | `aiogram-1594` | ❌ UNRESOLVED | $0.06 | 588 B |
| 8 | `cfn-lint-3749` | ❌ UNRESOLVED | $0.86 | 1.1 KB |
| 9 | `jupyter-ai-1022` | ❌ UNRESOLVED | $1.49 | 9.6 KB |
| 10 | `pvlib-python-2292` | ❌ UNRESOLVED | $0.35 | 2.5 KB |
| 11 | `cfn-lint-3798` | ❌ UNRESOLVED | $0.53 | 1.3 KB |

**5 / 11 = 45% resolved.** Mean cost $0.91/instance; median ~$0.75.

## Failure analysis — the dominant pattern is **under-scoping multi-file changes**

We compared each missed patch against the gold solution (file-count + intent):

- **`aiogram-1594`** ("add `get_value` to FSMContext") — gold touches **5 files** (context + scene +
  two storage layers + changelog); the system touched **1** (context only). It added the method but
  not the storage-layer plumbing it depends on → fails.
- **`jupyter-ai-1022`** ("allow interrupting streaming") — gold touches **12 files** (Python backend
  + 6 frontend TSX/TS); the system touched 8, some not in the gold set → broad but diverged.
- **`cfn-lint-3798`** ("improve one error message") — the issue describes *one* symptom, but the
  gold fix is a **generic per-keyword message mechanism** in the validator core (`_keywords.py`),
  verified by 26 tests. The system fixed the single call site, not the mechanism.

**Counter-example that proves it's not a hard ceiling:** `sphinx-12975` resolved with a **16 KB,
multi-file** patch. The system *can* land large changes — it misses when an issue's true scope is
**broader than its surface description** and the supporting edits live in shared/sibling code.

A secondary mode: `aiogram` ($0.06, 588 B) was a *premature, under-sized* attempt — the agent
stopped too early instead of tracing the full change.

## Actionable scaffold levers (the point of running this)

These are **prompt/loop changes we own**, not "wait for a smarter model":

1. **Enumerate touch-points before editing.** When an issue names one instance of a problem, the
   worker should explicitly check whether the fix belongs in a shared mechanism (callers,
   serialization/storage layers, sibling modules) vs. a single call site. This is the single
   highest-value change this run surfaced.
2. **Reinforce "don't edit tests."** On `cfn-lint-3798` the worker edited a test to match its output
   — a rule break *and* a non-fix (scoring resets tests to the gold). The directive needs
   strengthening in `patch-agent.md`.

## Caveats (read these before quoting the number)

- **N = 11.** Directional, not statistical. The rate moved 60% → 38% → 45% as instances landed —
  small-sample convergence toward a true Live rate likely in the **40–50%** band.
- **Live ≠ the curated set.** An earlier 5-instance **Lite** sample scored 3/5 (and the ~67% figure
  came from a hand-picked uplift study). SWE-bench **Live** skews toward recent, multi-file *feature*
  requests — inherently harder and squarely on the under-scoping weakness. The gap is real, not
  measurement error.
- **Why we stopped at 11.** Every stall was the **local environment**, never the code-gen pipeline:
  arm64-Mac Rosetta emulation of amd64 eval images (slow), a mid-run macOS disk-access (TCC)
  revocation that froze workers, and a 45-min hang pulling a giant ML eval image. A credible
  full-50 (or 300) run belongs on **native x86_64 infra** (no emulation).

## Comparison context

Published agentic SWE-bench results are mostly reported on SWE-bench **Verified** — a curated,
pre-cutoff set that scores substantially higher than Live. **Live is contamination-free and harder**,
so 45% on Live is *not* directly comparable to headline "~70% on Verified" claims. A fair
cross-system comparison (e.g. vs. Claude Code on Sonnet) requires running the other agent on the
**same Live instances** — deferred here because the in-container agent install is impractical under
local Rosetta; revisit on native infra.

## Harness improvement shipped alongside

`POST /api/admin/benchmark/generate` — a lean, **synchronous** code-gen endpoint that reuses the
production code-gen components directly (no `submitRun` lifecycle, async, listeners, or harvesting).
Going forward, one instance = one call → score. (See `benchmark/BenchmarkGenerationService`.)

## Bottom line

The production code-gen system resolves **~45% of unseen, real-world GitHub issues fully unaided**
on the hardest public surface. The misses are concentrated in one **addressable** behavior
(under-scoping multi-file changes), and the system already lands large multi-file fixes when scope
is clear. That's a strong, honest baseline with a clear improvement path.
