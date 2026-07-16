# Ganesh E1/E2 local completion record — 2026-07-16

Status: LOCAL IMPLEMENTATION AND INTEGRATION CHECKS COMPLETE; LIVE GANESH SERVING
NOT PROVEN

## Repository identity

- checkout: `/Users/navneetprabhakar/Documents/antigravity/executespec`
- branch: `codex/ganesh-e2-e1-integration`
- base HEAD: `0ae95edf563765a06fa233fccea547b9f3925e29`
- disposition: implementation remains uncommitted and unpushed in the existing
  worktree
- Flyway head in this worktree: V274

## Implemented scope

### E2 — executable verification

- Added fail-closed executable verification for workspace tests, including
  compile/assertion failures, timeouts and process-tree cleanup, empty or
  all-skipped results, malformed output, missing toolchains, and missing
  evidence.
- Persisted normalized verifier evidence through V273 and propagated it through
  validation reports, API DTOs, events, and the verification ledger.
- Hardened lifecycle normalization and rescoring so model confidence cannot
  replace executable evidence.

### E1 — bounded Ganesh worker continuation

- Added one shared Ganesh continuation path for BUILD and PATCH worker flows.
- The route is dark by default and requires the feature flag, a configured
  endpoint and platform token, SYSTEM or HYBRID key mode, an eligible BUILD or
  PATCH task, and exactly one supported target file.
- Model routing is restricted to `ganesh-python`, `ganesh-go`,
  `ganesh-typescript`, `ganesh-javascript`, and `ganesh-java`.
- Ganesh remains internal worker-only: it is not exposed as a selectable run,
  planner, reviewer, model-matrix, or failover provider.
- Every Ganesh attempt must pass E2. A failed attempt permits exactly one retry
  with the original frontier worker, followed by E2 again; recursive Ganesh
  retry is prohibited.
- V274 persists BUILD/PATCH attempt evidence. Run detail and SSE surfaces expose
  that evidence, and explicit PATCH target files are retained through the run
  pipeline.

## Test evidence

- E2 focused set: 40 tests passed.
- E1 focused backend set: 68 tests passed.
- Latest complete Maven Surefire report set: 2,762 tests passed, 0 failures,
  0 errors, 0 skipped, across 435 suites.
- The first full-suite run found one stale provider-enum cardinality invariant.
  It was corrected to allow the internal `GANESH` enum while explicitly proving
  that Ganesh is absent from public model selection and failover. The subsequent
  full run passed.

The full-suite count above is taken from the generated Surefire XML reports for
the successful 2026-07-16 18:22 local run, rather than inferred from source-file
or annotation counts.

## Local runtime proof

The final integration boot used profiles `dev,local`, bound only to
`127.0.0.1:18080`, and used the existing loopback PostgreSQL and Redis services.
It produced the following results:

1. Flyway validated 275 migrations and advanced the local `public` schema from
   273 to 274.
2. Spring started in 12.812 seconds.
3. `GET /actuator/health` returned HTTP 200 with `status=UP`.
4. The supplied local web credential authenticated with role `USER`, tier
   `FREE`, and account status `ACTIVE`.
5. The authenticated model catalog exposed six frontier providers and no
   Ganesh provider or model selection.
6. `DELETE /api/auth/logout` returned HTTP 204.
7. Temporary authentication response and cookie material was deleted.
8. The service shut down cleanly; no process remained on port 18080.

No provider inference, Ganesh inference, deployment, preview, billing, email,
GPU use, model download, publication, or infrastructure creation occurred.

## Corrected runtime defects and incident transparency

The first E1/E2 integration boot failed transactionally because V274 attempted
to seed a nonexistent `feature_flag.enabled` column. Flyway rolled the migration
back and startup stopped. V274 was corrected to the established
`flag_value` / `value_type` / `scope` schema. The next boot applied V274 and
completed all runtime checks above.

An earlier local-profile startup also revealed unconditional GCP client
initialization. Secret Manager authentication and a GCS ADC token refresh were
attempted, but both credential operations failed before any secret/object read
or mutation. Local crypto is now disabled, and disabled GCS workspace storage
returns before client construction. Focused tests cover the relevant guards.
There was no successful cloud access, no cloud-state change, and no cloud
resource creation, but it would be inaccurate to claim that no network attempt
ever occurred.

## Remaining hard stop

This work proves ExecuteSpec's local routing, persistence, verification, API,
and lifecycle seams with mocks and local services. It does **not** prove that a
real Ganesh server can satisfy the worker's required OpenAI-compatible native
tool-call exchange. No Ganesh model was served and no end-to-end Ganesh worker
task was executed.

Before enabling E1, the separately controlled serving work must freeze and
verify the exact vLLM image/version, model and adapter hashes, chat template,
tool-call parser, authentication, controller lifecycle, timeout/health
contract, and fresh benchmark task identities. Any GPU, new infrastructure,
paid call, deployment, or model download remains subject to explicit approval.

## Related evidence

- `docs/plans/ganesh-local-runtime-verification-2026-07-16.md`
- `docs/plans/ganesh-worker-route-e1.md`
- `src/main/resources/db/migration/V273__persist_executable_verification_evidence.sql`
- `src/main/resources/db/migration/V274__persist_ganesh_worker_attempt_evidence.sql`
