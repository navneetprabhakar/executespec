# DeepSeek benchmark handoff — 2026-07-18

## Objective

Run and independently reconcile the DeepSeek V4 Pro benchmark campaign in this
order:

1. Terminal-Bench 2.1 — full 89-task, k=1, with ATIF trajectory capture.
2. SWE-bench Lite — complete official test set, with an independently installed
   official scorer and durable per-task trajectories.
3. SWE-bench Pro — complete official test set, using the same evidence and cost
   controls.

ExecuteSpec's `/benchmark` page is the operator tracker. It is an event sink and
must agree with the Harbor/official-verifier artifacts; its headline score is not
the sole quality authority.

## Pinned environment

- VM: `benchmark-instance`, project `executespecv1`, zone `us-east4-a`.
- VM shape: x86, 16 vCPU, approximately 64 GiB RAM; no swap.
- Backend: `/home/admin_executespec_ai/executespec`, systemd
  `executespec-benchmark.service`, profile `benchmark`.
- Harbor: `/home/admin_executespec_ai/tbench/.venv/bin/harbor` (0.13.1).
- Jobs: `/home/admin_executespec_ai/es-tb-jobs/`.
- Credentials: `/home/admin_executespec_ai/swebench-bench/.espec-admin.env`
  (`ES_ADMIN_EMAIL` / `ES_ADMIN_PW`; never copy values into logs or docs).
- GCS results: `gs://executespec-swebench-lite-20260718-results`.
- Benchmark page: `https://benchmark.executespec.ai/benchmark`.

## TB 2.1 run

Current job name:

`tb21-deepseek-v4-pro-native-k1-full89-20260718`

The first live run `06079ef7-a7ca-4e44-ae85-351bd3e3b75c` was stopped as invalid:
25 zero-cost trials failed before provider dispatch because the durable internal
execution context was missing. Its trajectories and scores must not be used. A
replacement run will be launched only after the fix is deployed and a one-task
provider smoke passes.

Pinned route:

```text
ES_PROVIDER=deepseek
ES_MODEL=deepseek-v4-pro-native
HARBOR_MODEL=deepseek/deepseek-v4-pro
TB_DATASET=terminal-bench/terminal-bench-2-1
K=1
CONCURRENCY=4
HARBOR_AGENT_TIMEOUT_SEC=1500
ES_AGENT_BUDGET_SECONDS=1410
BRIDGE=on
SUBMIT_TB=off
```

Concurrency is intentionally fixed at 4. The VM has enough RAM for this level,
but 6 is not to be enabled without observed clean provider, Harbor, Redis, DB,
and timeout signals. Do not run multiple model jobs concurrently on this VM.

The runner pre-pulls all 89 task images before provider spend. The adapter writes
`agent/trajectory.json` for every trial. A future leaderboard submission must run
`submit-tb.sh` with ATIF validation enabled and must not proceed if any required
trajectory is absent or invalid. Internal runs remain `SUBMIT_TB=off`.

Watch:

```bash
JOB=tb21-deepseek-v4-pro-native-k1-full89-20260718
tail -f ~/es-tb-jobs/$JOB.launch.log
tail -f ~/es-tb-jobs/$JOB-run.log
tail -f ~/es-tb-jobs/$JOB-bridge.log
free -h
```

The bridge log contains the durable `benchmark_run` UUID. Confirm that UUID in
the `/benchmark` UI and via `GET /api/v2/test/benchmark/{id}` after authentication.

## SWE-bench Lite and Pro

Run each suite as a separate campaign and ledger. Do not reuse a failed tracker
UUID or merge old attempts into a fresh official result. Freeze and record:

- dataset revision and task manifest SHA-256;
- ExecuteSpec/backend JAR SHA and prompt/config flags;
- provider/model (`deepseek` / native V4 Pro);
- task timeout, concurrency, campaign budget, and opening balance;
- every task attempt, provider-reported cost, input/output/cache/reasoning tokens;
- trajectory location + SHA-256 and workspace evidence;
- official scorer version, command, JSON report, and denominator.

The official score is `resolved / submitted`, not `resolved / full upstream
dataset` when running a deliberate subset. Empty patches are submitted tasks and
must be counted explicitly. Recompute the aggregate from raw scorer output and
compare it with `/benchmark` before publishing a result.

After each suite reaches terminal state:

1. stop generation and reconcile reservations/costs;
2. run the pinned official scorer;
3. validate every patch and trajectory independently;
4. publish `TASK_SCORED` and `RUN_DONE` only after reconciliation;
5. copy the manifest, scorer report, tracker export, and logs to the GCS prefix;
6. only then start the next suite.

## Hourly monitoring

The monitor must record UTC timestamp, job status, completed/total, resolved,
cost, token totals, active attempts, last progress, ETA, Harbor process state,
Docker containers, memory, and any 429/5xx/timeout counts. A monitor must never
restart or alter a run automatically.

Useful command:

```bash
gcloud compute ssh benchmark-instance --project=executespecv1 --zone=us-east4-a \
  --command='JOB=tb21-deepseek-v4-pro-native-k1-full89-20260718; date -u; free -h; \
  pgrep -af "[h]arbor|[r]un-tb|[t]b-bench-bridge" || true; \
  tail -20 ~/es-tb-jobs/$JOB.launch.log'
```

If this session is unavailable, Claude should run that command hourly and append
the output to the handoff log. Do not kill the VM or teardown the benchmark
environment until all three DeepSeek campaigns and scorer reconciliation are
terminal, or Navneet explicitly approves teardown.

## Known script fix

Commit `7e2d97f27` makes `run-tb.sh` and `submit-tb.sh` accept both the historical
`ESPEC_ADMIN_*` names and the VM's `ES_ADMIN_*` names. Without this, Harbor would
fail before provider dispatch even though credentials were present.

## Follow-up routing experiment

After all three DeepSeek campaigns close, run the separately planned
DeepSeek→GLM escalation cohort. Do not change production defaults or Enterprise /
BYOK single-provider policy before that experiment's cost, quality, latency,
trajectory, and policy-compliance report is reviewed.

## Tracker cleanup evidence (2026-07-18)

The benchmark VM tracker was inspected directly in PostgreSQL. Rows with
`FAILED`, `STOPPED`, or `CANCELLED` status were absent (an idempotent cleanup
returned zero rows). One historical SWE-bench row was also identified as an
invalid pre-fix run: `86b1eed7-35d5-4daf-8266-997e268a0730` was labelled `DONE`
despite `0/10`, zero input/output tokens, and no provider evidence. That row was
deleted from `benchmark_run` (dependent benchmark events/tasks cascade); no
`pipeline_run`, workspace, trajectory, or valid result was deleted.

Post-cleanup tracker state:

| Run | Status | Evidence |
|---|---|---|
| `3da69653-c05b-4099-85aa-0ede3751f611` | DONE | DeepSeek Lite corrected recovery, 6/10, non-zero usage |
| `e3af80de-5050-4268-b9e1-e16ed53a6ed5` | DONE | GLM 5.2 Lite10, 7/10, official scorer evidence |
| `6fe55eaf-70dc-461e-9668-a83c6dfb9afb` | RUNNING | one-task DeepSeek TB smoke; retain until terminal evidence is captured |

The active smoke row currently shows zero tracker tokens/cost until its trial
publishes `TASK_SCORED`; the bridge publishes terminal usage today, while the
server-side worker ledger already contains live usage. This is a live-progress
tracking gap to fix or explicitly document before a long campaign.
