package com.navneet.executespec.executioncontext;

import java.time.Duration;
import java.time.Instant;

import com.navneet.executespec.cost.LlmUsageAttemptIntent;
import com.navneet.executespec.cost.LlmUsageAttemptIntentRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic, provider-inert pre-transport admission for an exact operation attempt.
 *
 * <p>Lock order is always context then operation. The same transaction validates the process-local
 * authority, reserves aggregate budget, extends both durable safety horizons and flushes the
 * immutable version-1 physical-attempt intent. Returning from this method is the only point at
 * which a future transport integration may start network I/O.
 */
@Service
public class LlmExecutionOperationAdmissionService {

  private static final Duration MAX_TRANSPORT_HORIZON = Duration.ofHours(24);

  private final LlmExecutionContextRepository contextRepository;
  private final LlmExecutionOperationRepository operationRepository;
  private final LlmExecutionBudgetGate budgetGate;
  private final LlmUsageAttemptIntentRepository intentRepository;

  @Autowired
  public LlmExecutionOperationAdmissionService(
      LlmExecutionContextRepository contextRepository,
      LlmExecutionOperationRepository operationRepository,
      LlmExecutionBudgetGate budgetGate,
      LlmUsageAttemptIntentRepository intentRepository) {
    this.contextRepository = contextRepository;
    this.operationRepository = operationRepository;
    this.budgetGate = budgetGate;
    this.intentRepository = intentRepository;
  }

  /**
   * Atomically admits one physical provider attempt without invoking a provider.
   *
   * @param reference exact durable context and tenant boundary
   * @param authority process-local claim plus owner; never serialized or logged
   * @param intent unfenced immutable intent body; all six V351 fence fields are set here
   * @param requestedMaxOutputTokens provider-enforced output cap used for honest reservation
   * @param transportDeadline absolute deadline after which the provider transport must be stopped
   * @param safetyMargin additional no-reclaim time for timeout/connection teardown
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public LlmUsageAttemptIntent admit(
      LlmExecutionReference reference,
      LlmExecutionOperationAuthority authority,
      LlmUsageAttemptIntent intent,
      Integer requestedMaxOutputTokens,
      Instant transportDeadline,
      Duration safetyMargin) {
    validateCommonInput(reference, authority, intent, safetyMargin);
    if (transportDeadline == null) {
      throw new IllegalArgumentException("transportDeadline required");
    }
    return admitLocked(
            reference,
            authority,
            intent,
            requestedMaxOutputTokens,
            transportDeadline,
            null,
            safetyMargin)
        .intent();
  }

  /**
   * Atomically admits one physical provider attempt using a relative transport timeout.
   *
   * <p>The absolute deadline is derived only after both durable rows are locked and from the same
   * database-time sample used to validate the context and operation. This avoids caller-clock skew
   * between lease validation, the persisted transport deadline, and the no-reclaim horizon.
   *
   * @param transportTimeout positive provider transport timeout
   * @return the admitted intent together with the database-derived absolute transport deadline
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public AdmittedAttempt admit(
      LlmExecutionReference reference,
      LlmExecutionOperationAuthority authority,
      LlmUsageAttemptIntent intent,
      Integer requestedMaxOutputTokens,
      Duration transportTimeout,
      Duration safetyMargin) {
    validateCommonInput(reference, authority, intent, safetyMargin);
    validateTransportTimeout(transportTimeout, safetyMargin);
    return admitLocked(
        reference,
        authority,
        intent,
        requestedMaxOutputTokens,
        null,
        transportTimeout,
        safetyMargin);
  }

  private AdmittedAttempt admitLocked(
      LlmExecutionReference reference,
      LlmExecutionOperationAuthority authority,
      LlmUsageAttemptIntent intent,
      Integer requestedMaxOutputTokens,
      Instant requestedTransportDeadline,
      Duration transportTimeout,
      Duration safetyMargin) {
    LlmExecutionOperationClaim claim = authority.claim();
    if (!claim.context().equals(reference)) {
      throw lease("Operation authority belongs to a different execution context");
    }

    // Global lock order: aggregate context first, independently leased operation second.
    LlmExecutionContext context =
        contextRepository
            .findForUpdate(reference.contextId(), reference.scope().isolationKey())
            .orElseThrow(
                () ->
                    new LlmExecutionOperationException(
                        LlmExecutionOperationException.Reason.CONTEXT_NOT_FOUND,
                        "Execution context is missing or outside the supplied tenant"));
    LlmExecutionOperation operation =
        operationRepository
            .findForUpdate(claim.operationId(), context.getId())
            .orElseThrow(
                () ->
                    new LlmExecutionOperationException(
                        LlmExecutionOperationException.Reason.OPERATION_NOT_FOUND,
                        "Execution operation is missing or outside the supplied context"));
    Instant databaseNow = contextRepository.databaseNow();
    Instant transportDeadline =
        transportTimeout == null
            ? requestedTransportDeadline
            : deadlineFrom(databaseNow, transportTimeout);
    Instant protectedUntil = protectedUntil(databaseNow, transportDeadline, safetyMargin);

    requireActiveContext(context, databaseNow);
    requireExactClaim(operation, claim, authority.leaseOwner(), databaseNow);
    budgetGate.reserveLockedContext(context, requestedMaxOutputTokens);
    if (!operation.admitTransport(
        authority.leaseOwner(), claim.leaseToken(), claim.attempt(), databaseNow, protectedUntil)) {
      throw lease("Execution operation lease was cancelled, expired, or replaced");
    }
    // Hibernate orders entity inserts before dirty updates. Flush the context reservation and
    // operation horizons now so the intent's database trigger observes the admitted generation.
    // This is still one transaction: any later intent failure rolls both updates back.
    operationRepository.saveAndFlush(operation);

    // Caller-provided fence material is never trusted. Admission owns all six V351 columns.
    intent.setStartedAt(databaseNow);
    intent.setOperationFenceVersion((short) 1);
    intent.setLlmExecutionContextId(context.getId());
    intent.setLlmExecutionOperationId(operation.getId());
    intent.setLlmExecutionOperationAttempt(operation.getAttempt());
    intent.setLlmExecutionLeaseToken(operation.getLeaseToken());
    intent.setOperationNotReclaimBefore(operation.getNotReclaimBefore());
    intent.setTransportDeadlineAt(transportDeadline);
    return new AdmittedAttempt(intentRepository.saveAndFlush(intent), transportDeadline);
  }

  private static void requireActiveContext(LlmExecutionContext context, Instant now) {
    boolean active =
        (context.getStatus() == LlmExecutionContext.Status.QUEUED
                || context.getStatus() == LlmExecutionContext.Status.RUNNING)
            && context.getCancellationRequestedAt() == null
            && (context.getExpiresAt() == null || context.getExpiresAt().isAfter(now));
    if (!active) {
      throw new LlmExecutionOperationException(
          LlmExecutionOperationException.Reason.CONTEXT_NOT_ACTIVE,
          "Execution context is terminal, cancelled, or expired");
    }
  }

  private static void requireExactClaim(
      LlmExecutionOperation operation,
      LlmExecutionOperationClaim claim,
      String owner,
      Instant now) {
    boolean exact =
        operation.getStatus() == LlmExecutionOperation.Status.RUNNING
            && operation.getInvocation() == claim.invocation()
            && operation.getAttempt() == claim.attempt()
            && owner.equals(operation.getLeaseOwner())
            && claim.leaseToken().equals(operation.getLeaseToken())
            && operation.getCancellationRequestedAt() == null
            && operation.getLeaseExpiresAt() != null
            && operation.getLeaseExpiresAt().isAfter(now);
    if (!exact) throw lease("Execution operation authority is stale or no longer owned");
  }

  private static Instant protectedUntil(
      Instant now, Instant transportDeadline, Duration safetyMargin) {
    if (!transportDeadline.isAfter(now)) {
      throw new IllegalArgumentException("transportDeadline must be in the future");
    }
    Duration untilDeadline = Duration.between(now, transportDeadline);
    if (untilDeadline.compareTo(MAX_TRANSPORT_HORIZON) > 0
        || safetyMargin.compareTo(MAX_TRANSPORT_HORIZON) > 0) {
      throw new IllegalArgumentException("transport horizon exceeds 24 hours");
    }
    Instant protectedUntil;
    try {
      protectedUntil = transportDeadline.plus(safetyMargin);
    } catch (RuntimeException overflow) {
      throw new IllegalArgumentException("transport horizon is invalid", overflow);
    }
    if (Duration.between(now, protectedUntil).compareTo(MAX_TRANSPORT_HORIZON) > 0) {
      throw new IllegalArgumentException("transport deadline plus safety exceeds 24 hours");
    }
    return protectedUntil;
  }

  private static Instant deadlineFrom(Instant databaseNow, Duration transportTimeout) {
    try {
      return databaseNow.plus(transportTimeout);
    } catch (RuntimeException overflow) {
      throw new IllegalArgumentException("transport timeout is invalid", overflow);
    }
  }

  private static void validateTransportTimeout(Duration transportTimeout, Duration safetyMargin) {
    if (transportTimeout == null || transportTimeout.isZero() || transportTimeout.isNegative()) {
      throw new IllegalArgumentException("positive transportTimeout required");
    }
    Duration combined;
    try {
      combined = transportTimeout.plus(safetyMargin);
    } catch (RuntimeException overflow) {
      throw new IllegalArgumentException("transport timeout plus safety is invalid", overflow);
    }
    if (transportTimeout.compareTo(MAX_TRANSPORT_HORIZON) > 0
        || combined.compareTo(MAX_TRANSPORT_HORIZON) > 0) {
      throw new IllegalArgumentException("transport timeout plus safety exceeds 24 hours");
    }
  }

  private static void validateCommonInput(
      LlmExecutionReference reference,
      LlmExecutionOperationAuthority authority,
      LlmUsageAttemptIntent intent,
      Duration safetyMargin) {
    if (reference == null) throw new IllegalArgumentException("reference required");
    if (authority == null) throw new IllegalArgumentException("authority required");
    if (intent == null) throw new IllegalArgumentException("intent required");
    if (safetyMargin == null || safetyMargin.isNegative()) {
      throw new IllegalArgumentException("non-negative safetyMargin required");
    }
  }

  private static LlmExecutionOperationException lease(String message) {
    return new LlmExecutionOperationException(
        LlmExecutionOperationException.Reason.LEASE_NOT_OWNED, message);
  }

  /** Durable admission result required by a future physical transport integration. */
  public record AdmittedAttempt(LlmUsageAttemptIntent intent, Instant transportDeadline) {
    public AdmittedAttempt {
      if (intent == null) throw new IllegalArgumentException("intent required");
      if (transportDeadline == null) {
        throw new IllegalArgumentException("transportDeadline required");
      }
    }
  }
}
