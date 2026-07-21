package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingGatewayObligation;
import com.navneet.executespec.billing.repository.BillingGatewayObligationRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Commits, then settles, the irreversible payment-gateway actions ExecuteSpec owes customers (V310;
 * RR-048 / RR-049 / RR-352 / RR-353).
 *
 * <p>The invariant every caller must preserve:
 *
 * <ol>
 *   <li>{@link #record} the obligation in its OWN committed transaction, before the gateway call.
 *   <li>Call the gateway <b>outside</b> any surrounding transaction.
 *   <li>{@link #markSucceeded} / {@link #markFailed} / {@link #markUnsupported} in another short
 *       committed transaction.
 * </ol>
 *
 * <p>{@code REQUIRES_NEW} on every method is what makes that true even when the caller is inside a
 * broad {@code @Transactional} service such as {@code BillingService}: if the caller's transaction
 * later rolls back, the obligation survives, which is the entire point. Losing the record of a
 * refund we owe — or of a gateway subscription still billing the customer — is the failure mode
 * being fixed.
 *
 * <p>{@link #record} is idempotent on {@code idempotencyKey}. A retried upgrade or a re-run sunset
 * cascade reuses the existing obligation instead of issuing a second refund or cancellation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingGatewayObligationService {

  /**
   * The number of failed attempts after which an obligation is considered dead-lettered: the
   * drainer stops retrying it and the admin surface reports it as needing an operator. Shared so
   * the retry loop and the visibility summary agree on the threshold.
   */
  public static final int MAX_ATTEMPTS = 8;

  private final BillingGatewayObligationRepository repo;

  /**
   * Persist (or find) the obligation for an intended gateway action.
   *
   * @return the durable obligation. When it is already {@link BillingGatewayObligation#isConfirmed}
   *     the caller must NOT repeat the gateway action.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BillingGatewayObligation record(BillingGatewayObligation obligation) {
    String key = obligation.getIdempotencyKey();
    var existing = repo.findByIdempotencyKey(key);
    if (existing.isPresent()) {
      log.info(
          "Reusing existing gateway obligation kind={} key={} status={}",
          existing.get().getKind(),
          key,
          existing.get().getStatus());
      return existing.get();
    }
    try {
      return repo.save(obligation);
    } catch (DataIntegrityViolationException race) {
      // A concurrent caller inserted the same key first; the unique index did its job.
      return repo.findByIdempotencyKey(key).orElseThrow(() -> race);
    }
  }

  /** The gateway confirmed the action. {@code externalRef} is its identifier for it. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markSucceeded(UUID obligationId, String externalRef) {
    repo.findById(obligationId)
        .ifPresent(
            o -> {
              o.setStatus(BillingGatewayObligation.STATUS_SUCCEEDED);
              o.setExternalRef(externalRef);
              o.setAttempts(o.getAttempts() + 1);
              o.setLastError(null);
              o.setResolvedAt(Instant.now());
              o.setUpdatedAt(Instant.now());
              repo.save(o);
            });
  }

  /** The gateway call failed. The obligation stays outstanding and retryable. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(UUID obligationId, String error) {
    repo.findById(obligationId)
        .ifPresent(
            o -> {
              o.setStatus(BillingGatewayObligation.STATUS_FAILED);
              o.setAttempts(o.getAttempts() + 1);
              o.setLastError(truncate(error));
              o.setUpdatedAt(Instant.now());
              repo.save(o);
            });
  }

  /**
   * ExecuteSpec has no automated path to perform this action on this gateway — an operator must
   * settle it by hand. Deliberately distinct from success: nothing customer-facing may claim the
   * action happened.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markUnsupported(UUID obligationId, String detail) {
    repo.findById(obligationId)
        .ifPresent(
            o -> {
              o.setStatus(BillingGatewayObligation.STATUS_UNSUPPORTED);
              o.setAttempts(o.getAttempts() + 1);
              o.setDetail(truncate(detail));
              o.setUpdatedAt(Instant.now());
              repo.save(o);
            });
  }

  @Transactional(readOnly = true)
  public List<BillingGatewayObligation> unresolved() {
    return repo.findUnresolved();
  }

  @Transactional(readOnly = true)
  public List<BillingGatewayObligation> unresolvedByKind(String kind) {
    return repo.findUnresolvedByKind(kind);
  }

  /**
   * Retryable obligations the drainer may attempt this pass (PENDING/FAILED under the attempt cap).
   * {@code UNSUPPORTED} rows are excluded — they have no automated gateway path.
   */
  @Transactional(readOnly = true)
  public List<BillingGatewayObligation> retryable() {
    return repo.findRetryable(MAX_ATTEMPTS);
  }

  /**
   * A visibility roll-up of every unresolved obligation, grouped by kind, so the admin surface can
   * show "N pending, M failed, K dead-lettered, U needs-operator" per kind instead of the failure
   * being silent. {@code deadLettered} = FAILED rows that have exhausted {@link #MAX_ATTEMPTS};
   * {@code unsupported} = actions with no automated gateway path. Both require an operator.
   */
  @Transactional(readOnly = true)
  public UnresolvedSummary unresolvedSummary() {
    var byKind =
        new java.util.LinkedHashMap<
            String, long[]>(); // [pending, failedRetrying, dead, unsupported]
    for (BillingGatewayObligation o : repo.findUnresolved()) {
      long[] counts = byKind.computeIfAbsent(o.getKind(), k -> new long[4]);
      switch (o.getStatus()) {
        case BillingGatewayObligation.STATUS_PENDING -> counts[0]++;
        case BillingGatewayObligation.STATUS_FAILED -> {
          if (o.getAttempts() >= MAX_ATTEMPTS) counts[2]++;
          else counts[1]++;
        }
        case BillingGatewayObligation.STATUS_UNSUPPORTED -> counts[3]++;
        default -> {
          // SUCCEEDED never appears in findUnresolved(); ignore any unexpected status defensively.
        }
      }
    }
    var kinds = new java.util.ArrayList<KindCounts>(byKind.size());
    long pending = 0;
    long failedRetrying = 0;
    long deadLettered = 0;
    long unsupported = 0;
    for (var entry : byKind.entrySet()) {
      long[] c = entry.getValue();
      kinds.add(new KindCounts(entry.getKey(), c[0], c[1], c[2], c[3]));
      pending += c[0];
      failedRetrying += c[1];
      deadLettered += c[2];
      unsupported += c[3];
    }
    long total = pending + failedRetrying + deadLettered + unsupported;
    return new UnresolvedSummary(
        total, pending, failedRetrying, deadLettered, unsupported, List.copyOf(kinds));
  }

  /** Per-kind unresolved-obligation counts for the admin visibility surface. */
  public record KindCounts(
      String kind, long pending, long failedRetrying, long deadLettered, long unsupported) {}

  /** Top-level unresolved-obligation roll-up plus the per-kind breakdown. */
  public record UnresolvedSummary(
      long total,
      long pending,
      long failedRetrying,
      long deadLettered,
      long unsupported,
      List<KindCounts> byKind) {}

  /** Deterministic dedupe key. Same inputs ⇒ same obligation ⇒ never a second gateway action. */
  public static String key(String kind, Object... parts) {
    StringBuilder sb = new StringBuilder(kind);
    for (Object part : parts) {
      sb.append(':').append(part == null ? "-" : part);
    }
    String key = sb.toString();
    return key.length() <= 255 ? key : key.substring(0, 255);
  }

  private static String truncate(String value) {
    if (value == null) return null;
    return value.length() <= 2000 ? value : value.substring(0, 2000);
  }
}
