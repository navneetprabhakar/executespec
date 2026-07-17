package com.navneet.executespec.testgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.report.RunChangeEvidenceService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestGenerationAdmissionServiceTest {

  @TempDir Path temp;

  @Test
  void httpRunWithSourceDiffButNoActualEndpointIsNotAdmitted() throws Exception {
    String runId = "empty-http";
    Path source = temp.resolve("src/Calculator.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "class Calculator { int add(int a, int b) { return a + b; } }");

    RunChangeEvidenceService changes = mock(RunChangeEvidenceService.class);
    var changed =
        new RunChangeEvidenceService.ChangedFile(
            "src/Calculator.java", "A", RunChangeEvidenceService.Provenance.WORKER, true);
    when(changes.inspect(runId, List.of()))
        .thenReturn(
            new RunChangeEvidenceService.ChangeEvidence(
                true, null, "baseline", List.of(changed), List.of(changed)));
    ApiSurfaceExtractor surface = mock(ApiSurfaceExtractor.class);
    when(surface.extractPaths(temp)).thenReturn(List.of());
    var workspace = mock(com.navneet.executespec.harness.WorkspaceInitService.class);
    when(workspace.runWorkspacePath(runId)).thenReturn(temp);

    TestGenerationAdmissionService service =
        new TestGenerationAdmissionService(changes, surface, workspace);

    assertThat(service.assess(runId, false, false).admitted()).isFalse();
    assertThat(service.assess(runId, false, false).reason()).contains("no extracted endpoint");
  }
}
