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

  String recomputeState(ManagedAiUsagePeriod period) {
    double raw = period.getRawCogsUsedUsd();
    double hard =
        period.getHardStopAfterRawUsd() > 0
            ? period.getHardStopAfterRawUsd()
            : period.getRawCogsCapUsd();
    if (hard > 0 && raw >= hard) return EXHAUSTED;
    if (period.getCheapOnlyAfterRawUsd() > 0 && raw >= period.getCheapOnlyAfterRawUsd()) {
      return CHEAP_ONLY;
    }
    if (period.getFrontierRawCapUsd() > 0
        && period.getFrontierRawUsedUsd() >= period.getFrontierRawCapUsd()) {
      return FRONTIER_LOCKED;
    }
    if (period.getValueModeAfterRawUsd() > 0 && raw >= period.getValueModeAfterRawUsd()) {
      return VALUE_MODE;
    }
    return FULL;
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
