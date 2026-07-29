# Repository intelligence

`repositoryintel` is the provider-neutral, read-only evidence boundary for repository exploration.
It does not clone repositories, build indexes, generate documents, invoke LLMs, or silently turn an
unsupported capability into an empty-success result.

## Contracts

- `RepositoryIntelligencePort` accepts one bounded `RepositoryQuery` and returns a
  `RepositoryEvidenceBatch`. Every query carries a server-derived `RepositoryAccessContext`; the
  actor and host-tenant identity participate in the query fingerprint.
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
- `RepositoryTarget` is the pre-snapshot authority identity: project, repository, authenticated
  actor, and host tenant only. It deliberately carries no caller path or snapshot assertion. Both
  `RepositoryTarget` and `RepositoryAccessContext` have package-private constructors: controllers,
  request binders, provider/model output, and other packages cannot mint authority values from
  caller-controlled identifiers. Construction belongs to a server-side authority adapter in this
  package.
  `AccessCheckedRepositorySnapshotService` resolves that target through the same production
  authority boundary, captures the repository-wide commit/inventory identity through
  `RepositorySearchService.snapshot`, requires the observed commit to equal the durable source
  revision, and returns a native schema-v1 `CURRENT` snapshot only for a clean Git worktree.
  Tracked modifications and non-ignored untracked files are explicit dirty state and cannot be
  certified as commit evidence. Snapshot capture fences Git SHA and dirty status around inventory
  hashing and fails closed if either moves. Inventory identities hash repository-relative paths and
  content, so rematerializing the same tree under another server path does not change its identity.
  It never clones, indexes, or calls a provider.

## Native adapter checkpoint

`NativeRepositoryIntelligenceAdapter` currently wraps deterministic exact-text, symbol, and
heuristic-relationship search from `RepositorySearchService`. It also delegates CODEMAP and
per-repository ARCHITECTURE reads to `PersistedProjectEvidenceSource`, which reads only artifacts
that already exist in `ProjectCodeMapRepository` / `RepoArchitectureDocRepository`; it never
generates, refreshes, indexes, embeds, or calls a provider. CODEMAP evidence is deterministic,
confidence `1.0`, `UNAMBIGUOUS`, and pinned to the exact current authorized handle/source SHA,
except that model-generated `**Purpose:**` summary lines are excluded. Partial inventories remain
explicitly `PARTIAL`. ARCHITECTURE evidence is explicitly model-derived, confidence `0.65`,
ambiguity `UNKNOWN`, and is accepted only when both its source SHA and `sourceCodeMapId` match the
exact current codemap. Fallback/failed architecture documents are unavailable, not evidence.

The adapter can report semantic-index readiness but deliberately returns
`SEMANTIC_RETRIEVER_NOT_BOUND_TO_NATIVE_ADAPTER`; it does not perform embeddings or provider-backed
retrieval. Project documents, ADRs, AST-backed relationships, semantic retrieval and pipeline
consumers remain open. The native adapter is registered as the current
`RepositoryIntelligencePort`, but no planner/worker/reviewer caller has migrated to it. This
checkpoint adds evidence plumbing only and makes no repository-understanding or generated-code
quality claim.
`RepositoryEvidenceBudgetCounter` owns the shared hit/character/token output accounting so the
registered adapter remains at the 600-line architecture ceiling without weakening that gate.
Each capability read is independently fenced against the requested repository-wide commit and
inventory identity; evidence is discarded as stale if the tree changes after the initial probe.
The search implementation also rehashes each decoded file and the final repository inventory before
returning, rather than labeling mutable bytes with a pre-read fingerprint.

`AccessCheckedRepositoryRootResolver` is the only production root resolver. Query resolution
remains snapshot-bound for the native adapter; target resolution exists only to capture the snapshot
before a query can honestly be constructed. Both paths reload the active actor and authoritative org
membership, apply `ProjectAccessGuard`, and require an active host tenant/project, an exact live
project-repository link, coherent source ownership, and SCM + `READY` source state. Query resolution
also requires the persisted revision to match the asserted snapshot. The resolver derives the
existing code-index clone identity through `CodeIndexWorkspaceIdentity`; callers cannot provide a
path. The resulting `AuthorizedRepositoryHandle` always binds actor, tenant, project, and
repository; query handles additionally bind the asserted snapshot, while target handles can only be
consumed by snapshot capture. USER-scoped repositories additionally require the current actor to be
the private owner/linker. Every consumer repeats its matching identity and filesystem-object check
before reading, so replacing an authorized root with a symlink or another directory fails closed.
Missing roots, roots outside
`agent.workspace`, symlink materialization segments and missing/symlinked `.git` boundaries fail
closed. Resolution never clones, refreshes, or indexes.

Registration does not migrate or activate a pipeline consumer. The next integration must construct
the access context from the authenticated server principal and must not accept actor/tenant values
from an untrusted request body. As of this checkpoint there is deliberately no production caller or
principal-to-`RepositoryTarget` factory; bean registration alone is not activation.

## Change rules

- Unsupported relationships stay `UNAVAILABLE` or ambiguous; never infer/fabricate them.
- Preserve deterministic ordering and stable length-prefixed SHA-256 identifiers.
- Enforce repository-relative paths and exact snapshot/query identity on every returned item.
- A new evidence source must declare deterministic/model-derived provenance and add budget,
  freshness, unavailable, and truncation coverage before a production caller uses it.
- Provider calls and prompt integration require a later explicitly authorized phase.
