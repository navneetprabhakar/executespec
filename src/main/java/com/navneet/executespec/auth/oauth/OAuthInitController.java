package com.navneet.executespec.auth.oauth;

import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridge endpoint that captures the originating surface (vibe / executespec) before delegating to
 * Spring Security's OAuth2 client for the actual provider bounce.
 *
 * <p>Frontend usage: instead of linking directly to the Spring OAuth client filter, the
 * surface-aware frontends use {@code /api/auth/oauth2/init?provider=google&surface=vibe}. The
 * session carries the surface across the OAuth round-trip so {@link OAuthSuccessHandler} knows
 * which subdomain to redirect back to.
 *
 * <p>Phase 1.0 skeleton — the endpoint is live and the resolver session-stash works today; the
 * success-handler integration ships under the same flag in Phase 2 once both subdomains exist.
 *
 * <p><b>OAuth client path:</b> {@link com.navneet.executespec.auth.security.SecurityConfig} remaps
 * Spring's default authorization base URI from {@code /oauth2/authorization} to {@code
 * /api/auth/oauth2/authorize}. The bridge MUST hand off to the remapped path — sending the user to
 * {@code /oauth2/authorization/{provider}} falls through to the SPA index.html and the OAuth dance
 * never starts.
 */
@RestController
@RequestMapping("/api/auth/oauth2")
@RequiredArgsConstructor
@Slf4j
@ConditionalOnExpression(
    "!'none'.equals('${spring.security.oauth2.client.registration.google.client-id:none}') or"
        + " !'none'.equals('${spring.security.oauth2.client.registration.github.client-id:none}')")
public class OAuthInitController {

  private static final Set<String> ALLOWED_PROVIDERS = Set.of("google", "github");
  private static final String DEFAULT_PROVIDER = "google";

  private final OAuthSurfaceResolver surfaceResolver;

  @GetMapping("/init")
  public RedirectView init(
      @RequestParam(required = false) String provider,
      @RequestParam(required = false) String surface,
      HttpServletRequest request) {
    // surface= param is optional — captureFromRequest also tries Origin/Referer.
    String resolvedSurface = surfaceResolver.captureFromRequest(request);
    String resolvedProvider =
        provider != null && ALLOWED_PROVIDERS.contains(provider) ? provider : DEFAULT_PROVIDER;
    log.info(
        "[OAuthInit] provider={} surface={} (param={})",
        resolvedProvider,
        resolvedSurface,
        surface);
    // Hand off to Spring Security's OAuth2 client filter at the remapped
    // base URI (see SecurityConfig.authorizationEndpoint baseUri override).
    // setContextRelative(true) makes RedirectView issue a path-only Location
    // header so the browser preserves the request scheme (https) — critical
    // when the GCE LB terminates TLS and the JVM sees the request as http.
    RedirectView view = new RedirectView("/api/auth/oauth2/authorize/" + resolvedProvider);
    view.setContextRelative(true);
    return view;
  }
}
