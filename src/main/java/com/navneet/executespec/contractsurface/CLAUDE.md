# `contractsurface/` — Brownfield public-contract preservation

`SpringHttpSurfaceExtractor` canonicalizes literal Spring servlet context, class mappings, method
mappings, HTTP method arrays, slash variants, and source locations.
`BrownfieldHttpContractGuard` stores the admitted surface/digest on `pipeline_run` (V360) and
compares it after workers/corrections and before publication. It is also enforced inside
`RunValidationKickoff`, so every normal, crash-resume, retry-validation, and re-review entrypoint
must pass compatibility before a fresh validation report can be persisted. Approved corrections
run exact-route and baseline checks before the read-only reviewer, then repeat them during final
validation.

An existing route may disappear only when the run's durable `gate:plan:{runId}` APPROVE receipt and
its referenced contract report cross-validate the exact canonical evidence digest, actor,
fingerprint, report digest, and uppercase `BREAKING` acknowledgement. Missing, malformed, stale, or
mismatched authorization fails closed. The digest-bound report must additionally contain the exact
canonical removals under `brownfieldHttpRouteRemovals: [{method, path}]`; the authorized set must
equal the actual missing set. A generic or unrelated BREAKING acknowledgement grants no removal
authority. Never add a caller-controlled production bypass.

`PlanContractGateService` is the deterministic producer for that field. It emits a route-removal
row only when the admitted plan/requirement contains an explicit removal verb plus exact HTTP
method/path and that tuple exists in this run's immutable baseline. Preserve/alias language,
generic “breaking change” prose, and exact tuples absent from the baseline emit no authority. Its
schema-v1 report envelope retains the existing registered-contract rows under `rows`; the web parser
remains backward-compatible with historical bare row arrays.
