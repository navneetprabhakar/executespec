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
   * projects/my-project/secrets/executespec-crypto-key/versions/latest}
   */
  private String gcpSecretName;

  /**
   * Base64-encoded 32-byte AES key for local development. If set, GCP Secret Manager call is
   * skipped entirely.
   */
  private String localKey;
}
