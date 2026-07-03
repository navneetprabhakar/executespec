# SWE-bench Pro — Full Public-Set Run + Leaderboard Submission Plan (DeepSeek)

Date: 2026-07-03
Target: **731-instance public set**, blind, pass@1, DeepSeek V4 Pro native, official Scale scoring, leaderboard submission.
Companion doc: [`BENCHMARK-REVIEW-2026-07-03.md`](BENCHMARK-REVIEW-2026-07-03.md) (TB diagnosis + SWE cost model).

---

## 1. Submission mechanics (verified 2026-07-03 from Scale's docs/repo)

| Item | Fact |
|---|---|
| Public set | 731 instances, 11 repos (go 280 / python 266 / js 165 / ts 20), from `ScaleAI/SWE-bench_Pro` on HF |
| Leaderboards | Public: `scale.com/leaderboard/swe_bench_pro_public` · Commercial/private: `labs.scale.com/leaderboard/swe_bench_pro_private` (Scale runs that one on held-out repos — not submittable by us directly) |
| How to submit | **Google Form** linked from the docs site (`scaleapi.github.io/SWE-bench_Pro-os` → "Submit"); Scale validates/announces officially |
| Artifacts | Predictions JSON (`[{instance_id, patch, prefix}]`) + **trajectories** (leaderboard entries link trajectory files in the GitHub repo) |
| Scoring rules | **pass@1**, 95% CI (binomial over the public problems), **250-turn cap**, cost uncapped |
| Local eval harness | `swe_bench_pro_eval.py --raw_sample_path=swe_bench_pro_full.csv --patch_path=<patches>.json --scripts_dir=run_scripts --dockerhub_username=jefzda` (repo `scaleapi/SWE-bench_Pro-os`) |
| Images | `jefzda/sweap-images:<dockerhub_tag>` — measured on our VM: **1.5–10 GB each, ~4 GB avg** |

Compliance check vs ExecuteSpec today:
- **250-turn cap**: our DeepSeek PATCH-mode iteration cap is 220 → compliant with margin. ✅
- **pass@1**: submit exactly one prediction set from one clean pass — **no rerunning failures into the submitted set** (that would be best-of-k disguised as pass@1). Internal k>1 is for our own error bars only, never mixed into the submission. ⚠️ policy
- **Blind**: `BenchmarkGenerationService` path injects only `problem_statement`; the old gold-`test_patch` injection is neutralized (RL-2026-05-062). Must be re-verified on the Pro path during smoke (Gate A checklist). ⚠️ verify
- **Patch format**: our `swebench_pro_patches.json` emitter already writes the list shape with `prediction-prefix: executespec`. Confirm field names match `{instance_id, patch, prefix}` exactly during Gate A. ⚠️ verify

## 2. Infra reality (measured)

- VM: x86_64, 16 cores, 62 GB RAM, ~900 GB free disk.
- **731 images × ~4 GB ≈ 2.9 TB — cannot hold the full set.** The run must be **batched: pull batch → generate → score → `docker rmi` batch → next**. Batch of 40 ≈ 160 GB — comfortable. Scoring inside the batch window avoids pulling every image twice.
- Concurrency: `SWEBENCH_MAX_CONCURRENT_RUNS` — 2 for the sample, raise to 4 for the full run (62 GB RAM is fine; the 2026-05-31 OOM was 4 flagship runs + orphans on a smaller box). Watch JVM heap on the first batch.
- Native DeepSeek API tolerates 4-way concurrency without the OpenRouter latency tax.

## 3. Cost & duration model (to be calibrated by Gate A)

Priors: SWE-bench **Lite** measured ≈ $0.03/instance on DeepSeek; Pro is long-horizon (multi-file, big repos) → expect 3–10×.

| Item | Estimate | Basis |
|---|---|---|
| DeepSeek Pro native, per instance | $0.10–0.40 | Lite ×3–10; **replace with sample-20 actuals ×36.55** |
| Full 731 generation | **$75–300** | above |
| Generation wall time | 731 × 10–20 min ÷ 4 concurrent ≈ **31–61 h** (~2–3 days) | TB latency profile of native Pro |
| Local scoring | GPU-free, container-bound; `num_workers` 8–12 (not the default 100) ≈ **6–12 h** batched | 16 cores |
| Disk churn | ~2.9 TB pulled+pruned over the run | measured 4 GB avg |
| Internal k=3 on 100-instance subset (optional, error bars) | ~$30–120 | for our own claims, not the submission |

Everything above is decision-gated on the sample-20 actuals — if per-instance cost or duration comes in >3× the prior, stop and re-plan.

## 4. Phased plan with gates

### Gate A — sample-20 validation (already staged; running next)
The 20-instance stratified sample (seed 42; 7 go / 7 py / 5 js / 1 ts) across all 5 matrix models. DeepSeek lane doubles as the **first-ever real Pro generation run**. Checklist before anything scales:
1. Patch capture works against `/app` (not `/testbed`) on all 4 languages.
2. `swebench_pro_patches.json` matches `{instance_id, patch, prefix}` exactly.
3. **Blind verified**: grep the worker prompt/event log for any `test_patch`/FAIL_TO_PASS leakage on the Pro path.
4. Shell-exec shape works in sweap images (repo warns "bash runs by default … you should not manually invoke bash" — confirm our `docker exec` pattern doesn't double-invoke).
5. Score the 20 with Scale's harness locally → resolved% + per-instance cost/duration actuals.
6. Non-empty-patch rate ≥ 90% (empty patches = harness bug, not model failure).

### Gate B — pilot 100 (DeepSeek only, ~1 day, ~$10–40)
Random 100 of the 731 (seeded, stratified). Purpose: batching machinery (pull→generate→score→prune loop), concurrency 4 stability, JVM heap, disk churn, resumability after a crash. Produces a defensible internal resolved% ±CI preview.

### Gate C — full 731 (DeepSeek, the submission run)
- One clean pass, temperature 0, concurrency 4, batched by 40.
- Driver = extension of `run-swe-pro-sample20.py`: batch loop + resumable manifest + image prune. Formalize into `infra/benchmark/scripts/`.
- **Freeze the config first** (commit SHA, model id, prompts, caps) — the submitted run must be reproducible and describable on the form.
- Mid-run failures (infra, not model): re-run **only** instances with infra-classified errors (connect errors, image pull fails) — never quality-failures. Log every re-run with reason in the manifest (auditable pass@1).

### Gate D — local official scoring
- Clone `scaleapi/SWE-bench_Pro-os`, get `swe_bench_pro_full.csv` + `run_scripts`.
- Score all 731 patches batched with the images already on disk per batch window.
- Output: resolved%, per-language split, 95% CI. Compare against leaderboard neighborhood for sanity before submitting.

### Gate E — submission (founder approval required)
1. **Artifacts**: predictions JSON + per-instance trajectories. We need a **trajectory exporter** — our runs persist event logs (Redis 24h!) and `run_step_usage`; the exporter must dump per-instance agent transcripts to files **during** the run, not after (Redis TTL). Build in Gate B.
2. **Form**: Google Form from the docs site — agent name (**"ExecuteSpec (DeepSeek V4 Pro)"**), model, scaffold description, our locally-scored number; Scale validates and announces.
3. **Disclosure notes**: blind, pass@1, single pass, 220-iteration cap (< their 250), temperature 0, cost actuals.
4. Publish trajectories (their leaderboard links these publicly — confirm on the form whether via PR to their repo's `traj/` or hosted link).

## 5. Risks

| Risk | Mitigation |
|---|---|
| First real Pro run finds a harness gap (workdir, patch capture, exec shape) | That's Gate A's job at ~$1; fix before scaling |
| Redis event-log TTL (24h) loses trajectories for submission | Exporter writes to disk per-instance at run time (Gate B deliverable) |
| 60-min task wall cap truncates long-horizon Pro tasks | Watch Gate A durations; raise `SWEBENCH_TASK_WALL_TIMEOUT_MINUTES` if >10% of tasks approach it |
| Disk fills mid-batch | Prune step in the batch loop + `df` guard in driver (abort < 100 GB free) |
| DeepSeek API instability over a 2–3 day run | Resumable manifest; router's bounded same-provider retry already handles transients |
| Score lands far below leaderboard neighborhood | It's an internal data point first — we decide at Gate E; nothing is public until the form is sent |
| TB lessons (noise) | Pro is pass@1-by-rule; our internal error bars come from the optional k=3 subset, kept separate |

## 6. Timeline (from Gate A start)

| Day | Milestone |
|---|---|
| 0 | Gate A: sample-20 DeepSeek smoke + full sample matrix; score; calibrate |
| 1–2 | Gate B: pilot 100 + batch machinery + trajectory exporter |
| 3–5 | Gate C: full 731 generation |
| 5–6 | Gate D: local official scoring + sanity |
| 6+ | Gate E: founder review → Google Form submission → trajectory publish |

## 7. Explicitly out of scope (this pass)

- Other models on the full 731 (decide after DeepSeek lands; Sonnet full-731 ≈ $500–2,000 by the same ×36.55 math — separate decision).
- The commercial/private leaderboard (Scale-run, not submittable).
- Any TB harness fixes (tracked in the companion doc, Part B) — frozen during the submission run so the submitted config is stable.
