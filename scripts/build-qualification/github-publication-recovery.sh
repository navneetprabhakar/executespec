#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
output_dir="${1:-${repo_root}/output/build-qualification/github-publication-v399-$(date -u +%Y%m%dT%H%M%SZ)}"
remote_one="${ES_R17_GITHUB_REMOTE_ONE:-https://github.com/adminspec/tsp-solver-demo.git}"
remote_two="${ES_R17_GITHUB_REMOTE_TWO:-https://github.com/navneetprabhakar/executespec-cli.git}"
test_class="PublicationRealGitPartialEffectPostgresTest"
test_report="target/surefire-reports/TEST-com.navneet.executespec.integration.scm.${test_class}.xml"

if [[ -z "${ES_R17_GITHUB_PAT:-}" ]]; then
  echo "ES_R17_GITHUB_PAT is required" >&2
  exit 2
fi

remote_identity() {
  local remote="$1"
  remote="${remote#https://github.com/}"
  remote="${remote#git@github.com:}"
  remote="${remote%.git}"
  printf '%s' "${remote}"
}

repo_one="$(remote_identity "${remote_one}")"
repo_two="$(remote_identity "${remote_two}")"
if [[ ! "${repo_one}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
   [[ ! "${repo_two}" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  echo "R17 remotes must be github.com owner/repository URLs" >&2
  exit 2
fi

mkdir -p "${output_dir}"
cd "${repo_root}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

declare -a cleanup_repositories=()
declare -a cleanup_branches=()
cleanup_verified=true

github_status() {
  local method="$1"
  local endpoint="$2"
  printf 'header = "Authorization: Bearer %s"\n' "${ES_R17_GITHUB_PAT}" |
    curl --config - --silent --show-error --output /dev/null --write-out '%{http_code}' \
      --connect-timeout 10 --max-time 30 \
      --request "${method}" \
      --header "Accept: application/vnd.github+json" \
      --header "X-GitHub-Api-Version: 2022-11-28" \
      "https://api.github.com${endpoint}"
}

github_ref_sha() {
  local repository="$1"
  local branch="$2"
  printf 'header = "Authorization: Bearer %s"\n' "${ES_R17_GITHUB_PAT}" |
    curl --config - --fail --silent --show-error \
      --connect-timeout 10 --max-time 30 \
      --header "Accept: application/vnd.github+json" \
      --header "X-GitHub-Api-Version: 2022-11-28" \
      "https://api.github.com/repos/${repository}/git/ref/heads/${branch}" |
    jq -er '.object.sha'
}

cleanup_refs() {
  local index repository branch encoded status
  for ((index=${#cleanup_branches[@]}-1; index>=0; index--)); do
    repository="${cleanup_repositories[$index]}"
    branch="${cleanup_branches[$index]}"
    encoded="$(jq -rn --arg value "${branch}" '$value|@uri')"
    status="$(github_status DELETE "/repos/${repository}/git/refs/heads/${encoded}")"
    if [[ "${status}" != "204" && "${status}" != "422" && "${status}" != "404" ]]; then
      echo "R17 cleanup delete failed repository=${repository} branch=${branch} status=${status}" >&2
      cleanup_verified=false
      continue
    fi
    status="$(github_status GET "/repos/${repository}/git/ref/heads/${encoded}")"
    if [[ "${status}" != "404" ]]; then
      echo "R17 cleanup verification failed repository=${repository} branch=${branch} status=${status}" >&2
      cleanup_verified=false
    else
      echo "R17_CLEANUP_REF repository=${repository} branch=${branch} state=ABSENT"
    fi
  done
}
trap cleanup_refs EXIT

summary_rows="${output_dir}/provider-evidence.ndjson"
: > "${summary_rows}"

for repetition in 1 2; do
  nonce="$(date -u +%Y%m%dT%H%M%SZ)-$$-${RANDOM}-${repetition}"
  branch_prefix="executespec/r17-${nonce}"
  branch_one="${branch_prefix}-repo-one"
  branch_two="${branch_prefix}-repo-two"
  cleanup_repositories+=("${repo_one}" "${repo_two}")
  cleanup_branches+=("${branch_one}" "${branch_two}")

  log_file="${output_dir}/maven-test-run-${repetition}.log"
  if ! ES_R17_GITHUB_REMOTE_ONE="${remote_one}" \
       ES_R17_GITHUB_REMOTE_TWO="${remote_two}" \
       ES_R17_BRANCH_PREFIX="${branch_prefix}" \
       mvn -q -Pintegration -Dtest="${test_class}" test 2>&1 | tee "${log_file}"; then
    jq -n \
      --arg status FAILED \
      --argjson failedRepetition "${repetition}" \
      --arg logSha256 "$(shasum -a 256 "${log_file}" | awk '{print $1}')" \
      '{schemaVersion:1,status:$status,failedRepetition:$failedRepetition,logSha256:$logSha256}' \
      > "${output_dir}/summary.json"
    exit 1
  fi

  cp "${test_report}" "${output_dir}/TEST-${test_class}-run-${repetition}.xml"
  marker_source="${output_dir}/TEST-${test_class}-run-${repetition}.xml"
  sha_one="$(sed -nE "s/.*R17_PROVIDER_REF remote=GITHUB:${repo_one//\//\\/} branch=${branch_one//\//\\/} sha=([0-9a-f]{40}) state=PUBLISHED.*/\1/p" "${marker_source}" | head -1)"
  sha_two="$(sed -nE "s/.*R17_PROVIDER_REF remote=GITHUB:${repo_two//\//\\/} branch=${branch_two//\//\\/} sha=([0-9a-f]{40}) state=INDETERMINATE.*/\1/p" "${marker_source}" | head -1)"
  if [[ -z "${sha_one}" || -z "${sha_two}" ]]; then
    echo "R17 provider evidence markers missing for repetition ${repetition}" >&2
    exit 1
  fi

  observed_one="$(github_ref_sha "${repo_one}" "${branch_one}")"
  observed_two="$(github_ref_sha "${repo_two}" "${branch_two}")"
  if [[ "${observed_one}" != "${sha_one}" || "${observed_two}" != "${sha_two}" ]]; then
    echo "R17 provider ref mismatch in repetition ${repetition}" >&2
    exit 1
  fi

  jq -cn \
    --argjson repetition "${repetition}" \
    --arg firstRepository "${repo_one}" \
    --arg firstBranch "${branch_one}" \
    --arg firstSha "${sha_one}" \
    --arg secondRepository "${repo_two}" \
    --arg secondBranch "${branch_two}" \
    --arg secondSha "${sha_two}" \
    --arg reportSha256 "$(shasum -a 256 "${marker_source}" | awk '{print $1}')" \
    --arg logSha256 "$(shasum -a 256 "${log_file}" | awk '{print $1}')" \
    '{repetition:$repetition,first:{repository:$firstRepository,branch:$firstBranch,sha:$firstSha,state:"PUBLISHED"},second:{repository:$secondRepository,branch:$secondBranch,sha:$secondSha,state:"INDETERMINATE"},reportSha256:$reportSha256,logSha256:$logSha256}' \
    >> "${summary_rows}"
done

cleanup_refs
cleanup_repositories=()
cleanup_branches=()
if [[ "${cleanup_verified}" != "true" ]]; then
  echo "R17 provider branch cleanup was not fully verified" >&2
  exit 1
fi

jq -s \
  --arg status PASSED \
  --arg scope GITHUB_PROVIDER_REAL_REMOTE_PARTIAL_EFFECT \
  --arg candidateCommit "$(git rev-parse HEAD)" \
  --arg gitDiffSha256 "$(git diff --binary | shasum -a 256 | awk '{print $1}')" \
  '{schemaVersion:1,status:$status,scope:$scope,candidateCommit:$candidateCommit,gitDiffSha256:$gitDiffSha256,repetitions:length,forcedPublisherDeaths:length,providerCredentialsUsed:true,remoteProviderWrite:true,cleanupVerified:true,paidSpendUsd:0,cases:[{id:"FIRST_REPOSITORY_EXACT_OBJECT",result:"PASSED"},{id:"SECOND_REPOSITORY_EFFECT_BEFORE_RECEIPT",result:"PASSED"},{id:"REPLACEMENT_JVM_RECOVERY",result:"PASSED"},{id:"BLIND_REPLAY",result:"BLOCKED"},{id:"REMOTE_REF_CLEANUP",result:"VERIFIED_ABSENT"}],evidence:.}' \
  "${summary_rows}" > "${output_dir}/summary.json"

jq . "${output_dir}/summary.json"
