package com.navneet.executespec.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 2026-07-17 credential gate — versioned envelope semantics. Envelopes fail CLOSED (wrong key,
 * unknown version, malformed, or crypto-disabled all throw); legacy bare-Base64 and plaintext reads
 * stay compatible so already-applied rows keep decrypting.
 */
class SimpleKeyEncryptionServiceTest {

  private static final String KEY_A = Base64.getEncoder().encodeToString(new byte[32]);
  private static final String KEY_B;

  static {
    byte[] other = new byte[32];
    java.util.Arrays.fill(other, (byte) 7);
    KEY_B = Base64.getEncoder().encodeToString(other);
  }

  private static SimpleKeyEncryptionService service(
      String localKey, String keyVersion, Map<String, String> previousKeys, boolean enabled) {
    CryptoProperties props = new CryptoProperties();
    props.setEnabled(enabled);
    props.setLocalKey(localKey);
    props.setKeyVersion(keyVersion);
    props.setPreviousKeys(previousKeys);
    SimpleKeyEncryptionService service = new SimpleKeyEncryptionService(props);
    service.init();
    return service;
  }

  @Test
  void roundTripWritesVersionedEnvelopeAndDecrypts() {
    var service = service(KEY_A, "1", Map.of(), true);
    String secret = "sk-ant-api03-super-secret";

    String stored = service.encrypt(secret);

    assertThat(stored).startsWith("enc:v1:1:");
    assertThat(stored).doesNotContain(secret);
    assertThat(service.isEnveloped(stored)).isTrue();
    assertThat(service.decrypt(stored)).isEqualTo(secret);
  }

  @Test
  void legacyBareBase64CiphertextStillDecrypts() {
    var service = service(KEY_A, "1", Map.of(), true);
    String secret = "legacy-format-credential-value";
    // The legacy on-disk format is exactly the envelope payload: Base64(IV || ciphertext+tag).
    String envelope = service.encrypt(secret);
    String legacy = envelope.substring("enc:v1:1:".length());

    assertThat(service.decrypt(legacy)).isEqualTo(secret);
  }

  @Test
  void plaintextAndProviderPrefixedValuesPassThroughUnchanged() {
    var service = service(KEY_A, "1", Map.of(), true);

    assertThat(service.decrypt("short")).isEqualTo("short");
    assertThat(service.decrypt("sk-proj-1234567890123456789012345678901234567890"))
        .isEqualTo("sk-proj-1234567890123456789012345678901234567890");
  }

  @Test
  void wrongKeyFailsClosedInsteadOfReturningCiphertext() {
    var writer = service(KEY_A, "1", Map.of(), true);
    var readerWithWrongKey = service(KEY_B, "1", Map.of(), true);
    String stored = writer.encrypt("secret-value");

    assertThatThrownBy(() -> readerWithWrongKey.decrypt(stored))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("wrong key or tampered");
  }

  @Test
  void unknownKeyVersionFailsClosed() {
    var writer = service(KEY_A, "9", Map.of(), true);
    var reader = service(KEY_A, "1", Map.of(), true);
    String stored = writer.encrypt("secret-value");

    assertThatThrownBy(() -> reader.decrypt(stored))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No key registered for envelope key version '9'");
  }

  @Test
  void rotationDecryptsOldVersionEnvelopesWhileWritingNewOnes() {
    var old = service(KEY_A, "1", Map.of(), true);
    String storedUnderOldKey = old.encrypt("rotate-me");

    var rotated = service(KEY_B, "2", Map.of("1", KEY_A), true);

    assertThat(rotated.decrypt(storedUnderOldKey)).isEqualTo("rotate-me");
    assertThat(rotated.encrypt("fresh")).startsWith("enc:v1:2:");
  }

  @Test
  void envelopeEncounteredWhileEncryptionDisabledFailsClosed() {
    var writer = service(KEY_A, "1", Map.of(), true);
    String stored = writer.encrypt("secret-value");
    var disabled = service(null, "1", Map.of(), false);

    assertThat(disabled.isEncryptionActive()).isFalse();
    assertThatThrownBy(() -> disabled.decrypt(stored))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("while encryption is disabled");
  }

  @Test
  void sharedProfileRejectsMutableSecretManagerAliasBeforeNetworkAccess() {
    CryptoProperties props = new CryptoProperties();
    props.setEnabled(true);
    props.setRequireEncryption(true);
    props.setGcpSecretName("projects/p/secrets/crypto/versions/latest");
    SimpleKeyEncryptionService service = new SimpleKeyEncryptionService(props);

    assertThatThrownBy(service::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable numeric Secret Manager version");
  }

  @Test
  void malformedAndUnsupportedEnvelopesFailClosed() {
    var service = service(KEY_A, "1", Map.of(), true);

    assertThatThrownBy(() -> service.decrypt("enc:v1:1:"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.decrypt("enc:v1:no-payload"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.decrypt("enc:v9:1:AAAA"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unsupported credential envelope");
  }
}
