package com.navneet.executespec.v3.api;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.v3.entitlements.EntitlementResolver;
import com.navneet.executespec.v3.entitlements.EntitlementsResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * R-142 — single-source entitlements endpoint for v3 surfaces.
 *
 * <p>Mounted under /api/v3 to keep the v2 surface untouched. The billing / pricing /
 * composer-gating logic on v3 reads exclusively from this contract, so the strict-review
 * billing-state contradiction (FREE label on an ENTERPRISE org) cannot recur.
 */
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class V3EntitlementsController {

  private final EntitlementResolver resolver;

  @GetMapping("/entitlements")
  public ResponseEntity<EntitlementsResponse> me(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(resolver.resolve(userDetails.getUser().getId()));
  }
}
