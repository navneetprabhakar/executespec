package com.navneet.executespec.deployment.credential;

import com.navneet.executespec.auth.security.AppUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for managing GCP deployment credentials.
 * Users upload their service account JSON key, and it's encrypted + stored.
 */
@RestController
@RequestMapping("/api/credentials/gcp")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.deployment.enabled", havingValue = "true")
public class CredentialController {

    private final CredentialService credentialService;

    /**
     * Store (or replace) GCP service account credentials.
     * The request body is the raw JSON key file content.
     */
    @PostMapping
    public ResponseEntity<CredentialService.CredentialSummary> storeCredential(
            @AuthenticationPrincipal AppUserDetails userDetails,
            @RequestBody String serviceAccountJson) {
        var summary = credentialService.storeCredential(userDetails.getUser().getId(), serviceAccountJson);
        return ResponseEntity.ok(summary);
    }

    /**
     * Get current credential status (without sensitive data).
     */
    @GetMapping
    public ResponseEntity<?> getCredential(@AuthenticationPrincipal AppUserDetails userDetails) {
        return credentialService.getCredentialSummary(userDetails.getUser().getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok().body(null));
    }

    /**
     * Validate stored credentials can authenticate to GCP.
     */
    @GetMapping("/validate")
    public ResponseEntity<CredentialService.ValidationResult> validateCredential(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        var result = credentialService.validateCredential(userDetails.getUser().getId());
        return ResponseEntity.ok(result);
    }

    /**
     * Delete stored GCP credentials.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> deleteCredential(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        credentialService.deleteCredential(userDetails.getUser().getId());
        return ResponseEntity.ok(Map.of("message", "GCP credentials deleted"));
    }
}
