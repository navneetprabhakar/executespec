#!/usr/bin/env bash
#
# Authorization-gated executor for scripts/build-qualification/paired-build-v1.json.
# Validation and --dry-run are provider-free. --execute performs paid BUILD submissions and must
# only be used after the user has separately authorized the candidate release and two $5 runs.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLAN="${BUILD_QUAL_PLAN:-${SCRIPT_DIR}/build-qualification/paired-build-v1.json}"
MODE="dry-run"
case "${1:-}" in
  ""|--dry-run) MODE="dry-run" ;;
  --execute) MODE="execute" ;;
  *)
    echo "usage: $0 [--dry-run|--execute]" >&2
    exit 2
    ;;
esac

node "${SCRIPT_DIR}/validate-paired-build-qualification.mjs" "$PLAN" --authorized-preflight

if [[ "$MODE" == "execute" ]]; then
  [[ -n "${BUILD_QUAL_AUTHORIZATION_ID:-}" ]] || {
    echo "BUILD_QUAL_AUTHORIZATION_ID is required for paid execution" >&2
    exit 2
  }
  [[ -n "${EMAIL:-}" && -n "${PASSWORD:-}" ]] || {
    echo "EMAIL and PASSWORD are required for paid execution" >&2
    exit 2
  }
fi

SUITE_ID="$(jq -r '.suiteId' "$PLAN")"
RESULT_ROOT="${BUILD_QUAL_RESULT_ROOT:-${SCRIPT_DIR}/build-qualification/results/${SUITE_ID}}"

while IFS=$'\t' read -r route run_id; do
  printf 'control: historical immutable evidence\n  %s, runId=%s, expected=ROLLED_BACK\n' "$route" "$run_id"
done < <(jq -r '.arms[] | select(.id=="control") | .runIdsByRoute | to_entries[] | [.key,.value] | @tsv' "$PLAN")

if [[ "$MODE" == "execute" ]]; then
  node "${SCRIPT_DIR}/validate-paired-build-results.mjs" "$PLAN" "$RESULT_ROOT" --controls-only
fi

while IFS=$'\t' read -r arm base_url_env release_sha_env; do
  base_url="${!base_url_env}"
  release_sha="${!release_sha_env}"
  while IFS=$'\t' read -r provider model cap; do
    while IFS= read -r case_id; do
      out_dir="${RESULT_ROOT}/${arm}/${provider}-${model}/${case_id}"
      printf '%s\n' \
        "${arm}: ${base_url} @ ${release_sha}" \
        "  ${provider}/${model}, case=${case_id}, cap=\$${cap}, output=${out_dir}"
      if [[ "$MODE" == "execute" ]]; then
        BASE_URL="$base_url" OUT_DIR="$out_dir" PROVIDER="$provider" MODEL="$model" \
          USER_COST_CAP="$cap" RELEASE_SHA="$release_sha" QUALIFICATION_ARM="$arm" \
          "${SCRIPT_DIR}/build-eval.sh" --provider "$provider" --model "$model" --case "$case_id"
      fi
    done < <(jq -r '.cases[].id' "$PLAN")
  done < <(jq -r '.routes[] | [.provider,.model,(.safeMaxUsdPerRun|tostring)] | @tsv' "$PLAN")
done < <(jq -r '.arms[] | select(.source=="LIVE_CANDIDATE") | [.id,.baseUrlEnv,.releaseShaEnv] | @tsv' "$PLAN")

if [[ "$MODE" == "dry-run" ]]; then
  echo "Dry run only: no authentication, deployment, provider call, or BUILD submission occurred."
fi
