# BYOK Deep-Dive — V2 Pipeline Routes Every Paying Customer Through Platform Keys

> **Audit branch:** `audit/v2-5-11-ux-product`
> **Author:** Claude (deep-dive follow-up to FINAL_REPORT.md)
> **Date:** 2026-05-01
> **Severity:** **P0 — contract violation for every TEAM/ENTERPRISE/MAX customer**
> **Status of fix:** Not yet implemented. This document specifies the fix.

---

## TL;DR

The V2 pipeline — which is the **only** code-generation path on dev/main — never threads per-user / per-org API keys through to the LLM provider call. Every V2 LLM request (planner, classifier, worker, reviewer, expander, coherence patcher) is dispatched against the platform's singleton beans wired from `application.yml`. The user-facing Settings → AI Provider Keys page accepts, encrypts, and persists keys; admission control (`ApiKeyResolver.validateKeyAvailability`) refuses BYOK runs without a key; but at dispatch time the resolved keys are silently discarded.

**Net effect:**
1. **TEAM** ($49/seat) and **MAX** ($29/mo, BYOK is the **whole** value prop) customers pay for "use your own keys" and get platform compute on platform billing.
2. **ENTERPRISE** customers whose contracts say "your data, your provider relationship" are silently violating that contract — provider-side request logs sit on ExecuteSpec's account, not theirs.
3. The V1 router still threads keys correctly (used by chat / docs / accuracy assessment / etc.). Only the **money path** — the V2 plan→worker→reviewer pipeline — is broken.

The wrappers (`OpenAiNativeToolClient`, `GeminiNativeToolClient`) **already accept** a per-request API key; `LlmRouterV2` just doesn't pass one. Anthropic is the only provider that needs a structural change (Spring AI's `AnthropicChatModel` is a singleton; we need a per-key cache). Estimated fix size: **L** (~3 days for code + tests + rollout audit).

---

## 1. Evidence — code trace

### 1.1 V2 router never resolves per-run keys

`v2/agent/LlmRouterV2.java:47-50` (header comment, **deliberately deferred**):

> *"Deferred to Phase 2.x / Phase 9: multi-provider failover, ProviderHealthTracker circuit breaker, **per-run API-key resolution**, tier-based model resolution. These are production-canary concerns; the Phase 2 baseline proves routing correctness end-to-end first."*

This was a pragmatic Phase-2 scope decision. Phase 2 shipped to dev. Phase 9 never happened. The deferred line is now production reality on every paid tier.

### 1.2 The router's three providers all bind to platform singletons

| Provider | File / line | Where the key comes from |
|----------|-------------|--------------------------|
| Anthropic | `LlmRouterV2.java:73-74`, `AgentModelConfig.java:101-119` | `@Qualifier("anthropicChatModel")` — built from `${spring.ai.anthropic.api-key}` at Spring-startup |
| OpenAI | `OpenAiNativeToolClient.java:68-69, 82-83` | `@Value("${spring.ai.openai.api-key:}")` as `defaultApiKey`; **`request.apiKey()` is honored if non-null** but `LlmRouterV2.callOpenAi()` (line 202-213) never sets it |
| Gemini | `GeminiNativeToolClient.java:88, 120-121` | `@Value("${spring.ai.google.genai.api-key:}")` as `defaultApiKey`; same wrapper-accepts-but-router-doesn't-thread story |

### 1.3 `LlmRequestV2` has no `apiKey` field at all

`v2/agent/LlmRequestV2.java`:

```java
public record LlmRequestV2(
    String provider, String model, List<Message> messages,
    ExecutionContext context, Double temperature, Integer maxTokens,
    List<ToolCallback> toolsOverride
) { ... }
```

There is no field through which any V2 agent (`PlannerAgentV2`, `WorkerAgentV2`, `OutlinePlannerAgent`, `TaskExpanderAgent`, `CoherencePlannerAgent`, `ReviewerAgentV3`, `SmartClassifierAgent`) could thread a resolved key — even if it tried.

### 1.4 V2 production code never references `ApiKeyResolver`

```bash
$ grep -rn "ApiKeyResolver\|ResolvedKeys" src/main/java/com/navneet/executespec/v2/
(empty)
```

The only V2 reference to user/org key fields is in `v2/test/NativeToolsTestController.java:64` (a `@Value("${spring.ai.openai.api-key:}")` wrapper for the boot-smoke endpoint), not the live pipeline.

### 1.5 Validation runs at the controller, dispatch ignores it

`api/controller/RunController.java:106` calls `apiKeyResolver.validateKeyAvailability(...)` on `POST /api/runs`. This **rejects** a BYOK user submitting without keys (good). But the resolved keys are not stored anywhere reachable from `V2RunOrchestrator`. The only consumer of the resolved keys is the gate; the dispatcher uses platform singletons.

### 1.6 V1 router does the right thing (proof the architecture knows how)

`agent/LlmRouter.java:50` (header):

> *"Keys are resolved via `ApiKeyResolver` (user → org → system hierarchy)"*

V1 still serves: chat copilot, apply suggestions, architecture / project / repo doc generation, accuracy assessment, run-title summarizer, eval framework, memory ledger. **All of those honor BYOK.** The V2 pipeline — the actual code-generation product — does not.

### 1.7 No tests would have caught this

`TierPermissionServiceSoloUserTest.java` (the BYOK-related tests added in Sprint 1 PR-3) all assert the **permission flag** `canManageAiKeys`, not whether a dispatched run actually reaches the customer's provider. The structural hole is invisible to the unit suite.

---

## 2. Reproduction proof

This is structural, not behavioral. To **observe** the violation:

1. Sign in as a TEAM-tier user (or any user with `tier in {TEAM, MAX, ENTERPRISE}`).
2. Settings → AI Provider Keys → paste your own Anthropic key (e.g. `sk-ant-...PERSONAL`).
3. Submit a run with provider=Anthropic.
4. Inspect Anthropic's API console for **your** account.
   * Expected (BYOK promise): you see usage entries matching the run's token counts.
   * Actual: zero traffic — your key was untouched. The traffic appears in **ExecuteSpec's** Anthropic account.

A unit-test reproduction lives in §7.1.

---

## 3. Impact matrix

| Tier | What customer paid for | What customer got (today) | Severity |
|------|------------------------|---------------------------|----------|
| FREE | Platform keys, 5/mo | Platform keys ✓ | None |
| PRO (HIDDEN) | Platform keys, 20/mo | Platform keys ✓ | None |
| TEAM | $49/seat — 50 credits/seat shared pool, BYOK option | Platform keys (silently); their key stored encrypted but unused | **P0** — customer believes they're paying for BYOK access |
| MAX | $29/mo — **BYOK is the entire value prop** ("unlimited *because you bring your own key*") | Platform keys; customer's encrypted key sits idle | **P0** — fundamental product contract violation |
| ENTERPRISE | Custom — "your data flows through your provider account" | Platform keys; their data flows through ExecuteSpec's provider account | **P0** — likely contractual/compliance violation; provider-side logs and data-handling are with the wrong tenant |

### Secondary impacts

- **Billing reconciliation** — `run_cost_log` records platform LLM cost. For BYOK customers this should be $0 to ExecuteSpec, not the dollar value of platform-billed tokens. Today every BYOK run's "platform cost" is an actual cost ExecuteSpec pays.
- **Provider quotas** — TEAM customers' workloads consume ExecuteSpec's per-org rate limits with the LLM provider. A noisy customer can starve every other tenant.
- **DPA / sub-processor disclosure** — ENT contracts that name OpenAI / Anthropic / Google as the customer's sub-processors are wrong. They're ExecuteSpec's sub-processors today.
- **Region routing** — customers using Anthropic Bedrock or Vertex with regional residency requirements have no way to enforce that. Platform keys go to wherever ExecuteSpec's account lives.
- **Audit / compliance** — there is no `key_source` on `pipeline_run` so ENT customers can't prove which keys were used in any historical run.

---

## 4. Fix architecture — three options

### Option A — Per-request key threading (recommended)

Add `apiKey` to `LlmRequestV2`. Every V2 agent constructs `LlmRequestV2` through a shared helper (`V2RunKeyContext.requestBuilder()`) that pre-populates the resolved key for the run's selected provider. Router passes through to wrappers; wraps Anthropic in a per-key model cache.

- **Pros:** minimal scope; per-request override gives full flexibility (e.g. fallback to platform key on a per-call basis if we ever want to); no global state mutation.
- **Cons:** every V2 agent's `LlmRequestV2.builder()` site needs to learn how to fill `apiKey`. ~10 call sites.

### Option B — Run-scoped Spring-bean factory

Build `AnthropicChatModel` / `OpenAiChatModel` / Gemini Client beans **per run** at run-start, register them in a `RunBeanRegistry` keyed by runId, dispose at run-end.

- **Pros:** zero changes to agents; they keep using injected singletons.
- **Cons:** Spring beans aren't designed for this; complex lifecycle bug surface (eviction, leak detection, request-scope vs prototype-scope mismatch). High risk for an L-sized fix.

### Option C — Sidecar resolver in `LlmRouterV2`

Inject `ApiKeyResolver` into the router; resolve from `request.context().runId()` → look up `pipeline_run.created_by_user_id` → `apiKeyResolver.resolve(userId)`.

- **Pros:** zero changes to agents.
- **Cons:** every LLM call hits the DB for keys (or hits a Redis cache we'd have to build); `runId` is technically optional in some boot paths; tight coupling between router and persistence layer; harder to test.

**Recommendation: Option A.** It mirrors how the wrappers already work, makes the data flow explicit, and the call-site fan-out is small enough that a one-shot edit + ArchUnit pin closes the gap.

---

## 5. Recommended fix — Option A in detail

### 5.1 Schema change

`LlmRequestV2`:

```java
@Builder
public record LlmRequestV2(
    String provider,
    String model,
    List<Message> messages,
    ExecutionContext context,
    Double temperature,
    Integer maxTokens,
    List<ToolCallback> toolsOverride,
    String apiKey,                    // NEW — null means "use platform default"
    KeySource keySource               // NEW — USER | ORG | PLATFORM (for audit only)
) {
    public enum KeySource { USER, ORG, PLATFORM }
    // ...
}
```

### 5.2 Run-scoped key context

New helper `V2RunKeyContext` injected into every V2 agent (or attached to `ExecutionContext` at run-start):

```java
public record V2RunKeyContext(
    String anthropicKey, String openAiKey, String geminiKey,
    KeySource anthropicSource, KeySource openAiSource, KeySource geminiSource,
    String userId, String orgId
) {
    public String keyFor(String provider) {
        return switch (provider.toLowerCase()) {
            case "anthropic" -> anthropicKey;
            case "openai"    -> openAiKey;
            case "gemini"    -> geminiKey;
            default -> null;
        };
    }
    public KeySource sourceFor(String provider) { /* mirror */ }
}
```

Built once at `V2RunOrchestrator.runPipeline` start from `apiKeyResolver.resolve(run.getCreatedByUserId())`, attached to every `LlmRequestV2` constructed during that run.

### 5.3 `LlmRouterV2` changes

```java
private ChatResponse callAnthropic(LlmRequestV2 r, List<ToolCallback> tools) {
    AnthropicChatModel model = (r.apiKey() != null && !r.apiKey().isBlank())
        ? perKeyAnthropicModel(r.apiKey())
        : anthropicModel; // platform singleton
    // ... rest unchanged
}

private ChatResponse callOpenAi(LlmRequestV2 r, List<ToolCallback> tools) {
    return openAiNativeToolClient.call(OpenAiNativeToolClient.OpenAiToolCallRequest.builder()
        .model(r.model())
        .messages(r.messages())
        .tools(tools)
        .temperature(r.temperature())
        .maxCompletionTokens(r.maxTokens())
        .apiKey(r.apiKey())              // NEW
        .build());
}

// ... same one-line addition for Gemini
```

### 5.4 Per-key Anthropic model cache

```java
// SHA-256(apiKey) → AnthropicChatModel ; Caffeine LRU 128 entries × 1h TTL
private final Cache<String, AnthropicChatModel> anthropicByKey = Caffeine.newBuilder()
    .maximumSize(128)
    .expireAfterAccess(Duration.ofHours(1))
    .build();

private AnthropicChatModel perKeyAnthropicModel(String apiKey) {
    String hash = sha256Hex(apiKey);
    return anthropicByKey.get(hash, h -> {
        AnthropicApi api = AnthropicApi.builder().apiKey(apiKey).build();
        return AnthropicChatModel.builder()
            .anthropicApi(api)
            .defaultOptions(AnthropicChatOptions.builder()
                .model("claude-haiku-4-5-20251001")  // overridden per-call
                .maxTokens(16000).temperature(0.0).build())
            .build();
    });
}
```

128 × 1h covers any realistic mix; eviction returns to bean-construction (~50ms one-time).

### 5.5 Hard-fail OWN_KEYS mode at dispatch

`V2RunOrchestrator` should refuse the run before it starts if:
- `keySourceMode = OWN_KEYS` AND
- the resolved key for the run's selected provider is null

This is the OWN_KEYS contract: no platform fallback. Today the validator catches this at `validateKeyAvailability` only when **all** providers are missing; we need to gate per-provider.

### 5.6 Audit + observability

- Add `pipeline_run.key_source VARCHAR(16)` (USER | ORG | PLATFORM), set by `V2RunOrchestrator` at run-start.
- Emit `RUN_KEY_SOURCE_RESOLVED` SSE event with `{provider, keySource}` so the frontend can show "Using your key" vs "Using platform key".
- Log line on every `LlmRouterV2.call()`: `keySource={USER|ORG|PLATFORM}` (alongside the existing latency log).
- New metric: `executespec.v2.llm.key_source` counter, tags `{tier, provider, source}`. Dashboards can flag any TEAM/MAX/ENT run where `source=PLATFORM`.

### 5.7 UX surface (post-backend fix)

- **Run Detail header chip:** "Keys: yours" (green) | "Keys: platform" (neutral) | "Keys: mixed" (amber, if a fallback fired mid-run).
- **Settings → AI Provider Keys:** add an inline "✓ Used in your last run" indicator next to each key, so customers can verify the wiring works.
- **Billing dashboard:** "Runs using your keys: N (no platform LLM charge)" on TEAM/MAX/ENT views.
- **First BYOK run:** one-shot toast "Your Anthropic key was used for this run — no platform tokens consumed."

---

## 6. Migration plan

| Phase | Days | What | Rollout gate |
|-------|------|------|--------------|
| 0 — instrumentation | 0.5 | Add `key_source` column + log + metric in **shadow mode** (read but don't enforce). Deploy to dev, verify metric counts `source=PLATFORM` ≈ 100% for all V2 runs. Confirms the diagnosis live. | Manual review |
| 1 — code change | 1.0 | Add `apiKey` to `LlmRequestV2`; thread through every V2 agent's `LlmRequestV2.builder()` site; per-key Anthropic cache; wrappers untouched. Behind feature flag `byok.v2-key-threading.enabled` (default OFF). | Unit tests + ArchUnit pin |
| 2 — local + dev validation | 0.5 | Flag-on for super-admin only. Run real BYOK Anthropic+OpenAI+Gemini specs from a TEAM-tier test account. Verify customer's provider console shows the requests; platform console shows none. | Manual eval — 3 runs × 3 providers |
| 3 — staged rollout | 1.0 | Flag-on for ENT first (where contract risk is highest), then MAX, then TEAM, then everyone (HYBRID still defaults to platform). | Per-tier slow-roll; revert flag if `key_source=PLATFORM` count rises on a tier where it shouldn't |
| 4 — OWN_KEYS hard-fail | 0.5 | Add per-provider gate (refuse OWN_KEYS run when resolved provider key is null). Behind flag `byok.own-keys-hard-fail.enabled`. | Manual review of error copy with support |
| 5 — audit visibility | 1.0 | UI chips on Run Detail; Settings "✓ used in last run"; Billing dashboard "your keys vs platform" split. | Frontend review |

Total: **~4.5 days** to fix + roll out.

### Risk

- **Caching surface** — a buggy per-key cache that leaks keys across runs would be a P0 security incident. The cache key is SHA-256(plaintext key); the value is a `ChatModel` whose internal HTTP client holds the key in memory. If the cache returned the wrong entry, a Customer-A run could hit Customer-B's provider account. Mitigation: Caffeine's `get(key, loader)` is atomic; the cache key is the full hash, not a prefix; ArchUnit pin that no other code path touches `anthropicByKey` directly.
- **Anthropic prompt cache** — the `CONVERSATION_HISTORY` cache in Spring AI is held in the API account, so per-key models also have per-key prompt caches. **Good** — it means Customer A's cached prompts don't leak to Customer B. **Bad** — frequent cache misses for low-volume BYOK customers. Acceptable trade-off; document in the BYOK FAQ.
- **HYBRID fallback** — if a BYOK call 401s (key revoked / rotated), today we'd just fail. Recommend: log + emit `RUN_KEY_FAILED_FALLBACK` event + retry once with platform key + flag the run for admin review. Tracked as a follow-up to the main fix.

---

## 7. Tests required

### 7.1 Reproduction unit test (must FAIL today, PASS after fix)

```java
// v2/agent/LlmRouterV2BYOKTest.java
@Test
void anthropicCall_usesPerRequestKey_notPlatformDefault() {
    String userKey = "sk-ant-customer-key";
    LlmRequestV2 req = LlmRequestV2.builder()
        .provider("anthropic")
        .model("claude-haiku-4-5-20251001")
        .messages(List.of(new UserMessage("hello")))
        .context(testContext())
        .apiKey(userKey)               // NEW field
        .build();
    
    ChatResponse resp = router.call(req);
    
    // The mock AnthropicApi captures what API key was used in the auth header.
    assertThat(capturedAuthHeader.get()).isEqualTo("Bearer " + userKey);
    assertThat(capturedAuthHeader.get()).doesNotContain(PLATFORM_KEY);
}
```

### 7.2 Per-tier integration test (matrix)

For each of `{FREE, PRO, TEAM, MAX, ENT}` × `{anthropic, openai, gemini}`:
- Set tier on user, set/skip BYOK keys, dispatch a run, assert the resolved `key_source` matches expectation.
- 5 × 3 × 3 = 45 cases. Parameterized.

### 7.3 ArchUnit pin

```java
@Test
void llmRouterV2_isOnlyPlaceThatTouchesAnthropicChatModel() {
    classes().that().haveSimpleName("AnthropicChatModel")
        .should().onlyBeAccessed().byAnyPackage(
            "com.navneet.executespec.v2.agent",  // LlmRouterV2 + per-key cache
            "com.navneet.executespec.config"      // AgentModelConfig (singleton bean)
        );
}
```

Prevents future agents from grabbing `AnthropicChatModel` directly.

### 7.4 Cache-isolation unit test

Build 2 keys, dispatch 2 calls, assert each got its own `AnthropicChatModel` instance and the auth header on the wire matched the request's key both times.

### 7.5 OWN_KEYS hard-fail test

OWN_KEYS org with no Gemini key + run dispatched against Gemini → expect 4xx (or run terminates with `KEY_SOURCE_UNAVAILABLE` reason), not platform fallback.

---

## 8. Out of scope for this fix (separate work)

- **Per-key billing reconciliation** — once dispatch is fixed, `run_cost_log.platform_cost` should be 0 for `key_source=USER|ORG`. Updating the cost-tracker math is a separate slice.
- **Anthropic Bedrock / Vertex routing** — customers who want BYOK + regional residency need a `key_endpoint` field too. Future work.
- **Key rotation flow** — today rotating a key in Settings doesn't terminate in-flight runs. Acceptable; rotated keys take effect on next run.
- **Multi-key per provider** — some ENT customers want to route LOW vs HIGH tasks through different keys (cost optimization). Deferred.
- **Usage-quota mirror** — surfacing the customer's provider-side rate-limit state in our UI. Nice-to-have, not blocking.

---

## 9. Appendix — classifier deep-dive findings

### Q-NEW-04 — "MEDIUM Express ~1,200 chars classified as SMALL via heuristic"

**Status: needs a live probe to confirm root cause; structural read is mostly done.**

`SmartClassifierAgent.runHeuristic` (lines 161-205) cascades:

| Length range | Verdict | Source flag |
|--------------|---------|-------------|
| 0 chars (blank) | SINGLE_SHOT (fast path) | `HEURISTIC_FAST_PATH` |
| ≤ 150 chars + no expansive keywords | SINGLE_SHOT (fast path) | `HEURISTIC_FAST_PATH` |
| ≤ 800 chars | **SMALL** | `HEURISTIC` (NOT fast-path; LLM verifier still runs, then `LLM_VERIFIED` or `LLM_FALLBACK_TO_HEURISTIC`) |
| ≤ 2,000 chars | **MEDIUM** | same as above |
| > 2,000 chars | LARGE | same |

If a "MEDIUM Express ~1,200 chars" spec ended up classified as SMALL with `source=HEURISTIC`, **only one path explains it:**

- LLM verifier ran, **failed** (any of: model resolve, prompt assembly, LLM 401, JSON parse, empty body, network timeout)
- `fallback()` returns `heuristic.strategy` (which was MEDIUM at 1,200 chars)
- BUT the persisted record showed SMALL

That contradiction means **either the spec was actually < 800 chars** (audit miscount), **or there's a separate path that downgrades MEDIUM to SMALL.** I read the entire `SmartClassifierAgent` and found no such downgrade path; cache hits would carry the original verdict, parse failures fall back to heuristic verdict (which would be MEDIUM, not SMALL). I cannot reproduce the SMALL outcome from any control-flow path on a >800-char spec.

**Recommended next step:** dispatch a ~1,200 char Express spec against the local stack with `logging.level.com.navneet.executespec.v2.agent.SmartClassifierAgent=DEBUG` and inspect the log line at line 142 (`SmartClassifier heuristic-fast-path`) and 281 (`SmartClassifier LLM`). Captures the exact heuristic verdict + LLM verdict. ~5 min once the stack is up. **I have not run this probe yet** — needs Postgres + Redis + backend up.

### Q-NEW-07 — Verifier behavior + B-N5 reason discriminator

The Sprint 2 [B-N5] fix added the failure-mode reason to the persisted `classifierReasoning`, so future fallbacks read:

```
LLM verifier skipped (LLM call failed: 401 Unauthorized) — heuristic verdict: ≤800 chars, single feature scope (LLM may downgrade or upgrade)
```

instead of the old generic "LLM verifier unavailable". Five catch sites pass distinct reasons:

| Catch site | Reason |
|------------|--------|
| `modelResolver.resolve` failure | `"model resolve failed: <message>"` |
| `promptAssembler.assemble` failure | `"prompt assembly failed: <message>"` |
| `router.call` failure | `"LLM call failed: <truncated message>"` |
| Empty body | `"LLM returned empty body"` |
| JSON parse failure | `"LLM JSON parse failed"` |

**Today's gap:** there is **no metric** on which catch site fires. If we're seeing systematic verifier failures across customers, we'd only notice via pattern-grep on `pipeline_run.classifier_reasoning`. Recommend adding a micrometer counter `executespec.v2.classifier.fallback{reason=<bucket>}` so dashboards can spot a regression instantly (e.g. "verifier 401 rate just spiked — did Anthropic rotate something?").

This is a **S** fix (1 hour) and unrelated to the BYOK P0 above. Bundle with the BYOK observability work (§5.6).

---

## 10. Recommended action

1. **This week** — run the §6 Phase 0 instrumentation. Confirm-or-falsify the diagnosis on dev with real metrics. Costs near zero; gives us live evidence to refute or confirm the structural read.
2. **Next sprint** — implement Phase 1 (code threading, behind flag).
3. **Phase 2 — surface migration window** — turn flag on for ENT first; expect zero noise (the math should just start working). If anything breaks, revert is one DB flip.
4. **Comms** — when this ships, send a one-line heads-up to existing TEAM/MAX/ENT customers: *"Your stored AI provider keys are now actually used for every run. You may see new entries in your provider's billing console — that's expected, and your platform charges from us will reflect this."*

I am **not** recommending we do this without the §6 Phase 0 instrumentation first. The structural read is strong but a live metric closes the loop and protects against any code path I missed in the trace.

---

*End of deep-dive.*
