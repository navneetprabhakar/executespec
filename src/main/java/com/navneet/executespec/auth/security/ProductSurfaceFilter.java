package com.navneet.executespec.auth.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.navneet.executespec.auth.oauth.OAuthSurfaceResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.extern.slf4j.Slf4j;

/**
 * Phase 1.0 #2 — derive {@code currentProduct} from each incoming request and stash it as a
 * request-scoped attribute that downstream code (controllers, repositories, the future
 * ProductSurfaceFilter wired into queries) can read without re-implementing the resolution policy.
 *
 * <p>Resolution ladder (first match wins):
 *
 * <ol>
 *   <li>{@code X-Product} header (server-to-server / webhooks / tests)
 *   <li>{@code Origin} or {@code Referer} matched against the configured per-surface origins
 *       ({@code app.product-routing.app-origin} → {@code vibe}, {@code build-origin} → {@code
 *       executespec})
 *   <li>{@code executespec} default (preserves today's single-surface behavior so legacy clients
 *       keep working)
 * </ol>
 *
 * <p>Pre-Phase-2 (today): the attribute is set on every request but no production query reads it
 * yet. Phase 2 ({@code feature/v2.7-foundation}'s follow-up) wires it into repository methods via
 * the {@link com.navneet.executespec.auth.service.TierProductSurfaceMapper} gating policy. Setting
 * it now means Phase 2's wiring is a single call-site change per repo, not a parallel filter
 * rollout.
 *
 * <p>Reads {@link OAuthSurfaceResolver} session attribute as a 4th-rung fallback so authenticated
 * users on a session-bound surface ({@code /api/auth/oauth2/init?surface=vibe} → session) keep
 * their surface across request paths that don't carry an Origin header (server-side fetches).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@Slf4j
public class ProductSurfaceFilter extends OncePerRequestFilter {

  /** Request attribute key — read via {@code request.getAttribute(ATTR_CURRENT_PRODUCT)}. */
  public static final String ATTR_CURRENT_PRODUCT = "executespec.currentProduct";

  public static final String SURFACE_VIBE = "vibe";
  public static final String SURFACE_EXECUTESPEC = "executespec";
  public static final String DEFAULT_SURFACE = SURFACE_EXECUTESPEC;

  private static final String HEADER_PRODUCT = "X-Product";

  private final Map<String, String> originToSurface;

  public ProductSurfaceFilter(
      @Value("${app.product-routing.app-origin:https://app.executespec.ai}") String appOrigin,
      @Value("${app.product-routing.build-origin:https://build.executespec.ai}")
          String buildOrigin) {
    this.originToSurface = new LinkedHashMap<>();
    this.originToSurface.put(appOrigin, SURFACE_VIBE);
    this.originToSurface.put(buildOrigin, SURFACE_EXECUTESPEC);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String surface = resolveSurface(request);
    request.setAttribute(ATTR_CURRENT_PRODUCT, surface);
    chain.doFilter(request, response);
  }

  String resolveSurface(HttpServletRequest request) {
    // 1. X-Product header (server-to-server, tests, internal calls)
    String header = request.getHeader(HEADER_PRODUCT);
    if (isKnownSurface(header)) return header.toLowerCase();

    // 2. Origin / Referer subdomain match
    String origin = request.getHeader("Origin");
    if (origin == null) origin = request.getHeader("Referer");
    if (origin != null) {
      for (Map.Entry<String, String> e : originToSurface.entrySet()) {
        if (origin.startsWith(e.getKey())) return e.getValue();
      }
    }

    // 3. Session attribute stashed by OAuthSurfaceResolver
    if (request.getSession(false) != null) {
      Object stashed = request.getSession(false).getAttribute(OAuthSurfaceResolver.SESSION_ATTR);
      if (stashed != null && isKnownSurface(stashed.toString())) {
        return stashed.toString().toLowerCase();
      }
    }

    // 4. Default — preserves today's behavior
    return DEFAULT_SURFACE;
  }

  private static boolean isKnownSurface(String s) {
    if (s == null) return false;
    String lower = s.toLowerCase();
    return SURFACE_VIBE.equals(lower) || SURFACE_EXECUTESPEC.equals(lower);
  }
}
