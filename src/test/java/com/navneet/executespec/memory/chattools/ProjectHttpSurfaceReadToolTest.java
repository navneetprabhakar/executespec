package com.navneet.executespec.memory.chattools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.rag.CodeIndexWorkspaceMaterializer;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectHttpSurfaceReadToolTest {

  @TempDir Path workspace;

  private final ProjectRepoLinkRepository links = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepoRepository repositories = mock(ProjectRepoRepository.class);
  private final CodeIndexWorkspaceMaterializer materializer =
      mock(CodeIndexWorkspaceMaterializer.class);
  private final ObjectMapper json = new ObjectMapper();
  private final ProjectHttpSurfaceReadTool tool =
      new ProjectHttpSurfaceReadTool(
          links, repositories, materializer, new SpringHttpSurfaceExtractor(), json);

  private ProjectRepo repository;
  private ProjectChatTools.ToolContext context;

  @BeforeEach
  void setUp() {
    UUID repoId = UUID.randomUUID();
    repository =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/montecarlo")
            .lastSyncedSha("a".repeat(40))
            .build();
    when(links.findByProjectId("project-1"))
        .thenReturn(
            List.of(ProjectRepoLink.builder().projectId("project-1").repoId(repoId).build()));
    when(repositories.findById(repoId)).thenReturn(Optional.of(repository));
    context = new ProjectChatTools.ToolContext("project-1", principal(), "caller-pat");
  }

  @Test
  void combinesServletContextAndControllerMappingIntoExactPublicRoute() throws Exception {
    Path resources = workspace.resolve("src/main/resources");
    Path source = workspace.resolve("src/main/java/acme/SimulationController.java");
    Files.createDirectories(resources);
    Files.createDirectories(source.getParent());
    Files.writeString(
        resources.resolve("application.properties"), "server.servlet.context-path=/simulation\n");
    Files.writeString(
        source,
        """
        package acme;
        import org.springframework.web.bind.annotation.PostMapping;
        import org.springframework.web.bind.annotation.RestController;
        @RestController
        class SimulationController {
          @PostMapping("casino-game") Object play() { return null; }
        }
        """);
    when(materializer.materialize("project-1", repository, null, "caller-pat"))
        .thenReturn(workspace);

    JsonNode result = call("{}");

    assertThat(result.path("ok").asBoolean()).isTrue();
    assertThat(result.path("applicable").asBoolean()).isTrue();
    assertThat(result.path("framework").asText()).isEqualTo("SPRING_MVC");
    assertThat(result.path("routes").size()).isEqualTo(1);
    assertThat(result.path("routes").get(0).path("method").asText()).isEqualTo("POST");
    assertThat(result.path("routes").get(0).path("path").asText())
        .isEqualTo("/simulation/casino-game");
    assertThat(result.path("routes").get(0).path("source").asText())
        .contains("SimulationController.java");
    verify(materializer).materialize("project-1", repository, null, "caller-pat");
  }

  @Test
  void doesNotClassifyNodeRepositoryAsSpringMvcWhenExtractionIsEmpty() throws Exception {
    Path source = workspace.resolve("src/catalog.js");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "export function filterCatalog(items) { return [...items]; }\n");
    Files.writeString(workspace.resolve("package.json"), "{\"type\":\"module\"}\n");
    when(materializer.materialize("project-1", repository, null, "caller-pat"))
        .thenReturn(workspace);

    JsonNode result = call("{}");

    assertThat(result.path("ok").asBoolean()).isTrue();
    assertThat(result.path("applicable").asBoolean()).isFalse();
    assertThat(result.path("framework").asText()).isEqualTo("NOT_ESTABLISHED");
    assertThat(result.path("reasonCode").asText())
        .isEqualTo("NO_DETERMINISTIC_SPRING_MVC_EVIDENCE");
    assertThat(result.path("routes")).isEmpty();
  }

  @Test
  void refusesAnUnlinkedRepositoryBeforeMaterialization() throws Exception {
    JsonNode result = call("{\"repoFullName\":\"other/private\"}");

    assertThat(result.path("ok").asBoolean()).isFalse();
    assertThat(result.path("code").asText()).isEqualTo("REPOSITORY_NOT_LINKED");
    verifyNoInteractions(materializer);
  }

  private JsonNode call(String input) throws Exception {
    return json.readTree(tool.bind(context).call(input));
  }

  private static AppUserDetails principal() {
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("user@example.com")
            .globalRole("USER")
            .status("ACTIVE")
            .build();
    return new AppUserDetails(user, null);
  }
}
