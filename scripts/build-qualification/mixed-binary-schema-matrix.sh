#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --previous-jar PATH --candidate-jar PATH --output-dir PATH" >&2
  exit 64
}

previous_jar=""
candidate_jar=""
output_dir=""
while (($#)); do
  case "$1" in
    --previous-jar) previous_jar="${2:-}"; shift 2 ;;
    --candidate-jar) candidate_jar="${2:-}"; shift 2 ;;
    --output-dir) output_dir="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done

[[ -f "$previous_jar" && -f "$candidate_jar" && -n "$output_dir" ]] || usage
mkdir -p "$output_dir"

matrix_id="$$"
postgres_name="es-build-matrix-pg-$matrix_id"
redis_name="es-build-matrix-redis-$matrix_id"
postgres_port=""
redis_port=""
active_pid=""

cleanup() {
  if [[ -n "$active_pid" ]] && kill -0 "$active_pid" 2>/dev/null; then
    kill "$active_pid" 2>/dev/null || true
    wait "$active_pid" 2>/dev/null || true
  fi
  docker stop "$postgres_name" "$redis_name" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

docker run -d --rm --name "$postgres_name" \
  -e POSTGRES_USER=test -e POSTGRES_PASSWORD=test -e POSTGRES_DB=postgres \
  -p 127.0.0.1::5432 pgvector/pgvector:pg16 >/dev/null
docker run -d --rm --name "$redis_name" -p 127.0.0.1::6379 redis:7-alpine >/dev/null

for _ in $(seq 1 60); do
  if docker exec "$postgres_name" pg_isready -U test -d postgres >/dev/null 2>&1 \
      && docker exec "$redis_name" redis-cli ping 2>/dev/null | grep -q PONG; then
    break
  fi
  sleep 1
done
docker exec "$postgres_name" pg_isready -U test -d postgres >/dev/null
docker exec "$redis_name" redis-cli ping | grep -q PONG

postgres_port="$(docker port "$postgres_name" 5432/tcp | sed 's/.*://')"
redis_port="$(docker port "$redis_name" 6379/tcp | sed 's/.*://')"
docker exec "$postgres_name" createdb -U test previous_schema
docker exec "$postgres_name" createdb -U test candidate_schema

results_tsv="$output_dir/results.tsv"
printf 'case\texpected\tobserved\tmatched\tdatabase\tmigration_head\tshared_build_flag\tlog\n' > "$results_tsv"

db_scalar() {
  local database="$1"
  local sql="$2"
  docker exec "$postgres_name" psql -U test -d "$database" -Atqc "$sql" 2>/dev/null || true
}

run_case() {
  local case_id="$1"
  local expected="$2"
  local jar="$3"
  local database="$4"
  local flyway_enabled="$5"
  local port="$6"
  local dwell_seconds="${7:-0}"
  local log_file="$output_dir/$case_id.log"
  local workspace="$output_dir/workspaces/$case_id"
  local observed="STARTUP_TIMEOUT"

  mkdir -p "$workspace"
  JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}" \
    "${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin/java" \
    -jar "$jar" \
    --spring.profiles.active=test \
    --server.port="$port" \
    --spring.datasource.url="jdbc:postgresql://127.0.0.1:$postgres_port/$database" \
    --spring.datasource.username=test \
    --spring.datasource.password=test \
    --spring.jpa.hibernate.ddl-auto=validate \
    --spring.flyway.enabled="$flyway_enabled" \
    --spring.flyway.baseline-on-migrate=false \
    --spring.flyway.out-of-order=false \
    --spring.data.redis.host=127.0.0.1 \
    --spring.data.redis.port="$redis_port" \
    --spring.ai.anthropic.api-key=test-key-not-used \
    --spring.ai.openai.api-key=test-key-not-used \
    --agent.workspace="$workspace" \
    --agent.recovery.enabled=false \
    --logging.level.root=WARN >"$log_file" 2>&1 &
  active_pid="$!"

  for _ in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then
      observed="BOOTED"
      break
    fi
    if ! kill -0 "$active_pid" 2>/dev/null; then
      wait "$active_pid" 2>/dev/null || true
      active_pid=""
      observed="FAILED_STARTUP"
      break
    fi
    sleep 1
  done

  if [[ "$observed" == "BOOTED" && "$dwell_seconds" -gt 0 ]]; then
    sleep "$dwell_seconds"
  fi
  if [[ -n "$active_pid" ]] && kill -0 "$active_pid" 2>/dev/null; then
    kill "$active_pid"
    wait "$active_pid" 2>/dev/null || true
  fi
  active_pid=""

  local matched=false
  [[ "$observed" == "$expected" ]] && matched=true
  local head
  local flag
  head="$(db_scalar "$database" "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
  flag="$(db_scalar "$database" "SELECT flag_value FROM feature_flag WHERE flag_key='build.shared-code-change-loop.enabled'")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$case_id" "$expected" "$observed" "$matched" "$database" "$head" "$flag" "$log_file" \
    >> "$results_tsv"
  [[ "$matched" == true ]]
}

previous_sha256="$(shasum -a 256 "$previous_jar" | awk '{print $1}')"
candidate_sha256="$(shasum -a 256 "$candidate_jar" | awk '{print $1}')"

run_case previous_on_previous BOOTED "$previous_jar" previous_schema true 18101
previous_head="$(db_scalar previous_schema "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"

# A candidate must not silently run its new protocol on a pre-expansion schema when migration is
# deliberately disabled. This is a fail-closed direction, not a supported serving configuration.
run_case candidate_on_previous_without_migration FAILED_STARTUP \
  "$candidate_jar" previous_schema false 18102

# The supported upgrade direction: the candidate migrates the previous schema and then boots.
run_case candidate_migrates_previous BOOTED "$candidate_jar" previous_schema true 18103
candidate_head="$(db_scalar previous_schema "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"

attempts_before="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_attempt")"
commands_before="$(db_scalar previous_schema "SELECT count(*) FROM sandbox_command_execution")"
preparations_before="$(db_scalar previous_schema "SELECT count(*) FROM workspace_publication_preparation")"
environment_receipts_before="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_environment_receipt")"
filesystem_steps_before="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_step")"
archive_cleanup_before="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_archive_cleanup")"
reconciliation_failures_before="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_reconciliation_failure")"

# Rolling-deploy direction plus dark-start dwell: the previous binary must boot against the
# expanded schema and must not invent candidate control-plane rows while shared BUILD is false.
run_case previous_on_candidate_dark_start BOOTED "$previous_jar" previous_schema false 18104 10

attempts_after="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_attempt")"
commands_after="$(db_scalar previous_schema "SELECT count(*) FROM sandbox_command_execution")"
preparations_after="$(db_scalar previous_schema "SELECT count(*) FROM workspace_publication_preparation")"
environment_receipts_after="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_environment_receipt")"
filesystem_steps_after="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_step")"
archive_cleanup_after="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_archive_cleanup")"
reconciliation_failures_after="$(db_scalar previous_schema "SELECT count(*) FROM workspace_generation_reconciliation_failure")"

run_case candidate_on_fresh_candidate BOOTED "$candidate_jar" candidate_schema true 18105
fresh_candidate_head="$(db_scalar candidate_schema "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"

[[ "$previous_head" == "383" ]] || {
  echo "expected previous schema head 383, got $previous_head" >&2
  exit 1
}
[[ "$candidate_head" == "398" && "$fresh_candidate_head" == "398" ]] || {
  echo "expected upgraded and fresh candidate schema head 398, got $candidate_head/$fresh_candidate_head" >&2
  exit 1
}
[[ "$(db_scalar previous_schema "SELECT flag_value FROM feature_flag WHERE flag_key='build.shared-code-change-loop.enabled'")" == "false" ]] || {
  echo "shared BUILD flag was not false after mixed-binary matrix" >&2
  exit 1
}
for count in \
  "$attempts_before" "$attempts_after" \
  "$commands_before" "$commands_after" \
  "$preparations_before" "$preparations_after" \
  "$environment_receipts_before" "$environment_receipts_after" \
  "$filesystem_steps_before" "$filesystem_steps_after" \
  "$archive_cleanup_before" "$archive_cleanup_after" \
  "$reconciliation_failures_before" "$reconciliation_failures_after"; do
  [[ "$count" =~ ^[0-9]+$ ]] || {
    echo "control-plane dark-start count is missing or non-numeric: '$count'" >&2
    exit 1
  }
done
for pair in \
  "$attempts_before:$attempts_after" \
  "$commands_before:$commands_after" \
  "$preparations_before:$preparations_after" \
  "$environment_receipts_before:$environment_receipts_after" \
  "$filesystem_steps_before:$filesystem_steps_after" \
  "$archive_cleanup_before:$archive_cleanup_after" \
  "$reconciliation_failures_before:$reconciliation_failures_after"; do
  [[ "${pair%%:*}" == "${pair##*:}" ]] || {
    echo "previous-binary dark start mutated candidate control rows: $pair" >&2
    exit 1
  }
done

cat > "$output_dir/summary.json" <<JSON
{
  "schemaVersion": 1,
  "previousJarSha256": "$previous_sha256",
  "candidateJarSha256": "$candidate_sha256",
  "previousSchemaHead": "$previous_head",
  "candidateSchemaHead": "$candidate_head",
  "freshCandidateSchemaHead": "$fresh_candidate_head",
  "sharedBuildFlag": "$(db_scalar previous_schema "SELECT flag_value FROM feature_flag WHERE flag_key='build.shared-code-change-loop.enabled'")",
  "darkStart": {
    "workspaceGenerationAttemptsBefore": $attempts_before,
    "workspaceGenerationAttemptsAfter": $attempts_after,
    "sandboxCommandsBefore": $commands_before,
    "sandboxCommandsAfter": $commands_after,
    "publicationPreparationsBefore": $preparations_before,
    "publicationPreparationsAfter": $preparations_after,
    "environmentReceiptsBefore": $environment_receipts_before,
    "environmentReceiptsAfter": $environment_receipts_after,
    "filesystemStepsBefore": $filesystem_steps_before,
    "filesystemStepsAfter": $filesystem_steps_after,
    "archiveCleanupRowsBefore": $archive_cleanup_before,
    "archiveCleanupRowsAfter": $archive_cleanup_after,
    "reconciliationFailuresBefore": $reconciliation_failures_before,
    "reconciliationFailuresAfter": $reconciliation_failures_after
  },
  "allCasesMatched": true
}
JSON

jq empty "$output_dir/summary.json"

echo "mixed binary/schema matrix passed; evidence: $output_dir"
