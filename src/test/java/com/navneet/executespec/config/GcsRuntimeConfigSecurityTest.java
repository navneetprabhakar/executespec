package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GcsRuntimeConfigSecurityTest {

  private static final String KEY = "agent.concurrency.max-runs";

  private Storage storage;
  private GcsRuntimeConfig config;

  @BeforeEach
  void setUp() {
    storage = mock(Storage.class);
    config = new GcsRuntimeConfig(new GeneratedSecretScanner());
    ReflectionTestUtils.setField(config, "deployBucket", "config-bucket");
    ReflectionTestUtils.setField(config, "storage", storage);
  }

  @AfterEach
  void clearProperty() {
    System.clearProperty(KEY);
  }

  @Test
  void rejectsUnknownAndSecretBearingEntriesBeforePersistence() {
    assertThatThrownBy(() -> config.put("OPENAI_API_KEY", "not-a-real-key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> config.put(KEY, "sk-proj-abcdefghijklmnopqrstuvwxyz1234567890"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void persistenceFailureDoesNotMutateMemoryOrSystemProperties() {
    when(storage.get(any(BlobId.class))).thenReturn(null);
    when(storage.create(
            any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
        .thenThrow(new RuntimeException("gcs unavailable"));

    assertThatThrownBy(() -> config.put(KEY, "7")).isInstanceOf(IllegalStateException.class);
    assertThat(config.getAll()).doesNotContainKey(KEY);
    assertThat(System.getProperty(KEY)).isNull();
  }

  @Test
  void successfulWriteUsesGenerationGuardThenAppliesRuntimeValue() {
    Blob existing = mock(Blob.class);
    when(existing.getGeneration()).thenReturn(41L);
    when(storage.get(any(BlobId.class))).thenReturn(existing);
    when(storage.create(
            any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class)))
        .thenReturn(mock(Blob.class));

    config.put(KEY, "9");

    assertThat(config.getAll()).containsEntry(KEY, "9");
    assertThat(System.getProperty(KEY)).isEqualTo("9");
    verify(storage)
        .create(any(BlobInfo.class), any(byte[].class), any(Storage.BlobTargetOption.class));
  }
}
