# API Key Management — Requirements

> Last updated: 2026-04-03

## Overview

Multi-level API key resolution with three key source modes, per-org provider restrictions, and runtime key injection. Supports user-level, org-level, and system-level keys with configurable fallback behavior. Default provider is OpenAI; user-selected per project.

---

## Features

### 1. Key Source Modes

| Mode | Behavior |
|------|----------|
| SYSTEM | All runs use platform (system) keys. User/org keys ignored. |
| OWN_KEYS | User/org keys only. No system fallback. At least one enabled provider must have a key. |
| HYBRID (default) | User/org first, system fallback if no user/org key. |

**Requirements:**
- Configured at org level: `org.key_source_mode`
- Validated pre-flight before run submission (OWN_KEYS: return 400 if no keys)
- Recorded on each run: `pipeline_run.key_source`
- UI: Org admins manage keys and mode in Settings page
- Endpoint: `PUT /api/org/me/settings/keys`

### 2. Key Resolution Hierarchy

```
1. User-level key  (app_user.anthropic_api_key / openai_api_key / gemini_api_key)
2. Org-level key   (org.anthropic_api_key / openai_api_key / gemini_api_key)
3. System key      (application.yml — spring.ai.*.api-key)
```

**Requirements:**
- ApiKeyResolver resolves at run-start based on user + org + mode
- Returns ResolvedKeys (per-provider key or null for system fallback)
- Keys stored in RunKeyStore (ConcurrentHashMap keyed by runId) for runtime injection
- LlmRouter looks up per-run keys from RunKeyStore for every LLM call
- Keys cleared from RunKeyStore after run completes

### 3. Provider Restrictions

**Requirements:**
- Org-level `enabled_providers` CSV (e.g., "anthropic,openai")
- null = all providers allowed
- Restricts which providers can be used for runs in that org
- Validated by AvailableProviderResolver
- ModelResolver respects restrictions when selecting providers
- Frontend shows available providers based on org settings

### 4. LLM Router (Provider Fallback)

**Requirements:**
- User-selected provider per project (Anthropic, Google, or OpenAI). Default: OpenAI.
- YAML-configured model matrix maps AgentTier x Provider to Model via ModelResolver
- Three agent tiers with provider-specific models:
  - PLANNING: `claude-sonnet-4-6` / `gemini-3-flash-preview` / `gpt-5.4`
  - WORKHORSE (Sonnet-class): `claude-sonnet-4-6` / `gemini-3-flash-preview` / `gpt-5.4`
  - LIGHTWEIGHT (Haiku-class): `claude-haiku-4-5-20251001` / `gemini-3-flash-preview` / `gpt-5.4-mini`
- Health-based failover via ProviderHealthTracker (Redis-backed circuit breaker)
- Failover chains by primary provider:
  - Anthropic -> [OpenAI, Google]
  - Google -> [OpenAI, Anthropic]
  - OpenAI -> [Google, Anthropic]
- Fallback modes: IMMEDIATE (planner/reviewer), TASK_BOUNDARY (workers)
- Per-minute rate limit: sleep 15s, retry once
- Anthropic overload (529): sleep 30s, retry once
- Per-run key injection from RunKeyStore
- Respect org enabledProviders policy

### 5. ProviderHealthTracker (Circuit Breaker)

**Requirements:**
- Redis-backed circuit breaker for provider availability tracking
- 5 consecutive failures per provider -> circuit OPEN (provider marked unhealthy)
- 60-second TTL auto-recovery (circuit resets to CLOSED after TTL expires)
- LlmRouter checks provider health before each call
- LlmRouter records success/failure after each call
- Metrics tracked per provider: failure count, circuit state, last failure timestamp

### 6. ModelResolver (Tier-Based Model Selection)

**Requirements:**
- Two-axis selection: AgentTier (PLANNING / WORKHORSE / LIGHTWEIGHT) x Provider (Anthropic / Google / OpenAI)
- Configuration via `application.yml` -> `model-matrix:` section
- Returns the correct model ID for a given tier + provider combination
- Works in conjunction with ProviderHealthTracker for failover selection

---

## Key Files

| File | Purpose |
|------|---------|
| `agent/ApiKeyResolver.java` | Multi-level key resolution |
| `agent/ResolvedKeys.java` | Resolved key set per run |
| `agent/RunKeyStore.java` | Runtime key injection store |
| `agent/LlmRouter.java` | Provider fallback chains + per-run key injection |
| `router/ModelResolver.java` | Tier-based model selection (AgentTier x Provider) |
| `router/ModelMatrixProperties.java` | YAML model matrix configuration |
| `router/AgentTier.java` | Agent tier enum (PLANNING, WORKHORSE, LIGHTWEIGHT) |
| `router/AvailableProviderResolver.java` | Org provider restrictions bridge |
| `router/ProviderHealthTracker.java` | Redis-backed circuit breaker (5 failures, 60s TTL) |
| `router/ModelTaskMetric.java` | Per-task metrics entity |

## Database Migrations

| Version | Description |
|---------|-------------|
| V10 | Per-user AI provider keys |
| V11 | Org-level AI provider keys |
| V14 | Key source mode + provider restrictions |
| V15 | Model task metrics table |
