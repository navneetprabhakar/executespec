package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositorySearchServiceTest {
  @TempDir Path root;

  @Test
  void returnsStableTypedCitationsAndPaging() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve("src/B.java"), "needle\n");
    Files.writeString(root.resolve("src/A.java"), "needle\nother\n");
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve("target/Ignored.java"), "needle\n");

    var service = new RepositorySearchService();
    var result =
        service.search(
            root,
            new RepositorySearchService.SearchRequest(
                "needle", RepositorySearchService.SearchMode.EXACT, true, null, "*.java", 1, 0));

    assertThat(result.engine()).isEqualTo("java-deterministic");
    assertThat(result.repositorySha()).isNotBlank();
    assertThat(result.inventoryFingerprint()).hasSize(64);
    assertThat(result.queryFingerprint()).hasSize(64);
    assertThat(result.totalMatches()).isEqualTo(2);
    assertThat(result.hits()).hasSize(1);
    assertThat(result.hits().getFirst().path()).isEqualTo("src/A.java");
    assertThat(result.hits().getFirst().line()).isEqualTo(1);
    assertThat(result.hasMore()).isTrue();
    assertThat(result.nextOffset()).isEqualTo(1);
  }

  @Test
  void confinesSearchAndRejectsInvalidRegex() throws Exception {
    Files.writeString(root.resolve("a.txt"), "needle\n");
    var service = new RepositorySearchService();
    var result =
        service.search(
            root,
            new RepositorySearchService.SearchRequest(
                "needle", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));
    assertThat(result.hits())
        .extracting(RepositorySearchService.SearchHit::path)
        .containsExactly("a.txt");
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            service.search(
                root,
                new RepositorySearchService.SearchRequest(
                    "[", RepositorySearchService.SearchMode.REGEX, true, null, null, 50, 0)));
    org.junit.jupiter.api.Assertions.assertThrows(
        SecurityException.class,
        () ->
            service.search(
                root,
                new RepositorySearchService.SearchRequest(
                    "needle", RepositorySearchService.SearchMode.EXACT, true, "..", null, 50, 0)));
  }

  @Test
  void refreshesCachedLinesWhenAWorkingTreeFileChanges() throws Exception {
    Path file = root.resolve("src.txt");
    Files.writeString(file, "before\n");
    var service = new RepositorySearchService();
    var request =
        new RepositorySearchService.SearchRequest(
            "before", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);

    assertThat(service.search(root, request).totalMatches()).isEqualTo(1);
    Files.writeString(file, "after\n");

    assertThat(service.search(root, request).totalMatches()).isZero();
    assertThat(
            service
                .search(
                    root,
                    new RepositorySearchService.SearchRequest(
                        "after", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0))
                .totalMatches())
        .isEqualTo(1);
  }

  @Test
  void gitInventoryIncludesTrackedAndNonIgnoredFilesButExcludesIgnoredTrees() throws Exception {
    runGit("init", "-q");
    Files.writeString(root.resolve(".gitignore"), ".generated/\n");
    Files.writeString(root.resolve("tracked.txt"), "needle\n");
    Files.createDirectories(root.resolve(".generated"));
    Files.writeString(root.resolve(".generated/ignored.txt"), "needle\n");
    runGit("add", "tracked.txt", ".gitignore");

    var result =
        new RepositorySearchService()
            .search(
                root,
                new RepositorySearchService.SearchRequest(
                    "needle", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));

    assertThat(result.hits())
        .extracting(RepositorySearchService.SearchHit::path)
        .containsExactly("tracked.txt");
  }

  private void runGit(String... args) throws Exception {
    var command = new java.util.ArrayList<>(List.of("git", "-C", root.toString()));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    org.junit.jupiter.api.Assertions.assertEquals(0, exit, output);
  }
}
