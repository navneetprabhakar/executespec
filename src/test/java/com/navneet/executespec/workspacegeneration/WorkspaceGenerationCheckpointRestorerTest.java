package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.deployment.LocalGenerationCheckpointStore;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceGenerationCheckpointRestorerTest {

  @TempDir Path temp;

  @Test
  void acknowledgedLocalArchiveRestoresAfterTheLiveWorkspaceIsDeleted() throws Exception {
    Path workspaceBase = temp.resolve("workspace");
    Path source = workspaceBase.resolve("run-1");
    Files.createDirectories(source);
    run(source, "git", "init", "-q");
    run(source, "git", "config", "user.email", "test@example.com");
    run(source, "git", "config", "user.name", "Test");
    Files.writeString(source.resolve("app.txt"), "accepted bytes\n");
    run(source, "git", "add", "app.txt");
    run(source, "git", "commit", "-qm", "accepted");

    CorrectionWorkspaceTransaction generations = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = generations.begin(source);
    WorkspaceGenerationArchiveService archives =
        new WorkspaceGenerationArchiveService(new ObjectMapper(), generations);
    Path artifact = archives.create(checkpoint);
    String digest = DigestUtils.sha256Hex(Files.readAllBytes(artifact));
    LocalGenerationCheckpointStore local =
        new LocalGenerationCheckpointStore(workspaceBase.toString());
    GcsWorkspaceStore.ArtifactReceipt receipt =
        local.store("run-1", checkpoint.generation(), digest, artifact);

    deleteTree(source);
    assertThat(source).doesNotExist();
    WorkspaceGenerationCheckpointRestorer restorer =
        new WorkspaceGenerationCheckpointRestorer(mock(GcsWorkspaceStore.class), local, archives);
    restorer.restore(
        "LOCAL_ARCHIVE",
        receipt.key(),
        digest,
        receipt.storageGeneration(),
        receipt.sizeBytes(),
        receipt.crc32c(),
        receipt.etag(),
        checkpoint.generation(),
        source,
        "run-1",
        () -> {});

    assertThat(Files.readString(source.resolve("app.txt"))).isEqualTo("accepted bytes\n");
    assertThat(generations.captureGeneration(source)).isEqualTo(checkpoint.generation());
  }

  private static void run(Path cwd, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
    if (process.waitFor() != 0) {
      throw new IllegalStateException(new String(process.getErrorStream().readAllBytes()));
    }
  }

  private static void deleteTree(Path root) throws Exception {
    if (!Files.exists(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
