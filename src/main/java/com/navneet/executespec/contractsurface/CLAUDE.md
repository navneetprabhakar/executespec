# `contractsurface/` — Brownfield public-contract preservation

`SpringHttpSurfaceExtractor` canonicalizes literal Spring servlet context, class mappings, method
mappings, HTTP method arrays, slash variants, and source locations.
`BrownfieldHttpContractGuard` stores the admitted surface/digest on `pipeline_run` (V360) and
compares it after workers/corrections/validation and before publication.

An existing route may disappear only when the run's durable `gate:plan:{runId}` APPROVE receipt and
its referenced contract report cross-validate the exact canonical evidence digest, actor,
fingerprint, report digest, and uppercase `BREAKING` acknowledgement. Missing, malformed, stale, or
mismatched authorization fails closed. Never add a caller-controlled production bypass.
