package com.navneet.executespec.repo;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ScmRepositoryClientRegistryTest {
  @Test
  void unknownProviderFailsClosedInsteadOfFallingBackToGitHub() {
    ScmRepositoryClient github = mock(ScmRepositoryClient.class);
    when(github.provider()).thenReturn(ScmProvider.GITHUB);
    ScmRepositoryClientRegistry registry = new ScmRepositoryClientRegistry(List.of(github));

    assertThatThrownBy(() -> registry.require("GITLAB"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("422 UNPROCESSABLE_ENTITY");
  }

  @Test
  void duplicateProviderAdaptersFailApplicationStartup() {
    ScmRepositoryClient first = mock(ScmRepositoryClient.class);
    ScmRepositoryClient second = mock(ScmRepositoryClient.class);
    when(first.provider()).thenReturn(ScmProvider.BITBUCKET);
    when(second.provider()).thenReturn(ScmProvider.BITBUCKET);

    assertThatThrownBy(() -> new ScmRepositoryClientRegistry(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate SCM client");
  }
}
