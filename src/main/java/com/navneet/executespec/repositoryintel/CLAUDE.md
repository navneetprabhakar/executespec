# Repository intelligence

`repositoryintel` is the provider-neutral, read-only evidence boundary for repository exploration.
It does not clone repositories, build indexes, generate documents, invoke LLMs, or silently turn an
unsupported capability into an empty-success result.

## Contracts

- `RepositoryIntelligencePort` accepts one bounded `RepositoryQuery` and returns a
  `RepositoryEvidenceBatch`.
- `RepositorySnapshotId` binds evidence to a repository/working-tree identity. The native adapter
  accepts only `native` engine/schema-v1 snapshots. An observed identity mismatch is
  `STALE_REJECTED`; missing or unverifiable identity is `UNAVAILABLE`. Neither returns evidence.
- Every requested `RepositoryCapability` has one explicit `CapabilityOutcome`; unavailable,
  partial, failed, stale, and genuine no-match results remain distinguishable.
- `RepositoryEvidence` records scope, kind, source path/lines, provenance, confidence, ambiguity,
  snapshot, content digest, and query fingerprint. Relationship evidence from the native adapter
  is heuristic and must never be presented as an AST-proven call graph.
- `QueryBudget` bounds adapter calls, per-capability and total hits, characters, estimated tokens,
  and one monotonic timeout across root resolution, snapshot probing, and capability calls.
  Timed-out operations are interrupted and reported as explicit failed/partial outcomes. Returned
  sizes cover the full JSON envelope, including required escaping, snapshot metadata, capability
  outcomes/reasons, and top-level accounting fields. The native adapter reserves worst-case
  outcome-envelope capacity before accepting evidence rows.
- Truncation is currently terminal. The port does not publish a cursor until a later contract can
  accept, authenticate, and advance one; a retry therefore cannot masquerade as page two.

## Native adapter checkpoint

`NativeRepositoryIntelligenceAdapter` currently wraps deterministic exact-text, symbol, and
heuristic-relationship search from `RepositorySearchService`. It can report semantic-index
readiness but deliberately returns `SEMANTIC_RETRIEVER_NOT_BOUND_TO_NATIVE_ADAPTER`; it does not
perform embeddings or provider-backed retrieval. AST, codemap, project-document, architecture, and
ADR adapters remain subsequent Phase 2 work. The first adapter is not yet registered as a Spring
bean and no planner/worker/reviewer caller has migrated to it.

Registration remains blocked until a caller supplies an access-checked tenant/actor-bound
repository handle (or an equivalent canonical registered root). `RepositoryRootResolver` still
returns a raw `Path`; exact project/snapshot binding does not authorize that filesystem root. Do
not register or inject this adapter into production callers until this authority boundary exists
and is tested.

## Change rules

- Unsupported relationships stay `UNAVAILABLE` or ambiguous; never infer/fabricate them.
- Preserve deterministic ordering and stable length-prefixed SHA-256 identifiers.
- Enforce repository-relative paths and exact snapshot/query identity on every returned item.
- A new evidence source must declare deterministic/model-derived provenance and add budget,
  freshness, unavailable, and truncation coverage before a production caller uses it.
- Provider calls and prompt integration require a later explicitly authorized phase.
