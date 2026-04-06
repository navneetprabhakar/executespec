# router/ — Model Selection & Provider Routing

User-selected provider architecture with YAML-configured model matrix and Redis-backed circuit breaker.

## Key Files
- `ModelResolver.java` — Resolves `agentTier × provider → model` from the model matrix. Handles health-based failover via ProviderHealthTracker.
- `ModelMatrixProperties.java` — `@ConfigurationProperties` for the YAML model matrix (providers, agent-tier-map, failover-order).
- `AgentTier.java` — Enum: PLANNING, WORKHORSE, LIGHTWEIGHT
- `ResolvedModel.java` — DTO: requestedProvider, actualProvider, model, agentTier, fallback
- `ProviderHealthTracker.java` — Redis-backed circuit breaker. Marks provider unhealthy after 5 consecutive failures. Auto-recovers via 60s Redis TTL.
- `LlmProvider.java` — Enum: ANTHROPIC, OPENAI, GOOGLE
- `AvailableProviderResolver.java` — Resolves available providers per run based on org policy + API key availability

## Agent Tiers (from application.yml model-matrix)
- PLANNING: Sonnet 4.6 / GPT-5.4 / Gemini 2.5 Flash (planner, reviewer, worker stall escalation)
- WORKHORSE: Haiku 4.5 / GPT-5.4-mini / Gemini 2.5 Flash (workers, compiler, test planner, test worker, conflict resolver, prompt optimiser, report generator)
- LIGHTWEIGHT: Haiku 4.5 / GPT-5.4-mini / Gemini 2.5 Flash (chat, context, test report, triage)

## Provider Selection
Each project selects its AI provider (ANTHROPIC/GOOGLE/OPENAI). ModelResolver maps `agentTier × provider → model` from YAML config. Failover to next healthy provider if selected provider is down.

## Circuit Breaker (ProviderHealthTracker)
- **Storage:** Redis (`StringRedisTemplate`). Keys: `provider:health:{name}`, `provider:failures:{name}`
- **Threshold:** 5 consecutive failures → circuit OPEN
- **Recovery:** Unhealthy key has 60s TTL → auto-expires → provider assumed healthy again
- **Fail-open:** Redis errors degrade to "assume healthy" (never blocks LLM calls due to Redis issues)
- **Metrics:** `executespec.provider.circuit_opened`, `executespec.provider.circuit_closed` (tagged by provider)
- **Integration:** LlmRouter checks health before each provider attempt; records success/failure after each call
