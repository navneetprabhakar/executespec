package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.deployment.LocalGenerationCheckpointStore;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real-process kill matrix for every Git mutation in a two-repository rollback. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationFilesystemKillPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_kill")
          .withUsername("test")
          .withPassword("test");

  @TempDir Path temp;

  @Test
  void replacementJvmResumesAfterEveryRepositoryMutationAndRestoresExactPredecessor()
      throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    List<String> labels = List.of("RESET_HARD", "CLEAN_UNTRACKED", "READ_TREE", "RESET_MIXED");
    for (String label : labels) {
      for (int occurrence : List.of(1, 2)) {
        String caseId = label.toLowerCase() + "-" + occurrence;
        Path root = createTwoRepositoryWorkspace(caseId);
        CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
        CorrectionWorkspaceTransaction.Checkpoint checkpoint = transactions.begin(root);
        String repositories = encode(root, checkpoint);
        mutate(root);
        assertThat(transactions.captureGeneration(root)).isNotEqualTo(checkpoint.generation());

        String runId = "v397-kill-" + caseId;
        UUID attemptId = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
                + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
            runId);
        jdbc.update(
            "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,"
                + "attempt_number,attempt_kind,state,workspace_root,authority_digest,"
                + "task_contract_digest,build_protocol_version) VALUES (?,?,'T1',0,1,"
                + "'BUILD_TASK','ADMITTED',?,repeat('1',64),repeat('2',64),'SHARED_KERNEL_V2')",
            attemptId,
            runId,
            root.toString());

        ProcessResult killed =
            probe(
                "KILL", attemptId, root, checkpoint.generation(), label, repositories, occurrence);
        assertThat(killed.exitCode()).as(killed.output()).isEqualTo(137);
        assertThat(killed.output()).contains("KILL_MARKER " + label + " " + occurrence);

        ProcessResult recovered =
            probe("RECOVER", attemptId, root, checkpoint.generation(), "-", repositories, 0);
        assertThat(recovered.exitCode()).as(recovered.output()).isZero();
        assertThat(recovered.output()).contains("ROLLBACK_PROBE_PASSED mode=RECOVER");
        assertThat(transactions.captureGeneration(root)).isEqualTo(checkpoint.generation());
        assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM workspace_generation_step WHERE attempt_id=? "
                        + "AND step_kind='ROLLBACK_RESTORE' AND state='VERIFIED'",
                    Integer.class,
                    attemptId))
            .isEqualTo(2);
      }
    }
  }

  @Test
  void replacementJvmResumesAfterEachAcceptedWorkspaceDirectoryMove() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    for (String label : List.of("DISPLACE_CURRENT", "INSTALL_ACCEPTED")) {
      String runId = "v397-replace-" + label.toLowerCase();
      UUID attemptId = UUID.randomUUID();
      Path acceptedSource = createSingleRepository(temp.resolve(runId + "-accepted"), "accepted\n");
      CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
      CorrectionWorkspaceTransaction.Checkpoint accepted = transactions.begin(acceptedSource);
      WorkspaceGenerationArchiveService archives =
          new WorkspaceGenerationArchiveService(
              new com.fasterxml.jackson.databind.ObjectMapper(), transactions);
      Path archive = archives.create(accepted);
      String digest =
          org.apache.commons.codec.digest.DigestUtils.sha256Hex(Files.readAllBytes(archive));
      Path workspaceBase = temp.resolve("checkpoint-store");
      LocalGenerationCheckpointStore local =
          new LocalGenerationCheckpointStore(workspaceBase.toString());
      var receipt = local.store(runId, accepted.generation(), digest, archive);
      Path root = createSingleRepository(temp.resolve(runId + "-current"), "wrong\n");

      jdbc.update(
          "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
              + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
          runId);
      jdbc.update(
          "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
              + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
              + "build_protocol_version) VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED',?,"
              + "repeat('1',64),repeat('2',64),'SHARED_KERNEL_V2')",
          attemptId,
          runId,
          root.toString());

      ProcessResult killed =
          replacementProbe(
              "KILL",
              attemptId,
              runId,
              root,
              receipt,
              digest,
              accepted.generation(),
              workspaceBase,
              label);
      assertThat(killed.exitCode()).as(killed.output()).isEqualTo(137);
      assertThat(killed.output()).contains("REPLACEMENT_KILL_MARKER " + label);

      ProcessResult recovered =
          replacementProbe(
              "RECOVER",
              attemptId,
              runId,
              root,
              receipt,
              digest,
              accepted.generation(),
              workspaceBase,
              "-");
      assertThat(recovered.exitCode()).as(recovered.output()).isZero();
      assertThat(transactions.captureGeneration(root)).isEqualTo(accepted.generation());
      assertThat(Files.readString(root.resolve("app.txt"))).isEqualTo("accepted\n");
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM workspace_generation_step WHERE attempt_id=? "
                      + "AND step_kind='ACCEPTED_REPLACEMENT'",
                  String.class,
                  attemptId))
          .isEqualTo("VERIFIED");
    }
  }

  @Test
  void replacementJvmReplaysArchiveAfterStorageBeforeReceiptKill() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    for (WorkspaceGenerationStepStore.Kind kind :
        List.of(
            WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
            WorkspaceGenerationStepStore.Kind.CANDIDATE_ARCHIVE_UPLOAD)) {
      String runId = "v397-upload-" + kind.name().toLowerCase();
      UUID attemptId = UUID.randomUUID();
      Path root = createSingleRepository(temp.resolve(runId), "accepted\n");
      String generation = new CorrectionWorkspaceTransaction().captureGeneration(root);
      String stepKey = kind.name().toLowerCase() + ":" + generation;
      String objectKey =
          "workspace-generations/" + runId + "/" + kind.name().toLowerCase() + ".tar.gz";
      Path workspaceBase = temp.resolve("upload-checkpoints");
      jdbc.update(
          "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
              + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
          runId);
      jdbc.update(
          "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
              + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
              + "build_protocol_version) VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED',?,"
              + "repeat('1',64),repeat('2',64),'SHARED_KERNEL_V2')",
          attemptId,
          runId,
          root.toString());

      ProcessResult killed =
          archiveUploadProbe(
              "KILL", attemptId, runId, root, workspaceBase, kind, stepKey, objectKey);
      assertThat(killed.exitCode()).as(killed.output()).isEqualTo(137);
      assertThat(killed.output()).contains("ARCHIVE_UPLOAD_KILL_MARKER");
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM workspace_generation_step WHERE attempt_id=? AND step_kind=?",
                  String.class,
                  attemptId,
                  kind.name()))
          .isEqualTo("INTENT");

      ProcessResult recovered =
          archiveUploadProbe(
              "RECOVER", attemptId, runId, root, workspaceBase, kind, stepKey, objectKey);
      assertThat(recovered.exitCode()).as(recovered.output()).isZero();
      assertThat(recovered.output()).contains("ARCHIVE_UPLOAD_PROBE_PASSED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM workspace_generation_step WHERE attempt_id=? AND step_kind=?",
                  String.class,
                  attemptId,
                  kind.name()))
          .isEqualTo("VERIFIED");
      Path storedRun =
          workspaceBase
              .resolve(".generation-checkpoints")
              .resolve(org.apache.commons.codec.digest.DigestUtils.sha256Hex(runId));
      try (var files = Files.list(storedRun)) {
        assertThat(files.filter(Files::isRegularFile).count())
            .as("replay must converge on one immutable archive")
            .isEqualTo(1);
      }
    }
  }

  private Path createTwoRepositoryWorkspace(String caseId) throws Exception {
    Path root = Files.createDirectories(temp.resolve(caseId));
    git(root, "init", "-q");
    Files.writeString(root.resolve("root.txt"), "accepted-root\n");
    git(root, "add", "root.txt");
    commit(root, "root");
    Path child = Files.createDirectories(root.resolve("repos/child"));
    git(child, "init", "-q");
    Files.writeString(child.resolve("child.txt"), "accepted-child\n");
    git(child, "add", "child.txt");
    commit(child, "child");
    return root.toRealPath();
  }

  private Path createSingleRepository(Path root, String content) throws Exception {
    Files.createDirectories(root);
    git(root, "init", "-q");
    Files.writeString(root.resolve("app.txt"), content);
    git(root, "add", "app.txt");
    commit(root, "initial");
    return root.toRealPath();
  }

  private static void mutate(Path root) throws Exception {
    Files.writeString(root.resolve("root.txt"), "candidate-root\n");
    Files.writeString(root.resolve("root.tmp"), "candidate-untracked\n");
    Path child = root.resolve("repos/child");
    Files.writeString(child.resolve("child.txt"), "candidate-child\n");
    Files.writeString(child.resolve("child.tmp"), "candidate-untracked\n");
  }

  private ProcessResult probe(
      String mode,
      UUID attemptId,
      Path root,
      String generation,
      String killLabel,
      String repositories,
      int occurrence)
      throws Exception {
    String java =
        Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    String classpath = System.getProperty("surefire.test.class.path");
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                classpath,
                "com.navneet.executespec.integration.WorkspaceGenerationRollbackKillProbe",
                mode,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                attemptId.toString(),
                root.toString(),
                generation,
                killLabel,
                repositories,
                String.valueOf(occurrence))
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    int exit = process.waitFor();
    return new ProcessResult(exit, output);
  }

  private ProcessResult replacementProbe(
      String mode,
      UUID attemptId,
      String runId,
      Path root,
      com.navneet.executespec.deployment.GcsWorkspaceStore.ArtifactReceipt receipt,
      String digest,
      String generation,
      Path workspaceBase,
      String killLabel)
      throws Exception {
    String java =
        Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("surefire.test.class.path"),
                "com.navneet.executespec.workspacegeneration.WorkspaceGenerationReplacementKillProbe",
                mode,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                attemptId.toString(),
                runId,
                root.toString(),
                "LOCAL_ARCHIVE",
                receipt.key(),
                digest,
                receipt.storageGeneration(),
                String.valueOf(receipt.sizeBytes()),
                receipt.crc32c(),
                receipt.etag(),
                generation,
                workspaceBase.toString(),
                killLabel,
                "1")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    return new ProcessResult(process.waitFor(), output);
  }

  private ProcessResult archiveUploadProbe(
      String mode,
      UUID attemptId,
      String runId,
      Path root,
      Path workspaceBase,
      WorkspaceGenerationStepStore.Kind kind,
      String stepKey,
      String objectKey)
      throws Exception {
    String java =
        Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("surefire.test.class.path"),
                "com.navneet.executespec.deployment.WorkspaceGenerationArchiveUploadKillProbe",
                mode,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                attemptId.toString(),
                runId,
                root.toString(),
                workspaceBase.toString(),
                kind.name(),
                stepKey,
                objectKey)
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    return new ProcessResult(process.waitFor(), output);
  }

  private static String encode(Path root, CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    List<String> encoded = new ArrayList<>();
    for (var repository : checkpoint.repositories()) {
      String relative = root.relativize(repository.repository()).toString().replace('\\', '/');
      encoded.add(
          (relative.isBlank() ? "." : relative)
              + "|"
              + repository.head()
              + "|"
              + repository.tree());
    }
    return String.join(";", encoded);
  }

  private static void commit(Path repository, String message) throws Exception {
    git(
        repository,
        "-c",
        "user.name=ExecuteSpec Test",
        "-c",
        "user.email=test@executespec.invalid",
        "commit",
        "-q",
        "-m",
        message);
  }

  private static void git(Path directory, String... args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
    String output = new String(process.getInputStream().readAllBytes());
    String error = new String(process.getErrorStream().readAllBytes());
    if (process.waitFor() != 0) throw new IllegalStateException(output + error);
  }

  private record ProcessResult(int exitCode, String output) {}
}
