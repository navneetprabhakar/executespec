# `contractsurface/` — Brownfield public-contract preservation

`SpringHttpSurfaceExtractor` canonicalizes literal Spring servlet context, class mappings, method
mappings, HTTP method arrays, slash variants, and source locations.
It supports both a materialized workspace and an immutable map of repository-relative source
bytes. Surface schema v2 includes explicit context-path configuration locations in the digest, so
remote codemap generation preserves controller and configuration provenance without staging a
temporary checkout.
Both extraction paths use an instance-scoped JavaParser configured for Java 21; valid modern Java
syntax such as records must not collapse a completed worker outcome into a generic pipeline error.
An actual parse failure remains fail-closed and surfaces as the typed
`BROWNFIELD_HTTP_SURFACE_UNPARSABLE` diagnostic without leaking workspace paths.
`SpringContractSourceInventory` owns materialized-workspace discovery: it walks the complete tree
without a depth cutoff, prunes `.git`, vendor, dependency, and conventional build-output trees
before descent, filters tests only by explicit `src/test`, and enforces visited-entry,
candidate-file, and candidate-byte budgets. Non-excluded symbolic links are rejected rather than
silently omitted, and candidate reads allocate at most the remaining byte budget plus one sentinel
byte used to detect concurrent growth. Generic `test`, `tests`, and `generated` path segments
remain eligible because they may be repository or production package names. An unreadable path or
exceeded budget fails extraction; it must never produce a partial baseline that silently omits a
deep controller.
`SpringHttpSurfaceSnapshot` owns canonical ordering, route keys and digest construction separately
from source parsing; provider prose never participates in that digest.
`ExactHttpConstraintParser` is the shared provider-free parser for explicit `METHOD /path`
acceptance. It distinguishes required producer routes from route-bound 405/Method Not Allowed
constraints, binds suffix polarity only to the current route occurrence, and ignores explicit
removal instructions. Both `PlanSourceHttpRouteGate` and final deterministic HTTP verification must
use this parser rather than maintaining divergent regexes. Exact source polarity overrides a
contradictory planner restatement. A forbidden method must be absent while another supported method
exists at the same path; an `ANY` mapping violates the forbidden-method contract.
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
