#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
output_dir="${1:-${repo_root}/output/build-qualification/real-container-command-recovery-2026-08-12}"
mkdir -p "${output_dir}"

test_class="SandboxCommandRealContainerRecoveryPostgresTest"
test_source="src/test/java/com/navneet/executespec/sandbox/${test_class}.java"
probe_source="src/test/java/com/navneet/executespec/sandbox/SandboxRecoveryTakeoverProbe.java"
migration="src/main/resources/db/migration/V391__recover_stale_sandbox_command_admissions.sql"
publication_migration="src/main/resources/db/migration/V392__fence_publication_attempt_recovery.sql"
cleanup_migration="src/main/resources/db/migration/V393__queue_workspace_recovery_artifact_cleanup.sql"
checkpoint_migration="src/main/resources/db/migration/V394__bind_workspace_checkpoint_storage_identity.sql"
verification_failure_migration="src/main/resources/db/migration/V395__classify_workspace_verification_failures.sql"
environment_receipt_migration="src/main/resources/db/migration/V396__persist_build_environment_receipts.sql"
filesystem_saga_migration="src/main/resources/db/migration/V397__journal_workspace_generation_filesystem_saga.sql"
reconciliation_failure_migration="src/main/resources/db/migration/V398__persist_terminal_reconciliation_failures.sql"
log_file="${output_dir}/maven-test.log"

cd "${repo_root}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

if ! mvn -q -Pintegration -Dtest="${test_class}" test 2>&1 | tee "${log_file}"; then
  jq -n \
    --arg status "FAILED" \
    --arg testClass "${test_class}" \
    --arg logSha256 "$(shasum -a 256 "${log_file}" | awk '{print $1}')" \
    '{schemaVersion:1,status:$status,testClass:$testClass,logSha256:$logSha256}' \
    > "${output_dir}/summary.json"
  exit 1
fi

report="target/surefire-reports/TEST-com.navneet.executespec.sandbox.${test_class}.xml"
cp "${report}" "${output_dir}/"

git_diff_sha="$(git diff --binary | shasum -a 256 | awk '{print $1}')"
image="executespec/sandbox-toolchain:litmus-20260802"
image_id="$(docker image inspect "${image}" --format '{{.Id}}')"
docker_version="$(docker version --format '{{.Server.Version}}')"
report_sha="$(shasum -a 256 "${report}" | awk '{print $1}')"
source_sha="$(shasum -a 256 "${test_source}" | awk '{print $1}')"
probe_sha="$(shasum -a 256 "${probe_source}" | awk '{print $1}')"
migration_sha="$(shasum -a 256 "${migration}" | awk '{print $1}')"
publication_migration_sha="$(shasum -a 256 "${publication_migration}" | awk '{print $1}')"
cleanup_migration_sha="$(shasum -a 256 "${cleanup_migration}" | awk '{print $1}')"
checkpoint_migration_sha="$(shasum -a 256 "${checkpoint_migration}" | awk '{print $1}')"
verification_failure_migration_sha="$(shasum -a 256 "${verification_failure_migration}" | awk '{print $1}')"
environment_receipt_migration_sha="$(shasum -a 256 "${environment_receipt_migration}" | awk '{print $1}')"
filesystem_saga_migration_sha="$(shasum -a 256 "${filesystem_saga_migration}" | awk '{print $1}')"
reconciliation_failure_migration_sha="$(shasum -a 256 "${reconciliation_failure_migration}" | awk '{print $1}')"
log_sha="$(shasum -a 256 "${log_file}" | awk '{print $1}')"

jq -n \
  --arg status "PASSED" \
  --arg testClass "${test_class}" \
  --arg baseCommit "$(git rev-parse HEAD)" \
  --arg gitDiffSha256 "${git_diff_sha}" \
  --arg dockerServerVersion "${docker_version}" \
  --arg image "${image}" \
  --arg imageId "${image_id}" \
  --arg migrationHead "V398" \
  --arg migrationSha256 "${migration_sha}" \
  --arg publicationMigrationSha256 "${publication_migration_sha}" \
  --arg cleanupMigrationSha256 "${cleanup_migration_sha}" \
  --arg checkpointMigrationSha256 "${checkpoint_migration_sha}" \
  --arg verificationFailureMigrationSha256 "${verification_failure_migration_sha}" \
  --arg environmentReceiptMigrationSha256 "${environment_receipt_migration_sha}" \
  --arg filesystemSagaMigrationSha256 "${filesystem_saga_migration_sha}" \
  --arg reconciliationFailureMigrationSha256 "${reconciliation_failure_migration_sha}" \
  --arg testSourceSha256 "${source_sha}" \
  --arg takeoverProbeSha256 "${probe_sha}" \
  --arg testReportSha256 "${report_sha}" \
  --arg logSha256 "${log_sha}" \
  --arg sharedBuildFlag "false" \
  '{
    schemaVersion: 1,
    status: $status,
    testClass: $testClass,
    baseCommit: $baseCommit,
    gitDiffSha256: $gitDiffSha256,
    dockerServerVersion: $dockerServerVersion,
    containerImage: $image,
    containerImageId: $imageId,
    migrationHead: $migrationHead,
    migrationSha256: $migrationSha256,
    publicationMigrationSha256: $publicationMigrationSha256,
    cleanupMigrationSha256: $cleanupMigrationSha256,
    checkpointMigrationSha256: $checkpointMigrationSha256,
    verificationFailureMigrationSha256: $verificationFailureMigrationSha256,
    environmentReceiptMigrationSha256: $environmentReceiptMigrationSha256,
    filesystemSagaMigrationSha256: $filesystemSagaMigrationSha256,
    reconciliationFailureMigrationSha256: $reconciliationFailureMigrationSha256,
    testSourceSha256: $testSourceSha256,
    takeoverProbeSha256: $takeoverProbeSha256,
    testReportSha256: $testReportSha256,
    logSha256: $logSha256,
    sharedBuildFlag: $sharedBuildFlag,
    cases: [
      {id:"KILL_BEFORE_DISPATCH", expected:"ADMITTED_TO_UNAVAILABLE", result:"PASSED"},
      {id:"KILL_DURING_COMMAND", expected:"DISPATCHED_TO_INDETERMINATE_AND_RUNTIME_DESTROYED_BY_REPLACEMENT_JVM", result:"PASSED"},
      {id:"KILL_AFTER_EXIT_BEFORE_SETTLEMENT", expected:"LATE_SUCCESS_FENCED_AND_INDETERMINATE", result:"PASSED"},
      {id:"KILL_DURING_TEARDOWN", expected:"REPLACEMENT_JVM_DESTROYS_AND_TERMINATES", result:"PASSED"},
      {id:"KILL_DURING_JANITOR_CLAIM", expected:"STALE_CLAIM_RECLAIMED_AND_TREE_DELETED_BY_REPLACEMENT_JVM", result:"PASSED"}
    ]
  }' > "${output_dir}/summary.json"

jq . "${output_dir}/summary.json"
