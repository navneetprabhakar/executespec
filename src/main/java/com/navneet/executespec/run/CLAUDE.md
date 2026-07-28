# Run admission, idempotency, and iteration

`run/CustomerRunSubmissionService` is the shared BUILD admission boundary for REST submit,
fork/iterate, confirmed Web Agent cloud-run creation, and Execution View. It resolves project write
authority, explicit repository admission, provider/key/spend policy, host-project tenancy, and the
submitting actor's payer/provider-policy organization before dispatch.

The current uncommitted V350 source stamps host `PipelineRun.orgId` separately from immutable
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
