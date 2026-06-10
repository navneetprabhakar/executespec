package com.navneet.executespec.router;

import lombok.Builder;
import lombok.Data;

/**
 * Result of resolving a model via {@link ModelResolver}. Contains the user's requested provider,
 * the actual provider (may differ on failover), the concrete model string, and the agent tier that
 * drove the selection.
 */
@Data
@Builder
public class ResolvedModel {
  private LlmProvider requestedProvider;
  private LlmProvider actualProvider;
  private String model;
  private AgentTier agentTier;
  private boolean fallback;

  /**
   * V2-canonical provider string for {@code LlmRouter.dispatch}. Always call this instead of
   * duplicating provider-name formatting at call sites.
   */
  public String providerName() {
    return actualProvider == null ? "openai" : actualProvider.name().toLowerCase();
  }
}
