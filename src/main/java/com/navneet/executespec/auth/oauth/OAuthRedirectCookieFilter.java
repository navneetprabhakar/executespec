package com.navneet.executespec.auth.oauth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures the device-authorization return URL ({@code ?redirect=/cli-login?...}) on the OAuth2
 * authorize request and stashes it in a short-lived, HttpOnly cookie so it survives the provider
 * round-trip. {@link OAuthSuccessHandler} reads + clears the cookie and forwards the user back to
 * {@code /cli-login} after Google/GitHub sign-in.
 *
 * <p>Without this, OAuth login drops the CLI / VS Code device-auth context: the email+password path
 * preserves it via {@code ?redirect=} (read by the SPA login page), but the bare OAuth {@code <a
 * href>} cannot carry a param through the Google/GitHub round-trip. The result was a CLI that hung
 * forever — the loopback callback never fired because the user never reached the Authorize step.
 *
 * <p>Scoped to {@code /cli-login} paths (the only consumer) so it can never be used as an open
 * redirect. {@code SameSite=Lax} so the cookie is sent on the provider's top-level callback
 * navigation; {@code Secure} is added only over HTTPS so local http testing still works.
 */
public class OAuthRedirectCookieFilter extends OncePerRequestFilter {

  /** Name of the short-lived device-auth return cookie. */
  public static final String COOKIE = "es_oauth_redirect";

  private static final String AUTHORIZE_PREFIX = "/api/auth/oauth2/authorize/";
  private static final int MAX_AGE_SECONDS = 300;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getRequestURI().startsWith(AUTHORIZE_PREFIX)) {
      String redirect = request.getParameter("redirect");
      if (isSafe(redirect)) {
        String value = URLEncoder.encode(redirect, StandardCharsets.UTF_8);
        StringBuilder cookie =
            new StringBuilder(COOKIE)
                .append('=')
                .append(value)
                .append("; Path=/; Max-Age=")
                .append(MAX_AGE_SECONDS)
                .append("; HttpOnly; SameSite=Lax");
        if (request.isSecure()) {
          cookie.append("; Secure");
        }
        response.addHeader("Set-Cookie", cookie.toString());
      }
    }
    chain.doFilter(request, response);
  }

  /**
   * Only same-origin device-auth paths are eligible — never an absolute URL or an arbitrary path.
   * Guards both the set (here) and the read ({@link OAuthSuccessHandler}) against open-redirect
   * abuse.
   */
  public static boolean isSafe(String redirect) {
    return redirect != null && redirect.startsWith("/cli-login");
  }
}
