# API Key Management — Requirements

## Overview

Multi-level API key resolution with three key source modes, per-org provider restrictions, and runtime key injection. Supports user-level, org-level, and system-level keys with configurable fallback behavior.

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
- ModelRouter respects restrictions when selecting providers
- Frontend shows available providers based on org settings

### 4. LLM Router (Provider Fallback)

**Requirements:**
- Three fallback chains:
  - ARCHITECT: Opus → Gemini Pro → GPT-4o
  - CAPABLE: Sonnet → Gemini Flash → GPT-4o
  - UTILITY: Haiku → Gemini Flash → GPT-4o-mini
- Fallback modes: IMMEDIATE (planner/reviewer), TASK_BOUNDARY (workers)
- Per-minute rate limit: sleep 15s, retry once
- Anthropic overload (529): sleep 30s, retry once
- Per-run key injection from RunKeyStore
- Respect org enabledProviders policy

### 5. Model Router (Tier Escalation)

**Requirements:**
- Two-axis selection: complexity → tier, then best healthy provider
- Tiers: FAST (Haiku) → CAPABLE (Sonnet) → POWER (Opus)
- Complexity mapping: LOW → FAST, MEDIUM → FAST, HIGH → CAPABLE
- Retry escalation: attempts 1-2 stay at tier, attempt 3 escalates one tier
- Never auto-escalate to POWER
- Provider health tracking: 3 consecutive failures = unhealthy, 5-min auto-recovery
- Track metrics per task (model, provider, tier, pass/fail, duration)

---

## Key Files

| File | Purpose |
|------|---------|
| `agent/ApiKeyResolver.java` | Multi-level key resolution |
| `agent/ResolvedKeys.java` | Resolved key set per run |
| `agent/RunKeyStore.java` | Runtime key injection store |
| `agent/LlmRouter.java` | Provider fallback chains |
| `router/ModelRouter.java` | Tier-based model selection |
| `router/AvailableProviderResolver.java` | Org provider restrictions bridge |
| `router/ProviderHealthTracker.java` | Provider availability tracking |
| `router/ModelTaskMetric.java` | Per-task metrics entity |

## Database Migrations

| Version | Description |
|---------|-------------|
| V10 | Per-user AI provider keys |
| V11 | Org-level AI provider keys |
| V14 | Key source mode + provider restrictions |
| V15 | Model task metrics table |
