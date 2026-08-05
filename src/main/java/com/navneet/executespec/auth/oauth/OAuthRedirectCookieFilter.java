package com.navneet.executespec.auth.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures a same-origin application return path on the OAuth2 authorize request and stashes it in
 * a short-lived, HttpOnly cookie so it survives the provider round-trip. {@link
 * OAuthSuccessHandler} reads + clears the cookie and forwards the user back to that path after
 * Google/GitHub sign-in.
 *
 * <p>Without this, OAuth login drops the CLI / VS Code device-auth context: the email+password path
 * preserves it via {@code ?redirect=} (read by the SPA login page), but the bare OAuth {@code <a
 * href>} cannot carry a param through the Google/GitHub round-trip. The result was a CLI that hung
 * forever — the loopback callback never fired because the user never reached the Authorize step.
 *
 * <p>Only a strict origin-relative path is accepted: no scheme, authority, protocol-relative path,
 * backslash, control character, or encoded path that decodes to one of those forms. Query and hash
 * are retained because protected application deep links use both. {@code SameSite=Lax} sends the
 * cookie on the provider's top-level callback; {@code Secure} is added over HTTPS.
 */
public class OAuthRedirectCookieFilter extends OncePerRequestFilter {

  /** Name of the short-lived device-auth return cookie. */
  public static final String COOKIE = "es_oauth_redirect";

  private static final String AUTHORIZE_PREFIX = "/api/auth/oauth2/authorize/";
  private static final int MAX_AGE_SECONDS = 300;
  private static final int MAX_REDIRECT_LENGTH = 2048;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().startsWith(AUTHORIZE_PREFIX)) {
      String redirect = request.getParameter("redirect");
      if (isSafe(redirect)) {
        response.addHeader(
            "Set-Cookie",
            cookieHeader(
                URLEncoder.encode(redirect, StandardCharsets.UTF_8), MAX_AGE_SECONDS, request));
      } else {
        // An abandoned OAuth attempt may have left a still-valid return cookie. Clear it whenever a
        // new authorize request does not carry a valid target so that stale navigation cannot win.
        response.addHeader("Set-Cookie", cookieHeader("", 0, request));
      }
    }
    chain.doFilter(request, response);
  }

  /**
   * Accepts only a strict same-origin application path. This guard is applied both when the cookie
   * is written and when it is consumed by {@link OAuthSuccessHandler}.
   */
  public static boolean isSafe(String redirect) {
    if (redirect == null
        || redirect.isBlank()
        || redirect.length() > MAX_REDIRECT_LENGTH
        || !redirect.startsWith("/")
        || redirect.startsWith("//")
        || redirect.indexOf('\\') >= 0
        || containsControlCharacter(redirect)) {
      return false;
    }
    try {
      URI uri = new URI(redirect);
      if (uri.isAbsolute() || uri.getRawAuthority() != null) return false;
      String rawPath = uri.getRawPath();
      if (rawPath == null || !rawPath.startsWith("/") || rawPath.startsWith("//")) return false;

      // Reject encoded slashes/backslashes only when decoding changes the path into an ambiguous
      // authority-like form. Encoded values in the query remain valid application state.
      String decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
      return !decodedPath.startsWith("//")
          && decodedPath.indexOf('\\') < 0
          && !containsControlCharacter(decodedPath);
    } catch (IllegalArgumentException | URISyntaxException invalid) {
      return false;
    }
  }

  private static boolean containsControlCharacter(String value) {
    return value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
  }

  private static String cookieHeader(
      String encodedValue, int maxAgeSeconds, HttpServletRequest request) {
    StringBuilder cookie =
        new StringBuilder(COOKIE)
            .append('=')
            .append(encodedValue)
            .append("; Path=/; Max-Age=")
            .append(maxAgeSeconds)
            .append("; HttpOnly; SameSite=Lax");
    if (request.isSecure()) cookie.append("; Secure");
    return cookie.toString();
  }
}
