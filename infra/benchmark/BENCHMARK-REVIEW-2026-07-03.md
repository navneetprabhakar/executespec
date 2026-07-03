# Benchmark Review — TB 2.1 Harness Diagnosis + SWE-bench Plan

Date: 2026-07-03
Scope: combines the TB 2.1 harness diagnosis with the SWE-bench test plan + cost model.
Method: 3 independent code audits (Python adapter/bridge, Java worker loop, remote-dispatch path) cross-checked against Harbor artifacts + `pipeline_run` / `run_step_usage` / `benchmark_task_result` on the benchmark VM, plus the full 5-run per-task matrix.

> Scope guard: harness review only. No model-parameter or provider-selection changes proposed. Model comparisons are used solely to separate harness loss from model loss.

---

## PART A — What is actually wrong (TB 2.1)

### A1. The headline finding: the measurement itself is broken

Pulling the per-task matrix for the 5 DONE runs:

| Run | Model | Score |
|---|---|---|
| cc39e727 | deepseek-v4-flash-native | 48/89 |
| 8113ed10 | deepseek-v4-flash-native | 43/89 |
| e8b23c89 | deepseek-v4-flash-native | 46/89 |
| d0e14512 | deepseek-v4-pro-native | 49/89 |
| c288a819 | claude-sonnet-4-6 | 42/89 |

**Three reruns of the identical DeepSeek Flash model scored 48 / 43 / 46, with 23 of 89 tasks (26%) flipping pass↔fail between runs — at temperature 0.** k=1 run-to-run noise is therefore **±3–5 tasks**.

Consequences:

- Every k=1 comparison made in the last months (model vs model, before-fix vs after-fix) had its signal below the noise floor. Six months of tuning got feedback that was substantially random. This — not any single bug — is the root cause of "nothing seems to move."
- The DeepSeek Pro "55→49 regression" is **inside the noise band**; the earlier overfitting conclusion drawn from it is withdrawn.
- Part of the gap to external "60–63%" numbers is noise + k>1 selection effects in how other harnesses report (best-of-k, mean-of-k, task-timeout differences).

The benchmark decomposes into:

- **25 always-pass** (every model, every rerun)
- **24 always-fail** (every model, every rerun) → the true harness/task ceiling
- **40 noisy middle** → where all model-vs-model differentiation currently happens, i.e. coin-flip territory at k=1

### A2. The always-fail ceiling (24 tasks) — where systematic loss lives

`caffe-cifar-10, circuit-fibsqrt, db-wal-recovery, dna-assembly, extract-moves-from-video, filter-js-from-html, gcode-to-text, gpt2-codegolf, install-windows-3.11, make-doom-for-mips, make-mips-interpreter, mteb-leaderboard, path-tracing, path-tracing-reverse, raman-fitting, regex-chess, rstan-to-pystan, sanitize-git-repo, schemelike-metacircular-eval, torch-pipeline-parallelism, train-fasttext, video-processing, winning-avg-corewars, write-compressor`

Triage:

- **Long-compile / heavy-compute (likely infeasible under 300s tool timeout + 1260s wall clock)**: caffe-cifar-10, make-doom-for-mips, make-mips-interpreter, train-fasttext, video-processing, mteb-leaderboard, path-tracing ×2, torch-pipeline-parallelism, gpt2-codegolf, rstan-to-pystan → fixed by R5 (timeouts) + sandbox budget.
- **Byte-exact transform (evidence-blind)**: filter-js-from-html, gcode-to-text, write-compressor, sanitize-git-repo → fixed by R3 (truncation).
- **Interactive / stateful**: install-windows-3.11 (needs stdin; adapter runs `stdin=DEVNULL` → structurally impossible) → persistent-terminal substrate.
- **Genuinely hard / perf-threshold (capability)**: circuit-fibsqrt, regex-chess, schemelike-metacircular-eval, raman-fitting, winning-avg-corewars, dna-assembly, db-wal-recovery, extract-moves-from-video.

~14–16 of the 24 map to harness fixes and would lift **every model simultaneously**. This — not the noisy middle — is where cross-model recovery lives.

### A3. Sonnet 4.6 failure taxonomy (run `c288a819`, 42/89, $136, 32.3M in / 1.36M out)

Integrity verified first: 89 unique task results, no duplicates, no restart/auth contamination. Live VM adapter byte-identical to the repo copy.

Of the 47 failures:

| Terminal state | Count | Meaning |
|---|---:|---|
| `COMPLETE\|COMPLETED` | **32** | Worker self-certified done; verifier disagreed (wrong/partial artifact) |
| `WALL_CLOCK_EXCEEDED` | 12 | Ran out of 1260s server budget |
| `ITERATION_LIMIT_EXCEEDED` | 2 | make-doom-for-mips, schemelike-metacircular-eval |
| Harbor `AgentTimeoutError` | 1 | extract-moves-from-video (1423s > 1350s) |

Quantitative signals:

- **Failed tasks burn 4.2× the input tokens of passed tasks** (median 205k vs 49k uncached; mean 560k vs 156k). 15/47 failures spiralled past 500k; schemelike hit 3.2M over 124 iterations. Thrash, not convergence.
- Sonnet drives almost everything through raw `shell`: **3,009 shell vs 160 readFile / 98 writeFile / 24 editFile** across 89 tasks. Many failures wrote all files via shell heredocs — sidestepping the deliverable-extraction the completion gate and pass-guard snapshot rely on.
- Only **23/47** failures ever ran the visible tests.
- **Cache is NOT broken**: 59.4% cache-read (47.3M cached vs 32.3M uncached). The $136 is raw context **volume** (~905k input tokens processed/task), not cache-miss.

### A4. Root causes, ranked

Confidence: **[data]** = confirmed from artifacts/DB; **[code]** = confirmed code path, fires server-side (not visible in Harbor artifacts).

**R1 — Verified-pass guard freezes the run on the first green self-authored test. [code, high] Est. 5–10 tasks.**
`WorkerAgent.terminalVerifierPass` arms on ANY successful shell command whose text contains `pytest`, `unittest`, `npm test`, `go test`, `cargo test`, ` verify`, ` benchmark`, … (substring match, `WorkerAgent.java:2595-2620`). With `/tests` not mounted (the common TB 2.1 case) the stricter `/tests`-targeting check is skipped, so a scratch smoke on one component arms the guard. Once armed: every later `shell`/`writeFile`/`editFile` is **refused** and a text-only turn is **force-completed** (`:1090-1104`, `:713-739`). Sonnet's incremental probe-test-continue style trips this; DeepSeek (tuning model) rarely self-tests mid-flight, so the guard measured as pure upside. Leading explanation for the 32 completed-but-wrong. Guard is unconditional in terminal mode — needs a code flag before it can even be A/B'd.

**R2 — History compaction fires at 30% of the window and was ON. [data: ON; code: mechanism] Est. 3–8 tasks + most of the cost.**
`run-tb.sh` flips `worker.history-compaction.enabled=true` for benchmark runs; `run-tb-internal-once-matrix.sh` defaults `COMPACTION=on`. (The CLAUDE.md claim "compactor default OFF" is false for the benchmark.) Trigger is **60k input tokens** — 30% of Sonnet's 200k window, calibrated in the DeepSeek-128k era — and replaces all but first-2/last-8 messages with a **≤1,200-token Haiku summary** (`WorkerHistoryCompactor.java:72-85`). Each firing discards 40–55k tokens of concrete evidence (exact expected output read from `/tests`, prior errors), forcing re-derivation → the token-volume blowup.

**R3 — Adapter observation caps destroy exact evidence with no recovery path. [data] Est. 4–7 tasks.**
`es_harbor_agent.py`: `readFile` = `cat` then **first 8,000 chars only**, and **`startLine`/`endLine` are silently ignored** although the server tool schema advertises range paging — there is no way to ever see past char 8000 of any container file. `shell` = 6,000 chars head+tail, **middle destroyed**; the server's spill-to-disk affordance does not exist remotely. `gitDiff` 5,000 head-only; `searchFiles` head -100. Blinds byte-exact transforms and buries long compile/test errors. The critic/visible-test machinery reads `/tests` through the same capped path.

**R4 — Iteration-keyed nudges + forced finalizer are DeepSeek-calibrated. [code] Est. 2–5 tasks.**
iter≥16 "stop exploring" and iter≥32 artifact-deadline injections are **unconditional** (keyed on iteration count, not stagnation — `WorkerAgent.java:1227-1252`). iter≥64 runs a **forced finalizer** (`tool_choice=required`) that returns COMPLETED on **any single productive call**, without the visible-tests gate (`:1253-1274`, `:1695-1751`) — a healthy run force-completed at 53% of its iteration budget. For a model whose legitimate first 40 iterations are env-setup + build, these steer into premature candidates, which then trip R1.

**R5 — Timeout regime kills long builds with zero partial output. [code] Est. 2–4 tasks.**
Adapter `ES_TOOL_TIMEOUT=300s` (not overridden by run-tb.sh); on timeout the model gets `INTERNAL` with **no partial output** and the in-container process keeps running (only the docker-exec client is killed) → model re-runs the same command, two compiles contend. Server `RemoteToolDispatcher` waits 600s — half the server budget structurally unreachable. One in-flight dispatch can carry the run past 1260s toward Harbor's 1350s kill. Timeout nesting today: 300 / 600 / 1260 / 1350.

**R6 — Tool-schema ↔ adapter mismatch wastes turns. [data: modest] Est. 1–2 tasks + ~6–7k schema tokens/call.**
`bindForWorker` advertises ~45 tools; the adapter implements 11. Measured: **40 unknown-tool calls across 8 tasks** (`executeCommand`, `terminalCommand`, `diagnostics`, `web_search`, `glob`, `multiEdit`, …). Latent contract bugs: `runTests` schema requires `test_ids` but adapter expects `command` (every schema-compliant call fails); `buildProject.command` optional in schema, required by adapter.

**R7 — writeFile/editFile ~96KB ceiling + non-UTF-8 corruption. [code] Est. 1–3 tasks.**
`_write` passes the whole base64 payload as one shell argv (Linux `MAX_ARG_STRLEN` 128KiB → ~96KB content ceiling); `editFile` round-trips the entire file `cat`→str→rewrite, so large files fail and any non-UTF-8 byte is written back corrupted (`errors="replace"`). `large-scale-text-editing` is named after this failure mode.

**Secondary (cross-model reliability):** single SSE connection with zero reconnect in the adapter — a mid-run drop silently abandons the run (tool calls emitted into the void until wall-clock death); replay-log sequence ids break after 500 events. Contributes to the 23-task flip noise.

### A5. Ruled out (don't chase these)

- **429 rate-limiting** — zero occurrences in the Sonnet agent logs.
- **Context-overflow → instant ERROR** — real code path (`isTransientError` doesn't match the 400), but did not fire this run (masked by compaction). Becomes relevant only if compaction is turned off — land the overflow handler together with any compaction change.
- **Split-filesystem** — all 12 tools proxy to the container in remote mode; no server/container divergence.
- **"Spill file you can't read"** — remote envelope returns before server-side budgeting; non-issue.
- **"Cache is broken drove the $136"** — false; 59.4% cache-read. Volume, not miss-rate.
- **Overfitting caused Pro 55→49** — withdrawn; inside the noise band.

### A6. Structural conclusion

The TB path reuses the production codegen WorkerAgent as remote brain over a **stateless** adapter: fresh `docker exec` per call (no cwd/env/venv persistence), `stdin=DEVNULL` (no interactive programs), truncated evidence, no reconnect. Reference TB harnesses (Terminus-class) are built around a **persistent tmux session** with real I/O. On top of that substrate mismatch sits a layer of DeepSeek-tuned control logic (R1/R2/R4) that misfires for other models. The deficit is: wrong substrate + overfit control loop + a measurement too noisy to steer by.

---

## PART B — Action items (TB), in order

1. **Install the instrument: k=3 minimum, report mean ± spread.** Flash is ~$1.4/run → k=5 ≈ $7. Until every number has error bars, a real +3 improvement is indistinguishable from noise. Highest-priority, near-zero cost.

2. **Attack the 24 always-fail ceiling, not the noisy middle.** Tasks that flip between reruns are randomness — do not tune against them. Start with the two harness-fixable clusters: byte-exact transforms (R3) and long-compile tasks (R5). These lift every model at once.

3. **Kill non-determinism at the source: persistent-terminal substrate.** tmux session in adapter `setup()`; `shell` = `tmux send-keys` + `capture-pane`; keep `env.exec` for file ops. Plus: honor `startLine`/`endLine`, head+tail readFile with paging hint, spill full shell output to a container-side file (R3, ~30 lines); `ES_TOOL_TIMEOUT=540` + partial-output-on-timeout + distinct TIMEOUT error kind (R5); chunked/in-container writes (R7); adapter alias-map or TB-restricted bind (R6); SSE reconnect + trim-safe event ids.

4. **Machinery ablation — measured at k=3.** COMPACTION=off, verified-pass guard off (add the code flag first), iter-16/32/64 nudges off vs current, one model. With error bars this finally answers whether the accumulated control logic is net positive. (Server-side changes: R1 tighten/flag, R2 window-aware trigger `0.75×window` + token-budgeted tail + 4–8k summary, R4 stagnation-keyed nudges + finalizer requires `completeTask` + visible-tests gate in finalizer.)

5. **Time-box TB, then decide the flagship benchmark with data.** A large share of the ceiling is long-compile/GPU/interactive tasks that may be infeasible in the sandbox regardless of model. ExecuteSpec's product is spec→PR codegen; SWE-bench measures exactly that (Part C). One focused TB cycle (items 1–3), re-measure, then choose where the next benchmark dollar goes. Don't let sunk cost pick the benchmark.

Expected recovery: R1–R5 midpoints overlap but plausibly close most of the 11–14 task gap to the 53–56/89 competitive band, and cut per-run cost roughly in half (volume drops with R2+R3).

---

## PART C — SWE-bench: how to test, and what it costs

### C1. What already exists (branch `feature/swebench-harness`, present in this worktree)

- **Blind, publishable path**: `BenchmarkGenerationService` behind `POST /api/admin/benchmark/generate` (alias `/run`), SUPER_ADMIN, gated by `swebench.enabled` (default OFF). Runs the **same CodeGenEngine seam as PATCH-mode product flows**; only the sandbox is bench-specific. The agent sees only the issue `problem_statement` — never `FAIL_TO_PASS`/gold `test_patch` (oracle path deleted, RL-2026-05-062).
- **Instances**: `SwebenchInstanceLoader` reads a local JSON dump (`swebench.instancesFile`, default `~/swebench-bench/swebench_lite_dev.json` = **23-instance Lite dev split** — the smallest standard slice). Full Lite-300 / Verified-500 dumps come from the same one-liner in `SwebenchProperties` javadoc.
- **Sandbox**: official per-instance Docker images (`swebench/sweb.eval.x86_64.*`, ~1–3 GB, **amd64-only**), repo pre-checked-out at `/testbed`; cleanroom `--network none`; PATCH-mode test-verification gate is auto-disabled for benchmark source types.
- **Scoring**: external, via the official `swebench` harness applying the hidden tests to the captured `preds.json`/`submission.patch` — this is what makes the number leaderboard-comparable. Ad-hoc driver + scorer tooling already exists in `~/swebench-bench/` (`core-codegen-*.manifest.jsonl`, h2h scorers) — worth formalizing into `infra/benchmark/`.
- **Prior result** (exec brief): **59% (Anthropic) / 52% (OpenAI) resolved on a 29-instance stratified Lite sample, blind, official scorer**; measured cost **$0.71/issue (Anthropic), $0.35/issue (OpenAI)**; ~2–5 min/instance.

### C2. Runbook

1. Run on **x86_64** (benchmark VM) — the local arm64/Rosetta path stalls (documented). Pre-pull images.
2. Config: `swebench.enabled=true`, `swebench.bootstrap=true` (first boot), `instancesFile` → dataset dump, `agent.workspace` **outside `~/Documents`** (macOS TCC freeze) — moot on the VM.
3. Per instance: `POST /api/admin/benchmark/generate {instanceId, aiProvider, modelName}` — synchronous, generous client timeout; response carries patch artifact paths + cost.
4. Score with the official `swebench` harness against the produced `preds.json`.
5. Track in the in-app `/benchmark` dashboard by pointing a bridge (same event shapes as `tb-bench-bridge.py`) at `POST /api/v2/test/benchmark` — the tracking layer is suite-agnostic by construction; no schema change needed.
6. **k=3 from day one** — same lesson as Part A. Report mean ± spread.

### C3. Cost (LLM spend, blind; grounded in the measured $0.71 / $0.35 per instance; DeepSeek extrapolated at ~$0.03 from TB economics)

| Slice | N | Anthropic | OpenAI | DeepSeek |
|---|---:|---:|---:|---:|
| Smoke / harness validation | 5 | **$3.55** | $1.75 | $0.15 |
| Lite **dev** (harness default) | 23 | **$16** | $8 | $0.70 |
| Prior stratified sample | 29 | $21 | $10 | $0.87 |
| Lite **full** (the citable "SWE-bench Lite" number) | 300 | **$213** | $105 | $9 |
| **Verified** | 500 | $355 | $175 | $15 |

At **k=3** (the trustworthy version): Lite-300 ≈ **$640 Anthropic / $27 DeepSeek**; Verified-500 ≈ **$1,065 Anthropic / $45 DeepSeek**. Infra adder per full run (x86 VM hours + 300 image pulls): ~$10–30.

### C4. Recommendation

- **Prove the harness end-to-end: ~$4** (5-instance Anthropic smoke; cents on DeepSeek).
- **Iterate exclusively on DeepSeek** — full k=3 Lite-300 ≈ $27, so harness/methodology iteration is essentially free (the inverse of TB, where $136 Sonnet runs made iteration painful).
- **Spend once for the headline**: a single k=3 Sonnet Lite-300 (~$640) or Verified-500 (~$1,065) after the harness is stable.
- **Marketing honesty (the brief already flags this)**: a 29-subset rate is not comparable to Lite-300 SOTA (~48%). The defensible number is **full Lite-300 or Verified-500, official scorer, blind, k≥3, mean ± spread** — nothing smaller.
- Strategic: SWE-bench *is* the product (issue → verified patch → PR). TB measures an adjacent skill on a borrowed agent. After the time-boxed TB cycle (Part B item 5), the default allocation of benchmark budget should favor SWE-bench unless the TB re-measure surprises.
