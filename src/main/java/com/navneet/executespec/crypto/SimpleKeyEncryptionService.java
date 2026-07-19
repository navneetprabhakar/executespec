package com.navneet.executespec.crypto;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * AES-256-GCM encryption service for credentials stored in the database.
 *
 * <p>Key source priority:
 *
 * <ol>
 *   <li>Local key from config (for dev) — skips GCP call entirely
 *   <li>GCP Secret Manager (for production)
 *   <li>Passthrough mode (no encryption) — if both missing and {@code require-encryption} is off
 * </ol>
 *
 * <p>Write format (2026-07-17 credential-security gate): a self-describing versioned envelope
 * {@code enc:v1:<keyVersion>:<Base64(IV || ciphertext+tag)>}. Envelopes fail CLOSED: an unknown
 * format version, a key version with no registered key, a GCM auth failure (wrong key / tampering),
 * or an envelope encountered while encryption is disabled all throw instead of silently returning
 * ciphertext as if it were the credential.
 *
 * <p>Read compatibility: legacy bare-Base64 values (the pre-envelope format {@code Base64(IV ||
 * ciphertext+tag)}) still decrypt leniently — on failure they are returned as-is, because that
 * heuristic path cannot distinguish plaintext-that-looks-like-Base64 from ciphertext. Legacy
 * plaintext passes through unchanged. {@link KeyMigrationRunner} re-writes both legacy forms into
 * the envelope at startup, after which the lenient path goes cold.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@EnableConfigurationProperties(CryptoProperties.class)
public class SimpleKeyEncryptionService {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 128; // bits

  /** Stable envelope prefix; bump the format token (v1 → v2) for algorithm/layout changes. */
  public static final String ENVELOPE_PREFIX = "enc:";

  public static final String ENVELOPE_V1 = "enc:v1:";

  private final CryptoProperties cryptoProperties;
  private final SecureRandom secureRandom = new SecureRandom();

  private SecretKey secretKey;
  private String keyVersion = "1";
  private final Map<String, SecretKey> decryptKeysByVersion = new LinkedHashMap<>();
  private boolean passthrough = false;

  @PostConstruct
  void init() {
    if (!cryptoProperties.isEnabled()) {
      log.warn("[Crypto] Encryption DISABLED via config — all API keys stored as plaintext");
      passthrough = true;
      return;
    }

    this.keyVersion =
        cryptoProperties.getKeyVersion() == null || cryptoProperties.getKeyVersion().isBlank()
            ? "1"
            : cryptoProperties.getKeyVersion().trim();

    // Priority 1: local key (dev)
    String localKey = cryptoProperties.getLocalKey();
    if (localKey != null && !localKey.isBlank()) {
      log.warn("[Crypto] Using local crypto key — NOT for production");
      this.secretKey = toAesKey(Base64.getDecoder().decode(localKey));
      registerDecryptKeys();
      return;
    }

    // Priority 2: GCP Secret Manager (prod)
    String gcpSecretName = cryptoProperties.getGcpSecretName();
    if (gcpSecretName != null && !gcpSecretName.isBlank()) {
      if (cryptoProperties.isRequireEncryption()
          && !gcpSecretName.matches("^projects/[^/]+/secrets/[^/]+/versions/[0-9]+$")) {
        throw new IllegalStateException(
            "[Crypto] Shared profiles require an immutable numeric Secret Manager version;"
                + " configure CRYPTO_GCP_SECRET_RESOURCE with /versions/<number>, never an alias");
      }
      try {
        this.secretKey = toAesKey(fetchFromSecretManager(gcpSecretName));
        registerDecryptKeys();
        log.info("[Crypto] Encryption key loaded from GCP Secret Manager");
        return;
      } catch (Exception e) {
        log.error(
            "[Crypto] Failed to fetch key from GCP Secret Manager errorClass={}",
            e.getClass().getSimpleName());
        // Fall through to passthrough
      }
    }

    // Priority 3: passthrough — fail-fast in production-pinned deployments.
    // Audit: 2026-05-12 line-by-line review item H #52.
    if (cryptoProperties.isRequireEncryption()) {
      throw new IllegalStateException(
          "[Crypto] executespec.crypto.require-encryption=true but no encryption key could be"
              + " loaded. Set executespec.crypto.local-key (dev) or CRYPTO_GCP_SECRET_RESOURCE"
              + " (shared profiles) — passthrough mode is forbidden in this profile.");
    }
    log.warn(
        "[Crypto] No encryption key configured — running in passthrough mode (no encryption). Set"
            + " executespec.crypto.local-key (dev) or executespec.crypto.gcp-secret-name (prod).");
    passthrough = true;
  }

  /** Register the primary key plus every retired rotation key for decrypt-only use. */
  private void registerDecryptKeys() {
    decryptKeysByVersion.put(keyVersion, secretKey);
    Map<String, String> previous = cryptoProperties.getPreviousKeys();
    if (previous == null) return;
    for (Map.Entry<String, String> entry : previous.entrySet()) {
      String version = entry.getKey() == null ? "" : entry.getKey().trim();
      String material = entry.getValue() == null ? "" : entry.getValue().trim();
      if (version.isEmpty() || material.isEmpty()) continue;
      try {
        byte[] keyBytes =
            material.startsWith("projects/")
                ? fetchFromSecretManager(material)
                : Base64.getDecoder().decode(material);
        decryptKeysByVersion.putIfAbsent(version, toAesKey(keyBytes));
        log.info("[Crypto] Registered retired decrypt-only key version '{}'", version);
      } catch (Exception e) {
        // A broken retired key must not take the service down; envelopes written under it will
        // fail closed at decrypt time with a precise error instead.
        log.error(
            "[Crypto] Failed to load retired key version '{}' errorClass={}",
            version,
            e.getClass().getSimpleName());
      }
    }
  }

  /**
   * Encrypt a plaintext string into a versioned envelope.
   *
   * @return {@code enc:v1:<keyVersion>:<Base64(IV || ciphertext+tag)>}, or the original string in
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

      return ENVELOPE_V1 + keyVersion + ":" + Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
      throw new RuntimeException("API key encryption failed", e);
    }
  }

  /**
   * Decrypt a stored value.
   *
   * <p>Envelopes ({@code enc:...}) fail closed on any problem. Legacy bare-Base64 ciphertext
   * decrypts leniently (returns the input on failure); anything else is legacy plaintext and passes
   * through unchanged.
   */
  public String decrypt(String encrypted) {
    if (encrypted == null) return null;
    if (encrypted.startsWith(ENVELOPE_PREFIX)) {
      return decryptEnvelope(encrypted);
    }
    if (passthrough) return encrypted;

    // Guard: if the value doesn't look like Base64 (legacy plaintext), return as-is
    if (!isLikelyEncrypted(encrypted)) {
      return encrypted;
    }

    try {
      return decryptWithKey(Base64.getDecoder().decode(encrypted), secretKey);
    } catch (Exception e) {
      // Legacy path only: might be plaintext that happens to be valid Base64.
      log.debug(
          "[Crypto] Legacy decryption failed, returning value as-is (likely legacy plaintext)"
              + " errorClass={}",
          e.getClass().getSimpleName());
      return encrypted;
    }
  }

  /** Envelope decrypt — every failure mode throws; ciphertext is never returned as a value. */
  private String decryptEnvelope(String envelope) {
    if (passthrough) {
      throw new IllegalStateException(
          "[Crypto] Encountered an encrypted credential envelope while encryption is disabled —"
              + " enable executespec.crypto and supply the key instead of reading ciphertext");
    }
    if (!envelope.startsWith(ENVELOPE_V1)) {
      throw new IllegalStateException(
          "[Crypto] Unsupported credential envelope format: "
              + envelope.substring(0, Math.min(12, envelope.length())));
    }
    String rest = envelope.substring(ENVELOPE_V1.length());
    int separator = rest.indexOf(':');
    if (separator <= 0 || separator == rest.length() - 1) {
      throw new IllegalStateException("[Crypto] Malformed credential envelope");
    }
    String version = rest.substring(0, separator);
    String payload = rest.substring(separator + 1);
    SecretKey key = decryptKeysByVersion.get(version);
    if (key == null) {
      throw new IllegalStateException(
          "[Crypto] No key registered for envelope key version '"
              + version
              + "' — configure executespec.crypto.previous-keys for rotation");
    }
    try {
      return decryptWithKey(Base64.getDecoder().decode(payload), key);
    } catch (Exception e) {
      throw new IllegalStateException(
          "[Crypto] Credential envelope decryption failed for key version '"
              + version
              + "' (wrong key or tampered value)",
          e);
    }
  }

  private static String decryptWithKey(byte[] combined, SecretKey key) throws Exception {
    if (combined.length < GCM_IV_LENGTH + 1) {
      throw new IllegalArgumentException("ciphertext too short");
    }
    ByteBuffer buffer = ByteBuffer.wrap(combined);
    byte[] iv = new byte[GCM_IV_LENGTH];
    buffer.get(iv);
    byte[] ciphertext = new byte[buffer.remaining()];
    buffer.get(ciphertext);

    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
    byte[] plaintext = cipher.doFinal(ciphertext);
    return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** Check if encryption is active (not passthrough). */
  public boolean isEncryptionActive() {
    return !passthrough;
  }

  /** True when the value is already a self-describing envelope. */
  public boolean isEnveloped(String value) {
    return value != null && value.startsWith(ENVELOPE_PREFIX);
  }

  /** True only when an envelope is already written under the active primary key version. */
  public boolean isCurrentEnvelope(String value) {
    return value != null && value.startsWith(ENVELOPE_V1 + keyVersion + ":");
  }

  private byte[] fetchFromSecretManager(String resourceName) throws Exception {
    try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
      AccessSecretVersionResponse response = client.accessSecretVersion(resourceName);
      return response.getPayload().getData().toByteArray();
    }
  }

  private static SecretKey toAesKey(byte[] keyBytes) {
    if (keyBytes.length != 32) {
      throw new IllegalArgumentException(
          "Crypto key must be 32 bytes (256-bit AES). Got " + keyBytes.length + " bytes.");
    }
    return new SecretKeySpec(keyBytes, "AES");
  }

  /**
   * Heuristic for LEGACY bare-Base64 ciphertext: at least ~40 chars (12-byte IV + 16-byte tag + min
   * 1-byte ciphertext = 29 bytes → 40 Base64 chars). API keys like "sk-..." or "AIza..." won't
   * match. Exposed for {@link KeyMigrationRunner} so it can skip already-encrypted rows instead of
   * re-encrypting them on every boot (AES-GCM's random IV makes byte-equality always false → audit
   * 2026-05-12 item H #51). New envelopes are detected exactly via {@link #isEnveloped} instead.
   */
  public boolean isLikelyEncrypted(String value) {
    if (isEnveloped(value)) return true;
    if (value.length() < 40) return false;
    // API keys typically start with known prefixes — skip decryption for those
    if (value.startsWith("sk-") || value.startsWith("AIza") || value.startsWith("xoxb-"))
      return false;
    // Check if it's valid Base64 (no whitespace, valid chars)
    return value.matches("^[A-Za-z0-9+/=]+$");
  }
}
