# 10 — OpenRouter & Fireworks as invisible aggregators (model-first routing design)

> Goal you stated: "I select the models, and the system routes to model via OpenRouter
> automatically; OpenRouter should not come anywhere in UI. Fireworks: same."
> This doc is the concrete design. It builds on doc 09's manifest; evidence cites are code-verified.

## 1. Why the current shape fights you

`LlmProvider` treats OPENROUTER / DEEPSEEK / FIREWORKS as peers of ANTHROPIC / OPENAI / GEMINI.
But they are different kinds of things:

- Anthropic/OpenAI/Gemini = **vendor + transport fused** (one API, their own models).
- OpenRouter/Fireworks = **transports only** (many vendors' models behind one API).
- DeepSeek-native = a vendor transport for a vendor that ALSO rides aggregators.

Because the system's only axis is "provider", every aggregator-reached model needs a fake
"provider" identity, which produces today's absurdities (all live, code-verified):

- The customer F3 grid shows an "Openrouter" row whose default model is `claude-sonnet-4-6`
  (application.yml:98-101 leaking through OrgController → IdeOrg grid; live-verified in UI).
- The same weights exist twice in the registry as different "models" (`deepseek-v4-flash` vs
  `deepseek-v4-flash-native`) just to encode WHICH PIPE they travel — pricing, eval results, and
  org overrides fragment across the twins.
- Cost attribution keys on provider strings and breaks when the fake provider doesn't match the
  pricing seed key ($0-actuals bug, RunCostTracker:720).
- Run rows, run detail, and "Try anthropic / Try gemini" buttons surface transport ids as if they
  were meaningful to a customer.
- Failover can't express the RIGHT move — "same model, different pipe" (mimo on a faster backend)
  — only "different vendor's model", which silently changes quality AND price (LlmRouter
  FAILOVER_EXCLUDED_PROVIDERS excludes aggregators as targets but not as sources).

Your mimo latency incident is the canonical case: the model was fine; the ROUTE was slow. The
architecture must make route a first-class, swappable, invisible concept.

## 2. Target architecture

### 2.1 Three layers, strictly separated

```
CUSTOMER sees:   Model (display label, vendor logo, class, price band)
                   │  e.g. "Qwen3 Coder · Balanced · ~$1/run"
PLATFORM picks:  Route = (transport, slug, pricing, health, throughput)
                   │  e.g. fireworks: accounts/fireworks/models/qwen3-coder-480b…
WIRE speaks:     Transport client (AnthropicClient | OpenAiCompatClient | GeminiClient)
                   with per-transport base-url + key + wire-shape rules
```

- `Transport` enum (internal only): `ANTHROPIC_API, OPENAI_API, GEMINI_API, DEEPSEEK_API,
  OPENROUTER, FIREWORKS` (+TOGETHER/DEEPINFRA later — one enum value + one config block each,
  since all are OpenAI-compatible and reuse `OpenAiNativeToolClient` exactly like the uncommitted
  `callFireworks` already does).
- A model declares `routes:[{via, slug, pricing, …}]` in the doc-09 manifest. The twins collapse:
  ONE `deepseek-v4-flash` with two routes (deepseek-api, openrouter).
- `LlmRouter.dispatch(ResolvedRoute)` replaces the provider switch; wire-shape rules (cache-token
  subtraction, tool_choice support, reasoning echo) hang off the TRANSPORT, fixing the
  double-billing class of bug structurally.

### 2.2 Route selection policy (the "routes automatically" part)

Per call, the router resolves model → route by:
1. Filter to routes whose transport has a key (org BYOK lane or platform) and isn't circuit-open.
2. Rank by policy: default `quality-safe-throughput` — prefer the route with the highest measured
   tok/s (rolling per-route telemetry: TTFT + gen tok/s + error rate, which RunCostTracker already
   half-collects), tie-break on price.
   Org-level policy knob (ENTERPRISE): `cheapest | fastest | pinned-route`.
3. Failover ladder on transient failure: same model next route → (only then) class-level fallback
   model. This is the ladder that makes "mimo is slow on OpenRouter" a config fact, not an incident.
4. OpenRouter calls always send `provider: {sort: "throughput"}` (or explicit `order`) — the
   missing preference that caused price-routing to the slow Xiaomi backend.

### 2.3 What the customer-facing surfaces become

- **Compose**: replace the PROVIDER select with a **Model select** (grouped Flagship / Balanced /
  Economy, showing display label + vendor mark + est. cost band). Default = org default model.
  The current "Default (org / system matrix)" copy dies.
- **Run detail / list**: show `Worker: Qwen3 Coder` (display label). Transport appears nowhere;
  for operator debugging it lives in run metadata/logs (`route=fireworks/...` on RunEvent).
- **"Try anthropic / Try gemini" buttons** → "Try with a different model" picker (same classes).
- **F3 org grid**: rows become agent tiers × MODEL pick (any active model), not provider columns.
  Org BYOK keys page groups by VENDOR (Anthropic key, OpenAI key, …) plus optional aggregator
  keys (OpenRouter/Fireworks) under an "Advanced: bring your own gateway" section — the only
  place an aggregator name may legitimately appear, since a customer pasting their own OpenRouter
  key must know it. With platform keys, aggregators are invisible end-to-end.
- **Pricing page**: per-model per-1M rates derived from the cheapest active route.

### 2.4 BYOK semantics (fixes the false "ENTERPRISE BYOK" claims)

`RunKeyContext.keyFor(transport)` gets explicit lanes per transport (today: missing for
deepseek/fireworks → silently platform — RunKeyContext.java:47-56). Resolution: org key for the
transport if present; else platform key; if `keySourceMode=OWN_KEYS`, routes without an org key
are filtered OUT at step 2.2-1 instead of silently billing the platform.

## 3. Migration plan (no big bang; each phase shippable + reversible)

| Phase | Change | Back-compat |
|------|--------|-------------|
| A | Manifest gains `routes[]`; loader derives today's structures; `model_pricing` re-keyed (model, route) with a view keeping the old (provider, model) readers alive | zero behavior change |
| B | `LlmRouter` route-dispatch + per-transport wire-shape rules + route telemetry + OpenRouter throughput preference; `RunKeyContext` transport lanes | provider enum still accepted; old call sites adapt via shim `routeFor(provider, model)` |
| C | Twins collapse (`*-native` ids become routes; legacy ids kept in `legacy-aliases`); failover ladder route-first | aliases keep old ids resolving |
| D | UI flip: model-first compose, run surfaces show labels, F3 grid model-cells | `RunRequest.aiProvider` still accepted (maps to vendor's default model), deprecate later |
| E | Delete: fake aggregator "providers" from the tier map, OpenRouter/Fireworks rows from any UI, dead availability/status services, FallbackLlmRouter (separate sunset) | — |

Effort honestly: B is the only intrusive step (LlmRouter is 668 LOC and well-factored; the
dispatch switch + key plumbing + pricing keys are mechanical). A/C/D are config + UI work. The
dangerous part is cost accounting — gate B behind a shadow-mode flag that logs route-priced cost
next to legacy-priced cost for a week of runs before flipping.

## 4. Decisions you should make now (my recommendations)

1. **Customer unit = model, not class-only.** Classes group the picker; the picker still shows
   named models. Your buyers are developers; hiding model names entirely would hurt trust, and
   your verified-score story benefits from "scored 94 with Qwen3 Coder at 1/10th the cost".
2. **Default policy = fastest-healthy-route, price tie-break** — your latency study showed
   throughput, not price, is what kills agentic runs (30 tok/s ⇒ wall-clock death).
3. **Keep DeepSeek-native as a route, not a provider** — same model one entry, two pipes.
4. **mimo**: no fast route exists (not on Fireworks); keep it routes:[openrouter(+sort:throughput→
   DeepInfra), deepinfra-direct later]. Don't let one model force a new transport until TB data
   justifies it.
5. **Fireworks slugs must be verified against the live catalog before the manifest lands** (the
   current 5 reference older generations — deepseek-v3 vs your matrix's v4, kimi-k2 vs k2.6;
   verify with the API key when you add it, exactly as the handoff's §1 step 2 planned).
