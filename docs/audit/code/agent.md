# Audit: `agent/` — LLM agents + routing

Status: **DONE** · Updated 2026-05-29 · 42 files / 8,586 lines · all read in full

## Files read in full
- `LlmRouter.java` (486) ✓
- **Batch A (2026-05-29):** `OpenAiNativeToolClient.java` (348) ✓ · `GeminiNativeToolClient.java` (526) ✓ · `PatchAgent.java` (340) ✓ · `PromptAssembler.java` (139) ✓
- **Batch B (2026-05-29):** `SmartClassifierAgent.java` (611) ✓ · `OutlinePlannerAgent.java` (533) ✓ · `TaskExpanderAgent.java` (332) ✓ · `PlannerOutputUtils.java` (419) ✓
- **Batch C1 (2026-05-29):** `FallbackLlmRouter.java` (1052) ✓ · `CoherencePlannerAgent.java` (446) ✓ · `CostEstimator.java` (385) ✓
- **Batch C2a (2026-05-29):** `ApiKeyResolver.java` (180) ✓ · `RunKeyStore.java` (131) ✓ · `RunKeyContextStore.java` (121) ✓ · `RunKeyContext.java` (84) ✓ · `ResolvedKeys.java` (59) ✓ · `RunTitleSummarizer.java` (93) ✓

- **Batch C2b (2026-05-29) — package complete:** `ConversationAgent.java` (852) ✓ · `TokenBudgetMonitor.java` (234) ✓ · `TokenBudgetPolicy.java` (40) ✓ · `SpecClassifier.java` (136) ✓ · `PlanStrategyCircuitBreaker.java` (142) ✓ · `PromptResourceVerifier.java` (79) ✓ · `PlannerRequest`/`Plan`/`PlannedTask`/`PlanStrategy`/`Classification`/`ClassificationResult`/`LlmRequest`/`PromptAudit`/`PromptHashAuditRepository`/`ApplySuggestionTooLargeException` (records/DTOs) ✓

## What it does (LlmRouter)
Canonical V2 native-tool dispatcher. `call(LlmRequest)` → per-run BYOK key resolve → provider switch (anthropic via Spring AI, openai/openrouter/deepseek via `OpenAiNativeToolClient`, gemini via `GeminiNativeToolClient`) → records cost via `RunCostTracker` → emits token-usage SSE.

## Findings

### Accuracy / Efficiency / Cost
- 🔵 **Anthropic prompt caching is real**: `AnthropicCacheOptions` CONVERSATION_HISTORY with per-message-type min lengths (SYSTEM 256 / USER 512 / ASSISTANT 512 / TOOL 512). Cache reads 10%, writes 125%. OpenAI auto-caches ≥1024 tokens; Gemini implicit. Per-customer-key Anthropic model cache (SHA-256(key), Caffeine 128×1h) keeps BYOK caching on the customer's account.
- 🔵 **Bounded same-provider transient retry** (4 attempts, 2/4/8s backoff) on 5xx/reset/timeout/429; permanent 4xx fail fast. Keeps per-provider benchmark numbers honest (no silent failover).

### Correctness / bugs / reliability
- 🔴/🔵 **No failover, no circuit breaker, no tier resolution in `LlmRouter`** — explicitly flagged in the class javadoc as **audit M#68**. A single post-retry 5xx hard-fails the V2 pipeline call; the legacy `FallbackLlmRouter` has all three. This is the top reliability gap (an approved, credit-charged run can die on a transient upstream blip after the 4 retries).
- 🟡 Temperature-deprecation handled via a JVM-wide mutable `ConcurrentHashMap.newKeySet()` of model names — works, but mutable static state; first call per model eats one failed request to learn.
- 🟡 `callDeepSeek` strips an internal `-native` suffix; `deepseek-v4-pro-native` is registered but NOT tier-active (api.deepseek.com needs `reasoning_content` echo on thinking-mode follow-ups, not yet round-tripped).

### Tech-debt
- 🟡 `FallbackLlmRouter` (deprecated, RL-2026-05-041) still serves ~14 non-tool agents — divergent retry/failover fork (audit risk #10) pending migration.

## Batch A findings — native clients + patch + prompt assembler (2026-05-29)

### Security / privacy
- 🔴 **`GeminiNativeToolClient` logs the full request + response body at INFO on EVERY call** ([lines 150–159](../../../src/main/java/com/navneet/executespec/agent/GeminiNativeToolClient.java) — `"Gemini wrapper REQUEST body…/RESPONSE body…"`, truncated to 2000 chars, comment says "Temporary. DEBUG"). This writes customer spec + generated code into application logs on every Gemini iteration — log-volume blowup AND a PII/customer-IP leak into log storage. Should be DEBUG-gated or deleted.

### Correctness / reliability
- 🔴 **Gemini thought-signature cache TTL (30 min) < HIGH-task wall-clock cap (75 min).** `thoughtSignatureById` is `expireAfterWrite(30 MINUTES)` ([line 122]); Gemini 2.5+ thinking models reject follow-up calls whose `functionCall` parts lack the original `thoughtSignature` (400). A long Gemini HIGH/project task (cap 75 min, ×2=150 min on project runs) can outlive the signature → mid-task 400 "missing thought_signature" → degraded/failed task. TTL should be ≥ the max task wall-clock.
- 🟡 The per-call `readTimeoutSeconds` override is honoured ONLY by the Gemini client; `OpenAiNativeToolClient.READ_TIMEOUT` is a static 120s (no override path). Documented as intentional in WorkerAgent, but means OpenAI worker calls can't get extended headroom.

### Tech-debt
- 🟡 **`PatchAgent` carries ~130 lines of dead deprecated prompt** (`buildSystemPromptInlineDeprecated`, [lines 208–339]) — the OLD oracle "READ THE GOLD TESTS FIRST" prompt that directly contradicts the live blind `patch-agent.md`. `@Deprecated(forRemoval=true)` but still in the file; should be deleted (it's misleading dead code).
- 🟡 `PatchAgent` javadoc says "Forced PLANNING-tier model" but the model is actually a `run(provider, model, …)` parameter — the caller (benchmark/autofix) picks it; the "forced" framing is stale.

### Accuracy / Efficiency / Cost (good)
- 🟢 Both native clients **surface cache-read tokens uniformly** (`cache_read_input_tokens`), set even at 0 so "auto-cache fired 0×" (prefix mutating) is distinguishable from "no metadata". OpenAI reads `prompt_tokens_details.cached_tokens`; Gemini reads `cachedContentTokenCount` + `thoughtsTokenCount` (thinking billed at output rate). This is the instrumentation the cost/caching levers need.
- 🟢 Gemini client: schema sanitization (strips `additionalProperties`), `forceToolCall=ANY` for planners (prevents the "emit Python as a function call" degradation), 429-aware exponential backoff (15/30/60s honouring Retry-After).
- 🟢 `PatchAgent`: stagnation disabled in patch mode (investigation IS the work), per-provider iter caps, test-verification gate correctly scoped OFF for blind benchmark / ON for `finding:*` autofix.
- 🟢 `PromptAssembler`: clean `{{include:fragment[:role]}}` + `{{VAR}}` substitution + SHA-256 prompt-hash audit row.

## Batch B findings — planning subsystem (2026-05-29)

### Accuracy (planning quality — drives the whole pipeline shape)
- 🔵 **Classification is the load-bearing decision** (`SmartClassifierAgent`): it picks SINGLE_SHOT (LLM-bypass) / SMALL / MEDIUM / LARGE / LEGACY, which selects FULL_PLAN vs SKELETON+expansion and the whole downstream flow. The heuristic is **crude length thresholds** (≤150 fast-path, ≤800 SMALL, ≤2000 MEDIUM, >2000 LARGE) + a 4-endpoint override + domain-explosive/multi-feature keyword lists. The LLM verifier (LIGHTWEIGHT) refines it, BUT…
- 🟡 **The LLM verifier is best-effort and degrades SILENTLY to the heuristic** on any error (model-resolve / prompt-assemble / empty body / JSON parse / call failure all → `fallback()`). The run never fails — it just gets a dumber classification. Two historical silent-skip bugs (double `prompts/` prefix, then stale `v2/` path) meant the verifier never ran for many runs and nobody noticed until `classifierReasoning` was audited. The only signal is the `recordClassifierVerifier("SKIPPED_*")` metric — worth an alert if SKIPPED rate is non-trivial.
- 🟡 **`maxTasks` silently truncates** (`PlannerOutputUtils.parsePlanFromArgs`): if the planner emits more tasks than the classification's cap (MEDIUM=4), the excess tasks are dropped with only a WARN — real planned work can vanish. A planner that over-emits on a MEDIUM-classified-but-actually-bigger spec loses tail tasks.
- 🟡 **Validation-v3 requirement coverage is OFF by default** (`FLAG_V3_STRICT`): `validatePlan` (every requirement cited by ≥1 task; test-task on testable surface) only runs + retries when the flag is on; default = log-only. So there's **no production guarantee** the plan covers every enumerated requirement. When on, it does one retry then **graceful-degrades** (accepts the still-invalid plan + flags the reviewer) rather than failing.
- 🟡 **`TaskExpanderAgent` sees only sibling TITLES + the requirements list** (`renderSkeletons` prints `id [complexity/type] title`, not descriptions), and has **no retry** (throws → orchestrator marks expansion FAILED). So LARGE/LEGACY task bodies are filled with limited cross-task context → the "skeleton mode loses context" risk is real.

### Reliability (good)
- 🟢 `OutlinePlannerAgent` wraps the planner LLM call in an 8-min `CompletableFuture` wall-timeout (a stalled provider socket used to wedge the run in PLANNING forever) + a Gemini pseudo-code salvage path + 240s Gemini read-timeout.
- 🟢 `PlannerOutputUtils` has strong defensive salvage: recovers a tasks array from Anthropic's `<parameter name="tasks">` XML-leak-into-`smokeTestStrategy` bug, and parses Gemini's `submitPlan(...)` pseudo-code (True/False/None → JSON). These are real model-off-protocol failure modes handled gracefully.
- 🟢 Classifier Redis-cached (1h, keyed by SHA-256(spec+repos+projectId)); heuristic fast path is $0/0ms.

### Cost
- 🔵 SINGLE_SHOT bypasses the LLM planner entirely (`synthesizeSingleShot` — only a LIGHTWEIGHT title call). SMALL/MEDIUM = 1 outline call (FULL_PLAN, 32k cap). LARGE/LEGACY = skeleton (8k) + N parallel WORKHORSE expander calls (3k each) — the expander fan-out is the main planning-cost driver on big specs.

## Batch C1 findings — legacy router + coherence + cost estimator (2026-05-29)

### Reliability — sharpens the failover gap (M#68)
- 🔵 **`FallbackLlmRouter` is the ONLY thing feeding `ProviderHealthTracker` + `ModelPerformanceTracker`** (calls `recordSuccess`/`recordFailure`/`recordCall` on every attempt). This confirms [router.md](router.md): the V2 `LlmRouter` path leaves both trackers unfed, so the circuit breaker + adaptive routing are inert for production runs.
- 🔵 **It deliberately refuses mid-task provider switches** — `FallbackMode.TASK_BOUNDARY` + `MidTaskProviderFailureException`; failover is allowed only at `iteration==0` or `justCompacted`. **This is the key constraint for fixing M#68**: a V2 worker mid-tool-loop CANNOT safely switch providers (the message history carries provider-shaped state — Gemini `thoughtSignature`s, OpenAI `tool_call_id`s). So the right `LlmRouter` fix is **task-boundary failover + feeding the health tracker**, NOT a mid-loop switch. The existing `SequentialExecutor` cross-provider retry (flag OFF) is already the task-boundary-shaped lever.
- 🟢 Robust transient handling: 429/529/5xx with 4-step exponential backoff (15/30/60/120s), org-policy provider skip, advisory run-provider pin, FREE-tier model lock, OpenAI via the native client (extra_body bypass). But note: a fully-degraded chain can block a call several minutes before failing (legacy path only).

### Tech-debt — dual cost estimators (consistency risk)
- 🟡 **Two cost estimators with divergent token assumptions.** `CostEstimator` (this file, V1 `TaskPlan`-shaped) uses LOW 8k / MEDIUM 18k / HIGH 35k input + iter ranges 3-10/8-25/15-50; `RunCostTracker.estimateRunTierAware` (the V2 path, see [cost.md](cost.md)) uses LOW 30k / MEDIUM 200k / HIGH 600k (P75-recalibrated, ~10-20× higher). `CostEstimator`'s **estimate** half is stale/under-calibrated; its **`computeCostForRun`** (actual post-run, cache-aware, per-task-model) is sound and still used. Reconcile which estimator drives credits vs. feasibility vs. actuals — the stale estimate path is a real under-quote risk if any surface still calls it for the charge.
- 🟡 `CostEstimator.computeCostForRun` has a vestigial empty `stateStore.findRun(...).ifPresent(run -> { /* comment only */ })` no-op lambda (lines ~225-231) — dead code.

### Accuracy
- 🟡 `CoherencePlannerAgent.FIX_HARD_CONSTRAINT_CONFLICT` only **appends** the corrected constraint (doesn't remove the conflicting one) — relies on the worker prompt's "newest wins" wording. A genuine constraint conflict is layered, not resolved; the worker sees both. Self-flagged "heavy-handed; refine in slice 5".
- 🟢 `CoherencePlannerAgent` is otherwise clean + fully non-fatal (every failure path → unpatched plan flows through; worker/reviewer catch residue). 5 structural patch kinds, mutable-copy-then-rebuild.

## Batch C2a findings — BYOK key resolution (2026-05-29)

### Reliability / billing-integrity
- 🔴 **The V2 BYOK key store has NO cross-instance persistence.** `RunKeyContextStore` (consumed by `LlmRouter`, [agent.md](agent.md)) is **Caffeine-only** (1024 × 6h) — no Redis L2. The V1 `RunKeyStore` (consumed by `FallbackLlmRouter`) has a **Redis L2 (AES-256-GCM, 2h TTL)** precisely so a run resumed on another instance keeps its keys. So on a rolling deploy / instance switch mid-run, a V2 run's customer keys vanish → `LlmRouter` falls through to `keyContextStore.get(runId)==null` → **HYBRID silently dispatches on PLATFORM keys (customer's spend leaks onto the platform bill), and OWN_KEYS would get a null key**. Either give `RunKeyContextStore` the same encrypted Redis L2, or re-resolve keys on resume. (Also: two redundant BYOK stores resolving the same `ApiKeyResolver` — V1 + V2 — a consolidation target.)
- 🟡 **DeepSeek BYOK is not wired.** `RunKeyContext` has `openRouterKey` but **no `deepseekKey` field**, and `keyFor("deepseek")` falls to the `default → null` branch; `AvailableProviderResolver` also omits `DEEPSEEK`. So a DeepSeek run can only use the system `spring.ai.deepseek.api-key` — org-level DeepSeek BYOK silently doesn't apply. Consistent with DeepSeek's "new / awaiting smoke" status (RL-2026-05-052), but the BYOK plumbing needs extending before DeepSeek is enterprise-ready.

### Good
- 🟢 `ApiKeyResolver` 3-level hierarchy (user→org→system) with `keySourceMode` (SYSTEM/OWN_KEYS/HYBRID) + `enabledProviders` policy + pre-flight `validateKeyAvailability` (immediate 400 in OWN_KEYS with no keys) + `isOwnKeysMode` (drives the BYOK cost-cap exemption). Clean.
- 🟢 `RunKeyStore` (V1) encrypts keys at rest in Redis (AES-256-GCM via `KeyEncryptor`); both stores fall back to SYSTEM_ONLY on miss rather than failing.
- 🟡 Minor: `ApiKeyResolver` merges user+org keys without recording which level won, so per-source billing attribution is approximate (`RunKeyContextStore` marks all USER); `RunTitleSummarizer` javadoc references the deleted `PlannerAgent`.

## Batch C2b findings — conversation, token budget, classifier dup (2026-05-29)

### Accuracy / Efficiency (compaction lever is half-built)
- 🔵 **The context-compaction decision machinery EXISTS but is NOT wired into the V2 worker.** `TokenBudgetPolicy` returns CONTINUE / COMPACT_AND_CONTINUE / FORCE_REPORT; `TokenBudgetMonitor.check()` returns OK/WARN/COMPACT/HARD_STOP against the model context window (with HIGH-complexity +5% headroom). **But the `engine/WorkerAgent` loop never calls either** ([engine.md](engine.md) confirms it has only the $-cap + iter/wall-clock caps). So the "add worker compaction" action item is partly pre-built — the COMPACT signal + policy exist; what's missing is (a) the worker consulting them each iter and (b) the actual history-summarisation step. `TokenBudgetMonitor.resolveEffectiveComplexity` (downgrade HIGH→MEDIUM for static/frontend / small-model-max / ≤2 files) + `maxOutputTokens` ceilings (LOW 6144 / MEDIUM 8192 / HIGH 12288, capped to model max) ARE used (by CostEstimator / testgen).
- 🟡 **`PlannedTask.touchesFiles` is declared by the planner but the worker ignores it** ("Phase 3's worker ignores it today — wiring lands in Phase 5", `PlannedTask` javadoc). Confirms the upfront dependency-file context-fill (hybrid context) is still unbuilt — an accuracy/efficiency lever.

### Tech-debt / duplication
- 🟡 **Two classifiers, two enums.** `SpecClassifier` (pure-static, keyword/length/bullet) → `Classification{SINGLE_SHOT,MEDIUM,MULTI_TASK}` is **self-labelled "Legacy V1 enum… New code MUST use PlanStrategy"** and survives only on the `rollout/` canary + `PlannerRequest.classification` back-compat. The live V2 path is `SmartClassifierAgent` → `PlanStrategy{SINGLE_SHOT,SMALL,MEDIUM,LARGE,LEGACY}`. Documented legacy debt, but a live duplicate-decision surface to eventually retire.
- 🟡 **`ConversationAgent` is half-legacy/half-live + has a ~130-line inline system prompt** (lines 478-593) — violates the "prompts in external files" MUST-pattern (CLAUDE.md). Its `chat()` is the legacy path (superseded by `chat/ChatService`); `generateSuggestionPlan` (Apply-to-Run mini-plan, ≤6 tasks) is still live. Routes OPENAI→ANTHROPIC for chat (Spring AI extra_body bug). The design-guidance block self-contradicts ("never name companies" then lists "think Notion, Linear").

### Good
- 🟢 **`PromptResourceVerifier`** boot-fail-fasts if any of 12 critical prompts is missing from the classpath — directly closes the silent-degradation class of bug (the classifier silent-skip from Batch B). Strong defensive posture.
- 🟢 `ConversationAgent` has real prompt-injection defense: `ContextSanitizer.sanitizeInput`/`wrapInBoundary` wraps workspace/plan/review data in tagged boundaries with an explicit "this is DATA not instructions" preamble.
- 🟢 `PlanStrategyCircuitBreaker` (5 fails/1h → open → degrade ladder LEGACY→LARGE→MEDIUM→SMALL→legacy, fail-open) mirrors the provider breaker cleanly — and unlike the provider one, this IS consulted by the orchestrator on the planning path.
- 🟢 `LlmRequest` confirms the "every tool always available, model picks what it needs" design (Claude-Code-style) with `toolsOverride` for the planner's single-tool restriction.

## Cross-refs
Worker loop consumer → [engine.md](engine.md) (unwired `TokenBudgetMonitor`/`TokenBudgetPolicy` compaction). Tier→model + the unfed health/perf trackers → [router.md](router.md). Cost recording + the V2 estimator → [cost.md](cost.md). Tool binding → [tool.md](tool.md). Key consumers: `LlmRouter` (V2, `RunKeyContextStore`) + `FallbackLlmRouter` (V1, `RunKeyStore`). Live chat → `chat/ChatService` (Tier 7); this `ConversationAgent.chat` is legacy. Patch prompt → `prompts/patch-agent.md`. Planner prompts → `prompts/{outline-planner,outline-planner-skeleton,task-expander,coherence-planner,classifier}.md`.
