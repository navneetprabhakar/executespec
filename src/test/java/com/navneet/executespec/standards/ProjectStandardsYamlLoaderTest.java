package com.navneet.executespec.standards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.folder.ProjectConfigPathResolver;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectStandardsYamlLoaderTest {

  @TempDir Path workspace;
  @Mock ProjectRepoService projectRepoService;
  @Mock ProjectConfigPathResolver configPathResolver;

  private ProjectStandardsYamlLoader loader;

  @BeforeEach
  void setUp() {
    loader = new ProjectStandardsYamlLoader(projectRepoService, configPathResolver);
  }

  @Test
  void loadsOnlyExactSelectedRepoConfigInSelectedOrder() throws Exception {
    UUID apiId = UUID.randomUUID();
    UUID webId = UUID.randomUUID();
    ProjectRepo api = repo(apiId, "acme/api");
    ProjectRepo web = repo(webId, "acme/web");
    when(projectRepoService.resolveForRun("project-1", List.of(webId, apiId)))
        .thenReturn(List.of(api, web));
    when(configPathResolver.resolveConfigFilePath("project-1", apiId, "standards.yaml"))
        .thenReturn(".executespec/config/standards.yaml");
    when(configPathResolver.resolveConfigFilePath("project-1", webId, "standards.yaml"))
        .thenReturn(".executespec/config/project-one/standards.yaml");
    write("repos/api/.executespec/config/standards.yaml", "- id: api\n");
    write("repos/web/.executespec/config/project-one/standards.yaml", "- id: web\n");
    write("repos/web/.executespec/config/other-project/standards.yaml", "- id: foreign\n");

    assertThat(loader.load("project-1", workspace, List.of(webId, apiId)))
        .containsExactly("- id: web\n", "- id: api\n");
  }

  @Test
  void rejectsSelectedConfigSymlink() throws Exception {
    UUID repoId = UUID.randomUUID();
    when(projectRepoService.resolveForRun("project-1", List.of(repoId)))
        .thenReturn(List.of(repo(repoId, "acme/api")));
    when(configPathResolver.resolveConfigFilePath("project-1", repoId, "standards.yaml"))
        .thenReturn(".executespec/config/standards.yaml");
    Path external = Files.createTempFile("external-policy", ".yaml");
    Path policy = workspace.resolve("repos/api/.executespec/config/standards.yaml");
    Files.createDirectories(policy.getParent());
    try {
      Files.createSymbolicLink(policy, external);
      assertThatThrownBy(() -> loader.load("project-1", workspace, List.of(repoId)))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      Files.deleteIfExists(external);
    }
  }

  private void write(String relative, String content) throws Exception {
    Path file = workspace.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static ProjectRepo repo(UUID id, String fullName) {
    return ProjectRepo.builder().id(id).githubRepoFullName(fullName).build();
  }
}
