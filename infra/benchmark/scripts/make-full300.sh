#!/usr/bin/env bash
# Generate full300.txt — every SWE-bench Lite test instance_id — from the dataset
# JSON already on the box. Used for the full-300 campaign preset.
set -uo pipefail
BENCH_DIR="${BENCH_DIR:-$HOME/swebench-bench}"
cd "$BENCH_DIR" || { echo "FATAL: BENCH_DIR $BENCH_DIR missing"; exit 1; }
[ -f swebench_lite_test.json ] || { echo "FATAL: swebench_lite_test.json missing in $BENCH_DIR"; exit 1; }
python3 - <<'PY'
import json
ids = sorted({r["instance_id"] for r in json.load(open("swebench_lite_test.json"))})
open("full300.txt", "w").write("\n".join(ids) + "\n")
print(f"wrote full300.txt with {len(ids)} instances")
PY
