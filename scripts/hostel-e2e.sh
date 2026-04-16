#!/usr/bin/env bash
# Iterative E2E driver for the HostelMgmt project. Submits runs one-by-one,
# waits for COMPLETE, auto-pushes to GitHub, tracks cumulative cost, stops at
# the $25 budget ceiling.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(cat /tmp/hostel-pid.txt)}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
BUDGET_USD="${BUDGET_USD:-25.00}"
LOG="${LOG:-/tmp/hostel-e2e.log}"

EMAIL="${EMAIL:-admin@executespec.ai}"
PASSWORD="${PASSWORD:-REDACTED_FOR_PUBLIC_RELEASE}"
TOKEN=$(curl -fsS -X POST "$BASE_URL/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | jq -r '.accessToken // .token')
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# Resolve repo ids once
FE_ID=$(curl -fsS "$BASE_URL/api/projects/$PROJECT_ID/repos" "${AUTH[@]}" | jq -r '.[] | select(.repo.githubRepoFullName=="navneetprabhakar/hostel-frontend") | .repo.id')
BE_ID=$(curl -fsS "$BASE_URL/api/projects/$PROJECT_ID/repos" "${AUTH[@]}" | jq -r '.[] | select(.repo.githubRepoFullName=="navneetprabhakar/hostel-backend") | .repo.id')

launch() {
  local label="$1" spec="$2" repos_json="$3"
  echo "[launch] $label :: $spec" | tee -a "$LOG"
  local body
  body=$(jq -nc --arg s "$spec" --arg pid "$PROJECT_ID" --argjson ids "$repos_json" \
    '{userRequest:$s, source:"GITHUB_EXISTING", repoMode:"EXISTING",
      skipApproval:true, executionMode:"PROTOTYPE",
      projectId:$pid, selectedRepoIds:$ids}')
  local rid
  rid=$(curl -fsS -X POST "$BASE_URL/api/runs" "${AUTH[@]}" -d "$body" | jq -r '.runId // .id')
  [ -z "$rid" ] || [ "$rid" = "null" ] && { echo "!! submit failed"; return 1; }
  echo "[launch] runId=$rid" | tee -a "$LOG"

  # Poll to terminal (30-min cap)
  local i=0 status=""
  while (( i < 120 )); do
    status=$(curl -fsS "$BASE_URL/api/runs/$rid" "${AUTH[@]}" | jq -r '.status')
    case "$status" in COMPLETE|FAILED|CANCELLED|PARTIAL) break ;; esac
    sleep 15; i=$((i+1))
  done
  local cost
  cost=$(curl -fsS "$BASE_URL/api/runs/$rid" "${AUTH[@]}" | jq -r '.actualCostUsd // 0')
  echo "[launch] $label → $status (\$$cost) after $((i*15))s" | tee -a "$LOG"

  # Push to GitHub
  if [ "$status" = "COMPLETE" ] || [ "$status" = "PARTIAL" ]; then
    curl -fsS -X POST "$BASE_URL/api/runs/$rid/push-github?repoMode=EXISTING" "${AUTH[@]}" > /dev/null 2>&1 || true
    echo "[launch] pushed $label" | tee -a "$LOG"
  fi
  echo "$cost"
}

total_cost() {
  curl -fsS "$BASE_URL/api/projects/$PROJECT_ID/dashboard" "${AUTH[@]}" | jq -r '.totalCostUsd // 0'
}

over_budget() {
  local spent="$1"
  awk -v s="$spent" -v b="$BUDGET_USD" 'BEGIN { exit !(s+0 >= b+0) }'
}

BOTH_JSON="[\"$FE_ID\",\"$BE_ID\"]"
FE_JSON="[\"$FE_ID\"]"
BE_JSON="[\"$BE_ID\"]"

# Ordered plan — each run builds on previous
declare -a PLAN=(
  "scaffold|$BE_JSON|Scaffold hostel-backend as a Node.js Express REST API with SQLite (better-sqlite3) + a package.json that supports 'npm start' on port 3001. Include /health endpoint returning {status:'ok'}, src/index.js bootstrap, src/db.js initialising schema.sql, and a README with setup steps."
  "fe-scaffold|$FE_JSON|Scaffold hostel-frontend as a React + Vite + TypeScript app. package.json with 'npm run dev'/'npm run build', a home page showing the app name, routing via react-router-dom with placeholder /students and /rooms routes, and fetch helper in src/lib/api.ts pointing to http://localhost:3001."
  "be-students|$BE_JSON|Add Students resource to hostel-backend: schema (id, name, email, phone, room_id nullable), CRUD endpoints /api/students (GET list, GET :id, POST, PUT :id, DELETE :id), request validation with Joi or zod, and unit-safe error responses."
  "be-rooms|$BE_JSON|Add Rooms resource to hostel-backend: schema (id, number, capacity, floor), CRUD endpoints /api/rooms, and a GET /api/rooms/:id/occupants endpoint returning students in that room."
  "be-alloc|$BE_JSON|Add Allocations to hostel-backend: endpoint POST /api/allocations {studentId, roomId} that assigns student to room (updates students.room_id, rejects if room at capacity), and DELETE /api/allocations/:studentId to vacate."
  "fe-students|$FE_JSON|Build the Students page in hostel-frontend at /students: list students from GET /api/students with a table, a 'Add student' dialog that POSTs, edit + delete actions per row. Use fetch via src/lib/api.ts."
  "fe-rooms|$FE_JSON|Build the Rooms page at /rooms: list rooms with occupancy N/capacity, click a row to show occupants from GET /api/rooms/:id/occupants, with an 'Add room' form."
  "fe-alloc|$FE_JSON|Add an Allocation UI to hostel-frontend: on the Students page, add a dropdown per row to assign a room (calls POST /api/allocations), and a 'Vacate' action calling DELETE /api/allocations/:studentId."
  "be-auth|$BE_JSON|Add a minimal JWT auth to hostel-backend: POST /api/auth/register {email,password}, POST /api/auth/login returns {token}, middleware that protects /api/students + /api/rooms + /api/allocations (health stays public). Password hashed with bcryptjs."
  "fe-login|$FE_JSON|Add login to hostel-frontend: a /login page that POSTs to /api/auth/login and stores the token in localStorage, an Authorization: Bearer header attached to all fetch calls, and a redirect-to-login guard on /students and /rooms."
)

for step in "${PLAN[@]}"; do
  IFS="|" read -r label repos spec <<< "$step"
  spent=$(total_cost)
  echo "[budget] spent=\$$spent / \$$BUDGET_USD" | tee -a "$LOG"
  if over_budget "$spent"; then
    echo "[budget] CAP REACHED — stopping before '$label'" | tee -a "$LOG"
    break
  fi
  launch "$label" "$spec" "$repos" || echo "[launch] $label errored — continuing"
done

echo "[done] final spend=$(total_cost)" | tee -a "$LOG"
