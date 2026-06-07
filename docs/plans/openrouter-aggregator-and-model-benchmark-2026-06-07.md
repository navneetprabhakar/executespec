# OpenRouter-as-aggregator + new-model benchmark — future notes

> **Status:** NOTES FOR FUTURE (no code yet). Captured 2026-06-07 from a user request.
> **Two coupled items:** (A) benchmark 3 new OpenRouter-hosted models; (B) the architectural finding that motivates doing (A) cleanly — OpenRouter is modeled as a *provider*, but it's really an *aggregator*.

---

## A. Models to benchmark (via OpenRouter endpoint)

User wants these three run through the production code-gen path (benchmark harness, real pipeline — only the sandbox may be test-specific, per the standing rule):

| User's name | Likely OpenRouter slug (⚠️ verify live) | Notes |
| --- | --- | --- |
| `xiaomi/mimo-v2.5` | `xiaomi/mimo-v2.5` (already vendor-prefixed) | verify exact slug |
| `minimax-m3` | `minimax/minimax-m3` or `minimaxai/minimax-m3` | verify exact slug |
| `qwen3.7-plus` | `qwen/qwen3.7-plus` or `alibaba/qwen3.7-plus` | verify exact slug |

**Before adding any of these, verify against the live catalogue** `https://openrouter.ai/api/v1/models` (the existing `OpenRouterModelMapper` discipline: never send an unverified slug). Also verify **each supports native tool-calling through OpenRouter** — the V2 worker is native-tool end-to-end; a model without tool-calling can't run the worker loop. And confirm **context-limit + max-output-tokens + live pricing** for the matrix entry.

Benchmark cohort to compare against: the existing 5-provider set (Anthropic / OpenAI / Gemini / DeepSeek-native / Qwen3-Coder). Suggested slices: SWE-bench Lite Hard-5 or the mixed10, and/or the TB-20 (TB is a different remote path — see caveat). Requires an `OPENROUTER_API_KEY` (ENTERPRISE BYOK).

---

## B. Architectural finding — OpenRouter is a provider, should be an aggregator

### Current state (grounded 2026-06-07)
- `OPENROUTER` is a `LlmProvider` enum value (`router/LlmProvider.java`) — one provider alongside ANTHROPIC/OPENAI/GEMINI/DEEPSEEK. Tagged `"Gateway"`, **excluded from the failover chain** (`LlmRouter.FAILOVER_EXCLUDED_PROVIDERS = {"deepseek","openrouter"}`, L111), ENTERPRISE-BYOK only.
- Dispatch: `LlmRouter.callOpenRouter(...)` (L567) → `spring.ai.openrouter.base-url` (`https://openrouter.ai/api`) with the org's BYOK key, OpenAI-wire-compatible via `OpenAiNativeToolClient`.
- **The matrix-id → slug mapping is a HARDCODED Java allow-list:** `router/OpenRouterModelMapper.SLUGS` (a static `Map.ofEntries(...)`). `toSlug()` throws on any id not in the map.
- A matrix entry (`application.yml model-matrix.model-pricing`, e.g. `deepseek-v4-flash` at L272) carries `provider: openrouter` + `chain-tier` + `context-limit` + `max-output-tokens` + `display-label` + `pricing` + `notes` — **but NOT the OpenRouter slug.** The slug lives only in Java.

### The wrongness
- **Violates the stated invariant.** CLAUDE.md / `ModelRegistry`: *"YAML is the single source of truth for every model identifier."* The OpenRouter slug is a model identifier living in Java, split from the rest of the entry.
- **Adding a model = 2 edits in 2 languages** (YAML entry + Java `SLUGS` map) + a recompile. For an *aggregator* whose entire value is "any model in the catalog," that's backwards — it should be a pure-config addition.
- **Treated as a provider with a fixed menu** rather than a transport that carries whatever the matrix declares. The allow-list's original purpose (fail-fast on unverified slugs) is real, but it belongs as a *validation* step over a YAML-declared slug, not as the *source* of the slug.

### Proposed change (OpenRouter = aggregator/transport)
1. **Add an explicit slug field to the matrix entry**, e.g. `upstream-slug: xiaomi/mimo-v2.5` (or `openrouter-slug:`), read by `ModelRegistry`. The matrix id stays the ExecuteSpec-internal handle; the slug is the catalogue address.
2. **`OpenRouterModelMapper.toSlug(id)` reads the YAML-declared slug** (via `ModelRegistry`) instead of the hardcoded map. Keep the allow-list semantics as an *optional* verification flag (e.g. a `verified: true` marker or a periodically-refreshed catalogue check), not as the source of truth. Fail-fast stays, but on "slug missing from the entry," not "id missing from Java."
3. **Any OpenRouter-hosted model becomes a pure YAML addition** — `provider: openrouter` + `upstream-slug` + pricing/context/label. No code, no recompile. xiaomi/minimax/qwen3.7-plus drop in as three YAML blocks.
4. **Touch the validation path:** `OrgModelOverrideService` validates org matrix overrides against `OpenRouterModelMapper`'s allow-list today — it must read the YAML-derived slugs instead, or the per-org override picker won't see the new models.
5. **Keep** the failover-exclusion + ENTERPRISE-BYOK gating unchanged — those are correct for a gateway.

### Effort / risk
- Small-to-medium, mechanical, flag-free (it's a config-schema refactor, not a behavior change). Pin with a test: a YAML-declared OpenRouter model resolves end-to-end through `LlmRouter` without a `SLUGS` entry.
- Risk is low — the existing hardcoded entries can stay during migration (read-through: YAML slug wins, fall back to `SLUGS`), then delete `SLUGS` once every OpenRouter entry carries its slug.
- Doc-refresh: CLAUDE.md (models/routing section), `router/CLAUDE.md`, `docs/CODEMAP.md` if the entry schema changes.

### Caveats specific to the 3 benchmark models
- **Tool-calling:** confirm each exposes OpenAI-style function/tool calling through OpenRouter (worker requirement). MiniMax / MiMo / Qwen-plus tool-calling fidelity varies — a model that returns XML-ish or no tool calls won't run the V2 worker.
- **TB path nuance:** Terminal-Bench runs remote-brain/local-hands and is currently DeepSeek-only by standing instruction. Benchmarking these on TB means broadening provider scope explicitly — confirm before doing so. The BUILD/PATCH (SWE-bench) path is the cleaner first comparison.
- **Reasoning-token echo:** if any is a thinking-mode model, it may need reasoning-content round-trip (the deepseek-v4-pro-native blocker at `application.yml:260`). Check before tier-routing.

---

## Suggested sequencing (when picked up)
1. Verify the 3 slugs + tool-calling + pricing against the live OpenRouter catalogue.
2. Do the small aggregator refactor (slug-in-YAML) — so adding the models (and any future OpenRouter model) is config-only.
3. Add the 3 matrix entries; smoke one call per model through `LlmRouter` (tool-call validity).
4. Run the benchmark slice (SWE-bench Lite mixed10 or Hard-5) × the 3 models; compare to the 5-provider cohort.
5. Record results in `docs/reason-log/2026-05.md` (or the then-current month).

**Cross-refs:** `router/OpenRouterModelMapper.java`, `router/LlmProvider.java`, `agent/LlmRouter.java:567` (`callOpenRouter`), `application.yml:74,95,237-298` (openrouter base-url + matrix entries), `router/ModelRegistry.java`, `org` model-override path (`OrgModelOverrideService`).
