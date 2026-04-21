package com.navneet.executespec.v2.cost;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V2 per-run cost accumulator. Called by {@link com.navneet.executespec.v2.agent.LlmRouterV2}
 * after every successful LLM dispatch, so the orchestrator and the {@code /cost} endpoint
 * can surface live spend without reading the V1 {@code agent_task} token columns.
 *
 * <p>Stores a single in-memory {@link Accumulator} per runId with input/output token
 * totals and a running USD cost computed against {@link ModelPricingRepository}. The
 * tracker is deliberately cache-aware only for providers that surface
 * {@code cache_read_input_tokens} / {@code cache_creation_input_tokens} in their
 * response metadata (Anthropic); for others, those fields stay zero and the pricing
 * falls back to the flat input/output rates.
 *
 * <p>{@link #finalizeRun(String)} returns a snapshot and removes the entry, so a
 * completed run's data doesn't leak memory. The tracker is best-effort — lookups
 * against a runId not seen before return an empty snapshot rather than throwing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class V2RunCostTracker {

    private static final double CACHE_WRITE_MULTIPLIER = 1.25;
    private static final double CACHE_READ_MULTIPLIER  = 0.10;

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
    private static final int[] TOKENS_LOW    = { 30_000,  4_000 };  // was 6k/1.5k
    private static final int[] TOKENS_MEDIUM = { 200_000, 15_000 }; // was 20k/4k
    private static final int[] TOKENS_HIGH   = { 600_000, 40_000 }; // was 50k/10k

    // Planner + reviewer fixed overhead (input / output tokens per run).
    private static final int OVERHEAD_INPUT_TOKENS  = 40_000;   // bumped 12k→40k (2026-04-19) after RN run showed
    private static final int OVERHEAD_OUTPUT_TOKENS = 5_000;    // pre-plan estimate was 42% under actual

    // High-end multiplier for the buffered estimate (retries, cache misses, escalations).
    private static final double HIGH_MULTIPLIER = 1.75;

    /** Credit multiplier — USD per credit. Matches V1's default (see AgentProperties.Cost). */
    private static final double USD_PER_CREDIT = 5.0;

    private final Map<String, Accumulator> byRun = new ConcurrentHashMap<>();
    /** Per-task accumulator, keyed by {@code runId|taskId}. Populated when the
     *  router is given a task-scoped {@code ExecutionContext}; consumed by the
     *  orchestrator to write real per-task token columns to {@code agent_task}
     *  instead of the old equal-split approximation. */
    private final Map<String, Accumulator> byRunTask = new ConcurrentHashMap<>();

    /** Records a single ChatResponse's token usage + cost against {@code runId}. */
    public void record(String runId, String provider, String model, ChatResponse response) {
        record(runId, null, provider, model, response);
    }

    /** Task-scoped variant — also accumulates into a per-task bucket keyed by
     *  {@code runId|taskId}. {@code taskId} may be null for non-task calls
     *  (planner, reviewer); behaviour matches the legacy two-arg variant. */
    public void record(String runId, String taskId, String provider, String model, ChatResponse response) {
        if (runId == null || response == null) return;

        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        int inTok  = usage == null ? 0 : safe(usage.getPromptTokens());
        int outTok = usage == null ? 0 : safe(usage.getCompletionTokens());

        // Anthropic cache tokens. Spring AI 1.1.4 exposes these in two possible
        // places depending on version: (a) as raw keys on the ChatResponse metadata
        // map (older behavior, snake_case or camelCase), (b) as typed fields on the
        // AnthropicApi.Usage record accessible only via reflection since the generic
        // Spring AI `Usage` interface returned by getMetadata().getUsage() doesn't
        // expose them. Try all three paths before giving up.
        int cacheWrite = readMetaInt(response, "cache_creation_input_tokens");
        int cacheRead  = readMetaInt(response, "cache_read_input_tokens");
        if (cacheWrite == 0 && cacheRead == 0) {
            cacheWrite = readMetaInt(response, "cacheCreationInputTokens");
            cacheRead  = readMetaInt(response, "cacheReadInputTokens");
        }
        if ((cacheWrite == 0 && cacheRead == 0) && usage != null) {
            cacheWrite = callUsageMethod(usage, "cacheCreationInputTokens");
            cacheRead  = callUsageMethod(usage, "cacheReadInputTokens");
        }

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
            log.info("V2RunCostTracker record run={} provider={} model={} in={} out={} "
                    + "cacheWrite={} cacheRead={} call#{} totals[in={}, out={}, ${}]",
                    runId, provider, model, inTok, outTok, cacheWrite, cacheRead,
                    acc.calls.get(), acc.inputTokens.get(), acc.outputTokens.get(),
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
     * Rough pre-run estimate from a list of complexity strings (one per planned task).
     * Used by V2RunOrchestrator to populate {@code cost_estimate_low / high},
     * {@code credits_charged}, and {@code hard_cap_usd} before execution.
     *
     * <p>Does not attempt to match V1's {@code CostEstimator} precisely — V2 has a
     * simpler pipeline (no compiler agent, deterministic reviewer passes) and a
     * flat per-task token model is enough for UI display. Actual cost comes from
     * the real token tracker at terminal state.
     */
    public Estimate estimateRun(String provider, String model, java.util.List<String> taskComplexities) {
        Optional<ModelPricing> found = pricingRepo.findByIdProviderAndIdModel(
                providerKey(provider), model);
        double inRate  = found.map(ModelPricing::getInputCostPer1m).orElse(0.0);
        double outRate = found.map(ModelPricing::getOutputCostPer1m).orElse(0.0);

        int totalIn  = OVERHEAD_INPUT_TOKENS;
        int totalOut = OVERHEAD_OUTPUT_TOKENS;
        int tasks = 0;
        if (taskComplexities != null) {
            tasks = taskComplexities.size();
            for (String c : taskComplexities) {
                int[] band = bandFor(c);
                totalIn  += band[0];
                totalOut += band[1];
            }
        }

        double low  = (totalIn / 1_000_000.0) * inRate + (totalOut / 1_000_000.0) * outRate;
        double high = low * HIGH_MULTIPLIER;
        double buffered = (low + high) / 2.0 * 1.10;  // +10% estimation buffer
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

    public record Estimate(double lowUsd, double highUsd, int credits, double hardCapUsd, int taskCount) {}

    /**
     * Tier-aware estimate: each task's cost uses the model that will actually run it
     * (resolved via {@link ModelResolver} per complexity → tier mapping). HIGH tasks
     * price at PLANNING tier rates (e.g. Sonnet / gpt-5.4), LOW/MEDIUM at
     * WORKHORSE/LIGHTWEIGHT rates (Haiku / gpt-5.4-mini). Reflects the real dispatch
     * done by {@code SequentialExecutorV2}.
     */
    public Estimate estimateRunTierAware(String provider, java.util.List<String> taskComplexities) {
        // v2.5.13 — enum is now GEMINI (was GOOGLE); "gemini" input → "GEMINI" enum
        // value. Keep the null + uppercase guards so callers can pass any case.
        String matrixProvider = provider == null ? "OPENAI" : provider.toUpperCase();
        LlmProvider p;
        try {
            p = LlmProvider.valueOf(matrixProvider);
        } catch (Exception e) {
            return estimateRun(provider, null, taskComplexities);  // fallback
        }

        // Planner + reviewer run at PLANNING tier in V2.
        ResolvedModel planning = modelResolver.resolve("PlannerAgent", p, AgentTier.PLANNING);
        double[] planningRates = rates(planning.getActualProvider().name().toLowerCase(), planning.getModel());
        double overheadCost = (OVERHEAD_INPUT_TOKENS / 1_000_000.0) * planningRates[0]
                + (OVERHEAD_OUTPUT_TOKENS / 1_000_000.0) * planningRates[1];

        double workerCost = 0.0;
        int totalTasks = 0;
        if (taskComplexities != null) {
            for (String c : taskComplexities) {
                totalTasks++;
                AgentTier tier = switch (c == null ? "MEDIUM" : c.toUpperCase()) {
                    case "LOW"  -> AgentTier.LIGHTWEIGHT;
                    case "HIGH" -> AgentTier.PLANNING;
                    default     -> AgentTier.WORKHORSE;
                };
                ResolvedModel resolved = modelResolver.resolve("WorkerAgent", p, tier);
                double[] r = rates(resolved.getActualProvider().name().toLowerCase(), resolved.getModel());
                int[] band = bandFor(c);
                workerCost += (band[0] / 1_000_000.0) * r[0] + (band[1] / 1_000_000.0) * r[1];
            }
        }

        double low  = workerCost + overheadCost;
        double high = low * HIGH_MULTIPLIER;
        double buffered = (low + high) / 2.0 * 1.10;
        int credits = Math.max(1, (int) Math.ceil(buffered / USD_PER_CREDIT));
        double hardCap = credits * USD_PER_CREDIT;
        return new Estimate(low, high, credits, hardCap, totalTasks);
    }

    private double[] rates(String providerLower, String model) {
        return pricingRepo.findByIdProviderAndIdModel(providerKey(providerLower), model)
                .map(mp -> new double[] {
                        mp.getInputCostPer1m()  != null ? mp.getInputCostPer1m()  : 0.0,
                        mp.getOutputCostPer1m() != null ? mp.getOutputCostPer1m() : 0.0
                })
                .orElse(new double[] {0.0, 0.0});
    }

    private double priceCall(String provider, String model,
                              int inTok, int outTok, int cacheWrite, int cacheRead) {
        Optional<ModelPricing> found = pricingRepo.findByIdProviderAndIdModel(
                providerKey(provider), model);
        if (found.isEmpty()) {
            log.debug("V2RunCostTracker: no pricing for {}/{} — recording tokens without cost",
                    provider, model);
            return 0.0;
        }
        ModelPricing p = found.get();
        double inRate  = p.getInputCostPer1m()  != null ? p.getInputCostPer1m()  : 0;
        double outRate = p.getOutputCostPer1m() != null ? p.getOutputCostPer1m() : 0;

        int normalIn = Math.max(0, inTok - cacheWrite - cacheRead);
        double cost = 0.0;
        cost += (normalIn   / 1_000_000.0) * inRate;
        cost += (cacheWrite / 1_000_000.0) * inRate * CACHE_WRITE_MULTIPLIER;
        cost += (cacheRead  / 1_000_000.0) * inRate * CACHE_READ_MULTIPLIER;
        cost += (outTok     / 1_000_000.0) * outRate;
        return cost;
    }

    /**
     * V2-canonical provider key for pricing-table lookup. v2.5.13 renamed
     * enum GOOGLE → GEMINI + YAML provider rows (application.yml `provider:
     * gemini`) so V2 and pricing table now agree on the string "gemini".
     * Legacy DB rows with provider="google" fall back through this helper
     * via the equalsIgnoreCase branch.
     */
    private static String providerKey(String provider) {
        if (provider == null) return "";
        String v = provider.toLowerCase();
        // Legacy compat — any remaining DB rows still seeded as "google"
        // before v2.5.13 will still be found when the caller passes "gemini".
        return v;
    }

    /**
     * Reflectively invokes a no-arg accessor on whatever {@link Usage} implementation
     * the provider used. Needed because Spring AI 1.1.4's generic {@code Usage}
     * interface doesn't expose Anthropic's cache-token fields; the concrete
     * {@code AnthropicApi.Usage} record does via {@code cacheCreationInputTokens()}
     * and {@code cacheReadInputTokens()}. Returns 0 if the method doesn't exist or
     * the provider doesn't emit cache data.
     */
    private static int callUsageMethod(Usage usage, String methodName) {
        try {
            var m = usage.getClass().getMethod(methodName);
            Object v = m.invoke(usage);
            if (v instanceof Number n) return n.intValue();
        } catch (Exception ignored) { }
        return 0;
    }

    private static int readMetaInt(ChatResponse response, String key) {
        try {
            Object v = response.getMetadata() == null ? null : response.getMetadata().get(key);
            if (v instanceof Number n) return n.intValue();
        } catch (Exception ignored) { }
        return 0;
    }

    private static int safe(Integer v) { return v == null ? 0 : v; }

    // ── types ────────────────────────────────────────────────────────────────

    private static final class Accumulator {
        final AtomicLong inputTokens      = new AtomicLong();
        final AtomicLong outputTokens     = new AtomicLong();
        final AtomicLong cacheWriteTokens = new AtomicLong();
        final AtomicLong cacheReadTokens  = new AtomicLong();
        final AtomicLong costMicros       = new AtomicLong();
        final AtomicLong calls            = new AtomicLong();

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
