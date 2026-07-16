# `batch/` — retired V2 batch surface

ExecuteSpec 2.0 does not implement provider batch execution. New `BATCH` approvals are rejected
by `RunController` with HTTP 410 and error code `BATCH_EXECUTION_RETIRED`; the production worker
always uses the realtime pipeline.

The former provider clients, router, coordinator, scheduled poller, and resume service were
removed because they were inert scaffolding: no production caller reached them, the coordinator
always threw, and the provider clients bypassed the canonical `LlmRouter` path. Keeping those
beans registered made unsupported network-spend paths look available and added scheduler/config
overhead.

Compatibility boundary:

- V61/V117 migrations remain immutable and are not deleted or rewritten.
- Historical `PipelineRun` batch columns, cost fields, DTO fields, and SSE event names remain
  readable for old records and are not reused for new execution.
- The `harness.v2.batch_enabled` feature-flag row remains historical; it no longer controls a
  runtime bean or provider dispatch path.

If batch execution is ever reconsidered, design it as a new, canonical-router-backed feature with
fresh contracts, live provider verification, explicit spend confirmation, and a new migration;
do not restore the deleted scaffold.
