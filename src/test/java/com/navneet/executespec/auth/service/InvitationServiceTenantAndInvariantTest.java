package com.navneet.executespec.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.dto.InviteRequest;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgInvitation;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-isolation and org-invariant guards on the invitation chokepoint.
 *
 * <ul>
 *   <li>RR-224 — resend is authorized on (id, orgId); a foreign UUID cannot be reactivated.
 *   <li>RR-824 — a personal workspace cannot invite members without conversion.
 *   <li>RR-1291 — a PRO org that already has an ORG_ADMIN cannot mint another via an invite.
 * </ul>
 */
class InvitationServiceTenantAndInvariantTest {

  private final OrgInvitationRepository invitationRepo = mock(OrgInvitationRepository.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);
  private InvitationService service;

  @BeforeEach
  void setUp() {
    service =
        new InvitationService(
            invitationRepo,
            orgRepo,
            orgMemberRepo,
            mock(AppUserRepository.class),
            mock(ProjectRepository.class),
            mock(ProjectMemberRepository.class),
            mock(TierConfigRepository.class),
            mock(BillingSubscriptionRepository.class),
            mock(MailService.class),
            mock(ActivityAuditService.class));
  }

  // RR-224 — resend must not act on another tenant's invitation.
  @Test
  void resendRejectsForeignOrgInvitation() {
    OrgInvitation foreign =
        OrgInvitation.builder().id("inv-1").orgId("other-org").status("PENDING").build();
    when(invitationRepo.findById("inv-1")).thenReturn(Optional.of(foreign));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.resendInvitation("host-org", "inv-1", "admin"));

    assertEquals(404, error.getStatusCode().value());
    verify(invitationRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  // RR-824 — personal orgs are not business-billable scopes and cannot invite.
  @Test
  void personalOrgCannotInvite() {
    Org personal = new Org();
    personal.setId("personal-org");
    personal.setTier("PRO");
    personal.setPersonal(true);
    when(orgRepo.findById("personal-org")).thenReturn(Optional.of(personal));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createInvitation(
                    "personal-org",
                    "owner",
                    new InviteRequest("dev@example.com", "DEVELOPER", List.of())));

    assertEquals(409, error.getStatusCode().value());
    verify(invitationRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  // RR-1291 — the PRO single-ORG_ADMIN invariant is enforced at the invite path too.
  @Test
  void proOrgCannotInviteSecondAdmin() {
    Org pro = new Org();
    pro.setId("pro-org");
    pro.setTier("PRO");
    pro.setPersonal(false);
    when(orgRepo.findById("pro-org")).thenReturn(Optional.of(pro));
    when(orgMemberRepo.countByOrgIdAndOrgRole("pro-org", "ORG_ADMIN")).thenReturn(1L);
    when(invitationRepo.countByOrgIdAndOrgRoleAndStatus("pro-org", "ORG_ADMIN", "PENDING"))
        .thenReturn(0L);

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createInvitation(
                    "pro-org",
                    "admin",
                    new InviteRequest("admin2@example.com", "ORG_ADMIN", List.of())));

    assertEquals(403, error.getStatusCode().value());
    verify(invitationRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
