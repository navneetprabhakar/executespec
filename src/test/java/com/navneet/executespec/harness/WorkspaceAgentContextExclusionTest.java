package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1546 — {@code .agent-context/} holds internal execution evidence (full build/test logs, diffs,
 * the run journal). {@code GitCommitTool} and {@code GitTool} stage with {@code git add -A}, so an
 * unexcluded directory is committed and pushed into the customer's pull request.
 */
class WorkspaceAgentContextExclusionTest {

  @TempDir Path workspace;

  private WorkspaceInitService service() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    return new WorkspaceInitService(
        properties,
        mock(WorkspaceRestoreService.class),
        mock(RunRepoSnapshotRepository.class),
        mock(VendoredPathGuard.class),
        mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class));
  }

  @Test
  void greenfieldWorkspaceGitignoreExcludesAgentContext() throws Exception {
    service().ensureRunWorkspace("run-1");

    String gitignore = Files.readString(workspace.resolve("run-1/.gitignore"));

    assertThat(gitignore.lines().map(String::strip).toList()).contains(".agent-context/");
    // Existing build-artifact exclusions must survive.
    assertThat(gitignore).contains("node_modules/", "target/", "*.log");
  }

  @Test
  void clonedRepositoryGetsTheExclusionInGitInfoExcludeNotItsTrackedGitignore() throws Exception {
    Path clone = workspace.resolve("clone");
    Files.createDirectories(clone.resolve(".git"));
    Files.writeString(clone.resolve(".gitignore"), "dist/\n");

    service().excludeAgentContextInClone(clone);

    assertThat(Files.readString(clone.resolve(".git/info/exclude"))).contains(".agent-context/");
    // The customer's tracked .gitignore must NOT be mutated — that would show as a PR diff.
    assertThat(Files.readString(clone.resolve(".gitignore"))).isEqualTo("dist/\n");
  }

  @Test
  void cloneExclusionIsIdempotent() throws Exception {
    Path clone = workspace.resolve("clone2");
    Files.createDirectories(clone.resolve(".git"));

    WorkspaceInitService service = service();
    service.excludeAgentContextInClone(clone);
    service.excludeAgentContextInClone(clone);

    String exclude = Files.readString(clone.resolve(".git/info/exclude"));
    assertThat(exclude.lines().filter(l -> l.strip().equals(".agent-context/")).count())
        .isEqualTo(1);
  }

  @Test
  void cloneExclusionIsANoOpForANonGitDirectory() {
    Path notARepo = workspace.resolve("plain");

    service().excludeAgentContextInClone(notARepo);

    assertThat(Files.exists(notARepo.resolve(".git/info/exclude"))).isFalse();
  }
}
