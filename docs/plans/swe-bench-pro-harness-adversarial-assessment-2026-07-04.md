# SWE-bench Pro Harness — Adversarial Assessment (2026-07-04)

**Question:** why is ExecuteSpec underperforming other harnesses on SWE-bench Pro?

**The gap to explain:** DeepSeek V4 Pro scores ~52% on SWE-bench Pro in public trackers with other scaffolds ([BenchLM](https://benchlm.ai/benchmarks/swePro), [morphllm](https://www.morphllm.com/swe-bench-pro)); ExecuteSpec got 6/20 (30%) on the stratified sample. Part of the gap is small-N noise (95% CI ≈ 15–50%), but the code audit found systematic losses that all stack against us. Findings ranked by estimated impact.

---

## 1. We withhold the `requirements` field that every leaderboard entry gets — we run a harder benchmark than everyone else

**This is the biggest finding, and it contradicts the handoff doc's conclusion that the failures are "inherent blind-mode difficulty, not harness-fixable."**

The [SWE-bench Pro paper](https://arxiv.org/abs/2509.16941) is explicit: *"models are evaluated in the setting without any ambiguity — the problem statement, **requirements** and **interface** specification are included in the agent prompt."* The `requirements` field is annotator-written expected behavior **grounded in the hidden unit tests**; `interface` contains exact class/function signatures. Both are official task inputs on the [dataset](https://huggingface.co/datasets/ScaleAI/SWE-bench_Pro).

Our harness: `SwebenchInstanceLoader.java:156` reads `interface` but **never reads `requirements` at all**, and `BenchmarkGenerationService.synthesizeBlindSpec()` (lines 437–495) injects only `problem_statement` + optional interface.

- The RCA's majority failure mode — "patch applied + compiled, hidden tests failed **on logic**" — is exactly what `requirements` disambiguates. We ask the model to guess behavior contracts that the hidden tests pin and that every competitor reads off the prompt.
- The `teleport-78b0d8c` failure ("hidden test references `undefined: newFnCache`; a blind model cannot know its exact name") is what `interface` exists for — check whether that instance's interface field was null in our dump or dropped in loading.

Fixing this is **leaderboard-legal, cheap, and plausibly worth double digits**. Every number produced so far understates the harness relative to the leaderboard protocol, and the prompt-iteration A/B currently running is optimizing the wrong variable.

## 2. The stagnation engine force-kills exploration at ~12 iterations — on a benchmark that allows 250 turns

Pre-first-edit, only `writeFile`/`editFile`-class calls count as progress (`WorkerAgent.java:245-255`); `shell`, `readFile`, `searchFiles` never reset the counter. A PATCH-mode nudge fires at **6** non-write iterations (`PATCH_NO_EDIT_WARN_AFTER_N`, `WorkerAgent.java:172`), force-termination at **12** (`STAGNATION_FORCE_AFTER_N`, `:164`). The terminal-bench carve-out where shell counts as progress does **not** apply to SWE runs (`:2733-2735`).

SWE-bench Pro repos are enormous (280 Go instances incl. teleport); competent localization legitimately takes 20–40 read/search/build turns. Scale allows **250 turns**; our effective pre-edit exploration budget is **12**, with pressure to edit starting at 6. The harness structurally forces either:

- (a) a premature shallow edit — which then fails hidden tests "on logic," feeding the very failure mode the RCA labeled *model capability*, or
- (b) `COMPLETED_BY_STAGNATION` → one-shot Lever B guess → junk or empty patch.

Add the two-strike text-only-stop rule (`WorkerAgent.java:705-768`) — brutal for DeepSeek's plan-out-loud style — and you have a clean explanation for **Flash's 9/20 no-usable-patch** rate. This control loop was tuned for small Lite/customer-patch repos and never re-derived for long-horizon Pro. Same class of overfit-control-logic problem the TB review (R1/R4) convicted, replayed on SWE.

**Cheap test:** query the run rows for the 20 sample instances and count `COMPLETED_BY_STAGNATION` / `STOPPED_WITHOUT_COMPLETE` / salvage-produced outcomes. If they cluster in the fail set, confirmed at zero cost.

## 3. Verification is a prompt-side plea; competitors enforce it mechanically — and our test tool doesn't speak the right languages

- `runTests` is **pytest-only** (`RunTestsTool.java:314-340`). The sample is 13/20 Go/JS/TS. The tool schema advertises "use this instead of shell for running tests," actively misleading the model into pytest calls on Go repos; the correct path (raw `shell` + `go test`) must be self-discovered.
- Every enforcement gate is OFF for benchmark runs: test-verification gate disabled for `sourceType=benchmark` (`CodeGenEngineImpl.java:118-128`), critic pass OFF (V231), verify-before-complete OFF (V235). The patch ships with **zero harness-side checks** — no compile check, no empty-diff rejection. The new `BENCH_VERIFY_CHECKLIST` just asks nicely; TB data showed only 23/47 Sonnet failures ever ran visible tests. Top harnesses make verification part of the scaffold, not the prompt.
- `shell` max timeout is **300s** (`ShellTool.java:34-35`). `go build ./...` or a JS suite on a Pro-scale repo can exceed it; the prompt's "run the repo's EXISTING tests" step becomes physically impossible on the biggest repos.
- The prompt says "Do NOT modify or add test files" — reads as forbidding reproduction scripts, cutting off the reproduce-first workflow that drove the RL-2026-05-061 GPT gains on Lite. (The diff already excludes `repro*`; the prompt should invite repro scripts, not deter them.)

## 4. The diff-exclusion list is Python-Lite-shaped and can silently delete correct hunks on Pro repos

`captureDiff` (`BenchmarkGenerationService.java:323-355`) globally excludes `**/tests/**`, `**/testing/**`, `**/conftest.py`, `**/pyproject.toml`, `**/setup.py`, `**/setup.cfg`, `**/requirements*.txt`, `**/tox.ini`. On Go repos, production helper packages under `internal/testing/` or `lib/…/testing/` are common and gold patches touch them; on JS/TS, build-config edits can be part of a fix. Any such hunk is silently stripped from the submission and scores as a model failure.

**Cheap test:** grep the 20 gold patches for paths matching the exclusion globs.

## 5. No candidate selection, no error recovery — one fragile rollout per instance

- One rollout, no best-of-n, no reranking (`BenchmarkGenerationService.java:162-172`). Pass@1 forbids resubmitting failures, but it does **not** forbid generating multiple candidates and selecting one via your own (non-hidden) signals — several leaderboard scaffolds do exactly this. We have no selection lever at all.
- Provider context-length error → `ERROR`, no retry, no truncation fallback (`WorkerAgent.java:628-637`). `WALL_CLOCK_EXCEEDED` gets no salvage call. Both compactors default OFF. Each turns a recoverable state into a zero.

## 6. The measurement can't steer the iteration (process failure, not points)

TB's own review established k=1 flip noise of ±3–5 tasks and "six months of tuning got feedback that was substantially random." The SWE loop is repeating it: N=20, k=1, and a prompt A/B (6/20 old vs iter-2 new) whose plausible effect size is smaller than the noise band. At DeepSeek prices (~$1/pass), k=3 on N=50–100 is a ~$10–20 instrument.

---

## What's NOT wrong (don't chase these)

- Blind discipline vs. hidden tests is correctly implemented (oracle path genuinely neutralized, `SwebenchTaskService.java:588-606`).
- Patch attribution is fixed (exact-path capture).
- The 220-iteration cap is compliant with Scale's 250.
- DeepSeek's 1M-token window makes context overflow unlikely.
- `--network none` matches the eval contract; images pre-cache deps.

## Bottom line

The ~20-point gap to what DeepSeek V4 Pro achieves elsewhere decomposes into:

1. **A harder task than the leaderboard runs** (no `requirements`, spotty `interface`) — biggest, self-inflicted, fully legal to fix.
2. **An exploration budget ~5% of the allowed turn count**, enforced by a stagnation engine tuned for small repos.
3. **A verification vacuum** (pytest-only tooling on a 65%-non-Python benchmark, all gates off).
4. Smaller leaks: exclusion-glob patch loss, no recovery paths, no candidate selection.
5. All measured through an instrument too noisy to show whether any fix works.

**Order of attack:**

1. Inject `requirements` + `interface` per the official protocol.
2. Count stagnation/text-stop terminations in the existing sample rows; relax the pre-edit force threshold for benchmark sources.
3. Audit sample-20 gold patches against the exclusion globs.
4. Move to k=3 / N≥50 before trusting any further A/B.

## Sources

- [SWE-bench Pro paper (arXiv 2509.16941)](https://arxiv.org/abs/2509.16941)
- [ScaleAI/SWE-bench_Pro dataset](https://huggingface.co/datasets/ScaleAI/SWE-bench_Pro)
- [Scale public leaderboard](https://labs.scale.com/leaderboard/swe_bench_pro_public)
- [BenchLM SWE-bench Pro](https://benchlm.ai/benchmarks/swePro)
- [morphllm SWE-bench Pro](https://www.morphllm.com/swe-bench-pro)
- [scaleapi/SWE-bench_Pro-os](https://github.com/scaleapi/SWE-bench_Pro-os)
- Internal: `infra/benchmark/SWE-BENCH-PRO-HANDOFF-2026-07-04.md`, `infra/benchmark/BENCHMARK-REVIEW-2026-07-03.md`, `infra/benchmark/SWE-BENCH-PRO-PLAN-2026-07-03.md`
