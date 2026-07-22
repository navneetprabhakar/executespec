package com.navneet.executespec.auth.oauth;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/** Normalizes OAuth2User attributes from Google and GitHub into a unified {@link OAuthProfile}. */
@Component
@ConditionalOnExpression(
    "!'none'.equals('${spring.security.oauth2.client.registration.google.client-id:none}') or"
        + " !'none'.equals('${spring.security.oauth2.client.registration.github.client-id:none}')")
@Slf4j
public class OAuthUserMapper {

  private static final String GITHUB_EMAILS_URL = "https://api.github.com/user/emails";

  /**
   * Map an OAuth2User into a provider-agnostic profile.
   *
   * @param registrationId "google" or "github"
   * @param oAuth2User the authenticated OAuth2 user
   * @param authorizedClient the authorized client (for access token, used by GitHub email fallback)
   * @return normalized profile
   */
  public OAuthProfile toProfile(
      String registrationId, OAuth2User oAuth2User, OAuth2AuthorizedClient authorizedClient) {
    return switch (registrationId.toLowerCase()) {
      case "google" -> mapGoogle(oAuth2User);
      case "github" -> mapGitHub(oAuth2User, authorizedClient);
      default ->
          throw new IllegalArgumentException("Unsupported OAuth provider: " + registrationId);
    };
  }

  private OAuthProfile mapGoogle(OAuth2User user) {
    String oauthId = user.getAttribute("sub");
    String email = user.getAttribute("email");
    String name = user.getAttribute("name");
    String picture = user.getAttribute("picture");
    // RR-131 — carry Google's explicit email_verified claim. A Google account can carry an
    // unverified email (a hosted-domain admin can pre-provision one); linking on an unverified
    // claim is the account-takeover vector this closes.
    boolean emailVerified = Boolean.TRUE.equals(user.getAttribute("email_verified"));
    return new OAuthProfile("google", oauthId, email, name, picture, emailVerified);
  }

  private OAuthProfile mapGitHub(OAuth2User user, OAuth2AuthorizedClient authorizedClient) {
    // GitHub uses integer ID
    Object idAttr = user.getAttribute("id");
    String oauthId = idAttr != null ? idAttr.toString() : null;
    String email = user.getAttribute("email");
    String name = user.getAttribute("name");
    String login = user.getAttribute("login");
    String avatarUrl = user.getAttribute("avatar_url");

    // Use login as display name fallback
    if (name == null || name.isBlank()) {
      name = login;
    }

    // RR-131 — the profile `email` attribute is user-controlled and NOT a verification signal, so
    // it cannot be trusted to link an existing account. Establish verification only from the
    // authoritative /user/emails list: cross-check a supplied profile email against it, or fall
    // back to a fetched primary-verified address. No authorized client (no way to check) means
    // unverified.
    boolean emailVerified = false;
    java.util.Set<String> verifiedEmails =
        authorizedClient != null ? fetchGitHubVerifiedEmails(authorizedClient) : java.util.Set.of();
    if (email != null && !email.isBlank()) {
      emailVerified = verifiedEmails.contains(email.toLowerCase());
    } else {
      email = verifiedEmails.stream().findFirst().orElse(null);
      emailVerified = email != null;
    }

    return new OAuthProfile("github", oauthId, email, name, avatarUrl, emailVerified);
  }

  /**
   * Calls GET https://api.github.com/user/emails with the user's access token and returns the set
   * of the account's VERIFIED emails, lower-cased, primary-verified first (RR-131). Only these are
   * trusted for account linking. Any error yields an empty set (fail closed — unverified).
   */
  private java.util.Set<String> fetchGitHubVerifiedEmails(OAuth2AuthorizedClient authorizedClient) {
    try {
      String accessToken = authorizedClient.getAccessToken().getTokenValue();

      List<Map<String, Object>> emails =
          RestClient.create()
              .get()
              .uri(GITHUB_EMAILS_URL)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
              .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});

      if (emails == null) return java.util.Set.of();

      // Insertion-ordered: primary-verified addresses first, then any other verified address.
      java.util.LinkedHashSet<String> verified = new java.util.LinkedHashSet<>();
      emails.stream()
          .filter(
              e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
          .map(e -> (String) e.get("email"))
          .filter(java.util.Objects::nonNull)
          .forEach(e -> verified.add(e.toLowerCase()));
      emails.stream()
          .filter(e -> Boolean.TRUE.equals(e.get("verified")))
          .map(e -> (String) e.get("email"))
          .filter(java.util.Objects::nonNull)
          .forEach(e -> verified.add(e.toLowerCase()));
      return verified;
    } catch (Exception e) {
      log.warn("Failed to fetch GitHub user emails: {}", e.getMessage());
      return java.util.Set.of();
    }
  }
}
