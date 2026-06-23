package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("PreviewFileSignals")
class PreviewFileSignalsTest {

  @Test
  @DisplayName("fileContainsPattern uses literal vararg OR and ignores oversized files")
  void fileContainsPatternLiteralOr(@TempDir Path ws) throws IOException {
    Path small = ws.resolve("app.js");
    Files.writeString(small, "const app = express();\napp.listen(3000);\n");

    assertThat(PreviewFileSignals.fileContainsPattern(small, "fastify", "app.listen")).isTrue();
    assertThatThrownBy(() -> PreviewFileSignals.fileContainsPattern(small, "fastify|app.listen"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("use varargs OR semantics");

    Path large = ws.resolve("large.txt");
    Files.writeString(large, "x".repeat(51 * 1024) + "spring-boot-starter");
    assertThat(PreviewFileSignals.fileContainsPattern(large, "spring-boot-starter")).isFalse();
  }

  @Test
  @DisplayName("detects root-level static HTML and JavaScript/TypeScript signals")
  void detectsStaticAndNodeSignals(@TempDir Path ws) throws IOException {
    assertThat(PreviewFileSignals.hasStaticHtml(ws)).isFalse();
    assertThat(PreviewFileSignals.hasJsOrTsFiles(ws)).isFalse();

    Files.writeString(ws.resolve("index.html"), "<main>Hello</main>");
    Files.writeString(ws.resolve("server.mjs"), "console.log('server');");

    assertThat(PreviewFileSignals.hasStaticHtml(ws)).isTrue();
    assertThat(PreviewFileSignals.hasJsOrTsFiles(ws)).isTrue();
  }

  @Test
  @DisplayName("detects root-level Java files without walking nested directories")
  void detectsRootJavaFilesOnly(@TempDir Path ws) throws IOException {
    Files.createDirectories(ws.resolve("src"));
    Files.writeString(ws.resolve("src/Main.java"), "class Main {}\n");
    assertThat(PreviewFileSignals.hasJavaFiles(ws)).isFalse();

    Files.writeString(ws.resolve("Main.java"), "class Main {}\n");
    assertThat(PreviewFileSignals.hasJavaFiles(ws)).isTrue();
  }

  @Test
  @DisplayName("missing or unreadable paths fail closed")
  void missingPathsFailClosed(@TempDir Path ws) {
    Path missing = ws.resolve("missing.txt");
    assertThat(PreviewFileSignals.fileContainsPattern(missing, "needle")).isFalse();
    assertThat(PreviewFileSignals.hasStaticHtml(missing)).isFalse();
    assertThat(PreviewFileSignals.hasJsOrTsFiles(missing)).isFalse();
    assertThat(PreviewFileSignals.hasJavaFiles(missing)).isFalse();
  }
}
