package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgInvitation;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgInvitationRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-221 / RR-222 — an org invitation grants the invited role to the invited person, exactly once.
 *
 * <p>Previously acceptance resolved the caller purely from the session and never compared it with
 * `invitedEmail`, so anyone holding a forwarded/leaked invite URL could join the org — potentially
 * as ORG_ADMIN or BILLING_ADMIN — while the intended recipient was locked out by the ACCEPTED
 * status. Acceptance also read PENDING, created memberships, and only then wrote ACCEPTED, so two
 * concurrent holders of one token could both be granted the seat.
 */
class InvitationServiceAcceptanceBindingTest {

  private OrgInvitationRepository invitationRepo;
  private OrgMemberRepository orgMemberRepo;
  private AppUserRepository userRepo;
  private InvitationService svc;

  @BeforeEach
  void setUp() {
    invitationRepo = mock(OrgInvitationRepository.class);
    orgMemberRepo = mock(OrgMemberRepository.class);
    userRepo = mock(AppUserRepository.class);
    svc =
        new InvitationService(
            invitationRepo,
            mock(OrgRepository.class),
            orgMemberRepo,
            userRepo,
            mock(ProjectRepository.class),
            mock(ProjectMemberRepository.class),
            mock(TierConfigRepository.class),
            mock(BillingSubscriptionRepository.class),
            mock(MailService.class),
            mock(ActivityAuditService.class));
  }

  private OrgInvitation pendingAdminInvite() {
    OrgInvitation inv =
        OrgInvitation.builder()
            .id("inv-1")
            .orgId("org-1")
            .invitedEmail("invited@acme.test")
            .invitedBy("admin-1")
            .orgRole("ORG_ADMIN")
            .token("tok-1")
            .status("PENDING")
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    when(invitationRepo.findByToken("tok-1")).thenReturn(Optional.of(inv));
    return inv;
  }

  private AppUser user(String id, String email) {
    AppUser u = new AppUser();
    u.setId(id);
    u.setEmail(email);
    when(userRepo.findById(id)).thenReturn(Optional.of(u));
    return u;
  }

  @Test
  void aDifferentSignedInAccountCannotClaimTheInvitedSeat() {
    pendingAdminInvite();
    user("attacker-1", "attacker@evil.test");

    assertThatThrownBy(() -> svc.acceptInvitation("tok-1", "attacker-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);

    verify(invitationRepo, never()).claimPending(anyString(), any(Instant.class));
    verify(orgMemberRepo, never()).save(any(OrgMember.class));
  }

  @Test
  void theInvitedRecipientIsAcceptedRegardlessOfAddressCasingOrPadding() {
    pendingAdminInvite();
    user("invitee-1", "  Invited@Acme.Test ");
    when(invitationRepo.claimPending(eq("inv-1"), any(Instant.class))).thenReturn(1);
    when(orgMemberRepo.existsByOrgIdAndUserId("org-1", "invitee-1")).thenReturn(false);

    svc.acceptInvitation("tok-1", "invitee-1");

    verify(orgMemberRepo).save(any(OrgMember.class));
  }

  @Test
  void losingTheAtomicClaimGrantsNothing() {
    pendingAdminInvite();
    user("invitee-1", "invited@acme.test");
    // A concurrent request already moved PENDING -> ACCEPTED.
    when(invitationRepo.claimPending(eq("inv-1"), any(Instant.class))).thenReturn(0);

    assertThatThrownBy(() -> svc.acceptInvitation("tok-1", "invitee-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);

    verify(orgMemberRepo, never()).save(any(OrgMember.class));
  }

  @Test
  void theClaimHappensBeforeAnyMembershipIsCreated() {
    OrgInvitation inv = pendingAdminInvite();
    user("invitee-1", "invited@acme.test");
    when(orgMemberRepo.existsByOrgIdAndUserId("org-1", "invitee-1")).thenReturn(false);
    when(invitationRepo.claimPending(eq("inv-1"), any(Instant.class)))
        .thenAnswer(
            call -> {
              verify(orgMemberRepo, never()).save(any(OrgMember.class));
              return 1;
            });

    svc.acceptInvitation("tok-1", "invitee-1");

    assertThat(inv.getStatus()).isEqualTo("ACCEPTED");
    verify(orgMemberRepo).save(any(OrgMember.class));
  }

  @Test
  void theIdempotentAlreadyAcceptedPathIsAlsoBoundToTheInvitedEmail() {
    OrgInvitation inv = pendingAdminInvite();
    inv.setStatus("ACCEPTED");
    user("attacker-1", "attacker@evil.test");

    assertThatThrownBy(() -> svc.acceptInvitation("tok-1", "attacker-1"))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }
}
