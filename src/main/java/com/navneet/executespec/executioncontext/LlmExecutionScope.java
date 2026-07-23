package com.navneet.executespec.executioncontext;

/** Tenant boundary for a durable LLM execution context. */
public record LlmExecutionScope(ScopeMode mode, String orgId) {

  public enum ScopeMode {
    CUSTOMER,
    PLATFORM_INTERNAL
  }

  public LlmExecutionScope {
    if (mode == null) throw new IllegalArgumentException("scope mode required");
    orgId = trimToNull(orgId);
    if (mode == ScopeMode.CUSTOMER && orgId == null) {
      throw new IllegalArgumentException("customer LLM scope requires orgId");
    }
    if (mode == ScopeMode.PLATFORM_INTERNAL && orgId != null) {
      throw new IllegalArgumentException("platform-internal LLM scope cannot carry orgId");
    }
  }

  public static LlmExecutionScope customer(String orgId) {
    return new LlmExecutionScope(ScopeMode.CUSTOMER, orgId);
  }

  public static LlmExecutionScope platformInternal() {
    return new LlmExecutionScope(ScopeMode.PLATFORM_INTERNAL, null);
  }

  public String isolationKey() {
    return mode == ScopeMode.CUSTOMER ? "org:" + orgId : "platform:internal";
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }
}
