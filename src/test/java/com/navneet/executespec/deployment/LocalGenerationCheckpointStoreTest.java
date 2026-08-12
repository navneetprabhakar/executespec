package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalGenerationCheckpointStoreTest {

  @TempDir Path temp;

  @Test
  void storesAndRestoresAnExactHostBoundArtifactAfterSourceDisappears() throws Exception {
    Path workspace = temp.resolve("workspace");
    Files.createDirectories(workspace);
    LocalGenerationCheckpointStore store = new LocalGenerationCheckpointStore(workspace.toString());
    Path source = temp.resolve("candidate.tar.gz");
    Files.writeString(source, "exact archive bytes");
    String digest = DigestUtils.sha256Hex(Files.readAllBytes(source));

    GcsWorkspaceStore.ArtifactReceipt receipt =
        store.store("run-1", "a".repeat(64), digest, source);

    assertThat(source).doesNotExist();
    assertThat(receipt.key()).startsWith("local-generation://");
    assertThat(receipt.storageGeneration()).isEqualTo("sha256:" + digest);
    assertThat(receipt.sizeBytes()).isPositive();
    Path restored =
        store.copyToTempExact(
            receipt.key(),
            "run-1",
            digest,
            receipt.storageGeneration(),
            receipt.sizeBytes(),
            receipt.crc32c(),
            receipt.etag());
    try {
      assertThat(Files.readString(restored)).isEqualTo("exact archive bytes");
    } finally {
      Files.deleteIfExists(restored);
    }
  }

  @Test
  void rejectsWrongRunAndStorageMetadataBeforeReturningBytes() throws Exception {
    Path workspace = temp.resolve("workspace");
    Files.createDirectories(workspace);
    LocalGenerationCheckpointStore store = new LocalGenerationCheckpointStore(workspace.toString());
    Path source = temp.resolve("candidate.tar.gz");
    Files.writeString(source, "exact archive bytes");
    String digest = DigestUtils.sha256Hex(Files.readAllBytes(source));
    GcsWorkspaceStore.ArtifactReceipt receipt =
        store.store("run-1", "a".repeat(64), digest, source);

    assertThatThrownBy(
            () ->
                store.copyToTempExact(
                    receipt.key(),
                    "run-2",
                    digest,
                    receipt.storageGeneration(),
                    receipt.sizeBytes(),
                    receipt.crc32c(),
                    receipt.etag()))
        .isInstanceOf(java.io.IOException.class)
        .hasMessage("LOCAL_CHECKPOINT_PATH_NOT_OWNED");
    assertThatThrownBy(
            () ->
                store.copyToTempExact(
                    receipt.key(),
                    "run-1",
                    digest,
                    "sha256:" + "f".repeat(64),
                    receipt.sizeBytes(),
                    receipt.crc32c(),
                    receipt.etag()))
        .isInstanceOf(java.io.IOException.class)
        .hasMessage("LOCAL_CHECKPOINT_METADATA_MISMATCH");
  }

  @Test
  void exactDeletionIsIdentityCheckedAndRestartIdempotent() throws Exception {
    Path workspace = temp.resolve("workspace");
    Files.createDirectories(workspace);
    LocalGenerationCheckpointStore store = new LocalGenerationCheckpointStore(workspace.toString());
    Path source = temp.resolve("candidate.tar.gz");
    Files.writeString(source, "orphan archive bytes");
    String digest = DigestUtils.sha256Hex(Files.readAllBytes(source));
    GcsWorkspaceStore.ArtifactReceipt receipt =
        store.store("run-delete", "c".repeat(64), digest, source);

    assertThatThrownBy(
            () ->
                store.deleteExact(
                    receipt.key(),
                    "wrong-run",
                    digest,
                    receipt.storageGeneration(),
                    receipt.sizeBytes(),
                    receipt.crc32c(),
                    receipt.etag()))
        .isInstanceOf(java.io.IOException.class)
        .hasMessage("LOCAL_CHECKPOINT_PATH_NOT_OWNED");

    store.deleteExact(
        receipt.key(),
        "run-delete",
        digest,
        receipt.storageGeneration(),
        receipt.sizeBytes(),
        receipt.crc32c(),
        receipt.etag());
    store.deleteExact(
        receipt.key(),
        "run-delete",
        digest,
        receipt.storageGeneration(),
        receipt.sizeBytes(),
        receipt.crc32c(),
        receipt.etag());
  }
}
