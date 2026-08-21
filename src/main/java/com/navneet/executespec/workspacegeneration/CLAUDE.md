# Workspace generation protocol

`WorkspaceGenerationFailureProjection` is the customer-safe deterministic taxonomy for a failed
BUILD attempt. It derives category, stage, retryability, candidate presence, rollback proof,
publication block, and recommended action only from durable attempt state, stable codes, and the
typed verification receipt—never from free-form provider/tool exceptions. The customer API retains
failed targets only when they exactly match a non-hidden frozen acceptance-check target; hidden and
historical unmatched targets remain redacted. The newest attempt is authoritative for terminal
projection, so older rejected candidates remain technical history but cannot overwrite a later
accepted frontier.

`WorkspaceGenerationQualificationEvidenceController` is a SUPER_ADMIN-only, `no-store` read path
for separately authorized qualification. It exposes the preserved rejected-diff JSON plus immutable
attempt/route/usage/receipt fields that customer-safe run detail intentionally redacts. It is not
acceptance authority and must never be opened to ordinary run readers. Historical attempts that
rolled back before `beginVerification` honestly return a null candidate generation; their negative
proof is the digest-bound non-empty rejected diff plus exact prior-frontier restoration and durable
deterministic task-smoke failure, not an invented generation digest.
Recovery-failure code/reason/timestamp are exposed on this operator-only response so a failed
restore is diagnosable without widening customer run detail.

V374 makes one initial BUILD task the unit of mutation authority. V375 preserves original rejection
and rejected-diff evidence independently from first recovery-failure evidence. V376 seeds the
default-off shared BUILD-loop rollout flag; V377 freezes the schema-v2 task contract, planned
initial failure, physical route and usage evidence. V378 freezes the initial acceptance probe
executed after baseline materialization and before provider dispatch; only an observed red result
admits dispatch. `WorkspaceGenerationLifecycle`
owns `PREPARED → MUTATING → VERIFYING → VERIFIED → CHECKPOINTING → ACCEPTED`; every rejection uses
`ROLLING_BACK → ROLLED_BACK`, and crash recovery may use `RECOVERY_FAILED → ROLLING_BACK` retries.
Never infer acceptance from a worker outcome or task status.
V380 adds a replayable task-transition outbox to each rolled-back generation. Normal rejection
publishes `MARK_PARTIAL`; crash recovery publishes `REOPEN_PENDING`. Downstream admission remains
closed while publication is pending, and the completion receipt uses an independent transaction.
V381 requires the exact current database workspace authority for rollback intent and resolution.
V382 permits only the byte-identical `PENDING → COMPLETED` task receipt on a resolved attempt; all
generation, rejection, checkpoint, authority and task-transition identity evidence stays immutable.
V383 moves pre-dispatch admission into the same protocol:
`ADMITTED → OPERATION_CLAIMED → AUTHORITY_ACQUIRED → PREPARED`. The admission transaction locks the
run then task, creates the generation, claims the exact physical LLM operation, and binds it before
any provider or mutating tool can run. Fresh and resumed BUILD tasks, same-route smoke retries,
provider/model changes, approved continuations, and correction regeneration all use this path.
`WorkspaceGenerationTerminalReconciler` converges task receipts, accepted task state, and the bound
LLM operation after acceptance/rollback and during startup recovery.
V384 gives ACCEPTED the same durable publication contract as rollback: the ACCEPTED row update
also writes `MARK_COMPLETE/PENDING` and snapshots the exact task status/recovery generation. The
coordinator re-reads committed attempt state through `WorkspaceGenerationConvergenceService`;
request-thread booleans cannot decide task or physical-operation terminal state. Global sweeps
isolate failures per attempt and run so one contradictory record cannot starve healthy work.
V385 snapshots `LEGACY_V1` or `SHARED_KERNEL_V2` once during initial BUILD admission and copies the
exact immutable value to every attempt. Execution and continuation admission read only that durable
identity; null/unknown historical rows and parent/attempt mismatches fail closed. BUILD dispatch
authority is a non-serializable `WorkspaceGenerationDispatchAuthority` carried by
`ExecutionContext`, bound to run/task/attempt/workspace lease/phase, and freshly rechecks both the
database lease and generation state at provider, tool, reproduction, and verification boundaries.
V386 assigns every pre-pin BUILD run/attempt `LEGACY_UNKNOWN` plus an immutable historical
disposition without changing run status, task state, generation state, operation state, or
publication evidence. Proven terminal contradictions are `QUARANTINED_CONFLICT`; incomplete rows
are `MISSING_EVIDENCE`. Unknown history is non-executable and non-publishable. The read-only
`WorkspaceGenerationHistoricalInventoryService` captures a repeatable, ordered, SHA-256-bound
operator snapshot with reconciled run/attempt/disposition counts, safe reasons, and evidence
reference booleans. V387 adds immutable DRY_RUN/APPLIED adjudication receipts over a minimized
causal before-state. `CLOSE_AS_NON_EXECUTABLE` retains evidence; only a distinct
`RELEASE_FOR_DELETION` receipt releases the database hold, and neither action can rewrite source
rows or unblock publication. Retention deletes rows independently so one held record cannot starve
unrelated eligible runs. V398 persists safe per-identity reconciliation failure counts, oldest-
backlog timing, dead-letter state, and later resolution; diagnostic failure cannot stop the
isolation loop. V399 persists the exact terminal physical-operation target and an immutable
completion receipt. The terminal reconciler consumes that durable intent, converges task and
operation independently, and releases workspace authority only after both receipts are complete.
The historical terminal-row backfill transactionally suspends the V384 resolved-attempt guard,
performs the enrichment under the migration table lock, and reinstalls the expanded V399 guard
before commit; `FlywayMigrationInventoryTest` pins that ordering.
`WorkspaceGenerationSchemaAttestor` independently verifies migration V400 and all 62 load-bearing
PostgreSQL indexes/triggers before protocol-v2 admission.
`BuildEnvironmentPlanResolver` derives deterministic, network-denied reconstruction commands from
accepted Node, Python, Maven, Gradle, Go, and Rust manifests plus a digest-pinned runtime image.
`BuildEnvironmentMaterializer` runs those commands only through RUN or exact VERIFICATION sandbox
authority and rejects any tracked/non-ignored product-generation mutation. V396 stores append-only
phase materialization and exact ignored-file cleanup receipts; both Java and PostgreSQL refuse the
protocol-v2 transition to VERIFYING without passing INITIAL_PROBE and WORKER receipts, and refuse a
passing final verification without FINAL_VERIFICATION. The SUPER_ADMIN qualification evidence
includes the full removed-path inventory while customer run detail remains bounded and redacted.
V388 also separates physical verification from canonical product mutation: `SandboxPurpose.RUN`
keeps the derived run mount, while `SandboxPurpose.VERIFICATION` uses the sandbox-owned root and an
opaque live capability. `DisposableVerificationWorkspaceService` copies and re-attests the exact
generation, captures/discards command side effects, and routes build, tests, Python HTTP runtime,
read-only reviewer attestation, standards lint/invariants, breaking-change CLIs, CLI smoke, and
validation through that capability. `NonExecutingGitTreeWriter` constructs restorable Git tree
identity using raw JGit blob/tree insertion rather than `git add`, preserving file modes and safe
symlinks without executing repository filters/helpers; validation change evidence likewise avoids
external diff/textconv. The materializer retains safe relative symlinks and refuses absolute or
escaping targets. `run_sandbox_workspace_cleanup` plus
the scheduled janitor provide crash/restart cleanup without deleting beneath a live mount.
V389 adds the physical-command protocol beneath that high-level verification result. Each command
persists `ADMITTED` before external execution, transitions to `DISPATCHED` only while the exact
VERIFICATION sandbox owner/token/runtime epoch remains live, and settles once with an immutable
terminal receipt. Protocol-v2 passing generation receipts must cite a non-empty set of successful
command receipt ids for the same attempt, candidate generation and verification purpose. A stale
dispatched command is reconciled only to `INDETERMINATE`; recovery never derives success from
runtime disappearance or later clean execution. The attempt-level command protocol is immutable:
previous writers default to v1 for rolling compatibility, while new admissions explicitly pin v2.
V390 moves final Git materialization into a separate `PUBLICATION_PREPARE` authority. A preparation
binds the exact accepted generation/checkpoint and canonical validation digest, freezes every
repository/branch plus whether exact repository provisioning is authorized, and persists the
non-executing JGit parent/tree/commit identity before remote work. Repository creation and exact
commit push each persist `ADMITTED` and `DISPATCHED` before provider execution. Prepared object
re-materialization after workspace restore must reproduce the durable ids byte-for-byte or
publication stops. Recovery changes stale dispatch only to `INDETERMINATE`, never success.
The worker outcome is advisory even when it is `COMPLETED_WITH_WARNING` or `WORKER_FAILED`: after
authorized mutation, the frozen server verifier always decides acceptance. A passing receipt
normalizes the task outcome to success while retaining raw worker evidence; a failed receipt rolls
the candidate back.

`WorkspaceGenerationExecutionEvidenceRecorder` owns the transactional MUTATING-state CAS that
freezes effective route, iteration/tool/model-call counts, token categories, and cost before
verification. This keeps usage-evidence persistence separate from the lifecycle state machine.
`WorkspaceGenerationRollbackService` owns the evidence-first database/filesystem/database rejection
sequence so the lifecycle state machine stays below the new-class size ceiling.
`WorkspaceGenerationRecoveryFailurePolicy` owns the three-attempt 30/60/120-second retry schedule
and the durable exhausted-quarantine receipt, keeping recovery orchestration separate from failure
adjudication without weakening its compare-and-set fence.
`WorkspaceGenerationRecoveryScope` is the single state-set definition used by recovery scans and
their exhaustive crash-state tests.

## Invariants

- Persist ADMITTED, the exact physical operation claim, workspace authority, PREPARED, its exact
  before checkpoint, frozen task/authority digests, ignored-artifact baseline, and workspace lease
  before provider or tool mutation.
- Bind MUTATING to the exact RUNNING `llm_execution_operation` attempt/lease. Every LLM and tool
  dispatch repeats the database join fence. Provider return is not authority: the same join is
  repeated with the exact expected state at MUTATING, VERIFYING, VERIFIED, and CHECKPOINTING before
  receipt persistence, checkpoint acknowledgement, or ACCEPTED. A MUTATING-only tool fence is not
  reused as post-mutation proof authority. Pause/cancel may still roll back but cannot advance the
  frontier.
- Verification commands are resolved by the server. Planner/model commands are not acceptance
  authority. The immutable receipt must bind candidate generation + task contract and prove the
  required build/tests. Acceptance reclassifies against the exact candidate diff: zero-diff
  generations fail, and any actual code-file change requires an executed non-empty green suite
  even when planner wording classified the task as documentation/configuration. Required-test
  tasks must also change a repository-owned non-bootstrap test file: immutable hidden tests cannot
  substitute for tests shipped with the candidate. Qualified Python HTTP tasks run the complete
  discovered pytest suite and the separate cleanroom live-runtime contract; both must pass.
- HTTP route checks canonicalize parameter names (`/todos/{id}` and `/todos/{todo_id}` are the same
  reachable route shape) while preserving exact methods and literal path segments.
- Under `build.shared-code-change-loop.enabled`, the small `GreenfieldBaselineService` facade
  delegates immutable contract planning to `GreenfieldBaselinePlanner`, symlink-safe lease-bound
  writes to `GreenfieldBaselineMaterializer`, and hidden acceptance-source rendering to
  `GreenfieldAcceptanceTestSource`. The planning path trusts the typed
  `WorkspaceOrigin` carried from durable run admission rather than rediscovering origin from
  filesystem metadata. Python and path-explicit plain Node JavaScript use qualified external bootstrap markers
  and hidden tests under `.executespec/bootstrap`; every bootstrap byte is digest-bound and checked
  again after worker mutation. TypeScript, Java/Maven, React and unsupported mixed repositories
  fail before provider dispatch until their external, non-no-op adapters are qualified. No
  qualified adapter creates or freezes a customer manifest, product implementation, or customer
  test path. `TaskAcceptanceContractResolver` freezes exact commands, required paths, runtime,
  scopes and checks before mutation.
- `TaskAcceptanceVerifier.probeInitial` must execute before provider dispatch. It refuses command
  drift or unavailable required test infrastructure, records missing required paths or missing
  HTTP interface declarations as red evidence, and refuses to relabel a green workspace as a
  failure-first task. Every frozen final check is evaluated independently; unknown checks remain
  `UNAVAILABLE` rather than disappearing.
- A provider/model change cannot continue inside a mutable generation attempt. It must create a
  new PREPARED attempt from the durable accepted frontier with its own exact LLM/workspace lease.
- Same-provider build-smoke retry is also a fresh attempt. No correction lease or legacy task reopen
  flag can bypass `BuildTaskGenerationCoordinator`.
- Candidate acceptance requires an exact generation archive acknowledgement. Production may not
  use a same-host receipt.
- `pipeline_run.accepted_workspace_generation`, task index, accepted attempt, and the exact internal
  checkpoint kind/key/digest/completion receipt advance atomically with ACCEPTED. These dedicated
  fields must never alias `gcs_workspace_key`: that field is the terminal customer-download ZIP,
  while the generation checkpoint is an internal tarball with Git bundles. The legacy asynchronous
  post-task export is forbidden on this path because it can race the next mutation. Reviewer, normal validation,
  SCM, deployment, and recovery must compare current bytes/archive identity to this frontier and
  reject any unresolved attempt.
- The database frontier is contiguous and non-erasable: it must seed at `-1` and advance exactly
  one task index at a time. Checkpoint receipts are one-shot and immutable. Recovery and accepted
  task reconciliation dereference `pipeline_run.accepted_generation_attempt_id`; they never infer
  the frontier from the newest timestamp.
- Initial review is read-only. Formatting or repair belongs inside the task transaction or the
  existing durable correction lifecycle.
- Rejection is evidence-first and two-phase: commit `ROLLING_BACK` plus the exact bounded candidate
  diff before touching the filesystem, restore outside that database transaction, then commit
  `ROLLED_BACK`. A restore/proof failure commits `RECOVERY_FAILED` without erasing the original
  candidate evidence; the coordinator never retries rollback with a released lease.
- Archives are internal generation archives, not customer download ZIPs: every repository retains
  its original HEAD bundle and exact tree/modes. Restore must re-attest the canonical multi-repo
  generation. Missing, malformed, partial, or stale local trees are reconstructed into an absent
  sibling and atomically swapped only while holding exact RECOVERY authority; a busy recovery
  leaves the existing bytes untouched.
- Delete every ignored root before mutation and before checkpoint/acceptance, plus rollback and
  recovery. Task-local dependency installation may survive through deterministic verification, but
  ignored bytes are outside both the content-addressed generation and its cross-node archive and
  must never become cross-task state. Dependencies are reconstructed from accepted manifests and
  governed environment inputs at each task boundary. `git clean -ndX` may collapse ignored files
  into a parent whose name does not itself match an ignore rule, so cleanup revalidates the same
  scoped dry-run result rather than incorrectly requiring `git check-ignore` on that parent.
- Local-hands BUILD is fail-closed until the remote proxy protocol carries the same generations,
  lease and immutable receipt. Do not reintroduce a server-side bypass.

## Persistence

- Migrations: `V374__workspace_generation_protocol.sql`,
  `V375__preserve_workspace_generation_recovery_evidence.sql`,
  `V376__seed_build_shared_code_change_loop_flag.sql`,
  `V377__workspace_generation_execution_evidence.sql`, and
  `V378__observed_initial_build_failure.sql`, and
  `V379__persist_workspace_origin.sql` (nullable staged origin for new writes; no historical
  rewrite or premature NOT NULL tightening),
  `V380__durable_workspace_generation_task_transition.sql`,
  `V381__fence_workspace_generation_rollback_authority.sql`,
  `V382__allow_workspace_generation_task_transition_receipts.sql`, and
  `V383__fence_build_attempt_admission.sql`, and
  `V384__durable_accepted_task_transition.sql`, and
  `V385__pin_build_protocol_version.sql`, and
  `V386__quarantine_unpinned_build_history.sql`, and
  `V387__append_only_build_history_adjudication.sql`.
- **V388 verification sandbox identity + cleanup:**
  `V388__separate_verification_sandbox_identity.sql`.
- **V389 verification command journal:**
  `V389__journal_verification_sandbox_commands.sql` adds mutable command intent/state, immutable
  terminal receipts, stale-dispatch recovery, exact sandbox/generation/lease bindings, and
  command-receipt ids on the high-level generation verification receipt.
- **V390 durable publication preparation:**
  `V390__durable_publication_preparation.sql` adds the accepted-frontier preparation,
  per-repository exact commit receipts, repository-provision intents, exact push attempts,
  immutable transition/delete guards, recovery indexes, and digest self-checks.
- **V391–V400 forward reliability controls:** V391 recovers stale sandbox command admission, V392
  fences publication attempt recovery, V393 queues recovery-artifact cleanup, V394 binds checkpoint
  storage identity, V395 types verification causality, and
  `V396__persist_build_environment_receipts.sql` makes environment cleanup/materialization evidence
  append-only state-transition authority. V397 journals durable intent/APPLIED/VERIFIED receipts
  around archive writes, per-repository rollback, and accepted replacement; deterministic physical
  identities make takeover replay safe. Step evidence is serialized with recursively ordered map
  keys before hashing, so the same logical intent is byte-stable across replacement JVMs. Guarded
  orphan cleanup rechecks accepted/unresolved references before exact-generation deletion. The
  replacement-JVM kill matrix terminates after each Git mutation, directory move, and
  archive-storage-before-receipt window. V398 persists
  poison terminal-reconciliation diagnostics and dead-letter state without granting those rows any
  generation or terminal authority. V399 persists exact terminal operation intent and its
  immutable receipt, blocks later attempt admission until that receipt completes, and permits
  authority release only after task and operation convergence. V400 permits pre-dispatch rollback
  to preserve an already-claimed immutable LLM identity, moves authority acquisition into the
  externally invoked `WorkspaceGenerationAdmissionStore` transaction, and persists bounded
  recovery count/backoff/support identity. The third failed recovery becomes resolved
  `RECOVERY_FAILED` quarantine only after durable task/operation failure intents exist; RECOVERY
  authority is released before parent-run terminal publication. An ADMITTED attempt that has no
  physical LLM operation completes its durable operation-transition receipt directly instead of
  leaving a permanently pending unbound receipt. `RunCancellationStore` atomically commits normal
  parent cancellation with every PENDING/LOCKED/WORKING task cancellation; executor interruption
  and generation rollback occur only after that transaction. The terminal kill matrix halts a real
  JVM at eight boundaries twice each and replays every case twice from replacement JVMs.
- Entities: `WorkspaceGenerationAttempt`, `WorkspaceGenerationVerificationReceipt`.
- Evidence rows are immutable while the parent run exists; whole-run retention deletion may
  cascade. Original `failure_*` and `rejected_diff_*` evidence freezes independently from the first
  `recovery_failure_*` tuple, so recovery cannot rewrite causality.
- V377's contract/planned-initial-failure/requested-route tuple and effective route/iteration/tool/call/
  token/cost tuple are write-once. Run detail deliberately omits prompts, patches and failure
  reasons while exposing bounded customer-safe proof and the newest immutable verification receipt.
- V378's executed initial-probe JSON and observation timestamp are independently write-once.
- `WorkspaceGenerationRecoveryService` runs before generic startup replay and resolves pre-dispatch
  crashes without filesystem access, restores mature mutation crashes, and invokes terminal
  reconciliation for the ACCEPTED→task-COMPLETE and terminal LLM-operation windows.

## Tests

- `WorkspaceGenerationProtocolPostgresTest`: real PostgreSQL transitions, exact task-operation and
  workspace-owner/expiry/LLM-expiry/pause/cancel fences, competing-active-attempt rejection,
  single-winner recovery CAS, contiguous frontier monotonicity, atomic early admission/operation
  claim, exact next-checkpoint admission, receipt binding and retention.
- `WorkspaceGenerationTaskTransitionPostgresTest`: real-PostgreSQL task/run locking, cancellation
  precedence, rollback publication, stale-transition rejection, and terminal convergence.
- `WorkspaceGenerationPreDispatchPostgresTest`: real Spring/PostgreSQL proof for the missing
  transaction exception, normal PREPARED admission, ADMITTED/OPERATION_CLAIMED/AUTHORITY_ACQUIRED
  replacement recovery, every pre-dispatch cancellation state, recovery-first and cancel-first
  orderings, cancellation from MUTATING/VERIFYING/VERIFIED/CHECKPOINTING plus stale ROLLING_BACK
  and retryable RECOVERY_FAILED (including future-backoff bypass), provider-dispatch cancellation
  fencing, exact workspace restoration with CANCELLED_WINS, atomic parent/task cancellation
  rollback on a forced task-write failure, and bounded quarantine.
- `WorkspaceGenerationTerminalReconcilerTest`: accepted/rolled-back task and operation convergence.
- `WorkspaceGenerationProtocolReferenceModelTest`: exhaustive reachable-state exploration for the
  coupled run/task/generation/operation/authority/intent/checkpoint/publication invariants.
- `WorkspaceGenerationConvergenceServiceTest`: terminal re-read, held-BUILD-lease rollback, and
  recovery-required behavior without request-thread inference.
- `WorkspaceGenerationAuthorityQueryContractTest`: the production JPA join takes the caller's exact
  lifecycle state and cannot regress to a hard-coded MUTATING-only query.
- `MigratedSchemaBootSmokeTest`: all migrations + Hibernate validation.
- `WorkspaceGenerationArchiveServiceTest`: exact multi-repo cross-node reconstruction.
- `WorkspaceGenerationRecoveryServiceTest`: replacement-node restore uses the current configured
  workspace root and repository-relative identities, never the persisted old-host path; stale
  local trees require RECOVERY authority; every unresolved kill-point restores tracked, staged,
  untracked, nested-repository, and ignored-artifact state exactly.
- `WorkspaceIgnoredArtifactGuardTest`: stale-output cleanup, baseline preservation, and Git's
  exact NUL-delimited ignored-file classification, tracked-sibling preservation, ignored-product
  rejection, nested-repository isolation, and safe live/broken symlink unlinking.
- `BuildEnvironmentPlanResolverTest`, `BuildEnvironmentMaterializerTest`, and
  `BuildEnvironmentSequentialTaskTest`: lock/image/network binding, mixed-language roots,
  product-mutation refusal, internal-protocol classification, and task-2 reconstruction after
  task-1 cleanup for Node, Python, Maven, Gradle, Go, and Rust.
- `BuildEnvironmentReceiptPostgresTest`: append-only receipts and database phase gates on a real
  PostgreSQL 16 schema.
- `WorkspaceGenerationLifecycleBoundaryTest`: task-local ignored dependencies survive deterministic
  verification but are purged before checkpoint/acceptance; rejection evidence commits before
  filesystem rollback and restore failure is durable.
- `SandboxCommandJournalPostgresTest`: real-PostgreSQL admission/dispatch/settlement, immutable
  successful and indeterminate receipts, protocol-v2 high-level receipt binding, stale-dispatch
  recovery, receipt immutability, and previous-writer v1 compatibility.
- `BuildVerificationCommandArchitectureTest`: every verification command journals admission before
  dispatch and settles afterward; lifecycle passing evidence binds physical command receipts while
  recovery code cannot manufacture `SUCCEEDED`.
- `PublicationPreparationPostgresTest` and `PublicationPreparationArchitectureTest`: exact
  preparation/provision/push transition guards, stale-outcome recovery, immutable receipts,
  journal-before-side-effect ordering, and prohibition of the retired generic BUILD commit path.
- `WorkspaceGenerationDispatchAuthorityTest`, `WorkspaceGenerationAuthorityArchitectureTest`, and
  `WorkerCorrectionToolFenceTest`: explicit coordinator-to-worker capability handoff, exact
  run/task/attempt/phase dispatch fencing, fresh lease/state checks, and absence of generation
  ThreadLocal authority.
- `TaskAcceptanceVerifierTest`: zero-diff rejection, repository-owned test evidence,
  actual-code-change test escalation, exact
  initial test counts, command drift, immutable-bootstrap tamper rejection, held-out endpoint
  rejection, and green-workspace refusal.
- `TaskAcceptanceContractResolverTest`: typed canonical contract/digest, sorted touch paths,
  documentation-vs-code test policy, and admitted plan/goal/workspace authority binding.
- `BuildTaskGenerationCoordinatorTest`: required orchestration order, exact checkpoint-before-accept,
  deterministic proof outranking worker termination state, rollback/no-accept behavior for failed
  deterministic verification, flag-off
  telemetry isolation, schema-v2 execution evidence, typed lifecycle emission, initial-delta seed,
  and hidden-verifier non-disclosure to the worker.
- `GreenfieldBaselineServiceTest` + `GreenfieldWorkspaceCompositionTest`: the split
  planner/materializer facade, typed-origin behavior,
  executable external Python/Node red baselines, no product implementation, exact multi-repository
  scoping, real frozen commands, and held-out FastAPI/Node behavior.
- `WorkspaceOriginResolverTest` + `RunWorkspaceOriginServiceTest`: submit/history derivation,
  persisted-origin precedence, exact durable update cardinality, and iteration non-reclassification.
- `DeterministicFailureDeltaTest` + `WorkerAutoVerifyProgressTest`: stable normalized comparison of
  consecutive verifier evidence and first-edit comparison against the V378 red state.
