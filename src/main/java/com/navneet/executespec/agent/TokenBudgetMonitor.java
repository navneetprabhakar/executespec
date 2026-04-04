package com.navneet.executespec.agent;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.ModelRegistry;
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
    private final ModelRegistry modelRegistry;
    private final TokenBudgetPolicy budgetPolicy = new TokenBudgetPolicy();

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
     * Apply complexity overrides before resolving token budget.
     * Last line of defense against planner over-assignment.
     *
     * @param originalComplexity the planner-assigned complexity (LOW/MEDIUM/HIGH)
     * @param projectType        the project type from .agent-context/project.json (e.g. "STATIC_HTML")
     * @param ownedFilesCount    number of files in the task's owned_files list
     * @param taskId             for logging
     * @return effective complexity after overrides
     */
    public String resolveEffectiveComplexity(String originalComplexity, String projectType,
                                              int ownedFilesCount, String taskId) {
        String c = originalComplexity != null ? originalComplexity.toUpperCase() : "MEDIUM";

        // STATIC_HTML / NODE_FRONTEND: clamp to MEDIUM (8192 output tokens).
        // LOW (4096) is too small for full-page HTML/CSS/JS writes — causes truncation loops.
        // HIGH (12288) is unnecessary since tasks are single-file.
        if ("STATIC_HTML".equalsIgnoreCase(projectType) || "NODE_FRONTEND".equalsIgnoreCase(projectType)) {
            if ("LOW".equals(c)) {
                log.info("Complexity override: task {} upgraded LOW → MEDIUM ({} project)",
                        taskId, projectType);
                return "MEDIUM";
            }
            if ("HIGH".equals(c)) {
                log.info("Complexity override: task {} downgraded HIGH → MEDIUM ({} project)",
                        taskId, projectType);
                return "MEDIUM";
            }
            return c; // already MEDIUM
        }

        // Tasks with ≤2 owned files are almost never HIGH.
        if ("HIGH".equals(c) && ownedFilesCount <= 2) {
            log.info("Complexity override: task {} downgraded HIGH → MEDIUM (≤{} owned files)",
                    taskId, ownedFilesCount);
            return "MEDIUM";
        }

        return c;
    }

    /**
     * Returns the max output tokens for a given model and complexity.
     * Uses the model's actual output limit, capped by complexity-based soft limits
     * to avoid wasteful over-generation on simple tasks.
     */
    public int maxOutputTokens(String model, String complexity) {
        // Fixed ceilings — decoupled from model max output to cap latency.
        // Lower ceilings force the model to iterate (one file per response) rather than
        // producing one massive response that gets truncated and loses writeFile calls.
        // LOW bumped from 4K→6K to reduce truncation loops on CSS/HTML files.
        // If a task genuinely needs more, WorkerAgent auto-continues on truncation (max 2).
        return switch (complexity == null ? "MEDIUM" : complexity.toUpperCase()) {
            case "LOW"    -> 6_144;
            case "MEDIUM" -> 8_192;
            case "HIGH"   -> 12_288;
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
     * Reads from model-matrix.models config via ModelRegistry.
     */
    public int maxOutputForModel(String model) {
        return modelRegistry.maxOutputForModel(model);
    }

    /**
     * Returns the context window limit for the given model.
     * Reads from model-matrix.models config via ModelRegistry.
     */
    public int contextLimitFor(String model) {
        return modelRegistry.contextLimitFor(model);
    }

    private int softLimitFor(String complexity, AgentProperties.TokenBudget budget) {
        return switch (complexity == null ? "MEDIUM" : complexity.toUpperCase()) {
            case "LOW"  -> budget.getLow();
            case "HIGH" -> budget.getHigh();
            default     -> budget.getMedium();
        };
    }
}
