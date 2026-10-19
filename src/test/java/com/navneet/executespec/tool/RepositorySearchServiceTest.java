package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositorySearchServiceTest {
  @TempDir Path root;

  @Test
  void unicodeSearchMatchesTheActualUtf8SourceInsteadOfMojibake() throws Exception {
    Files.writeString(root.resolve("test_main.py"), "assert slugify(\"İTEM\") == \"i-tem\"\n");
    var result =
        new RepositorySearchService()
            .search(
                root,
                new RepositorySearchService.SearchRequest(
                    "İTEM", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));
    assertThat(result.hits()).hasSize(1);
    assertThat(result.hits().getFirst().toString()).contains("İTEM").doesNotContain("Ä°TEM");
  }

  @Test
  void capturesRepositoryWideSnapshotWithoutFabricatedSearchTerm() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Path source = root.resolve("src/Widget.java");
    Files.writeString(source, "class Widget {}\n");
    var service = new RepositorySearchService();

    var before = service.snapshot(root);
    var search =
        service.search(
            root,
            new RepositorySearchService.SearchRequest(
                "Widget", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));

    assertThat(before.engine()).isEqualTo("java-deterministic");
    assertThat(before.discoveredFiles()).isEqualTo(1);
    assertThat(before.repositorySha()).isEqualTo(search.repositorySha());
    assertThat(before.inventoryFingerprint()).isEqualTo(search.inventoryFingerprint());
    assertThat(before.inventoryFingerprint()).hasSize(64);

    Files.writeString(source, "class Widget { int changed; }\n");
    assertThat(service.snapshot(root).inventoryFingerprint())
        .isNotEqualTo(before.inventoryFingerprint());
  }

  @Test
  void findsMatchesInNonUtf8EncodedSourceFiles() throws Exception {
    // A latin-1 file: the 0xE9 byte ("é" in ISO-8859-1) is a malformed UTF-8 lead byte, so the old
    // strict-UTF-8 readAllLines threw MalformedInputException, the file was silently dropped, and
    // the ASCII token in it read as absent — the model would then re-implement it.
    Path source = root.resolve("Legacy.java");
    byte[] bytes =
        ("class Legacy { /* café needleToken */ }\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    Files.write(source, bytes);
    var service = new RepositorySearchService();

    var search =
        service.search(
            root,
            new RepositorySearchService.SearchRequest(
                "needleToken", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));

    assertThat(search.hits()).isNotEmpty();
    assertThat(search.hits().get(0).path()).isEqualTo("Legacy.java");
  }

  @Test
  void inventoryFingerprintIsIndependentOfMaterializationPath() throws Exception {
    Path first = root.resolve("first");
    Path second = root.resolve("second");
    Files.createDirectories(first.resolve("src"));
    Files.createDirectories(second.resolve("src"));
    Files.writeString(first.resolve("src/Widget.java"), "class Widget {}\n");
    Files.writeString(second.resolve("src/Widget.java"), "class Widget {}\n");
    var service = new RepositorySearchService();

    assertThat(service.snapshot(first).inventoryFingerprint())
        .isEqualTo(service.snapshot(second).inventoryFingerprint());
  }

  @Test
  void rejectsMutationBetweenContentHashAndEvidenceRead() throws Exception {
    Path source = root.resolve("Widget.java");
    Files.writeString(source, "class Widget {}\n");
    AtomicBoolean mutateOnce = new AtomicBoolean(true);
    var service =
        new RepositorySearchService() {
          @Override
          List<String> readIndexedLines(Path workspace, Path file, String contentHash) {
            if (mutateOnce.compareAndSet(true, false)) {
              try {
                Files.writeString(file, "class Widget { int changed; }\n");
              } catch (java.io.IOException failure) {
                throw new IllegalStateException(failure);
              }
            }
            return super.readIndexedLines(workspace, file, contentHash);
          }
        };

    assertThatThrownBy(
            () ->
                service.search(
                    root,
                    new RepositorySearchService.SearchRequest(
                        "Widget",
                        RepositorySearchService.SearchMode.EXACT,
                        true,
                        null,
                        null,
                        50,
                        0)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("changed while reading");
  }

  @Test
  void snapshotMarksTrackedAndUntrackedWorktreeStateAsDirty() throws Exception {
    runGit(root, "init");
    runGit(root, "config", "user.email", "repository-search@example.test");
    runGit(root, "config", "user.name", "Repository Search Test");
    Files.writeString(root.resolve("tracked.txt"), "clean\n");
    runGit(root, "add", "tracked.txt");
    runGit(root, "commit", "-m", "fixture");
    var service = new RepositorySearchService();

    assertThat(service.snapshot(root).dirtyFingerprint()).isNull();
    Files.writeString(root.resolve("untracked.txt"), "not committed\n");
    assertThat(service.snapshot(root).dirtyFingerprint()).isNotBlank();
    Files.writeString(root.resolve("tracked.txt"), "modified\n");
    assertThat(service.snapshot(root).dirtyFingerprint()).isNotBlank();
  }

  @Test
  void snapshotRejectsMissingOrNonDirectoryWorkspace() throws Exception {
    var service = new RepositorySearchService();
    Path file = root.resolve("file.txt");
    Files.writeString(file, "content");

    assertThatThrownBy(() -> service.snapshot(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workspace");
    assertThatThrownBy(() -> service.snapshot(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("directory");
  }

  private static void runGit(Path directory, String... args) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(directory.toString());
    command.addAll(java.util.List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as(output).isZero();
  }

  @Test
  void snapshotRejectsRevisionChangeDuringInventoryCapture() throws Exception {
    Files.writeString(root.resolve("source.txt"), "content\n");
    var service =
        new RepositorySearchService() {
          private int revisionRead;

          @Override
          String readRepositorySha(Path ignored) {
            return revisionRead++ == 0 ? "before" : "after";
          }
        };

    assertThatThrownBy(() -> service.snapshot(root))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("revision changed");
  }

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
  void excludesSymlinkedFilesThatResolveOutsideWorkspace() throws Exception {
    Path outside = Files.createTempDirectory("repository-search-outside");
    Files.writeString(outside.resolve("secret.txt"), "needle\n");
    Path link = root.resolve("linked.txt");
    try {
      Files.createSymbolicLink(link, outside.resolve("secret.txt"));
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
      // Some CI filesystems disable symlink creation; the deterministic contract is covered on
      // platforms that support the filesystem primitive.
      return;
    }

    var result =
        new RepositorySearchService()
            .search(
                root,
                new RepositorySearchService.SearchRequest(
                    "needle", RepositorySearchService.SearchMode.EXACT, true, null, null, 50, 0));

    assertThat(result.hits()).isEmpty();
  }

  @Test
  void rejectsSymlinkedWorkspaceRootInsteadOfRebasingTrustToItsTarget() throws Exception {
    Path outside = Files.createTempDirectory("repository-search-root-outside");
    Files.writeString(outside.resolve("secret.txt"), "needle\n");
    Path link = root.resolve("swapped-root");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
      return;
    }

    assertThatThrownBy(
            () ->
                new RepositorySearchService()
                    .search(
                        link,
                        new RepositorySearchService.SearchRequest(
                            "needle",
                            RepositorySearchService.SearchMode.EXACT,
                            true,
                            null,
                            null,
                            50,
                            0)))
        .isInstanceOf(SecurityException.class)
        .hasMessageContaining("symbolic link");
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
  void boundsDecodedSnapshotCacheByFileCount() throws Exception {
    int files = RepositorySearchService.MAX_CACHED_SNAPSHOTS_PER_WORKSPACE + 32;
    for (int i = 0; i < files; i++) {
      Files.writeString(root.resolve("cache-" + i + ".txt"), "symbol-" + i + "\n");
    }
    var service = new RepositorySearchService();

    service.search(
        root,
        new RepositorySearchService.SearchRequest(
            "missing-symbol", RepositorySearchService.SearchMode.SYMBOL, true, null, null, 50, 0));

    assertThat(service.cachedSnapshotCount(root))
        .isLessThanOrEqualTo(RepositorySearchService.MAX_CACHED_SNAPSHOTS_PER_WORKSPACE);
    assertThat(service.cachedCharacterCount(root))
        .isLessThanOrEqualTo(RepositorySearchService.MAX_CACHED_CHARACTERS_PER_WORKSPACE);
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
  void relationshipsDoNotInventTargetWhenSymbolHasMultipleDefinitions() throws Exception {
    Files.writeString(root.resolve("Mysql.java"), "class DatabaseWrapper {}\n");
    Files.writeString(root.resolve("Postgres.java"), "class DatabaseWrapper {}\n");
    Files.writeString(
        root.resolve("BackendTest.java"),
        "@Test void backend() { DatabaseWrapper wrapper = new DatabaseWrapper(); }\n");

    var result =
        new RepositorySearchService()
            .relationships(
                root,
                new RepositorySearchService.RelationshipRequest(
                    "DatabaseWrapper", null, "*.java", 50));

    assertThat(result.edges())
        .isNotEmpty()
        .allSatisfy(edge -> assertThat(edge.targetPath()).isNull());
  }

  @Test
  void relationshipsAreWorkspaceConfinedAndPaged() throws Exception {
    Files.writeString(root.resolve("Widget.java"), "class Widget {}\n");
    Files.writeString(root.resolve("WidgetTest.java"), "@Test void widget() { Widget(); }\n");
    Files.writeString(root.resolve("Runner.java"), "class Runner { void run() { Widget(); } }\n");
    var service = new RepositorySearchService();

    var page =
        service.relationships(
            root,
            new RepositorySearchService.RelationshipRequest("Widget", true, ".", "*.java", 1, 1));
    assertThat(page.edges()).hasSize(1);
    assertThat(page.totalRelationships()).isEqualTo(2);
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
