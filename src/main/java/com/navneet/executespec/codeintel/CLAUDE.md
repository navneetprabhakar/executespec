# `codeintel/` — project architecture and generated documents

## Provider and model-matrix admission (2026-08-02)

Architecture refresh and generated-document jobs fail before dispatch: missing provider returns
`PROJECT_PROVIDER_NOT_CONFIGURED` (409), invalid persisted provider returns
`PROJECT_PROVIDER_INVALID` (409), and unavailable/incomplete matrix returns
`MODEL_MATRIX_UNAVAILABLE` (503). Rejection creates no durable job or provider call.

`ProjectArchitectureService` owns the project-level architecture pipeline. Per-repository codemaps,
repository architecture documents, and the combined architecture document remain source evidence;
provider-generated prose is never stronger than the cited repository snapshot.

`CodeMapGenerator` delegates the bounded exact-SHA evidence lane to
`SpringHttpArchitectureEvidenceRenderer`, which renders a versioned block from
`SpringHttpSurfaceExtractor` output. The block
contains the canonical method + externally reachable path, controller source location, explicit
`server.servlet.context-path` configuration provenance, schema version, and digest. A truncated
inventory, bounded-source limit, failed source read, or unsupported mapping is rendered explicitly
as `UNAVAILABLE`; downstream generators must not infer routes from it. Legacy same-SHA codemaps and
architecture documents without the current block are not cache-reusable.

`RepoArchitectureGenerator` appends that exact trusted block after the provider response, replacing
any provider-emitted lookalike marker. `ArchitectureDocGenerator` similarly appends every
repository's exact post-LLM block to the combined map. These deterministic appendices are the
authoritative HTTP surface; surrounding model prose remains explanatory only.

## Durable system-map refresh (V353)

The web console uses `POST /api/projects/{id}/architecture/generate-async` and polls
`GET /api/projects/{id}/architecture/jobs/{jobId}`. `GET .../jobs/active` resumes an active poll
after mount/reload; `GET .../jobs/latest` also restores terminal/NEEDS_CONFIRMATION truth. The
compatibility `POST .../architecture/generate`, per-branch `analyse`, and per-repository regenerate
route now admit the same project-wide durable job and return 202; they must not restore
provider-duration HTTP requests or expose mixed project/repository versions.

`ProjectArchitectureJobStore` persists
QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/NEEDS_CONFIRMATION plus
PREPARING/EXECUTING/PUBLISHING, timestamps, cancellation/deadline state, safe errors, the canonical
per-repository source snapshot + digest, the exact provider, the configured PLANNING model with
separate repository/project context identities, staged artifacts + digest, quality evidence, and the
promoted result identity. A partial unique index permits only one active job per project. Duplicate
compatible requests reuse that job; an actor/branch/provider/model mismatch is a typed 409 rather
than silently sharing authority or provider spend. Atomic claims use the database clock and carry
owner, expiry, and a monotonically increasing fencing token.
`ProjectArchitectureJobAdmission` owns this DB/config-local admission boundary: project lookup,
provider and tier-model resolution, exact-route validation, normalized branch identity, durable job
creation/dedupe, and active-job compatibility. `ProjectArchitectureJobService` begins source/SCM
preparation only after admission returns a compatible job.
The architecture-job API DTO exposes `attemptCount` and `deadlineAt` alongside phase/status so web
clients can show retry and deadline truth without inferring it from polling duration.
`NEEDS_CONFIRMATION` is a server admission barrier for every client: ordinary submit returns the
same terminal job and cannot create new spend. Only
`POST .../architecture/jobs/{jobId}/confirm-retry` by the original requester resets that same
identity to QUEUED and appends an immutable actor/time confirmation receipt.

Only PREPARING work may be reclaimed after lease expiry. The worker resolves the actor's current SCM
credential ephemerally through `ScmCredentialResolver`; each linked repository uses its own
`linkedBy` credential subject (falling back to the requesting actor only for legacy rows). No
credential is persisted. PREPARING resolves exact branch revisions, binds the immutable source and
the two execution contexts, and then durably moves to EXECUTING before the first provider-capable
call. The execution-context source/correlation identity is a compact 128-bit SHA-256 projection of
the full job UUID, source digest, and attempt deadline, keeping distinct source snapshots and
confirmed retries within the shared 96-character execution-context column contract. An expired
EXECUTING/PUBLISHING lease becomes `NEEDS_CONFIRMATION` with
`OUTCOME_UNCERTAIN`; automatic provider replay is forbidden. A second live revision check after
generation prevents a moving repository from being reported as current.
The job claims, heartbeats, and terminalizes both exact-route LLM contexts. Partial context creation
is cancelled before PREPARING can escape. A database-backed cooperative checkpoint runs around
each repository/SCM/provider operation and before combined synthesis, so cancellation, deadline, or
lease loss fences later dispatch instead of waiting only for the periodic heartbeat.

Generation is staged in memory and then as canonical JSON on the job row. It cannot write canonical
codemap, repo-architecture, project-architecture, workspace, activity, retention, or PR state. The
transactional `ProjectArchitectureJobPublisher` is the only visibility boundary: it row-locks the
project first (the shared architecture/generated-document publication lock), then row-locks the
job, verifies lease/fence/source/stage digest, proves exact one-to-one staged coverage of every
snapshotted repo (id/name/branch/revision plus combined source maps), saves all canonical DB
artifacts, and marks SUCCEEDED atomically. Workspace mirroring and retention run only after commit
and are best-effort; the database remains authoritative. Provider truncation or abnormal finish
reasons become explicit terminal failure and are never published. Deterministic fallback remains
available only for other classified provider failures and is explicit WARNING quality evidence,
not provider success. Before any canonical row is saved, `GeneratedSignatureGrounding` checks
inline-code method contracts against the staged codemaps. Unverifiable parameter/arity/type claims
are downgraded to the qualified method name; codemaps remain unchanged source evidence. The same
gate covers repository architecture, combined architecture, and summary while preserving the
publisher's atomic no-partial-save boundary.

V353's database trigger enforces project/org and result/project coherence. SUCCEEDED jobs retain a
RESTRICT reference to their architecture proof; terminal-job retention must remove the job before
that old architecture version can be pruned.
The historical direct `ProjectArchitectureService.generate*` / `regenerateForRepo` publication
methods fail closed; production callers must use the durable boundary.

Whole-project callers must enqueue this protocol. Callers that semantically require a fresh map
(execution analysis and the run planner's stale-map refresh) bounded-wait for the exact promoted
architecture id and abort/fail-soft according to their existing contract; they must never enqueue
and immediately continue against stale state. Post-merge and chat callers are deliberately
queue-only. Customer-repository publication is suppressed because that external PR side effect is
not bound to the job fence. Provider transport is outside claim transactions; all state
transitions are short database writes.

## Generated project documents

V358 routes generation through `project_generated_doc_job`: POST returns `202`, the UI restores and
polls the durable job, and a leased worker executes the exact provider/model resolved at admission.
The document row and `SUCCEEDED` job transition share the publisher transaction, so a lost lease
rolls publication back instead of exposing a document from a failed job. Result references use
`ON DELETE SET NULL` so normal document-version retention remains possible while the immutable
result version survives as audit evidence; terminal jobs are retained for 30 days. Customer-facing
failure messages are bounded and secret-safe.

V363 makes provider-outcome ambiguity fail closed. Immediately before the first provider-bearing
call, the worker must persist `provider_started_at` under the live lease/fencing token; losing that
write prevents dispatch. A failure or expired worker after that fence becomes
`NEEDS_CONFIRMATION`, not an ordinary retryable failure. Ordinary POST admission returns the same
barrier job, so reloads and duplicate clicks cannot silently create more model spend. Only
The migration first widens `project_generated_doc_job.status` to `VARCHAR(32)` so the durable
barrier is representable on PostgreSQL rather than failing during the recovery sweep. Only
`POST .../docs/{type}/jobs/{jobId}/confirm-retry` by the original requester resets that same job
identity to QUEUED while preserving its exact provider/model and appending an immutable
`project_generated_doc_job_retry_confirmation` receipt. Pre-dispatch readiness failures remain
FAILED, and the UI independently restores both document types, renders QUEUED versus RUNNING, and
requires a spend warning confirmation before replaying an uncertain attempt. The durable
document-operation deadline remains 30 minutes; it is returned with attempt/provider-start evidence
on every job response instead of being represented by the browser's generic request timeout.

`ProjectDocGeneratorService` generates `EXPLANATION` and `SETUP` documents only through
`ProjectDocValidatedGeneration`, whose bounded two-attempt loop uses
`LlmRouter.callTextStrict` on the exact resolved route and rejects truncated, unsafe, or
deterministically invalid output before publication. Text is concatenated across every non-empty
provider generation because Anthropic may return multiple generations; reading only the first can
misclassify a paid, complete response as empty. New document rows use `saveAndFlush` before the
durable job-result fence writes its PostgreSQL foreign key, while both writes remain in the same
publisher transaction and roll back together on fence loss. Its provider-neutral system prompts are
`prompts/project-doc-explanation.md` and `prompts/project-doc-setup.md`, assembled through
`PromptAssembler`. Project metadata, exact-SHA codemaps, and separately reserved deterministic fact
blocks stay in the wrapped `PROJECT_DOC_EVIDENCE` user message. The model-generated architecture
body is deliberately excluded from factual grounding, and model-generated `Purpose` lines are
removed from codemap evidence before document generation.
Concrete inline-code method signatures in generated documents are grounded only against the
codemap sections of that evidence bundle, never against generated architecture prose; unverifiable
contracts are reduced to method-name-only references before publication.
Declaration co-occurrence is not call-graph or liveness proof. The EXPLANATION validator rejects
high-confidence method/class call-edge claims (including works/interacts/collaborates/depends/uses
euphemisms) and inactive/unused/stub/dead/unwired assertions when they name codemap symbols but
declaration-only evidence cannot establish that behavior. Speculative hinted/placeholder/prototype/
candidate labels are rejected only when they occur near a known symbol, avoiding unrelated prose
such as candidate moves. Grounding runs before final validation. Unsupported prose sentences are
deterministically replaced with a declaration-only symbol list plus the explicit evidence-limit
note. To avoid endless semantic synonym chasing, every prose sentence containing two or more
distinct known codemap symbols is treated as an unproven relationship regardless of its verb;
single-symbol descriptive inventory remains unless it attributes reliance, a hinted mechanism, or
incomplete integration (including grammatical variants). Directional Mermaid/sequence blocks are
removed as a whole and replaced with the same non-directional note. Other content and headings
remain in place.
Exact-revision project facts cover selected schema/configuration paths, literal Compose service
declarations, and Spring handler response modes. Schema ownership and Compose application/full-stack
startup are never inferred from framework conventions, directory names, arbitrary images, or build
targets. Unsupported factual claims fail deterministic validation and receive the same single
same-route corrective retry as structural failures; two unsupported responses fail publication.
Positive route-representation claims resolve against a repository + HTTP method + exact-path key.
An unresolved `NOT_ESTABLISHED` handler mode proves neither a view nor a serialized body; in a
multi-repository bundle, a claim without unambiguous repository attribution fails closed so one
repository's same-path handler cannot authorize prose about another repository.
Legacy same-SHA codemaps without the current fact marker are not reusable, and every repository's
complete fact block receives a reserved source-budget section before bulk declaration inventory.
Surrounding repository/combined architecture prose remains model-derived explanatory content; it is
conservatively sanitized and carries byte-exact deterministic appendices separately.

Every EXPLANATION corrective retry enumerates the four
mandatory H2 lines verbatim/exactly once and forbids renaming, deleting, merging, reordering, or
demoting them. The validator remains as a fail-closed post-sanitization backstop, and cache hits are
checked under the same current grounding contract before reuse.

The source bundle has a hard 60,000-character ceiling. Budgeting is section-aware: metadata,
reserved per-repository facts, and every linked repository receive independent water-filled shares;
short sections return unused
capacity, cuts occur only after complete lines, and each affected section carries an omission
marker. Resolved repositories are sorted by canonical full name and repository id before rendering,
so prompt bytes and budget allocation do not depend on database query order. Each codemap is loaded
by the exact repository SHA recorded in the selected architecture fingerprint; a missing, extra, or
newer-only codemap rejects generation as `STALE_SOURCE` instead of mixing revisions. Never replace
this with a whole-prefix substring or a latest-codemap lookup.

A provider `length` or `max_tokens` finish reason is `PROVIDER_OUTPUT_LIMIT`; every other explicit
non-complete finish reason (including safety, refusal, recitation, and content filtering) also fails
closed. Before publication, a provider-neutral deterministic validator requires the documented
headings, a meaningful minimum body, balanced fences, no private tool protocol, and no
high-confidence credential material. A complete and customer-safe response that fails only this
deterministic content contract gets at most one corrective regeneration: the validation reason is
fed back, the full document is requested again, and the same exact provider/model plus the same
durable execution context are reused through strict routing. Both physical calls consume the shared
operation budget and their token usage is included in the saved document's cost fields. Truncation,
provider non-completion, and unsafe content never retry. Nothing is inserted into
`project_generated_doc` or mirrored into the workspace until a response validates; two malformed
responses still fail atomically. POST maps provider non-completion to 502 and unsafe/malformed
content to 422; GET and download rescan retained legacy rows before returning content.

Provider transport runs outside a database transaction. Same-source requests share one in-flight
future within a JVM only when provider, resolved model, actor, and confirmed authority context also
match; distinct routes or confirmations must never share provider spend. After a complete response,
a short `TransactionTemplate` phase acquires
`ProjectRepository.findByIdForQuotaUpdate`, re-reads and exactly matches the current architecture
version/fingerprint, rechecks the source cache, allocates the next version, saves, and prunes. The
durable architecture publisher takes the same project-row lock, so architecture promotion cannot
race between the recheck and generated-document insert. The lock serializes publication/version
assignment across application nodes; it is not a cross-node provider-call lease, so do not claim
cross-node exactly-once spend.

The database row is authoritative. Workspace `docs/<type>.md` is a best-effort read cache written
only after durable persistence.

`ProjectDocSourceBundleBuilder` owns linked-repository resolution, canonical ordering, exact-SHA
codemap lookup, architecture-fingerprint parsing, and the final section-aware evidence budget.
`ProjectGeneratedDocPublisher` owns the short project-row-locked publication transaction:
source revalidation, same-source deduplication, version assignment, save, and retention. Provider
dispatch and deterministic retry/shape validation stay in `ProjectDocValidatedGeneration`;
customer-safety validation stays in `ProjectDocGeneratorService`, while
`ProjectDocResponsePolicy` owns fail-closed provider finish-reason classification. Do not fold these
source, publication, or response-policy responsibilities back into that service.
