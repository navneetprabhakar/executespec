package com.navneet.executespec.deployment.credential;

import com.navneet.executespec.auth.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for managing GCP deployment credentials.
 * Users upload their service account JSON key, and it's encrypted + stored.
 * <p>
 * Credentials can be user-level (default) or project-scoped.
 */
@RestController
@RequestMapping("/api/credentials/gcp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.deployment.enabled", havingValue = "true")
public class CredentialController {

    private final CredentialService credentialService;

    // ── Permission Requirements (shown BEFORE upload) ─────────────────────────

    /**
     * Returns the list of required GCP IAM roles/permissions the service account needs.
     * Show this to the user before they create and upload a service account key.
     */
    @GetMapping("/required-permissions")
    public ResponseEntity<List<CredentialService.RequiredPermission>> requiredPermissions() {
        return ResponseEntity.ok(credentialService.getRequiredPermissions());
    }

    // ── Store ─────────────────────────────────────────────────────────────────

    /**
     * Store (or replace) GCP service account credentials.
     * The request body is the raw JSON key file content.
     *
     * @param projectId optional — scopes credential to an ExecuteSpec project.
     *                  If null, stored as user-level default.
     */
    @PostMapping
    public ResponseEntity<CredentialService.CredentialSummary> storeCredential(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(required = false) String projectId,
            @RequestBody String serviceAccountJson) {
        var summary = credentialService.storeCredential(
                userDetails.getUser().getId(), projectId, serviceAccountJson);
        return ResponseEntity.ok(summary);
    }

    // ── Retrieve ──────────────────────────────────────────────────────────────

    /**
     * Get current credential status (without sensitive data).
     * Resolves: project-level → user-level default.
     */
    @GetMapping
    public ResponseEntity<?> getCredential(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(required = false) String projectId) {
        return credentialService.getCredentialSummary(userDetails.getUser().getId(), projectId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok().body(null));
    }

    /**
     * List all credentials for the current user (all project-scoped + default).
     */
    @GetMapping("/all")
    public ResponseEntity<List<CredentialService.CredentialSummary>> listCredentials(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        return ResponseEntity.ok(
                credentialService.listCredentials(userDetails.getUser().getId()));
    }

    // ── Validate ──────────────────────────────────────────────────────────────

    /**
     * Validate stored credentials can authenticate to GCP and have required permissions.
     * Returns per-permission validation status (e.g., Cloud Run Admin: ✓, Cloud Build: ✗).
     */
    @GetMapping("/validate")
    public ResponseEntity<CredentialService.ValidationResult> validateCredential(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(required = false) String projectId) {
        var result = credentialService.validateCredential(
                userDetails.getUser().getId(), projectId);
        return ResponseEntity.ok(result);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Delete stored GCP credentials.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteCredential(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestParam(required = false) String projectId) {
        credentialService.deleteCredential(userDetails.getUser().getId(), projectId);
        return ResponseEntity.ok(Map.of("message", "GCP credentials deleted"));
    }
}
