package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.dto.CreateProjectRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.router.ProviderReadinessService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectServiceProviderPolicyTest {

  private final ProjectRepository projectRepo = mock(ProjectRepository.class);
  private final ProjectMemberRepository memberRepo = mock(ProjectMemberRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final ProviderReadinessService readiness = mock(ProviderReadinessService.class);
  private ProjectService service;

  @BeforeEach
  void setUp() {
    service =
        new ProjectService(
            projectRepo,
            memberRepo,
            userRepo,
            mock(OrgMemberRepository.class),
            mock(ActivityAuditService.class),
            mock(EventTrackingService.class),
            mock(TierEnforcementService.class),
            readiness,
            mock(com.navneet.executespec.auth.repository.OrgRepository.class));
    when(projectRepo.save(any(Project.class))).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void deepSeekOnlyOwnKeysAccountDefaultsToDeepSeekWithoutAnthropicFallback() {
    AppUser user = AppUser.builder().id("user-1").orgId("org-1").build();
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(readiness.resolve("user-1"))
        .thenReturn(List.of(available("DEEPSEEK", "OWN_KEYS"), unavailable("ANTHROPIC")));

    var created =
        service.createProject(
            "org-1", "user-1", new CreateProjectRequest("Brownfield", null, null, null));

    assertThat(created.aiProvider()).isEqualTo("DEEPSEEK");
    assertThat(user.getPreferredAiProvider()).isEqualTo("DEEPSEEK");
  }

  @Test
  void explicitProviderThatIsNotCredentialedFailsBeforeProjectSave() {
    when(readiness.resolve("user-1"))
        .thenReturn(List.of(available("DEEPSEEK", "OWN_KEYS"), unavailable("ANTHROPIC")));

    assertThatThrownBy(
            () ->
                service.createProject(
                    "org-1",
                    "user-1",
                    new CreateProjectRequest("Brownfield", null, "ANTHROPIC", null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(409);
    verify(projectRepo, never()).save(any(Project.class));
  }

  @Test
  void multipleEligibleProvidersRequireAnExplicitChoiceWhenPreferenceIsAbsent() {
    when(userRepo.findById("user-1")).thenReturn(Optional.empty());
    when(readiness.resolve("user-1"))
        .thenReturn(
            List.of(available("DEEPSEEK", "OWN_KEYS"), available("OPENROUTER", "OWN_KEYS")));

    assertThatThrownBy(
            () ->
                service.createProject(
                    "org-1", "user-1", new CreateProjectRequest("Brownfield", null, null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .extracting(error -> ((ResponseStatusException) error).getStatusCode().value())
        .isEqualTo(400);
    verify(projectRepo, never()).save(any(Project.class));
  }

  @Test
  void dispatchableAccountPreferenceWinsAmongMultipleEligibleProviders() {
    AppUser user =
        AppUser.builder().id("user-1").orgId("org-1").preferredAiProvider("OPENROUTER").build();
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(readiness.resolve("user-1"))
        .thenReturn(
            List.of(available("DEEPSEEK", "OWN_KEYS"), available("OPENROUTER", "OWN_KEYS")));

    var created =
        service.createProject(
            "org-1", "user-1", new CreateProjectRequest("Brownfield", null, null, null));

    assertThat(created.aiProvider()).isEqualTo("OPENROUTER");
  }

  private static ProviderReadinessService.ProviderReadiness available(
      String provider, String mode) {
    return new ProviderReadinessService.ProviderReadiness(
        provider, ProviderReadinessService.State.AVAILABLE_ORG_KEY, true, mode, "available");
  }

  private static ProviderReadinessService.ProviderReadiness unavailable(String provider) {
    return new ProviderReadinessService.ProviderReadiness(
        provider, ProviderReadinessService.State.UNAVAILABLE, false, "OWN_KEYS", "missing key");
  }
}
