package com.navneet.executespec.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.GcsWorkspaceStore;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectExportObjectStoreTest {

  @Mock GcsWorkspaceStore gcs;
  @TempDir Path temp;

  private ProjectExportProperties properties;
  private ProjectExportObjectStore store;

  @BeforeEach
  void setUp() {
    AgentProperties agent = new AgentProperties();
    agent.setWorkspace(temp.toString());
    properties = new ProjectExportProperties();
    properties.setStorageRoot(temp.resolve("objects").toString());
    store =
        new ProjectExportObjectStore(
            agent,
            properties,
            gcs,
            new ArtifactPublicationGuard(agent, new GeneratedSecretScanner()));
  }

  @Test
  void localArtifactSurvivesStoreRecreationStreamsAndDeletes() throws Exception {
    UUID jobId = UUID.randomUUID();
    Path staging = store.createStagingFile(jobId, 7);
    writeArchive(staging, "ordinary archive content");
    byte[] expected = Files.readAllBytes(staging);
    var artifact = store.publish(jobId, 7, "project-1", staging, "application/gzip");

    assertThat(artifact.objectKey()).startsWith("local:");
    assertThat(artifact.sizeBytes()).isEqualTo(expected.length);
    assertThat(artifact.sha256()).hasSize(64);
    assertThat(store.exists(artifact.objectKey())).isTrue();

    ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
    store.copyTo(artifact.objectKey(), downloaded);
    assertThat(downloaded.toByteArray()).isEqualTo(expected);

    store.delete(artifact.objectKey());
    assertThat(store.exists(artifact.objectKey())).isFalse();
    verify(gcs, never()).uploadArtifact(any(), any(), any(), anyMap());
  }

  @Test
  void gcsPublicationRequiresExplicitExportFlagAndConfiguredStore() throws Exception {
    UUID jobId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    String relativeKey = "project-exports/" + jobId + "/3.tar.gz";
    properties.setGcsEnabled(true);
    when(gcs.isConfigured()).thenReturn(true);
    when(gcs.uploadArtifact(eq(relativeKey), any(Path.class), eq("application/gzip"), anyMap()))
        .thenReturn("dev/" + relativeKey);
    Path staging = store.createStagingFile(jobId, 3);
    writeArchive(staging, "ordinary archive content");

    var artifact = store.publish(jobId, 3, "project-1", staging, "application/gzip");

    assertThat(artifact.objectKey()).isEqualTo("gcs:dev/" + relativeKey);
    verify(gcs).uploadArtifact(eq(relativeKey), eq(staging), eq("application/gzip"), anyMap());
  }

  @Test
  void unknownOrTraversalKeyIsRejected() {
    assertThatThrownBy(() -> store.copyTo("local:../../secret", new ByteArrayOutputStream()))
        .isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(
            () -> store.copyTo("https://example.test/archive", new ByteArrayOutputStream()))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void historicalLocalArtifactWithSecretIsRejectedAtDownload() throws Exception {
    UUID jobId = UUID.randomUUID();
    String relative = "project-exports/" + jobId + "/9.tar.gz";
    Path historical = temp.resolve("objects").resolve(relative);
    Files.createDirectories(historical.getParent());
    String secret = "es_pat_" + "A".repeat(32);
    writeArchive(historical, "secret=" + secret);

    assertThatThrownBy(() -> store.copyTo("local:" + relative, new ByteArrayOutputStream()))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageNotContaining(secret);
  }

  @Test
  void historicalLocalArtifactWithTraversalEntryIsRejectedAtDownload() throws Exception {
    UUID jobId = UUID.randomUUID();
    String relative = "project-exports/" + jobId + "/10.tar.gz";
    Path historical = temp.resolve("objects").resolve(relative);
    Files.createDirectories(historical.getParent());
    writeArchive(historical, "../escape.txt", "ordinary content");

    assertThatThrownBy(() -> store.copyTo("local:" + relative, new ByteArrayOutputStream()))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("ARCHIVE_PATH_TRAVERSAL")
        .hasMessageNotContaining("../escape.txt");
  }

  private static void writeArchive(Path target, String content) throws Exception {
    writeArchive(target, "content.txt", content);
  }

  private static void writeArchive(Path target, String entryName, String content) throws Exception {
    try (var file = Files.newOutputStream(target);
        var gzip = new GZIPOutputStream(file);
        var tar = new ProjectExportBundleWriter.TarOutputStream(gzip, 1024 * 1024)) {
      tar.putBytes(entryName, content.getBytes(StandardCharsets.UTF_8));
    }
  }
}
