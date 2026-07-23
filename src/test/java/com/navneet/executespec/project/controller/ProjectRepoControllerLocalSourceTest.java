package com.navneet.executespec.project.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.dto.LocalProjectSourceDto;
import com.navneet.executespec.project.dto.RegisterLocalSourceRequest;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.LocalProjectSourceService;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProjectRepoControllerLocalSourceTest {

  @Test
  void registerLocalSourceUsesWriteGuardAndReturnsCreatedForNewOpaqueSource() {
    ProjectRepoService repoService = mock(ProjectRepoService.class);
    LocalProjectSourceService localService = mock(LocalProjectSourceService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    ProjectRepoController controller =
        new ProjectRepoController(
            repoService,
            localService,
            mock(GitHubRepoService.class),
            mock(ScmRepositoryClientRegistry.class),
            mock(ScmCredentialResolver.class),
            guard);
    AppUserDetails principal =
        new AppUserDetails(
            AppUser.builder()
                .id("user-1")
                .orgId("org-1")
                .email("user@example.com")
                .globalRole("USER")
                .status("ACTIVE")
                .tier("TEAM")
                .build(),
            null);
    RegisterLocalSourceRequest request =
        new RegisterLocalSourceRequest(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "repo",
            "rev",
            List.of("READ_FILES"),
            Map.of(),
            90);
    LocalProjectSourceDto body = new LocalProjectSourceDto(true, null);
    when(localService.register("project-1", request, "user-1", "org-1", false)).thenReturn(body);

    var response = controller.registerLocalSource(principal, "project-1", request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).isSameAs(body);
    verify(guard).assertCanWrite("project-1", principal);
  }
}
