package com.navneet.executespec.auth.controller;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated read of which OAuth providers this deployment can actually serve.
 *
 * <p>Why this exists: {@code SecurityConfig} only registers the OAuth2 login filter chain when a
 * client id is configured, so on any environment without one, {@code
 * /api/auth/oauth2/authorize/{provider}} does not exist. The login page previously rendered
 * "Continue with Google" and "Continue with GitHub" unconditionally, so clicking them navigated the
 * browser out of the SPA onto a raw JSON 404. The frontend now asks this endpoint first and only
 * renders a provider it can genuinely complete.
 *
 * <p>This exposes availability only. Client ids are not returned — the browser never needs one for
 * the server-side authorization-code flow, and client secrets are never exposed anywhere.
 */
@RestController
@RequestMapping("/api/auth/providers")
public class AuthProvidersController {

  /** Sentinel used across profiles to mean "not configured". */
  private static final String UNSET = "none";

  private final boolean googleEnabled;
  private final boolean githubEnabled;

  public AuthProvidersController(
      @Value("${spring.security.oauth2.client.registration.google.client-id:none}")
          String googleClientId,
      @Value("${spring.security.oauth2.client.registration.github.client-id:none}")
          String githubClientId) {
    this.googleEnabled = configured(googleClientId);
    this.githubEnabled = configured(githubClientId);
  }

  private static boolean configured(String clientId) {
    return clientId != null && !clientId.isBlank() && !UNSET.equals(clientId);
  }

  /**
   * @return the OAuth providers this deployment can complete a login with, plus whether
   *     email/password remains available (it always does today, but the flag keeps the contract
   *     honest if that ever becomes deployment-specific).
   */
  @GetMapping
  public AuthProviders get() {
    List<String> oauth = new java.util.ArrayList<>(2);
    if (googleEnabled) {
      oauth.add("google");
    }
    if (githubEnabled) {
      oauth.add("github");
    }
    return new AuthProviders(List.copyOf(oauth), true);
  }

  /** Availability contract consumed by the login and signup surfaces. */
  public record AuthProviders(List<String> oauthProviders, boolean passwordEnabled) {}
}
