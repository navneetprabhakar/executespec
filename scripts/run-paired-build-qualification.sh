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

assert_candidate_release_ready() {
  local base_url="$1" expected_sha="$2" login_json token release_json flags_json
  local flag_value running_sha authority
  login_json="$(curl -fsS -X POST "${base_url}/api/auth/login" -H 'Content-Type: application/json' \
    --data "$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" \
      '{email:$email,password:$password}')")"
  token="$(jq -er '.accessToken // .token' <<<"$login_json")"
  release_json="$(curl -fsS "${base_url}/api/admin/release-identity" \
    -H "Authorization: Bearer ${token}")"
  running_sha="$(jq -er 'select(.schemaVersion==1) | .runningSha' <<<"$release_json")"
  authority="$(jq -er 'select(.schemaVersion==1) | .authority' <<<"$release_json")"
  [[ "$running_sha" == "$expected_sha" ]] || {
    echo "Refusing paid qualification: running SHA ${running_sha:-missing} != ${expected_sha}" >&2
    return 1
  }
  [[ "$authority" == "DEPLOYMENT_HOST:/opt/app/RUNNING_SHA" ]] || {
    echo "Refusing paid qualification: unrecognized release authority ${authority:-missing}" >&2
    return 1
  }
  flags_json="$(curl -fsS "${base_url}/api/admin/feature-flags" \
    -H "Authorization: Bearer ${token}")"
  flag_value="$(jq -er '.[] | select(.flagKey=="build.shared-code-change-loop.enabled") | .flagValue' \
    <<<"$flags_json")"
  [[ "$flag_value" == "true" ]] || {
    echo "Refusing paid qualification: build.shared-code-change-loop.enabled is ${flag_value:-missing}" >&2
    return 1
  }
  BUILD_QUAL_RELEASE_IDENTITY_AUTHORITY="$authority"
}

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
        # Recheck immediately before every paid submission. A candidate result is not shared-kernel
        # evidence if the global rollout flag is off, even when every held-out test later passes.
        assert_candidate_release_ready "$base_url" "$release_sha"
        BASE_URL="$base_url" OUT_DIR="$out_dir" PROVIDER="$provider" MODEL="$model" \
          USER_COST_CAP="$cap" RELEASE_SHA="$release_sha" QUALIFICATION_ARM="$arm" \
          BUILD_SHARED_KERNEL_ENABLED=true \
          RELEASE_IDENTITY_AUTHORITY="$BUILD_QUAL_RELEASE_IDENTITY_AUTHORITY" \
          "${SCRIPT_DIR}/build-eval.sh" --provider "$provider" --model "$model" --case "$case_id"
      fi
    done < <(jq -r '.cases[].id' "$PLAN")
  done < <(jq -r '.routes[] | [.provider,.model,(.safeMaxUsdPerRun|tostring)] | @tsv' "$PLAN")
done < <(jq -r '.arms[] | select(.source=="LIVE_CANDIDATE") | [.id,.baseUrlEnv,.releaseShaEnv] | @tsv' "$PLAN")

if [[ "$MODE" == "dry-run" ]]; then
  echo "Dry run only: no authentication, deployment, provider call, or BUILD submission occurred."
fi
