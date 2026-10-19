# BUILD orchestration boundaries

`RunOrchestrator` coordinates the normal BUILD pipeline; `RunLifecycleService` owns control and
recovery entrypoints. Provider calls remain in agents through `LlmRouter`.

`BuildExecutionPolicyStore` freezes plan-bound execution settings in V403. Crash recovery consumes
that exact snapshot and starts a fresh task transcript after generation recovery. Review and
validation use `AcceptedGenerationVerificationRunner` so commands operate in a disposable copy of
the accepted generation rather than mutating the canonical workspace. The original goal, design
block, confirmation requirements, and benchmark requirement survive recovery.

`RunCompletionPublisher` owns terminal SSE closure, metrics, application events, and activation
tracking. The normal BUILD path persists terminal evidence before SCM admission but calls this
publisher only after automatic publication settles or is refused. Early failures still publish
completion immediately through the public `terminate` entrypoint.

`ContinuationActionRecorder` reserves action budgets as RESERVED. The BUILD admission service marks
EXECUTED only at the prepared physical worker boundary and records its generation-attempt id.
Reserved ceilings survive recovery even if dispatch never happened.

`RunPublicationFence` fails closed on unreadable or superseded Execution-View parent identity and
stopped parent executions. `scm/PublicationAdmissionService` shares that check with manual push and
permanent deployment. Durable publication/provision dispatch repeats it under parent-first locks
before external effects. Prepared artifact identity and workspace authority remain mandatory.

Migration contracts: V401 belongs to `workspacegeneration/CLAUDE.md`; V402 to
`report/CLAUDE.md`; V403 to `state/CLAUDE.md`. Applied migrations are never edited.

Publication admissions retain a live parent-authority check. SCM push/PR/repository creation and
permanent image push/deploy recheck it immediately before dispatch, including legacy publication.
A superseded or stopped parent cannot retain authority obtained earlier in the operation.

BUILD operation claims derive `context.child(taskId)` before binding the physical operation lease.
Both initial and replacement claims preserve the durable run reference while replacing the run-level
placeholder task identity, so initial verification and worker dispatch match the generation capability.

Approval and resume share `PlanGateDecisionEvidence.decimal` for PostgreSQL float8/numeric
rounding parity. A valid immutable approval must remain resumable at six-decimal boundaries.
Executor unwind preserves deterministic workspace diagnostic codes instead of replacing them
with INTERNAL_ERROR; unqualified runtime plans have a safe BUILD_PLAN_RUNTIME_UNSUPPORTED code.
