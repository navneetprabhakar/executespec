# Finding-triggered PATCH submission

`FindingPatchSubmitter` is the single server-internal chokepoint for PR-review, tech-debt, CI-
failure, and code-review autofix findings. It remains PATCH-specific and must not be routed through
the BUILD-only customer submission service.

The current uncommitted V350-era implementation resolves the active host project and active finding
actor before dedupe or dispatch. The project organization is the run's data/governance tenant; the
actor's home organization is the payer, credential, and provider-policy authority. It submits both
identities through `RunOrchestrator.SubmitPolicy` and stamps `triggered_by_finding` on the child.

Current-source focused coverage and clean 5,738-test backend verification pass. Existing
deterministic tests are not generated-code, live-provider, browser, deployment, or release
evidence.
