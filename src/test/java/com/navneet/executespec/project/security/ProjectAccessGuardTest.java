package com.navneet.executespec.project.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * V175 — pins the ProjectAccessGuard's allow/deny decision tree, including the new cross-org
 * project_member membership path.
 *
 * <p>Decisions covered:
 *
 * <ul>
 *   <li>SUPER_ADMIN bypasses every check
 *   <li>Same-org caller passes
 *   <li>Cross-org caller WITH a project_member row passes (V175)
 *   <li>Cross-org caller WITHOUT a project_member row → 403
 *   <li>Anonymous → 401
 *   <li>Missing project → 404 (no existence leak)
 *   <li>Blank projectId → 400
 * </ul>
 */
class ProjectAccessGuardTest {

  private ProjectRepository projectRepo;
  private ProjectMemberRepository memberRepo;
  private ProjectAccessGuard guard;

  @BeforeEach
  void setUp() {
    projectRepo = mock(ProjectRepository.class);
    memberRepo = mock(ProjectMemberRepository.class);
    guard = new ProjectAccessGuard(projectRepo, memberRepo);
    lenient()
        .when(
            memberRepo.existsByProjectIdAndUserId(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(false);
  }

  @Test
  void super_admin_bypasses_org_check() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails admin = userDetails("admin1", "host-org", "SUPER_ADMIN");
    assertSame(p, guard.assertCanRead("p1", admin));
  }

  @Test
  void same_org_caller_passes() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails user = userDetails("u1", "host-org", "USER");
    assertSame(p, guard.assertCanRead("p1", user));
  }

  @Test
  void cross_org_caller_with_project_member_row_passes() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails external = userDetails("u-ext", "other-org", "USER");
    when(memberRepo.existsByProjectIdAndUserId("p1", "u-ext")).thenReturn(true);
    assertSame(p, guard.assertCanRead("p1", external));
  }

  @Test
  void cross_org_caller_without_member_row_is_blocked() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails stranger = userDetails("u-stranger", "other-org", "USER");
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanRead("p1", stranger));
    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void crossOrgProjectViewerIsBlockedFromWrites() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails viewer = userDetails("u-viewer", "other-org", "USER");
    when(memberRepo.existsByProjectIdAndUserId("p1", "u-viewer")).thenReturn(true);
    when(memberRepo.findByProjectIdAndUserId("p1", "u-viewer"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId("p1")
                    .userId("u-viewer")
                    .projectRole("PROJECT_VIEWER")
                    .external(true)
                    .build()));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanWrite("p1", viewer));

    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void sameOrgProjectViewerIsBlockedFromWrites() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails viewer = userDetails("u-viewer", "host-org", "USER");
    when(memberRepo.findByProjectIdAndUserId("p1", "u-viewer"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId("p1")
                    .userId("u-viewer")
                    .projectRole("PROJECT_VIEWER")
                    .build()));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanWrite("p1", viewer));

    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void sameOrgUserWithoutProjectSpecificRoleIsBlockedFromWrites() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails user = userDetails("u-org", "host-org", "USER");
    when(memberRepo.findByProjectIdAndUserId("p1", "u-org")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanWrite("p1", user));

    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void sameOrgProjectDeveloperMayWrite() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails developer = userDetails("u-developer", "host-org", "USER");
    when(memberRepo.findByProjectIdAndUserId("p1", "u-developer"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId("p1")
                    .userId("u-developer")
                    .projectRole("PROJECT_DEVELOPER")
                    .build()));

    assertSame(p, guard.assertCanWrite("p1", developer));
  }

  @Test
  void hostOrgAdminMayWriteWithoutProjectMembership() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails orgAdmin = orgUserDetails("u-admin", "host-org", "ORG_ADMIN");
    when(memberRepo.findByProjectIdAndUserId("p1", "u-admin")).thenReturn(Optional.empty());

    assertSame(p, guard.assertCanWrite("p1", orgAdmin));
  }

  @Test
  void billingAdminCannotWriteWithoutProjectMembership() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails billing = orgUserDetails("u-billing", "host-org", "BILLING_ADMIN");
    when(memberRepo.findByProjectIdAndUserId("p1", "u-billing")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanWrite("p1", billing));

    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void crossOrgProjectDeveloperMayWrite() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails developer = userDetails("u-developer", "other-org", "USER");
    when(memberRepo.existsByProjectIdAndUserId("p1", "u-developer")).thenReturn(true);
    when(memberRepo.findByProjectIdAndUserId("p1", "u-developer"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId("p1")
                    .userId("u-developer")
                    .projectRole("PROJECT_DEVELOPER")
                    .external(true)
                    .build()));

    assertSame(p, guard.assertCanWrite("p1", developer));
  }

  @Test
  void projectDeveloperCannotReadAdministrativeActivity() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails developer = userDetails("u-developer", "host-org", "USER");
    when(memberRepo.findByProjectIdAndUserId("p1", "u-developer"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId("p1")
                    .userId("u-developer")
                    .projectRole("PROJECT_DEVELOPER")
                    .build()));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanAdmin("p1", developer));

    assertEquals(403, ex.getStatusCode().value());
  }

  @Test
  void explicitExternalProjectAdminMayReadAdministrativeActivity() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails admin = userDetails("u-external-admin", "other-org", "USER");
    when(memberRepo.existsByProjectIdAndUserId("p1", "u-external-admin")).thenReturn(true);
    when(memberRepo.findByProjectIdAndUserId("p1", "u-external-admin"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder()
                    .projectId("p1")
                    .userId("u-external-admin")
                    .projectRole("PROJECT_ADMIN")
                    .external(true)
                    .build()));

    assertSame(p, guard.assertCanAdmin("p1", admin));
  }

  @Test
  void hostOrgAdminMayReadAdministrativeActivity() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    AppUserDetails admin = orgUserDetails("u-admin", "host-org", "ORG_ADMIN");

    assertSame(p, guard.assertCanAdmin("p1", admin));
  }

  @Test
  void anonymous_caller_returns_401() {
    Project p = project("p1", "host-org");
    when(projectRepo.findById("p1")).thenReturn(Optional.of(p));
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanRead("p1", null));
    assertEquals(401, ex.getStatusCode().value());
  }

  @Test
  void unknown_project_returns_404_not_403() {
    when(projectRepo.findById("missing")).thenReturn(Optional.empty());
    AppUserDetails user = userDetails("u1", "any-org", "USER");
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanRead("missing", user));
    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void blank_project_id_returns_400() {
    AppUserDetails user = userDetails("u1", "any-org", "USER");
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> guard.assertCanRead("  ", user));
    assertEquals(400, ex.getStatusCode().value());
  }

  private static Project project(String id, String orgId) {
    Project p = new Project();
    p.setId(id);
    p.setOrgId(orgId);
    p.setName("test");
    return p;
  }

  private static AppUserDetails userDetails(String userId, String orgId, String globalRole) {
    AppUser u = new AppUser();
    u.setId(userId);
    u.setOrgId(orgId);
    u.setGlobalRole(globalRole);
    u.setEmail(userId + "@example.com");
    return new AppUserDetails(u, null);
  }

  private static AppUserDetails orgUserDetails(String userId, String orgId, String orgRole) {
    AppUser u = new AppUser();
    u.setId(userId);
    u.setOrgId(orgId);
    u.setGlobalRole("USER");
    u.setEmail(userId + "@example.com");
    OrgMember member =
        OrgMember.builder().id("om-" + userId).orgId(orgId).userId(userId).orgRole(orgRole).build();
    return new AppUserDetails(u, member);
  }
}
