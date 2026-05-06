package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.FounderPricingAllocation;
import com.navneet.executespec.billing.entity.FounderPricingDiscount;
import com.navneet.executespec.billing.repository.FounderPricingAllocationRepository;
import com.navneet.executespec.billing.repository.FounderPricingDiscountRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P2.4 (GA-readiness, 2026-05-06) — pins the atomic allocation contract on {@link
 * FounderPricingService}. Concurrent-allocation safety relies on the pessimistic lock; this test
 * doesn't stress concurrency directly (that needs a DB-backed integration test) but it pins the
 * happy-path + sold-out + lifetime + revoke semantics.
 */
class FounderPricingServiceTest {

  private FounderPricingDiscountRepository discountRepo;
  private FounderPricingAllocationRepository allocRepo;
  private FounderPricingService svc;

  private FounderPricingDiscount soloOffer;
  private UUID soloId;

  @BeforeEach
  void setUp() {
    discountRepo = mock(FounderPricingDiscountRepository.class);
    allocRepo = mock(FounderPricingAllocationRepository.class);
    svc = new FounderPricingService(discountRepo, allocRepo);

    soloId = UUID.randomUUID();
    soloOffer =
        FounderPricingDiscount.builder()
            .id(soloId)
            .name("FOUNDER50_SOLO_LIFETIME")
            .tier("PRO")
            .discountPct(50)
            .maxSubscribers(100)
            .currentCount(0)
            .isLifetime(true)
            .active(true)
            .build();

    lenient().when(discountRepo.findByActiveTrue()).thenReturn(List.of(soloOffer));
    lenient().when(discountRepo.findByIdForUpdate(soloId)).thenReturn(Optional.of(soloOffer));
    // Default — no existing allocation. Specific tests override.
    lenient()
        .when(allocRepo.findByDiscountIdAndUserId(any(UUID.class), anyString()))
        .thenReturn(Optional.empty());
    lenient()
        .when(allocRepo.save(any(FounderPricingAllocation.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(discountRepo.save(any(FounderPricingDiscount.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void firstSubscriber_grantsAndIncrements() {
    var result = svc.tryAllocate("user-1", "org-1", "PRO");

    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.Granted.class);
    assertThat(soloOffer.getCurrentCount()).isEqualTo(1);

    verify(allocRepo, times(1)).save(any(FounderPricingAllocation.class));
    verify(discountRepo, times(1)).save(soloOffer);
  }

  @Test
  void hundredthSubscriber_grants_andHitsCap() {
    soloOffer.setCurrentCount(99);

    var result = svc.tryAllocate("user-100", "org-100", "PRO");

    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.Granted.class);
    assertThat(soloOffer.getCurrentCount()).isEqualTo(100);
    assertThat(soloOffer.hasCapacity()).isFalse();
  }

  @Test
  void hundredAndFirstSubscriber_soldOut() {
    soloOffer.setCurrentCount(100);

    var result = svc.tryAllocate("user-101", "org-101", "PRO");

    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.SoldOut.class);
    assertThat(soloOffer.getCurrentCount()).isEqualTo(100); // not incremented
    verify(allocRepo, never()).save(any());
  }

  @Test
  void inactiveOffer_returnsInactive() {
    soloOffer.setActive(false);
    when(discountRepo.findByActiveTrue()).thenReturn(List.of()); // findByActiveTrue filters
    var result = svc.tryAllocate("user-x", "org-x", "PRO");
    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.NoOffer.class);
  }

  @Test
  void inactiveOffer_betweenLookupAndLock_returnsInactive() {
    // Race-condition path: discountRepo.findByActiveTrue returned the offer (it WAS active when
    // we checked) but by the time we acquire the row lock another transaction has flipped it.
    soloOffer.setActive(true);
    FounderPricingDiscount nowInactive =
        FounderPricingDiscount.builder()
            .id(soloId)
            .name(soloOffer.getName())
            .tier(soloOffer.getTier())
            .discountPct(soloOffer.getDiscountPct())
            .maxSubscribers(soloOffer.getMaxSubscribers())
            .currentCount(soloOffer.getCurrentCount())
            .isLifetime(true)
            .active(false) // ← flipped by another tx
            .build();
    when(discountRepo.findByIdForUpdate(soloId)).thenReturn(Optional.of(nowInactive));

    var result = svc.tryAllocate("user-y", "org-y", "PRO");

    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.Inactive.class);
    verify(allocRepo, never()).save(any());
  }

  @Test
  void wrongTier_returnsNoOffer() {
    var result = svc.tryAllocate("user-1", "org-1", "TEAM"); // offer is for PRO
    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.NoOffer.class);
    verify(allocRepo, never()).save(any());
  }

  @Test
  void resubscribeUser_lifetime_doesNotDoubleCount() {
    // User already has an active allocation → re-subscribe must NOT consume another slot.
    soloOffer.setCurrentCount(50);
    FounderPricingAllocation existing =
        FounderPricingAllocation.builder()
            .id(UUID.randomUUID())
            .discountId(soloId)
            .userId("user-1")
            .orgId("org-1")
            .allocatedAt(java.time.Instant.now())
            .build();
    when(allocRepo.findByDiscountIdAndUserId(soloId, "user-1")).thenReturn(Optional.of(existing));

    var result = svc.tryAllocate("user-1", "org-1", "PRO");

    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.Granted.class);
    assertThat(soloOffer.getCurrentCount()).isEqualTo(50); // unchanged
    verify(discountRepo, never()).save(any()); // counter not bumped
  }

  @Test
  void resubscribeUser_revokedAllocation_unrevokes() {
    // User had an allocation that ops revoked. They re-subscribe; we re-grant from THEIR row,
    // which means we DO bump the counter (the revoke decremented it earlier).
    soloOffer.setCurrentCount(49);
    FounderPricingAllocation revoked =
        FounderPricingAllocation.builder()
            .id(UUID.randomUUID())
            .discountId(soloId)
            .userId("user-1")
            .orgId("org-1")
            .allocatedAt(java.time.Instant.now())
            .revokedAt(java.time.Instant.now())
            .revokeReason("ops")
            .build();
    when(allocRepo.findByDiscountIdAndUserId(soloId, "user-1")).thenReturn(Optional.of(revoked));

    var result = svc.tryAllocate("user-1", "org-1", "PRO");

    assertThat(result).isInstanceOf(FounderPricingService.AllocationResult.Granted.class);
    assertThat(soloOffer.getCurrentCount()).isEqualTo(50); // re-bumped
    assertThat(revoked.getRevokedAt()).isNull();
    assertThat(revoked.getRevokeReason()).isNull();
  }

  @Test
  void revokeAllocation_decrementsCounter_andSetsRevokedAt() {
    soloOffer.setCurrentCount(50);
    FounderPricingAllocation active =
        FounderPricingAllocation.builder()
            .id(UUID.randomUUID())
            .discountId(soloId)
            .userId("user-1")
            .orgId("org-1")
            .allocatedAt(java.time.Instant.now())
            .build();
    when(allocRepo.findByDiscountIdAndUserId(soloId, "user-1")).thenReturn(Optional.of(active));

    boolean revoked = svc.revokeAllocation(soloId, "user-1", "abuse");

    assertThat(revoked).isTrue();
    assertThat(active.getRevokedAt()).isNotNull();
    assertThat(active.getRevokeReason()).isEqualTo("abuse");
    assertThat(soloOffer.getCurrentCount()).isEqualTo(49);
  }

  @Test
  void revokeAllocation_alreadyRevoked_returnsFalse() {
    soloOffer.setCurrentCount(50);
    FounderPricingAllocation alreadyRevoked =
        FounderPricingAllocation.builder()
            .id(UUID.randomUUID())
            .discountId(soloId)
            .userId("user-1")
            .allocatedAt(java.time.Instant.now())
            .revokedAt(java.time.Instant.now())
            .revokeReason("ops")
            .build();
    when(allocRepo.findByDiscountIdAndUserId(soloId, "user-1"))
        .thenReturn(Optional.of(alreadyRevoked));

    boolean revoked = svc.revokeAllocation(soloId, "user-1", "abuse");

    assertThat(revoked).isFalse();
    assertThat(soloOffer.getCurrentCount()).isEqualTo(50); // unchanged
  }

  @Test
  void findActiveOfferForTier_returnsOnlyOffersWithCapacity() {
    soloOffer.setCurrentCount(0);
    Optional<FounderPricingDiscount> hit = svc.findActiveOfferForTier("PRO");
    assertThat(hit).isPresent();

    soloOffer.setCurrentCount(100);
    Optional<FounderPricingDiscount> miss = svc.findActiveOfferForTier("PRO");
    assertThat(miss).isEmpty();
  }

  @Test
  void findActiveOfferForTier_wrongTier_empty() {
    Optional<FounderPricingDiscount> miss = svc.findActiveOfferForTier("TEAM");
    assertThat(miss).isEmpty();
  }
}
