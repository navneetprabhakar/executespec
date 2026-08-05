# Execution View launch and solution execution

`execution/` owns the retained Execution View launch/solution graph. BUILD child creation delegates
to the same customer admission and orchestrator path as other cloud runs; it must not persist a
detached `PipelineRun` or recreate project/repository/provider policy locally.

`ExecutionRun.branch` is checkout/source intent, not publication authority. V373 persists it as
`PipelineRun.checkoutBranch`. Ordinary launches clone that branch and open a fresh generated PR;
only complete continue-on-PR metadata (tracked open repository + PR number + canonical head branch
and exact 40-hex head SHA) also sets `branchOverride` and the immutable publication identity. Both
unified and approved-solution paths
must preserve this distinction, and canonical replay rejects a child with different branch intent.

## Committed reservation and recovery contract (`13ead3f40`)

- `ExecutionSolutionDispatchRegistry` uses the existing V90 unique
  `(solution_id, attempt_number)` row as the durable pre-dispatch claim. Attempt numbers are
  `retryCount + 1`; the row reserves the canonical child UUID before `RunOrchestrator` starts.
- `ReservedPipelineRunStore` inserts and flushes the reserved `PipelineRun` in its own short
  transaction. Replay must match source, creator, host tenant, billing org, and project; an existing
  unrelated row is never adopted.
- A same-process dispatch exception fences/cancels the reserved child before propagating. Provider
  and SCM side effects remain outside the database transaction.
- `ExecutionSolutionObserverRecovery` scans RUNNING approved solutions after startup and on a
  schedule, claims the existing V315 launch recovery lease, and reattaches to the already-persisted
  child. Normal and recovered observers compete for one terminal projection claim, so only the
  winner publishes terminal launch/solution effects.
- These changes add no migration: they reuse V90 uniqueness and V315 recovery fields. The named
  PostgreSQL dispatch-reservation race/replay gate and clean 5,738-test backend verification pass
  at backend checkpoint `13ead3f40`.

Do not describe this as exactly-once provider/tool execution. It fences approved-solution identity
and terminal observation; ordinary WORKING side effects retain their existing recovery semantics.

## Final PR publication admission

`ExecutionBranchService.createFinalPr` is a publication boundary. It must receive every current
backing `PipelineRun` identity for the launch, load each durable row, and pass each row through
`PublicationAdmissionService` before calling GitHub. Each successful admission keeps its workspace
authority lease open across final-branch creation and PR publication, rechecks ownership before
the irreversible SCM call, and closes all leases on success or failure. A missing backing
identity/row or one failed admission returns a structured `FinalPrResult` and performs no Git
operation. The orchestrator
persists that diagnostic as a terminal launch failure and emits no `EXEC_FINAL_PR_CREATED` event.
