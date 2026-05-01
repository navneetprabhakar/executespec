package com.navneet.executespec.agent;

import lombok.extern.slf4j.Slf4j;

/**
 * Stateless policy evaluator for token budget decisions.
 *
 * <p>Given the number of tokens consumed so far and the configured soft/hard limits, returns a
 * {@link BudgetAction} indicating what the agentic loop should do next.
 */
@Slf4j
public class TokenBudgetPolicy {

  public enum BudgetAction {
    CONTINUE, // Under budget — proceed normally
    COMPACT_AND_CONTINUE, // Over soft limit — trigger compaction, then continue
    FORCE_REPORT // Over hard ceiling — worker must produce final report immediately
  }

  /**
   * @param usedTokens tokens consumed so far in this task
   * @param softLimitTokens normal budget (triggers compaction when exceeded)
   * @param hardCeilingTokens absolute maximum (triggers force-report)
   * @return the action the agentic loop should take
   */
  public BudgetAction evaluate(int usedTokens, int softLimitTokens, int hardCeilingTokens) {
    if (usedTokens >= hardCeilingTokens) {
      log.warn("Token budget HARD CEILING reached: {} >= {}", usedTokens, hardCeilingTokens);
      return BudgetAction.FORCE_REPORT;
    }
    if (usedTokens >= softLimitTokens) {
      log.info(
          "Token budget soft limit reached: {} >= {} — triggering compaction",
          usedTokens,
          softLimitTokens);
      return BudgetAction.COMPACT_AND_CONTINUE;
    }
    return BudgetAction.CONTINUE;
  }
}
