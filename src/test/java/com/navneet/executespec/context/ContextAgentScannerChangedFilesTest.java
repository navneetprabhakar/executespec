package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.state.entity.PipelineRun;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1255 — the structured {@code changedFiles} list must be derived from the typed JGit diff
 * entries (mapping every ChangeType), not re-parsed from the human display string. The old parser
 * accepted only lines beginning {@code "A "}/{@code "M "}/{@code "D "} while the display emitted
 * full enum names (ADD/MODIFY/DELETE/...), so changedFiles was always empty.
 */
class ContextAgentScannerChangedFilesTest {

  @TempDir Path project;

  private final ContextAgentScanner scanner = new ContextAgentScanner();

  private PipelineRun run() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setUserRequest("do a thing");
    return run;
  }

  @Test
  void changedFilesArePopulatedFromTypedDiffEntries() throws Exception {
    try (Git git = Git.init().setDirectory(project.toFile()).call()) {
      Path tracked = project.resolve("Service.java");
      Files.writeString(tracked, "class Service {}\n");
      git.add().addFilepattern("Service.java").call();
      git.commit().setMessage("init").setSign(false).call();

      // Working-tree modification so the index↔worktree diff reports MODIFY Service.java.
      Files.writeString(tracked, "class Service { void run() {} }\n");
    }

    ContextAgentInput input = scanner.scan(run(), List.of(), null, project);

    assertThat(input.changedFiles()).contains("Service.java");
    assertThat(input.gitDiffSummary()).contains("MODIFY Service.java");
  }
}
