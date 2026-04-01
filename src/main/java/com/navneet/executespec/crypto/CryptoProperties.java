package com.navneet.executespec.crypto;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for API key encryption.
 *
 * <p>Key resolution order:
 * <ol>
 *   <li>GCP Secret Manager ({@code gcpSecretName}) — production</li>
 *   <li>Local key ({@code localKey}) — local development</li>
 *   <li>Passthrough (no encryption) — if both are missing</li>
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "executespec.crypto")
public class CryptoProperties {

    /** Master switch. When false, encrypt/decrypt are no-ops (passthrough). */
    private boolean enabled = true;

    /**
     * Full GCP Secret Manager resource name, e.g.
     * {@code projects/my-project/secrets/executespec-crypto-key/versions/latest}
     */
    private String gcpSecretName;

    /**
     * Base64-encoded 32-byte AES key for local development.
     * If set, GCP Secret Manager call is skipped entirely.
     */
    private String localKey;
}
