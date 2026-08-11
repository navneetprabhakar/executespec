# Workspace generation protocol

`WorkspaceGenerationQualificationEvidenceController` is a SUPER_ADMIN-only, `no-store` read path
for separately authorized qualification. It exposes the preserved rejected-diff JSON plus immutable
attempt/route/usage/receipt fields that customer-safe run detail intentionally redacts. It is not
acceptance authority and must never be opened to ordinary run readers. Historical attempts that
rolled back before `beginVerification` honestly return a null candidate generation; their negative
proof is the digest-bound non-empty rejected diff plus exact prior-frontier restoration and durable
deterministic task-smoke failure, not an invented generation digest.

V374 makes one initial BUILD task the unit of mutation authority. V375 preserves original rejection
and rejected-diff evidence independently from first recovery-failure evidence. V376 seeds the
default-off shared BUILD-loop rollout flag; V377 freezes the schema-v2 task contract, planned
initial failure, physical route and usage evidence. V378 freezes the initial acceptance probe
executed after baseline materialization and before provider dispatch; only an observed red result
admits dispatch. `WorkspaceGenerationLifecycle`
owns `PREPARED → MUTATING → VERIFYING → VERIFIED → CHECKPOINTING → ACCEPTED`; every rejection uses
`ROLLING_BACK → ROLLED_BACK`, and crash recovery may use `RECOVERY_FAILED → ROLLING_BACK` retries.
Never infer acceptance from a worker outcome or task status.

`WorkspaceGenerationExecutionEvidenceRecorder` owns the transactional MUTATING-state CAS that
freezes effective route, iteration/tool/model-call counts, token categories, and cost before
verification. This keeps usage-evidence persistence separate from the lifecycle state machine.

## Invariants

- Persist PREPARED, its exact before checkpoint, frozen task/authority digests, ignored-artifact
  baseline, and workspace lease before provider or tool mutation.
- Bind MUTATING to the exact RUNNING `llm_execution_operation` attempt/lease. Every LLM and tool
  dispatch repeats the database join fence. Provider return is not authority: VERIFYING, receipt
  persistence, CHECKPOINTING, checkpoint acknowledgement, and ACCEPTED repeat the exact LLM lease,
  workspace lease, and `pipeline_run.status=WORKING` fence. Pause/cancel may still roll back but
  cannot advance the frontier.
- Verification commands are resolved by the server. Planner/model commands are not acceptance
  authority. The immutable receipt must bind candidate generation + task contract and prove the
  required build/tests. Acceptance reclassifies against the exact candidate diff: zero-diff
  generations fail, and any actual code-file change requires an executed non-empty green suite
  even when planner wording classified the task as documentation/configuration.
- Under `build.shared-code-change-loop.enabled`, `GreenfieldBaselineService` trusts the typed
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
- Archives are internal generation archives, not customer download ZIPs: every repository retains
  its original HEAD bundle and exact tree/modes. Restore must re-attest the canonical multi-repo
  generation. Missing, malformed, partial, or stale local trees are reconstructed into an absent
  sibling and atomically swapped only while holding exact RECOVERY authority; a busy recovery
  leaves the existing bytes untouched.
- Delete every ignored root before mutation and before checkpoint/acceptance, plus rollback and
  recovery. Task-local dependency installation may survive through deterministic verification, but
  ignored bytes are outside both the content-addressed generation and its cross-node archive and
  must never become cross-task state. Dependencies are reconstructed from accepted manifests and
  governed environment inputs at each task boundary.
- Local-hands BUILD is fail-closed until the remote proxy protocol carries the same generations,
  lease and immutable receipt. Do not reintroduce a server-side bypass.

## Persistence

- Migrations: `V374__workspace_generation_protocol.sql`,
  `V375__preserve_workspace_generation_recovery_evidence.sql`,
  `V376__seed_build_shared_code_change_loop_flag.sql`,
  `V377__workspace_generation_execution_evidence.sql`, and
  `V378__observed_initial_build_failure.sql`, and
  `V379__persist_workspace_origin.sql` (nullable staged origin for new writes; no historical
  rewrite or premature NOT NULL tightening).
- Entities: `WorkspaceGenerationAttempt`, `WorkspaceGenerationVerificationReceipt`.
- Evidence rows are immutable while the parent run exists; whole-run retention deletion may
  cascade. Original `failure_*` and `rejected_diff_*` evidence freezes independently from the first
  `recovery_failure_*` tuple, so recovery cannot rewrite causality.
- V377's contract/planned-initial-failure/requested-route tuple and effective route/iteration/tool/call/
  token/cost tuple are write-once. Run detail deliberately omits prompts, patches and failure
  reasons while exposing bounded customer-safe proof and the newest immutable verification receipt.
- V378's executed initial-probe JSON and observation timestamp are independently write-once.
- `WorkspaceGenerationRecoveryService` runs before generic startup replay and reconciles the
  ACCEPTED→task-COMPLETE crash window.

## Tests

- `WorkspaceGenerationProtocolPostgresTest`: real PostgreSQL transitions, exact task-operation and
  workspace-owner/expiry/LLM-expiry/pause/cancel fences, competing-active-attempt rejection,
  single-winner recovery CAS, contiguous frontier monotonicity, pre-bind recovery, receipt binding
  and retention.
- `MigratedSchemaBootSmokeTest`: all migrations + Hibernate validation.
- `WorkspaceGenerationArchiveServiceTest`: exact multi-repo cross-node reconstruction.
- `WorkspaceGenerationRecoveryServiceTest`: replacement-node restore uses the current configured
  workspace root and repository-relative identities, never the persisted old-host path; stale
  local trees require RECOVERY authority; every unresolved kill-point restores tracked, staged,
  untracked, nested-repository, and ignored-artifact state exactly.
- `WorkspaceIgnoredArtifactGuardTest`: stale-output cleanup and baseline preservation.
- `WorkspaceGenerationLifecycleBoundaryTest`: task-local ignored dependencies survive deterministic
  verification but are purged and re-attested before checkpoint/acceptance.
- `WorkerCorrectionToolFenceTest`: ThreadLocal propagation and exact dispatch fence.
- `TaskAcceptanceVerifierTest`: zero-diff rejection, actual-code-change test escalation, exact
  initial test counts, command drift, immutable-bootstrap tamper rejection, held-out endpoint
  rejection, and green-workspace refusal.
- `TaskAcceptanceContractResolverTest`: typed canonical contract/digest, sorted touch paths,
  documentation-vs-code test policy, and admitted plan/goal/workspace authority binding.
- `BuildTaskGenerationCoordinatorTest`: required orchestration order, exact checkpoint-before-accept,
  rollback/no-accept behavior for worker or deterministic-verification rejection, flag-off
  telemetry isolation, schema-v2 execution evidence, typed lifecycle emission, initial-delta seed,
  and hidden-verifier non-disclosure to the worker.
- `GreenfieldBaselineServiceTest` + `GreenfieldWorkspaceCompositionTest`: typed-origin behavior,
  executable external Python/Node red baselines, no product implementation, exact multi-repository
  scoping, real frozen commands, and held-out FastAPI/Node behavior.
- `WorkspaceOriginResolverTest` + `RunWorkspaceOriginServiceTest`: submit/history derivation,
  persisted-origin precedence, exact durable update cardinality, and iteration non-reclassification.
- `DeterministicFailureDeltaTest` + `WorkerAutoVerifyProgressTest`: stable normalized comparison of
  consecutive verifier evidence and first-edit comparison against the V378 red state.
