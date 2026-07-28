package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.rag.CodeIndexStateStore;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.tool.RepositorySearchService;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RepositoryIntelligenceRegistrationTest {

  @Test
  void springRegistersOnePortBackedByTheAccessCheckedResolver() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(AppUserRepository.class, () -> mock(AppUserRepository.class));
      context.registerBean(OrgRepository.class, () -> mock(OrgRepository.class));
      context.registerBean(OrgMemberRepository.class, () -> mock(OrgMemberRepository.class));
      context.registerBean(ProjectAccessGuard.class, () -> mock(ProjectAccessGuard.class));
      context.registerBean(ProjectRepoRepository.class, () -> mock(ProjectRepoRepository.class));
      context.registerBean(
          ProjectRepoLinkRepository.class, () -> mock(ProjectRepoLinkRepository.class));
      context.registerBean(WorkspaceInitService.class, () -> mock(WorkspaceInitService.class));
      context.registerBean(AgentProperties.class, AgentProperties::new);
      context.registerBean(RepositorySearchService.class, RepositorySearchService::new);
      context.registerBean(CodeIndexStateStore.class, () -> mock(CodeIndexStateStore.class));
      context.register(AccessCheckedRepositoryRootResolver.class);
      context.register(AccessCheckedRepositorySnapshotService.class);
      context.register(NativeRepositoryIntelligenceAdapter.class);
      context.refresh();

      assertThat(context.getBeansOfType(RepositoryRootResolver.class))
          .containsOnlyKeys("accessCheckedRepositoryRootResolver");
      assertThat(context.getBeansOfType(RepositoryIntelligencePort.class))
          .containsOnlyKeys("nativeRepositoryIntelligenceAdapter");
      assertThat(context.getBean(RepositoryIntelligencePort.class))
          .isInstanceOf(NativeRepositoryIntelligenceAdapter.class);
      assertThat(context.getBeansOfType(AccessCheckedRepositorySnapshotService.class))
          .containsOnlyKeys("accessCheckedRepositorySnapshotService");
    }
  }
}
