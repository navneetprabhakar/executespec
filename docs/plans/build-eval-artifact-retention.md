# BUILD evaluator artifact contract

`scripts/build-eval.sh` runs the production BUILD path with hidden acceptance tests. Each completed
run now retains the server's canonical workspace ZIP under
`scripts/build-eval-results/artifacts/` and writes one JSONL record to `completed-runs.jsonl`
containing the run ID, archive SHA-256, artifact source, and held-out result.

The script prefers the live workspace for immediate scoring, but downloads
`GET /api/runs/{runId}/download?format=zip` as a retention copy. If the local workspace has already
been cleaned, it downloads that same endpoint, safely extracts the ZIP, and runs the hidden tests
against the extracted directory. The extraction rejects absolute/parent-traversal paths and symlink
entries. The endpoint may return a short-lived GCS redirect; `curl --location` follows it.

The archive and JSONL sidecar are evaluator inputs, not a benchmark score by themselves. An
independent evaluator must verify the recorded SHA-256 and rerun the held-out tests from the archive.
