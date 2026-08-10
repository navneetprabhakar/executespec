#!/usr/bin/env bash
# Capture immutable historical rollback controls after the candidate release exposes the
# SUPER_ADMIN-only qualification-evidence endpoint. This script never submits a run or calls a model.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLAN="${BUILD_QUAL_PLAN:-${SCRIPT_DIR}/build-qualification/paired-build-v1.json}"
BASE_URL="${BUILD_QUAL_CANDIDATE_URL:-https://app.executespec.ai}"
SUITE_ID="$(jq -r '.suiteId' "$PLAN")"
RESULT_ROOT="${BUILD_QUAL_RESULT_ROOT:-${SCRIPT_DIR}/build-qualification/results/${SUITE_ID}}"

[[ "$BASE_URL" == "https://app.executespec.ai" ]] || {
  echo "BUILD_QUAL_CANDIDATE_URL must be https://app.executespec.ai" >&2
  exit 2
}
[[ -n "${EMAIL:-}" && -n "${PASSWORD:-}" ]] || {
  echo "EMAIL and PASSWORD are required" >&2
  exit 2
}

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
curl -fsS -X POST "${BASE_URL}/api/auth/login" -H 'Content-Type: application/json' \
  --data "$(jq -cn --arg email "$EMAIL" --arg password "$PASSWORD" '{email:$email,password:$password}')" \
  -o "${tmp_dir}/login.json"
access_token="$(jq -er '.accessToken // .token' "${tmp_dir}/login.json")"

control_release="$(jq -r '.arms[] | select(.id=="control") | .releaseSha' "$PLAN")"
case_id="$(jq -r '.cases[0].id' "$PLAN")"
spec_sha="$(jq -r '.cases[0].specSha256' "$PLAN")"

while IFS=$'\t' read -r provider model run_id; do
  out_dir="${RESULT_ROOT}/control/${provider}-${model}/${case_id}"
  mkdir -p "$out_dir"
  curl -fsS "${BASE_URL}/api/runs/${run_id}" -H "Authorization: Bearer ${access_token}" \
    -o "${out_dir}/run-detail.json"
  curl -fsS "${BASE_URL}/api/runs/${run_id}/cost" -H "Authorization: Bearer ${access_token}" \
    -o "${out_dir}/cost.json"
  attempt_id="$(jq -er '.workspaceGenerationAttempts[0].attemptId' "${out_dir}/run-detail.json")"
  curl -fsS "${BASE_URL}/api/admin/workspace-generation-attempts/${attempt_id}/qualification-evidence" \
    -H "Authorization: Bearer ${access_token}" -o "${out_dir}/attempt-evidence.json"

  jq -S '{runId,status,tasks:[.tasks[]|{taskId,status,provider,model,failureReason}]}' \
    "${out_dir}/run-detail.json" > "${out_dir}/deterministic-failure-evidence.json"
  failure_evidence_sha="$(shasum -a 256 "${out_dir}/deterministic-failure-evidence.json" | awk '{print $1}')"
  failed_tests="$(jq -er '[.tasks[].failureReason // "" | capture("(?<failed>[0-9]+) of [0-9]+ tests FAILED").failed | tonumber] | max' "${out_dir}/run-detail.json")"
  candidate="$(jq -r '.candidateGeneration // empty' "${out_dir}/attempt-evidence.json")"
  if [[ -n "$candidate" ]]; then candidate_kind="FROZEN_GENERATION"; else candidate_kind="LEGACY_REJECTED_DIFF_ONLY"; fi
  unresolved="$(jq '[.workspaceGenerationAttempts[] | select(.state != "ACCEPTED" and .state != "ROLLED_BACK")] | length' "${out_dir}/run-detail.json")"

  jq -cn \
    --arg arm control --arg release "$control_release" --arg caseId "$case_id" \
    --arg specSha "$spec_sha" --arg runId "$run_id" --arg reqProvider "$provider" \
    --arg reqModel "$model" --arg provider "$(jq -r '.effectiveProvider // .requestedProvider // .aiProvider' "${out_dir}/run-detail.json")" \
    --arg effectiveModel "$(jq -r '.effectiveModel // .requestedModel // .workerModel' "${out_dir}/run-detail.json")" \
    --arg terminal "$(jq -r '.status' "${out_dir}/run-detail.json")" \
    --arg prior "$(jq -r '.priorAcceptedGeneration' "${out_dir}/attempt-evidence.json")" \
    --arg candidateKind "$candidate_kind" --arg candidate "$candidate" \
    --arg rejected "$(jq -r '.rejectedDiffDigest' "${out_dir}/attempt-evidence.json")" \
    --arg restored "$(jq -r '.acceptedWorkspaceGeneration' "${out_dir}/run-detail.json")" \
    --arg failureSha "$failure_evidence_sha" \
    --argjson cost "$(jq '.costUsd' "${out_dir}/cost.json")" --argjson unresolved "$unresolved" \
    --argjson failedTests "$failed_tests" \
    '{qualificationArm:$arm,releaseSha:$release,case:$caseId,specSha256:$specSha,runId:$runId,
      requestedProvider:$reqProvider,requestedModel:$reqModel,provider:$provider,model:$effectiveModel,
      costUsd:$cost,unresolvedWorkspaceGenerationAttemptCount:$unresolved,terminalStatus:$terminal,
      priorAcceptedWorkspaceGeneration:$prior,candidateEvidenceKind:$candidateKind,
      candidateWorkspaceGeneration:(if $candidate=="" then null else $candidate end),
      rejectedDiffSha256:$rejected,rollbackRestoredGeneration:$restored,
      latestWorkspaceGenerationAttemptState:"ROLLED_BACK",deterministicFailureSource:"DURABLE_TASK_SMOKE",
      deterministicTestsExecuted:true,deterministicTestsFailed:$failedTests,
      deterministicFailureEvidenceSha256:$failureSha}' > "${out_dir}/completed-runs.jsonl"

  jq -n --arg tuple "control/${provider}/${model}/${case_id}" --arg runId "$run_id" \
    --arg kind "$candidate_kind" --arg rejected "$(jq -r '.rejectedDiffDigest' "${out_dir}/attempt-evidence.json")" \
    --arg diffPath "${out_dir}/attempt-evidence.json" \
    '{tuple:$tuple,runId:$runId,verdict:"REVIEW_REQUIRED",candidateEvidenceKind:$kind,
      rejectedDiffSha256:$rejected,rejectedDiffEvidencePath:$diffPath,
      notes:"Review rejectedDiffJson against the frozen spec, then create reviews/<route>/review.json."}' \
    > "${out_dir}/review-template.json"
done < <(jq -r '.routes[] as $route | .arms[] | select(.id=="control") |
  [$route.provider,$route.model,.runIdsByRoute[($route.provider+"/"+$route.model)]] | @tsv' "$PLAN")

echo "Captured provider-free historical control evidence under ${RESULT_ROOT}/control"
echo "Manual rejected-diff reviews are still required; no run was submitted and no model was called."
