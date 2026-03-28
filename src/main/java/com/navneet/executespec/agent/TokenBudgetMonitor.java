package com.navneet.executespec.agent;

import com.navneet.executespec.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Checks the most-recent LLM call's input-token count against the model's context window.
 * Uses lastCallInputTokens (the current window size), NOT cumulative totals.
 *
 * <p>Also exposes {@link #evaluateBudget} which delegates to {@link TokenBudgetPolicy}
 * for CONTINUE / COMPACT_AND_CONTINUE / FORCE_REPORT decisions based on
 * proportional limits derived from the configured percentages.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TokenBudgetMonitor {

    public enum Status { OK, WARN, COMPACT, HARD_STOP }

    public record BudgetStatus(Status status, double usagePct, int contextLimit) {}

    private final AgentProperties props;
    private final TokenBudgetPolicy budgetPolicy = new TokenBudgetPolicy();

    // Context limits by model family (tokens)
    // Floor at lowest common denominator (Anthropic 200k) for safety
    private static final int ANTHROPIC_LIMIT  = 200_000;
    private static final int OPENAI_LIMIT     = 200_000;  // GPT-5.4: 1.05M, GPT-5.4-mini: 400k — floor to Anthropic limit
    private static final int GEMINI_LIMIT     = 200_000;  // Gemini: 1M+ — floor to Anthropic limit

    // Max output tokens per model (from provider docs)
    private static final int OPUS_MAX_OUTPUT    = 32_000;
    private static final int SONNET_MAX_OUTPUT  = 64_000;
    private static final int HAIKU_MAX_OUTPUT   = 8_192;
    private static final int GPT54_MAX_OUTPUT   = 16_384;
    private static final int GPT54M_MAX_OUTPUT  = 16_384;
    private static final int GEMINI_PRO_OUTPUT  = 65_536;
    private static final int GEMINI_FLASH_OUTPUT = 24_576;
    private static final int DEFAULT_MAX_OUTPUT = 16_000;

    public BudgetStatus check(String model, String complexity, int lastCallInputTokens) {
        int limit = contextLimitFor(model);
        double usagePct = (double) lastCallInputTokens / limit;

        // HIGH complexity gets 5% extra headroom (thresholds raised by 0.05)
        double headroom = "HIGH".equalsIgnoreCase(complexity) ? 0.05 : 0.0;
        double warnPct      = props.getCompaction().getWarnPct()      + headroom;
        double compactPct   = props.getCompaction().getCompactPct()   + headroom;
        double hardStopPct  = props.getCompaction().getHardStopPct()  + headroom;

        Status status;
        if (usagePct >= hardStopPct) {
            status = Status.HARD_STOP;
        } else if (usagePct >= compactPct) {
            status = Status.COMPACT;
        } else if (usagePct >= warnPct) {
            status = Status.WARN;
        } else {
            status = Status.OK;
        }

        log.debug("[Budget] model={} complexity={} tokens={}/{} ({}%) -> {}",
            model, complexity, lastCallInputTokens, limit,
            String.format("%.1f", usagePct * 100), status);

        return new BudgetStatus(status, usagePct, limit);
    }

    /**
     * Evaluates the task's output-token consumption against proportional soft and
     * hard limits configured in {@code agent.token-budget.*-pct}.
     *
     * @param usedTokens  total output tokens consumed by this task so far
     * @param complexity  task complexity: LOW | MEDIUM | HIGH
     * @return CONTINUE, COMPACT_AND_CONTINUE, or FORCE_REPORT
     */
    public TokenBudgetPolicy.BudgetAction evaluateBudget(int usedTokens, String complexity) {
        return evaluateBudget(usedTokens, null, complexity);
    }

    public TokenBudgetPolicy.BudgetAction evaluateBudget(int usedTokens, String model, String complexity) {
        int modelMax    = maxOutputForModel(model);
        int softLimit   = maxOutputTokens(model, complexity);
        int hardCeiling = (int) (modelMax * props.getTokenBudget().getHardCeilingPct());
        return budgetPolicy.evaluate(usedTokens, softLimit, hardCeiling);
    }

    /**
     * Returns the max output tokens for a given model and complexity.
     * Uses the model's actual output limit, capped by complexity-based soft limits
     * to avoid wasteful over-generation on simple tasks.
     */
    public int maxOutputTokens(String model, String complexity) {
        // Fixed ceilings — decoupled from model max output to cap latency.
        // 16K tokens ≈ 400-500 lines of code, ~2.5min at 100 tok/s.
        // If a task genuinely needs more, WorkerAgent auto-continues on truncation.
        return switch (complexity == null ? "MEDIUM" : complexity.toUpperCase()) {
            case "LOW"    -> 8_192;
            case "MEDIUM" -> 12_288;
            case "HIGH"   -> 16_384;
            default       -> 8_192;
        };
    }

    /** @deprecated Use {@link #maxOutputTokens(String, String)} with model parameter. */
    @Deprecated
    public int maxOutputTokens(String complexity) {
        return maxOutputTokens(null, complexity);
    }

    /**
     * Returns the maximum output tokens supported by the given model.
     */
    public int maxOutputForModel(String model) {
        if (model == null) return DEFAULT_MAX_OUTPUT;
        String m = model.toLowerCase();
        if (m.contains("opus"))          return OPUS_MAX_OUTPUT;
        if (m.contains("sonnet"))        return SONNET_MAX_OUTPUT;
        if (m.contains("haiku"))         return HAIKU_MAX_OUTPUT;
        if (m.contains("gpt-5.4-mini"))  return GPT54M_MAX_OUTPUT;
        if (m.contains("gpt-5.4"))       return GPT54_MAX_OUTPUT;
        if (m.contains("gemini") && m.contains("pro"))   return GEMINI_PRO_OUTPUT;
        if (m.contains("gemini") && m.contains("flash")) return GEMINI_FLASH_OUTPUT;
        return DEFAULT_MAX_OUTPUT;
    }

    public int contextLimitFor(String model) {
        if (model == null) return ANTHROPIC_LIMIT;
        String m = model.toLowerCase();
        if (m.contains("gpt") || m.contains("openai")) {
            return OPENAI_LIMIT;
        } else if (m.contains("gemini")) {
            return GEMINI_LIMIT;
        }
        return ANTHROPIC_LIMIT; // Anthropic (claude/haiku/sonnet/opus) and safe default
    }

    private int softLimitFor(String complexity, AgentProperties.TokenBudget budget) {
        return switch (complexity == null ? "MEDIUM" : complexity.toUpperCase()) {
            case "LOW"  -> budget.getLow();
            case "HIGH" -> budget.getHigh();
            default     -> budget.getMedium();
        };
    }
}
