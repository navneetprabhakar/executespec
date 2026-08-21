package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.deployment.WorkspaceExportService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

class RunControllerWorkspaceDownloadEvidenceTest {

  @TempDir Path tempDir;

  @Test
  void canonicalWorkspaceDownloadBindsArchiveToAcceptedGenerationHeader() throws Exception {
    Constructor<?> constructor = RunController.class.getDeclaredConstructors()[0];
    Class<?>[] parameterTypes = constructor.getParameterTypes();
    Object[] arguments = new Object[parameterTypes.length];
    RunAccessGuard guard = null;
    WorkspaceExportService exportService = null;
    for (int i = 0; i < parameterTypes.length; i++) {
      arguments[i] = mock(parameterTypes[i]);
      if (parameterTypes[i] == RunAccessGuard.class) guard = (RunAccessGuard) arguments[i];
      if (parameterTypes[i] == WorkspaceExportService.class) {
        exportService = (WorkspaceExportService) arguments[i];
      }
    }
    assertThat(guard).isNotNull();
    assertThat(exportService).isNotNull();
    RunController controller = (RunController) constructor.newInstance(arguments);

    String runId = "run-generation-bound";
    String acceptedGeneration = "sha256:accepted-generation";
    Path archive = tempDir.resolve("workspace.zip");
    Files.writeString(archive, "zip-evidence");
    AppUserDetails actor = mock(AppUserDetails.class);
    when(guard.requireRunAccess(runId, actor)).thenReturn(mock(PipelineRun.class));
    when(exportService.exportWorkspaceWithEvidence(runId))
        .thenReturn(new WorkspaceExportService.ExportedWorkspace(archive, acceptedGeneration));

    ResponseEntity<?> response = controller.downloadWorkspace(runId, "zip", actor);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getHeaders().getFirst("X-ExecuteSpec-Workspace-Generation"))
        .isEqualTo(acceptedGeneration);
  }
}
