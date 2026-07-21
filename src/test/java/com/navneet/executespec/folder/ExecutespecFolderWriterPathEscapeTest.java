package com.navneet.executespec.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpClient;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.project.entity.ProjectRepo;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RR-525 regression — the writer is the last boundary before an SCM write, so a path that merely
 * <em>starts with</em> {@code .executespec/} must not be enough. A contract named {@code
 * ../../../.github/workflows/release} previously turned a narrow "refresh one contract" action into
 * repository-wide write authority.
 */
class ExecutespecFolderWriterPathEscapeTest {

  private final ExecutespecFolderWriter writer =
      new ExecutespecFolderWriter(
          Mockito.mock(HttpClient.class),
          new ObjectMapper(),
          Mockito.mock(com.navneet.executespec.repo.GitHubRepoService.class));

  private ProjectRepo repo() {
    return Mockito.mock(ProjectRepo.class);
  }

  @Test
  void rejectsATraversalPathThatStillStartsWithTheFolderRoot() {
    String escape = ".executespec/contracts/produced/../../../.github/workflows/release.yml";
    // The pre-fix check was exactly this prefix test, which the escape passes.
    assertThat(escape).startsWith(ExecutespecFolderWriter.FOLDER_ROOT);

    assertThatThrownBy(
            () -> writer.writeFiles(repo(), null, Map.of(escape, "content"), "t", "d", "pat"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsEncodedSeparators() {
    assertThatThrownBy(
            () ->
                writer.writeFiles(
                    repo(), null, Map.of(".executespec/a%2f..%2fb.yml", "c"), "t", "d", "pat"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsABackslashSeparator() {
    assertThatThrownBy(
            () ->
                writer.writeFiles(
                    repo(), null, Map.of(".executespec/a\\..\\b.yml", "c"), "t", "d", "pat"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void stillRejectsPathsOutsideTheFolderRootEntirely() {
    assertThatThrownBy(
            () ->
                writer.writeFiles(
                    repo(), null, Map.of("src/main/java/Foo.java", "c"), "t", "d", "pat"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
