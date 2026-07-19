package com.navneet.executespec.deployment.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.navneet.executespec.crypto.CryptoProperties;
import com.navneet.executespec.crypto.SimpleKeyEncryptionService;

import org.junit.jupiter.api.Test;

class CredentialEncryptionServiceTest {

  @Test
  void newWritesUseVersionedCanonicalEnvelopeAndRoundTrip() {
    byte[] key = key(7);
    CredentialEncryptionService service = new CredentialEncryptionService(canonical("2", key), "");

    byte[] encrypted = service.encrypt("secret-json".getBytes(StandardCharsets.UTF_8));

    assertThat(new String(encrypted, StandardCharsets.UTF_8)).startsWith("dep:v1:enc:v1:2:");
    assertThat(service.decrypt(encrypted))
        .isEqualTo("secret-json".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void canonicalRetiredKeyDecryptsCredentialAfterRotation() {
    byte[] oldKey = key(3);
    byte[] newKey = key(9);
    CredentialEncryptionService oldService =
        new CredentialEncryptionService(canonical("1", oldKey), "");
    byte[] encrypted = oldService.encrypt("rotate-me".getBytes(StandardCharsets.UTF_8));

    CryptoProperties rotatedProperties = properties("2", newKey);
    rotatedProperties.setPreviousKeys(Map.of("1", Base64.getEncoder().encodeToString(oldKey)));
    SimpleKeyEncryptionService rotatedCanonical = new SimpleKeyEncryptionService(rotatedProperties);
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(rotatedCanonical, "init");
    CredentialEncryptionService rotated = new CredentialEncryptionService(rotatedCanonical, "");

    assertThat(rotated.decrypt(encrypted)).isEqualTo("rotate-me".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void legacyCiphertextRequiresExplicitHistoricalKey() throws Exception {
    byte[] legacyKey = key(4);
    byte[] legacy = legacyEncrypt("legacy-json".getBytes(StandardCharsets.UTF_8), legacyKey);
    CredentialEncryptionService withoutLegacy =
        new CredentialEncryptionService(canonical("1", key(8)), "");

    assertThatThrownBy(() -> withoutLegacy.decrypt(legacy))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires agent.deployment.credential-encryption-key");

    CredentialEncryptionService withLegacy =
        new CredentialEncryptionService(
            canonical("1", key(8)), Base64.getEncoder().encodeToString(legacyKey));
    assertThat(withLegacy.decrypt(legacy))
        .isEqualTo("legacy-json".getBytes(StandardCharsets.UTF_8));
  }

  private static SimpleKeyEncryptionService canonical(String version, byte[] key) {
    SimpleKeyEncryptionService service = new SimpleKeyEncryptionService(properties(version, key));
    org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "init");
    return service;
  }

  private static CryptoProperties properties(String version, byte[] key) {
    CryptoProperties properties = new CryptoProperties();
    properties.setEnabled(true);
    properties.setRequireEncryption(true);
    properties.setKeyVersion(version);
    properties.setLocalKey(Base64.getEncoder().encodeToString(key));
    return properties;
  }

  private static byte[] legacyEncrypt(byte[] plaintext, byte[] key) throws Exception {
    byte[] iv = new byte[12];
    new SecureRandom().nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
    byte[] ciphertext = cipher.doFinal(plaintext);
    return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
  }

  private static byte[] key(int seed) {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) seed);
    return key;
  }
}
