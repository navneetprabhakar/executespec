package com.navneet.executespec.billing.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "managed-ai")
public class ManagedAiProperties {

  private boolean enabled = true;

  /** Retail definition for managed AI credits. Default: $1.25 buys 100 AI credits. */
  private double creditPackUsd = 1.25;

  private long creditPackCredits = 100;

  private Map<String, TierPolicy> tiers = new HashMap<>();

  public double getCreditsPerUsd() {
    if (creditPackUsd <= 0 || creditPackCredits <= 0) return 80.0;
    return creditPackCredits / creditPackUsd;
  }

  public String creditDefinition() {
    return "$" + formatAmount(creditPackUsd) + " = " + creditPackCredits + " AI credits";
  }

  public TierPolicy policyFor(String tier) {
    String key = tier == null || tier.isBlank() ? "FREE" : tier.trim().toUpperCase();
    TierPolicy policy = tiers.get(key);
    if (policy != null) return policy;
    return tiers.getOrDefault("FREE", TierPolicy.freeDefaults());
  }

  private static String formatAmount(double amount) {
    if (Math.rint(amount) == amount) {
      return String.valueOf((long) amount);
    }
    return java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
  }

  @Data
  public static class TierPolicy {
    private long includedCredits = 0;
    private double rawCogsCapUsd = 0.0;
    private double frontierRawCapUsd = 0.0;
    private double valueModeAfterRawUsd = 0.0;
    private double cheapOnlyAfterRawUsd = 0.0;
    private double hardStopAfterRawUsd = 0.0;
    private double retailMultiplier = 1.0;
    private boolean concurrencyEnforced = false;
    private boolean rateLimitsEnabled = false;
    private List<Integer> notificationThresholdsPct = List.of(50, 75, 90, 100);

    public double effectiveHardStopUsd() {
      if (hardStopAfterRawUsd > 0) return hardStopAfterRawUsd;
      return rawCogsCapUsd;
    }

    static TierPolicy freeDefaults() {
      TierPolicy policy = new TierPolicy();
      policy.setIncludedCredits(0);
      policy.setRawCogsCapUsd(0.0);
      policy.setFrontierRawCapUsd(0.0);
      policy.setRetailMultiplier(1.0);
      return policy;
    }
  }
}
