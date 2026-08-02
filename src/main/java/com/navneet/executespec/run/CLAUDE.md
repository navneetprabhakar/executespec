# Run admission, idempotency, and iteration

## Terminal outcome and workspace recovery (2026-08-02)

`run/outcome/RunTerminalOutcomeContract` is the additive customer-safe interpretation of terminal
runs. Persisted forensic fields remain unchanged; API/Web/email/in-app consumers use the safe
projection. Unknown failures receive a stable run support code. `RunSyncService` attempts
`WorkspaceRestoreService.ensureWorkspaceAvailable` before returning `WORKSPACE_UNAVAILABLE` (410).
Because durable archives intentionally omit `.git`, a successful restore reconstructs repository
metadata only from the run's immutable repo snapshots and the linked project's credential-free SCM
clone coordinate, then the normal caller-authenticated fetch/reset selects source bytes. Sync also
cleans restored untracked archive residue while preserving `.agent-context/`. A confirmed absent
archive is the permanent 410; storage/platform restore faults return the retryable
`WORKSPACE_RESTORE_FAILED` (503) contract without leaking the underlying path or provider error.

`run/CustomerRunSubmissionService` is the shared BUILD admission boundary for REST submit,
fork/iterate, confirmed Web Agent cloud-run creation, and Execution View. It resolves project write
authority, explicit repository admission, provider/key/spend policy, host-project tenancy, and the
submitting actor's payer/provider-policy organization before dispatch.

Backend checkpoint `13ead3f40` stamps host `PipelineRun.orgId` separately from immutable
`billingOrgId`. `RunIterationService` preserves the source run's host tenant and inherited route/
cost policy while capturing the current iteration actor's organization as the child payer. A losing
idempotency contender is cancelled and must be durably accounted for before the canonical child is
returned. `IterationSubmitPolicyFactory` constructs that follow-up policy without inheriting the
source payer, while `integration.RunSubmitAuthority` is the single submission-time resolver for
host tenant versus billing org. V350 stores/backfills the identity split and PostgreSQL rejection
triggers make both payer/credential authority columns immutable; application admission and dispatch
guards independently enforce the binding before spend.

The external-collaborator lifecycle and named plan-gate PostgreSQL gates pass independently, and
clean backend verification passes 5,738/5,738 with zero failures, errors, or skips. Do not infer
generated-code quality, browser E2E, provider quality, deployment, or release readiness from these
control-plane contracts.
