package com.navneet.executespec.crypto;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PostConstruct;

import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AES-256-GCM encryption service for API keys stored in the database.
 *
 * <p>Key source priority:
 *
 * <ol>
 *   <li>Local key from config (for dev) — skips GCP call entirely
 *   <li>GCP Secret Manager (for production)
 *   <li>Passthrough mode (no encryption) — if both missing
 * </ol>
 *
 * <p>Encrypted format: Base64( [12-byte IV] + [ciphertext + 16-byte GCM tag] )
 */
@Service
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(CryptoProperties.class)
public class SimpleKeyEncryptionService {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 128; // bits

  private final CryptoProperties cryptoProperties;
  private final SecureRandom secureRandom = new SecureRandom();

  private SecretKey secretKey;
  private boolean passthrough = false;

  @PostConstruct
  void init() {
    if (!cryptoProperties.isEnabled()) {
      log.warn("[Crypto] Encryption DISABLED via config — all API keys stored as plaintext");
      passthrough = true;
      return;
    }

    // Priority 1: local key (dev)
    String localKey = cryptoProperties.getLocalKey();
    if (localKey != null && !localKey.isBlank()) {
      log.warn("[Crypto] Using local crypto key — NOT for production");
      byte[] keyBytes = Base64.getDecoder().decode(localKey);
      validateKeyLength(keyBytes);
      this.secretKey = new SecretKeySpec(keyBytes, "AES");
      return;
    }

    // Priority 2: GCP Secret Manager (prod)
    String gcpSecretName = cryptoProperties.getGcpSecretName();
    if (gcpSecretName != null && !gcpSecretName.isBlank()) {
      try {
        byte[] keyBytes = fetchFromSecretManager(gcpSecretName);
        validateKeyLength(keyBytes);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("[Crypto] Encryption key loaded from GCP Secret Manager");
        return;
      } catch (Exception e) {
        log.error("[Crypto] Failed to fetch key from GCP Secret Manager: {}", e.getMessage());
        // Fall through to passthrough
      }
    }

    // Priority 3: passthrough — fail-fast in production-pinned deployments.
    // Audit: 2026-05-12 line-by-line review item H #52.
    if (cryptoProperties.isRequireEncryption()) {
      throw new IllegalStateException(
          "[Crypto] executespec.crypto.require-encryption=true but no encryption key could be"
              + " loaded. Set executespec.crypto.local-key (dev) or"
              + " executespec.crypto.gcp-secret-name (prod) — passthrough mode is forbidden in this"
              + " profile.");
    }
    log.warn(
        "[Crypto] No encryption key configured — running in passthrough mode (no encryption). Set"
            + " executespec.crypto.local-key (dev) or executespec.crypto.gcp-secret-name (prod).");
    passthrough = true;
  }

  /**
   * Encrypt a plaintext string.
   *
   * @return Base64-encoded ciphertext (IV + ciphertext + GCM tag), or the original string in
   *     passthrough mode
   */
  public String encrypt(String plaintext) {
    if (plaintext == null) return null;
    if (passthrough) return plaintext;

    try {
      byte[] iv = new byte[GCM_IV_LENGTH];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] ciphertext =
          cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

      byte[] combined =
          ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();

      return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      throw new RuntimeException("API key encryption failed", e);
    }
  }

  /**
   * Decrypt a Base64-encoded ciphertext string.
   *
   * @return the original plaintext, or the input string as-is in passthrough mode
   */
  public String decrypt(String encrypted) {
    if (encrypted == null) return null;
    if (passthrough) return encrypted;

    // Guard: if the value doesn't look like Base64 (legacy plaintext), return as-is
    if (!isLikelyEncrypted(encrypted)) {
      return encrypted;
    }

    try {
      byte[] combined = Base64.getDecoder().decode(encrypted);
      if (combined.length < GCM_IV_LENGTH + 1) {
        // Too short to be encrypted — likely a legacy plaintext value
        return encrypted;
      }

      ByteBuffer buffer = ByteBuffer.wrap(combined);
      byte[] iv = new byte[GCM_IV_LENGTH];
      buffer.get(iv);

      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
      byte[] plaintext = cipher.doFinal(ciphertext);

      return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      // Decryption failed — might be legacy plaintext that happens to be valid Base64
      log.debug(
          "[Crypto] Decryption failed, returning value as-is (likely legacy plaintext): {}",
          e.getMessage());
      return encrypted;
    }
  }

  /** Check if encryption is active (not passthrough). */
  public boolean isEncryptionActive() {
    return !passthrough;
  }

  private byte[] fetchFromSecretManager(String resourceName) throws Exception {
    try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
      AccessSecretVersionResponse response = client.accessSecretVersion(resourceName);
      return response.getPayload().getData().toByteArray();
    }
  }

  private void validateKeyLength(byte[] keyBytes) {
    if (keyBytes.length != 32) {
      throw new IllegalArgumentException(
          "Crypto key must be 32 bytes (256-bit AES). Got " + keyBytes.length + " bytes.");
    }
  }

  /**
   * Heuristic: encrypted values are Base64-encoded and at least ~40 chars (12-byte IV + 16-byte tag
   * + min 1-byte ciphertext = 29 bytes → 40 Base64 chars). API keys like "sk-..." or "AIza..."
   * won't match. Exposed for {@link KeyMigrationRunner} so it can skip already-encrypted rows
   * instead of re-encrypting them on every boot (AES-GCM's random IV makes byte-equality always
   * false → audit 2026-05-12 item H #51).
   */
  public boolean isLikelyEncrypted(String value) {
    if (value.length() < 40) return false;
    // API keys typically start with known prefixes — skip decryption for those
    if (value.startsWith("sk-") || value.startsWith("AIza") || value.startsWith("xoxb-"))
      return false;
    // Check if it's valid Base64 (no whitespace, valid chars)
    return value.matches("^[A-Za-z0-9+/=]+$");
  }
}
