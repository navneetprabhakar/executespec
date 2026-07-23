package com.navneet.executespec.deployment.credential;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.navneet.executespec.crypto.SimpleKeyEncryptionService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Versioned encryption for stored deployment credentials.
 *
 * <p>New writes use {@code dep:v1:<canonical-envelope>} where the inner value is produced by the
 * application's rotation-aware {@link SimpleKeyEncryptionService}. Legacy V30 raw AES-GCM bytes
 * remain read-compatible only when their historical {@code
 * agent.deployment.credential-encryption-key} is explicitly configured. The service never creates
 * an ephemeral persistence key: doing so made every stored credential unreadable after restart.
 */
@Service
public class CredentialEncryptionService {

  static final String ENVELOPE_V1 = "dep:v1:";
  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 128;

  private final SimpleKeyEncryptionService canonicalEncryption;
  private final SecretKey legacyDecryptKey;

  public CredentialEncryptionService(
      SimpleKeyEncryptionService canonicalEncryption,
      @Value("${agent.deployment.credential-encryption-key:}") String legacyBase64Key) {
    this.canonicalEncryption = canonicalEncryption;
    this.legacyDecryptKey = parseLegacyKey(legacyBase64Key);
  }

  /** Encrypt plaintext bytes into the self-describing deployment credential envelope. */
  public byte[] encrypt(byte[] plaintext) {
    if (plaintext == null) return null;
    String payload = Base64.getEncoder().encodeToString(plaintext);
    String encrypted = canonicalEncryption.encrypt(payload);
    return (ENVELOPE_V1 + encrypted).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Decrypt a deployment credential. New envelopes use canonical versioned crypto; pre-envelope
   * rows use the explicitly configured legacy key and otherwise fail closed.
   */
  public byte[] decrypt(byte[] encryptedData) {
    if (encryptedData == null) return null;
    String serialized = new String(encryptedData, StandardCharsets.UTF_8);
    if (serialized.startsWith(ENVELOPE_V1)) {
      String canonicalEnvelope = serialized.substring(ENVELOPE_V1.length());
      try {
        return Base64.getDecoder().decode(canonicalEncryption.decrypt(canonicalEnvelope));
      } catch (RuntimeException invalidEnvelope) {
        throw new IllegalStateException(
            "Deployment credential envelope decryption failed", invalidEnvelope);
      }
    }
    if (legacyDecryptKey == null) {
      throw new IllegalStateException(
          "Legacy deployment credential requires agent.deployment.credential-encryption-key; "
              + "refusing to generate an ephemeral replacement key");
    }
    return decryptLegacy(encryptedData, legacyDecryptKey);
  }

  private static SecretKey parseLegacyKey(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) return null;
    byte[] keyBytes;
    try {
      keyBytes = Base64.getDecoder().decode(base64Key.trim());
    } catch (IllegalArgumentException invalidBase64) {
      throw new IllegalArgumentException(
          "Deployment credential encryption key is not valid Base64");
    }
    if (keyBytes.length != 32) {
      throw new IllegalArgumentException(
          "Deployment credential encryption key must be 32 bytes (256-bit). Got "
              + keyBytes.length);
    }
    return new SecretKeySpec(keyBytes, "AES");
  }

  private static byte[] decryptLegacy(byte[] encryptedData, SecretKey key) {
    if (encryptedData.length < GCM_IV_LENGTH + 16) {
      throw new IllegalStateException("Legacy deployment credential ciphertext is too short");
    }
    try {
      ByteBuffer buffer = ByteBuffer.wrap(encryptedData);
      byte[] iv = new byte[GCM_IV_LENGTH];
      buffer.get(iv);
      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);
      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      return cipher.doFinal(ciphertext);
    } catch (Exception failure) {
      throw new IllegalStateException(
          "Legacy deployment credential decryption failed — wrong key or tampered data", failure);
    }
  }
}
