package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextEvidenceBundleWriterTest {

  @TempDir Path tempDir;

  @Test
  void writesDeduplicatedBoundedBundleAndReadsItWhenSourcesAreCurrent() throws Exception {
    Path contextDir = tempDir.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "# Context\n");
    Files.writeString(contextDir.resolve("decisions.md"), "# Decisions\n");
    var input =
        new ContextAgentInput(
            "# Context\n",
            "M src/A.java",
            List.of("com.example", "com.example"),
            List.of("A", "A"),
            List.of("worker summary", "worker summary"),
            "PASS",
            "recent",
            "run-1",
            "ship it");

    var writer = new ContextEvidenceBundleWriter(new ObjectMapper());
    Path written = writer.write(tempDir, input, "run-1");

    assertThat(written).isEqualTo(contextDir.resolve(ContextEvidenceBundleWriter.FILE_NAME));
    var bundle = writer.readCurrent(tempDir, "run-1").orElseThrow();
    assertThat(bundle.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(bundle.path("evidence").path("newPackages")).hasSize(1);
    assertThat(bundle.path("evidence").path("newFiles")).hasSize(1);
    assertThat(bundle.path("sources").path("contextDocumentSha256").asText()).hasSize(64);
  }

  @Test
  void rejectsStaleBundleWhenAContextSourceChanges() throws Exception {
    Path contextDir = tempDir.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "old");
    Files.writeString(contextDir.resolve("decisions.md"), "decisions");
    var input =
        new ContextAgentInput(
            "old", "diff", List.of(), List.of(), List.of(), "", "", "run-1", "goal");
    var writer = new ContextEvidenceBundleWriter(new ObjectMapper());

    writer.write(tempDir, input, "run-1");
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "new");

    assertThat(writer.readCurrent(tempDir, "run-1")).isEmpty();
  }
}
