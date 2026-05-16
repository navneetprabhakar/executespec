package com.navneet.executespec.auth.sso;

import java.time.Instant;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.hibernate.validator.constraints.URL;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 1.2 #17 — SSO/SAML configuration CRUD for ORG_ADMINs.
 *
 * <p><b>Skeleton scope:</b> this controller persists the SSO config columns V127 added on {@code
 * org} ({@code sso_provider}, {@code sso_enforced}, {@code sso_metadata_url}, {@code sso_domain})
 * and exposes them via {@code GET /api/auth/sso/config} + {@code PUT /api/auth/sso/config}.
 *
 * <p><b>NOT in this skeleton:</b> the actual SAML auth flow (IdP-initiated + SP-initiated
 * handshake, assertion validation, attribute mapping). That's a multi-day integration that needs
 * the SP metadata endpoint, an SAML library (e.g. Spring Security SAML2), and per-IdP test rigs.
 * The controllers here let admins SAVE intent so the marketing site can show "SSO configured ✓" —
 * the SSO flow itself ships in a follow-up.
 *
 * <p>Tier-gating: SSO is a TEAM/ENTERPRISE feature (per persona-roadmap §5.3). Enforcement happens
 * at the {@code @PreAuthorize} level — only ORG_ADMIN can edit; tier check is a separate concern at
 * the front-end gate (PlansPage marketing copy + a {@code locked} flag in the response if the org
 * is on FREE/PRO).
 */
@RestController
@RequestMapping("/api/auth/sso")
@RequiredArgsConstructor
@Slf4j
public class SsoConfigController {

  private static final Set<String> ALLOWED_PROVIDERS =
      Set.of("GOOGLE_OIDC", "OKTA_SAML", "AZURE_AD_SAML");

  private final OrgRepository orgRepo;

  @GetMapping("/config")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public SsoConfigDto getConfig(@AuthenticationPrincipal AppUserDetails principal) {
    Org org = requireOrgFromPrincipal(principal);
    return SsoConfigDto.from(org);
  }

  @PutMapping("/config")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public SsoConfigDto putConfig(
      @AuthenticationPrincipal AppUserDetails principal, @Valid @RequestBody SsoConfigUpdate body) {
    Org org = requireOrgFromPrincipal(principal);

    if (body.provider != null && !ALLOWED_PROVIDERS.contains(body.provider)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Unknown SSO provider — supported: " + ALLOWED_PROVIDERS);
    }
    if (body.enforced != null
        && body.enforced
        && body.provider == null
        && org.getSsoProvider() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Cannot enforce SSO without configuring a provider first.");
    }

    // M-016 (Wave 3) — lockout-prevention guard. Enforcing SSO before
    // the calling admin themselves has logged in via SSO is the most
    // common foot-gun: it traps every member (including the admin)
    // out of the org until they trigger a database fix. Block the
    // transition with a 409 Conflict + an explainer instead of a
    // sterile 400, so the UI can surface a "verify your own SSO
    // login first" hint.
    boolean transitionsToEnforced = Boolean.TRUE.equals(body.enforced) && !org.isSsoEnforced();

    // [J-14] (audit Sprint 3, 2026-05-01) — refuse enforcement on
    // SAML providers until the actual handshake ships. Per
    // docs/sso-saml-wiring.md the OKTA_SAML / AZURE_AD_SAML handshake
    // is schema-only (V127 columns + this controller); the
    // assertion-validation + AssertionConsumerService wiring is ~3-5
    // days of unscoped work that hasn't landed. Today only
    // GOOGLE_OIDC is a functional SSO route (it piggybacks on the
    // existing OAuth2 flow). Allowing enforced=TRUE on SAML providers
    // creates a setting the platform cannot honor — the IdP redirect
    // never fires, users hit the regular login form, and either the
    // M-016 guard locks the calling admin out (best case) or the
    // setting silently no-ops (worst case). Reject up-front.
    String effectiveProvider = body.provider != null ? body.provider : org.getSsoProvider();
    if (transitionsToEnforced
        && effectiveProvider != null
        && !"GOOGLE_OIDC".equals(effectiveProvider)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Enforcement is currently only supported for GOOGLE_OIDC. The "
              + "SAML2 handshake for OKTA_SAML / AZURE_AD_SAML is on the "
              + "roadmap (see docs/sso-saml-wiring.md). Configure the "
              + "provider as GOOGLE_OIDC, or contact support to schedule "
              + "SAML enablement.");
    }

    if (transitionsToEnforced
        && !"SAML".equals(principal.getUser().getOauthProvider())
        && !"SSO".equals(principal.getUser().getOauthProvider())
        && !"GOOGLE".equals(principal.getUser().getOauthProvider())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Verify your own SSO login first. Sign out, sign back in via the "
              + "configured IdP, then re-attempt enforcement. This guard "
              + "prevents locking the entire org out of the platform.");
    }

    if (body.provider != null) org.setSsoProvider(body.provider);
    if (body.enforced != null) org.setSsoEnforced(body.enforced);
    if (body.metadataUrl != null) org.setSsoMetadataUrl(body.metadataUrl);
    if (body.domain != null) org.setSsoDomain(body.domain);
    org.setUpdatedAt(Instant.now());
    orgRepo.save(org);

    log.info(
        "[SSO] org={} provider={} enforced={} domain={} updatedBy={}",
        org.getId(),
        org.getSsoProvider(),
        org.isSsoEnforced(),
        org.getSsoDomain(),
        principal.getUser().getId());

    return SsoConfigDto.from(org);
  }

  @DeleteMapping("/config")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
  public ResponseEntity<Void> clear(@AuthenticationPrincipal AppUserDetails principal) {
    Org org = requireOrgFromPrincipal(principal);
    org.setSsoProvider(null);
    org.setSsoEnforced(false);
    org.setSsoMetadataUrl(null);
    org.setSsoDomain(null);
    org.setUpdatedAt(Instant.now());
    orgRepo.save(org);
    log.info("[SSO] cleared for org={} by userId={}", org.getId(), principal.getUser().getId());
    return ResponseEntity.noContent().build();
  }

  private Org requireOrgFromPrincipal(AppUserDetails principal) {
    if (principal == null
        || principal.getUser() == null
        || principal.getUser().getOrgId() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User has no org");
    }
    return orgRepo
        .findById(principal.getUser().getOrgId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
  }

  public record SsoConfigUpdate(
      @Pattern(
              regexp = "GOOGLE_OIDC|OKTA_SAML|AZURE_AD_SAML",
              message = "provider must be one of GOOGLE_OIDC, OKTA_SAML, AZURE_AD_SAML")
          String provider,
      Boolean enforced,
      // SDET 2026-05-16 (BUG-005) — refuse non-URL metadataUrl values so a typo can't lock the org
      // out of SSO after save. Pure validation: @URL accepts http/https/ftp etc; @Pattern narrows
      // to https-only (the IdP metadata endpoints are HTTPS-only in production). Empty-string is
      // allowed because the PUT is a partial update and unset fields shouldn't trip the validator.
      @Size(max = 1024, message = "metadataUrl must not exceed 1024 characters")
          @URL(message = "metadataUrl must be a valid URL")
          @Pattern(regexp = "^$|^https://.+", message = "metadataUrl must use HTTPS")
          String metadataUrl,
      @Size(max = 255, message = "domain must not exceed 255 characters")
          @Pattern(
              regexp = "^$|^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
              message = "domain must be a valid DNS name")
          String domain) {}

  public record SsoConfigDto(
      String provider, boolean enforced, String metadataUrl, String domain, boolean configured) {
    public static SsoConfigDto from(Org org) {
      return new SsoConfigDto(
          org.getSsoProvider(),
          org.isSsoEnforced(),
          org.getSsoMetadataUrl(),
          org.getSsoDomain(),
          org.getSsoProvider() != null);
    }
  }
}
