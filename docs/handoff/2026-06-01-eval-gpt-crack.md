# Handoff — SWE-bench eval: GPT-5.4 crack + overnight eval env (2026-06-01)

> Point-in-time working-state handoff. The **permanent** technical record is **RL-2026-05-061**
> (`docs/reason-log/2026-05.md`). Delete/archive this once the next session has picked it up.

## TL;DR

- **Merged** (branch `fix/cross-org-invitee-access`, commits `4dd959ec` + `64dae9aa`): a generic
  worker-prompt lever that **flipped GPT 0/11 → 4/11** (single run) on its addressable SWE-bench gap,
  plus a parallel/overnight **GCP eval environment** (`infra/benchmark/`).
- **Control PASSED (2026-06-01):** re-run shows **3 of 4 flips reproduce** (django-14580 is
  nondeterministic — the model doesn't always regenerate the gold fix) and **0 regression** on the
  passing set of *any* provider (GPT 3/3, Sonnet 4/4, DeepSeek 3/3 graded). Honest GPT gain ≈ **+3 →
  ~19–20**, shared prompt is **safe**.
- **Owed gate (not yet run):** the **net full-50 × all-providers** re-run — the control only sampled
  the passing set; the full net number (all flips − any regression across all 50) is still unconfirmed.
- **Next:** confirm GPT at full-50 (the overnight run), then apply the same evidence-driven
  methodology to the **other providers' gaps** + the **token-reduction** track.

## Where the numbers stand (post-diff-strip, verification OFF, /50)

| Model (ours) | resolve | Reference (frontier / same-model) |
|---|--:|---|
| Sonnet-4.6 | **23** | Claude Code · Sonnet 25 (−2) |
| Gemini-3.5 | **22** | Codex · GPT 22 (tie) |
| DeepSeek-flash | **21** | — (44× cheaper than Gemini: $0.016/run) |
| **GPT-5.4** | **17 → ~19–20 projected** | Codex · GPT 22 |
| — | — | **Claude Code · Opus 4.7 = 35/50 (70%)** — frontier ceiling |

GPT's **~19–20 is a projection** from **~3 reliable** gap flips (control-confirmed: 3/4 reproduce, 0
regression). The single-run high was 4/11; django-14580 is nondeterministic. **The overnight full-50
gives the true net number.**

## What shipped (the merge)

**Commit `4dd959ec` — the GPT lever (`SequentialExecutor.SYSTEM_PROMPT_BASE` points 2 + 4):**
- REPRODUCE with the **issue's verbatim MWE** (not a paraphrase — a self-written repro can pass while
  the real symptom remains).
- VERIFY against the **issue's exact symptom**: *"if your edit didn't change the observed output, the
  code you edited doesn't run on this path → you're in the WRONG place; trace where the bad output is
  produced and fix THERE."* Plus: **match the issue's stated contract** (expose `text=`, not a renamed `mode`).
- **Dropped verification-in-loop** (RL-059's `runWorkspaceTests`-in-prompt + V209/V217): it regressed
  native DeepSeek (the tool in its schema → reasoning-only no-edit stalls). V209/V217 back OFF.
- The 4 per-provider fragments (V219 `provider-prompt-tuning`, ON) — EXISTING-CODE FIX DISCIPLINE.

**Commit `64dae9aa` — GCP eval env (`infra/benchmark/`):** stop/start-persistent VM, sharded 8-way
runner, GCS results, one-command overnight campaign. See `infra/benchmark/EVAL-ENV-PLAN.md`.

## The single-run result (patch-level, scored — see Control above: 3/4 reproduce)

GPT's **addressable gap = 11** (failed, but ≥1 other provider solved). All failed `FAIL_TO_PASS pass=0`
— **wrong-fix / wrong-layer**, NOT no-edits and NOT test-pollution (both disproved by reading the
scored reports: every patch `applied=True`; swebench resets test files to gold before scoring).

| flipped 0→1 ✓ | still failing |
|---|---|
| django-14580 (wrong file `writer.py` → **gold-identical** `serializer.py`) | django-11283 (wrong strategy), django-15061, matplotlib-24265 (*looked* correct — patch-read over-predicted; scoring is the only judge), matplotlib-24334, **flask-4992 (unwinnable blind — `mode` vs hidden `text=`)**, pytest-5103, **sympy-23262 (still mislocated)** |
| matplotlib-23913 (was **no patch** → correct `legend.py`) | |
| sympy-15011, sympy-17022 (de-polluted + correct source) | |

## In flight / how to resume

- **Local control batch — DONE, PASSED.** GPT re-run: **3/4 flips reproduce** (django-14580
  nondeterministic), 3/3 passes held. Sonnet 4/4 + DeepSeek 3/3 graded passes held. **0 regression
  anywhere** → the shared prompt is safe; GPT gain ≈ +3 (~19–20). Manifests
  `core-codegen-ctrl-{gpt,sonnet,ds}.manifest.jsonl` + scoring JSONs in `~/swebench-bench`.
- **The definitive run** = full-50 × {GPT, Sonnet, Gemini, DeepSeek} with this prompt, on the GCP env.
  Runbook: `infra/benchmark/EVAL-ENV-PLAN.md`. ⚠️ pre-flight gotchas: the VM must carry this prompt
  (clone branch `fix/cross-org-invitee-access`) and `deploy.sh` sets the flags — both handled by the
  scripts.

## Methodology + guardrails (hard-won — carry these forward)

- **Score from the actual `submission.patch` / `logs/run_evaluation/<run>/executespec-<prov>/<inst>/`
  artifacts, NOT the manifest `patchBytes`** (it only polls 10 s → false 0 B).
- **Addressable gap = "I failed but ≥1 other solved"** — not the solved-by-nobody set (capability ceiling).
- **Disprove your hypothesis with evidence** before acting (pollution + no-edits were BOTH wrong for GPT).
- **Patch-reading over-predicts** — scoring is the only judge (matplotlib-24265 looked right, failed).
- **Don't overfit** — generic levers in the shared base, no instance names; validate with a control
  (re-run flips + check the passing set + cross-provider blast radius).
- **Eval NEVER provider-shifts** — `cross-provider-fallback` OFF; a 429 is a failed instance, never a swap.
- **Env quirks:** macOS `grep` is broken (use python); foreground `sleep` blocked (background tasks);
  `source` needs an absolute path in zsh; `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for mvn; the runner
  is serial + the app caps concurrency via `swebench.max-concurrent-runs`.

## Key locations

- Eval harness: `~/swebench-bench/` (`run-core-codegen.sh` serial runner, `score-core-codegen.sh`,
  `set50.txt`, `.espec-admin.env`, `swebench_lite_test.json`, manifests, `logs/run_evaluation/`).
- Worker prompt: `src/main/java/com/navneet/executespec/harness/SequentialExecutor.java`
  (`SYSTEM_PROMPT_BASE`) + `src/main/resources/prompts/fragments/provider-*.md`.
- Backend: local, profile `local`, `:8080`; flags provider-prompt-tuning ON, V209/V217 OFF,
  cross-provider-fallback OFF.
- GCP env: `infra/benchmark/` (`EVAL-ENV-PLAN.md` + `scripts/`).
- Token-reduction levers (built, OFF): `tool/ToolOutputCompactor.java`, `tool/WorkerHistoryCompactor.java`.

## Backlog / next improvements (prioritized)

1. **Confirm GPT @ full-50** (the owed gate) — overnight run; net number + zero regression.
2. **Other providers' gaps** — same unbiased per-provider gap analysis for Sonnet/Gemini/DeepSeek;
   the symptom-verify lever is generic — does it lift them? what are their distinct failure modes?
3. **GPT's remaining 7** — django-11283/15061 (wrong/incomplete), sympy-23262 (mislocated), and the
   matplotlib-24265 puzzle (why did a correct-looking patch fail?). flask-4992 is likely unwinnable blind.
4. **Token reduction (task #14)** — per-iteration token breakdown; flip + measure `ToolOutputCompactor`
   (note: only fires on multi-tool-call *turns*) + `WorkerHistoryCompactor` (60k trigger); quality must hold.
5. **Sonnet → 25** — its 2 no-edits; validate the built-but-unproven `AnthropicForcedToolClient` salvage.
6. **Harness ceiling** — run Opus 4.7 through our harness (CC·Opus 35 vs our best 23) to size harness-vs-model gap.
