# router/ — Model Selection & Provider Routing

Tier-based model selection with weighted provider distribution.

## Key Files
- `ModelRouter.java` — Selects model+provider from tier. Non-deterministic weighted random (not round-robin).

## Tiers
- FAST: Haiku / GPT-5.4-mini / Gemini Flash
- CAPABLE: Sonnet / GPT-5.4 / Gemini Pro
- POWER: Opus / GPT-5.4 / Gemini Pro

## Config
- Weights in `config/RoutingProperties.java` (default 4:3:3 Anthropic:Google:OpenAI)
- Override via `executespec.routing.provider-weights` in application.yml
