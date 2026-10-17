package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SourceTextIntegrityTest {

  private static final Set<String> TEXT_EXTENSIONS =
      Set.of(
          ".java", ".kt", ".ts", ".tsx", ".js", ".mjs", ".json", ".yml", ".yaml", ".xml", ".sql",
          ".md", ".txt", ".sh", ".py", ".css", ".html");

  @Test
  void trackedSourceTextContainsNoNulBytes() throws IOException {
    Path root = Path.of("").toAbsolutePath().normalize();
    List<String> offenders = new ArrayList<>();

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            if (!dir.equals(root) && isGeneratedOrDependency(root.relativize(dir))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attributes)
              throws IOException {
            if (attributes.isRegularFile()
                && !isGeneratedOrDependency(root.relativize(path))
                && isTextSource(path.getFileName().toString())) {
              for (byte value : Files.readAllBytes(path)) {
                if (value == 0) {
                  offenders.add(root.relativize(path).toString());
                  break;
                }
              }
            }
            return FileVisitResult.CONTINUE;
          }
        });

    assertThat(offenders).as("text source files containing NUL bytes").isEmpty();
  }

  private static boolean isGeneratedOrDependency(Path relative) {
    String value = relative.toString().replace('\\', '/');
    return value.equals(".git")
        || value.startsWith(".git/")
        || value.equals(".codex-work")
        || value.startsWith(".codex-work/")
        || value.equals(".claude")
        || value.startsWith(".claude/")
        || value.equals("target")
        || value.startsWith("target/")
        || value.endsWith("/node_modules")
        || value.contains("/node_modules/")
        || value.equals("frontend/dist")
        || value.startsWith("frontend/dist/")
        || value.equals("src/main/resources/static/assets")
        || value.startsWith("src/main/resources/static/assets/");
  }

  private static boolean isTextSource(String name) {
    return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith)
        || name.equals("Dockerfile")
        || name.equals("Makefile");
  }
}
