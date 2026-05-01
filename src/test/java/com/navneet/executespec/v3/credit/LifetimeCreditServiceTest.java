package com.navneet.executespec.v3.credit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LifetimeCreditServiceTest {

  private AppUserRepository repo;
  private LifetimeCreditService svc;

  @BeforeEach
  void setUp() {
    repo = mock(AppUserRepository.class);
    when(repo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    svc = new LifetimeCreditService(repo);
  }

  private AppUser freeUser(int balance) {
    AppUser u =
        AppUser.builder()
            .id("u1")
            .email("u1@test")
            .firstName("Test")
            .tier("FREE")
            .lifetimeRunCreditBalance(balance)
            .lifetimeRunCreditCap(25)
            .build();
    when(repo.findById("u1")).thenReturn(Optional.of(u));
    return u;
  }

  private AppUser paidUser() {
    AppUser u =
        AppUser.builder()
            .id("u2")
            .email("u2@test")
            .firstName("Test")
            .tier("PRO")
            .lifetimeRunCreditBalance(0)
            .lifetimeRunCreditCap(25)
            .build();
    when(repo.findById("u2")).thenReturn(Optional.of(u));
    return u;
  }

  @Test
  void free_user_starts_with_baseline_balance() {
    freeUser(5);
    assertEquals(5, svc.currentBalance("u1"));
    assertTrue(svc.canSubmit("u1"));
  }

  @Test
  void decrement_reduces_balance_by_one() {
    AppUser u = freeUser(5);
    int after = svc.decrementForRun("u1");
    assertEquals(4, after);
    assertEquals(4, u.getLifetimeRunCreditBalance());
  }

  @Test
  void decrement_throws_when_balance_zero() {
    freeUser(0);
    assertThrows(
        LifetimeCreditService.InsufficientCreditException.class, () -> svc.decrementForRun("u1"));
  }

  @Test
  void decrement_no_op_for_paid_tier() {
    AppUser u = paidUser();
    int after = svc.decrementForRun("u2");
    assertEquals(Integer.MAX_VALUE, after);
    assertEquals(0, u.getLifetimeRunCreditBalance(), "paid tier balance should not change");
    verify(repo, never()).save(any());
  }

  @Test
  void can_submit_returns_true_for_paid_even_at_zero_balance() {
    paidUser();
    assertTrue(svc.canSubmit("u2"));
    assertEquals(Integer.MAX_VALUE, svc.currentBalance("u2"));
  }

  @Test
  void grant_invite_credit_adds_five() {
    AppUser u = freeUser(0);
    int after = svc.grantInviteCredit("u1");
    assertEquals(5, after);
    assertEquals(5, u.getLifetimeRunCreditBalance());
  }

  @Test
  void grant_invite_credit_caps_at_lifetime_cap() {
    AppUser u = freeUser(23);
    int after = svc.grantInviteCredit("u1");
    assertEquals(25, after, "cap must hold even when grant would overflow");
    assertEquals(25, u.getLifetimeRunCreditBalance());
  }

  @Test
  void refund_caps_at_lifetime_cap() {
    AppUser u = freeUser(25);
    int after = svc.refund("u1");
    assertEquals(25, after);
    assertEquals(25, u.getLifetimeRunCreditBalance(), "refund must not push over cap");
  }

  @Test
  void refund_no_op_for_paid_tier() {
    paidUser();
    assertEquals(Integer.MAX_VALUE, svc.refund("u2"));
    verify(repo, never()).save(any());
  }

  @Test
  void unknown_user_treated_as_zero_balance() {
    when(repo.findById("missing")).thenReturn(Optional.empty());
    assertEquals(0, svc.currentBalance("missing"));
    assertFalse(svc.canSubmit("missing"));
  }
}
