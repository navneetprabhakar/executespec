#!/usr/bin/env bash
# Sharded parallel SWE-bench code-gen runner (runs ON the Linux eval VM; bash 5).
#
# Splits SET_FILE into SHARDS round-robin shards and runs run-core-codegen.sh on
# each shard CONCURRENTLY. The app-side semaphore (swebench.max-concurrent-runs,
# set to 8 by deploy.sh) is the REAL concurrency cap; SHARDS just feeds it — keep
# SHARDS == the semaphore so submits never queue-timeout. Round-robin (not
# contiguous) split so a slow-repo cluster can't bottleneck one shard. Merges the
# per-shard manifests into one for scoring.
#
# Env:
#   PROVIDER     OPENAI | ANTHROPIC | GOOGLE | DEEPSEEK            (required)
#   MODEL        model pin (e.g. gpt-5.4)                          (required)
#   SET_FILE     instance set file name (in BENCH_DIR)            (default set50.txt)
#   SHARDS       parallel shard count                              (default 8)
#   RUN_TAG      manifest/log tag                                  (default <prov>-shard)
#   BENCH_DIR    holds run-core-codegen.sh + sets + .espec-admin.env (default $HOME/swebench-bench)
#   BACKEND_LOG  backend log path (429 detection)                 (default /tmp/backend-reloc.log)
#
# Output: core-codegen-${RUN_TAG}.manifest.jsonl (merged) in BENCH_DIR.
set -uo pipefail

PROVIDER="${PROVIDER:?set PROVIDER}"
MODEL="${MODEL:?set MODEL}"
SET_FILE="${SET_FILE:-set50.txt}"
SHARDS="${SHARDS:-8}"
BENCH_DIR="${BENCH_DIR:-$HOME/swebench-bench}"
BACKEND_LOG="${BACKEND_LOG:-/tmp/backend-reloc.log}"
RUN_TAG="${RUN_TAG:-${PROVIDER,,}-shard}"

cd "$BENCH_DIR" || { echo "FATAL: BENCH_DIR $BENCH_DIR missing"; exit 1; }
[ -f "$SET_FILE" ]            || { echo "FATAL: set file $SET_FILE missing in $BENCH_DIR"; exit 1; }
[ -f run-core-codegen.sh ]   || { echo "FATAL: run-core-codegen.sh missing in $BENCH_DIR"; exit 1; }

# round-robin split → ${RUN_TAG}.shardN.txt
python3 - "$SET_FILE" "$SHARDS" "$RUN_TAG" <<'PY'
import sys
setf, shards, tag = sys.argv[1], int(sys.argv[2]), sys.argv[3]
lines = [l.strip() for l in open(setf) if l.strip()]
buckets = [[] for _ in range(shards)]
for i, l in enumerate(lines):
    buckets[i % shards].append(l)
for s, b in enumerate(buckets):
    with open(f"{tag}.shard{s}.txt", "w") as f:
        f.write("\n".join(b) + ("\n" if b else ""))
print(f"split {len(lines)} instances into {shards} shards")
PY

# launch one runner per non-empty shard. Each runner is serial within its shard;
# SHARDS of them run in parallel. Set every provider count var high so the shard
# file (not the count) is the bound.
pids=()
for s in $(seq 0 $((SHARDS - 1))); do
  sf="${RUN_TAG}.shard${s}.txt"
  [ -s "$sf" ] || continue
  PROVIDERS="$PROVIDER" SET_FILE="$sf" \
    OPENAI_N=99999 ANTHRO_N=99999 GEMINI_N=99999 DEEPSEEK_N=99999 \
    MODEL="$MODEL" RUN_TAG="${RUN_TAG}.s${s}" BACKEND_LOG="$BACKEND_LOG" \
    bash run-core-codegen.sh > "core-codegen-${RUN_TAG}.s${s}.out" 2>&1 &
  pids+=("$!")
  echo "[shard $s] pid $! on $sf ($(wc -l < "$sf" | tr -d ' ') instances)"
done

echo "waiting on ${#pids[@]} shards..."
fail=0
for p in "${pids[@]}"; do wait "$p" || fail=$((fail + 1)); done
echo "shards done (process failures=$fail)"

# merge per-shard manifests
MERGED="core-codegen-${RUN_TAG}.manifest.jsonl"
: > "$MERGED"
for s in $(seq 0 $((SHARDS - 1))); do
  m="core-codegen-${RUN_TAG}.s${s}.manifest.jsonl"
  [ -f "$m" ] && cat "$m" >> "$MERGED"
done
echo "merged manifest: $MERGED ($(wc -l < "$MERGED" | tr -d ' ') entries)"
