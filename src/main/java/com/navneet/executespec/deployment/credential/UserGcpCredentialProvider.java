package com.navneet.executespec.deployment.credential;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Resolves a user's stored GCP credentials into a {@link GoogleCredentials} object
 * that can be used to authenticate GCP API calls on the user's behalf.
 * <p>
 * This is the bridge between the credential storage layer and GCP client libraries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserGcpCredentialProvider {

    private final DeploymentCredentialRepository credentialRepo;
    private final CredentialEncryptionService encryptionService;

    /**
     * Load and decrypt the user's GCP credentials, returning a usable GoogleCredentials object.
     *
     * @param userId the user whose credentials to load
     * @return GoogleCredentials if stored and valid, empty otherwise
     */
    public Optional<GoogleCredentials> getCredentials(String userId) {
        return credentialRepo.findByUserIdAndCloudProvider(userId, "GCP")
                .map(this::decryptToGoogleCredentials);
    }

    /**
     * Load credentials or throw if not found.
     */
    public GoogleCredentials getCredentialsOrThrow(String userId) {
        return getCredentials(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No GCP credentials stored for user " + userId
                                + ". Please upload a service account key first."));
    }

    /**
     * Get the GCP project ID associated with the user's stored credentials.
     */
    public Optional<String> getGcpProjectId(String userId) {
        return credentialRepo.findByUserIdAndCloudProvider(userId, "GCP")
                .map(DeploymentCredential::getGcpProjectId);
    }

    /**
     * Check if a user has stored GCP credentials.
     */
    public boolean hasCredentials(String userId) {
        return credentialRepo.existsByUserId(userId);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private GoogleCredentials decryptToGoogleCredentials(DeploymentCredential credential) {
        try {
            byte[] decrypted = encryptionService.decrypt(credential.getEncryptedData());
            String json = new String(decrypted, StandardCharsets.UTF_8);
            return ServiceAccountCredentials.fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("Failed to decrypt/parse GCP credentials for user {}: {}",
                    credential.getUserId(), e.getMessage());
            throw new RuntimeException("Failed to load GCP credentials", e);
        }
    }
}
