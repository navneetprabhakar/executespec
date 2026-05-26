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

  private final JwtUtil jwtUtil;
  private final UserDetailsServiceImpl userDetailsService;
  private final PersonalAccessTokenService personalAccessTokenService;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String token = extractBearerToken(request);
      if (token != null) {
        if (token.startsWith(PersonalAccessTokenService.TOKEN_PREFIX)) {
          authenticateWithPat(token, request);
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
  private void authenticateWithPat(String token, HttpServletRequest request) {
    String userId = personalAccessTokenService.resolveUserId(token);
    if (userId != null) {
      setAuthentication(userDetailsService.loadUserById(userId), request);
    }
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

    if (jwtUtil.isTokenValid(token, userInvalidatedAt)) {
      setAuthentication(userDetails, request);
    }
  }

  private void setAuthentication(UserDetails userDetails, HttpServletRequest request) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private String extractBearerToken(HttpServletRequest request) {
    // 1. Standard Authorization header
    String header = request.getHeader("Authorization");
    if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
      return header.substring(7);
    }
    // 2. Query-param fallback for SSE (EventSource can't set headers)
    String queryToken = request.getParameter("token");
    if (StringUtils.hasText(queryToken)) {
      return queryToken;
    }
    return null;
  }
}
