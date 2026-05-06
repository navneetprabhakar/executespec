package com.navneet.executespec.auth.oauth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.auth.service.AuthService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles successful OAuth2 login — extracts profile, finds or creates user, generates JWT tokens,
 * and redirects to the frontend callback page.
 *
 * <p>{@code @Lazy} on AuthService breaks the circular dependency: SecurityConfig →
 * OAuthSuccessHandler → AuthService → SecurityConfig.
 */
@Component
@ConditionalOnExpression(
    "!'none'.equals('${spring.security.oauth2.client.registration.google.client-id:none}') or"
        + " !'none'.equals('${spring.security.oauth2.client.registration.github.client-id:none}')")
@Slf4j
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {

  private final OAuthUserMapper oAuthUserMapper;
  private final AuthService authService;
  private final JwtUtil jwtUtil;
  private final OAuth2AuthorizedClientService authorizedClientService;

  public OAuthSuccessHandler(
      OAuthUserMapper oAuthUserMapper,
      @Lazy AuthService authService,
      JwtUtil jwtUtil,
      OAuth2AuthorizedClientService authorizedClientService) {
    this.oAuthUserMapper = oAuthUserMapper;
    this.authService = authService;
    this.jwtUtil = jwtUtil;
    this.authorizedClientService = authorizedClientService;
  }

  @Value("${app.mail.base-url:http://localhost:5173}")
  private String dashboardUrl;

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    try {
      OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
      OAuth2User oAuth2User = oauthToken.getPrincipal();
      String registrationId = oauthToken.getAuthorizedClientRegistrationId();

      // Retrieve the authorized client to access the access token (needed for GitHub email
      // fallback)
      OAuth2AuthorizedClient authorizedClient =
          authorizedClientService.loadAuthorizedClient(registrationId, oauthToken.getName());

      // Map provider-specific attributes to a unified profile
      OAuthProfile profile =
          oAuthUserMapper.toProfile(registrationId, oAuth2User, authorizedClient);

      // Single-surface retirement (2026-05-06) — the per-surface
      // OAuth return URL was groundwork for the consumer/B2B split
      // that's no longer on the roadmap. Every login now lands on
      // the single dashboardUrl, like it did before V143.
      String baseRedirectUrl = dashboardUrl + "/auth/callback";
      String baseLoginErrorUrl = dashboardUrl + "/login";

      if (profile.email() == null || profile.email().isBlank()) {
        log.warn("OAuth login failed: no email returned from provider {}", registrationId);
        response.sendRedirect(
            baseLoginErrorUrl
                + "?error="
                + URLEncoder.encode("oauth_no_email", StandardCharsets.UTF_8));
        return;
      }

      // Find existing user or create new one
      AppUser user =
          authService.findOrCreateFromOAuth(
              profile.provider(),
              profile.oauthId(),
              profile.email(),
              profile.name(),
              profile.avatarUrl());

      // Generate JWT tokens
      String accessToken = jwtUtil.generateAccessToken(user, null);
      String refreshToken = jwtUtil.generateRefreshToken(user);

      log.info(
          "OAuth login success: provider={}, email={}, userId={}",
          profile.provider(),
          profile.email(),
          user.getId());

      // Redirect to frontend callback with tokens
      String redirectUrl =
          baseRedirectUrl
              + "?token="
              + URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
              + "&refresh="
              + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
      response.sendRedirect(redirectUrl);

    } catch (OAuthEmailConflictException e) {
      log.warn("OAuth email conflict: {}", e.getMessage());
      response.sendRedirect(
          dashboardUrl
              + "/login?error="
              + URLEncoder.encode("oauth_email_conflict", StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.error("OAuth login error", e);
      response.sendRedirect(
          dashboardUrl
              + "/login?error="
              + URLEncoder.encode("oauth_failed", StandardCharsets.UTF_8));
    }
  }
}
