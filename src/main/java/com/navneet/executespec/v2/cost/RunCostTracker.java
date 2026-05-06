package com.navneet.executespec.v2.cost;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 per-run cost accumulator. Called by {@link com.navneet.executespec.v2.agent.LlmRouterV2} after
 * every successful LLM dispatch, so the orchestrator and the {@code /cost} endpoint can surface
 * live spend without reading the V1 {@code agent_task} token columns.
 *
 * <p>Stores a single in-memory {@link Accumulator} per runId with input/output token totals and a
 * running USD cost computed against {@link ModelPricingRepository}. The tracker is deliberately
 * cache-aware only for providers that surface {@code cache_read_input_tokens} / {@code
 * cache_creation_input_tokens} in their response metadata (Anthropic); for others, those fields
 * stay zero and the pricing falls back to the flat input/output rates.
 *
 * <p>{@link #finalizeRun(String)} returns a snapshot and removes the entry, so a completed run's
 * data doesn't leak memory. The tracker is best-effort — lookups against a runId not seen before
 * return an empty snapshot rather than throwing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RunCostTracker {

  /**
   * Anthropic prompt-cache writes are billed at 1.25× the input rate (per
   * https://www.anthropic.com/news/prompt-caching). Other providers don't bill cache writes as a
   * separate line item, so this multiplier applies on Anthropic only and is a no-op for OpenAI /
   * Gemini (their `cacheWrite` is always 0).
   */
  private static final double CACHE_WRITE_MULTIPLIER = 1.25;

  private final ModelPricingRepository pricingRepo;
  private final ModelResolver modelResolver;

  // ── rough per-task input/output token estimates by complexity ────────────
  // Used by estimateRun() for pre-plan cost estimation. Calibrated against
  // observed V2 worker invocations (~3-8 iterations × ~1.5k input / ~500 output).
  // Bands recalibrated 2026-04-19 from real run data. Previous values
  // (6k/20k/50k input, 1.5k/4k/10k output) underestimated by ~3x on
  // code-dense tasks — Calorie Tracker MEDIUM task T2 burned 629k in /
  // 19k out, nowhere near the old 20k/4k band. New bands target the
  // observed P75 per complexity so the pre-plan estimate lands in a
  // credible ballpark instead of looking like a bug.
  private static final int[] TOKENS_LOW = {30_000, 4_000}; // was 6k/1.5k
  private static final int[] TOKENS_MEDIUM = {200_000, 15_000}; // was 20k/4k
  private static final int[] TOKENS_HIGH = {600_000, 40_000}; // was 50k/10k

  // Planner + reviewer fixed overhead (input / output tokens per run).
  private static final int OVERHEAD_INPUT_TOKENS =
      40_000; // bumped 12k→40k (2026-04-19) after RN run showed
  private static final int OVERHEAD_OUTPUT_TOKENS = 5_000; // pre-plan estimate was 42% under actual

  // High-end multiplier for the buffered estimate (retries, cache misses, escalations).
  private static final double HIGH_MULTIPLIER = 1.75;

  /** Credit multiplier — USD per credit. Matches V1's default (see AgentProperties.Cost). */
  private static final double USD_PER_CREDIT = 5.0;

  private final Map<String, Accumulator> byRun = new ConcurrentHashMap<>();

  /**
   * Per-task accumulator, keyed by {@code runId|taskId}. Populated when the router is given a
   * task-scoped {@code ExecutionContext}; consumed by the orchestrator to write real per-task token
   * columns to {@code agent_task} instead of the old equal-split approximation.
   */
  private final Map<String, Accumulator> byRunTask = new ConcurrentHashMap<>();

  /** Records a single ChatResponse's token usage + cost against {@code runId}. */
  public void record(String runId, String provider, String model, ChatResponse response) {
    record(runId, null, provider, model, response);
  }

  /**
   * Task-scoped variant — also accumulates into a per-task bucket keyed by {@code runId|taskId}.
   * {@code taskId} may be null for non-task calls (planner, reviewer); behaviour matches the legacy
   * two-arg variant.
   */
  public void record(
      String runId, String taskId, String provider, String model, ChatResponse response) {
    if (runId == null || response == null) return;

    Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
    int inTok = usage == null ? 0 : safe(usage.getPromptTokens());
    int outTok = usage == null ? 0 : safe(usage.getCompletionTokens());

    // 2026-05-04 — billing audit: Spring AI 1.1.4's AnthropicChatModel.getDefaultUsage
    // (decompiled) only sets inputTokens + outputTokens on DefaultUsage; cache fields
    // are NOT exposed via the generic Usage interface and NOT added to the metadata
    // map. The fix is to reach into DefaultUsage's `nativeUsage` field (set by Spring
    // AI's 4-arg constructor) which holds the original AnthropicApi$Usage record with
    // cacheCreationInputTokens() + cacheReadInputTokens() accessors.
    //
    // OpenAI + Gemini use our own native tool clients (OpenAiNativeToolClient,
    // GeminiNativeToolClient). Those now write their cache + thinking fields into the
    // ChatResponse metadata map under stable keys (see "Cache field convention" below);
    // we read them via readMetaInt.
    //
    // Cache field convention (used by all 3 providers):
    //   cache_read_input_tokens  → tokens read from cache (billed at provider's cache rate)
    //   cache_write_input_tokens → tokens written to cache (Anthropic 5-min: 125%; absent for
    // OpenAI/Gemini)
    //   thinking_tokens          → Gemini 2.5+ reasoning tokens (billed at output rate)
    int cacheWrite = readMetaInt(response, "cache_creation_input_tokens");
    int cacheRead = readMetaInt(response, "cache_read_input_tokens");
    if (cacheWrite == 0 && cacheRead == 0) {
      // Spring AI 1.1.4 path — try the typed Anthropic Usage via DefaultUsage.nativeUsage().
      Object nativeUsage = callUsageMethod0(usage, "getNativeUsage");
      if (nativeUsage != null) {
        cacheWrite = callUsageMethodInt(nativeUsage, "cacheCreationInputTokens");
        cacheRead = callUsageMethodInt(nativeUsage, "cacheReadInputTokens");
      }
    }
    // Final fallback — older Spring AI 1.0 versions where the typed Usage WAS exposed
    // directly without the nativeUsage indirection. Cheap; harmless if it returns 0.
    if ((cacheWrite == 0 && cacheRead == 0) && usage != null) {
      cacheWrite = callUsageMethodInt(usage, "cacheCreationInputTokens");
      cacheRead = callUsageMethodInt(usage, "cacheReadInputTokens");
    }
    // Gemini 2.5+ thinking tokens — Gemini bills these at the OUTPUT rate, so we add
    // them to outTok before pricing. Our native client surfaces them via this metadata
    // key; Anthropic + OpenAI don't have an equivalent, so the read returns 0.
    int thinkingTok = readMetaInt(response, "thinking_tokens");
    if (thinkingTok > 0) outTok += thinkingTok;

    double callCost = priceCall(provider, model, inTok, outTok, cacheWrite, cacheRead);

    Accumulator acc = byRun.computeIfAbsent(runId, k -> new Accumulator());
    acc.inputTokens.addAndGet(inTok);
    acc.outputTokens.addAndGet(outTok);
    acc.cacheWriteTokens.addAndGet(cacheWrite);
    acc.cacheReadTokens.addAndGet(cacheRead);
    acc.costMicros.addAndGet((long) Math.round(callCost * 1_000_000d));
    acc.calls.incrementAndGet();

    if (taskId != null && !taskId.isBlank()) {
      Accumulator taskAcc = byRunTask.computeIfAbsent(runId + "|" + taskId, k -> new Accumulator());
      taskAcc.inputTokens.addAndGet(inTok);
      taskAcc.outputTokens.addAndGet(outTok);
      taskAcc.cacheWriteTokens.addAndGet(cacheWrite);
      taskAcc.cacheReadTokens.addAndGet(cacheRead);
      taskAcc.costMicros.addAndGet((long) Math.round(callCost * 1_000_000d));
      taskAcc.calls.incrementAndGet();
    }

    // Per-call diagnostic so we can tell at-a-glance whether a provider is
    // under-reporting tokens. Look for zero in/out on OpenAI → usage parse issue;
    // big deltas between Anthropic and OpenAI on same spec → cache-write
    // accounting or model-intrinsic verbosity.
    if (log.isInfoEnabled()) {
      log.info(
          "RunCostTracker record run={} provider={} model={} in={} out={} "
              + "cacheWrite={} cacheRead={} call#{} totals[in={}, out={}, ${}]",
          runId,
          provider,
          model,
          inTok,
          outTok,
          cacheWrite,
          cacheRead,
          acc.calls.get(),
          acc.inputTokens.get(),
          acc.outputTokens.get(),
          String.format("%.6f", acc.costMicros.get() / 1_000_000.0));
    }
  }

  /** Current live cost snapshot, or zero-init if unknown. */
  public Snapshot snapshot(String runId) {
    Accumulator acc = byRun.get(runId);
    if (acc == null) return new Snapshot(0, 0, 0, 0, 0.0, 0);
    return acc.toSnapshot();
  }

  /** Per-task snapshot, or zero-init if the tracker never saw task-scoped calls. */
  public Snapshot snapshotTask(String runId, String taskId) {
    if (runId == null || taskId == null) return new Snapshot(0, 0, 0, 0, 0.0, 0);
    Accumulator acc = byRunTask.get(runId + "|" + taskId);
    return acc == null ? new Snapshot(0, 0, 0, 0, 0.0, 0) : acc.toSnapshot();
  }

  /** Returns the final snapshot and clears the per-run entry (plus any per-task entries). */
  public Snapshot finalizeRun(String runId) {
    Accumulator acc = byRun.remove(runId);
    if (runId != null) {
      String prefix = runId + "|";
      byRunTask.keySet().removeIf(k -> k.startsWith(prefix));
    }
    return acc == null ? new Snapshot(0, 0, 0, 0, 0.0, 0) : acc.toSnapshot();
  }

  /**
   * Rough pre-run estimate from a list of complexity strings (one per planned task). Used by
   * RunOrchestrator to populate {@code cost_estimate_low / high}, {@code credits_charged}, and
   * {@code hard_cap_usd} before execution.
   *
   * <p>Does not attempt to match V1's {@code CostEstimator} precisely — V2 has a simpler pipeline
   * (no compiler agent, deterministic reviewer passes) and a flat per-task token model is enough
   * for UI display. Actual cost comes from the real token tracker at terminal state.
   */
  public Estimate estimateRun(
      String provider, String model, java.util.List<String> taskComplexities) {
    Optional<ModelPricing> found =
        pricingRepo.findByIdProviderAndIdModel(providerKey(provider), model);
    double inRate = found.map(ModelPricing::getInputCostPer1m).orElse(0.0);
    double outRate = found.map(ModelPricing::getOutputCostPer1m).orElse(0.0);

    int totalIn = OVERHEAD_INPUT_TOKENS;
    int totalOut = OVERHEAD_OUTPUT_TOKENS;
    int tasks = 0;
    if (taskComplexities != null) {
      tasks = taskComplexities.size();
      for (String c : taskComplexities) {
        int[] band = bandFor(c);
        totalIn += band[0];
        totalOut += band[1];
      }
    }

    double low = (totalIn / 1_000_000.0) * inRate + (totalOut / 1_000_000.0) * outRate;
    double high = low * HIGH_MULTIPLIER;
    double buffered = (low + high) / 2.0 * 1.10; // +10% estimation buffer
    int credits = Math.max(1, (int) Math.ceil(buffered / USD_PER_CREDIT));
    double hardCap = credits * USD_PER_CREDIT;
    return new Estimate(low, high, credits, hardCap, tasks);
  }

  private static int[] bandFor(String complexity) {
    if (complexity == null) return TOKENS_MEDIUM;
    return switch (complexity.toUpperCase()) {
      case "LOW" -> TOKENS_LOW;
      case "HIGH" -> TOKENS_HIGH;
      default -> TOKENS_MEDIUM;
    };
  }

  public record Estimate(
      double lowUsd, double highUsd, int credits, double hardCapUsd, int taskCount) {}

  /**
   * Tier-aware estimate: each task's cost uses the model that will actually run it (resolved via
   * {@link ModelResolver} per complexity → tier mapping). HIGH tasks price at PLANNING tier rates
   * (e.g. Sonnet / gpt-5.4), LOW/MEDIUM at WORKHORSE/LIGHTWEIGHT rates (Haiku / gpt-5.4-mini).
   * Reflects the real dispatch done by {@code SequentialExecutor}.
   *
   * <p>v2.6.2 slice 5 — convenience overload that defaults to a null strategy (legacy single-phase
   * planner shape). New callers should pass an explicit {@link
   * com.navneet.executespec.v2.agent.PlanStrategy} so the estimate accounts for
   * hierarchical-pipeline phase costs.
   */
  public Estimate estimateRunTierAware(String provider, java.util.List<String> taskComplexities) {
    return estimateRunTierAware(provider, taskComplexities, null);
  }

  /**
   * v2.6.2 slice 5 — strategy-aware estimate. Adds the per-strategy hierarchical- planner phase
   * costs on top of the worker cost:
   *
   * <pre>
   *   SINGLE_SHOT — workers only (heuristic-fast-path bypasses every LLM call)
   *   SMALL       — classifier (LIGHTWEIGHT) + outline (PLANNING, FULL_PLAN) + workers
   *   MEDIUM      — classifier + outline + coherence (LIGHTWEIGHT) + workers
   *   LARGE       — classifier + outline (SKELETON) + N expansion (WORKHORSE)
   *                 + coherence + workers
   *   LEGACY      — same as LARGE plus a sub-outline + sub-expansion fan-out per
   *                 expected EPIC task (depth-2 cap, ~25% of skeletons assumed EPIC)
   * </pre>
   *
   * Per-phase token bands are coarse but match the configured agent maxTokens and what live runs
   * have actually burned. They are intentionally pessimistic on the high side so {@code
   * costEstimateHigh} doesn't undercount; the silent hard cap is computed off the buffered
   * midpoint.
   */
  public Estimate estimateRunTierAware(
      String provider,
      java.util.List<String> taskComplexities,
      com.navneet.executespec.v2.agent.PlanStrategy strategy) {
    // v2.5.13 — enum is now GEMINI (was GOOGLE); "gemini" input → "GEMINI" enum
    // value. Keep the null + uppercase guards so callers can pass any case.
    String matrixProvider = provider == null ? "OPENAI" : provider.toUpperCase();
    LlmProvider p;
    try {
      p = LlmProvider.valueOf(matrixProvider);
    } catch (Exception e) {
      return estimateRun(provider, null, taskComplexities); // fallback
    }

    // ── Worker cost (per-task, tier-aware). Same logic for every strategy. ──
    double workerCost = 0.0;
    int totalTasks = 0;
    if (taskComplexities != null) {
      for (String c : taskComplexities) {
        totalTasks++;
        AgentTier tier =
            switch (c == null ? "MEDIUM" : c.toUpperCase()) {
              case "LOW" -> AgentTier.LIGHTWEIGHT;
              case "HIGH" -> AgentTier.PLANNING;
              default -> AgentTier.WORKHORSE;
            };
        ResolvedModel resolved = modelResolver.resolve("WorkerAgent", p, tier);
        double[] r = rates(resolved.getActualProvider().name().toLowerCase(), resolved.getModel());
        int[] band = bandFor(c);
        workerCost += (band[0] / 1_000_000.0) * r[0] + (band[1] / 1_000_000.0) * r[1];
      }
    }

    // ── Planning-phase overhead, strategy-shaped. ─────────────────────
    double planningPhaseCost = computePlanningPhaseCost(p, strategy, totalTasks);

    double low = workerCost + planningPhaseCost;
    double high = low * HIGH_MULTIPLIER;
    double buffered = (low + high) / 2.0 * 1.10;
    int credits = Math.max(1, (int) Math.ceil(buffered / USD_PER_CREDIT));
    double hardCap = credits * USD_PER_CREDIT;
    return new Estimate(low, high, credits, hardCap, totalTasks);
  }

  // ── Strategy-aware planning-phase cost ─────────────────────────────────
  // Token bands per agent (input / output) — pessimistic on the high side.

  /**
   * SmartClassifierAgent: LIGHTWEIGHT, ~prompt + spec + repo context. Skip when heuristic fast path
   * catches the spec (estimated ~30% of SMALL traffic).
   */
  private static final int[] CLASSIFIER_TOKENS = {1_500, 200};

  /** OutlinePlannerAgent FULL_PLAN: PLANNING, full plan in one call. */
  private static final int[] OUTLINE_FULL_TOKENS = {8_000, 6_000};

  /** OutlinePlannerAgent SKELETON_ONLY: PLANNING, skeleton-only, smaller output. */
  private static final int[] OUTLINE_SKELETON_TOKENS = {8_000, 2_500};

  /** TaskExpanderAgent (per task): WORKHORSE, ~plan context + skeleton + body. */
  private static final int[] EXPANDER_PER_TASK_TOKENS = {3_500, 1_500};

  /** CoherencePlannerAgent: LIGHTWEIGHT, ~assembled plan + small patch list. */
  private static final int[] COHERENCE_TOKENS = {5_000, 800};

  /** Per-EPIC sub-outline cost (LEGACY only) — same shape as a SKELETON outline. */
  private static final int[] SUB_OUTLINE_TOKENS = {4_000, 1_500};

  /** Fraction of LEGACY skeletons we assume will be EPIC (drives sub-outline + sub-expand cost). */
  private static final double LEGACY_EPIC_RATIO = 0.25;

  /** Children-per-EPIC assumption (Classification.MEDIUM caps at 4). */
  private static final int LEGACY_CHILDREN_PER_EPIC = 3;

  private double computePlanningPhaseCost(
      LlmProvider p, com.navneet.executespec.v2.agent.PlanStrategy strategy, int totalTasks) {
    // Pre-resolved per-tier rates for this provider.
    double[] lightweightRates = ratesForTier(p, AgentTier.LIGHTWEIGHT);
    double[] workhorseRates = ratesForTier(p, AgentTier.WORKHORSE);
    double[] planningRates = ratesForTier(p, AgentTier.PLANNING);

    if (strategy == null) {
      // Legacy single-phase planner shape — preserves slice-1-and-earlier
      // estimates for SINGLE_SHOT/legacy fallbacks.
      return cost(OVERHEAD_INPUT_TOKENS, OVERHEAD_OUTPUT_TOKENS, planningRates);
    }
    return switch (strategy) {
        // SINGLE_SHOT bypasses the LLM entirely (heuristic fast path) — no
        // phase overhead beyond the synthesised plan + workers.
      case SINGLE_SHOT -> 0.0;
        // SMALL: classifier (LIGHTWEIGHT) + outline FULL_PLAN (PLANNING). No coherence.
      case SMALL ->
          cost(CLASSIFIER_TOKENS, lightweightRates) + cost(OUTLINE_FULL_TOKENS, planningRates);
        // MEDIUM: classifier + outline FULL_PLAN + coherence.
      case MEDIUM ->
          cost(CLASSIFIER_TOKENS, lightweightRates)
              + cost(OUTLINE_FULL_TOKENS, planningRates)
              + cost(COHERENCE_TOKENS, lightweightRates);
        // LARGE: classifier + outline SKELETON + N parallel expanders + coherence.
      case LARGE ->
          cost(CLASSIFIER_TOKENS, lightweightRates)
              + cost(OUTLINE_SKELETON_TOKENS, planningRates)
              + totalTasks * cost(EXPANDER_PER_TASK_TOKENS, workhorseRates)
              + cost(COHERENCE_TOKENS, lightweightRates);
        // LEGACY: LARGE shape + per-EPIC sub-outline + sub-expansion fan-out.
        // Assume LEGACY_EPIC_RATIO of skeletons are EPIC, each spawning
        // LEGACY_CHILDREN_PER_EPIC sub-LEAFs.
      case LEGACY -> {
        double base =
            cost(CLASSIFIER_TOKENS, lightweightRates)
                + cost(OUTLINE_SKELETON_TOKENS, planningRates)
                + totalTasks * cost(EXPANDER_PER_TASK_TOKENS, workhorseRates)
                + cost(COHERENCE_TOKENS, lightweightRates);
        int epicCount = (int) Math.ceil(totalTasks * LEGACY_EPIC_RATIO);
        int extraChildren = epicCount * LEGACY_CHILDREN_PER_EPIC;
        yield base
            + epicCount * cost(SUB_OUTLINE_TOKENS, planningRates)
            + extraChildren * cost(EXPANDER_PER_TASK_TOKENS, workhorseRates);
      }
    };
  }

  private double[] ratesForTier(LlmProvider p, AgentTier tier) {
    try {
      ResolvedModel rm = modelResolver.resolve("WorkerAgent", p, tier);
      return rates(rm.getActualProvider().name().toLowerCase(), rm.getModel());
    } catch (Exception e) {
      return new double[] {0.0, 0.0};
    }
  }

  private static double cost(int[] band, double[] rates) {
    return (band[0] / 1_000_000.0) * rates[0] + (band[1] / 1_000_000.0) * rates[1];
  }

  private static double cost(int inputTokens, int outputTokens, double[] rates) {
    return (inputTokens / 1_000_000.0) * rates[0] + (outputTokens / 1_000_000.0) * rates[1];
  }

  private double[] rates(String providerLower, String model) {
    return pricingRepo
        .findByIdProviderAndIdModel(providerKey(providerLower), model)
        .map(
            mp ->
                new double[] {
                  mp.getInputCostPer1m() != null ? mp.getInputCostPer1m() : 0.0,
                  mp.getOutputCostPer1m() != null ? mp.getOutputCostPer1m() : 0.0
                })
        .orElse(new double[] {0.0, 0.0});
  }

  /**
   * Provider-aware pricing. The "normal input" calculation differs by provider because each API
   * reports {@code input_tokens} / {@code prompt_tokens} differently relative to the cache fields:
   *
   * <ul>
   *   <li><b>Anthropic</b> — {@code input_tokens} EXCLUDES cache (verified via Spring AI 1.1.4's
   *       {@code AnthropicChatModel.getDefaultUsage} decompilation 2026-05-04: only {@code
   *       inputTokens()} flows into Spring AI's promptTokens). Cache fields are independent. So
   *       {@code normalIn = inTok} — no subtraction.
   *   <li><b>OpenAI</b> — {@code prompt_tokens} INCLUDES cached_tokens. The cached_tokens count is
   *       a SUBSET. So {@code normalIn = inTok - cacheRead} to extract the uncached billable
   *       portion. OpenAI has no cache_write concept (caching is automatic + free to populate).
   *   <li><b>Gemini</b> — {@code promptTokenCount} INCLUDES {@code cachedContentTokenCount}. Same
   *       shape as OpenAI: subtract. Gemini also bills thinking tokens at the OUTPUT rate; those
   *       are folded into {@code outTok} by the caller (record method) so the math here stays
   *       uniform.
   * </ul>
   *
   * <p>Cache write multiplier is Anthropic-specific (5-min ephemeral cache: 125% of base input);
   * OpenAI + Gemini have no cache write concept so callers pass {@code cacheWrite=0} for those
   * providers and the term zeroes out. Cache read multiplier (10%) is roughly accurate for
   * Anthropic (10%); OpenAI's actual cached rate is closer to 50% on most models and Gemini's is
   * 25% on context-cached content — those would need per-provider rates if this code path actually
   * fires for them, but as of 2026-05-04 OpenAI/Gemini cache reads are 0 in our flow (auto-caching
   * for OpenAI is hit-or-miss + we don't enable Gemini context caching).
   */
  /**
   * Public cost helper for callers that aren't going through the run-tracking flow (per-PR review,
   * code-review scan, architecture/doc generation). Computes USD from tokens via the same pricing
   * matrix the recording path uses; no cache_write / cache_read accounting (those callers don't
   * expose those counts), so the result is an upper-bound when cached input is involved — matches
   * what we'd record for a non-cached call. Returns 0 if pricing is missing for the provider/model
   * tuple, same as the recording path.
   */
  public double computeCost(String provider, String model, int inTok, int outTok) {
    return priceCall(provider, model, inTok, outTok, 0, 0);
  }

  /**
   * 2026-05-06 — single source of truth. Cache-read pricing is now read directly from the {@code
   * cached-input-per-1m} field on each model in the YAML matrix, NOT from a hardcoded per-provider
   * multiplier. The audit (docs/reports/model-mapping-audit-2026-05-06.md §4 #4) found the prior
   * multiplier-based code ignored YAML's cache rate for OpenAI + Gemini — YAML now reflects each
   * provider's documented rate (Anthropic 10%, OpenAI 50%, Gemini 25% of input) and this method
   * does the math directly.
   *
   * <p>Token-input semantics still differ by provider:
   *
   * <ul>
   *   <li>Anthropic: {@code input_tokens} is uncached only — don't subtract {@code cacheRead}.
   *   <li>OpenAI / Gemini: {@code prompt_tokens} / {@code promptTokenCount} INCLUDES cached tokens
   *       — subtract to get uncached count.
   * </ul>
   */
  private double priceCall(
      String provider, String model, int inTok, int outTok, int cacheWrite, int cacheRead) {
    Optional<ModelPricing> found =
        pricingRepo.findByIdProviderAndIdModel(providerKey(provider), model);
    if (found.isEmpty()) {
      log.debug(
          "RunCostTracker: no pricing for {}/{} — recording tokens without cost", provider, model);
      return 0.0;
    }
    ModelPricing p = found.get();
    double inRate = p.getInputCostPer1m() != null ? p.getInputCostPer1m() : 0;
    double outRate = p.getOutputCostPer1m() != null ? p.getOutputCostPer1m() : 0;
    // Cache-read rate from YAML; falls back to inRate * 0.10 (Anthropic-shape default) when the
    // YAML row predates the 2026-05-06 cache-rate seeding. Defensive — every shipping model
    // SHOULD have cached_input_cost_per_1m populated by ModelPricingInitializer.
    double cacheRate =
        p.getCachedInputCostPer1m() != null ? p.getCachedInputCostPer1m() : inRate * 0.10;

    // Anthropic returns uncached input tokens directly; OpenAI + Gemini bundle cached + uncached
    // into a single count and require subtraction. This is a wire-level distinction, not a
    // pricing one — the rates above already capture the discount.
    final int normalIn =
        "anthropic".equalsIgnoreCase(provider) ? inTok : Math.max(0, inTok - cacheRead);

    double cost = 0.0;
    cost += (normalIn / 1_000_000.0) * inRate;
    cost += (cacheWrite / 1_000_000.0) * inRate * CACHE_WRITE_MULTIPLIER; // Anthropic-only
    cost += (cacheRead / 1_000_000.0) * cacheRate;
    cost += (outTok / 1_000_000.0) * outRate;
    return cost;
  }

  /**
   * V2-canonical provider key for pricing-table lookup. v2.5.13 renamed enum GOOGLE → GEMINI + YAML
   * provider rows (application.yml `provider: gemini`) so V2 and pricing table now agree on the
   * string "gemini". Legacy DB rows with provider="google" fall back through this helper via the
   * equalsIgnoreCase branch.
   */
  private static String providerKey(String provider) {
    if (provider == null) return "";
    String v = provider.toLowerCase();
    // Legacy compat — any remaining DB rows still seeded as "google"
    // before v2.5.13 will still be found when the caller passes "gemini".
    return v;
  }

  /**
   * Reflectively invokes a no-arg accessor on whatever object is passed and returns the result as
   * an int. Used by the cache-token extraction path: Spring AI 1.1.4's generic {@link Usage}
   * doesn't expose Anthropic's cache fields, but the underlying {@code AnthropicApi.Usage} record
   * does via {@code cacheCreationInputTokens()} / {@code cacheReadInputTokens()}. Returns 0 on any
   * reflection failure or non-numeric result — silent fallback rather than throwing.
   */
  private static int callUsageMethodInt(Object target, String methodName) {
    if (target == null) return 0;
    try {
      var m = target.getClass().getMethod(methodName);
      Object v = m.invoke(target);
      if (v instanceof Number n) return n.intValue();
    } catch (Exception ignored) {
    }
    return 0;
  }

  /**
   * Reflectively invokes a no-arg accessor and returns the raw Object — used to walk one
   * indirection (e.g. {@code DefaultUsage.getNativeUsage()} returns the original Anthropic Usage
   * record, on which we then call {@link #callUsageMethodInt} to read the cache fields).
   */
  private static Object callUsageMethod0(Object target, String methodName) {
    if (target == null) return null;
    try {
      var m = target.getClass().getMethod(methodName);
      return m.invoke(target);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static int readMetaInt(ChatResponse response, String key) {
    try {
      Object v = response.getMetadata() == null ? null : response.getMetadata().get(key);
      if (v instanceof Number n) return n.intValue();
    } catch (Exception ignored) {
    }
    return 0;
  }

  private static int safe(Integer v) {
    return v == null ? 0 : v;
  }

  // ── types ────────────────────────────────────────────────────────────────

  private static final class Accumulator {
    final AtomicLong inputTokens = new AtomicLong();
    final AtomicLong outputTokens = new AtomicLong();
    final AtomicLong cacheWriteTokens = new AtomicLong();
    final AtomicLong cacheReadTokens = new AtomicLong();
    final AtomicLong costMicros = new AtomicLong();
    final AtomicLong calls = new AtomicLong();

    Snapshot toSnapshot() {
      return new Snapshot(
          (int) inputTokens.get(),
          (int) outputTokens.get(),
          (int) cacheWriteTokens.get(),
          (int) cacheReadTokens.get(),
          costMicros.get() / 1_000_000.0,
          (int) calls.get());
    }
  }

  public record Snapshot(
      int inputTokens,
      int outputTokens,
      int cacheWriteTokens,
      int cacheReadTokens,
      double costUsd,
      int calls) {}
}
