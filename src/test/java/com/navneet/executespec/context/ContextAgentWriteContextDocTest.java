package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextAgentWriteContextDocTest {

  @TempDir Path tempDir;

  @Test
  void malformedLlmResponseWritesRejectedSidecarWithoutOverwritingContextDoc() throws Exception {
    ContextAgent agent =
        new ContextAgent(
            null,
            null,
            null,
            null,
            null,
            null,
            new ObjectMapper(),
            new ContextEvidenceBundleWriter(new ObjectMapper()));
    Path contextDir = tempDir.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Path contextDoc = contextDir.resolve("CODEBASE_CONTEXT.md");
    Files.writeString(contextDoc, "# Current\n\nKeep this.\n");

    agent.writeContextDoc(tempDir, "# Current\n\nKeep this.\n", "not json patch", "run-1");

    assertThat(Files.readString(contextDoc)).isEqualTo("# Current\n\nKeep this.\n");
    assertThat(Files.readString(contextDir.resolve("CODEBASE_CONTEXT.rejected.run-1.txt")))
        .isEqualTo("not json patch");
  }
}
