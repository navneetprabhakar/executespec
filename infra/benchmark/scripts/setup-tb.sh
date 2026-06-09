#!/bin/bash
# setup-tb.sh — one-time Terminal-Bench harness setup on the benchmark VM (or a fresh laptop).
# Runs AFTER startup.sh (which installed Docker + Java 21 + Maven + Postgres + Redis).
# Creates ~/tbench = harbor 0.13.1 (uv venv, py3.12) + the es_harbor_agent from the repo.
set -euo pipefail
REPO="${REPO:-$HOME/executespec}"
TB_DIR="${TB_DIR:-$HOME/tbench}"

# uv (fast Python pkg manager — how harbor was installed)
if ! command -v uv >/dev/null 2>&1; then curl -LsSf https://astral.sh/uv/install.sh | sh; fi
export PATH="$HOME/.local/bin:$PATH"

mkdir -p "$TB_DIR"; cd "$TB_DIR"
[ -d .venv ] || uv venv --python 3.12
uv pip install "harbor==0.13.1"

# the ExecuteSpec remote-brain agent (version-controlled in the repo)
cp "$REPO/infra/benchmark/tb/es_harbor_agent.py" "$TB_DIR/es_harbor_agent.py"

echo "✅ TB harness ready in $TB_DIR (harbor $(./.venv/bin/harbor --version 2>/dev/null || echo 0.13.1) + es_harbor_agent)."
echo "   Dataset auto-caches to ~/.cache/harbor on first run (89 tasks)."
echo "   Run a k=5 eval:  GCS_BUCKET=gs://<bucket> bash $REPO/infra/benchmark/scripts/run-tb.sh"
