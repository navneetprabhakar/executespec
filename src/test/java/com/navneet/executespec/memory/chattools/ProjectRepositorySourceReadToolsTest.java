package com.navneet.executespec.memory.chattools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.rag.CodeIndexWorkspaceMaterializer;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRepositorySourceReadToolsTest {

  @TempDir Path temp;

  private final ProjectRepoLinkRepository links = mock(ProjectRepoLinkRepository.class);
  private final ProjectRepoRepository repos = mock(ProjectRepoRepository.class);
  private final CodeIndexWorkspaceMaterializer materializer =
      mock(CodeIndexWorkspaceMaterializer.class);
  private final ObjectMapper json = new ObjectMapper();
  private final ProjectRepositorySourceReadTools tools =
      new ProjectRepositorySourceReadTools(
          links, repos, materializer, new GeneratedSecretScanner(), json);

  private ProjectRepo repository;
  private ProjectChatTools.ToolContext context;

  @BeforeEach
  void setUpLinkedRepository() {
    UUID repoId = UUID.randomUUID();
    repository =
        ProjectRepo.builder()
            .id(repoId)
            .githubRepoFullName("acme/service")
            .lastSyncedSha("a".repeat(40))
            .build();
    when(links.findByProjectId("project-1"))
        .thenReturn(
            List.of(ProjectRepoLink.builder().projectId("project-1").repoId(repoId).build()));
    when(repos.findById(repoId)).thenReturn(java.util.Optional.of(repository));
    context = new ProjectChatTools.ToolContext("project-1", principal(), "caller-github-pat");
  }

  @Test
  void readsExactInclusiveLineRangeFromCanonicalMaterializedRevision() throws Exception {
    Path source = temp.resolve("src/main/java/acme/Service.java");
    Files.createDirectories(source.getParent());
    Files.writeString(
        source,
        String.join(
            "\n",
            "package acme;",
            "",
            "class Service {",
            "  String greet(String name) {",
            "    return \"hello \" + name;",
            "  }",
            "}"));
    when(materializer.materialize("project-1", repository, null, "caller-github-pat"))
        .thenReturn(temp);

    JsonNode result =
        call(
            """
            {"filePath":"src/main/java/acme/Service.java","startLine":3,"endLine":6}
            """);

    assertThat(result.path("ok").asBoolean()).isTrue();
    assertThat(result.path("repo").asText()).isEqualTo("acme/service");
    assertThat(result.path("returnedStartLine").asInt()).isEqualTo(3);
    assertThat(result.path("returnedEndLine").asInt()).isEqualTo(6);
    assertThat(result.path("linesReturned").asInt()).isEqualTo(4);
    assertThat(result.path("content").asText())
        .isEqualTo(
            """
            class Service {
              String greet(String name) {
                return "hello " + name;
              }""");
    assertThat(result.path("truncated").asBoolean()).isFalse();
    verify(materializer).materialize("project-1", repository, null, "caller-github-pat");
  }

  @Test
  void rejectsOversizedOrMalformedRangeBeforeMaterialization() throws Exception {
    JsonNode oversized = call("{\"filePath\":\"src/App.java\",\"startLine\":1,\"endLine\":201}");
    JsonNode malformed = call("{\"filePath\":\"src/App.java\",\"startLine\":\"1\",\"endLine\":2}");

    assertThat(oversized.path("code").asText()).isEqualTo("RANGE_TOO_LARGE");
    assertThat(malformed.path("code").asText()).isEqualTo("INVALID_ARGUMENT");
    verifyNoInteractions(materializer);
  }

  @Test
  void explicitRepositoryMustBeLinkedToTheCurrentProject() throws Exception {
    JsonNode result =
        call(
            """
            {"filePath":"src/App.java","startLine":1,"endLine":20,"repoFullName":"other/private"}
            """);

    assertThat(result.path("ok").asBoolean()).isFalse();
    assertThat(result.path("code").asText()).isEqualTo("REPOSITORY_NOT_LINKED");
    verifyNoInteractions(materializer);
  }

  @Test
  void rejectsTraversalAbsoluteGitMetadataAndCredentialPathsBeforeMaterialization()
      throws Exception {
    for (String rejected :
        List.of("../outside.java", "/etc/passwd", ".git/config", ".env", "keys/id_rsa")) {
      JsonNode result =
          call(
              json.createObjectNode()
                  .put("filePath", rejected)
                  .put("startLine", 1)
                  .put("endLine", 10)
                  .toString());
      assertThat(result.path("ok").asBoolean()).as(rejected).isFalse();
      assertThat(result.path("code").asText())
          .as(rejected)
          .isIn("PATH_REJECTED", "GIT_METADATA_REJECTED", "SENSITIVE_PATH");
    }
    verifyNoInteractions(materializer);
  }

  @Test
  void rejectsSymlinkEscapeEvenWhenLeafLooksLikeSource() throws Exception {
    Path outside = Files.createTempFile("outside-source", ".java");
    try {
      Files.writeString(outside, "class HostSecret {}");
      Path link = temp.resolve("src/Escape.java");
      Files.createDirectories(link.getParent());
      Files.createSymbolicLink(link, outside);
      when(materializer.materialize("project-1", repository, null, "caller-github-pat"))
          .thenReturn(temp);

      JsonNode result = call("{\"filePath\":\"src/Escape.java\",\"startLine\":1,\"endLine\":10}");

      assertThat(result.path("ok").asBoolean()).isFalse();
      assertThat(result.path("code").asText()).isEqualTo("PATH_REJECTED");
      assertThat(result.toString()).doesNotContain("HostSecret", outside.toString());
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void blocksRecognizedCredentialContentFromModelContext() throws Exception {
    Path source = temp.resolve("src/AccidentalSecret.java");
    Files.createDirectories(source.getParent());
    String token = "ghp_" + "a".repeat(35);
    Files.writeString(source, "class AccidentalSecret { String token = \"" + token + "\"; }");
    when(materializer.materialize("project-1", repository, null, "caller-github-pat"))
        .thenReturn(temp);

    JsonNode result =
        call("{\"filePath\":\"src/AccidentalSecret.java\",\"startLine\":1,\"endLine\":10}");

    assertThat(result.path("code").asText()).isEqualTo("SOURCE_CONTENT_SENSITIVE");
    assertThat(result.toString()).doesNotContain(token);
  }

  @Test
  void capsSerializedContextAndReturnsAResumableNextLine() throws Exception {
    Path source = temp.resolve("src/Large.java");
    Files.createDirectories(source.getParent());
    String line = "x".repeat(180);
    Files.writeString(source, String.join("\n", java.util.Collections.nCopies(200, line)));
    when(materializer.materialize("project-1", repository, null, "caller-github-pat"))
        .thenReturn(temp);

    JsonNode result = call("{\"filePath\":\"src/Large.java\",\"startLine\":1,\"endLine\":200}");

    assertThat(result.path("ok").asBoolean()).isTrue();
    assertThat(result.path("truncated").asBoolean()).isTrue();
    assertThat(result.path("hasMore").asBoolean()).isTrue();
    assertThat(result.path("nextStartLine").asInt()).isGreaterThan(1);
    assertThat(result.path("content").asText().length())
        .isLessThanOrEqualTo(ProjectRepositorySourceReadTools.MAX_CONTENT_CHARS);
  }

  @Test
  void bitbucketReadUsesOnlyAuthenticatedBitbucketCredential() throws Exception {
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("user@example.com")
            .globalRole("USER")
            .status("ACTIVE")
            .bitbucketUsername("bb-user")
            .bitbucketAppPassword("bb-secret")
            .build();
    context =
        new ProjectChatTools.ToolContext(
            "project-1", new AppUserDetails(user, null), "must-not-cross-to-bitbucket");
    repository.setScmProvider("BITBUCKET");
    Path source = temp.resolve("src/App.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "class App {}");
    when(materializer.materialize("project-1", repository, "bb-user", "bb-secret"))
        .thenReturn(temp);

    assertThat(
            call("{\"filePath\":\"src/App.java\",\"startLine\":1,\"endLine\":1}")
                .path("ok")
                .asBoolean())
        .isTrue();
    verify(materializer).materialize("project-1", repository, "bb-user", "bb-secret");
    verify(materializer, never())
        .materialize("project-1", repository, null, "must-not-cross-to-bitbucket");
  }

  private JsonNode call(String input) throws Exception {
    return json.readTree(tools.bind(context).call(input));
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
