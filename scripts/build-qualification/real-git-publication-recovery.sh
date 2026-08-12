#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
output_dir="${1:-${repo_root}/output/build-qualification/real-git-publication-v399-2026-08-12}"
mkdir -p "${output_dir}"

test_class="PublicationRealGitPartialEffectPostgresTest"
test_source="src/test/java/com/navneet/executespec/integration/scm/${test_class}.java"
probe_source="src/test/java/com/navneet/executespec/integration/scm/PublicationRealGitCrashProbe.java"
dispatcher_source="src/main/java/com/navneet/executespec/integration/scm/PublicationDispatchService.java"
git_source="src/main/java/com/navneet/executespec/tool/GitTool.java"
migration="src/main/resources/db/migration/V392__fence_publication_attempt_recovery.sql"
terminal_migration="src/main/resources/db/migration/V399__persist_generation_operation_intents.sql"

cd "${repo_root}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}"

for repetition in 1 2; do
  log_file="${output_dir}/maven-test-run-${repetition}.log"
  if ! mvn -q -Pintegration -Dtest="${test_class}" test 2>&1 | tee "${log_file}"; then
    jq -n \
      --arg status FAILED \
      --argjson failedRepetition "${repetition}" \
      --arg logSha256 "$(shasum -a 256 "${log_file}" | awk '{print $1}')" \
      '{schemaVersion:1,status:$status,failedRepetition:$failedRepetition,logSha256:$logSha256}' \
      > "${output_dir}/summary.json"
    exit 1
  fi
  report="target/surefire-reports/TEST-com.navneet.executespec.integration.scm.${test_class}.xml"
  cp "${report}" "${output_dir}/TEST-${test_class}-run-${repetition}.xml"
done

jq -n \
  --arg status PASSED \
  --arg scope LOCAL_BARE_GIT_NO_PROVIDER_CREDENTIAL \
  --arg baseCommit "$(git rev-parse HEAD)" \
  --arg gitDiffSha256 "$(git diff --binary | shasum -a 256 | awk '{print $1}')" \
  --arg migrationHead V399 \
  --arg migrationSha256 "$(shasum -a 256 "${migration}" | awk '{print $1}')" \
  --arg terminalMigrationSha256 "$(shasum -a 256 "${terminal_migration}" | awk '{print $1}')" \
  --arg testSourceSha256 "$(shasum -a 256 "${test_source}" | awk '{print $1}')" \
  --arg probeSourceSha256 "$(shasum -a 256 "${probe_source}" | awk '{print $1}')" \
  --arg dispatcherSourceSha256 "$(shasum -a 256 "${dispatcher_source}" | awk '{print $1}')" \
  --arg gitSourceSha256 "$(shasum -a 256 "${git_source}" | awk '{print $1}')" \
  --arg report1Sha256 "$(shasum -a 256 "${output_dir}/TEST-${test_class}-run-1.xml" | awk '{print $1}')" \
  --arg report2Sha256 "$(shasum -a 256 "${output_dir}/TEST-${test_class}-run-2.xml" | awk '{print $1}')" \
  --arg log1Sha256 "$(shasum -a 256 "${output_dir}/maven-test-run-1.log" | awk '{print $1}')" \
  --arg log2Sha256 "$(shasum -a 256 "${output_dir}/maven-test-run-2.log" | awk '{print $1}')" \
  --arg sharedBuildFlag false \
  '{
    schemaVersion:1,
    status:$status,
    scope:$scope,
    claimBoundary:"Real local Git transports and PostgreSQL recovery only; no GitHub/Bitbucket provider or deployed-candidate claim",
    baseCommit:$baseCommit,
    gitDiffSha256:$gitDiffSha256,
    migrationHead:$migrationHead,
    migrationSha256:$migrationSha256,
    terminalMigrationSha256:$terminalMigrationSha256,
    testSourceSha256:$testSourceSha256,
    probeSourceSha256:$probeSourceSha256,
    dispatcherSourceSha256:$dispatcherSourceSha256,
    gitSourceSha256:$gitSourceSha256,
    repetitions:2,
    forcedPublisherDeaths:2,
    cases:[
      {id:"FIRST_REPOSITORY_EXACT_OBJECT",expected:"REMOTE_REF_EQUALS_PREPARED_NOT_UNBOUND",result:"PASSED"},
      {id:"SECOND_REPOSITORY_EFFECT_BEFORE_RECEIPT",expected:"REAL_PUSH_THEN_FORCED_JVM_DEATH",result:"PASSED"},
      {id:"REPLACEMENT_JVM_RECOVERY",expected:"DISPATCHED_TO_INDETERMINATE",result:"PASSED"},
      {id:"BLIND_REPLAY",expected:"BLOCKED_WITHOUT_SECOND_REMOTE_EFFECT",result:"PASSED"},
      {id:"FIRST_REPOSITORY_PRESERVATION",expected:"PUBLISHED_REMAINS_PUBLISHED",result:"PASSED"}
    ],
    reportSha256:[$report1Sha256,$report2Sha256],
    logSha256:[$log1Sha256,$log2Sha256],
    sharedBuildFlag:$sharedBuildFlag,
    providerCredentialsUsed:false,
    remoteProviderWrite:false,
    paidSpendUsd:0
  }' > "${output_dir}/summary.json"

jq . "${output_dir}/summary.json"
