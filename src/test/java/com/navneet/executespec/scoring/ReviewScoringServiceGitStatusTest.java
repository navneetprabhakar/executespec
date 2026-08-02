package com.navneet.executespec.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewScoringServiceGitStatusTest {

  @Test
  void singleRepoCountsOnlyOneChangedProductionFileAndOneNewTest(@TempDir Path workspace)
      throws Exception {
    try (Git git = initializedRepository(workspace)) {
      write(workspace, "src/main/java/example/Changed.java", "class Changed {}\n");
      write(workspace, "src/main/java/example/Untouched.java", "class Untouched {}\n");
      write(
          workspace,
          "src/main/java/example/Security.java",
          "class Security { String authorize; }\n");
      write(workspace, "src/test/java/example/UntouchedTest.java", "class UntouchedTest {}\n");
      commitAll(git);

      write(workspace, "src/main/java/example/Changed.java", "class Changed { int value; }\n");
      write(workspace, "src/test/java/example/ChangedTest.java", "class ChangedTest {}\n");

      ReviewScoringService.WorkspaceStats stats = serviceFor(workspace).scanWorkspace(run());

      assertThat(stats.changedFilesMeasured()).isTrue();
      // The existing coverage denominator counts tests as source files: one production + one test.
      assertThat(stats.sourceFilesChanged()).isEqualTo(2);
      assertThat(stats.testFilesChanged()).isEqualTo(1);
      assertThat(stats.hasAuthHints()).isTrue();
    }
  }

  @Test
  void multiRepoUnionsExactStatusesWithoutCountingEitherCloneBaseline(@TempDir Path workspace)
      throws Exception {
    Path api = workspace.resolve("repos/api");
    Path web = workspace.resolve("repos/web");
    try (Git apiGit = initializedRepository(api);
        Git webGit = initializedRepository(web)) {
      write(api, "src/main/java/example/Api.java", "class Api {}\n");
      write(api, "src/main/java/example/Untouched.java", "class Untouched {}\n");
      commitAll(apiGit);
      write(web, "src/App.tsx", "export const App = () => null;\n");
      write(web, "src/Untouched.tsx", "export const Untouched = () => null;\n");
      commitAll(webGit);

      write(api, "src/main/java/example/Api.java", "class Api { int value; }\n");
      write(web, "src/App.test.tsx", "export const testCase = true;\n");

      ReviewScoringService.WorkspaceStats stats = serviceFor(workspace).scanWorkspace(run());

      assertThat(stats.changedFilesMeasured()).isTrue();
      assertThat(stats.sourceFilesChanged()).isEqualTo(2);
      assertThat(stats.testFilesChanged()).isEqualTo(1);
    }
  }

  @Test
  void missingGitEvidenceIsUnmeasuredRatherThanAnEmptyMeasuredChange(@TempDir Path workspace)
      throws Exception {
    write(workspace, "src/main/java/example/Fresh.java", "class Fresh {}\n");

    ReviewScoringService.WorkspaceStats stats = serviceFor(workspace).scanWorkspace(run());

    assertThat(stats.changedFilesMeasured()).isFalse();
    assertThat(stats.sourceFilesChanged()).isZero();
    assertThat(stats.testFilesChanged()).isZero();
  }

  private static ReviewScoringService serviceFor(Path workspace) {
    WorkspaceInitService workspaces = mock(WorkspaceInitService.class);
    when(workspaces.runWorkspacePath("run-1")).thenReturn(workspace);
    return new ReviewScoringService(workspaces);
  }

  private static PipelineRun run() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    return run;
  }

  private static Git initializedRepository(Path root) throws Exception {
    Files.createDirectories(root);
    Git git = Git.init().setDirectory(root.toFile()).call();
    git.getRepository().getConfig().setString("user", null, "name", "test");
    git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
    git.getRepository().getConfig().save();
    return git;
  }

  private static void commitAll(Git git) throws Exception {
    git.add().addFilepattern(".").call();
    git.commit()
        .setMessage("clone baseline")
        .setAuthor(new PersonIdent("test", "test@example.com"))
        .call();
  }

  private static void write(Path root, String relative, String body) throws Exception {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, body);
  }
}
