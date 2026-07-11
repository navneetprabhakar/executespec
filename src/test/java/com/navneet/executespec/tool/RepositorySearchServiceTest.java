package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
  void detectsSameSizeMutationEvenWhenMtimeIsRestored() throws Exception {
    Path file = root.resolve("same-size.txt");
    Files.writeString(file, "before\n");
    FileTime originalMtime = Files.getLastModifiedTime(file);
    var service = new RepositorySearchService();
    var beforeRequest =
        new RepositorySearchService.SearchRequest(
            "before", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);
    var afterRequest =
        new RepositorySearchService.SearchRequest(
            "after ", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0);

    var before = service.search(root, beforeRequest);
    Files.writeString(file, "after \n");
    // Keep both metadata signals unchanged: freshness must come from content, not size/mtime.
    Files.setLastModifiedTime(file, originalMtime);

    assertThat(service.search(root, beforeRequest).totalMatches()).isZero();
    assertThat(service.search(root, afterRequest).totalMatches()).isEqualTo(1);
    assertThat(service.search(root, afterRequest).inventoryFingerprint())
        .isNotEqualTo(before.inventoryFingerprint());
  }

  @Test
  void supportsConcurrentReadOnlySearchesWithConsistentSnapshots() throws Exception {
    for (int i = 0; i < 12; i++) {
      Files.writeString(root.resolve("file-" + i + ".txt"), "shared\nterm-" + i + "\n");
    }
    var service = new RepositorySearchService();
    var request =
        new RepositorySearchService.SearchRequest(
            "shared", RepositorySearchService.SearchMode.EXACT, true, null, "*.txt", 100, 0);

    try (var pool = Executors.newFixedThreadPool(8)) {
      var futures =
          java.util.stream.IntStream.range(0, 32)
              .mapToObj(ignored -> pool.submit(() -> service.search(root, request)))
              .toList();
      pool.shutdown();
      org.junit.jupiter.api.Assertions.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
      for (var future : futures) {
        assertThat(future.get().totalMatches()).isEqualTo(12);
        assertThat(future.get().hits()).hasSize(12);
      }
    }
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

  @Test
  void symbolModeRanksDeterministicDeclarationsAheadOfReferencesAcrossLanguages() throws Exception {
    Files.writeString(root.resolve("z-use.ts"), "export const result = Widget;\n");
    Files.writeString(root.resolve("a-widget.ts"), "export class Widget {}\n");
    Files.writeString(root.resolve("b-widget.py"), "def Widget(value):\n    return value\n");

    var result =
        new RepositorySearchService()
            .search(
                root,
                new RepositorySearchService.SearchRequest(
                    "Widget", RepositorySearchService.SearchMode.SYMBOL, true, null, null, 50, 0));

    assertThat(result.hits()).hasSize(3);
    assertThat(result.hits().get(0).matchType())
        .isEqualTo(RepositorySearchService.MatchType.SYMBOL_DEFINITION);
    assertThat(result.hits().get(0).relevanceReason()).contains("symbol definition");
    assertThat(result.hits().get(1).matchType())
        .isEqualTo(RepositorySearchService.MatchType.SYMBOL_DEFINITION);
    assertThat(result.hits().get(2).matchType())
        .isEqualTo(RepositorySearchService.MatchType.SYMBOL_REFERENCE);
    assertThat(result.hits().get(0).repositoryFingerprint())
        .isEqualTo(result.inventoryFingerprint());
    assertThat(result.repositoryFingerprint()).hasSize(64);
  }

  @Test
  void symbolModeClassifiesImportTestAndCallSiteEvidence() throws Exception {
    Files.writeString(
        root.resolve("Widget.java"),
        "import com.example.Widget;\nclass Runner { void run() { Widget(); } }\n");
    Files.writeString(
        root.resolve("WidgetTest.java"), "@Test void widget() { Widget value = new Widget(); }\n");

    var result =
        new RepositorySearchService()
            .search(
                root,
                new RepositorySearchService.SearchRequest(
                    "Widget", RepositorySearchService.SearchMode.SYMBOL, true, null, null, 50, 0));

    assertThat(result.hits())
        .extracting(RepositorySearchService.SearchHit::matchType)
        .contains(
            RepositorySearchService.MatchType.IMPORT,
            RepositorySearchService.MatchType.TEST_REFERENCE,
            RepositorySearchService.MatchType.CALL_SITE);
  }

  @Test
  void relationshipsExposeImportsTestsAndCallsAgainstFreshRepositoryFingerprint() throws Exception {
    Files.writeString(root.resolve("Widget.java"), "package demo; public class Widget {}\n");
    Files.writeString(
        root.resolve("Runner.java"),
        "import demo.Widget;\nclass Runner { void run() { Widget(); } }\n");
    Files.writeString(
        root.resolve("WidgetTest.java"), "@Test void widget() { Widget value = new Widget(); }\n");

    var service = new RepositorySearchService();
    var result =
        service.relationships(
            root, new RepositorySearchService.RelationshipRequest("Widget", null, "*.java", 50));

    assertThat(result.engine()).isEqualTo("java-deterministic-relationship-index");
    assertThat(result.repositoryFingerprint()).hasSize(64);
    assertThat(result.edges())
        .extracting(RepositorySearchService.RelationshipEdge::relationshipType)
        .containsExactly(
            RepositorySearchService.RelationshipType.IMPORT,
            RepositorySearchService.RelationshipType.TEST_TO_SOURCE,
            RepositorySearchService.RelationshipType.CALL_SITE);
    assertThat(result.edges())
        .allSatisfy(edge -> assertThat(edge.targetPath()).isEqualTo("Widget.java"));
    assertThat(result.edges().get(0).repositoryFingerprint())
        .isEqualTo(result.inventoryFingerprint());

    Files.writeString(root.resolve("Widget.java"), "package demo; public class Other {}\n");
    var changed =
        service.relationships(
            root, new RepositorySearchService.RelationshipRequest("Widget", null, "*.java", 50));
    assertThat(changed.repositoryFingerprint()).isNotEqualTo(result.repositoryFingerprint());
  }

  @Test
  void relationshipsAreWorkspaceConfinedAndPaged() throws Exception {
    Files.writeString(root.resolve("Widget.java"), "class Widget {}\n");
    Files.writeString(root.resolve("WidgetTest.java"), "@Test void widget() { Widget(); }\n");
    var service = new RepositorySearchService();

    var page =
        service.relationships(
            root,
            new RepositorySearchService.RelationshipRequest("Widget", true, ".", "*.java", 1, 1));
    assertThat(page.edges()).hasSize(1);
    assertThat(page.totalRelationships()).isEqualTo(1);
    assertThat(page.hasMore()).isFalse();

    assertThatThrownBy(
            () ->
                service.relationships(
                    root,
                    new RepositorySearchService.RelationshipRequest(
                        "Widget", true, "..", "*.java", 50, 0)))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void citationValidationRejectsWorkingTreeMutation() throws Exception {
    Path file = root.resolve("src/Widget.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "class Widget {}\n");
    var service = new RepositorySearchService();
    var result =
        service.search(
            root,
            new RepositorySearchService.SearchRequest(
                "Widget", RepositorySearchService.SearchMode.SYMBOL, true, null, null, 50, 0));
    var citation = result.hits().getFirst();

    assertThat(service.isCitationCurrent(root, result, citation)).isTrue();
    Files.writeString(file, "class WidgetRenamed {}\n");
    assertThat(service.isCitationCurrent(root, result, citation)).isFalse();
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
