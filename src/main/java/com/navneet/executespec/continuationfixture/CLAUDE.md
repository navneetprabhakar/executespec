# Continuation fixture

`ContinuationFixtureController` is local/dev-only, `SUPER_ADMIN`-only test infrastructure at
`POST /api/v2/test/continuation-fixtures`. It creates real `pipeline_run` and `agent_task` rows,
calls the production `GoalContinuationAdvisor`, persists the same goal/continuation/action JSON
consumed by run detail, and emits the normal typed SSE events.

The fixture never calls an LLM, worker, shell, repository, or provider. Automatic actions are
labelled `SIMULATED`. `ContinuationFixtureApprovalAspect` intercepts the existing
`POST /api/runs/{runId}/tasks/{taskId}/continuation/approve` service call only when the run carries
`source_type=CONTINUATION_FIXTURE`; it appends `APPROVED` plus `SIMULATED` evidence and completes the
fixture without dispatch. Every non-fixture approval proceeds unchanged.

Scenarios: `STEP_UP_QUALITY`, `STEP_DOWN_SIMPLE`, `CHANGE_PROVIDER_BREAKDOWN`,
`BLOCK_BENCHMARK`, `BLOCK_CONFIRMATION`, and `ROUTE_PINNED`.
