package com.navneet.executespec.billing.service;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.FounderPricingAllocation;
import com.navneet.executespec.billing.entity.FounderPricingDiscount;
import com.navneet.executespec.billing.repository.FounderPricingAllocationRepository;
import com.navneet.executespec.billing.repository.FounderPricingDiscountRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Atomic allocation contract for founder-pricing offers (V177, GA-readiness P2.4).
 *
 * <p>Use {@link #tryAllocate} from {@code BillingService.subscribe} pre-checkout — it returns the
 * matching {@link FounderPricingDiscount} when the user is eligible, or empty when the offer is
 * sold out / the user already used it / the offer is inactive. The caller then forwards the coupon
 * ID to Stripe / Razorpay.
 *
 * <p>Concurrency: a row-level pessimistic lock on the discount row makes the "check capacity →
 * increment → record allocation" sequence atomic across replicas. Two concurrent {@link
 * #tryAllocate} calls for the same discount serialise on the lock; the second sees the incremented
 * count.
 *
 * <p>Lifetime semantics: when {@link FounderPricingDiscount#isLifetime} is TRUE, the second
 * allocation attempt by the same user returns the existing allocation row (no double-counting) so a
 * user who churns + re-subscribes inherits their original discount.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FounderPricingService {

  private final FounderPricingDiscountRepository discountRepo;
  private final FounderPricingAllocationRepository allocRepo;

  /** Result of an allocation attempt. */
  public sealed interface AllocationResult {
    /** Allocation succeeded; coupon applies. */
    record Granted(FounderPricingDiscount discount, FounderPricingAllocation allocation)
        implements AllocationResult {}

    /** No discount applies for this (user, tier) — full price. */
    record NoOffer() implements AllocationResult {}

    /** Offer sold out. */
    record SoldOut(FounderPricingDiscount discount) implements AllocationResult {}

    /** Offer inactive (manually disabled by ops or expired). */
    record Inactive(FounderPricingDiscount discount) implements AllocationResult {}
  }

  /**
   * Look up the active offer for a tier without allocating. Read-only — useful for the pricing page
   * to show "X left" or "founder pricing" badge.
   */
  @Transactional(readOnly = true)
  public Optional<FounderPricingDiscount> findActiveOfferForTier(String tier) {
    return discountRepo.findByActiveTrue().stream()
        .filter(d -> tier.equals(d.getTier()))
        .filter(FounderPricingDiscount::hasCapacity)
        .findFirst();
  }

  /**
   * Try to allocate a founder-pricing discount for the given user + tier. Atomic; safe under
   * concurrent checkout.
   *
   * <p>Returns Granted on first allocation OR on re-allocation of an existing lifetime discount.
   * Returns NoOffer when no offer exists for the tier; SoldOut when capacity is exhausted; Inactive
   * when the offer is disabled.
   */
  @Transactional
  public AllocationResult tryAllocate(String userId, String orgId, String tier) {
    Optional<FounderPricingDiscount> firstHit =
        discountRepo.findByActiveTrue().stream().filter(d -> tier.equals(d.getTier())).findFirst();

    if (firstHit.isEmpty()) {
      log.debug("FounderPricing — no offer for tier {} (user={})", tier, userId);
      return new AllocationResult.NoOffer();
    }

    UUID discountId = firstHit.get().getId();

    // Pessimistic lock on the discount row — serialises concurrent allocations.
    FounderPricingDiscount locked =
        discountRepo
            .findByIdForUpdate(discountId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Founder discount " + discountId + " disappeared mid-allocation"));

    if (!locked.isActive()) {
      log.info("FounderPricing — offer {} is inactive (user={})", locked.getName(), userId);
      return new AllocationResult.Inactive(locked);
    }

    // Lifetime path — if this user already has an allocation, return it as-is and don't
    // increment the counter (otherwise re-subscribe would consume two slots).
    Optional<FounderPricingAllocation> existing =
        allocRepo.findByDiscountIdAndUserId(discountId, userId);
    if (existing.isPresent() && existing.get().isActive()) {
      log.info(
          "FounderPricing — user {} re-subscribing on existing lifetime allocation for {}",
          userId,
          locked.getName());
      return new AllocationResult.Granted(locked, existing.get());
    }

    if (!locked.hasCapacity()) {
      log.info(
          "FounderPricing — offer {} sold out at {}/{} (user={})",
          locked.getName(),
          locked.getCurrentCount(),
          locked.getMaxSubscribers(),
          userId);
      return new AllocationResult.SoldOut(locked);
    }

    // Capacity available — atomic increment + allocation row.
    locked.setCurrentCount(locked.getCurrentCount() + 1);
    discountRepo.save(locked);

    FounderPricingAllocation alloc =
        existing.orElseGet(
            () ->
                FounderPricingAllocation.builder()
                    .discountId(discountId)
                    .userId(userId)
                    .orgId(orgId)
                    .build());
    if (existing.isPresent()) {
      // Was revoked previously; un-revoke and re-allocate.
      alloc.setRevokedAt(null);
      alloc.setRevokeReason(null);
    }
    alloc = allocRepo.save(alloc);

    log.info(
        "FounderPricing — granted offer {} to user {} ({}/{} consumed)",
        locked.getName(),
        userId,
        locked.getCurrentCount(),
        locked.getMaxSubscribers());

    return new AllocationResult.Granted(locked, alloc);
  }

  /**
   * Revoke a user's allocation. Used by ops + abuse-mitigation paths. Decrements the counter so the
   * slot is reusable.
   */
  @Transactional
  public boolean revokeAllocation(UUID discountId, String userId, String reason) {
    Optional<FounderPricingAllocation> existing =
        allocRepo.findByDiscountIdAndUserId(discountId, userId);
    if (existing.isEmpty() || !existing.get().isActive()) {
      return false;
    }
    FounderPricingAllocation alloc = existing.get();
    alloc.setRevokedAt(java.time.Instant.now());
    alloc.setRevokeReason(reason);
    allocRepo.save(alloc);

    // Decrement the discount counter under the lock so the freed slot can be re-allocated.
    discountRepo
        .findByIdForUpdate(discountId)
        .ifPresent(
            d -> {
              d.setCurrentCount(Math.max(0, d.getCurrentCount() - 1));
              discountRepo.save(d);
            });
    log.info("FounderPricing — revoked discount {} from user {}: {}", discountId, userId, reason);
    return true;
  }
}
