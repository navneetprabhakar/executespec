# 09 — Model matrix: why it feels inconsistent, and the plan to enforce consistency

> Everything below is code-verified (file:line), not doc-derived. Raw evidence:
> `checkpoints/be-router-cost.md` (exhaustive consumer map), `checkpoints/be-core-agents.md`,
> `checkpoints/be-worker-engine.md`, application.yml read in full.

## 1. Diagnosis — the inconsistency is structural, not cosmetic

Your instinct is correct. The root cause: **there is no single definition of "a model" in the
system.** A model id is a string that ~10 subsystems each interpret with their own private rules.
Adding or changing a model requires touching an unbounded, undocumented set of places, and nothing
fails when you miss one — things silently misbehave instead.

### 1.1 Where model truth lives today (10 sources)

| # | Source | What it claims to own | Drift found |
|---|--------|----------------------|-------------|
| 1 | `model-matrix.models` (application.yml:107-318) | id, provider, pricing, context, labels, chain-tier | mimo needs bracket-quoted key; notes carry changelog prose |
| 2 | `model-matrix.providers.<p>.<tier>` (yml:85-105) | tier→model per provider | openrouter tier cells = `claude-sonnet-4-6` (an aggregator "provider" defaulting to another vendor's model — leaks into the customer F3 grid) |
| 3 | `spring.ai.*.chat.options.model` (yml:56-73) | provider default models | haiku / gpt-5.4-mini / gemini-3-flash-preview — a SECOND opinion about defaults that bypasses the matrix |
| 4 | `OpenRouterModelMapper` (hand-list, 13 ids) | id→OpenRouter slug | contains a non-canonical alias (`claude-haiku-4-5`); header comment stale |
| 5 | `FireworksModelMapper` (uncommitted, 5 ids) | id→Fireworks slug | ZERO overlap with the YAML registry; slugs reference different model generations (deepseek-v3 vs matrix v4, kimi-k2 vs k2.6); arbitrary pass-through for `accounts/fireworks/...` strings |
| 6 | `fallback-chains` + `chain-tier` (yml:328-340) | failover, in ARCHITECT/CAPABLE/UTILITY vocabulary | consumed in prod ONLY by the legacy router; V2 consumption is behind flag V226 default OFF |
| 7 | `failover-order` (yml:373-382) | provider-level failover | covers only anthropic/gemini/openai; disagrees with fallback-chains on gemini-vs-openai order; a third hardcoded copy sits in dead `ProviderStatusService:45-49` |
| 8 | `agent.recovery.capable-models` + `agent.roles` (yml:388-450) | recovery + role defaults | names models/providers independently of the matrix |
| 9 | Code constants | various | `RunCostTracker.ownerProviderFor:720` ("every model is claude-*/gpt-*/gemini-*" — stale, causes the $0 bug); `UsageReportService:284` hardcodes stale Haiku rates ($0.80/$4.00 vs matrix $1.00/$5.00); `GeminiNativeToolClient` default-model constant; six agent names missing from agent-tier-map resolving via WARN+WORKHORSE default |
| 10 | Eval/bench scripts + ~/tbench configs | model pins for benchmarks | TB bypasses ModelResolver entirely (TerminalBenchRunService takes provider+model raw) — the only path that can run an unregistered model |

### 1.2 The symptoms this structure produces (all live today)

- **$0 actual cost for every OpenRouter-only model** (deepseek-v4-pro/flash, kimi-k2.6,
  qwen3-coder, mimo) — `priceCall` rewrites the pricing key via `ownerProviderFor` to "openai",
  but rows are seeded under "openrouter" (RunCostTracker.java:669-727 vs ModelPricingInitializer:72).
  Estimates price correctly → you charge credits on a non-zero estimate and record $0 actuals.
- **Cached input double-billed** for claude-via-OpenRouter (Anthropic token-shape rule applied to
  OpenAI wire shape — RunCostTracker:690-696).
- **A new provider can be "added" while remaining unroutable**: FIREWORKS has enum + dispatch but
  no providers block → `ModelResolver.lookupModel:261` throws BEFORE the override lookup, so even a
  per-run pin can't reach it. No pricing rows → $0. No `RunKeyContext.keyFor` case → "ENTERPRISE
  BYOK" is structurally false for deepseek AND fireworks (always platform key).
- **Boot never fails on a broken matrix** — `ModelRegistry.validate()` only `log.error`s (:37-90).
  The YAML dotted-key trap (kimi-k2.6, mimo-v2.5-pro silently binding as nested paths until
  bracket-quoted) produced a real $0-pricing incident; nothing would catch the next one.
- **Tier routing is currently a no-op for model choice**: every provider's three tier cells point
  at the same model (yml:86-105), so complexity routing changes caps but never models — and six
  services resolve agent names that aren't in agent-tier-map, landing on WORKHORSE via a WARN that
  fires on every chat message (ModelResolver:60-65,170-180).
- **Free-tier model lock enforced only on the legacy router path** (ModelRegistry.freeTierModel
  callers = FallbackLlmRouter only); the V2 path never consults it.
- **Customer UI leaks the internals**: F3 grid renders "Openrouter" as a peer provider defaulting
  to claude-sonnet-4-6, raw ids like `deepseek-v4-flash-native` instead of display labels, and
  internal tier names (Planning/Workhorse/Lightweight) as customer-facing labels (live-verified).
- **Dead/placebo subsystems** confuse every reader: AvailableProviderResolver (zero callers — the
  uncommitted Fireworks diff edits dead code), ProviderStatusService (polls 3 vendor status pages
  every 60s, writes Redis keys nobody reads), ProviderRecommendationService, ModelTaskMetric,
  adaptive routing fed only by legacy-router data.

## 2. Target model — one manifest, one resolver, hard validation

### 2.1 Concepts (vocabulary to standardize on)

- **Model** — the product-facing unit (`qwen3-coder`, `claude-sonnet-4-6`). One entry, one
  display label, one pricing source per route.
- **Vendor** — who makes the weights (Anthropic, OpenAI, Google, DeepSeek, Moonshot, Alibaba,
  Xiaomi, Zhipu). Display metadata, BYOK grouping.
- **Route (transport)** — how a call reaches the model: `anthropic-api`, `openai-api`,
  `gemini-api`, `deepseek-api`, `openrouter`, `fireworks`. Pure infrastructure. NEVER UI-visible.
  (This is the aggregator reframe — full design in doc 10.)
- **Agent tier** — PLANNING / WORKHORSE / LIGHTWEIGHT. Keep; it's the selection axis agents use.
- **Class** — FLAGSHIP / BALANCED / ECONOMY (replaces chain-tier's ARCHITECT/CAPABLE/UTILITY).
  One vocabulary for "caliber", used for fallback grouping AND customer-facing grouping.

### 2.2 The single manifest (one YAML block owns everything)

```yaml
model-matrix:
  models:
    qwen3-coder:
      vendor: alibaba
      class: BALANCED
      display-label: "Qwen3 Coder"
      context-limit: 262144
      max-output-tokens: 32000
      status: active            # active | legacy-alias-target | pin-only | retired
      routes:                   # ordered by preference (policy can re-rank)
        - via: fireworks
          slug: accounts/fireworks/models/qwen3-coder-480b-a35b-instruct
          pricing: { input-per-1m: 0.90, cached-input-per-1m: 0.09, output-per-1m: 3.60 }
        - via: openrouter
          slug: qwen/qwen3-coder
          pricing: { input-per-1m: 0.70, cached-input-per-1m: 0.07, output-per-1m: 2.80 }
```

Derived FROM the manifest at boot (no second source allowed to survive):
- the OpenRouter/Fireworks slug maps (delete both hand-written mapper lists; mappers become
  `registry.routeFor(model, via).slug()`),
- `model_pricing` rows keyed **(model, route)** — not (provider, model),
- the provider/route availability list (key present? route enabled?),
- the per-class fallback chains (class members ordered by policy),
- the UI picker payloads (display label, vendor, class — never route).

Explicitly deleted as sources of truth: `spring.ai.*.chat.options.model` defaults (router always
supplies a model), `chain-tier` + `fallback-chains` + `failover-order` (replaced by class-derived
chains + route failover), `agent.recovery.capable-models` (reference tier cells instead),
hardcoded rates in `UsageReportService:284`, `ownerProviderFor` prefix matching (the route IS the
pricing key), both mapper hand-lists, dead `ProviderStatusService.FAILOVER_ORDER`.

### 2.3 Hard validation — make wrongness fail loudly at boot + CI

1. **Boot fails** (not log.error) when: a tier cell / alias target / free-tier / pin references an
   unknown model; an active model has a route with no pricing; two models share a display label;
   a route's `via` has no registered transport client; YAML key looks dotted-unquoted (lint the
   raw keys against `models.keySet()`).
2. **`ModelRegistryCompletenessTest`** (CI, runs on every PR) asserts per active model:
   resolvable end-to-end for each declared route (LlmRouter dry-run with a stub transport),
   pricing row seeds for each route, mapper/slug coverage, a `RunKeyContext` lane exists for the
   route's BYOK story (or the model is explicitly marked platform-key-only), display label
   present, appears in `/api/models/available` payload.
3. **Cost honesty canary**: a metric + alert when any successful LLM call prices to $0.00 for an
   `active` model (today that's a DEBUG log — RunCostTracker:673-677). $0 actuals is how three of
   your current bugs stayed invisible.
4. **Reflection canary** for the Anthropic cache-token extraction (RunCostTracker:736-760) — one
   test against the real Spring AI Usage type so an upgrade can't silently zero cache accounting.

### 2.4 "Adding a model" becomes one PR with one shape

Checklist enforced by the completeness test (no tribal knowledge):
1. Add the manifest block (model + routes + pricing).
2. If the route's transport is new: add the transport client + `RunKeyContext` lane + env var.
3. Done. Mappers, pricing rows, picker, fallback membership, availability all derive.

### 2.5 Fix-first list (independent of the redesign — these are live bugs)

1. `RunCostTracker.priceCall`: look up `(route, model)` as seeded; kill `ownerProviderFor` rewrite.
   Fixes $0 actuals for all OpenRouter models AND the claude-via-OpenRouter double-billing (apply
   token-shape rule by WIRE SHAPE, not by pricing key).
2. Finish or park Fireworks: add `providers.fireworks` block + matrix entries + pricing + keyFor
   lanes for deepseek/fireworks (or drop the BYOK claim from the enum docs). Currently 2/6 shipped.
3. Make `ModelRegistry.validate()` fail boot; add the dotted-key lint.
4. Normalize `testCoverageLevel` once (case drift currently disables anti-gaming guards —
   ValidationReportService:1168 vs :1465).
5. Add the six missing agent-tier-map entries (ChatService, SpecChatService, ApplyService,
   ProjectChatAgent, PlannerAgent, FilePurposeSummaryService) — kills the per-message WARN.
6. Delete dead: AvailableProviderResolver, ProviderStatusService, ProviderRecommendationService,
   ModelTaskMetric(+repo), RunRubricVerifier family (incl. its unsandboxed `/bin/sh -c`),
   resolveFallbackExcluding, notesFor, RunProviderPinStore (with FallbackLlmRouter sunset).
7. Rename `workhouseModelFor` → `workhorseModelFor` (6 call sites).
8. Tighten the lenient run-pin (RISK-1): unknown id → 400 at submit unless an explicit
   `allowUnregistered` escape hatch is set.

## 3. Sequencing (pragmatic, each step shippable)

| Phase | Scope | Outcome |
|-------|-------|---------|
| 0 (now) | Fix-first list items 1-5 | Honest costs, Fireworks routable, boot-safe matrix |
| 1 | Manifest schema + loader + completeness test, keeping old keys as derived shims | One source of truth, zero behavior change |
| 2 | Kill duplicate sources (mappers, chains, spring.ai defaults, recovery models) | Drift becomes impossible, not just unlikely |
| 3 | Route/aggregator reframe (doc 10) + UI model-first picker | OpenRouter/Fireworks invisible; model-first UX |
| 4 | Delete dead subsystems + FallbackLlmRouter sunset plan | One router, one registry, one vocabulary |
