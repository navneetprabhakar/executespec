package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.harness.WorkspaceInitService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandaloneExistingWorkspaceAdmissionTest {

  @TempDir Path workspace;

  @Test
  void admitsClonedPublicRepositoryWithDeterministicSourceInventory() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    when(workspaceInit.runWorkspacePath("run-1")).thenReturn(workspace);
    doAnswer(
            ignored -> {
              Files.createDirectories(workspace.resolve(".git"));
              Files.createDirectories(workspace.resolve("src"));
              Files.writeString(workspace.resolve("src/Main.java"), "final class Main {}");
              return null;
            })
        .when(workspaceInit)
        .cloneFromRepo("run-1", "https://github.com/acme/public-repo", null);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(workspaceInit)
            .prepareInitial(
                "run-1",
                null,
                null,
                false,
                "EXISTING",
                "https://github.com/acme/public-repo",
                null);

    assertThat(admission.admitted()).isTrue();
    assertThat(admission.sourceFileCount()).isEqualTo(1);
    verify(workspaceInit).cloneFromRepo("run-1", "https://github.com/acme/public-repo", null);
  }

  @Test
  void failsClosedWhenStandaloneCloneFails() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    doThrow(new IllegalStateException("network unavailable"))
        .when(workspaceInit)
        .cloneFromRepo("run-1", "https://github.com/acme/repo", null);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(workspaceInit)
            .prepareInitial(
                "run-1", null, null, false, "EXISTING", "https://github.com/acme/repo", null);

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("repository clone failed");
  }

  @Test
  void failsClosedWhenCloneContainsNoSourceInventory() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    when(workspaceInit.runWorkspacePath("run-1")).thenReturn(workspace);
    doAnswer(
            ignored -> {
              Files.createDirectories(workspace.resolve(".git"));
              Files.writeString(workspace.resolve("README.md"), "documentation only");
              return null;
            })
        .when(workspaceInit)
        .cloneFromRepo("run-1", "https://github.com/acme/empty", null);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(workspaceInit)
            .prepareInitial(
                "run-1", null, null, false, "EXISTING", "https://github.com/acme/empty", null);

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("repository has no deterministic source inventory");
  }

  @Test
  void sourceRunIterationCopiesParentAndNeverClones() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(workspaceInit)
            .prepareInitial(
                "child",
                null,
                "parent",
                false,
                "EXISTING",
                "https://github.com/acme/repo",
                "secret");

    assertThat(admission.admitted()).isTrue();
    verify(workspaceInit).copyFromRunRequired("parent", "child");
    verifyNoMoreInteractions(workspaceInit);
  }

  @Test
  void localHandsInitializesServerPlaceholderAndNeverClones() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(workspaceInit)
            .prepareInitial(
                "run-1", null, null, true, "EXISTING", "https://github.com/acme/repo", "secret");

    assertThat(admission.admitted()).isTrue();
    verify(workspaceInit).ensureRunWorkspace("run-1");
    verifyNoMoreInteractions(workspaceInit);
  }

  @Test
  void orchestratorCompletesWorkspaceAdmissionBeforeClaimingAnyLlmOperation() throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/integration/RunOrchestrator.java"));

    assertThat(source.indexOf("standaloneExistingWorkspaceAdmission.prepareInitial("))
        .isGreaterThan(0)
        .isLessThan(source.indexOf("llmExecutionPolicyFactory.forRun(runId)"));
  }
}
