# SWE-bench Scoring Oracle — Correction & Re-measurement (2026-05-25)

> **Headline:** while iterating on the fixed Lite-10/Anthropic sample I discovered the **scoring
> oracle itself was broken for 4 of the 10 instances** — the *gold* patch fails in my scoring
> container, so those cases could never score RESOLVED no matter how good the worker's patch was.
> The earlier "2/10 Lite" and "~6–7% Live" numbers were measured against this broken ruler and are
> **under-counts**. This doc records the bug, the two fixes (both verified), and the corrected
> re-measurement.

## 1. How it was found

Per the "verify deeply / read the on-disk state" rule, instead of running yet another prompt-lever
A/B I opened up the actual worker-vs-gold diffs on the UNRESOLVED cases. On **pvlib-1854** the worker
had produced the *exact* gold functional change (`elif isinstance(arrays, Array): self.arrays = (arrays,)`)
plus an `np.Inf → np.inf` cleanup — which I first mis-read as "over-scoping noise." Testing the
minimal isinstance-only patch revealed the truth: it **fails to import**, because the image ships
`numpy 2.0.2` (which *removed* `np.Inf`) while the pvlib code at that commit still uses `np.Inf`.

That prompted the decisive check: **score the gold patch itself.** It failed too. So the worker's
`np.Inf → np.inf` edit was not noise — it was *correct adaptive behaviour* that let the code run at
all in the drifted container.

## 2. Gold-validation: only 6/10 gold patches pass

Apply **only** the gold patch + gold test, run F2P, for all 10 Lite cases:

| Instance | Gold verdict | Root cause |
|---|---|---|
| marshmallow-1359 | **PASS** | — |
| pydicom-1139 | **PASS** | (numpy 1.24.3) |
| pydicom-1413 | **PASS** | (numpy 2.0.2, but no `np.Inf` in path) |
| astroid-1333 | **PASS** | — |
| sqlfluff-1625 | **PASS** | — |
| sqlfluff-1763 | **PASS** | — |
| **pvlib-1154** | **FAIL** | numpy 2.0.2 removed `np.Inf`; code imports crash |
| **pvlib-1707** | **FAIL** | same |
| **pvlib-1854** | **FAIL** | same |
| **astroid-1866** | **FAIL** | F2P is a parametrized test with a multiline-string param id (`...[\n…`) → the literal node-id selector matches nothing → "no tests ran" |

So the **maximum achievable score** on this sample with the old scorer was **6/10, not 10/10**, and
the pvlib instances were a *coin-flip*: they could only score RESOLVED if the worker *coincidentally*
fixed `np.Inf`. (Observed live: pvlib-1707 flipped RESOLVED→UNRESOLVED across two runs with the
**same prompt** — pure oracle non-determinism, not a real signal.)

## 3. Two root causes, two fixes (both verified)

### 3a. NumPy 2.0 image drift (pvlib ×3)
The public `swebench/sweb.eval.x86_64.*:latest` images for these older commits were (re)built with
`numpy>=2.0`, which removed `np.Inf` / `np.NaN` / `np.float_` etc. Any test that imports the package
crashes before collection. The gold patches were authored against a `numpy<2` era and do not fix this.

**Fix (verified):** detect the `np.Inf`-class `AttributeError`, `pip install 'numpy<2'` (→ 1.26.4),
retry. Restores an environment contemporaneous with the gold commit. After the pin, pvlib-1854 gold
→ **1 passed**. Mirrors the existing libGL apt-install repair the driver already does.

### 3b. Parametrized-test F2P selector (astroid-1866)
F2P = `tests/...::TestStringNodes::test_string_format_uninferable[\n…]`. The `[…]` param id contains
a newline/escapes; passed as a literal pytest selector it matches **no collectible node** → "no tests
ran" → scored as fail even for gold.

**Fix (verified):** strip the `[…]` suffix, select the **function** (runs all its params, requires all
pass). After the strip, astroid-1866 gold → **7 passed**.

Both fixes are folded into the driver (`bench_matrix.py` `score()`) so all future runs score against a
valid oracle, and into a standalone `re_score.py` for re-scoring already-generated patches with **no
re-generation**.

## 4. Corrected re-measurement (sandbox-arm patches, valid oracle)

> Sandbox arm = baseline (oracle) patch-agent prompt **+** the `ShellTool` in-container sandbox fix
> (lets the worker actually run its own repro/pytest inside the cleanroom container). Re-scored with
> the numpy<2 + param-strip fixes.

**Corrected Lite-10/Anthropic = 2/10 RESOLVED** (`pvlib-1854`, `pydicom-1413`).

| Instance | Corrected verdict | Detail |
|---|---|---|
| pvlib-1854 | **RESOLVED** | numpy<2 repair → isinstance fix is correct (`1 passed`). **Hidden by the broken oracle.** |
| pydicom-1413 | **RESOLVED** | `38 passed` — legit. |
| pvlib-1707 | UNRESOLVED | `1 failed` — real iam.py fix is wrong. **Was a FALSE resolve in baseline** (only "passed" via coincidental np.Inf + oracle non-determinism). |
| pvlib-1154 | UNRESOLVED | `numpy.dtype size changed` ABI mismatch — a C-extension built against numpy 2.0 breaks under numpy<2. **This image is broken under *both* numpy versions** → unscoreable locally without a from-source rebuild. |
| marshmallow-1359 | UNRESOLVED | `1 failed` — defensive `getattr` guard, missed the wrong-object root. |
| pydicom-1139 | UNRESOLVED | `1 failed, 2 passed` — has `__iter__`, missing `__next__`. |
| astroid-1866 | UNRESOLVED | `1 failed, 6 passed` — 6 of 7 params pass; one edge case unhandled. Close. |
| sqlfluff-1625 | UNRESOLVED | `1 failed`. |
| sqlfluff-1763 | UNRESOLVED | `3 failed, 1 passed` — temp-file approach differs from gold + stray `tmpl1pabduk` in diff. |
| astroid-1333 | EMPTY_PATCH | thrash — 98 iters, 0 edits, $2.90. |

**Net effect of fixing the oracle: headline unchanged (2/10).** A hidden resolve (pvlib-1854) and a
false resolve (pvlib-1707) cancelled. The system is **not** being massively under-counted on this
sample — but the *composition* is now honest, and pvlib-1154 shows some public `:latest` images are
simply not locally scoreable (the official harness / a from-source env rebuild is the only fix there).

## 5. What this does NOT change

- The **clean-signal failure taxonomy** stands (the 6 gold-PASS instances):
  - **marshmallow-1359** — worker made a *defensive* `getattr(schema,"opts",None)` guard; gold's real
    fix is a wrong-object reference (`schema.opts → self.root.opts`). Comprehension miss.
  - **pydicom-1139** — worker added `__iter__`/`__contains__` (functionally iterable) but **not**
    `__next__`; gold implements the full stateful iterator protocol. Right behaviour, wrong *shape* →
    fails the test that pins the protocol. Sub-type A (different valid approach), hard blind.
  - **sqlfluff-1763** — worker's write-to-temp-then-replace fix is functionally close to gold, but the
    diff also captured a **stray empty file `tmpl1pabduk`** the worker created while testing
    (diff-pollution). Real product-PR-quality bug (L3), though rare in this sample (1/10).
  - **astroid-1333** — **thrash**: 98 iters, **$2.90, 17 min, 0 edits, empty patch**. PATCH mode
    *deliberately disables* WorkerAgent's no-edit force-terminate ("investigation is the work"), so the
    worker read to near the cap and still produced nothing. The long runway did **not** rescue it.
- The dominant blind failure remains **fix-quality / wrong-shape**, which prompt levers don't move
  (the blast-radius and L1-minimality A/Bs both moved the score by ~0, and L1's minimality clause
  actively *hurt* a multi-file fix). Honest ceiling unchanged: this needs investigation-quality work,
  not a prompt sentence.

## 6. Product-relevant levers that survive (focus: general runs, not the benchmark)

| Lever | Observed trigger | Product surface it protects |
|---|---|---|
| **L2 — no-edit ceiling (middle ground ~40, not 12, not ∞)** | astroid-1333 $2.90/17min/0-edit | autofix + existing-repo runs: caps runaway cost/time when the worker can't localise |
| **L3 — gitignore-aware diff capture** | sqlfluff-1763 stray `tmpl1pabduk` | the 4 autofix surfaces open PRs — stop committing scratch/temp files |
| **ShellTool in-container sandbox skip** | worker couldn't run repro/pytest in cleanroom | a real correctness fix: blind/benchmark + cleanroom test-exec scoring |

L2/L3 are cost/quality wins, **not** blind-score movers — stated honestly.

## 7. Caveats / honesty

- The earlier **matrix report (`swebench-matrix-report-2026-05-25.md`) numbers are under-counts** for
  any instance with dep drift; its "diff-pollution ~20%" was inflated by broken-oracle Live runs
  (true rate here 1/10). Those docs are annotated with a pointer to this correction.
- N is small (10 Lite). Directional, not statistical.
- The numpy<2 pin is an environment *repair*, not a change to the task — it restores the gold-era env
  so the worker's fix is judged on merit rather than on whether it stumbled into an unrelated env fix.
