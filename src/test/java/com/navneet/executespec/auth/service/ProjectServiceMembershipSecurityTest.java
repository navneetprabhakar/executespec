package com.navneet.executespec.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class ProjectServiceMembershipSecurityTest {

  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectMemberRepository memberRepo = mock(ProjectMemberRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);
  private final ActivityAuditService auditService = mock(ActivityAuditService.class);
  private ProjectService service;

  @BeforeEach
  void setUp() {
    service =
        new ProjectService(
            projectRepo,
            memberRepo,
            userRepo,
            orgMemberRepo,
            auditService,
            mock(EventTrackingService.class),
            mock(TierEnforcementService.class),
            mock(com.navneet.executespec.router.ProviderReadinessService.class));
    Project project = new Project();
    project.setId("p1");
    project.setOrgId("host-org");
    project.setCreatedBy("actor");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project));
    AppUser actor = user("actor", "host-org");
    when(userRepo.findById("actor")).thenReturn(Optional.of(actor));
    when(orgMemberRepo.findByOrgIdAndUserId("host-org", "actor"))
        .thenReturn(
            Optional.of(
                OrgMember.builder()
                    .id("om-actor")
                    .orgId("host-org")
                    .userId("actor")
                    .orgRole("ORG_ADMIN")
                    .build()));
  }

  @Test
  void directMembershipRejectsCrossOrgTarget() {
    when(userRepo.findById("external")).thenReturn(Optional.of(user("external", "other-org")));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.addMember("p1", "external", "PROJECT_DEVELOPER", "actor"));

    assertEquals(400, error.getStatusCode().value());
    verify(memberRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void directMembershipRejectsUnknownRole() {
    when(userRepo.findById("target")).thenReturn(Optional.of(user("target", "host-org")));

    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () -> service.addMember("p1", "target", "OWNER", "actor"));

    assertEquals(400, error.getStatusCode().value());
    verify(memberRepo, never()).save(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void directMembershipAllowsValidatedHostOrgRole() {
    when(userRepo.findById("target")).thenReturn(Optional.of(user("target", "host-org")));
    when(memberRepo.existsByProjectIdAndUserId("p1", "target")).thenReturn(false);

    service.addMember("p1", "target", "PROJECT_VIEWER", "actor");

    ArgumentCaptor<ProjectMember> saved = ArgumentCaptor.forClass(ProjectMember.class);
    verify(memberRepo).save(saved.capture());
    assertEquals("PROJECT_VIEWER", saved.getValue().getProjectRole());
    assertEquals("target", saved.getValue().getUserId());
  }

  private static AppUser user(String id, String orgId) {
    AppUser user = new AppUser();
    user.setId(id);
    user.setOrgId(orgId);
    user.setEmail(id + "@example.com");
    user.setGlobalRole("USER");
    return user;
  }
}
