package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentContextResolverTest {

  @Mock ProjectRepoRepository projectRepoRepo;
  @Mock ProjectRepoLinkRepository projectRepoLinkRepo;
  @Mock ProjectAccessGuard projectAccessGuard;

  private AgentContextResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new AgentContextResolver(projectRepoRepo, projectRepoLinkRepo, projectAccessGuard);
  }

  @Test
  void resolvesGitRemoteToAccessibleLinkedProject() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("navneet/repo")
            .githubOwner("navneet")
            .build();
    ProjectRepoLink link = ProjectRepoLink.builder().projectId("p1").repoId(repoId).build();
    Project project =
        Project.builder().id("p1").orgId("org-1").name("Core API").createdBy("u1").build();
    when(projectRepoRepo.findByGithubRepoFullNameIgnoreCase("navneet/repo"))
        .thenReturn(List.of(repo));
    when(projectRepoLinkRepo.findByRepoId(repoId)).thenReturn(List.of(link));
    when(projectAccessGuard.assertCanRead(org.mockito.Mockito.eq("p1"), any(AppUserDetails.class)))
        .thenReturn(project);

    var response =
        resolver.resolve(
            new ContextResolveRequest("git@github.com:Navneet/Repo.git", null, "main", null),
            user());

    assertThat(response.project()).isNotNull();
    assertThat(response.project().projectId()).isEqualTo("p1");
    assertThat(response.project().projectName()).isEqualTo("Core API");
    assertThat(response.project().repoId()).isEqualTo(String.valueOf(repoId));
    assertThat(response.project().repoFullName()).isEqualTo("navneet/repo");
    assertThat(response.reason()).isEqualTo("repo remote matches linked project repo navneet/repo");
  }

  @Test
  void usesNestedRepoContextRemoteWhenTopLevelRemoteIsAbsent() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("owner/service")
            .githubOwner("owner")
            .build();
    ProjectRepoLink link = ProjectRepoLink.builder().projectId("p2").repoId(repoId).build();
    Project project =
        Project.builder().id("p2").orgId("org-1").name("Service").createdBy("u1").build();
    when(projectRepoRepo.findByGithubRepoFullNameIgnoreCase("owner/service"))
        .thenReturn(List.of(repo));
    when(projectRepoLinkRepo.findByRepoId(repoId)).thenReturn(List.of(link));
    when(projectAccessGuard.assertCanRead(org.mockito.Mockito.eq("p2"), any(AppUserDetails.class)))
        .thenReturn(project);

    var response =
        resolver.resolve(
            new ContextResolveRequest(
                null,
                null,
                "main",
                new RepoContext(
                    "service",
                    "https://github.com/Owner/Service.git",
                    "main",
                    List.of(),
                    List.of())),
            user());

    assertThat(response.project().projectId()).isEqualTo("p2");
    assertThat(response.project().repoFullName()).isEqualTo("owner/service");
  }

  @Test
  void skipsUnreadableLinkedProjectsWithoutLeakingThem() {
    UUID repoId = UUID.randomUUID();
    ProjectRepo repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("owner/repo")
            .githubOwner("owner")
            .build();
    ProjectRepoLink inaccessible =
        ProjectRepoLink.builder().projectId("p-denied").repoId(repoId).build();
    ProjectRepoLink accessible = ProjectRepoLink.builder().projectId("p-ok").repoId(repoId).build();
    Project project =
        Project.builder().id("p-ok").orgId("org-1").name("Allowed").createdBy("u1").build();
    when(projectRepoRepo.findByGithubRepoFullNameIgnoreCase("owner/repo"))
        .thenReturn(List.of(repo));
    when(projectRepoLinkRepo.findByRepoId(repoId)).thenReturn(List.of(inaccessible, accessible));
    when(projectAccessGuard.assertCanRead(
            org.mockito.Mockito.eq("p-denied"), any(AppUserDetails.class)))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"));
    when(projectAccessGuard.assertCanRead(
            org.mockito.Mockito.eq("p-ok"), any(AppUserDetails.class)))
        .thenReturn(project);

    var response =
        resolver.resolve(new ContextResolveRequest(null, "owner/repo", null, null), user());

    assertThat(response.project().projectId()).isEqualTo("p-ok");
    assertThat(response.reason()).doesNotContain("p-denied");
  }

  @Test
  void returnsNoMatchWhenRepoIsNotLinkedToReadableProject() {
    when(projectRepoRepo.findByGithubRepoFullNameIgnoreCase("owner/repo")).thenReturn(List.of());

    var response =
        resolver.resolve(new ContextResolveRequest(null, "owner/repo", null, null), user());

    assertThat(response.project()).isNull();
    assertThat(response.reason()).isEqualTo("no accessible project is linked to repo owner/repo");
  }

  @Test
  void returnsNoRemoteResponseForBlankOrNullRequest() {
    var response = resolver.resolve(null, user());

    assertThat(response.project()).isNull();
    assertThat(response.reason()).isEqualTo("no git remote or repository name was provided");
    verify(projectRepoRepo, never()).findByGithubRepoFullNameIgnoreCase(org.mockito.Mockito.any());
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setTier("PRO");
    user.setEmail("u1@example.com");
    return new AppUserDetails(user, null);
  }
}
