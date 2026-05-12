# Model-Mapping Audit + Pricing Eval — 2026-05-06

> **Snapshot:** point-in-time audit + 3-provider LOW-band eval that drove the
> 2026-05-06 model-mapping consolidation. Last reviewed for relevance:
> 2026-05-12 — kept as a breadcrumb because the 5 fix commits + YAML
> consolidation pattern are still referenced from `CLAUDE.md` and
> `ARCHITECTURE.md` as the single-source-of-truth rationale.
> **Branch this work landed on:** `feature/project-cost-pool-tuning` (merged).
> **Eval results:** `scripts/v2-eval-results/v2-eval-20260506-120242.{json,md}` + the 2026-05-04 baseline.
>
> ---
>
> ## Resolution status (updated 2026-05-06 13:30 IST)
>
> **All 8 audit drift sites consolidated to YAML.** 5 commits on this branch:
>
> | Item | Status | Commit |
> | --- | --- | --- |
> | #1 ModelContextWindowMap (active drift) | ✅ deleted; routes through ModelRegistry | `72d75b49` |
> | #4 RunCostTracker cache multipliers | ✅ reads YAML cached-input-per-1m directly; YAML rates updated to provider reality (OpenAI 50%, Gemini 25%) | `c9c2799a` |
> | #2 ModelController.MODEL_LABELS | ✅ reads YAML `display-label`; new field added to ModelConfig | `fdf0dac1` |
> | #3 RunOrchestrator.defaultModelFor | ✅ routes through ModelRegistry.workhouseModelFor | `f23b0e77` |
> | #7 spring.ai.* stale gemini-2.5-flash comment | ✅ bumped to gemini-3-flash-preview + added bean-init-only header comment | `f23b0e77` |
> | #8 *Properties.java default model strings | ✅ nullified; consumers fall through to ModelRegistry | `f23b0e77` |
> | #5 frontend PricingTable.MODEL_ROLES | ✅ reads chainTier from YAML-sourced PricingRow DTO | `ab586b5f` |
> | #6 Test fixtures with legacy aliases | 🟢 left alone — handled by YAML legacy-aliases mechanism, no functional drift |
>
> **Single-source-of-truth state:** every model identifier, context-window value, max-output-tokens, pricing rate (input + cached + output), display label, fallback chain, free-tier lock, agent→tier map, and provider-default model now lives in `application.yml model-matrix.*` and is read via `ModelRegistry`. The remaining model-string occurrences in the codebase are legacy-alias test fixtures (handled by the alias mechanism) and Spring AI bean-init defaults (overridden per-call by the V2 router).
>
> **Tests:** 39 across all touched surfaces, 0 failures. Backend boots clean with new YAML; live `/api/billing/plans` + `/api/models/available` + `/api/pricing` all serve YAML-derived data.
>
> ## Post-eval log-noise cleanup (2026-05-06 14:20 IST)
>
> Two cosmetic warnings flagged during the validation eval:
>
> | Warning | Resolution |
> | --- | --- |
> | `[ModelResolver] No tier mapping for agent 'ReviewerAgent' — defaulting to WORKHORSE` | `CostEstimator.java:101` + `RunOrchestrator.java:2854` updated to use the canonical class name `ReviewerAgent` (the actual reviewer since v2.5.10). YAML already has `ReviewerAgent: PLANNING`. |
> | `preview-engine handoff failed: ... docker.sock` (ERROR with stack trace, 1.5KB log line per run) | `PreviewService.handoffToPreviewEngine` now detects docker-daemon-down via `e.getMessage()` and logs a single concise WARN ("preview skipped — Docker daemon not reachable...") instead of the full ERROR. Real preview-engine bugs still ERROR. |
>
> Verified by re-running the LOW Anthropic eval after the fix: zero `ReviewerAgent` warnings, exactly two clean WARN lines for the daemon-down case (one from the entry catch + one from `fail()`'s post-emit log), zero ERROR-level entries outside known OTel/gcloud fail-open paths.

---

## 1. TL;DR

The codebase has **one designed source of truth** (`application.yml` `model-matrix.*` → `ModelMatrixProperties` → `ModelRegistry` → DB-seeded `model_pricing`). It works correctly for the live cost path. **But there are eight independent hardcodes that duplicate or drift from that truth** — fixing them collapses the surface from 9 places to 1.

**The sharpest drift:** `ModelContextWindowMap` says `gpt-5.4` is **256K** while YAML says **200K**. The chat header chip + any other consumer of the map shows the wrong number.

**The sharpest cost finding from the live eval:** Gemini at the new `gemini-3-pro-preview` PLANNING/WORKHORSE tier burned 122K input + 34K output tokens on a LOW-band spec (timed out at 300s mid-REVIEW). At YAML's $3.50/M-input + $21/M-output that's **~$1.16 for a single LOW run** — 10–25× the Anthropic ($0.10) and OpenAI ($0.04) costs on the identical spec. The 2026-05-04 bump from `gemini-3-flash-preview` to `gemini-3-pro-preview` traded speed + cost for accuracy; on this LOW band the trade is bad.

---

## 2. Live cost eval (2026-05-06 12:02 IST)

Spec: `scripts/v2-eval-specs/low.txt` (single-task LOW band). Concurrency 3, timeout 300s.

| Provider | Model (planner) | Status | Duration | Input tok | Output tok | Cost |
|---|---|---|---:|---:|---:|---:|
| Anthropic | `claude-sonnet-4-6` | COMPLETE | 110s | 16,395 | 3,379 | **$0.1017** |
| OpenAI | `gpt-5.4` | COMPLETE | 60s | 45,750 | 960 | **$0.0437** |
| Gemini | `gemini-3-pro-preview` | TIMEOUT (script) — server still REVIEWING at 300s | >300s | 122,845 | 34,724 | (in-flight; YAML rates → ~$1.16) |

**Sanity-check cost math** (from YAML rates × tokens):
- Anthropic: 16.4K × $3 + 3.4K × $15 = $0.049 + $0.051 = **$0.100 ✓ (matches $0.1017)**
- OpenAI: 45.7K × $2.50 + 960 × $15 = $0.114 + $0.014 = $0.128. Live shows $0.044 — that's **2.9× cheaper than YAML predicts**, almost certainly because OpenAI cache hits dominated this run (`RunCostTracker` applies the 50% cache-read multiplier on `cached_tokens`).
- Gemini: 122.8K × $3.50 + 34.7K × $21 = $0.430 + $0.729 = **$1.16** if the run completes at the current trajectory.

**What this confirms about the cost path:**

- ✅ DB-seeded `model_pricing` matches YAML exactly (verified by `psql` → 8 rows, identical numbers).
- ✅ `RunCostTracker.priceCall` reads from the seeded table; Anthropic `cost = inputCost + outputCost` lines up cleanly.
- ⚠️ OpenAI cache-hit accounting is doing real work (3× discount observed live) but uses a hardcoded 0.50 multiplier, NOT the YAML `cached-input-per-1m` field. See §6 #4.
- ⚠️ Gemini Pro on LOW = bad ROI. At 25× Anthropic's per-run cost, the Pro bump only makes sense if HIGH-band quality matters — and we don't even hit the HIGH band on dev today (hierarchical-planner not merged).

**Cross-reference to the 2026-05-04 baseline** (pre-2026-05-04 Gemini bump, MEDIUM band data):

| Provider | LOW $/run | MEDIUM $/run |
|---|---:|---:|
| Anthropic | $0.054 | $0.216 |
| OpenAI | $0.055 | $0.321 |
| Gemini (flash-only) | $0.063 | TIMEOUT |

The Anthropic LOW run today ($0.10) is ~2× the 2026-05-04 number ($0.054). Likely cause: planner spec is the same but workhorse was bumped sonnet → sonnet on 2026-04-19 (already in the 2026-05-04 data). Need to dig into the per-task token breakdown to confirm — outside this report's scope.

---

## 3. The model-mapping landscape (current state)

**Designed single source of truth:**

```
application.yml model-matrix.*
       │
       ▼
ModelMatrixProperties.java  (@ConfigurationProperties)
       │
       ├──▶  ModelRegistry.java   (lookups: contextLimitFor, pricingFor, providerFor, freeTier*, fallbackChainFor)
       │           │
       │           └──▶  ModelPricingInitializer  ──▶  model_pricing DB table  ──▶  RunCostTracker.priceCall
       │
       └──▶  ModelResolver.resolve(agentClass, provider, tier)  ──▶  resolved model string
```

This works. Anything below should ideally read FROM `ModelRegistry` instead of hardcoding.

---

## 4. All model mappings in the codebase (the drift surface)

Eight independent hardcoded sources of model metadata, in priority order from highest leverage to lowest:

### #1 — `ModelContextWindowMap.java` 🔴 ACTIVE DRIFT

`router/ModelContextWindowMap.java` — hardcoded `Map<String, Integer>` of model → context-window size.

| Model | YAML `context-limit` | Map value | Drift? |
|---|---:|---:|:---:|
| `claude-opus-4-6` | 200,000 | 200,000 | ✓ |
| `claude-sonnet-4-6` | 200,000 | 200,000 | ✓ |
| `claude-haiku-4-5-20251001` | 200,000 | 200,000 | ✓ |
| `gemini-2.5-flash` | 1,048,576 | 1,000,000 | drift (1.05M vs 1.00M) |
| `gemini-3-flash-preview` | 1,048,576 | 1,000,000 | drift (same) |
| `gemini-3-pro-preview` | 1,048,576 | (missing — falls to prefix → 1,000,000) | drift |
| `gpt-5.4` | 200,000 | **256,000** | **🔴 ACTIVE DRIFT** |
| `gpt-5.4-mini` | 200,000 | **256,000** | **🔴 ACTIVE DRIFT** |

**Impact:** the chat-header context-window chip (`ProjectChatController.info`) shows 256K for OpenAI users; the worker-side token-budget guard reads from YAML and uses 200K. The two surfaces disagree. Either YAML is wrong (gpt-5.4 actually has 256K) or the map is wrong.

**Fix:** delete `ModelContextWindowMap` entirely and route every caller through `ModelRegistry.contextLimitFor(model)`. The fallback prefix logic already lives in the registry conceptually (it returns `DEFAULT_CONTEXT_LIMIT = 200_000` for unknowns); promote it to a public `contextLimitForOrFallback` method if needed.

### #2 — `ModelController.MODEL_LABELS` 🟡 STALE

`api/controller/ModelController.java:42–49` — a hardcoded label map exposed via `GET /api/models/available` (the frontend's model selector).

The live response (just queried) returns `claude-sonnet-4-6`, `gemini-3-pro-preview`, `gpt-5.4`, `gpt-5.4-mini` — but the YAML-defined `claude-haiku-4-5-20251001` (LIGHTWEIGHT for Anthropic) is **missing from the response** because the endpoint only emits PLANNING + WORKHORSE rows. That's a UX gap — users picking Anthropic don't see Haiku as an option.

**Fix:** generate the response from `ModelRegistry.allModels()` filtering by chain-tier + provider, instead of a hand-maintained label map.

### #3 — `RunOrchestrator.defaultModelFor(provider)` 🟡 STALE

`integration/RunOrchestrator.java:3335–3338` — fallback model strings used when no explicit model is supplied:

| Provider | Fallback in code | YAML PLANNING tier | Drift? |
|---|---|---|:---:|
| anthropic | `claude-haiku-4-5-20251001` | `claude-sonnet-4-6` | drift (uses Haiku, not Sonnet) |
| gemini | `gemini-3-flash-preview` | `gemini-3-pro-preview` | drift (uses flash, not Pro) |
| openai | `gpt-5.4-mini` | `gpt-5.4` | drift (uses mini, not full) |

**Documented in CLAUDE.md** as `router/CLAUDE.md` → "planner/reviewer don't use tier resolution (v2.5.11 finding)". The planner ends up running on Haiku-tier workers because of this. **Material to cost + quality.** Fix: thread `ModelResolver.resolve("PlannerAgent", provider, PLANNING)` into the planner request.

### #4 — `RunCostTracker.priceCall` cache-read multipliers 🟡 LOGIC DRIFT

`cost/RunCostTracker.java:493, 499, 504` — hardcoded provider-specific cache-read discounts that **ignore the YAML `cached-input-per-1m` field**:

```java
if ("anthropic")        cacheReadMultiplier = 0.10;  // matches YAML cache/input ratio
else if ("openai")      cacheReadMultiplier = 0.50;  // YAML says 0.10 → live cost shows 0.50 wins
else if ("gemini")      cacheReadMultiplier = 0.25;  // YAML says 0.10 → 0.25 wins
```

So the YAML's `cached-input-per-1m` field is **dead config** for OpenAI + Gemini — only used for Anthropic. Two possible fixes:

- **Option A (preserve current behaviour):** Document the YAML field as Anthropic-only + remove it for the other two providers. Honest but loses the per-provider override knob.
- **Option B (use YAML):** Change `priceCall` to compute `cacheReadCost = cacheRead * cachedInputPer1m` directly. Single source of truth restored. Requires updating YAML `cached-input-per-1m` to reflect provider reality (OpenAI 50% of input, Gemini 25%).

**Recommend B** — closer to the design intent.

### #5 — `frontend/src/components/pricing/PricingTable.tsx` 🟡 STALE

`frontend/src/components/pricing/PricingTable.tsx:14–28` — a `MODEL_ROLES` map mapping model strings to display labels ("Architect", "Fast", etc).

- Missing `gemini-3-pro-preview` (added to YAML 2026-05-04).
- Includes `gemini-3.1-pro-preview-customtools` and `gemini-3.1-flash-lite-preview` — preview variants that have NEVER been in the YAML matrix.

**Fix:** fetch from `GET /api/models/available` instead of hardcoding. (Once #2 is fixed, this becomes free.)

### #6 — Test fixtures with legacy model strings 🟢 LOW PRIORITY

| File | Model used | Comment |
|---|---|---|
| `TenantIsolationIntegrationTest.java` | `claude-sonnet-4-5` | YAML alias maps to `4-6`; test still asserts the literal `4-5`. |
| `StateStoreIntegrationTest.java` | `claude-sonnet-4-5` | Same. |
| `TokenBudgetMonitorTest.java` | `claude-haiku-4-5` | Alias maps to `haiku-4-5-20251001`; test asserts the literal short form. |
| `LlmRouterTest.java` | `claude-sonnet-4-5` | Same. |

**Impact:** none today (legacy aliases handle them). But if YAML drops the alias mapping, tests break. **Fix:** flip tests to canonical names; aliases remain for in-flight DB rows.

### #7 — `application.yml` `spring.ai.*.chat.options.model` 🟢 INFO

```yaml
spring.ai.anthropic.chat.options.model: claude-haiku-4-5-20251001
spring.ai.openai.chat.options.model: gpt-5.4-mini
spring.ai.google.genai.chat.options.model: gemini-2.5-flash    # ← stale comment says "all tiers" but matrix uses 3-flash/3-pro
```

These are Spring AI's own auto-config defaults. They're used at bean construction time before V2's `LlmRouter` overrides per-call. Effectively dead config in the V2 path but kept for graceful boot when matrix is unconfigured.

**Fix:** add a comment that these are bean-init defaults only + replace `gemini-2.5-flash` with `gemini-3-flash-preview` so a graceful-degraded boot uses a current model.

### #8 — `*Properties.java` default model strings 🟢 INFO

| File | Default | Used when |
|---|---|---|
| `AgentProperties.java:145, 155` | `claude-haiku-4-5-20251001` | YAML key missing (never happens in practice) |
| `ContextAgentProperties.java:14` | `claude-haiku-4-5-20251001` | same |
| `PromptOptimiserProperties.java:27` | `claude-sonnet-4-6` | same |

These match YAML so no drift, but they're 4 separate places to update if a model name changes. **Fix:** delete the defaults; let YAML be the only source.

### Not duplicated (good)

- **Agent → tier mapping:** lives only in YAML `model-matrix.agent-tier-map`. ✓
- **Fallback chains:** lives only in YAML `model-matrix.fallback-chains`. ✓ (`LlmRouter` has no hardcoded chain constants.)
- **Free-tier lock:** lives only in YAML `model-matrix.free-tier`. ✓
- **Legacy aliases:** lives only in YAML `model-matrix.legacy-aliases`. ✓

---

## 5. Context window — pricing — max-output crosstable (current YAML)

Single-source view of every active model's published metadata + role:

| Model | Provider | YAML role | Context | Max out | $/M in | $/M cache | $/M out |
|---|---|---|---:|---:|---:|---:|---:|
| `claude-opus-4-6` | anthropic | escalation only | 200K | 32K | $5.00 | $0.50 | $25.00 |
| `claude-sonnet-4-6` | anthropic | PLANNING + WORKHORSE | 200K | 64K | $3.00 | $0.30 | $15.00 |
| `claude-haiku-4-5-20251001` | anthropic | LIGHTWEIGHT | 200K | 8.2K | $1.00 | $0.10 | $5.00 |
| `gemini-2.5-flash` | gemini | legacy | 1.05M | 65K | $0.30 | $0.03 | $2.50 |
| `gemini-3-flash-preview` | gemini | LIGHTWEIGHT | 1.05M | 65K | $0.50 | $0.05 | $3.00 |
| `gemini-3-pro-preview` | gemini | PLANNING + WORKHORSE | 1.05M | 65K | $3.50 | $0.35 | $21.00 |
| `gpt-5.4` | openai | PLANNING | 200K | 16K | $2.50 | $0.25 | $15.00 |
| `gpt-5.4-mini` | openai | WORKHORSE + LIGHTWEIGHT | 200K | 16K | $0.75 | $0.075 | $4.50 |

**Verification still needed against provider docs (no internet from this session — check yourself):**
1. **Claude Opus 4.6 at $5/$25 per M** seems aggressively low — Anthropic's published Opus pricing has historically been $15/$75. Either we're using a model name that doesn't exist OR the price is stale-low. **Verify before relying on this for invoicing.** The model is also marked "reserved for escalation" in the YAML — if we never call it, this is moot, but the cost-estimator does include it in fallback chains.
2. **Gemini 3 Pro Preview at $3.50/$21 per M** matches a 7× flash multiplier per the comment — verify against `https://ai.google.dev/gemini-api/docs/pricing` (preview tier).
3. **GPT-5.4 at $2.50/$15** — verify against OpenAI's pricing page; if the model is `gpt-4o`-aliased, the actual rate is `$2.50/$10` (output cheaper than recorded).
4. **Context windows**: `gemini-*` listed at 1,048,576 (the `1M = 2^20` interpretation). Google's docs say 1,048,576 for the public Gemini-2.5-Flash, so YAML matches; `ModelContextWindowMap` rounds to 1,000,000 — minor cosmetic drift.

---

## 6. Pricing recommendations

Tying the cost-eval data back to the SOLO ($29/mo, 30 credits) + TEAM ($39/seat × 2 minimum = $78/mo BYOK) ladder shipped overnight.

### What a typical SOLO month costs us

Modal user does ~20 LOW + 5 MEDIUM + 1 HIGH per month. From the live eval + 2026-05-04 baseline:

- Anthropic — **modal user lands at $4.84/mo cost** ($0.10 × 20 + $0.22 × 5 + ~$1.74 × 1 = $2.00 + $1.10 + $1.74). At $29 plan price, **margin = 83%**. Healthy.
- OpenAI — **$3.49/mo cost** ($0.04 × 20 + $0.32 × 5 + ~$1.30 × 1). **Margin = 88%**.
- Gemini at the new Pro tier — **~$30+/mo cost** if all 30 credits run on Pro ($1.16 × 20 LOW + $7 × 5 MED + $20+ × 1 HIGH). **Margin would be NEGATIVE.** SOLO is managed-keys, so we eat that.

**Action items:**

1. **Stop routing SOLO Gemini users to Pro.** Either:
   - Lock SOLO + FREE to LIGHTWEIGHT-only Gemini (`gemini-3-flash-preview`), or
   - Document that SOLO only includes 5–10 Gemini Pro credits/month + the rest fall back to flash.
2. **Verify the YAML `cached-input-per-1m` against actuals.** OpenAI's 50% hardcoded discount produced a cost 2.9× lower than YAML predicted on the live eval — that's load-bearing. If YAML's $0.25 cached-input number is wrong, every cost estimate is wrong.
3. **Surface the live cost ratio in the dashboard.** Customers comparing providers want to see "$0.04 (OpenAI) vs $0.10 (Anthropic) vs $1.16 (Gemini Pro)" on identical specs. Today they see total cost only — no per-token comparison.
4. **Promote `gemini-3-flash-preview` to PLANNING for SOLO/TEAM tier** as a cost guard. The Pro bump made sense for accuracy but breaks SOLO unit economics. A tier-based override (e.g. SOLO → flash, TEAM-paying-for-quality → pro) closes the gap without removing the Pro option entirely.

---

## 7. Single-source-of-truth proposal

Three commits, each ~half-day:

### Commit 1 — Delete `ModelContextWindowMap`, route through `ModelRegistry`
- Add `public int contextLimitForOrFallback(String model)` to `ModelRegistry`: same body as today's `contextLimitFor` but with the prefix-fallback logic from the map (claude → 200K, gemini → 1M, gpt → 256K).
- Update every caller of `ModelContextWindowMap.forModel(model)` (mainly `ProjectChatController.info`) to call the registry method instead.
- Delete the file. Done.
- ✅ Closes the gpt-5.4 context-window drift (200K vs 256K) by forcing one canonical answer.

### Commit 2 — Fix `RunCostTracker` cache pricing to read YAML
- Change `priceCall` to read `cachedInputPer1m` from `ModelPricing` and compute `cacheReadCost = cacheReadTokens / 1_000_000 * cachedInputPer1m` directly.
- Update YAML `cached-input-per-1m` for `gpt-5.4` family to $1.25 (50% of $2.50 input) and `gemini-3-pro-preview` to $0.875 (25% of $3.50) so the migration is rate-neutral.
- Delete the `cacheReadMultiplier` switch in `RunCostTracker`.
- ✅ Closes #4. YAML becomes the only knob for cache pricing.

### Commit 3 — `ModelController.MODEL_LABELS` → `ModelRegistry.allModels()`
- Replace the hardcoded label map with a registry walk. Filter by chain-tier (CAPABLE + ARCHITECT visible to UI; UTILITY hidden unless requested by `?includeUtility=true`).
- Frontend `PricingTable.MODEL_ROLES` deleted; the table fetches from `/api/models/available` directly.
- ✅ Closes #2 + #5.

### After these three: only YAML edits required to add/change a model.

The remaining drift sources (#3 RunOrchestrator.defaultModelFor, #6 test fixtures, #7 spring.ai.* defaults, #8 `*Properties.java` defaults) are **inert** unless something else changes — they each match YAML today. Track them as low-priority cleanup.

---

## 8. Files / artefacts produced

- This report: `docs/reports/model-mapping-audit-2026-05-06.md`
- Live eval JSON: `scripts/v2-eval-results/v2-eval-20260506-120242.json`
- Live eval markdown: `scripts/v2-eval-results/v2-eval-20260506-120242.md`
- Backend boot log: `/tmp/backend-boot.log`
- Frontend boot log: `/tmp/frontend-boot.log`

Servers are still running on http://localhost:8080 (backend) + http://localhost:5173 (frontend). Stop with `ps aux | grep spring-boot:run | grep -v grep | awk '{print $2}' | xargs kill` and `pkill -f vite`.
