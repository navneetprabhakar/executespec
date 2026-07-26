# Project source and linking package

## Project Chat confirmed actions (V292)

`project_chat_confirmation` stores only a token digest and canonical secret-free action scope.
Web/CLI grants are action/provider/model/target, actor, host-org and project bound, short-lived and
consumed once before confirmed architecture, generated-document or code-review work. Both
controller and service require `ProjectAccessGuard.assertCanWrite`; unsafe chat actions without a
confirmed handler stay unbound.

The supported actions are `REGENERATE_ARCHITECTURE`, `GENERATE_PROJECT_DOC`, and
`RUN_CODE_REVIEW`. They are explicit Web/CLI/OpenTUI buttons or commands, not structured proposals
emitted by the chat model. The issue response returns the 256-bit bearer once; clients retain it in
process memory only, while PostgreSQL stores SHA-256 token/target/scope hashes. Scope includes the
initiating actor, host org, project, canonical target, exact provider/model and immutable hard
budget. The grant expires in five minutes by default and is consumed in a committed `REQUIRES_NEW`
transaction under a pessimistic row lock before external work. Expiry, substitution, wrong actor or
tenant, and replay fail closed; executor failure never resurrects authority.

`ProjectChatController.info` returns `confirmedActionModels` resolved under the host org's matrix
overrides. Confirmed execution reuses one V282 action reference and exact route through repository
architecture, combined architecture, generated-doc, and asynchronous Code Review calls. Adaptive
routing and failover are prohibited. The initiating member receives usage attribution while key
resolution stays on the host project credential subject. Exact-route/context failure and the
architecture deterministic-fallback case return 503 rather than false success. Hard caps are:

- architecture: 1,000,000 total tokens, $10, 64 provider calls;
- generated docs: 200,000 total tokens, $3, 2 provider calls;
- code review: 300,000 total tokens, $5, 2 provider calls.

V292 also makes `activity_audit` append-only and serializes hash-chain writers with a transaction
advisory lock. A real V291→V292 migration rehearsal and a real PostgreSQL two-consumer race prove
schema idempotence/mutation rejection and exactly-one consumption respectively.

`ProjectRepo` is the provider-neutral project source registry; `ProjectRepoLink` attaches a source
to one or more projects. The legacy GitHub fields and `/api/repos` contracts remain supported.

## LOCAL sources (V278)

- `POST /api/projects/{projectId}/sources/local` is write-guarded and registers or renews a LOCAL
  connector. It accepts random client-minted UUIDv4 `localFolderId` and `installationId`, a bounded
  label/revision/capability set, small path-free metadata, and a 30-300 second lease.
- The server never accepts or stores an absolute local filesystem path. `source_key` for LOCAL is
  the opaque `localFolderId`; labels and metadata are not used to reconstruct a path.
- Registration is idempotent for `(owner_user_id, local_folder_id, installation_id)`. A different
  installation attempting to claim the same user/folder identity fails with 409.
- Connector state is derived from `source_lease_expires_at`: future means `ONLINE`, expired or
  absent means `OFFLINE`. Registration proves only `LINKED`, not readable/inventoried/ready source.
- LOCAL rows are user-owned. The controller and service both enforce project-write access: only a
  PROJECT_ADMIN/PROJECT_DEVELOPER member or an explicitly controller-validated ORG_ADMIN/
  SUPER_ADMIN may register or heartbeat one. Same-org membership alone is not authority, and a
  cross-org `PROJECT_VIEWER` cannot register one.
- The normal cloud clone pipeline rejects LOCAL rows with 422 — `ProjectRepoService.resolveForRunStrict`
  keeps LOCAL sources out of cloud runs unconditionally. This prevents a null GitHub identity from
  failing deep in clone/push code.

## Heartbeat + local-run binding (2026-07-17 slice)

- `POST /api/projects/{projectId}/sources/local/{localFolderId}/heartbeat` (write-guarded) renews
  the lease for the holding installation only: unknown folder → 404, foreign installation → 409,
  not linked to the project → 404. It never creates sources or links, and only overwrites the
  stored revision when a non-blank one is supplied (registration, by contrast, is a full metadata
  re-declaration and overwrites).
- **Local-run admission** (`ProjectRepoService.resolveForLocalRunStrict`, called from
  `RunController.submit` when `localExecution=true` and a `projectId` is present): every selected
  LOCAL source must be owned by the submitter, registered to the `RunRequest.localInstallationId`
  the client sent, and hold a currently-ONLINE lease — otherwise 422 before quota, billing, or any
  provider spend. SCM sources pass through unchanged (the client works in its own clone). Local
  submits with a projectId also now get the same `ProjectAccessGuard.assertCanWrite` as cloud
  submits (previously skipped entirely).
- The CLI half lives in `executespec-cli` `core/src/localSource.ts`: installation identity at
  `<configDir>/installation.json`, folder identity at `<dir>/.executespec/local-source.json`,
  `ensureLocalSourceRegistered` + `startLocalSourceHeartbeat` (45s cadence vs the 90s default
  lease); `run local` binds only on explicit intent (`--project` flag or the directory's link
  file), degrades to an unbound local run when the endpoint is absent (older backend), and fails
  closed on a 409 foreign-installation conflict.
- `LocalProjectSourceService` has TWO constructors; the production one is explicitly
  `@Autowired` (the package-private Clock overload is test-only). Removing that annotation breaks
  context startup — this was caught live, not by the mocked unit tests.

V278 makes the GitHub identity columns nullable only for LOCAL rows, backfills neutral SCM fields
for existing rows, and adds scoped unique/indexed local identities and lease lookup.

## SCM project sources (V284)

- New remote attaches resolve through `ScmRepositoryClientRegistry`; no project controller or
  service chooses a provider by testing which credential happens to exist. `GITHUB` remains the
  default only for the legacy request/row compatibility path.
- `scm_repository_id` is the immutable provider identity (GitHub numeric id / Bitbucket UUID) and
  is mandatory for every new remote attach. Slugs in `source_key` are display/assertion values and
  may change after a provider rename. V284 enforces owner-scope uniqueness on
  `(owner, scm_provider, scm_repository_id)` while leaving unverified legacy rows nullable.
  Bitbucket reads and syncs use the stored UUID after attach, then refresh the display slug from
  canonical provider metadata.
- Repo-level mutations use ownership authority, not visibility authority: syncing shared source
  metadata and installing a GitHub webhook require ORG_ADMIN for ORG-scoped repos and the owner for
  USER-scoped repos. The service repeats this check before any SCM credential lookup, provider call,
  or persistence even when a controller has already admitted the request.
- Repository topology and HEAD evidence have an explicit cache-invalidation boundary.
  `ProjectSourceFreshnessInvalidator` deletes the exact repo-scoped Workspace Health origin-SHA key
  and the exact linked project staleness keys after sync, after-commit link/unlink/create+link, and
  signed GitHub push/merged-PR observations. It resolves only persisted repo/link rows and never
  scans Redis keyspaces. Redis invalidation is fail-soft so a committed mutation or acknowledged
  signed webhook is not converted into a customer failure; the existing bounded TTL remains the
  outage fallback.
- Provider credentials are resolved server-side as short-lived `ScmCredential` values. DTOs and
  `project_repo` store only credential-free metadata and an HTTPS clone URL with no userinfo,
  query, or fragment.
- Bitbucket Cloud currently supports catalogue, attach, metadata sync, deterministic source
  readiness, and source browsing. It intentionally does **not** advertise cloud/local execution,
  architecture/codemap generation, code/PR review, GitHub webhooks, `.executespec` bootstrap, or
  PR links. Those actions are hidden in Web and rejected at the server capability boundary.
- GitHub and LOCAL behavior remains compatible: legacy GitHub rows derive their old capabilities
  from the registered GitHub adapter; LOCAL rows retain their lease-bound local-hands contract.

## Review admission (V281)

`ProjectRepoService.resolveGitHubReviewRepoStrict` is the canonical admission boundary for the
GitHub-backed PR Review and Code Review surfaces. Both require a `repoId` that is currently linked
to the named project. The caller-supplied `repoFullName` is only a compatibility assertion: a
mismatch is rejected, while the durable `ProjectRepo.githubRepoFullName` is passed downstream.
LOCAL and non-GitHub SCM sources are rejected before quota checks or provider spend.
`ProjectAccessGuard.assertCanWrite` rejects `PROJECT_VIEWER` and unassigned same-org users; writes
require SUPER_ADMIN, host ORG_ADMIN, or explicit PROJECT_ADMIN / PROJECT_DEVELOPER membership.

## Least-privilege project membership (V285)

V285 idempotently backfills missing `PROJECT_ADMIN` memberships for project creators before the
write guard removes the old same-org compatibility grant. Direct member administration validates
the three supported project roles and is host-org-only; cross-org collaborators must use the
expiring, email-bound project invite/accept flow so external-sharing policy and consent cannot be
bypassed with a raw user id.

## Durable semantic code-index readiness (V286)

- `project/rag/CodeIndexingService` no longer treats the presence of any V223 chunk as readiness.
  `project_code_index_state` is authoritative per `(project_id, repo_id)` and binds every result to
  the repository's current source SHA/revision.
- Lifecycle is explicit: `UNINDEXED`, `INDEXING`, `READY`, `PARTIAL`, `STALE`, `FAILED`. READY
  requires a pinned revision, a non-truncated inventory, no deferred/failed files, and 100% of
  eligible files covered. Counts, coverage, truncation and fixed failure reasons are durable and
  exposed through `GET /api/projects/{projectId}/code-index`.
- Index workers acquire a database lease with a monotonically increasing fencing token. Chunk
  replacement/deletion checks the exact unexpired owner/token, so an expired JVM cannot publish
  after a restart takeover. Partial refresh-cap runs are resumable because unchanged files do not
  consume the next pass's refresh allowance.
- Inventory traversal is deterministic, symlink-safe and bounded. Manifest/build/source paths are
  prioritised, deleted/no-longer-indexable files are removed, and an inventory ceiling is reported
  as `PARTIAL` rather than hidden.
- Credential-like paths are rejected through `SensitiveWorkspacePathPolicy` before file reads, and
  high-confidence credential content is rejected through `GeneratedSecretScanner` before chunking
  or embedding. Because excluded paths do not enter the current eligible set, a successful refresh
  also deletes any older chunks for a file that has become sensitive.
- `search_code` remains an embedding-backed chunk similarity aid, not a language-aware relationship
  graph. The response and Repos UI explicitly report `relationshipGraphAvailable=false`; the
  ripgrep-backed `search_repo_files` path stays available for exact/regex lookup whenever semantic
  readiness is absent or partial. FastContext is not referenced by this production path.
- The explicit refresh endpoint may return HTTP 202 with `status=UNAVAILABLE` when the governed
  embedding route cannot run. Web reads the response status (`QUEUED | UNAVAILABLE | PARTIAL |
  READY`) and fixed reason instead of translating every accepted response into “queued”; current
  readiness status/reason and the RIPGREP fallback remain visible. Refresh is idempotent for
  `READY` and already-`INDEXING` states: it returns the current admission status without scheduling
  redundant provider work.
- The Repos surface keeps code-index loading, loaded-empty and failed states distinct. A failed
  readiness request renders a retryable unknown-state notice and preserves the last known
  projection; it never hides the subsystem or treats an outage as evidence that no semantic index
  exists.
- V290 makes provider policy part of readiness. The current 1536-dimensional schema supports only
  governed OpenAI `text-embedding-3-small`; a project selected to DeepSeek, Gemini, Anthropic,
  OpenRouter or Fireworks (or an OpenAI OWN_KEYS project with no current allowed key) is reported as
  lexical-only. It cannot dispatch through a hidden platform OpenAI key, and an old READY state is
  dynamically exposed as STALE until the exact compatible route is available again.

## System Map UI evidence states

- Current map, history, rules/violations, decisions and repository-contract coverage retain
  separate loading/failure/loaded-empty states. A secondary request failure cannot become “No
  previous versions”, “No rules”, an all-PASS rules list, an empty decision cache or “Link repos”.
- Navigating between projects clears every secondary System Map projection before the next
  project's requests settle. The generation fence still drops late responses, while the eager
  clear prevents the prior project's history/rules/contracts from appearing under the new project
  route.

## Hostile project-document admission (V288)

- `ProjectDocumentService` reads multipart streams through a hard byte ceiling and treats the
  declared MIME and original filename as untrusted display metadata. `DocumentTypeInspector`
  recognizes only strict UTF-8 text, PDF and bounded non-macro OOXML (DOCX/XLSX/PPTX); ordinary
  archives, traversal/duplicate entries, polyglots, binary/legacy Office formats and compression
  bombs are rejected before persistence.
- Every required upload passes the local `DocumentMalwareScanner` contract. Enterprise and the
  default configuration fail closed on scanner unavailable/error; the deterministic baseline pins
  EICAR rejection without a network dependency. It is an integration seam, not a claim that the
  built-in signature check replaces a full enterprise AV engine.
- Tika extraction is hard-bounded and returns typed page/character/token evidence. Per-file and
  transactionally serialized per-project document/byte/page/text/token quotas are enforced before
  the accepted row and blob commit. Extracted text also passes `GeneratedSecretScanner` before the
  blob/row commit; restart processing repeats that check before writing a workspace copy, chunking,
  lexical publication or embedding dispatch. Credential-bearing content fails with the stable
  `CREDENTIAL_CONTENT_DETECTED` code and the matched value is never retained or logged.
- User filenames are NFKC-normalized display labels only. Storage uses
  `docs/.uploads/<document-uuid>.bin`, while `project_document_blob` is authoritative for new
  uploads so a restarted JVM can reconstruct its workspace copy. Internal path, actor and parser
  reason fields are excluded from API JSON; clients receive only a stable `errorCode`.
- `ProjectDocumentIndexJob` is the real async event listener (the old self-invoked `@Async` method
  was synchronous) and scans stale UPLOADED/EXTRACTING/EMBEDDING/RETRY_PENDING rows. A row-locked
  lease carries an owner, monotonic fencing token and expiry. Every phase transition and the atomic
  chunk-replace+READY transaction revalidates the exact live fence; cancellation invalidates it.
  An expired JVM therefore cannot publish after another JVM takes over.

## Governed document/code retrieval (V290)

- `EmbeddingService` no longer owns or reads a global key directly. It first resolves the
  project's selected provider and current org key-source/allow-list, creates an `EMBEDDING_JOB`
  V282 context, and asks `LlmExecutionDispatchGuard` for the exact current USER/ORG/PLATFORM source.
  PLATFORM is possible only when SYSTEM/HYBRID policy explicitly authorized it; OWN_KEYS cannot
  fall through. Every physical embedding attempt writes a V283 provider/model/key-source/tenant/
  user/project/token/cost/latency success-or-failure row.
- V290 adds `retrieval_mode`, `embedding_provider`, and `embedding_model` to
  `project_document`; historical READY rows are truthfully backfilled to OpenAI
  `text-embedding-3-small`. `project_document_chunk.embedding` is nullable so lexical-only chunks
  are durable without fabricated zero vectors. Semantic SQL requires `retrieval_mode=SEMANTIC` and
  a non-null vector.
- Unsupported/missing-key document jobs finish as `LEXICAL_READY` and PostgreSQL full-text search
  remains available after restart. A governed semantic-query outage also degrades to the same
  bounded lexical corpus. The document API/Web label `SEMANTIC READY` versus `LEXICAL READY` with a
  fixed readiness reason; it never exposes a historical vector as currently semantic-ready after
  route drift.
