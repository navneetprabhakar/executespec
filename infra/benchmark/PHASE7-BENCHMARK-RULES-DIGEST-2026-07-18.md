# Phase 7 — Official benchmark rules digest (2026-07-18)

Authoritative submission/execution rules for the two benchmarks in the plan, captured **before any
paid run**, plus the compliance gaps in our current internal harness. Sources at the bottom.

> Bottom line: our **internal** TB 2.1 runs (k=1, custom timeouts, private) are fine for validation,
> but a **leaderboard submission** for either benchmark needs config + artifact changes. None of
> this blocks an internal DeepSeek measurement; it blocks *publishing* until we comply.

---

## A. Terminal-Bench 2.1 (Harbor) — leaderboard submission rules

**Trials / coverage**
- **≥ 5 trials per task, every task in the dataset** (89 tasks → ≥ 445 trials). Our internal runs are **k=1** → not submittable as-is.
- **Errored trials count as reward 0** and are NOT excluded from the metric. (Our internal reconcile currently separates `AgentTimeoutError`/infra failures out of the denominator — legitimate for internal analysis, **not** for the leaderboard metric.)

**Execution constraints (hard CI gate)**
- Exact pinned dataset version from `core/hub.py` (`DATASET@DATASET_REF`).
- **`timeout_multiplier` unset/None or 1.0; NO agent or verifier timeout overrides; NO resource overrides. Default execution settings only.**
  - ⚠️ Our `run-tb.sh` sets `HARBOR_AGENT_TIMEOUT_SEC=1500` + `agents[].override_timeout_sec`/`max_timeout_sec` and an ExecuteSpec worker wall-clock of 1410s. Those are **agent-timeout overrides → prohibited for submission.** They exist to reduce the ~8–10% agent-timeout loss internally; for a leaderboard run they must be removed and we accept default timeouts (so the Phase-12 timeout-remediation work must live *inside* the agent budget, not via Harbor overrides).

**Artifacts / trajectories**
- Run with **`harbor run --upload`** (or upload after) → trials must be **publicly readable** on Harbor Hub (`https://hub.harborframework.com/jobs/<uuid>`). Our internal runs are private → must switch to `--upload`.
- Successful trials must have a Hub **`trajectory_path` in ATIF format**; "agents that only upload custom logs fail" validation.

**Submission process**
- `uv run lb submit <job-links…>` (or the 3-step `lb filter` → `lb metadata` → `lb open-prs`) from the `leaderboard/` dir of `harbor-framework/terminal-bench-2-1`.
- **One PR per submission, one file**, branch `submission/<name>`, new files only.
- Display metadata required: `display_name`, `display_url`, `display_org`, `org_url`.
- CI static-analysis gates: valid source filter, complete metadata, proper timeout settings, full task coverage (≥5/task), per-trial config matching, trajectory paths for rewarded trials.

**Cost/time impact:** k=5 = ~5× our k=1 numbers. DeepSeek Flash k=1 ≈ $1.3/89; k=5 ≈ **~$6–7** + ~5× wall-clock. Still well under the founder's <$25 note, but plan for the time.

---

## B. SWE-bench Lite — leaderboard submission rules

**Format (PR to `swe-bench/experiments`)**
```
evaluation/lite/<YYYYMMDD>_<model_name>/
├── all_preds.jsonl        # (or preds.json) one prediction per instance — pass@1
├── metadata.yaml          # fields per checklist.md (name, org, links, etc.)
├── README.md
├── trajs/                 # one file per instance: <instance_id>.md/.json/.yaml, ALL intermediate steps
└── logs/                  # auto-generated per-instance: patch.diff, report.json, test_output.txt
```
- Clean with `python -m analysis.get_results evaluation/lite/<date_model>` before the PR.

**Validation / "Verified"**
- Open an issue with reproduction instructions; maintainers re-run a **random subset**; a **Verified** check is awarded on success. So the run must be reproducible from the submitted config.

**Prohibited / policy**
- Standard SWE-bench rules: **pass@1**, hidden tests + gold `test_patch` applied *externally* at scoring (blind — the model never sees the tests). Our `benchmark/` harness already runs blind PATCH-mode, which matches.
- **Policy (11/18/2025):** SWE-bench **Verified & Multilingual** now require an "Open Research Publication" (arXiv/tech report) + ≥1 author from an academic institution/established research lab. **SWE-bench Lite is not listed under this restriction** — but ⚠️ **verify Lite is still an actively-maintained/accepting split** before investing (several splits have been de-emphasized in favor of Verified). If Lite is closed, publish as a self-hosted reproducible result rather than a leaderboard PR.

**Cost/time:** founder's runbook projects full-731 SWE-bench *Pro* at ~$34 DeepSeek; **Lite is 300 instances** → materially cheaper (~$10–15 DeepSeek Pro-native, less for Flash), ~a few hours on the VM with concurrency 4.

---

## C. Actionable deltas before publishing (not before internal measuring)

1. **TB 2.1 leaderboard run:** remove all Harbor timeout/resource overrides, set default timeouts, run **k=5** with `--upload` (public), capture ATIF trajectories, then `uv run lb submit`. Keep a *separate* internal k=1 override run only for our own analysis — never submit it.
2. **TB metric honesty:** for a submitted run, do NOT exclude timeout/infra-errored trials from the denominator (they're reward 0). Our internal reconcile can still report the breakdown separately.
3. **SWE-bench Lite:** confirm the split is still accepting submissions; if yes, produce the full `evaluation/lite/<date>_<model>/` bundle incl. per-instance `trajs/` + `logs/`; if no, publish a reproducible self-report.
4. **Trajectory export is mandatory both ways** — our event log is Redis 24h TTL, so we must dump per-instance trajectories (run_step_usage + event log, and ATIF for TB) to disk *during* the run.
5. **MODEL-SWITCH GATE reminder:** the plan wants GPT-5.6 Luna driving the benchmark phases; founder waived it for this session (proceed on current model). Benchmark *results* come from DeepSeek/GLM regardless.

## Sources
- SWE-bench leaderboards: https://www.swebench.com/
- SWE-bench submission spec: https://github.com/swe-bench/experiments (README + checklist.md)
- Terminal-Bench 2.1: https://github.com/harbor-framework/terminal-bench-2-1 (`leaderboard/SUBMIT.md`)
- TB 2.1 leaderboard: https://www.tbench.ai/leaderboard/terminal-bench/2.1
- Harbor run docs: https://www.harborframework.com/docs/tutorials/running-terminal-bench
