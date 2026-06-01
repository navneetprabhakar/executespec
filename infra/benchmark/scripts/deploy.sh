#!/usr/bin/env bash
# Idempotent deploy of the ExecuteSpec backend on the eval VM, configured for eval.
# Pulls the repo, sources LLM keys, launches the backend at concurrency=8, waits
# for health, then sets the eval feature-flag config (overriding migration
# defaults so the VM matches the validated config). Safe to re-run.
#
# Env:
#   REPO_DIR    cloned repo path                       (default $HOME/executespec)
#   KEYS_ENV    keys env file to source (exports KEYS) (default $HOME/eval-keys.env)
#   BENCH_DIR   runner + .espec-admin.env dir          (default $HOME/swebench-bench)
#   BACKEND_LOG backend log path                       (default /tmp/backend-reloc.log)
#   CONCURRENCY swebench.max-concurrent-runs           (default 8)
#   JAVA_HOME_VM Temurin path                          (default /usr/lib/jvm/temurin-21-jdk-amd64)
set -uo pipefail

REPO_DIR="${REPO_DIR:-$HOME/executespec}"
KEYS_ENV="${KEYS_ENV:-$HOME/eval-keys.env}"
BENCH_DIR="${BENCH_DIR:-$HOME/swebench-bench}"
BACKEND_LOG="${BACKEND_LOG:-/tmp/backend-reloc.log}"
CONCURRENCY="${CONCURRENCY:-8}"
JAVA_HOME_VM="${JAVA_HOME_VM:-/usr/lib/jvm/temurin-21-jdk-amd64}"

[ -d "$REPO_DIR" ] || { echo "FATAL: repo not at $REPO_DIR — git clone it first"; exit 1; }
[ -f "$KEYS_ENV" ] || { echo "FATAL: keys env $KEYS_ENV missing (scp a file with ANTHROPIC_API_KEY=… etc.)"; exit 1; }

cd "$REPO_DIR"
echo "[deploy] git pull (gets the new VERIFY-prompt branch if pushed)"
git pull --ff-only || echo "[deploy] WARN: non-ff pull — staying on current checkout (ensure it has the new prompt)"

# stop any running backend so the new build/flags take effect
if curl -sf -m3 http://localhost:8080/actuator/health >/dev/null 2>&1; then
  echo "[deploy] stopping existing backend"
  pkill -f 'ExecutespecApplication' 2>/dev/null || true
  for _ in $(seq 1 30); do curl -sf -m2 http://localhost:8080/actuator/health >/dev/null 2>&1 || break; sleep 1; done
fi

# secrets + concurrency via env
set -a; . "$KEYS_ENV"; set +a
export SWEBENCH_MAX_CONCURRENT_RUNS="$CONCURRENCY"

# profile=local if an application-local.yml was scp'd to $HOME, else default + env keys
PROFILE_ARG=""
if [ -f "$HOME/application-local.yml" ]; then
  cp "$HOME/application-local.yml" "$REPO_DIR/src/main/resources/application-local.yml"
  PROFILE_ARG="-Dspring-boot.run.profiles=local"
  echo "[deploy] using scp'd application-local.yml (profile=local)"
fi

echo "[deploy] launching backend (concurrency=$CONCURRENCY) → $BACKEND_LOG"
# shellcheck disable=SC2086
JAVA_HOME="$JAVA_HOME_VM" nohup mvn -o spring-boot:run $PROFILE_ARG > "$BACKEND_LOG" 2>&1 &

for i in $(seq 1 120); do
  curl -sf -m3 http://localhost:8080/actuator/health >/dev/null 2>&1 && { echo "[deploy] backend UP after ~$((i * 5))s"; break; }
  sleep 5
done
curl -sf -m3 http://localhost:8080/actuator/health >/dev/null 2>&1 \
  || { echo "FATAL: backend did not come up — tail $BACKEND_LOG:"; tail -30 "$BACKEND_LOG"; exit 1; }

# eval flag config — overrides Flyway migration defaults to match the validated run
echo "[deploy] setting eval feature flags"
ADMIN_ENV="$BENCH_DIR/.espec-admin.env"
[ -f "$ADMIN_ENV" ] && . "$ADMIN_ENV"
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d "$(jq -n --arg e "${ESPEC_ADMIN_EMAIL:-}" --arg p "${ESPEC_ADMIN_PW:-}" '{email:$e,password:$p}')" \
  | jq -r '.accessToken // .token // empty')
setflag() {
  curl -s -X PUT "http://localhost:8080/api/admin/feature-flags/$1" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"value\":\"$2\"}" >/dev/null && echo "  $1 = $2"
}
if [ -n "$TOKEN" ]; then
  setflag worker.provider-prompt-tuning.enabled true   # GPT/DeepSeek fragments ON (matches validated run)
  setflag build.test-exec-gate.enabled          false  # verification-in-loop OFF (the (b) baseline)
  setflag worker.build-tdd.enabled              false  # "
  setflag worker.salvage-force-tool-choice.enabled true # Lever-B forced-tool salvage (matches validated)
  setflag worker.cross-provider-fallback.enabled false # eval MUST never provider-shift
else
  echo "[deploy] WARN: admin login failed — flags NOT set. Check $ADMIN_ENV and set them manually."
fi
echo "[deploy] done — backend healthy, eval flags applied"
