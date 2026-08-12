package com.navneet.executespec.workspacegeneration;

import java.nio.file.Path;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.LocalGenerationCheckpointStore;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate JVM killed after either deterministic accepted-workspace directory move. */
public final class WorkspaceGenerationReplacementKillProbe {

  private WorkspaceGenerationReplacementKillProbe() {}

  public static void main(String[] args) {
    if (args.length != 18) {
      throw new IllegalArgumentException(
          "expected mode,jdbc,user,password,attempt,run,root,kind,key,digest,storage,size,crc,etag,"
              + "candidate,workspaceBase,killLabel,marker");
    }
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(args[1]);
    dataSource.setUsername(args[2]);
    dataSource.setPassword(args[3]);
    WorkspaceGenerationStepStore steps =
        new WorkspaceGenerationStepStore(
            new JdbcTemplate(dataSource),
            new ObjectMapper(),
            new DataSourceTransactionManager(dataSource));
    CorrectionWorkspaceTransaction workspaces = new CorrectionWorkspaceTransaction();
    LocalGenerationCheckpointStore local = new LocalGenerationCheckpointStore(args[15]);
    WorkspaceGenerationArchiveService archives =
        new WorkspaceGenerationArchiveService(new ObjectMapper(), workspaces);
    WorkspaceGenerationCheckpointRestorer restorer =
        new WorkspaceGenerationCheckpointRestorer(null, local, archives);
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.fromString(args[4]))
            .runId(args[5])
            .checkpointKind(args[7])
            .checkpointKey(args[8])
            .checkpointDigest(args[9])
            .checkpointStorageGeneration(args[10])
            .checkpointSizeBytes(Long.parseLong(args[11]))
            .checkpointCrc32c(args[12])
            .checkpointEtag(args[13])
            .candidateGeneration(args[14])
            .build();
    new WorkspaceGenerationAcceptedReplacementSaga(
            restorer,
            workspaces,
            steps,
            null,
            (label, source, target) -> {
              if ("KILL".equals(args[0]) && args[16].equals(label)) {
                System.out.println("REPLACEMENT_KILL_MARKER " + label + " " + args[17]);
                System.out.flush();
                Runtime.getRuntime().halt(137);
              }
            })
        .ensureAccepted(accepted, Path.of(args[6]), () -> {});
    if (!args[14].equals(workspaces.captureGeneration(Path.of(args[6])))) {
      throw new IllegalStateException("accepted replacement generation mismatch");
    }
    System.out.println("REPLACEMENT_PROBE_PASSED mode=" + args[0]);
  }
}
