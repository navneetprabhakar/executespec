package com.navneet.executespec.sharing.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProjectShareLinkControllerTest {

  @Mock private ProjectShareLinkService linkService;
  @Mock private ProjectAccessGuard accessGuard;
  @Mock private ProjectMemberRepository projectMemberRepo;

  private ProjectShareLinkController controller;

  @BeforeEach
  void setUp() {
    controller = new ProjectShareLinkController(linkService, accessGuard, projectMemberRepo);
  }

  @Test
  void listRedactsTokensForProjectViewer() {
    AppUserDetails viewer = userDetails("u-viewer", "org-1", "ORG_ADMIN");
    Project project = project();
    when(accessGuard.assertCanRead("p1", viewer)).thenReturn(project);
    when(projectMemberRepo.findByProjectIdAndUserId("p1", "u-viewer"))
        .thenReturn(Optional.of(projectMember("u-viewer", "PROJECT_VIEWER")));
    when(linkService.listForProject("p1")).thenReturn(List.of(link("secret-token")));
    when(linkService.activeCountForProject("p1")).thenReturn(1L);

    var body = controller.list("p1", viewer).getBody();

    assertNotNull(body);
    assertEquals(1, body.activeCount());
    assertNull(body.links().get(0).token());
  }

  @Test
  void listIncludesTokensForProjectDeveloper() {
    AppUserDetails developer = userDetails("u-dev", "org-1", "DEVELOPER");
    Project project = project();
    when(accessGuard.assertCanRead("p1", developer)).thenReturn(project);
    when(projectMemberRepo.findByProjectIdAndUserId("p1", "u-dev"))
        .thenReturn(Optional.of(projectMember("u-dev", "PROJECT_DEVELOPER")));
    when(linkService.listForProject("p1")).thenReturn(List.of(link("secret-token")));
    when(linkService.activeCountForProject("p1")).thenReturn(1L);

    var body = controller.list("p1", developer).getBody();

    assertNotNull(body);
    assertEquals("secret-token", body.links().get(0).token());
  }

  @Test
  void createRejectsSameOrgDeveloperWithoutProjectWriterRole() {
    AppUserDetails developer = userDetails("u-dev", "org-1", "DEVELOPER");
    Project project = project();
    when(accessGuard.assertCanWrite("p1", developer)).thenReturn(project);
    when(projectMemberRepo.findByProjectIdAndUserId("p1", "u-dev")).thenReturn(Optional.empty());

    assertThrows(
        ResponseStatusException.class,
        () ->
            controller.create(
                "p1",
                new ProjectShareLinkController.CreateShareLinkRequest(
                    "PROJECT_OVERVIEW", null, "demo", null),
                developer));

    verify(linkService, never()).createLink(any(), any(), any(), any(), any(), any());
  }

  @Test
  void createAllowsSameOrgAdminWithoutExplicitProjectMembership() {
    AppUserDetails orgAdmin = userDetails("u-admin", "org-1", "ORG_ADMIN");
    Project project = project();
    when(accessGuard.assertCanWrite("p1", orgAdmin)).thenReturn(project);
    when(projectMemberRepo.findByProjectIdAndUserId("p1", "u-admin")).thenReturn(Optional.empty());
    when(linkService.createLink(
            eq("p1"),
            eq(ProjectShareLink.Surface.PROJECT_OVERVIEW),
            isNull(),
            eq("demo"),
            isNull(),
            same(orgAdmin.getUser())))
        .thenReturn(link("secret-token"));

    var body =
        controller
            .create(
                "p1",
                new ProjectShareLinkController.CreateShareLinkRequest(
                    "PROJECT_OVERVIEW", null, "demo", null),
                orgAdmin)
            .getBody();

    assertNotNull(body);
    assertEquals("secret-token", body.token());
  }

  private static AppUserDetails userDetails(String userId, String orgId, String orgRole) {
    AppUser user =
        AppUser.builder()
            .id(userId)
            .email(userId + "@example.com")
            .firstName("Test")
            .globalRole("USER")
            .tier("TEAM")
            .status("ACTIVE")
            .orgId(orgId)
            .build();
    OrgMember orgMember =
        OrgMember.builder().id("om-" + userId).orgId(orgId).userId(userId).orgRole(orgRole).build();
    return new AppUserDetails(user, orgMember);
  }

  private static Project project() {
    return Project.builder().id("p1").orgId("org-1").name("Project").createdBy("owner").build();
  }

  private static ProjectMember projectMember(String userId, String role) {
    return ProjectMember.builder()
        .id("pm-" + userId)
        .projectId("p1")
        .userId(userId)
        .projectRole(role)
        .build();
  }

  private static ProjectShareLink link(String token) {
    return ProjectShareLink.builder()
        .id(UUID.randomUUID())
        .projectId("p1")
        .token(token)
        .surface(ProjectShareLink.Surface.PROJECT_OVERVIEW)
        .label("demo")
        .build();
  }
}
