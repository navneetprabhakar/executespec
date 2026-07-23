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
   *
   * <p>RR-364 fail-closed: this used to return {@code "openai"} when {@code actualProvider} was
   * null. A {@link ResolvedModel} represents a completed routing decision, so a null actual
   * provider is a broken invariant (partial construction, a mapper/config regression, an unresolved
   * route) — not a request to spend against OpenAI. Silently coercing it hid the defect AND
   * attributed the spend to a provider the caller never chose. Every real resolution path ({@link
   * ModelResolver} and the chat/assessment builders) sets {@code actualProvider}, so this throws
   * only when that invariant is already broken, failing at the broken route rather than dispatching
   * to OpenAI.
   */
  public String providerName() {
    if (actualProvider == null) {
      throw new IllegalStateException(
          "ResolvedModel has no actual provider (model=" + model + ", tier=" + agentTier + ")");
    }
    return actualProvider.name().toLowerCase();
  }
}
