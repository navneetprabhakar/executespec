# SWE-bench Harness — Clean-Slate Design & Implementation Plan

> **Status:** DRAFT for review. Branch `feature/swebench-harness`. Not yet approved, not committed.
> **Author:** drafted 2026-05-24. Supersedes the PR #88 SWE-bench adapter (see §3).
> **Decision owner:** @navneet — review section-by-section, then approve to start Slice 0.

---

## 1. Goal & non-goals

**Goal.** Measure and publicly showcase ExecuteSpec's **real production code-gen pipeline** on public SWE-bench tasks, and use the same runs to drive scaffold improvement.

- **SWE-bench Live** → the **publish** surface (contamination-free, monthly-fresh → honest by construction, recurring PR cadence).
- **SWE-bench Lite** → the **local iteration** surface (tune the scaffold here; Live's separate, fresh instances mean tuning on Lite cannot inflate the published Live number).

**Non-goals.**
- No benchmark-specific code-gen path, prompt, or model routing. (This is the hard constraint — see §2.)
- Not replacing PATCH mode or `patch-agent.md` — those still serve the 4 production autofix surfaces and stay untouched.
- Not the official SWE-bench **Verified** leaderboard — as of Nov 2025 it accepts only academic/research teams with open-source methods. Out of reach for a commercial closed-source product; we publish on **Live** + (optionally) **Lite** instead.

---

## 2. Principles (the contract this design must honour)

1. **Real system, sandbox-only.** The code-gen system runs *exactly* as in production. Only the **sandbox** (container, dataset loading, input adaptation, scoring, submission packaging) is benchmark-specific. _(Memory: `feedback_benchmark_real_system`.)_
2. **Blind.** The agent receives **only the issue text** as the spec — never the hidden `FAIL_TO_PASS`/`PASS_TO_PASS` tests, never the gold `test_patch`. Hidden tests are applied **at scoring time, outside the agent's view**. This is what makes the number leaderboard-comparable.
3. **Honest methodology.** The only difference from a paying customer's run is that the plan gate is auto-approved (you can't have a human click "approve" 300 times). We disclose this in the submission README.
4. **Production parity.** Same `RunOrchestrator.submitRun` entry, same tier-routing, same planner/reviewer/worker/tools as a normal `BUILD` run.

---

## 3. Why clean-slate (and what was wrong with the old adapter)

The existing `swebench/` package (PR #88, unmerged) **violates Principle 2** and produces a non-publishable number:

- It runs **PATCH mode** with the benchmark-shaped `patch-agent.md` prompt — not the core pipeline.
- [`SwebenchTaskService.captureFailingTestSource()`](../../src/main/java/com/navneet/executespec/swebench/SwebenchTaskService.java) reads the gold `test_patch` and injects it into the prompt ("## Gold test patch (the assertions your fix must satisfy)"), and [`patch-agent.md`](../../src/main/resources/prompts/patch-agent.md) instructs "READ THE GOLD TESTS FIRST." → **oracle / test-aware setting**, not comparable to any public leaderboard. The earlier 67%/60% numbers were measured this way and must be discarded.

What we **keep** from the old code (it's sound, sandbox-layer, not the problem):

- Cleanroom container lifecycle: `docker pull` → `docker create`/`cp /testbed` → `docker run -d --rm --network none -v <host>:/testbed` → register in a container store so `ShellTool`/`BuildProjectTool` dispatch via `docker exec`.
- Final-state capture: `git diff HEAD` inside the container → unified patch.

What we **delete**: `SwebenchTaskService`, `SwebenchInstanceLoader`, `SwebenchProperties`, `SwebenchController`, and the gold-test injection. (`programbench/` is unaffected; PATCH mode + `patch-agent.md` stay for autofix.)

---

## 4. Architecture

```
                 POST /api/admin/benchmark/run {dataset, instanceId, provider}
                                  │
                                  ▼
                       BenchmarkRunService
   ┌──────────────────────────────┼───────────────────────────────────┐
   │ 1. load instance              │ 3. submitRun(runMode=BUILD,        │
   │    via BenchmarkSource        │    execMode=PROTOTYPE,             │
   │ 2. set up cleanroom container │    skipApproval=true,             │
   │    via CleanroomContainerMgr  │    spec = issue text ONLY)        │
   └──────────────────────────────┼───────────────────────────────────┘
                                  ▼
              ┌─────────────────────────────────────────┐
              │   THE REAL PIPELINE (unchanged)          │
              │   classify → plan → execute → review     │  ← tools docker-exec into /testbed
              │   → final diff                           │
              └─────────────────────────────────────────┘
                                  │ on terminal
                                  ▼
                       SweBenchScorer  ── apply gold test_patch, run FAIL_TO_PASS + PASS_TO_PASS
                                  │        in the official eval image → RESOLVED / UNRESOLVED
                                  ▼
              ┌──────────────────────────┬──────────────────────────────┐
              │ Lite: DevLoopRunner      │ Live: LeaderboardSubmissionWriter
              │  → failure taxonomy report│  → preds.json + results.json + trajs/ + README
              └──────────────────────────┴──────────────────────────────┘
```

### Package layout (new `benchmark/` package)

```
com.navneet.executespec.benchmark
├── BenchmarkController            // POST /api/admin/benchmark/run, /preview, /instances, /devloop
├── BenchmarkProperties           // @ConfigurationProperties("benchmark")
├── BenchmarkRunService           // orchestrates: load → setup → submitRun(BUILD) → poll → score
├── source/
│   ├── BenchmarkSource           // interface: list(), findById(), imageFor(), problemStatement()
│   ├── BenchmarkInstance         // record: id, repo, baseCommit, problemStatement, failToPass[],
│   │                             //         passToPass[], testPatch, image  (testPatch used ONLY by scorer)
│   ├── SweBenchLiteSource        // princeton-nlp/SWE-bench_Lite + swebench/sweb.eval.* images
│   └── SweBenchLiveSource        // SWE-bench-Live HF dataset + Live images; split = month or lite
├── container/
│   ├── CleanroomContainerManager // reused plumbing: pull/cp/run/exec/diff
│   └── CleanroomContainerStore   // runId → containerId  (see §6 decision)
├── score/
│   ├── SweBenchScorer            // official eval: apply test_patch, run F2P+P2P → Verdict
│   └── Verdict                   // record: resolved, f2pPassed, p2pPassed, log
├── eval/
│   └── DevLoopRunner             // Lite batch + FailureTaxonomy aggregation
└── publish/
    └── LeaderboardSubmissionWriter // Live submission folder (preds/results/trajs/README)
```

### The blind input (exact contract)

`BenchmarkRunService` builds the spec from **`problemStatement` only**, plus non-leaking environment context:

```
Resolve the following GitHub issue in the <repo> repository.

Working directory: /testbed (repo is checked out at the relevant commit).
The environment is offline (no network); dependencies are pre-installed.

# Issue
<problem_statement>
```

**Explicitly excluded:** FAIL_TO_PASS / PASS_TO_PASS test IDs, the gold test_patch, any "acceptance criteria" test list. (The old adapter included all of these — removed.)

---

## 5. Data sources

| | **SWE-bench Lite** (iterate) | **SWE-bench Live** (publish) |
|---|---|---|
| Dataset | `princeton-nlp/SWE-bench_Lite` (HF) — 300 test | `SWE-bench-Live/SWE-bench-Live` (HF) — monthly + frozen lite/verified |
| Images | `docker.io/swebench/sweb.eval.x86_64.<inst>:latest` | Live image registry (per their harness) |
| Eval | official `swebench` harness / `sb-cli` | SWE-bench-Live eval script → `results.json` |
| Publish to | — (internal only) | PR to `SWE-bench-Live/submission`: `YYYYMMDD-executespec/{preds.json,results.json,trajs/,README}` |
| Iteration split | fixed ~50-instance dev slice (disjoint from any reported set) | full monthly set |

**Loader detail:** both sources read the HF parquet/JSON (cache locally under `benchmark.dataDir`). `testPatch` is loaded into `BenchmarkInstance` but is **only ever** read by `SweBenchScorer` — never by `BenchmarkRunService`'s input builder. A unit test asserts the input builder never references `testPatch`/`failToPass`/`passToPass` (guard against regression of Principle 2).

---

## 6. Key decisions to confirm during review

- **D1 — Container store sharing.** `ShellTool`/`BuildProjectTool` today consult `SwebenchContainerStore` + `ProgramBenchContainerStore`. Options: **(a)** introduce one `CleanroomContainerStore` and point both tools at it (cleanest; touches shared tool code → needs tests), or **(b)** have the new manager reuse the existing `SwebenchContainerStore` bean (smallest blast radius for v1). **Recommendation: (b) for v1, refactor to (a) later.**
- **D2 — Run mode.** `BUILD` (full pipeline; my recommendation, matches "showcase the core system") vs `PATCH`/autofix engine. **Plan assumes BUILD.** Flip here if you want the autofix engine showcased instead.
- **D3 — Branch/PR.** Single branch `feature/swebench-harness`, one PR into `dev` for the whole work-session _(memory: `feedback_single_pr_no_new_branch`)_.

---

## 7. Risks & open questions (resolved by the Slice 0 spike)

1. **BUILD mode on a no-build (Python) repo.** BUILD mode runs a per-task `buildProject` smoke + a reviewer fix-build pass. Python SWE-bench repos have no compile step. **Need to verify** the build resolver + reviewer **degrade gracefully** (treat "no build contract" as pass/skip, not as a red build that triggers the fix-build third pass forever). _Hypothesis: the resolver no-ops for Python; confirm, and if not, add a "no-build project type" signal — sandbox-side config, not a code-gen change._ **This is the single biggest assumption and why Slice 0 exists.**
2. **BUILD running inside the cleanroom.** Worker tools must `docker exec` into `/testbed` (not host). The container-store dispatch is mode-agnostic (keyed by runId), so this should "just work" once the store is registered before the pipeline starts — verify ordering vs the `RunStarted` event.
3. **Cost/latency.** BUILD adds planner + reviewer over PATCH. SWE-bench tasks classify SINGLE_SHOT/SMALL so the tax is bounded, but measure it in Slice 0 (see §9).
4. **Agent writing its own tests.** BUILD may author tests in the diff. Harmless — the official eval resets test files to gold before scoring. Confirm the scorer applies `test_patch` on top of (and overriding) any agent test edits.
5. **Live dataset/image availability & licensing** for offline pulls; confirm the monthly split id format.
6. **Throughput.** Disk + Docker pressure when running 50–300 instances; decide sequential vs bounded-parallel (e.g. N=3) and per-instance wall cap.

---

## 8. Implementation slices

> Each slice is independently testable. **Slice 0 is a spike that de-risks the whole plan** — if BUILD mode can't run cleanly in the cleanroom, we revisit D2 before building anything else.

- **Slice 0 — Spike (½ day, no new package yet).** Manually wire one Lite instance: set up its cleanroom container, `submitRun(runMode=BUILD, execMode=PROTOTYPE)` with the blind issue spec + `repoMode=NONE`, watch it run end-to-end against `/testbed`, capture the diff, score by hand. **Exit criteria:** BUILD pipeline completes, tools hit the container, build/reviewer don't loop on the no-build repo. Output: go/no-go on D2 + any no-build config needed. **Local only** _(memory: `feedback_test_locally_first`, `feedback_local_only_for_testing`)._
- **Slice 1 — Source + blind input + BUILD submit.** `BenchmarkSource` + `SweBenchLiteSource` + `BenchmarkInstance`; `CleanroomContainerManager` (reused plumbing); `BenchmarkRunService` happy path; the input-builder leakage guard test. Run 1 instance, capture `submission.patch`.
- **Slice 2 — Scorer.** `SweBenchScorer` via the official harness/`sb-cli`: apply `test_patch`, run F2P + P2P → `Verdict`. Validate on one known-resolvable and one known-hard instance (expect RESOLVED / UNRESOLVED respectively).
- **Slice 3 — Live source + submission packaging.** `SweBenchLiveSource` + `LeaderboardSubmissionWriter` (preds.json + results.json + trajs/ + README). Dry-run the submission folder shape against the `SWE-bench-Live/submission` spec.
- **Slice 4 — Dev loop.** `DevLoopRunner`: batch the fixed 50-instance Lite slice, aggregate a **FailureTaxonomy** report (localization-miss / wrong-fix / out-of-iters / gave-up-early / build-loop / env-fail / cost-cap). This powers the improvement flywheel.
- **Slice 5 — Surface + config + docs.** `BenchmarkController` endpoints, `BenchmarkProperties`, feature flag `benchmark.enabled` (default OFF), and the §10 doc-hygiene pass.
- **Slice 6 — First real runs (post-merge, your trigger, real $).** First Lite baseline + a few improvement cycles; first Live monthly run + leaderboard PR.

---

## 9. Cost & throughput (estimates — validated in Slice 0)

BUILD mode ≈ PATCH per-instance **+ planner + reviewer**. Anchored on prior PATCH data (~$1.50/inst blended) → BUILD est **~$2–4/instance**, provider-dependent (Anthropic mid, OpenAI high, Gemini low — see prior cost analysis). Targets:

| Scope | Anthropic | OpenAI | Gemini |
|---|---|---|---|
| Lite dev slice (50) — per cycle | ~$130 | ~$170 | ~$80 |
| Lite full (300) — one pass | ~$750 | ~$1,000 | ~$450 |
| Live monthly (~50) — one pass | ~$130 | ~$170 | ~$80 |

Per-instance hard cap stays (currently $8). Slice 0 produces the first **real** BUILD-mode per-instance datapoint to replace these estimates.

---

## 10. Doc-hygiene (lands with the code commits, per the standing rule)

- Root `CLAUDE.md`: replace the PR #88 SWE-bench line; document the `benchmark/` package + BUILD-mode-blind approach + Live/Lite split.
- `docs/CODEMAP.md`: new `benchmark/` package, removed `swebench/`.
- `docs/document_map.md`: add this plan.
- `docs/reason-log/2026-05.md`: new RL entry — "rebuilt SWE-bench harness clean: real BUILD pipeline, blind input, Live publish + Lite iterate; retired oracle-mode PATCH adapter."
- Per-package `benchmark/CLAUDE.md`.
- No DB migration expected (synthetic bench org/user/project IDs reused from the old props, or re-seeded).

---

## 11. Approval checklist

- [ ] **D1** container-store sharing — confirm (a) or (b)
- [ ] **D2** run mode — confirm **BUILD** (vs PATCH/autofix)
- [ ] §5 datasets/splits — confirm Lite test (300) for iteration slice source + Live monthly for publish
- [ ] §7 risks — agree Slice 0 spike gates the rest
- [ ] §8 slices — agree scope/order
- [ ] Green-light to start **Slice 0** (local, no spend beyond one instance)

---

## 12. Review outcomes — 2026-05-24 (decisions locked + grounded findings)

@navneet reviewed. Decisions and code-verified findings below supersede the open items above.

### Decisions locked

- **D1 — container store:** reuse existing store for v1 (option **b**). ✅
- **D2 — run mode:** **BUILD**, run **lean** (skip the token-wasteful test machinery where the system allows). ✅ See D5 for the test nuance.
- **D3 — branch/PR:** single branch `feature/swebench-harness`, one PR. ✅
- **D4 — project context (new):** run each instance as a **standalone EXISTING-repo run** (not a full project). This auto-generates a light per-run codemap (~1 PLANNING call, ~$0.10–0.30) and injects it into the planner — delivering the codemap benefit @navneet wants, per-instance, with no per-repo project/architecture setup. Escalate to a full project + pre-generated architecture only if localization proves to be the dominant failure mode in the Lite loop. _(Full project runs add memory+RAG+reused-architecture, which pay off for repeated work on one repo — not the one-shot-per-repo SWE-bench pattern.)_
- **D5 — test-writing (new):** the worker's "tests are mandatory" directive is **unconditional and hard-coded** in `SequentialExecutor.java:102-113` (`SYSTEM_PROMPT_BASE`) — there is **no** production setting to disable it. **For the first Live run: go BUILD-pure** (accept that the worker may write a small test; it's harmless — the official eval applies the gold `test_patch` and runs only F2P/P2P, so agent-written tests don't affect the verdict). **Measure** the token overhead. If material, add a real product setting `requireTests` (default = true → production unchanged) that customers and benchmarks both use — a general feature, not a benchmark fork. Don't block today's run on it.

### Grounded findings (replace the §7 hypotheses)

- **§7.1 RESOLVED — BUILD on no-build Python degrades gracefully.** `BuildCommandResolver` returns `python3 -m compileall -q .` (syntax check) for repos with Python markers, or `true` (no-op) otherwise. The per-task smoke is **advisory, not blocking** — worst case is `COMPLETED_WITH_WARNING`/`PARTIAL`, run continues, **one retry max**. The reviewer fix-build third pass fires **only** on a genuinely red build (non-empty error output) and is capped at 25 iters / 360s — **no loop risk**. SWE-bench images ship `setup.py`/`pyproject.toml`, so we get the syntax-check path. **No special config needed.**
- **§7.3 — small-task assumption (Q3): we are NOT hard-coding "small".** The production classifier routes each instance per-task (SINGLE_SHOT…LEGACY). The cost *estimate* assumed small for budgeting, but at runtime the system adapts — which is the whole point of running the real pipeline. We measure the classification distribution in the Lite loop.
- **§7.4 RESOLVED (Q4) — agent tests are harmless to scoring**; see D5.
- **Project-context wiring confirmed:** `projectId` → `ProjectMemoryProvider.buildPlannerContext` + brief + RAG (~13k tokens); per-run codemap auto-fires for standalone EXISTING-repo runs on PRO+ tier (`RunOrchestrator` ~1022–1055). Wiring detail for Slice 1: make the per-run codemap generate over the **container-cp'd** workspace (repo arrives via `docker cp`, not a GitHub clone).

### Open-question answers

1. **Build on no-build Python — recommend:** proceed with BUILD, no special config (see §7.1 RESOLVED).
2. **Is the cleanroom needed given the project route?** **Yes — still required.** The container provides the exact pinned deps + the offline (`--network none`) eval environment; the project route only adds *context* (codemap/memory), not the runtime. They're complementary and coexist via the bind-mount (`-v <host>:/testbed`). The project route does not remove the container.
3. **Aren't we oversimplifying task size?** Pro: most Lite/Live instances are single-issue localized fixes → classifier routes them lean. Con: some (esp. harder Live) are multi-file → if under-decomposed we under-serve them; if a deceptively-small task is over-decomposed we waste tokens. **Mitigation: we let the real classifier decide per-instance (no forced tier) and measure the distribution.** Both risks are visible, not assumed away.
4. **Agent's own tests:** deferred to your recommendation → see D5 (harmless to score; measure overhead).
5. **What I need from you to run Live today:** (a) Docker running locally + ~10–20 GB free disk (eval images are 1–3 GB each); (b) which provider for the headline + confirm its API key is set (recommend **Anthropic Sonnet**); (c) a budget cap for today's smoke (e.g. **$30–40** for ~10 instances); (d) confirm we run **locally** (not dev.executespec.ai).
6. **Throughput:** sequential for the first runs (avoid Docker contention + easier triage); bounded parallel (N=2–3) once stable. Per-instance wall cap ~30–45 min.

### Lean BUILD submit config (per instance)

`submitRun(runMode=BUILD, executionMode=PROTOTYPE, skipApproval=true, repoMode=EXISTING, projectId=null, aiProvider=anthropic, userCostCap=<cap>, skipAutoPush=true, spec=<issue text only>)` — repo files arrive via the cleanroom container; codemap auto-generates; the diff is captured at terminal and scored externally.

### Today's realistic scope

Build the clean `benchmark/` path + compile-check locally, then **smoke ONE Live instance end-to-end** (this is Slice 0, now running against Live instead of Lite). Full monthly Live run + leaderboard PR follows once the path is proven. Lite stays the iteration surface.

---

## 13. Implementation outcome — BUILD → PATCH pivot (2026-05-24)

> Supersedes **D2** (§11/§12) and the "Lean BUILD submit config" above. The as-built harness runs **`runMode=PATCH`**, not BUILD. Recorded as **RL-2026-05-028**.

**What happened.** §12 locked D2=BUILD on paper-review. The first BUILD-mode smoke (`astroid-1268`) scored `verifiedScore` 94 — but the captured diff was **product churn, not the fix**: a 469-line reformat of `rebuilder.py`, scaffolding files, and a fixture rename. BUILD's product machinery (PostEditFormatter, scaffolding, auto-commit) is exactly what a *customer* wants and exactly what makes a *submission* unscoreable — a leaderboard scores the **minimal source diff**, and BUILD pollutes it.

**The pivot.** Switched to **`runMode=PATCH`** — the production autofix engine (the thin-worker loop that already powers the four customer autofix surfaces via `finding/FindingPatchSubmitter`). PATCH emits a minimal diff with no formatter/scaffolding/auto-commit. **This still honours Principle 1** ("real system, sandbox-only"): PATCH is a first-class production code-gen path, not a benchmark fork — the same engine, tier routing, worker, and tools as the autofix product surfaces. The blind input + cleanroom + external scoring (Principles 2–3) are unchanged.

**Knock-on change.** PATCH normally enforces a test-verification gate (`requireTestVerification=true` — can't `completeTask` until `runTests` is green). That gate is correct for autofix (the failing test IS the spec) but wrong for a **blind** benchmark run (the target test is held out, so the gate can never pass → the worker loops to the iter cap). `engine/CodeGenEngineImpl.java` now scopes the gate by `sourceType`: OFF for `swebench*`/`benchmark*`, ON for `finding:*`.

**D5 (agent-written tests) becomes moot** under PATCH — the `synthesizeBlindSpec` prompt instructs "edit only non-test source; do NOT add/modify test files," and the diff-capture excludes test churn anyway. The BUILD-only "tests are mandatory" `SYSTEM_PROMPT_BASE` directive does not run in PATCH mode.

**The original BUILD submit config (above) is retained for the record** but is NOT what ships — see the as-built call in `BenchmarkRunService.submit` (`runMode=RunMode.PATCH`, `repoMode="NONE"`, repo arrives via `docker cp`, codemap/memory still inject via `projectId`).
