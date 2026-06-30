package com.navneet.executespec.billing.service;

import org.springframework.stereotype.Service;

@Service
public class ManagedAiCreditCalculator {

  public long creditsFor(double rawCostUsd, double retailMultiplier, long creditsPerUsd) {
    if (rawCostUsd <= 0) return 0;
    long effectiveCreditsPerUsd = creditsPerUsd > 0 ? creditsPerUsd : 125;
    return (long) Math.ceil(rawCostUsd * Math.max(0.0, retailMultiplier) * effectiveCreditsPerUsd);
  }
}
