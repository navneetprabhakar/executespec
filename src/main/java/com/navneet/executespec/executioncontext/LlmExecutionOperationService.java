package com.navneet.executespec.executioncontext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns independently fenced operation attempts under a shared LLM policy/budget context.
 *
 * <p>This is a dormant foundation until provider-attempt admission and all production roots carry
 * {@link LlmExecutionOperationClaim}. Creating this service does not alter existing dispatch.
 */
@Service
public class LlmExecutionOperationService {

  private static final Duration MAX_LEASE = Duration.ofHours(24);
  private static final Duration MAX_RETRY_DELAY = Duration.ofHours(24);
  private static final int MAX_REDACTED_ERROR_CHARS = 4000;
  private static final List<LlmExecutionOperation.Status> NON_TERMINAL =
      List.of(
          LlmExecutionOperation.Status.QUEUED,
          LlmExecutionOperation.Status.RUNNING,
          LlmExecutionOperation.Status.CANCEL_REQUESTED);
  private static final Pattern JSON_SECRET =
      Pattern.compile(
          "(?i)([\\\"'](?:api[-_ ]?key|authorization|bearer|token|secret)[\\\"']"
              + "\\s*:\\s*[\\\"'])([^\\\"']*)([\\\"'])");
  private static final Pattern NAMED_SECRET =
      Pattern.compile(
          "(?i)((?:api[-_ ]?key|authorization|token|secret)\\s*[:=]\\s*)"
              + "(?:bearer\\s+)?[^\\s,;}]+");
  private static final Pattern BARE_BEARER =
      Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{12,}");
  private static final Pattern COMMON_KEY_SHAPE =
      Pattern.compile("(?i)(sk-[a-z0-9_-]{12,}|AIza[a-z0-9_-]{12,})");
  private static final Pattern COMMON_SERVICE_TOKEN =
      Pattern.compile("(?i)\\b(?:gh[pousr]_[a-z0-9]{12,}|xox[baprs]-[a-z0-9-]{12,})\\b");
  private static final Pattern JWT_SHAPE =
      Pattern.compile("\\beyJ[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\.[a-zA-Z0-9_-]{8,}\\b");

  private final LlmExecutionContextRepository contextRepository;
  private final LlmExecutionOperationRepository operationRepository;

  @Autowired
  public LlmExecutionOperationService(
      LlmExecutionContextRepository contextRepository,
      LlmExecutionOperationRepository operationRepository) {
    this.contextRepository = contextRepository;
    this.operationRepository = operationRepository;
  }

  /** Claims the latest nonterminal invocation or creates invocation one for a new operation key. */
  @Transactional
  public Optional<LlmExecutionOperationClaim> claimOrResume(
      LlmExecutionReference reference,
      LlmExecutionOperationDescriptor descriptor,
      String leaseOwner,
      Duration leaseDuration) {
    validate(reference, descriptor, leaseOwner, leaseDuration);
    LlmExecutionContext context = lockContext(reference);
    Optional<LlmExecutionOperation> latest =
        operationRepository.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), descriptor.operationKey());
    Instant now = contextRepository.databaseNow();
    requireActiveContext(context, now);
    LlmExecutionOperation operation =
        latest.orElseGet(
            () ->
                operationRepository.saveAndFlush(
                    LlmExecutionOperation.queued(
                        operationId(context.getId(), descriptor.operationKey(), 1),
                        context.getId(),
                        descriptor,
                        1,
                        now)));
    requireSameIdentity(operation, descriptor);
    return claim(reference, operation, leaseOwner.trim(), leaseDuration, now);
  }

  /**
   * Rehydrates an already-owned live claim only when the caller proves the complete prior
   * capability. Owner strings alone never redisclose a lease token.
   */
  @Transactional
  public Optional<LlmExecutionOperationClaim> resumeOwned(
      LlmExecutionOperationClaim claim, String leaseOwner, Duration leaseDuration) {
    validateClaimMutation(claim, leaseOwner, leaseDuration);
    LlmExecutionContext context = lockContext(claim.context());
    LlmExecutionOperation operation = lockOperation(claim, context);
    Instant now = contextRepository.databaseNow();
    requireActiveContext(context, now);
    boolean retained =
        operation.heartbeat(
            leaseOwner.trim(), claim.leaseToken(), claim.attempt(), now, leaseDuration);
    if (!retained) return Optional.empty();
    operationRepository.save(operation);
    return Optional.of(claim);
  }

  /** Starts a new explicit invocation after the previous invocation is terminal. */
  @Transactional
  public LlmExecutionOperationClaim beginNewInvocation(
      LlmExecutionReference reference,
      LlmExecutionOperationDescriptor descriptor,
      String leaseOwner,
      Duration leaseDuration) {
    validate(reference, descriptor, leaseOwner, leaseDuration);
    LlmExecutionContext context = lockContext(reference);
    Optional<LlmExecutionOperation> latest =
        operationRepository.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), descriptor.operationKey());
    Instant now = contextRepository.databaseNow();
    requireActiveContext(context, now);
    if (latest.isPresent()) {
      if (!latest.get().isTerminal()) {
        throw error(
            LlmExecutionOperationException.Reason.ACTIVE_INVOCATION_EXISTS,
            "A nonterminal invocation already exists for this operation key");
      }
      if (!latest.get().getOperationKind().equals(descriptor.operationKind())) {
        throw error(
            LlmExecutionOperationException.Reason.IDENTITY_CONFLICT,
            "Operation kind changed across explicit invocations");
      }
    }
    int invocation = latest.map(value -> value.getInvocation() + 1).orElse(1);
    LlmExecutionOperation operation =
        operationRepository.saveAndFlush(
            LlmExecutionOperation.queued(
                operationId(context.getId(), descriptor.operationKey(), invocation),
                context.getId(),
                descriptor,
                invocation,
                now));
    return claim(reference, operation, leaseOwner.trim(), leaseDuration, now)
        .orElseThrow(
            () ->
                error(
                    LlmExecutionOperationException.Reason.LEASE_NOT_OWNED,
                    "New operation invocation could not be claimed"));
  }

  /** Renews only the exact still-active owner/token/attempt tuple. */
  @Transactional
  public boolean heartbeat(
      LlmExecutionOperationClaim claim, String leaseOwner, Duration leaseDuration) {
    validateClaimMutation(claim, leaseOwner, leaseDuration);
    LlmExecutionContext context = lockContext(claim.context());
    LlmExecutionOperation operation = lockOperation(claim, context);
    Instant now = contextRepository.databaseNow();
    requireActiveContext(context, now);
    boolean changed =
        operation.heartbeat(
            leaseOwner.trim(), claim.leaseToken(), claim.attempt(), now, leaseDuration);
    if (changed) operationRepository.save(operation);
    return changed;
  }

  @Transactional
  public boolean complete(LlmExecutionOperationClaim claim, String leaseOwner) {
    requireClaimAndOwner(claim, leaseOwner);
    LlmExecutionContext context = lockContext(claim.context());
    LlmExecutionOperation operation = lockOperation(claim, context);
    Instant now = contextRepository.databaseNow();
    boolean changed =
        operation.complete(leaseOwner.trim(), claim.leaseToken(), claim.attempt(), now);
    if (changed) operationRepository.save(operation);
    return changed;
  }

  @Transactional
  public boolean fail(LlmExecutionOperationClaim claim, String leaseOwner, Failure failure) {
    requireClaimAndOwner(claim, leaseOwner);
    if (failure == null) throw new IllegalArgumentException("failure required");
    validateRetryDelay(failure.retryDelay());
    LlmExecutionContext context = lockContext(claim.context());
    LlmExecutionOperation operation = lockOperation(claim, context);
    Instant now = contextRepository.databaseNow();
    boolean changed =
        operation.fail(
            leaseOwner.trim(),
            claim.leaseToken(),
            claim.attempt(),
            now,
            normalizeErrorCode(failure.code()),
            redact(failure.redactedMessage()),
            failure.retryable(),
            retryAt(now, failure.retryDelay()));
    if (changed) operationRepository.save(operation);
    return changed;
  }

  @Transactional
  public Optional<LlmExecutionOperation.Status> requestCancellation(
      LlmExecutionReference reference, String operationKey) {
    String key = requireText(operationKey, "operationKey");
    LlmExecutionContext context = lockContext(reference);
    Optional<LlmExecutionOperation> operation =
        operationRepository.findFirstByLlmExecutionContextIdAndOperationKeyOrderByInvocationDesc(
            context.getId(), key);
    Instant now = contextRepository.databaseNow();
    if (operation.isEmpty()) return Optional.empty();
    LlmExecutionOperation.Status status = operation.get().requestCancellation(now);
    operationRepository.save(operation.get());
    return Optional.of(status);
  }

  /** Requests cancellation for every live operation under the context in a deterministic order. */
  @Transactional
  public int requestCancellationForAll(LlmExecutionReference reference) {
    LlmExecutionContext context = lockContext(reference);
    List<LlmExecutionOperation> active =
        operationRepository.findNonTerminalForUpdate(context.getId(), NON_TERMINAL);
    Instant now = contextRepository.databaseNow();
    active.forEach(operation -> operation.requestCancellation(now));
    operationRepository.saveAll(active);
    return active.size();
  }

  /**
   * Bounded recovery sweep for owners that died after cancellation was requested. Candidate reads
   * are unlocked; mutation always reacquires locks in context -> operation order.
   */
  @Transactional
  public int finalizeDueCancellations(int limit) {
    if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
    Instant now = contextRepository.databaseNow();
    var candidates =
        operationRepository.findDueCancellationCandidates(
            LlmExecutionOperation.Status.CANCEL_REQUESTED, now, PageRequest.of(0, limit));
    int completed = 0;
    for (var candidate : candidates) {
      Optional<LlmExecutionContext> context =
          contextRepository.findForUpdateById(candidate.getContextId());
      if (context.isEmpty()) continue;
      Optional<LlmExecutionOperation> operation =
          operationRepository.findForUpdate(candidate.getOperationId(), context.get().getId());
      if (operation.isPresent() && operation.get().finalizeCancellationIfDue(now)) {
        operationRepository.save(operation.get());
        completed++;
      }
    }
    return completed;
  }

  @Transactional
  public boolean acknowledgeCancellation(LlmExecutionOperationClaim claim, String leaseOwner) {
    requireClaimAndOwner(claim, leaseOwner);
    LlmExecutionContext context = lockContext(claim.context());
    LlmExecutionOperation operation = lockOperation(claim, context);
    Instant now = contextRepository.databaseNow();
    boolean changed =
        operation.acknowledgeCancellation(
            leaseOwner.trim(), claim.leaseToken(), claim.attempt(), now);
    if (changed) operationRepository.save(operation);
    return changed;
  }

  private Optional<LlmExecutionOperationClaim> claim(
      LlmExecutionReference reference,
      LlmExecutionOperation operation,
      String owner,
      Duration leaseDuration,
      Instant now) {
    LlmExecutionOperation.ClaimOutcome outcome = operation.claim(owner, now, leaseDuration);
    if (outcome == LlmExecutionOperation.ClaimOutcome.CLAIMED
        || outcome == LlmExecutionOperation.ClaimOutcome.TERMINALIZED) {
      operationRepository.save(operation);
    }
    if (outcome != LlmExecutionOperation.ClaimOutcome.CLAIMED) {
      return Optional.empty();
    }
    return Optional.of(toClaim(reference, operation));
  }

  private static void requireActiveContext(LlmExecutionContext context, Instant databaseNow) {
    boolean active =
        (context.getStatus() == LlmExecutionContext.Status.QUEUED
                || context.getStatus() == LlmExecutionContext.Status.RUNNING)
            && (context.getExpiresAt() == null || context.getExpiresAt().isAfter(databaseNow));
    if (!active) {
      throw error(
          LlmExecutionOperationException.Reason.CONTEXT_NOT_ACTIVE,
          "Execution context is terminal, cancelled, or expired");
    }
  }

  private LlmExecutionContext lockContext(LlmExecutionReference reference) {
    if (reference == null) throw new IllegalArgumentException("reference required");
    return contextRepository
        .findForUpdate(reference.contextId(), reference.scope().isolationKey())
        .orElseThrow(
            () ->
                error(
                    LlmExecutionOperationException.Reason.CONTEXT_NOT_FOUND,
                    "Execution context is missing or outside the supplied tenant"));
  }

  private LlmExecutionOperation lockOperation(
      LlmExecutionOperationClaim claim, LlmExecutionContext context) {
    return operationRepository
        .findForUpdate(claim.operationId(), context.getId())
        .orElseThrow(
            () ->
                error(
                    LlmExecutionOperationException.Reason.OPERATION_NOT_FOUND,
                    "Execution operation is missing or outside the supplied context"));
  }

  private static void requireSameIdentity(
      LlmExecutionOperation operation, LlmExecutionOperationDescriptor descriptor) {
    if (!operation.getOperationKind().equals(descriptor.operationKind())
        || !operation.getOperationFingerprint().equals(descriptor.fingerprint())
        || operation.getMaxAttempts() != descriptor.maxAttempts()) {
      throw error(
          LlmExecutionOperationException.Reason.IDENTITY_CONFLICT,
          "Operation key was reused with different immutable inputs");
    }
  }

  private static LlmExecutionOperationClaim toClaim(
      LlmExecutionReference reference, LlmExecutionOperation operation) {
    return new LlmExecutionOperationClaim(
        reference,
        operation.getId(),
        operation.getInvocation(),
        operation.getAttempt(),
        operation.getLeaseToken());
  }

  private static void validate(
      LlmExecutionReference reference,
      LlmExecutionOperationDescriptor descriptor,
      String owner,
      Duration duration) {
    if (reference == null) throw new IllegalArgumentException("reference required");
    if (descriptor == null) throw new IllegalArgumentException("descriptor required");
    validateLease(owner, duration);
  }

  private static void validateClaimMutation(
      LlmExecutionOperationClaim claim, String owner, Duration duration) {
    requireClaimAndOwner(claim, owner);
    validateLease(owner, duration);
  }

  private static void requireClaimAndOwner(LlmExecutionOperationClaim claim, String owner) {
    if (claim == null) throw new IllegalArgumentException("claim required");
    requireText(owner, "leaseOwner");
  }

  private static void validateLease(String owner, Duration duration) {
    requireText(owner, "leaseOwner");
    if (owner.trim().length() > 128) {
      throw new IllegalArgumentException("leaseOwner exceeds 128 characters");
    }
    if (duration == null
        || duration.isZero()
        || duration.isNegative()
        || duration.compareTo(MAX_LEASE) > 0) {
      throw new IllegalArgumentException("leaseDuration must be positive and at most 24 hours");
    }
  }

  private static void validateRetryDelay(Duration retryDelay) {
    if (retryDelay == null) return;
    if (retryDelay.isNegative() || retryDelay.compareTo(MAX_RETRY_DELAY) > 0) {
      throw new IllegalArgumentException("retryDelay must be non-negative and at most 24 hours");
    }
  }

  private static Instant retryAt(Instant databaseNow, Duration retryDelay) {
    return retryDelay == null ? null : databaseNow.plus(retryDelay);
  }

  private static String operationId(String contextId, String key, int invocation) {
    return "llmop_" + sha256(contextId + "|" + key + "|" + invocation).substring(0, 32);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String normalizeErrorCode(String value) {
    String code = value == null || value.isBlank() ? "UNCLASSIFIED" : value.trim();
    code = code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
    return code.length() <= 64 ? code : code.substring(0, 64);
  }

  static String redact(String value) {
    String message = value == null || value.isBlank() ? "No operation error detail" : value.trim();
    message = JSON_SECRET.matcher(message).replaceAll("$1[REDACTED]$3");
    message = NAMED_SECRET.matcher(message).replaceAll("$1[REDACTED]");
    message = BARE_BEARER.matcher(message).replaceAll("Bearer [REDACTED]");
    message = COMMON_KEY_SHAPE.matcher(message).replaceAll("[REDACTED]");
    message = COMMON_SERVICE_TOKEN.matcher(message).replaceAll("[REDACTED]");
    message = JWT_SHAPE.matcher(message).replaceAll("[REDACTED]");
    return message.length() <= MAX_REDACTED_ERROR_CHARS
        ? message
        : message.substring(0, MAX_REDACTED_ERROR_CHARS);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    return value.trim();
  }

  private static LlmExecutionOperationException error(
      LlmExecutionOperationException.Reason reason, String message) {
    return new LlmExecutionOperationException(reason, message);
  }

  public record Failure(
      String code, String redactedMessage, boolean retryable, Duration retryDelay) {}
}
