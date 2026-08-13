package com.navneet.executespec.deployment;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationArchiveService;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationStepStore;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/** Separate JVM killed after immutable archive storage but before its database receipt. */
public final class WorkspaceGenerationArchiveUploadKillProbe {

  private WorkspaceGenerationArchiveUploadKillProbe() {}

  public static void main(String[] args) {
    if (args.length != 11) {
      throw new IllegalArgumentException(
          "expected mode,jdbc,user,password,attempt,run,root,workspaceBase,kind,stepKey,objectKey");
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
    UUID attemptId = UUID.fromString(args[4]);
    CorrectionWorkspaceTransaction workspaces = new CorrectionWorkspaceTransaction();
    var checkpoint = workspaces.begin(Path.of(args[6]));
    var kind = WorkspaceGenerationStepStore.Kind.valueOf(args[8]);
    steps.recordIntent(
        attemptId,
        kind,
        args[9],
        checkpoint.generation(),
        null,
        Map.of("objectKey", args[10], "runId", args[5]));
    LocalGenerationCheckpointStore local = new LocalGenerationCheckpointStore(args[7]);
    DeploymentProperties properties =
        new DeploymentProperties(
            false,
            null,
            null,
            null,
            null,
            0,
            null,
            null,
            null,
            null,
            new DeploymentProperties.WorkspaceConfig(1, null, 1, false));
    GcsTaskCheckpointService service =
        new GcsTaskCheckpointService(
            new WorkspaceGenerationArchiveService(new ObjectMapper(), workspaces),
            new GcsWorkspaceStore(properties, "test"),
            local,
            new MockEnvironment(),
            key -> {
              if ("KILL".equals(args[0])) {
                System.out.println("ARCHIVE_UPLOAD_KILL_MARKER " + key);
                System.out.flush();
                Runtime.getRuntime().halt(137);
              }
            });
    var receipt = service.checkpointGeneration(args[5], "T1:probe", checkpoint, args[10]);
    steps.markArchiveApplied(
        attemptId,
        kind,
        args[9],
        new WorkspaceGenerationStepStore.ArchiveReceipt(
            receipt.key(),
            receipt.artifactDigest(),
            receipt.storageGeneration(),
            receipt.sizeBytes(),
            receipt.crc32c(),
            receipt.etag()));
    steps.markVerified(
        attemptId, kind, args[9], Map.of("generation", checkpoint.generation(), "replayed", true));
    System.out.println("ARCHIVE_UPLOAD_PROBE_PASSED digest=" + receipt.artifactDigest());
  }
}
