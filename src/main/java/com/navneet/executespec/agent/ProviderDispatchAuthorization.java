package com.navneet.executespec.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared fail-closed authorization boundary for every provider-spending transport. */
@Component
public class ProviderDispatchAuthorization {

  private final boolean liveLlmEnabled;
  private final boolean liveLlmTestMode;
  private final String reservationId;

  public ProviderDispatchAuthorization(
      @Value("${executespec.live-llm.enabled:true}") boolean liveLlmEnabled,
      @Value("${executespec.live-llm.test-mode:false}") boolean liveLlmTestMode,
      @Value("${executespec.live-llm.reservation-id:}") String reservationId) {
    this.liveLlmEnabled = liveLlmEnabled;
    this.liveLlmTestMode = liveLlmTestMode;
    this.reservationId = reservationId;
  }

  public void authorize(String provider, String model, String runId) {
    if (!liveLlmEnabled) {
      throw new IllegalStateException(
          "Live LLM transport is disabled. Reserve provider budget before dispatch.");
    }
    if (liveLlmTestMode) {
      ProviderTestBudgetGuard.authorize(reservationId, provider, model, runId);
    }
  }
}
