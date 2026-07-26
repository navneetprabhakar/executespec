# Local/dev evidence-safe execution gates

Local and dev deliberately enforce the same three server-side quality gates:

- `execution.goal-decision.enforcement.enabled=true` stops the sequential executor when durable
  goal evidence says the task is blocked, failed, or unsafe to continue.
- `worker.verify-before-complete.enabled=true` runs the resolved project test suite before accepting
  final-task `completeTask`.
- `worker.auto-verify.enabled=true` runs bounded verification after edit bursts and returns concrete
  failures to the worker.

`EvidenceSafeExecutionGateProfileInitializer` repairs these values at startup only under the
`local` and `dev` Spring profiles. It does not run under `prod`; existing production defaults remain
unchanged.

The older `worker.build-tdd.enabled` and `build.test-exec-gate.enabled` flags stay off. They add a
model-visible test tool/schema path that previously caused no-edit regressions. VBC and auto-verify
provide the real server-owned test/build evidence without changing provider tool schemas.

Cancellation is not a feature flag. The worker and sequential executor consult a durable scalar
`CANCELLED` query before each task/provider dispatch and immediately after every provider response.
Once observed, the current task is recorded `PARTIAL`, the remaining tasks are skipped, the run
summarises as aborted, and a late provider response cannot become `COMPLETE`.
