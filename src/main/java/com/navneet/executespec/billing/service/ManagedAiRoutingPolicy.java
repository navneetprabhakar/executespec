package com.navneet.executespec.billing.service;

import java.util.Locale;

import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class ManagedAiRoutingPolicy {

  static final String FULL = "FULL";
  static final String VALUE_MODE = "VALUE_MODE";
  static final String FRONTIER_LOCKED = "FRONTIER_LOCKED";
  static final String CHEAP_ONLY = "CHEAP_ONLY";
  static final String EXHAUSTED = "EXHAUSTED";

  /**
   * Run-count retirement (2026-06-22): the customer-facing spend limit is the ES AI credit grant,
   * not a raw-$ cap. EXHAUSTED and the credit step-downs are denominated in CREDITS ({@code
   * aiCreditsUsed} vs {@code includedAiCredits}), so a top-up — which raises {@code
   * includedAiCredits} — lifts the gate. Step-down thresholds keep their configured shape by
   * scaling the grant by the raw-COGS proportion (e.g. cheap-only at $12 of a $16 cap → 75% of the
   * grant). The frontier sub-cap stays an internal raw-COGS guardrail (margin control on the most
   * expensive models), independent of the customer credit limit.
   */
  String recomputeState(ManagedAiUsagePeriod period) {
    long included = period.getIncludedAiCredits() + period.getPurchasedAiCredits();
    long usedCredits = period.getAiCreditsUsed();
    if (included > 0 && usedCredits >= included) return EXHAUSTED;
    double hardRaw =
        period.getHardStopAfterRawUsd() > 0
            ? period.getHardStopAfterRawUsd()
            : period.getRawCogsCapUsd();
    long cheapOnly = creditThreshold(included, period.getCheapOnlyAfterRawUsd(), hardRaw);
    if (cheapOnly > 0 && usedCredits >= cheapOnly) return CHEAP_ONLY;
    if (period.getFrontierRawCapUsd() > 0
        && period.getFrontierRawUsedUsd() >= period.getFrontierRawCapUsd()) {
      return FRONTIER_LOCKED;
    }
    long valueMode = creditThreshold(included, period.getValueModeAfterRawUsd(), hardRaw);
    if (valueMode > 0 && usedCredits >= valueMode) return VALUE_MODE;
    return FULL;
  }

  /**
   * Credit-denominated step-down threshold = the credit grant scaled by the configured raw-COGS
   * proportion ({@code thresholdRaw / hardRaw}). Keeps the policy's configured shape while gating
   * on credits. Returns 0 (disabled) when any input is non-positive.
   */
  private static long creditThreshold(long includedCredits, double thresholdRaw, double hardRaw) {
    if (includedCredits <= 0 || thresholdRaw <= 0 || hardRaw <= 0) return 0;
    return Math.round(includedCredits * (thresholdRaw / hardRaw));
  }

  boolean isAllowed(String state, String costClass, String agentRole) {
    return switch (state) {
      case EXHAUSTED -> false;
      case CHEAP_ONLY -> "CHEAP".equals(costClass);
      case FRONTIER_LOCKED -> !"FRONTIER".equals(costClass);
      case VALUE_MODE -> !"FRONTIER".equals(costClass) || isCriticalAgent(agentRole);
      default -> true;
    };
  }

  boolean shouldStepUp(
      String state, String costClass, String agentRole, ManagedAiUsagePeriod period) {
    return VALUE_MODE.equals(state)
        && !"FRONTIER".equals(costClass)
        && isCriticalAgent(agentRole)
        && frontierBudgetAvailable(period);
  }

  ResponseStatusException blocked(String state, String costClass) {
    return new ResponseStatusException(
        HttpStatus.PAYMENT_REQUIRED,
        "Managed AI routing state " + state + " does not allow " + costClass + " model calls.");
  }

  private static boolean frontierBudgetAvailable(ManagedAiUsagePeriod period) {
    return period.getFrontierRawCapUsd() <= 0
        || period.getFrontierRawUsedUsd() < period.getFrontierRawCapUsd();
  }

  private static boolean isCriticalAgent(String agentRole) {
    if (agentRole == null) return false;
    String role = agentRole.toLowerCase(Locale.ROOT);
    return role.contains("planner") || role.contains("reviewer");
  }
}
