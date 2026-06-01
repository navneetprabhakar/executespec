#!/usr/bin/env bash
# Sync eval results (manifests, scoring JSONs, set files, campaign logs, patches)
# to a GCS bucket so they survive `terraform destroy`.
#
#   sync-results.sh gs://bucket [prefix]
set -uo pipefail

BUCKET="${1:?usage: sync-results.sh gs://bucket [prefix]}"
PREFIX="${2:-$(date +%Y%m%d-%H%M%S)}"
BENCH_DIR="${BENCH_DIR:-$HOME/swebench-bench}"

cd "$BENCH_DIR" || { echo "FATAL: BENCH_DIR $BENCH_DIR missing"; exit 1; }
DEST="${BUCKET%/}/$PREFIX"
echo "[sync] → $DEST"

# small text artefacts (manifests / scoring reports / sets / campaign logs)
gsutil -m cp core-codegen-*.manifest.jsonl executespec-*.json set*.txt full*.txt campaign-*.log "$DEST/" 2>/dev/null || true
# the generated patches (runs/<instance>-<ts>/submission.patch + preds.json)
gsutil -m rsync -r runs "$DEST/runs" 2>/dev/null || true

echo "[sync] done → $DEST"
echo "  pull back later with:  gsutil -m rsync -r $DEST ./eval-results-$PREFIX"
