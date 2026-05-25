# SWE-bench Phase-1 — CORRECTED Report (official harness) — 2026-05-25

> **This supersedes every benchmark number I reported in the prior 2 days.** Those were produced by a
> hand-rolled scorer that turned out to have **4 distinct bugs** and mis-scored ~70% of the sample in
> **both directions**. All numbers below are from the **official SWE-bench harness** (`swebench 4.1.0`),
> which runs each repo's real test command. Re-scoring is **$0 LLM** (it runs tests on already-saved
> patches), ~5–6 min per 29 instances.

## TL;DR — the system is performing ~3× better than my broken scorer showed

| Metric (test-300 stratified-30, django/sympy-heavy real repos) | My buggy scorer | **Official harness** |
|---|---|---|
| **Anthropic** (claude-sonnet-4-6, reproduce-first) | 6/29 (21%) | **17/29 (59%)** |
| **OpenAI** (gpt-5.4, reproduce-first) | ~6/28 (21%) | **15/29 (52%)** |

**~59% (Anthropic) on real existing-repos is strong** — above the published SWE-bench *Lite* SOTA (~48%). The "17%, this is worse, overhaul the logic" conclusion was a **scoring artifact**, not the system.

## The scorer bugs (why every prior number was wrong)
My hand-rolled `bench_matrix.py` ran `python -m pytest <F2P>` for every repo. That breaks because:
1. **numpy-2.0 image drift** — `np.Inf` removed → import crash (caught + patched earlier).
2. **parametrized F2P** (`name[param]`) — unmatchable selector (caught + patched).
3. **missing pytest** — sympy's testbed has no pytest → "No module named pytest" → test never ran.
4. **non-pytest F2P formats** — **django** uses unittest (`method (module.Class)`); **sympy** uses bare names. `python -m pytest` can't run either → silent false-negatives.

Net: only matplotlib/sklearn/sphinx/pytest (pytest node-IDs) were scored at all, and even those had **false positives** (see sphinx below). The official harness knows each repo's real runner (django→`runtests.py`, etc.).

## Corrected Anthropic detail (official)

**Per band** — the "LOW=0%, difficulty inverted" story I told you was *entirely* a scorer artifact:
| Band | My buggy scorer | **Official** | cost/inst | time/inst |
|---|---|---|---|---|
| LOW | 0/10 | **7/10** | $0.55 | 373s |
| MEDIUM | 3/11 | **6/11** | $1.00 | 544s |
| HIGH | 3/8 | **4/8** | $0.51 | 509s |
| **Total** | 6/29 | **17/29 (59%)** | **$0.71/inst** | **~475s (8 min)/inst** |

**Per repo** — the correction is bidirectional:
| Repo | My buggy scorer | **Official** |
|---|---|---|
| django | 0/13 | **9/13** |
| sympy | 0/7 | **4/7** |
| scikit-learn | (some) | 2/2 |
| matplotlib | (some) | 1/3 |
| pytest | — | 1/1 |
| **sphinx** | **2/2 (false +)** | **0/2** |
| pydata/xarray | — | 0/1 |

## The 12 genuine Anthropic failures (the REAL improvement targets, from a 59% base)
django-11019, django-12589, django-14534, django-15738, matplotlib-18869, matplotlib-22835,
xarray-3364, sphinx-10325, sphinx-8435, sympy-14317, sympy-16106, sympy-24102.
These are correctly-scored misses — the actual targets for any code-gen improvement (not the phantom 23).

## Cost / time (unchanged by the scorer fix — from generation)
- Anthropic: **$0.71/instance, ~8 min/instance** (475s avg; some django 13–20 min).
- OpenAI: **~$0.35/instance, ~2 min/instance** — ~2× cheaper, ~4× faster, ~10pts lower accuracy.

## Still UNRELIABLE (used the buggy scorer; not yet re-scored on the official harness)
- **dev-10 "baseline 2/10 → reproduce-first 5/10"** and **fresh-10 "6/10"** — the basis for commit fb7a90e0. **Cheaply re-scoreable** on the official harness (dev split, $0 LLM, ~15 min); was in progress when stopped.
- **Live-45 ("45%" then "6-7%")** — SWE-bench *Live* is a separate dataset, **not in the official Lite/Verified harness**; needs the Live harness to re-score. Treat as unknown.

## Conclusion / what actually needs to change
1. **Adopt the official SWE-bench harness as the canonical scorer; retire the hand-rolled one.** It caused two days of phantom results. This is the single most important "fix."
2. **The system is solid** on real existing-repo PATCH (~48–59%). No emergency logic overhaul is warranted by the data.
3. **Real improvement targets = the 12 genuine misses** — improving from ~59%, not a false 17%.
4. **Re-validate commit fb7a90e0** (reproduce-first) on the official harness before trusting/merging it.
