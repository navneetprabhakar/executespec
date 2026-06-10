package com.navneet.executespec.standards.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.standards.EffectiveStandardsResolver;
import com.navneet.executespec.standards.OrgStandardRepository;
import com.navneet.executespec.standards.ProjectStandardOverrideRepository;
import com.navneet.executespec.standards.StandardsViolationRepository;
import com.navneet.executespec.standards.model.Standard;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class StandardsComplianceControllerTest {

  private final EffectiveStandardsResolver resolver = mock(EffectiveStandardsResolver.class);
  private final ProjectStandardOverrideRepository overrideRepo =
      mock(ProjectStandardOverrideRepository.class);
  private final StandardsViolationRepository violationRepo =
      mock(StandardsViolationRepository.class);
  private final ProjectAccessGuard projectAccessGuard = mock(ProjectAccessGuard.class);
  private final StandardsComplianceController controller =
      new StandardsComplianceController(
          resolver,
          mock(OrgStandardRepository.class),
          overrideRepo,
          violationRepo,
          projectAccessGuard);

  @Test
  void reportRejectsOrgIdThatDoesNotOwnProject() {
    Authentication auth = auth(principal("user-1", "org-a", "USER"));
    when(projectAccessGuard.assertCanRead(
            org.mockito.ArgumentMatchers.eq("project-1"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Project.builder().id("project-1").orgId("org-a").name("Project").build());

    assertThatThrownBy(() -> controller.report("project-1", "org-b", 30, auth))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("403");

    verify(resolver, never())
        .resolve(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void reportResolvesStandardsForProjectOrg() {
    Authentication auth = auth(principal("user-1", "org-a", "USER"));
    when(projectAccessGuard.assertCanRead(
            org.mockito.ArgumentMatchers.eq("project-1"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Project.builder().id("project-1").orgId("org-a").name("Project").build());
    when(resolver.resolve("org-a", "project-1", null))
        .thenReturn(
            List.of(
                new EffectiveStandardsResolver.Effective(
                    new Standard(
                        "std-1",
                        null,
                        Standard.Severity.WARNING,
                        "No risky patterns",
                        new Standard.FilePatternEnforcement(List.of("*.pem"), List.of()),
                        null,
                        List.of(),
                        false),
                    EffectiveStandardsResolver.Source.ORG,
                    false,
                    Standard.Severity.WARNING,
                    false)));
    when(violationRepo.findRecentForProject(
            org.mockito.ArgumentMatchers.eq("project-1"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    when(overrideRepo.findByProjectId("project-1")).thenReturn(List.of());

    var response = controller.report("project-1", "org-a", 30, auth);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().orgId()).isEqualTo("org-a");
    assertThat(response.getBody().standards()).extracting("id").containsExactly("std-1");
    verify(resolver).resolve("org-a", "project-1", null);
  }

  private static Authentication auth(AppUserDetails principal) {
    Authentication auth = mock(Authentication.class);
    when(auth.getPrincipal()).thenReturn(principal);
    return auth;
  }

  private static AppUserDetails principal(String userId, String orgId, String globalRole) {
    AppUser user =
        AppUser.builder()
            .id(userId)
            .email(userId + "@example.com")
            .firstName("Test")
            .orgId(orgId)
            .globalRole(globalRole)
            .status("ACTIVE")
            .build();
    return new AppUserDetails(user, null);
  }
}
