package com.navneet.executespec.referral;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.credit.LifetimeCreditService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferralServiceTest {

  private AppUserRepository userRepo;
  private ReferralInviteRepository inviteRepo;
  private LifetimeCreditService credit;
  private MailService mail;
  private ReferralService svc;

  /**
   * In-memory shim so the mock repo behaves like a real table for the assertions that follow a save
   * → find sequence.
   */
  private final Map<String, ReferralInvite> store = new HashMap<>();

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    inviteRepo = mock(ReferralInviteRepository.class);
    credit = mock(LifetimeCreditService.class);
    mail = mock(MailService.class);
    svc = new ReferralService(userRepo, inviteRepo, credit, mail);

    store.clear();
    when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    when(inviteRepo.save(any(ReferralInvite.class)))
        .thenAnswer(
            inv -> {
              ReferralInvite r = inv.getArgument(0);
              store.put(r.getId(), r);
              return r;
            });
    when(inviteRepo.findByRefereeEmailIgnoreCase(anyString()))
        .thenAnswer(
            inv -> {
              String email = inv.getArgument(0);
              return store.values().stream()
                  .filter(r -> r.getRefereeEmail().equalsIgnoreCase(email))
                  .findFirst();
            });
    when(inviteRepo.findByReferrerAndEmail(anyString(), anyString()))
        .thenAnswer(
            inv -> {
              String ref = inv.getArgument(0);
              String email = inv.getArgument(1);
              return store.values().stream()
                  .filter(
                      r ->
                          r.getReferrerUserId().equals(ref)
                              && r.getRefereeEmail().equalsIgnoreCase(email))
                  .findFirst();
            });
    when(inviteRepo.findPendingByReferrer(anyString()))
        .thenAnswer(
            inv -> {
              String ref = inv.getArgument(0);
              return store.values().stream()
                  .filter(r -> r.getReferrerUserId().equals(ref) && r.getGrantedAt() == null)
                  .toList();
            });
    when(inviteRepo.findUngrantedByAcceptedUser(anyString()))
        .thenAnswer(
            inv -> {
              String userId = inv.getArgument(0);
              return store.values().stream()
                  .filter(r -> userId.equals(r.getAcceptedByUserId()) && r.getGrantedAt() == null)
                  .findFirst();
            });
  }

  private AppUser user(String id, String email) {
    AppUser u = AppUser.builder().id(id).email(email).firstName("X").tier("FREE").build();
    when(userRepo.findById(id)).thenReturn(Optional.of(u));
    return u;
  }

  @Test
  void records_pending_invite_for_unknown_email() {
    user("u1", "alice@test");
    when(userRepo.findAll()).thenReturn(List.of());

    ReferralService.InviteRecord rec = svc.recordInvite("u1", "bob@test");

    assertEquals("u1", rec.referrerUserId());
    assertEquals("bob@test", rec.refereeEmail());
    assertEquals(ReferralService.InviteStatus.PENDING, rec.status());
    assertEquals(1, store.size());
    ReferralInvite stored = store.values().iterator().next();
    assertEquals("u1", stored.getReferrerUserId());
    assertEquals("bob@test", stored.getRefereeEmail());
    assertNotNull(stored.getToken(), "token must be generated for the future email pipeline");
    assertNotNull(stored.getCreatedAt());
    assertNull(stored.getAcceptedAt());
    assertNull(stored.getGrantedAt());
  }

  @Test
  void short_circuits_when_referee_already_registered() {
    user("u1", "alice@test");
    AppUser bob = user("u2", "bob@test");
    when(userRepo.findAll()).thenReturn(List.of(bob));

    ReferralService.InviteRecord rec = svc.recordInvite("u1", "bob@test");

    assertEquals(ReferralService.InviteStatus.ALREADY_EXISTS, rec.status());
    assertEquals(0, store.size(), "ALREADY_EXISTS must not write a row");
    assertEquals(0, svc.getInviteState("u1").pendingCount());
  }

  @Test
  void rejects_self_invite() {
    user("u1", "alice@test");
    assertThrows(IllegalArgumentException.class, () -> svc.recordInvite("u1", "alice@test"));
    assertEquals(0, store.size());
  }

  @Test
  void rejects_blank_email() {
    user("u1", "alice@test");
    assertThrows(IllegalArgumentException.class, () -> svc.recordInvite("u1", "  "));
  }

  @Test
  void duplicate_invite_returns_existing_row() {
    user("u1", "alice@test");
    when(userRepo.findAll()).thenReturn(List.of());

    ReferralService.InviteRecord first = svc.recordInvite("u1", "bob@test");
    ReferralService.InviteRecord second = svc.recordInvite("u1", "bob@test");

    assertEquals(1, store.size(), "duplicate invite must not write a second row");
    assertEquals(
        first.createdAt(),
        second.createdAt(),
        "second call must return the existing row's created_at");
  }

  @Test
  void bind_referral_stamps_referee_referred_by_user_id() {
    user("u1", "alice@test");
    AppUser bob = user("u2", "bob@test");
    when(userRepo.findAll()).thenReturn(List.of());

    svc.recordInvite("u1", "bob@test");
    boolean bound = svc.bindReferral("u2", "bob@test");

    assertTrue(bound);
    assertEquals("u1", bob.getReferredByUserId());
    verify(userRepo).save(bob);
    // Invite row's accepted_at should now be stamped
    ReferralInvite inv = store.values().iterator().next();
    assertNotNull(inv.getAcceptedAt());
    assertEquals("u2", inv.getAcceptedByUserId());
  }

  @Test
  void bind_referral_idempotent_when_already_bound() {
    AppUser bob = user("u2", "bob@test");
    bob.setReferredByUserId("someone-else");
    boolean bound = svc.bindReferral("u2", "bob@test");

    assertFalse(bound, "must not overwrite an existing referral binding");
    assertEquals("someone-else", bob.getReferredByUserId());
  }

  @Test
  void on_referee_first_ready_run_grants_credit_to_referrer() {
    AppUser bob = user("u2", "bob@test");
    bob.setReferredByUserId("u1");
    // Seed an accepted-but-not-granted invite row.
    ReferralInvite invite =
        ReferralInvite.builder()
            .id("i1")
            .referrerUserId("u1")
            .refereeEmail("bob@test")
            .createdAt(Instant.now())
            .acceptedAt(Instant.now())
            .acceptedByUserId("u2")
            .build();
    store.put("i1", invite);
    when(credit.grantInviteCredit("u1")).thenReturn(10);

    boolean granted = svc.onRefereeFirstReadyRun("u2");

    assertTrue(granted);
    verify(credit).grantInviteCredit("u1");
    assertNotNull(invite.getGrantedAt(), "granted_at must be stamped");
  }

  @Test
  void on_first_ready_run_no_op_when_no_referral() {
    user("u2", "bob@test"); // not referred
    boolean granted = svc.onRefereeFirstReadyRun("u2");
    assertFalse(granted);
    verify(credit, never()).grantInviteCredit(anyString());
  }

  @Test
  void on_first_ready_run_no_op_when_already_granted() {
    AppUser bob = user("u2", "bob@test");
    bob.setReferredByUserId("u1");
    ReferralInvite invite =
        ReferralInvite.builder()
            .id("i1")
            .referrerUserId("u1")
            .refereeEmail("bob@test")
            .createdAt(Instant.now())
            .acceptedAt(Instant.now())
            .acceptedByUserId("u2")
            .grantedAt(Instant.now())
            .build();
    store.put("i1", invite);

    boolean granted = svc.onRefereeFirstReadyRun("u2");

    assertFalse(granted, "already-granted invite must not double-credit");
    verify(credit, never()).grantInviteCredit(anyString());
  }

  @Test
  void lookup_by_token_returns_inviter_first_name_and_grant() {
    AppUser alice = user("u1", "alice@test");
    when(userRepo.findAll()).thenReturn(List.of());
    // Wire token-based lookup on the mock repo
    when(inviteRepo.findByToken(anyString()))
        .thenAnswer(
            inv -> {
              String token = inv.getArgument(0);
              return store.values().stream().filter(r -> token.equals(r.getToken())).findFirst();
            });

    svc.recordInvite("u1", "bob@test");
    ReferralInvite invite = store.values().iterator().next();

    ReferralService.ReferralLookup lookup = svc.lookupByToken(invite.getToken());

    assertTrue(lookup.valid());
    assertEquals("X", lookup.inviterFirstName(), "first name from the AppUser builder fixture");
    assertEquals(ReferralService.INVITE_GRANT, lookup.creditGrant());
    assertEquals("bob@test", lookup.refereeEmail());
  }

  @Test
  void lookup_by_unknown_token_returns_invalid_with_grant_intact() {
    when(inviteRepo.findByToken(anyString())).thenReturn(Optional.empty());

    ReferralService.ReferralLookup lookup = svc.lookupByToken("nonexistent");

    assertFalse(lookup.valid());
    assertNull(lookup.inviterFirstName());
    assertEquals(
        ReferralService.INVITE_GRANT,
        lookup.creditGrant(),
        "frontend still needs the grant size for the fallback warm copy");
  }

  @Test
  void lookup_by_blank_or_null_token_returns_unknown_without_db_hit() {
    ReferralService.ReferralLookup empty = svc.lookupByToken("");
    ReferralService.ReferralLookup nul = svc.lookupByToken(null);

    assertFalse(empty.valid());
    assertFalse(nul.valid());
    verify(inviteRepo, never()).findByToken(anyString());
  }

  @Test
  void invite_state_counts_only_caller_pending_invites() {
    user("u1", "alice@test");
    user("u3", "carol@test");
    when(userRepo.findAll()).thenReturn(List.of());

    svc.recordInvite("u1", "bob@test");
    svc.recordInvite("u1", "dan@test");
    svc.recordInvite("u3", "eve@test");

    assertEquals(2, svc.getInviteState("u1").pendingCount());
    assertEquals(1, svc.getInviteState("u3").pendingCount());
  }
}
