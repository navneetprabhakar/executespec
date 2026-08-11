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
  local flag_value running_sha artifact_sha256 deployment_attempt_id authority read_at
  login_json="$(curl -fsS -X POST "${base_url}/api/auth/login" -H 'Content-Type: application/json' \
    --data "$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" \
      '{email:$email,password:$password}')")"
  token="$(jq -er '.accessToken // .token' <<<"$login_json")"
  release_json="$(curl -fsS "${base_url}/api/admin/release-identity" \
    -H "Authorization: Bearer ${token}")"
  running_sha="$(jq -er 'select(.schemaVersion==2) | .runningSha | select(test("^[a-f0-9]{40}$"))' <<<"$release_json")"
  artifact_sha256="$(jq -er 'select(.schemaVersion==2) | .artifactSha256 | select(test("^[a-f0-9]{64}$"))' <<<"$release_json")"
  deployment_attempt_id="$(jq -er 'select(.schemaVersion==2) | .deploymentAttemptId | select(test("^[A-Za-z0-9._:-]{1,128}$"))' <<<"$release_json")"
  authority="$(jq -er 'select(.schemaVersion==2) | .authority' <<<"$release_json")"
  read_at="$(jq -er 'select(.schemaVersion==2) | .readAt | select(test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z$"))' <<<"$release_json")"
  [[ "$running_sha" == "$expected_sha" ]] || {
    echo "Refusing paid qualification: running SHA ${running_sha:-missing} != ${expected_sha}" >&2
    return 1
  }
  [[ "$authority" == "PROCESS_STARTUP_ENVIRONMENT" ]] || {
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
  BUILD_QUAL_ATTESTED_RUNNING_SHA="$running_sha"
  BUILD_QUAL_ATTESTED_ARTIFACT_SHA256="$artifact_sha256"
  BUILD_QUAL_ATTESTED_DEPLOYMENT_ATTEMPT_ID="$deployment_attempt_id"
  BUILD_QUAL_RELEASE_IDENTITY_READ_AT="$read_at"
}

login_candidate() {
  local base_url="$1" login_json
  login_json="$(curl -fsS -X POST "${base_url}/api/auth/login" -H 'Content-Type: application/json' \
    --data "$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" \
      '{email:$email,password:$password}')")"
  jq -er '.accessToken // .token' <<<"$login_json"
}

read_shared_kernel_flag() {
  local base_url="$1" token="$2"
  curl -fsS "${base_url}/api/admin/feature-flags" -H "Authorization: Bearer ${token}" \
    | jq -er '.[] | select(.flagKey=="build.shared-code-change-loop.enabled") | .flagValue'
}

set_shared_kernel_flag() {
  local base_url="$1" token="$2" value="$3" updated
  updated="$(curl -fsS -X PUT \
    "${base_url}/api/admin/feature-flags/build.shared-code-change-loop.enabled" \
    -H "Authorization: Bearer ${token}" -H 'Content-Type: application/json' \
    --data "$(jq -cn --arg value "$value" '{value:$value}')")"
  [[ "$(jq -er '.flagValue' <<<"$updated")" == "$value" ]]
  [[ "$(read_shared_kernel_flag "$base_url" "$token")" == "$value" ]]
}

SUITE_ID="$(jq -r '.suiteId' "$PLAN")"
RESULT_ROOT="${BUILD_QUAL_RESULT_ROOT:-${SCRIPT_DIR}/build-qualification/results/${SUITE_ID}}"
mkdir -p "$RESULT_ROOT"

while IFS=$'\t' read -r route run_id; do
  printf 'control: historical immutable evidence\n  %s, runId=%s, expected=ROLLED_BACK\n' "$route" "$run_id"
done < <(jq -r '.arms[] | select(.id=="control") | .runIdsByRoute | to_entries[] | [.key,.value] | @tsv' "$PLAN")

if [[ "$MODE" == "execute" ]]; then
  node "${SCRIPT_DIR}/validate-paired-build-results.mjs" "$PLAN" "$RESULT_ROOT" --controls-only

  QUAL_BASE_URL_ENV="$(jq -r '.arms[] | select(.source=="LIVE_CANDIDATE") | .baseUrlEnv' "$PLAN")"
  QUAL_SHA_ENV="$(jq -r '.arms[] | select(.source=="LIVE_CANDIDATE") | .releaseShaEnv' "$PLAN")"
  QUAL_BASE_URL="${!QUAL_BASE_URL_ENV}"
  QUAL_RELEASE_SHA="${!QUAL_SHA_ENV}"
  QUAL_TOKEN="$(login_candidate "$QUAL_BASE_URL")"
  INITIAL_FLAG_VALUE="$(read_shared_kernel_flag "$QUAL_BASE_URL" "$QUAL_TOKEN")"
  [[ "$INITIAL_FLAG_VALUE" == "false" ]] || {
    echo "Refusing paid qualification: shared BUILD kernel must start false, found ${INITIAL_FLAG_VALUE}" >&2
    exit 1
  }
  FLAG_ENABLED_AT=""
  restore_shared_kernel() {
    local original_status=$? restored="false" final_value="unknown" restored_at
    trap - EXIT
    set +e
    set_shared_kernel_flag "$QUAL_BASE_URL" "$QUAL_TOKEN" false
    cleanup_status=$?
    final_value="$(read_shared_kernel_flag "$QUAL_BASE_URL" "$QUAL_TOKEN" 2>/dev/null)"
    [[ $cleanup_status -eq 0 && "$final_value" == "false" ]] && restored="true"
    restored_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    jq -cn --arg candidateSha "$QUAL_RELEASE_SHA" --arg initialValue "$INITIAL_FLAG_VALUE" \
      --arg enabledAt "$FLAG_ENABLED_AT" --arg restoredAt "$restored_at" \
      --arg finalValue "$final_value" --argjson restored "$restored" \
      '{candidateSha:$candidateSha,initialValue:$initialValue,enabledAt:$enabledAt,
        restoredAt:$restoredAt,finalValue:$finalValue,restored:$restored}' \
      > "${RESULT_ROOT}/shared-kernel-flag-window.json"
    if [[ "$restored" != "true" ]]; then
      echo "CRITICAL: shared BUILD kernel cleanup could not prove false" >&2
      exit 97
    fi
    exit "$original_status"
  }
  trap restore_shared_kernel EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  set_shared_kernel_flag "$QUAL_BASE_URL" "$QUAL_TOKEN" true
  FLAG_ENABLED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
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
          ATTESTED_RUNNING_SHA="$BUILD_QUAL_ATTESTED_RUNNING_SHA" \
          ATTESTED_ARTIFACT_SHA256="$BUILD_QUAL_ATTESTED_ARTIFACT_SHA256" \
          ATTESTED_DEPLOYMENT_ATTEMPT_ID="$BUILD_QUAL_ATTESTED_DEPLOYMENT_ATTEMPT_ID" \
          RELEASE_IDENTITY_READ_AT="$BUILD_QUAL_RELEASE_IDENTITY_READ_AT" \
          "${SCRIPT_DIR}/build-eval.sh" --provider "$provider" --model "$model" --case "$case_id"
      fi
    done < <(jq -r '.cases[].id' "$PLAN")
  done < <(jq -r '.routes[] | [.provider,.model,(.safeMaxUsdPerRun|tostring)] | @tsv' "$PLAN")
done < <(jq -r '.arms[] | select(.source=="LIVE_CANDIDATE") | [.id,.baseUrlEnv,.releaseShaEnv] | @tsv' "$PLAN")

if [[ "$MODE" == "dry-run" ]]; then
  echo "Dry run only: no authentication, deployment, provider call, or BUILD submission occurred."
fi
