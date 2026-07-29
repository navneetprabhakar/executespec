package com.navneet.executespec.executioncontext;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One independently leased operation within a shared durable LLM policy/budget context. */
@Entity
@Table(name = "llm_execution_operation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmExecutionOperation {

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCEL_REQUESTED,
    CANCELLED
  }

  enum ClaimOutcome {
    CLAIMED,
    REJECTED,
    TERMINALIZED
  }

  @Id
  @Column(length = 64)
  private String id;

  @Column(name = "llm_execution_context_id", nullable = false, length = 64)
  private String llmExecutionContextId;

  @Column(name = "operation_key", nullable = false, length = 192)
  private String operationKey;

  @Column(name = "operation_kind", nullable = false, length = 40)
  private String operationKind;

  @Column(nullable = false)
  private int invocation;

  @Column(name = "operation_fingerprint", nullable = false, length = 64)
  private String operationFingerprint;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private Status status;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "lease_owner", length = 128)
  private String leaseOwner;

  @Column(name = "lease_token")
  private UUID leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "not_reclaim_before")
  private Instant notReclaimBefore;

  @Column(name = "next_attempt_at")
  private Instant nextAttemptAt;

  @Column(name = "cancellation_requested_at")
  private Instant cancellationRequestedAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

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

  static LlmExecutionOperation queued(
      String id,
      String contextId,
      LlmExecutionOperationDescriptor descriptor,
      int invocation,
      Instant now) {
    LlmExecutionOperation operation = new LlmExecutionOperation();
    operation.id = id;
    operation.llmExecutionContextId = contextId;
    operation.operationKey = descriptor.operationKey();
    operation.operationKind = descriptor.operationKind();
    operation.invocation = invocation;
    operation.operationFingerprint = descriptor.fingerprint();
    operation.status = Status.QUEUED;
    operation.maxAttempts = descriptor.maxAttempts();
    operation.nextAttemptAt = now;
    operation.createdAt = now;
    operation.updatedAt = now;
    return operation;
  }

  ClaimOutcome claim(String owner, Instant now, Duration leaseDuration) {
    if (isTerminal() || status == Status.CANCEL_REQUESTED) return ClaimOutcome.REJECTED;
    boolean queuedAndDue =
        status == Status.QUEUED
            && (nextAttemptAt == null || !nextAttemptAt.isAfter(now))
            && (notReclaimBefore == null || !notReclaimBefore.isAfter(now));
    boolean expiredAndSafe =
        status == Status.RUNNING
            && (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now))
            && (notReclaimBefore == null || !notReclaimBefore.isAfter(now))
            && !owner.equals(leaseOwner);
    if (!queuedAndDue && !expiredAndSafe) return ClaimOutcome.REJECTED;
    if (attempt >= maxAttempts) {
      terminalFailure("ATTEMPTS_EXHAUSTED", "No execution attempts remain", now);
      return ClaimOutcome.TERMINALIZED;
    }
    status = Status.RUNNING;
    attempt++;
    leaseOwner = owner;
    leaseToken = UUID.randomUUID();
    leaseExpiresAt = now.plus(leaseDuration);
    heartbeatAt = now;
    nextAttemptAt = null;
    if (startedAt == null) startedAt = now;
    updatedAt = now;
    return ClaimOutcome.CLAIMED;
  }

  boolean heartbeat(
      String owner, UUID token, int claimedAttempt, Instant now, Duration leaseDuration) {
    if (!hasValidLease(owner, token, claimedAttempt, now) || cancellationRequestedAt != null) {
      return false;
    }
    heartbeatAt = now;
    leaseExpiresAt = now.plus(leaseDuration);
    updatedAt = now;
    return true;
  }

  boolean protectUntil(
      String owner, UUID token, int claimedAttempt, Instant now, Instant deadline) {
    if (!hasValidLease(owner, token, claimedAttempt, now) || cancellationRequestedAt != null) {
      return false;
    }
    if (deadline == null || !deadline.isAfter(now)) return false;
    if (notReclaimBefore == null || deadline.isAfter(notReclaimBefore)) {
      notReclaimBefore = deadline;
      updatedAt = now;
    }
    return true;
  }

  /**
   * Extends both ownership and the reclaim fence before an external transport can start.
   *
   * <p>The two horizons move together to at least {@code protectedUntil}. A different worker may
   * reclaim only after both have elapsed, so a slow-but-bounded provider request cannot overlap a
   * replacement attempt. This mutation is valid only for the exact live owner/token/attempt.
   */
  boolean admitTransport(
      String owner, UUID token, int claimedAttempt, Instant now, Instant protectedUntil) {
    if (!hasValidLease(owner, token, claimedAttempt, now) || cancellationRequestedAt != null) {
      return false;
    }
    if (protectedUntil == null || !protectedUntil.isAfter(now)) return false;
    heartbeatAt = now;
    if (leaseExpiresAt == null || protectedUntil.isAfter(leaseExpiresAt)) {
      leaseExpiresAt = protectedUntil;
    }
    if (notReclaimBefore == null || protectedUntil.isAfter(notReclaimBefore)) {
      notReclaimBefore = protectedUntil;
    }
    updatedAt = now;
    return true;
  }

  boolean complete(String owner, UUID token, int claimedAttempt, Instant now) {
    if (!hasValidLease(owner, token, claimedAttempt, now) || cancellationRequestedAt != null) {
      return false;
    }
    status = Status.SUCCEEDED;
    completedAt = now;
    updatedAt = now;
    clearLease();
    return true;
  }

  boolean fail(
      String owner,
      UUID token,
      int claimedAttempt,
      Instant now,
      String errorCode,
      String redactedError,
      boolean retryable,
      Instant retryAt) {
    if (!hasValidLease(owner, token, claimedAttempt, now) || cancellationRequestedAt != null) {
      return false;
    }
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
    boolean leaseExpired = leaseExpiresAt == null || !leaseExpiresAt.isAfter(now);
    boolean externalAttemptSafe = notReclaimBefore == null || !notReclaimBefore.isAfter(now);
    if (status == Status.QUEUED
        || ((status == Status.RUNNING || status == Status.CANCEL_REQUESTED)
            && leaseExpired
            && externalAttemptSafe)) {
      status = Status.CANCELLED;
      completedAt = now;
      clearLease();
    } else if (status == Status.RUNNING) {
      status = Status.CANCEL_REQUESTED;
    }
    updatedAt = now;
    return status;
  }

  boolean acknowledgeCancellation(String owner, UUID token, int claimedAttempt, Instant now) {
    if (status != Status.CANCEL_REQUESTED
        || !owner.equals(leaseOwner)
        || !token.equals(leaseToken)
        || claimedAttempt != attempt) {
      return false;
    }
    status = Status.CANCELLED;
    completedAt = now;
    updatedAt = now;
    clearLease();
    return true;
  }

  boolean finalizeCancellationIfDue(Instant now) {
    if (status != Status.CANCEL_REQUESTED) return false;
    boolean leaseExpired = leaseExpiresAt == null || !leaseExpiresAt.isAfter(now);
    boolean externalAttemptSafe = notReclaimBefore == null || !notReclaimBefore.isAfter(now);
    if (!leaseExpired || !externalAttemptSafe) return false;
    status = Status.CANCELLED;
    completedAt = now;
    updatedAt = now;
    clearLease();
    return true;
  }

  boolean isTerminal() {
    return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
  }

  private boolean hasValidLease(String owner, UUID token, int claimedAttempt, Instant now) {
    return status == Status.RUNNING
        && owner != null
        && owner.equals(leaseOwner)
        && token != null
        && token.equals(leaseToken)
        && claimedAttempt == attempt
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
    leaseToken = null;
    leaseExpiresAt = null;
  }
}
