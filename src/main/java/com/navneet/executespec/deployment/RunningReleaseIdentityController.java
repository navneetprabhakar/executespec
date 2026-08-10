package com.navneet.executespec.deployment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** SUPER_ADMIN-only, no-store attestation of the exact deployment-host release marker. */
@RestController
@RequestMapping("/api/admin/release-identity")
@RequiredArgsConstructor
public class RunningReleaseIdentityController {

  private final RunningReleaseIdentityService identities;

  @GetMapping
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public ResponseEntity<RunningReleaseIdentityService.ReleaseIdentity> read() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identities.read());
  }
}
