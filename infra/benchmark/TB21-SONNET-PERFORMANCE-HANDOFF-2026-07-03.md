# TB 2.1 Sonnet Performance Debug Handoff

Date: 2026-07-03

Owner intent: debug why ExecuteSpec's Terminal-Bench 2.1 harness is underperforming versus stronger harnesses, with Sonnet 4.6 as the immediate investigation target. Treat this as a harness-quality investigation first, not a model-quality conclusion.

## Current judgment

The current Sonnet result is weak enough that we should assume harness loss until disproven. The same model class is reportedly performing around **60-63% on TB 2.1** elsewhere. Our current Sonnet result is **42/89 = 47.2%**. That is not a minor 5-10% wobble; it is roughly an **11-14 task gap** against a 60-63% expectation.

Observed current Sonnet 4.6 one-pass result:

- In-app benchmark run id: `c288a819-ffba-4571-b3f3-8b41cd5cfd62`
- Harbor job dir: `/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1053-claude-sonnet-4-6-c4`
- Model/provider: `anthropic` / `claude-sonnet-4-6`
- Status at latest snapshot: `DONE`
- Score: `42/89`
- Cost: about `$136.02`
- Tokens: `32,334,412` input / `1,362,329` output

This was run during a live backend deploy and resume. Restart/auth-contaminated result dirs were removed, and the final Harbor dir now has exactly 89 task result files with no duplicate task result dirs. Still, the handoff recipient should verify before analysis.

Working target for the debug:

- Expected competitive Sonnet range: about `53-56/89`
- Current ExecuteSpec Sonnet result: `42/89`
- Required recovery to be competitive: about `+11` to `+14` tasks
- Therefore, do not spend time on small prompt wording or model parameter tweaks until the harness path has been audited for structural losses.

## Active environment

Benchmark host:

```bash
CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.12 \
  gcloud compute ssh benchmark-instance --zone us-east4-a
```

Running backend:

```bash
cat /opt/app/RUNNING_SHA
curl -sf http://localhost:8080/actuator/health
unzip -p /opt/app/app.jar BOOT-INF/classes/application.yml | sed -n '/^terminal-bench:/,/^[^ ]/p'
```

Expected deployed SHA for the timeout fix:

```text
217880227d22bf5ac567a8c4e46d1b7bb03d6a77
```

Expected deployed TB wall-clock config:

```yaml
terminal-bench:
  worker-wall-clock-seconds: 1260
```

Repo branch with the latest operational fixes:

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec
git status --short --branch
```

Current local branch should be `codex/benchmark-visible-tests-gate`, two commits ahead of `origin/benchmark`:

- `217880227` - `Tune Terminal-Bench worker wall clock`
- `5537f4ce0` - `Add benchmark bridge resume mode`

## Credential and secret locations

Do not paste raw provider keys or passwords into a tracked file or chat transcript. Use these locations on the VM.

App/runtime env:

```bash
sudo cat /opt/app/executespec.env
```

This contains runtime env vars such as DB connection info, provider keys, OAuth settings, and benchmark app configuration.

Benchmark admin auth env:

```bash
cat /home/admin_executespec_ai/swebench-bench/.espec-admin.env
```

This is the env file used by benchmark scripts for admin login. Normalize both naming schemes if running Harbor manually:

```bash
set -a
. /home/admin_executespec_ai/swebench-bench/.espec-admin.env
set +a
export ES_ADMIN_EMAIL="${ES_ADMIN_EMAIL:-${ESPEC_ADMIN_EMAIL:-}}"
export ES_ADMIN_PW="${ES_ADMIN_PW:-${ESPEC_ADMIN_PW:-}}"
export ESPEC_ADMIN_EMAIL="${ESPEC_ADMIN_EMAIL:-${ES_ADMIN_EMAIL:-}}"
export ESPEC_ADMIN_PW="${ESPEC_ADMIN_PW:-${ES_ADMIN_PW:-}}"
```

Local Postgres access:

```bash
set -a
sudo grep -E '^(POSTGRES_HOST|POSTGRES_PORT|POSTGRES_DB|POSTGRES_USER|POSTGRES_PASSWORD)=' /opt/app/executespec.env > /tmp/es-db.env
. /tmp/es-db.env
set +a
PGPASSWORD="$POSTGRES_PASSWORD" psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

Do not hardcode DB credentials into scripts or docs; source the env.

## Current benchmark runs

Snapshot from 2026-07-03 around 15:09 IST:

```sql
select id, model, status, resolved_tasks, total_tasks, cost_usd, input_tokens, output_tokens
from benchmark_run
where id in (
  'c288a819-ffba-4571-b3f3-8b41cd5cfd62',
  '945b85ac-2c18-4f02-a89b-0af3fabc4e82',
  '7744b918-2d13-4d0d-8755-944731abd407'
)
order by created_at;
```

Known rows at snapshot:

| Model | In-app run id | Status | Resolved | Notes |
| --- | --- | ---: | ---: | --- |
| Sonnet 4.6 | `c288a819-ffba-4571-b3f3-8b41cd5cfd62` | `DONE` | `42/89` | Primary debug target |
| GLM 5.2 | `945b85ac-2c18-4f02-a89b-0af3fabc4e82` | `RUNNING` | `18/89` then still running | OpenRouter |
| DeepSeek V4 Pro native | `7744b918-2d13-4d0d-8755-944731abd407` | `RUNNING` | `9/89` then still running | Started at concurrency 3 |

Job dirs:

```bash
SONNET_JOB=/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1053-claude-sonnet-4-6-c4
GLM_JOB=/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1220-glm-5-2-openrouter-c4
PRO_JOB=/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1340-deepseek-v4-pro-native-c3-1260
```

## Sonnet pass/fail list

Passed tasks:

```text
adaptive-rejection-sampler
bn-fit-modify
build-cython-ext
build-pmars
chess-best-move
cobol-modernization
configure-git-webserver
constraints-scheduling
count-dataset-tokens
crack-7z-hash
distribution-search
dna-insert
feal-differential-cryptanalysis
financial-document-processor
fix-code-vulnerability
fix-git
fix-ocaml-gc
git-leak-recovery
headless-terminal
hf-model-inference
kv-store-grpc
log-summary-date-ranges
modernize-scientific-stack
multi-source-data-merger
nginx-request-logging
openssl-selfsigned-cert
overfull-hbox
polyglot-c-py
polyglot-rust-c
portfolio-optimization
prove-plus-comm
pypi-server
pytorch-model-recovery
qemu-startup
query-optimize
regex-log
reshard-c4-data
sam-cell-seg
sqlite-db-truncate
sqlite-with-gcov
tune-mjcf
vulnerable-secret
```

Failed tasks:

```text
break-filter-js-from-html
build-pov-ray
caffe-cifar-10
cancel-async-tasks
circuit-fibsqrt
code-from-image
compile-compcert
custom-memory-heap-crash
db-wal-recovery
dna-assembly
extract-elf
extract-moves-from-video
feal-linear-cryptanalysis
filter-js-from-html
gcode-to-text
git-multibranch
gpt2-codegolf
install-windows-3.11
large-scale-text-editing
largest-eigenval
llm-inference-batching-scheduler
mailman
make-doom-for-mips
make-mips-interpreter
mcmc-sampling-stan
merge-diff-arc-agi-task
model-extraction-relu-logits
mteb-leaderboard
mteb-retrieve
password-recovery
path-tracing-reverse
path-tracing
protein-assembly
pytorch-model-cli
qemu-alpine-ssh
raman-fitting
regex-chess
rstan-to-pystan
sanitize-git-repo
schemelike-metacircular-eval
sparql-university
torch-pipeline-parallelism
torch-tensor-parallelism
train-fasttext
video-processing
winning-avg-corewars
write-compressor
```

## Integrity checks before analysis

Run these first. Do not debug model behavior from contaminated files.

```bash
python3 - <<'PY'
import json, pathlib, collections
job = pathlib.Path("/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1053-claude-sonnet-4-6-c4")
by = collections.defaultdict(list)
bad = []
for f in job.glob("*/result.json"):
    task = f.parent.name.split("__", 1)[0]
    d = json.loads(f.read_text())
    ar = d.get("agent_result") or {}
    exc = json.dumps(d.get("exception_info") or {}, default=str).lower()
    by[task].append(f.parent.name)
    if (not isinstance(ar, dict) or ar.get("n_input_tokens") is None or ar.get("n_output_tokens") is None) and any(
        s in exc for s in ("provide es_pat", "connecterror", "remoteprotocolerror", "connection refused")
    ):
        bad.append(f.parent.name)
print("tasks", len(by), "result_json", sum(len(v) for v in by.values()))
print("duplicates", {k:v for k,v in sorted(by.items()) if len(v) > 1})
print("bad_infra", bad)
PY
```

Expected for the final Sonnet dir:

```text
tasks 89
result_json 89
duplicates {}
bad_infra []
```

## Files to inspect

Harness and adapter:

```bash
/home/admin_executespec_ai/tbench/es_harbor_agent.py
infra/benchmark/tb/es_harbor_agent.py
infra/benchmark/tb/tb-bench-bridge.py
infra/benchmark/scripts/run-tb.sh
infra/benchmark/scripts/tb-tracker.py
```

Server execution path:

```text
src/main/java/com/navneet/executespec/terminalbench/TerminalBenchRunService.java
src/main/java/com/navneet/executespec/engine/WorkerAgent.java
src/main/java/com/navneet/executespec/harness/SequentialExecutor.java
src/main/java/com/navneet/executespec/tool/remote/RemoteToolDispatcher.java
src/main/java/com/navneet/executespec/tool/ToolRegistry.java
src/main/resources/prompts/
src/main/resources/application.yml
```

Operational notes:

```text
infra/benchmark/TB21-OPERATIONAL-NOTES.md
```

## Useful DB queries

Map Sonnet benchmark task results to ExecuteSpec run ids:

```sql
select task_name, reward, es_run_id, cost_usd, input_tokens, output_tokens, outcome, outcome_reason
from benchmark_task_result
where benchmark_run_id = 'c288a819-ffba-4571-b3f3-8b41cd5cfd62'
order by task_name;
```

Inspect a failed run:

```sql
select id, status, status_reason, ai_provider, model_name, verified_score, created_at, completed_at
from pipeline_run
where id = '<es_run_id>';
```

Find wall-clock / timeout shaped failures:

```sql
select b.task_name, b.reward, b.es_run_id, p.status, p.status_reason, p.error_message
from benchmark_task_result b
left join pipeline_run p on p.id = b.es_run_id
where b.benchmark_run_id = 'c288a819-ffba-4571-b3f3-8b41cd5cfd62'
  and b.reward = 0
order by b.task_name;
```

## Harbor artifact inspection

Example for one task:

```bash
TASK=build-pov-ray
TRIAL_DIR=$(find "$SONNET_JOB" -mindepth 1 -maxdepth 1 -type d -name "${TASK}__*" | head -1)
echo "$TRIAL_DIR"
jq '{task_name, agent_result, verifier_result, exception_info}' "$TRIAL_DIR/result.json"
find "$TRIAL_DIR" -maxdepth 3 -type f | sort
```

Common files:

```text
result.json
agent/trajectory.json
agent/*.log
```

## High-signal debugging plan

1. Separate verifier-visible artifact failures from harness-control failures.

   For every Sonnet failed task, classify:

   - Did ExecuteSpec run reach terminal state cleanly?
   - Did the worker call `completeTask`?
   - Did the expected `/app` artifact exist?
   - Did verifier fail due exact file content, missing file, performance, or timeout?
   - Did the remote adapter drop a final tool result or finish before finalizer output?

2. Compare Sonnet failures against DeepSeek Pro and Flash.

   Use the old first-pass data:

   - Old DeepSeek Flash first pass: `44/89`
   - Current Flash rerun: `46/89`
   - Old DeepSeek Pro first pass: `55/89`
   - Current DeepSeek Pro earlier rerun: `49/89`
   - Current Sonnet: `42/89`

   If Sonnet fails tasks that DeepSeek passes with similar harness behavior, inspect prompt/model behavior. If all models fail the same shape, inspect harness/tooling.

3. Prioritize these Sonnet failures first.

   These are likely harness-leverage tasks because stronger harnesses usually recover with better terminal discipline, visible-test extraction, or artifact-finalization:

   ```text
   break-filter-js-from-html
   build-pov-ray
   custom-memory-heap-crash
   merge-diff-arc-agi-task
   sanitize-git-repo
   schemelike-metacircular-eval
   write-compressor
   caffe-cifar-10
   circuit-fibsqrt
   path-tracing
   path-tracing-reverse
   ```

4. Look for these harness weaknesses. The goal is to find an 11-14 task loss, so prioritize broad systematic defects over one-off task quirks.

   - Worker does not systematically inspect visible `/tests` files early enough.
   - Worker treats terminal task as open-ended coding instead of artifact contract extraction.
   - The remote tool bridge compresses or truncates evidence that should remain exact.
   - Finalizer runs too late or lacks task-specific artifact contract.
   - No explicit "stop modifying once verifier-visible checks pass" policy.
   - Long-running tasks burn wall-clock without checkpointing the candidate artifact.
   - Shell progress resets no-progress counters, delaying decisive finalization.
   - Prompt does not force byte-for-byte output contracts for transform/filter tasks.

5. Do not start with model temperature or provider settings.

   Sonnet at `temperature=0.0` is already deterministic enough for this pass. If the same model can reach 60-63% elsewhere, model parameter tuning is lower leverage than task contract extraction, finalizer quality, and remote-tool reliability.

## Commands to compare task matrices

Generate clean task matrix from Harbor:

```bash
python3 - <<'PY'
import json, pathlib, csv
jobs = {
  "sonnet": pathlib.Path("/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1053-claude-sonnet-4-6-c4"),
  "glm": pathlib.Path("/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1220-glm-5-2-openrouter-c4"),
  "deepseek_pro": pathlib.Path("/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1340-deepseek-v4-pro-native-c3-1260"),
}
rows = {}
for name, root in jobs.items():
    for f in root.glob("*/result.json"):
        task = f.parent.name.split("__", 1)[0]
        d = json.loads(f.read_text())
        vr = d.get("verifier_result") or {}
        reward = int(float(((vr.get("rewards") or {}).get("reward") or 0))) if isinstance(vr, dict) else 0
        rows.setdefault(task, {})[name] = reward
for task in sorted(rows):
    print(task, rows[task])
PY
```

## Operational caution

Do not casually stop or restart the backend while Harbor is running. Harbor has no true live pause. A backend restart while trials are active creates `ConnectError` / `RemoteProtocolError` / auth failure result files that look like task failures unless purged.

If a checkpoint is unavoidable:

1. Stop Harbor launchers.
2. Preserve completed trial dirs with `result.json`.
3. Remove restart/auth failure trial dirs before resuming.
4. Resume Harbor with both auth env naming schemes set.
5. Restart the bridge using:

```bash
python3 infra/benchmark/tb/tb-bench-bridge.py \
  --job-dir "$SONNET_JOB" \
  --provider anthropic \
  --model claude-sonnet-4-6 \
  --k 1 \
  --total 89 \
  --dataset terminal-bench-2-1 \
  --run-id c288a819-ffba-4571-b3f3-8b41cd5cfd62 \
  --resume-existing
```

## Paste-ready prompt for the next debugging agent

You are debugging ExecuteSpec's Terminal-Bench 2.1 harness on the benchmark VM. Focus on Sonnet 4.6 performance. Do not treat the current `42/89` as a pure model score until you separate harness failures from model failures.

Start from `/Users/navneetprabhakar/Documents/antigravity/executespec`, read `infra/benchmark/TB21-SONNET-PERFORMANCE-HANDOFF-2026-07-03.md`, then SSH to `benchmark-instance` in `us-east4-a`. Use the credential locations in the handoff, but do not print raw secrets. Verify the Sonnet Harbor dir has 89 unique task results and no restart/auth contamination. Same-model external expectation is 60-63% on TB 2.1; our result is 42/89, so look for an 11-14 task harness gap. Deep-dive the 47 failed Sonnet tasks by reading Harbor `result.json`, trajectories, ExecuteSpec `pipeline_run` rows, and any workspace artifacts. Classify each failure as harness/tooling/finalizer/prompt/model/verifier/performance. Produce a prioritized fix plan, starting with harness-level fixes likely to recover double-digit tasks without creating a separate codegen path.
