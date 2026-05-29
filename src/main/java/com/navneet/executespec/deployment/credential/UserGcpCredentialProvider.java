package com.navneet.executespec.deployment.credential;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves a user's stored GCP credentials into a {@link GoogleCredentials} object that can be used
 * to authenticate GCP API calls on the user's behalf.
 *
 * <p>Resolution order: project-scoped credential → user-level default → not found.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserGcpCredentialProvider {

  private final DeploymentCredentialRepository credentialRepo;
  private final CredentialEncryptionService encryptionService;

  /** Load and decrypt the user's GCP credentials (user-level default). */
  public Optional<GoogleCredentials> getCredentials(String userId) {
    return resolveCredential(userId, null).map(this::decryptToGoogleCredentials);
  }

  /** Load credentials with project-scoped resolution. Tries: project-level → user default. */
  public Optional<GoogleCredentials> getCredentials(String userId, String projectId) {
    return resolveCredential(userId, projectId).map(this::decryptToGoogleCredentials);
  }

  /** Load credentials or throw if not found. */
  public GoogleCredentials getCredentialsOrThrow(String userId) {
    return getCredentials(userId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No GCP credentials stored for user "
                        + userId
                        + ". Please upload a service account key first."));
  }

  /** Load credentials with project scope or throw if not found. */
  public GoogleCredentials getCredentialsOrThrow(String userId, String projectId) {
    return getCredentials(userId, projectId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No GCP credentials found for user "
                        + userId
                        + (projectId != null ? " (project: " + projectId + ")" : "")
                        + ". Please upload a service account key first."));
  }

  /** Get the GCP project ID associated with the user's stored credentials. */
  public Optional<String> getGcpProjectId(String userId) {
    return resolveCredential(userId, null).map(DeploymentCredential::getGcpProjectId);
  }

  /** Get GCP project ID with project-scoped resolution. */
  public Optional<String> getGcpProjectId(String userId, String projectId) {
    return resolveCredential(userId, projectId).map(DeploymentCredential::getGcpProjectId);
  }

  /** Check if a user has stored GCP credentials (any scope). */
  public boolean hasCredentials(String userId) {
    return credentialRepo.existsByUserId(userId);
  }

  /** Resolve credential: project-level → user default. */
  private Optional<DeploymentCredential> resolveCredential(String userId, String projectId) {
    if (projectId != null) {
      var projectCred =
          credentialRepo.findByUserIdAndProjectIdAndCloudProvider(userId, projectId, "GCP");
      if (projectCred.isPresent()) return projectCred;
    }
    return credentialRepo.findUserDefault(userId, "GCP");
  }

  private GoogleCredentials decryptToGoogleCredentials(DeploymentCredential credential) {
    try {
      byte[] decrypted = encryptionService.decrypt(credential.getEncryptedData());
      String json = new String(decrypted, StandardCharsets.UTF_8);
      return ServiceAccountCredentials.fromStream(
          new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      log.error(
          "Failed to decrypt/parse GCP credentials for user {}: {}",
          credential.getUserId(),
          e.getMessage());
      throw new RuntimeException("Failed to load GCP credentials", e);
    }
  }
}
