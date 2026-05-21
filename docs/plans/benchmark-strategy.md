# Benchmark strategy — testing the *system*, not the model

> Created 2026-05-20. Status: **proposed**. Owner: founder.
>
> Goal: produce **publishable** benchmark numbers that measure the ExecuteSpec
> *pipeline* (classify → plan → gate → workers → reviewer → score → PR), not the
> raw LLM. Two tracks: **PATCH** (bug-fix from a failing test — already a key
> feature, wired to the Project options) and **BUILD** (greenfield spec →
> multi-repo PR with build + tests).

## Why "publishable" is the sort key

A benchmark number is only worth running if we can stand behind it in public. There are two tiers of publishability:

- **Tier 1 — official maintained leaderboard.** Third-party-verified, citable, recognized by customers. SWE-bench (`swebench.com`) runs Verified / Lite / Full / Multilingual / Multimodal boards with an "Org" filter, so a commercial agent can list — but listing requires submitting predictions **plus trajectories/logs** for reproducibility.
- **Tier 2 — self-reported.** A blog post / README / landing-page claim backed by a reproducible script. Anyone can do it; lower friction, medium credibility.

Most of the marketing value lives in Tier-1 SWE-bench numbers. BUILD benchmarks are mostly Tier-2 today (research credibility + differentiation), because their leaderboards are newer and less recognized.

## PATCH track — recommendations (ranked by publishability)

| # | Benchmark | Size | Why publish here | Cost (1 provider, est.) | When |
| --- | --- | --- | --- | --- | --- |
| **1** | **SWE-bench Lite** | 300 | Recognized entry number, official board, **adapter already exists** (`swebench/`) | ~$450 | Start here |
| **2** | **SWE-bench Verified** | 500 | *The* number Anthropic/OpenAI cite — marquee marketing claim | ~$750 | After Lite looks healthy |
| 3 | **SWE-bench Pro** (Scale) | larger | Harder, less-contaminated, "enterprise" framing — credible secondary claim | TBD | Optional differentiator |

**Contamination caveat.** Lite/Verified instances are public and old, so models may have trained on them. That is acceptable for a *system* test (we measure the pipeline and report per-provider), but for publishing we must state the methodology plainly. SWE-bench Pro / Live are the contamination-resistant follow-ups if a reviewer pushes back.

## BUILD track — recommendations (ranked by publishability)

Mainstream recognition is far lower than SWE-bench, so the value is **research credibility + differentiation** ("we build greenfield from a spec, not just patch"), not a number customers already know.

| # | Benchmark | What it tests | Lift to adopt | Caveat |
| --- | --- | --- | --- | --- |
| **1** | **Commit0** | Implement a Python library from scratch given an API spec + hidden unit tests | Low–med — **reuses the ProgramBench cleanroom containers** | Python-only; verify the leaderboard is live before betting on it |
| **2** | **BaxBench** | Generate backend apps from scratch, scored on correctness **+ security** | Med | Enterprise/security angle; confirm the board exists before relying on it |
| 3 | **DevBench** | PRD → multi-file codebase across full SDLC (design, setup, impl, test) | High — multi-language, multi-stage scoring | Lowest publishing ROI; do last |

**Do not lead with** FeatureBench / ProjDevBench — too new, no established board, low citation value today. Revisit in ~6 months.

**Feasibility.** BUILD is more harness than PATCH. PATCH is easy because the benchmark hands us the failing test; BUILD gives a spec + a **held-out** acceptance suite. We already own the two hard pieces — the BUILD pipeline and the `--network none` cleanroom containers — so each BUILD benchmark is an **adapter + scorer**: (a) feed the spec as the run's `userRequest`, (b) after the run, execute the benchmark's held-out tests against the generated repo in a cleanroom, (c) score pass/fail.

## Cost model (grounded in our own data)

- Empirical anchor (CHANGELOG): **30 instance-runs ≈ $45 → ~$1.50/instance** blended.
- Patch hard-cap: **$8/instance** (`RunOrchestrator.DEFAULT_PATCH_HARD_CAP_USD`).
- PATCH forces the **PLANNING tier** (priciest model per provider) — no "cheap provider" trick.
- SWE-bench Lite = **300 instances** (`princeton-nlp/SWE-Bench_Lite`, loaded by `SwebenchInstanceLoader`).

| Scope | Instances | Est. cost (≈$1.50 avg) | Notes |
| --- | --- | --- | --- |
| Smoke (reuse existing) | 10 | ~$15 | validates harness only |
| **Stratified subset** | 50 | **~$75** | ±~7% CI on resolved% — *recommended first* |
| Lite "dev" split | 23 | ~$35 | smallest standard set |
| **Full Lite** | 300 | **~$450** (band $300–750) | one provider, one pass |
| Full Lite × 3 providers | 900 | ~$1,350 | only if comparing providers |
| Absolute worst case | 300 × $8 cap | $2,400 | every instance maxes the cap — won't happen |

Per-run exposure can be tightened with `userCostCap`. SWE-bench **Verified** is 500 instances (~$750) — bigger, not smaller — so it is a Phase-2 spend, not a cheaper alternative.

## Recommended rollout (cost-staged)

1. **Phase 0 — ~$75:** 50-instance SWE-bench Lite subset, one provider. Proves harness + cost model end-to-end. *(nearly free given the existing adapter)*
2. **Phase 1 — ~$450:** Full Lite, one provider → self-report + submit to the official board.
3. **Phase 2 — ~$750:** Verified, one provider → headline marketing number.
4. **Phase 3:** Commit0 BUILD adapter → first publishable greenfield number.
5. **Defer:** SWE-bench Pro, BaxBench, DevBench until 1–2 numbers are live.

## Publishing-integrity checklist

What makes any of these numbers defensible (without it, a reviewer discounts the claim):

- Fixed instance set + fixed seed.
- Open trajectories / logs for every instance.
- Report provider + model + date + total cost + resolved%.
- Prove no test leakage into the agent context (the failing test is injected for PATCH, but the *held-out* suite must never reach the worker).
- State the system-vs-model framing explicitly: we score the pipeline, per-provider.

## Sources

- SWE-bench official leaderboards — http://www.swebench.com/
- SWE-bench Verified (Epoch AI) — https://epoch.ai/benchmarks/swe-bench-verified
- SWE-bench Pro leaderboard (Morph) — https://www.morphllm.com/swe-bench-pro
- Commit0: Library Generation from Scratch — https://arxiv.org/html/2412.01769v1
- DevBench — https://arxiv.org/html/2403.08604v1
- FeatureBench — https://arxiv.org/abs/2602.10975
