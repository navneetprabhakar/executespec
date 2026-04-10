package com.navneet.executespec.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for sensitive in-memory data persisted to Redis.
 *
 * <p>Key derivation: SHA-256 of the JWT secret → 32-byte AES key.
 * This ties the encryption to the app's existing secret management without requiring
 * a separate KMS. If the JWT secret rotates, previously-encrypted Redis entries will
 * be unreadable and will fall back to SYSTEM_ONLY (graceful degradation).
 *
 * <p>Ciphertext format: Base64( IV(12 bytes) || GCM-ciphertext )
 *
 * <p>Thread-safety: stateless except for the derived key (immutable after construction).
 */
@Slf4j
@Component
public class KeyEncryptor {

    private static final String ALGORITHM     = "AES/GCM/NoPadding";
    private static final int    IV_LENGTH     = 12;   // 96-bit nonce (GCM standard)
    private static final int    GCM_TAG_BITS  = 128;

    private final SecretKey secretKey;
    private final SecureRandom rng = new SecureRandom();

    public KeyEncryptor(@Value("${app.jwt.secret}") String jwtSecret) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive AES key from JWT secret", e);
        }
    }

    /**
     * Encrypts a plaintext string and returns a Base64-encoded ciphertext.
     *
     * @param plaintext the value to encrypt (must not be null)
     * @return Base64-encoded IV + GCM ciphertext
     * @throws RuntimeException on encryption failure
     */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            rng.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded ciphertext produced by {@link #encrypt(String)}.
     *
     * @param ciphertext Base64-encoded IV + GCM ciphertext
     * @return decrypted plaintext
     * @throws RuntimeException on decryption failure (e.g. key mismatch, tampering)
     */
    public String decrypt(String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            byte[] iv         = new byte[IV_LENGTH];
            byte[] encrypted  = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));

            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
