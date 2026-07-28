# Execution View launch and solution execution

`execution/` owns the retained Execution View launch/solution graph. BUILD child creation delegates
to the same customer admission and orchestrator path as other cloud runs; it must not persist a
detached `PipelineRun` or recreate project/repository/provider policy locally.

## Current uncommitted reservation and recovery contract

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
  on the current uncommitted source.

Do not describe this as exactly-once provider/tool execution. It fences approved-solution identity
and terminal observation; ordinary WORKING side effects retain their existing recovery semantics.
