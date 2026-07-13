#!/usr/bin/env bash
# Deterministic, provider-free contract test for the held-out acceptance runner.
#
# This deliberately makes the runtime dependency probe fail, so the test can prove that an
# unavailable runtime is reported as STRUCTURAL_ONLY and never becomes a correctness score.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
RUNNER="$ROOT/run.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/bin" "$TMP/good" "$TMP/bad"
cat >"$TMP/bin/python3" <<'EOF'
#!/usr/bin/env sh
# Make the acceptance runner's optional FastAPI/uvicorn probe fail without requiring any host
# packages or network access.  The runner must then use its explicit non-scoring fallback.
exit 1
EOF
chmod +x "$TMP/bin/python3"

cat >"$TMP/good/main.py" <<'EOF'
from fastapi import FastAPI
app = FastAPI()
@app.get("/health")
def health(): return {"status": "ok"}
@app.get("/todos")
def todos(): return []
@app.post("/todos")
def create(): pass
@app.delete("/todos/{todo_id}")
def delete(todo_id: int): pass
EOF
echo 'fastapi' >"$TMP/good/requirements.txt"

good_output=""
good_status=0
if good_output="$(PATH="$TMP/bin:$PATH" bash "$RUNNER" "$TMP/good" 2>/dev/null)"; then
  good_status=0
else
  good_status=$?
fi
[[ "$good_status" -eq 2 ]]
grep -q '^PASS 0/5$' <<<"$good_output"
grep -q '^STRUCTURAL_ONLY 5/5 ' <<<"$good_output"

# A partial source fixture must remain non-scoring too; only the diagnostic may change.
printf '@app.get("/health")\ndef health(): pass\n' >"$TMP/bad/main.py"
echo 'fastapi' >"$TMP/bad/requirements.txt"
bad_output=""
bad_status=0
if bad_output="$(PATH="$TMP/bin:$PATH" bash "$RUNNER" "$TMP/bad" 2>/dev/null)"; then
  bad_status=0
else
  bad_status=$?
fi
[[ "$bad_status" -eq 2 ]]
grep -q '^PASS 0/5$' <<<"$bad_output"
grep -q '^STRUCTURAL_ONLY 2/5 ' <<<"$bad_output"

echo "acceptance contract: PASS/STRUCTURAL_ONLY behavior verified without providers"
