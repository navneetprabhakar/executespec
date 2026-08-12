package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class PublicationTargetResolverTest {

  @Test
  void newRepositoryTargetIsDeterministicAndMatchesOnlyItsExactRemote() {
    AgentProperties properties = new AgentProperties();
    properties.getGithub().setDefaultOwner("forge-owner");
    PublicationTargetResolver resolver =
        new PublicationTargetResolver(
            new ObjectMapper(),
            mock(ProjectRepoRepository.class),
            mock(ProjectRepository.class),
            properties);
    PipelineRun run =
        PipelineRun.builder()
            .runId("12345678-abcd-4321-acde-1234567890ab")
            .repoMode("NEW")
            .scmProvider("GITHUB")
            .runTitle("Generated app")
            .build();

    PublicationTargetResolver.Target target = resolver.resolve(run).getFirst();

    assertThat(target.repositoryIdentity())
        .isEqualTo("GITHUB:forge-owner/executespec-12345678abcd4321");
    assertThat(target.provisionRequired()).isTrue();
    assertThat(target.publicationBranch()).isEqualTo("main");
    assertThat(
            PublicationTargetResolver.canonicalRemoteIdentity(
                target.repositoryIdentity(),
                "https://github.com/forge-owner/executespec-12345678abcd4321.git"))
        .isEqualTo(target.repositoryIdentity());
    assertThat(
            PublicationTargetResolver.canonicalRemoteIdentity(
                target.repositoryIdentity(), "https://github.com/attacker/wrong.git"))
        .isNotEqualTo(target.repositoryIdentity());
  }

  @Test
  void existingRemoteNeverAcquiresRepositoryProvisionAuthority() {
    AgentProperties properties = new AgentProperties();
    PublicationTargetResolver resolver =
        new PublicationTargetResolver(
            new ObjectMapper(),
            mock(ProjectRepoRepository.class),
            mock(ProjectRepository.class),
            properties);
    PipelineRun existing =
        PipelineRun.builder()
            .runId("12345678-abcd-4321-acde-1234567890ab")
            .repoMode("EXISTING")
            .scmProvider("GITHUB")
            .repoUrl("https://github.com/owner/repository.git")
            .build();

    PublicationTargetResolver.Target target = resolver.resolve(existing).getFirst();

    assertThat(target.repositoryIdentity()).isEqualTo("GITHUB:owner/repository");
    assertThat(target.provisionRequired()).isFalse();
  }

  @Test
  void credentialsInRemoteCoordinatesAreRejected() {
    assertThatThrownBy(
            () ->
                PublicationTargetResolver.canonicalRemoteIdentity(
                    "GITHUB:owner/repo", "https://token@github.com/owner/repo.git"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CONTAINS_CREDENTIAL");
  }
}
