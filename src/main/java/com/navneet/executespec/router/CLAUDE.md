# router/ — Model Selection & Provider Routing

Tier-based model selection with fixed-priority provider fallback.

## Key Files
- `ModelRouter.java` — Selects model+provider from tier. Fixed priority: Anthropic → OpenAI → Google. Falls back to next on failure/unavailability.

## Tiers
- FAST: Haiku / GPT-5.4-mini / Gemini Flash
- CAPABLE: Sonnet / GPT-5.4 / Gemini Pro
- POWER: Opus / GPT-5.4 / Gemini Pro

## Provider Order
Fixed priority: Anthropic → OpenAI → Google. No weighted distribution — first healthy provider wins.
