package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderHandoffDossierTest {

  @TempDir Path workspace;

  @Test
  void writesAndReloadsActualRouteAndFailureContext() {
    ProviderHandoffDossier.write(
        generationContext("run-1", "T1"),
        workspace,
        "run-1",
        "T1",
        "anthropic",
        "claude-sonnet",
        "deepseek",
        "deepseek-v4",
        "CHANGE_PROVIDER",
        "build failed on the previous route",
        "missing import");

    var dossier = ProviderHandoffDossier.read(workspace, "run-1", "T1").orElseThrow();
    assertThat(dossier.path("fromProvider").asText()).isEqualTo("anthropic");
    assertThat(dossier.path("toModel").asText()).isEqualTo("deepseek-v4");
    assertThat(ProviderHandoffDossier.recoveryNote(dossier))
        .contains("CHANGE_PROVIDER", "Do not repeat the failed approach blindly");
  }

  @Test
  void refusesDossierFromAnotherTask() {
    ProviderHandoffDossier.write(
        generationContext("run-1", "T1"),
        workspace,
        "run-1",
        "T1",
        "openai",
        "gpt",
        "gemini",
        "flash",
        "STEP_UP_MODEL",
        "",
        "");

    assertThat(ProviderHandoffDossier.read(workspace, "run-1", "T2")).isEmpty();
  }

  @Test
  void refusesWriteWithoutExactGenerationAuthority() {
    assertThatThrownBy(
            () ->
                ProviderHandoffDossier.write(
                    new ExecutionContext("run-1", "T1", workspace.toAbsolutePath()),
                    workspace,
                    "run-1",
                    "T1",
                    "openai",
                    "gpt",
                    "gemini",
                    "flash",
                    "CHANGE_PROVIDER",
                    "",
                    ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_AUTHORITY_REQUIRED");
  }

  private static ExecutionContext generationContext(String runId, String taskId) {
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn(runId);
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(java.util.UUID.randomUUID(), null, lease);
    return new ExecutionContext(runId, taskId, Path.of("/tmp").toAbsolutePath())
        .withWorkspaceGenerationAuthority(
            new WorkspaceGenerationDispatchAuthority(
                runId,
                taskId,
                prepared,
                WorkspaceGenerationDispatchAuthority.Phase.MUTATION,
                mock(WorkspaceGenerationLifecycle.class)));
  }
}
