package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectProviderResolverTest {

  private ProjectRepository projectRepository;
  private AppUserRepository appUserRepository;
  private ProjectProviderResolver resolver;

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    appUserRepository = mock(AppUserRepository.class);
    resolver = new ProjectProviderResolver(projectRepository, appUserRepository);
  }

  @Test
  void explicitProjectProviderWinsForAllSupportedProviders() {
    for (LlmProvider provider :
        new LlmProvider[] {LlmProvider.ANTHROPIC, LlmProvider.OPENAI, LlmProvider.DEEPSEEK}) {
      Project project = Project.builder().aiProvider(provider).createdBy("u-1").build();
      assertThat(resolver.resolve(project)).isEqualTo(provider);
    }
  }

  @Test
  void legacyNullProjectProviderUsesCreatorPreference() {
    AppUser user = new AppUser();
    user.setPreferredAiProvider("deepseek");
    when(appUserRepository.findById("u-1")).thenReturn(Optional.of(user));

    Project project = Project.builder().aiProvider(null).createdBy("u-1").build();
    assertThat(resolver.resolve(project)).isEqualTo(LlmProvider.DEEPSEEK);
  }

  @Test
  void missingOrInvalidPreferenceFailsClosed() {
    AppUser user = new AppUser();
    user.setPreferredAiProvider("retired-provider");
    when(appUserRepository.findById("u-1")).thenReturn(Optional.of(user));

    assertThatThrownBy(
            () -> resolver.resolve(Project.builder().aiProvider(null).createdBy("u-1").build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("invalid account provider preference");
    assertThatThrownBy(() -> resolver.resolve((Project) null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("project required");
  }

  @Test
  void explicitRunOverrideWinsOverProjectPolicy() {
    Project project = Project.builder().id("p-1").aiProvider(LlmProvider.OPENAI).build();
    when(projectRepository.findById("p-1")).thenReturn(Optional.of(project));
    assertThat(resolver.resolve("p-1", LlmProvider.DEEPSEEK)).isEqualTo(LlmProvider.DEEPSEEK);
    assertThat(resolver.resolve("p-1", null)).isEqualTo(LlmProvider.OPENAI);
  }
}
