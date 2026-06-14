package com.navneet.executespec.auth.oauth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * Stores OAuth authorization requests in short-lived signed cookies keyed by {@code state}.
 *
 * <p>The default HttpSession repository is fragile for the hosted app: a missing, stale, duplicate,
 * or overwritten session cookie turns the provider callback into {@code
 * authorization_request_not_found}. Keeping the request next to the callback state makes the OAuth
 * round-trip resilient without reintroducing server-side session state for normal JWT traffic.
 */
@Component
@Slf4j
public class CookieOAuth2AuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  private static final String COOKIE_PREFIX = "es_oauth2_req_";
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final String CALLBACK_PATH = "/api/auth/oauth2/callback";

  private final byte[] signingKey;

  public CookieOAuth2AuthorizationRequestRepository(@Value("${app.jwt.secret}") String jwtSecret) {
    this.signingKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    String state = request.getParameter("state");
    if (state == null || state.isBlank()) {
      return null;
    }

    Cookie cookie = findCookie(request, cookieName(state));
    if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
      return null;
    }

    String[] parts = cookie.getValue().split("\\.", 2);
    if (parts.length != 2 || !matchesSignature(parts[0], parts[1])) {
      log.warn("Ignoring OAuth authorization request cookie with invalid signature");
      return null;
    }

    try {
      byte[] serialized = Base64.getUrlDecoder().decode(parts[0]);
      Object value = SerializationUtils.deserialize(serialized);
      if (!(value instanceof OAuth2AuthorizationRequest authorizationRequest)) {
        return null;
      }
      return state.equals(authorizationRequest.getState()) ? authorizationRequest : null;
    } catch (RuntimeException e) {
      log.warn("Ignoring unreadable OAuth authorization request cookie", e);
      return null;
    }
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorizationRequest,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (authorizationRequest == null) {
      clearCookie(request.getParameter("state"), request, response);
      return;
    }

    byte[] serialized = SerializationUtils.serialize(authorizationRequest);
    if (serialized == null) {
      return;
    }

    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(serialized);
    String value = payload + "." + signature(payload);
    addCookie(cookieName(authorizationRequest.getState()), value, TTL, request, response);
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
    clearCookie(request.getParameter("state"), request, response);
    return authorizationRequest;
  }

  private static Cookie findCookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    return Arrays.stream(cookies)
        .filter(cookie -> name.equals(cookie.getName()))
        .findFirst()
        .orElse(null);
  }

  private static String cookieName(String state) {
    return COOKIE_PREFIX + sha256Hex(state).substring(0, 32);
  }

  private static String sha256Hex(String value) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private void clearCookie(String state, HttpServletRequest request, HttpServletResponse response) {
    if (state == null || state.isBlank()) {
      return;
    }
    addCookie(cookieName(state), "", Duration.ZERO, request, response);
  }

  private static void addCookie(
      String name,
      String value,
      Duration maxAge,
      HttpServletRequest request,
      HttpServletResponse response) {
    ResponseCookie cookie =
        ResponseCookie.from(name, value)
            .path(CALLBACK_PATH)
            .maxAge(maxAge)
            .httpOnly(true)
            .secure(request.isSecure())
            .sameSite("Lax")
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private String signature(String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Unable to sign OAuth authorization request cookie", e);
    }
  }

  private boolean matchesSignature(String payload, String signature) {
    return java.security.MessageDigest.isEqual(
        signature(payload).getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8));
  }
}
