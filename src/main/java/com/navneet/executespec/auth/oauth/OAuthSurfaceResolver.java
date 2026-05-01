package com.navneet.executespec.auth.oauth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the originating frontend surface for an OAuth flow so the success handler knows which
 * subdomain to redirect back to.
 *
 * <p><b>Phase 1.0 skeleton (Plan 1 #5)</b> — wires the model + capture; the frontend has not yet
 * been split into separate subdomains, so in the current dev tip every flow falls through to the
 * default {@code dashboardUrl} branch in {@link OAuthSuccessHandler}. Phase 2 ships the per-surface
 * frontend deploys, after which this resolver becomes the authoritative redirect target.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Frontend on {@code app.executespec.ai} or {@code build.executespec.ai} hits {@code
 *       /api/auth/oauth2/init?provider=google&surface=vibe}.
 *   <li>{@link #captureFromRequest} stashes the surface in the session.
 *   <li>Spring Security bounces to the provider; provider bounces back to the canonical backend
 *       callback.
 *   <li>{@link OAuthSuccessHandler} calls {@link #resolveReturnUrl} to pick the per-surface
 *       frontend URL for the post-login redirect.
 * </ol>
 *
 * <p>Fallback ladder: explicit {@code surface} query param → Origin/Referer subdomain match →
 * {@code executespec} (B2B) default.
 *
 * <p>The "dev" / staging environment isn't a separate surface — it's a deployment of the {@code
 * executespec} surface. The per-environment return URL comes from Spring config (env vars override
 * the @Value defaults below) so this resolver stays surface-only.
 */
@Component
@Slf4j
public class OAuthSurfaceResolver {

  public static final String SESSION_ATTR = "oauth.surface";
  public static final String SURFACE_VIBE = "vibe";
  public static final String SURFACE_EXECUTESPEC = "executespec";
  public static final Set<String> KNOWN_SURFACES = Set.of(SURFACE_VIBE, SURFACE_EXECUTESPEC);

  private final Map<String, String> originToSurface;
  private final Map<String, String> surfaceToReturnUrl;

  public OAuthSurfaceResolver(
      @Value("${app.product-routing.app-origin:https://app.executespec.ai}") String appOrigin,
      @Value("${app.product-routing.build-origin:https://build.executespec.ai}") String buildOrigin,
      @Value("${app.product-routing.app-return-url:https://app.executespec.ai/auth/callback}")
          String appReturn,
      @Value("${app.product-routing.build-return-url:https://build.executespec.ai/auth/callback}")
          String buildReturn) {
    // LinkedHashMap to keep deterministic iteration order during prefix matching.
    this.originToSurface = new LinkedHashMap<>();
    this.originToSurface.put(appOrigin, SURFACE_VIBE);
    this.originToSurface.put(buildOrigin, SURFACE_EXECUTESPEC);

    this.surfaceToReturnUrl =
        Map.of(
            SURFACE_VIBE, appReturn,
            SURFACE_EXECUTESPEC, buildReturn);
  }

  /**
   * Captures the originating surface from request context and stashes it in the HTTP session for
   * {@link #resolveReturnUrl} to read after the OAuth bounce returns.
   *
   * <p>Only stashes when a positive match is found (explicit {@code surface} query param OR
   * Origin/Referer matches a configured surface origin). On no-match — e.g. local-dev frontend on
   * {@code http://localhost:5173} that doesn't match either configured surface origin — returns
   * {@code null} and clears any stale stash so the post-OAuth handler falls back to {@code
   * dashboardUrl}. This is what keeps Google login on localhost from landing on production {@code
   * build.executespec.ai}.
   *
   * @return the captured surface, or {@code null} when neither the {@code surface} param nor
   *     Origin/Referer identify a known surface
   */
  public String captureFromRequest(HttpServletRequest request) {
    String surfaceParam = request.getParameter("surface");
    if (surfaceParam != null && KNOWN_SURFACES.contains(surfaceParam)) {
      request.getSession().setAttribute(SESSION_ATTR, surfaceParam);
      return surfaceParam;
    }
    String origin = request.getHeader("Origin");
    if (origin == null) origin = request.getHeader("Referer");
    if (origin != null) {
      for (Map.Entry<String, String> e : originToSurface.entrySet()) {
        if (origin.startsWith(e.getKey())) {
          request.getSession().setAttribute(SESSION_ATTR, e.getValue());
          return e.getValue();
        }
      }
    }
    HttpSession existing = request.getSession(false);
    if (existing != null) existing.removeAttribute(SESSION_ATTR);
    return null;
  }

  /**
   * Returns the per-surface frontend return URL for the post-login redirect, or {@code null} if no
   * surface was stashed in the session — the caller should fall back to its own default ({@code
   * dashboardUrl} today).
   */
  public String resolveReturnUrl(HttpServletRequest request) {
    if (request.getSession(false) == null) return null;
    Object stashed = request.getSession(false).getAttribute(SESSION_ATTR);
    if (stashed == null) return null;
    String surface = stashed.toString();
    String url = surfaceToReturnUrl.get(surface);
    if (url == null) {
      log.warn("Unknown OAuth surface '{}' in session — caller will fall back", surface);
    }
    return url;
  }
}
