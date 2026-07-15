# Provider prompt overlay rollout policy

Status: controlled rollout only; production default remains OFF.

`worker.provider-prompt-tuning.enabled` is a global database-backed feature flag. The current
`FeatureFlagService` does not attach an environment/profile scope, so enabling it through the admin
API affects every application instance sharing that database. A local `application-*.yml` value
cannot safely create a dev-only override while the V219 row exists.

## Safe procedure

1. Use an isolated local/dev database or a maintenance window with no production traffic.
2. Record a baseline on the same prompt corpus with the flag disabled.
3. Enable the flag through the authenticated SUPER_ADMIN feature-flag API.
4. Run the bounded provider matrix (Anthropic/OpenAI/Gemini/DeepSeek; add OpenRouter/Fireworks
   only when their keys and caps are explicitly reserved).
5. Require zero-regression on completion/tool-call validity and compare tokens, latency, and cost
   before promoting the flag. Do not infer quality from a single successful run.
6. Roll back by setting the same flag to `false` if any provider regresses or the budget ledger is
   unavailable.

The deterministic `ProviderPromptTuningTest` proves fragment selection when the flag is ON and
byte-identical base-prompt behavior when it is OFF. It does not justify a production default flip.
The terminal-benchmark lane has a separate provider-prompt flag and may be evaluated independently.

## Release decision

Keep V219's `false` seed unchanged until a comparable, budget-reconciled cross-provider matrix is
available. A profile-scoped rollout requires a separate feature-flag scope implementation; do not
simulate it with a shared global row or an untracked environment variable.
