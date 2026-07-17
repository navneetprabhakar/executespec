package com.navneet.executespec.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.FileDataStoreFactory;

import org.junit.jupiter.api.Test;

/**
 * 2026-07-17 credential gate — OAuth token stores are enveloped at rest, legacy stores readable.
 */
class EncryptedDataStoreFactoryTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  private static SimpleKeyEncryptionService liveCrypto() {
    CryptoProperties props = new CryptoProperties();
    props.setEnabled(true);
    props.setLocalKey(KEY);
    SimpleKeyEncryptionService service = new SimpleKeyEncryptionService(props);
    service.init();
    return service;
  }

  @Test
  void valuesAreEnvelopedOnDiskAndRoundTrip() throws Exception {
    File dir = Files.createTempDirectory("es-enc-store-").toFile();
    try {
      var factory = new EncryptedDataStoreFactory(new FileDataStoreFactory(dir), liveCrypto());
      DataStore<String> store = factory.getDataStore("tokens");

      store.set("user", "refresh-token-material");

      assertThat(store.get("user")).isEqualTo("refresh-token-material");
      String onDisk =
          Files.readString(
              new File(dir, "tokens").toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
      assertThat(onDisk).doesNotContain("refresh-token-material");
      assertThat(onDisk).contains("enc:v1:");
    } finally {
      for (File f : dir.listFiles()) f.delete();
      dir.delete();
    }
  }

  /** Models the real Gmail case: legacy stores hold serialized OBJECTS, not envelope strings. */
  public record LegacyCredential(String refreshToken) implements java.io.Serializable {}

  @Test
  void legacyPreGateObjectStoreStillReadsAndReenvelopesOnWrite() throws Exception {
    File dir = Files.createTempDirectory("es-legacy-store-").toFile();
    try {
      // Pre-gate world: credential objects written directly through the plain FileDataStore.
      DataStore<LegacyCredential> legacy =
          new FileDataStoreFactory(dir).getDataStore("tokenslegacy");
      legacy.set("user", new LegacyCredential("legacy-refresh-token"));

      var factory = new EncryptedDataStoreFactory(new FileDataStoreFactory(dir), liveCrypto());
      DataStore<LegacyCredential> wrapped = factory.getDataStore("tokenslegacy");

      assertThat(wrapped.get("user")).isEqualTo(new LegacyCredential("legacy-refresh-token"));
      // Next write re-envelopes at rest.
      wrapped.set("user", new LegacyCredential("legacy-refresh-token"));
      String onDisk =
          Files.readString(
              new File(dir, "tokenslegacy").toPath(), java.nio.charset.StandardCharsets.ISO_8859_1);
      assertThat(onDisk).doesNotContain("legacy-refresh-token");
      assertThat(wrapped.get("user")).isEqualTo(new LegacyCredential("legacy-refresh-token"));
    } finally {
      for (File f : dir.listFiles()) f.delete();
      dir.delete();
    }
  }
}
