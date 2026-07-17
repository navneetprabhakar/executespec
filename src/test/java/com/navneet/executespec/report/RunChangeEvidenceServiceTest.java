package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunChangeEvidenceServiceTest {

  @TempDir Path temp;

  @Test
  void excludesUntouchedInventoryAndDeterministicReadmeButCountsOneSourceEdit() throws Exception {
    String runId = "run-1";
    Path workspace = temp.resolve(runId);
    Files.createDirectories(workspace);
    git(workspace, "init", "-b", "main");
    git(workspace, "config", "user.email", "test@example.com");
    git(workspace, "config", "user.name", "Test");
    for (int i = 0; i < 1000; i++) {
      Path file = workspace.resolve("src/f" + i + ".java");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "class F" + i + " {}\n");
    }
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "baseline");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId)).thenReturn(List.of());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());
    RunChangeEvidenceService service = new RunChangeEvidenceService(properties, snapshots);

    assertThat(service.inspect(runId, List.of()).productFiles()).isEmpty();

    Files.writeString(workspace.resolve("README.md"), "deterministic run metadata\n");
    var readmeEvidence = service.inspect(runId, List.of());
    assertThat(readmeEvidence.productFiles()).isEmpty();
    assertThat(readmeEvidence.allFiles().getFirst().provenance())
        .isEqualTo(RunChangeEvidenceService.Provenance.SYSTEM);

    Files.writeString(workspace.resolve("src/f500.java"), "class F500 { int value = 1; }\n");
    WorkerReport workerClaim =
        WorkerReport.builder()
            .filesModified(List.of("src/f500.java"))
            .filesCreated(List.of())
            .build();
    var evidence = service.inspect(runId, List.of(workerClaim));
    assertThat(evidence.valid()).isTrue();
    assertThat(evidence.productFiles())
        .extracting(RunChangeEvidenceService.ChangedFile::path)
        .containsExactly("src/f500.java");
    assertThat(evidence.productFiles().getFirst().provenance())
        .isEqualTo(RunChangeEvidenceService.Provenance.WORKER);
    assertThat(service.meaningfulProductDiffCount(runId)).isEqualTo(1);
  }

  private static void git(Path dir, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
    int exit = process.waitFor();
    if (exit != 0) throw new AssertionError(new String(process.getErrorStream().readAllBytes()));
  }
}
