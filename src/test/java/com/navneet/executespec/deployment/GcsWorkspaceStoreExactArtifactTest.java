package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcsWorkspaceStoreExactArtifactTest {

  @TempDir Path temp;

  @Test
  void immutableUploadReturnsPhysicalObjectIdentity() throws Exception {
    Storage storage = mock(Storage.class);
    Blob blob = blob(Map.of("artifactDigest", "a".repeat(64)), 17L, 7L, "crc", "etag");
    when(storage.createFrom(
            any(BlobInfo.class), any(InputStream.class), any(Storage.BlobWriteOption.class)))
        .thenReturn(blob);
    GcsWorkspaceStore store = new GcsWorkspaceStore(properties(), "dev", storage);
    Path source = temp.resolve("artifact.tar.gz");
    Files.writeString(source, "archive");

    GcsWorkspaceStore.ArtifactReceipt receipt =
        store.uploadArtifactExact(
            "workspace-generations/run/artifact.tar.gz",
            source,
            "application/gzip",
            Map.of("artifactDigest", "a".repeat(64)));

    assertThat(receipt.key()).isEqualTo("dev/workspace-generations/run/artifact.tar.gz");
    assertThat(receipt.storageGeneration()).isEqualTo("17");
    assertThat(receipt.sizeBytes()).isEqualTo(7L);
    assertThat(receipt.crc32c()).isEqualTo("crc");
    assertThat(receipt.etag()).isEqualTo("etag");
  }

  @Test
  void exactDownloadRejectsMetadataDriftAndNeverFallsBackToLegacyKey() throws Exception {
    Storage storage = mock(Storage.class);
    Blob blob = blob(Map.of(), 17L, 7L, "crc", "etag");
    when(storage.get(any(com.google.cloud.storage.BlobId.class))).thenReturn(blob);
    GcsWorkspaceStore store = new GcsWorkspaceStore(properties(), "dev", storage);

    assertThatThrownBy(
            () ->
                store.downloadArtifactToTempExact(
                    "dev/workspace-generations/run/artifact.tar.gz",
                    "run-1",
                    "18",
                    7L,
                    "crc",
                    "etag"))
        .isInstanceOf(java.io.IOException.class)
        .hasMessage("GCS_ARTIFACT_IDENTITY_MISMATCH");
  }

  @Test
  void exactDownloadReturnsOnlyTheRequestedGenerationBytes() throws Exception {
    Storage storage = mock(Storage.class);
    Blob blob = blob(Map.of(), 17L, 7L, "crc", "etag");
    when(storage.get(any(com.google.cloud.storage.BlobId.class))).thenReturn(blob);
    doAnswer(
            invocation -> {
              Files.writeString(invocation.getArgument(0), "archive");
              return null;
            })
        .when(blob)
        .downloadTo(any(Path.class));
    GcsWorkspaceStore store = new GcsWorkspaceStore(properties(), "dev", storage);

    Path result =
        store.downloadArtifactToTempExact(
            "dev/workspace-generations/run/artifact.tar.gz", "run-1", "17", 7L, "crc", "etag");
    try {
      assertThat(Files.readString(result)).isEqualTo("archive");
    } finally {
      Files.deleteIfExists(result);
    }
  }

  private static Blob blob(
      Map<String, String> metadata, long generation, long size, String crc32c, String etag) {
    Blob blob = mock(Blob.class);
    when(blob.getMetadata()).thenReturn(metadata);
    when(blob.getGeneration()).thenReturn(generation);
    when(blob.getSize()).thenReturn(size);
    when(blob.getCrc32c()).thenReturn(crc32c);
    when(blob.getEtag()).thenReturn(etag);
    return blob;
  }

  private static DeploymentProperties properties() {
    return new DeploymentProperties(
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
        new DeploymentProperties.WorkspaceConfig(7, "bucket", 24, true));
  }
}
