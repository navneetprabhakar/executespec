package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    try (var paths = Files.walk(root)) {
      paths
          .filter(Files::isRegularFile)
          .filter(path -> !isGeneratedOrDependency(root.relativize(path)))
          .filter(path -> isTextSource(path.getFileName().toString()))
          .forEach(
              path -> {
                try {
                  byte[] bytes = Files.readAllBytes(path);
                  for (byte value : bytes) {
                    if (value == 0) {
                      offenders.add(root.relativize(path).toString());
                      break;
                    }
                  }
                } catch (IOException error) {
                  throw new IllegalStateException("Could not inspect " + path, error);
                }
              });
    }

    assertThat(offenders).as("text source files containing NUL bytes").isEmpty();
  }

  private static boolean isGeneratedOrDependency(Path relative) {
    String value = relative.toString().replace('\\', '/');
    return value.startsWith(".git/")
        || value.startsWith("target/")
        || value.contains("/node_modules/")
        || value.startsWith("frontend/dist/")
        || value.startsWith("src/main/resources/static/assets/");
  }

  private static boolean isTextSource(String name) {
    return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith)
        || name.equals("Dockerfile")
        || name.equals("Makefile");
  }
}
