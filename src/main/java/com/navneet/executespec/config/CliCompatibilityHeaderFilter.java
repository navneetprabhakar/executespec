package com.navneet.executespec.config;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Advertises the oldest CLI/VS Code protocol version supported by this server. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class CliCompatibilityHeaderFilter extends OncePerRequestFilter {

  public static final String MINIMUM_VERSION_HEADER = "X-ES-Min-CLI-Version";

  private static final Pattern CANONICAL_SEMVER =
      Pattern.compile("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)");

  private final String minimumCliVersion;

  public CliCompatibilityHeaderFilter(
      @Value("${executespec.client-compatibility.minimum-cli-version:1.0.0}")
          String minimumCliVersion) {
    String canonical = minimumCliVersion == null ? "" : minimumCliVersion.trim();
    if (!CANONICAL_SEMVER.matcher(canonical).matches()) {
      throw new IllegalArgumentException(
          "executespec.client-compatibility.minimum-cli-version must be canonical"
              + " major.minor.patch");
    }
    this.minimumCliVersion = canonical;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return uri == null || !(uri.equals("/api") || uri.startsWith("/api/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // Set this before dispatch so authentication failures, ordinary errors, and streaming responses
    // all expose the same server-owned compatibility floor.
    response.setHeader(MINIMUM_VERSION_HEADER, minimumCliVersion);
    filterChain.doFilter(request, response);
  }
}
