package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
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

    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId,
                List.of("writeFile({\"path\":\"README.md\",\"content\":\"metadata\"}) -> OK")))
        .isZero();
    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId, List.of("editFile({\"path\":\"src/f500.java\",\"edits\":[{}]}) -> OK")))
        .isEqualTo(1);
    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId, List.of("editFile({\"path\":\"src/f500.java\",\"edits\":[{}]}) -> ERR")))
        .isZero();
    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId,
                List.of(
                    "editFile({\"content\":\""
                        + "x".repeat(100)
                        + "...}) [target=src/f500.java] -> OK")))
        .isEqualTo(1);
    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId, List.of("editFile(sig=deadbeef) [target=src/f500.java] -> OK")))
        .as("canonical salvage entries attribute the admitted product diff")
        .isEqualTo(1);
    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId, List.of("editFile(sig=deadbeef) [target=src/f500.java] -> ERR")))
        .as("failed salvage entries cannot claim the product diff")
        .isZero();
    assertThat(
            service.meaningfulProductDiffCountForToolLog(
                runId, List.of("editFile({\"path\":\"src/f500.java\"})")))
        .isZero();
  }

  /**
   * RR-287 — without an admitted run-start snapshot, an EXISTING repository's root commit is not
   * this run's baseline. Diffing against the first-ever commit counted every change since the
   * repository was created as this run's delivery, so a no-op run cleared the {@code filesGenerated
   * > 0} hard floor and earned a nonzero verified score plus a HIGH-confidence "Code delivered"
   * ledger row.
   */
  @Test
  void failsClosedWhenAnExistingRepositoryHasNoAdmittedRunStartSnapshot() throws Exception {
    String runId = "run-2";
    Path workspace = temp.resolve(runId);
    Files.createDirectories(workspace);
    git(workspace, "init", "-b", "main");
    git(workspace, "config", "user.email", "test@example.com");
    git(workspace, "config", "user.name", "Test");
    // Pre-existing repository history that predates this run entirely.
    Files.writeString(workspace.resolve("existing.java"), "class Existing {}\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "first ever commit");
    Files.writeString(workspace.resolve("existing.java"), "class Existing { int a = 1; }\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "later work, months before this run");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId)).thenReturn(List.of());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());
    RunChangeEvidenceService service = new RunChangeEvidenceService(properties, snapshots);

    // This run wrote nothing. Evidence must be INVALID, not "everything since the root commit".
    var evidence = service.inspect(runId, List.of());
    assertThat(evidence.valid()).isFalse();
    assertThat(evidence.invalidReason()).contains("run-start snapshot");
    assertThat(service.meaningfulProductDiffCount(runId)).isZero();
  }

  @Test
  void managedFreshWorkspaceRemainsProvableAfterRunOutputCommit() throws Exception {
    String runId = "run-3";
    Path workspace = temp.resolve(runId);
    Files.createDirectories(workspace);
    git(workspace, "init", "-b", "main");
    git(workspace, "config", "user.email", "test@example.com");
    git(workspace, "config", "user.name", "Test");
    Files.writeString(workspace.resolve(".gitignore"), "__pycache__/\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "init agent workspace");
    Files.createDirectories(workspace.resolve("app"));
    Files.writeString(workspace.resolve("app/main.py"), "print('ready')\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "V2 run output for " + runId);

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId)).thenReturn(List.of());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());
    RunChangeEvidenceService service = new RunChangeEvidenceService(properties, snapshots);

    var evidence = service.inspect(runId, List.of());
    assertThat(evidence.valid()).isTrue();
    assertThat(evidence.productFiles())
        .extracting(RunChangeEvidenceService.ChangedFile::path)
        .containsExactly("app/main.py");
  }

  @Test
  void changeEvidenceNeverExecutesRepositoryDiffDriver() throws Exception {
    String runId = "run-hostile-diff";
    Path workspace = temp.resolve(runId);
    Files.createDirectories(workspace);
    git(workspace, "init", "-b", "main");
    git(workspace, "config", "user.email", "test@example.com");
    git(workspace, "config", "user.name", "Test");
    Files.writeString(workspace.resolve("tracked.txt"), "baseline\n");
    Files.writeString(workspace.resolve(".gitattributes"), "*.txt diff=hostile\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "baseline");
    Path marker = temp.resolve("diff-driver-executed");
    Path driver = temp.resolve("hostile-diff.sh");
    Files.writeString(driver, "#!/bin/sh\ntouch '" + marker + "'\nexit 0\n");
    Files.setPosixFilePermissions(driver, PosixFilePermissions.fromString("rwx------"));
    git(workspace, "config", "diff.hostile.command", driver.toString());
    Files.writeString(workspace.resolve("tracked.txt"), "changed\n");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId)).thenReturn(List.of());
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());

    var evidence = new RunChangeEvidenceService(properties, snapshots).inspect(runId, List.of());

    assertThat(evidence.valid()).isTrue();
    assertThat(evidence.productFiles())
        .extracting(RunChangeEvidenceService.ChangedFile::path)
        .containsExactly("tracked.txt");
    assertThat(marker).doesNotExist();
  }

  @Test
  void resolvesAnAdmittedStandaloneSnapshotAtTheRunWorkspaceRoot() throws Exception {
    String runId = "run-root-snapshot";
    Path workspace = temp.resolve(runId);
    Files.createDirectories(workspace);
    git(workspace, "init", "-b", "trunk");
    git(workspace, "config", "user.email", "test@example.com");
    git(workspace, "config", "user.name", "Test");
    Files.writeString(workspace.resolve("app.js"), "export const value = 1;\n");
    Files.createDirectories(workspace.resolve("repos/app"));
    Files.writeString(workspace.resolve("repos/app/bait.js"), "export const bait = true;\n");
    git(workspace, "add", "app.js");
    git(workspace, "commit", "-m", "baseline");
    String baseline = gitOutput(workspace, "rev-parse", "HEAD");
    Files.writeString(workspace.resolve("app.js"), "export const value = 2;\n");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId))
        .thenReturn(
            List.of(
                RunRepoSnapshot.builder()
                    .runId(runId)
                    .repoFullName("acme/app")
                    .branch("trunk")
                    .commitSha(baseline)
                    .build()));
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());

    var evidence = new RunChangeEvidenceService(properties, snapshots).inspect(runId, List.of());

    assertThat(evidence.valid()).isTrue();
    assertThat(evidence.productFiles())
        .extracting(RunChangeEvidenceService.ChangedFile::path)
        // `repos/app` is ordinary content in this admitted standalone repository. Keeping the
        // root-relative prefix proves it was not misclassified as a nested Project checkout.
        .containsExactly("app.js", "repos/app/bait.js");
  }

  @Test
  void restoredNestedProjectFailsClosedInsteadOfUsingSyntheticRootRepository() throws Exception {
    String runId = "run-restored-project";
    Path workspace = temp.resolve(runId);
    Files.createDirectories(workspace);
    git(workspace, "init", "-b", "main");
    git(workspace, "config", "user.email", "test@example.com");
    git(workspace, "config", "user.name", "Test");
    Path nested = Files.createDirectories(workspace.resolve("repos/api"));
    Files.writeString(nested.resolve("Api.java"), "class Api {}\n");
    git(workspace, "add", ".");
    git(workspace, "commit", "-m", "synthetic restored archive");

    RunRepoSnapshotRepository snapshots = mock(RunRepoSnapshotRepository.class);
    when(snapshots.findByRunId(runId))
        .thenReturn(
            List.of(
                RunRepoSnapshot.builder()
                    .runId(runId)
                    .repoId(java.util.UUID.randomUUID())
                    .repoFullName("acme/api")
                    .branch("main")
                    .commitSha("a".repeat(40))
                    .build()));
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());

    var evidence = new RunChangeEvidenceService(properties, snapshots).inspect(runId, List.of());

    assertThat(evidence.valid()).isFalse();
    assertThat(evidence.invalidReason())
        .isEqualTo("admitted repository checkout is unavailable: api");
  }

  private static String gitOutput(Path dir, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    if (process.waitFor() != 0) throw new AssertionError(output);
    return output;
  }

  private static void git(Path dir, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    // A large test commit can trigger background auto-maintenance on Linux. JUnit may then race
    // that child while deleting @TempDir and fail after every assertion has passed. Keep repository
    // maintenance synchronous and out of this fixture; the test exercises evidence, not Git GC.
    command.addAll(List.of("-c", "gc.auto=0", "-c", "maintenance.auto=false"));
    command.addAll(List.of(args));
    Process process =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    byte[] output;
    try (var stream = process.getInputStream()) {
      output = stream.readAllBytes();
    }
    int exit = process.waitFor();
    if (exit != 0) throw new AssertionError(new String(output));
  }
}
