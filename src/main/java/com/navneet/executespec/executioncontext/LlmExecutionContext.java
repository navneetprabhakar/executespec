package com.navneet.executespec.executioncontext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Durable, secret-free identity, routing policy, budget, and lease lifecycle for one LLM operation.
 * All lifecycle mutation is package-scoped and serialized by {@link LlmExecutionContextService}.
 */
@Entity
@Table(name = "llm_execution_context")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmExecutionContext {

  public enum ContextType {
    PIPELINE_RUN,
    AGENT_SESSION,
    AGENT_TURN,
    PROJECT_JOB,
    REVIEW_JOB,
    EMBEDDING_JOB,
    BENCHMARK,
    INTERNAL_TEST
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED
  }

  @Id
  @Column(length = 64)
  private String id;

  @Enumerated(EnumType.STRING)
  @Column(name = "context_type", nullable = false, length = 32)
  private ContextType contextType;

  @Column(nullable = false, length = 80)
  private String feature;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType;

  @Column(name = "source_id", nullable = false, length = 160)
  private String sourceId;

  @Column(name = "parent_context_id", length = 64)
  private String parentContextId;

  @Column(name = "correlation_id", nullable = false, length = 96)
  private String correlationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_mode", nullable = false, length = 24)
  private LlmExecutionScope.ScopeMode scopeMode;

  @Column(name = "isolation_key", nullable = false, length = 160)
  private String isolationKey;

  @Column(name = "org_id", length = 64)
  private String orgId;

  @Column(name = "user_id", length = 64)
  private String userId;

  @Column(name = "project_id", length = 64)
  private String projectId;

  @Column(name = "repo_id")
  private java.util.UUID repoId;

  @Enumerated(EnumType.STRING)
  @Column(name = "key_source_mode", nullable = false, length = 16)
  private LlmPolicySnapshot.KeySourceMode keySourceMode;

  @Column(name = "key_subject_user_id", length = 64)
  private String keySubjectUserId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "allowed_providers_json", nullable = false, columnDefinition = "jsonb")
  private List<String> allowedProvidersJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "allowed_provider_models_json", nullable = false, columnDefinition = "jsonb")
  private Map<String, List<String>> allowedProviderModelsJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "provider_key_sources_json", nullable = false, columnDefinition = "jsonb")
  private Map<String, String> providerKeySourcesJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "model_overrides_json", nullable = false, columnDefinition = "jsonb")
  private Map<String, String> modelOverridesJson;

  @Column(name = "requested_provider", length = 32)
  private String requestedProvider;

  @Column(name = "requested_model", length = 160)
  private String requestedModel;

  @Enumerated(EnumType.STRING)
  @Column(name = "routing_mode", nullable = false, length = 32)
  private LlmPolicySnapshot.RoutingMode routingMode;

  @Column(name = "route_pinned", nullable = false)
  private boolean routePinned;

  @Column(name = "allow_provider_failover", nullable = false)
  private boolean allowProviderFailover;

  @Column(name = "route_snapshot_version", nullable = false)
  private int routeSnapshotVersion;

  @Column(name = "max_input_tokens")
  private Long maxInputTokens;

  @Column(name = "max_output_tokens")
  private Long maxOutputTokens;

  @Column(name = "max_total_tokens")
  private Long maxTotalTokens;

  @Column(name = "max_cost_usd", precision = 14, scale = 6)
  private BigDecimal maxCostUsd;

  @Column(name = "max_provider_calls")
  private Integer maxProviderCalls;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private Status status;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "idempotency_key", nullable = false, length = 160)
  private String idempotencyKey;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "cancellation_requested_at")
  private Instant cancellationRequestedAt;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "last_error_code", length = 64)
  private String lastErrorCode;

  @Column(name = "last_error_redacted", columnDefinition = "text")
  private String lastErrorRedacted;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  static LlmExecutionContext queued(
      String id, String requestFingerprint, LlmExecutionContextCreateRequest request, Instant now) {
    LlmExecutionContext context = new LlmExecutionContext();
    LlmPolicySnapshot policy = request.policy();
    LlmExecutionBudget budget = request.budget();

    context.id = id;
    context.contextType = request.contextType();
    context.feature = request.feature();
    context.sourceType = request.sourceType();
    context.sourceId = request.sourceId();
    context.parentContextId = request.parentContextId();
    context.correlationId = request.correlationId();
    context.scopeMode = request.scope().mode();
    context.isolationKey = request.scope().isolationKey();
    context.orgId = request.scope().orgId();
    context.userId = request.userId();
    context.projectId = request.projectId();
    context.repoId = request.repoId();

    context.keySourceMode = policy.keySourceMode();
    context.keySubjectUserId = policy.keySubjectUserId();
    context.allowedProvidersJson = List.copyOf(policy.allowedProviders());
    context.allowedProviderModelsJson = copyNested(policy.allowedProviderModels());
    context.providerKeySourcesJson = new LinkedHashMap<>();
    policy
        .providerKeySources()
        .forEach((provider, source) -> context.providerKeySourcesJson.put(provider, source.name()));
    context.modelOverridesJson = Map.copyOf(policy.modelOverrides());
    context.requestedProvider = policy.requestedProvider();
    context.requestedModel = policy.requestedModel();
    context.routingMode = policy.routingMode();
    context.routePinned = policy.routePinned();
    context.allowProviderFailover = policy.allowProviderFailover();
    context.routeSnapshotVersion = policy.routeSnapshotVersion();

    context.maxInputTokens = budget.maxInputTokens();
    context.maxOutputTokens = budget.maxOutputTokens();
    context.maxTotalTokens = budget.maxTotalTokens();
    context.maxCostUsd = budget.maxCostUsd();
    context.maxProviderCalls = budget.maxProviderCalls();

    context.status = Status.QUEUED;
    context.attempt = 0;
    context.maxAttempts = request.maxAttempts();
    context.idempotencyKey = request.idempotencyKey();
    context.requestFingerprint = requestFingerprint;
    context.nextAttemptAt = now;
    context.expiresAt = request.expiresAt();
    context.createdAt = now;
    context.updatedAt = now;
    return context;
  }

  public LlmExecutionScope scope() {
    return new LlmExecutionScope(scopeMode, orgId);
  }

  public LlmPolicySnapshot policySnapshot() {
    Map<String, LlmPolicySnapshot.CredentialSource> sources = new LinkedHashMap<>();
    providerKeySourcesJson.forEach(
        (provider, source) ->
            sources.put(provider, LlmPolicySnapshot.CredentialSource.valueOf(source)));
    return new LlmPolicySnapshot(
        keySourceMode,
        keySubjectUserId,
        SetSupport.copyOf(allowedProvidersJson),
        copyNested(allowedProviderModelsJson),
        sources,
        Map.copyOf(modelOverridesJson),
        requestedProvider,
        requestedModel,
        routingMode,
        routePinned,
        allowProviderFailover,
        routeSnapshotVersion);
  }

  ClaimOutcome claim(String owner, Instant now, Duration leaseDuration) {
    if (isTerminal()) return ClaimOutcome.REJECTED;
    if (expiresAt != null && !expiresAt.isAfter(now)) {
      terminalFailure("CONTEXT_EXPIRED", "Execution context expired before claim", now);
      return ClaimOutcome.TERMINALIZED;
    }
    boolean queuedAndDue =
        status == Status.QUEUED && (nextAttemptAt == null || !nextAttemptAt.isAfter(now));
    boolean staleRunning =
        status == Status.RUNNING && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now));
    if (!queuedAndDue && !staleRunning) return ClaimOutcome.REJECTED;
    if (attempt >= maxAttempts) {
      terminalFailure("ATTEMPTS_EXHAUSTED", "No execution attempts remain", now);
      return ClaimOutcome.TERMINALIZED;
    }

    status = Status.RUNNING;
    attempt++;
    leaseOwner = owner;
    leaseExpiresAt = now.plus(leaseDuration);
    heartbeatAt = now;
    nextAttemptAt = null;
    if (startedAt == null) startedAt = now;
    updatedAt = now;
    return ClaimOutcome.CLAIMED;
  }

  boolean heartbeat(String owner, Instant now, Duration leaseDuration) {
    if (!hasValidLease(owner, now) || cancellationRequestedAt != null) return false;
    heartbeatAt = now;
    leaseExpiresAt = now.plus(leaseDuration);
    updatedAt = now;
    return true;
  }

  boolean complete(String owner, Instant now) {
    if (!hasValidLease(owner, now) || cancellationRequestedAt != null) return false;
    status = Status.SUCCEEDED;
    completedAt = now;
    updatedAt = now;
    clearLease();
    return true;
  }

  boolean fail(
      String owner,
      Instant now,
      String errorCode,
      String redactedError,
      boolean retryable,
      Instant retryAt) {
    if (!hasValidLease(owner, now) || cancellationRequestedAt != null) return false;
    lastErrorCode = errorCode;
    lastErrorRedacted = redactedError;
    updatedAt = now;
    if (retryable && attempt < maxAttempts) {
      status = Status.QUEUED;
      nextAttemptAt = retryAt == null || retryAt.isBefore(now) ? now : retryAt;
      clearLease();
      return true;
    }
    terminalFailure(errorCode, redactedError, now);
    return true;
  }

  Status requestCancellation(Instant now) {
    if (isTerminal()) return status;
    cancellationRequestedAt = cancellationRequestedAt == null ? now : cancellationRequestedAt;
    if (status == Status.QUEUED
        || (status == Status.RUNNING && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)))
        || (status == Status.CANCEL_REQUESTED
            && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)))) {
      status = Status.CANCELLED;
      completedAt = now;
      clearLease();
    } else if (status == Status.RUNNING) {
      status = Status.CANCEL_REQUESTED;
    }
    updatedAt = now;
    return status;
  }

  boolean acknowledgeCancellation(String owner, Instant now) {
    if (status != Status.CANCEL_REQUESTED || !owner.equals(leaseOwner)) return false;
    status = Status.CANCELLED;
    completedAt = now;
    updatedAt = now;
    clearLease();
    return true;
  }

  boolean isTerminal() {
    return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
  }

  private boolean hasValidLease(String owner, Instant now) {
    return status == Status.RUNNING
        && owner != null
        && owner.equals(leaseOwner)
        && leaseExpiresAt != null
        && leaseExpiresAt.isAfter(now);
  }

  private void terminalFailure(String code, String redactedError, Instant now) {
    status = Status.FAILED;
    lastErrorCode = code;
    lastErrorRedacted = redactedError;
    completedAt = now;
    updatedAt = now;
    clearLease();
  }

  private void clearLease() {
    leaseOwner = null;
    leaseExpiresAt = null;
  }

  private static Map<String, List<String>> copyNested(Map<String, List<String>> source) {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    if (source != null) {
      source.forEach((provider, models) -> copy.put(provider, List.copyOf(models)));
    }
    return copy;
  }

  enum ClaimOutcome {
    CLAIMED,
    REJECTED,
    TERMINALIZED
  }

  /** Avoids exposing a mutable set implementation from the JSON list. */
  private static final class SetSupport {
    private SetSupport() {}

    static java.util.Set<String> copyOf(List<String> values) {
      return values == null ? java.util.Set.of() : java.util.Set.copyOf(values);
    }
  }
}
