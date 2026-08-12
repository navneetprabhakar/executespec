package com.navneet.executespec.deployment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** SUPER_ADMIN-only export of the exact running BUILD control-plane qualification tuple. */
@RestController
@RequestMapping("/api/admin/control-plane-release-attestation")
@RequiredArgsConstructor
public class ControlPlaneReleaseAttestationController {

  private final ControlPlaneReleaseAttestationService attestations;

  @GetMapping
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<ControlPlaneReleaseAttestationService.Attestation> read() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(attestations.attest());
  }
}
