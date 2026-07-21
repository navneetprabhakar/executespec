package com.navneet.executespec.executioncontext;

/** Exact durable identity attached to every customer LLM request. */
public record LlmExecutionReference(String contextId, LlmExecutionScope scope) {

  public LlmExecutionReference {
    if (contextId == null || contextId.isBlank()) {
      throw new IllegalArgumentException("contextId required");
    }
    if (scope == null) throw new IllegalArgumentException("scope required");
    contextId = contextId.trim();
  }
}
