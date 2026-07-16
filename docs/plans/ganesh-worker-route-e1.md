# Ganesh worker route (E1)

Status: implemented dark; `worker.ganesh.enabled` defaults to `false` in V274.

Ganesh is an internal worker substitution, not a run-level planning or review provider. A run keeps
its selected frontier provider/model for planning and review. The shared BUILD/PATCH policy may
substitute exactly one of `ganesh-python`, `ganesh-go`, `ganesh-typescript`, `ganesh-javascript`, or
`ganesh-java` only with explicit endpoint/platform-token configuration, SYSTEM or HYBRID key mode,
and exactly one declared `.py`, `.go`, `.ts`, `.js`, or `.java` target.

E2 runs after Ganesh. Any non-PASSED verdict triggers the one allowed retry on the exact original
frontier provider/model. E2 runs again; another missing/failing verdict is terminal. Both attempts
are persisted in `ganesh_worker_attempt` and emitted as `GANESH_E2_ATTEMPT`. Evidence retains a
bounded output tail and SHA-256, not an unbounded raw process transcript.

No endpoint, provider, GPU, cloud resource, or paid service is created or called by this change.
