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
import com.navneet.executespec.auth.entity.Project;
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

class InvitationServiceProjectScopeTest {

  private final OrgInvitationRepository invitationRepo = mock(OrgInvitationRepository.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private InvitationService service;

  @BeforeEach
  void setUp() {
    service =
        new InvitationService(
            invitationRepo,
            orgRepo,
            mock(OrgMemberRepository.class),
            mock(AppUserRepository.class),
            projectRepo,
            mock(ProjectMemberRepository.class),
            mock(TierConfigRepository.class),
            mock(BillingSubscriptionRepository.class),
            mock(MailService.class),
            mock(ActivityAuditService.class));
    Org host = new Org();
    host.setId("host-org");
    host.setTier("TEAM");
    when(orgRepo.findById("host-org")).thenReturn(Optional.of(host));
  }

  @Test
  void rejectsUnknownOrganizationRole() {
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createInvitation(
                    "host-org",
                    "admin",
                    new InviteRequest("dev@example.com", "SUPER_ADMIN", List.of())));

    assertEquals(400, error.getStatusCode().value());
    verify(invitationRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsProjectAssignmentFromAnotherOrganization() {
    Project foreign = new Project();
    foreign.setId("foreign-project");
    foreign.setOrgId("other-org");
    when(projectRepo.findById("foreign-project")).thenReturn(Optional.of(foreign));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createInvitation(
                    "host-org",
                    "admin",
                    new InviteRequest("dev@example.com", "DEVELOPER", List.of("foreign-project"))));

    assertEquals(400, error.getStatusCode().value());
    verify(invitationRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsUnboundedProjectAssignmentList() {
    List<String> ids = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "p" + i).toList();

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                service.createInvitation(
                    "host-org", "admin", new InviteRequest("dev@example.com", "DEVELOPER", ids)));

    assertEquals(400, error.getStatusCode().value());
    verify(invitationRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }
}
