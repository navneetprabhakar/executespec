package com.navneet.executespec.deployment.credential;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

/**
 * REST API for managing GCP deployment credentials. Users upload their service account JSON key,
 * and it's encrypted + stored.
 *
 * <p>Credentials can be user-level (default) or project-scoped.
 */
@RestController
@RequestMapping("/api/credentials/gcp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.deployment.enabled", havingValue = "true")
public class CredentialController {

  private final CredentialService credentialService;
  private final ProjectAccessGuard projectAccessGuard;

  // ── Permission Requirements (shown BEFORE upload) ─────────────────────────

  /**
   * Returns the list of required GCP IAM roles/permissions the service account needs. Show this to
   * the user before they create and upload a service account key.
   */
  @GetMapping("/required-permissions")
  public ResponseEntity<List<CredentialService.RequiredPermission>> requiredPermissions() {
    return ResponseEntity.ok(credentialService.getRequiredPermissions());
  }

  // ── Store ─────────────────────────────────────────────────────────────────

  /**
   * Store (or replace) GCP service account credentials. The request body is the raw JSON key file
   * content.
   *
   * @param projectId optional — scopes credential to an ExecuteSpec project. If null, stored as
   *     user-level default.
   */
  @PostMapping
  public ResponseEntity<CredentialService.CredentialSummary> storeCredential(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String projectId,
      @RequestBody String serviceAccountJson) {
    // Audit 2026-05-12 review item B #15 — project-scoped credential writes must verify the
    // caller can write to that project. User-default credentials (projectId=null) are owned by
    // the authenticated user and don't need the project guard.
    if (projectId != null && !projectId.isBlank()) {
      projectAccessGuard.assertCanWrite(projectId, userDetails);
    }
    var summary =
        credentialService.storeCredential(
            userDetails.getUser().getId(), projectId, serviceAccountJson);
    return ResponseEntity.ok(summary);
  }

  // ── Retrieve ──────────────────────────────────────────────────────────────

  /**
   * Get current credential status (without sensitive data). Resolves: project-level → user-level
   * default.
   */
  @GetMapping
  public ResponseEntity<?> getCredential(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String projectId) {
    return credentialService
        .getCredentialSummary(userDetails.getUser().getId(), projectId)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.ok().body(null));
  }

  /** List all credentials for the current user (all project-scoped + default). */
  @GetMapping("/all")
  public ResponseEntity<List<CredentialService.CredentialSummary>> listCredentials(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    return ResponseEntity.ok(credentialService.listCredentials(userDetails.getUser().getId()));
  }

  // ── Validate ──────────────────────────────────────────────────────────────

  /**
   * Validate stored credentials can authenticate to GCP and have required permissions. Returns
   * per-permission validation status (e.g., Cloud Run Admin: ✓, Cloud Build: ✗).
   */
  @GetMapping("/validate")
  public ResponseEntity<CredentialService.ValidationResult> validateCredential(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String projectId) {
    var result = credentialService.validateCredential(userDetails.getUser().getId(), projectId);
    return ResponseEntity.ok(result);
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  /** Delete stored GCP credentials. */
  @DeleteMapping
  public ResponseEntity<Map<String, String>> deleteCredential(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(required = false) String projectId) {
    // Audit 2026-05-12 review item B #16 — project-scoped delete must verify caller write access
    // so an attacker can't delete legitimate per-project credentials they don't own.
    if (projectId != null && !projectId.isBlank()) {
      projectAccessGuard.assertCanWrite(projectId, userDetails);
    }
    credentialService.deleteCredential(userDetails.getUser().getId(), projectId);
    return ResponseEntity.ok(Map.of("message", "GCP credentials deleted"));
  }
}
