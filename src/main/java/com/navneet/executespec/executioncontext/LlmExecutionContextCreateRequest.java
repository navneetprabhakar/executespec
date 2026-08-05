package com.navneet.executespec.executioncontext;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Immutable input used to create one idempotent durable LLM execution context. */
public record LlmExecutionContextCreateRequest(
    LlmExecutionContext.ContextType contextType,
    String feature,
    String sourceType,
    String sourceId,
    String parentContextId,
    String correlationId,
    LlmExecutionScope scope,
    String userId,
    String credentialOrgId,
    String projectId,
    UUID repoId,
    LlmPolicySnapshot policy,
    LlmExecutionBudget budget,
    Integer maxAttempts,
    String idempotencyKey,
    Instant expiresAt) {

  private static final Set<LlmExecutionContext.ContextType> PROJECT_CONTEXTS =
      Set.of(
          LlmExecutionContext.ContextType.PROJECT_JOB,
          LlmExecutionContext.ContextType.REVIEW_JOB,
          LlmExecutionContext.ContextType.EMBEDDING_JOB);

  public LlmExecutionContextCreateRequest {
    if (contextType == null) throw new IllegalArgumentException("contextType required");
    if (scope == null) throw new IllegalArgumentException("scope required");
    if (policy == null) throw new IllegalArgumentException("policy required");
    budget = budget == null ? LlmExecutionBudget.UNBOUNDED : budget;
    maxAttempts = maxAttempts == null ? 1 : maxAttempts;
    if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");

    feature = require(feature, "feature").toLowerCase(java.util.Locale.ROOT);
    sourceType = require(sourceType, "sourceType").toUpperCase(java.util.Locale.ROOT);
    sourceId = requireMax(sourceId, "sourceId", 160);
    correlationId = requireMax(correlationId, "correlationId", 96);
    idempotencyKey = require(idempotencyKey, "idempotencyKey");
    parentContextId = trimToNull(parentContextId);
    userId = trimToNull(userId);
    credentialOrgId = trimToNull(credentialOrgId);
    projectId = trimToNull(projectId);

    if (scope.mode() == LlmExecutionScope.ScopeMode.CUSTOMER) {
      if (userId == null) throw new IllegalArgumentException("customer context requires userId");
      if (credentialOrgId == null) {
        throw new IllegalArgumentException("customer context requires credentialOrgId");
      }
      if (policy.keySubjectUserId() == null) {
        throw new IllegalArgumentException("customer context requires keySubjectUserId");
      }
    }
    if (PROJECT_CONTEXTS.contains(contextType) && projectId == null) {
      throw new IllegalArgumentException(contextType + " requires projectId");
    }
  }

  private static String require(String value, String field) {
    String normalized = trimToNull(value);
    if (normalized == null) throw new IllegalArgumentException(field + " required");
    return normalized;
  }

  private static String requireMax(String value, String field, int maxLength) {
    String normalized = require(value, field);
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
    }
    return normalized;
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }
}
