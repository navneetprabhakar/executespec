package com.navneet.executespec.executioncontext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates and owns every tenant-scoped lifecycle transition for durable LLM contexts. */
@Service
public class LlmExecutionContextService {

  private static final Duration MAX_LEASE = Duration.ofHours(24);
  private static final int MAX_REDACTED_ERROR_CHARS = 4000;
  private static final Pattern NAMED_SECRET =
      Pattern.compile("(?i)(api[-_ ]?key|authorization|bearer|token|secret)\\s*[:=]\\s*\\S+");
  private static final Pattern COMMON_KEY_SHAPE =
      Pattern.compile("(?i)(sk-[a-z0-9_-]{12,}|AIza[a-z0-9_-]{12,})");

  private final LlmExecutionContextRepository repository;
  private final Clock clock;

  @Autowired
  public LlmExecutionContextService(LlmExecutionContextRepository repository) {
    this(repository, Clock.systemUTC());
  }

  LlmExecutionContextService(LlmExecutionContextRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * Creates a queued context or returns the original row for an exact idempotent retry. Reusing the
   * same key with different scope, policy, budget, or source data fails closed.
   */
  public LlmExecutionContext create(LlmExecutionContextCreateRequest request) {
    String fingerprint = fingerprint(request);
    Optional<LlmExecutionContext> existing = findIdempotent(request);
    if (existing.isPresent()) return requireMatchingFingerprint(existing.get(), fingerprint);

    String id = deterministicId(request);
    LlmExecutionContext context =
        LlmExecutionContext.queued(id, fingerprint, request, clock.instant());
    try {
      return repository.saveAndFlush(context);
    } catch (DataIntegrityViolationException race) {
      return findIdempotent(request)
          .map(winner -> requireMatchingFingerprint(winner, fingerprint))
          .orElseThrow(() -> race);
    }
  }

  /**
   * Recovers the immutable context created for an idempotent operation without rebuilding its
   * policy or budget from mutable source rows. This is the only safe recovery path after a run has
   * persisted derived values such as its estimated hard cost cap.
   */
  @Transactional(readOnly = true)
  public Optional<LlmExecutionReference> findReference(
      LlmExecutionScope scope,
      String feature,
      String sourceType,
      String sourceId,
      String idempotencyKey) {
    if (scope == null) throw new IllegalArgumentException("scope required");
    return repository
        .findByIsolationKeyAndFeatureAndSourceTypeAndSourceIdAndIdempotencyKey(
            scope.isolationKey(),
            requireText(feature, "feature"),
            requireText(sourceType, "sourceType"),
            requireText(sourceId, "sourceId"),
            requireText(idempotencyKey, "idempotencyKey"))
        .map(context -> new LlmExecutionReference(context.getId(), scope));
  }

  /** Tenant-scoped lookup. A different org receives the same empty result as an unknown id. */
  @Transactional(readOnly = true)
  public Optional<LlmExecutionContext> load(LlmExecutionScope scope, String contextId) {
    requireScopeAndId(scope, contextId);
    return repository.findByIdAndIsolationKey(contextId, scope.isolationKey());
  }

  @Transactional(readOnly = true)
  public LlmExecutionContext require(LlmExecutionScope scope, String contextId) {
    return load(scope, contextId).orElseThrow(LlmExecutionContextException::notFound);
  }

  /**
   * Claims a due queued context or steals an expired RUNNING lease. An active lease, cancellation,
   * tenant mismatch, exhausted attempt budget, or expired context returns empty.
   */
  @Transactional
  public Optional<LlmExecutionContext> claim(
      LlmExecutionScope scope, String contextId, String leaseOwner, Duration leaseDuration) {
    validateLease(leaseOwner, leaseDuration);
    Optional<LlmExecutionContext> found = locked(scope, contextId);
    if (found.isEmpty()) return Optional.empty();
    LlmExecutionContext context = found.get();
    LlmExecutionContext.ClaimOutcome outcome =
        context.claim(leaseOwner.trim(), clock.instant(), leaseDuration);
    if (outcome != LlmExecutionContext.ClaimOutcome.REJECTED) repository.save(context);
    return outcome == LlmExecutionContext.ClaimOutcome.CLAIMED
        ? Optional.of(context)
        : Optional.empty();
  }

  /** Renews only the caller's still-active lease; an expired or stolen owner is fenced out. */
  @Transactional
  public boolean heartbeat(
      LlmExecutionScope scope, String contextId, String leaseOwner, Duration leaseDuration) {
    validateLease(leaseOwner, leaseDuration);
    Optional<LlmExecutionContext> found = locked(scope, contextId);
    if (found.isEmpty()) return false;
    LlmExecutionContext context = found.get();
    if (!context.heartbeat(leaseOwner.trim(), clock.instant(), leaseDuration)) return false;
    repository.save(context);
    return true;
  }

  /** Completes only the caller's still-active lease. */
  @Transactional
  public boolean complete(LlmExecutionScope scope, String contextId, String leaseOwner) {
    String owner = requireText(leaseOwner, "leaseOwner");
    Optional<LlmExecutionContext> found = locked(scope, contextId);
    if (found.isEmpty()) return false;
    LlmExecutionContext context = found.get();
    if (!context.complete(owner, clock.instant())) return false;
    repository.save(context);
    return true;
  }

  /**
   * Records a redacted failure. Retryable failures requeue only while an attempt remains; otherwise
   * the context becomes terminal FAILED.
   */
  @Transactional
  public boolean fail(
      LlmExecutionScope scope, String contextId, String leaseOwner, Failure failure) {
    String owner = requireText(leaseOwner, "leaseOwner");
    if (failure == null) throw new IllegalArgumentException("failure required");
    Optional<LlmExecutionContext> found = locked(scope, contextId);
    if (found.isEmpty()) return false;
    LlmExecutionContext context = found.get();
    boolean changed =
        context.fail(
            owner,
            clock.instant(),
            normalizeErrorCode(failure.code()),
            redact(failure.redactedMessage()),
            failure.retryable(),
            failure.retryAt());
    if (!changed) return false;
    repository.save(context);
    return true;
  }

  /**
   * QUEUED or expired work cancels immediately. Active work moves to CANCEL_REQUESTED and must be
   * acknowledged by its fenced lease owner; a later repeated request finalizes an expired owner.
   */
  @Transactional
  public Optional<LlmExecutionContext.Status> requestCancellation(
      LlmExecutionScope scope, String contextId) {
    Optional<LlmExecutionContext> found = locked(scope, contextId);
    if (found.isEmpty()) return Optional.empty();
    LlmExecutionContext context = found.get();
    LlmExecutionContext.Status before = context.getStatus();
    LlmExecutionContext.Status after = context.requestCancellation(clock.instant());
    if (before != after || context.getCancellationRequestedAt() != null) repository.save(context);
    return Optional.of(after);
  }

  @Transactional
  public boolean acknowledgeCancellation(
      LlmExecutionScope scope, String contextId, String leaseOwner) {
    String owner = requireText(leaseOwner, "leaseOwner");
    Optional<LlmExecutionContext> found = locked(scope, contextId);
    if (found.isEmpty()) return false;
    LlmExecutionContext context = found.get();
    if (!context.acknowledgeCancellation(owner, clock.instant())) return false;
    repository.save(context);
    return true;
  }

  private Optional<LlmExecutionContext> locked(LlmExecutionScope scope, String contextId) {
    requireScopeAndId(scope, contextId);
    return repository.findForUpdate(contextId, scope.isolationKey());
  }

  private Optional<LlmExecutionContext> findIdempotent(LlmExecutionContextCreateRequest request) {
    return repository.findByIsolationKeyAndFeatureAndSourceTypeAndSourceIdAndIdempotencyKey(
        request.scope().isolationKey(),
        request.feature(),
        request.sourceType(),
        request.sourceId(),
        request.idempotencyKey());
  }

  private static LlmExecutionContext requireMatchingFingerprint(
      LlmExecutionContext existing, String fingerprint) {
    if (!fingerprint.equals(existing.getRequestFingerprint())) {
      throw LlmExecutionContextException.idempotencyConflict();
    }
    return existing;
  }

  private static String deterministicId(LlmExecutionContextCreateRequest request) {
    String unique =
        String.join(
            "|",
            request.scope().isolationKey(),
            request.feature(),
            request.sourceType(),
            request.sourceId(),
            request.idempotencyKey());
    return "llmctx_" + sha256(unique).substring(0, 32);
  }

  private static String fingerprint(LlmExecutionContextCreateRequest request) {
    String canonical =
        String.join(
            "|",
            request.contextType().name(),
            request.feature(),
            request.sourceType(),
            request.sourceId(),
            String.valueOf(request.parentContextId()),
            request.correlationId(),
            request.scope().toString(),
            String.valueOf(request.userId()),
            String.valueOf(request.projectId()),
            String.valueOf(request.repoId()),
            request.policy().canonicalForm(),
            request.budget().toString(),
            request.maxAttempts().toString(),
            request.idempotencyKey(),
            String.valueOf(request.expiresAt()));
    return sha256(canonical);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void validateLease(String leaseOwner, Duration leaseDuration) {
    requireText(leaseOwner, "leaseOwner");
    if (leaseDuration == null
        || leaseDuration.isZero()
        || leaseDuration.isNegative()
        || leaseDuration.compareTo(MAX_LEASE) > 0) {
      throw new IllegalArgumentException("leaseDuration must be positive and at most 24 hours");
    }
  }

  private static void requireScopeAndId(LlmExecutionScope scope, String contextId) {
    if (scope == null) throw new IllegalArgumentException("scope required");
    requireText(contextId, "contextId");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    return value.trim();
  }

  private static String normalizeErrorCode(String value) {
    String code = value == null || value.isBlank() ? "UNCLASSIFIED" : value.trim();
    code = code.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_.-]", "_");
    return code.length() <= 64 ? code : code.substring(0, 64);
  }

  private static String redact(String value) {
    String message = value == null || value.isBlank() ? "No provider error detail" : value.trim();
    message = NAMED_SECRET.matcher(message).replaceAll("$1=[REDACTED]");
    message = COMMON_KEY_SHAPE.matcher(message).replaceAll("[REDACTED]");
    return message.length() <= MAX_REDACTED_ERROR_CHARS
        ? message
        : message.substring(0, MAX_REDACTED_ERROR_CHARS);
  }

  public record Failure(String code, String redactedMessage, boolean retryable, Instant retryAt) {}
}
