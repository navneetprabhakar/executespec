package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewNodeSignalsTest {

  @Test
  void parsePackageJsonReturnsNullWhenFileIsMissingOrMalformed(@TempDir Path workspace)
      throws IOException {
    assertThat(PreviewNodeSignals.parsePackageJson(workspace)).isNull();

    Files.writeString(workspace.resolve("package.json"), "not-json");

    assertThat(PreviewNodeSignals.parsePackageJson(workspace)).isNull();
  }

  @Test
  void entryPointUsesExistingPackageMainBeforeCommonCandidates(@TempDir Path workspace)
      throws IOException {
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("package.json"), "{\"main\":\"src/index.js\"}\n");
    Files.writeString(workspace.resolve("src/index.js"), "console.log('main')\n");
    Files.writeString(workspace.resolve("server.js"), "console.log('server')\n");

    assertThat(PreviewNodeSignals.findEntryPoint(workspace)).isEqualTo("src/index.js");
  }

  @Test
  void entryPointIgnoresMissingPackageMainAndUsesCandidatePriority(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("package.json"), "{\"main\":\"missing.js\"}\n");
    Files.writeString(workspace.resolve("server.js"), "console.log('server')\n");
    Files.writeString(workspace.resolve("app.js"), "console.log('app')\n");

    assertThat(PreviewNodeSignals.findEntryPoint(workspace)).isEqualTo("server.js");
  }

  @Test
  void entryPointUsesAlphabeticalRootJsFallback(@TempDir Path workspace) throws IOException {
    Files.writeString(workspace.resolve("zeta.mjs"), "console.log('z')\n");
    Files.writeString(workspace.resolve("alpha.js"), "console.log('a')\n");

    assertThat(PreviewNodeSignals.findEntryPoint(workspace)).isEqualTo("alpha.js");
  }

  @Test
  void entryPointInDirUsesDirectoryCandidatesWithoutPackageMain(@TempDir Path workspace)
      throws IOException {
    Path backend = workspace.resolve("backend");
    Files.createDirectories(backend.resolve("src"));
    Files.writeString(backend.resolve("src/app.js"), "console.log('app')\n");
    Files.writeString(backend.resolve("server.mjs"), "console.log('server')\n");

    assertThat(PreviewNodeSignals.findEntryPointInDir(backend)).isEqualTo("server.mjs");
  }

  @Test
  void detectBackendPortReadsMainPortFallbackExpression(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("package.json"), "{\"main\":\"api.js\"}\n");
    Files.writeString(workspace.resolve("api.js"), "const port = process.env.PORT || 4321;\n");
    JsonNode pkg = PreviewNodeSignals.parsePackageJson(workspace);

    assertThat(PreviewNodeSignals.detectBackendPort(workspace, pkg)).isEqualTo(4321);
  }

  @Test
  void detectBackendPortReadsListenLiteralAndDefaultsWhenMissing(@TempDir Path workspace)
      throws IOException {
    Files.writeString(workspace.resolve("package.json"), "{}\n");
    Files.writeString(workspace.resolve("server.js"), "app.listen(4001)\n");
    JsonNode pkg = PreviewNodeSignals.parsePackageJson(workspace);

    assertThat(PreviewNodeSignals.detectBackendPort(workspace, pkg)).isEqualTo(4001);

    Files.writeString(workspace.resolve("server.js"), "app.listen(process.env.PORT)\n");
    assertThat(PreviewNodeSignals.detectBackendPort(workspace, pkg)).isEqualTo(3000);
  }
}
