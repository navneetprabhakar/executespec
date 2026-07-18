# GLM 5.2 OpenRouter SWE-bench Lite-10 readiness

**Audited:** 2026-07-18 IST
**Decision:** `NO-GO / ZERO PROVIDER SPEND`
**Planned claim:** deterministic focused 10-case experiment, not a full-suite or leaderboard claim

This is the pre-spend gate for the founder-approved order: GLM 5.2 OpenRouter Lite-10, then native
DeepSeek V4 Pro Lite-300, then native DeepSeek V4 Pro public Pro-731. It does not remove the original
conditional Terminal-Bench and GLM TB-subset requirements.

## Proven ready

- `benchmark-instance` is healthy on GCP `executespecv1`, zone `us-east4-a`.
- VM shape: `e2-standard-16`, 62 GiB RAM, 1 TiB disk.
- Benchmark API and public UI return HTTP 200 under the `benchmark` profile.
- Deployed JAR SHA-256:
  `daa2d31e7dcd9c0e8248b2573b0489188f5cbd8e19de4d243031e14a98176222`.
- SWE-bench Lite dataset has 300 rows; file SHA-256:
  `a1d8b49a4118716578646889417ad860535567a19298e61fc146fc7fa217cd9a`.
- Official scorer is `swebench 4.1.0`.
- All ten selected tasks and cached images exist; image digests must be copied into the frozen
  manifest rather than relying on mutable tags.
- Route is ExecuteSpec `openrouter/glm-5.2` → OpenRouter `z-ai/glm-5.2`, temperature 0, 16,000 max
  output tokens, 220 worker iterations, same-provider retry bounded to four, cross-provider fallback
  disabled.
- Assembled patch prompt is 7,583 characters, SHA-256
  `2aec80c6af57ac09686723496c3025d9c7ef4019c3c8ea36407c55ed83e332b1`.
- Generation concurrency is currently 2; planned official scoring concurrency is 4.
- Local code now carries a harness-supplied absolute epoch deadline and shutdown reserve through
  the admin request, `EngineBudget`, `CodeGenEngineImpl` and `WorkerTaskRequest`. The server clamps
  the deadline to its configured task maximum and will not accept a reserve below 120 seconds.
  Focused Java deadline/engine tests and all 41 `k-eval.py` tests pass. Deployment/VM proof is still
  required before spend.

## Dispatch blockers

1. **The approved OpenRouter key is not injected into the benchmark process or benchmark org.** A
   local approved credential source contains an `OPEN_ROUTER` entry, but the Java process lacks
   `OPENROUTER_API_KEY`, the synthetic benchmark org has no encrypted OpenRouter key, and no
   benchmark OpenRouter Secret Manager entry exists. Normalize and inject it through a protected
   `0600` environment file or Secret Manager, never shell history or tracked files; verify only a
   fingerprint/availability signal, never the value.
2. **The approved account cap implementation is local-only, not deployed.** The runner now requires
   $2/task, a $20 command-wide campaign and the exact no-top-up opening balance of $24.897977. It
   atomically reserves across every `k` pass before dispatch and uses an OS lock across duplicate
   runner processes; the admin API can only tighten the configured server cap. Deploy and
   re-run the zero-spend tests before this blocker closes.
3. **The VM runner is stale.** VM `k-eval.py` SHA-256 is
   `e8dbbfaad413d6fc64feab12767ed286860db13227ccf1d98594b5b36efd2565`; repository SHA-256 is
   `e1f79cf47e65ed79ff160b5a8c24f3b60797e121a8351bb24069513f481dfe6e`. The stale copy lacks token
   capture and exact manifest/result guards.
4. **The repository runner gates are implemented locally, not deployed.** Tracker creation/events
   fail closed; failed/401 rows never count as complete; 401 refresh retries exactly once under the
   same reservation; duplicate/malformed terminal rows fail; campaign reservations are durable.
   VM checksum parity and deployed smoke evidence remain required.
5. **VM-side short-lived admin auth is absent.** `.espec-admin.env` is missing. Use a protected
   short-lived token or a `0600` credential file and remove it after the run.
6. **Full durable trajectories are absent.** Persist redacted JSONL continuously per task, including
   messages, assistant content, tool calls/results, retries, provider usage and terminal reason.
   Store path and SHA-256 in tracker and manifest.
7. **Absolute-deadline implementation is not deployed or proven on the VM.** The local runner now
   sends one per-task absolute deadline plus cleanup reserve, the server clamps it against
   `swebench.task-wall-timeout-minutes`, and the engine passes it to the worker/tool enforcement
   already used by other execution lanes. Deploy the exact JAR and runner, then prove a deliberately
   short no-provider deadline returns before the HTTP timeout and leaves no worker/container behind.
8. **Tracker schema/UI are incomplete for the goal.** Add trajectory path/hash, active attempts,
   ETA and submission readiness; prove reload, SSE reconnect and backend restart rebuild.
9. **The local dataset/image freeze is complete; the deployed execution tuple is not.** The frozen
   dataset and manifest below pin row hashes, base commits and immutable evaluator-image manifest
   and config digests. The deployed JAR still lacks Git build metadata. Before spend, freeze the
   deployed Git SHA, dirty declaration, JAR SHA, scorer version, prompt bytes/hash, exact serialized
   tool-schema bytes/hash, flags, timeouts, retries, concurrency and actual upstream provider.
10. **OpenRouter upstream is not deterministic.** Requests use `provider.sort=latency`; record the
    actual upstream provider per physical attempt and disclose this in the focused claim.

## Frozen case order

1. `astropy__astropy-12907`
2. `astropy__astropy-14182`
3. `astropy__astropy-14365`
4. `astropy__astropy-14995`
5. `astropy__astropy-7746`
6. `astropy__astropy-6938`
7. `django__django-10914`
8. `django__django-11001`
9. `django__django-10924`
10. `django__django-11019`

Do not substitute `--limit 10` against the full dataset. Create an exact ordered ten-row file and
validate every ID, base commit, image digest and result cardinality before dispatch.

## Frozen local artifacts

- Exact ordered dataset:
  `infra/benchmark/datasets/swebench-lite10-glm-5.2-2026-07-18.json`
  - 10 rows, 143,698 bytes
  - SHA-256: `b498cf6d7af26f6f97ddbf7927f0cd5e0e47d115d3d775318b4515f3887a3950`
- Reproducibility manifest:
  `infra/benchmark/datasets/swebench-lite10-glm-5.2-2026-07-18.manifest.json`
  - 12,641 bytes
  - SHA-256: `c2674d897a826b7fb10f49451a8e9c54a08d600743f2a5592f7756b1f3cf383a`
- Validator: `infra/benchmark/scripts/validate-lite10-freeze.py`
  - SHA-256: `61f676de58eea6dc224bbc1f5e4d20cd1b1d73e9f86ef0ba6214fe4782010de0`

The frozen rows are structural copies of the matching rows in the authoritative local 300-row
`swebench_lite_test.json`; no field or value was dropped or rewritten. The manifest independently
records each canonical row hash, base commit, repository/version, Docker Hub manifest digest,
container config digest, immutable `name@sha256:...` reference and `linux/amd64` platform. Image
digests were resolved read-only with `docker manifest inspect --verbose`; mutable `:latest` tags are
recorded only as provenance and must not be used for dispatch.

The model matrix permits 32,000 output tokens for `glm-5.2`, while PATCH execution deliberately
sets `CodeGenEngineImpl.MAX_TOKENS_PER_CALL=16_000`; the manifest records both values and the 16,000
effective request limit. It also freezes temperature 0, 220 iterations, four bounded same-provider
attempts, no cross-provider fallback, a 3,600-second task deadline with a 120-second shutdown
reserve, generation concurrency 2, scoring concurrency 4, official scorer 4.1.0, 1,800-second
verifier timeout, `k=1`, denominator 10 and the approved no-top-up budget gates.
The local machine's `swebench 3.0.17` was not used to transform or score the rows.

Deterministic validation command:

```bash
python3 infra/benchmark/scripts/validate-lite10-freeze.py \
  --source "$HOME/swebench-bench/swebench_lite_test.json" \
  --frozen infra/benchmark/datasets/swebench-lite10-glm-5.2-2026-07-18.json \
  --manifest infra/benchmark/datasets/swebench-lite10-glm-5.2-2026-07-18.manifest.json \
  --verify-registry
```

Recorded result on 2026-07-18: source 300/300 at
`a1d8b49a4118716578646889417ad860535567a19298e61fc146fc7fa217cd9a`; frozen
10/10 ordered and unique; 10/10 source rows equal after JSON parsing; 10/10 base commits and row
hashes valid; 10/10 immutable image/config digests valid on `linux/amd64`; route and denominator
match `openrouter/glm-5.2` / `z-ai/glm-5.2` / 10; 10/10 live registry manifest digests and 10/10
registry config digests match the freeze.

## Acceptance sequence

1. Close all blockers above with deterministic tests and deploy the exact complete JAR/runner.
2. Record opening provider balance and reserve the $20 campaign before any provider call.
3. Create the tracking run fail-closed and verify authenticated UI/API visibility.
4. Dispatch at generation concurrency 2 with per-task $2 and campaign $20 limits.
5. Persist/reconcile trajectory, provider attempts, tokens and costs continuously.
6. Score only after 10/10 non-duplicate terminal predictions exist; use official scorer concurrency 4.
7. Independently review all patches and verifier outcomes; never use ExecuteSpec's internal score as
   the benchmark oracle.
8. Publish only a focused-subset result with hashes, commands, costs, failures and limitations.

No paid run is authorized until all steps through fail-closed tracking and budget reservation are
provably ready in the deployed VM tuple. Local implementation alone is not authorization.
