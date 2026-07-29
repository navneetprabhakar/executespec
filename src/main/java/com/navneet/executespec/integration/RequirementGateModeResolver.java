package com.navneet.executespec.integration;

import java.util.Locale;

import com.navneet.executespec.agent.requirement.RequirementGateBindingService.RequestedMode;
import com.navneet.executespec.config.featureflag.FeatureFlagRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Reads the durable, single-valued RequirementIR gate mode without a cache downgrade path. */
@Component
@RequiredArgsConstructor
class RequirementGateModeResolver {
  static final String MODE_FLAG = "requirement.ir.gate.mode";

  private final FeatureFlagRepository repository;

  RequestedMode resolve() {
    String configured =
        repository
            .findByFlagKey(MODE_FLAG)
            .map(flag -> flag.getFlagValue())
            .orElseThrow(() -> new IllegalStateException("RequirementIR gate mode is missing"));
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException("RequirementIR gate mode is blank");
    }
    try {
      return RequestedMode.valueOf(configured.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalStateException("Invalid RequirementIR gate mode: " + configured, invalid);
    }
  }
}
