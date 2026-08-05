# ExecuteSpec Studio — Authority, Effect, Recovery, and Redaction Matrix

| Action | Authority shown before action | Durable effect | Recovery | Redaction rule |
|---|---|---|---|---|
| Start build/change | requested outcome, affected Project, estimate band | Task + Version | cancel, retry, restore prior Version | no secret/provider key in prompt transcript |
| Connect source | provider, repository, branch, permissions | SourceBinding + ContextBundle | disconnect; preserve export/history | tokens never echoed |
| Use local hands | exact folder and command categories | AgentSession + action ledger | revoke PAT, stop session | local file content stays scoped to approved context |
| Add data/auth/storage/function | resource, environment, access default, quota | Resource + Version | remove/restore/migrate | secret values masked; sample data synthetic |
| Connect API/secret | requested scopes and server-side use | SecretBinding | rotate/revoke/retry | last four characters only where useful |
| Approve plan | plan, provider/model, cap, external effects | approved Task/Plan | cancel before side effect; retry from checkpoint | client/user data minimised |
| Commit/push/PR | repo, branch, files, commit authority | Change + PR | revert/close/new change | client boundary visible on every review |
| Run checks/CI | suite, environment, estimated duration/cost | Check + Evidence | retry, quarantine flaky, inspect raw logs | logs redact credentials and customer records |
| Publish/deploy | target, privacy, domain, data migration, cost | immutable Release | rollback to named Release | production secrets not shown |
| Share/invite | audience, permission, expiry | ShareGrant | revoke/expire | client list never searchable across workspaces |
| Upgrade/purchase | exact capability and recurring/one-time price | entitlement/usage period | billing cancellation/refund policy route | no dark-pattern countdown |
| Export/delete/offboard | included objects, retention, irreversible boundary | export/delete/offboard audit | cooling-off where safe; support path | deletion proof contains IDs, not data payload |

P0 rule: every external, destructive, billable, production, permission-expanding, or client-boundary-changing action uses a DecisionCard with named target, effect, and recovery.

