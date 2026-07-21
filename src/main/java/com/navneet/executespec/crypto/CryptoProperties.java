package com.navneet.executespec.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Configuration properties for API key encryption.
 *
 * <p>Key resolution order:
 *
 * <ol>
 *   <li>GCP Secret Manager ({@code gcpSecretName}) — production
 *   <li>Local key ({@code localKey}) — local development
 *   <li>Passthrough (no encryption) — if both are missing
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "executespec.crypto")
public class CryptoProperties {

  /** Master switch. When false, encrypt/decrypt are no-ops (passthrough). */
  private boolean enabled = true;

  /**
   * Full GCP Secret Manager resource name, e.g. {@code
   * projects/my-project/secrets/executespec-crypto-key/versions/7}. Shared profiles require an
   * immutable numeric version and reject aliases such as {@code latest}.
   */
  private String gcpSecretName;

  /**
   * Base64-encoded 32-byte AES key for local development. If set, GCP Secret Manager call is
   * skipped entirely.
   */
  private String localKey;

  /**
   * When true, the application FAILS TO START if no encryption key can be loaded (instead of
   * silently falling through to passthrough plaintext-storage mode). Strongly recommended for
   * production deployments. Default is {@code false} to preserve dev-friendly boot semantics.
   *
   * <p>Audit: 2026-05-12 line-by-line review item H #52.
   */
  private boolean requireEncryption = false;

  /**
   * Version label stamped into every newly written {@code enc:v1:<keyVersion>:...} envelope and
   * used to select the decryption key. Rotate by adding the old key to {@link #previousKeys} under
   * its version label and bumping this to the new key's label.
   */
  private String keyVersion = "1";

  /**
   * Retired key material kept for decrypt-only use during rotation, keyed by version label. Values
   * are Base64-encoded 32-byte AES keys (or GCP Secret Manager resource names when they start with
   * {@code projects/}). Envelopes written under these versions decrypt normally; new writes always
   * use the primary key + {@link #keyVersion}.
   */
  private java.util.Map<String, String> previousKeys = java.util.Map.of();
}
