# Harness Resolution — Decision & Handoff Log

> **Purpose:** running log of every decision and completed step in the harness-resolution build, so ANY agent (Claude, Codex/GPT-5.5) or human can continue from the exact current state. **Update this file after every completed step — that is a standing instruction from the founder.**
>
> Started 2026-07-04 by Claude (Fable 5). Reference docs: [`docs/plans/executespec-codegen-harness-adversarial-assessment-2026-07-04.md`](../../docs/plans/executespec-codegen-harness-adversarial-assessment-2026-07-04.md) (the full assessment + deep dives + tool analysis + pillar evaluation + resolution path) and [`docs/plans/swe-bench-pro-harness-adversarial-assessment-2026-07-04.md`](../../docs/plans/swe-bench-pro-harness-adversarial-assessment-2026-07-04.md) (SWE-bench-specific findings).

---

## 0. Working environment (read first)

| Item | Value |
|---|---|
| **This clone** | `/Users/navneetprabhakar/Documents/antigravity/executespec-harness-resolution` — an isolated full clone. The ORIGINAL repo at `.../antigravity/executespec` has a **Codex eval running — do not touch it**. |
| **Branch** | `feature/harness-resolution`, cut from `origin/benchmark` at `fd0714126` (PR #279 merge). NOT from dev/main — founder decision (the benchmark branch carries the SWE-bench Pro work this builds on). |
| **Remote** | origin = `https://github.com/navneetprabhakar/executespec.git` |
| **Build** | `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` before ANY mvn command. `mvn compile -q` to check, `mvn verify -q` for full (includes spotless). |
| **Migration head** | verify with `ls src/main/resources/db/migration/ | sort -V | tail -3` before creating any new V*.sql (CLAUDE.md said V254 in the old worktree; benchmark branch may differ — CHECK). Never modify applied migrations. |
| **Founder rules (from memory, binding)** | Ask before EVERY git push. No Co-Authored-By trailer in commits. One PR per work-session. Test locally before pushing to dev. Feature flags flip via API, never DB writes. Confirm version bumps. Verify against code, not docs. |
| **Benchmark VM** | `gcloud compute ssh benchmark-instance --zone us-east4-a` (CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.12). Auth expires; founder reauths. iter-2 eval may be running — quiesce before any deploy (deploys kill detached lanes). See `SWE-BENCH-PRO-HANDOFF-2026-07-04.md` in the ORIGINAL repo (untracked) for VM state. |

## 1. Founder decisions (2026-07-04, recorded verbatim intent)

| # | Decision |
|---|---|
| D1 | **Build everything through Phase 3** of the resolution path (B-track + Phases 0–3), all flag-gated/dark. Work in an isolated clone on a branch cut from `benchmark` (Codex eval running in the original worktree). Handoff doc logged at each step. |
| D2 | Branch/landing: as above (supersedes the feature/*→dev default for the cut point; PR target to be decided at PR time — ask founder). |
| D3 | **Eval budget: $20 to start** (DeepSeek only); founder will increase once results show. No Anthropic/OpenRouter iteration spend. |
| D4 | **Flag triage: Claude's recommended dispositions pre-approved** — apply + log them here (§3); nothing flips ON in prod without passing its gate. |
| D5 | Token policy: proposed judge-on-cost-per-verified-point with 2× baseline alarm was implicitly accepted with D3's budget frame; revisit at Phase-1 gate. |

## 2. Plan of record (13 tasks)

Source: resolution path in the assessment doc. Statuses live here; update as you go.

| # | Task | Status |
|---|---|---|
| 1 | Setup: handoff log + assessment docs in repo | ✅ DONE (this file + docs/plans copies) |
| 2 | B.1: inject `requirements`+`interface` into Pro blind spec (SwebenchInstanceLoader + BenchmarkGenerationService.synthesizeBlindSpec) + blindness test | ✅ DONE |
| 3 | B.2: RCA scripts — stagnation-outcome count (**against DB dump — VM being torn down**) + gold-patch exclusion-glob audit | ✅ DONE (scripts committed; RUN them when the DB dump + dataset JSON are available) |
| 4 | Phase 0: k-eval wrapper + canary suite + flag triage (§3) | ⏳ NEXT |
| 5 | Phase 1: worker loop — novelty progress, repetition stagnation, no text-stop kills, nudge copy (flag `worker.progress-v2.enabled`) | ☐ |
| 6 | Phase 1: language-aware `runTests` via resolver + honest schema + remote contract fix | ☐ |
| 7 | Phase 1: lean toolset binding (T1/T2, flag `worker.lean-toolset.enabled`) + gitDiff budget (T3) + writeFile overwrite-with-read-recency (T4) | ☐ |
| 8 | Phase 2: server-side auto-verify + VBC repair loop (flag `worker.auto-verify.enabled`) + reviewer test runner | ☐ |
| 9 | Phase 2: window-aware structured compaction + context-overflow retry (one change) | ☐ |
| 10 | Phase 3: per-provider capability profiles (YAML `model-matrix.capability-profiles`) | ☐ |
| 11 | Phase 3: localization pre-task step (flag `worker.localization.enabled`) | ☐ |
| 12 | Phase 3: run journal + bounded re-plan (flag `execution.run-journal.enabled`) | ☐ |
| 13 | Verify: compile + tests + doc hygiene + PR prep (**NO push without founder approval**) | ☐ |

## 3. Flag triage (founder pre-approved dispositions — D4)

| Flag | Current | Disposition | Graduation criterion | Expiry |
|---|---|---|---|---|
| `build.test-exec-gate.enabled` (V209) | OFF (reverted RL-2026-05-061) | **SUPERSEDE** by `worker.auto-verify.enabled` (task 8) — schema-free design removes the DeepSeek stall cause. Delete flag after auto-verify graduates. | auto-verify k=3: quality ≥ baseline on all providers AND tests-green-at-completion ↑ | 2026-08-15 |
| `worker.tool-compactor.enabled` (V216) | OFF | HOLD until task 9 lands; then k=3 ablation arm | quality ≥ baseline AND tokens/task −15% | 2026-08-15 |
| `worker.build-tdd.enabled` (V217) | OFF (reverted) | **RE-SCOPE to capability profiles** (task 10): ON for anthropic/openai profiles only, stays OFF for deepseek | k=3 on Sonnet lane: quality ≥ baseline | 2026-08-15 |
| `worker.history-compaction.enabled` (V222) | OFF | REPLACE trigger/summary per task 9, then flip ON via ablation | zero overflow-ERRORs AND no quality regression AND failed-task token multiple ↓ | 2026-08-15 |
| `worker.verify-boot.enabled` (V227) | OFF | Profile-scope: ON for app-shaped BUILD runs on all providers (it adds a tool → keep OFF for deepseek until profile eval) | k=3 BUILD eval: verified-score ≥ baseline | 2026-08-31 |
| `worker.background-jobs.enabled` (V233) | OFF (wiring incomplete) | Complete the run-end JobRegistry cleanup TODO **only if** verifyBoot graduates (they serve the same app-shaped class); else DELETE | dependent on V227 outcome | 2026-08-31 |
| `worker.code-outline.enabled` (V234) | OFF | Profile-scope (anthropic/openai); Java-only is fine for now, tree-sitter later | k=3: no schema-stall signal (unknown-tool rate ~0) AND iterations-to-first-edit ↓ | 2026-08-31 |

New flags introduced by this build (ALL default OFF; graduation = k=3 gate vs Phase-0 baseline): `worker.progress-v2.enabled`, `worker.lean-toolset.enabled`, `worker.auto-verify.enabled`, `worker.localization.enabled`, `execution.run-journal.enabled`.

## 4. Step log (append-only — newest at bottom)

| When | Agent | Step | Detail |
|---|---|---|---|
| 2026-07-04 | Claude (Fable 5) | Clone + branch | Cloned original → `executespec-harness-resolution`; `feature/harness-resolution` from `origin/benchmark` @ `fd0714126`. |
| 2026-07-04 | Claude (Fable 5) | Docs in repo | Copied both assessment docs to `docs/plans/`; created this log. |
| 2026-07-04 | Claude (Fable 5) | **B.1 DONE** | `SwebenchInstanceLoader`: added `requirements` record field + parse (`n.path("requirements")`). `BenchmarkGenerationService.synthesizeBlindSpec` (now package-private for tests): new `# Requirements` section ("acceptance contract, senior to issue text") + `# Interface` promoted from one-line repo bullet to full section with exact-signature directive; "How to work" renumbered; repro-script clarification added to the no-test-edit rule; `BENCH_VERIFY_CHECKLIST` re-reads Requirements. Tests: new `BenchmarkBlindSpecTest` (4 tests — sections present/ordered, omitted when blank, NO leak of gold patch/test_patch/FAIL_TO_PASS ids, checklist retained) + `SwebenchInstanceLoaderTest` extended. `mvn compile` clean; 8/8 tests green. Package `benchmark/CLAUDE.md` principle #2 updated. |
| 2026-07-04 | Founder | Env change | Codex eval session STOPPED; benchmark VM being torn down (cost) — founder copying DB data first. B.2 stagnation script must target the **DB dump**, not the live VM. Any future eval runs need a new VM or local docker (recalibrate Phase-0 baseline plan when environment is known). |

## 5. Continuation instructions (for the next agent)

1. Read §0–§3 of this file, then the assessment doc's **Resolution path** section (it is the spec for tasks 2–13; the **Appendix — Implementation detail per priority item** section has per-item file:line design notes).
2. **Line numbers in the assessment were audited on an older worktree** (`codex/swebench-pro-plan`). This branch is `benchmark` @ fd0714126 — always re-read the actual code before editing (founder rule: no guessing).
3. Pick the first ☐/⏳ task in §2, set it in progress, implement, compile (`JAVA_HOME=... mvn compile -q`), test, then append a row to §4 and flip §2 status **before** moving on.
4. All new behavior behind the flags listed in §3 — defaults OFF, so the benchmark branch stays byte-identical in behavior until eval flips them.
5. Do NOT push, deploy, or touch the original repo/VM without founder approval. Commit locally on this branch as you complete tasks (no Co-Authored-By trailer).
6. Eval spend: $20 cap (D3), DeepSeek only, and only after tasks 2–7 compile clean — coordinate VM access with the founder.
| 2026-07-04 | Claude (Fable 5) | **B.2 DONE (scripts)** | `infra/benchmark/scripts/rca-stagnation-outcomes.py` — outcome counts from `pipeline_run.status_reason='BENCH_GEN:<OUTCOME>'` per model, joined to `benchmark_task_result.reward` when present; targets any Postgres DSN (use the copied dump; VM teardown noted). `rca-exclusion-glob-audit.py` — parses gold `patch` paths from the dataset JSON and matches the EXACT captureDiff exclusion list; validated on synthetic data (flags `internal/testing/*.go` + `setup.py`, no false positive on `*_test.go`). **ACTION for founder/next agent: run both once the DB dump is restored + point the glob audit at `/var/swebench/swebench_pro_sample20.json` (copy it off the VM before teardown!).** |
