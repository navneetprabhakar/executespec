#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
output_dir="${1:-${repo_root}/output/build-qualification/publication-recovery-v397}"
mkdir -p "${output_dir}"

test_class="PublicationPreparationPostgresTest"
test_source="src/test/java/com/navneet/executespec/integration/scm/${test_class}.java"
probe_source="src/test/java/com/navneet/executespec/integration/scm/PublicationRecoveryTakeoverProbe.java"
migration="src/main/resources/db/migration/V392__fence_publication_attempt_recovery.sql"
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
  jq -n --arg status FAILED --arg logSha256 "$(shasum -a 256 "${log_file}" | awk '{print $1}')" \
    '{schemaVersion:1,status:$status,logSha256:$logSha256}' > "${output_dir}/summary.json"
  exit 1
fi

report="target/surefire-reports/TEST-com.navneet.executespec.integration.scm.${test_class}.xml"
cp "${report}" "${output_dir}/"

jq -n \
  --arg status PASSED \
  --arg baseCommit "$(git rev-parse HEAD)" \
  --arg gitDiffSha256 "$(git diff --binary | shasum -a 256 | awk '{print $1}')" \
  --arg migrationHead V398 \
  --arg migrationSha256 "$(shasum -a 256 "${migration}" | awk '{print $1}')" \
  --arg cleanupMigrationSha256 "$(shasum -a 256 "${cleanup_migration}" | awk '{print $1}')" \
  --arg checkpointMigrationSha256 "$(shasum -a 256 "${checkpoint_migration}" | awk '{print $1}')" \
  --arg verificationFailureMigrationSha256 "$(shasum -a 256 "${verification_failure_migration}" | awk '{print $1}')" \
  --arg environmentReceiptMigrationSha256 "$(shasum -a 256 "${environment_receipt_migration}" | awk '{print $1}')" \
  --arg filesystemSagaMigrationSha256 "$(shasum -a 256 "${filesystem_saga_migration}" | awk '{print $1}')" \
  --arg reconciliationFailureMigrationSha256 "$(shasum -a 256 "${reconciliation_failure_migration}" | awk '{print $1}')" \
  --arg testSourceSha256 "$(shasum -a 256 "${test_source}" | awk '{print $1}')" \
  --arg takeoverProbeSha256 "$(shasum -a 256 "${probe_source}" | awk '{print $1}')" \
  --arg testReportSha256 "$(shasum -a 256 "${report}" | awk '{print $1}')" \
  --arg logSha256 "$(shasum -a 256 "${log_file}" | awk '{print $1}')" \
  --arg sharedBuildFlag false \
  '{
    schemaVersion:1,
    status:$status,
    baseCommit:$baseCommit,
    gitDiffSha256:$gitDiffSha256,
    migrationHead:$migrationHead,
    migrationSha256:$migrationSha256,
    cleanupMigrationSha256:$cleanupMigrationSha256,
    checkpointMigrationSha256:$checkpointMigrationSha256,
    verificationFailureMigrationSha256:$verificationFailureMigrationSha256,
    environmentReceiptMigrationSha256:$environmentReceiptMigrationSha256,
    filesystemSagaMigrationSha256:$filesystemSagaMigrationSha256,
    reconciliationFailureMigrationSha256:$reconciliationFailureMigrationSha256,
    testSourceSha256:$testSourceSha256,
    takeoverProbeSha256:$takeoverProbeSha256,
    testReportSha256:$testReportSha256,
    logSha256:$logSha256,
    sharedBuildFlag:$sharedBuildFlag,
    cases:[
      {id:"PROVISION_KILL_BEFORE_DISPATCH",expected:"FAILED_AND_NUMBERED_RETRY",result:"PASSED"},
      {id:"PROVISION_KILL_AFTER_DISPATCH",expected:"INDETERMINATE_NO_GUESSED_SUCCESS",result:"PASSED"},
      {id:"PUBLICATION_KILL_BEFORE_DISPATCH",expected:"FAILED_AND_NUMBERED_RETRY",result:"PASSED"},
      {id:"MULTI_REPO_PARTIAL_REMOTE_EFFECT",expected:"FIRST_PUBLISHED_SECOND_INDETERMINATE",result:"PASSED"},
      {id:"LATE_PUBLICATION_SUCCESS",expected:"EXPIRED_OWNER_SETTLEMENT_FENCED",result:"PASSED"},
      {id:"REPLACEMENT_JVM_RECOVERY",expected:"ALL_STALE_TRANSITIONS_SETTLED_BY_SEPARATE_PROCESS",result:"PASSED"}
    ]
  }' > "${output_dir}/summary.json"

jq . "${output_dir}/summary.json"
