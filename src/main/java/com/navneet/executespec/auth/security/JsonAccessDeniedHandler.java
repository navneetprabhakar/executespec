package com.navneet.executespec.auth.security;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 403 JSON handler for filter-level authorization denials (URL-rule {@code hasRole} matchers in
 * {@link SecurityConfig}).
 *
 * <p>Before: an authenticated caller hitting a role-guarded route (e.g. {@code /api/admin/**}
 * without SUPER_ADMIN) fell through to Spring's default {@code AccessDeniedHandlerImpl} → {@code
 * response.sendError(403)} → the Boot default error body, whose field shape differs from every
 * other ExecuteSpec error envelope. Buyers probing the API saw an inconsistent contract, and only
 * method-level denials (which reach {@code ValidationAdvice.onAccessDenied}) were uniform.
 *
 * <p>After: backend paths get the SAME stable JSON shape as the 401 entry point ({@link
 * JsonAuthenticationEntryPoint}) and the 400/4xx advices — {@code timestamp, status, error,
 * message, path} — with the established {@code error="forbidden"} slug ({@code
 * ValidationAdvice.slugForStatus(403)}). No stack traces, exception class names, or internal
 * detail. Non-backend (SPA) paths keep the default container 403 behaviour.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
    if (isBackendPath(uri)) {
      writeJson(response, uri);
    } else {
      // Preserve the pre-existing container error page for non-API browser routes.
      response.sendError(HttpStatus.FORBIDDEN.value());
    }
  }

  /** Same backend-path boundary as {@link JsonAuthenticationEntryPoint}. */
  private static boolean isBackendPath(String uri) {
    return uri.startsWith("/api/")
        || uri.equals("/api")
        || uri.startsWith("/actuator/")
        || uri.equals("/actuator")
        || uri.startsWith("/webhooks/")
        || uri.startsWith("/oauth2/")
        || uri.startsWith("/login/oauth2/");
  }

  private void writeJson(HttpServletResponse response, String path) throws IOException {
    writeForbiddenBody(objectMapper, response, path, "Access denied");
  }

  /**
   * Shared writer for every filter-level 403 producer. {@code JwtAuthenticationFilter}'s
   * PAT-capability rejection reuses this so a scoped-PAT caller sees the same {@code timestamp,
   * status, error, message, path} envelope as the URL-rule denial path instead of the Boot default
   * body (live-verification residual, 2026-08-05).
   */
  public static void writeForbiddenBody(
      ObjectMapper objectMapper, HttpServletResponse response, String path, String message)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", HttpStatus.FORBIDDEN.value());
    body.put("error", "forbidden");
    body.put("message", message);
    body.put("path", path);
    objectMapper.writeValue(response.getWriter(), body);
  }
}
