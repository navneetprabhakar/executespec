#!/usr/bin/env bash
#
# Held-out acceptance tests for the `rest-api-low` BUILD eval case (RL-2026-05-041).
# Authored outside the run workspace; $1 is the generated workspace directory.
#
# The runtime path is the correctness score.  If the runtime cannot be started, the script emits
# a separate STRUCTURAL_ONLY diagnostic and returns 2.  Source grep is never reported as PASS,
# because route declarations do not prove endpoint behavior.

set -uo pipefail
WS="${1:?usage: run.sh <workspace-dir>}"
PASS=0
TOTAL=5

MAIN="$(find "$WS" -name main.py -not -path '*/node_modules/*' -not -path '*/.git/*' 2>/dev/null | head -1)"
SRC=""
[[ -n "$MAIN" ]] && SRC="$(cat "$MAIN" 2>/dev/null)"

BOOTED=0
PORT=8099
PY="$(command -v python3 || true)"
APP_PID=""
if [[ -n "$MAIN" && -n "$PY" ]] && "$PY" -c 'import fastapi, uvicorn' >/dev/null 2>&1; then
  ( cd "$(dirname "$MAIN")" && "$PY" -m uvicorn main:app --host 127.0.0.1 --port "$PORT" >/tmp/be_app.log 2>&1 ) &
  APP_PID=$!
  for _ in $(seq 1 20); do
    if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
      BOOTED=1
      break
    fi
    sleep 0.5
  done
fi

if [[ "$BOOTED" == "1" ]]; then
  # 1) GET /health must return the specified value, not merely contain a status key.
  health_json="$(curl -fsS "http://127.0.0.1:${PORT}/health")"
  if echo "$health_json" | jq -e '.status == "ok"' >/dev/null 2>&1; then
    PASS=$((PASS+1))
  fi

  # 2) GET /todos must return a JSON array.
  todos_json="$(curl -fsS "http://127.0.0.1:${PORT}/todos")"
  if echo "$todos_json" | jq -e 'type == "array"' >/dev/null 2>&1; then
    PASS=$((PASS+1))
  fi

  # 3) POST /todos must return the created shape and values.
  create_json="$(curl -fsS -X POST "http://127.0.0.1:${PORT}/todos" \
    -H 'Content-Type: application/json' -d '{"title":"x"}')"
  if echo "$create_json" | jq -e '(.id | type == "number") and .title == "x" and .done == false' >/dev/null 2>&1; then
    PASS=$((PASS+1))
  fi

  # 4) DELETE must return the contract and remove the item from subsequent list responses.
  delete_id="$(curl -fsS -X POST "http://127.0.0.1:${PORT}/todos" \
    -H 'Content-Type: application/json' -d '{"title":"delete-me"}' | jq -r '.id // empty')"
  delete_json=""
  [[ -n "$delete_id" ]] && delete_json="$(curl -fsS -X DELETE "http://127.0.0.1:${PORT}/todos/${delete_id}" || true)"
  delete_ok=0
  if echo "$delete_json" | jq -e '.deleted == true' >/dev/null 2>&1; then
    remaining="$(curl -fsS "http://127.0.0.1:${PORT}/todos")"
    if ! echo "$remaining" | jq -e --arg id "$delete_id" 'any(.[]; (.id|tostring) == $id)' >/dev/null 2>&1; then
      delete_ok=1
    fi
  fi
  [[ "$delete_ok" == "1" ]] && PASS=$((PASS+1))

  # 5) requirements.txt must declare FastAPI.
  grep -riqs 'fastapi' "$WS/requirements.txt" 2>/dev/null && PASS=$((PASS+1))

  [[ -n "$APP_PID" ]] && kill "$APP_PID" >/dev/null 2>&1 || true
  echo "PASS ${PASS}/${TOTAL}"
  [[ "$PASS" -eq "$TOTAL" ]] && exit 0 || exit 1
fi

# Runtime dependencies were unavailable.  Keep source diagnostics for triage but intentionally
# return a non-zero result; build-eval.sh therefore records PASS 0/N instead of a false score.
STRUCTURAL_PASS=0
[[ -n "$SRC" ]] && echo "$SRC" | grep -Eq '@(app\.)?(get|route)\(\s*["'"'"']/health' && STRUCTURAL_PASS=$((STRUCTURAL_PASS+1))
[[ -n "$SRC" ]] && echo "$SRC" | grep -Eq '@(app\.)?get\(\s*["'"'"']/todos' && STRUCTURAL_PASS=$((STRUCTURAL_PASS+1))
[[ -n "$SRC" ]] && echo "$SRC" | grep -Eq '@(app\.)?post\(\s*["'"'"']/todos' && STRUCTURAL_PASS=$((STRUCTURAL_PASS+1))
[[ -n "$SRC" ]] && echo "$SRC" | grep -Eq '@(app\.)?delete\(\s*["'"'"']/todos/\{' && STRUCTURAL_PASS=$((STRUCTURAL_PASS+1))
grep -riqs 'fastapi' "$WS/requirements.txt" 2>/dev/null && STRUCTURAL_PASS=$((STRUCTURAL_PASS+1))
[[ -n "$APP_PID" ]] && kill "$APP_PID" >/dev/null 2>&1 || true
echo "PASS 0/${TOTAL}"
echo "STRUCTURAL_ONLY ${STRUCTURAL_PASS}/${TOTAL} (runtime unavailable; not a correctness score)"
exit 2
