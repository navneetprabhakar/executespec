#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 <base-url> <token-file> <output-dir> <source-sha> <artifact-sha256> <graph-sha256> <graph-manifest-sha256> [expected-shared-build]" >&2
  exit 64
}

[[ $# -ge 7 && $# -le 8 ]] || usage

base_url=${1%/}
token_file=$2
output_dir=$3
expected_source_sha=${4,,}
expected_artifact_sha=${5,,}
expected_graph_sha=${6,,}
expected_graph_manifest_sha=${7,,}
expected_shared_build=${8:-false}

[[ -f "$token_file" ]] || { echo "token file not found" >&2; exit 66; }
[[ "$expected_source_sha" =~ ^[a-f0-9]{40}$ ]] || { echo "invalid expected source SHA" >&2; exit 64; }
for digest in "$expected_artifact_sha" "$expected_graph_sha" "$expected_graph_manifest_sha"; do
  [[ "$digest" =~ ^[a-f0-9]{64}$ ]] || { echo "invalid expected SHA-256" >&2; exit 64; }
done
[[ "$expected_shared_build" == "true" || "$expected_shared_build" == "false" ]] || {
  echo "expected-shared-build must be true or false" >&2
  exit 64
}

mkdir -p "$output_dir"
chmod 700 "$output_dir"
curl_config=$(mktemp)
trap 'rm -f "$curl_config"' EXIT
chmod 600 "$curl_config"
token=$(tr -d '\r\n' < "$token_file")
[[ -n "$token" ]] || { echo "token file is empty" >&2; exit 66; }
printf 'silent\nshow-error\nfail-with-body\nheader = "Authorization: Bearer %s"\n' "$token" > "$curl_config"
unset token

raw="$output_dir/control-plane-release-attestation.json"
curl --config "$curl_config" \
  "$base_url/api/admin/control-plane-release-attestation" \
  --output "$raw"
chmod 600 "$raw"

jq -e \
  --arg source "$expected_source_sha" \
  --arg artifact "$expected_artifact_sha" \
  --arg graph "$expected_graph_sha" \
  --arg graph_manifest "$expected_graph_manifest_sha" \
  --arg shared "$expected_shared_build" '
    .schemaVersion == 1 and
    .authority == "READ_ONLY_RUNNING_RELEASE_SNAPSHOT_V1" and
    .evidenceComplete == true and
    (.bundleSha256 | test("^[a-f0-9]{64}$")) and
    .release.complete == true and
    .release.runningSha == $source and
    .release.artifactSha256 == $artifact and
    .schema.complete == true and
    .schema.attestation.databaseReachable == true and
    .schema.attestation.ready == true and
    (.schema.exported.appliedMigrations | length > 0) and
    (.schema.exported.appliedMigrations | all(.success == true)) and
    (.schema.exported.appliedMigrations[-1].version == .schema.attestation.latestSuccessfulMigration) and
    (.schema.exported.definitions | length == (.schema.attestation.artifacts | length)) and
    (.schema.exported.definitions | all(
      .present == true and .active == true and .digestMatchesAttestor == true and
      (.definition | type == "string" and length > 0) and
      (.definitionSha256 | test("^[a-f0-9]{64}$")))) and
    (.featureFlags | length == 4) and
    (.featureFlags | all(.persisted == true and .processParity == true)) and
    ((.featureFlags[] | select(.key == "build.shared-code-change-loop.enabled") | .effectiveValue) == $shared) and
    .runtime.complete == true and
    .runtime.imagePinnedByDigest == true and
    .runtime.nonRootIdentity == true and
    .runtime.sandboxWorkspaceRootAbsolute == true and
    .runtime.agentWorkspaceRootAbsolute == true and
    .graph.complete == true and
    .graph.graphSha256 == $graph and
    .graph.graphManifestSha256 == $graph_manifest and
    (if $shared == "false" then .safeDarkState == true and (.reasons | length == 0)
     else .safeDarkState == false and (.reasons | index("SHARED_BUILD_ADMISSION_ENABLED") != null)
     end)
  ' "$raw" >/dev/null

raw_sha=$(shasum -a 256 "$raw" | awk '{print $1}')
jq \
  --arg capturedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg endpoint "$base_url/api/admin/control-plane-release-attestation" \
  --arg rawSha256 "$raw_sha" '
    {
      capturedAt: $capturedAt,
      endpoint: $endpoint,
      rawSha256: $rawSha256,
      bundleSha256: .bundleSha256,
      runningSha: .release.runningSha,
      artifactSha256: .release.artifactSha256,
      latestSuccessfulMigration: .schema.attestation.latestSuccessfulMigration,
      appliedMigrationCount: (.schema.exported.appliedMigrations | length),
      definitionCount: (.schema.exported.definitions | length),
      featureFlags: .featureFlags,
      runtime: .runtime,
      graph: .graph,
      evidenceComplete: .evidenceComplete,
      safeDarkState: .safeDarkState,
      result: "PASSED"
    }
  ' "$raw" > "$output_dir/summary.json"
chmod 600 "$output_dir/summary.json"

echo "control-plane release attestation PASSED"
echo "raw=$raw"
echo "raw_sha256=$raw_sha"
echo "summary=$output_dir/summary.json"
