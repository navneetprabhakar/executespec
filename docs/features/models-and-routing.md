# Models, Routing, and BYOK

## Current Model Shape

The model matrix has three layers:

1. System defaults in `src/main/resources/application.yml`.
2. ORG_ADMIN overrides stored sparsely on `Org.model_overrides_json`.
3. User-level BYOK keys and future user model preferences for users allowed to bring their own keys.

FREE users do not get a matrix. They should receive the configured free-tier default model from properties only.
The org matrix API returns `modelLabels` keyed by canonical model id, so the UI
renders YAML display labels instead of raw provider slugs while still submitting
canonical ids.

## Provider Classes

| Class | Providers | Product rule |
| --- | --- | --- |
| Direct providers | Anthropic, OpenAI, Gemini, DeepSeek | Only route to models the provider directly supports. |
| Aggregators | OpenRouter, Fireworks | Curated model choices over a transport/marketplace. Do not expose the whole catalog by default. |

## Implemented Routing

| Area | Status | Code |
| --- | --- | --- |
| YAML model registry | Live | `router/ModelRegistry.java`, `application.yml` |
| Tier-to-model lookup | Live | `router/ModelResolver.java` |
| Matrix display labels | Live | `OrgController.ModelMatrixView.modelLabels`, `router/ModelRegistry.displayLabelFor` |
| Native tool routing | Live | `agent/LlmRouter.java` |
| OpenAI-wire shared client | Live | `agent/OpenAiNativeToolClient.java` |
| Gemini native client | Live | `agent/GeminiNativeToolClient.java` |
| Anthropic via Spring AI | Live | `agent/LlmRouter.java` |
| OpenRouter mapping | Live | `router/OpenRouterModelMapper.java` |
| Fireworks mapping | Added | `router/FireworksModelMapper.java` |
| DeepSeek native lane | Added | `LlmProvider.DEEPSEEK`, `spring.ai.deepseek.*` |
| BYOK key resolution | Added for DeepSeek/Fireworks | `ApiKeyResolver`, `RunKeyContextStore`, `OrgService`, `AuthController` |
| OpenRouter latency sort | Added | `LlmRouter.callOpenRouter`, `provider.sort=latency` |
| Account-scoped provider preference | Live | `app_user.preferred_ai_provider` (V242), `AiProviderPreferenceService`, `GET/PUT /api/auth/me/provider-preference` |
| Per-call usage attribution (role/step/retry) | Live | `agent/LlmRequest` (`agentRole`/`stepId`/`retryOf`), `cost/RunStepUsageRecorder`, `run_step_usage` (V243) |

## Cross-Surface Provider Preference

`app_user.preferred_ai_provider` (V242) is an account-scoped default provider shared by web, CLI, and VS Code through `GET/PUT /api/auth/me/provider-preference`.

- Resolution order: an explicit provider on the current run/chat request wins **and is persisted as the new preference**; an absent provider resolves to the account preference; project/YAML defaults remain fallback only.
- Legacy `GOOGLE` input normalizes to `GEMINI` (V2 provider naming).
- `RunController.submit`, agent-session turns, and the legacy `/api/spec/chat` fallback all route through `AiProviderPreferenceService` (RL-2026-06-036).

## Latency Finding

Local OpenRouter probing for Chinese/open models showed `provider.sort=latency` was materially faster than OpenRouter default routing for short calls. That does not prove the same result from the `us-east4` VM. The next proof step is a VM-side A/B:

- OpenRouter default.
- OpenRouter `provider.sort=latency`.
- Fireworks for matching models.
- Native DeepSeek for DeepSeek models.

Do not relocate the main app server before that data exists. Prefer route selection and aggregator choice first.

## Matrix Rules To Preserve

- YAML remains the single source of truth for model ids, display names, context windows, output limits, prices, fallback chain, free-tier default, and provider defaults.
- Direct providers should fail validation on unsupported models.
- Aggregators should use curated allow-lists or verified YAML slugs.
- OpenRouter literal route pricing must win before legacy owner-provider fallback.
- DeepSeek native aliases should resolve to native pricing rows.
- BYOK runs must not silently fail over to platform keys.

## Open Decisions

- Whether OpenRouter should become pure aggregator transport with YAML-declared slugs instead of Java hardcoded slug maps.
- Whether Fireworks becomes preferred route for Chinese models from GCP if VM latency proves better.
- Whether a per-(tier/model) user preference should be persisted beyond the account-level provider preference (V242), which currently only pins the provider, not the specific model.
