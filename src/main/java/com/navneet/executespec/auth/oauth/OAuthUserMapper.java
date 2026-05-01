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
    return new OAuthProfile("google", oauthId, email, name, picture);
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

    // GitHub may not include email in profile — fetch from /user/emails API
    if ((email == null || email.isBlank()) && authorizedClient != null) {
      email = fetchGitHubPrimaryEmail(authorizedClient);
    }

    return new OAuthProfile("github", oauthId, email, name, avatarUrl);
  }

  /**
   * Calls GET https://api.github.com/user/emails with the user's access token to retrieve their
   * primary verified email address.
   */
  private String fetchGitHubPrimaryEmail(OAuth2AuthorizedClient authorizedClient) {
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

      if (emails == null) return null;

      // Find primary verified email
      return emails.stream()
          .filter(
              e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
          .map(e -> (String) e.get("email"))
          .findFirst()
          // Fallback: any verified email
          .or(
              () ->
                  emails.stream()
                      .filter(e -> Boolean.TRUE.equals(e.get("verified")))
                      .map(e -> (String) e.get("email"))
                      .findFirst())
          .orElse(null);
    } catch (Exception e) {
      log.warn("Failed to fetch GitHub user emails: {}", e.getMessage());
      return null;
    }
  }
}
