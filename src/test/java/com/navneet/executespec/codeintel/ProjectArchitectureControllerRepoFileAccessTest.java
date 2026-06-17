package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ProjectArchitectureControllerRepoFileAccessTest {

  private ProjectAccessGuard projectAccessGuard;
  private ProjectRepoRepository projectRepoRepo;
  private ProjectRepoLinkRepository projectRepoLinkRepo;
  private AppUserRepository userRepo;
  private GitHubRepoService github;
  private ProjectArchitectureController controller;

  @BeforeEach
  void setUp() {
    projectAccessGuard = mock(ProjectAccessGuard.class);
    projectRepoRepo = mock(ProjectRepoRepository.class);
    projectRepoLinkRepo = mock(ProjectRepoLinkRepository.class);
    userRepo = mock(AppUserRepository.class);
    github = mock(GitHubRepoService.class);
    controller =
        new ProjectArchitectureController(
            mock(ProjectArchitectureService.class),
            mock(StalenessService.class),
            mock(com.navneet.executespec.project.workspace.ProjectWorkspaceService.class),
            mock(ArchitectureDiffService.class),
            projectAccessGuard,
            projectRepoRepo,
            projectRepoLinkRepo,
            userRepo,
            github);
  }

  @Test
  void listRepoFiles_usesProjectLinkCreatorPatForInvitedProjectMember() {
    UUID repoId = UUID.randomUUID();
    String projectId = "project-1";
    var link =
        ProjectRepoLink.builder().projectId(projectId).repoId(repoId).linkedBy("host-user").build();
    var repo =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/private-api")
            .defaultBranch("main")
            .build();
    var host =
        AppUser.builder().id("host-user").email("host@example.com").githubPat("host-pat").build();
    var invited =
        new AppUserDetails(
            AppUser.builder()
                .id("invitee")
                .email("invitee@example.com")
                .githubPat("invitee-pat")
                .status("ACTIVE")
                .build(),
            null);

    when(projectRepoLinkRepo.findByProjectIdAndRepoId(projectId, repoId))
        .thenReturn(Optional.of(link));
    when(projectRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(userRepo.findById("host-user")).thenReturn(Optional.of(host));
    when(github.listRepoFiles("https://github.com/acme/private-api", "main", "host-pat"))
        .thenReturn(List.of("README.md", "src/App.tsx"));

    var response = controller.listRepoFiles(projectId, repoId, invited);

    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().files()).containsExactly("README.md", "src/App.tsx");
    verify(projectAccessGuard).assertCanRead(projectId, invited);
    verify(github).listRepoFiles("https://github.com/acme/private-api", "main", "host-pat");
    verify(github, never())
        .listRepoFiles("https://github.com/acme/private-api", "main", "invitee-pat");
  }

  @Test
  void readRepoFile_rejectsRepoThatIsNotLinkedToProject() {
    UUID repoId = UUID.randomUUID();
    String projectId = "project-1";
    var caller =
        new AppUserDetails(
            AppUser.builder().id("invitee").email("invitee@example.com").status("ACTIVE").build(),
            null);

    when(projectRepoLinkRepo.findByProjectIdAndRepoId(projectId, repoId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.readRepoFile(projectId, repoId, "README.md", caller))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404 NOT_FOUND");
    verify(projectAccessGuard).assertCanRead(projectId, caller);
    verifyNoInteractions(github);
  }
}
