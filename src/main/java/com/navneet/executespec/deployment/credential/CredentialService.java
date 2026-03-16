package com.navneet.executespec.deployment.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Manages lifecycle of user GCP deployment credentials:
 * store, validate, retrieve, delete.
 * <p>
 * Credentials can be:
 * <ul>
 *   <li><b>User-level default</b>: projectId = null — used when no project-level credential exists</li>
 *   <li><b>Project-scoped</b>: tied to an ExecuteSpec project — different teams can deploy to different GCP projects</li>
 * </ul>
 * Resolution order: project-level → user-level default → not found.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialService {

    private final DeploymentCredentialRepository credentialRepo;
    private final CredentialEncryptionService encryptionService;
    private final UserGcpCredentialProvider credentialProvider;
    private final ObjectMapper objectMapper;

    // ── Required GCP Permissions ──────────────────────────────────────────────

    /**
     * GCP IAM roles/permissions required for the deploy engine.
     * Displayed to the user BEFORE they upload credentials so they can
     * configure the service account correctly.
     */
    public static final List<RequiredPermission> REQUIRED_PERMISSIONS = List.of(
            new RequiredPermission(
                    "roles/run.admin",
                    "Cloud Run Admin",
                    "Create, update, and delete Cloud Run services in your project",
                    true),
            new RequiredPermission(
                    "roles/cloudbuild.builds.editor",
                    "Cloud Build Editor",
                    "Submit and view Cloud Build jobs for building container images",
                    true),
            new RequiredPermission(
                    "roles/artifactregistry.admin",
                    "Artifact Registry Admin",
                    "Create repos and push/pull container images",
                    true),
            new RequiredPermission(
                    "roles/storage.admin",
                    "Storage Admin",
                    "Create and manage GCS buckets for build source uploads",
                    true),
            new RequiredPermission(
                    "roles/iam.serviceAccountUser",
                    "Service Account User",
                    "Allow Cloud Build and Cloud Run to act as the service account",
                    true),
            new RequiredPermission(
                    "roles/compute.admin",
                    "Compute Engine Admin",
                    "Provision VMs for Docker Compose deployments (Path B only)",
                    false)
    );

    /**
     * Returns the static list of required permissions with descriptions.
     * Shown to the user before they upload credentials.
     */
    public List<RequiredPermission> getRequiredPermissions() {
        return REQUIRED_PERMISSIONS;
    }

    // ── Store ─────────────────────────────────────────────────────────────────

    /**
     * Store (or replace) a user's GCP service account key.
     * Optionally scoped to an ExecuteSpec project.
     */
    @Transactional
    public CredentialSummary storeCredential(String userId, String projectId,
                                              String serviceAccountJson) {
        // 1. Parse JSON to extract metadata
        JsonNode root;
        try {
            root = objectMapper.readTree(serviceAccountJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Invalid JSON — please upload a valid GCP service account key file");
        }

        String type = textOrNull(root, "type");
        if (!"service_account".equals(type)) {
            throw new IllegalArgumentException(
                    "Invalid key type: '" + type + "'. Expected 'service_account'. "
                            + "Please upload a GCP service account key (not an OAuth client or API key).");
        }

        String gcpProjectId = textOrNull(root, "project_id");
        String clientEmail = textOrNull(root, "client_email");
        String privateKeyId = textOrNull(root, "private_key_id");

        if (gcpProjectId == null || clientEmail == null || privateKeyId == null) {
            throw new IllegalArgumentException(
                    "Service account key is missing required fields (project_id, client_email, private_key_id)");
        }

        // 2. Encrypt the raw JSON
        byte[] encrypted = encryptionService.encrypt(
                serviceAccountJson.getBytes(StandardCharsets.UTF_8));

        // 3. Upsert: delete existing for this user+project combo, then create
        if (projectId != null) {
            credentialRepo.findByUserIdAndProjectIdAndCloudProvider(userId, projectId, "GCP")
                    .ifPresent(credentialRepo::delete);
        } else {
            credentialRepo.findUserDefault(userId, "GCP")
                    .ifPresent(credentialRepo::delete);
        }

        DeploymentCredential credential = DeploymentCredential.create(
                userId, projectId, encrypted, gcpProjectId, clientEmail);
        credentialRepo.save(credential);

        log.info("Stored GCP credential for user {} — project={}, gcpProject={}, sa={}",
                userId, projectId, gcpProjectId, clientEmail);

        return toSummary(credential);
    }

    /**
     * Store user-level default credential (backward compatible).
     */
    @Transactional
    public CredentialSummary storeCredential(String userId, String serviceAccountJson) {
        return storeCredential(userId, null, serviceAccountJson);
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    /**
     * Validate that stored credentials can authenticate to GCP and have the
     * required permissions. Returns per-permission validation status.
     */
    @Transactional
    public ValidationResult validateCredential(String userId) {
        return validateCredential(userId, null);
    }

    /**
     * Validate credentials for a specific project scope.
     */
    @Transactional
    public ValidationResult validateCredential(String userId, String projectId) {
        DeploymentCredential cred = resolveCredential(userId, projectId);
        if (cred == null) {
            return new ValidationResult(false, List.of("No GCP credentials stored"),
                    null, List.of());
        }

        List<String> issues = new ArrayList<>();
        List<PermissionCheck> permissionChecks = new ArrayList<>();

        try {
            GoogleCredentials credentials = credentialProvider.getCredentialsOrThrow(userId);
            // Try to refresh the access token — validates the key is active
            credentials.refreshIfExpired();

            // Basic auth works — now check individual permissions
            permissionChecks = checkPermissions(credentials, cred.getGcpProjectId());

            // Count failed required permissions
            long failedRequired = permissionChecks.stream()
                    .filter(p -> p.required() && !p.granted())
                    .count();
            if (failedRequired > 0) {
                issues.add(failedRequired + " required permission(s) missing");
            }

            // Mark as validated (even if some permissions missing — auth itself works)
            cred.markValidated();
            credentialRepo.save(cred);

            log.info("GCP credential validated for user {} — project={}, checks={}/{}",
                    userId, cred.getGcpProjectId(),
                    permissionChecks.stream().filter(PermissionCheck::granted).count(),
                    permissionChecks.size());

        } catch (Exception e) {
            issues.add("Authentication failed: " + e.getMessage());
            log.warn("GCP credential validation failed for user {}: {}", userId, e.getMessage());
        }

        return new ValidationResult(
                issues.isEmpty(),
                issues,
                cred.getGcpProjectId(),
                permissionChecks
        );
    }

    /**
     * Check individual GCP permissions using the IAM testPermissions API.
     */
    private List<PermissionCheck> checkPermissions(GoogleCredentials credentials,
                                                    String gcpProjectId) {
        List<PermissionCheck> results = new ArrayList<>();

        try {
            // Use the Resource Manager API to test permissions on the project
            ProjectsSettings settings = ProjectsSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                    .build();

            try (ProjectsClient projectsClient = ProjectsClient.create(settings)) {
                String resource = "projects/" + gcpProjectId;

                // Map roles to specific permissions we can test
                Map<String, List<String>> rolePermissions = Map.of(
                        "roles/run.admin", List.of("run.services.create", "run.services.delete",
                                "run.services.update", "run.services.setIamPolicy"),
                        "roles/cloudbuild.builds.editor", List.of("cloudbuild.builds.create",
                                "cloudbuild.builds.get"),
                        "roles/artifactregistry.admin", List.of(
                                "artifactregistry.repositories.create",
                                "artifactregistry.repositories.get"),
                        "roles/storage.admin", List.of("storage.buckets.create",
                                "storage.objects.create"),
                        "roles/iam.serviceAccountUser", List.of("iam.serviceAccounts.actAs")
                );

                // Test all permissions in one batch
                List<String> allPermissions = rolePermissions.values().stream()
                        .flatMap(List::stream)
                        .toList();

                var response = projectsClient.testIamPermissions(resource, allPermissions);
                Set<String> grantedPermissions = new HashSet<>(response.getPermissionsList());

                // Build results per role
                for (RequiredPermission req : REQUIRED_PERMISSIONS) {
                    List<String> permsForRole = rolePermissions.getOrDefault(req.role(), List.of());
                    if (permsForRole.isEmpty()) {
                        // Optional role with no testable permissions (e.g., compute.admin)
                        results.add(new PermissionCheck(
                                req.role(), req.label(), req.required(),
                                true, "Not tested (optional for this path)"));
                        continue;
                    }

                    boolean allGranted = permsForRole.stream().allMatch(grantedPermissions::contains);
                    List<String> missing = permsForRole.stream()
                            .filter(p -> !grantedPermissions.contains(p))
                            .toList();

                    results.add(new PermissionCheck(
                            req.role(), req.label(), req.required(),
                            allGranted,
                            allGranted ? "All permissions granted"
                                    : "Missing: " + String.join(", ", missing)));
                }
            }
        } catch (Exception e) {
            log.warn("Permission check failed (auth may still work): {}", e.getMessage());
            // Return unknown status for all
            for (RequiredPermission req : REQUIRED_PERMISSIONS) {
                results.add(new PermissionCheck(
                        req.role(), req.label(), req.required(),
                        false, "Could not verify: " + e.getMessage()));
            }
        }

        return results;
    }

    // ── Retrieve ──────────────────────────────────────────────────────────────

    /**
     * Get credential summary (no sensitive data). Resolves project → user default.
     */
    public Optional<CredentialSummary> getCredentialSummary(String userId) {
        return getCredentialSummary(userId, null);
    }

    public Optional<CredentialSummary> getCredentialSummary(String userId, String projectId) {
        DeploymentCredential cred = resolveCredential(userId, projectId);
        return Optional.ofNullable(cred).map(this::toSummary);
    }

    /**
     * List all credentials for a user (all project-scoped + default).
     */
    public List<CredentialSummary> listCredentials(String userId) {
        return credentialRepo.findByUserIdOrderByProjectIdAsc(userId).stream()
                .map(this::toSummary)
                .toList();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteCredential(String userId) {
        deleteCredential(userId, null);
    }

    @Transactional
    public void deleteCredential(String userId, String projectId) {
        if (projectId != null) {
            credentialRepo.findByUserIdAndProjectIdAndCloudProvider(userId, projectId, "GCP")
                    .ifPresent(c -> {
                        credentialRepo.delete(c);
                        log.info("Deleted project-scoped GCP credential for user {} project {}", userId, projectId);
                    });
        } else {
            credentialRepo.findUserDefault(userId, "GCP")
                    .ifPresent(c -> {
                        credentialRepo.delete(c);
                        log.info("Deleted default GCP credential for user {}", userId);
                    });
        }
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * Resolve credential: project-level → user-level default → null.
     */
    private DeploymentCredential resolveCredential(String userId, String projectId) {
        if (projectId != null) {
            // Try project-scoped first
            var projectCred = credentialRepo.findByUserIdAndProjectIdAndCloudProvider(
                    userId, projectId, "GCP");
            if (projectCred.isPresent()) return projectCred.get();
        }
        // Fall back to user default
        return credentialRepo.findUserDefault(userId, "GCP").orElse(null);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record CredentialSummary(
            String id,
            String gcpProjectId,
            String serviceAccountEmail,
            String projectId,         // null = user-level default
            String createdAt,
            String validatedAt
    ) {}

    public record ValidationResult(
            boolean valid,
            List<String> issues,
            String gcpProjectId,
            List<PermissionCheck> permissionChecks
    ) {}

    public record RequiredPermission(
            String role,
            String label,
            String reason,
            boolean required          // false = optional (e.g., Compute for Path B only)
    ) {}

    public record PermissionCheck(
            String role,
            String label,
            boolean required,
            boolean granted,
            String detail
    ) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CredentialSummary toSummary(DeploymentCredential c) {
        return new CredentialSummary(
                c.getId(),
                c.getGcpProjectId(),
                c.getServiceAccountEmail(),
                c.getProjectId(),
                c.getCreatedAt().toString(),
                c.getValidatedAt() != null ? c.getValidatedAt().toString() : null
        );
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }
}
