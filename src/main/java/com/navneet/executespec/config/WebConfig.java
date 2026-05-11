package com.navneet.executespec.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration. CORS is now managed by Spring Security
 * (SecurityConfig#corsConfigurationSource), so this class no longer overrides addCorsMappings.
 *
 * <p>2026-05-11 — adds an immutable-cache header override for hashed SPA assets (the 2026-05-11 UX
 * audit B8). Spring Security's default {@code CacheControlHeadersWriter} stamps every response with
 * {@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate}, which is correct for HTML
 * / API responses but actively wasteful for fingerprinted bundles like {@code
 * /assets/index-CGZPfWkO.js}. The filter installed here runs <em>after</em> the security chain and
 * overwrites the cache header for {@code /assets/**} only so hashed bundles are cached forever (the
 * filename hash already serves as cache-busting); everything else stays no-cache.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  // CORS handled by SecurityConfig to work correctly with the JWT filter chain.

  /**
   * Immutable cache for hashed SPA assets. Runs after Spring Security's default header writers so
   * its {@code setHeader} call overwrites the earlier {@code no-cache, no-store, …} value for
   * {@code /assets/**}. Hash-based filenames produced by Vite already make every change cache-bust
   * automatically, so {@code max-age=31536000, immutable} is safe and saves ~1 MB per repeat
   * visitor.
   */
  @Bean
  public FilterRegistrationBean<OncePerRequestFilter> immutableAssetCacheFilter() {
    OncePerRequestFilter filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws ServletException, IOException {
            chain.doFilter(request, response);
            // After the chain runs (response committed flags + headers
            // set by Spring Security), rewrite Cache-Control for hashed
            // assets only. Avoid touching anything else.
            String uri = request.getRequestURI();
            if (uri == null) return;
            if (uri.startsWith("/assets/")) {
              response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
              // Strip the no-store companion headers Spring Security
              // emitted so intermediaries don't see a contradiction.
              response.setHeader("Pragma", "");
              response.setHeader("Expires", "");
            }
          }
        };
    FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
    reg.setOrder(Ordered.LOWEST_PRECEDENCE);
    reg.addUrlPatterns("/assets/*");
    return reg;
  }
}
