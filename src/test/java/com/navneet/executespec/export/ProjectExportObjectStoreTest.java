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

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.GcsWorkspaceStore;

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
    store = new ProjectExportObjectStore(agent, properties, gcs);
  }

  @Test
  void localArtifactSurvivesStoreRecreationStreamsAndDeletes() throws Exception {
    UUID jobId = UUID.randomUUID();
    Path staging = store.createStagingFile(jobId, 7);
    Files.writeString(staging, "archive", StandardCharsets.UTF_8);
    var artifact = store.publish(jobId, 7, "project-1", staging, "application/gzip");

    assertThat(artifact.objectKey()).startsWith("local:");
    assertThat(artifact.sizeBytes()).isEqualTo(7);
    assertThat(artifact.sha256()).hasSize(64);
    assertThat(store.exists(artifact.objectKey())).isTrue();

    ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
    store.copyTo(artifact.objectKey(), downloaded);
    assertThat(downloaded.toString(StandardCharsets.UTF_8)).isEqualTo("archive");

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
    Files.writeString(staging, "archive", StandardCharsets.UTF_8);

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
}
