package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

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
}
