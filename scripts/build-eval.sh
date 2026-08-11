#!/usr/bin/env bash
#
# BUILD Eval Harness — RL-2026-05-041 (audit risk #1: "the measured thing isn't the sold thing").
#
# The product's PRIMARY path is BUILD (greenfield spec → PR). Its only metric today is
# scripts/v2-eval.sh's verifiedScore, which is deterministic/hybrid. The legacy accuracyScore is
# retained only as a diagnostic because it is LLM-judged (the system grading itself). This harness
# is the BUILD analog of the official SWE-bench methodology: a BLIND, HELD-OUT-TEST eval.
#
#   1. Run the REAL production BUILD pipeline blind (spec only) via POST /api/runs.
#   2. Take the generated workspace (workspace/<runId>/).
#   3. Run OUR hidden acceptance tests (authored by us, NOT the worker) against it.
#   4. Score = % of held-out acceptance tests passing. Objective, deterministic, $0 at scoring.
#
# This is the credibility difference vs. the verified-score TESTS slot (which runs the WORKER's own
# tests = self-consistency). Here we run OUR tests = correctness vs. the spec.
#
# Runs locally against a local backend (same machine → the workspace is on local disk). Each case
# lives in scripts/build-eval-cases/<id>/ as { spec.md, band, acceptance/<runner-specific tests> }.
#
# Usage:
#   ./scripts/build-eval.sh                       # all cases, default provider
#   ./scripts/build-eval.sh --provider anthropic  # pin a provider
#   ./scripts/build-eval.sh --case rest-api-low    # a single case
#
# Env: BASE_URL (default http://localhost:8080), EMAIL, PASSWORD, TIMEOUT (default 1200).
#
# STATUS: harness skeleton (RL-2026-05-041). Ships with one worked sample case (rest-api-low). Grow
# the corpus to 10–15 cases across LOW/MED/HIGH (REST API, CLI, small web app), 3–5 hidden tests
# each. The harness — submit → blind-build → run-held-out → score — is the deliverable; the corpus
# accretes over time.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CASES_DIR="${SCRIPT_DIR}/build-eval-cases"
OUT_DIR="${OUT_DIR:-${SCRIPT_DIR}/build-eval-results}"
WORKSPACE_ROOT="${ROOT_DIR}/workspace"
mkdir -p "$OUT_DIR"
# Keep a self-contained archive for every completed BUILD run.  The held-out evaluator must not
# depend on this VM's ephemeral workspace (or on a later GCS cleanup window).
ARTIFACT_DIR="${OUT_DIR}/artifacts"
mkdir -p "$ARTIFACT_DIR/workspaces"
# One manifest per invocation; archives themselves remain content-addressable by run ID.
: > "${ARTIFACT_DIR}/completed-runs.jsonl"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-revalidate-ent@test.local}"
PASSWORD="${PASSWORD:?PASSWORD environment variable is required}"
PROVIDER="${PROVIDER:-anthropic}"
MODEL="${MODEL:-}"
TIMEOUT="${TIMEOUT:-1200}"
RELEASE_SHA="${RELEASE_SHA:-}"
QUALIFICATION_ARM="${QUALIFICATION_ARM:-}"
USER_COST_CAP="${USER_COST_CAP:-}"
BUILD_SHARED_KERNEL_ENABLED="${BUILD_SHARED_KERNEL_ENABLED:-false}"
RELEASE_IDENTITY_AUTHORITY="${RELEASE_IDENTITY_AUTHORITY:-}"
ATTESTED_RUNNING_SHA="${ATTESTED_RUNNING_SHA:-}"
ATTESTED_ARTIFACT_SHA256="${ATTESTED_ARTIFACT_SHA256:-}"
ATTESTED_DEPLOYMENT_ATTEMPT_ID="${ATTESTED_DEPLOYMENT_ATTEMPT_ID:-}"
RELEASE_IDENTITY_READ_AT="${RELEASE_IDENTITY_READ_AT:-}"
ONLY_CASE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider) PROVIDER="$2"; shift 2 ;;
    --model)    MODEL="$2"; shift 2 ;;
    --case)     ONLY_CASE="$2"; shift 2 ;;
    --timeout)  TIMEOUT="$2"; shift 2 ;;
    -h|--help)  sed -n '3,33p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

assert_candidate_attestation_env() {
  [[ "$QUALIFICATION_ARM" != "candidate" ]] && return 0
  [[ "$BUILD_SHARED_KERNEL_ENABLED" == "true" ]] \
    || { log "refuse candidate: shared-kernel preflight is not positive"; return 1; }
  [[ "$RELEASE_IDENTITY_AUTHORITY" == "PROCESS_STARTUP_ENVIRONMENT" ]] \
    || { log "refuse candidate: process-startup release authority is missing"; return 1; }
  [[ "$ATTESTED_RUNNING_SHA" == "$RELEASE_SHA" && "$ATTESTED_RUNNING_SHA" =~ ^[a-f0-9]{40}$ ]] \
    || { log "refuse candidate: attested running SHA does not match the candidate release"; return 1; }
  [[ "$ATTESTED_ARTIFACT_SHA256" =~ ^[a-f0-9]{64}$ ]] \
    || { log "refuse candidate: attested artifact SHA-256 is missing"; return 1; }
  [[ "$ATTESTED_DEPLOYMENT_ATTEMPT_ID" =~ ^[A-Za-z0-9._:-]{1,128}$ ]] \
    || { log "refuse candidate: attested deployment attempt is missing"; return 1; }
  [[ "$RELEASE_IDENTITY_READ_AT" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z$ ]] \
    || { log "refuse candidate: release identity read timestamp is missing or invalid"; return 1; }
}

safe_extract_zip() {
  local archive="$1" destination="$2"
  python3 "${SCRIPT_DIR}/build-eval-safe-extract.py" "$archive" "$destination"
}

# Download the server's canonical workspace export.  This follows either the local ZIP response
# or the short-lived GCS signed redirect, and validates the archive before it is retained.
download_workspace_archive() {
  local run_id="$1" archive="${ARTIFACT_DIR}/workspace-${run_id}.zip"
  local generation_file="${archive}.generation"
  [[ -s "$archive" && -s "$generation_file" ]] \
    && { unzip -tqq "$archive" >/dev/null 2>&1; return $?; }
  local partial="${archive}.part" headers="${archive}.headers"
  rm -f "$partial"
  if ! curl --fail --silent --show-error --location --max-time 300 \
      --dump-header "$headers" \
      -H "Authorization: Bearer ${TOKEN}" \
      "$BASE_URL/api/runs/${run_id}/download?format=zip" -o "$partial"; then
    rm -f "$partial" "$headers"
    return 1
  fi
  if ! unzip -tqq "$partial" >/dev/null 2>&1; then
    log "  $run_id archive failed ZIP validation"
    rm -f "$partial" "$headers"
    return 1
  fi
  local archive_generation
  archive_generation="$(awk 'BEGIN{IGNORECASE=1} /^X-ExecuteSpec-Workspace-Generation:/ {gsub("\\r", "", $2); print $2}' "$headers" | tail -1)"
  rm -f "$headers"
  [[ "$archive_generation" =~ ^[a-f0-9]{64}$ ]] || {
    log "  $run_id archive response lacked an accepted-generation binding"
    rm -f "$partial"
    return 1
  }
  mv "$partial" "$archive"
  printf '%s\n' "$archive_generation" > "$generation_file"
  return 0
}

api_provider() { case "$1" in gemini) echo GOOGLE ;; *) echo "$1" | tr '[:lower:]' '[:upper:]' ;; esac; }

assert_candidate_attestation_env

TOKEN=$(curl -s -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "$EMAIL" --arg p "$PASSWORD" '{email:$e,password:$p}')" \
  | jq -r '.accessToken // .token // empty')
[[ -z "$TOKEN" || "$TOKEN" == "null" ]] && { echo "Auth failed for $EMAIL" >&2; exit 1; }

# Run one case: submit spec blind → poll to terminal → execute held-out acceptance tests → score.
run_case() {
  local case_dir="$1" id; id="$(basename "$case_dir")"
  local spec_file="${case_dir}/spec.md"
  [[ -f "$spec_file" ]] || { log "skip $id: no spec.md"; return 0; }
  local band; band="$(cat "${case_dir}/band" 2>/dev/null || echo unknown)"
  log "submit  $id (band=$band, provider=$PROVIDER, model=${MODEL:-tier-default})"
  local body run_id
  body=$(jq -n --arg req "$(cat "$spec_file")" --arg prov "$(api_provider "$PROVIDER")" \
    --arg model "$MODEL" --arg costCap "$USER_COST_CAP" \
    '{userRequest:$req, skipApproval:true, executionMode:"PROTOTYPE", runMode:"BUILD", aiProvider:$prov,
      requireConfirmationBeforeExpensiveContinuation:true,
      requireBenchmarkPriorForAutomaticContinuation:false}
      + (if $model == "" then {} else {modelName:$model} end)
      + (if $costCap == "" then {} else {userCostCap:($costCap | tonumber)} end)')
  run_id=$(curl -s -X POST "$BASE_URL/api/runs" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$body" | jq -r '.runId // empty')
  [[ -z "$run_id" ]] && { log "submit $id FAILED"; echo "$id,$band,SUBMIT_FAILED,0,0"; return 0; }
  log "  $id runId=$run_id"

  # Poll to terminal.
  local elapsed=0 status="" run_json="{}"
  while [[ $elapsed -lt $TIMEOUT ]]; do
    run_json=$(curl -s "$BASE_URL/api/runs/$run_id" -H "Authorization: Bearer $TOKEN")
    status=$(jq -r '.status // ""' <<<"$run_json")
    case "$status" in COMPLETE|PARTIAL|FAILED|CANCELLED) break ;; esac
    sleep 5; elapsed=$((elapsed + 5))
  done
  log "  $id terminal=$status (${elapsed}s)"

  # A held-out acceptance pass is a correctness score only for a terminal, successful BUILD.
  # PARTIAL/FAILED/CANCELLED runs may leave a plausible workspace behind; scoring that workspace
  # would turn an incomplete agent run into a misleading product-quality number. Keep the original
  # terminal status in the CSV, but contribute 0/0 so the headline excludes the unscoreable case.
  if [[ "$status" != "COMPLETE" ]]; then
    log "  $id held-out score unavailable: run status is $status"
    echo "$id,$band,$status,0,0"
    return 0
  fi

  # Locate the generated workspace + retain a canonical archive for an independent evaluator.
  local ws="${WORKSPACE_ROOT}/${run_id}"
  local artifact_source="local"
  local archive="${ARTIFACT_DIR}/workspace-${run_id}.zip"
  if [[ -d "$ws" ]]; then
    # Archive generation is best-effort for a still-local workspace: scoring can proceed even if
    # GCS/HTTP is unavailable, while the manifest records that independent replay is unavailable.
    if download_workspace_archive "$run_id"; then
      log "  $id retained evaluator archive: $archive"
    else
      log "  $id archive retention unavailable; scoring local workspace"
      archive=""
    fi
  else
    # A cleaned VM workspace is recoverable when the server has a GCS archive.  Materialize it in
    # the eval output directory so acceptance tests and a later evaluator use the same bytes.
    artifact_source="archive"
    ws="${ARTIFACT_DIR}/workspaces/${run_id}"
    if ! download_workspace_archive "$run_id"; then
      log "  $id workspace and server archive unavailable — score 0"
      echo "$id,$band,NO_ARTIFACT,0,0"
      return 0
    fi
    if ! safe_extract_zip "$archive" "$ws"; then
      log "  $id retained archive failed safe extraction — score 0"
      echo "$id,$band,INVALID_ARTIFACT,0,0"
      return 0
    fi
    log "  $id materialized evaluator workspace from archive: $ws"
  fi

  # acceptance/run.sh receives the generated workspace dir as $1 and must exit 0 only when every
  # hidden acceptance test passes. It prints "PASS n/m" on stdout. Authored by us, never seen by the
  # worker (it lives outside the run workspace).
  local acc="${case_dir}/acceptance/run.sh"
  if [[ ! -x "$acc" ]]; then log "  $id no acceptance/run.sh — score unscoreable"; echo "$id,$band,NO_ACCEPTANCE,0,0"; return 0; fi
  local result passed total
  result="$(bash "$acc" "$ws" 2>/dev/null || true)"
  passed="$(echo "$result" | grep -oE 'PASS [0-9]+/[0-9]+' | grep -oE '[0-9]+' | head -1 || echo 0)"
  total="$(echo "$result"  | grep -oE 'PASS [0-9]+/[0-9]+' | grep -oE '[0-9]+' | tail -1 || echo 0)"
  log "  $id acceptance: ${passed}/${total} held-out tests passed"
  # A small sidecar is intentionally plain JSONL: external adjudicators can consume one completed
  # run without parsing the CSV or depending on the source VM.  Never include credentials/tokens.
  local sha="" archive_generation=""
  [[ -n "$archive" ]] && sha="$(shasum -a 256 "$archive" | awk '{print $1}')"
  [[ -n "$archive" ]] && archive_generation="$(cat "${archive}.generation" 2>/dev/null || true)"
  local effective_provider effective_model cost accepted_generation prior_generation latest_attempt_state unresolved spec_sha
  local accepted_attempt initial_failure_outcome initial_failure_code acceptance_check_count
  effective_provider=$(jq -r '.effectiveProvider // .aiProvider // ""' <<<"$run_json")
  effective_model=$(jq -r '.effectiveModel // ""' <<<"$run_json")
  cost=$(jq -r '.actualCostUsd // 0' <<<"$run_json")
  accepted_generation=$(jq -r '.acceptedWorkspaceGeneration // ""' <<<"$run_json")
  accepted_attempt=$(jq -c '[.workspaceGenerationAttempts[]? | select(.state == "ACCEPTED")][0] // {}' <<<"$run_json")
  prior_generation=$(jq -r '.priorAcceptedGeneration // ""' <<<"$accepted_attempt")
  latest_attempt_state=$(jq -r '.state // ""' <<<"$accepted_attempt")
  initial_failure_outcome=$(jq -r '.initialFailureEvidence.outcome // ""' <<<"$accepted_attempt")
  initial_failure_code=$(jq -r '.initialFailureEvidence.code // ""' <<<"$accepted_attempt")
  acceptance_check_count=$(jq '[.acceptanceChecks[]?] | length' <<<"$accepted_attempt")
  unresolved=$(jq '[.workspaceGenerationAttempts[]? | select(.state != "ACCEPTED" and .state != "ROLLED_BACK")] | length' <<<"$run_json")
  spec_sha="$(shasum -a 256 "$spec_file" | awk '{print $1}')"
  jq -cn \
    --arg arm "$QUALIFICATION_ARM" --arg releaseSha "$RELEASE_SHA" \
    --arg caseId "$id" --arg specSha256 "$spec_sha" --arg runId "$run_id" --arg status "$status" \
    --arg requestedProvider "$PROVIDER" --arg requestedModel "$MODEL" \
    --arg provider "$effective_provider" --arg model "$effective_model" \
    --arg artifactSource "$artifact_source" --arg archive "$archive" \
    --arg archiveSha256 "$sha" --arg workspaceArchiveGeneration "$archive_generation" \
    --arg priorAcceptedWorkspaceGeneration "$prior_generation" \
    --arg acceptedWorkspaceGeneration "$accepted_generation" \
    --arg latestWorkspaceGenerationAttemptState "$latest_attempt_state" \
    --arg initialFailureOutcome "$initial_failure_outcome" \
    --arg initialFailureCode "$initial_failure_code" \
    --argjson acceptanceCheckCount "$acceptance_check_count" \
    --argjson sharedKernelFlagEnabledAtSubmission "$BUILD_SHARED_KERNEL_ENABLED" \
    --arg releaseIdentityAuthority "$RELEASE_IDENTITY_AUTHORITY" \
    --arg attestedRunningSha "$ATTESTED_RUNNING_SHA" \
    --arg attestedArtifactSha256 "$ATTESTED_ARTIFACT_SHA256" \
    --arg attestedDeploymentAttemptId "$ATTESTED_DEPLOYMENT_ATTEMPT_ID" \
    --arg releaseIdentityReadAt "$RELEASE_IDENTITY_READ_AT" \
    --argjson unresolvedWorkspaceGenerationAttemptCount "$unresolved" \
    --argjson costUsd "$cost" --argjson heldOutPassed "$passed" --argjson heldOutTotal "$total" \
    '{qualificationArm:$arm,releaseSha:$releaseSha,case:$caseId,specSha256:$specSha256,runId:$runId,terminalStatus:$status,
      requestedProvider:$requestedProvider,requestedModel:$requestedModel,provider:$provider,model:$model,
      costUsd:$costUsd,artifactSource:$artifactSource,archive:$archive,
      workspaceArchiveSha256:$archiveSha256,workspaceArchiveGeneration:$workspaceArchiveGeneration,
      priorAcceptedWorkspaceGeneration:$priorAcceptedWorkspaceGeneration,
      acceptedWorkspaceGeneration:$acceptedWorkspaceGeneration,
      latestWorkspaceGenerationAttemptState:$latestWorkspaceGenerationAttemptState,
      initialFailureOutcome:$initialFailureOutcome,initialFailureCode:$initialFailureCode,
      acceptanceCheckCount:$acceptanceCheckCount,
      sharedKernelFlagEnabledAtSubmission:$sharedKernelFlagEnabledAtSubmission,
      releaseIdentityAuthority:$releaseIdentityAuthority,
      attestedRunningSha:$attestedRunningSha,
      attestedArtifactSha256:$attestedArtifactSha256,
      attestedDeploymentAttemptId:$attestedDeploymentAttemptId,
      releaseIdentityReadAt:$releaseIdentityReadAt,
      unresolvedWorkspaceGenerationAttemptCount:$unresolvedWorkspaceGenerationAttemptCount,
      heldOutPassed:$heldOutPassed,heldOutTotal:$heldOutTotal,generatedCodeReview:null}' \
    >> "${ARTIFACT_DIR}/completed-runs.jsonl"
  echo "$id,$band,$status,$passed,$total"
}

RESULTS=()
if [[ -n "$ONLY_CASE" ]]; then
  RESULTS+=("$(run_case "${CASES_DIR}/${ONLY_CASE}")")
else
  for d in "${CASES_DIR}"/*/; do [[ -d "$d" ]] && RESULTS+=("$(run_case "$d")"); done
fi

TS="$(date +%Y%m%d-%H%M%S)"
OUT="${OUT_DIR}/build-eval-${TS}.csv"
{ echo "case,band,status,held_out_passed,held_out_total"; printf '%s\n' "${RESULTS[@]}"; } > "$OUT"
log "BUILD eval done → $OUT"
# Headline: BUILD-resolved % = held-out tests passed / total across all cases.
awk -F, 'NR>1{p+=$4; t+=$5} END{ if(t>0) printf "\nBUILD-resolved: %d/%d held-out acceptance tests = %.0f%%\n", p, t, 100*p/t; else print "\nBUILD-resolved: no scoreable cases" }' "$OUT" >&2
