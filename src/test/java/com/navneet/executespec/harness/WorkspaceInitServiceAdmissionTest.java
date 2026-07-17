package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceInitServiceAdmissionTest {

  @TempDir Path workspace;

  @Test
  void admitsOnlyWhenEverySelectedRepositoryHasGitMetadataAndProductFiles() throws Exception {
    WorkspaceInitService service = service();
    Path first = workspace.resolve("run-1/repos/api");
    Path second = workspace.resolve("run-1/repos/web");
    Files.createDirectories(first.resolve(".git"));
    Files.createDirectories(second.resolve(".git"));
    Files.writeString(first.resolve("Application.java"), "class Application {}");
    Files.writeString(second.resolve("package.json"), "{}");

    var admission =
        service.verifyMultipleRepos(
            "run-1",
            List.of(
                new WorkspaceInitService.RepoToClone("acme/api", null),
                new WorkspaceInitService.RepoToClone("acme/web", null)));

    assertTrue(admission.admitted());
    assertEquals(2, admission.fileCount());
  }

  @Test
  void rejectsMissingCloneInsteadOfTreatingOuterSeedAsBrownfieldEvidence() throws Exception {
    WorkspaceInitService service = service();
    Path seed = workspace.resolve("run-2");
    Files.createDirectories(seed.resolve(".git"));
    Files.writeString(seed.resolve("README.md"), "seed");

    var admission =
        service.verifyMultipleRepos(
            "run-2", List.of(new WorkspaceInitService.RepoToClone("acme/api", null)));

    assertFalse(admission.admitted());
    assertTrue(admission.reason().contains("clone missing"));
  }

  @Test
  void rejectsRepositoryContainingOnlyHiddenMetadata() throws Exception {
    WorkspaceInitService service = service();
    Path repo = workspace.resolve("run-3/repos/api");
    Files.createDirectories(repo.resolve(".git"));
    Files.createDirectories(repo.resolve(".github/workflows"));
    Files.writeString(repo.resolve(".github/workflows/ci.yml"), "name: ci");

    var admission =
        service.verifyMultipleRepos(
            "run-3", List.of(new WorkspaceInitService.RepoToClone("acme/api", null)));

    assertFalse(admission.admitted());
    assertTrue(admission.reason().contains("no source files"));
  }

  @Test
  void rejectsTwoSelectedRepositoriesThatWouldShareOneCheckoutDirectory() {
    WorkspaceInitService service = service();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.cloneMultipleRepos(
                "run-4",
                List.of(
                    new WorkspaceInitService.RepoToClone("org-a/api", null),
                    new WorkspaceInitService.RepoToClone("org-b/api", null)),
                null));
  }

  private WorkspaceInitService service() {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    return new WorkspaceInitService(
        properties,
        mock(WorkspaceRestoreService.class),
        mock(RunRepoSnapshotRepository.class),
        mock(VendoredPathGuard.class));
  }
}
