> ⚠️ **CORRECTION (later same day): the numbers below are under-counts.** The scoring oracle was
> later found broken for ~4/10 Lite instances (gold patch itself fails: numpy-2.0 image drift +
> a parametrized-F2P selector bug), and the "diff-pollution ~20%" was inflated by broken-oracle Live
> runs (true rate ~1/10). See **[`swebench-oracle-correction-2026-05-25.md`](swebench-oracle-correction-2026-05-25.md)**
> for the bug, the fixes, and the corrected re-measurement. Read this report's *failure-mode analysis
> and fixes* as still-valid; read its *resolved counts* as lower bounds.

# SWE-bench Provider Matrix — Strengths, Weaknesses & Fixes (2026-05-25)

> **Scope:** the production code-gen system run **blind** (issue-only spec; hidden tests applied
> externally at scoring), through the real pipeline (`/api/admin/benchmark/generate` → PATCH mode),
> across **all three providers**. Hit the backend on `:8080`; eval containers are the SWE-bench
> images.

## TL;DR (read this)

1. **On a hard random Live sample, accuracy is ~6–7% and is the SAME across all three providers** (Anthropic 1/15, OpenAI 0/15, Gemini 1/15). The providers differ **only in cost and speed, not accuracy.**
2. **The earlier "45%" was a lucky, easy, N=11 sample.** A proper random sample (checkov / xarray / reflex / streamlink / starlette …) is far harder. **6–7% is the honest Live number for this system today.**
3. **The bottleneck is the *system*, not the model.** Paying 4× more (Gemini $0.95 vs OpenAI $0.21) or running 4× longer buys **zero** extra resolves. A prompt-lever A/B (blast-radius enumeration) moved the number by **0**.
4. **The 3 dominant weaknesses are all fixable in the scaffold** — and the fixes help **normal product runs** (autofix surfaces + existing-repo runs), not just the benchmark.
5. **We are slow because of one thing: the worker's long investigate-without-converging tail** (runs hitting 80–120 iterations / $2–2.8 / 13–20 min). Fixing that is the single biggest speed *and* cost win.

---

## 1. Results

### 1a. SWE-bench **Live** — 15 instances × 3 providers (baseline/production prompt)

| Provider | Resolved | Cost/run | Total $ | Time avg / med / max | Iters avg/max | Verdict mix |
|---|---|---|---|---|---|---|
| **Anthropic** (Sonnet) | **1/15 (7%)** | $0.80 | $12.05 | 283s / 209s / 893s | 39 / 102 | 1 R, 9 UNRES, 3 PATCH_FAIL, 2 EMPTY |
| **OpenAI** (gpt-5.4) | **0/15 (0%)** | **$0.21** | $3.11 | **61s / 41s / 222s** | **12 / 20** | 10 UNRES, 3 PATCH_FAIL, 2 EMPTY |
| **Gemini** (3.5-flash) | **1/15 (7%)** | $0.95 | $14.25 | 235s / 192s / 581s | 48 / 120 | 1 R, 9 UNRES, 3 PATCH_FAIL, 2 EMPTY |

Only **`briefcase-2088`** resolved (on Anthropic + Gemini). Everything else: wrong fix, no fix, or non-applying patch.

### 1b. The A/B that matters: blast-radius lever changed nothing

Same 15 × 3, **with** the experimental "enumerate all touch-points" lever vs **without**:

| | Anthropic | OpenAI | Gemini |
|---|---|---|---|
| baseline | 1/15 | 0/15 | 1/15 |
| with lever | 1/15 | 1/15 | 1/15 |

Instance-for-instance near-identical. **The lever is unproven and has been dropped from the PR.** (My earlier "the lever caused a regression" hypothesis was wrong — the A/B disproved it.)

### 1c. SWE-bench **Lite** — partial (9/30 before the run was stopped)

| Provider | Resolved (partial) | Note |
|---|---|---|
| Anthropic | 0/3 | — |
| OpenAI | 1/3 | resolved 1 |
| Gemini | 0/3 | thrashed (avg 80 iters) |

Lite is **incomplete** (3 of 10 instances). Directionally consistent with Live (low, provider-similar). *Owed: the remaining 21 Lite jobs.*

### 1d. Context: the first Live run (the "45%")

5/11 resolved on an **easy, hand-adjacent sample** (pvlib / tox / sphinx / conan / k8s). Not representative — small N and easier repos. The matrix above is the corrective.

---

## 2. Per-provider strengths & weaknesses

**Accuracy is a tie (~6–7%, or 0 for OpenAI).** The real differences:

- **Anthropic (Sonnet) — best balance.** Investigates moderately (39 iters avg), $0.80/run, resolves at the top of the (low) range. **Weakness:** a long tail — `xarray` 893s/102-iters/$2.14 producing **nothing** (see §3). **Recommendation: keep Anthropic as the default for hard/autofix work.**
- **OpenAI (gpt-5.4) — fast & cheap, but gives up.** 4× cheaper ($0.21), 4–5× faster (61s), but **12 iters avg / 20 max → 0 resolved.** It under-investigates and bails. **Weakness:** thin-iteration give-up makes it unsuitable for hard existing-code tasks. **Strength:** for *trivial* localized fixes its speed/cost is attractive — but only there.
- **Gemini (3.5-flash) — most expensive, no accuracy gain.** $0.95/run, 48 iters avg, the longest tail (`xarray` 120 iters, max single run 581–1825s). Same 7% as Anthropic for ~20% more cost and more time. **Weakness:** pays the most, returns the least efficiency.

**Headline for provider choice: route by cost/speed within the accuracy tie — Anthropic for hard work, OpenAI only for trivial fast fixes, Gemini has no niche here.**

---

## 3. Failure-mode analysis (the weaknesses, ranked)

1. **Wrong fix — DOMINANT (~9–10/15 UNRESOLVED).** Patch applies cleanly, the held-out test fails. The system localizes and edits but produces a *plausible-but-incorrect* fix. This is the core capability gap on hard tasks.
2. **Thrash-without-editing (2/15 EMPTY) — the cost/speed killer.** On large codebases (`xarray`), the worker investigates to the **iteration cap (102–120 iters)** and **never makes an edit** — burning **$2.14–2.29 and 8–15 min to produce a 0-byte patch.** Pure waste.
3. **Diff pollution / over-scope (3/15 PATCH_FAIL).** On `checkov` the worker **ran the tool**, and the tool's **generated output files (scan-result CSVs)** got captured into the patch → a 9-file, non-applying diff. This is a **harness `captureDiff` bug** *and* an over-scoping behavior.
4. **OpenAI under-investigation (0/15).** Bails at ~12 iters — never reaches a fix on hard tasks.

---

## 4. Fixes — *focused on the general/product runs we support*

These are not benchmark-only; each maps to a real product surface (the 4 autofix surfaces + existing-repo BUILD runs).

### Fix 1 — Thrash guard: "no edit after N iters → force decision" (HIGH value, easy)
**Problem:** the worker can burn 100–120 iters investigating without ever editing (xarray: $2.14, 893s, 0 bytes). **Product impact:** on a customer's large existing repo, an autofix or existing-repo run can run for 15 min and $2–3 and deliver *nothing* — worst possible UX + cost.
**Fix:** extend the existing progress signal (`WorkerAgent.itersSinceProgress`) with an **investigate-without-edit ceiling**: if no `writeFile`/`editFile` after e.g. 25 iters, inject a hard "commit to an edit now or call `completeTask` with 'cannot fix'" nudge, and force-terminate sooner. Today the stagnation signal counts *no-progress*, but a worker that's *reading 100 files* looks "busy." **This is the single biggest speed + cost win** and directly protects product runs from runaway cost. Validate on `scripts/v2-eval.sh` (no-regression on greenfield, which doesn't thrash).

### Fix 2 — Diff hygiene: exclude generated/output files from the captured patch (HIGH value, easy)
**Problem:** the worker runs a tool (checkov, a build, a formatter) and its **output artifacts** (CSVs, build dirs, caches) get captured into the patch → polluted PRs that don't apply. **Product impact:** the autofix surfaces open **PRs**; committing scan-output CSVs / build artifacts is a real quality bug customers would see.
**Fix:** make `captureDiff` (benchmark) **and `gitCommit`/`ScmPushService` (product)** **gitignore-aware** + exclude a denylist of generated patterns (timestamped CSVs, `dist/`, `build/`, `*.egg-info`, coverage files). The benchmark `captureDiff` already excludes a few patterns; widen it and share the logic with the product commit path.

### Fix 3 — Root-cause / verification quality for the dominant wrong-fix mode (HIGH value, hard)
**Problem:** ~60% of attempts are confident-but-wrong fixes. **Product impact:** this is the ceiling on *every* surface.
**Fixes (layered):**
- **Run the existing tests before finishing** — already enforced for the autofix surfaces (the PATCH test-verification gate, ON for `finding:*`); the blind benchmark holds the test out, so it can't. So the *product* already has this lever; the gap is mainly for greenfield/BUILD, where **real-test-exec scoring (RL-2026-05-001)** is the analog. Keep both on.
- **Self-consistency on hard tasks** — for HIGH-complexity tasks, generate 2 candidate patches and keep the one whose existing-test run is cleaner. Costs 2×; gate behind complexity so it only fires where it pays.
- **Better localization** — the worker finds *a* site but often the wrong/incomplete one. (The blast-radius lever tried this and didn't move the needle on its own — so this needs investigation-loop work, not just a prompt line.)

### Fix 4 — Provider routing by cost/accuracy (MEDIUM value, easy)
**Finding:** accuracy is provider-flat; cost/speed is 4×. **Product impact:** default-provider and per-tier routing.
**Fix:** keep **Anthropic (Sonnet) as the default for PATCH/autofix and HIGH-complexity work** (best accuracy/$). **Do not route hard tasks to OpenAI** (its thin-iteration give-up → 0 resolves). Reserve OpenAI for trivial/localized fast fixes if at all. Gemini has no efficiency niche here — don't pay its premium for hard work. (This matches the current `application.yml` default = Anthropic; the data backs it.)

---

## 5. Execution time & speed (you flagged this — here's the data)

- **Scoring is ~1s; code-gen is ~99% of wall time.** Speed = the worker loop, nothing else.
- **The long tail dominates.** Slowest baseline runs: `xarray` 893s (102 iters, EMPTY), `starlette` 793s (83 iters, $2.78), `pylint` 581s. These iter-cap-approachers are simultaneously the **slowest, most expensive, and lowest-yield** runs. **Fix 1 (thrash guard) cuts this tail** — biggest single speed + cost lever.
- **OpenAI's "speed" is give-up, not efficiency.** 61s avg because it bails at 12 iters — fast and wrong. Not a model to chase for speed.
- **Concurrency already won us ~3–4×.** N=3 ran 45 Live jobs in ~80 min vs ~4–5 h sequential. **Recommendation: bounded concurrency (N=3–4) is safe** here (the TCC-freeze that looked like a concurrency bug was a workspace-location bug, now fixed). For the *product*, runs are already concurrent across customers.
- **Native infra would remove the Rosetta tax.** These eval containers are `amd64` under Rosetta on an arm64 Mac — a fixed per-run emulation tax. A native x86_64 box would cut per-run time materially (and is required for a credible large-N or Claude-Code head-to-head).

**Speed roadmap, ranked:** (1) thrash guard (kills the long tail), (2) cap-tuning per provider (OpenAI doesn't need 100 iters; Gemini's 120-iter thrash should cap earlier), (3) bounded concurrency for benchmarking, (4) native amd64 infra for scale.

---

## 6. Claude Code head-to-head — NOT completed (honest status)

The in-container Claude Code install is **impractical on this local arm64/Rosetta box** — `apt`/Node/npm under emulation is glacial (earlier attempts spent 16 min on `apt-get` alone and never installed). The pre-baked-image workaround was prepped but not run.

**Recommendation:** do the same-set head-to-head on **native amd64 infra** (where Claude Code installs in seconds), OR for now cite Anthropic's **published** Claude Code SWE-bench numbers — noting they're on **Verified** (curated, scores much higher) and **not directly comparable** to this **Live** number. A fair comparison requires running Claude Code on these *same* Live instances on real infra.

---

## 7. Cost & honesty caveats

- **Spend this matrix:** Live $12.05 (Anthropic) + $3.11 (OpenAI) + $14.25 (Gemini) ≈ **$29** + Lite partial ≈ $5. Well within budget.
- **N is small** (15 Live, 9 Lite) — directional, not statistical. Confidence intervals are wide at N=15.
- **Sample is hard** (random, big diverse repos) — that's the point, but it's harder than typical curated benchmarks.
- **Two harness bugs found & noted** (diff-pollution, strict git-apply → both now fixed in the driver/captureDiff plan); neither rescued a resolve on this sample.
- **Not done:** full Lite (21 jobs), Claude Code. Both can be completed — say the word.
