#!/usr/bin/env bash
#
# Project View E2E driver — provisions a clean test project with 12 repos and
# submits the Launches defined in docs/plans/project-view-test-plan.md.
#
# Phase A: bootstrap (project + 12 repos linked)
# Phase B: warm-up Launches (TC-A1..A3, single-repo)
# Phase C: multi-repo Launches (TC-B1..B4, TC-C1..C2)
# Phase D: filler runs to drive memory compaction (until 20 + 10 more for v2)
# Phase E: chat assertions (TC-F1..F4)
#
# Each phase is independent — pass --phase=A,B,C,D,E to run a subset.
#
# Requires: a running server, ENTERPRISE/MAX user, jq, curl.
#
# Usage:
#   ./scripts/project-view-e2e.sh                       # all phases
#   ./scripts/project-view-e2e.sh --phase=A,B           # bootstrap + warm-up only
#   PROJECT_ID=abc ./scripts/project-view-e2e.sh --phase=D   # add filler runs to existing project
#
# Env:
#   BASE_URL   (default http://localhost:8080)
#   EMAIL      (default admin@executespec.local)
#   PASSWORD   (default admin)
#   GH_OWNER   GitHub owner for test repos (default exspec-test) — must exist + be writable
#   PROJECT_ID (optional — skip Phase A if provided)
#
# Output:
#   scripts/project-view-results/<timestamp>/{project.json,launches.json,memory.json,chat.json}

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${SCRIPT_DIR}/project-view-results/${TS}"
mkdir -p "$OUT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${EMAIL:-admin@executespec.local}"
PASSWORD="${PASSWORD:?PASSWORD environment variable is required}"
GH_OWNER="${GH_OWNER:-exspec-test}"
PHASES="${PHASES:-A,B,C,D,E}"

for arg in "$@"; do
  case "$arg" in
    --phase=*) PHASES="${arg#--phase=}" ;;
  esac
done

REPOS=(
  "auth-svc:spring-boot"
  "users-svc:spring-boot"
  "billing-svc:node-backend"
  "notifications-svc:node-backend"
  "search-svc:python-flask"
  "reports-svc:python-flask"
  "gateway-svc:go"
  "scheduler-svc:go"
  "audit-svc:spring-boot"
  "metrics-svc:node-backend"
  "web-frontend:node-frontend"
  "mobile-shell:node-frontend"
)

# ── auth ──────────────────────────────────────────────────────────────────────
echo ">> login as $EMAIL"
TOKEN="$(curl -fsS -X POST "$BASE_URL/api/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r '.accessToken // .token')"

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "!! login failed" >&2; exit 1
fi
AUTH=( -H "Authorization: Bearer $TOKEN" )

api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$BASE_URL$path" "${AUTH[@]}" \
      -H "Content-Type: application/json" -d "$body"
  else
    curl -fsS -X "$method" "$BASE_URL$path" "${AUTH[@]}"
  fi
}

has_phase() { [[ ",$PHASES," == *",$1,"* ]]; }

# ── Phase A: bootstrap ────────────────────────────────────────────────────────
if has_phase A && [[ -z "${PROJECT_ID:-}" ]]; then
  echo ">> [A] creating project"
  PROJECT_ID="$(api POST /api/projects \
    "{\"name\":\"ProjectViewE2E-${TS}\",\"description\":\"E2E test fixture\",\"aiProvider\":\"OPENAI\"}" \
    | tee "$OUT_DIR/project.json" | jq -r '.id')"
  echo "   project=$PROJECT_ID"

  echo ">> [A] registering and linking 12 repos"
  for entry in "${REPOS[@]}"; do
    name="${entry%%:*}"
    full="$GH_OWNER/$name"
    repo_id="$(api POST /api/repos \
      "{\"scope\":\"ORG\",\"githubRepoFullName\":\"$full\",\"defaultBranch\":\"main\"}" \
      | jq -r '.id')"
    api POST "/api/projects/$PROJECT_ID/repos/$repo_id" >/dev/null
    echo "   linked $full ($repo_id)"
  done
fi

[[ -z "${PROJECT_ID:-}" ]] && { echo "!! PROJECT_ID required for phase B+"; exit 1; }
echo ">> using project $PROJECT_ID"

# Helper: submit a project-scoped run via POST /api/runs with selectedRepoIds.
# Uses PROTOTYPE + skipApproval=true so the plan gate is auto-cleared and the
# run flows through unattended. Triggers RunLedgerAgent on COMPLETE.
launch() {
  local label="$1" requirement="$2" repos_csv="$3"
  echo ">> launch [$label] :: $requirement"
  local repo_ids repo_url
  repo_ids="$(api GET "/api/projects/$PROJECT_ID/repos" \
    | jq -c --arg names "$repos_csv" \
        '[.[] | select((.repo.githubRepoFullName | split("/")[1]) as $n | ($names | split(",")) | index($n)) | .repo.id]')"
  # Pick the first repo as the legacy single-repo URL too (some code paths still read it).
  repo_url="$(api GET "/api/projects/$PROJECT_ID/repos" \
    | jq -r --arg names "$repos_csv" \
        'first(.[] | select((.repo.githubRepoFullName | split("/")[1]) as $n | ($names | split(",") | .[0]) == $n) | "https://github.com/" + .repo.githubRepoFullName + ".git")')"
  local body
  body="$(jq -nc \
    --arg req "$requirement" \
    --arg url "$repo_url" \
    --arg pid "$PROJECT_ID" \
    --argjson ids "$repo_ids" \
    '{userRequest:$req, source:"GITHUB_EXISTING", repoUrl:$url, repoMode:"EXISTING",
      skipApproval:true, executionMode:"PROTOTYPE", projectId:$pid, selectedRepoIds:$ids}')"
  local resp run_id
  resp="$(api POST "/api/runs" "$body")"
  run_id="$(echo "$resp" | jq -r '.runId // .id // empty')"
  if [[ -z "$run_id" ]]; then
    echo "   !! submit failed: $resp" >&2
    return 1
  fi
  echo "   runId=$run_id"
  local i=0 status="" max=120
  while (( i < max )); do
    status="$(api GET "/api/runs/$run_id" | jq -r '.status')"
    case "$status" in
      COMPLETE|FAILED|CANCELLED|PARTIAL) break ;;
    esac
    sleep 15; i=$((i+1))
  done
  echo "   [$label] -> $status (after $((i*15))s)"
  jq -nc --arg id "$run_id" --arg label "$label" --arg status "$status" \
    '{label:$label, runId:$id, status:$status}' >> "$OUT_DIR/launches.jsonl"
}

# ── Phase B: warm-up ──────────────────────────────────────────────────────────
if has_phase B; then
  launch "A1-health"      "Add a /health endpoint to auth-svc returning {status:'ok'}" "auth-svc"
  launch "A2-version"     "Add /version endpoint to users-svc returning the git sha"   "users-svc"
  launch "A3-avatar"      "Add /users/{id}/avatar endpoint to users-svc"               "users-svc"
fi

# ── Phase C: multi-repo ───────────────────────────────────────────────────────
if has_phase C; then
  launch "B1-welcome-email" \
    "When a user is created in users-svc, send a welcome email via notifications-svc /notifications/email" \
    "users-svc,notifications-svc"
  launch "B2-signin-events" \
    "Emit user.signed_in from auth-svc; consume in audit-svc (log) + metrics-svc (counter)" \
    "auth-svc,audit-svc,metrics-svc"
  launch "B3-search-proxy" \
    "Add /search?q=... to search-svc returning {hits:[]}; gateway-svc proxies /api/search to it" \
    "search-svc,gateway-svc"
  launch "B4-daily-report" \
    "scheduler-svc cron calls reports-svc /generate-daily which reads billing-svc /invoices/yesterday" \
    "billing-svc,reports-svc,scheduler-svc"
  launch "C1-users-page" \
    "Add a users page to web-frontend listing users-svc /users" \
    "web-frontend,users-svc"
  launch "C2-mobile-list" \
    "mobile-shell consumes users-svc /users and renders a JSON list, no native modules" \
    "mobile-shell,users-svc"
fi

# ── Phase D: filler runs to drive memory compaction (target 20 then 30) ───────
if has_phase D; then
  echo ">> [D] driving filler runs to exercise memory compaction"
  current="$(api GET "/api/projects/$PROJECT_ID/memory" | jq -r '.stats.totalRuns // 0')"
  echo "   current totalRuns=$current"
  target_v1=20; target_v2=30
  i=$current
  for repo in auth-svc users-svc billing-svc notifications-svc search-svc reports-svc \
              gateway-svc scheduler-svc audit-svc metrics-svc web-frontend mobile-shell \
              auth-svc users-svc billing-svc notifications-svc search-svc reports-svc; do
    (( i >= target_v2 )) && break
    launch "D-filler-$i" "Add a /ping endpoint to $repo returning 'pong'" "$repo"
    i=$((i+1))
    if (( i == target_v1 )); then
      sleep 30   # let MemoryCompactionService run
      api GET "/api/projects/$PROJECT_ID/memory" | tee "$OUT_DIR/memory-after-20.json" >/dev/null
    fi
  done
  api GET "/api/projects/$PROJECT_ID/memory" | tee "$OUT_DIR/memory-after-30.json" >/dev/null
fi

# ── Phase E: chat assertions ──────────────────────────────────────────────────
if has_phase E; then
  echo ">> [E] chat over project memory"
  ask() {
    local q="$1"
    api POST "/api/projects/$PROJECT_ID/chat" "{\"message\":\"$q\"}" \
      | tee -a "$OUT_DIR/chat.jsonl" >/dev/null
    echo "   asked: $q"
  }
  ask "What repos has this project touched so far?"
  ask "Summarise what has been built in the first 20 runs"
  ask "How does auth-svc share user state with audit-svc?"
  ask "List any failed runs and their reasons"
fi

echo ">> done. results in $OUT_DIR"
