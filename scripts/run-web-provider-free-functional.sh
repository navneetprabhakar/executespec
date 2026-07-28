#!/usr/bin/env bash
set -euo pipefail

# Provider-free, disposable browser-functional run.
#
# The harness never reads a credential file. It creates a one-run QA identity in a disposable
# PostgreSQL container, promotes only that disposable row to SUPER_ADMIN, runs the web-qa profile,
# and removes the generated containers/processes on exit. Provider keys are explicitly removed
# from the backend process environment and the server dispatch guard remains disabled by profile.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QA_SUFFIX="$$-$(date +%s)"
POSTGRES_CONTAINER="executespec-webqa-postgres-${QA_SUFFIX}"
REDIS_CONTAINER="executespec-webqa-redis-${QA_SUFFIX}"
POSTGRES_PORT="${WEB_QA_POSTGRES_PORT:-55432}"
REDIS_PORT="${WEB_QA_REDIS_PORT:-56379}"
BACKEND_PORT="${WEB_QA_BACKEND_PORT:-18080}"
FRONTEND_PORT="${WEB_QA_FRONTEND_PORT:-15174}"
QA_EMAIL="web-qa-${QA_SUFFIX}@example.test"
QA_PASSWORD="WebQa!${QA_SUFFIX}x"
RUN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/executespec-webqa.XXXXXX")"
BACKEND_PID=""
FRONTEND_PID=""
SAFE_PATH="${PATH}"
SAFE_HOME="${HOME}"
SAFE_TMPDIR="${TMPDIR:-/tmp}"
SAFE_LANG="${LANG:-C.UTF-8}"
PRESERVE_PROVIDER_READINESS=false
if [[ -n "${WEB_QA_CLIENT_ROOT:-}" ]]; then
  PRESERVE_PROVIDER_READINESS=true
fi

cleanup() {
  set +e
  if [[ -n "${FRONTEND_PID}" ]]; then
    pkill -TERM -P "${FRONTEND_PID}" 2>/dev/null
    kill "${FRONTEND_PID}" 2>/dev/null
  fi
  if [[ -n "${BACKEND_PID}" ]]; then
    pkill -TERM -P "${BACKEND_PID}" 2>/dev/null
    kill "${BACKEND_PID}" 2>/dev/null
  fi
  wait "${FRONTEND_PID}" 2>/dev/null
  wait "${BACKEND_PID}" 2>/dev/null
  docker rm -f "${POSTGRES_CONTAINER}" "${REDIS_CONTAINER}" >/dev/null 2>&1
  if [[ "${WEB_QA_KEEP_LOGS:-false}" != "true" ]]; then
    case "${RUN_DIR}" in
      "${TMPDIR:-/tmp}"/executespec-webqa.*) rm -rf -- "${RUN_DIR}" ;;
      *) echo "Refusing to remove unexpected QA log path: ${RUN_DIR}" >&2 ;;
    esac
  else
    echo "QA process logs retained at ${RUN_DIR}"
  fi
}
trap cleanup EXIT INT TERM

wait_http() {
  local url="$1"
  local label="$2"
  for _ in $(seq 1 180); do
    if curl --silent --fail "${url}" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "${label} did not become ready at ${url}" >&2
  return 1
}

assert_port_free() {
  local port="$1"
  if nc -z localhost "${port}" >/dev/null 2>&1; then
    echo "Required QA port ${port} is already in use; override the matching WEB_QA_*_PORT." >&2
    return 1
  fi
}

assert_port_free "${POSTGRES_PORT}"
assert_port_free "${REDIS_PORT}"
assert_port_free "${BACKEND_PORT}"
assert_port_free "${FRONTEND_PORT}"

docker run --detach --rm \
  --name "${POSTGRES_CONTAINER}" \
  --publish "127.0.0.1:${POSTGRES_PORT}:5432" \
  --env POSTGRES_DB=executespec_webqa \
  --env POSTGRES_USER=postgres \
  --env POSTGRES_PASSWORD=postgres \
  pgvector/pgvector:pg16 >/dev/null

docker run --detach --rm \
  --name "${REDIS_CONTAINER}" \
  --publish "127.0.0.1:${REDIS_PORT}:6379" \
  redis:7-alpine >/dev/null

for _ in $(seq 1 60); do
  if docker exec "${POSTGRES_CONTAINER}" pg_isready -U postgres -d executespec_webqa >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "${POSTGRES_CONTAINER}" pg_isready -U postgres -d executespec_webqa >/dev/null

(
  cd "${ROOT_DIR}"
  env -i \
    PATH="${SAFE_PATH}" \
    HOME="${SAFE_HOME}" \
    TMPDIR="${SAFE_TMPDIR}" \
    LANG="${SAFE_LANG}" \
    NO_PROXY="localhost,127.0.0.1,::1" \
    JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
    SPRING_PROFILES_ACTIVE=web-qa \
    SERVER_PORT="${BACKEND_PORT}" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${POSTGRES_PORT}/executespec_webqa" \
    SPRING_DATASOURCE_USERNAME=postgres \
    SPRING_DATASOURCE_PASSWORD=postgres \
    SPRING_DATA_REDIS_HOST=localhost \
    SPRING_DATA_REDIS_PORT="${REDIS_PORT}" \
    APP_CORS_ALLOWED_ORIGINS="http://localhost:${FRONTEND_PORT}" \
    AGENT_WORKSPACE="${RUN_DIR}/workspace" \
    mvn spring-boot:run >"${RUN_DIR}/backend.log" 2>&1
) &
BACKEND_PID="$!"
wait_http "http://localhost:${BACKEND_PORT}/actuator/health" "backend"

curl --silent --fail \
  --request POST \
  --header 'Content-Type: application/json' \
  --data "{\"email\":\"${QA_EMAIL}\",\"firstName\":\"Web\",\"lastName\":\"QA\",\"password\":\"${QA_PASSWORD}\"}" \
  "http://localhost:${BACKEND_PORT}/api/auth/register" >/dev/null

docker exec "${POSTGRES_CONTAINER}" psql -U postgres -d executespec_webqa -v ON_ERROR_STOP=1 \
  --command "UPDATE app_user SET global_role='SUPER_ADMIN', tier='ENTERPRISE', status='ACTIVE', email_verified=TRUE, must_change_password=FALSE WHERE email='${QA_EMAIL}'; UPDATE org SET tier='ENTERPRISE' WHERE id=(SELECT org_id FROM app_user WHERE email='${QA_EMAIL}');" >/dev/null

(
  cd "${ROOT_DIR}"
  env -i \
    PATH="${SAFE_PATH}" \
    HOME="${SAFE_HOME}" \
    TMPDIR="${SAFE_TMPDIR}" \
    LANG="${SAFE_LANG}" \
    NO_PROXY="localhost,127.0.0.1,::1" \
    VITE_BACKEND_URL="http://localhost:${BACKEND_PORT}" \
    npm --prefix frontend run dev -- --host localhost --port "${FRONTEND_PORT}" --strictPort \
    >"${RUN_DIR}/frontend.log" 2>&1
) &
FRONTEND_PID="$!"
wait_http "http://localhost:${FRONTEND_PORT}" "frontend"

(
  cd "${ROOT_DIR}"
  env -i \
    PATH="${SAFE_PATH}" \
    HOME="${SAFE_HOME}" \
    TMPDIR="${SAFE_TMPDIR}" \
    LANG="${SAFE_LANG}" \
    NO_PROXY="localhost,127.0.0.1,::1" \
    CODEX_RUNTIME_NODE_MODULES="${CODEX_RUNTIME_NODE_MODULES:-}" \
    CHROME_PATH="${CHROME_PATH:-}" \
    WEB_PROVIDER_FREE_RESULTS_DIR="${WEB_PROVIDER_FREE_RESULTS_DIR:-}" \
    WEB_QA_PRESERVE_PROVIDER_READINESS="${PRESERVE_PROVIDER_READINESS}" \
    EXECUTESPEC_QA_EMAIL="${QA_EMAIL}" \
    EXECUTESPEC_QA_PASSWORD="${QA_PASSWORD}" \
    BACKEND_URL="http://localhost:${BACKEND_PORT}" \
    FRONTEND_URL="http://localhost:${FRONTEND_PORT}" \
    node scripts/web-provider-free-functional.mjs
)

# Optional cross-repository CLI functional pass against this exact disposable backend. The client
# harness mints a one-day PAT from the disposable identity, strips the email/password from every
# child process, and revokes only that harness-owned PAT in its finally block. No provider-backed
# command is submitted. Omit WEB_QA_CLIENT_ROOT for the browser-only gate.
if [[ -n "${WEB_QA_CLIENT_ROOT:-}" ]]; then
  CLIENT_ROOT="$(cd "${WEB_QA_CLIENT_ROOT}" && pwd)"
  if [[ ! -f "${CLIENT_ROOT}/package.json" || ! -d "${CLIENT_ROOT}/cli" ]]; then
    echo "WEB_QA_CLIENT_ROOT is not an ExecuteSpec client checkout: ${CLIENT_ROOT}" >&2
    exit 1
  fi
  (
    cd "${CLIENT_ROOT}"
    env -i \
      PATH="${SAFE_PATH}" \
      HOME="${SAFE_HOME}" \
      TMPDIR="${SAFE_TMPDIR}" \
      LANG="${SAFE_LANG}" \
      NO_PROXY="localhost,127.0.0.1,::1" \
      EXECUTESPEC_NO_UPDATE_CHECK=1 \
      NO_UPDATE_NOTIFIER=1 \
      EXECUTESPEC_ENABLE_LIVE_WEB=0 \
      EXECUTESPEC_QA_HOST="http://localhost:${BACKEND_PORT}" \
      EXECUTESPEC_QA_SUPERADMIN_EMAIL="${QA_EMAIL}" \
      EXECUTESPEC_QA_SUPERADMIN_PASSWORD="${QA_PASSWORD}" \
      npm run qa:cli-behavior
  )
fi
