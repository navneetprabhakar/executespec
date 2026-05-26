#!/usr/bin/env bash
#
# Held-out acceptance tests for the `rest-api-low` BUILD eval case (RL-2026-05-041, audit #1).
# Authored by US, never shown to the worker (lives outside the run workspace). $1 = generated
# workspace dir. Prints "PASS n/m" where m = total checks.
#
# This sample uses behavioral checks where cheap (boot the app + curl the live endpoints) and falls
# back to structural assertions when the runtime isn't available, so it scores on any host. A fuller
# corpus case would always boot + curl. The 5 checks map 1:1 to the spec's acceptance criteria.

set -uo pipefail
WS="${1:?usage: run.sh <workspace-dir>}"
PASS=0
TOTAL=5

# Resolve the app's main.py (the spec asked for a single main.py; be tolerant of a subdir).
MAIN="$(find "$WS" -name main.py -not -path '*/node_modules/*' -not -path '*/.git/*' 2>/dev/null | head -1)"
SRC=""
[[ -n "$MAIN" ]] && SRC="$(cat "$MAIN" 2>/dev/null)"

# Try a real boot + HTTP probe (best evidence). Falls back to source assertions if uvicorn/venv
# aren't available on the host — the structural checks below still encode the same 5 criteria.
BOOTED=0; PORT=8099; PY="$(command -v python3 || true)"
if [[ -n "$MAIN" && -n "$PY" ]] && "$PY" -c 'import fastapi, uvicorn' >/dev/null 2>&1; then
  ( cd "$(dirname "$MAIN")" && "$PY" -m uvicorn main:app --port "$PORT" >/tmp/be_app.log 2>&1 ) &
  APP_PID=$!
  for _ in $(seq 1 20); do curl -sf "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 && { BOOTED=1; break; }; sleep 0.5; done
fi

check() { # check <description> <behavioral-cmd-or-empty> <structural-grep-regex>
  local ok=1
  if [[ "$BOOTED" == "1" && -n "$2" ]]; then bash -c "$2" >/dev/null 2>&1 && ok=0
  elif [[ -n "$SRC" ]]; then echo "$SRC" | grep -Eq "$3" && ok=0; fi
  [[ $ok -eq 0 ]] && PASS=$((PASS+1))
}

# 1) GET /health → {"status":"ok"}
check "health" "curl -sf http://127.0.0.1:${PORT}/health | grep -q '\"status\"'" '@(app\.)?(get|route)\(\s*["'"'"'"]/health'
# 2) GET /todos
check "list todos" "curl -sf http://127.0.0.1:${PORT}/todos >/dev/null" '@(app\.)?get\(\s*["'"'"'"]/todos'
# 3) POST /todos
check "create todo" "curl -sf -XPOST http://127.0.0.1:${PORT}/todos -H 'Content-Type: application/json' -d '{\"title\":\"x\"}' | grep -q '\"id\"'" '@(app\.)?post\(\s*["'"'"'"]/todos'
# 4) DELETE /todos/{id}
check "delete todo" "" '@(app\.)?delete\(\s*["'"'"'"]/todos/\{'
# 5) requirements.txt with fastapi
if grep -riqs 'fastapi' "$WS"/requirements.txt 2>/dev/null; then PASS=$((PASS+1)); fi

[[ -n "${APP_PID:-}" ]] && kill "$APP_PID" >/dev/null 2>&1 || true
echo "PASS ${PASS}/${TOTAL}"
[[ $PASS -eq $TOTAL ]] && exit 0 || exit 1
