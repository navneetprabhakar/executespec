package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1253 regression — {@code .agent-context/CODEBASE_CONTEXT.md} and {@code decisions.md} live
 * inside a customer-controlled checkout and their full contents are sent to an external model
 * provider. A repository symlink pointing either name at a host file would exfiltrate it.
 */
class ContextAgentScannerSymlinkTest {

  @TempDir Path tempRoot;

  private final ContextAgentScanner scanner = new ContextAgentScanner();

  private Path project;
  private Path outside;

  @BeforeEach
  void setUp() throws IOException {
    project = Files.createDirectories(tempRoot.resolve("checkout"));
    outside = Files.createDirectories(tempRoot.resolve("host"));
    Files.writeString(outside.resolve("credentials"), "AKIA-CANARY-SECRET");
  }

  private boolean link(Path from, Path to) {
    try {
      Files.createDirectories(from.getParent());
      Files.createSymbolicLink(from, to);
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }

  private PipelineRun run() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setUserRequest("do a thing");
    return run;
  }

  @Test
  void doesNotReadAHostFileThroughALinkedContextDocument() throws Exception {
    assumeThat(
            link(
                project.resolve(".agent-context/CODEBASE_CONTEXT.md"),
                outside.resolve("credentials")))
        .isTrue();

    ContextAgentInput input = scanner.scan(run(), List.of(), null, project);

    assertThat(input.currentContextDoc()).doesNotContain("AKIA-CANARY-SECRET");
    assertThat(input.currentContextDoc()).isEmpty();
  }

  @Test
  void doesNotReadAHostFileThroughALinkedContextDirectory() throws Exception {
    assumeThat(link(project.resolve(".agent-context"), outside)).isTrue();

    ContextAgentInput input = scanner.scan(run(), List.of(), null, project);

    assertThat(input.currentContextDoc()).doesNotContain("AKIA-CANARY-SECRET");
    assertThat(input.recentDecisions()).doesNotContain("AKIA-CANARY-SECRET");
  }

  @Test
  void stillReadsAGenuineInRepositoryContextDocument() throws Exception {
    Files.createDirectories(project.resolve(".agent-context"));
    Files.writeString(project.resolve(".agent-context/CODEBASE_CONTEXT.md"), "# Real context");

    ContextAgentInput input = scanner.scan(run(), List.of(), null, project);

    assertThat(input.currentContextDoc()).contains("# Real context");
  }
}
