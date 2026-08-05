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

import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
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
        new StandaloneExistingWorkspaceAdmission(
                workspaceInit,
                mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class))
            .prepareInitial(
                "run-1",
                null,
                null,
                false,
                "EXISTING",
                "https://github.com/acme/public-repo",
                null,
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
        new StandaloneExistingWorkspaceAdmission(
                workspaceInit,
                mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class))
            .prepareInitial(
                "run-1", null, null, false, "EXISTING", "https://github.com/acme/repo", null, null);

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
        new StandaloneExistingWorkspaceAdmission(
                workspaceInit,
                mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class))
            .prepareInitial(
                "run-1",
                null,
                null,
                false,
                "EXISTING",
                "https://github.com/acme/empty",
                null,
                null);

    assertThat(admission.admitted()).isFalse();
    assertThat(admission.reason()).isEqualTo("repository has no deterministic source inventory");
  }

  @Test
  void sourceRunIterationCopiesParentAndNeverClones() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    BrownfieldHttpContractGuard contractGuard = mock(BrownfieldHttpContractGuard.class);
    when(workspaceInit.runWorkspacePath("child")).thenReturn(workspace);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(workspaceInit, contractGuard)
            .prepareInitial(
                "child",
                null,
                "parent",
                false,
                "EXISTING",
                "https://github.com/acme/repo",
                "secret",
                null);

    assertThat(admission.admitted()).isTrue();
    verify(workspaceInit).copyFromRunRequired("parent", "child");
    verify(workspaceInit).runWorkspacePath("child");
    verify(contractGuard).captureBaselineIfAbsent("child", workspace);
    verifyNoMoreInteractions(workspaceInit, contractGuard);
  }

  @Test
  void localHandsInitializesServerPlaceholderAndNeverClones() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(
                workspaceInit,
                mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class))
            .prepareInitial(
                "run-1",
                null,
                null,
                true,
                "EXISTING",
                "https://github.com/acme/repo",
                "secret",
                null);

    assertThat(admission.admitted()).isTrue();
    verify(workspaceInit).ensureRunWorkspace("run-1");
    verifyNoMoreInteractions(workspaceInit);
  }

  @Test
  void explicitBranchUsesBranchAwareCloneBeforeInventory() throws Exception {
    WorkspaceInitService workspaceInit = mock(WorkspaceInitService.class);
    when(workspaceInit.runWorkspacePath("run-pr")).thenReturn(workspace);
    doAnswer(
            ignored -> {
              Files.createDirectories(workspace.resolve(".git"));
              Files.createDirectories(workspace.resolve("src"));
              Files.writeString(workspace.resolve("src/app.js"), "export const ok = true;");
              return null;
            })
        .when(workspaceInit)
        .cloneFromRepoBranch("run-pr", "https://github.com/acme/repo", "feature/pr-12", "secret");

    StandaloneExistingWorkspaceAdmission.Admission admission =
        new StandaloneExistingWorkspaceAdmission(
                workspaceInit, mock(BrownfieldHttpContractGuard.class))
            .prepareInitial(
                "run-pr",
                null,
                null,
                false,
                "EXISTING",
                "https://github.com/acme/repo",
                "secret",
                "feature/pr-12");

    assertThat(admission.admitted()).isTrue();
    verify(workspaceInit)
        .cloneFromRepoBranch("run-pr", "https://github.com/acme/repo", "feature/pr-12", "secret");
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

  @Test
  void orchestratorPersistsAndTerminatesAbortedWorkerBeforeBrownfieldCompatibility()
      throws Exception {
    String source =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/integration/RunOrchestrator.java"));
    int worker = source.indexOf("execResult =\n            executor.execute(");
    int taskPersistence =
        source.indexOf("persistenceWriter.applyExecutionResultToTasks(runId, execResult);", worker);
    int abortedTerminal =
        source.indexOf(
            "if (execResult.overall() == ExecutionResult.Overall.ABORTED)", taskPersistence);
    int compatibility =
        source.indexOf("brownfieldHttpContractGuard.assertCompatibleIfPresent(runId);", worker);

    assertThat(worker).isGreaterThan(0);
    assertThat(taskPersistence).isGreaterThan(worker).isLessThan(abortedTerminal);
    assertThat(abortedTerminal).isLessThan(compatibility);
  }
}
