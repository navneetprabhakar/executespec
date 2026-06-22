package com.navneet.executespec.api;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.entitlements.EntitlementResolver;
import com.navneet.executespec.entitlements.EntitlementsResponse;
import com.navneet.executespec.entitlements.GovernancePolicyResolver;
import com.navneet.executespec.entitlements.GovernancePolicyResponse;

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
public class EntitlementsController {

  private final EntitlementResolver resolver;
  private final GovernancePolicyResolver governancePolicyResolver;

  @GetMapping("/entitlements")
  public ResponseEntity<EntitlementsResponse> me(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(resolver.resolve(userDetails.getUser().getId()));
  }

  /**
   * Read side of T26 — the effective governance/model/tool policy for the caller. The CLI {@code
   * policy} / {@code doctor} surfaces consume this so they show the real policy instead of inferring
   * it. Pure-read; reflects only what the enforcement guards actually apply.
   */
  @GetMapping("/governance-policy")
  public ResponseEntity<GovernancePolicyResponse> governancePolicy(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    if (userDetails == null) {
      return ResponseEntity.status(401).build();
    }
    return ResponseEntity.ok(governancePolicyResolver.resolve(userDetails.getUser().getId()));
  }
}
