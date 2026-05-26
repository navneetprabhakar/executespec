#!/usr/bin/env bash
#
# BUILD Eval Harness — RL-2026-05-041 (audit risk #1: "the measured thing isn't the sold thing").
#
# The product's PRIMARY path is BUILD (greenfield spec → PR). Its only metric today is
# scripts/v2-eval.sh's accuracyScore, which is LLM-judged (the system grading itself). This harness
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
OUT_DIR="${SCRIPT_DIR}/build-eval-results"
WORKSPACE_ROOT="${ROOT_DIR}/workspace"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-revalidate-ent@test.local}"
PASSWORD="${PASSWORD:-Revalidate1!}"
PROVIDER="${PROVIDER:-anthropic}"
TIMEOUT="${TIMEOUT:-1200}"
ONLY_CASE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider) PROVIDER="$2"; shift 2 ;;
    --case)     ONLY_CASE="$2"; shift 2 ;;
    --timeout)  TIMEOUT="$2"; shift 2 ;;
    -h|--help)  sed -n '3,33p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }

api_provider() { case "$1" in gemini) echo GOOGLE ;; *) echo "$1" | tr '[:lower:]' '[:upper:]' ;; esac; }

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
  log "submit  $id (band=$band, provider=$PROVIDER)"

  local body run_id
  body=$(jq -n --arg req "$(cat "$spec_file")" --arg prov "$(api_provider "$PROVIDER")" \
    '{userRequest:$req, skipApproval:true, executionMode:"PROTOTYPE", aiProvider:$prov}')
  run_id=$(curl -s -X POST "$BASE_URL/api/runs" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$body" | jq -r '.runId // empty')
  [[ -z "$run_id" ]] && { log "submit $id FAILED"; echo "$id,$band,SUBMIT_FAILED,0,0"; return 0; }
  log "  $id runId=$run_id"

  # Poll to terminal.
  local elapsed=0 status=""
  while [[ $elapsed -lt $TIMEOUT ]]; do
    status=$(curl -s "$BASE_URL/api/runs/$run_id" -H "Authorization: Bearer $TOKEN" | jq -r '.status // ""')
    case "$status" in COMPLETE|PARTIAL|FAILED|CANCELLED) break ;; esac
    sleep 5; elapsed=$((elapsed + 5))
  done
  log "  $id terminal=$status (${elapsed}s)"

  # Locate the generated workspace + run OUR held-out acceptance tests against it.
  local ws="${WORKSPACE_ROOT}/${run_id}"
  if [[ ! -d "$ws" ]]; then log "  $id workspace gone — score 0"; echo "$id,$band,NO_WORKSPACE,0,0"; return 0; fi

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
