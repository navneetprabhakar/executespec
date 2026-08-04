# Run admission, idempotency, and iteration

## Terminal outcome and workspace recovery (2026-08-02)

`run/outcome/RunTerminalOutcomeContract` is the additive customer-safe interpretation of terminal
runs. Persisted forensic fields remain unchanged; API/Web/email/in-app consumers use the safe
projection. Unknown failures receive a stable run support code. `RunSyncService` attempts
`WorkspaceRestoreService.ensureWorkspaceAvailable` before returning `WORKSPACE_UNAVAILABLE` (410).
Deterministic HTTP failures include separate customer-safe outcomes for a missing required route, a
forbidden method that was implemented, and 405 behavior that cannot be proven because no supported
method exists at the path; raw validation/provider detail remains private.
The Web result surface renders exactly one primary control from `terminalOutcome.nextAction`
(honoring an explicit `href`) and keeps Logs/Evidence/Files as secondary diagnostics. It treats
`USER_STOPPED` as a neutral stop rather than a failed recovery case and does not poll for a report
after a terminal one-shot report read returns absent. Email and in-app cancellation notifications
also honor the neutral completion preference rather than the failure preference.
An existing pull request is publication history, not attempt-scoped mutation proof: only a clean or
warning completion paired with a PR may set `impact.codeChanged=true`; failed/limited attempts keep
that field unknown unless a future mutation receipt proves it. Provider/key/model-matrix outcomes
return one `OPEN_PROVIDER_SETTINGS` action at `/provider-keys` instead of retrying an unchanged
route.
Because durable archives intentionally omit `.git`, a successful restore reconstructs repository
metadata only from immutable repo snapshots plus an authoritative credential-free coordinate:
linked Project repositories use their stored SCM clone URL, while standalone runs use the exact
`PipelineRun.repoUrl` rather than a fabricated GitHub URL. Clone coordinates containing URI user
information, a query, or a fragment fail closed before Git metadata is written. The normal
caller-authenticated
fetch/reset then selects source bytes. Sync also cleans restored untracked archive residue while
preserving `.agent-context/`. Render may use restored nested repository bytes without `.git`, but
change evidence and required iteration fail closed until original Git history is available; they do
not invent a baseline. A confirmed absent archive is the permanent 410; storage/platform restore
faults return retryable `WORKSPACE_RESTORE_FAILED` (503) without leaking the provider error.

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
