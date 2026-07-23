package com.navneet.executespec.adr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AdrControllerCapabilityTest {

  @Test
  void reportsReadOnlyCacheWithoutClaimingAutomaticRepositoryIngestion() {
    AdrRepository repository = mock(AdrRepository.class);
    AdrInjectionService injection = mock(AdrInjectionService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    Authentication authentication = mock(Authentication.class);
    when(repository.countByProjectId("project-1")).thenReturn(3L);
    when(injection.injectionEnabled()).thenReturn(false);

    AdrController controller = new AdrController(repository, injection, guard);
    AdrController.AdrCapability capability =
        controller.capability("project-1", authentication).getBody();

    assertThat(capability).isNotNull();
    assertThat(capability.automaticIngestion()).isFalse();
    assertThat(capability.source()).isEqualTo("READ_ONLY_CACHE");
    assertThat(capability.indexedCount()).isEqualTo(3);
    assertThat(capability.plannerInjectionEnabled()).isFalse();
    assertThat(capability.reason()).contains("not implemented");
    verify(guard).assertCanRead("project-1", null);
  }
}
