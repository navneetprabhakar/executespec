# Model-specific capability profile contract evidence

Status: **CONTRACT_TESTED; production rollout remains disabled**

This is a no-spend A/B seam test, not a provider-quality claim. It proves that the model-aware
profile path is connected to the production worker tier configuration without requiring an API key
or sending repository content to a provider.

## Evidence

Run from the backend repository:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
mvn -q -Dtest=ModelCapabilityProfileABTest test
```

`ModelCapabilityProfileABTest` exercises the private `SequentialExecutor.tierConfigFor` seam used
by real BUILD-mode worker dispatch with a mocked `ModelResolver`, matrix, and feature-flag service:

| Arm | Input | Observed stagnation thresholds |
| --- | --- | --- |
| Baseline | `worker.capability-profiles.enabled=false` | LOW complexity defaults: warn `10`, force `16` |
| Provider-only control | `anthropic` profile (not selected by the concrete model) | warn `18`, force `34` (asserted as not selected) |
| Model profile | `anthropic/claude-sonnet-profiled`, gate ON | warn `22`, force `47` |

The test therefore catches a dormant implementation: the model is resolved first, the concrete
`provider/model` key wins over the provider fallback, and disabling the evidence gate preserves the
pre-profile baseline. Existing `ToolRegistryWorkerBindTest` and `ProviderPromptTuningTest` cover the
same fail-closed rule for optional tools and model prompt fragments.

## Rollout gate

The profile map in `src/main/resources/application.yml` is `{}` (commented examples only), and
Flyway V271 seeds `worker.capability-profiles.enabled` as `false`. The test is sufficient to claim
**wired contract**, but not sufficient to claim quality uplift or to enable the flag. Graduation
still requires a fixed-corpus, same-provider/model A/B with `k >= 3`, reconciled spend, and a
non-regression decision recorded before adding a live profile entry.
