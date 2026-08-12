package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationFilesystemSaga;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationStepStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate JVM that is deliberately SIGKILL-equivalent after an exact rollback Git mutation. */
public final class WorkspaceGenerationRollbackKillProbe {

  private WorkspaceGenerationRollbackKillProbe() {}

  public static void main(String[] args) {
    if (args.length != 10) {
      throw new IllegalArgumentException(
          "expected mode,jdbc,user,password,attempt,root,generation,killLabel,repositories,marker");
    }
    String mode = args[0];
    UUID attemptId = UUID.fromString(args[4]);
    Path root = Path.of(args[5]).toAbsolutePath().normalize();
    String generation = args[6];
    String killLabel = args[7];
    int killOccurrence = Integer.parseInt(args[9]);
    java.util.concurrent.atomic.AtomicInteger observed =
        new java.util.concurrent.atomic.AtomicInteger();
    var repositories =
        Arrays.stream(args[8].split(";"))
            .map(
                encoded -> {
                  String[] fields = encoded.split("\\|", -1);
                  Path repository = ".".equals(fields[0]) ? root : root.resolve(fields[0]);
                  return new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                      repository, fields[1], fields[2]);
                })
            .toList();
    var checkpoint = new CorrectionWorkspaceTransaction.Checkpoint(root, repositories);
    if (!generation.equals(checkpoint.generation())) {
      throw new IllegalStateException("probe checkpoint generation mismatch");
    }

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(args[1]);
    dataSource.setUsername(args[2]);
    dataSource.setPassword(args[3]);
    WorkspaceGenerationStepStore store =
        new WorkspaceGenerationStepStore(
            new JdbcTemplate(dataSource),
            new ObjectMapper(),
            new DataSourceTransactionManager(dataSource));
    CorrectionWorkspaceTransaction workspaces =
        new CorrectionWorkspaceTransaction(
            new CorrectionGitCommands(),
            new NonExecutingGitTreeWriter(),
            (label, repository) -> {
              if ("KILL".equals(mode) && killLabel.equals(label)) {
                int occurrence = observed.incrementAndGet();
                if (occurrence == killOccurrence) {
                  System.out.println("KILL_MARKER " + label + " " + occurrence);
                  System.out.flush();
                  Runtime.getRuntime().halt(137);
                }
              }
            });
    new WorkspaceGenerationFilesystemSaga(store, workspaces)
        .restoreRepositories(attemptId, checkpoint, () -> {});
    String actual = workspaces.currentGeneration(checkpoint);
    if (!generation.equals(actual)) {
      throw new IllegalStateException("probe recovery did not restore predecessor");
    }
    System.out.println("ROLLBACK_PROBE_PASSED mode=" + mode + " generation=" + actual);
  }
}
