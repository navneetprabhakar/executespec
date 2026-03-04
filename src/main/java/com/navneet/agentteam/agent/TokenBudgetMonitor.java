package com.navneet.agentteam.agent;

import com.navneet.agentteam.config.AgentProperties;
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

    // Context limits by model (tokens)
    private static final int ANTHROPIC_LIMIT = 200_000;
    private static final int OPENAI_LIMIT     = 128_000;

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
        AgentProperties.TokenBudget budget = props.getTokenBudget();
        int softLimit   = softLimitFor(complexity, budget);
        int hardCeiling = budget.getHardCeiling();
        return budgetPolicy.evaluate(usedTokens, softLimit, hardCeiling);
    }

    public int maxOutputTokens(String complexity) {
        return switch (complexity == null ? "MEDIUM" : complexity.toUpperCase()) {
            case "LOW"  -> props.getTokenBudget().getLow();
            case "HIGH" -> props.getTokenBudget().getHigh();
            default     -> props.getTokenBudget().getMedium();
        };
    }

    public int contextLimitFor(String model) {
        if (model == null) return ANTHROPIC_LIMIT;
        String m = model.toLowerCase();
        if (m.contains("haiku") || m.contains("sonnet") || m.contains("claude")) {
            return ANTHROPIC_LIMIT;
        } else if (m.contains("gpt") || m.contains("openai")) {
            return OPENAI_LIMIT;
        }
        return ANTHROPIC_LIMIT; // safe default
    }

    private int softLimitFor(String complexity, AgentProperties.TokenBudget budget) {
        return switch (complexity == null ? "MEDIUM" : complexity.toUpperCase()) {
            case "LOW"  -> budget.getLow();
            case "HIGH" -> budget.getHigh();
            default     -> budget.getMedium();
        };
    }
}
