package com.navneet.executespec.deployment.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages lifecycle of user GCP deployment credentials:
 * store, validate, retrieve, delete.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialService {

    private final DeploymentCredentialRepository credentialRepo;
    private final CredentialEncryptionService encryptionService;
    private final UserGcpCredentialProvider credentialProvider;
    private final ObjectMapper objectMapper;

    /**
     * Store (or replace) a user's GCP service account key.
     * Parses the JSON to extract project_id and client_email, then encrypts and stores.
     *
     * @param userId           the user storing the credential
     * @param serviceAccountJson the raw JSON key file content
     * @return summary of stored credential (never includes the key itself)
     */
    @Transactional
    public CredentialSummary storeCredential(String userId, String serviceAccountJson) {
        // 1. Parse JSON to extract metadata
        JsonNode root;
        try {
            root = objectMapper.readTree(serviceAccountJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON — please upload a valid GCP service account key file");
        }

        String type = textOrNull(root, "type");
        if (!"service_account".equals(type)) {
            throw new IllegalArgumentException(
                    "Invalid key type: '" + type + "'. Expected 'service_account'. "
                            + "Please upload a GCP service account key (not an OAuth client or API key).");
        }

        String projectId = textOrNull(root, "project_id");
        String clientEmail = textOrNull(root, "client_email");
        String privateKeyId = textOrNull(root, "private_key_id");

        if (projectId == null || clientEmail == null || privateKeyId == null) {
            throw new IllegalArgumentException(
                    "Service account key is missing required fields (project_id, client_email, private_key_id)");
        }

        // 2. Encrypt the raw JSON
        byte[] encrypted = encryptionService.encrypt(serviceAccountJson.getBytes(StandardCharsets.UTF_8));

        // 3. Upsert: delete existing if present, then create
        credentialRepo.findByUserIdAndCloudProvider(userId, "GCP")
                .ifPresent(existing -> credentialRepo.delete(existing));

        DeploymentCredential credential = DeploymentCredential.create(
                userId, encrypted, projectId, clientEmail);
        credentialRepo.save(credential);

        log.info("Stored GCP credential for user {} — project={}, sa={}", userId, projectId, clientEmail);

        return new CredentialSummary(
                credential.getId(),
                projectId,
                clientEmail,
                credential.getCreatedAt().toString(),
                credential.getValidatedAt() != null ? credential.getValidatedAt().toString() : null
        );
    }

    /**
     * Validate that stored credentials can actually authenticate to GCP.
     */
    @Transactional
    public ValidationResult validateCredential(String userId) {
        var credOpt = credentialRepo.findByUserIdAndCloudProvider(userId, "GCP");
        if (credOpt.isEmpty()) {
            return new ValidationResult(false, List.of("No GCP credentials stored"), null);
        }

        var cred = credOpt.get();
        List<String> issues = new ArrayList<>();

        try {
            GoogleCredentials credentials = credentialProvider.getCredentialsOrThrow(userId);
            // Try to refresh the access token — this validates the key is still active
            credentials.refreshIfExpired();

            // Mark as validated
            cred.markValidated();
            credentialRepo.save(cred);

            log.info("GCP credential validated for user {} — project={}", userId, cred.getGcpProjectId());
        } catch (Exception e) {
            issues.add("Authentication failed: " + e.getMessage());
            log.warn("GCP credential validation failed for user {}: {}", userId, e.getMessage());
        }

        return new ValidationResult(
                issues.isEmpty(),
                issues,
                cred.getGcpProjectId()
        );
    }

    /**
     * Get credential summary without sensitive data.
     */
    public Optional<CredentialSummary> getCredentialSummary(String userId) {
        return credentialRepo.findByUserIdAndCloudProvider(userId, "GCP")
                .map(c -> new CredentialSummary(
                        c.getId(),
                        c.getGcpProjectId(),
                        c.getServiceAccountEmail(),
                        c.getCreatedAt().toString(),
                        c.getValidatedAt() != null ? c.getValidatedAt().toString() : null
                ));
    }

    /**
     * Delete stored credentials for a user.
     */
    @Transactional
    public void deleteCredential(String userId) {
        credentialRepo.findByUserIdAndCloudProvider(userId, "GCP")
                .ifPresent(c -> {
                    credentialRepo.delete(c);
                    log.info("Deleted GCP credential for user {}", userId);
                });
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CredentialSummary(
            String id,
            String gcpProjectId,
            String serviceAccountEmail,
            String createdAt,
            String validatedAt
    ) {}

    public record ValidationResult(
            boolean valid,
            List<String> issues,
            String gcpProjectId
    ) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
