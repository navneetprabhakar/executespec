package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.state.repository.RunRepoRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentGitHubServicePublicationTest {

  @TempDir Path temp;

  @Test
  void rejectedWorkspaceStopsBeforeRemoteRepositoryCreationOrPersistence() throws Exception {
    ArtifactPublicationGuard guard = mock(ArtifactPublicationGuard.class);
    RunRepoRepository repository = mock(RunRepoRepository.class);
    doThrow(
            new ArtifactPublicationGuard.PublicationBlockedException(
                "GITHUB_TOKEN", "generated.txt"))
        .when(guard)
        .assertGitWorkspaceSafe("run-1");
    DeploymentGitHubService service =
        new DeploymentGitHubService(new AgentProperties(), new ObjectMapper(), repository, guard);

    assertThatThrownBy(
            () -> service.createAndPush("run-1", "new-repository", temp, "github_pat_value"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("GITHUB_TOKEN");
    verifyNoInteractions(repository);
  }
}
