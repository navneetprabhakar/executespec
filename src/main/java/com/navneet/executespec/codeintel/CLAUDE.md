# `codeintel/` — project architecture and generated documents

## Provider and model-matrix admission (2026-08-02)

Architecture refresh and generated-document jobs fail before dispatch: missing provider returns
`PROJECT_PROVIDER_NOT_CONFIGURED` (409), invalid persisted provider returns
`PROJECT_PROVIDER_INVALID` (409), and unavailable/incomplete matrix returns
`MODEL_MATRIX_UNAVAILABLE` (503). These are structured `errorCode` response fields with separate
customer-safe copy, not prefixes that clients must parse from a message. Rejection creates no
durable job or provider call.

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
Publication grounds each repository only against its repo-id-matched codemap. After every prose
sanitizer runs, the publisher strips model-supplied or stale evidence markers and rebuilds the
repository appendices byte-for-byte from that codemap; the combined appendix is then rebuilt from
those restored repository documents. This ordering prevents sanitizer corruption of exact route
rows and prevents one repository's same-path evidence from authorizing another repository.

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

An empty successful Spring surface extraction is not positive framework evidence. Codemap
appendices render it as `Framework: NOT_ESTABLISHED`, `Status: NOT_APPLICABLE`, and
`NO_DETERMINISTIC_SPRING_MVC_EVIDENCE`; positive route or context-path evidence retains the existing
`SPRING_MVC` contract, while extraction failures remain `UNAVAILABLE`.

`ExportedFunctionEvidence` adds bounded `EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1` blocks to
exact-SHA codemaps for exported named JavaScript/TypeScript functions only. The deliberately narrow
lexer omits explicit TypeScript return types and files containing a non-comment code slash until
regex/division grammar is modeled. Project-document bundling validates every retained block's
repository/SHA, fields, lines and SHA-256 content, strips it from ordinary declaration inventory,
and budgets it atomically. Malformed evidence is stale; an oversized allocation is omitted whole.
These excerpts establish only visibly present body code, never callers, consumers, wiring, or
cross-repository relationships.

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
Known post-dispatch validation, truncation, stale-source, and safety failures remain behind the same
confirmation barrier but retain their safe stable error code; only genuinely unknown failures use
`OUTCOME_UNCERTAIN`. Confirmed replay preserves the lifetime worker-attempt count and receives a new
`project-doc:<job UUID>:<lease token>` execution context. Each explicitly confirmed attempt therefore
receives its own bounded attempt budget without resetting the lifetime worker-attempt count or
losing the joinable durable job identity.

`ProjectDocGeneratorService` generates `EXPLANATION` and `SETUP` documents only through
`ProjectDocValidatedGeneration`, whose bounded two-attempt loop uses
`LlmRouter.callTextStrict` on the exact resolved route with the INTERACTIVE transient-retry profile
and `ProjectDocExecutionPolicy` gives each attempt hard 200,000-token / two-physical-call limits
plus post-success $3 spend reconciliation; the final admitted call may cross the dollar ceiling.
The durable provider-start fence runs after attempt admission and local client preflight, immediately
before transport, so it proves local dispatch began but does not prove provider delivery or charge.
Generated-document job DTOs also normalize the legacy persisted phrase `provider execution began`
to truthful dispatch/uncertain-delivery wording for every API consumer; the web fallback remains for
older servers.
The validator rejects truncated, unsafe, or deterministically invalid output before publication.
For SETUP, `ProjectSetupOperationalGrounding` replaces the complete customer-visible body with a
byte-stable document assembled from decoded exact-revision `SetupAuthority`. Each linked repository
gets one exact clone block and each projected command gets its own fenced block; Node engine facts
are retained, while environment, service-order, health, and troubleshooting gaps are stated as
unknown. The assembled document is safety-scanned and passes the unchanged strict validator.
Canonicality is required for cache reuse and publication dedupe.

**Empty-projection SETUP is zero-provider (2026-08-05).** When
`ProjectSetupOperationalGrounding.hasEmptyCommandProjection` proves that the valid setup authority
projects no non-clone command for any linked repository, `generateOnce` skips the provider lane
entirely: `ProjectDocValidatedGeneration.assembleDeterministicSetup` builds the clone-only
document, safety-scans it, and runs the unchanged strict validator — no execution context, no
provider dispatch fence, zero token spend, `inputTokens=outputTokens=0`. This closes the live dev
defect (project 7238a44d, job ce2751c3): a fresh project with no derivable package.json scripts or
Makefile targets has an EMPTY projection, so the model (DeepSeek) kept emitting conventional
`npm test`-style steps, every attempt fail-closed as `SETUP_COMMAND_UNAUTHORIZED`, and the durable
job repeatedly terminated `NEEDS_CONFIRMATION` even after confirm-retry. Because the fence is never
marked on this path, a deterministic validation failure is an honest `FAILED` with its exact
`INVALID_GENERATED_DOCUMENT_<SUBCODE>`, never the uncertain-outcome confirmation barrier. A
non-empty projection keeps the existing provider→validate→corrective lane byte-identical. The
provider-dispatch lane itself (messages, bounded execution reference, validated loop) lives in
`ProjectGeneratedDocAssembly.dispatchProviderGeneration` so `ProjectDocGeneratorService` stays
under the 600-line new-class gate.

**Multi-repo command attribution (2026-08-05, dev project 7238a44d).** The strict SETUP validator
attributes each fenced command block to a repository from the text between the PREVIOUS fenced
block and the block itself (`SetupAuthority.presentedRepository`: last `##`–`######` heading in
that window, then exactly one linked-repository mention). The deterministic assembly emitted the
`### <repo>` heading once per repository in `## First-run per repo` and then bare labels
(`Test:` / `Start:`), so in a multi-repository project every repository's SECOND and later command
blocks had a context with no heading and no repository mention — attribution `null` →
`authorizes(null, command)` → `SETUP_COMMAND_UNAUTHORIZED` even though the command was projected
for that exact repository. Three scripts-bearing repos therefore terminated `NEEDS_CONFIRMATION`
on every attempt. Producer-side fix in `ProjectSetupOperationalGrounding.appendFirstRun`: every
command label now names the exact repository (`Test for `<repo>`:`), matching the convention the
full-stack section already used (`Evidenced start command for `<repo>`:`), so each block's
immediately-preceding context carries exactly one repository mention. The validator is byte-
unchanged; wrong-repo and unattributable commands still fail closed
(`ProjectSetupMultiRepoLiveShapeTest`, which builds the envelopes through the real
`DeterministicProjectFactEvidence.render`). Canonicality follows `assemble`, so pre-fix cached
SETUP documents simply read as non-canonical and regenerate.

**Known gap — projection ignores V362 run command evidence.** The
`#### Safe deterministic command projection` is rendered at codemap-generation time
(`CodeMapGenerator` → `SpringHttpArchitectureEvidenceRenderer` →
`DeterministicProjectFactEvidence.renderSetupCommandSources`) and derives commands ONLY from
exact-revision repository files: `package.json` `scripts` and `Makefile` targets. It never reads
the V362 `validation_report.build_command` / `test_command` evidence persisted by COMPLETE runs, so
a project whose runs proved exact build/test commands still shows an empty projection. Wiring it is
deliberately deferred as a larger change: (a) the renderer is a static exact-revision boundary with
no repository/report access; (b) V362 evidence is run-scoped (one build/test command per report)
and attributes to a repository only via workspace working directories, which needs an explicit
mapping model for multi-repo runs; (c) the `EXECUTESPEC_SETUP_SOURCES_V2` envelope is bound to one
exact 40-hex source revision, while run evidence binds to the run's own workspace generation
(V361), so admitting run-derived commands honestly requires a new versioned evidence envelope plus
validator/producer compatibility, not string injection into the existing revision-bound block. The
clone-only fallback above keeps SETUP generatable and honest until that envelope exists.
Text is concatenated across every non-empty
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
declarations, bounded setup-source excerpts, and Spring handler response modes. Shell commands
other than repository clone commands must appear verbatim in those excerpts; a manifest path alone
never proves a command. Current V2 fact/setup envelopes require the producer-compatible full
40-hex source revision. For `package.json`, command bodies are authoritative only inside the typed
`scripts` object; arbitrary metadata string values never authorize a setup command. Schema
ownership and Compose application/full-stack
startup are never inferred from framework conventions, directory names, arbitrary images, or build
targets. Unsupported factual prose is replaced deterministically with an explicit not-established
statement before validation, without paying for a corrective call; structural failures may still
receive one same-route corrective generation.
Positive route-representation claims resolve against a repository + HTTP method + exact-path key.
An unresolved `NOT_ESTABLISHED` handler mode proves neither a view nor a serialized body; in a
single- or multi-repository bundle, a claim missing an explicit repository or HTTP method fails
closed so one repository's same-path handler cannot authorize prose about another repository. A
repository reference is a whole canonical token: a longer slash-qualified name cannot satisfy a
linked repository or route-ownership check by suffix.
Setup commands follow the same ownership boundary: single-repository documents infer that sole
repository, while multi-repository documents must present each command under an explicit exact
repository context and may use only that repository's encoded sources/derived script commands.
Legacy same-SHA codemaps without the current fact marker are not reusable, and every repository's
complete fact block receives a reserved source-budget section before bulk declaration inventory.
Surrounding repository/combined architecture prose remains model-derived explanatory content; it is
conservatively sanitized and carries byte-exact deterministic appendices separately.
`GeneratedSourceContractGrounding` also rejects exhaustive single-symbol contracts such as allowed,
supported, accepted, or valid field/option/value lists unless one canonical
`EXECUTESPEC_EXPORTED_FUNCTION_EVIDENCE_V1` entry is bound to that repository and exact source SHA,
the same complete function declares the literal collection, the same collection receives the
membership check against a function parameter, and the claimed values exactly equal that
collection. Declaration inventory, model-generated `Purpose` prose, another function or
repository, an unrelated collection/check, a subset, an unresolved constant, a truncated excerpt,
or an absent/malformed evidence envelope cannot authorize the list. Structural matching runs
against a code-only projection (`ExportedFunctionEvidence.maskNonCode`) with comment bodies and
string contents blanked at stable offsets, so commented-out or string-quoted pseudo-code cannot
authorize a contract while literal collection values are still read from the original matched
offsets. Combined architecture and
summary prose have no single-repository source-contract authority and therefore fail closed. The
guard runs after both architecture generators and again in `ProjectArchitectureJobPublisher`.
Unsupported prose is replaced with an explicit exact-source-evidence limitation; Markdown code
examples and the deterministic appendices remain byte-exact.

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
high-confidence credential material. Setup command validation treats known single-token
executables as commands even outside a shell-labelled fence, rejects unclosed backtick or tilde
fences, rejects shell operators even when repository prose contains the same line, and projects npm
scripts only from operator-free script names. Inline strings containing only version-range
characters (for example `>=20`) are non-executable facts, not shell commands. A complete and customer-safe response that fails only
this deterministic content contract gets at most one corrective regeneration: the validation reason
is fed back, the full document is requested again, and the same exact provider/model plus the same
durable execution context are reused through strict routing. SETUP operational claims are instead
discarded and deterministically assembled from the complete safe-command projection; an absent
projection emits zero non-clone commands. The publication validator remains unchanged and
fail-closed. Both physical calls, when a genuine provider-shape retry is needed, consume the shared operation budget and their token usage is
included in the saved document's cost fields. Truncation, provider non-completion, and unsafe
content never retry. Nothing is inserted into
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

## 2026-08-04 generated-document consistency

- SETUP repository coverage requires one exact safe clone target for every expected repository
  after parsing the existing literal `git clone` grammar; a narrative repository token is not
  sufficient. The strict standalone `owner/repo` token boundary is not relaxed, so suffix/prefix
  repository attacks remain rejected.
- Deterministic validation failures carry a stable subcode and durable jobs expose
  `INVALID_GENERATED_DOCUMENT_<SUBCODE>` through the existing `error_code` column; customer-safe
  messages remain generic while the internal reason drives the one bounded same-route correction.
- EXPLANATION publication rebuilds `Verified local workflow` and `Troubleshooting` from typed
  exact-revision setup evidence. It emits only evidenced commands and an `engines.node` constraint
  when present; absent command categories remain explicitly unestablished. Cache reuse and the
  serialized publication dedupe both require byte-canonical operational grounding for the current
  exact evidence/repository set, and the final grounded document is safety-scanned before save.
- Re-appending combined deterministic repository evidence replaces the prior marker-owned appendix
  instead of leaving duplicate H2/H3 wrappers.
