# TB 2.1 Benchmark Session Handoff

Date: 2026-07-03

Purpose: continue the internal Terminal-Bench 2.1 campaign without relying on this long chat thread.
This is an internal validation pass only: `k=1`, no leaderboard submission, and no frontier-model
expansion unless Forge explicitly changes scope.

## Immediate state

Local checkout:

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec
git status --short --branch
```

Current branch:

```text
codex/benchmark-visible-tests-gate
```

Open PR:

- PR: https://github.com/navneetprabhakar/executespec/pull/275
- Base: `benchmark`
- Head: `codex/benchmark-visible-tests-gate`
- Status at creation: open, mergeable, CI started

Commits currently ahead of `origin/benchmark`:

```text
ebaa611e3 Use OpenRouter reported costs
e18944b54 Add Sonnet TB21 performance handoff
5537f4ce0 Add benchmark bridge resume mode
217880227 Tune Terminal-Bench worker wall clock
```

Untracked local files intentionally not staged in the cost PR:

```text
infra/benchmark/TB21-DEEPSEEK-PRO-VS-FLASH-RCA-2026-07-03.md
website/images/linkedin-progress/
website/press-kit/linkedin-progress/
```

Do not accidentally include these in unrelated commits.

## Hard operational constraint

Do not stop, restart, deploy, or kill benchmark jobs unless Forge explicitly asks.

Reason: a backend restart during active Harbor execution already created contaminated `result.json`
rows and auth-failure rows. Those were cleaned once. Repeating that during the active GLM / Pro runs
will make the results harder to trust.

Safe actions while runs are active:

- read VM health,
- read DB benchmark rows,
- read Harbor job directories,
- inspect logs,
- open/merge PRs if Forge chooses,
- prepare deployment instructions.

Unsafe actions while runs are active:

- restarting the backend,
- rerunning Terraform/app deploy,
- killing Harbor launchers,
- cleaning result directories,
- starting extra models without checking VM headroom and attribution.

## Benchmark VM

Connect:

```bash
CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.12 \
  gcloud compute ssh benchmark-instance --zone us-east4-a
```

Latest read-only VM health snapshot from this handoff:

```text
app jar: /opt/app/releases/app-217880227d22bf5ac567a8c4e46d1b7bb03d6a77.jar
load average: 2.83, 3.45, 3.91
RAM: 62 GiB total, 5.6 GiB used, 56 GiB available
swap: none
```

The deployed app already contains the worker wall-clock timeout fix at SHA:

```text
217880227d22bf5ac567a8c4e46d1b7bb03d6a77
```

Expected TB timeout config in deployed JAR:

```yaml
terminal-bench:
  worker-wall-clock-seconds: 1260
```

## Secret handling

Do not paste raw secrets into chat or tracked docs.

Runtime app env on VM:

```bash
sudo cat /opt/app/executespec.env
```

Benchmark admin env:

```bash
cat /home/admin_executespec_ai/swebench-bench/.espec-admin.env
```

For DB access, source credentials from `/opt/app/executespec.env` instead of hardcoding them.

## Current benchmark rows

Read-only query used for this handoff:

```bash
PGPASSWORD=... psql -h localhost -U ... -d executespec -F '|' -Atc \
  "select id, suite, model, status, resolved_tasks, total_tasks,
          round(cost_usd::numeric, 2), input_tokens, output_tokens, created_at
     from benchmark_run
    order by created_at desc
    limit 10;"
```

Latest rows at handoff time:

| Model | In-app run id | Status | Resolved | Cost shown | Input tokens | Output tokens | Created UTC |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| DeepSeek V4 Pro native | `7744b918-2d13-4d0d-8755-944731abd407` | `RUNNING` | `9/89` | `$0.54` | `13,015,335` | `367,565` | `2026-07-03 13:43:31` |
| GLM 5.2 OpenRouter | `945b85ac-2c18-4f02-a89b-0af3fabc4e82` | `RUNNING` | `19/89` | `$44.82` | `48,405,128` | `721,258` | `2026-07-03 12:20:51` |
| Sonnet 4.6 | `c288a819-ffba-4571-b3f3-8b41cd5cfd62` | `DONE` | `42/89` | `$136.02` | `32,334,412` | `1,362,329` | `2026-07-03 10:53:24` |
| DeepSeek V4 Pro native | `d0e14512-48dc-4c72-a868-bfa306761a9a` | `DONE` | `49/89` | `$2.88` | `71,169,361` | `1,798,857` | `2026-07-03 08:26:04` |
| DeepSeek V4 Flash native | `e8b23c89-9750-4c86-82c2-09dcd89c8c24` | `DONE` | `46/89` | `$1.53` | `88,003,628` | `2,272,229` | `2026-07-03 03:25:56` |
| DeepSeek V4 Flash native | `8113ed10-9928-4178-8807-077e3879cc03` | `DONE` | `43/89` | `$1.29` | `78,869,071` | `1,834,725` | `2026-07-02 23:42:19` |
| DeepSeek V4 Flash native | `cc39e727-9245-4966-b4e0-86d1e77b4699` | `DONE` | `48/89` | `$1.36` | `84,023,282` | `1,965,461` | `2026-07-02 18:48:39` |

Active Harbor job dirs:

```bash
GLM_JOB=/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1220-glm-5-2-openrouter-c4
PRO_JOB=/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1340-deepseek-v4-pro-native-c3-1260
```

Completed Sonnet job dir:

```bash
SONNET_JOB=/home/admin_executespec_ai/es-tb-jobs/tb21-20260703-1053-claude-sonnet-4-6-c4
```

## What changed this session

### Timeout and resume fixes

- Deployed app SHA `217880227d22bf5ac567a8c4e46d1b7bb03d6a77`.
- Worker wall-clock moved to `1260s`, leaving buffer below Harbor's `1350s` agent timeout.
- Bridge resume support added:
  - `--run-id`
  - `--resume-existing`
- Bridge resume script was copied to both benchmark VM locations:
  - `/home/admin_executespec_ai/executespec/infra/benchmark/tb/tb-bench-bridge.py`
  - `/home/admin_executespec_ai/tbench/tb-bench-bridge.py`

### Handoff docs

- `infra/benchmark/TB21-SONNET-PERFORMANCE-HANDOFF-2026-07-03.md`
  - Sonnet 4.6 underperformance handoff.
  - Treats `42/89` as a harness-quality issue until disproven.
- `infra/benchmark/TB21-OPERATIONAL-NOTES.md`
  - Living operational notes for TB 2.1 campaign.

### OpenRouter GLM cost accounting fix

Problem observed:

- GLM 5.2 OpenRouter was showing about `$44+`.
- OpenRouter user-visible spend expectation was about `$21+`.
- DB showed high cached-token counts for GLM, but local cost tracking charged cached input at the
  static matrix rate because the YAML row has `cached-input-per-1m = input-per-1m`.

Fix in PR #275:

- OpenRouter requests now send `usage.include=true`.
- `OpenAiNativeToolClient` parses:
  - `usage.cost` -> `provider_reported_cost_usd`
  - `usage.cost_details.upstream_inference_cost` -> `provider_upstream_cost_usd`
- `RunCostTracker` uses `provider_reported_cost_usd` only when `provider=openrouter`.
- Direct providers keep YAML pricing.

Validation run locally:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn -q -Dtest=RunCostTrackerProviderReportedCostTest,RunCostTrackerReasoningTest,RunCostTrackerCachePricingTest test

JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q spotless:check
```

Important: this fix is not deployed yet and does not retroactively correct the active GLM row.
Handle cost adjustment after the active run finishes.

## Current benchmark interpretation

DeepSeek Pro:

- Latest clean completed Pro row is `49/89` at `$2.88`.
- This is the best current full-89 internal row in the visible DB snapshot.
- Compare it with previous Harbor job links separately if doing historical regression analysis;
  the VM itself has no older history beyond the new benchmark environment.

DeepSeek Flash:

- Comparable recent one-pass rows:
  - `48/89` previous
  - `43/89` gatefix
  - `46/89` timeout-fix rerun
- Flash improved from gatefix but is still below the earlier `48/89`.

Sonnet 4.6:

- Current row: `42/89`.
- Forge believes same model/harness class should be around `60-63%` elsewhere.
- Treat this as a serious harness gap of roughly `+11` to `+14` tasks, not minor variance.
- See `TB21-SONNET-PERFORMANCE-HANDOFF-2026-07-03.md`.

GLM 5.2:

- Active row cost is inflated by pre-fix accounting.
- Do not judge final cost from current DB row without post-run adjustment.
- Score can still be used if the run finishes cleanly and no Harbor/setup contamination appears.

## Next-session first actions

1. Re-check PR #275 CI and merge status.

```bash
gh pr view 275 --json number,title,url,baseRefName,headRefName,state,isDraft,mergeable,statusCheckRollup
```

2. Do not deploy PR #275 while active Harbor jobs are running unless Forge explicitly accepts the
   contamination risk.

3. Poll active benchmark status read-only.

```bash
CLOUDSDK_PYTHON=/opt/homebrew/bin/python3.12 \
  gcloud compute ssh benchmark-instance --zone us-east4-a --command '
    uptime
    free -h
    PGPASSWORD=... psql -h localhost -U ... -d executespec -F "|" -Atc "
      select id, model, status, resolved_tasks, total_tasks, round(cost_usd::numeric, 2),
             input_tokens, output_tokens, created_at
        from benchmark_run
       order by created_at desc
       limit 10;"
  '
```

Use sourced DB credentials in the real command.

4. When GLM and Pro finish, produce a side-by-side table:

- resolved / total,
- cost shown,
- adjusted OpenRouter GLM cost if applicable,
- token totals,
- timeout count,
- infrastructure/setup failure count,
- task-level pass/fail deltas against Flash/Pro/Sonnet where useful.

5. Only after current active runs finish:

- decide whether to merge and deploy PR #275,
- correct the GLM cost row or annotate it as pre-fix-inflated,
- run remaining approved models if still needed.

## Known traps

- Harbor has no true live pause for an in-flight trial.
- If a backend deploy is unavoidable, stop/resume the bridge carefully and use:

```bash
tb-bench-bridge.py --run-id <benchmark_run_id> --resume-existing
```

- Completed Harbor trial dirs with `result.json` should be reused; active incomplete dirs can rerun.
- Do not compare setup failures as model failures.
- Do not mix two models into one Harbor job.
- Keep separate job dirs and separate in-app benchmark run ids for attribution.
- Do not trust GLM cost rows generated before PR #275 is deployed.

## Source files most likely to matter next

```text
infra/benchmark/scripts/run-tb.sh
infra/benchmark/scripts/run-tb-internal-once-matrix.sh
infra/benchmark/tb/tb-bench-bridge.py
infra/benchmark/tb/es_harbor_agent.py
src/main/java/com/navneet/executespec/terminalbench/
src/main/java/com/navneet/executespec/agent/LlmRouter.java
src/main/java/com/navneet/executespec/agent/OpenAiNativeToolClient.java
src/main/java/com/navneet/executespec/cost/RunCostTracker.java
src/test/java/com/navneet/executespec/cost/RunCostTrackerProviderReportedCostTest.java
```

