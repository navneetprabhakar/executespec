# ProgramBench runner

Adapter that drives [mini-swe-agent](https://github.com/SWE-agent/mini-swe-agent)
against [ProgramBench](https://github.com/facebookresearch/programbench) tasks.

Forks the structure of mini-swe-agent's built-in `swebench.py`. Three real
differences:

1. **Task source** — reads from the locally installed `programbench` package
   (`<install>/data/tasks/<id>/task.yaml`), not from a HuggingFace dataset.
2. **Cleanroom contract** — starts each container with `--network none`, so the
   agent inside the box cannot `curl` the original source. The agent process
   on the host still has internet for LLM API calls.
3. **Submission shape** — instead of capturing a unified diff, the runner
   `docker cp`s `/workspace` out and tars it as `submission.tar.gz` at the
   layout `programbench eval` expects.

This is **step 1** of a two-step benchmarking effort: get a baseline number
from a generic agent loop. Step 2 (separate branch later) wires the same
adapter shape into ExecuteSpec's V2 worker stack so we can measure what our
planner + reviewer + tool design contributes on top.

## Layout

```
scripts/programbench/
├── programbench.yaml        # mini-swe-agent config (system prompt, agent, env, model)
├── programbench_runner.py   # CLI runner
└── README.md                # this file
```

## Setup (one-time, on the inference host)

The runner requires Linux x86_64 (programbench cleanroom images are amd64-only;
QEMU emulation works but is glacially slow).

```bash
# Tools
curl -LsSf https://astral.sh/uv/install.sh | sh

# Isolated venv with both packages
mkdir -p /opt/programbench-bench && cd /opt/programbench-bench
uv venv --python 3.10 .venv
source .venv/bin/activate
uv pip install mini-swe-agent programbench

# API keys (any provider you want to run)
export ANTHROPIC_API_KEY=...
export OPENAI_API_KEY=...
export GEMINI_API_KEY=...
```

## Usage

### Single task (smoke run)

```bash
python /path/to/programbench_runner.py \
  --instance abishekvashok__cmatrix.5c082c6 \
  --output ./out \
  --model anthropic/claude-sonnet-4-5-20250929
```

Then score:

```bash
programbench eval ./out
programbench info ./out          # show summary again later
```

### Subset

```bash
# All easy C tasks, parallel 2
python programbench_runner.py \
  --language c --difficulty easy \
  --workers 2 \
  --output ./out
```

```bash
# First 5 tasks (alphabetical), Anthropic
python programbench_runner.py \
  --slice 0:5 \
  --output ./out \
  --model anthropic/claude-sonnet-4-5-20250929
```

```bash
# Regex filter
python programbench_runner.py \
  --filter '^abishek.*' \
  --output ./out
```

### Provider matrix

Each provider is a separate `--output` dir so scores don't merge:

```bash
for prov in \
    anthropic/claude-sonnet-4-5-20250929 \
    openai/gpt-5 \
    gemini/gemini-3-pro-preview; do
  out_tag=$(echo "$prov" | tr '/' '_')
  python programbench_runner.py \
    --slice 0:20 \
    --model "$prov" \
    --output "./out-${out_tag}"
done
```

## Cost knobs

In `programbench.yaml`:

- `agent.step_limit` (default 500) — max iterations per task
- `agent.cost_limit` (default $5.0) — max LLM spend per task
- `environment.timeout` (default 120s) — max per-shell-command runtime

For cmatrix (small C, "easy"), $5 is generous. For larger Rust/Go tasks
(`ffmpeg`, `chroma`), expect to push `cost_limit` to $15–30 and `step_limit`
to 800+.

## Output layout

After a run, `--output` looks like:

```
out/
├── abishekvashok__cmatrix.5c082c6/
│   ├── submission/                    # raw /workspace capture
│   ├── submission.tar.gz              # what programbench eval reads
│   └── abishekvashok__cmatrix.5c082c6.traj.json
├── exit_statuses_<timestamp>.yaml
└── run.log
```

`programbench eval ./out` writes `<task_id>.eval.json` next to each
`submission.tar.gz` with the test results.

## Status / known caveats

- The runner uses mini-swe-agent v2.2.8's `DefaultAgent` + docker
  Environment. v2 introduced API changes from v1; this code targets v2.
- `network_disabled` for docker is enforced via `run_args: ["--rm",
  "--network", "none"]` in the yaml. Confirm with `docker inspect <cid>`
  if anything seems off.
- mini-swe-agent's docker env uses `--rm` so containers self-clean. We
  also `docker rm -f` defensively in `finally`.
- Disk: each cleanroom image is 100 MB – 2 GB. Pull on demand and
  `docker image prune -a` between batches if disk is tight.
