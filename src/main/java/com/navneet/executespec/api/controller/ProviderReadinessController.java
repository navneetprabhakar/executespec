package com.navneet.executespec.api.controller;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.ProviderReadinessService;
import com.navneet.executespec.router.ProviderReadinessService.ProviderReadiness;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Authenticated, account-effective provider readiness consumed by Web and local clients. */
@RestController
@RequestMapping("/api/v3/provider-readiness")
@RequiredArgsConstructor
public class ProviderReadinessController {

  private final ProviderReadinessService readinessService;

  @GetMapping
  public Response readiness(@AuthenticationPrincipal AppUserDetails principal) {
    if (principal == null || principal.getUser() == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNAUTHORIZED, "Authentication required");
    }
    return new Response(1, Instant.now(), readinessService.resolve(principal.getUser().getId()));
  }

  public record Response(
      int schemaVersion, Instant generatedAt, List<ProviderReadiness> providers) {}
}
