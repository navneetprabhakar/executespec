package com.navneet.executespec.billing.service;

import org.springframework.stereotype.Service;

@Service
public class ManagedAiCreditCalculator {

  public long creditsFor(double rawCostUsd, double retailMultiplier, double creditsPerUsd) {
    if (rawCostUsd <= 0) return 0;
    double effectiveCreditsPerUsd = creditsPerUsd > 0 ? creditsPerUsd : 80.0;
    return (long) Math.ceil(rawCostUsd * Math.max(0.0, retailMultiplier) * effectiveCreditsPerUsd);
  }
}
