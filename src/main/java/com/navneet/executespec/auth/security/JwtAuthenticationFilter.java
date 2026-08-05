package com.navneet.executespec.auth.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.navneet.executespec.auth.service.PersonalAccessTokenService;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  public static final String AUTH_SOURCE_ATTRIBUTE = "executespec.auth.source";
  public static final String AUTH_SOURCE_JWT = "JWT";
  public static final String AUTH_SOURCE_PAT = "PAT";
  public static final String PAT_TOKEN_ID_ATTRIBUTE = "executespec.auth.pat-token-id";
  public static final String PAT_SCOPES_ATTRIBUTE = "executespec.auth.pat-scopes";

  private final JwtUtil jwtUtil;
  private final UserDetailsServiceImpl userDetailsService;
  private final PersonalAccessTokenService personalAccessTokenService;
  private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String token = extractBearerToken(request);
      if (token != null) {
        if (token.startsWith(PersonalAccessTokenService.TOKEN_PREFIX)) {
          if (!authenticateWithPat(token, request, response)) return;
        } else {
          authenticateWithJwt(token, request);
        }
      }
    } catch (Exception e) {
      log.debug("Auth filter error: {}", e.getMessage());
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Personal Access Token path (CLI / VS Code / CI). Resolves the token to its owning user and
   * binds the SAME {@link AppUserDetails} a JWT would — identical global + org roles, identical
   * tenant — so every downstream access guard behaves the same regardless of credential type.
   */
  private boolean authenticateWithPat(
      String token, HttpServletRequest request, HttpServletResponse response) throws IOException {
    PersonalAccessTokenService.ResolvedToken resolved =
        personalAccessTokenService.resolveToken(token);
    if (resolved != null) {
      if (!PatScopePolicy.allows(resolved.scopes(), request.getMethod(), request.getRequestURI())) {
        // Same uniform 403 envelope as JsonAccessDeniedHandler — sendError() here leaked the Boot
        // default body shape to scoped-PAT API consumers (2026-08-05 live-verification residual).
        JsonAccessDeniedHandler.writeForbiddenBody(
            objectMapper,
            response,
            request.getRequestURI(),
            "PAT scope does not permit this action");
        return false;
      }
      UserDetails userDetails = userDetailsService.loadUserById(resolved.userId());
      if (isActive(userDetails)) {
        setAuthentication(userDetails, request, AUTH_SOURCE_PAT);
        request.setAttribute(PAT_TOKEN_ID_ATTRIBUTE, resolved.tokenId());
        request.setAttribute(PAT_SCOPES_ATTRIBUTE, resolved.scopes());
      }
    }
    return true;
  }

  private void authenticateWithJwt(String token, HttpServletRequest request) {
    // Extract userId first (validates signature + expiry — throws on invalid token)
    String userId = jwtUtil.extractUserId(token);
    UserDetails userDetails = userDetailsService.loadUserById(userId);

    // Per-user invalidation check: pass tokenInvalidatedAt from the loaded user
    java.time.Instant userInvalidatedAt = null;
    if (userDetails instanceof AppUserDetails appUser) {
      userInvalidatedAt = appUser.getUser().getTokenInvalidatedAt();
    }

    // RR-128: only a token minted for API access may authenticate here. A refresh cookie value or a
    // scoped SSE query token replayed as a bearer must not yield a full-authority session.
    if (jwtUtil.isAccessTokenValid(token, userInvalidatedAt)) {
      if (isActive(userDetails)) {
        setAuthentication(userDetails, request, AUTH_SOURCE_JWT);
      }
    }
  }

  private boolean isActive(UserDetails userDetails) {
    return userDetails.isEnabled() && userDetails.isAccountNonLocked();
  }

  private void setAuthentication(
      UserDetails userDetails, HttpServletRequest request, String source) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    request.setAttribute(AUTH_SOURCE_ATTRIBUTE, source);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String extractBearerToken(HttpServletRequest request) {
    // Standard Authorization header only. Query-string credentials are intentionally never
    // promoted to a general Spring Security authentication: the SSE controllers validate their
    // short-lived query token against the exact run/execution resource before subscribing.
    String header = request.getHeader("Authorization");
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
      return header.substring(7);
    }
    return null;
  }
}
