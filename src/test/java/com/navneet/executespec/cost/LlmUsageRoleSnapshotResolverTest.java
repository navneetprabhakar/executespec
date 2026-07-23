package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.entity.ProjectMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;

import org.junit.jupiter.api.Test;

class LlmUsageRoleSnapshotResolverTest {

  private final OrgMemberRepository memberships = mock(OrgMemberRepository.class);
  private final ProjectMemberRepository projectMemberships = mock(ProjectMemberRepository.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final LlmUsageRoleSnapshotResolver resolver =
      new LlmUsageRoleSnapshotResolver(memberships, projectMemberships, users);

  @Test
  void orgRoleWinsAndGlobalRoleIsTheSoloFallback() {
    when(memberships.findByOrgIdAndUserId("org-1", "user-1"))
        .thenReturn(Optional.of(OrgMember.builder().orgRole("BILLING_ADMIN").build()));
    assertThat(resolver.resolve("org-1", "user-1", null)).isEqualTo("BILLING_ADMIN");

    AppUser solo = new AppUser();
    solo.setGlobalRole("SUPER_ADMIN");
    when(users.findById("solo")).thenReturn(Optional.of(solo));
    assertThat(resolver.resolve(null, "solo", null)).isEqualTo("SUPER_ADMIN");
  }

  @Test
  void projectRoleCapturesCrossOrgCollaboratorAuthority() {
    when(projectMemberships.findByProjectIdAndUserId("project-1", "external-user"))
        .thenReturn(
            Optional.of(
                ProjectMember.builder().projectRole("PROJECT_VIEWER").external(true).build()));

    assertThat(resolver.resolve("host-org", "external-user", "project-1"))
        .isEqualTo("PROJECT_VIEWER");
  }

  @Test
  void missingIdentityIsExplicitlyUnknown() {
    assertThat(resolver.resolve("org-1", null, null)).isEqualTo("UNKNOWN");
    assertThat(resolver.resolve("org-1", "missing", null)).isEqualTo("UNKNOWN");
  }
}
